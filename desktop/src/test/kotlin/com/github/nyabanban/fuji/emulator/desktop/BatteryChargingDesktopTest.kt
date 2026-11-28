package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.BatteryPort
import com.github.nyabanban.fuji.emulator.api.PowerControlPort
import com.github.nyabanban.fuji.emulator.desktop.console.EmulatorConsole
import com.github.nyabanban.fuji.emulator.desktop.console.RuntimeConsoleBackend
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.BatteryChargingStatusSnapshot
import com.github.nyabanban.fuji.emulator.desktop.power.BatteryPowerConfig
import com.github.nyabanban.fuji.emulator.desktop.power.BatteryPowerStore
import com.github.nyabanban.fuji.emulator.desktop.power.BatteryPowerValidation
import com.github.nyabanban.fuji.emulator.desktop.power.parsePowerLaunchArgs
import com.github.nyabanban.fuji.emulator.desktop.theme.UserProfileStore
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BatteryChargingDesktopTest {

    @Test
    fun cliPowerFlagsParseAndDeriveRealisticNpW235Voltage() {
        val empty = parsePowerLaunchArgs(emptyArray())
        assertFalse(empty.hasOverrides())

        val charging78 = parsePowerLaunchArgs(arrayOf("--charging", "--battery-percent=78"))
        assertTrue(charging78.hasOverrides())
        assertEquals(true, charging78.charging)
        assertEquals(true, charging78.externalPowerConnected)
        assertEquals(78.0, charging78.chargePercent)

        val appliedCharging = charging78.applyTo(BatteryPowerConfig.DEFAULT)
        assertTrue(appliedCharging.charging)
        assertTrue(appliedCharging.externalPowerConnected)
        assertEquals(78.0, appliedCharging.chargePercent)
        assertTrue(appliedCharging.voltageMillivolts in 7900..8200, "Expected realistic 2S charging voltage, got ${appliedCharging.voltageMillivolts}")

        val discharging45 = parsePowerLaunchArgs(arrayOf("--no-charging", "--battery-percent", "45"))
        val appliedDischarging = discharging45.applyTo(appliedCharging)
        assertFalse(appliedDischarging.charging)
        assertFalse(appliedDischarging.externalPowerConnected)
        assertEquals(45.0, appliedDischarging.chargePercent)
        assertTrue(appliedDischarging.voltageMillivolts < appliedCharging.voltageMillivolts)
        assertTrue(appliedDischarging.voltageMillivolts in 7100..7400)

        // Bounds check on NP-W235 curve (6400..8400 mV)
        assertEquals(6400, BatteryPowerValidation.deriveNpW235Millivolts(0.0, charging = false))
        assertEquals(8400, BatteryPowerValidation.deriveNpW235Millivolts(100.0, charging = true))

        assertThrows<IllegalArgumentException> { parsePowerLaunchArgs(arrayOf("--battery-percent=105")) }
        assertThrows<IllegalArgumentException> { parsePowerLaunchArgs(arrayOf("--battery-percent=-1")) }
        assertThrows<IllegalArgumentException> { parsePowerLaunchArgs(arrayOf("--battery-percent=abc")) }
        assertThrows<IllegalArgumentException> { parsePowerLaunchArgs(arrayOf("--battery-percent")) }
    }

    @Test
    fun emulatorYamlPowerBlockValidatesCleanly(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val baseConfig = mapOf<String, Any>(
            "schema" to 1,
            "components" to "compoments",
            "httpPort" to 0,
            "executionMode" to RelaxedPeripheralFallback.MODE,
            "firmwareMetadata" to repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
            "firmwareFile" to tempRoot.resolve("unused.dat").toString(),
            "instructionBudget" to 1000,
            "enabledDevices" to listOf("soc-timer"),
        )

        validateLaunchConfiguration(
            baseConfig + (
                "power" to mapOf(
                    "present" to true,
                    "chargePercent" to 65.0,
                    "charging" to true,
                    "externalPowerConnected" to true,
                )
            ),
        )

        assertThrows<IllegalArgumentException> {
            validateLaunchConfiguration(baseConfig + ("power" to mapOf("bogus" to true)))
        }
        assertThrows<IllegalArgumentException> {
            validateLaunchConfiguration(baseConfig + ("power" to mapOf("chargePercent" to 180.0)))
        }
    }

    @Test
    fun batteryAndChargingPersistAcrossRestartsAndConsoleCommandsSyncLive(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val home = IntegrationTest().fixture()
        try {
            val config = mapOf<String, Any>(
                "schema" to 1,
                "components" to "compoments",
                "httpPort" to 0,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "firmwareMetadata" to repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
                "firmwareFile" to tempRoot.resolve("unused.dat").toString(),
                "nativeLibrary" to tempRoot.resolve("unused.so").toString(),
                "instructionBudget" to 1000,
                "enabledDevices" to listOf("soc-timer", "battery"),
                "telemetry" to mapOf("enabled" to false),
            )
            val storePath = BatteryPowerStore.defaultPath(home, config)

            // Boot #1 with CLI --charging --battery-percent=78
            val cliOverrides = parsePowerLaunchArgs(arrayOf("--charging", "--battery-percent=78"))
            EmulatorRuntime(home, config, telemetryEnv = emptyMap(), powerCliOverrides = cliOverrides).use { rt1 ->
                val st1 = rt1.powerRuntime.statusMap()
                assertEquals(true, st1["charging"])
                assertEquals(true, st1["externalPowerConnected"])
                assertEquals(78.0, (st1["chargePercent"] as Number).toDouble())
                assertTrue(Files.isRegularFile(storePath), "Expected persisted battery-power.yaml at $storePath")

                val batPort = rt1.owner.submit { rt1.host.ports.find("battery", BatteryPort::class.java) }.join()
                assertNotNull(batPort)
                assertEquals(78.0, batPort.chargePercent())
                assertTrue(batPort.charging(), "BatteryPort.charging() must reflect CLI --charging")
                assertTrue(batPort.externalPowerConnected(), "BatteryPort.externalPowerConnected() must reflect CLI --charging")
                assertTrue(batPort.chargeRatePercentPerHour() > 0.0, "Charging battery must report positive CRATE")

                val powerCtrl = batPort as? PowerControlPort
                assertNotNull(powerCtrl, "BatteryPort instance must implement PowerControlPort")
                assertTrue(powerCtrl.powerStatusSnapshot().charging)

                // Verify HTTP /api/battery <-> HostPowerRuntime <-> battery-power.yaml parity
                rt1.serve()
                val apiPort = requireNotNull(rt1.server).address.port
                val http = HttpClient.newHttpClient()
                val postResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$apiPort/api/battery"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"chargePercent":78.0,"charging":true}"""))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, postResp.statusCode(), postResp.body())

                // Exercise Console /battery commands via RuntimeConsoleBackend
                val out = ByteArrayOutputStream()
                val console = EmulatorConsole(
                    backend = RuntimeConsoleBackend(
                        runtime = rt1,
                        paused = AtomicBoolean(false),
                        stopRequested = AtomicBoolean(false),
                        onBootRequested = { "ok" },
                        onStopRequested = {},
                    ),
                    input = ByteArrayInputStream(ByteArray(0)),
                    output = out,
                    interactivePrompt = false,
                    timeSupplier = { LocalTime.of(12, 0, 0) },
                )
                assertTrue(console.executeLine("battery status"))
                assertTrue(console.executeLine("battery percent 62"))
                assertTrue(console.executeLine("battery charge off"))
                assertTrue(console.executeLine("battery charge on"))
                assertTrue(console.executeLine("battery usb off"))
                assertTrue(console.executeLine("battery charge on"))
                console.close()

                val text = out.toString(StandardCharsets.UTF_8)
                assertTrue(text.contains("Battery: present=true | charge=78.0%"), text)
                assertTrue(text.contains("Battery charge -> 62.0%"), text)
                assertTrue(text.contains("Battery charging -> OFF"), text)
                assertTrue(text.contains("Battery charging -> ON (USB-C VBUS connected)"), text)
                assertTrue(text.contains("USB-C external power (VBUS) -> DISCONNECTED"), text)

                // Verify profile.yaml schema keys remain strictly 5 UI/theme keys
                val profilePath = UserProfileStore.defaultPath(home)
                if (Files.isRegularFile(profilePath)) {
                    val profileMap = Formats.read(profilePath)
                    assertEquals(setOf("schema", "language", "theme", "windows", "graphics"), profileMap.keys)
                }
            }

            // Boot #2 without CLI flags -> verify persisted charging=true & chargePercent=62.0 survive restart
            EmulatorRuntime(home, config, telemetryEnv = emptyMap()).use { rt2 ->
                val restored = rt2.powerRuntime.configSnapshot()
                assertTrue(restored.charging)
                assertTrue(restored.externalPowerConnected)
                assertEquals(62.0, restored.chargePercent)
                val batPort2 = rt2.owner.submit { rt2.host.ports.find("battery", BatteryPort::class.java) }.join()
                assertNotNull(batPort2)
                assertEquals(62.0, batPort2.chargePercent())
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun batteryChargingPanelSnapshotsFormatCleanlyAcrossAllSixLocales() {
        val chargingSnap = BatteryChargingStatusSnapshot.fromStatusMap(
            mapOf(
                "present" to true,
                "chargePercent" to 78.0,
                "voltageMillivolts" to 8060,
                "charging" to true,
                "externalPowerConnected" to true,
                "cratePercentPerHour" to 42.5,
            ),
        )
        val dischargingSnap = BatteryChargingStatusSnapshot.fromStatusMap(
            mapOf(
                "present" to true,
                "chargePercent" to 35.0,
                "voltageMillivolts" to 7080,
                "charging" to false,
                "externalPowerConnected" to false,
            ),
        )
        try {
            for (lang in I18n.supportedLanguageCodes()) {
                I18n.setLocale(lang)
                assertTrue(I18n.t("battery.panel.section").isNotBlank())
                val chgSummary = chargingSnap.summaryText()
                assertTrue(chgSummary.contains("78%"), chgSummary)
                assertTrue(chgSummary.contains("8.06 V (8060 mV)"), chgSummary)
                assertTrue(chgSummary.contains("+42.5%/h"), chgSummary)
                val disSummary = dischargingSnap.summaryText()
                assertTrue(disSummary.contains("35%"), disSummary)
                assertTrue(disSummary.contains("7.08 V (7080 mV)"), disSummary)
            }
        } finally {
            I18n.resetToDefault()
        }
    }
}
