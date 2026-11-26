package com.github.nyabanban.fuji.emulator.components.sdhost

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Finite DAT software accesses. Every initial value and retention law remains HYP. */
internal class SdControlHost(context: ComponentContext, config: Map<String, Any>, inputOverride: Map<String, Any?>? = null) :
    ScalarComponent("soc-sd-host", context, config), StatefulComponent {
    companion object {
        val fields = listOf("word80", "control623e", "mixed6078", "control403e", "word60ec")
        private val widths = intArrayOf(4, 2, 4, 2, 4)
        fun port(slot: Int, field: Int) = "soc-sd-host." + fields[field] + "-" + slot
        private fun integer(value: Any?): Long {
            require(value is Byte || value is Short || value is Int || value is Long)
            return (value as Number).toLong()
        }
    }
    private val phy = config["profile"] == SdHostFactory.PHY_PROFILE
    private val initial = LongArray(10)
    private val registers = LongArray(10)
    private var closed = false
    private var count = 0L
    private var reads = 0L
    private var writes = 0L
    private val indices = IntArray(256)
    private val directions = BooleanArray(256)
    private val sizes = IntArray(256)
    private val data = LongArray(256)
    private val ticks = LongArray(256)
    private val cores = IntArray(256) { -1 }
    private val pcs = LongArray(256) { -1 }
    private val claims = (0..1).map { port(it, 0) } +
        (0..1).flatMap { slot -> (1..4).map { port(slot, it) } }
    private val inputSha: String

    internal data class ParsedControlAccess(
        val index: Int,
        val write: Boolean,
        val size: Int,
        val value: Long,
        val tick: Long,
        val core: Int,
        val pcVirtual: Long,
    )

    internal data class ParsedSnapshot(
        val rawScalars: Map<*, *>,
        val nextRegisters: LongArray,
        val readCount: Long,
        val writeCount: Long,
        val accessCount: Long,
        val accesses: List<ParsedControlAccess>,
    )

    init {
        require(config["profile"] == SdHostFactory.CONTROL_PROFILE || phy)
        val file = config["inputFile"] as String
        require(file.isNotBlank()) { "Explicit SD control inputs required" }
        val input = inputOverride ?: context.readData(file)
        require(input.keys == setOf("schema", "profile", "initialRegisters") &&
            integer(input["schema"]) == 1L && input["profile"] == config["profile"])
        val raw = input["initialRegisters"] as? Map<*, *> ?: error("Both explicit slots required")
        require(raw.keys == setOf("0", "1"))
        for (slot in 0..1) {
            val values = raw[slot.toString()] as? Map<*, *> ?: error("Explicit register set required")
            require(values.keys == fields.toSet())
            fields.forEachIndexed { field, name ->
                val value = integer(values[name])
                require(value in 0..if (widths[field] == 2) 0xffffL else 0xffffffffL)
                initial[slot * 5 + field] = value
            }
        }
        initial.copyInto(registers)
        // Slot-major, field order above, natural field widths, little endian: 32 bytes total.
        val canonical = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        for (slot in 0..1) for (field in fields.indices) {
            val value = initial[slot * 5 + field]
            if (widths[field] == 2) canonical.putShort(value.toShort()) else canonical.putInt(value.toInt())
        }
        inputSha = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.array()))
        require(claims.all { context.ports.find(it, MmioDevice::class.java) == null }) { "SD control port alias" }
        for (slot in 0..1) for (field in fields.indices) {
            val index = slot * 5 + field
            context.ports.register(id, port(slot, field), MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 85
                override val clockIndex = index
                private fun checkAccess(offset: Long, size: Int, write: Boolean) {
                    check(!closed) { "SD host closed" }
                    val expected = if (write && field == 2) 2 else widths[field]
                    require(offset == 0L && (size == expected || phy && write && field == 2 && size == 4) &&
                        (write || field != 4)) {
                        "Unsupported SD control width/direction/offset"
                    }
                }
                override fun read(offset: Long, size: Int): Long {
                    checkAccess(offset, size, false)
                    val tick = preflight()
                    val location = location()
                    val value = registers[index]
                    record(index, false, size, value, tick, location)
                    return value
                }
                override fun write(offset: Long, size: Int, value: Long) {
                    checkAccess(offset, size, true)
                    require(value in 0..if (size == 2) 0xffffL else 0xffffffffL)
                    val tick = preflight()
                    val location = location()
                    registers[index] = if (field == 2 && size == 2) (registers[index] and 0xffff0000L) or value else value
                    record(index, true, size, value, tick, location)
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
    private fun record(index: Int, write: Boolean, size: Int, value: Long, tick: Long, location: ExecutionLocation?) {
        val at = (count % 256).toInt()
        indices[at] = index; directions[at] = write; sizes[at] = size; data[at] = value
        ticks[at] = tick; cores[at] = location?.core ?: -1; pcs[at] = location?.pcVirtual ?: -1
        count++
        if (write) writes++ else reads++
    }
    private fun snapshot(data: LongArray) = (0..1).associate { slot ->
        slot.toString() to fields.mapIndexed { field, name -> name to data[slot * 5 + field] }.toMap()
    }
    override fun start() { check(!closed) }
    override fun close() { closed = true }
    override fun validateSettings(settings: Map<String, Any>) {
        check(!closed)
        require(settings.all { values[it.key] == it.value }) { "SD settings require restart" }
    }
    override fun applySettings(settings: Map<String, Any>) { validateSettings(settings) }
    override fun additionalMmioPorts() = claims
    override fun saveSnapshot(): Map<String, Any?> {
        require(!closed) { "SD host closed" }
        return linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "kind" to "control",
            "scalarValues" to saveScalarValues(),
            "inputCanonicalSha256" to inputSha,
            "registers" to snapshot(registers),
            "readCount" to reads,
            "writeCount" to writes,
            "accessCount" to count,
            "accesses" to (maxOf(0L, count - 256) until count).map { sequence ->
                val ringSlot = (sequence % 256).toInt()
                val registerIndex = indices[ringSlot]
                linkedMapOf(
                    "slot" to registerIndex / 5,
                    "field" to fields[registerIndex % 5],
                    "write" to directions[ringSlot],
                    "size" to sizes[ringSlot],
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
        val snapshotData = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD control snapshot")
        val expectedKeys = setOf(
            "schema", "componentId", "kind", "scalarValues", "inputCanonicalSha256",
            "registers", "readCount", "writeCount", "accessCount", "accesses",
        )
        require(snapshotData.keys == expectedKeys) { "Unexpected SD control snapshot keys" }
        require(SdHostSnapshotSupport.parseIntegralInt(snapshotData["schema"], "schema") == snapshotSchemaVersion) {
            "Unsupported SD control snapshot schema"
        }
        require(snapshotData["componentId"] == id && snapshotData["kind"] == "control") { "Mismatched SD control snapshot identity" }
        val rawScalars = SdHostSnapshotSupport.validateScalarValues(snapshotData["scalarValues"], values, id)
        require(snapshotData["inputCanonicalSha256"] == inputSha) { "SD control inputCanonicalSha256 differs" }
        val rawRegisters = snapshotData["registers"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD control registers")
        require(rawRegisters.keys == setOf("0", "1")) { "SD control requires slot 0 and 1 registers" }
        val nextRegisters = LongArray(10)
        for (slotIndex in 0..1) {
            val slotRow = rawRegisters[slotIndex.toString()] as? Map<*, *>
                ?: throw IllegalArgumentException("Invalid SD control register slot $slotIndex")
            require(slotRow.keys == fields.toSet()) { "Unexpected SD control register fields" }
            for ((fieldIndex, fieldName) in fields.withIndex()) {
                val maxWord = if (widths[fieldIndex] == 2) 0xffffL else 0xffffffffL
                val parsedValue = SdHostSnapshotSupport.parseIntegralLong(slotRow[fieldName], "registers.$slotIndex.$fieldName")
                require(parsedValue in 0L..maxWord) { "SD control register $fieldName out of range" }
                val flatIndex = slotIndex * 5 + fieldIndex
                if (!phy && fieldIndex == 2) {
                    require((parsedValue and 0xffff0000L) == (initial[flatIndex] and 0xffff0000L)) {
                        "Legacy mixed6078 high halfword must match frozen initial value"
                    }
                }
                nextRegisters[flatIndex] = parsedValue
            }
        }
        val nextReads = SdHostSnapshotSupport.parseIntegralLong(snapshotData["readCount"], "readCount")
        val nextWrites = SdHostSnapshotSupport.parseIntegralLong(snapshotData["writeCount"], "writeCount")
        val nextCount = SdHostSnapshotSupport.parseIntegralLong(snapshotData["accessCount"], "accessCount")
        require(nextReads >= 0L && nextWrites >= 0L && nextCount >= 0L) { "Negative counter in SD control snapshot" }
        val totalAccesses = try { Math.addExact(nextReads, nextWrites) } catch (_: ArithmeticException) {
            throw IllegalArgumentException("SD control counter overflow")
        }
        require(totalAccesses == nextCount) { "SD control readCount + writeCount must equal accessCount" }
        val rawAccesses = snapshotData["accesses"] as? List<*> ?: throw IllegalArgumentException("Invalid SD control accesses")
        require(rawAccesses.size == minOf(nextCount, 256L).toInt()) { "SD control access log size disagrees with accessCount" }
        val parsedAccesses = rawAccesses.map { rawEntry ->
            val entryMap = rawEntry as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD control access entry")
            require(entryMap.keys == setOf("slot", "field", "write", "size", "value", "tick", "core", "pcVirtual")) {
                "Unexpected SD control access entry keys"
            }
            val entrySlot = SdHostSnapshotSupport.parseIntegralInt(entryMap["slot"], "controlAccess.slot")
            require(entrySlot in 0..1) { "Invalid SD control access slot" }
            val fieldName = entryMap["field"] as? String ?: throw IllegalArgumentException("Invalid SD control access field")
            val fieldIndex = fields.indexOf(fieldName)
            require(fieldIndex >= 0) { "Unknown SD control field $fieldName" }
            val entryWrite = entryMap["write"] as? Boolean ?: throw IllegalArgumentException("Invalid SD control access write flag")
            val entrySize = SdHostSnapshotSupport.parseIntegralInt(entryMap["size"], "controlAccess.size")
            val expectedWidth = if (entryWrite && fieldIndex == 2) 2 else widths[fieldIndex]
            require((entrySize == expectedWidth || (phy && entryWrite && fieldIndex == 2 && entrySize == 4)) && (entryWrite || fieldIndex != 4)) {
                "Invalid SD control access width or direction"
            }
            val maxEntryValue = if (entrySize == 2) 0xffffL else 0xffffffffL
            val entryValue = SdHostSnapshotSupport.parseIntegralLong(entryMap["value"], "controlAccess.value")
            require(entryValue in 0L..maxEntryValue) { "SD control access value out of range" }
            val entryTick = SdHostSnapshotSupport.parseIntegralLong(entryMap["tick"], "controlAccess.tick")
            require(entryTick >= 0L) { "Negative SD control access tick" }
            val entryCore = if (entryMap["core"] == null) -1 else SdHostSnapshotSupport.parseIntegralInt(entryMap["core"], "controlAccess.core").also { parsed -> require(parsed >= 0) }
            val entryPc = if (entryMap["pcVirtual"] == null) -1L else SdHostSnapshotSupport.parseIntegralLong(entryMap["pcVirtual"], "controlAccess.pcVirtual").also { parsed -> require(parsed >= 0L) }
            ParsedControlAccess(entrySlot * 5 + fieldIndex, entryWrite, entrySize, entryValue, entryTick, entryCore, entryPc)
        }
        return ParsedSnapshot(rawScalars, nextRegisters, nextReads, nextWrites, nextCount, parsedAccesses)
    }
    internal fun applyParsedSnapshot(parsed: ParsedSnapshot) {
        restoreScalarValues(parsed.rawScalars, SdHostSnapshotSupport.DESCRIPTOR)
        parsed.nextRegisters.copyInto(registers)
        reads = parsed.readCount
        writes = parsed.writeCount
        count = parsed.accessCount
        indices.fill(0)
        directions.fill(false)
        sizes.fill(0)
        data.fill(0L)
        ticks.fill(0L)
        cores.fill(-1)
        pcs.fill(-1L)
        val startSequence = maxOf(0L, parsed.accessCount - 256)
        for ((offset, accessEntry) in parsed.accesses.withIndex()) {
            val ringSlot = ((startSequence + offset) % 256).toInt()
            indices[ringSlot] = accessEntry.index
            directions[ringSlot] = accessEntry.write
            sizes[ringSlot] = accessEntry.size
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
        "enabled" to true, "closed" to closed,
        "initialRegisters" to snapshot(initial), "registers" to snapshot(registers),
        "inputCanonicalSha256" to inputSha,
        "readCount" to reads, "writeCount" to writes, "accessCount" to count,
        "accessCapacity" to 256, "accessLogTruncated" to (count > 256),
        "accesses" to (maxOf(0, count - 256) until count).map { n ->
            val at = (n % 256).toInt(); val index = indices[at]
            mapOf("slot" to index / 5, "field" to fields[index % 5], "write" to directions[at],
                "size" to sizes[at], "value" to data[at], "tick" to ticks[at],
                "core" to cores[at].takeIf { it >= 0 }, "pcVirtual" to pcs[at].takeIf { it >= 0 })
        },
        "cardProtocolQualified" to false,
        "basis" to if (phy)
            "DAT finite software accesses only; explicit unmeasured inputs/full-width retention and mixed6078 write2 low-lane preservation/write4 full-u32 replacement HYP. No ready/reset/IRQ/DMA/card protocol or block storage."
        else "DAT finite software accesses only; explicit unmeasured inputs/full-width retention and mixed6078 low-lane preservation HYP. No ready/reset/IRQ/DMA/card protocol or block storage."
    )
}
