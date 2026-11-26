package com.github.nyabanban.fuji.emulator.components.dram

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DramSnapshotTest {
    private val pairFixture = DramFixture()
    private val commandFixture = CommandFixture()

    private class PortRegistry : Ports {
        val registered = linkedMapOf<String, Any>()
        override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
            require(type.isInstance(port) && name !in registered)
            registered[name] = port
        }
        override fun <T : Any> find(name: String, type: Class<T>): T? = registered[name]?.let(type::cast)
        override fun removeOwner(owner: String) {
            registered.clear()
        }
    }

    private inner class TestContext(val rootDocument: Map<String, Any?>) : ComponentContext {
        override val directory: Path = Files.createTempDirectory(Path.of(DramTestPaths.value("studyOutput")), "dram-stateful-")
        override val ports = PortRegistry()
        var tick = 0L
        private val commandFiles = (0..3).associate { bankIndex ->
            "command-$bankIndex.yaml" to mapOf<String, Any?>(
                "schema" to 1,
                "bank" to bankIndex,
                "profile" to DramFactory.SNAPSHOT_PROFILE,
                "firmwareSha256" to DramFactory.FIRMWARE,
                "modes" to (0..4).associate { mode ->
                    mode.toString() to commandFixture.tables(bankIndex)[mode].map { (offset, word) ->
                        mapOf("offset" to offset, "value" to word)
                    }
                },
            )
        }
        override val clock = EmulationClock { tick }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) { error("No DRAM event is qualified") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path =
            directory.resolve(relative).normalize().also { resolved ->
                require(relative.isNotBlank() && resolved.startsWith(directory))
            }
        override fun readData(relative: String): Map<String, Any?> {
            dataPath(relative)
            return if (relative == "input.yaml") rootDocument else commandFiles.getValue(relative)
        }
    }

    private fun buildDocument(postAckPhy: Boolean = false, replay: Boolean = false, repeatableReplay: Boolean = false): Map<String, Any?> = linkedMapOf(
        "schema" to 1,
        "profile" to DramFactory.SNAPSHOT_PROFILE,
        "firmwareSha256" to DramFactory.FIRMWARE,
        "programmingWords" to pairFixture.programming.map { (offset, word) -> mapOf("offset" to offset, "value" to word) },
        "configurationWords" to pairFixture.config.map { (offset, word) -> mapOf("offset" to offset, "value" to word) },
        "commandTableFiles" to (0..3).associate { bankIndex -> bankIndex.toString() to "command-$bankIndex.yaml" },
        "banks" to linkedMapOf(
            "0" to linkedMapOf<String, Any?>(
                "responseTicks" to listOf(20, 20, 20, 20, 20),
                "initialController7044" to 0x80,
                "initialController7364" to 0x40,
                "responses" to (0..4).associate { stage -> stage.toString() to pairFixture.reply(stage).toList() },
                "commandPlans" to listOf(
                    mapOf("mode" to 4, "responseTicks" to 25, "responseWord" to 0, "counterWord" to 5),
                ),
                "snapshotResponse" to null,
            ),
            "1" to linkedMapOf<String, Any?>(
                "responseTicks" to listOf(20, 20, 20, 20, 20),
                "initialController7044" to 0x80,
                "initialController7364" to 0x40,
                "responses" to (0..4).associate { stage -> stage.toString() to pairFixture.reply(stage).toList() },
                "commandPlans" to listOf(
                    mapOf("mode" to 4, "responseTicks" to 30, "responseWord" to 0, "counterWord" to null),
                ),
                "snapshotResponse" to mapOf("responseTicks" to 40, "pendingStatusWord" to 0, "responseWord" to 1),
            ) + (if (repeatableReplay) mapOf("postAckPhyControl" to DramPhyReplay.REPEATABLE_PROFILE)
                else if (replay) mapOf("postAckPhyControl" to DramPhyReplay.PROFILE)
                else if (postAckPhy) mapOf("postAckPhyControl" to "clear-bit0-once-v1") else emptyMap()),
        ),
    )

    private inner class Harness(enabled: Boolean = true, postAckPhy: Boolean = false, replay: Boolean = false,
                                polls: List<Long>? = null, repeatableReplay: Boolean = false,
                                repeatableCommandModes: Boolean = false) {
        val context = TestContext(buildDocument(postAckPhy, replay, repeatableReplay).let { document ->
            if (polls == null) document else {
                val banks = document.getValue("banks") as Map<*, *>
                val bank = banks["1"] as Map<*, *>
                document + ("banks" to banks + ("1" to bank + ("postAckReplayPollWords" to polls)))
            }
        })
        val factory = DramFactory()
        val config: Map<String, Any> = if (enabled) {
            mapOf(
                "profile" to DramFactory.SNAPSHOT_PROFILE,
                "inputFile" to "input.yaml",
                "repeatableCommandModes" to repeatableCommandModes,
            )
        } else {
            mapOf("profile" to "disabled", "inputFile" to "")
        }
        val component = (factory.create(context, config) as StatefulComponent).also { instance -> instance.start() }
        fun controller(bankIndex: Int): ScheduledMmioDevice =
            context.ports.find("soc-dram.controller-$bankIndex", MmioDevice::class.java) as ScheduledMmioDevice
        fun phy(bankIndex: Int): MmioDevice =
            context.ports.find("soc-dram.phy-$bankIndex", MmioDevice::class.java)!!
        fun snapshotAck(bankIndex: Int): MmioDevice =
            context.ports.find("soc-dram.snapshot-ack-$bankIndex", MmioDevice::class.java)!!
        fun advanceTo(targetTick: Long) {
            context.tick = targetTick
            controller(0).beforeInstruction(targetTick)
            controller(1).beforeInstruction(targetTick)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun simulateJsonRoundTrip(value: Any?): Any? = when (value) {
        null, is Boolean, is String -> value
        is Byte, is Short, is Int, is Long -> {
            val numeric = (value as Number).toLong()
            if (numeric in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) numeric.toInt() else numeric
        }
        is Float, is Double -> (value as Number).toDouble()
        is List<*> -> value.map { element -> simulateJsonRoundTrip(element) }
        is Map<*, *> -> {
            val copy = LinkedHashMap<String, Any?>()
            for ((rawKey, rawElement) in value) {
                check(rawKey is String) { "JSON object keys must be strings" }
                copy[rawKey] = simulateJsonRoundTrip(rawElement)
            }
            copy
        }
        else -> error("Unsupported non-JSON value type: ${value::class.java.name}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun roundTripMap(snapshot: Map<String, Any?>): Map<String, Any?> =
        simulateJsonRoundTrip(snapshot) as Map<String, Any?>

    private fun runBankOneInitializationAndAck(target: Harness) {
        for ((offset, word) in pairFixture.programming) {
            target.phy(1).write(offset.toLong(), 4, word)
        }
        target.controller(1).write(0x7c18, 4, 0x40000000L)
        target.advanceTo(20L)
        target.controller(1).write(0x7040, 4, 3L)
        target.advanceTo(40L)
        target.controller(1).write(0x7040, 4, 0x10L)
        target.advanceTo(60L)
        target.phy(1).write(0x2000, 4, 0xb01L)
        target.advanceTo(80L)
        for ((offset, word) in pairFixture.config) if (offset != 0x7364) {
            target.controller(1).write(offset.toLong(), 4, word)
        }
        target.controller(1).write(0x7360, 4, 0xff00c00dL)
        target.advanceTo(100L)
        assertEquals(0x41L, target.controller(1).read(0x7364, 4))
        target.controller(1).write(0x7364, 4, 1L)
    }

        private fun runPrefixToT1(target: Harness) {
            runBankOneInitializationAndAck(target)
            for ((offset, word) in commandFixture.tables(1)[4]) {
                target.controller(1).write(offset.toLong(), 4, word)
            }
            target.advanceTo(130L)
            assertEquals(0L, target.controller(1).read(0x5384, 4))

            val bankZeroTable = commandFixture.tables(0)[4]
            for ((offset, word) in bankZeroTable.dropLast(1)) {
                target.controller(0).write(offset.toLong(), 4, word)
            }
            assertEquals(5L, target.controller(0).read(0x5200, 4))
            target.controller(0).write(0x5204, 4, 4L)

            for ((offset, word) in DramSnapshotBank.configuration()) {
                target.controller(1).write(offset.toLong(), 4, word)
            }
            target.controller(1).write(DramSnapshotBank.REQUEST_OFFSET.toLong(), 4, DramSnapshotBank.REQUEST_VALUE)
            target.advanceTo(150L)
        }
        private fun runContinuationToT2(target: Harness): List<Long> {
            val observations = mutableListOf<Long>()
            observations += target.controller(1).read(DramSnapshotBank.STATUS_OFFSET.toLong(), 4)
            target.controller(0).write(0x5208, 4, 4L)
            val finalCommandPair = commandFixture.tables(0)[4].last()
            target.controller(0).write(finalCommandPair.first.toLong(), 4, finalCommandPair.second)
            target.advanceTo(170L)
            observations += target.controller(1).read(DramSnapshotBank.STATUS_OFFSET.toLong(), 4)
            target.snapshotAck(1).write(0, 4, 1L)
            observations += target.controller(0).read(0x5384, 4)
            target.advanceTo(175L)
            observations += target.controller(0).read(0x5384, 4)
            observations += target.phy(1).read(0x4670, 4)
            return observations
        }

    @Test
    fun snapshotRoundTripPreservesPairCommandAndSnapshotBanksAcrossT1ToT2() {
        val continuous = Harness()
        runPrefixToT1(continuous)
        val savedAtT1 = roundTripMap(continuous.component.saveSnapshot())
        val stateAtT1 = continuous.component.state()

        val resumed = Harness()
        resumed.context.tick = 150L
        resumed.component.restoreSnapshot(savedAtT1)
        assertEquals(stateAtT1, resumed.component.state())
        assertEquals(continuous.controller(0).nextDeadline(), resumed.controller(0).nextDeadline())
        assertEquals(continuous.controller(1).nextDeadline(), resumed.controller(1).nextDeadline())

        val expectedObservations = runContinuationToT2(continuous)
        val actualObservations = runContinuationToT2(resumed)
        assertEquals(expectedObservations, actualObservations)
        assertEquals(continuous.component.state(), resumed.component.state())
        assertFails { resumed.phy(1).write(0x2000, 4, 0xb00) }
        val selected = Harness(postAckPhy = true)
        selected.context.tick = 150L
        selected.component.restoreSnapshot(savedAtT1)
        assertFails { selected.phy(1).write(0x2000, 4, 0xb00) }
        assertEquals(expectedObservations, runContinuationToT2(selected))
        val before = selected.component.saveSnapshot()
        assertFails { selected.phy(1).write(0x2000, 2, 0xb00) }
        assertFails { selected.phy(1).write(0x2000, 4, 0xb02) }
        assertEquals(before, selected.component.saveSnapshot())
        selected.phy(1).write(0x2000, 4, 0xb00)
        val after = selected.component.saveSnapshot()
        assertFails { selected.phy(1).write(0x2000, 4, 0xb00) }
        assertEquals(after, selected.component.saveSnapshot())
        selected.component.restoreSnapshot(roundTripMap(after))
        assertEquals(0xb00L, selected.phy(1).read(0x2000, 4))
        selected.component.close()
        continuous.component.close()
        resumed.component.close()
    }

    @Test
    fun replayRestoresConsumedFirstWordAndChecksTheEntireDatSequence() {
        val once = Harness(postAckPhy = true)
        runPrefixToT1(once)
        runContinuationToT2(once)
        once.phy(1).write(0x2000, 4, 0xb00)
        val start = once.component.saveSnapshot()
        val selected = Harness(replay = true)
        selected.context.tick = once.context.tick
        selected.component.restoreSnapshot(roundTripMap(start))
        val phy = selected.phy(1)
        val controller = selected.controller(1)
        val original = DramPhyReplay.calibration.associate { (offset, _) ->
            offset to once.phy(1).read(offset.toLong(), 4)
        }
        val before = selected.component.saveSnapshot()
        assertFails { phy.write(0x2000, 4, 0xb00) }
        assertFails { phy.write(0x2004, 4, 0) }
        assertFails { phy.write(0x2004, 2, 0x100) }
        assertEquals(before, selected.component.saveSnapshot())
        for ((offset, value) in pairFixture.programming.drop(1)) phy.write(offset.toLong(), 4, value)
        val mid = roundTripMap(selected.component.saveSnapshot())
        assertFails { once.component.restoreSnapshot(mid) }
        selected.component.restoreSnapshot(mid)
        for ((offset, value) in pairFixture.config.take(24)) controller.write(offset.toLong(), 4, value)
        assertEquals(pairFixture.pairs(0x15ae888, 6), DramPhyReplay.setup)
        val words = pairFixture.programming.toMap() + DramPhyReplay.setup
        for ((offset, value) in DramPhyReplay.setup) phy.write(offset.toLong(), 4, value)
        for ((offset, mask) in DramPhyReplay.calibration) {
            val retained = phy.read(offset.toLong(), 4)
            assertEquals(words.getValue(offset), retained)
            val updated = (retained and mask.inv()) or (original.getValue(offset) and mask)
            assertFails { phy.write(offset.toLong(), 4, updated xor 0x80000000) }
            phy.write(offset.toLong(), 4, updated)
        }
        controller.write(0x7040, 4, 0x100)
        assertEquals(once.controller(1).read(0x7044, 4), controller.read(0x7044, 4))
        assertEquals(pairFixture.pairs(0x15b1768, 4) + pairFixture.pairs(0x15b17a0, 1), DramPhyReplay.request)
        for ((offset, value) in DramPhyReplay.request) controller.write(offset.toLong(), 4, value)
        val end = selected.component.saveSnapshot()
        assertFails { controller.read(0x7090, 4) }
        assertEquals(end, selected.component.saveSnapshot())
        selected.component.restoreSnapshot(roundTripMap(end))
        assertFails { phy.write(0x2000, 4, 0xb00) }
        val samples = listOf(1L, 0x30924, 0x30003, 0x30003)
        val full = Harness(replay = true, polls = samples)
        full.context.tick = once.context.tick
        full.component.restoreSnapshot(roundTripMap(end))
        assertEquals(1L, full.controller(1).read(0x7090, 4))
        full.controller(1).write(0x7c80, 4, 2)
        assertFails { full.phy(1).write(0, 4, 0) }
        assertFails { full.snapshotAck(1).write(0, 4, 1) }
        full.snapshotAck(1).write(0, 4, 0)
        val acknowledged = roundTripMap(full.component.saveSnapshot())
        val unready = Harness(replay = true, polls = listOf(0, 0, 0, 0))
        unready.context.tick = once.context.tick
        assertFails { unready.component.restoreSnapshot(acknowledged) }
        unready.component.restoreSnapshot(roundTripMap(end))
        assertEquals(0L, unready.controller(1).read(0x7090, 4))
        assertFails { unready.controller(1).read(0x7090, 4) }
        assertFails { selected.component.restoreSnapshot(acknowledged) }
        full.component.restoreSnapshot(acknowledged)
        assertFails { full.snapshotAck(1).write(0, 4, 0) }
        full.controller(1).write(0x7c18, 4, 0x40000000)
        for ((index, offset) in listOf(0x4670L, 0x4674L, 0x4678L).withIndex()) {
            assertEquals(samples[index + 1], full.phy(1).read(offset, 4))
        }
        assertFails { full.controller(1).write(0x7c50, 4, 1) }
        verifyFinalization(full, samples)
        full.component.close()
        unready.component.close()
        once.component.close()
        selected.component.close()
    }

    private fun verifyFinalization(previous: Harness, prefix: List<Long>) {
        val samples = prefix + listOf(1L, 0, 1, 768, 768, 0, 0, 0, 0) +
            List(8) { 0x02000200L } + listOf(512, 512, 1)
        val saved = roundTripMap(previous.component.saveSnapshot())
        val extension = Harness(replay = true, polls = samples)
        extension.context.tick = previous.context.tick
        extension.component.restoreSnapshot(saved)
        val controller = extension.controller(1)
        val phy = extension.phy(1)
        controller.write(0x7c50, 4, 1)
        assertFails { controller.write(0x5000, 4, 0x01004100) }
        assertEquals(1, controller.read(0x7c18, 4))
        controller.write(0x7080, 4, 0x200)
        assertEquals(0, controller.read(0x7090, 4))
        controller.write(0x7c50, 4, 0)
        val mid = roundTripMap(extension.component.saveSnapshot())
        assertFails { previous.component.restoreSnapshot(mid) }
        extension.component.restoreSnapshot(mid)
        val offsets = listOf(0x2110, 0x40dc, 0x42dc, 0x40ec, 0x42ec, 0x40f8, 0x42f8,
            0x4138, 0x413c, 0x4140, 0x4144, 0x4338, 0x433c, 0x4340, 0x4344, 0x4148, 0x4348)
        offsets.forEachIndexed { index, offset -> assertEquals(samples[6 + index], phy.read(offset.toLong(), 4)) }
        controller.write(0x7360, 4, 0xff00c00d)
        assertEquals(1, controller.read(0x7364, 4))
        for ((offset, value) in pairFixture.pairs(0x15b1708, 4)) controller.write(offset.toLong(), 4, value)
        assertContains(assertFails { controller.write(0x5000, 4, 0x01004100) }.message.orEmpty(), "command input plan exhausted")
        extension.component.close()
        val busy = Harness(replay = true, polls = samples.toMutableList().also { it[4] = 0 })
        busy.context.tick = previous.context.tick
        busy.component.restoreSnapshot(saved)
        busy.controller(1).write(0x7c50, 4, 1)
        assertEquals(0, busy.controller(1).read(0x7c18, 4))
        assertFails { busy.controller(1).write(0x7080, 4, 0x200) }
        busy.component.close()
    }

    @Test
    fun disabledProfileAndMidPairInitializationRoundTripCleanly() {
        val disabled = Harness(enabled = false)
        val disabledSnapshot = roundTripMap(disabled.component.saveSnapshot())
        val restoredDisabled = Harness(enabled = false)
        restoredDisabled.component.restoreSnapshot(disabledSnapshot)
        assertEquals(disabled.component.state(), restoredDisabled.component.state())
        disabled.component.close()
        restoredDisabled.component.close()

        val midPair = Harness()
        for ((offset, word) in pairFixture.programming) {
            midPair.phy(0).write(offset.toLong(), 4, word)
        }
        midPair.context.tick = 10L
        midPair.controller(0).write(0x7c18, 4, 0x40000000L)
        val savedMidPair = roundTripMap(midPair.component.saveSnapshot())
        val resumedMidPair = Harness()
        resumedMidPair.context.tick = 10L
        resumedMidPair.component.restoreSnapshot(savedMidPair)
        assertEquals(midPair.component.state(), resumedMidPair.component.state())
        midPair.advanceTo(30L)
        resumedMidPair.advanceTo(30L)
        assertEquals(midPair.controller(0).read(0x7c18, 4), resumedMidPair.controller(0).read(0x7c18, 4))
        assertEquals(midPair.component.state(), resumedMidPair.component.state())
        midPair.component.close()
        resumedMidPair.component.close()
    }

    @Test
    fun invalidOrClosedSnapshotsRejectAtomicallyWithoutMutatingLiveState() {
        val live = Harness()
        for ((offset, word) in pairFixture.programming.take(12)) {
            live.phy(0).write(offset.toLong(), 4, word)
        }
        val baselineState = live.component.state()
        val validSnapshot = roundTripMap(live.component.saveSnapshot())

        val badSchema = LinkedHashMap(validSnapshot).apply { put("schema", 9) }
        val badComponent = LinkedHashMap(validSnapshot).apply { put("componentId", "soc-other") }
        val badProfile = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val scalars = LinkedHashMap(get("scalars") as Map<String, Any?>)
            scalars["profile"] = DramFactory.PROFILE
            put("scalars", scalars)
        }
        val badPhyWord = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val banksMap = LinkedHashMap(get("banks") as Map<String, Any?>)
            @Suppress("UNCHECKED_CAST")
            val bankZero = LinkedHashMap(banksMap["0"] as Map<String, Any?>)
            @Suppress("UNCHECKED_CAST")
            val pairSection = LinkedHashMap(bankZero["pair"] as Map<String, Any?>)
            @Suppress("UNCHECKED_CAST")
            val phyWords = LinkedHashMap(pairSection["phyWords"] as Map<String, Any?>)
            val firstKey = pairFixture.programming.first().first.toString(16)
            phyWords[firstKey] = 0xdeadbeefL
            pairSection["phyWords"] = phyWords
            bankZero["pair"] = pairSection
            banksMap["0"] = bankZero
            put("banks", banksMap)
        }
        val prematureSnapshotActive = LinkedHashMap(validSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val banksMap = LinkedHashMap(get("banks") as Map<String, Any?>)
            @Suppress("UNCHECKED_CAST")
            val bankOne = LinkedHashMap(banksMap["1"] as Map<String, Any?>)
            @Suppress("UNCHECKED_CAST")
            val snapSection = LinkedHashMap(bankOne["snapshot"] as Map<String, Any?>)
            snapSection["configurationWrites"] = 1
            bankOne["snapshot"] = snapSection
            banksMap["1"] = bankOne
            put("banks", banksMap)
        }

        for (corrupted in listOf(badSchema, badComponent, badProfile, badPhyWord, prematureSnapshotActive)) {
            assertFailsWith<IllegalArgumentException> { live.component.restoreSnapshot(corrupted) }
            assertEquals(baselineState, live.component.state())
        }

        live.component.close()
        assertFailsWith<IllegalStateException> { live.component.saveSnapshot() }
        assertFailsWith<IllegalStateException> { live.component.restoreSnapshot(validSnapshot) }
    }

    @Test
    fun repeatableReplayAllowsSecondSnapshotAndWakeupCycleAndPromotionFromReplayOnce() {
        val fullSamples = listOf(1L, 0x30924, 0x30003, 0x30003, 1L, 0, 1, 768, 768, 0, 0, 0, 0) +
            List(8) { 0x02000200L } + listOf(512, 512, 1)

        fun executeReplayCycle(harness: Harness, originalCal: Map<Int, Long>) {
            val phy = harness.phy(1)
            val controller = harness.controller(1)
            for ((offset, value) in pairFixture.programming) phy.write(offset.toLong(), 4, value)
            for ((offset, value) in pairFixture.config.take(24)) controller.write(offset.toLong(), 4, value)
            val words = pairFixture.programming.toMap() + DramPhyReplay.setup
            for ((offset, value) in DramPhyReplay.setup) phy.write(offset.toLong(), 4, value)
            for ((offset, mask) in DramPhyReplay.calibration) {
                val retained = phy.read(offset.toLong(), 4)
                assertEquals(words.getValue(offset), retained)
                val updated = (retained and mask.inv()) or (originalCal.getValue(offset) and mask)
                phy.write(offset.toLong(), 4, updated)
            }
            controller.write(0x7040, 4, 0x100)
            controller.read(0x7044, 4)
            for ((offset, value) in DramPhyReplay.request) controller.write(offset.toLong(), 4, value)
            assertEquals(1L, controller.read(0x7090, 4))
            controller.write(0x7c80, 4, 2)
            harness.snapshotAck(1).write(0, 4, 0)
            controller.write(0x7c18, 4, 0x40000000)
            for ((index, offset) in listOf(0x4670L, 0x4674L, 0x4678L).withIndex()) {
                assertEquals(fullSamples[index + 1], phy.read(offset, 4))
            }
            controller.write(0x7c50, 4, 1)
            assertEquals(1L, controller.read(0x7c18, 4))
            controller.write(0x7080, 4, 0x200)
            assertEquals(0L, controller.read(0x7090, 4))
            controller.write(0x7c50, 4, 0)
            val offsets = listOf(0x2110, 0x40dc, 0x42dc, 0x40ec, 0x42ec, 0x40f8, 0x42f8,
                0x4138, 0x413c, 0x4140, 0x4144, 0x4338, 0x433c, 0x4340, 0x4344, 0x4148, 0x4348)
            offsets.forEachIndexed { index, offset -> assertEquals(fullSamples[6 + index], phy.read(offset.toLong(), 4)) }
            controller.write(0x7360, 4, 0xff00c00d)
            assertEquals(1L, controller.read(0x7364, 4))
            for ((offset, value) in pairFixture.pairs(0x15b1708, 4)) controller.write(offset.toLong(), 4, value)
        }

        val once = Harness(replay = true, polls = fullSamples)
        runPrefixToT1(once)
        runContinuationToT2(once)
        val originalCal = DramPhyReplay.calibration.associate { (offset, _) -> offset to once.phy(1).read(offset.toLong(), 4) }
        executeReplayCycle(once, originalCal)
        val afterFirstCycle = roundTripMap(once.component.saveSnapshot())

        // Non-repeatable profile refuses a second snapshot cycle
        assertFails { once.controller(1).write(DramSnapshotBank.configuration().first().first.toLong(), 4, DramSnapshotBank.configuration().first().second) }

        // Promote snapshot from dat-replay-once-v1 into dat-replay-repeatable-v1
        val repeatable = Harness(repeatableReplay = true, polls = fullSamples)
        repeatable.context.tick = once.context.tick
        repeatable.component.restoreSnapshot(afterFirstCycle)

        // Execute a second full DRAM self-refresh + snapshot + PHY replay wakeup cycle
        for ((offset, word) in DramSnapshotBank.configuration()) {
            repeatable.controller(1).write(offset.toLong(), 4, word)
        }
        repeatable.controller(1).write(DramSnapshotBank.REQUEST_OFFSET.toLong(), 4, DramSnapshotBank.REQUEST_VALUE)
        repeatable.advanceTo(repeatable.context.tick + 40L)
        assertEquals(1L, repeatable.controller(1).read(DramSnapshotBank.STATUS_OFFSET.toLong(), 4))
        repeatable.snapshotAck(1).write(0, 4, 1L)
        executeReplayCycle(repeatable, originalCal)

        val afterSecondCycle = roundTripMap(repeatable.component.saveSnapshot())
        val restored = Harness(repeatableReplay = true, polls = fullSamples)
        restored.context.tick = repeatable.context.tick
        restored.component.restoreSnapshot(afterSecondCycle)
        assertEquals(repeatable.component.state(), restored.component.state())

        // Non-repeatable profile strictly refuses snapshot from repeatable profile
        assertFails { once.component.restoreSnapshot(afterSecondCycle) }

        once.component.close()
        repeatable.component.close()
        restored.component.close()
    }

    @Test
    fun factoryCommandPlanModePromotesMonotonicallyAndRefusesUnknownMode() {
        val base = Harness()
        runPrefixToT1(base)
        runContinuationToT2(base)
        val afterFirstCmd = roundTripMap(base.component.saveSnapshot())
        assertFails {
            for ((offset, word) in commandFixture.tables(0)[2]) {
                base.controller(0).write(offset.toLong(), 4, word)
            }
        }

        val repeatableDoc = buildDocument().let { doc ->
            @Suppress("UNCHECKED_CAST")
            val banks = doc.getValue("banks") as Map<String, Map<String, Any?>>
            doc + ("banks" to banks + ("0" to banks.getValue("0") + ("commandPlanMode" to DramCommandBank.REPEATABLE_ANY_MODE)))
        }
        val repeatableCtx = TestContext(repeatableDoc)
        repeatableCtx.tick = base.context.tick
        val repeatable = (DramFactory().create(
            repeatableCtx,
            mapOf("profile" to DramFactory.SNAPSHOT_PROFILE, "inputFile" to "input.yaml"),
        ) as StatefulComponent).also { it.start() }
        repeatable.restoreSnapshot(afterFirstCmd)
        val ctrl0 = repeatableCtx.ports.find("soc-dram.controller-0", MmioDevice::class.java)!!
        val mode2 = commandFixture.tables(0)[2]
        for ((offset, word) in mode2.dropLast(1)) ctrl0.write(offset.toLong(), 4, word)
        assertEquals(5L, ctrl0.read(0x5200, 4))
        ctrl0.write(0x5204, 4, 4L)
        ctrl0.write(0x5208, 4, 4L)
        ctrl0.write(mode2.last().first.toLong(), 4, mode2.last().second)
        repeatableCtx.tick += 25L
        ctrl0.beforeInstruction(repeatableCtx.tick)
        assertEquals(0L, ctrl0.read(0x5384, 4))
        val repeatableSnap = roundTripMap(repeatable.saveSnapshot())
        assertFailsWith<IllegalArgumentException> { base.component.restoreSnapshot(repeatableSnap) }

        val badModeDoc = buildDocument().let { doc ->
            @Suppress("UNCHECKED_CAST")
            val banks = doc.getValue("banks") as Map<String, Map<String, Any?>>
            doc + ("banks" to banks + ("0" to banks.getValue("0") + ("commandPlanMode" to "unknown-mode-v1")))
        }
        assertFailsWith<IllegalArgumentException> {
            DramFactory().create(
                TestContext(badModeDoc),
                mapOf("profile" to DramFactory.SNAPSHOT_PROFILE, "inputFile" to "input.yaml"),
            )
        }

        base.component.close()
        repeatable.close()
    }

    @Test fun repeatableCommandModesMatchesMultipleDatTablesAndMonotonicallyUpgrades() {
        val finite = Harness(repeatableCommandModes = false)
        runPrefixToT1(finite)
        runContinuationToT2(finite)
        val snapAfterPlan = roundTripMap(finite.component.saveSnapshot())

        // Finite plan refuses an extra mode-1 command write on bank 0 after mode-4 finished
        val mode1 = commandFixture.tables(0)[1]
        assertFails { finite.controller(0).write(mode1.first().first.toLong(), 4, mode1.first().second) }

        // Monotonic upgrade false -> true accepts mode-1 and then mode-4 again while rejecting non-DAT words
        val repeatable = Harness(repeatableCommandModes = true)
        repeatable.context.tick = finite.context.tick
        repeatable.component.restoreSnapshot(snapAfterPlan)

        for (mode in listOf(1, 4)) {
            val table = commandFixture.tables(0)[mode]
            for ((offset, word) in table.dropLast(1)) {
                repeatable.controller(0).write(offset.toLong(), 4, word)
            }
            assertEquals(5L, repeatable.controller(0).read(0x5200, 4))
            repeatable.controller(0).write(0x5204, 4, 4L)
            repeatable.controller(0).write(0x5208, 4, 4L)
            val finalPair = table.last()
            repeatable.controller(0).write(finalPair.first.toLong(), 4, finalPair.second)
            repeatable.advanceTo(repeatable.context.tick + 25L)
            assertEquals(0L, repeatable.controller(0).read(0x5384, 4))
        }
        // Non-DAT bogus word is still strictly refused
        assertFails { repeatable.controller(0).write(mode1.first().first.toLong(), 4, mode1.first().second xor 0xdeadbeefL) }

        // DAT 0x011fb654 targeted 3-word Movie (0x015b15e0) and Normal (0x015b15bc) command tables succeed
        repeatable.controller(0).write(0x5038, 4, 0x03204020L)
        repeatable.controller(0).write(0x5438, 4, 0x00000083L)
        repeatable.controller(0).write(0x5384, 4, 1L)
        repeatable.advanceTo(repeatable.context.tick + 25L)
        assertEquals(0L, repeatable.controller(0).read(0x5384, 4))

        repeatable.controller(0).write(0x5038, 4, 0x01004100L)
        repeatable.controller(0).write(0x5438, 4, 0x00000081L)
        repeatable.controller(0).write(0x5384, 4, 1L)
        repeatable.advanceTo(repeatable.context.tick + 25L)
        assertEquals(0L, repeatable.controller(0).read(0x5384, 4))

        val snapRepeatable = roundTripMap(repeatable.component.saveSnapshot())
        val restored = Harness(repeatableCommandModes = true)
        restored.context.tick = repeatable.context.tick
        restored.component.restoreSnapshot(snapRepeatable)
        assertEquals(repeatable.component.state(), restored.component.state())

        // Downgrade true -> false must be strictly refused
        assertFails { finite.component.restoreSnapshot(snapRepeatable) }

        finite.component.close()
        repeatable.component.close()
        restored.component.close()
    }
}
