package com.github.nyabanban.fuji.emulator.components.gpio

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class GpioSnapshotTest {
    private val buttonsV2Profile = "gfx100s-2.13-gpio-buttons-v2"
    private val directionProfile = "gfx100s-2.13-direction-input-v1"

    private class GpioContext : ComponentContext {
        val pressedKeys = hashSetOf<String>()
        val registeredPorts = hashMapOf<String, Any>()
        var currentTick: Long = 0L
        override val directory: Path = Path.of("unused-soc-gpio-snapshot-test")
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
    }

    private fun createGpio(
        context: GpioContext,
        selectedProfile: String = buttonsV2Profile,
        selectedDirectionProfile: String = directionProfile,
    ): StatefulComponent {
        val factory = GpioFactory()
        val defaults = factory.descriptor.settings.associate { setting -> setting.key to setting.default }
        return assertIs<StatefulComponent>(
            factory.create(
                context,
                defaults + mapOf(
                    "profile" to selectedProfile,
                    "directionButtonProfile" to selectedDirectionProfile,
                ),
            ),
        )
    }

    private fun bankOffset(bankIndex: Int, registerIndex: Int): Long = bankIndex * 64L + registerIndex * 4L

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
    fun allBanksMaskedOutputsRetainedWordsInputSelectionsAndButtonsRoundTripEquivalence() {
        val directContext = GpioContext()
        val direct = createGpio(directContext)
        direct.start()
        val directMmio = direct as MmioDevice
        val directOutputs = direct as GpioOutputPort

        direct.applySettings(
            mapOf(
                "input0" to 0x00ffL,
                "input7" to 0x0200L,
                "input9" to 0x0060L,
                "input12" to 0xa55aL,
            ),
        )

        directMmio.write(bankOffset(12, 0), 4, (0x0025L shl 16) or 0x0021L)
        directMmio.write(bankOffset(12, 0), 4, (0x0020L shl 16) or 0x0000L)
        directMmio.write(bankOffset(12, 0), 4, (0x0020L shl 16) or 0x0020L)
        directMmio.write(bankOffset(12, 1), 4, (0xffffL shl 16) or 0x1234L)
        directMmio.write(bankOffset(3, 4), 4, (0x0f0fL shl 16) or 0x0505L)
        directMmio.write(bankOffset(5, 6), 4, 0xdead_beefL)
        directMmio.write(bankOffset(15, 7), 4, 0x8000_0001L)

        assertTrue(directOutputs.outputLevel(12, 5))
        assertEquals(3L, directOutputs.outputSequence(12, 5))

        val roundTrippedSnapshot = simulateJsonRoundTrip(direct.saveSnapshot())

        val restoredContext = GpioContext()
        val restored = createGpio(restoredContext)
        restored.start()
        val restoredMmio = restored as MmioDevice
        val restoredOutputs = restored as GpioOutputPort
        restored.restoreSnapshot(roundTrippedSnapshot)

        assertEquals(direct.state(), restored.state())
        assertEquals(direct.saveSnapshot(), restored.saveSnapshot())
        assertEquals(directOutputs.outputLevel(12, 5), restoredOutputs.outputLevel(12, 5))
        assertEquals(directOutputs.outputSequence(12, 5), restoredOutputs.outputSequence(12, 5))

        fun runPhaseTwo(context: GpioContext, mmio: MmioDevice, outputPort: GpioOutputPort): List<Long> {
            val results = ArrayList<Long>()
            context.pressedKeys.clear()
            context.pressedKeys += "play"
            context.pressedKeys += "shutterHalf"
            context.pressedKeys += "up"
            results += mmio.read(bankOffset(0, 5), 4)
            results += mmio.read(bankOffset(7, 5), 4)
            results += mmio.read(bankOffset(9, 5), 4)
            results += mmio.read(bankOffset(5, 6), 4)
            results += mmio.read(bankOffset(15, 7), 4)

            mmio.write(bankOffset(12, 0), 4, (0x0020L shl 16) or 0x0000L)
            results += if (outputPort.outputLevel(12, 5)) 1L else 0L
            results += outputPort.outputSequence(12, 5)
            return results
        }

        val directPhaseTwo = runPhaseTwo(directContext, directMmio, directOutputs)
        val restoredPhaseTwo = runPhaseTwo(restoredContext, restoredMmio, restoredOutputs)
        assertEquals(directPhaseTwo, restoredPhaseTwo)
        assertEquals(direct.state(), restored.state())
        assertEquals(direct.saveSnapshot(), restored.saveSnapshot())

        direct.close()
        restored.close()
    }

    @Test
    fun compatibleProfileUpgradeAndAtomicInvalidSnapshotRejection() {
        val disabledContext = GpioContext()
        val disabledGpio = createGpio(
            disabledContext,
            selectedProfile = "disabled",
            selectedDirectionProfile = "disabled",
        )
        disabledGpio.applySettings(mapOf("input4" to 0x4321L))
        (disabledGpio as MmioDevice).write(bankOffset(4, 0), 4, (0x0008L shl 16) or 0x0008L)
        val disabledSnap = simulateJsonRoundTrip(disabledGpio.saveSnapshot())

        val fullContext = GpioContext()
        val fullGpio = createGpio(fullContext)
        fullGpio.start()
        fullGpio.restoreSnapshot(disabledSnap)
        val fullMmio = fullGpio as MmioDevice
        assertEquals(0x4321L, fullMmio.read(bankOffset(4, 5), 4))
        assertTrue((fullGpio as GpioOutputPort).outputLevel(4, 3))

        val baselineState = fullGpio.state()
        val baselineSnapshot = fullGpio.saveSnapshot()

        fun baseValid(): MutableMap<String, Any?> = LinkedHashMap(simulateJsonRoundTrip(baselineSnapshot))

        val invalidMutations = listOf<(MutableMap<String, Any?>) -> Unit>(
            { snap -> snap["schema"] = 2 },
            { snap -> snap["componentId"] = "soc-adc" },
            { snap -> snap["extraKey"] = 1 },
            { snap ->
                val scalars = LinkedHashMap(snap["scalars"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                scalars["input0"] = 70000
                snap["scalars"] = scalars
            },
            { snap ->
                val scalars = LinkedHashMap(snap["scalars"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                scalars["input0"] = 1.5
                snap["scalars"] = scalars
            },
            { snap ->
                val matrix = (snap["registers"] as List<*>).map { row -> (row as List<*>).toMutableList() }.toMutableList()
                matrix[0][5] = 1
                snap["registers"] = matrix
            },
            { snap ->
                val matrix = (snap["registers"] as List<*>).map { row -> (row as List<*>).toMutableList() }.toMutableList()
                matrix[4][0] = 0x1_0000
                snap["registers"] = matrix
            },
            { snap ->
                val changesMatrix = (snap["outputChanges"] as List<*>).map { row -> (row as List<*>).toMutableList() }.toMutableList()
                changesMatrix[4][3] = 2
                snap["outputChanges"] = changesMatrix
            },
        )

        for (mutate in invalidMutations) {
            val candidate = baseValid()
            mutate(candidate)
            assertFailsWith<IllegalArgumentException> {
                fullGpio.restoreSnapshot(candidate)
            }
            assertEquals(baselineState, fullGpio.state())
            assertEquals(baselineSnapshot, fullGpio.saveSnapshot())
        }

        disabledGpio.close()
        fullGpio.close()
    }

    @Test
    fun buttonsV4MovieModeSwitchPolarityAndMonotonicSnapshotPromotion() {
        val v3Context = GpioContext()
        val v3Gpio = createGpio(v3Context, selectedProfile = "gfx100s-2.13-gpio-buttons-v3")
        v3Gpio.start()
        v3Gpio.applySettings(mapOf("input7" to 0x2040L))
        val v3Snap = simulateJsonRoundTrip(v3Gpio.saveSnapshot())

        val v4Context = GpioContext()
        val v4Gpio = createGpio(v4Context, selectedProfile = "gfx100s-2.13-gpio-buttons-v4")
        v4Gpio.start()
        v4Gpio.restoreSnapshot(v3Snap)
        val v4Mmio = v4Gpio as MmioDevice

        // movieMode = false (STILL): active-LOW Bank 7 Pin 5 (0x20) + active-LOW play Pin 9 (0x200) read HIGH
        assertEquals(0x2260L, v4Mmio.read(bankOffset(7, 5), 4))
        // movieMode = true (MOVIE): active-LOW Bank 7 Pin 5 reads LOW (bit 5 = 0)
        v4Context.pressedKeys += "movieMode"
        assertEquals(0x2240L, v4Mmio.read(bankOffset(7, 5), 4))
        v4Context.pressedKeys -= "movieMode"
        assertEquals(0x2260L, v4Mmio.read(bankOffset(7, 5), 4))

        // Downgrade from v4 snapshot into v3 component is strictly refused
        val v4Snap = simulateJsonRoundTrip(v4Gpio.saveSnapshot())
        assertFailsWith<IllegalArgumentException> { v3Gpio.restoreSnapshot(v4Snap) }

        v3Gpio.close()
        v4Gpio.close()
    }

    @Test
    fun buttonsV5DriveAfOnFocusModePolarityAndMonotonicSnapshotPromotion() {
        val v4Context = GpioContext()
        val v4Gpio = createGpio(v4Context, selectedProfile = "gfx100s-2.13-gpio-buttons-v4")
        v4Gpio.start()
        v4Gpio.applySettings(mapOf("input0" to 0x4000L, "input6" to 0x0012L, "input7" to 0x2040L))
        val v4Snap = simulateJsonRoundTrip(v4Gpio.saveSnapshot())

        val v5Context = GpioContext()
        val v5Gpio = createGpio(v5Context, selectedProfile = "gfx100s-2.13-gpio-buttons-v5")
        v5Gpio.start()
        v5Gpio.restoreSnapshot(v4Snap)
        val v5Mmio = v5Gpio as MmioDevice

        // Idle: active-LOW AF-ON (0,14), rearDialPress (0,13), S1/S2 (0,0..1) all HIGH -> 0x6003
        assertEquals(0x6003L, v5Mmio.read(bankOffset(0, 5), 4))
        // Idle: active-LOW DRIVE (6,1) + DELETE (6,2) + bit4 (0x10) all HIGH -> 0x0016
        assertEquals(0x0016L, v5Mmio.read(bankOffset(6, 5), 4))
        // Idle: active-LOW Focus Mode (7,13) + direction gate (7,9) + movieMode (7,5) + bit6 (0x40) -> 0x2260
        assertEquals(0x2260L, v5Mmio.read(bankOffset(7, 5), 4))

        // Press DRIVE -> clears Bank 6 Pin 1 only
        v5Context.pressedKeys += "drive"
        assertEquals(0x0014L, v5Mmio.read(bankOffset(6, 5), 4))
        assertEquals(0x6003L, v5Mmio.read(bankOffset(0, 5), 4))
        assertEquals(0x2260L, v5Mmio.read(bankOffset(7, 5), 4))
        v5Context.pressedKeys -= "drive"

        // Press AF-ON -> clears Bank 0 Pin 14 only
        v5Context.pressedKeys += "afOn"
        assertEquals(0x2003L, v5Mmio.read(bankOffset(0, 5), 4))
        v5Context.pressedKeys -= "afOn"

        // Latch Focus Mode Lever -> clears Bank 7 Pin 13 only
        v5Context.pressedKeys += "focusMode"
        assertEquals(0x0260L, v5Mmio.read(bankOffset(7, 5), 4))
        v5Context.pressedKeys -= "focusMode"
        assertEquals(0x2260L, v5Mmio.read(bankOffset(7, 5), 4))

        // Downgrade from v5 snapshot into v4 component is strictly refused
        val v5Snap = simulateJsonRoundTrip(v5Gpio.saveSnapshot())
        assertFailsWith<IllegalArgumentException> { v4Gpio.restoreSnapshot(v5Snap) }

        v4Gpio.close()
        v5Gpio.close()
    }

    @Test
    fun buttonsV6FrontDialPressFn4FocusStickWiredOrAndQuadratureRoundTrip() {
        val v5Context = GpioContext()
        val v5Gpio = createGpio(v5Context, selectedProfile = "gfx100s-2.13-gpio-buttons-v5")
        v5Gpio.start()
        v5Gpio.applySettings(mapOf("input1" to 0L, "input2" to 0x2004L, "input7" to 0x2040L))
        val v5Snap = simulateJsonRoundTrip(v5Gpio.saveSnapshot())

        val v6Context = GpioContext()
        val v6Gpio = createGpio(v6Context, selectedProfile = "gfx100s-2.13-gpio-buttons-v6")
        v6Gpio.start()
        v6Gpio.restoreSnapshot(v5Snap)
        val mmio = v6Gpio as MmioDevice
        val tickAware = v6Gpio as TickAwareMmioDevice

        // Idle: Bank 2 pins 2 & 13 HIGH (0x2004), Bank 7 pin 9 HIGH (0x2260)
        assertEquals(0x2004L, mmio.read(bankOffset(2, 5), 4))
        assertEquals(0x2260L, mmio.read(bankOffset(7, 5), 4))

        // Press frontDialPress -> clears Bank 2 pin 2; press fn4 -> clears Bank 2 pin 13
        v6Context.pressedKeys += "frontDialPress"
        assertEquals(0x2000L, mmio.read(bankOffset(2, 5), 4))
        v6Context.pressedKeys += "fn4"
        assertEquals(0x0000L, mmio.read(bankOffset(2, 5), 4))
        v6Context.pressedKeys -= "frontDialPress"
        assertEquals(0x0004L, mmio.read(bankOffset(2, 5), 4))
        v6Context.pressedKeys -= "fn4"
        assertEquals(0x2004L, mmio.read(bankOffset(2, 5), 4))

        // Focus Stick center press shares GPIO(7,9) active-LOW wired-OR with direction gate
        v6Context.pressedKeys += "focusStickPress"
        assertEquals(0x2060L, mmio.read(bankOffset(7, 5), 4))
        v6Context.pressedKeys += "up"
        assertEquals(0x2060L, mmio.read(bankOffset(7, 5), 4))
        v6Context.pressedKeys -= "focusStickPress"
        assertEquals(0x2060L, mmio.read(bankOffset(7, 5), 4))
        v6Context.pressedKeys -= "up"
        assertEquals(0x2260L, mmio.read(bankOffset(7, 5), 4))

        // Front Command Dial quadrature CW (+4 detents: 00 -> 10 -> 11 -> 01 -> 00 on GPIO(1,2) & GPIO(1,3))
        fun readFrontPhase(): Int {
            val word = mmio.read(bankOffset(1, 5), 4)
            val a = ((word ushr 2) and 1L).toInt()
            val b = ((word ushr 3) and 1L).toInt()
            return (a shl 1) or b
        }
        assertEquals(0, readFrontPhase())
        v6Context.currentTick = 1_000_000L
        v6Gpio.applySettings(mapOf("frontDialDetents" to 4L))
        // Step 1 immediate: 00 -> 10 (phase 2)
        assertEquals(2, readFrontPhase())
        assertEquals(1_000_000L + GpioFactory.FRONT_DIAL_STEP_TICKS, tickAware.nextDeadline())

        // Snapshot mid-rotation and verify restored component produces identical remaining phases
        val midSnap = simulateJsonRoundTrip(v6Gpio.saveSnapshot())
        val restoredContext = GpioContext().apply { currentTick = 1_000_000L }
        val restoredGpio = createGpio(restoredContext, selectedProfile = "gfx100s-2.13-gpio-buttons-v6")
        restoredGpio.start()
        restoredGpio.restoreSnapshot(midSnap)
        val restoredMmio = restoredGpio as MmioDevice
        val restoredTickAware = restoredGpio as TickAwareMmioDevice

        val expectedPhases = listOf(3, 1, 0)
        for (expected in expectedPhases) {
            val nextAt = requireNotNull(tickAware.nextDeadline())
            assertEquals(nextAt, restoredTickAware.nextDeadline())
            v6Context.currentTick = nextAt
            restoredContext.currentTick = nextAt
            tickAware.beforeInstruction(nextAt)
            restoredTickAware.beforeInstruction(nextAt)
            assertEquals(expected, readFrontPhase())
            val rWord = restoredMmio.read(bankOffset(1, 5), 4)
            val rPhase = ((((rWord ushr 2) and 1L).toInt()) shl 1) or (((rWord ushr 3) and 1L).toInt())
            assertEquals(expected, rPhase)
        }
        // Final hold window clears deadline to null
        val settleAt = requireNotNull(tickAware.nextDeadline())
        v6Context.currentTick = settleAt
        tickAware.beforeInstruction(settleAt)
        assertNull(tickAware.nextDeadline())

        // CCW (-2 detents: 00 -> 01 -> 11)
        v6Gpio.applySettings(mapOf("frontDialDetents" to 2L))
        assertEquals(1, readFrontPhase())
        val ccwAt = requireNotNull(tickAware.nextDeadline())
        v6Context.currentTick = ccwAt
        tickAware.beforeInstruction(ccwAt)
        assertEquals(3, readFrontPhase())

        // Downgrade from v6 snapshot into v5 component is strictly refused
        val v6Snap = simulateJsonRoundTrip(v6Gpio.saveSnapshot())
        assertFailsWith<IllegalArgumentException> { v5Gpio.restoreSnapshot(v6Snap) }

        v5Gpio.close()
        v6Gpio.close()
        restoredGpio.close()
    }

    @Test
    fun threeWayFocusModeLeverDrivesBank7Pins13And14AndRoundTripsSnapshot() {
        val ctx = GpioContext()
        val gpio = createGpio(ctx, selectedProfile = "gfx100s-2.13-gpio-buttons-v6")
        gpio.start()
        gpio.applySettings(mapOf("input7" to 0x2040L))
        val mmio = gpio as MmioDevice

        // Default input7=0x2040 + idle buttons -> AF-S (bit13=1, bit14=0; plus pin5/pin9 idle=0x2260)
        assertEquals("AF-S", gpio.state()["effectiveFocusMode"])
        assertEquals(0x2260L, mmio.read(bankOffset(7, 5), 4))

        // Switch to AF-C -> bit13=1, bit14=1 (0x6260)
        gpio.applySettings(mapOf("focusMode" to "AF-C"))
        assertEquals("AF-C", gpio.state()["effectiveFocusMode"])
        assertEquals(0x6260L, mmio.read(bankOffset(7, 5), 4))

        // Switch to MF -> bit13=0, bit14=1 (0x4260)
        gpio.applySettings(mapOf("focusMode" to "MF"))
        assertEquals("MF", gpio.state()["effectiveFocusMode"])
        assertEquals(0x4260L, mmio.read(bankOffset(7, 5), 4))

        // Snapshot & restore preserves focusMode=MF and Bank 7 pin levels
        val snap = simulateJsonRoundTrip(gpio.saveSnapshot())
        val restoredCtx = GpioContext()
        val restored = createGpio(restoredCtx, selectedProfile = "gfx100s-2.13-gpio-buttons-v6")
        restored.start()
        restored.restoreSnapshot(snap)
        assertEquals("MF", restored.state()["focusMode"])
        assertEquals("MF", restored.state()["effectiveFocusMode"])
        assertEquals(0x4260L, (restored as MmioDevice).read(bankOffset(7, 5), 4))

        // Switch back to AF-S -> bit13=1, bit14=0 (0x2260)
        restored.applySettings(mapOf("focusMode" to "AF-S"))
        assertEquals("AF-S", restored.state()["effectiveFocusMode"])
        assertEquals(0x2260L, restored.read(bankOffset(7, 5), 4))

        gpio.close()
        restored.close()
    }
}
