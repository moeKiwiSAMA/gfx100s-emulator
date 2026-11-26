package com.github.nyabanban.fuji.emulator.components.i2c

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class I2cSnapshotTest {
    private val activeProfile = "byte-i2c-functional-experimental-v1"

    private class SyntheticPeer(val peerAddress7: Int?, initialCells: Map<Int, Int> = emptyMap()) : I2cBusPort {
        val cells = IntArray(256) { offset -> initialCells[offset] ?: -1 }
        var selected = false
        var pointer: Int? = null
        var expectPointer = false
        var ownerName: String? = null

        fun copyStateFrom(other: SyntheticPeer) {
            other.cells.copyInto(cells)
            selected = other.selected
            pointer = other.pointer
            expectPointer = other.expectPointer
        }

        override fun connect(owner: String): I2cConnection {
            check(ownerName == null)
            ownerName = owner
            return object : I2cConnection {
                private var closed = false
                override fun address(byte: Int): Boolean {
                    check(!closed)
                    selected = peerAddress7 != null && peerAddress7 == (byte ushr 1)
                    expectPointer = selected && (byte and 1) == 0
                    return selected
                }
                override fun checkWrite(byte: Int) {
                    check(!closed)
                    val slot = if (expectPointer) byte else pointer
                    require(selected && slot != null && cells[slot] >= 0)
                }
                override fun write(byte: Int) {
                    checkWrite(byte)
                    if (expectPointer) {
                        pointer = byte
                        expectPointer = false
                    } else {
                        val currentPointer = checkNotNull(pointer)
                        cells[currentPointer] = byte
                        pointer = (currentPointer + 1) and 255
                    }
                }
                override fun peekRead(): Int {
                    check(!closed)
                    val currentPointer = pointer
                    require(selected && currentPointer != null && cells[currentPointer] >= 0)
                    return cells[currentPointer]
                }
                override fun consumeRead(expected: Int) {
                    check(!closed)
                    require(peekRead() == expected)
                    pointer = (checkNotNull(pointer) + 1) and 255
                }
                override fun stop() {
                    check(!closed)
                    selected = false
                    expectPointer = false
                }
                override fun close() {
                    if (!closed) {
                        stop()
                        closed = true
                        ownerName = null
                    }
                }
            }
        }
    }

    private inner class TestRig(controllerIndices: List<Int> = listOf(0, 1, 2), eyeIrq: Int? = null) : AutoCloseable {
        var currentTick = 0L
        val irqLevels = hashMapOf<Int, Boolean>()
        val registeredPorts = hashMapOf<String, Any>()
        val busPeers = mapOf(
            0 to SyntheticPeer(0x24, mapOf(0 to 0x11, 1 to 0x22, 2 to 0x33)),
            1 to SyntheticPeer(0x3a, mapOf(5 to 0x77, 6 to 0x88)),
            2 to SyntheticPeer(null),
        )
        val component: StatefulComponent

        private val controllerData = linkedMapOf<String, Any?>(
            "profile" to activeProfile,
            "controllers" to controllerIndices.map { controllerIndex ->
                linkedMapOf(
                    "index" to controllerIndex,
                    "irq" to (if (controllerIndex == 1 && eyeIrq != null) eyeIrq else 160 + controllerIndex),
                    "interruptPort" to (if (eyeIrq != null) "soc-gic.lines" else "soc-gic.spi"),
                    "busPort" to "i2c-bus.bus$controllerIndex",
                    "byte_ticks" to (8L + controllerIndex * 4L),
                    "reset" to mapOf("ccr" to 0, "adr" to 0, "csr" to 7, "fsr" to 12),
                )
            },
        )

        private val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-soc-i2c-snapshot-test")
            override val clock = EmulationClock { currentTick }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) = error("unexpected event")
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    check(registeredPorts.putIfAbsent(name, port) == null)
                }
                override fun <T : Any> find(name: String, type: Class<T>): T? = registeredPorts[name]?.let(type::cast)
                override fun removeOwner(owner: String) = error("not used")
            }
            override fun dataPath(relative: String): Path = directory.resolve(relative)
            override fun readData(relative: String): Map<String, Any?> = controllerData
        }

        init {
            registeredPorts[if (eyeIrq != null) "soc-gic.lines" else "soc-gic.spi"] = object : InterruptSink {
                override fun setLine(irq: Int, high: Boolean) {
                    irqLevels[irq] = high
                }
            }
            for ((busIndex, peer) in busPeers) {
                registeredPorts["i2c-bus.bus$busIndex"] = peer
            }
            val factory = I2cFactory()
            val defaults = factory.descriptor.settings.associate { setting -> setting.key to setting.default }
            component = assertIs<StatefulComponent>(
                factory.create(
                    context,
                    defaults + mapOf("profile" to activeProfile, "inputFile" to "i2c-${eyeIrq ?: controllerIndices.size}.json"),
                ),
            )
            component.start()
        }

        fun controller(index: Int): ScheduledMmioDevice =
            registeredPorts.getValue("soc-i2c.controller$index") as ScheduledMmioDevice

        fun stepAll(deltaTicks: Long) {
            currentTick += deltaTicks
            for (index in listOf(0, 1, 2)) {
                val port = registeredPorts["soc-i2c.controller$index"] as? ScheduledMmioDevice ?: continue
                port.beforeInstruction(currentTick)
            }
        }

        override fun close() {
            component.close()
        }
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
    fun threeControllersMidTransactionIrqAndBoundedEventsRoundTripEquivalence() {
        TestRig().use { directRig ->
            val ctrl0 = directRig.controller(0)
            val ctrl1 = directRig.controller(1)
            val ctrl2 = directRig.controller(2)

            ctrl0.write(8L, 1, 0x20L)
            ctrl1.write(8L, 1, 0x20L)
            ctrl2.write(8L, 1, 0x20L)

            repeat(140) {
                ctrl2.write(16L, 1, (0x50L shl 1))
                ctrl2.write(4L, 1, 0x30L)
                directRig.stepAll(16L)
                ctrl2.write(4L, 1, 0x00L)
                directRig.stepAll(16L)
            }
            ctrl2.write(8L, 1, 0x00L)
            ctrl2.write(28L, 1, 0x02L)

            ctrl0.write(16L, 1, (0x24L shl 1))
            ctrl0.write(4L, 1, 0x32L)
            directRig.stepAll(8L)
            ctrl0.write(16L, 1, 0x00L)
            ctrl0.write(4L, 1, 0x12L)
            directRig.stepAll(8L)
            ctrl0.write(16L, 1, 0x9aL)
            ctrl0.write(4L, 1, 0x12L)
            directRig.stepAll(3L)

            ctrl1.write(16L, 1, (0x3aL shl 1))
            ctrl1.write(4L, 1, 0x32L)
            directRig.stepAll(12L)
            ctrl1.write(16L, 1, 0x05L)
            ctrl1.write(4L, 1, 0x12L)
            directRig.stepAll(12L)
            ctrl1.write(16L, 1, (0x3aL shl 1) or 1L)
            ctrl1.write(4L, 1, 0x32L)
            directRig.stepAll(12L)
            ctrl1.write(4L, 1, 0x1aL)
            directRig.stepAll(12L)

            assertEquals(true, directRig.irqLevels[161])
            val roundTrippedSnapshot = simulateJsonRoundTrip(directRig.component.saveSnapshot())

            TestRig().use { restoredRig ->
                restoredRig.currentTick = directRig.currentTick
                for (busIndex in 0..2) {
                    restoredRig.busPeers.getValue(busIndex).copyStateFrom(directRig.busPeers.getValue(busIndex))
                }
                restoredRig.component.restoreSnapshot(roundTrippedSnapshot)

                assertEquals(directRig.component.state(), restoredRig.component.state())
                assertEquals(directRig.component.saveSnapshot(), restoredRig.component.saveSnapshot())
                assertEquals(directRig.irqLevels, restoredRig.irqLevels)
                assertEquals(directRig.controller(0).nextDeadline(), restoredRig.controller(0).nextDeadline())

                fun runContinuation(rig: TestRig): List<Long> {
                    val observed = ArrayList<Long>()
                    val c0 = rig.controller(0)
                    val c1 = rig.controller(1)
                    val c2 = rig.controller(2)

                    rig.stepAll(8L)
                    c0.write(16L, 1, (0x24L shl 1) or 1L)
                    c0.write(4L, 1, 0x32L)
                    rig.stepAll(8L)
                    c0.write(4L, 1, 0x12L)
                    rig.stepAll(8L)
                    observed += c0.read(16L, 1)
                    c0.write(4L, 1, 0x00L)
                    rig.stepAll(8L)

                    observed += c1.read(16L, 1)
                    c1.write(4L, 1, 0x12L)
                    rig.stepAll(12L)
                    observed += c1.read(16L, 1)
                    c1.write(4L, 1, 0x00L)
                    rig.stepAll(12L)

                    observed += c2.read(28L, 1)
                    c2.write(28L, 1, 0x00L)
                    c2.write(8L, 1, 0x20L)
                    c2.write(16L, 1, (0x50L shl 1))
                    c2.write(4L, 1, 0x32L)
                    rig.stepAll(16L)
                    observed += c2.read(0L, 1)
                    c2.write(4L, 1, 0x00L)
                    rig.stepAll(16L)
                    return observed
                }

                val directReads = runContinuation(directRig)
                val restoredReads = runContinuation(restoredRig)
                assertEquals(directReads, restoredReads)
                assertEquals(directRig.component.state(), restoredRig.component.state())
                assertEquals(directRig.irqLevels, restoredRig.irqLevels)
            }
        }
    }

    @Test
    fun route66CanReplaceOnlyTheIdleLabRoute() {
        TestRig(listOf(1),511).use { lab ->
            TestRig(listOf(1),66).use { selected ->
                val idle = lab.component.saveSnapshot()
                selected.component.restoreSnapshot(idle)
                val saved = selected.component.saveSnapshot()
                assertEquals(66, ((saved["controllers"] as List<*>).single() as Map<*,*>)["irq"])
                TestRig(listOf(1),67).use { wrong ->
                    assertFailsWith<IllegalArgumentException> { wrong.component.restoreSnapshot(idle) }
                }
                val device = lab.controller(1)
                device.write(8,1,0x20)
                device.write(16,1,0x74)
                device.write(4,1,0x32)
                assertFailsWith<IllegalArgumentException> { selected.component.restoreSnapshot(lab.component.saveSnapshot()) }
                lab.stepAll(12)
                assertFailsWith<IllegalArgumentException> { selected.component.restoreSnapshot(lab.component.saveSnapshot()) }
                assertEquals(saved, selected.component.saveSnapshot())
            }
        }
    }

    @Test
    fun route66CanReplaceNacked0x39ProbeStateFromSnap1200kWithoutAdmitting0x3aNack() {
        TestRig(listOf(1), 511).use { lab ->
            val dev = lab.controller(1)
            dev.write(8, 1, 0x20)
            // Probe 0x39 (dar = 0x72): SyntheticPeer is 0x3a so 0x39 NACKs cleanly
            dev.write(16, 1, 0x72)
            dev.write(4, 1, 0x32)
            lab.stepAll(12)
            val nacked0x39 = lab.component.saveSnapshot()
            assertEquals("nacked", ((nacked0x39["controllers"] as List<*>).single() as Map<*, *>)["phase"])

            TestRig(listOf(1), 66).use { selected ->
                selected.component.restoreSnapshot(nacked0x39)
                val restoredCtrl = (selected.component.saveSnapshot()["controllers"] as List<*>).single() as Map<*, *>
                assertEquals(66, restoredCtrl["irq"])
                assertEquals("nacked", restoredCtrl["phase"])
                assertEquals(0x72, restoredCtrl["dar"])
                assertEquals(true, selected.irqLevels[66])

                // Wrong IRQ (67) must still reject
                TestRig(listOf(1), 67).use { wrong ->
                    assertFailsWith<IllegalArgumentException> { wrong.component.restoreSnapshot(nacked0x39) }
                }

                // A different nacked address (0x1c -> dar = 0x38) must still reject
                dev.write(4, 1, 0x00)
                lab.stepAll(12)
                dev.write(16, 1, 0x38)
                dev.write(4, 1, 0x32)
                lab.stepAll(12)
                assertFailsWith<IllegalArgumentException> { selected.component.restoreSnapshot(lab.component.saveSnapshot()) }
            }
        }
    }

    @Test
    fun compatibleControllerExpansionAndAtomicInvalidSnapshotRejection() {
        TestRig(listOf(0, 1)).use { twoControllerRig ->
            twoControllerRig.controller(0).write(8L, 1, 0x20L)
            twoControllerRig.controller(0).write(16L, 1, (0x24L shl 1))
            twoControllerRig.controller(0).write(4L, 1, 0x32L)
            twoControllerRig.stepAll(8L)
            val twoControllerSnap = simulateJsonRoundTrip(twoControllerRig.component.saveSnapshot())

            TestRig(listOf(0, 1, 2)).use { threeControllerRig ->
                threeControllerRig.controller(2).write(28L, 1, 0x03L)
                threeControllerRig.component.restoreSnapshot(twoControllerSnap)
                val controllersState = threeControllerRig.component.state()["controllers"] as List<*>
                assertEquals(3, controllersState.size)
                val ctrl2Regs = ((controllersState[2] as Map<*, *>)["registers"] as Map<*, *>)
                assertEquals(0, ctrl2Regs["lowDrives"])

                val baselineState = threeControllerRig.component.state()
                val baselineSnapshot = threeControllerRig.component.saveSnapshot()
                val baselineIrqs = HashMap(threeControllerRig.irqLevels)

                fun baseValid(): MutableMap<String, Any?> = LinkedHashMap(simulateJsonRoundTrip(baselineSnapshot))

                val invalidMutations = listOf<(MutableMap<String, Any?>) -> Unit>(
                    { snap -> snap["schema"] = 0 },
                    { snap -> snap["componentId"] = "i2c-bus" },
                    { snap -> snap["extra"] = false },
                    { snap ->
                        val list = (snap["controllers"] as List<*>).map { row ->
                            LinkedHashMap(row as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                        }.toMutableList()
                        list[0]["address7"] = null
                        snap["controllers"] = list
                    },
                    { snap ->
                        val list = (snap["controllers"] as List<*>).map { row ->
                            LinkedHashMap(row as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                        }.toMutableList()
                        list[0]["ccr"] = 200
                        snap["controllers"] = list
                    },
                    { snap ->
                        val list = (snap["controllers"] as List<*>).map { row ->
                            LinkedHashMap(row as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                        }.toMutableList()
                        list[1]["eventCount"] = 999
                        snap["controllers"] = list
                    },
                )

                for (mutate in invalidMutations) {
                    val candidate = baseValid()
                    mutate(candidate)
                    assertFailsWith<IllegalArgumentException> {
                        threeControllerRig.component.restoreSnapshot(candidate)
                    }
                    assertEquals(baselineState, threeControllerRig.component.state())
                    assertEquals(baselineSnapshot, threeControllerRig.component.saveSnapshot())
                    assertEquals(baselineIrqs, threeControllerRig.irqLevels)
                }
            }
        }
    }
}
