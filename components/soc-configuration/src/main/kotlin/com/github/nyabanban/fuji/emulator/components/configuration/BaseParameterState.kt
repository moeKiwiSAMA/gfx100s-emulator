package com.github.nyabanban.fuji.emulator.components.configuration

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.ExecutionLocationSource

/** Opaque u32 pairs; delayed request acknowledgment and settling are declared inputs. */
internal class BaseParameterState(private val context: ComponentContext,file: String) {
    companion object {
        const val PROFILE="configuration-base-parameters-experimental-v1"
        val groups=listOf(0,7,9,10,11,12,13,14,15,16,17,18,19)
        private val indexByGroup=IntArray(20){-1}.apply {groups.forEachIndexed {index,group->this[group]=index}}
        fun index(group: Int)=indexByGroup[group]
        private fun integer(v: Any?): Long {
            require(v is Byte||v is Short||v is Int||v is Long) { "base parameters require integer inputs" }
            return (v as Number).toLong()
        }
        private val SNAPSHOT_KEYS = setOf(
            "profile", "inputs", "staged", "applied", "status", "written", "settled",
            "phase", "requestAt", "settleAt", "pendingPair", "readCount", "writeCount",
            "accessCount", "accesses", "eventCount", "events",
        )
        private val INPUT_KEYS = setOf("initialPairs", "initialStatus", "requestTicks", "settleTicks")
        private val ACCESS_KEYS = setOf("index", "offset", "write", "value", "tick", "core", "pcVirtual")
        private val EVENT_KEYS = setOf("type", "index", "tick", "requestAt", "settleAt", "pair")
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
        private fun snapshotList(value: Any?, field: String): List<*> =
            value as? List<*> ?: throw IllegalArgumentException("Snapshot field $field requires list")
        private fun snapshotMap(value: Any?, field: String): Map<*, *> {
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all { key -> key is String }) { "Snapshot field $field requires string keys" }
            return map
        }
    }
    internal class ValidatedSnapshot(
        val staged: LongArray,
        val applied: LongArray,
        val status: LongArray,
        val written: IntArray,
        val settled: BooleanArray,
        val phase: IntArray,
        val requestAt: LongArray,
        val settleAt: LongArray,
        val pendingPair: LongArray,
        val accessCount: Long,
        val readCount: Long,
        val writeCount: Long,
        val accessIndices: IntArray,
        val accessOffsets: IntArray,
        val accessWrites: BooleanArray,
        val accessValues: LongArray,
        val accessTicks: LongArray,
        val accessCores: IntArray,
        val accessPcs: LongArray,
        val eventCount: Long,
        val eventTypes: IntArray,
        val eventIndices: IntArray,
        val eventTicks: LongArray,
        val eventRequests: LongArray,
        val eventSettles: LongArray,
        val eventFirst: LongArray,
        val eventSecond: LongArray,
    ) {
        fun busy(index: Int): Boolean = phase[index] != 0
    }
    private val initialPairs: LongArray
    private val initialStatus: LongArray
    private val requestTicks: LongArray
    private val settleTicks: LongArray
    private val staged: LongArray
    private val applied: LongArray
    private val status: LongArray
    private val written=IntArray(13)
    private val settled=BooleanArray(13)
    private val phase=IntArray(13)
    private val requestAt=LongArray(13)
    private val settleAt=LongArray(13)
    private val pendingPair=LongArray(26)
    private val accessCapacity=512
    private val accessIndices=IntArray(accessCapacity)
    private val accessOffsets=IntArray(accessCapacity)
    private val accessWrites=BooleanArray(accessCapacity)
    private val accessValues=LongArray(accessCapacity)
    private val accessTicks=LongArray(accessCapacity)
    private val accessCores=IntArray(accessCapacity){-1}
    private val accessPcs=LongArray(accessCapacity){-1}
    private var accessCount=0L
    private var readCount=0L
    private var writeCount=0L
    private val eventCapacity=256
    private val eventTypes=IntArray(eventCapacity)
    private val eventIndices=IntArray(eventCapacity)
    private val eventTicks=LongArray(eventCapacity)
    private val eventRequests=LongArray(eventCapacity)
    private val eventSettles=LongArray(eventCapacity)
    private val eventFirst=LongArray(eventCapacity)
    private val eventSecond=LongArray(eventCapacity)
    private var eventCount=0L
    init {
        val data=context.readData(file)
        require(data.keys==setOf("schema","profile","initialPairs","initialStatus","requestTicks","settleTicks")&&data["schema"]==1&&data["profile"]==PROFILE) { "invalid base-parameter input schema" }
        fun array(name: String,min: Long,max: Long): LongArray {
            val rows=data[name]as? List<*>?:error("base-parameter array required")
            require(rows.size==13) { "base parameters require thirteen entries" }
            return rows.map {integer(it).also {n->require(n in min..max)}}.toLongArray()
        }
        val pairs=data["initialPairs"]as? List<*>?:error("base parameter pairs required")
        require(pairs.size==13)
        initialPairs=pairs.flatMap {row->val values=row as? List<*>?:error("base pair requires an array");require(values.size==2);values.map {integer(it).also {n->require(n in 0..0xffffffffL)}}}.toLongArray()
        initialStatus=array("initialStatus",0,1);requestTicks=array("requestTicks",1,1000000);settleTicks=array("settleTicks",1,1000000)
        staged=initialPairs.copyOf();applied=initialPairs.copyOf();status=initialStatus.copyOf()
    }
    fun nextDeadline(index:Int):Long?=when(phase[index]){1->requestAt[index];2->settleAt[index];else->null}
    fun busy(index: Int)=phase[index]!=0
    fun appliedPair(index: Int)=listOf(applied[index*2],applied[index*2+1])
    fun stagedPair(index: Int)=listOf(staged[index*2],staged[index*2+1])
    fun settlesAt(index: Int): Long?=if(busy(index))settleAt[index]else null
    fun submitReconfiguration(index: Int) {
        require(!busy(index)) { "reconfiguration submit while busy is not supported by this model" }
        submit(index)
    }
    fun request(index: Int)=if(phase[index]==1)3L else 0L
    fun peekStagedWord(index: Int, word: Int): Long = staged[index * 2 + word]
    fun peekStatus(index: Int): Long = status[index]
    private fun tick()=context.clock.ticks().also {require(it>=0)}
    private fun access(index: Int,offset: Int,write: Boolean,value: Long,now: Long) {
        val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current()
        val slot=(accessCount%accessCapacity).toInt()
        accessIndices[slot]=index;accessOffsets[slot]=offset;accessWrites[slot]=write;accessValues[slot]=value;accessTicks[slot]=now
        accessCores[slot]=location?.core?:-1;accessPcs[slot]=location?.pcVirtual?:-1
        accessCount++;if(write)writeCount++ else readCount++
    }
    private fun preflightAccess() {require(accessCount<Long.MAX_VALUE&&readCount<Long.MAX_VALUE&&writeCount<Long.MAX_VALUE) { "base-parameter access counter overflow" }}
    private fun preflightEvents(count: Int) {require(eventCount<=Long.MAX_VALUE-count) { "base-parameter event counter overflow" }}
    private fun event(index: Int,type: Int,now: Long) {
        val slot=(eventCount%eventCapacity).toInt();eventTypes[slot]=type;eventIndices[slot]=index;eventTicks[slot]=now
        eventRequests[slot]=requestAt[index];eventSettles[slot]=settleAt[index];eventFirst[slot]=pendingPair[index*2];eventSecond[slot]=pendingPair[index*2+1];eventCount++
    }
    fun read(index: Int,offset: Int): Long {
        require(offset==0||offset==8||offset==0xf0)
        val now=tick();preflightAccess()
        val value=if(offset==0xf0)status[index] else staged[index*2+if(offset==8)1 else 0]
        access(index,offset,false,value,now);return value
    }
    fun write(index: Int,offset: Int,value: Long) {
        require(offset==0||offset==8) { "base-parameter status is read-only" }
        require(value in 0..0xffffffffL&&!busy(index)) { "base-parameter u32 data or busy write" }
        val now=tick();preflightAccess()
        val word=if(offset==8)1 else 0
        staged[index*2+word]=value;written[index]=written[index]or(1 shl word)
        access(index,offset,true,value,now)
    }
    fun submit(index: Int) {
        val now=tick();require(written[index]==3) { "command 3 requires both base-parameter writes" }
        preflightEvents(1)
        if(busy(index)){event(index,3,now);return}
        val requested=Math.addExact(now,requestTicks[index]);val settledAt=Math.addExact(requested,settleTicks[index])
        requestAt[index]=requested;settleAt[index]=settledAt
        pendingPair[index*2]=staged[index*2];pendingPair[index*2+1]=staged[index*2+1]
        status[index]=1;phase[index]=1;event(index,0,now)
    }
    fun advance(index: Int,now: Long) {
        require(now>=0)
        if(!busy(index)||now<requestAt[index])return
        val events=(if(phase[index]==1)1 else 0)+(if(now>=settleAt[index])1 else 0)
        preflightEvents(events)
        if(phase[index]==1){phase[index]=2;event(index,1,now)}
        if(now>=settleAt[index]){
            applied[index*2]=pendingPair[index*2];applied[index*2+1]=pendingPair[index*2+1]
            status[index]=0;settled[index]=true;phase[index]=0;event(index,2,now)
        }
    }
    fun pllLabels(): List<String> = Spx4ClockTree.PLLS.map { it.label }
    fun pllTopology(): List<Map<String, Any?>> = Spx4ClockTree.PLLS.map { it.toStateMap() }

    fun state(): Map<String,Any?> = mapOf(
        "profile" to PROFILE,"groupIndices" to groups,
        "inputs" to mapOf("initialPairs" to initialPairs.toList().chunked(2),"initialStatus" to initialStatus.toList(),"requestTicks" to requestTicks.toList(),"settleTicks" to settleTicks.toList()),
        "staged" to staged.toList().chunked(2),"applied" to applied.toList().chunked(2),"status" to status.toList(),"written" to written.toList(),"settled" to settled.toList(),
        "pending" to phase.indices.map {i->if(phase[i]==0)null else mapOf("phase" to if(phase[i]==1)"request" else "settling","requestAt" to requestAt[i],"settleAt" to settleAt[i],"pair" to listOf(pendingPair[i*2],pendingPair[i*2+1]))},
        "eventCount" to eventCount,"eventCapacity" to eventCapacity,"eventLogTruncated" to(eventCount>eventCapacity),
        "events" to (maxOf(0L,eventCount-eventCapacity) until eventCount).map {n->val s=(n%eventCapacity).toInt();mapOf("operation" to listOf("submit","acknowledge","settle","reissue")[eventTypes[s]],"index" to eventIndices[s],"group" to groups[eventIndices[s]],"tick" to eventTicks[s],"requestAt" to eventRequests[s],"settleAt" to eventSettles[s],"pair" to listOf(eventFirst[s],eventSecond[s]))},
        "readCount" to readCount,"writeCount" to writeCount,"accessCount" to accessCount,"accessCapacity" to accessCapacity,"accessLogTruncated" to(accessCount>accessCapacity),
        "accesses" to(maxOf(0L,accessCount-accessCapacity) until accessCount).map {n->val s=(n%accessCapacity).toInt();mapOf("index" to accessIndices[s],"group" to groups[accessIndices[s]],"offset" to accessOffsets[s],"write" to accessWrites[s],"value" to accessValues[s],"tick" to accessTicks[s],"core" to accessCores[s].takeIf {it>=0},"pcVirtual" to accessPcs[s].takeIf {it>=0})},
        "basis" to "Selected-DAT word transfers/poll masks; explicit opaque u32 initial pairs/status and unmeasured request/settle delays. No physical clock/reset/ready/IRQ effects")

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "profile" to PROFILE,
        "inputs" to linkedMapOf(
            "initialPairs" to initialPairs.toList().chunked(2),
            "initialStatus" to initialStatus.toList(),
            "requestTicks" to requestTicks.toList(),
            "settleTicks" to settleTicks.toList(),
        ),
        "staged" to staged.toList().chunked(2),
        "applied" to applied.toList().chunked(2),
        "status" to status.toList(),
        "written" to written.toList(),
        "settled" to settled.toList(),
        "phase" to phase.toList(),
        "requestAt" to requestAt.toList(),
        "settleAt" to settleAt.toList(),
        "pendingPair" to pendingPair.toList().chunked(2),
        "readCount" to readCount,
        "writeCount" to writeCount,
        "accessCount" to accessCount,
        "accesses" to (maxOf(0L, accessCount - accessCapacity) until accessCount).map { sequence ->
            val slot = (sequence % accessCapacity).toInt()
            linkedMapOf(
                "index" to accessIndices[slot],
                "offset" to accessOffsets[slot],
                "write" to accessWrites[slot],
                "value" to accessValues[slot],
                "tick" to accessTicks[slot],
                "core" to accessCores[slot].takeIf { coreValue -> coreValue >= 0 },
                "pcVirtual" to accessPcs[slot].takeIf { pcValue -> pcValue >= 0 },
            )
        },
        "eventCount" to eventCount,
        "events" to (maxOf(0L, eventCount - eventCapacity) until eventCount).map { sequence ->
            val slot = (sequence % eventCapacity).toInt()
            linkedMapOf(
                "type" to eventTypes[slot],
                "index" to eventIndices[slot],
                "tick" to eventTicks[slot],
                "requestAt" to eventRequests[slot],
                "settleAt" to eventSettles[slot],
                "pair" to listOf(eventFirst[slot], eventSecond[slot]),
            )
        },
    )

    fun validateSnapshot(raw: Any?): ValidatedSnapshot {
        val section = snapshotMap(raw, "baseParameters")
        require(section.keys == SNAPSHOT_KEYS) { "Unexpected baseParameters snapshot keys" }
        require(section["profile"] == PROFILE) { "Unsupported baseParameters snapshot profile" }
        fun parsePairs(rawPairs: Any?, field: String): LongArray {
            val rows = snapshotList(rawPairs, field)
            require(rows.size == 13) { "Snapshot field $field requires 13 pairs" }
            val flattened = LongArray(26)
            rows.forEachIndexed { rowIndex, rawRow ->
                val pairValues = snapshotList(rawRow, "$field[$rowIndex]")
                require(pairValues.size == 2) { "Snapshot field $field[$rowIndex] requires 2 words" }
                flattened[rowIndex * 2] = snapshotLong(pairValues[0], "$field[$rowIndex][0]", 0L, 0xffffffffL)
                flattened[rowIndex * 2 + 1] = snapshotLong(pairValues[1], "$field[$rowIndex][1]", 0L, 0xffffffffL)
            }
            return flattened
        }
        fun parseLongVector(rawVector: Any?, field: String, minimum: Long, maximum: Long): LongArray {
            val rows = snapshotList(rawVector, field)
            require(rows.size == 13) { "Snapshot field $field requires 13 entries" }
            return LongArray(13) { entryIndex -> snapshotLong(rows[entryIndex], "$field[$entryIndex]", minimum, maximum) }
        }
        fun parseIntVector(rawVector: Any?, field: String, minimum: Int, maximum: Int): IntArray {
            val rows = snapshotList(rawVector, field)
            require(rows.size == 13) { "Snapshot field $field requires 13 entries" }
            return IntArray(13) { entryIndex -> snapshotInt(rows[entryIndex], "$field[$entryIndex]", minimum, maximum) }
        }
        fun parseBooleanVector(rawVector: Any?, field: String): BooleanArray {
            val rows = snapshotList(rawVector, field)
            require(rows.size == 13) { "Snapshot field $field requires 13 entries" }
            return BooleanArray(13) { entryIndex -> snapshotBoolean(rows[entryIndex], "$field[$entryIndex]") }
        }
        val inputsMap = snapshotMap(section["inputs"], "baseParameters.inputs")
        require(inputsMap.keys == INPUT_KEYS) { "Unexpected baseParameters.inputs keys" }
        require(parsePairs(inputsMap["initialPairs"], "inputs.initialPairs").contentEquals(initialPairs)) {
            "baseParameters initialPairs differ from live configuration"
        }
        require(parseLongVector(inputsMap["initialStatus"], "inputs.initialStatus", 0L, 1L).contentEquals(initialStatus)) {
            "baseParameters initialStatus differs from live configuration"
        }
        require(parseLongVector(inputsMap["requestTicks"], "inputs.requestTicks", 1L, 1_000_000L).contentEquals(requestTicks)) {
            "baseParameters requestTicks differ from live configuration"
        }
        require(parseLongVector(inputsMap["settleTicks"], "inputs.settleTicks", 1L, 1_000_000L).contentEquals(settleTicks)) {
            "baseParameters settleTicks differ from live configuration"
        }
        val nextStaged = parsePairs(section["staged"], "baseParameters.staged")
        val nextApplied = parsePairs(section["applied"], "baseParameters.applied")
        val nextStatus = parseLongVector(section["status"], "baseParameters.status", 0L, 1L)
        val nextWritten = parseIntVector(section["written"], "baseParameters.written", 0, 3)
        val nextSettled = parseBooleanVector(section["settled"], "baseParameters.settled")
        val nextPhase = parseIntVector(section["phase"], "baseParameters.phase", 0, 2)
        val nextRequestAt = parseLongVector(section["requestAt"], "baseParameters.requestAt", 0L, Long.MAX_VALUE)
        val nextSettleAt = parseLongVector(section["settleAt"], "baseParameters.settleAt", 0L, Long.MAX_VALUE)
        val nextPendingPair = parsePairs(section["pendingPair"], "baseParameters.pendingPair")
        for (entryIndex in 0 until 13) {
            if (nextPhase[entryIndex] != 0) {
                require(nextWritten[entryIndex] == 3 && nextStatus[entryIndex] == 1L) {
                    "Busy base-parameter slot $entryIndex requires written==3 and status==1"
                }
                require(nextRequestAt[entryIndex] >= 1L && nextSettleAt[entryIndex] > nextRequestAt[entryIndex]) {
                    "Busy base-parameter slot $entryIndex requires ordered positive deadlines"
                }
            } else if (nextSettled[entryIndex]) {
                require(nextWritten[entryIndex] == 3 && nextStatus[entryIndex] == 0L) {
                    "Settled idle base-parameter slot $entryIndex requires written==3 and status==0"
                }
            } else {
                require(nextStatus[entryIndex] == initialStatus[entryIndex]) {
                    "Unsettled idle base-parameter slot $entryIndex must match initialStatus"
                }
            }
        }
        val nextReadCount = snapshotLong(section["readCount"], "baseParameters.readCount", 0L, Long.MAX_VALUE)
        val nextWriteCount = snapshotLong(section["writeCount"], "baseParameters.writeCount", 0L, Long.MAX_VALUE)
        val nextAccessCount = snapshotLong(section["accessCount"], "baseParameters.accessCount", 0L, Long.MAX_VALUE)
        require(try { Math.addExact(nextReadCount, nextWriteCount) == nextAccessCount } catch (_: ArithmeticException) { false }) {
            "baseParameters readCount + writeCount must equal accessCount"
        }
        val accessRows = snapshotList(section["accesses"], "baseParameters.accesses")
        val expectedAccessSize = minOf(nextAccessCount, accessCapacity.toLong()).toInt()
        require(accessRows.size == expectedAccessSize) { "baseParameters accesses size mismatch" }
        val nextAccessIndices = IntArray(accessCapacity)
        val nextAccessOffsets = IntArray(accessCapacity)
        val nextAccessWrites = BooleanArray(accessCapacity)
        val nextAccessValues = LongArray(accessCapacity)
        val nextAccessTicks = LongArray(accessCapacity)
        val nextAccessCores = IntArray(accessCapacity) { -1 }
        val nextAccessPcs = LongArray(accessCapacity) { -1L }
        val startAccessSequence = maxOf(0L, nextAccessCount - accessCapacity)
        var observedWriteEntries = 0L
        var observedReadEntries = 0L
        accessRows.forEachIndexed { rowOffset, rawEntry ->
            val entryMap = snapshotMap(rawEntry, "baseParameters.accesses[$rowOffset]")
            require(entryMap.keys == ACCESS_KEYS) { "Unexpected baseParameters access entry keys" }
            val entryIndex = snapshotInt(entryMap["index"], "accesses[$rowOffset].index", 0, 12)
            val entryOffset = snapshotInt(entryMap["offset"], "accesses[$rowOffset].offset")
            require(entryOffset == 0 || entryOffset == 8 || entryOffset == 0xf0) { "Invalid baseParameters access offset" }
            val entryWrite = snapshotBoolean(entryMap["write"], "accesses[$rowOffset].write")
            if (entryWrite) {
                require(entryOffset == 0 || entryOffset == 8) { "baseParameters status offset cannot be a write" }
                observedWriteEntries++
            } else {
                observedReadEntries++
            }
            val entryValue = snapshotLong(entryMap["value"], "accesses[$rowOffset].value", 0L, 0xffffffffL)
            val entryTick = snapshotLong(entryMap["tick"], "accesses[$rowOffset].tick", 0L, Long.MAX_VALUE)
            val entryCore = if (entryMap["core"] == null) -1 else snapshotInt(entryMap["core"], "accesses[$rowOffset].core", 0, Int.MAX_VALUE)
            val entryPc = if (entryMap["pcVirtual"] == null) -1L else snapshotLong(entryMap["pcVirtual"], "accesses[$rowOffset].pcVirtual", 0L, Long.MAX_VALUE)
            val ringSlot = ((startAccessSequence + rowOffset) % accessCapacity).toInt()
            nextAccessIndices[ringSlot] = entryIndex
            nextAccessOffsets[ringSlot] = entryOffset
            nextAccessWrites[ringSlot] = entryWrite
            nextAccessValues[ringSlot] = entryValue
            nextAccessTicks[ringSlot] = entryTick
            nextAccessCores[ringSlot] = entryCore
            nextAccessPcs[ringSlot] = entryPc
        }
        if (nextAccessCount <= accessCapacity) {
            require(observedWriteEntries == nextWriteCount && observedReadEntries == nextReadCount) {
                "baseParameters retained access direction counts disagree with totals"
            }
        } else {
            require(observedWriteEntries <= nextWriteCount && observedReadEntries <= nextReadCount) {
                "baseParameters retained access direction counts exceed totals"
            }
        }
        val nextEventCount = snapshotLong(section["eventCount"], "baseParameters.eventCount", 0L, Long.MAX_VALUE)
        val eventRows = snapshotList(section["events"], "baseParameters.events")
        val expectedEventSize = minOf(nextEventCount, eventCapacity.toLong()).toInt()
        require(eventRows.size == expectedEventSize) { "baseParameters events size mismatch" }
        val nextEventTypes = IntArray(eventCapacity)
        val nextEventIndices = IntArray(eventCapacity)
        val nextEventTicks = LongArray(eventCapacity)
        val nextEventRequests = LongArray(eventCapacity)
        val nextEventSettles = LongArray(eventCapacity)
        val nextEventFirst = LongArray(eventCapacity)
        val nextEventSecond = LongArray(eventCapacity)
        val startEventSequence = maxOf(0L, nextEventCount - eventCapacity)
        eventRows.forEachIndexed { rowOffset, rawEntry ->
            val entryMap = snapshotMap(rawEntry, "baseParameters.events[$rowOffset]")
            require(entryMap.keys == EVENT_KEYS) { "Unexpected baseParameters event entry keys" }
            val eventType = snapshotInt(entryMap["type"], "events[$rowOffset].type", 0, 3)
            val eventIndex = snapshotInt(entryMap["index"], "events[$rowOffset].index", 0, 12)
            val eventTick = snapshotLong(entryMap["tick"], "events[$rowOffset].tick", 0L, Long.MAX_VALUE)
            val eventRequestAt = snapshotLong(entryMap["requestAt"], "events[$rowOffset].requestAt", 1L, Long.MAX_VALUE)
            val eventSettleAt = snapshotLong(entryMap["settleAt"], "events[$rowOffset].settleAt", 1L, Long.MAX_VALUE)
            require(eventSettleAt > eventRequestAt) { "baseParameters event settleAt must exceed requestAt" }
            val pairWords = snapshotList(entryMap["pair"], "events[$rowOffset].pair")
            require(pairWords.size == 2) { "baseParameters event pair requires 2 words" }
            val firstWord = snapshotLong(pairWords[0], "events[$rowOffset].pair[0]", 0L, 0xffffffffL)
            val secondWord = snapshotLong(pairWords[1], "events[$rowOffset].pair[1]", 0L, 0xffffffffL)
            val ringSlot = ((startEventSequence + rowOffset) % eventCapacity).toInt()
            nextEventTypes[ringSlot] = eventType
            nextEventIndices[ringSlot] = eventIndex
            nextEventTicks[ringSlot] = eventTick
            nextEventRequests[ringSlot] = eventRequestAt
            nextEventSettles[ringSlot] = eventSettleAt
            nextEventFirst[ringSlot] = firstWord
            nextEventSecond[ringSlot] = secondWord
        }
        return ValidatedSnapshot(
            staged = nextStaged,
            applied = nextApplied,
            status = nextStatus,
            written = nextWritten,
            settled = nextSettled,
            phase = nextPhase,
            requestAt = nextRequestAt,
            settleAt = nextSettleAt,
            pendingPair = nextPendingPair,
            accessCount = nextAccessCount,
            readCount = nextReadCount,
            writeCount = nextWriteCount,
            accessIndices = nextAccessIndices,
            accessOffsets = nextAccessOffsets,
            accessWrites = nextAccessWrites,
            accessValues = nextAccessValues,
            accessTicks = nextAccessTicks,
            accessCores = nextAccessCores,
            accessPcs = nextAccessPcs,
            eventCount = nextEventCount,
            eventTypes = nextEventTypes,
            eventIndices = nextEventIndices,
            eventTicks = nextEventTicks,
            eventRequests = nextEventRequests,
            eventSettles = nextEventSettles,
            eventFirst = nextEventFirst,
            eventSecond = nextEventSecond,
        )
    }

    fun applySnapshot(validated: ValidatedSnapshot) {
        validated.staged.copyInto(staged)
        validated.applied.copyInto(applied)
        validated.status.copyInto(status)
        validated.written.copyInto(written)
        validated.settled.copyInto(settled)
        validated.phase.copyInto(phase)
        validated.requestAt.copyInto(requestAt)
        validated.settleAt.copyInto(settleAt)
        validated.pendingPair.copyInto(pendingPair)
        accessCount = validated.accessCount
        readCount = validated.readCount
        writeCount = validated.writeCount
        validated.accessIndices.copyInto(accessIndices)
        validated.accessOffsets.copyInto(accessOffsets)
        validated.accessWrites.copyInto(accessWrites)
        validated.accessValues.copyInto(accessValues)
        validated.accessTicks.copyInto(accessTicks)
        validated.accessCores.copyInto(accessCores)
        validated.accessPcs.copyInto(accessPcs)
        eventCount = validated.eventCount
        validated.eventTypes.copyInto(eventTypes)
        validated.eventIndices.copyInto(eventIndices)
        validated.eventTicks.copyInto(eventTicks)
        validated.eventRequests.copyInto(eventRequests)
        validated.eventSettles.copyInto(eventSettles)
        validated.eventFirst.copyInto(eventFirst)
        validated.eventSecond.copyInto(eventSecond)
    }
}
