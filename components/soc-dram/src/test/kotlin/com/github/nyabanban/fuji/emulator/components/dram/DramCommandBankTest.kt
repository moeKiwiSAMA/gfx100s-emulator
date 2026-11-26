package com.github.nyabanban.fuji.emulator.components.dram

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

internal class CommandFixture {
 val app=Files.readAllBytes(Path.of(DramTestPaths.value("fujiRepo"),"firmware-data/gfx100s/2.13/decoded/section4.bin"))
 init {require(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(app))=="eb5372dfda41009b969ccf148f031b3976230a6a9d249d0ea3d0588e6feab157")}
 fun word(address:Long)=ByteBuffer.wrap(app,Math.toIntExact(address-0x1021000L),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()and 0xffffffffL
 fun tables(bank:Int):List<List<Pair<Int,Long>>> = listOf(0x15b0cbcL,0x15afabcL,0x15ae8bcL,0x15af1bcL,0x15b03bcL).map{base->val at=base+bank*8;require(word(at)==71L);val pointer=word(at+4);(0 until 71).map{index->val address=word(pointer+index*8);require(address in (0xff500000L+bank*0x10000)..(0xff50ffffL+bank*0x10000));(address-(0xff500000L+bank*0x10000)).toInt() to word(pointer+index*8+4)}}
}
class DramCommandBankTest {
 private val f=CommandFixture()
 private fun model(bank:Int=0,mode:Int=4,response:Long?=0,delay:Long=80,counter:Long?=null)=DramCommandBank(f.tables(bank),listOf(DramCommandBank.Request(mode,delay,response,counter)),bank)
 private fun program(m:DramCommandBank,bank:Int=0,mode:Int=4,tick:Long=0){for((o,v)in f.tables(bank)[mode])m.write(o,4,v,tick)}
 @Test fun allModesBanksHaveDelayedExplicitResponsesAndGuestZeroObservationSeparately() {
  for(bank in 0..3)for(mode in 0..4){val m=model(bank,mode);assertNull(m.nextDeadline());assertFails{m.read(0x5384,4,0)};program(m,bank,mode);assertEquals(80L,m.nextDeadline());assertEquals(1L,m.read(0x5384,4,79));m.advance(80);assertEquals(1L,m.state()["responseDeliveryCount"]);assertEquals(0L,m.state()["guestZeroObservationCount"]);assertEquals(0L,m.read(0x5384,4,80));assertEquals(1L,m.state()["guestZeroObservationCount"]);assertFails{m.read(0x5384,4,80)};assertFails{m.write(0x5000,4,f.tables(bank)[mode][0].second,80)}}
 }
 @Test fun missingInputRetainsEntirePendingStateAtAndAfterDeadline() {
  val m=model(response=null);program(m);m.advance(79);val before=m.state();repeat(2){assertFailsWith<IllegalStateException>{m.advance(80+it.toLong())};assertEquals(before,m.state());assertEquals(80L,m.nextDeadline())};assertFails{m.read(0x5384,4,80)};assertEquals(before,m.state())
 }
 @Test fun explicitHighBitNonzeroIsDeliveredOnceAndNeverBecomesReady() {
  val m=model(response=0x80000000);program(m);m.advance(800);repeat(10){assertEquals(0x80000000L,m.read(0x5384,4,800+it.toLong()))};assertNull(m.nextDeadline());assertEquals(1L,m.state()["responseDeliveryCount"]);assertEquals(0L,m.state()["guestZeroObservationCount"]);val before=m.state();assertFails{program(m,tick=900)};assertEquals(before,m.state())
 }
 @Test fun wrongWidthAddressSequenceAndOverflowDoNotCommitFailedWrites() {
  val m=model();val before=m.state();for((offset,size,value)in listOf(Triple(0x5000,1,0x01004100L),Triple(0x5002,4,0x01004100L),Triple(0,4,0L),Triple(0x5004,4,0x01004100L),Triple(0x5000,4,0L),Triple(0x5000,4,0x100000000L))){assertFails{m.write(offset,size,value,0)};assertEquals(before,m.state())}
  val overflow=model(delay=2);for((o,v)in f.tables(0)[4].dropLast(1))overflow.write(o,4,v,Long.MAX_VALUE-1);val pending=overflow.state();assertFailsWith<ArithmeticException>{overflow.write(0x5384,4,1,Long.MAX_VALUE-1)};assertEquals(pending,overflow.state())
 }
 @Test fun finitePlansSwitchOnlyAfterActualZeroReadAndRemainFrozen() {
  val row=f.tables(0).map{it.toMutableList()}.toMutableList();val requests=mutableListOf(DramCommandBank.Request(4,20,0),DramCommandBank.Request(0,30,0x40));val m=DramCommandBank(row,requests,0);requests.clear();row[4][0]=0 to 0;program(m);m.advance(20);assertFails{program(m,tick=20)};assertEquals(0L,m.read(0x5384,4,20));program(m,mode=0,tick=21);assertEquals(51L,m.nextDeadline());assertEquals(0x40L,m.read(0x5384,4,51));assertEquals(2L,m.state()["responseDeliveryCount"]);assertEquals(1L,m.state()["guestZeroObservationCount"])
 }
 @Test fun restoredPlanCanAppendRequestsWithoutChangingTheSavedPrefix() {
  val first = DramCommandBank.Request(4,80,0)
  val second = DramCommandBank.Request(0,30,0)
  val original = model()
  program(original)
  assertEquals(0L,original.read(0x5384,4,80))
  val saved = original.saveSnapshot()
  val extended = DramCommandBank(f.tables(0),listOf(first,second),0)
  assertFails { extended.validateSnapshot(saved + ("planIndex" to 2)) }
  assertFails { extended.validateSnapshot(saved + ("acceptedWordCount" to 1)) }
  assertFails { model(delay=81).validateSnapshot(saved) }
  extended.applySnapshot(extended.validateSnapshot(saved))
  program(extended,mode=0,tick=81)
  assertEquals(111L,extended.nextDeadline())
  assertFails { original.validateSnapshot(extended.saveSnapshot()) }
  assertEquals(0L,extended.read(0x5384,4,111))
  assertEquals(2L,extended.state()["guestZeroObservationCount"])
 }
 @Test fun specialCounterIsExplicitReadOnceAndRequiresBothSaturatingWrites() {
  for(value in listOf(0L,1L,2L,0xffffffffL)){val m=model(counter=value);val table=f.tables(0)[4];table.dropLast(1).forEach{(o,v)->m.write(o,4,v,0)};assertEquals(value,m.read(0x5200,4,0));val before=m.state();assertFails{m.read(0x5200,4,0)};assertFails{m.write(0x5384,4,1,0)};assertEquals(before,m.state());val dec=if(value==0L)0L else value-1;m.write(0x5204,4,dec,0);m.write(0x5208,4,dec,0);m.write(0x5384,4,1,0);assertEquals(73L,m.state()["completedWriteCount"]);assertEquals(0L,m.read(0x5384,4,80))}
 }
 @Test fun absentCounterNoPlanInvalidRequestsAndClosedBankRefuse() {
  val m=model();f.tables(0)[4].dropLast(1).forEach{(o,v)->m.write(o,4,v,0)};val before=m.state();assertFails{m.read(0x5200,4,0)};assertEquals(before,m.state());assertFails{DramCommandBank(f.tables(1),listOf(DramCommandBank.Request(4,1,0,0)),1)};assertFails{model(delay=0)};assertFails{model(response=-1)};val none=DramCommandBank(f.tables(0),emptyList(),0);assertFails{program(none)};none.close();assertFails{none.nextDeadline()};assertFails{none.advance(0)};assertFails{none.write(0x5000,4,0x01004100,0)}
 }
 @Test fun deadlineQueriesDoNotMutateAndLateDeliveryKeepsActualDeadline() {
  val m=model();program(m,tick=100);val before=m.state();repeat(5){assertEquals(180L,m.nextDeadline())};assertEquals(before,m.state());m.advance(500);assertEquals(180L,m.state()["responseDeliveredAt"]);assertEquals(500L,m.state()["clock"]);val late=m.state();assertFails{m.advance(499)};assertEquals(late,m.state())
 }
 @Test fun repeatableAnyModeAcceptsAnyQualifiedTableRefusesUnqualifiedWordsAndPromotesMonotonically() {
  assertFails{DramCommandBank(f.tables(0),emptyList(),0,repeatableAnyMode=true)}
  val fixed=model(bank=0,mode=4,delay=20,counter=5L)
  val firstTable=f.tables(0)[4]
  firstTable.dropLast(1).forEach{(o,v)->fixed.write(o,4,v,0)}
  assertEquals(5L,fixed.read(0x5200,4,0))
  fixed.write(0x5204,4,4L,0)
  fixed.write(0x5208,4,4L,0)
  fixed.write(firstTable.last().first,4,firstTable.last().second,0)
  assertEquals(0L,fixed.read(0x5384,4,20))
  val fixedSnap=fixed.saveSnapshot()

  val repeatable=DramCommandBank(f.tables(0),listOf(DramCommandBank.Request(4,20,0,5L)),0,repeatableAnyMode=true)
  repeatable.applySnapshot(repeatable.validateSnapshot(fixedSnap))
  assertEquals(DramCommandBank.REPEATABLE_ANY_MODE,repeatable.state()["commandPlanMode"])

  // Switch to mode 2 (different from template mode 4) and checkpoint mid-command
  val mode2=f.tables(0)[2]
  mode2.take(15).forEach{(o,v)->repeatable.write(o,4,v,21)}
  val midSnap=repeatable.saveSnapshot()
  // Non-repeatable bank strictly refuses snapshot from repeatable bank
  assertFails{fixed.validateSnapshot(midSnap)}
  // Restore mid-command into fresh repeatable bank and confirm candidate mask & bad word rejection
  val resumed=DramCommandBank(f.tables(0),listOf(DramCommandBank.Request(4,20,0,5L)),0,repeatableAnyMode=true)
  resumed.applySnapshot(resumed.validateSnapshot(midSnap))
  val beforeBad=resumed.state()
  assertFails{resumed.write(mode2[15].first,4,mode2[15].second xor 1L,21)}
  assertEquals(beforeBad,resumed.state())
  mode2.drop(15).dropLast(1).forEach{(o,v)->resumed.write(o,4,v,21)}
  assertEquals(5L,resumed.read(0x5200,4,21))
  resumed.write(0x5204,4,4L,21)
  resumed.write(0x5208,4,4L,21)
  resumed.write(mode2.last().first,4,mode2.last().second,21)
  assertEquals(41L,resumed.nextDeadline())
  assertEquals(0L,resumed.read(0x5384,4,41))
  assertEquals(2L,resumed.state()["guestZeroObservationCount"])

  // Switch again to mode 0 on the next cycle
  val mode0=f.tables(0)[0]
  mode0.dropLast(1).forEach{(o,v)->resumed.write(o,4,v,42)}
  assertEquals(5L,resumed.read(0x5200,4,42))
  resumed.write(0x5204,4,4L,42)
  resumed.write(0x5208,4,4L,42)
  resumed.write(mode0.last().first,4,mode0.last().second,42)
  assertEquals(0L,resumed.read(0x5384,4,62))
  assertEquals(3L,resumed.state()["guestZeroObservationCount"])
 }

