package com.github.nyabanban.fuji.emulator.components.sdhost

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Explicit finite initialization words; no commands, card response or IRQ is synthesized. */
internal class SdInitHost(
    context: ComponentContext,
    config: Map<String, Any>,
    private val hardwareClockStable: Boolean = false,
) :
    ScalarComponent("soc-sd-host", context, config), StatefulComponent {
    companion object {
        val fields = listOf("capability6244", "present4024", "present6224", "signal6238", "block4006", "host6228", "host4028")
        private val widths = intArrayOf(4, 4, 4, 2, 2, 2, 2)
        fun port(slot: Int, field: Int) = "soc-sd-host." + fields[field] + "-" + slot
        private fun integer(value: Any?): Long {
            require(value is Byte || value is Short || value is Int || value is Long)
            return (value as Number).toLong()
        }
    }
    private val phy = config["profile"] == SdHostFactory.PHY_PROFILE
    private val initial = LongArray(14)
    private val words = LongArray(14)
    private val clockHost: SdClockHost
    private val inputSha: String
    private var closed = false
    private var reads = 0L
    private var writes = 0L
    private var count = 0L
    private val indices = IntArray(256)
    private val directions = BooleanArray(256)
    private val data = LongArray(256)
    private val ticks = LongArray(256)
    private val cores = IntArray(256) { -1 }
    private val pcs = LongArray(256) { -1 }

    internal data class ParsedInitAccess(
        val flatIndex: Int,
        val write: Boolean,
        val value: Long,
        val tick: Long,
        val core: Int,
        val pcVirtual: Long,
    )

    internal data class ParsedSnapshot(
        val rawScalars: Map<*, *>,
        val clockSnapshot: SdClockHost.ParsedSnapshot,
        val nextWords: LongArray,
        val readCount: Long,
        val writeCount: Long,
        val accessCount: Long,
        val accesses: List<ParsedInitAccess>,
    )

    init {
        require(config["profile"] == SdHostFactory.INIT_PROFILE || phy)
        val file = config["inputFile"] as String
        require(file.isNotBlank()) { "Explicit SD initialization input required" }
        val input = context.readData(file)
        require(input.keys == setOf("schema", "profile", "initialRegisters", "clocks", "initWords") &&
            integer(input["schema"]) == 1L && input["profile"] == config["profile"])
        val raw = input["initWords"] as? Map<*, *> ?: error("Both explicit init word sets required")
        require(raw.keys == setOf("0", "1"))
        for (slot in 0..1) {
            val row = raw[slot.toString()] as? Map<*, *> ?: error("Init slot missing")
            require(row.keys == fields.toSet())
            for ((field, name) in fields.withIndex()) {
                val value = integer(row[name])
                require(value in 0..(if (widths[field] == 2) 65535L else 0xffffffffL))
                initial[slot * 7 + field] = value
            }
        }
        initial.copyInto(words)
        val oldClaims = (0..1).map { SdControlHost.port(it, 0) } +
            (0..1).flatMap { slot -> (1..4).map { SdControlHost.port(slot, it) } } + (0..3).map { SdClockHost.port(it) }
        val newClaims = (0..1).flatMap { slot -> fields.indices.map { port(slot, it) } }
        require((oldClaims + newClaims).all { context.ports.find(it, MmioDevice::class.java) == null }) { "SD init port alias" }
        val clockProfile = if (phy) SdHostFactory.PHY_PROFILE else SdHostFactory.CLOCK_PROFILE
        val clockInput = mapOf("schema" to 1, "profile" to clockProfile,
            "initialRegisters" to input.getValue("initialRegisters"), "clocks" to input.getValue("clocks"))
        clockHost = SdClockHost(context, config + ("profile" to clockProfile), clockInput, hardwareClockStable)
        val clockDigest = java.util.HexFormat.of().parseHex(clockHost.state()["inputCanonicalSha256"] as String)
        val canonical = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN).put(clockDigest)
        for (slot in 0..1) for (field in fields.indices) {
            val value = initial[slot * 7 + field]
            if (widths[field] == 2) canonical.putShort(value.toShort()) else canonical.putInt(value.toInt())
        }
        inputSha = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.array()))
        for (slot in 0..1) for (field in fields.indices) {
            val index = slot * 7 + field
            context.ports.register(id, port(slot, field), MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 85
                override val clockIndex = 14 + index
                private fun checkAccess(offset: Long, size: Int, write: Boolean) {
                    check(!closed) { "SD host closed" }
                    require(offset == 0L && size == widths[field] &&
                        (if (write) field >= 3 else field != 3 && field != 4)) { "Unsupported SD init width/direction/offset" }
                }
                override fun read(offset: Long, size: Int): Long {
                    checkAccess(offset, size, false)
                    val tick = preflight(); val location = location(); val value = words[index]
                    record(index, false, value, tick, location)
                    return value
                }
                override fun write(offset: Long, size: Int, value: Long) {
                    checkAccess(offset, size, true)
                    require(value in 0..65535)
                    val tick = preflight(); val location = location()
                    words[index] = value
                    record(index, true, value, tick, location)
                }
            })
        }
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
    private fun snapshot(values: LongArray) = (0..1).associate { slot -> slot.toString() to
        fields.mapIndexed { field, name -> name to values[slot * 7 + field] }.toMap() }
    override fun start() { check(!closed); clockHost.start() }
    override fun close() { closed = true; clockHost.close() }
    override fun validateSettings(settings: Map<String, Any>) {
        check(!closed)
        require(settings.all { values[it.key] == it.value }) { "SD init settings require restart" }
    }
    override fun applySettings(settings: Map<String, Any>) { validateSettings(settings) }
    override fun additionalMmioPorts() = clockHost.additionalMmioPorts() +
        (0..1).flatMap { slot -> fields.indices.map { port(slot, it) } }
    override fun saveSnapshot(): Map<String, Any?> {
        require(!closed) { "SD host closed" }
        return linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "kind" to "init",
            "scalarValues" to saveScalarValues(),
            "inputCanonicalSha256" to inputSha,
            "clockSnapshot" to clockHost.saveSnapshot(),
            "initWords" to snapshot(words),
            "initReadCount" to reads,
            "initWriteCount" to writes,
            "initAccessCount" to count,
            "initAccesses" to (maxOf(0L, count - 256) until count).map { sequence ->
                val ringSlot = (sequence % 256).toInt()
                val flatIndex = indices[ringSlot]
                linkedMapOf(
                    "slot" to flatIndex / 7,
                    "field" to fields[flatIndex % 7],
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
        val snapshotData = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD init snapshot")
        val expectedKeys = setOf(
            "schema", "componentId", "kind", "scalarValues", "inputCanonicalSha256",
            "clockSnapshot", "initWords", "initReadCount", "initWriteCount", "initAccessCount", "initAccesses",
        )
        require(snapshotData.keys == expectedKeys) { "Unexpected SD init snapshot keys" }
        require(SdHostSnapshotSupport.parseIntegralInt(snapshotData["schema"], "schema") == snapshotSchemaVersion) {
            "Unsupported SD init snapshot schema"
        }
        require(snapshotData["componentId"] == id && snapshotData["kind"] == "init") { "Mismatched SD init snapshot identity" }
        val rawScalars = SdHostSnapshotSupport.validateScalarValues(snapshotData["scalarValues"], values, id)
        require(snapshotData["inputCanonicalSha256"] == inputSha) { "SD init inputCanonicalSha256 differs" }
        val parsedClock = clockHost.parseSnapshot(snapshotData["clockSnapshot"])
        val rawWords = snapshotData["initWords"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid initWords map")
        require(rawWords.keys == setOf("0", "1")) { "SD init requires slot 0 and 1 words" }
        val nextWords = LongArray(14)
        for (slotIndex in 0..1) {
            val slotRow = rawWords[slotIndex.toString()] as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD init slot $slotIndex")
            require(slotRow.keys == fields.toSet()) { "Unexpected SD init fields" }
            for ((fieldIndex, fieldName) in fields.withIndex()) {
                val flatIndex = slotIndex * 7 + fieldIndex
                val parsedValue = SdHostSnapshotSupport.parseIntegralLong(slotRow[fieldName], "initWords.$slotIndex.$fieldName")
                if (fieldIndex < 3) {
                    require(parsedValue == initial[flatIndex]) { "Read-only SD init word $fieldName differs from frozen initial value" }
                } else {
                    require(parsedValue in 0L..0xffffL) { "Writable SD init word $fieldName out of u16 range" }
                }
                nextWords[flatIndex] = parsedValue
            }
        }
        val nextReads = SdHostSnapshotSupport.parseIntegralLong(snapshotData["initReadCount"], "initReadCount")
        val nextWrites = SdHostSnapshotSupport.parseIntegralLong(snapshotData["initWriteCount"], "initWriteCount")
        val nextCount = SdHostSnapshotSupport.parseIntegralLong(snapshotData["initAccessCount"], "initAccessCount")
        require(nextReads >= 0L && nextWrites >= 0L && nextCount >= 0L) { "Negative counter in SD init snapshot" }
        val totalAccesses = try { Math.addExact(nextReads, nextWrites) } catch (_: ArithmeticException) {
            throw IllegalArgumentException("SD init counter overflow")
        }
        require(totalAccesses == nextCount) { "SD initReadCount + initWriteCount must equal initAccessCount" }
        val rawAccesses = snapshotData["initAccesses"] as? List<*> ?: throw IllegalArgumentException("Invalid SD initAccesses")
        require(rawAccesses.size == minOf(nextCount, 256L).toInt()) { "SD init access log size disagrees with initAccessCount" }
        val parsedAccesses = rawAccesses.map { rawEntry ->
            val entryMap = rawEntry as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD init access entry")
            require(entryMap.keys == setOf("slot", "field", "write", "value", "tick", "core", "pcVirtual")) {
                "Unexpected SD init access entry keys"
            }
            val entrySlot = SdHostSnapshotSupport.parseIntegralInt(entryMap["slot"], "initAccess.slot")
            require(entrySlot in 0..1) { "Invalid SD init access slot" }
            val fieldName = entryMap["field"] as? String ?: throw IllegalArgumentException("Invalid SD init field")
            val fieldIndex = fields.indexOf(fieldName)
            require(fieldIndex >= 0) { "Unknown SD init field $fieldName" }
            val entryWrite = entryMap["write"] as? Boolean ?: throw IllegalArgumentException("Invalid SD init write flag")
            require(if (entryWrite) fieldIndex >= 3 else (fieldIndex != 3 && fieldIndex != 4)) {
                "Unsupported SD init access direction for $fieldName"
            }
            val maxEntryValue = if (widths[fieldIndex] == 2) 0xffffL else 0xffffffffL
            val entryValue = SdHostSnapshotSupport.parseIntegralLong(entryMap["value"], "initAccess.value")
            require(entryValue in 0L..maxEntryValue) { "SD init access value out of range" }
            val entryTick = SdHostSnapshotSupport.parseIntegralLong(entryMap["tick"], "initAccess.tick")
            require(entryTick >= 0L) { "Negative SD init access tick" }
            val entryCore = if (entryMap["core"] == null) -1 else SdHostSnapshotSupport.parseIntegralInt(entryMap["core"], "initAccess.core").also { parsed -> require(parsed >= 0) }
            val entryPc = if (entryMap["pcVirtual"] == null) -1L else SdHostSnapshotSupport.parseIntegralLong(entryMap["pcVirtual"], "initAccess.pcVirtual").also { parsed -> require(parsed >= 0L) }
            ParsedInitAccess(entrySlot * 7 + fieldIndex, entryWrite, entryValue, entryTick, entryCore, entryPc)
        }
        return ParsedSnapshot(rawScalars, parsedClock, nextWords, nextReads, nextWrites, nextCount, parsedAccesses)
    }
    internal fun applyParsedSnapshot(parsed: ParsedSnapshot) {
        restoreScalarValues(parsed.rawScalars, SdHostSnapshotSupport.DESCRIPTOR)
        clockHost.applyParsedSnapshot(parsed.clockSnapshot)
        parsed.nextWords.copyInto(words)
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
            indices[ringSlot] = accessEntry.flatIndex
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
        "enabled" to true, "closed" to closed, "clockState" to clockHost.state(),
        "initialInitWords" to snapshot(initial), "initWords" to snapshot(words),
        "inputCanonicalSha256" to inputSha,
        "initReadCount" to reads, "initWriteCount" to writes, "initAccessCount" to count,
        "initAccessCapacity" to 256, "initAccessLogTruncated" to (count > 256),
        "initAccesses" to (maxOf(0, count - 256) until count).map { n ->
            val at = (n % 256).toInt(); val index = indices[at]; val field = index % 7
            mapOf("slot" to index / 7, "field" to fields[field], "write" to directions[at],
                "size" to widths[field], "value" to data[at], "tick" to ticks[at],
                "core" to cores[at].takeIf { it >= 0 }, "pcVirtual" to pcs[at].takeIf { it >= 0 })
        },
        "cardProtocolQualified" to false,
        "basis" to if (phy)
            "DAT finite init accesses; explicit u32 inputs and u16 write retention HYP. SOURCE SDHCI/UHS-II field candidates only, not measured GFX values, contacts, power, IRQ, DMA or card protocol. clockState retains the actual v5 profile of the same frozen input."
        else "DAT finite init accesses; explicit u32 inputs and u16 write retention HYP. SOURCE SDHCI/UHS-II field candidates only, not measured GFX values, contacts, power, IRQ, DMA or card protocol. clockState is the v3 projection of the same frozen input."
    )
}
