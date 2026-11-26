package com.github.nyabanban.fuji.emulator.components.powerpeer

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PowerPeerSnapshotTest {
    private val finiteProfile = "finite-candidate-reads-experimental-v3"
    private val maskedProfile = "masked-retained-registers-experimental-v2"
    private val switchProfile = "gfx100s-2.13-power-switch-v1"

    private class TestContext(
        val inputData: Map<String, Any?>,
        val original: Map<String, Any?> = inputData,
        override val directory: Path = Path.of("unused-power-peer-snapshot-test"),
        override val relaxedMode: Boolean = false,
    ) : ComponentContext {
        var csHigh = false
        var csEdge = 0L
        var powerPressed = false
        val registeredPorts = hashMapOf<String, Any>()
        override val clock = EmulationClock { 0L }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        init {
            registeredPorts["soc-gpio.outputs"] = object : GpioOutputPort {
                override fun outputLevel(port: Int, pin: Int) = csHigh
                override fun outputSequence(port: Int, pin: Int) = csEdge
            }
            registeredPorts["buttons"] = object : ButtonPort {
                override fun pressed(key: String): Boolean {
                    require(key == "power")
                    return powerPressed
                }
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
        override fun readData(relative: String): Map<String, Any?> = if (relative == "input.json") original else inputData
        fun selectChip() {
            if (csHigh) {
                csHigh = false
                csEdge++
            }
            csHigh = true
            csEdge++
        }
        fun deselectChip() {
            if (csHigh) {
                csHigh = false
                csEdge++
            }
        }
    }

    private fun candidateEntry(selector: Int, samples: List<Int>): Map<String, Any> = linkedMapOf(
        "spaceId" to "gfx100s-power-protocol-requests",
        "requestId" to "request.power-peer.selector0x${selector.toString(16).padStart(2, '0')}",
        "selector" to selector,
        "wordBits" to 16,
        "confidence" to "HYPOTHESIS",
        "values" to samples,
    )

    private fun v3InputData(): Map<String, Any?> = linkedMapOf(
        "profile" to finiteProfile,
        "chip_select" to mapOf("port" to 12, "pin" to 5, "active_high" to true, "idle_high" to false),
        "inputs" to linkedMapOf("0" to 0x0400, "2" to 0x0004, "17" to 0x1111),
        "retained_registers" to listOf(17),
        "disable_writes" to listOf(25),
        "write_masks" to linkedMapOf("2" to 0x0104),
        "candidate_reads" to listOf(
            candidateEntry(3, listOf(0x0301, 0x0302, 0x0303)),
            candidateEntry(14, listOf(0x0e01, 0x0e02)),
            candidateEntry(47, listOf(0x2f01, 0x2f02, 0x2f03)),
            candidateEntry(48, listOf(0x3001, 0x3002)),
            candidateEntry(49, listOf(0x3101, 0x3102)),
            candidateEntry(50, listOf(0x3201, 0x3202, 0x3203)),
        ),
    )

    private fun createPeer(
        context: TestContext,
        selectedProfile: String = finiteProfile,
        inputFile: String = "input.json",
        buttonProfile: String = switchProfile,
    ): StatefulComponent {
        val factory = PowerPeerFactory()
        val defaults = factory.descriptor.settings.associate { setting -> setting.key to setting.default }
        val component = factory.create(
            context,
            defaults + mapOf(
                "profile" to selectedProfile,
                "inputFile" to inputFile,
                "powerButtonProfile" to buttonProfile,
            ),
        )
        return assertIs<StatefulComponent>(component)
    }

    private fun spiPort(context: TestContext): SpiPeer =
        checkNotNull(context.ports.find("power-peer.spi", SpiPeer::class.java))

    @Test fun selector03ChecksAll160ConsumedWordsAndCommitsTheFiniteTailOnce() {
        val prefix = List(7) { 0 } + List(153) { 502 }
        val tail = List(160) { 0xff00 + it }
        fun input(samples: List<Int>) = v3InputData() + ("candidate_reads" to
            listOf(candidateEntry(3, samples), candidateEntry(14, listOf(27, 39))))
        val source = TestContext(input(prefix))
        val saved = createPeer(source, inputFile = "input-160.json").use { peer ->
            peer.start()
            transferFrame(source, 14, 0)
            repeat(160) { transferFrame(source, 3, 4L * (it + 1)) }
            peer.saveSnapshot()
        }
        val selected = prefix + tail
        for (index in listOf(0, 7, 83, 159)) {
            val changed = selected.toMutableList().apply { this[index]++ }
            createPeer(TestContext(input(changed)), inputFile = "input-320.json").use { peer ->
                val before = peer.saveSnapshot()
                val failure = assertFailsWith<IllegalArgumentException> { peer.restoreSnapshot(saved) }
                assertContains(failure.message.orEmpty(), "sample prefix differs")
                assertEquals(before, peer.saveSnapshot())
            }
        }
        val resumed = TestContext(input(selected))
        createPeer(resumed, inputFile = "input-320.json").use { peer ->
            peer.start()
            peer.restoreSnapshot(simulateJsonRoundTrip(saved))
            resumed.csEdge = source.csEdge
            resumed.csHigh = true
            val other = transferFrame(resumed, 14, 644)
            assertEquals(39, other[1] shl 8 or other[2])
            tail.forEachIndexed { index, expected ->
                val frame = transferFrame(resumed, 3, 648L + 4 * index)
                assertEquals(expected, frame[1] shl 8 or frame[2])
            }
            val end = peer.saveSnapshot()
            val reads = end["candidateReads"] as Map<*, *>
            assertEquals(selected, reads["selector03Prefix"])
            assertEquals("power peer finite candidate input exhausted register3",
                assertFailsWith<IllegalStateException> { spiPort(resumed).exchange(8, 3, 1288) }.message)
            assertEquals(end, peer.saveSnapshot())
        }
    }

    @Test fun exhaustedLegacyBatteryRestoresThroughOneExplicitGroupAndRefusesChangedHistory() {
        fun input(samples: List<Int>) = v3InputData() + ("candidate_reads" to listOf(candidateEntry(3, samples)))
        val samples = List(128) { 400 + it }
        val original = input(samples.take(96))
        val first = TestContext(original)
        val legacy = createPeer(first).use { peer ->
            peer.start()
            repeat(96) { index -> transferFrame(first, 3, 4L * index) }
            peer.saveSnapshot()
        }
        val next = TestContext(input(samples), original)
        val extended = createPeer(next, inputFile = "extended.json").use { peer ->
            peer.start()
            peer.restoreSnapshot(simulateJsonRoundTrip(legacy))
            next.csEdge = first.csEdge
            next.csHigh = true
            for (index in 96 until 128) {
                val response = transferFrame(next, 3, 4L * index)
                assertEquals(samples[index], response[1] shl 8 or response[2])
            }
            val before = peer.saveSnapshot()
            assertEquals("power peer finite candidate input exhausted register3",
                assertFailsWith<IllegalStateException> { spiPort(next).exchange(8, 3, 512) }.message)
            assertEquals(before, peer.saveSnapshot())
            before
        }
        createPeer(TestContext(input(samples)), inputFile = "extended.json").use { peer ->
            peer.restoreSnapshot(simulateJsonRoundTrip(extended))
            assertEquals(extended, peer.saveSnapshot())
        }
        val tail = List(32) { 800 + it }
        val continued = TestContext(input(samples + tail))
        createPeer(continued, inputFile = "extended.json").use { peer ->
            peer.start()
            peer.restoreSnapshot(simulateJsonRoundTrip(extended))
            continued.csEdge = next.csEdge
            continued.csHigh = true
            tail.forEachIndexed { index, expected ->
                val response = transferFrame(continued, 3, 512L + 4L * index)
                assertEquals(expected, response[1] shl 8 or response[2])
            }
            val exhausted = peer.saveSnapshot()
            assertFailsWith<IllegalStateException> { spiPort(continued).exchange(8, 3, 640) }
            assertEquals(exhausted, peer.saveSnapshot())
        }
        for ((snapshot, changedIndex) in listOf(legacy to 0, extended to 96)) {
            val changed = samples.toMutableList().apply { this[changedIndex]++ }
            createPeer(TestContext(input(changed), original), inputFile = "extended.json").use { peer ->
                val before = peer.saveSnapshot()
                val failure = assertFailsWith<IllegalArgumentException> { peer.restoreSnapshot(snapshot) }
                assertContains(failure.message.orEmpty(), "sample prefix differs")
                assertEquals(before, peer.saveSnapshot())
            }
        }
    }

    private fun frameBytes(selector: Int, wordValue: Int = 0, write: Boolean = false): List<Int> {
        val header = selector or if (write) 128 else 0
        val prefix = listOf(header, wordValue ushr 8, wordValue and 255)
        return prefix + (prefix.sum().inv() and 255)
    }

    private fun transferFrame(
        context: TestContext,
        selector: Int,
        startTick: Long,
        wordValue: Int = 0,
        write: Boolean = false,
    ): List<Int> {
        context.selectChip()
        val peer = spiPort(context)
        return frameBytes(selector, wordValue, write).mapIndexed { byteOffset, txByte ->
            peer.exchange(8, txByte, startTick + byteOffset)
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
    fun v3StoredBit8CandidateCursorsBoundedLogsAndMidFrameLeaseRoundTripEquivalence() {
        val directContext = TestContext(v3InputData())
        val direct = createPeer(directContext)
        direct.start()
        var tick = 10L

        transferFrame(directContext, selector = 2, startTick = tick, wordValue = 0x0104, write = true)
        tick += 4
        transferFrame(directContext, selector = 17, startTick = tick, wordValue = 0xbeef, write = true)
        tick += 4
        transferFrame(directContext, selector = 25, startTick = tick, wordValue = 0x0000, write = true)
        tick += 4

        assertEquals(0x0301, transferFrame(directContext, 3, tick).let { reply -> (reply[1] shl 8) or reply[2] })
        tick += 4
        assertEquals(0x0302, transferFrame(directContext, 3, tick).let { reply -> (reply[1] shl 8) or reply[2] })
        tick += 4
        assertEquals(0x0e01, transferFrame(directContext, 14, tick).let { reply -> (reply[1] shl 8) or reply[2] })
        tick += 4
        assertEquals(0x2f01, transferFrame(directContext, 47, tick).let { reply -> (reply[1] shl 8) or reply[2] })
        tick += 4
        assertEquals(0x3001, transferFrame(directContext, 48, tick).let { reply -> (reply[1] shl 8) or reply[2] })
        tick += 4
        assertEquals(0x3101, transferFrame(directContext, 49, tick).let { reply -> (reply[1] shl 8) or reply[2] })
        tick += 4
        assertEquals(0x3201, transferFrame(directContext, 50, tick).let { reply -> (reply[1] shl 8) or reply[2] })
        tick += 4
        assertEquals(0x3202, transferFrame(directContext, 50, tick).let { reply -> (reply[1] shl 8) or reply[2] })
        tick += 4

        for (iteration in 0 until 260) {
            directContext.powerPressed = (iteration % 2 == 0)
            transferFrame(directContext, selector = 0, startTick = tick)
            tick += 4
        }

        directContext.deselectChip()
        spiPort(directContext).exchange(8, 0xaa, tick++)
        directContext.selectChip()
        val abortedBytes = frameBytes(2, 0x0000, write = true)
        spiPort(directContext).exchange(8, abortedBytes[0], tick++)
        spiPort(directContext).exchange(8, abortedBytes[1], tick++)

        directContext.selectChip()
        val midReadBytes = frameBytes(47)
        assertEquals(0, spiPort(directContext).exchange(8, midReadBytes[0], tick++))
        assertEquals(0x2f, spiPort(directContext).exchange(8, midReadBytes[1], tick++))

        assertEquals(0x0104, (direct.state()["stored"] as Map<*, *>)["2"])
        assertEquals("prepared", direct.state()["preparedReadState"])
        assertEquals(true, direct.state()["eventLogTruncated"])
        assertEquals(true, direct.state()["wordLogTruncated"])

        val roundTrippedSnapshot = simulateJsonRoundTrip(direct.saveSnapshot())

        val restoredContext = TestContext(v3InputData())
        restoredContext.csHigh = directContext.csHigh
        restoredContext.csEdge = directContext.csEdge
        restoredContext.powerPressed = directContext.powerPressed
        val restored = createPeer(restoredContext)
        restored.start()
        restored.restoreSnapshot(roundTrippedSnapshot)

        assertEquals(direct.state(), restored.state())
        assertEquals(direct.saveSnapshot(), restored.saveSnapshot())

        fun runPhaseTwo(context: TestContext, baseTick: Long): List<Int> {
            val outputs = ArrayList<Int>()
            val spi = spiPort(context)
            var localTick = baseTick
            outputs += spi.exchange(8, midReadBytes[2], localTick++)
            outputs += spi.exchange(8, midReadBytes[3], localTick++)
            outputs += transferFrame(context, selector = 3, startTick = localTick)
            localTick += 4
            outputs += transferFrame(context, selector = 14, startTick = localTick)
            localTick += 4
            outputs += transferFrame(context, selector = 47, startTick = localTick)
            localTick += 4
            outputs += transferFrame(context, selector = 48, startTick = localTick)
            localTick += 4
            outputs += transferFrame(context, selector = 49, startTick = localTick)
            localTick += 4
            outputs += transferFrame(context, selector = 50, startTick = localTick)
            localTick += 4
            outputs += transferFrame(context, selector = 2, startTick = localTick, wordValue = 0x0100, write = true)
            localTick += 4
            outputs += transferFrame(context, selector = 2, startTick = localTick)
            localTick += 4
            outputs += transferFrame(context, selector = 17, startTick = localTick)
            localTick += 4
            context.powerPressed = true
            outputs += transferFrame(context, selector = 0, startTick = localTick)
            return outputs
        }

        val directOutputs = runPhaseTwo(directContext, tick)
        val restoredOutputs = runPhaseTwo(restoredContext, tick)
        assertEquals(directOutputs, restoredOutputs)
        assertEquals(direct.state(), restored.state())
        assertEquals(direct.saveSnapshot(), restored.saveSnapshot())

        direct.close()
        restored.close()
    }

    @Test
    fun compatibleProfileUpgradeFromDisabledAndV2PreservesInvariants() {
        val disabledContext = TestContext(emptyMap())
        val disabledPeer = createPeer(
            disabledContext,
            selectedProfile = "disabled",
            inputFile = "",
            buttonProfile = "disabled",
        )
        val disabledSnap = simulateJsonRoundTrip(disabledPeer.saveSnapshot())

        val promotedContext = TestContext(v3InputData())
        val promotedPeer = createPeer(promotedContext)
        promotedPeer.start()
        transferFrame(promotedContext, selector = 2, startTick = 1L, wordValue = 0x0104, write = true)
        transferFrame(promotedContext, selector = 3, startTick = 10L)
        promotedPeer.restoreSnapshot(disabledSnap)
        assertEquals(0x0004, (promotedPeer.state()["stored"] as Map<*, *>)["2"])
        assertEquals(0L, promotedPeer.state()["eventCount"])
        assertEquals(0x0301, transferFrame(promotedContext, 3, 20L).let { reply -> (reply[1] shl 8) or reply[2] })

        val v2Input = linkedMapOf<String, Any?>(
            "profile" to maskedProfile,
            "chip_select" to mapOf("port" to 12, "pin" to 5, "active_high" to true, "idle_high" to false),
            "inputs" to linkedMapOf("2" to 0x0004, "17" to 0x1111),
            "retained_registers" to listOf(17),
            "disable_writes" to listOf(25),
            "write_masks" to linkedMapOf("2" to 0x0004),
        )
        val v2Context = TestContext(v2Input)
        val v2Peer = createPeer(v2Context, selectedProfile = maskedProfile, buttonProfile = "disabled")
        v2Peer.start()
        transferFrame(v2Context, selector = 2, startTick = 1L, wordValue = 0x0000, write = true)
        val v2Snap = simulateJsonRoundTrip(v2Peer.saveSnapshot())

        promotedPeer.restoreSnapshot(v2Snap)
        assertEquals(0x0000, (promotedPeer.state()["stored"] as Map<*, *>)["2"])
        assertEquals(0x1111, (promotedPeer.state()["stored"] as Map<*, *>)["17"])

        disabledPeer.close()
        v2Peer.close()
        promotedPeer.close()
    }

    @Test
    fun invalidSnapshotsAreRejectedAtomicallyWithoutMutatingLiveState() {
        val context = TestContext(v3InputData())
        val peer = createPeer(context)
        peer.start()
        transferFrame(context, selector = 2, startTick = 1L, wordValue = 0x0104, write = true)
        transferFrame(context, selector = 3, startTick = 5L)
        context.selectChip()
        val headerBytes = frameBytes(47)
        spiPort(context).exchange(8, headerBytes[0], 9L)
        spiPort(context).exchange(8, headerBytes[1], 10L)

        val baselineState = peer.state()
        val baselineSnapshot = peer.saveSnapshot()

        fun baseValid(): MutableMap<String, Any?> = LinkedHashMap(simulateJsonRoundTrip(baselineSnapshot))

        val mutations = listOf<(MutableMap<String, Any?>) -> Unit>(
            { snap -> snap["schema"] = 2 },
            { snap -> snap["componentId"] = "rtc" },
            { snap -> snap["unexpected"] = true },
            { snap -> snap.remove("wordsLog") },
            { snap -> snap["phase"] = 4 },
            { snap -> snap["phase"] = 0 },
            { snap -> snap["frame"] = listOf(47, 0, 0) },
            { snap -> snap["selectedWordCount"] = 99999 },
            { snap ->
                val storedMap = LinkedHashMap(snap["stored"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                storedMap["2"] = 0xffff
                snap["stored"] = storedMap
            },
            { snap ->
                val storedMap = LinkedHashMap(snap["stored"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                storedMap["3"] = 0
                snap["stored"] = storedMap
            },
            { snap ->
                val candMap = LinkedHashMap(snap["candidateReads"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                val posMap = LinkedHashMap(candMap["positions"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                posMap["3"] = 99
                candMap["positions"] = posMap
                snap["candidateReads"] = candMap
            },
            { snap ->
                val scalarsMap = LinkedHashMap(snap["scalars"] as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                scalarsMap["gpioPort"] = "other.gpio"
                snap["scalars"] = scalarsMap
            },
            { snap -> snap["eventCount"] = 1.5 },
        )

        for (mutate in mutations) {
            val candidate = baseValid()
            mutate(candidate)
            assertFailsWith<IllegalArgumentException> {
                peer.restoreSnapshot(candidate)
            }
            assertEquals(baselineState, peer.state())
            assertEquals(baselineSnapshot, peer.saveSnapshot())
        }

        assertEquals(0x01, spiPort(context).exchange(8, headerBytes[2], 11L))
        assertEquals((47 + 0x2f + 0x01).inv() and 255, spiPort(context).exchange(8, headerBytes[3], 12L))
        peer.close()
    }

    @Test
    fun repeatLastCandidateReadPromotesRoundTripsAfterRepeatAndRefusesDemotion() {
        val finiteCtx = TestContext(v3InputData())
        val finitePeer = createPeer(finiteCtx)
        finitePeer.start()
        // Exhaust selector 3 (3 entries: 0x0301, 0x0302, 0x0303)
        assertEquals(0x0301, transferFrame(finiteCtx, selector = 3, startTick = 1L).let { (it[1] shl 8) or it[2] })
        assertEquals(0x0302, transferFrame(finiteCtx, selector = 3, startTick = 10L).let { (it[1] shl 8) or it[2] })
        assertEquals(0x0303, transferFrame(finiteCtx, selector = 3, startTick = 20L).let { (it[1] shl 8) or it[2] })
        val exhaustedSnap = simulateJsonRoundTrip(finitePeer.saveSnapshot())

        fun createRepeatable(ctx: TestContext): StatefulComponent = assertIs(
            PowerPeerFactory().create(
                ctx,
                mapOf(
                    "profile" to "finite-candidate-reads-experimental-v3",
                    "inputFile" to "v3-input.json",
                    "gpioPort" to "soc-gpio.outputs",
                    "registerPort" to "",
                    "powerButtonProfile" to "gfx100s-2.13-power-switch-v1",
                    "repeatLastCandidateRead" to true,
                ),
            ),
        )

        val promoCtx = TestContext(v3InputData())
        val promoPeer = createRepeatable(promoCtx)
        promoPeer.start()
        promoPeer.restoreSnapshot(exhaustedSnap)
        promoCtx.csEdge = finiteCtx.csEdge
        // Repeat beyond declared size (committedReads > consumedSum)
        assertEquals(0x0303, transferFrame(promoCtx, selector = 3, startTick = 30L).let { (it[1] shl 8) or it[2] })
        assertEquals(0x0303, transferFrame(promoCtx, selector = 3, startTick = 40L).let { (it[1] shl 8) or it[2] })
        val repeatedSnap = simulateJsonRoundTrip(promoPeer.saveSnapshot())

        // Round-trip repeated snapshot back into repeatLastCandidateRead=true
        val resumedCtx = TestContext(v3InputData())
        val resumedPeer = createRepeatable(resumedCtx)
        resumedPeer.start()
        resumedPeer.restoreSnapshot(repeatedSnap)
        assertEquals(promoPeer.state(), resumedPeer.state())

        // Demotion true -> false must be rejected
        assertFailsWith<IllegalArgumentException> { finitePeer.restoreSnapshot(repeatedSnap) }

        finitePeer.close()
        promoPeer.close()
        resumedPeer.close()
    }

    @Test
    fun batteryPortProjectsLiveSmartBatteryAndPmicSelectorsAcrossSnapshot() {
        var present = true
        var charge = 100.0
        var voltage = 7400
        val ctx = TestContext(v3InputData())
        ctx.registeredPorts["battery"] = object : BatteryPort {
            override fun chargePercent(): Double = charge
            override fun voltageMillivolts(): Int = voltage
            override fun present(): Boolean = present
        }
        fun createLiveBatteryPeer(c: TestContext): StatefulComponent = assertIs(
            PowerPeerFactory().create(
                c,
                mapOf(
                    "profile" to finiteProfile,
                    "inputFile" to "v3-input.json",
                    "gpioPort" to "soc-gpio.outputs",
                    "registerPort" to "",
                    "powerButtonProfile" to switchProfile,
                    "repeatLastCandidateRead" to true,
                    "batteryPort" to "battery",
                ),
            ),
        )
        fun readWord(c: TestContext, sel: Int, tick: Long): Int =
            transferFrame(c, selector = sel, startTick = tick).let { (it[1] shl 8) or it[2] }

        val peer = createLiveBatteryPeer(ctx)
        peer.start()
        assertEquals("battery", peer.state()["batteryPort"])
        // Healthy 100% / 7400mV
        assertEquals(1, readWord(ctx, 14, 10L))
        assertEquals(626, readWord(ctx, 3, 20L))
        assertEquals(2200, readWord(ctx, 47, 30L))
        assertEquals(2090, readWord(ctx, 48, 40L))
        assertEquals(100 shl 8, readWord(ctx, 49, 50L))
        assertEquals(2982, readWord(ctx, 50, 60L))

        // Low battery 3% / 6200mV -> RC=215 (displayed_pct=3% -> BATT_STS_PREEND), VBAT=524 (>507 cutoff)
        charge = 3.0
        voltage = 6200
        assertEquals(524, readWord(ctx, 3, 70L))
        assertEquals(215, readWord(ctx, 48, 80L))

        // External AC >= 8900mV -> >= 752 (BATT_STS_AC)
        voltage = 9000
        assertTrue(readWord(ctx, 3, 90L) >= 752)

        // Snapshot round-trip preserves batteryPort binding and resumes live reads
        val snap = simulateJsonRoundTrip(peer.saveSnapshot())
        val ctx2 = TestContext(v3InputData())
        ctx2.registeredPorts["battery"] = ctx.registeredPorts["battery"]!!
        val peer2 = createLiveBatteryPeer(ctx2)
        peer2.start()
        peer2.restoreSnapshot(snap)
        ctx2.csEdge = ctx.csEdge
        present = false
        assertEquals(0, readWord(ctx2, 14, 100L))
        assertEquals(0, readWord(ctx2, 3, 110L))
        assertEquals(0, readWord(ctx2, 48, 120L))

        peer.close()
        peer2.close()
    }

    @Test
    fun shutdownWriteToRegister15WithLiveBatteryPresentBit0SucceedsUnderMask0xC001AndRejectsUndeclaredBits() {
        val base = v3InputData()
        val inputs = (base["inputs"] as Map<*, *>).entries.associateTo(LinkedHashMap<String, Any>()) { (k, v) -> k as String to requireNotNull(v) }.apply { put("15", 0) }
        val writeMasks = (base["write_masks"] as Map<*, *>).entries.associateTo(LinkedHashMap<String, Any>()) { (k, v) -> k as String to requireNotNull(v) }.apply { put("15", 49153) }
        val inputWithReg15 = base + mapOf("inputs" to inputs, "write_masks" to writeMasks)

        val ctx = TestContext(inputWithReg15)
        ctx.registeredPorts["battery"] = object : BatteryPort {
            override fun chargePercent(): Double = 100.0
            override fun voltageMillivolts(): Int = 7400
            override fun present(): Boolean = true
        }
        val peer = assertIs<StatefulComponent>(
            PowerPeerFactory().create(
                ctx,
                mapOf(
                    "profile" to finiteProfile,
                    "inputFile" to "v3-input.json",
                    "gpioPort" to "soc-gpio.outputs",
                    "registerPort" to "",
                    "powerButtonProfile" to switchProfile,
                    "repeatLastCandidateRead" to true,
                    "batteryPort" to "battery",
                ),
            ),
        )
        peer.start()

        // DAT s4:0x012edac8..0x012edb0c: reads sel 0x0E (bit 0 = 1 when battery present), then writes sel 0x0F with
        // bit 0 = sel0E.bit0, bit 14 = ~sel00.bit3, bit 15 = ~sel00.bit14 -> 0xC001 (49153) during shutdown/APO!
        val sel0E = transferFrame(ctx, selector = 14, startTick = 10L).let { (it[1] shl 8) or it[2] }
        assertEquals(1, sel0E)

        transferFrame(ctx, selector = 15, startTick = 20L, wordValue = 0xC001, write = true)
        val storedAfterShutdown = (peer.state()["stored"] as Map<*, *>)["15"]
        assertEquals(0xC001, storedAfterShutdown)

        // Writing 0xC000 (battery absent) also succeeds under mask 0xC001
        transferFrame(ctx, selector = 15, startTick = 30L, wordValue = 0xC000, write = true)
        assertEquals(0xC000, (peer.state()["stored"] as Map<*, *>)["15"])

        // Writing undeclared bit 1 (0xC002 / 0xC003) must still fail strictly
        assertFailsWith<IllegalArgumentException> {
            transferFrame(ctx, selector = 15, startTick = 40L, wordValue = 0xC003, write = true)
        }

        // Snapshot round-trip preserves register 15 state cleanly
        transferFrame(ctx, selector = 15, startTick = 50L, wordValue = 0xC001, write = true)
        val snap = simulateJsonRoundTrip(peer.saveSnapshot())
        val ctx2 = TestContext(inputWithReg15)
        ctx2.registeredPorts["battery"] = ctx.registeredPorts["battery"]!!
        val peer2 = assertIs<StatefulComponent>(
            PowerPeerFactory().create(
                ctx2,
                mapOf(
                    "profile" to finiteProfile,
                    "inputFile" to "v3-input.json",
                    "gpioPort" to "soc-gpio.outputs",
                    "registerPort" to "",
                    "powerButtonProfile" to switchProfile,
                    "repeatLastCandidateRead" to true,
                    "batteryPort" to "battery",
                ),
            ),
        )
        peer2.start()
        peer2.restoreSnapshot(snap)
        assertEquals(0xC001, (peer2.state()["stored"] as Map<*, *>)["15"])

        peer.close()
        peer2.close()
    }

    @Test
    fun nvramFilePersistsSelectors31To34AcrossColdRestartInRelaxedModeAndIsIgnoredInStrictMode(@TempDir tempDir: Path) {
        val base = v3InputData()
        val inputs = (base["inputs"] as Map<*, *>).entries.associateTo(LinkedHashMap<String, Any>()) { (k, v) -> k as String to requireNotNull(v) }.apply {
            for (sel in 31..34) put(sel.toString(), 0)
        }
        val writeMasks = (base["write_masks"] as Map<*, *>).entries.associateTo(LinkedHashMap<String, Any>()) { (k, v) -> k as String to requireNotNull(v) }.apply {
            for (sel in 31..34) put(sel.toString(), 65535)
        }
        val inputWithNvram = base + mapOf("inputs" to inputs, "write_masks" to writeMasks)

        fun createPeerWithNvram(ctx: TestContext): StatefulComponent = assertIs(
            PowerPeerFactory().create(
                ctx,
                mapOf(
                    "profile" to finiteProfile,
                    "inputFile" to "v3-input.json",
                    "gpioPort" to "soc-gpio.outputs",
                    "registerPort" to "",
                    "powerButtonProfile" to switchProfile,
                    "repeatLastCandidateRead" to true,
                    "batteryPort" to "",
                    "nvramFile" to "submcu-nvram.json",
                ),
            ),
        )

        val nvramPath = tempDir.resolve("submcu-nvram.json")

        // 1. Strict/discovery mode (relaxedMode=false) MUST NOT create or write submcu-nvram.json
        val strictCtx = TestContext(inputWithNvram, directory = tempDir, relaxedMode = false)
        val strictPeer = createPeerWithNvram(strictCtx)
        strictPeer.start()
        transferFrame(strictCtx, selector = 31, startTick = 10L, wordValue = 0x1234, write = true)
        strictPeer.close()
        assertFalse(Files.exists(nvramPath), "strict/discovery mode must never persist submcu-nvram.json")

        // 2. Relaxed mode (relaxedMode=true) persists selectors 31..34 atomically on change
        val relaxedCtx1 = TestContext(inputWithNvram, directory = tempDir, relaxedMode = true)
        val relaxedPeer1 = createPeerWithNvram(relaxedCtx1)
        relaxedPeer1.start()
        transferFrame(relaxedCtx1, selector = 31, startTick = 10L, wordValue = 0x0102, write = true)
        transferFrame(relaxedCtx1, selector = 32, startTick = 20L, wordValue = 0x0304, write = true)
        transferFrame(relaxedCtx1, selector = 33, startTick = 30L, wordValue = 0x0506, write = true)
        transferFrame(relaxedCtx1, selector = 34, startTick = 40L, wordValue = 0x0708, write = true)
        relaxedPeer1.close()
        assertTrue(Files.isRegularFile(nvramPath), "relaxed mode must persist submcu-nvram.json")

        // 3. Process 2 fresh cold-boot in relaxed mode loads selectors 31..34 from disk with ZERO snapshot
        val relaxedCtx2 = TestContext(inputWithNvram, directory = tempDir, relaxedMode = true)
        val relaxedPeer2 = createPeerWithNvram(relaxedCtx2)
        relaxedPeer2.start()
        assertEquals(0x0102, transferFrame(relaxedCtx2, selector = 31, startTick = 10L).let { (it[1] shl 8) or it[2] })
        assertEquals(0x0304, transferFrame(relaxedCtx2, selector = 32, startTick = 20L).let { (it[1] shl 8) or it[2] })
        assertEquals(0x0506, transferFrame(relaxedCtx2, selector = 33, startTick = 30L).let { (it[1] shl 8) or it[2] })
        assertEquals(0x0708, transferFrame(relaxedCtx2, selector = 34, startTick = 40L).let { (it[1] shl 8) or it[2] })
        relaxedPeer2.close()

        // 4. Strict mode ignores an existing submcu-nvram.json file completely
        val strictCtx2 = TestContext(inputWithNvram, directory = tempDir, relaxedMode = false)
        val strictPeer2 = createPeerWithNvram(strictCtx2)
        strictPeer2.start()
        assertEquals(0, transferFrame(strictCtx2, selector = 31, startTick = 10L).let { (it[1] shl 8) or it[2] })
        assertEquals(0, transferFrame(strictCtx2, selector = 32, startTick = 20L).let { (it[1] shl 8) or it[2] })
        strictPeer2.close()

        // 5. Deleting submcu-nvram.json cleanly factory-resets selectors 31..34 back to 0
        Files.delete(nvramPath)
        val relaxedCtx3 = TestContext(inputWithNvram, directory = tempDir, relaxedMode = true)
        val relaxedPeer3 = createPeerWithNvram(relaxedCtx3)
        relaxedPeer3.start()
        assertEquals(0, transferFrame(relaxedCtx3, selector = 31, startTick = 10L).let { (it[1] shl 8) or it[2] })
        relaxedPeer3.close()
    }

    @Test
    fun nvramBinPersistsFnvrBinaryAcrossColdRestartAcceptsHeaderlessRawDumpAndRejectsCorruption(@TempDir tempDir: Path) {
        val base = v3InputData()
        val inputs = (base["inputs"] as Map<*, *>).entries.associateTo(LinkedHashMap<String, Any>()) { (k, v) -> k as String to requireNotNull(v) }.apply {
            for (sel in 31..34) put(sel.toString(), 0)
        }
        val writeMasks = (base["write_masks"] as Map<*, *>).entries.associateTo(LinkedHashMap<String, Any>()) { (k, v) -> k as String to requireNotNull(v) }.apply {
            for (sel in 31..34) put(sel.toString(), 65535)
        }
        val inputWithNvram = base + mapOf("inputs" to inputs, "write_masks" to writeMasks)

        fun createPeerWithBin(ctx: TestContext): StatefulComponent = assertIs(
            PowerPeerFactory().create(
                ctx,
                mapOf(
                    "profile" to finiteProfile,
                    "inputFile" to "v3-input.json",
                    "gpioPort" to "soc-gpio.outputs",
                    "registerPort" to "",
                    "powerButtonProfile" to switchProfile,
                    "repeatLastCandidateRead" to true,
                    "batteryPort" to "",
                    "nvramFile" to "submcu-nvram.bin",
                ),
            ),
        )

        val binPath = tempDir.resolve("submcu-nvram.bin")

        // 1. Strict mode never writes submcu-nvram.bin
        val strictCtx = TestContext(inputWithNvram, directory = tempDir, relaxedMode = false)
        val strictPeer = createPeerWithBin(strictCtx)
        strictPeer.start()
        transferFrame(strictCtx, selector = 31, startTick = 10L, wordValue = 0xabcd, write = true)
        strictPeer.close()
        assertFalse(Files.exists(binPath), "strict/discovery mode must never persist submcu-nvram.bin")

        // 2. Relaxed mode writes 16-byte FNVR v1 binary atomically
        val relaxedCtx1 = TestContext(inputWithNvram, directory = tempDir, relaxedMode = true)
        val relaxedPeer1 = createPeerWithBin(relaxedCtx1)
        relaxedPeer1.start()
        transferFrame(relaxedCtx1, selector = 31, startTick = 10L, wordValue = 0x1122, write = true)
        transferFrame(relaxedCtx1, selector = 32, startTick = 20L, wordValue = 0x3344, write = true)
        transferFrame(relaxedCtx1, selector = 33, startTick = 30L, wordValue = 0x5566, write = true)
        transferFrame(relaxedCtx1, selector = 34, startTick = 40L, wordValue = 0x7788, write = true)
        relaxedPeer1.close()
        assertTrue(Files.isRegularFile(binPath))
        val rawBytes = Files.readAllBytes(binPath)
        assertEquals(16, rawBytes.size)
        assertEquals("FNVR", String(rawBytes, 0, 4, Charsets.US_ASCII))

        // 3. Reloading 16-byte FNVR binary on cold restart restores all 4 selectors
        val relaxedCtx2 = TestContext(inputWithNvram, directory = tempDir, relaxedMode = true)
        val relaxedPeer2 = createPeerWithBin(relaxedCtx2)
        relaxedPeer2.start()
        assertEquals(0x1122, transferFrame(relaxedCtx2, selector = 31, startTick = 10L).let { (it[1] shl 8) or it[2] })
        assertEquals(0x3344, transferFrame(relaxedCtx2, selector = 32, startTick = 20L).let { (it[1] shl 8) or it[2] })
        assertEquals(0x5566, transferFrame(relaxedCtx2, selector = 33, startTick = 30L).let { (it[1] shl 8) or it[2] })
        assertEquals(0x7788, transferFrame(relaxedCtx2, selector = 34, startTick = 40L).let { (it[1] shl 8) or it[2] })
        relaxedPeer2.close()

        // 4. Headerless 8-byte raw u16 LE dump is also accepted
        val raw8 = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0011).putShort(0x0022).putShort(0x0033).putShort(0x0044).array()
        Files.write(binPath, raw8)
        val relaxedCtx3 = TestContext(inputWithNvram, directory = tempDir, relaxedMode = true)
        val relaxedPeer3 = createPeerWithBin(relaxedCtx3)
        relaxedPeer3.start()
        assertEquals(0x0011, transferFrame(relaxedCtx3, selector = 31, startTick = 10L).let { (it[1] shl 8) or it[2] })
        assertEquals(0x0044, transferFrame(relaxedCtx3, selector = 34, startTick = 20L).let { (it[1] shl 8) or it[2] })
        relaxedPeer3.close()

        // 5. Invalid magic or wrong length fails closed
        Files.write(binPath, ByteArray(16) { 0x55 })
        val badMagicPeer = createPeerWithBin(TestContext(inputWithNvram, directory = tempDir, relaxedMode = true))
        assertFailsWith<IllegalArgumentException> { badMagicPeer.start() }

        Files.write(binPath, ByteArray(12))
        val badSizePeer = createPeerWithBin(TestContext(inputWithNvram, directory = tempDir, relaxedMode = true))
        assertFailsWith<IllegalArgumentException> { badSizePeer.start() }
    }
}
