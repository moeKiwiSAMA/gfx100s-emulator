package com.github.nyabanban.fuji.emulator.components.sdhost

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Explicit finite bit1 samples (v3/v4/v5) or authentic SDHCI CLOCK_CONTROL bit1 tracking bit0 (v6..v9). */
internal class SdClockHost(
    context: ComponentContext,
    config: Map<String, Any>,
    inputOverride: Map<String, Any?>? = null,
    private val hardwareClockStable: Boolean = false,
) :
    ScalarComponent("soc-sd-host", context, config), StatefulComponent {
    companion object {
        fun key(index: Int) = (index / 2).toString() + ":" + (index % 2)
        fun port(index: Int) = "soc-sd-host.clock-" + key(index).replace(':', '-')
        private fun integer(value: Any?): Long {
            require(value is Byte || value is Short || value is Int || value is Long)
            return (value as Number).toLong()
        }
    }
    private val phy = config["profile"] == SdHostFactory.PHY_PROFILE
    private val initial = LongArray(4)
    private val words = LongArray(4)
    private val samples = Array(4) { ByteArray(0) }
    private val consumed = IntArray(4)
    private val controls: SdControlHost
    private val inputSha: String
    private var closed = false
    private var count = 0L
    private var reads = 0L
    private var writes = 0L
    private val indices = IntArray(256)
    private val directions = BooleanArray(256)
    private val data = LongArray(256)
    private val ticks = LongArray(256)
    private val cores = IntArray(256) { -1 }
    private val pcs = LongArray(256) { -1 }

    internal data class ParsedClockAccess(
        val clockIndex: Int,
        val write: Boolean,
        val value: Long,
        val tick: Long,
        val core: Int,
        val pcVirtual: Long,
    )

    internal data class ParsedSnapshot(
        val rawScalars: Map<*, *>,
        val controlSnapshot: SdControlHost.ParsedSnapshot,
        val storedWords: LongArray,
        val consumedCounts: IntArray,
        val readCount: Long,
        val writeCount: Long,
        val accessCount: Long,
        val accesses: List<ParsedClockAccess>,
    )

    init {
        require(config["profile"] == SdHostFactory.CLOCK_PROFILE || phy)
        val file = config["inputFile"] as String
        require(file.isNotBlank()) { "Explicit SD clock input required" }
        val input = inputOverride ?: context.readData(file)
        require(input.keys == setOf("schema", "profile", "initialRegisters", "clocks") &&
            integer(input["schema"]) == 1L && input["profile"] == config["profile"])
        val raw = input["clocks"] as? Map<*, *> ?: error("Four explicit clock inputs required")
        require(raw.keys == (0..3).map { key(it) }.toSet())
        for (index in 0..3) {
            val row = raw[key(index)] as? Map<*, *> ?: error("Clock input missing")
            require(row.keys == setOf("initialWord", "bit1Samples"))
            val value = integer(row["initialWord"])
            require(value in 0..65535 && value and 2L == 0L) { "bit1 belongs exclusively to explicit samples" }
            initial[index] = value
            val sequence = row["bit1Samples"] as? List<*> ?: error("Explicit sample list required")
            require(sequence.size <= 4096)
            samples[index] = ByteArray(sequence.size) { at ->
                val sample = sequence[at]
                if (sample == null) (-1).toByte() else integer(sample).also { require(it in 0..1) }.toByte()
            }
        }
        initial.copyInto(words)
        val controlProfile = if (phy) SdHostFactory.PHY_PROFILE else SdHostFactory.CONTROL_PROFILE
        val controlInput = mapOf("schema" to 1, "profile" to controlProfile,
            "initialRegisters" to input.getValue("initialRegisters"))
        val controlInitial = input["initialRegisters"] as? Map<*, *> ?: error("Control initial values missing")
        require(controlInitial.keys == setOf("0", "1"))
        val canonical = ByteBuffer.allocate(32 + samples.sumOf { 6 + it.size }).order(ByteOrder.LITTLE_ENDIAN)
        for (slot in 0..1) {
            val row = controlInitial[slot.toString()] as? Map<*, *> ?: error("Control slot missing")
            require(row.keys == SdControlHost.fields.toSet())
            for (field in SdControlHost.fields) {
                val value = integer(row[field]); val halfword = field == "control623e" || field == "control403e"
                require(value in 0..(if (halfword) 65535L else 0xffffffffL))
                if (halfword) canonical.putShort(value.toShort()) else canonical.putInt(value.toInt())
            }
        }
        for (index in 0..3) canonical.putShort(initial[index].toShort()).putInt(samples[index].size).put(samples[index])
        inputSha = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.array()))
        val controlClaims = (0..1).map { SdControlHost.port(it, 0) } +
            (0..1).flatMap { slot -> (1..4).map { SdControlHost.port(slot, it) } }
        require((controlClaims + (0..3).map { port(it) }).all { context.ports.find(it, MmioDevice::class.java) == null }) {
            "SD host port alias"
        }
        controls = SdControlHost(context, config + ("profile" to controlProfile), controlInput)
        for (index in 0..3) context.ports.register(id, port(index), MmioDevice::class.java, object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 10 + index
            private fun checkAccess(offset: Long, size: Int) {
                check(!closed) { "SD host closed" }
                require(offset == 0L && size == 2) { "Exact clock halfword only" }
            }
            override fun read(offset: Long, size: Int): Long {
                checkAccess(offset, size)
                if (hardwareClockStable) {
                    val tick = preflight(); val location = location()
                    // Authentic SDHCI / Socionext F_SDH30 CLOCK_CONTROL (0x2C):
                    // bit 1 (Internal Clock Stable, RO) tracks bit 0 (Internal Clock Enable, RW).
                    val stableBit = (words[index] and 1L) shl 1
                    val value = (words[index] and 0xfffdL) or stableBit
                    record(index, false, value, tick, location)
                    return value
                }
                val at = consumed[index]
                require(at < samples[index].size && samples[index][at] >= 0) { "Missing explicit SD clock bit1 sample" }
                val tick = preflight(); val location = location()
                val value = (words[index] and 0xfffdL) or (samples[index][at].toLong() shl 1)
                consumed[index]++
                record(index, false, value, tick, location)
                return value
            }
            override fun write(offset: Long, size: Int, value: Long) {
                checkAccess(offset, size); require(value in 0..65535)
                val tick = preflight(); val location = location()
                words[index] = value
                record(index, true, value, tick, location)
            }
        })
    }
    private fun preflight(): Long {
        val tick = context.clock.ticks()
        require(tick >= 0 && count < Long.MAX_VALUE && reads < Long.MAX_VALUE && writes < Long.MAX_VALUE)
        return tick
    }
    private fun location() = context.ports.find("cpu.execution-location", ExecutionLocationSource::class.java)?.current()
    private fun record(index: Int, write: Boolean, value: Long, tick: Long, location: ExecutionLocation?) {
        val at = (count % 256).toInt()
        indices[at] = index; directions[at] = write; data[at] = value; ticks[at] = tick
        cores[at] = location?.core ?: -1; pcs[at] = location?.pcVirtual ?: -1
        count++
        if (write) writes++ else reads++
    }
    override fun start() { check(!closed); controls.start() }
    override fun close() { closed = true; controls.close() }
    override fun validateSettings(settings: Map<String, Any>) {
        check(!closed)
        require(settings.all { values[it.key] == it.value }) { "SD settings require restart" }
    }
    override fun applySettings(settings: Map<String, Any>) { validateSettings(settings) }
    override fun additionalMmioPorts() = controls.additionalMmioPorts() + (0..3).map { port(it) }
    override fun saveSnapshot(): Map<String, Any?> {
        require(!closed) { "SD host closed" }
        return linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "kind" to "clock",
            "scalarValues" to saveScalarValues(),
            "inputCanonicalSha256" to inputSha,
            "controlSnapshot" to controls.saveSnapshot(),
            "clocks" to (0..3).associate { clockIndex ->
                key(clockIndex) to linkedMapOf(
                    "storedWord" to words[clockIndex],
                    "consumedSamples" to consumed[clockIndex],
                )
            },
            "clockReadCount" to reads,
            "clockWriteCount" to writes,
            "clockAccessCount" to count,
            "clockAccesses" to (maxOf(0L, count - 256) until count).map { sequence ->
                val ringSlot = (sequence % 256).toInt()
                linkedMapOf(
                    "clock" to key(indices[ringSlot]),
                    "write" to directions[ringSlot],
                    "value" to data[ringSlot],
                    "tick" to ticks[ringSlot],
                    "core" to cores[ringSlot].takeIf { coreIndex -> coreIndex >= 0 },
                    "pcVirtual" to pcs[ringSlot].takeIf { pcValue -> pcValue >= 0L },
                )
            },
        )
    }
    internal fun parseSnapshot(raw: Any?): ParsedSnapshot {
        require(!closed) { "SD host closed" }
        val snapshotData = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD clock snapshot")
        val expectedKeys = setOf(
            "schema", "componentId", "kind", "scalarValues", "inputCanonicalSha256",
            "controlSnapshot", "clocks", "clockReadCount", "clockWriteCount", "clockAccessCount", "clockAccesses",
        )
        require(snapshotData.keys == expectedKeys) { "Unexpected SD clock snapshot keys" }
        require(SdHostSnapshotSupport.parseIntegralInt(snapshotData["schema"], "schema") == snapshotSchemaVersion) {
            "Unsupported SD clock snapshot schema"
        }
        require(snapshotData["componentId"] == id && snapshotData["kind"] == "clock") { "Mismatched SD clock snapshot identity" }
        val rawScalars = SdHostSnapshotSupport.validateScalarValues(snapshotData["scalarValues"], values, id)
        require(snapshotData["inputCanonicalSha256"] == inputSha) { "SD clock inputCanonicalSha256 differs" }
        val parsedControl = controls.parseSnapshot(snapshotData["controlSnapshot"])
        val rawClocks = snapshotData["clocks"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid clocks map in SD snapshot")
        val clockKeys = (0..3).map(::key)
        require(rawClocks.keys == clockKeys.toSet()) { "SD clock keys differ" }
        val nextWords = LongArray(4)
        val nextConsumed = IntArray(4)
        for (clockIndex in 0..3) {
            val clockName = clockKeys[clockIndex]
            val clockRow = rawClocks[clockName] as? Map<*, *> ?: throw IllegalArgumentException("Invalid clock entry $clockName")
            require(clockRow.keys == setOf("storedWord", "consumedSamples")) { "Unexpected clock entry keys for $clockName" }
            nextWords[clockIndex] = SdHostSnapshotSupport.parseUnsigned16(clockRow["storedWord"], "clocks.$clockName.storedWord")
            val consumedCount = SdHostSnapshotSupport.parseIntegralInt(clockRow["consumedSamples"], "clocks.$clockName.consumedSamples")
            require(consumedCount in 0..samples[clockIndex].size) { "Clock consumedSamples out of bounds for $clockName" }
            for (sampleOffset in 0 until consumedCount) {
                require(samples[clockIndex][sampleOffset] >= 0) { "Consumed clock sample cannot be null for $clockName" }
            }
            nextConsumed[clockIndex] = consumedCount
        }
        val nextReads = SdHostSnapshotSupport.parseIntegralLong(snapshotData["clockReadCount"], "clockReadCount")
        val nextWrites = SdHostSnapshotSupport.parseIntegralLong(snapshotData["clockWriteCount"], "clockWriteCount")
        val nextCount = SdHostSnapshotSupport.parseIntegralLong(snapshotData["clockAccessCount"], "clockAccessCount")
        require(nextReads >= 0L && nextWrites >= 0L && nextCount >= 0L) { "Negative counter in SD clock snapshot" }
        val totalAccesses = try { Math.addExact(nextReads, nextWrites) } catch (_: ArithmeticException) {
            throw IllegalArgumentException("SD clock counter overflow")
        }
        require(totalAccesses == nextCount) { "SD clockReadCount + clockWriteCount must equal clockAccessCount" }
        val rawAccesses = snapshotData["clockAccesses"] as? List<*> ?: throw IllegalArgumentException("Invalid SD clockAccesses")
        require(rawAccesses.size == minOf(nextCount, 256L).toInt()) { "SD clock access log size disagrees with clockAccessCount" }
        val parsedAccesses = rawAccesses.map { rawEntry ->
            val entryMap = rawEntry as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD clock access entry")
            require(entryMap.keys == setOf("clock", "write", "value", "tick", "core", "pcVirtual")) {
                "Unexpected SD clock access entry keys"
            }
            val clockLabel = entryMap["clock"] as? String ?: throw IllegalArgumentException("Invalid SD clock label")
            val clockIndex = clockKeys.indexOf(clockLabel)
            require(clockIndex >= 0) { "Unknown SD clock label $clockLabel" }
            val entryWrite = entryMap["write"] as? Boolean ?: throw IllegalArgumentException("Invalid SD clock write flag")
            val entryValue = SdHostSnapshotSupport.parseUnsigned16(entryMap["value"], "clockAccess.value")
            val entryTick = SdHostSnapshotSupport.parseIntegralLong(entryMap["tick"], "clockAccess.tick")
            require(entryTick >= 0L) { "Negative SD clock access tick" }
            val entryCore = if (entryMap["core"] == null) -1 else SdHostSnapshotSupport.parseIntegralInt(entryMap["core"], "clockAccess.core").also { parsed -> require(parsed >= 0) }
            val entryPc = if (entryMap["pcVirtual"] == null) -1L else SdHostSnapshotSupport.parseIntegralLong(entryMap["pcVirtual"], "clockAccess.pcVirtual").also { parsed -> require(parsed >= 0L) }
            ParsedClockAccess(clockIndex, entryWrite, entryValue, entryTick, entryCore, entryPc)
        }
        return ParsedSnapshot(rawScalars, parsedControl, nextWords, nextConsumed, nextReads, nextWrites, nextCount, parsedAccesses)
    }
    internal fun applyParsedSnapshot(parsed: ParsedSnapshot) {
        restoreScalarValues(parsed.rawScalars, SdHostSnapshotSupport.DESCRIPTOR)
        controls.applyParsedSnapshot(parsed.controlSnapshot)
        parsed.storedWords.copyInto(words)
        parsed.consumedCounts.copyInto(consumed)
        reads = parsed.readCount
        writes = parsed.writeCount
        count = parsed.accessCount
        indices.fill(0)
        directions.fill(false)
        data.fill(0L)
        ticks.fill(0L)
        cores.fill(-1)
        pcs.fill(-1L)
        val startSequence = maxOf(0L, parsed.accessCount - 256)
        for ((offset, accessEntry) in parsed.accesses.withIndex()) {
            val ringSlot = ((startSequence + offset) % 256).toInt()
            indices[ringSlot] = accessEntry.clockIndex
            directions[ringSlot] = accessEntry.write
            data[ringSlot] = accessEntry.value
            ticks[ringSlot] = accessEntry.tick
            cores[ringSlot] = accessEntry.core
            pcs[ringSlot] = accessEntry.pcVirtual
        }
    }
    override fun restoreSnapshot(data: Map<String, Any?>) {
        applyParsedSnapshot(parseSnapshot(data))
    }
    override fun state(): Map<String, Any?> = super.state() + mapOf(
        "enabled" to true, "closed" to closed, "controlState" to controls.state(),
        "clocks" to (0..3).associate { index -> key(index) to mapOf(
            "initialWord" to initial[index], "storedWord" to words[index],
            "bit1Samples" to samples[index].map { if (it < 0) null else it.toInt() },
            "consumedSamples" to consumed[index]) },
        "inputCanonicalSha256" to inputSha,
        "clockReadCount" to reads, "clockWriteCount" to writes, "clockAccessCount" to count,
        "clockAccessCapacity" to 256, "clockAccessLogTruncated" to (count > 256),
        "clockAccesses" to (maxOf(0, count - 256) until count).map { n ->
            val at = (n % 256).toInt()
            mapOf("clock" to key(indices[at]), "write" to directions[at], "size" to 2,
                "value" to data[at], "tick" to ticks[at], "core" to cores[at].takeIf { it >= 0 },
                "pcVirtual" to pcs[at].takeIf { it >= 0 })
        },
        "cardProtocolQualified" to false,
        "basis" to if (phy)
            "Finite explicit per-read bit1 samples and full-u16 stored-write retention HYP; no repeat-last/default-ready, physical stable-time, reset, IRQ, DMA or card protocol. controlState retains the actual v5 profile of this same frozen input."
        else "Finite explicit per-read bit1 samples and full-u16 stored-write retention HYP; no repeat-last/default-ready, physical stable-time, reset, IRQ, DMA or card protocol. controlState is the v2 projection of this same frozen input."
    )
}
