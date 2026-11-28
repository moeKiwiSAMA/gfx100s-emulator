package com.github.nyabanban.fuji.emulator.components.battery

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class BatterySnapshotTest {
    private class BatteryTestHarness {
        lateinit var batteryPort: BatteryPort
        val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-battery-test")
            override val clock = EmulationClock { 0L }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    assertEquals("battery", owner)
                    assertEquals("battery", name)
                    assertEquals<Class<*>>(BatteryPort::class.java, type)
                    batteryPort = port as BatteryPort
                }
                override fun <T : Any> find(name: String, type: Class<T>): T? = null
                override fun removeOwner(owner: String) {}
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) = error("unexpected battery event")
                override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = error("unexpected subscribe")
            }
            override fun dataPath(relative: String): Path = error("unexpected dataPath")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun simulateJsonRoundTripMap(map: Map<String, Any?>): Map<String, Any?> =
        simulateJsonRoundTripValue(map) as Map<String, Any?>

    private fun simulateJsonRoundTripValue(value: Any?): Any? = when (value) {
        null, is Boolean, is String -> value
        is Byte, is Short, is Int -> (value as Number).toInt()
        is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else value
        is Float, is Double -> (value as Number).toDouble()
        is List<*> -> value.map { element -> simulateJsonRoundTripValue(element) }
        is Map<*, *> -> value.entries.associate { entry -> (entry.key as String) to simulateJsonRoundTripValue(entry.value) }
        else -> error("Unsupported non-JSON value: ${value::class.java.name}")
    }

    @Test
    fun jsonRoundTripPreservesBatteryPortReadingsAndContinuation() {
        val factory = BatteryFactory()
        val defaultConfig = mapOf("chargePercent" to 100.0, "voltageMillivolts" to 7400L, "present" to true)

        val harnessOne = BatteryTestHarness()
        val batteryOne = factory.create(harnessOne.context, defaultConfig) as StatefulComponent
        batteryOne.applySettings(mapOf("chargePercent" to 62.5, "voltageMillivolts" to 7150L, "present" to true))

        val snapshot = simulateJsonRoundTripMap(batteryOne.saveSnapshot())

        val harnessTwo = BatteryTestHarness()
        val batteryTwo = factory.create(harnessTwo.context, defaultConfig) as StatefulComponent
        batteryTwo.restoreSnapshot(snapshot)

        assertEquals(batteryOne.state(), batteryTwo.state())
        assertEquals(62.5, harnessTwo.batteryPort.chargePercent())
        assertEquals(7150, harnessTwo.batteryPort.voltageMillivolts())
        assertTrue(harnessTwo.batteryPort.present())

        val wholeChargeSnapshot = snapshot + ("scalarValues" to mapOf("chargePercent" to 80, "voltageMillivolts" to 7300, "present" to false))
        batteryOne.restoreSnapshot(wholeChargeSnapshot)
        batteryTwo.restoreSnapshot(wholeChargeSnapshot)
        assertEquals(80.0, harnessTwo.batteryPort.chargePercent())
        assertEquals(7300, harnessTwo.batteryPort.voltageMillivolts())
        assertFalse(harnessTwo.batteryPort.present())

        batteryOne.applySettings(mapOf("chargePercent" to 45.0, "present" to true))
        batteryTwo.applySettings(mapOf("chargePercent" to 45.0, "present" to true))
        assertEquals(batteryOne.state(), batteryTwo.state())
        assertEquals(batteryOne.saveSnapshot(), batteryTwo.saveSnapshot())
    }

    @Test
    fun invalidSnapshotsRejectAtomicallyWithoutMutatingBatteryReadings() {
        val factory = BatteryFactory()
        val harness = BatteryTestHarness()
        val battery = factory.create(
            harness.context,
            mapOf("chargePercent" to 77.5, "voltageMillivolts" to 7250L, "present" to true),
        ) as StatefulComponent
        val baselineState = battery.state()
        val validSnapshot = simulateJsonRoundTripMap(battery.saveSnapshot())
        @Suppress("UNCHECKED_CAST")
        val validScalars = validSnapshot["scalarValues"] as Map<String, Any?>

        val invalidCandidates = listOf<Map<String, Any?>>(
            validSnapshot + ("schema" to 0),
            validSnapshot + ("componentId" to "cmos"),
            validSnapshot + ("unexpected" to 123),
            validSnapshot + ("scalarValues" to (validScalars + ("chargePercent" to 101.0) + ("present" to false))),
            validSnapshot + ("scalarValues" to (validScalars + ("chargePercent" to -0.5) + ("present" to false))),
            validSnapshot + ("scalarValues" to (validScalars + ("voltageMillivolts" to 7250.5) + ("present" to false))),
            validSnapshot + ("scalarValues" to (validScalars + ("voltageMillivolts" to 15000) + ("present" to false))),
            validSnapshot + ("scalarValues" to (validScalars + ("present" to "yes"))),
            validSnapshot + ("scalarValues" to (validScalars + ("extraKey" to 1))),
        )

        for (candidate in invalidCandidates) {
            assertFails { battery.restoreSnapshot(candidate) }
            assertEquals(baselineState, battery.state())
            assertEquals(77.5, harness.batteryPort.chargePercent())
            assertEquals(7250, harness.batteryPort.voltageMillivolts())
            assertTrue(harness.batteryPort.present())
        }
    }

    @Test
    fun chargingExternalPowerCurveListenersAndDriftSynchronizeAndRoundTrip() {
        var nowTick = 1_000_000L
        lateinit var registeredPort: BatteryPort
        val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-battery-charging-test")
            override val clock = EmulationClock { nowTick }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    assertEquals<Class<*>>(BatteryPort::class.java, type)
                    registeredPort = port as BatteryPort
                }
                override fun <T : Any> find(name: String, type: Class<T>): T? = null
                override fun removeOwner(owner: String) {}
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) = error("unexpected battery event")
                override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = error("unexpected subscribe")
            }
            override fun dataPath(relative: String): Path = error("unexpected dataPath")
        }

        val factory = BatteryFactory()
        val comp = factory.create(
            context,
            mapOf("chargePercent" to 60.0, "voltageMillivolts" to 7400L, "present" to true),
        ) as StatefulComponent
        val control = assertIs<PowerControlPort>(registeredPort)

        var notifications = 0
        val lease = registeredPort.addPowerStateListener { notifications++ }

        assertFalse(registeredPort.charging())
        assertFalse(registeredPort.externalPowerConnected())
        assertTrue(registeredPort.chargeRatePercentPerHour() < 0.0)

        // Enabling charging automatically connects external USB-C power and derives NP-W235 charging voltage
        val chargingStatus = control.updatePowerState(chargePercent = 78.0, charging = true, autoChargeDrift = true)
        assertEquals(1, notifications)
        assertTrue(chargingStatus.charging)
        assertTrue(chargingStatus.externalPowerConnected)
        assertEquals(78.0, chargingStatus.chargePercent)
        assertEquals(NpW235BatteryCurve.deriveMillivolts(78.0, charging = true), chargingStatus.voltageMillivolts)
        assertEquals(36.0, chargingStatus.chargeRatePercentPerHour)

        // Advance virtual clock by 50 seconds (5 x 10s intervals at 54 MHz = +0.5% SOC)
        nowTick += 5L * 540_000_000L
        assertEquals(78.5, registeredPort.chargePercent(), 1e-6)
        assertEquals(NpW235BatteryCurve.deriveMillivolts(78.5, charging = true), registeredPort.voltageMillivolts())

        // Snapshot round-trip preserves charging, externalPowerConnected, and drifted chargePercent
        val snap = simulateJsonRoundTripMap(comp.saveSnapshot())
        val harnessRestored = BatteryTestHarness()
        val restored = factory.create(
            harnessRestored.context,
            mapOf("chargePercent" to 100.0, "voltageMillivolts" to 7400L, "present" to true),
        ) as StatefulComponent
        restored.restoreSnapshot(snap)
        assertTrue(harnessRestored.batteryPort.charging())
        assertTrue(harnessRestored.batteryPort.externalPowerConnected())
        assertEquals(78.5, harnessRestored.batteryPort.chargePercent(), 1e-6)

        // Plugged in, not charging (charge complete / idle on external power) -> CRATE == 0.0
        control.applyPowerAction(mapOf("charging" to false, "externalPowerConnected" to true))
        assertFalse(registeredPort.charging())
        assertTrue(registeredPort.externalPowerConnected())
        assertEquals(0.0, registeredPort.chargeRatePercentPerHour())

        // Unplugging external USB-C power clears both externalPowerConnected and charging
        control.applyPowerAction(mapOf("usbVbusConnected" to false))
        assertFalse(registeredPort.charging())
        assertFalse(registeredPort.externalPowerConnected())
        assertTrue(registeredPort.chargeRatePercentPerHour() < 0.0)

        lease.close()
        val countBeforeAfterClose = notifications
        control.updatePowerState(charging = true)
        assertEquals(countBeforeAfterClose, notifications)
        comp.close()
        restored.close()
    }
}
