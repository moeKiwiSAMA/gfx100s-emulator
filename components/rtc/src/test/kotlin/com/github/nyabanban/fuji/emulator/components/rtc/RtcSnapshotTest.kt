package com.github.nyabanban.fuji.emulator.components.rtc

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.components.powerpeer.PowerPeerFactory
import com.github.nyabanban.fuji.emulator.components.spi.SpiFactory
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class RtcSnapshotTest {
    private val scriptedProfile = "gfx100s-rtc-scripted-stage-read-experimental-v2"
    private val legacyProfile = "gfx100s-rtc-functional-experimental-v1"

    private class StandaloneContext(val rtcData: Map<String, Any?>) : ComponentContext {
        var currentTick = 0L
        val registeredPorts = hashMapOf<String, Any>()
        override val directory: Path = Path.of("unused-rtc-snapshot-test")
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
        override fun readData(relative: String): Map<String, Any?> = rtcData
    }

    private fun v2InputData(
        lowSamples: List<Int> = listOf(0x1234, 0x2345, 0x3456),
        highSamples: List<Int> = listOf(0xabcd, 0xbcde),
    ): Map<String, Any?> = linkedMapOf(
        "schema" to 2,
        "profile" to scriptedProfile,
        "ticksPerSecond" to 10L,
        "initialSeconds" to 100L,
        "initialControl" to 3,
        "counterRunning" to true,
        "loadDelayTicks" to 5L,
        "stageReadInputs" to linkedMapOf("29" to lowSamples, "30" to highSamples),
    )

    private fun createRtc(
        context: StandaloneContext,
        selectedProfile: String = scriptedProfile,
        inputFile: String = "rtc.json",
    ): StatefulComponent {
        val factory = RtcFactory()
        val defaults = factory.descriptor.settings.associate { setting -> setting.key to setting.default }
        return assertIs<StatefulComponent>(
            factory.create(context, defaults + mapOf("profile" to selectedProfile, "inputFile" to inputFile)),
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
    fun scriptedV2AndDelayedStagedLoadRoundTripEquivalence() {
        val directContext = StandaloneContext(v2InputData())
        val direct = createRtc(directContext)
        val directPort = directContext.registeredPorts.getValue("rtc.registers") as PowerRegisterPort
        val directConn = directPort.connect("owner") as PreparedPowerRegisterConnection

        directConn.write(0x1d, 0x0010, 2L)
        directConn.write(0x1e, 0x0001, 3L)
        directConn.write(0x27, 0x0203, 4L)

        val cancelledLease = directConn.prepareRead(29, 10L)
        assertEquals(0x1234, cancelledLease.value)
        cancelledLease.cancel()

        val committedLow = directConn.prepareRead(29, 11L)
        assertEquals(0x1234, committedLow.value)
        committedLow.commit(12L)

        val counterLease = directConn.prepareRead(0x23, 19L)
        counterLease.commit(20L)

        directConn.write(0x1d, 0x8888, 21L)
        directConn.write(0x1e, 0x0002, 22L)
        directConn.write(0x27, 0x0203, 23L)

        val pendingHigh = directConn.prepareRead(30, 24L)
        assertEquals(0xabcd, pendingHigh.value)
        directContext.currentTick = 25L

        val roundTrippedSnapshot = simulateJsonRoundTrip(direct.saveSnapshot())

        val restoredContext = StandaloneContext(v2InputData())
        restoredContext.currentTick = 25L
        val restored = createRtc(restoredContext)
        val restoredPort = restoredContext.registeredPorts.getValue("rtc.registers") as PowerRegisterPort
        val restoredConn = restoredPort.connect("owner") as PreparedPowerRegisterConnection
        restored.restoreSnapshot(roundTrippedSnapshot)

        assertEquals(direct.state(), restored.state())
        assertEquals(direct.saveSnapshot(), restored.saveSnapshot())

        val reclaimedHigh = restoredConn.prepareRead(30, 24L)
        assertEquals(0xabcd, reclaimedHigh.value)

        pendingHigh.commit(26L)
        reclaimedHigh.commit(26L)

        for (probeTick in listOf(26L, 27L, 28L, 38L)) {
            directContext.currentTick = probeTick
            restoredContext.currentTick = probeTick
            assertEquals(directConn.read(0x23, probeTick), restoredConn.read(0x23, probeTick))
            assertEquals(directConn.read(0x24, probeTick), restoredConn.read(0x24, probeTick))
            assertEquals(directConn.read(0x27, probeTick), restoredConn.read(0x27, probeTick))
            assertEquals(direct.state(), restored.state())
        }

        directConn.prepareRead(29, 40L).also { lease ->
            assertEquals(0x2345, lease.value)
            lease.commit(41L)
        }
        restoredConn.prepareRead(29, 40L).also { lease ->
            assertEquals(0x2345, lease.value)
            lease.commit(41L)
        }
        directContext.currentTick = 42L
        restoredContext.currentTick = 42L
        assertEquals(direct.state(), restored.state())
        assertEquals(direct.saveSnapshot(), restored.saveSnapshot())

        directConn.close()
        restoredConn.close()
        direct.close()
        restored.close()
    }

    private class SpiRtcRig : AutoCloseable {
        var currentTick = 0L
        var csHigh = false
        var csEdge = 0L
        val registeredPorts = hashMapOf<String, Any>()
        val rtc: StatefulComponent
        val peer: StatefulComponent
        val spi: HardwareComponent
        val controller: MmioDevice

        private val rtcData = linkedMapOf<String, Any?>(
            "schema" to 2,
            "profile" to "gfx100s-rtc-scripted-stage-read-experimental-v2",
            "ticksPerSecond" to 10L,
            "initialSeconds" to 0x12345678L,
            "initialControl" to 3,
            "counterRunning" to true,
            "loadDelayTicks" to 5L,
            "stageReadInputs" to linkedMapOf("29" to listOf(0x4321, 0x8765), "30" to listOf(0xfedc)),
        )
        private val peerData = linkedMapOf<String, Any?>(
            "profile" to "checksum-registers-experimental-v1",
            "chip_select" to mapOf("port" to 12, "pin" to 5, "active_high" to true, "idle_high" to false),
            "inputs" to mapOf("0" to 0),
            "retained_registers" to emptyList<Int>(),
            "disable_writes" to emptyList<Int>(),
        )
        private val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-rtc-spi-snapshot-rig")
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
            override fun readData(relative: String): Map<String, Any?> = when (relative) {
                "rtc.json" -> rtcData
                "peer.json" -> peerData
                else -> error("unexpected data file: $relative")
            }
        }

        init {
            registeredPorts["soc-gpio.outputs"] = object : GpioOutputPort {
                override fun outputLevel(port: Int, pin: Int) = csHigh
                override fun outputSequence(port: Int, pin: Int) = csEdge
            }
            fun instantiate(factory: ComponentFactory, overrides: Map<String, Any>): HardwareComponent {
                val defaults = factory.descriptor.settings.associate { setting -> setting.key to setting.default }
                return factory.create(context, defaults + overrides)
            }
            rtc = assertIs<StatefulComponent>(
                instantiate(RtcFactory(), mapOf("profile" to "gfx100s-rtc-scripted-stage-read-experimental-v2", "inputFile" to "rtc.json")),
            )
            peer = assertIs<StatefulComponent>(
                instantiate(
                    PowerPeerFactory(),
                    mapOf(
                        "profile" to "checksum-registers-experimental-v1",
                        "inputFile" to "peer.json",
                        "registerPort" to "rtc.registers",
                    ),
                ),
            )
            peer.start()
            spi = instantiate(SpiFactory(), mapOf("profile" to "spi-fifo-peer-experimental-v2", "peerPort" to "power-peer.spi"))
            controller = registeredPorts.getValue("soc-spi.spi1") as MmioDevice
            controller.write(0, 4, 7)
            controller.write(0x10, 4, 2)
            controller.write(4, 4, 2)
        }

        fun selectChip() {
            if (csHigh) {
                csHigh = false
                csEdge++
            }
            csHigh = true
            csEdge++
        }

        fun exchangeWord(txByte: Int): Int {
            controller.write(8, 4, txByte.toLong())
            currentTick += 16
            controller.beforeInstruction(currentTick)
            return controller.read(8, 4).toInt()
        }

        override fun close() {
            peer.close()
            rtc.close()
            spi.close()
        }
    }

    @Test
    fun crossComponentMidSpiFramePreparedRtcReadRestoresInEitherOrder() {
        SpiRtcRig().use { directRig ->
            directRig.selectChip()
            assertEquals(0, directRig.exchangeWord(29))
            assertEquals(0x43, directRig.exchangeWord(0))

            val rtcSnap = simulateJsonRoundTrip(directRig.rtc.saveSnapshot())
            val peerSnap = simulateJsonRoundTrip(directRig.peer.saveSnapshot())
            val savedTick = directRig.currentTick
            val savedCsHigh = directRig.csHigh
            val savedCsEdge = directRig.csEdge

            SpiRtcRig().use { rtcFirstRig ->
                SpiRtcRig().use { peerFirstRig ->
                    for (targetRig in listOf(rtcFirstRig, peerFirstRig)) {
                        targetRig.currentTick = savedTick
                        targetRig.csHigh = savedCsHigh
                        targetRig.csEdge = savedCsEdge
                    }
                    rtcFirstRig.rtc.restoreSnapshot(rtcSnap)
                    rtcFirstRig.peer.restoreSnapshot(peerSnap)

                    peerFirstRig.peer.restoreSnapshot(peerSnap)
                    peerFirstRig.rtc.restoreSnapshot(rtcSnap)

                    for (targetRig in listOf(rtcFirstRig, peerFirstRig)) {
                        assertEquals(directRig.rtc.state(), targetRig.rtc.state())
                        assertEquals(directRig.peer.state(), targetRig.peer.state())
                    }

                    fun finishAndReadNext(rig: SpiRtcRig): List<Int> {
                        val results = ArrayList<Int>()
                        results += rig.exchangeWord(0)
                        results += rig.exchangeWord(29.inv() and 255)
                        rig.selectChip()
                        for (txByte in listOf(29, 0, 0, 29.inv() and 255)) {
                            results += rig.exchangeWord(txByte)
                        }
                        return results
                    }

                    val directReplies = finishAndReadNext(directRig)
                    val rtcFirstReplies = finishAndReadNext(rtcFirstRig)
                    val peerFirstReplies = finishAndReadNext(peerFirstRig)

                    assertEquals(directReplies, rtcFirstReplies)
                    assertEquals(directReplies, peerFirstReplies)
                    assertEquals(directRig.rtc.state(), rtcFirstRig.rtc.state())
                    assertEquals(directRig.rtc.state(), peerFirstRig.rtc.state())
                    assertEquals(directRig.peer.state(), rtcFirstRig.peer.state())
                    assertEquals(directRig.peer.state(), peerFirstRig.peer.state())
                }
            }
        }
    }

    @Test
    fun compatibleUpgradeAndAtomicInvalidSnapshotRejection() {
        val disabledContext = StandaloneContext(emptyMap())
        val disabledRtc = createRtc(disabledContext, selectedProfile = "disabled", inputFile = "")
        val disabledSnap = simulateJsonRoundTrip(disabledRtc.saveSnapshot())

        val v1Data = linkedMapOf<String, Any?>(
            "schema" to 1,
            "profile" to legacyProfile,
            "ticksPerSecond" to 10L,
            "initialSeconds" to 100L,
            "initialControl" to 3,
            "counterRunning" to true,
            "loadDelayTicks" to 5L,
        )
        val v1Context = StandaloneContext(v1Data)
        val v1Rtc = createRtc(v1Context, selectedProfile = legacyProfile)
        (v1Context.registeredPorts.getValue("rtc.registers") as PowerRegisterPort).connect("owner").use { conn ->
            conn.write(0x1d, 0x2222, 1L)
        }
        val v1Snap = simulateJsonRoundTrip(v1Rtc.saveSnapshot())

        val liveContext = StandaloneContext(v2InputData())
        val liveRtc = createRtc(liveContext)
        val liveConn = (liveContext.registeredPorts.getValue("rtc.registers") as PowerRegisterPort)
            .connect("owner") as PreparedPowerRegisterConnection

        liveRtc.restoreSnapshot(disabledSnap)
        assertNull((liveRtc.state()["registers"] as Map<*, *>)["stageLow"])

        liveContext.currentTick = 1L
        liveRtc.restoreSnapshot(v1Snap)
        assertEquals(0x2222, (liveRtc.state()["registers"] as Map<*, *>)["stageLow"])

        liveConn.prepareRead(29, 5L).commit(6L)
        liveContext.currentTick = 6L
        val baselineState = liveRtc.state()
        val baselineSnapshot = liveRtc.saveSnapshot()

        assertFailsWith<IllegalArgumentException> {
            v1Rtc.restoreSnapshot(simulateJsonRoundTrip(baselineSnapshot))
        }

        fun baseValid(): MutableMap<String, Any?> = LinkedHashMap(simulateJsonRoundTrip(baselineSnapshot))

        val invalidMutations = listOf<(MutableMap<String, Any?>) -> Unit>(
            { snap -> snap["schema"] = 99 },
            { snap -> snap["componentId"] = "power-peer" },
            { snap -> snap["extra"] = 1 },
            { snap ->
                val bankMap = LinkedHashMap(snap["bank"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                bankMap["freshHalves"] = 3
                snap["bank"] = bankMap
            },
            { snap ->
                val bankMap = LinkedHashMap(snap["bank"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                bankMap["loadAt"] = 50
                snap["bank"] = bankMap
            },
            { snap ->
                val scriptMap = LinkedHashMap(snap["scriptedStageReads"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                scriptMap["consumed"] = mapOf("29" to 99, "30" to 0)
                snap["scriptedStageReads"] = scriptMap
            },
            { snap ->
                val scriptMap = LinkedHashMap(snap["scriptedStageReads"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                scriptMap["completedReads"] = 0
                snap["scriptedStageReads"] = scriptMap
            },
        )

        for (mutate in invalidMutations) {
            val candidate = baseValid()
            mutate(candidate)
            assertFailsWith<IllegalArgumentException> {
                liveRtc.restoreSnapshot(candidate)
            }
            assertEquals(baselineState, liveRtc.state())
            assertEquals(baselineSnapshot, liveRtc.saveSnapshot())
        }

        liveConn.close()
        disabledRtc.close()
        v1Rtc.close()
        liveRtc.close()
    }

    @Test
    fun legacyTwoKeyScalarsSnapshotRestoresCleanly() {
        val ctx = StandaloneContext(v2InputData())
        val rtc = createRtc(ctx)
        val snap = LinkedHashMap(simulateJsonRoundTrip(rtc.saveSnapshot()))
        val legacyScalars = LinkedHashMap(snap["scalars"] as Map<*, *>).apply { remove("clockMode") }
        snap["scalars"] = legacyScalars

        val restoredCtx = StandaloneContext(v2InputData())
        val restored = createRtc(restoredCtx)
        restored.restoreSnapshot(snap)
        assertEquals(rtc.state(), restored.state())
        rtc.close()
        restored.close()
    }
}
