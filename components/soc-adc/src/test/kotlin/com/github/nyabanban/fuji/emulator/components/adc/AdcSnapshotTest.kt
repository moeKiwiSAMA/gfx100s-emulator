package com.github.nyabanban.fuji.emulator.components.adc

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class AdcSnapshotTest {
    private val activeProfile = "adc-source-functional-experimental-v1"
    private val menuProfile = "gfx100s-2.13-menu-adc-v1"
    private val directionProfile = "gfx100s-2.13-direction-input-v1"

    private class AdcContext(val adcData: Map<String, Any?>) : ComponentContext {
        var currentTick = 0L
        val pressedKeys = hashSetOf<String>()
        val registeredPorts = hashMapOf<String, Any>()
        override val directory: Path = Path.of("unused-soc-adc-snapshot-test")
        override val clock = EmulationClock { currentTick }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        init {
            registeredPorts["buttons"] = object : ButtonPort {
                override fun pressed(key: String): Boolean = key in pressedKeys
            }
        }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(registeredPorts.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = registeredPorts[name]?.let(type::cast)
            override fun removeOwner(owner: String) = error("not used")
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = adcData
    }

    private fun fullAdcData(withButtons: Boolean = true): Map<String, Any?> {
        val base = linkedMapOf<String, Any?>(
            "profile" to activeProfile,
            "sample_ticks" to 10,
            "inputs" to linkedMapOf("0" to 500, "3" to 4000, "9" to 3900, "11" to 1234),
            "word_44" to 427,
        )
        if (withButtons) {
            base["menu_pressed_sample"] = 120
            base["direction_pressed_samples"] = linkedMapOf(
                "up" to 2000,
                "upRight" to 800,
                "right" to 1500,
                "downRight" to 1300,
                "down" to 2800,
                "downLeft" to 2400,
                "left" to 3200,
                "upLeft" to 1800,
            )
        }
        return base
    }

    private fun createAdc(
        context: AdcContext,
        selectedProfile: String = activeProfile,
        inputFile: String = "adc.json",
        selectedMenuProfile: String = menuProfile,
        selectedDirectionProfile: String = directionProfile,
    ): StatefulComponent {
        val factory = AdcFactory()
        val defaults = factory.descriptor.settings.associate { setting -> setting.key to setting.default }
        return assertIs<StatefulComponent>(
            factory.create(
                context,
                defaults + mapOf(
                    "profile" to selectedProfile,
                    "inputFile" to inputFile,
                    "menuButtonProfile" to selectedMenuProfile,
                    "directionButtonProfile" to selectedDirectionProfile,
                ),
            ),
        )
    }

    private fun simulateJsonRoundTrip(raw: Map<String, Any?>): Map<String, Any?> {
        fun convert(value: Any?): Any? = when (value) {
            null, is Boolean, is String -> value
            is Byte, is Short, is Int, is Long -> {
                val number = (value as Number).toLong()
                if (number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) number.toInt() else number
            }
            is List<*> -> value.map { element -> convert(element) }
            is Map<*, *> -> {
                val copy = linkedMapOf<String, Any?>()
                for ((mapKey, mapVal) in value) {
                    check(mapKey != null)
                    copy[mapKey.toString()] = convert(mapVal)
                }
                copy
            }
            else -> error("Unsupported non-JSON value: ${value::class.java.name}")
        }
        @Suppress("UNCHECKED_CAST")
        return convert(raw) as Map<String, Any?>
    }

    @Test
    fun continuousPhaseLastEdgeBoundedLogsAndButtonSamplingRoundTripEquivalence() {
        val directContext = AdcContext(fullAdcData())
        val direct = createAdc(directContext)
        direct.start()
        val directDevice = direct as ScheduledMmioDevice

        val channelMask = (1L shl 0) or (1L shl 3) or (1L shl 9) or (1L shl 11)
        directDevice.write(0x04L, 4, 1L)
        directDevice.write(0x08L, 4, 1L)
        directDevice.write(0x40L, 4, 0x5aL)
        directDevice.write(0x00L, 4, (channelMask shl 16) or 3L)

        for (cycle in 0 until 262) {
            directContext.pressedKeys.clear()
            if (cycle % 2 == 0) directContext.pressedKeys += "menu"
            if (cycle % 3 == 0) {
                directContext.pressedKeys += "up"
                directContext.pressedKeys += "left"
            }
            val deadline = checkNotNull(directDevice.nextDeadline())
            directContext.currentTick = deadline + 3L
            directDevice.beforeInstruction(directContext.currentTick)
            directDevice.read(0x10L + 3L * 4L, 4)
            directDevice.read(0x10L + 9L * 4L, 4)
            directContext.currentTick += 22L
            directDevice.write(0x0cL, 4, 1L)
        }

        directContext.currentTick += 15L
        assertEquals(true, direct.state()["eventLogTruncated"])
        assertEquals(true, direct.state()["sampleReadLogTruncated"])
        assertNotNull(directDevice.nextDeadline())

        val roundTrippedSnapshot = simulateJsonRoundTrip(direct.saveSnapshot())

        val restoredContext = AdcContext(fullAdcData())
        restoredContext.currentTick = directContext.currentTick
        restoredContext.pressedKeys.addAll(directContext.pressedKeys)
        val restored = createAdc(restoredContext)
        restored.start()
        val restoredDevice = restored as ScheduledMmioDevice
        restored.restoreSnapshot(roundTrippedSnapshot)

        assertEquals(direct.state(), restored.state())
        assertEquals(direct.saveSnapshot(), restored.saveSnapshot())
        assertEquals(directDevice.nextDeadline(), restoredDevice.nextDeadline())

        fun runPhaseTwo(context: AdcContext, device: ScheduledMmioDevice): List<Long> {
            val observations = ArrayList<Long>()
            context.pressedKeys.clear()
            context.pressedKeys += "down"
            context.pressedKeys += "right"
            val firstDeadline = checkNotNull(device.nextDeadline())
            context.currentTick = firstDeadline + 2L
            device.beforeInstruction(context.currentTick)
            for (channel in listOf(0, 3, 9, 11)) {
                observations += device.read(0x10L + channel * 4L, 4)
            }
            observations += device.read(0x44L, 4)
            context.currentTick += 19L
            device.write(0x0cL, 4, 1L)
            observations += checkNotNull(device.nextDeadline())

            context.pressedKeys.clear()
            context.pressedKeys += "menu"
            context.currentTick = checkNotNull(device.nextDeadline())
            device.beforeInstruction(context.currentTick)
            for (channel in listOf(0, 3, 9, 11)) {
                observations += device.read(0x10L + channel * 4L, 4)
            }
            return observations
        }

        val directReads = runPhaseTwo(directContext, directDevice)
        val restoredReads = runPhaseTwo(restoredContext, restoredDevice)
        assertEquals(directReads, restoredReads)
        assertEquals(direct.state(), restored.state())
        assertEquals(direct.saveSnapshot(), restored.saveSnapshot())

        direct.close()
        restored.close()
    }

    @Test
    fun compatibleButtonProfileUpgradeAndAtomicInvalidSnapshotRejection() {
        val plainContext = AdcContext(fullAdcData(withButtons = false))
        val plainAdc = createAdc(
            plainContext,
            inputFile = "plain.json",
            selectedMenuProfile = "disabled",
            selectedDirectionProfile = "disabled",
        )
        plainAdc.start()
        val plainDevice = plainAdc as ScheduledMmioDevice
        plainDevice.write(0x08L, 4, 1L)
        plainDevice.write(0x00L, 4, ((1L shl 3) shl 16) or 1L)
        plainContext.currentTick = 10L
        plainDevice.beforeInstruction(10L)
        val plainSnap = simulateJsonRoundTrip(plainAdc.saveSnapshot())

        val upgradedContext = AdcContext(fullAdcData(withButtons = true))
        val upgradedAdc = createAdc(upgradedContext)
        upgradedAdc.start()
        upgradedAdc.restoreSnapshot(plainSnap)
        val upgradedDevice = upgradedAdc as ScheduledMmioDevice
        assertEquals(4000L, upgradedDevice.read(0x10L + 3L * 4L, 4))

        upgradedContext.pressedKeys += "menu"
        upgradedContext.currentTick = 20L
        upgradedDevice.write(0x0cL, 4, 1L)
        upgradedDevice.write(0x00L, 4, ((1L shl 3) shl 16) or 1L)
        upgradedContext.currentTick = 30L
        upgradedDevice.beforeInstruction(30L)
        assertEquals(120L, upgradedDevice.read(0x10L + 3L * 4L, 4))

        val baselineState = upgradedAdc.state()
        val baselineSnapshot = upgradedAdc.saveSnapshot()

        fun baseValid(): MutableMap<String, Any?> = LinkedHashMap(simulateJsonRoundTrip(baselineSnapshot))

        val invalidMutations = listOf<(MutableMap<String, Any?>) -> Unit>(
            { snap -> snap["schema"] = 2 },
            { snap -> snap["componentId"] = "soc-gpio" },
            { snap -> snap["sampleTicks"] = 99 },
            { snap ->
                val regs = LinkedHashMap(snap["registers"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                regs["control"] = 0x8000_0000L
                snap["registers"] = regs
            },
            { snap ->
                val regs = LinkedHashMap(snap["registers"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                regs["deadline"] = 100
                regs["gate"] = 0
                snap["registers"] = regs
            },
            { snap ->
                val regs = LinkedHashMap(snap["registers"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                regs["samples"] = List(11) { 0 }
                snap["registers"] = regs
            },
            { snap -> snap["sampleReadCount"] = 999 },
        )

        for (mutate in invalidMutations) {
            val candidate = baseValid()
            mutate(candidate)
            assertFailsWith<IllegalArgumentException> {
                upgradedAdc.restoreSnapshot(candidate)
            }
            assertEquals(baselineState, upgradedAdc.state())
            assertEquals(baselineSnapshot, upgradedAdc.saveSnapshot())
        }

        plainAdc.close()
        upgradedAdc.close()
    }

    @Test
    fun liveAdcControlsCh3Ch5Ch6RearDialRawOverridesAndLegacySnapshotRestore() {
        val allChannelsData = linkedMapOf<String, Any?>(
            "profile" to activeProfile,
            "sample_ticks" to 10,
            "inputs" to linkedMapOf(
                "0" to 0, "1" to 257, "2" to 514, "3" to 771, "4" to 1028, "5" to 1285,
                "6" to 1542, "7" to 1799, "8" to 2056, "9" to 4000, "10" to 2570, "11" to 2827,
            ),
            "word_44" to null,
            "menu_pressed_sample" to 2800,
            "direction_pressed_samples" to linkedMapOf(
                "up" to 2074, "upRight" to 665, "right" to 1568, "downRight" to 1339,
                "down" to 2823, "downLeft" to 2412, "left" to 3380, "upLeft" to 1809,
            ),
        )
        val ctx = AdcContext(allChannelsData)
        val adc = createAdc(ctx)
        adc.start()
        val dev = adc as ScheduledMmioDevice
        dev.write(0x08L, 4, 1L)

        fun sampleChannel(ch: Int): Long {
            val mask = 1L shl ch
            dev.write(0x0cL, 4, 1L)
            dev.write(0x00L, 4, (mask shl 16) or 1L)
            ctx.currentTick = checkNotNull(dev.nextDeadline())
            dev.beforeInstruction(ctx.currentTick)
            return dev.read(0x10L + ch * 4L, 4)
        }

        fun samplePair(chA: Int, chB: Int): Pair<Long, Long> {
            val mask = (1L shl chA) or (1L shl chB)
            dev.write(0x0cL, 4, 1L)
            dev.write(0x00L, 4, (mask shl 16) or 1L)
            ctx.currentTick = checkNotNull(dev.nextDeadline())
            dev.beforeInstruction(ctx.currentTick)
            return dev.read(0x10L + chA * 4L, 4) to dev.read(0x10L + chB * 4L, 4)
        }

        // Default static inputs
        assertEquals(771L, sampleChannel(3))
        assertEquals(1285L, sampleChannel(5))
        assertEquals(1542L, sampleChannel(6))

        // CH3: Q, AEL, MENU/OK, and chords
        adc.applySettings(mapOf("qButton" to true))
        assertEquals(3440L, sampleChannel(3))
        ctx.pressedKeys += "menu"
        assertEquals(2490L, sampleChannel(3)) // Q + MENU/OK
        adc.applySettings(mapOf("qButton" to false, "aelButton" to true))
        assertEquals(1802L, sampleChannel(3)) // MENU/OK + AEL
        ctx.pressedKeys.clear()
        assertEquals(2230L, sampleChannel(3)) // AEL only
        adc.applySettings(mapOf("qButton" to true, "aelButton" to true))
        assertEquals(2005L, sampleChannel(3)) // Q + AEL
        adc.applySettings(mapOf("ch3Override" to 3150L))
        assertEquals(3150L, sampleChannel(3))
        adc.applySettings(mapOf("qButton" to false, "aelButton" to false, "ch3Override" to -1L))
        assertEquals(771L, sampleChannel(3))

        // CH5: Mode Dial positions (DAT bank14 table)
        val expectedModes = mapOf(
            "M" to 345L, "C1" to 792L, "C2" to 1194L, "C3" to 1595L, "C4" to 1997L,
            "C5" to 2402L, "C6" to 2815L, "P" to 3237L, "S" to 3672L, "A" to 3994L,
        )
        for ((mode, expected) in expectedModes) {
            adc.applySettings(mapOf("modeDial" to mode))
            assertEquals(expected, sampleChannel(5), "Mode dial $mode mismatch")
        }
        adc.applySettings(mapOf("ch5Override" to 1000L))
        assertEquals(1000L, sampleChannel(5))
        adc.applySettings(mapOf("modeDial" to "default", "ch5Override" to -1L))
        assertEquals(1285L, sampleChannel(5))

        // CH6: Fn1, Fn2, Fn3, LIGHT, and chords
        adc.applySettings(mapOf("fn1Button" to true))
        assertEquals(3440L, sampleChannel(6))
        adc.applySettings(mapOf("fn3Button" to true))
        assertEquals(2490L, sampleChannel(6)) // Fn1 + Fn3
        adc.applySettings(mapOf("fn1Button" to false))
        assertEquals(2870L, sampleChannel(6)) // Fn3 only
        adc.applySettings(mapOf("fn2Button" to true))
        assertEquals(1802L, sampleChannel(6)) // Fn2 + Fn3
        adc.applySettings(mapOf("fn3Button" to false))
        assertEquals(2230L, sampleChannel(6)) // Fn2 only
        adc.applySettings(mapOf("fn1Button" to true))
        assertEquals(2005L, sampleChannel(6)) // Fn1 + Fn2
        adc.applySettings(mapOf("lightButton" to true))
        assertEquals(391L, sampleChannel(6)) // LIGHT priority
        adc.applySettings(mapOf("fn1Button" to false, "fn2Button" to false, "lightButton" to false))
        assertEquals(1542L, sampleChannel(6))

        // CH7 + CH11: Rear Command Dial direct Gray position and CW/CCW quadrature stepping
        // Initial input.json CH7=1799 (0), CH11=2827 (1) -> Gray state 1 (index 1 in [0, 1, 3, 2])
        // +2 CW steps -> Gray 3, held for three sample pairs, then Gray 2.
        adc.applySettings(mapOf("rearDialDetents" to 2L))
        assertEquals(3500L to 3500L, samplePair(7, 11)) // step 1: Gray 1 -> 3
        assertEquals(3500L to 3500L, samplePair(7, 11)) // hold 2
        assertEquals(3500L to 3500L, samplePair(7, 11)) // hold 3
        assertEquals(3500L to 500L, samplePair(7, 11)) // step 2: Gray 3 -> 2

        // Direct position override
        adc.applySettings(mapOf("rearDialPosition" to 3L))
        assertEquals(3500L to 3500L, samplePair(7, 11))
        // Raw overrides take precedence over quadrature
        adc.applySettings(mapOf("ch7Override" to 123L, "ch11Override" to 456L))
        assertEquals(123L to 456L, samplePair(7, 11))

        // Legacy 4-key snapshot restore resets live controls back to defaults
        val snap = LinkedHashMap(simulateJsonRoundTrip(adc.saveSnapshot()))
        snap["scalars"] = linkedMapOf(
            "profile" to activeProfile,
            "inputFile" to "adc.json",
            "menuButtonProfile" to menuProfile,
            "directionButtonProfile" to directionProfile,
        )
        adc.restoreSnapshot(snap)
        assertEquals(false, adc.state()["qButton"])
        assertEquals("default", adc.state()["modeDial"])
        assertEquals(-1L, adc.state()["ch7Override"])
        assertEquals(771L, sampleChannel(3))
        assertEquals(1285L, sampleChannel(5))

        adc.close()
    }
}
