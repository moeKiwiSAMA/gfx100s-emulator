package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*

/** One finite software snapshot path; samples are not measured register/reset values. */
internal class LcdcSnapshotInput private constructor(private val context:ComponentContext,private val offsets:LongArray,private val samples:LongArray,private val controlReadLimit:Int?):MmioDevice {
    override val clockOrder=90
    private var position=0
    private val ticks=LongArray(LIMIT);private val cores=IntArray(LIMIT){-1};private val pcs=LongArray(LIMIT){-1}
    val unready get()=controlReadLimit!=null
    val port get()=if(unready)UNREADY_PORT else PORT
    private var controlWrites=0
    private var controlReads=0
    // Only the write and first/last poll coordinates are retained. No per-poll allocation/log.
    private val controlTicks=LongArray(3){-1};private val controlCores=IntArray(3){-1};private val controlPcs=LongArray(3){-1}
    override fun read(offset:Long,size:Int):Long {
        if(unready&&position==LIMIT) {
            require(offset==0L&&size==4) {"LCD unready control requires exact offset0 READ4"}
            check(controlWrites==1) {"LCD unready control requires its single zero write before polling"}
            check(controlReads<controlReadLimit!!) {"LCD unready finite control input exhausted"}
            recordControl(2)
            if(controlReads==0) {controlTicks[1]=controlTicks[2];controlCores[1]=controlCores[2];controlPcs[1]=controlPcs[2]}
            controlReads++
            return 0L
        }
        require(offset in 0..0x600L&&offset%4==0L&&size==4) {"LCD snapshot requires an exact aligned word"}
        check(position<LIMIT) {"LCD snapshot finite input exhausted"}
        require(offset==offsets[position]) {"LCD snapshot SOURCE read order differs"}
        val tick=context.clock.ticks();require(tick>=0) {"invalid LCD snapshot clock"}
        val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current()
        val value=samples[position]
        ticks[position]=tick;cores[position]=location?.core?:-1;pcs[position]=location?.pcVirtual?:-1;position++
        return value
    }
    override fun write(offset:Long,size:Int,value:Long) {
        check(unready) {"LCD snapshot input is read-only"}
        check(position==LIMIT) {"LCD unready control write requires complete snapshot prefix"}
        require(offset==0L&&size==4&&value==0L) {"LCD unready control accepts only exact offset0 WRITE4(0)"}
        check(controlWrites==0) {"LCD unready control accepts only one write"}
        recordControl(0)
        controlWrites=1
    }
    private fun recordControl(index:Int) {
        val tick=context.clock.ticks();require(tick>=0) {"invalid LCD unready clock"}
        val location=context.ports.find("cpu.execution-location",ExecutionLocationSource::class.java)?.current()
        controlTicks[index]=tick;controlCores[index]=location?.core?:-1;controlPcs[index]=location?.pcVirtual?:-1
    }
    private fun controlLocation(index:Int):Map<String,Any?>?=if(controlTicks[index]<0)null else mapOf("tick" to controlTicks[index],"core" to controlCores[index].takeIf{it>=0},"pcVirtual" to controlPcs[index].takeIf{it>=0})
    fun state():Map<String,Any?> = mapOf("profile" to if(unready)UNREADY_PROFILE else PROFILE,"readConfidence" to "HYPOTHESIS","sourceReadOffsets" to offsets.toList(),"readSamples" to samples.toList(),
        "transactionCapacity" to LIMIT,"transactionCount" to position,"remainingTransactions" to LIMIT-position,"eventLogTruncated" to false,
        "transactions" to (0 until position).map{i->mapOf("offset" to offsets[i],"direction" to "read","size" to 4,"value" to samples[i],"tick" to ticks[i],"core" to cores[i].takeIf{it>=0},"pcVirtual" to pcs[i].takeIf{it>=0})},
        "scope" to if(unready)"Finite software instance1 snapshot followed by an explicitly selected finite unready control hypothesis. Physical effects UNKNOWN; no ready/IRQ/clear/DMA/render completion law." else "Finite software instance1 snapshot only. All samples HYPOTHESIS; physical panel/reset/read effects/shared hardware UNKNOWN. Guest owns shadow stores, flags and return. No ready/IRQ/clear/DMA/render completion law.")+
        (if(!unready)emptyMap()else mapOf("control" to mapOf(
            "confidence" to "HYPOTHESIS","phase" to when {position<LIMIT->"snapshot";controlWrites==0->"awaiting-zero-write";controlReads==controlReadLimit->"exhausted";else->"unready"},
            "initialValue" to 0L,"writeValue" to 0L,"readValue" to 0L,"retainedValue" to 0L,
            "readLimit" to controlReadLimit,"readCount" to controlReads,"remainingReads" to controlReadLimit!!-controlReads,"writeCount" to controlWrites,
            "write" to controlLocation(0),"firstRead" to controlLocation(1),"lastRead" to controlLocation(2),
            "observationScope" to "Complete accepted counts; only write and first/last read coordinates retained, not an ordered poll trace.",
            "scope" to "One retained zero write and finite zero reads only. Initial/readback zero and absent completion are HYPOTHESIS, not reset/disable/stop semantics or physical LCD truth. No ready, ACK, IRQ, clear, DMA or frame generated.")))
    internal fun saveSnapshotState(): Map<String, Any?> = linkedMapOf(
        "unready" to unready,
        "controlReadLimit" to controlReadLimit,
        "offsets" to VglibSnapshotCodec.encodeWords(offsets),
        "samples" to VglibSnapshotCodec.encodeWords(samples),
        "position" to position,
        "coordinates" to VglibSnapshotCodec.encodeCoordinates(position, ticks, cores, pcs),
        "controlWrites" to controlWrites,
        "controlReads" to controlReads,
        "controlLocations" to (0..2).map { slot ->
            if (controlTicks[slot] < 0L) null
            else linkedMapOf("tick" to controlTicks[slot], "core" to controlCores[slot], "pcVirtual" to controlPcs[slot])
        },
    )
    internal fun parseSnapshotState(raw: Any?): VglibSnapshotCodec.ParsedLcdcSnapshotState {
        val map = VglibSnapshotCodec.requireMap(raw, "lcdcSnapshot")
        val expectedKeys = setOf(
            "unready", "controlReadLimit", "offsets", "samples",
            "position", "coordinates", "controlWrites", "controlReads", "controlLocations",
        )
        require(map.keys == expectedKeys) { "Unexpected keys in lcdcSnapshot state" }
        require(VglibSnapshotCodec.requireBoolean(map["unready"], "lcdcSnapshot.unready") == unready) {
            "LCD snapshot unready flag mismatch"
        }
        val savedLimit = map["controlReadLimit"]?.let { rawLimit ->
            VglibSnapshotCodec.requireInt(rawLimit, "lcdcSnapshot.controlReadLimit", 1..MAX_CONTROL_READS)
        }
        require(savedLimit == controlReadLimit) { "LCD snapshot controlReadLimit mismatch" }
        val savedOffsets = VglibSnapshotCodec.decodeWords(map["offsets"], LIMIT, "lcdcSnapshot.offsets")
        val savedSamples = VglibSnapshotCodec.decodeWords(map["samples"], LIMIT, "lcdcSnapshot.samples")
        require(savedOffsets.contentEquals(offsets) && savedSamples.contentEquals(samples)) {
            "LCD snapshot finite definition differs from live input"
        }
        val nextPosition = VglibSnapshotCodec.requireInt(map["position"], "lcdcSnapshot.position", 0..LIMIT)
        val (nextTicks, nextCores, nextPcs) = VglibSnapshotCodec.decodeCoordinates(
            map["coordinates"],
            nextPosition,
            LIMIT,
            "lcdcSnapshot.coordinates",
        )
        val nextControlWrites = VglibSnapshotCodec.requireInt(
            map["controlWrites"],
            "lcdcSnapshot.controlWrites",
            0..if (unready) 1 else 0,
        )
        if (nextPosition < LIMIT) {
            require(nextControlWrites == 0) { "LCD unready control write cannot precede complete snapshot prefix" }
        }
        val maxReads = if (unready && nextControlWrites == 1) checkNotNull(controlReadLimit) else 0
        val nextControlReads = VglibSnapshotCodec.requireInt(
            map["controlReads"],
            "lcdcSnapshot.controlReads",
            0..maxReads,
        )
        val rawLocations = map["controlLocations"] as? List<*>
            ?: throw IllegalArgumentException("Missing lcdcSnapshot.controlLocations")
        require(rawLocations.size == 3) { "LCD snapshot requires 3 controlLocations slots" }
        val parsedLocations = rawLocations.mapIndexed { slot, rawLocation ->
            rawLocation?.let { nonNull -> VglibSnapshotCodec.parseCoordinateObject(nonNull, "lcdcSnapshot.controlLocations[$slot]") }
        }
        require((parsedLocations[0] != null) == (nextControlWrites == 1)) {
            "LCD unready write coordinate disagrees with controlWrites"
        }
        if (nextControlReads == 0) {
            require(parsedLocations[1] == null && parsedLocations[2] == null) {
                "LCD unready read coordinates present with zero controlReads"
            }
        } else if (nextControlReads == 1) {
            require(parsedLocations[1] != null && parsedLocations[1] == parsedLocations[2]) {
                "Single LCD unready poll must have matching firstRead and lastRead coordinates"
            }
        } else {
            require(parsedLocations[1] != null && parsedLocations[2] != null) {
                "Multiple LCD unready polls require both firstRead and lastRead coordinates"
            }
        }
        val nextControlTicks = LongArray(3) { slot -> parsedLocations[slot]?.tick ?: -1L }
        val nextControlCores = IntArray(3) { slot -> parsedLocations[slot]?.core ?: -1 }
        val nextControlPcs = LongArray(3) { slot -> parsedLocations[slot]?.pcVirtual ?: -1L }
        return VglibSnapshotCodec.ParsedLcdcSnapshotState(
            position = nextPosition,
            ticks = nextTicks,
            cores = nextCores,
            pcs = nextPcs,
            controlWrites = nextControlWrites,
            controlReads = nextControlReads,
            controlTicks = nextControlTicks,
            controlCores = nextControlCores,
            controlPcs = nextControlPcs,
        )
    }
    internal fun applySnapshotState(parsed: VglibSnapshotCodec.ParsedLcdcSnapshotState) {
        position = parsed.position
        parsed.ticks.copyInto(ticks)
        parsed.cores.copyInto(cores)
        parsed.pcs.copyInto(pcs)
        controlWrites = parsed.controlWrites
        controlReads = parsed.controlReads
        parsed.controlTicks.copyInto(controlTicks)
        parsed.controlCores.copyInto(controlCores)
        parsed.controlPcs.copyInto(controlPcs)
    }
    companion object {
        const val PROFILE="lcdc-instance1-snapshot-experimental-v1"
        const val UNREADY_PROFILE="lcdc-instance1-unready-experimental-v1"
        const val MAX_CONTROL_READS=400_000
        const val PORT="soc-vglib-init.lcdc-snapshot"
        const val UNREADY_PORT="soc-vglib-init.lcdc-unready"
        const val LIMIT=140
        fun load(context:ComponentContext,file:String):LcdcSnapshotInput? {
            if(file.isEmpty())return null
            require(file.isNotBlank()) {"LCD snapshot input file cannot be blank"}
            val data=context.readData(file)
            val unready=data["profile"]==UNREADY_PROFILE
            val keys=setOf("schema","profile","readConfidence","reads")+(if(unready)setOf("controlReadLimit","controlInitialValue","controlWriteValue","controlReadValue")else emptySet())
            require(data.keys==keys&&data["schema"]==1&&data["profile"] in setOf(PROFILE,UNREADY_PROFILE)&&data["readConfidence"]=="HYPOTHESIS") {"invalid LCD snapshot input"}
            val rows=data["reads"] as? List<*> ?: error("missing LCD snapshot reads")
            require(rows.size==LIMIT) {"LCD snapshot requires 140 explicit reads"}
            fun unsigned(x:Any?):Long {require(x is Byte||x is Short||x is Int||x is Long){"LCD snapshot input requires integer"};return (x as Number).toLong().also{require(it in 0..0xffffffffL){"LCD snapshot sample exceeds u32"}}}
            val controlLimit=if(!unready)null else {
                val limit=unsigned(data["controlReadLimit"]);require(limit in 1..MAX_CONTROL_READS.toLong()) {"LCD unready control read limit outside 1..400000"}
                listOf("controlInitialValue","controlWriteValue","controlReadValue").forEach{require(unsigned(data[it])==0L) {"LCD unready control values must be explicit integer zero"}}
                limit.toInt()
            }
            val offsets=LongArray(LIMIT);val samples=LongArray(LIMIT)
            rows.forEachIndexed{i,raw->val row=raw as? Map<*,*> ?: error("invalid LCD snapshot row");require(row.keys==setOf("offset","value"));offsets[i]=unsigned(row["offset"]);samples[i]=unsigned(row["value"]);require(offsets[i]<=0x600L&&offsets[i]%4==0L){"LCD snapshot offset outside bounded aligned domain"}}
            return LcdcSnapshotInput(context,offsets,samples,controlLimit)
        }
    }
}
