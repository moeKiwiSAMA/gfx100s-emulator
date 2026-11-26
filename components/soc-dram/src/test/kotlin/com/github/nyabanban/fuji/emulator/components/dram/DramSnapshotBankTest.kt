package com.github.nyabanban.fuji.emulator.components.dram

import org.junit.jupiter.api.Test
import kotlin.test.*

class DramSnapshotBankTest {
 private fun configured(bank:Int=1,delay:Long=80,pending:Long=0,reply:Long?=1)=DramSnapshotBank(bank,delay,pending,reply).also{b->DramSnapshotBank.configuration().forEach{(o,v)->b.write(o,4,v,0)}}
 @Test fun actualMatchingReadAndExternalAckAreDistinctFromTimedResponseDelivery() {
  for(index in 0..3){val b=configured(index);b.write(0x7080,4,0x100,20);assertEquals(100L,b.nextDeadline());assertEquals(0L,b.read(0x7090,4,99));b.advance(100);assertEquals(1L,b.state()["responseDeliveryCount"]);assertEquals(false,b.state()["guestMatchObserved"]);assertFails{b.acknowledge(0,4,1,100)};assertEquals(1L,b.read(0x7090,4,100));b.acknowledge(0,4,1,101);assertEquals(1L,b.state()["externalAcknowledgementCount"]);assertEquals(0xfff49c00L+index*16,DramSnapshotBank.acknowledgementAddress(index));assertNull(b.nextDeadline())}
 }
 @Test fun missingResponseAtExactOrLateDeadlinePreservesEveryCounterWordAndPendingTick() {
  for(tick in listOf(80L,81L,1000L)){val b=configured(reply=null);b.write(0x7080,4,0x100,0);b.advance(79);val before=b.state();assertFails{b.advance(tick)};assertEquals(before,b.state());assertFails{b.read(0x7090,4,tick)};assertEquals(before,b.state());assertEquals(80L,b.nextDeadline())}
 }
 @Test fun explicitNonreadyNeverMatchesAndOnlyMaskedBitControlsTheGuestPredicate() {
  val nonready=configured(reply=0x80000000L);nonready.write(0x7080,4,0x100,0);for(tick in 80L..100L)assertEquals(0x80000000L,nonready.read(0x7090,4,tick));assertEquals(false,nonready.state()["guestMatchObserved"]);assertEquals(1L,nonready.state()["responseDeliveryCount"]);assertFails{nonready.acknowledge(0,4,1,100)}
  val ready=configured(reply=0x80000001L);ready.write(0x7080,4,0x100,0);assertEquals(0x80000001L,ready.read(0x7090,4,80));assertEquals(true,ready.state()["guestMatchObserved"])
 }
 @Test fun wrongOrderValuesWidthOffsetsAndPrematureReadOrAckAreAtomic() {
  val b=DramSnapshotBank(1,80,0,1);val initial=b.state();for((offset,size,value)in listOf(Triple(0x7114,2,0L),Triple(0x7114,4,1L),Triple(0x7c40,4,0x80000000L),Triple(0x7080,4,0x100L),Triple(0x7115,4,0L),Triple(0x7114,4,0x100000000L))){assertFails{b.write(offset,size,value,0)};assertEquals(initial,b.state())};assertFails{b.read(0x7090,4,0)};assertFails{b.acknowledge(0,4,1,0)};assertEquals(initial,b.state())
  DramSnapshotBank.configuration().forEach{(o,v)->b.write(o,4,v,0)};val configured=b.state();for(value in listOf(0L,1L,0x101L)){assertFails{b.write(0x7080,4,value,0)};assertEquals(configured,b.state())}
 }
 @Test fun overflowBackwardsClockAndInvalidStatusReadDoNotPartiallyAdvance() {
  val b=configured(delay=Long.MAX_VALUE);val before=b.state();assertFails{b.write(0x7080,4,0x100,1)};assertEquals(before,b.state());b.advance(10);val later=b.state();assertFails{b.advance(9)};assertFails{b.write(0x7080,4,0x100,9)};assertEquals(later,b.state())
  val c=configured();c.write(0x7080,4,0x100,0);val pending=c.state();for((o,s)in listOf(0x7090 to 2,0x7080 to 4,0x7c14 to 4,0x7094 to 4)){assertFails{c.read(o,s,80)};assertEquals(pending,c.state())}
 }
 @Test fun lateDeliveryRetainsScheduledDeadlineAndQueriesNeverConsumeIt() {
  val b=configured();b.write(0x7080,4,0x100,20);val before=b.state();repeat(5){assertEquals(100L,b.nextDeadline());assertTrue(b.hasPendingResponse());assertTrue(b.isActive());assertTrue(b.claims(0x7c14))};assertEquals(before,b.state());b.advance(120);assertEquals(100L,b.state()["responseDeliveredAt"]);assertEquals(1L,b.state()["responseDeliveryCount"]);b.advance(121);assertEquals(1L,b.state()["responseDeliveryCount"])
 }
 @Test fun finiteExhaustionAndWrongExternalAckCannotReplayOrResetTheSequence() {
  val b=configured();b.write(0x7080,4,0x100,0);assertEquals(1L,b.read(0x7090,4,80));val matched=b.state();for((o,s,v)in listOf(Triple(4L,4,1L),Triple(0L,2,1L),Triple(0L,4,0L))){assertFails{b.acknowledge(o,s,v,80)};assertEquals(matched,b.state())};b.acknowledge(0,4,1,80);val done=b.state();assertFails{b.acknowledge(0,4,1,80)};assertFails{b.read(0x7090,4,80)};assertFails{b.write(0x7114,4,0,80)};assertEquals(done,b.state())
 }
 @Test fun constructorAndClosedHandlesRefuseUnqualifiedInputs() {
  for(bank in listOf(-1,4)){assertFails{DramSnapshotBank(bank,80,0,1)}};assertFails{DramSnapshotBank(1,0,0,1)};assertFails{DramSnapshotBank(1,80,1,1)};assertFails{DramSnapshotBank(1,80,0,0x100000000L)};assertFails{DramSnapshotBank(1,80,-1,1)}
  val b=configured();b.close();assertFails{b.advance(0)};assertFails{b.read(0x7090,4,0)};assertFails{b.write(0x7080,4,0x100,0)};assertFails{b.acknowledge(0,4,1,0)};assertFails{b.nextDeadline()};assertFails{b.isActive()}
 }
}
