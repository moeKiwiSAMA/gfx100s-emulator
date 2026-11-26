package com.github.nyabanban.fuji.emulator.components.rtc

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class RtcFactoryTest {
    private class Context(var data: Map<String,Any?>): ComponentContext {
        var tick=0L;var calls=0;val entries=hashMapOf<String,Any>()
        override val directory=Path.of("unused-rtc-test")
        override val clock=EmulationClock{tick}
        override val events=object:EventChannel{override fun publish(event:ComponentEvent){error("unexpected event")};override fun subscribe(listener:(ComponentEvent)->Unit)=AutoCloseable{}}
        override val ports=object:Ports{
            override fun<T:Any> register(owner:String,name:String,type:Class<T>,port:T){check(entries.putIfAbsent(name,port)==null)}
            override fun<T:Any> find(name:String,type:Class<T>):T?=entries[name]?.let{type.cast(it)}
            override fun removeOwner(owner:String){error("not needed")}
        }
        override fun dataPath(relative:String)=directory.resolve(relative)
        override fun readData(relative:String):Map<String,Any?>{assertEquals("input.json",relative);calls++;return data}
    }
    private fun input()=mutableMapOf<String,Any?>("schema" to 1,"profile" to "gfx100s-rtc-functional-experimental-v1","ticksPerSecond" to 10L,"initialSeconds" to 0x12345678L,"initialControl" to 0,"counterRunning" to true,"loadDelayTicks" to 5L)
    private fun create(c:Context,enabled:Boolean=true):HardwareComponent {val f=RtcFactory();return f.create(c,f.descriptor.settings.associate{it.key to it.default}+mapOf("profile" to if(enabled)"gfx100s-rtc-functional-experimental-v1" else "disabled","inputFile" to if(enabled)"input.json" else ""))}
    @Test fun disabledJarHasNoEndpointReadsEventsOrIrq(){val c=Context(input());val component=create(c,false);assertTrue(c.entries.isEmpty());assertEquals(0,c.calls);assertEquals(false,component.state()["enabled"]);assertTrue(component.interruptRoutes().isEmpty());assertTrue(component.additionalMmioPorts().isEmpty())}
    @Test fun explicitInputIsFrozenAndUsesFunctionalOwnerClock(){val data=input();val c=Context(data);val component=create(c);data["initialSeconds"]=0;data["ticksPerSecond"]=1;c.tick=10;assertEquals(0x12345679L,(component.state()["registers"]as Map<*,*>)["seconds"]);assertEquals(0x12345678L,(component.state()["inputs"]as Map<*,*>)["initialSeconds"]);assertNotNull(c.ports.find("rtc.registers",PowerRegisterPort::class.java));assertEquals(1,c.calls)}
    @Test fun everyRequiredInputMissingAndEveryExtraFieldRefuseBeforePortPublication(){for(key in input().keys){val c=Context(input()-key);assertFails{create(c)};assertTrue(c.entries.isEmpty())};val c=Context(input()+mapOf("ready" to true));assertFails{create(c)};assertTrue(c.entries.isEmpty())}
    @Test fun lossyNumbersStringsAndWrongBooleansAreNeverCoerced(){for((key,bad)in listOf("schema" to 1.0,"ticksPerSecond" to 10.0,"initialSeconds" to "0","initialControl" to 0.0,"counterRunning" to 1,"loadDelayTicks" to 5.0)){val data=input();data[key]=bad;val c=Context(data);assertFails{create(c)};assertTrue(c.entries.isEmpty())}}
    @Test fun factoryLifecycleClosesThePublishedEndpointAndOldLease(){val ctx=Context(input());val component=create(ctx);val port=ctx.ports.find("rtc.registers",PowerRegisterPort::class.java)!!;val connection=port.connect("test");component.close();component.close();assertFails{connection.read(39,0)};assertFails{port.connect("new")};connection.close();assertEquals(true,(component.state()["registers"]as Map<*,*>)["closed"])}
    @Test fun profileAndFileMustBothBeExplicit(){val c=Context(input());val f=RtcFactory();val defaults=f.descriptor.settings.associate{it.key to it.default};assertFails{f.create(c,defaults+mapOf("inputFile" to "input.json"))};assertFails{f.create(c,defaults+mapOf("profile" to "gfx100s-rtc-functional-experimental-v1"))};assertTrue(c.entries.isEmpty());assertEquals(0,c.calls)}
    @Test fun calibrated54MhzAndHostWallClockModesOverrideTimebaseAndKeepLegacyDefault(){
        val c54=Context(input())
        val f54=RtcFactory()
        val defaults=f54.descriptor.settings.associate{it.key to it.default}
        val comp54=f54.create(c54,defaults+mapOf("profile" to "gfx100s-rtc-functional-experimental-v1","inputFile" to "input.json","clockMode" to RtcFactory.CLOCK_MODE_CALIBRATED_54MHZ))
        c54.tick=54_000_000L
        assertEquals(54_000_000L,(comp54.state()["registers"]as Map<*,*>)["ticksPerSecond"])
        assertEquals(0x12345679L,(comp54.state()["registers"]as Map<*,*>)["seconds"])
        comp54.close()

        val fixedUnix=RtcFactory.GFX_RTC_EPOCH_UNIX_SECONDS + 700_000_000L
        val cHost=Context(input())
        val fHost=RtcFactory { fixedUnix }
        val compHost=fHost.create(cHost,defaults+mapOf("profile" to "gfx100s-rtc-functional-experimental-v1","inputFile" to "input.json","clockMode" to RtcFactory.CLOCK_MODE_HOST_WALL_CLOCK))
        cHost.tick=108_000_000L
        assertEquals(700_000_002L,(compHost.state()["registers"]as Map<*,*>)["seconds"])
        compHost.close()
    }

    @Test fun relaxedStateFilePersistsRtcAcrossRestartsAndAdvancesByWallDeltaWhileStrictIgnoresIt(){
        val tempDir = java.nio.file.Files.createTempDirectory("rtc-persist-test-")
        try {
            class PersistContext(val dir: Path, override val relaxedMode: Boolean, val data: Map<String, Any?>) : ComponentContext {
                var tick = 0L
                val entries = hashMapOf<String, Any>()
                override val directory = dir
                override val clock = EmulationClock { tick }
                override val events = object : EventChannel {
                    override fun publish(event: ComponentEvent) {}
                    override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
                }
                override val ports = object : Ports {
                    override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                        check(entries.putIfAbsent(name, port) == null)
                    }
                    override fun <T : Any> find(name: String, type: Class<T>): T? = entries[name]?.let { type.cast(it) }
                    override fun removeOwner(owner: String) {}
                }
                override fun dataPath(relative: String) = dir.resolve(relative)
                override fun readData(relative: String): Map<String, Any?> = data
            }

            val stateFile = tempDir.resolve("rtc-state.json")
            var wallUnix = RtcFactory.GFX_RTC_EPOCH_UNIX_SECONDS + 800_000_000L
            val f = RtcFactory { wallUnix }
            val baseCfg = f.descriptor.settings.associate { it.key to it.default } + mapOf(
                "profile" to "gfx100s-rtc-functional-experimental-v1",
                "inputFile" to "input.json",
                "clockMode" to RtcFactory.CLOCK_MODE_HOST_WALL_CLOCK,
                "stateFile" to "rtc-state.json",
            )

            // 1. Strict/discovery mode (relaxedMode=false) must NEVER create or write rtc-state.json
            val strictCtx = PersistContext(tempDir, relaxedMode = false, data = input())
            val strictComp = f.create(strictCtx, baseCfg)
            strictCtx.tick = 54_000_000L
            strictComp.close()
            assertFalse(java.nio.file.Files.exists(stateFile), "Strict mode must not persist rtc-state.json")

            // 2. Relaxed mode Boot 1: starts at host wall clock, guest sets custom user clock, persists on load & close
            val boot1Ctx = PersistContext(tempDir, relaxedMode = true, data = input())
            val boot1 = f.create(boot1Ctx, baseCfg)
            assertEquals(800_000_000L, (boot1.state()["registers"] as Map<*, *>)["seconds"])
            val port1 = boot1Ctx.ports.find("rtc.registers", PowerRegisterPort::class.java)!!
            val conn1 = port1.connect("test")
            val customSeconds = 750_000_123L
            boot1Ctx.tick = 100L
            conn1.write(0x1d, (customSeconds and 0xffffL).toInt(), 100L)
            conn1.write(0x1e, (customSeconds ushr 16).toInt(), 100L)
            conn1.write(0x27, 0x0202, 100L)
            assertTrue(java.nio.file.Files.isRegularFile(stateFile), "Relaxed mode must persist rtc-state.json on RTC load")
            boot1Ctx.tick = 105L + 54_000_000L * 3L // +3s virtual runtime after delayed load
            conn1.close()
            boot1.close()

            // 3. Relaxed mode Boot 2 after 45s host wall-clock powered-off interval: restores custom clock + 45s!
            wallUnix += 45L
            val boot2Ctx = PersistContext(tempDir, relaxedMode = true, data = input())
            val boot2 = f.create(boot2Ctx, baseCfg)
            assertEquals(customSeconds + 3L + 45L, (boot2.state()["registers"] as Map<*, *>)["seconds"])
            boot2.close()

            // 4. Strict mode still ignores existing rtc-state.json completely
            val strict2Ctx = PersistContext(tempDir, relaxedMode = false, data = input())
            val strict2 = f.create(strict2Ctx, baseCfg)
            assertEquals(800_000_045L, (strict2.state()["registers"] as Map<*, *>)["seconds"])
            strict2.close()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
