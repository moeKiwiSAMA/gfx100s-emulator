package com.github.nyabanban.fuji.emulator.components.temperature

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class TemperatureFactoryTest {
    private fun inputs()=mapOf<String,Any>("schema" to 1,"profile" to TemperatureFactory.PROFILE,"conversionTicks" to 10L,"initialWords" to TemperatureBank.addresses.withIndex().associate{(i,a)->a.toString(16)to if(i==7)15L else 0L})
    private class Context(var data:Map<String,Any>):ComponentContext {
        var tick=0L;var reads=0;val entries=mutableMapOf<String,Any>()
        override val directory=Path.of("unused-temperature-data")
        override val clock=EmulationClock{tick}
        override val ports=object:Ports{override fun<T:Any>register(owner:String,name:String,type:Class<T>,port:T){check(entries.putIfAbsent(name,port)==null)};override fun<T:Any>find(name:String,type:Class<T>):T?=entries[name]?.let{type.cast(it)};override fun removeOwner(owner:String)=error("unexpected remove")}
        override val events=object:EventChannel{override fun publish(event:ComponentEvent)=error("unexpected IRQ/event");override fun subscribe(listener:(ComponentEvent)->Unit):AutoCloseable=error("unexpected subscription")}
        override fun dataPath(relative:String)=directory.resolve(relative)
        override fun readData(relative:String):Map<String,Any>{reads++;assertEquals("input.yaml",relative);return data}
    }
    private fun create(c:Context,enabled:Boolean=true,source:String=""):HardwareComponent {val f=TemperatureFactory();return f.create(c,f.descriptor.settings.associate{it.key to it.default}+mapOf("profile" to if(enabled)TemperatureFactory.PROFILE else "disabled","inputFile" to if(enabled)"input.yaml" else "","samplePort" to source))}
    @Test fun disabledControllerNeverPublishesMmioOrReadsInputs(){val c=Context(inputs());val h=create(c,false);h.start();assertEquals(0,c.reads);assertTrue(c.entries.isEmpty());assertTrue(h.additionalMmioPorts().isEmpty());assertTrue(h.interruptRoutes().isEmpty())}
    @Test fun completeFrozenInputsAndExactThreeClaims(){val data=inputs().toMutableMap();val c=Context(data);val h=create(c);data["conversionTicks"]=1;assertEquals(3,c.entries.size);assertEquals(listOf("soc-temperature.common-control","soc-temperature.common-gate","soc-temperature.converter"),h.additionalMmioPorts());assertEquals(10L,(h.state()["registers"]as Map<*,*>)["conversionTicks"]);assertTrue(h.interruptRoutes().isEmpty());assertEquals(1,c.reads)}
    @Test fun missingExtraWrongTypedAndInconsistentInputsFailBeforeAnyPublication(){for(key in inputs().keys){val c=Context(inputs()-key);assertFails{create(c)};assertTrue(c.entries.isEmpty())};for((key,bad)in listOf("schema" to 1.0,"conversionTicks" to 10.0,"initialWords" to emptyMap<String,Int>(),"extra" to 0)){val c=Context(inputs()+mapOf(key to bad));assertFails{create(c)};assertTrue(c.entries.isEmpty())}}
    @Test fun holesAlignmentAndWordSizesRemainStrictInsideNativeTransportPage(){val c=Context(inputs());val h=create(c);h.start();val p=c.ports.find("soc-temperature.converter",MmioDevice::class.java)!!;val before=h.state();for((o,w)in listOf(12L to 4,28L to 4,-4L to 4,1L to 4,0L to 2,0L to 8,68L to 4)){assertFails{p.read(o,w)};assertFails{p.write(o,w,0)};assertEquals(before,h.state())};val common=c.ports.find("soc-temperature.common-control",MmioDevice::class.java)!!;assertFails{common.read(4,4)}}
    @Test fun deadlineIsPureAndMissingSampleRetainsPendingThenExplicitSampleCanComplete(){val c=Context(inputs());val h=create(c);h.start();val p=c.ports.find("soc-temperature.converter",MmioDevice::class.java)!!;val s=p as ScheduledMmioDevice;p.write(0,4,1);p.write(4,4,1);assertEquals(8,s.clockOrder);assertEquals(10L,s.nextDeadline());val before=h.state();c.tick=10;assertEquals(before,h.state());assertFails{s.beforeInstruction(10)};assertEquals(before,h.state());assertEquals(10L,s.nextDeadline())}
    @Test fun namedTypedSampleEndpointIsResolvedOnceAndClosedControllerRefusesRetainedPorts(){val c=Context(inputs());var calls=0;c.entries["source"]=TemperatureSamplePort{calls++;31};val h=create(c,source="source");h.start();val p=c.ports.find("soc-temperature.converter",MmioDevice::class.java)!!;p.write(0,4,1);p.write(4,4,1);c.tick=10;(p as ScheduledMmioDevice).beforeInstruction(10);assertEquals(31L,p.read(36,4));assertEquals(1,calls);h.close();h.close();assertFails{p.read(0,4)};assertFails{p.nextDeadline()};assertFails{h.start()}}
    @Test fun absentNamedSamplePortFailsStartupWithoutGuessingADevice(){val c=Context(inputs());val h=create(c,source="missing");assertFails{h.start()};h.close()}
}
