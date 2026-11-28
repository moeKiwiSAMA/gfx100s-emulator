package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ControlClient
import com.github.nyabanban.fuji.emulator.api.NpW235BatteryCurve
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatteryControlServerTest {
    private class StubBatteryControlClient(private val presentComponent: Boolean = true) : ControlClient {
        private var rev = 1L
        private var present = true
        private var chargePercent = 100.0
        private var voltageMillivolts = 7400L
        private var charging = false
        private var externalPowerConnected = false
        private var autoChargeDrift = false

        private fun componentMap(): Map<String, Any?> {
            val effCharging = present && charging
            val effExt = externalPowerConnected || charging
            val crate = when {
                !present -> 0.0
                effCharging -> if (chargePercent < 99.5) 36.0 else 6.0
                effExt -> 0.0
                else -> -12.0
            }
            return mapOf(
                "id" to "battery",
                "revision" to rev,
                "config" to mapOf(
                    "present" to present,
                    "chargePercent" to chargePercent,
                    "voltageMillivolts" to voltageMillivolts,
                    "charging" to charging,
                    "externalPowerConnected" to externalPowerConnected,
                    "autoChargeDrift" to autoChargeDrift,
                ),
                "state" to mapOf(
                    "present" to present,
                    "chargePercent" to chargePercent,
                    "voltageMillivolts" to voltageMillivolts,
                    "charging" to effCharging,
                    "externalPowerConnected" to effExt,
                    "usbVbusConnected" to effExt,
                    "cratePercentPerHour" to crate,
                    "autoChargeDrift" to autoChargeDrift,
                ),
            )
        }

        override fun snapshot(): CompletableFuture<Map<String, Any?>> = CompletableFuture.completedFuture(
            mapOf("components" to if (presentComponent) listOf(componentMap()) else emptyList()),
        )

        override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> {
            require(id == "battery" && revision == rev)
            changes["present"]?.let { present = it as Boolean }
            changes["chargePercent"]?.let { chargePercent = (it as Number).toDouble() }
            if ("charging" in changes) {
                charging = changes["charging"] as Boolean
                if ("externalPowerConnected" !in changes) {
                    externalPowerConnected = charging
                }
            }
            if ("externalPowerConnected" in changes) {
                externalPowerConnected = changes["externalPowerConnected"] as Boolean
                if (!externalPowerConnected && "charging" !in changes) {
                    charging = false
                }
            }
            if ("voltageMillivolts" in changes) {
                voltageMillivolts = (changes["voltageMillivolts"] as Number).toLong()
            } else if ("charging" in changes || (charging && "chargePercent" in changes)) {
                voltageMillivolts = NpW235BatteryCurve.deriveMillivolts(chargePercent, present && charging).toLong()
            }
            changes["autoChargeDrift"]?.let { autoChargeDrift = it as Boolean }
            rev++
            return CompletableFuture.completedFuture(componentMap())
        }
    }

    @Test
    fun batteryAndPowerRoutesReadUpdateAndValidateCleanly() {
        val http = HttpClient.newHttpClient()
        ControlServer(control = StubBatteryControlClient(), port = 0).use { server ->
            val base = "http://127.0.0.1:${server.address.port}"

            val getInitial = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/battery")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, getInitial.statusCode())
            val initialJson = Formats.json.readTree(getInitial.body())
            assertEquals(true, initialJson.path("present").asBoolean())
            assertEquals(false, initialJson.path("charging").asBoolean())
            assertEquals(100.0, initialJson.path("chargePercent").asDouble())

            val postCharging = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/battery"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"chargePercent":78.0,"charging":true}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postCharging.statusCode())
            val chgJson = Formats.json.readTree(postCharging.body())
            assertEquals(true, chgJson.path("charging").asBoolean())
            assertEquals(true, chgJson.path("externalPowerConnected").asBoolean())
            assertEquals(78.0, chgJson.path("chargePercent").asDouble())
            assertTrue(chgJson.path("cratePercentPerHour").asDouble() > 0.0)

            // Verify /api/power alias reflects identical live state and accepts usbVbusConnected alias
            val getPower = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/power")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, getPower.statusCode())
            assertEquals(true, Formats.json.readTree(getPower.body()).path("charging").asBoolean())

            val postUnplug = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/power"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"usbVbusConnected":false}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postUnplug.statusCode())
            val unplugJson = Formats.json.readTree(postUnplug.body())
            assertEquals(false, unplugJson.path("charging").asBoolean())
            assertEquals(false, unplugJson.path("externalPowerConnected").asBoolean())
            assertTrue(unplugJson.path("cratePercentPerHour").asDouble() < 0.0)

            // Reject invalid percentage / unknown keys with 400
            for (badBody in listOf("""{}""", """{"chargePercent":150}""", """{"unknownKey":true}""")) {
                val badResp = http.send(
                    HttpRequest.newBuilder(URI.create("$base/api/battery"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(badBody))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(400, badResp.statusCode(), "Expected 400 for body=$badBody")
            }
        }

        // Verify 404 when battery component is absent
        ControlServer(control = StubBatteryControlClient(presentComponent = false), port = 0).use { server ->
            val resp = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.address.port}/api/battery")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(404, resp.statusCode())
        }
    }
}
