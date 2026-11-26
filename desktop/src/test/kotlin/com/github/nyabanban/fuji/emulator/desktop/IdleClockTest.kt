package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.runtime.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class IdleClockTest {
    private fun selected(advances:Long=16,ticks:Long=1000)=IdleClock.parse(mapOf("profile" to IdleClock.PROFILE,"maxAdvances" to advances,"maxIdleTicks" to ticks))!!
    private class Device(override val clockOrder:Int,var due:Long?,val log:MutableList<Int>,val refusal:Boolean=false):ScheduledMmioDevice {
        var queries=0
        override fun nextDeadline():Long? {queries++;return due}
        override fun read(offset:Long,size:Int)=0L
        override fun write(offset:Long,size:Int,value:Long)=Unit
        override fun beforeInstruction(tick:Long) {log.add(clockOrder);if(refusal)error("missing selected input");if(due==tick)due=null}
    }
    @Test fun queriesDistinctIdentitiesAndChoosesEarliestThenPreservesClockOrder() {
        val log=ArrayList<Int>();val timer=Device(0,8,log);val dma=Device(10,5,log)
        val router=MmioRouter(listOf(MmioWindow("dma",0,4,dma),MmioWindow("timer",4096,4,timer),MmioWindow("alias",8192,4,dma)))
        var now=0L;val idle=selected()
        assertTrue(idle.advance(now,123,router){now=it});assertEquals(5L,now)
        assertEquals(1,timer.queries);assertEquals(1,dma.queries);assertEquals(listOf(0,10),log)
        assertEquals(8L,timer.due);assertNull(dma.due);assertEquals(123L,idle.state(now,123)["instructionAttempts"])
        assertTrue(idle.advance(now,123,router){now=it});assertEquals(8L,now);assertEquals(2L,idle.state(now,123)["advanceCount"])
        assertFalse(idle.advance(now,123,router){error("unknown time cannot advance")})
    }
    @Test fun sameTickEarlierDeviceRefusalDoesNotCommitLaterDma() {
        val log=ArrayList<Int>();val timer=Device(0,10,log,true);val dma=Device(10,10,log)
        val router=MmioRouter(listOf(MmioWindow("dma",0,4,dma),MmioWindow("timer",4096,4,timer)))
        var now=0L;val idle=selected()
        assertFails {idle.advance(now,0,router){now=it}}
        assertEquals(10L,now);assertEquals(listOf(0),log);assertEquals(10L,dma.due)
        val event=(idle.state(now,0)["events"] as List<*>).single() as Map<*,*>
        assertEquals(false,event["devicesAdvanced"]);assertEquals("missing selected input",event["failure"])
    }
    @Test fun noKnownDeadlineNeverInventsProgress() {
        val legacy=object:MmioDevice {override fun read(offset:Long,size:Int)=0L;override fun write(offset:Long,size:Int,value:Long)=Unit}
        val router=MmioRouter(listOf(MmioWindow("unknown",0,4,legacy)))
        assertFalse(selected().advance(0,0,router){error("must stay stopped")})
    }
    @Test fun staleDeadlinesAndFiniteBoundsRefuseBeforeClockOrDeviceMutation() {
        for(due in listOf(-1L,0L,5L,Long.MAX_VALUE)) {
            val log=ArrayList<Int>();val device=Device(0,due,log)
            val router=MmioRouter(listOf(MmioWindow("test",0,4,device)))
            assertFails {selected().advance(5,0,router){error("must refuse before mutation")}}
            assertTrue(log.isEmpty());assertEquals(due,device.due)
        }
        val log=ArrayList<Int>();val device=Device(0,3,log);val router=MmioRouter(listOf(MmioWindow("test",0,4,device)))
        var now=0L;val one=selected(1,5);assertTrue(one.advance(now,0,router){now=it});device.due=4
        assertFails {one.advance(now,0,router){now=it}};assertEquals(3L,now);assertEquals(listOf(0),log)
        val limited=selected(10,2);assertFails {limited.advance(0,0,router){error("tick limit preflight")}}
        assertEquals(0L,limited.state(0,0)["advanceCount"])
    }
    @Test fun logRetentionIsBoundedWithoutLosingTotals() {
        val device=Device(0,1,ArrayList());val router=MmioRouter(listOf(MmioWindow("test",0,4,device)));val idle=selected(300,300)
        var now=0L
        repeat(270){device.due=now+1;assertTrue(idle.advance(now,9,router){now=it})}
        val state=idle.state(now,9);assertEquals(270L,state["advanceCount"]);assertEquals(270L,state["idleTicks"])
        assertEquals(256,(state["events"] as List<*>).size);assertEquals(true,state["eventLogTruncated"])
    }
    @Test fun explicitSchemaLimitsAndClockProfileAreStrict() {
        assertNull(IdleClock.parse(null))
        val valid=mapOf<String,Any>("profile" to IdleClock.PROFILE,"maxAdvances" to 16,"maxIdleTicks" to 1000)
        for(bad in listOf<Any>("bad",valid-"profile",valid+mapOf("extra" to 0),valid+mapOf("profile" to "bad"),valid+mapOf("maxAdvances" to 0),valid+mapOf("maxAdvances" to 65537),valid+mapOf("maxAdvances" to 1.0),valid+mapOf("maxIdleTicks" to true),valid+mapOf("maxIdleTicks" to Long.MAX_VALUE)))assertFails{IdleClock.parse(bad)}
    }
}
