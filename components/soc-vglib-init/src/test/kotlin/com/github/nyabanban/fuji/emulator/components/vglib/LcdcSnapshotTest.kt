package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class LcdcSnapshotTest {
    private fun offsets()=listOf(8L,0x30L,0x14L,0x20L)+(0x40L..0x68L step 4)+(0x130L..0x1c0L step 4)+(0x1c8L..0x320L step 4)+listOf(0x600L)
    private inner class Context(val bad:Any?=0xa5000000L):ComponentContext {
        private val entries=HashMap<String,Any>();var tick=0L;var failLocation=false
        override val directory=Path.of(".")
        override val ports=object:Ports {
            override fun<T:Any> register(owner:String,name:String,type:Class<T>,port:T){check(entries.putIfAbsent(name,port)==null)}
            override fun<T:Any> find(name:String,type:Class<T>):T?=if(name=="cpu.execution-location")type.cast(ExecutionLocationSource{if(failLocation)error("location failed")else ExecutionLocation(2,0x5afe0)})else entries[name]?.let(type::cast)
            override fun removeOwner(owner:String){entries.clear()}
        }
        override val clock=EmulationClock{tick}
        override val events=object:EventChannel {override fun publish(event:ComponentEvent)=error("snapshot has no autonomous effects");override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
        override fun dataPath(relative:String)=directory.resolve(relative)
        override fun readData(relative:String):Map<String,Any?> = if(relative=="bank")mapOf("schema" to 1,"profile" to VglibInitFactory.PROFILE,"readConfidence" to "HYPOTHESIS","transactions" to List(18){mapOf("offset" to 0,"readValue" to 0,"writeValue" to 0)})else mapOf("schema" to 1,"profile" to LcdcSnapshotInput.PROFILE,"readConfidence" to "HYPOTHESIS","reads" to offsets().mapIndexed{i,x->mapOf("offset" to x,"value" to if(i==0)bad else 0xa5000000L+i)})
    }
    @Test fun all140ReadsAndRefusalsAreFiniteAndOldStateIsPreservedWhenAbsent() {
        val f=VglibInitFactory();val old=f.create(Context(),mapOf("profile" to VglibInitFactory.PROFILE,"inputFile" to "bank"));val empty=f.create(Context(),mapOf("profile" to VglibInitFactory.PROFILE,"inputFile" to "bank","lcdcSnapshotInputFile" to ""))
        assertEquals(old.state(),empty.state());assertEquals(old.additionalMmioPorts(),empty.additionalMmioPorts());assertFalse(empty.state().containsKey("lcdcSnapshotInputFile"))
        val disabled=f.create(Context(),mapOf("profile" to "disabled","inputFile" to "","lcdcSnapshotInputFile" to ""));assertTrue(disabled.additionalMmioPorts().isEmpty());assertFalse(disabled.state().containsKey("lcdcSnapshot"))
        val c=Context();val h=f.create(c,mapOf("profile" to VglibInitFactory.PROFILE,"inputFile" to "bank","lcdcSnapshotInputFile" to "snapshot"));val p=c.ports.find(LcdcSnapshotInput.PORT,MmioDevice::class.java)!!
        fun refused(action:()->Unit){val before=h.state();assertFails{action()};assertEquals(before,h.state())}
        val order=offsets();assertEquals(140,order.size)
        order.forEachIndexed{i,x->refused{p.write(x,4,0)};refused{p.read(x,2)};refused{p.read(x+1,4)};refused{p.read(0x604,4)};if(i<139)refused{p.read(order[i+1],4)};if(i>0)refused{p.read(order[i-1],4)}
            if(i==0||i==139){c.tick=-1;refused{p.read(x,4)};c.tick=i.toLong();c.failLocation=true;refused{p.read(x,4)};c.failLocation=false}
            assertEquals(0xa5000000L+i,p.read(x,4))
        }
        refused{p.read(order[0],4)};refused{p.write(0,4,0)}
        val state=h.state()["lcdcSnapshot"] as Map<*,*>;assertEquals(140,state["transactionCount"]);assertEquals(0,state["remainingTransactions"]);assertEquals(false,state["eventLogTruncated"]);assertEquals(0,h.state()["transactionCount"]);assertTrue(h.interruptRoutes().isEmpty())
        for(v in listOf(null,-1L,0x100000000L,"0",1.5))assertFails{LcdcSnapshotInput.load(Context(v),"snapshot")}
        assertFails{f.create(Context(),mapOf("profile" to "disabled","inputFile" to "","lcdcSnapshotInputFile" to "snapshot"))}
    }
}
