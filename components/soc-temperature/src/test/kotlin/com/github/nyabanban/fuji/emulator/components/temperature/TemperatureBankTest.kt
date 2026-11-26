package com.github.nyabanban.fuji.emulator.components.temperature

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class TemperatureBankTest {
    private fun reset()=LongArray(15).apply{this[7]=15}
    private fun bank(ticks:Long=10,raw:Int=200)=TemperatureBank(ticks,reset()).apply{source=TemperatureSamplePort{raw}}
    private fun start(b:TemperatureBank,tick:Long=0){b.write(2,1,tick);b.write(3,1,tick)}
    @Test fun exactDeadlineAndLateCompletionUseScheduledEdgeAndObservationalState(){for(late in listOf(10L,17L)){val b=bank();start(b);val before=b.state();assertEquals(10L,b.nextDeadline());b.advance(9);assertEquals(before,b.state());assertEquals(before,b.state());b.advance(late);assertNull(b.nextDeadline());assertEquals(1L,b.read(8,late));assertEquals(200L,b.read(9,late));assertEquals(10L,b.state()["completedAt"]);assertEquals(1L,b.state()["conversionCount"]);b.advance(late+10);assertEquals(1L,b.state()["conversionCount"])}}
    @Test fun everyRaw12PatternIsPreservedExactlyWithoutCalibratedTemperatureAssumptions(){val b=bank();for(raw in 0..4095){b.source=TemperatureSamplePort{raw};val at=raw*20L;start(b,at);b.advance(at+10);assertEquals(raw.toLong(),b.read(9,at+10));assertEquals(1L,b.read(8,at+10))};assertEquals(4096L,b.state()["conversionCount"])}
    @Test fun dividerScalesOnlyNewConversionsAndBusyChangeRefusesAtomically(){val b=bank();b.write(4,3,0);start(b);assertEquals(80L,b.nextDeadline());val before=b.state();assertFails{b.write(4,2,1)};assertEquals(before,b.state());b.write(4,3,1);assertEquals(80L,b.nextDeadline());b.advance(80);b.write(4,1,80);b.write(3,1,80);assertEquals(100L,b.nextDeadline())}
    @Test fun absentInvalidAndMissingPortsRetainPendingOldResultAndReadyWithoutCompletion(){for(raw in listOf(-1,4096)){val b=bank(raw=raw);start(b);val before=b.state();assertFails{b.advance(10)};assertEquals(before,b.state());assertEquals(10L,b.nextDeadline());b.source=TemperatureSamplePort{99};b.advance(10);assertEquals(99L,b.read(9,10))};val b=bank();b.source=null;start(b);val before=b.state();assertFails{b.advance(10)};assertEquals(before,b.state())}
    @Test fun sourceIsSampledAtCompletionObservationAndCanChangeLiveBeforeTheEdge(){val b=bank(raw=1);start(b);b.source=TemperatureSamplePort{4095};b.advance(10);assertEquals(4095L,b.read(9,10));assertEquals(1L,b.state()["conversionCount"])}
    @Test fun cancellationDoesNotConsumeSampleAndRestartCreatesNewDeadline(){var calls=0;val b=bank().apply{source=TemperatureSamplePort{calls++;7}};start(b);b.write(3,0,5);assertNull(b.nextDeadline());b.advance(10);assertEquals(0,calls);b.write(3,1,20);assertEquals(30L,b.nextDeadline());b.advance(30);assertEquals(1,calls)}
    @Test fun repeatedStartClearsReadyAndRetainsLastResultUntilNextActualSample(){val b=bank(raw=9);start(b);b.advance(10);assertEquals(9L,b.read(9,10));b.source=TemperatureSamplePort{11};b.write(3,1,20);assertEquals(0L,b.read(8,20));assertEquals(9L,b.read(9,20));b.advance(30);assertEquals(11L,b.read(9,30));assertEquals(2L,b.state()["conversionCount"])}
    @Test fun overflowIsPreflightedBeforeEnableStartReadyOrSampleMutation(){val b=bank();b.write(2,1,0);val before=b.state();assertFails{b.write(3,1,Long.MAX_VALUE-1)};assertEquals(before,b.state());assertNull(b.nextDeadline());assertFails{TemperatureBank(Long.MAX_VALUE,reset())}}
    @Test fun signedThresholdProducerIsAcceptedWithoutBlanketIgnoringUpperWordBits(){val b=bank();for(raw in -2048..2047){val word=raw.toLong()and 0xffffffffL;b.write(10,word,0);assertEquals((raw and 4095).toLong(),b.read(10,0))};val before=b.state();for(w in listOf(0x1000L,0x10000L,0xfffff7ffL))assertFails{b.write(10,w,0)};assertEquals(before,b.state())}
    @Test fun comparisonInterruptUnmaskAndReservedDataWritesRemainStrict(){val b=bank();val before=b.state();for(i in listOf(5,8,9)){assertFails{b.write(i,0,0)};assertEquals(before,b.state())};for(mask in 0..14){assertFails{b.write(7,mask.toLong(),0)};assertEquals(before,b.state())};b.write(7,15,0);assertEquals(15L,b.read(7,0))}
    @Test fun explicitStatusW1cAndCommonWordRetentionHaveNoReadySideEffects(){val initial=reset().apply{this[1]=0x80000000L;this[5]=15;this[9]=123};val b=TemperatureBank(10,initial);b.write(6,5,0);assertEquals(10L,b.read(5,0));b.write(1,0x80000001L,0);assertEquals(0x80000001L,b.read(1,0));assertNull(b.nextDeadline());assertEquals(0L,b.read(8,0));assertEquals(123L,b.read(9,0))}
    @Test fun clockRollbackRefusesAndLifecycleInvalidatesOldControllerHandles(){val b=bank();b.write(2,1,100);val before=b.state();assertFails{b.write(3,1,99)};assertEquals(before,b.state());b.close();b.close();assertFails{b.read(0,100)};assertFails{b.write(0,0,100)};assertFails{b.advance(100)};assertFails{b.nextDeadline()};assertEquals(true,b.state()["closed"])}
    @Test fun exactInitialShapeAndIdleResetAreExplicitRatherThanImpliedDefaults(){assertFails{TemperatureBank(10,LongArray(14))};assertFails{TemperatureBank(0,reset())};for(i in listOf(2,3)){val r=reset();r[i]=1;assertFails{TemperatureBank(10,r)}};val r=reset();r[7]=0;assertFails{TemperatureBank(10,r)}}
    @Test fun continuousModeRefreshesResultRegisterOnLaterReadsWhenEnabledAndStarted() {
        var liveRaw = 200
        var calls = 0
        val b = TemperatureBank(10, reset()).apply {
            source = TemperatureSamplePort { calls++; liveRaw }
        }
        start(b, 0L)
        b.advance(10L)
        assertEquals(1, calls)
        assertEquals(200L, b.read(9, 10L)) // same tick as completion: no extra sample
        assertEquals(1, calls)

        // Runtime thermal rise to 95.0 C (raw=760) sampled on 15s OS poll (tick > lastTick)
        liveRaw = 760
        assertEquals(760L, b.read(9, 810_000_010L))
        assertEquals(2, calls)
        assertEquals(1L, b.read(8, 810_000_010L)) // ready remains 1

        // Runtime thermal rise to 100.0 C (raw=800) sampled on next poll
        liveRaw = 800
        assertEquals(800L, b.read(9, 1_620_000_010L))
        assertEquals(3, calls)

        // Snapshot round-trip after continuous refresh preserves exact register & tick invariants
        val snap = b.saveSnapshot()
        val restored = TemperatureBank(10, reset()).apply { source = TemperatureSamplePort { liveRaw } }
        restored.applySnapshot(restored.validateSnapshot(snap))
        assertEquals(b.state(), restored.state())

        // Stopping continuous conversion stops live refresh and retains last sampled value
        b.write(3, 0L, 1_620_000_020L)
        liveRaw = 900
        assertEquals(800L, b.read(9, 2_000_000_000L))
        assertEquals(3, calls)
    }
}
