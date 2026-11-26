package com.github.nyabanban.fuji.emulator.components.flash

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class FlashEraseTest {
    private fun hash(b:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
    private inner class Context:ComponentContext {
        var tick=0L
        val raw=ByteArray(131072)
        val seedSha=hash(raw)
        var currentSha=seedSha
        var generation=0L
        var prepared=0;var cancelled=0;var committed=0;var pending=false;var failCommit=false;var mismatch=false
        val claims=linkedMapOf<String,Any>();val lines=mutableListOf<Boolean>()
        override val directory=Path.of(".").toAbsolutePath()
        override val clock=EmulationClock{tick}
        override val ports=object:Ports {
            override fun <T:Any> register(owner:String,name:String,type:Class<T>,port:T){check(claims.putIfAbsent(name,port)==null)}
            override fun <T:Any> find(name:String,type:Class<T>):T?=claims[name]?.let{type.cast(it)}
            override fun removeOwner(owner:String){}
        }
        override val events=object:EventChannel{override fun publish(event:ComponentEvent){};override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
        override fun dataPath(relative:String):Path=error("no filesystem in controller TEST")
        init {
            claims["test.image"]=object:SupplementalImagePort {
                override val length=4096L;override val sha256="0".repeat(64)
                override val supplementalRegions=listOf(ImageRegionIdentity("test",0x3de0000,131072,seedSha,"seed.img"))
                override fun check(offset:Long,size:Long){require(offset>=0x3de0000&&size>0&&offset<=0x3e00000-size)}
                override fun read(offset:Long,destination:ByteBuffer){check(offset,destination.remaining().toLong());destination.put(raw,(offset-0x3de0000).toInt(),destination.remaining())}
            }
            claims["test.image.mutable"]=object:WritableImagePort {
                override fun snapshot()=WritableImageState("test",0x3de0000,131072,"copy.img",0x3de0000,131072,if(mismatch)"f".repeat(64)else seedSha,currentSha,generation,pending,false,null)
                override fun prepareErase(offset:Long,size:Long):ImageEraseTransaction {
                    require(!pending&&size in listOf(4096L,65536L)&&offset>=0x3de0000&&offset%size==0L&&offset<=0x3e00000-size)
                    pending=true;prepared++
                    return object:ImageEraseTransaction {
                        override val offset=offset;override val size=size;var ended=false
                        override fun commit():ImageEraseReceipt {
                            check(!ended&&pending);if(failCommit)error("injected storage failure before write")
                            val before=currentSha;val start=(offset-0x3de0000).toInt();val changed=(start until start+size.toInt()).count{raw[it]!=0xff.toByte()}.toLong()
                            raw.fill(0xff.toByte(),start,start+size.toInt());currentSha=hash(raw);generation++;committed++;pending=false;ended=true
                            return ImageEraseReceipt(offset,size,changed,before,currentSha,generation)
                        }
                        override fun close(){if(!ended){ended=true;pending=false;cancelled++}}
                    }
                }
            }
            claims["test.irq"]=object:InterruptSink{override fun setLine(irq:Int,high:Boolean){lines.add(high)}}
            claims["board.layout"]=object:AddressLayout{override fun window(port:String)=AddressWindow(port,0xf0000000,0x8000000)}
        }
    }
    private inner class Fixture(profile:String=FlashFactory.ERASE_PROFILE,irq:String="test.irq") {
        val context=Context()
        val hardware=FlashFactory().create(context,mapOf("profile" to profile,"jedec" to FlashFactory.ENABLE_JEDEC,"imagePort" to "test.image","interruptPort" to irq,"irq" to 37L))
        val port=context.ports.find("soc-flash.registers",MmioDevice::class.java)!!
        val clocked get()=port as ScheduledMmioDevice
        fun write(at:Int,value:Long)=port.write(at.toLong(),4,value)
        fun read(at:Int)=port.read(at.toLong(),4)
        fun advance(tick:Long){context.tick=tick;port.beforeInstruction(tick)}
        fun enable(){write(0x90,0x06000001);advance(context.tick+8)}
        fun submit(address:Long=0xf3de0000,command:Long=0xdc0b0001){write(0x94,address);write(0x90,command)}
        fun status()=hardware.state()["erase"] as Map<*,*>
        fun latch()=(hardware.state()["pioEnableStatus"] as Map<*,*>)["writeEnabledBySelector"] as List<*>
    }
    @Test fun commandAcceptanceAndArrayCommitAreSeparateExactDeadlinesAndStatusPhases() {
        val f=Fixture();f.enable();f.context.tick=10;f.submit()
        assertEquals(18L,f.clocked.nextDeadline());assertEquals(listOf(true,false),f.latch());assertEquals(0,f.context.committed)
        f.advance(17);assertEquals(2L,f.read(0x90)and 2);f.advance(18)
        assertEquals(4106L,f.clocked.nextDeadline());assertEquals(listOf(false,false),f.latch());assertTrue(f.context.raw.all{it==0.toByte()})
        f.context.tick=20;f.write(0x90,0x05800001);f.advance(28);assertEquals(1L,f.read(0xa0))
        assertFails{f.write(0,1L shl 10)};assertFails{f.write(0,1L shl 30)};assertFails{f.write(0x90,0x06000001)}
        f.advance(4105);assertEquals(0,f.context.committed);f.advance(4106);assertEquals(1,f.context.committed);assertNull(f.clocked.nextDeadline())
        assertTrue(f.context.raw.take(65536).all{it==0xff.toByte()});assertTrue(f.context.raw.drop(65536).all{it==0.toByte()})
        f.context.tick=4110;f.write(0x90,0x05800001);f.advance(4118);assertEquals(0L,f.read(0xa0));assertEquals(1L,f.status()["committed"])
    }
    @Test fun equalDeadlineCommitsBeforeStatusAndLateObservationKeepsDeclaredOrder() {
        for(observed in listOf(4106L,8000L)) {
            val f=Fixture();f.enable();f.context.tick=10;f.submit();f.advance(18)
            f.context.tick=4098;f.write(0x90,0x05800001);assertEquals(4106L,f.clocked.nextDeadline());f.advance(observed)
            assertEquals(0L,f.read(0xa0));assertEquals(1,f.context.committed)
            val events=(f.hardware.state()["events"]as List<*>).map{it as Map<*,*>}.takeLast(2)
            assertEquals(listOf("erase-array-complete","pio-complete"),events.map{it["operation"]});assertTrue(events.all{it["deadline"]==4106L&&it["observedAt"]==observed})
        }
    }
    @Test fun oldV2AndMissingWriteEnableRefuseBeforeLeaseOrControllerMutation() {
        val legacy=Fixture(FlashFactory.ENABLE_PROFILE);legacy.enable();legacy.write(0x94,0xf3de0000);val old=legacy.hardware.state()
        assertFails{legacy.write(0x90,0xdc0b0001)};assertEquals(old,legacy.hardware.state());assertEquals(0,legacy.context.prepared)
        val f=Fixture();f.write(0x94,0xf3de0000);val before=f.hardware.state();assertFails{f.write(0x90,0xdc0b0001)};assertEquals(before,f.hardware.state());assertEquals(0,f.context.prepared)
    }
    @Test fun malformedShapeAddressSelectorAndParametersRefuseWithoutSideEffects() {
        val cases=listOf(0x90 to 0xdc0b0011L,0x94 to 0xf3de0001L,0x94 to 0xefff0000L,0x94 to 0xf8000000L,0x94 to 0xf3dc0000L,
            0 to 0x40000000L,0 to 1024L,0xa8 to 1L,0xe0 to 1L,0xf0 to 1L)
        for((at,value)in cases){val f=Fixture();f.enable();f.write(0x94,0xf3de0000);if(at!=0x90)f.write(at,value);val before=f.hardware.state()
            assertFails{f.write(0x90,if(at==0x90)value else 0xdc0b0001)};assertEquals(before,f.hardware.state());assertEquals(0,f.context.prepared);assertFalse(f.context.pending)}
    }
    @Test fun missingPeerIdentityMismatchAndOverflowAllRefuseBeforeOwnership() {
        for(kind in listOf("missing","identity","overflow","negative")) {
            val f=Fixture();f.enable();f.write(0x94,0xf3de0000)
            when(kind){"missing"->f.context.claims.remove("test.image.mutable");"identity"->f.context.mismatch=true;"overflow"->f.context.tick=Long.MAX_VALUE-100;"negative"->f.context.tick=-1}
            val before=f.hardware.state();assertFails{f.write(0x90,0xdc0b0001)};assertEquals(before,f.hardware.state());assertEquals(0,f.context.prepared);assertNull(f.clocked.nextDeadline())
        }
    }
    @Test fun closeCancelsPendingStorageAtBothPhasesAndNeverCommitsLater() {
        for(accepted in listOf(false,true)){val f=Fixture();f.enable();f.context.tick=10;f.submit();if(accepted)f.advance(18);f.hardware.close();f.hardware.close()
            assertEquals(1,f.context.cancelled);assertEquals(0,f.context.committed);assertFalse(f.context.pending);assertNull(f.clocked.nextDeadline());assertFails{f.advance(5000)}
            assertTrue(f.context.raw.all{it==0.toByte()});assertEquals(if(accepted)listOf(false,false)else listOf(true,false),f.latch())}
    }
    @Test fun missingIrqRetainsAcceptedCommandAndStorageRemainsPending() {
        val f=Fixture(irq="");f.enable();f.write(0x44,0x4000);f.context.tick=10;f.submit()
        assertFails{f.advance(18)};assertEquals(true,f.hardware.state()["irqPending"]);assertEquals(4106L,f.clocked.nextDeadline());assertEquals(listOf(false,false),f.latch())
        assertEquals(0,f.context.committed);assertTrue(f.context.pending);f.hardware.close();assertEquals(1,f.context.cancelled)
    }
    @Test fun failedStorageNeverClearsPendingOrReportsArrayCompletion() {
        val f=Fixture();f.enable();f.context.tick=10;f.submit();f.advance(18);f.context.failCommit=true;assertFails{f.advance(4106)}
        assertNotNull(f.status()["failure"]);assertNotNull(f.status()["pending"]);assertEquals(0L,f.status()["committed"]);assertEquals(false,f.hardware.state()["mutations"])
        assertEquals(0,f.context.committed);assertTrue(f.context.raw.all{it==0.toByte()});assertFails{f.advance(5000)}
        assertFalse((f.hardware.state()["events"]as List<*>).map{it as Map<*,*>}.any{it["operation"]=="erase-array-complete"});f.hardware.close()
    }
    @Test fun eachEraseConsumesOneEnableAndFourKiBPathUsesItsOwnByteCount() {
        val f=Fixture();f.enable();f.context.tick=10;f.submit();f.advance(4106);f.write(0x94,0xf3df0000);val before=f.hardware.state()
        assertFails{f.write(0x90,0x210b0001)};assertEquals(before,f.hardware.state())
        f.enable();f.context.tick=4120;f.submit(0xf3df0000,0x210b0001);f.advance(8216)
        assertEquals(2,f.context.committed);assertTrue(f.context.raw.take(65536+4096).all{it==0xff.toByte()});assertTrue(f.context.raw.drop(65536+4096).all{it==0.toByte()});assertEquals(listOf(false,false),f.latch())
        assertFails{f.write(0x90,0x120b0001)};assertFails{f.write(0x70,0)}
    }
}