 @Test fun repeatableAnyModeAcceptsDatTargetedThreeWordMovieAndNormalCommandTablesWithSnapshotRoundTrip() {
  // Verify exact DAT 0x015b15bc (normal), 0x015b15e0 (movie/high-bw), and 0x015b15cc (trigger/poll) words in section4.bin
  assertEquals(listOf(0x5038L,0x01004100L,0x5438L,0x81L),(0..3).map{f.word(0x15b15bcL+it*4)})
  assertEquals(listOf(0x5038L,0x03204020L,0x5438L,0x83L),(0..3).map{f.word(0x15b15e0L+it*4)})
  assertEquals(listOf(0x5384L,1L,0x5384L,0xffffffffL,0L),(0..4).map{f.word(0x15b15ccL+it*4)})

  val bank=DramCommandBank(f.tables(0),listOf(DramCommandBank.Request(4,20,0,null)),0,repeatableAnyMode=true)
  program(bank,bank=0,mode=4,tick=0)
  assertEquals(0L,bank.read(0x5384,4,20))
  assertEquals(1L,bank.state()["guestZeroObservationCount"])

  // Finite bank strictly refuses targeted 3-word write after plan exhaustion
  val finite=model(bank=0,mode=4,delay=20)
  program(finite,bank=0,mode=4,tick=0)
  assertEquals(0L,finite.read(0x5384,4,20))
  assertFails{finite.write(0x5038,4,0x03204020L,21)}

  // Movie / high-bw 3-word targeted update (DAT 0x015b15e0), with mid-sequence snapshot round-trip and mismatch refusal
  bank.write(0x5038,4,0x03204020L,21)
  val midSnap=bank.saveSnapshot()
  assertFails{finite.validateSnapshot(midSnap)}
  val resumed=DramCommandBank(f.tables(0),listOf(DramCommandBank.Request(4,20,0,null)),0,repeatableAnyMode=true)
  resumed.applySnapshot(resumed.validateSnapshot(midSnap))
  val beforeBad=resumed.state()
  assertFails{resumed.write(0x5438,4,0x81L,21)} // wrong table word (normal instead of movie)
  assertFails{resumed.read(0x5384,4,21)} // premature read before 0x5384 trigger
  assertEquals(beforeBad,resumed.state())

  resumed.write(0x5438,4,0x83L,21)
  resumed.write(0x5384,4,1L,21)
  assertEquals(41L,resumed.nextDeadline())
  assertEquals(1L,resumed.read(0x5384,4,40))
  val pendingSnap=resumed.saveSnapshot()
  val pendingRestored=DramCommandBank(f.tables(0),listOf(DramCommandBank.Request(4,20,0,null)),0,repeatableAnyMode=true)
  pendingRestored.applySnapshot(pendingRestored.validateSnapshot(pendingSnap))
  assertEquals(41L,pendingRestored.nextDeadline())
  assertEquals(0L,pendingRestored.read(0x5384,4,41))
  assertEquals(2L,pendingRestored.state()["guestZeroObservationCount"])
  assertFails{pendingRestored.read(0x5384,4,41)} // post-zero read still refuses

  // Normal 3-word targeted restore (DAT 0x015b15bc) followed by a full 71-word mode-0 table switch
  pendingRestored.write(0x5038,4,0x01004100L,50)
  pendingRestored.write(0x5438,4,0x81L,50)
  pendingRestored.write(0x5384,4,1L,50)
  assertEquals(70L,pendingRestored.nextDeadline())
  assertEquals(0L,pendingRestored.read(0x5384,4,70))
  assertEquals(3L,pendingRestored.state()["guestZeroObservationCount"])

  program(pendingRestored,bank=0,mode=0,tick=80)
  assertEquals(100L,pendingRestored.nextDeadline())
  assertEquals(0L,pendingRestored.read(0x5384,4,100))
  assertEquals(4L,pendingRestored.state()["guestZeroObservationCount"])
 }
}
