package com.github.nyabanban.fuji.emulator.components.lens

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class LensSnapshotTest {
    private class LensTestHarness {
        var nowTick: Long = 0L
        lateinit var lensPort: LensPort
        var depthPort: SceneDepthPort? = null
        val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-lens-test")
            override val clock = EmulationClock { nowTick }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    assertEquals("lens", owner)
                    when (name) {
                        "lens" -> {
                            assertEquals<Class<*>>(LensPort::class.java, type)
                            lensPort = port as LensPort
                        }
                        SceneDepthPort.PORT_NAME -> {
                            assertEquals<Class<*>>(SceneDepthPort::class.java, type)
                            depthPort = port as SceneDepthPort
                        }
                        else -> fail("Unexpected lens port registration: $name")
                    }
                }
                override fun <T : Any> find(name: String, type: Class<T>): T? = null
                override fun removeOwner(owner: String) {}
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) = error("unexpected lens event")
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
    fun roundTripPreservesLensParametersAndRejectsOutOfBoundsAtomically() {
        val factory = LensFactory()
        val defaultConfig = factory.descriptor.settings.associate { setting -> setting.key to setting.default }

        val harnessOne = LensTestHarness()
        val lensOne = factory.create(harnessOne.context, defaultConfig) as StatefulComponent
        lensOne.applySettings(
            mapOf(
                "model" to "gf-80mm-f1.7",
                "focalLengthMm" to 80.0,
                "aperture" to 1.7,
                "focusDistanceM" to 2.5,
                "transmission" to 0.94,
                "focusBreathing" to -0.05,
            )
        )

        val snapshot = simulateJsonRoundTripMap(lensOne.saveSnapshot())

        val harnessTwo = LensTestHarness()
        val lensTwo = factory.create(harnessTwo.context, defaultConfig) as StatefulComponent
        lensTwo.restoreSnapshot(snapshot)

        assertEquals(lensOne.saveSnapshot(), lensTwo.saveSnapshot())
        assertEquals(harnessOne.lensPort.parameters(), harnessTwo.lensPort.parameters())

        val integerJsonScalars = mapOf(
            "model" to "gf-50mm",
            "focalLengthMm" to 50,
            "aperture" to 4,
            "focusDistanceM" to 5,
            "transmission" to 1,
            "focusBreathing" to 0,
        )
        lensOne.restoreSnapshot(snapshot + ("scalarValues" to integerJsonScalars))
        lensTwo.restoreSnapshot(snapshot + ("scalarValues" to integerJsonScalars))
        assertEquals(LensParameters("gf-50mm", 50.0, 4.0, 5.0, 1.0, 0.0), harnessTwo.lensPort.parameters())

        val baselineState = lensTwo.state()
        val baselineParameters = harnessTwo.lensPort.parameters()
        val invalidCandidates = listOf<Map<String, Any?>>(
            snapshot + ("schema" to 9),
            snapshot + ("componentId" to "mount"),
            snapshot + ("scalarValues" to (integerJsonScalars + ("focalLengthMm" to 0.2))),
            snapshot + ("scalarValues" to (integerJsonScalars + ("aperture" to 128.0))),
            snapshot + ("scalarValues" to (integerJsonScalars + ("transmission" to 1.2))),
            snapshot + ("scalarValues" to (integerJsonScalars + ("focusBreathing" to 0.9))),
        )
        for (candidate in invalidCandidates) {
            assertFails { lensTwo.restoreSnapshot(candidate) }
            assertEquals(baselineState, lensTwo.state())
            assertEquals(baselineParameters, harnessTwo.lensPort.parameters())
        }

        lensOne.applySettings(mapOf("aperture" to 5.6))
        lensTwo.applySettings(mapOf("aperture" to 5.6))
        assertEquals(lensOne.saveSnapshot(), lensTwo.saveSnapshot())
        assertEquals(harnessOne.lensPort.parameters(), harnessTwo.lensPort.parameters())
    }

    @Test
    fun commandFocusEncoderHonorsCommandedSpeedAndStopsOnZeroSpeed() {
        val factory = LensFactory()
        val defaultConfig = factory.descriptor.settings.associate { setting -> setting.key to setting.default }
        val harness = LensTestHarness()
        val comp = factory.create(
            harness.context,
            defaultConfig + mapOf(
                "focusDistanceM" to 5.0,
                "focusMotorInstant" to false,
                "focusSweepSeconds" to 1.0,
                "focusMotorType" to "linear",
            ),
        ) as StatefulComponent
        val modular = harness.lensPort as ModularLensPort
        val startEnc = modular.focusEncoderPosition
        assertTrue(startEnc < 5_000, "Expected far start encoder < 5000, got $startEnc")

        // Command slow CDAF-style slew (4000 pulses/sec) toward near (20_000)
        modular.commandFocusEncoder(20_000, 4_000)
        assertTrue(modular.focusMotorBusy, "Motor must report busy while slewing")

        // Advance 50ms (5_000_000 ticks at 100MHz nominal) -> ~200 pulses traveled at 4000 pps (not instant jump)
        harness.nowTick += 5_000_000L
        val midEnc = modular.focusEncoderPosition
        assertTrue(midEnc in (startEnc + 150)..(startEnc + 250), "Expected speed-throttled slew (~200 pulses), start=$startEnc mid=$midEnc")
        assertTrue(modular.focusMotorBusy, "Motor must remain busy mid-slew")

        // Command STOP (speed=0) -> halts immediately and clears busy
        modular.commandFocusEncoder(midEnc, 0)
        assertFalse(modular.focusMotorBusy, "Motor must stop immediately on speed=0")
        harness.nowTick += 5_000_000L
        assertEquals(midEnc, modular.focusEncoderPosition, "Stopped motor must hold position")
        comp.close()
    }

    @Test
    fun apertureRingModesAndBodyIrisCommandStayTwoWaySynced() {
        val factory = LensFactory()
        val defaultConfig = factory.descriptor.settings.associate { setting -> setting.key to setting.default }
        val harness = LensTestHarness()
        val comp = factory.create(harness.context, defaultConfig + mapOf("template" to "gf45-100")) as StatefulComponent
        val modular = harness.lensPort as ModularLensPort

        // Default on electronic GF lens is A (Auto)
        assertEquals("A", modular.apertureRingMode)
        assertEquals(0, modular.irisStepIndex)
        assertEquals(false, comp.state()["bodyIrisSynced"])

        // Body AE commands f/8.0 (stepIdx 6 for f/4..32: 4.0, 4.5, 5.0, 5.6, 6.4, 7.1, 8.0)
        modular.commandApertureThirdStopIndex(6)
        assertEquals("A", modular.apertureRingMode, "Body iris command must not change physical ring mode")
        assertEquals(6, modular.irisStepIndex)
        assertEquals(8.0, harness.lensPort.parameters().aperture, 1e-3)
        assertEquals(true, comp.state()["bodyIrisSynced"])
        assertEquals(8.0, (comp.state()["bodyCommandedAperture"] as Number).toDouble(), 1e-3)

        // Switching to C (Command Dial) and body commanding f/11 (stepIdx 9)
        comp.applySettings(mapOf("apertureRingMode" to "C"))
        assertEquals("C", modular.apertureRingMode)
        modular.commandApertureThirdStopIndex(9)
        assertEquals(9, modular.irisStepIndex)
        assertEquals(11.0, harness.lensPort.parameters().aperture, 1e-3)

        // Turning manual aperture directly automatically switches ring to MANUAL and updates apertureRingStepIndex
        comp.applySettings(mapOf("aperture" to 5.6))
        assertEquals("MANUAL", modular.apertureRingMode)
        assertEquals(3, modular.apertureRingStepIndex)
        assertEquals(3, modular.irisStepIndex)
        assertEquals(5.6, harness.lensPort.parameters().aperture, 1e-3)

        // Snapshot round-trip preserves apertureRingMode
        val snap = simulateJsonRoundTripMap(comp.saveSnapshot())
        val harness2 = LensTestHarness()
        val comp2 = factory.create(harness2.context, defaultConfig) as StatefulComponent
        comp2.restoreSnapshot(snap)
        assertEquals("MANUAL", (harness2.lensPort as ModularLensPort).apertureRingMode)
        assertEquals(5.6, harness2.lensPort.parameters().aperture, 1e-3)

        comp.close()
        comp2.close()
    }
}
