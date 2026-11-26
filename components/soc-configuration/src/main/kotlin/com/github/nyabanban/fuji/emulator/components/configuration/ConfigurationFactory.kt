package com.github.nyabanban.fuji.emulator.components.configuration

import com.github.nyabanban.fuji.emulator.api.*
import java.util.ArrayDeque

/** FW-007/MD-006. Counts follow pinned descriptors; board metadata alone owns bases. */
class ConfigurationFactory : ComponentFactory {
    companion object {
        private val DESCRIPTOR = ComponentDescriptor("soc-configuration", "0.0.1", settings = listOf(
            Setting("profile", ValueKind.TEXT, "configuration-command2-experimental-v1", choices = listOf("configuration-command2-experimental-v1", PairReconfigurationState.PROFILE), live = false),
            Setting("initialValue", ValueKind.INTEGER, 0L, 0.0, 511.0, live = false),
            Setting("completionTicks", ValueKind.INTEGER, 8L, 1.0, 1000000.0, live = false),
            Setting("baseProfile", ValueKind.TEXT, "disabled", choices = listOf("disabled", BaseParameterState.PROFILE), live = false),
            Setting("baseInputFile", ValueKind.TEXT, "", live = false),
        ))
        private val SNAPSHOT_KEYS = setOf(
            "schema", "componentId", "scalars", "staged", "applied", "written",
            "pending", "eventCount", "events", "baseParameters", "reconfiguration",
        )
        private val PENDING_KEYS = setOf("deadline", "values")
        private val REISSUE_EVENT_KEYS = setOf("operation", "group", "tick", "deadline")
        private val SUBMIT_EVENT_KEYS = setOf("operation", "group", "tick", "deadline", "values")
        private val COMPLETE_EVENT_KEYS = setOf("operation", "group", "deadline", "observedAt", "values")
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
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = Configuration(context, config)
    class Configuration(context: ComponentContext, config: Map<String, Any>) : ScalarComponent("soc-configuration", context, config), StatefulComponent, NativeMmioMirrorPort {
        private val counts = intArrayOf(4, 3, 4, 4, 4, 4, 4, 4, 1, 1, 2, 1, 1, 4, 3, 2, 1, 3, 1, 1)
        private val initial = (config.getValue("initialValue") as Number).toLong()
        private val delay = (config.getValue("completionTicks") as Number).toLong()
        private val staged = counts.map { LongArray(it) { initial } }
        private val applied = counts.map { LongArray(it) { initial } }
        private val written = BooleanArray(counts.size)
        private data class Pending(val deadline: Long, val values: LongArray)
        private val pending = arrayOfNulls<Pending>(counts.size)
        private val groupNextInterested = LongArray(counts.size) { Long.MAX_VALUE }
        private val activeDeadlineCountCell = IntArray(1)
        private val events = ArrayDeque<Map<String, Any>>()
        private var eventCount = 0L
        private val base: BaseParameterState?
        private val reconfiguration: PairReconfigurationState?
        private val groupMirrors = arrayOfNulls<MmioMirrorHandle>(counts.size)
        private val baseStatusMirrors = arrayOfNulls<MmioMirrorHandle>(BaseParameterState.groups.size)

        private fun groupRequestWord(group: Int): Int {
            if (pending[group] != null) return 2
            val b = base
            val idx = BaseParameterState.index(group)
            return if (b != null && idx >= 0) (reconfiguration?.request(idx) ?: b.request(idx)).toInt() else 0
        }

        private fun syncGroupMirror(group: Int) {
            val h = groupMirrors[group]
            val b = base
            val baseIdx = BaseParameterState.index(group)
            if (h != null) {
                var slot = 0
                if (b != null && baseIdx >= 0) {
                    h.writeSlotRaw(slot++, b.peekStagedWord(baseIdx, 0).toInt())
                    h.writeSlotRaw(slot++, b.peekStagedWord(baseIdx, 1).toInt())
                }
                val words = staged[group]
                for (w in words.indices) {
                    h.writeSlotRaw(slot++, words[w].toInt())
                }
                h.writeSlotRaw(slot, groupRequestWord(group))
                h.setValidMask(h.allSlotsMask)
            }
            if (b != null && baseIdx >= 0) {
                baseStatusMirrors[baseIdx]?.writeSlot(0, b.peekStatus(baseIdx).toInt())
            }
        }

        private fun syncAllMirrors() {
            for (g in counts.indices) syncGroupMirror(g)
        }

        override fun describeMmioMirrorWindows(): List<MmioMirrorWindowSpec> = buildList {
            for (g in counts.indices) {
                val baseIdx = BaseParameterState.index(g)
                val slots = ArrayList<MmioMirrorSlotSpec>(counts[g] + 3)
                if (base != null && baseIdx >= 0) {
                    slots.add(MmioMirrorSlotSpec(0x00L, 4))
                    slots.add(MmioMirrorSlotSpec(0x08L, 4))
                }
                for (w in 0 until counts[g]) {
                    slots.add(MmioMirrorSlotSpec(0x40L + w * 4L, 4))
                }
                slots.add(MmioMirrorSlotSpec(0x80L, 4))
                add(MmioMirrorWindowSpec("$id.group$g", 0x84L, slots))
            }
            if (base != null) {
                for (idx in BaseParameterState.groups.indices) {
                    add(MmioMirrorWindowSpec("$id.base-status$idx", 4L, listOf(MmioMirrorSlotSpec(0x00L, 4))))
                }
            }
        }

        override fun attachMmioMirror(portName: String, handle: MmioMirrorHandle) {
            val groupPrefix = "$id.group"
            val basePrefix = "$id.base-status"
            when {
                portName.startsWith(groupPrefix) -> {
                    val g = portName.substring(groupPrefix.length).toIntOrNull() ?: return
                    if (g in groupMirrors.indices) {
                        groupMirrors[g] = handle
                        syncGroupMirror(g)
                    }
                }
                portName.startsWith(basePrefix) -> {
                    val idx = portName.substring(basePrefix.length).toIntOrNull() ?: return
                    val b = base ?: return
                    if (idx in baseStatusMirrors.indices) {
                        baseStatusMirrors[idx] = handle
                        handle.writeSlot(0, b.peekStatus(idx).toInt())
                    }
                }
            }
        }

        override fun detachMmioMirrors() {
            for (g in groupMirrors.indices) {
                groupMirrors[g]?.invalidateAll()
                groupMirrors[g] = null
            }
            for (i in baseStatusMirrors.indices) {
                baseStatusMirrors[i]?.invalidateAll()
                baseStatusMirrors[i] = null
            }
        }
        private fun computeGroupDeadline(group: Int): Long? {
            val command = pending[group]?.deadline
            val index = BaseParameterState.index(group)
            val settling = if (index >= 0) base?.nextDeadline(index) else null
            val regular = if (command == null) settling else if (settling == null) command else minOf(command, settling)
            val preparing = if (index >= 0) reconfiguration?.nextDeadline(index) else null
            return if (regular == null) preparing else if (preparing == null) regular else minOf(regular, preparing)
        }
        private fun refreshGroupDeadline(group: Int) {
            val prevActive = groupNextInterested[group] != Long.MAX_VALUE
            val next = computeGroupDeadline(group) ?: Long.MAX_VALUE
            groupNextInterested[group] = next
            val nextActive = next != Long.MAX_VALUE
            if (prevActive != nextActive) {
                activeDeadlineCountCell[0] += if (nextActive) 1 else -1
            }
        }
        private fun refreshAllGroupDeadlines() {
            var active = 0
            for (g in counts.indices) {
                val next = computeGroupDeadline(g) ?: Long.MAX_VALUE
                groupNextInterested[g] = next
                if (next != Long.MAX_VALUE) active++
            }
            activeDeadlineCountCell[0] = active
        }
        fun hasAnyActiveDeadline(): Boolean = activeDeadlineCountCell[0] != 0
        fun activeDeadlineCountCell(): IntArray = activeDeadlineCountCell
        init {
            require(config["profile"] in listOf("configuration-command2-experimental-v1", PairReconfigurationState.PROFILE) && initial in 0..511 && delay in 1..1000000)
            values = values + mapOf("initialValue" to initial, "completionTicks" to delay)
            val enabled=config.getValue("baseProfile")==BaseParameterState.PROFILE
            val file=config.getValue("baseInputFile")as String
            require(config["baseProfile"]in listOf("disabled",BaseParameterState.PROFILE)&&enabled==file.isNotBlank()) { "explicit base-parameter input required when enabled" }
            base=if(enabled)BaseParameterState(context,file)else null
            require(config["profile"]!=PairReconfigurationState.PROFILE || enabled) { "reconfiguration requires explicit base profile and input" }
            reconfiguration=if(config["profile"]==PairReconfigurationState.PROFILE)PairReconfigurationState(context,checkNotNull(base),delay)else null
            counts.indices.forEach { group ->
                context.ports.register(id, "$id.group$group", MmioDevice::class.java, object : TickAwareMmioDevice {
                    override val clockOrder = 20 + group
                    override fun nextDeadline(): Long? {
                        val t = groupNextInterested[group]
                        return if (t == Long.MAX_VALUE) null else t
                    }
                    override fun nextInterestedTick(): Long = groupNextInterested[group]
                    override fun readMayMutateSchedule(): Boolean = false
                    override fun read(offset: Long, size: Int) = access(group, offset, size, false, 0)
                    override fun write(offset: Long, size: Int, value: Long) { access(group, offset, size, true, value) }
                    override fun beforeInstruction(tick: Long) { advanceGroup(group, tick) }
                })
            }
            if(base!=null)BaseParameterState.groups.indices.forEach {index->
                context.ports.register(id,"$id.base-status$index",MmioDevice::class.java,object: MmioDevice {
                    override val clockOrder=80+index
                    override fun read(offset: Long,size: Int): Long {
                        require(offset==0L&&size==4) { "base-parameter exact status word required" }
                        return base.read(index,0xf0)
                    }
                    override fun write(offset: Long,size: Int,value: Long) {error("base-parameter status is read-only")}
                })
            }
        }
        private fun record(event: Map<String, Any>) {
            if (events.size == 256) events.removeFirst()
            events.addLast(event); eventCount++
        }
        private fun preflightEvent() { require(eventCount<Long.MAX_VALUE) { "configuration event counter overflow" } }
        private fun access(group: Int, offset: Long, size: Int, write: Boolean, value: Long): Long {
            require(size == 4 && offset >= 0 && offset % 4 == 0L) { "configuration aligned words required" }
            val index=BaseParameterState.index(group)
            if(base!=null&&index>=0&&(offset==0L||offset==8L)) {
                if(write){require(pending[group]==null) { "base-parameter write while command 2 is busy" };reconfiguration?.preflightWrite(index);base.write(index,offset.toInt(),value);reconfiguration?.wrote(index,offset.toInt());syncGroupMirror(group);return value}
                return base.read(index,offset.toInt())
            }
            if (offset in 0x40L..<(0x40L + counts[group] * 4)) {
                val word = ((offset - 0x40) / 4).toInt()
                if (write) {
                    require(reconfiguration?.active(index)!=true) { "nine-bit write during reconfiguration is not supported by this model" }
                    require(value in 0..511 && pending[group] == null && !(base!=null&&index>=0&&base.busy(index))) { "configuration nine-bit mask or busy write" }
                    staged[group][word] = value; written[group] = true; syncGroupMirror(group)
                }
                return staged[group][word]
            }
            require(offset == 0x80L) { "configuration reserved register" }
            if (!write) return if (pending[group] != null) 2 else if(base!=null&&index>=0)reconfiguration?.request(index)?:base.request(index)else 0
            if(reconfiguration!=null&&(value==5L||value==6L)) {
                require(pending[group]==null) { "reconfiguration while command 2 is busy is not supported by this model" }
                if(value==5L)reconfiguration.begin(index)else reconfiguration.commit(index)
                refreshGroupDeadline(group); syncGroupMirror(group)
                return 0
            }
            require(reconfiguration?.active(index)!=true) { "legacy command during reconfiguration is not supported by this model" }
            if(value==3L&&base!=null&&index>=0) {
                require(pending[group]==null) { "command 3 while command 2 is busy" };base.submit(index);refreshGroupDeadline(group);syncGroupMirror(group);return 0
            }
            require(!(base!=null&&index>=0&&base.busy(index))) { "command 2 while base parameters are busy" }
            require(value == 2L && written[group]) { "configuration requires prior value and command 2" }
            val now = context.clock.ticks(); require(now >= 0)
            preflightEvent()
            val existing = pending[group]
            if (existing != null) {
                record(mapOf("operation" to "reissue", "group" to group, "tick" to now, "deadline" to existing.deadline)); return 0
            }
            val deadline = Math.addExact(now, delay); val snapshot = staged[group].copyOf()
            pending[group] = Pending(deadline, snapshot)
            refreshGroupDeadline(group); syncGroupMirror(group)
            record(mapOf("operation" to "submit", "group" to group, "tick" to now, "deadline" to deadline, "values" to snapshot.toList()))
            return 0
        }
        private fun advanceGroup(group: Int, tick: Long) {
            require(tick >= 0)
            if (groupNextInterested[group] > tick) return
            val item = pending[group]
            if(item!=null&&tick>=item.deadline){
                preflightEvent()
                item.values.copyInto(applied[group]); pending[group] = null
                record(mapOf("operation" to "complete", "group" to group, "deadline" to item.deadline, "observedAt" to tick, "values" to item.values.toList()))
            }
            if(base!=null){val index=BaseParameterState.index(group);if(index>=0){if(reconfiguration!=null)reconfiguration.advance(index,tick)else base.advance(index,tick)}}
            refreshGroupDeadline(group)
            syncGroupMirror(group)
        }
        override fun additionalMmioPorts()=if(base==null)emptyList()else BaseParameterState.groups.indices.map {"$id.base-status$it"}
        fun clockTreeState(): Map<String, Any?> = Spx4ClockTree.toStateMap()
        fun basePllLabels(): List<String> = base?.pllLabels().orEmpty()

        override fun state() = super.state() + mapOf("groupCounts" to counts.toList(), "staged" to staged.flatMap { it.toList() }, "applied" to applied.flatMap { it.toList() },
            "pending" to pending.map { it?.let { p -> mapOf("deadline" to p.deadline, "values" to p.values.toList()) } }, "written" to written.toList(),
            "events" to events.toList(), "eventCount" to eventCount, "eventLogTruncated" to (eventCount > events.size), "physicalClockResetEffects" to false, "irq" to false,
            "baseParametersEnabled" to(base!=null),"baseParameters" to base?.state()) + (reconfiguration?.let { mapOf("reconfiguration" to it.state()) } ?: emptyMap())

        override fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "scalars" to saveScalarValues(),
            "staged" to staged.map { groupWords -> groupWords.toList() },
            "applied" to applied.map { groupWords -> groupWords.toList() },
            "written" to written.toList(),
            "pending" to pending.map { item ->
                item?.let { pendingItem ->
                    linkedMapOf("deadline" to pendingItem.deadline, "values" to pendingItem.values.toList())
                }
            },
            "eventCount" to eventCount,
            "events" to events.toList(),
            "baseParameters" to base?.saveSnapshot(),
            "reconfiguration" to reconfiguration?.saveSnapshot(),
        )

        override fun restoreSnapshot(data: Map<String, Any?>) {
            require(data.keys == SNAPSHOT_KEYS) { "Unexpected snapshot keys for $id" }
            require(snapshotInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = snapshotMap(data["scalars"], "scalars")
            require(scalarsRaw.keys == DESCRIPTOR.settings.map { setting -> setting.key }.toSet()) {
                "Scalar setting keys differ in snapshot for $id"
            }
            require(scalarsRaw.values.all { scalarValue -> scalarValue != null }) { "Null scalar setting in snapshot for $id" }
            val previousValues = values
            val nextStaged: List<LongArray>
            val nextApplied: List<LongArray>
            val nextWritten: BooleanArray
            val nextPending: Array<Pending?>
            val nextEventCount: Long
            val nextEvents: List<Map<String, Any>>
            val validatedBase: BaseParameterState.ValidatedSnapshot?
            val validatedReconfiguration: PairReconfigurationState.ValidatedSnapshot?
            try {
                require((base != null) == (data["baseParameters"] != null)) { "baseParameters presence mismatch for $id" }
                require((reconfiguration != null) == (data["reconfiguration"] != null)) { "reconfiguration presence mismatch for $id" }
                fun parseGroupWords(rawGroups: Any?, field: String): List<LongArray> {
                    val rows = snapshotList(rawGroups, field)
                    require(rows.size == counts.size) { "Snapshot field $field requires ${counts.size} groups" }
                    return counts.indices.map { group ->
                        val groupRow = snapshotList(rows[group], "$field[$group]")
                        require(groupRow.size == counts[group]) { "Snapshot field $field[$group] requires ${counts[group]} words" }
                        LongArray(counts[group]) { slot -> snapshotLong(groupRow[slot], "$field[$group][$slot]", 0L, 511L) }
                    }
                }
                nextStaged = parseGroupWords(data["staged"], "staged")
                nextApplied = parseGroupWords(data["applied"], "applied")
                val writtenRows = snapshotList(data["written"], "written")
                require(writtenRows.size == counts.size) { "Snapshot written requires ${counts.size} entries" }
                nextWritten = BooleanArray(counts.size) { group -> snapshotBoolean(writtenRows[group], "written[$group]") }
                val pendingRows = snapshotList(data["pending"], "pending")
                require(pendingRows.size == counts.size) { "Snapshot pending requires ${counts.size} entries" }
                nextPending = Array(counts.size) { group ->
                    val rawPending = pendingRows[group] ?: return@Array null
                    val pendingMap = snapshotMap(rawPending, "pending[$group]")
                    require(pendingMap.keys == PENDING_KEYS) { "Unexpected pending[$group] keys" }
                    val deadline = snapshotLong(pendingMap["deadline"], "pending[$group].deadline", 1L, Long.MAX_VALUE)
                    val wordRows = snapshotList(pendingMap["values"], "pending[$group].values")
                    require(wordRows.size == counts[group]) { "Snapshot pending[$group].values size mismatch" }
                    val snapshotWords = LongArray(counts[group]) { slot ->
                        snapshotLong(wordRows[slot], "pending[$group].values[$slot]", 0L, 511L)
                    }
                    Pending(deadline, snapshotWords)
                }
                for (group in counts.indices) {
                    if (nextPending[group] != null || nextStaged[group].any { word -> word != initial } || nextApplied[group].any { word -> word != initial }) {
                        require(nextWritten[group]) { "Group $group requires written flag when modified or pending" }
                    }
                }
                nextEventCount = snapshotLong(data["eventCount"], "eventCount", 0L, Long.MAX_VALUE)
                val eventRows = snapshotList(data["events"], "events")
                require(eventRows.size == minOf(nextEventCount, 256L).toInt()) { "Configuration events size mismatch" }
                nextEvents = eventRows.mapIndexed { rowOffset, rawEvent ->
                    val eventMap = snapshotMap(rawEvent, "events[$rowOffset]")
                    val operation = snapshotString(eventMap["operation"], "events[$rowOffset].operation")
                    val group = snapshotInt(eventMap["group"], "events[$rowOffset].group", 0, counts.lastIndex)
                    val deadline = snapshotLong(eventMap["deadline"], "events[$rowOffset].deadline", 1L, Long.MAX_VALUE)
                    when (operation) {
                        "reissue" -> {
                            require(eventMap.keys == REISSUE_EVENT_KEYS) { "Unexpected reissue event keys" }
                            val eventTick = snapshotLong(eventMap["tick"], "events[$rowOffset].tick", 0L, Long.MAX_VALUE)
                            mapOf("operation" to "reissue", "group" to group, "tick" to eventTick, "deadline" to deadline)
                        }
                        "submit" -> {
                            require(eventMap.keys == SUBMIT_EVENT_KEYS) { "Unexpected submit event keys" }
                            val eventTick = snapshotLong(eventMap["tick"], "events[$rowOffset].tick", 0L, Long.MAX_VALUE)
                            val valuesList = snapshotList(eventMap["values"], "events[$rowOffset].values")
                            require(valuesList.size == counts[group]) { "Submit event values size mismatch" }
                            val parsedValues = List(counts[group]) { slot ->
                                snapshotLong(valuesList[slot], "events[$rowOffset].values[$slot]", 0L, 511L)
                            }
                            mapOf("operation" to "submit", "group" to group, "tick" to eventTick, "deadline" to deadline, "values" to parsedValues)
                        }
                        "complete" -> {
                            require(eventMap.keys == COMPLETE_EVENT_KEYS) { "Unexpected complete event keys" }
                            val observedAt = snapshotLong(eventMap["observedAt"], "events[$rowOffset].observedAt", 0L, Long.MAX_VALUE)
                            val valuesList = snapshotList(eventMap["values"], "events[$rowOffset].values")
                            require(valuesList.size == counts[group]) { "Complete event values size mismatch" }
                            val parsedValues = List(counts[group]) { slot ->
                                snapshotLong(valuesList[slot], "events[$rowOffset].values[$slot]", 0L, 511L)
                            }
                            mapOf("operation" to "complete", "group" to group, "deadline" to deadline, "observedAt" to observedAt, "values" to parsedValues)
                        }
                        else -> throw IllegalArgumentException("Unsupported configuration event operation")
                    }
                }
                validatedBase = base?.validateSnapshot(data["baseParameters"])
                validatedReconfiguration = reconfiguration?.validateSnapshot(data["reconfiguration"], checkNotNull(validatedBase))
                for (group in counts.indices) {
                    if (nextPending[group] != null) {
                        val baseIndex = BaseParameterState.index(group)
                        if (baseIndex >= 0) {
                            require(validatedBase?.busy(baseIndex) != true && validatedReconfiguration?.active(baseIndex) != true) {
                                "Command 2 pending conflicts with active base or reconfiguration state at group $group"
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                values = previousValues
                throw failure
            }
            restoreScalarValues(scalarsRaw, DESCRIPTOR)
            for (group in counts.indices) {
                nextStaged[group].copyInto(staged[group])
                nextApplied[group].copyInto(applied[group])
                written[group] = nextWritten[group]
                pending[group] = nextPending[group]
            }
            events.clear()
            events.addAll(nextEvents)
            eventCount = nextEventCount
            if (base != null && validatedBase != null) base.applySnapshot(validatedBase)
            if (reconfiguration != null && validatedReconfiguration != null) reconfiguration.applySnapshot(validatedReconfiguration)
            refreshAllGroupDeadlines()
            syncAllMirrors()
        }
    }
}
