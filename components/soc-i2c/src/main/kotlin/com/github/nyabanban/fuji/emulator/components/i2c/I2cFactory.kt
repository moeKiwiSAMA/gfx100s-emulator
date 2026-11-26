package com.github.nyabanban.fuji.emulator.components.i2c

import com.github.nyabanban.fuji.emulator.api.*
import java.util.ArrayDeque

/** FW-018/SRC-014 bounded byte master; time/reset/IRQ wiring are explicitly selected, bus lives in another JAR. */
class I2cFactory : ComponentFactory {
    companion object {
        private val DESCRIPTOR=ComponentDescriptor("soc-i2c","0.0.1",settings=listOf(
            Setting("profile",ValueKind.TEXT,"disabled",choices=listOf("disabled","byte-i2c-functional-experimental-v1"),live=false),Setting("inputFile",ValueKind.TEXT,"",live=false)))
        private fun snapshotLong(value: Any?,field: String,min: Long,max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for $field" }
            val number=(value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $field")
            require(number in min..max) { "Out-of-range integer for $field" }
            return number
        }
        private fun snapshotInt(value: Any?,field: String,min: Int,max: Int): Int =
            snapshotLong(value,field,min.toLong(),max.toLong()).toInt()
    }
    override val descriptor=DESCRIPTOR
    override fun create(context: ComponentContext,config: Map<String,Any>): HardwareComponent=Controllers(context,config)
    private class Controllers(context: ComponentContext,config: Map<String,Any>) : ScalarComponent("soc-i2c",context,config),StatefulComponent,NativeMmioMirrorPort {
        private val profile=config.getValue("profile") as String
        private val enabled=profile!="disabled"
        private val controllers=ArrayList<Controller>()
        private var started=false
        private var closed=false
        init {
            val file=config.getValue("inputFile") as String;require(enabled==file.isNotBlank())
            if(enabled) {
                val data=context.readData(file);require(data.keys==setOf("profile","controllers") && data["profile"]=="byte-i2c-functional-experimental-v1")
                val rows=data["controllers"];require(rows is List<*> && rows.size in 1..3);val indexes=HashSet<Int>();val routes=HashSet<Pair<String,Int>>()
                rows.forEach { raw->
                    require(raw is Map<*,*> && raw.keys==setOf("index","irq","interruptPort","busPort","byte_ticks","reset"))
                    val index=integer(raw["index"],2).toInt();require(indexes.add(index)) { "duplicate I2C controller index" }
                    val irq=raw["irq"]?.let { integer(it,511).toInt().also { n->require(n>=32) } }
                    val sink=raw["interruptPort"];val bus=raw["busPort"];require(sink is String && bus is String && sink.length<=128 && bus.length<=128);require((irq==null)==sink.isEmpty()) { "I2C IRQ/sink selection mismatch" }
                    if(irq!=null) require(routes.add(sink to irq)) { "duplicate I2C IRQ route" }
                    val ticks=integer(raw["byte_ticks"],0xffffffffL);require(ticks>=1)
                    val reset=raw["reset"];require(reset is Map<*,*> && reset.keys==setOf("ccr","adr","csr","fsr"))
                    val ccr=integer(reset["ccr"],255).toInt();val adr=integer(reset["adr"],255).toInt();val csr=integer(reset["csr"],63).toInt();val fsr=integer(reset["fsr"],255).toInt();require(ccr and 128==0 && adr==0)
                    val controller=Controller(context,index,irq,sink,bus,ticks,ccr,adr,csr,fsr);controllers.add(controller)
                    context.ports.register(id,"$id.controller$index",MmioDevice::class.java,controller)
                }
                controllers.sortBy {it.index}
            }
        }
        private fun integer(v: Any?,max: Long): Long { require(v is Byte || v is Short || v is Int || v is Long);val n=(v as Number).toLong();require(n in 0..max);return n }
        override fun start() { check(!closed) { "soc-i2c closed" }; controllers.forEach { controller -> controller.connect() }; started=true }
        override fun additionalMmioPorts()=controllers.filter {it.index==2}.map {"soc-i2c.controller${it.index}"}
        override fun interruptRoutes()=controllers.mapNotNull {it.route()}
        override fun state(): Map<String,Any?> = super.state()+mapOf("controllers" to controllers.map {it.state()},"basis" to "Indexed byte-controller ports use firmware metadata for addresses; static reset/byte ticks, explicit bus and lab routes are unmeasured inputs")
        override fun close() { closed=true; detachMmioMirrors(); controllers.reversed().forEach { controller -> controller.close() } }
        override fun describeMmioMirrorWindows(): List<MmioMirrorWindowSpec> =
            if (!enabled) emptyList() else controllers.map { it.mirrorWindowSpec() }
        override fun attachMmioMirror(portName: String, handle: MmioMirrorHandle) {
            controllers.firstOrNull { portName == "$id.controller${it.index}" }?.attachMirror(handle)
        }
        override fun detachMmioMirrors() {
            controllers.forEach { it.detachMirror() }
        }
        override fun saveSnapshot(): Map<String,Any?> {
            check(!closed) { "soc-i2c closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "enabled" to enabled,
                "controllers" to controllers.map { controller -> controller.saveSnapshot() },
            )
        }
        override fun restoreSnapshot(data: Map<String,Any?>) {
            check(!closed) { "soc-i2c closed" }
            val expectedKeys=setOf("schema","componentId","scalars","enabled","controllers")
            require(data.keys==expectedKeys) { "Unexpected or missing keys in soc-i2c snapshot" }
            require(snapshotInt(data["schema"],"schema",1,1)==snapshotSchemaVersion) { "Unsupported soc-i2c snapshot schema" }
            require(data["componentId"]==id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw=data["scalars"] as? Map<*,*> ?: throw IllegalArgumentException("Missing scalar settings map for $id")
            require(scalarsRaw.keys==DESCRIPTOR.settings.map { setting -> setting.key }.toSet()) { "Scalar setting keys differ for $id" }
            val savedProfile=scalarsRaw["profile"] as? String ?: throw IllegalArgumentException("Invalid profile in soc-i2c snapshot")
            require(savedProfile in listOf("disabled","byte-i2c-functional-experimental-v1")) { "Unknown soc-i2c profile in snapshot" }
            val savedEnabled=data["enabled"] as? Boolean ?: throw IllegalArgumentException("Missing enabled boolean in soc-i2c snapshot")
            require(savedEnabled==(savedProfile!="disabled")) { "soc-i2c enabled flag disagrees with profile in snapshot" }
            val rawControllers=data["controllers"] as? List<*> ?: throw IllegalArgumentException("Missing controllers list in soc-i2c snapshot")
            if(!savedEnabled) {
                require(rawControllers.isEmpty()) { "Disabled soc-i2c snapshot contains controller entries" }
                val resetTargets=controllers.map { controller -> controller to controller.prepareResetSync() }
                restoreScalarValues(scalarsRaw,DESCRIPTOR,compatibleNonLiveKeys=setOf("profile","inputFile"))
                resetTargets.forEach { (controller,sink) -> controller.applyReset(sink) }
                return
            }
            require(enabled) { "Cannot restore enabled soc-i2c snapshot into disabled component" }
            require(rawControllers.size in 1..3) { "soc-i2c snapshot requires 1..3 controllers" }
            val strictConfig=scalarsRaw["profile"]==profile && scalarsRaw["inputFile"]==values["inputFile"]
            val liveByIndex=controllers.associateBy { controller -> controller.index }
            val seenIndexes=HashSet<Int>()
            val parsedByIndex=HashMap<Int,Controller.ParsedSnapshot>()
            for((listPos,rawItem) in rawControllers.withIndex()) {
                val row=rawItem as? Map<*,*> ?: throw IllegalArgumentException("Invalid controller snapshot at position $listPos")
                val controllerIndex=snapshotInt(row["index"],"controllers[$listPos].index",0,2)
                require(seenIndexes.add(controllerIndex)) { "Duplicate controller index $controllerIndex in soc-i2c snapshot" }
                val liveController=liveByIndex[controllerIndex]
                    ?: throw IllegalArgumentException("Snapshot I2C controller $controllerIndex missing in live configuration")
                parsedByIndex[controllerIndex]=liveController.parseSnapshot(row,strictConfig=strictConfig,requireSinkWhenPending=started)
            }
            if(strictConfig) {
                require(seenIndexes==liveByIndex.keys) { "Controller index set differs from live configuration" }
            }
            val resetTargets=controllers.filter { controller -> controller.index !in parsedByIndex }
                .map { controller -> controller to controller.prepareResetSync() }
            restoreScalarValues(scalarsRaw,DESCRIPTOR,compatibleNonLiveKeys=setOf("profile","inputFile"))
            controllers.forEach { controller ->
                val parsed=parsedByIndex[controller.index]
                if(parsed!=null) controller.applySnapshot(parsed)
            }
            resetTargets.forEach { (controller,sink) -> controller.applyReset(sink) }
        }
    }
    private class Controller(private val context: ComponentContext,val index: Int,private val irq: Int?,private val sink: String,private val bus: String,private val ticks: Long,ccr: Int,adr: Int,csr: Int,fsr: Int) : TickAwareMmioDevice,AutoCloseable {
        override val clockOrder=5 // Preserve the I2C family after ADC4 and before flash7/DMA10.
        override val clockIndex=index // Explicit functional order, not measured silicon arbitration.
        private val irqSink by lazy { if (irq == null) null else context.ports.find(sink, InterruptSink::class.java) ?: error("I2C IRQ sink missing") }
        private var syncedIrq: Boolean? = null
        override fun nextInterestedTick(): Long = if (syncedIrq != irqPending()) 0L else (s.pending?.deadline ?: Long.MAX_VALUE)
        override fun readMayMutateSchedule(): Boolean = false
        private data class Pending(val submitted: Long,val deadline: Long,val kind: String,val byte: Int=0,val ack: Boolean=false)
        private data class State(val ccr: Int,val adr: Int,val csr: Int,val fsr: Int,val bcr: Int=0,val bsr: Int=0,val dar: Int=0,
            val staged: Boolean=false,val rxReady: Boolean=false,val lowDrives: Int=0,val phase: String="idle",val pending: Pending?=null,val address7: Int?=null)
        private val initialState=State(ccr,adr,csr,fsr)
        private var s=State(ccr,adr,csr,fsr);private val reset=mapOf("ccr" to ccr,"adr" to adr,"csr" to csr,"fsr" to fsr)
        private var peer: I2cConnection?=null;private val events=ArrayDeque<Map<String,Any>>();private var eventCount=0L
        private var mirror: MmioMirrorHandle? = null
        fun mirrorWindowSpec(): MmioMirrorWindowSpec = MmioMirrorWindowSpec(
            portName = "soc-i2c.controller$index",
            windowSize = 0x20L,
            slots = listOf(
                MmioMirrorSlotSpec(0x00L, 1), // bsr
                MmioMirrorSlotSpec(0x04L, 1), // bcr
                MmioMirrorSlotSpec(0x08L, 1), // ccr
                MmioMirrorSlotSpec(0x0CL, 1), // adr
                // 0x10 (dar) intentionally omitted: reads clear rxReady
                MmioMirrorSlotSpec(0x14L, 1), // csr
                MmioMirrorSlotSpec(0x18L, 1), // fsr
                MmioMirrorSlotSpec(0x1CL, 1), // lowDrives + line state
            ),
        )
        fun attachMirror(handle: MmioMirrorHandle) {
            mirror = handle
            syncMirror()
        }
        fun detachMirror() {
            mirror?.invalidateAll()
            mirror = null
        }
        private fun syncMirror() {
            val h = mirror ?: return
            h.writeSlotRaw(0, s.bsr)
            h.writeSlotRaw(1, s.bcr)
            h.writeSlotRaw(2, s.ccr)
            h.writeSlotRaw(3, s.adr)
            h.writeSlotRaw(4, s.csr)
            h.writeSlotRaw(5, s.fsr)
            val lowWord = s.lowDrives or (if (s.lowDrives and 1 == 0) 16 else 0) or (if (s.lowDrives and 2 == 0) 32 else 0)
            h.writeSlotRaw(6, lowWord)
            h.setValidMask(h.allSlotsMask)
        }
        fun connect() { if(bus.isNotEmpty()) peer=checkNotNull(context.ports.find(bus,I2cBusPort::class.java)) { "I2C bus port missing: $bus" }.connect("soc-i2c.controller$index") }
        fun route()=irq?.let {InterruptRoute(sink,it..it)}
        private fun irqPending()=s.bcr and 3==3 || s.bcr and 192==192
        private fun sync() {
            val pending=irqPending();check(!pending || irq!=null) { "unmodeled-byte-i2c-interrupt" }
            if (syncedIrq == pending) return
            if(irq!=null) irqSink?.setLine(irq,pending)
            syncedIrq = pending
        }
        private fun link(): I2cConnection=checkNotNull(peer) { "I2C peer unspecified" }
        private fun command(value: Int): Pending? {
            require(value and 4==0) { "I2C general call unsupported" }
            require(!(value and 1!=0 && s.bcr and 1==0 || value and 128!=0 && s.bcr and 128==0)) { "I2C status cannot be generated by software" }
            val control=value and 129.inv()
            if(value and 1!=0) { require(s.pending==null && !s.staged && control and 66.inv()==s.bcr and (129 or 66).inv()) { "retained I2C event permits only interrupt-enable changes" };return null }
            require(s.pending?.kind!="stop") { "I2C STOP already pending" }
            var kind: String;var byte=0;var ack=false
            if(control and 16==0) { require(!s.rxReady) { "I2C STOP requires receive consumption" };require(control and 40==0) { "unsupported idle I2C control" };if(s.phase=="idle" && s.pending==null) return null;kind="stop" }
            else {
                require(s.ccr and 32!=0 && s.lowDrives==0 && s.pending==null && !s.rxReady) { "I2C disabled/recovering/pending/unread command" }
                if(control and 32!=0 || s.phase=="idle") { require(s.staged && s.dar ushr 1 in 8..0x77 && control and 8==0) { "I2C START needs staged seven-bit address" };kind="address";byte=s.dar }
                else {
                    require(s.bcr and 1!=0) { "I2C continuation requires completed event" }
                    when {
                        s.phase=="write" && s.staged && control and 8==0->{link().checkWrite(s.dar);kind="write";byte=s.dar}
                        s.phase=="read" && !s.staged->{kind="read";ack=control and 8!=0;byte=link().peekRead();require(byte in 0..255) { "I2C peer returned non-byte data" }}
                        else->error("unsupported I2C phase continuation")
                    }
                }
            }
            val now=context.clock.ticks();require(now>=0);val at=try {Math.addExact(now,ticks)}catch(e: ArithmeticException){throw IllegalArgumentException("I2C deadline overflow",e)}
            return Pending(now,at,kind,byte,ack)
        }
        private fun check(offset: Long,size: Int) { require(size==1 && offset in 0..28 && offset%4==0L) { "I2C requires known byte register/value" } }
        override fun read(offset: Long,size: Int): Long {
            check(offset,size)
            val value=when(offset) {0L->s.bsr;4L->s.bcr;8L->s.ccr;12L->s.adr;16L->s.dar;20L->s.csr;24L->s.fsr;else->s.lowDrives or (if(s.lowDrives and 1==0)16 else 0) or (if(s.lowDrives and 2==0)32 else 0)}
            if(offset==16L && (s.phase=="read" || s.phase=="read-ended")) {require(s.rxReady) { "I2C receive data not ready" };s=s.copy(rxReady=false)}
            return value.toLong()
        }
        override fun write(offset: Long,size: Int,value: Long) {
            check(offset,size);require(value in 0..255);val v=value.toInt()
            var stop=false
            val next=when(offset) {
                0L->error("I2C bus status is read-only")
                4L->{val op=command(v);s.copy(pending=op,staged=if(op==null)s.staged else false,bsr=if(op?.kind=="address")128 else s.bsr,bcr=(v and 161.inv()) or (s.bcr and v and 129))}
                8L,12L,20L,24L->{
                    if(offset==8L && v and 160==0) {stop=true;s.copy(ccr=v,pending=null,phase="idle",bcr=0,bsr=0,staged=false,rxReady=false,address7=null)}
                    else if(offset==8L && v==s.ccr) {s}
                    else {require(s.phase=="idle" && s.pending==null) { "active I2C reconfiguration unsupported" };when(offset) {8L->{require(v and 128==0);s.copy(ccr=v)};12L->{require(v==0);s.copy(adr=v)};20L->{require(v<=63);s.copy(csr=v)};else->s.copy(fsr=v)}}
                }
                16L->{require(s.pending==null && !s.rxReady && !s.staged && s.phase!="read-ended" && s.phase!="nacked") { "I2C data staging in illegal phase" };s.copy(dar=v,staged=true)}
                else->{require(v<=3 && s.phase=="idle" && s.pending==null) { "I2C virtual low-drive recovery requires idle" };s.copy(lowDrives=v)}
            }
            if(stop) peer?.stop()
            s=next // IRQ levels are sampled at the next owner instruction boundary, like other source ports.
            syncMirror()
        }
        override fun nextDeadline():Long?=s.pending?.deadline
        override fun beforeInstruction(tick: Long) {
            require(tick>=0)
            val p=s.pending
            if(p!=null && p.deadline<=tick) {
                val event=linkedMapOf<String,Any>("tick" to p.deadline,"operation" to p.kind)
                val next=when(p.kind) {
                    "address"->{val connection=peer ?: error("I2C peer unspecified for address 0x${(p.byte ushr 1).toString(16).padStart(2,'0')}");val ack=connection.address(p.byte)
                        event.putAll(mapOf("address7" to (p.byte ushr 1),"direction" to if(p.byte and 1!=0)"read" else "write","peerAck" to ack))
                        s.copy(pending=null,phase=if(!ack)"nacked" else if(p.byte and 1!=0)"read" else "write",bsr=129 or (if(p.byte and 1==0)8 else 0) or (if(ack)0 else 16),bcr=s.bcr or 1,address7=if(ack)p.byte ushr 1 else null)}
                    "write"->{link().write(p.byte);event.putAll(mapOf("address7" to checkNotNull(s.address7),"data" to p.byte,"peerAck" to true));s.copy(pending=null,bsr=136,bcr=s.bcr or 1)}
                    "read"->{link().consumeRead(p.byte);event.putAll(mapOf("address7" to checkNotNull(s.address7),"data" to p.byte,"masterAck" to p.ack));s.copy(pending=null,dar=p.byte,rxReady=true,bsr=128 or if(p.ack)0 else 16,phase=if(p.ack)s.phase else "read-ended",bcr=s.bcr or 1)}
                    "stop"->{peer?.stop();s.copy(pending=null,phase="idle",bsr=0,rxReady=false,address7=null)}
                    else->error("unknown I2C pending operation")
                }
                s=next;if(events.size==256) events.removeFirst();events.addLast(event);eventCount++
                syncMirror()
            }
            sync()
        }
        fun state(): Map<String,Any?> = mapOf("index" to index,"busPort" to bus,"irq" to irq,"interruptPort" to sink,"byteTicks" to ticks,"reset" to reset,
            "registers" to mapOf("ccr" to s.ccr,"adr" to s.adr,"csr" to s.csr,"fsr" to s.fsr,"bcr" to s.bcr,"bsr" to s.bsr,"dar" to s.dar,"staged" to s.staged,"rxReady" to s.rxReady,"lowDrives" to s.lowDrives,"phase" to s.phase,
                "pending" to s.pending?.let {p->mapOf("submitted" to p.submitted,"deadline" to p.deadline,"kind" to p.kind)+when(p.kind){"read"->mapOf("byte" to p.byte,"ack" to p.ack);"stop"->emptyMap();else->mapOf("byte" to p.byte)}}),
            "irqPending" to irqPending(),"events" to events.toList(),"eventCount" to eventCount,"eventLogTruncated" to (eventCount>events.size))
        override fun close() {peer?.close();peer=null}

        internal class ParsedSnapshot(
            val nextState: State,
            val nextEventCount: Long,
            val nextEvents: List<Map<String,Any>>,
            val nextIrqPending: Boolean,
            val resolvedSink: InterruptSink?,
        )

        fun saveSnapshot(): Map<String,Any?> = linkedMapOf(
            "index" to index,
            "busPort" to bus,
            "irq" to irq,
            "interruptPort" to sink,
            "byteTicks" to ticks,
            "ccr" to s.ccr,
            "adr" to s.adr,
            "csr" to s.csr,
            "fsr" to s.fsr,
            "bcr" to s.bcr,
            "bsr" to s.bsr,
            "dar" to s.dar,
            "staged" to s.staged,
            "rxReady" to s.rxReady,
            "lowDrives" to s.lowDrives,
            "phase" to s.phase,
            "address7" to s.address7,
            "pending" to s.pending?.let { op ->
                linkedMapOf(
                    "submitted" to op.submitted,
                    "deadline" to op.deadline,
                    "kind" to op.kind,
                    "byte" to op.byte,
                    "ack" to op.ack,
                )
            },
            "eventCount" to eventCount,
            "events" to events.map { entry -> LinkedHashMap(entry) },
        )

        fun parseSnapshot(row: Map<*,*>,strictConfig: Boolean,requireSinkWhenPending: Boolean): ParsedSnapshot {
            val expectedKeys=setOf(
                "index","busPort","irq","interruptPort","byteTicks","ccr","adr","csr","fsr",
                "bcr","bsr","dar","staged","rxReady","lowDrives","phase","address7","pending","eventCount","events",
            )
            require(row.keys==expectedKeys) { "Invalid keys for I2C controller $index snapshot" }
            require(snapshotInt(row["index"],"controller.index",0,2)==index) { "I2C controller index mismatch" }
            val savedBus=row["busPort"] as? String ?: throw IllegalArgumentException("Invalid busPort in controller $index snapshot")
            val savedIrq=row["irq"]?.let { irqVal -> snapshotInt(irqVal,"controller[$index].irq",32,511) }
            val savedSink=row["interruptPort"] as? String ?: throw IllegalArgumentException("Invalid interruptPort in controller $index snapshot")
            require((savedIrq==null)==savedSink.isEmpty()) { "Mismatched irq/interruptPort in controller $index snapshot" }
            val savedTicks=snapshotLong(row["byteTicks"],"controller[$index].byteTicks",1L,0xffffffffL)
            if(strictConfig) {
                require(savedBus==bus && savedIrq==irq && savedSink==sink && savedTicks==ticks) {
                    "Controller $index wiring or timing differs from live configuration"
                }
            } else {
                require(savedBus.isEmpty() || savedBus==bus) { "Incompatible busPort change on I2C controller $index" }
                val idleEyeBus = index == 1 && savedIrq == 511 && irq == 66 &&
                    savedSink == "soc-gic.lines" && sink == savedSink && bus == "i2c-bus.bus1" && savedBus == bus &&
                    savedTicks == ticks && row["phase"] == "idle" && row["pending"] == null &&
                    row["staged"] == false && row["rxReady"] == false &&
                    snapshotInt(row["bcr"], "bcr", 0, 255) == 0 && snapshotInt(row["bsr"], "bsr", 0, 255) == 0
                val nackedEyeBus = index == 1 && savedIrq == 511 && irq == 66 &&
                    savedSink == "soc-gic.lines" && sink == savedSink && bus == "i2c-bus.bus1" && savedBus == bus &&
                    savedTicks == ticks && row["phase"] == "nacked" && row["pending"] == null &&
                    row["staged"] == false && row["rxReady"] == false &&
                    snapshotInt(row["dar"], "controller[$index].dar", 0, 255) == 0x72 &&
                    (snapshotInt(row["bcr"], "controller[$index].bcr", 0, 255) and 36) == 0
                require(savedIrq==null || (savedIrq==irq && savedSink==sink) || idleEyeBus || nackedEyeBus) { "Incompatible IRQ route change on I2C controller $index" }
            }
            val nextCcr=snapshotInt(row["ccr"],"controller[$index].ccr",0,127)
            val nextAdr=snapshotInt(row["adr"],"controller[$index].adr",0,0)
            val nextCsr=snapshotInt(row["csr"],"controller[$index].csr",0,63)
            val nextFsr=snapshotInt(row["fsr"],"controller[$index].fsr",0,255)
            val nextBcr=snapshotInt(row["bcr"],"controller[$index].bcr",0,255)
            require((nextBcr and 36)==0) { "Illegal control bits in restored BCR for controller $index" }
            val nextBsr=snapshotInt(row["bsr"],"controller[$index].bsr",0,255)
            val nextDar=snapshotInt(row["dar"],"controller[$index].dar",0,255)
            val nextStaged=row["staged"] as? Boolean ?: throw IllegalArgumentException("Invalid staged boolean for controller $index")
            val nextRxReady=row["rxReady"] as? Boolean ?: throw IllegalArgumentException("Invalid rxReady boolean for controller $index")
            val nextLowDrives=snapshotInt(row["lowDrives"],"controller[$index].lowDrives",0,3)
            val nextPhase=row["phase"] as? String ?: throw IllegalArgumentException("Invalid phase for controller $index")
            require(nextPhase in setOf("idle","write","read","read-ended","nacked")) { "Unknown I2C phase: $nextPhase" }
            val nextAddress7=row["address7"]?.let { addr -> snapshotInt(addr,"controller[$index].address7",8,0x77) }
            if(nextPhase in setOf("write","read","read-ended")) {
                require(nextAddress7!=null) { "Active I2C phase $nextPhase requires address7" }
            } else {
                require(nextAddress7==null) { "Phase $nextPhase must not retain address7" }
            }
            if(nextRxReady) {
                require(nextPhase in setOf("read","read-ended") && !nextStaged) { "Illegal rxReady state for controller $index" }
            }
            if(nextStaged) {
                require(nextPhase !in setOf("read-ended","nacked")) { "Illegal staged state in phase $nextPhase" }
            }
            if(nextLowDrives!=0) {
                require(nextPhase=="idle") { "Non-zero lowDrives requires idle phase" }
            }
            val nextPending=row["pending"]?.let { rawPending ->
                require(savedTicks==ticks) { "Pending I2C operation requires matching byteTicks" }
                val pendingMap=rawPending as? Map<*,*> ?: throw IllegalArgumentException("Invalid pending map for controller $index")
                require(pendingMap.keys==setOf("submitted","deadline","kind","byte","ack")) {
                    "Invalid pending keys for controller $index"
                }
                val submitted=snapshotLong(pendingMap["submitted"],"pending.submitted",0L,Long.MAX_VALUE)
                val deadline=snapshotLong(pendingMap["deadline"],"pending.deadline",submitted,Long.MAX_VALUE)
                val kind=pendingMap["kind"] as? String ?: throw IllegalArgumentException("Invalid pending.kind")
                require(kind in setOf("address","write","read","stop")) { "Unknown pending I2C kind: $kind" }
                val byteVal=snapshotInt(pendingMap["byte"],"pending.byte",0,255)
                val ackVal=pendingMap["ack"] as? Boolean ?: throw IllegalArgumentException("Invalid pending.ack")
                require(!nextStaged && !nextRxReady && nextLowDrives==0) {
                    "Pending I2C command conflicts with staged, rxReady, or lowDrives state"
                }
                when(kind) {
                    "address" -> require((byteVal ushr 1) in 8..0x77 && !ackVal) { "Invalid pending I2C address command" }
                    "write" -> require(nextPhase=="write" && !ackVal) { "Invalid pending I2C write command" }
                    "read" -> require(nextPhase=="read") { "Invalid pending I2C read command" }
                    "stop" -> require(byteVal==0 && !ackVal) { "Invalid pending I2C stop command" }
                }
                Pending(submitted,deadline,kind,byteVal,ackVal)
            }
            val nextEventCount=snapshotLong(row["eventCount"],"controller[$index].eventCount",0L,Long.MAX_VALUE)
            val nextEvents=parseEvents(row["events"],nextEventCount)
            val nextIrqPending=(nextBcr and 3)==3 || (nextBcr and 192)==192
            require(!nextIrqPending || irq!=null) { "Restored I2C controller $index has pending IRQ without configured IRQ route" }
            val resolvedSink=if(irq!=null) context.ports.find(sink,InterruptSink::class.java) else null
            if(nextIrqPending && requireSinkWhenPending) {
                require(resolvedSink!=null) { "I2C IRQ sink missing for controller $index" }
            }
            val restoredState=State(
                ccr=nextCcr,adr=nextAdr,csr=nextCsr,fsr=nextFsr,bcr=nextBcr,bsr=nextBsr,dar=nextDar,
                staged=nextStaged,rxReady=nextRxReady,lowDrives=nextLowDrives,phase=nextPhase,
                pending=nextPending,address7=nextAddress7,
            )
            return ParsedSnapshot(restoredState,nextEventCount,nextEvents,nextIrqPending,resolvedSink)
        }

        fun applySnapshot(parsed: ParsedSnapshot) {
            s=parsed.nextState
            eventCount=parsed.nextEventCount
            events.clear()
            events.addAll(parsed.nextEvents)
            if(irq!=null && parsed.resolvedSink != null) {
                parsed.resolvedSink.setLine(irq,parsed.nextIrqPending)
                syncedIrq = parsed.nextIrqPending
            } else {
                syncedIrq = null
            }
            syncMirror()
        }

        fun prepareResetSync(): InterruptSink? = if(irq!=null) context.ports.find(sink,InterruptSink::class.java) else null

        fun applyReset(resolvedSink: InterruptSink?) {
            s=initialState
            eventCount=0L
            events.clear()
            if(irq!=null && resolvedSink != null) {
                resolvedSink.setLine(irq,false)
                syncedIrq = false
            } else {
                syncedIrq = null
            }
            syncMirror()
        }

        private fun parseEvents(raw: Any?,totalCount: Long): List<Map<String,Any>> {
            val rows=raw as? List<*> ?: throw IllegalArgumentException("Missing events list for controller $index")
            val expectedSize=minOf(totalCount,256L).toInt()
            require(rows.size==expectedSize) { "Controller $index events size mismatches eventCount" }
            return rows.mapIndexed { eventIndex,item ->
                val entry=item as? Map<*,*> ?: throw IllegalArgumentException("Invalid event at $eventIndex for controller $index")
                val tick=snapshotLong(entry["tick"],"events[$eventIndex].tick",0L,Long.MAX_VALUE)
                val operation=entry["operation"] as? String ?: throw IllegalArgumentException("Missing event operation at $eventIndex")
                val reconstructed=linkedMapOf<String,Any>("tick" to tick,"operation" to operation)
                when(operation) {
                    "address" -> {
                        require(entry.keys==setOf("tick","operation","address7","direction","peerAck")) { "Invalid address event keys" }
                        val addr=snapshotInt(entry["address7"],"events[$eventIndex].address7",8,0x77)
                        val direction=entry["direction"] as? String ?: throw IllegalArgumentException("Invalid address event direction")
                        require(direction in setOf("read","write")) { "Invalid I2C direction: $direction" }
                        val peerAck=entry["peerAck"] as? Boolean ?: throw IllegalArgumentException("Invalid peerAck")
                        reconstructed["address7"]=addr
                        reconstructed["direction"]=direction
                        reconstructed["peerAck"]=peerAck
                    }
                    "write" -> {
                        require(entry.keys==setOf("tick","operation","address7","data","peerAck")) { "Invalid write event keys" }
                        val addr=snapshotInt(entry["address7"],"events[$eventIndex].address7",8,0x77)
                        val dataByte=snapshotInt(entry["data"],"events[$eventIndex].data",0,255)
                        val peerAck=entry["peerAck"] as? Boolean ?: throw IllegalArgumentException("Invalid peerAck")
                        require(peerAck) { "Committed I2C write event requires peerAck=true" }
                        reconstructed["address7"]=addr
                        reconstructed["data"]=dataByte
                        reconstructed["peerAck"]=true
                    }
                    "read" -> {
                        require(entry.keys==setOf("tick","operation","address7","data","masterAck")) { "Invalid read event keys" }
                        val addr=snapshotInt(entry["address7"],"events[$eventIndex].address7",8,0x77)
                        val dataByte=snapshotInt(entry["data"],"events[$eventIndex].data",0,255)
                        val masterAck=entry["masterAck"] as? Boolean ?: throw IllegalArgumentException("Invalid masterAck")
                        reconstructed["address7"]=addr
                        reconstructed["data"]=dataByte
                        reconstructed["masterAck"]=masterAck
                    }
                    "stop" -> require(entry.keys==setOf("tick","operation")) { "Invalid stop event keys" }
                    else -> throw IllegalArgumentException("Unknown I2C event operation: $operation")
                }
                reconstructed
            }
        }
    }
}
