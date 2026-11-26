package com.github.nyabanban.fuji.emulator.components.rtc

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class RtcBankTest {
    private fun bank(seconds: Long=100,control: Int=0,running: Boolean=false,frequency: Long=10,delay: Long=10)=RtcBank(frequency,seconds,control,running,delay)
    private fun value(c: PowerRegisterConnection,tick: Long)=(c.read(0x24,tick).toLong() shl 16) or c.read(0x23,tick).toLong()
    private fun stage(c: PowerRegisterConnection,seconds: Long,tick: Long){c.write(0x1d,(seconds and 65535).toInt(),tick);c.write(0x1e,(seconds ushr 16).toInt(),tick)}
    @Test fun independentHalvesFollowDeclaredTickFrequencyAndWrapWithoutLongOverflow(){
        val b=bank(0xffff,running=true,frequency=1);b.connect("test").use{c->
            assertEquals(0xffff,c.read(0x23,0));assertEquals(1,c.read(0x24,1));assertEquals(0x10000L,value(c,1))
            // Separate requests deliberately have no invented cross-request latch.
            assertEquals(0x1ffffL,(c.read(0x24,1).toLong() shl 16) or c.read(0x23,0).toLong())
        }
        bank(0xffffffffL,running=true,frequency=1).connect("overflow").use{c->assertEquals(0L,value(c,1));assertEquals(0xfffffffeL,value(c,Long.MAX_VALUE))}
    }
    @Test fun stagedPairDoesNotChangeCounterUntilExactExplicitDeadline(){
        val b=bank();b.connect("test").use{c->stage(c,0x1234ffff,1);assertEquals(100L,value(c,2));c.write(0x27,0xa203,3)
            assertEquals(100L,value(c,12));assertEquals(0xa203,c.read(0x27,12));assertEquals(0x1234ffffL,value(c,13));assertEquals(0xa003,c.read(0x27,13));assertEquals(0xa003,c.read(0x27,100));assertEquals(1L,b.state(100)["loadCount"])
        }
    }
    @Test fun bothFreshHalvesAreRequiredAndConsumedOnce(){
        val b=bank();b.connect("test").use{c->val before=b.state(0);assertFails{c.write(0x27,0x202,0)};assertEquals(before,b.state(0))
            c.write(0x1d,7,0);val half=b.state(0);assertFails{c.write(0x27,0x202,0)};assertEquals(half,b.state(0));c.write(0x1e,1,0);c.write(0x27,0x202,0)
            val done=b.state(10);assertFails{c.write(0x27,0x202,10)};assertEquals(done,b.state(10));c.write(0x1e,2,10);assertFails{c.write(0x27,0x202,10)}
        }
    }
    @Test fun busyAndMissingValidBitRefuseAtomically(){
        val b=bank();b.connect("test").use{c->stage(c,400,0);val before=b.state(0);assertFails{c.checkWrite(0x27,0x200,0)};assertEquals(before,b.state(0));c.write(0x27,0x202,0)
            val pending=b.state(9);for(reg in listOf(0x1d,0x1e,0x27)){assertFails{c.checkWrite(reg,0x202,9)};assertFails{c.write(reg,0x202,9)}};assertEquals(pending,b.state(9));c.write(0x1d,33,10)
        }
    }
    @Test fun invalidAddressWidthAndEarlierClockHaveNoEffects(){
        val b=bank();b.connect("test").use{c->c.write(0x1d,1,5);val before=b.state(5)
            for(reg in listOf(-1,0,0x1c,0x1f,0x23,0x24,128))assertFails{c.write(reg,0,5)}
            for(reg in listOf(0x1d,0x1e,0x28,128))assertFails{c.read(reg,5)}
            for(v in listOf(-1,65536))assertFails{c.write(0x1e,v,5)}
            assertFails{c.read(0x27,4)};assertFails{c.write(0x1e,1,4)};assertEquals(before,b.state(5))
        }
    }
    @Test fun endpointClaimAndLeaseRemainExclusiveAndDefensive(){
        val b=bank();assertFails{b.connect(" ")};val c=b.connect("first");val returned=c.registers();assertContentEquals(intArrayOf(29,30,35,36,39),returned);returned[0]=1;assertEquals(29,c.registers()[0]);assertFails{b.connect("second")};c.close();c.close();assertFails{c.read(39,0)};assertFails{c.registers()};b.connect("second").use{assertEquals(0,it.read(39,0))};assertNull(b.state(0)["owner"])
    }
    @Test fun deadlineOverflowPreservesEveryStateField(){
        val b=bank();b.connect("test").use{c->stage(c,123,0);val t=Long.MAX_VALUE-5;val before=b.state(t);assertFails{c.checkWrite(39,0x202,t)};assertFails{c.write(39,0x202,t)};assertEquals(before,b.state(t))}
    }
    @Test fun repeatedDelayedLoadKeepsOldCounterFractionUntilSecondCommit(){
        val b=bank(running=true,frequency=10,delay=5);b.connect("test").use{c->stage(c,1000,1);c.write(39,0x202,2);assertEquals(1000L,value(c,16));stage(c,2000,16);c.write(39,0x202,17);assertEquals(1001L,value(c,21));assertEquals(2000L,value(c,22));assertEquals(2001L,value(c,32));assertEquals(2L,b.state(32)["loadCount"])}
    }
    @Test fun preflightAndQueriesDoNotConsumeOrMutateAStage(){
        val b=bank();b.connect("test").use{c->stage(c,0xabcd,0);val before=b.state(0);repeat(20){c.checkWrite(39,0x202,1);c.read(39,1);value(c,100)};assertEquals(before,b.state(0));c.write(39,0x202,1);assertEquals(0xabcdL,value(c,11))}
    }
    @Test fun declaredStoppedClockStaysFixedAndControlRetainsOtherBits(){
        bank(0xffffffffL,0xf9fd,false).connect("test").use{c->assertEquals(0xffffffffL,value(c,Long.MAX_VALUE));assertEquals(0xf9fd,c.read(39,Long.MAX_VALUE));c.write(39,0x81,0);assertEquals(0x81,c.read(39,0));assertEquals(0xffffffffL,value(c,100))}
    }
    @Test fun closingTheDeviceInvalidatesAllRetainedConnectionsAndAcquisition(){val b=bank();val c=b.connect("owner");c.write(29,1,0);b.close();b.close();assertFails{c.registers()};assertFails{c.read(39,0)};assertFails{c.checkWrite(30,1,0)};assertFails{c.write(30,1,0)};assertFails{b.connect("new")};c.close();assertEquals(true,b.state(0)["closed"]);assertNull(b.state(0)["owner"])}
    @Test fun constructorRejectsMissingTimebaseRangeAndInProgressReset(){
        assertFails{bank(frequency=0)};assertFails{bank(frequency=-1)};assertFails{bank(seconds=-1)};assertFails{bank(seconds=0x100000000)};assertFails{bank(control=65536)};assertFails{bank(control=0x200)};assertFails{bank(delay=0)}
    }
}
