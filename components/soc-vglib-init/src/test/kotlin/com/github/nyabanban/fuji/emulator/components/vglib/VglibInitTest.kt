package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class VglibInitTest {
    private class PortMap:Ports {
        val ports=HashMap<String,Any>()
        override fun <T:Any> register(owner:String,name:String,type:Class<T>,port:T){check(ports.putIfAbsent(name,port)==null)}
        override fun <T:Any> find(name:String,type:Class<T>):T?=ports[name]?.let(type::cast)
        override fun removeOwner(owner:String){ports.keys.removeIf{it.startsWith(owner)}}
    }
    private class Context(val input:Map<String,Any?>):ComponentContext {
        override val directory=Path.of(".");override val ports=PortMap();var tick=0L
        override val clock=EmulationClock{tick}
        override val events=object:EventChannel{override fun publish(event:ComponentEvent)=error("no vglib event effects permitted");override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
        override fun dataPath(relative:String)=directory.resolve(relative)
        override fun readData(relative:String)=input
    }
    @Test fun finitePairsAndAllRefusalsPreserveSequenceAndHaveNoAutonomousEffects() {
        val rows=(0 until 18).map{mapOf("offset" to (it%2)*4,"readValue" to (0x80000000L+it),"writeValue" to it)}
        val c=Context(mapOf("schema" to 1,"profile" to VglibInitFactory.PROFILE,"readConfidence" to "HYPOTHESIS","transactions" to rows))
        val f=VglibInitFactory();val disabled=f.create(c,mapOf("profile" to "disabled","inputFile" to ""));assertTrue(disabled.additionalMmioPorts().isEmpty());assertNull(c.ports.find("soc-vglib-init.registers",MmioDevice::class.java))
        val component=f.create(c,mapOf("profile" to VglibInitFactory.PROFILE,"inputFile" to "sequence.yaml"));val port=c.ports.find("soc-vglib-init.registers",MmioDevice::class.java)!!
        fun refused(call:()->Unit){val before=component.state();assertFails{call()};assertEquals(before,component.state())}
        rows.forEachIndexed{i,row->val offset=(row.getValue("offset") as Int).toLong()
            refused{port.write(offset,4,i.toLong())};refused{port.read(offset,8)};refused{port.read(offset+1,4)};refused{port.read(0x10,4)}
            c.tick=-1;refused{port.read(offset,4)};c.tick=i.toLong()
            assertEquals(0x80000000L+i,port.read(offset,4));refused{port.read(offset,4)};refused{port.write(offset,2,i.toLong())};refused{port.write(offset,4,i+1L)}
            port.write(offset,4,i.toLong())
        }
        assertEquals(36,component.state()["transactionCount"]);assertEquals(18,component.state()["completedPairs"]);assertEquals(0,component.state()["remainingTransactions"])
        refused{port.read(0,4)};refused{port.write(4,4,17)};assertTrue(component.interruptRoutes().isEmpty())
    }
}
