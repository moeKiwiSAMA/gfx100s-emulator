package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.api.display.DotLcdTransportCodec
import com.github.nyabanban.fuji.emulator.catalog.HistoricalCatalogFixture
import com.github.nyabanban.fuji.emulator.catalog.CatalogCompiler
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Real independent JARs and archived transport fixture. No CPU/native/GUI is started. */
class DotLcdBindingTest {
    @TempDir lateinit var temporary:Path
    private val project=Path.of(System.getProperty("componentProjectRoot"))
    private val history = HistoricalCatalogFixture(project)
    private fun archived(name: String): ByteArray = history.bytes(
        "72e84d535ff07721c06d627c12b7c9ce72ee250f",
        "evidence/refactor/power26-flash-original-fresh-06/study/discovery-cap8-v5-gpio110-power26-flash-zero/$name",
    )
    private val serialInput by lazy {
        Files.write(temporary.resolve("serial-input.yaml"), archived("compoments/soc-serial-engine/input.yaml"))
    }
    private val sourceProfile="gfx100s-serial-transmit-functional-experimental-v3"
    private val sinkProfile="dotlcd-serial1-experimental-v1"
    private fun actual()=archived("serial-capture-1-1.bin").also {assertEquals(19488,it.size);assertTrue(DotLcdTransportCodec.decode(it).all {b->b.toInt()==1})}
    private data class Setup(val loop:OwnerLoop,val host:ComponentHost):AutoCloseable {
        override fun close(){loop.submit {host.close()}.join();loop.close()}
    }
    private fun setup(events:Boolean=false,binding:Boolean=false,sizes:List<Int> = listOf(19488),onlyShoulder:Boolean=false,width:Int=if(binding)304 else 320,height:Int=if(binding)240 else 160):Setup {
        val home=Files.createTempDirectory(temporary,"components-")
        for(id in listOf("shoulder-display")+if(onlyShoulder)emptyList()else listOf("soc-serial-engine")) {
            Files.copy(project.resolve("components/"+id+"/build/libs/"+id+"-0.3.0.jar"),home.resolve(id+"-0.3.0.jar"))
            Files.createDirectories(home.resolve(id))
        }
        Formats.atomicYaml(home.resolve("shoulder-display/shoulder-display.yaml"),mapOf("width" to width,"height" to height,"brightness" to 1.0,"profile" to if(binding)sinkProfile else "unbound"))
        if(!onlyShoulder) {
            Formats.atomicYaml(home.resolve("soc-serial-engine/soc-serial-engine.yaml"),mapOf("profile" to sourceProfile,"inputFile" to "input.yaml","captureEvents" to events))
            val data=Formats.readData(serialInput)
            Formats.writeJson(home.resolve("soc-serial-engine/input.yaml"),data+("transmitPlans" to mapOf("1" to sizes.map {mapOf("byteCount" to it,"acceptDelayTicks" to 0)})))
        }
        val loop=OwnerLoop();return Setup(loop,ComponentHost(loop,home,CatalogCompiler.compile(project)))
    }
    private fun load(s:Setup)=s.loop.submit {
        s.host.load()
        for(c in s.host.components())assertNotSame(ComponentFactory::class.java.classLoader,c.component.javaClass.classLoader)
        s.host.ports.register("test","board.layout",AddressLayout::class.java,object:AddressLayout{override fun window(port:String)=AddressWindow(port,0xfff30024L,4)})
    }.join()
    private fun transmit(s:Setup,bytes:ByteArray)=s.loop.submit {
        val control=assertNotNull(s.host.ports.find("soc-serial-engine.control-1",MmioDevice::class.java))
        val peer=assertNotNull(s.host.ports.find("soc-serial-engine.tx-1",DmaDestinationPeer::class.java))
        control.write(0,4,0)
        assertEquals(0L,peer.claim(0,0xfff30024L,bytes.size.toLong(),4,21))
        peer.write(0,0xfff30024L,0,ByteBuffer.wrap(bytes))
        peer.finish(0,0,0)
        assertEquals(0L,s.host.tick)
        assertTrue(s.host.components().all {it.component.interruptRoutes().isEmpty()})
    }.join()
    private fun component(s:Setup,id:String)=s.host.components().single {it.descriptor.id==id}.component
    private fun state(s:Setup)=component(s,"shoulder-display").state()
    private fun serialState(s:Setup)=((component(s,"soc-serial-engine").state()["transmitStates"] as Map<*,*>)["1"] as Map<*,*>)
    private fun display(s:Setup)=assertNotNull(s.host.ports.find("display.shoulder",DisplayPort::class.java))
    @Test fun defaultsKeepFullLegacyStateNoEventAndNoFrameDespiteCompletedCapture() {
        setup().use {s->load(s)
            s.loop.submit {assertEquals(mapOf("width" to 320L,"height" to 160L,"brightness" to 1.0,"sequence" to 0L,"firmwareDisplayBinding" to "unqualified","backlightOn" to false),state(s))}.join()
            transmit(s,actual())
            s.loop.submit {
                assertEquals(0L,display(s).sequence)
                val target=IntArray(320*160){0x1234};assertFalse(display(s).copyArgb(target));assertTrue(target.all {it==0x1234})
                assertFalse(component(s,"soc-serial-engine").state().containsKey("captureEvents"))
                assertFalse(serialState(s).containsKey("captureNotifications"))
                assertEquals(1L,serialState(s)["completedCount"])
            }.join()
        }
    }
    @Test fun archivedCaptureReachesIndependentJarAndPreservesEveryEmittedRow() {
        setup(events=true,binding=true).use {s->load(s);transmit(s,actual())
            s.loop.submit {
                val p=display(s);assertEquals(304,p.width);assertEquals(240,p.height);assertEquals(1L,p.sequence)
                val pixels=IntArray(304*240);assertTrue(p.copyArgb(pixels));assertTrue(pixels.all {it==-1})
                val snap=state(s);assertEquals(1L,snap["acceptedCaptures"]);assertEquals(0L,snap["rejectedCaptures"])
                assertEquals(10,snap["extraRowsPreserved"]);assertEquals("UNKNOWN",snap["physicalPanelIdentity"]);assertEquals("UNKNOWN",snap["physicalPolarity"])
                val source=serialState(s);assertEquals(1L,source["completedCount"]);assertEquals(1L,source["captureNotifications"])
                assertEquals(0L,source["captureNotificationFailures"]);assertEquals(1,(source["planIndex"] as Number).toInt())
                val capture=assertNotNull(s.host.ports.find("soc-serial-engine.capture-1",TransferCapturePort::class.java)).capture()
                assertEquals(capture?.sha256,(snap["lastAcceptedCapture"] as Map<*,*>)["sha256"])
                assertFails {p.presentArgb(IntArray(304*240),304,240)};assertEquals(1L,p.sequence)
                pixels[0]=0;val again=IntArray(pixels.size);assertTrue(p.copyArgb(again));assertEquals(-1,again[0])
            }.join()
        }
    }
    @Test fun testPatternIsRenderedFromPayloadIncludingLastExtraRow() {
        // TEST transport, not an additional original-firmware observation.
        val bits=ByteArray(304*240){i->if((i%304+i/304)%7==0)1 else 0}
        bits[239*304+303]=1
        setup(events=true,binding=true).use {s->load(s);transmit(s,DotLcdTransportCodec.encode(bits))
            s.loop.submit {val pixels=IntArray(bits.size);assertTrue(display(s).copyArgb(pixels));assertContentEquals(IntArray(bits.size){if(bits[it].toInt()==1)-1 else 0xff000000.toInt()},pixels);assertEquals(-1,pixels.last());assertEquals(1L,display(s).sequence)}.join()
        }
    }
    @Test fun malformedPacketNeverReplacesFrameAndNeverFailsCompletedSource() {
        val bad=actual().also {it[0]=(it[0].toInt() xor 1).toByte()}
        setup(events=true,binding=true,sizes=listOf(19488,19488,19488)).use {s->load(s);transmit(s,bad)
            s.loop.submit {assertEquals(0L,display(s).sequence);assertEquals(1L,state(s)["rejectedCaptures"]);assertEquals(1L,serialState(s)["completedCount"])}.join()
            transmit(s,actual())
            s.loop.submit {assertEquals(1L,display(s).sequence);assertEquals(1L,state(s)["acceptedCaptures"]);assertEquals(1L,state(s)["rejectedCaptures"]);assertEquals(2L,serialState(s)["completedCount"]);assertEquals(0L,serialState(s)["captureNotificationFailures"])}.join()
            transmit(s,bad)
            s.loop.submit {val pixels=IntArray(304*240);assertTrue(display(s).copyArgb(pixels));assertTrue(pixels.all {it==-1});assertEquals(1L,display(s).sequence);assertEquals(2L,state(s)["rejectedCaptures"]);assertEquals(3L,serialState(s)["completedCount"])}.join()
        }
        setup(events=true,binding=true,sizes=listOf(64)).use {s->load(s);transmit(s,ByteArray(64));s.loop.submit {assertEquals(0L,display(s).sequence);assertEquals(1L,state(s)["rejectedCaptures"]);assertEquals(1L,serialState(s)["completedCount"])}.join()}
    }
    @Test fun partialTypedCaptureReadCannotReusePreviousPacketBytes() {
        setup(binding=true,onlyShoulder=true).use {s->
            val bytes=actual();var sequence=1L;var partial=false
            s.loop.submit {s.host.ports.register("test","soc-serial-engine.capture-1",TransferCapturePort::class.java,object:TransferCapturePort {
                override fun capture()=TransferCapture(sequence,bytes.size,"TEST",0,0)
                override fun read(sequence:Long,offset:Long,destination:ByteBuffer){destination.put(bytes,0,if(partial)bytes.size-4 else bytes.size)}
            })}.join();load(s)
            fun notify()=s.loop.submit {s.host.events.publish(ComponentEvent("soc-serial-engine","transfer-capture-complete",0,mapOf("port" to "soc-serial-engine.capture-1","sequence" to sequence,"size" to bytes.size)))}.join()
            notify();s.loop.submit {assertEquals(1L,display(s).sequence)}.join()
            sequence=2;partial=true;notify()
            s.loop.submit {assertEquals(1L,display(s).sequence);assertEquals(1L,state(s)["acceptedCaptures"]);assertEquals(1L,state(s)["rejectedCaptures"]);assertTrue(state(s)["lastRejection"].toString().contains("incomplete"))}.join()
        }
    }
    @Test fun duplicateStaleAndForgedLargeIdentityEventsCannotProduceFrames() {
        setup(events=false,binding=true).use {s->load(s);transmit(s,actual())
            s.loop.submit {
                s.host.events.publish(ComponentEvent("soc-serial-engine","transfer-capture-complete",0,mapOf("port" to "soc-serial-engine.capture-1","sequence" to 1L,"size" to (0x100000000L+19488))))
                assertEquals(0L,display(s).sequence);assertEquals(1L,state(s)["rejectedCaptures"])
                s.host.events.publish(ComponentEvent("soc-serial-engine","transfer-capture-complete",0,mapOf("port" to "soc-serial-engine.capture-1","sequence" to 1L,"size" to 19488)))
                assertEquals(1L,display(s).sequence);assertEquals(0L,state(s)["duplicateEvents"])
                s.host.events.publish(ComponentEvent("soc-serial-engine","transfer-capture-complete",0,mapOf("port" to "soc-serial-engine.capture-1","sequence" to 1L,"size" to 19488)))
                assertEquals(1L,display(s).sequence);assertEquals(1L,state(s)["duplicateEvents"])
                s.host.events.publish(ComponentEvent("soc-serial-engine","transfer-capture-complete",0,mapOf("port" to "soc-serial-engine.capture-1","sequence" to 2L,"size" to 19488)))
                assertEquals(1L,display(s).sequence);assertEquals(2L,state(s)["rejectedCaptures"]);assertEquals(1L,state(s)["lastSeenCaptureSequence"])
            }.join()
        }
    }
    @Test fun explicitSourceAndSinkOptInsAreBothNecessaryForDelivery() {
        for(pair in listOf(true to false,false to true))setup(events=pair.first,binding=pair.second).use {s->load(s);transmit(s,actual());s.loop.submit {assertEquals(0L,display(s).sequence)}.join()}
    }
    @Test fun ownedReadCloseAndNeighborObserverFailureDoNotMutateGuestTime() {
        setup(events=true,binding=true).use {s->load(s)
            val retained=s.loop.submit {s.host.events.subscribe {throw IllegalStateException("TEST observer fault")};display(s)}.join()
            assertFails {retained.sequence};assertFails {retained.copyArgb(IntArray(304*240))}
            transmit(s,actual());s.loop.submit {assertEquals(1L,display(s).sequence);assertEquals(1L,state(s)["acceptedCaptures"]);assertEquals(0L,s.host.tick)}.join()
        }
        setup(events=true,binding=true).use {s->load(s);s.loop.submit {component(s,"shoulder-display").close()}.join();transmit(s,actual());s.loop.submit {assertEquals(0L,state(s)["sequence"]);assertEquals(0L,state(s)["captureEvents"]);assertEquals(1L,serialState(s)["completedCount"])}.join()}
    }
    @Test fun missingSourceWrongGeometryAndRestartOnlyBindingFailWithoutLeakingPorts() {
        for(s in listOf(setup(binding=true,onlyShoulder=true),setup(events=true,binding=true,height=230)))s.use {
            assertFails {s.loop.submit {s.host.load()}.join()}
            s.loop.submit {assertTrue(s.host.components().isEmpty());assertNull(s.host.ports.find("display.shoulder",DisplayPort::class.java))}.join()
        }
        setup(events=true,binding=true).use {s->load(s);val snap=s.host.snapshot().join();assertNotNull(snap)
            assertFails {s.host.patch("shoulder-display",0,mapOf("profile" to "unbound")).join()}
            s.host.patch("shoulder-display",0,mapOf("brightness" to 0.5)).join()
            s.loop.submit {assertEquals(0.5,state(s)["brightness"]);assertEquals(0L,display(s).sequence)}.join()
        }
    }
}
