package com.github.nyabanban.fuji.emulator.components.method4

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Arrays
import java.util.Base64
import java.util.HexFormat

class Method4Factory : ComponentFactory {
    companion object {
        const val V1_PROFILE="method4-functional-experimental-v1"
        const val V2_PROFILE="method4-ring-input-experimental-v2"
        private val HEX = HexFormat.of()
        val DESCRIPTOR=ComponentDescriptor("soc-method4","0.0.1",settings=listOf(
            Setting("profile",ValueKind.TEXT,V1_PROFILE,choices=listOf(V1_PROFILE,V2_PROFILE),live=false),
            Setting("memoryPort",ValueKind.TEXT,"cpu.memory",live=false),Setting("interruptPort",ValueKind.TEXT,"",live=false),
            Setting("irq",ValueKind.INTEGER,128L,32.0,511.0,live=false)))
    }
    override val descriptor=DESCRIPTOR
    override fun create(context: ComponentContext,config: Map<String,Any>): HardwareComponent=Engine(context,config)
    private class Engine(context: ComponentContext,config: Map<String,Any>) : ScalarComponent("soc-method4",context,config),TickAwareMmioDevice,StatefulComponent {
        override val clockOrder=50
        // Page decoding is synchronous on MMIO writes; IRQ synchronization has no timed completion.
        override fun nextDeadline():Long?=null
        private val interruptPortName = config.getValue("interruptPort") as String
        private val irqLine = (config.getValue("irq") as Number).toInt()
        private val irqSink by lazy {
            if (interruptPortName.isEmpty()) null else context.ports.find(interruptPortName, InterruptSink::class.java) ?: error("method-4 IRQ sink missing")
        }
        private var irqDirty = true
        override fun nextInterestedTick(): Long = if (irqDirty) 0L else Long.MAX_VALUE
        override fun readMayMutateSchedule(): Boolean = false
        private val offsets=(0..0x28 step 4).toSet()+setOf(0x40,0x68)
        private val r=LongArray(27); private var stream=Stream(); private var configured=false; private var pending=false
        private var completedSha: String?=null
        private val events=ArrayDeque<Map<String,Any?>>()
        private var eventCount=0L
        private val pageDigest = MessageDigest.getInstance("SHA-256")
        private val streamDigest = MessageDigest.getInstance("SHA-256")
        private var streamDigestValid = false
        private var readbackScratch = ByteArray(65536)
        // Explicit finite P207 caller geometry; all other streams retain the linear v1 law.
        private fun ring(base:Long)=values["profile"]=="method4-ring-input-experimental-v2" && base==0x1c08000L
        private fun expectedSource(base:Long,consumed:Int)=base+if(ring(base))consumed%0x40000 else consumed
        init { context.ports.register(id,id,MmioDevice::class.java,this) }
        override fun interruptRoutes(): List<InterruptRoute> {
            val name=values.getValue("interruptPort") as String; val line=(values.getValue("irq") as Number).toInt()
            return if(name.isEmpty()) emptyList() else listOf(InterruptRoute(name,line..line))
        }
        override fun beforeInstruction(tick: Long) {
            val name = interruptPortName
            if (name.isNotEmpty()) irqSink?.setLine(irqLine, pending)
            check(!pending || name.isNotEmpty()) { "method-4 IRQ pending: GIC route missing" }
            irqDirty = false
        }
        private fun memory()=context.ports.find(values.getValue("memoryPort") as String,DmaMemory::class.java) ?: error("method-4 RAM peer missing")
        private fun readRam(address: Long,size: Int): ByteArray {
            require(size>0); val memory=memory(); memory.check(address,size.toLong(),false)
            val b=ByteBuffer.allocate(size); memory.read(address,b); require(!b.hasRemaining()) { "short method-4 RAM peer read" }; return b.array()
        }
        private fun verifyRamReadback(memory: DmaMemory, address: Long, expected: ByteArray) {
            val size = expected.size
            memory.check(address, size.toLong(), false)
            if (readbackScratch.size < size) readbackScratch = ByteArray(size)
            val b = ByteBuffer.wrap(readbackScratch, 0, size)
            memory.read(address, b)
            require(!b.hasRemaining()) { "short method-4 RAM peer read" }
            require(Arrays.equals(expected, 0, size, readbackScratch, 0, size)) { "method-4 output readback differs" }
        }
        private fun hash(bytes: ByteArray): String {
            pageDigest.reset()
            return HEX.formatHex(pageDigest.digest(bytes))
        }
        private fun hashRam(address: Long,size: Int): String {
            val memory = memory()
            pageDigest.reset(); var done=0
            while(done<size) {
                val count=minOf(readbackScratch.size,size-done)
                memory.check(address+done,count.toLong(),false)
                val b=ByteBuffer.wrap(readbackScratch,0,count)
                memory.read(address+done,b)
                require(!b.hasRemaining()) { "short method-4 RAM peer read" }
                pageDigest.update(readbackScratch,0,count)
                done+=count
            }
            return HEX.formatHex(pageDigest.digest())
        }
        private fun check(offset: Long,size: Int) { require(size==4 && offset in 0..0x68 && offset%4==0L && offset.toInt() in offsets) { "unmodeled method-4 register/width" } }
        override fun read(offset: Long,size: Int): Long {
            check(offset,size); return when(offset) { 0L->1; 0x24L->0x10001; else->r[offset.toInt()/4] }
        }
        override fun write(offset: Long,size: Int,value: Long) {
            check(offset,size); require(value in 0..0xffffffffL); val i=offset.toInt()/4
            when(offset) {
                0L -> { require(value==0L) { "unsupported method-4 reset value" }; r.fill(0); stream=Stream(); configured=false; pending=false; completedSha=null; streamDigestValid=false; irqDirty=true }
                0x28L,0x68L -> { r[i]=r[i] and value.inv(); pending=(r[10] or r[26]) and 1!=0L; irqDirty=true }
                0x24L -> error("write to method-4 ready status")
                0x40L -> { require(value==0L) { "OSD transport not selected" }; r[i]=value }
                0x20L -> {
                    require(value==0x84000400L || value==0x94001400L) { "unsupported method-4 configuration" }
                    if(value==0x84000400L) { stream=Stream(); configured=false; completedSha=null; streamDigestValid=false } else configured=true
                    r[i]=value
                }
                4L -> { require(value==0L || value==1L) { "unsupported method-4 start value" }; if(value==1L) { decode(); irqDirty=true } else r[i]=0 }
                else -> r[i]=value
            }
        }
        private fun decode() {
            require(configured && r[16]==0L) { "method-4 stream not configured in mode zero" }
            require(r[10] and 1==0L) { "method-4 completion must be acknowledged" }
            val next=stream.copy(); val source=r[2]; val destination=r[4]
            val memory = memory()
            val startingFresh = next.header == null
            if(startingFresh) {
                require(source>=20) { "method-4 header address underflow" }
                val b=ByteBuffer.wrap(readRam(source-20,20)).order(ByteOrder.LITTLE_ENDIAN)
                val h=List(5) { b.getInt(it*4).toLong() and 0xffffffffL }
                require(h[4]==4L && h[3]==0x4000L && h[0] in 1..0x4000000L && h[1] in 20..0x4000000L &&
                    h[2]==(h[1]+0x3fff)/0x4000 && r[5]==h[0] && r[6]==20L && r[7]==h[2]) { "inconsistent or unsupported method-4 header" }
                memory.check(destination,h[0],true)
                memory.check(destination,h[0],false)
                require(destination+h[0]<=source-20 || source-20+h[1]<=destination) { "method-4 source and destination overlap unqualified" }
                next.header=h.map { it.toInt() }; next.sourceBase=source-20; next.destination=destination
            }
            val h=checkNotNull(next.header)
            require(next.page<h[2] && source==expectedSource(next.sourceBase,next.consumed) && destination==next.destination) { "method-4 source/destination/page order differs" }
            val expected=minOf(h[1]-next.page*0x4000,0x4000)
            require(r[3]==expected.toLong()) { "method-4 page length differs" }
            val count=expected-if(next.page==0) 20 else 0
            val input=if(count==0) ByteArray(0) else readRam(source,count)
            val offset=next.produced; val output=next.feed(input) // validate entire page before any output mutation
            if(output.isNotEmpty()) {
                val b=ByteBuffer.wrap(output); memory.check(destination+offset,output.size.toLong(),true); memory.write(destination+offset,b)
                require(!b.hasRemaining()) { "short method-4 RAM peer write" }
                verifyRamReadback(memory, destination+offset, output)
            }
            if (startingFresh) {
                streamDigest.reset()
                streamDigestValid = true
            }
            if (streamDigestValid && output.isNotEmpty()) {
                streamDigest.update(output)
            }
            next.page++
            val finalHash=if(next.page==h[2]) {
                if (streamDigestValid) {
                    streamDigestValid = false
                    HEX.formatHex(streamDigest.digest())
                } else {
                    hashRam(destination, next.produced)
                }
            } else null
            stream=next; r[10]=r[10] or 1; pending=true; completedSha=finalHash
            if(events.size==4096) events.removeFirst()
            events.addLast(mapOf("tick" to context.clock.ticks(),"source" to source,"destination" to destination+offset,"inputSize" to input.size,"outputSize" to output.size,
                "page" to next.page,"consumed" to next.consumed,"produced" to next.produced,"inputSha256" to hash(input),"outputSha256" to hash(output),"completedSha256" to finalHash)); eventCount++
        }
        override fun state()=super.state()+mapOf("registers" to offsets.sorted().associate { "0x${it.toString(16)}" to r[it/4] },"configured" to configured,"irqPending" to pending,
            "header" to stream.header?.toList(),"consumed" to stream.consumed,"produced" to stream.produced,"page" to stream.page,"literalRemaining" to stream.literal,"matchFirst" to stream.matchFirst,
            "sourceMode" to if(ring(stream.sourceBase))"ring" else "linear","sourceBase" to stream.sourceBase,"sourceCapacity" to if(ring(stream.sourceBase))0x40000 else null,
            "completedSha256" to completedSha,"outputBase" to stream.destination,"outputReadbackSize" to stream.produced,"outputReadbackSha256" to if(stream.produced>0) hashRam(stream.destination,stream.produced) else null,
            "events" to events.toList(),"eventCount" to eventCount,"eventLogTruncated" to (eventCount>events.size),
            "basis" to "Mode-0 method-4 streaming actual RAM/readback; zero reset/ready constants/instant page completion/IRQ route HYPOTHESIS; no OSD codec")
        override fun saveSnapshot(): Map<String,Any?> = linkedMapOf(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "settings" to saveScalarValues(),
            "registers" to Method4SnapshotCodec.encodeRegisters(r),
            "configured" to configured,
            "irqPending" to pending,
            "completedSha256" to completedSha,
            "stream" to linkedMapOf(
                "header" to stream.header?.toList(),
                "sourceBase" to stream.sourceBase,
                "destination" to stream.destination,
                "consumed" to stream.consumed,
                "produced" to stream.produced,
                "page" to stream.page,
                "literalRemaining" to stream.literal,
                "matchFirst" to stream.matchFirst,
                "historyBase64" to Base64.getEncoder().encodeToString(stream.historyBytes()),
            ),
            "eventCount" to eventCount,
            "events" to events.map { entry -> LinkedHashMap(entry) },
        )
        override fun restoreSnapshot(data: Map<String,Any?>) {
            val parsed = Method4SnapshotCodec.parse(
                data = data,
                expectedSchema = snapshotSchemaVersion,
                expectedId = id,
                liveValues = values,
                modeledOffsets = offsets,
            )
            restoreScalarValues(parsed.settingsMap, DESCRIPTOR, parsed.compatibleKeys)
            parsed.registers.copyInto(r)
            configured = parsed.configured
            pending = parsed.pending
            completedSha = parsed.completedSha
            stream = parsed.stream
            streamDigestValid = false
            eventCount = parsed.eventCount
            events.clear()
            events.addAll(parsed.events)
            irqDirty = true
        }
    }
}
