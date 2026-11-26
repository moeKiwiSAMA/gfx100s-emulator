package com.github.nyabanban.fuji.emulator.components.temperaturesensor

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class TemperatureSensorTest {
    private class Context:ComponentContext {
        lateinit var sample:TemperatureSamplePort
        override val directory=Path.of("unused-temperature-test")
        override val clock=EmulationClock{0}
        override val ports=object:Ports{override fun<T:Any>register(owner:String,name:String,type:Class<T>,port:T){assertEquals("temperature-sensor",owner);assertEquals(owner,name);assertTrue(type==TemperatureSamplePort::class.java);sample=port as TemperatureSamplePort};override fun<T:Any>find(name:String,type:Class<T>):T?=error("unexpected lookup");override fun removeOwner(owner:String)=error("unexpected remove")}
        override val events=object:EventChannel{override fun publish(event:ComponentEvent)=error("unexpected event");override fun subscribe(listener:(ComponentEvent)->Unit):AutoCloseable=error("unexpected event")}
        override fun dataPath(relative:String)=error("no data file for explicit live scalar source")
    }
    @Test fun defaultMissingIsNotAZeroSampleAndEveryLiveRawWordIsIndependentOfBattery(){val c=Context();val f=TemperatureSensorFactory();val s=f.create(c,mapOf("rawSample" to -1));assertEquals(-1,c.sample.sampleRaw());assertEquals(false,s.state()["samplePresent"]);for(v in listOf(0,1,2047,2048,4095,-1)){s.applySettings(mapOf("rawSample" to v));assertEquals(v,c.sample.sampleRaw())};assertTrue(s.interruptRoutes().isEmpty());assertTrue(s.additionalMmioPorts().isEmpty())}
    @Test fun schemaRejectsOutOfRangeAndPhysicalUnitStringsWithoutCoercion(){val setting=TemperatureSensorFactory().descriptor.settings.single();for(v in listOf(-2,4096,"25 C",200.5))assertFails{setting.validate(v)};assertEquals(4095L,setting.validate(4095))}
    @Test fun closeInvalidatesRetainedSampleEndpoint(){val c=Context();val s=TemperatureSensorFactory().create(c,mapOf("rawSample" to 200));assertEquals(200,c.sample.sampleRaw());s.close();s.close();assertFails{c.sample.sampleRaw()}}
}
