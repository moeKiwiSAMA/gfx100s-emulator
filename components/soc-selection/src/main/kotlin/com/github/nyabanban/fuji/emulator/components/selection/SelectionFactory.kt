package com.github.nyabanban.fuji.emulator.components.selection

import com.github.nyabanban.fuji.emulator.api.*

/** Explicit field retention only; physical signal identity, reset, clocks and readiness are unknown. */
class SelectionFactory : ComponentFactory {
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = Selection(context, config)

    companion object {
        const val PROFILE = "selection-special-retention-experimental-v1"
        private val DESCRIPTOR = ComponentDescriptor("soc-selection", "0.0.1", settings = listOf(
            Setting("profile", ValueKind.TEXT, "disabled", choices = listOf("disabled", PROFILE), live = false),
            Setting("inputFile", ValueKind.TEXT, "", live = false)))
        private val SNAPSHOT_KEYS = setOf(
            "schema", "componentId", "scalars", "enabled", "inputs",
            "words", "readCount", "writeCount", "accessCount", "accesses",
        )
        private val INPUT_KEYS = setOf("selectionReset", "special52Reset", "special53Reset")
        private val ACCESS_KEYS = setOf("port", "write", "value", "tick", "core", "pcVirtual")
        private val masks = longArrayOf(0x1000000,0x40000,0x20000,0x10000,0xc00,0x300,0xc0,0x30,4,2,1)
        private val encodings = longArrayOf(0,0x1000000,0,0x40000,0,0x20000,0,0x10000,0,0x400,0x800,0,0x100,0x200,0,0x40,0x80,0,0x10,0x20,0,4,0,2,0,1)
        private val choices = arrayOf(intArrayOf(0,1),intArrayOf(2,3),intArrayOf(4,5),intArrayOf(6,7),intArrayOf(8,9,10),intArrayOf(11,12,13),intArrayOf(14,15,16),intArrayOf(17,18,19),intArrayOf(20,21),intArrayOf(22,23),intArrayOf(24,25))
        private const val combinedMask = 0x01070ff7L
        private fun validSelection(value: Long) = value in 0..0xffffffffL && value and combinedMask.inv() == 0L &&
            masks.indices.all { field -> choices[field].any { encodings[it] == value and masks[field] } }
        private fun validSpecial(value: Long, minimum: Long, maximum: Long) = value in 0..0x1ff && value and 255 in minimum..maximum
        private fun unsigned(value: Any?): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "selection input requires an unsigned 32-bit integer" }
            return (value as Number).toLong().also { require(it in 0..0xffffffffL) }
        }
        private fun snapshotLong(value: Any?, field: String, minimum: Long = Long.MIN_VALUE, maximum: Long = Long.MAX_VALUE): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Snapshot field $field requires integer" }
            val converted = (value as? Number)?.toLong() ?: throw IllegalArgumentException("Snapshot field $field requires integer")
            require(converted in minimum..maximum) { "Snapshot field $field out of range" }
            return converted
        }
        private fun snapshotInt(value: Any?, field: String, minimum: Int = Int.MIN_VALUE, maximum: Int = Int.MAX_VALUE): Int =
            snapshotLong(value, field, minimum.toLong(), maximum.toLong()).toInt()
        private fun snapshotBoolean(value: Any?, field: String): Boolean =
            value as? Boolean ?: throw IllegalArgumentException("Snapshot field $field requires boolean")
        private fun snapshotString(value: Any?, field: String): String =
            value as? String ?: throw IllegalArgumentException("Snapshot field $field requires string")
        private fun snapshotList(value: Any?, field: String): List<*> =
            value as? List<*> ?: throw IllegalArgumentException("Snapshot field $field requires list")
        private fun snapshotMap(value: Any?, field: String): Map<*, *> {
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all { key -> key is String }) { "Snapshot field $field requires string keys" }
            return map
        }
    }
    private class Selection(context: ComponentContext, config: Map<String, Any>) : ScalarComponent("soc-selection", context, config), StatefulComponent, NativeMmioMirrorPort {
        private val active = config.getValue("profile") == PROFILE
        private val input = LongArray(3)
        private val words = LongArray(3)
        private val names = listOf("selection", "special52", "special53")
        private val ports = names.map { "$id.$it" }
        private val mirrorHandles = arrayOfNulls<MmioMirrorHandle>(3)
        private fun syncMirror(index: Int) {
            mirrorHandles[index]?.writeSlot(0, words[index].toInt())
        }
        override fun describeMmioMirrorWindows(): List<MmioMirrorWindowSpec> =
            if (!active) emptyList()
            else ports.map { portName -> MmioMirrorWindowSpec(portName, 4L, listOf(MmioMirrorSlotSpec(0L, 4))) }
        override fun attachMmioMirror(portName: String, handle: MmioMirrorHandle) {
            val idx = ports.indexOf(portName)
            if (idx >= 0) {
                mirrorHandles[idx] = handle
                syncMirror(idx)
            }
        }
        override fun detachMmioMirrors() {
            for (i in mirrorHandles.indices) {
                mirrorHandles[i]?.invalidateAll()
                mirrorHandles[i] = null
            }
        }
        private val retained = 512
        private val accessPorts = IntArray(retained)
        private val accessWrites = BooleanArray(retained)
        private val accessValues = LongArray(retained)
        private val accessTicks = LongArray(retained)
        private val accessCores = IntArray(retained) { -1 }
        private val accessPcs = LongArray(retained) { -1 }
        private var accessCount = 0L
        private var readCount = 0L
        private var writeCount = 0L
        init {
            val file = config.getValue("inputFile") as String
            require(config.getValue("profile") in listOf("disabled", PROFILE) && active == file.isNotBlank()) { "explicit selection input file required when enabled" }
            if (active) {
                val data = context.readData(file)
                require(data.keys == setOf("schema", "profile", "selectionReset", "special52Reset", "special53Reset") && data["schema"] == 1 && data["profile"] == PROFILE) { "invalid selection input schema" }
                input[0] = unsigned(data["selectionReset"]); input[1] = unsigned(data["special52Reset"]); input[2] = unsigned(data["special53Reset"])
                require(valid(0,input[0]) && valid(1,input[1]) && valid(2,input[2])) { "explicit selection/special initial value outside DAT field domain" }
                input.copyInto(words)
                names.indices.forEach { i -> context.ports.register(id, ports[i], MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 75
                    private fun check(offset: Long, size: Int) { require(offset == 0L && size == 4) { "selection/special requires its exact aligned word" } }
                    override fun read(offset: Long, size: Int): Long {
                        check(offset,size); val tick = context.clock.ticks(); preflight(tick)
                        val result = words[i]; record(i,false,result,tick); return result
                    }
                    override fun write(offset: Long, size: Int, value: Long) {
                        check(offset,size); require(valid(i,value)) { "selection/special value outside DAT field domain" }
                        val tick = context.clock.ticks(); preflight(tick)
                        words[i] = value; syncMirror(i); record(i,true,value,tick)
                    }
                }) }
            }
        }
        private fun valid(index: Int, value: Long) = when(index) { 0 -> validSelection(value); 1 -> validSpecial(value,2,255); else -> validSpecial(value,0,16) }
        private fun preflight(tick: Long) { require(tick >= 0 && accessCount < Long.MAX_VALUE && readCount < Long.MAX_VALUE && writeCount < Long.MAX_VALUE) { "selection diagnostics overflow or invalid clock" } }
        private fun record(index: Int, write: Boolean, value: Long, tick: Long) {
            val location = context.ports.find("cpu.execution-location", ExecutionLocationSource::class.java)?.current()
            val slot = (accessCount % retained).toInt(); accessPorts[slot]=index; accessWrites[slot]=write; accessValues[slot]=value; accessTicks[slot]=tick
            accessCores[slot]=location?.core ?: -1; accessPcs[slot]=location?.pcVirtual ?: -1
            accessCount++; if(write)writeCount++ else readCount++
        }
        override fun additionalMmioPorts() = if(active) ports else emptyList()
        override fun state(): Map<String, Any?> = super.state() + mapOf(
            "enabled" to active, "inputs" to if(active) mapOf("selectionReset" to input[0],"special52Reset" to input[1],"special53Reset" to input[2]) else null,
            "words" to if(active) names.indices.associate { names[it] to words[it] } else emptyMap<String,Long>(),
            "masks" to masks.toList(), "encodings" to encodings.toList(), "eligible" to choices.map { it.toList() }, "combinedMask" to combinedMask,
            "readCount" to readCount, "writeCount" to writeCount, "accessCount" to accessCount, "accessCapacity" to retained, "accessLogTruncated" to (accessCount > retained),
            "accesses" to (maxOf(0L,accessCount-retained) until accessCount).map { n -> val i=(n%retained).toInt(); mapOf(
                "port" to ports[accessPorts[i]], "write" to accessWrites[i], "value" to accessValues[i], "tick" to accessTicks[i],
                "core" to accessCores[i].takeIf { it >= 0 }, "pcVirtual" to accessPcs[i].takeIf { it >= 0 }) },
            "basis" to "Selected-DAT field domains and explicit unmeasured initial values; passive retention only, no clock/reset/ready/IRQ/DMA effects")

        override fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "scalars" to saveScalarValues(),
            "enabled" to active,
            "inputs" to if (active) linkedMapOf(
                "selectionReset" to input[0],
                "special52Reset" to input[1],
                "special53Reset" to input[2],
            ) else null,
            "words" to if (active) names.indices.associate { slot -> names[slot] to words[slot] } else emptyMap<String, Long>(),
            "readCount" to readCount,
            "writeCount" to writeCount,
            "accessCount" to accessCount,
            "accesses" to (maxOf(0L, accessCount - retained) until accessCount).map { sequence ->
                val ringSlot = (sequence % retained).toInt()
                linkedMapOf(
                    "port" to ports[accessPorts[ringSlot]],
                    "write" to accessWrites[ringSlot],
                    "value" to accessValues[ringSlot],
                    "tick" to accessTicks[ringSlot],
                    "core" to accessCores[ringSlot].takeIf { coreValue -> coreValue >= 0 },
                    "pcVirtual" to accessPcs[ringSlot].takeIf { pcValue -> pcValue >= 0 },
                )
            },
        )

        override fun restoreSnapshot(data: Map<String, Any?>) {
            require(data.keys == SNAPSHOT_KEYS) { "Unexpected snapshot keys for $id" }
            require(snapshotInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = snapshotMap(data["scalars"], "scalars")
            require(scalarsRaw.keys == DESCRIPTOR.settings.map { setting -> setting.key }.toSet()) {
                "Scalar setting keys differ in snapshot for $id"
            }
            val previousValues = values
            val nextWords = LongArray(3)
            val nextAccessPorts = IntArray(retained)
            val nextAccessWrites = BooleanArray(retained)
            val nextAccessValues = LongArray(retained)
            val nextAccessTicks = LongArray(retained)
            val nextAccessCores = IntArray(retained) { -1 }
            val nextAccessPcs = LongArray(retained) { -1L }
            val nextReadCount: Long
            val nextWriteCount: Long
            val nextAccessCount: Long
            try {
                restoreScalarValues(scalarsRaw, DESCRIPTOR)
                require(snapshotBoolean(data["enabled"], "enabled") == active) { "Snapshot enabled flag differs for $id" }
                nextReadCount = snapshotLong(data["readCount"], "readCount", 0L, Long.MAX_VALUE)
                nextWriteCount = snapshotLong(data["writeCount"], "writeCount", 0L, Long.MAX_VALUE)
                nextAccessCount = snapshotLong(data["accessCount"], "accessCount", 0L, Long.MAX_VALUE)
                require(try { Math.addExact(nextReadCount, nextWriteCount) == nextAccessCount } catch (_: ArithmeticException) { false }) {
                    "Selection readCount + writeCount must equal accessCount"
                }
                val wordsMap = snapshotMap(data["words"], "words")
                val accessRows = snapshotList(data["accesses"], "accesses")
                if (!active) {
                    require(data["inputs"] == null) { "Disabled selection snapshot must have null inputs" }
                    require(wordsMap.isEmpty()) { "Disabled selection snapshot must have empty words" }
                    require(nextAccessCount == 0L && accessRows.isEmpty()) { "Disabled selection snapshot cannot carry accesses" }
                } else {
                    val inputsMap = snapshotMap(data["inputs"], "inputs")
                    require(inputsMap.keys == INPUT_KEYS) { "Unexpected selection snapshot inputs keys" }
                    require(
                        snapshotLong(inputsMap["selectionReset"], "inputs.selectionReset", 0L, 0xffffffffL) == input[0] &&
                            snapshotLong(inputsMap["special52Reset"], "inputs.special52Reset", 0L, 0xffffffffL) == input[1] &&
                            snapshotLong(inputsMap["special53Reset"], "inputs.special53Reset", 0L, 0xffffffffL) == input[2]
                    ) { "Selection snapshot reset inputs differ from live configuration" }
                    require(wordsMap.keys == names.toSet()) { "Selection snapshot words keys mismatch" }
                    for (slot in names.indices) {
                        val wordName = names[slot]
                        val wordValue = snapshotLong(wordsMap[wordName], "words.$wordName", 0L, 0xffffffffL)
                        require(valid(slot, wordValue)) { "Selection word $wordName outside DAT field domain" }
                        nextWords[slot] = wordValue
                    }
                    require(accessRows.size == minOf(nextAccessCount, retained.toLong()).toInt()) {
                        "Selection accesses size mismatch"
                    }
                    val startSequence = maxOf(0L, nextAccessCount - retained)
                    var observedWrites = 0L
                    var observedReads = 0L
                    accessRows.forEachIndexed { rowOffset, rawEntry ->
                        val entryMap = snapshotMap(rawEntry, "accesses[$rowOffset]")
                        require(entryMap.keys == ACCESS_KEYS) { "Unexpected selection access entry keys" }
                        val portName = snapshotString(entryMap["port"], "accesses[$rowOffset].port")
                        val portIndex = ports.indexOf(portName)
                        require(portIndex in 0..2) { "Unknown selection port in snapshot access entry" }
                        val writeFlag = snapshotBoolean(entryMap["write"], "accesses[$rowOffset].write")
                        if (writeFlag) observedWrites++ else observedReads++
                        val entryValue = snapshotLong(entryMap["value"], "accesses[$rowOffset].value", 0L, 0xffffffffL)
                        require(valid(portIndex, entryValue)) { "Selection access entry value outside DAT field domain" }
                        val entryTick = snapshotLong(entryMap["tick"], "accesses[$rowOffset].tick", 0L, Long.MAX_VALUE)
                        val entryCore = if (entryMap["core"] == null) -1 else snapshotInt(entryMap["core"], "accesses[$rowOffset].core", 0, Int.MAX_VALUE)
                        val entryPc = if (entryMap["pcVirtual"] == null) -1L else snapshotLong(entryMap["pcVirtual"], "accesses[$rowOffset].pcVirtual", 0L, Long.MAX_VALUE)
                        val ringSlot = ((startSequence + rowOffset) % retained).toInt()
                        nextAccessPorts[ringSlot] = portIndex
                        nextAccessWrites[ringSlot] = writeFlag
                        nextAccessValues[ringSlot] = entryValue
                        nextAccessTicks[ringSlot] = entryTick
                        nextAccessCores[ringSlot] = entryCore
                        nextAccessPcs[ringSlot] = entryPc
                    }
                    if (nextAccessCount <= retained) {
                        require(observedWrites == nextWriteCount && observedReads == nextReadCount) {
                            "Selection retained access direction counts disagree with totals"
                        }
                    } else {
                        require(observedWrites <= nextWriteCount && observedReads <= nextReadCount) {
                            "Selection retained access direction counts exceed totals"
                        }
                    }
                }
            } catch (failure: Throwable) {
                values = previousValues
                throw failure
            }
            nextWords.copyInto(words)
            for (i in 0..2) syncMirror(i)
            nextAccessPorts.copyInto(accessPorts)
            nextAccessWrites.copyInto(accessWrites)
            nextAccessValues.copyInto(accessValues)
            nextAccessTicks.copyInto(accessTicks)
            nextAccessCores.copyInto(accessCores)
            nextAccessPcs.copyInto(accessPcs)
            readCount = nextReadCount
            writeCount = nextWriteCount
            accessCount = nextAccessCount
        }
    }
}
