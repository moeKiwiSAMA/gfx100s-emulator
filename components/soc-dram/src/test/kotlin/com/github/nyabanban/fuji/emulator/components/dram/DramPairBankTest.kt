package com.github.nyabanban.fuji.emulator.components.dram

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

internal class DramFixture {
 val root=Path.of(DramTestPaths.value("fujiRepo"))
 val app=Files.readAllBytes(root.resolve("firmware-data/gfx100s/2.13/decoded/section4.bin"))
 val kernel=Files.readAllBytes(root.resolve("firmware-data/gfx100s/2.13/decoded/kernel.bin"))
 fun word(address:Long)=ByteBuffer.wrap(app,Math.toIntExact(address-0x1021000L),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()and 0xffffffffL
 fun pairs(address:Long,count:Int)=(0 until count).map{word(address+it*8).toInt() to word(address+it*8+4)}
 val programming=pairs(0x15ad2dc,word(0x15ae84c).toInt())
 val config=pairs(0x15b16cc,word(0x15b1704).toInt())+pairs(0x15b1640,word(0x15b16c8).toInt())+pairs(0x15b1708,4)
 fun bank(source:DramPairBank.ResponseSource?=DramPairBank.ResponseSource{stage->reply(stage)},delays:LongArray=longArrayOf(20,20,20,20,20))=DramPairBank(programming,config,delays,0x80,0x40,source)
 fun reply(stage:Int):LongArray = when(stage) {
  0->longArrayOf(1,0x30000,0x30003,0x30003)
  1,2->longArrayOf(0)
  3->longArrayOf(1,0,0,0x20000,0x02000200,0x02000200,0x200,0x02000000,0x200,0x02000200,0x02000200,0x300,0x300,0,0,0,0)+LongArray(8){0x02000200}+longArrayOf(0x200,0x200)
  4->longArrayOf(1)
  else->error("Unknown phase")
 }
 fun program(b:DramPairBank,tick:Long=0){programming.forEach{(offset,value)->b.write(false,offset,4,value,tick)}}
 fun deliverInitial(b:DramPairBank){program(b);b.write(true,0x7c18,4,0x40000000,0);b.advance(20)}
 fun throughPhy(b:DramPairBank){deliverInitial(b);b.write(true,0x7040,4,3,20);b.advance(40);b.write(true,0x7040,4,0x10,40);b.advance(60);b.write(false,0x2000,4,0xb01,60);b.advance(80)}
}
class DramPairBankTest {
 private val f=DramFixture()
 @Test fun constructorCopiesInputsAndRejectsIncompleteOrUnqualifiedDomains() {
  val times=longArrayOf(20,20,20,20,20);val p=f.programming.toMutableList();val b=DramPairBank(p,f.config,times,0x80,0x40,null);times.fill(0);p.clear();f.program(b);b.write(true,0x7c18,4,0x40000000,1);assertEquals(21L,b.nextDeadline())
  assertFails{DramPairBank(f.programming.drop(1),f.config,longArrayOf(1,1,1,1,1),0,0,null)}
  assertFails{DramPairBank(f.programming.dropLast(1)+f.programming.first(),f.config,longArrayOf(1,1,1,1,1),0,0,null)}
  assertFails{f.bank(delays=longArrayOf(0,1,1,1,1))};assertFails{f.bank(delays=longArrayOf(1,1))}
  assertFails{DramPairBank(f.programming,f.config,longArrayOf(1,1,1,1,1),0x100000000,0,null)}
 }
 @Test fun outOfOrderProgrammingAndDuplicateValuesDoNotInventMoreThan686Words() {
  val b=f.bank();assertFails{b.read(false,0x2000,4,0)};for((o,v)in f.programming.reversed())b.write(false,o,4,v,0);val pair=f.programming.first();b.write(false,pair.first,4,pair.second,0);assertEquals(686,b.state()["programmedWordCount"]);assertEquals(687L,b.state()["completedWriteCount"]);assertEquals(pair.second,b.read(false,pair.first,4,0))
 }
 @Test fun allBadWidthsOffsetsValuesAndEarlyRequestsAreAtomic() {
  val b=f.bank();val before=b.state()
  for(width in listOf(1,2,8))assertFails{b.write(false,0x2000,width,0xb00,0)}
  for(offset in listOf(0,0x2002,0x10000,-4))assertFails{b.write(false,offset,4,0,0)}
  assertFails{b.write(false,0x2000,4,0xb02,0)};assertFails{b.write(false,0x2000,4,0x100000000,0)};assertFails{b.write(true,0x7c18,4,0x40000000,0)};assertFails{b.write(true,0x7040,4,3,0)};assertFails{b.write(true,0x7044,4,0,0)}
  assertEquals(before,b.state())
 }
 @Test fun exactDeadlinePatchesOnlyQualifiedFieldsAndKeepsProgrammedLowBits() {
  val b=f.bank();f.program(b);b.write(true,0x7c18,4,0x40000000,10);val before=b.state();repeat(3){assertEquals(30L,b.nextDeadline())};assertEquals(before,b.state());b.advance(29);assertEquals(0,b.state()["pendingStage"]);b.advance(30)
  assertEquals(0x30924L,b.read(false,0x4670,4,30));assertEquals(0x30003L,b.read(false,0x4674,4,30));assertEquals(1L,b.read(true,0x7c18,4,30));assertEquals(30L,b.state()["responseDeliveredAt"]);assertEquals(1L,b.state()["responseDeliveryCount"]);assertNull(b.nextDeadline())
 }
 @Test fun lateObservationRetainsScheduledEdgeAndDeliversOnlyOnce() {
  var samples=0;val b=f.bank(DramPairBank.ResponseSource{samples++;f.reply(it)});f.program(b);b.write(true,0x7c18,4,0x40000000,10);b.advance(100);b.advance(101);assertEquals(1,samples);assertEquals(30L,b.state()["responseDeliveredAt"]);assertEquals(101L,b.state()["clock"])
 }
 @Test fun absentMalformedOrOutOfMaskResponseNeverPartiallyChangesWordsOrPending() {
  for(input in listOf<LongArray?>(null,longArrayOf(1),longArrayOf(1,0x30000,0x30003,0x30004),longArrayOf(1,0x30000,0x30003,-1),longArrayOf(1,0x30000,0x30003,0x100000000))){val b=f.bank(DramPairBank.ResponseSource{input});f.program(b);b.write(true,0x7c18,4,0x40000000,0);val before=b.state();assertFails{b.advance(20)};assertEquals(before,b.state());assertEquals(20L,b.nextDeadline())}
 }
 @Test fun missingReplyCanArriveLaterWithoutChangingTheOriginalDeadline() {
  var sample:LongArray?=null;val b=f.bank(DramPairBank.ResponseSource{sample});f.program(b);b.write(true,0x7c18,4,0x40000000,0);assertFails{b.advance(20)};sample=f.reply(0);b.advance(100);assertEquals(20L,b.state()["responseDeliveredAt"]);assertEquals(100L,b.state()["clock"])
 }
 @Test fun overflowBackwardClockAndBusyReconfigurationArePreflighted() {
  val b=f.bank();f.program(b);val before=b.state();assertFails{b.write(true,0x7c18,4,0x40000000,Long.MAX_VALUE-5)};assertEquals(before,b.state());b.write(true,0x7c18,4,0x40000000,10);val pending=b.state();assertFails{b.write(true,0x7c18,4,0x40000000,11)};assertFails{b.write(true,f.config.first().first,4,f.config.first().second,11)};assertFails{b.advance(9)};assertEquals(pending,b.state())
 }
 @Test fun allFiveInputsPreserveOtherStatusBitsAndProgrammedCalibrationBits() {
  val b=f.bank();f.throughPhy(b);assertEquals(0x80L,b.read(true,0x7044,4,80));assertEquals(0x200ccL,b.read(false,0x4480,4,80));assertEquals(0x200L,b.read(false,0x4148,4,80));assertEquals(1L,b.read(false,0x2110,4,80));b.write(true,0x7360,4,0xff00c00d,80);assertEquals(0x40L,b.read(true,0x7364,4,80));b.advance(100);assertEquals(0x41L,b.read(true,0x7364,4,100));assertEquals(5L,b.state()["responseDeliveryCount"])
 }
 @Test fun explicitNonreadyInputDoesNotBecomeASuccessfulTrainingCompletion() {
  val b=f.bank(DramPairBank.ResponseSource{LongArray(f.reply(it).size)});f.deliverInitial(b);assertEquals(0L,b.read(true,0x7c18,4,20));assertEquals(0x924L,b.read(false,0x4670,4,20));val before=b.state();assertFails{b.write(true,0x7040,4,3,20)};assertEquals(before,b.state());assertEquals(1L,b.state()["responseDeliveryCount"])
 }
 @Test fun busySampleAndMissingPhyReadyRefuseTheNextCommand() {
  val first=f.bank(DramPairBank.ResponseSource{if(it==1)longArrayOf(1)else f.reply(it)});f.deliverInitial(first);first.write(true,0x7040,4,3,20);first.advance(40);val before=first.state();assertFails{first.write(true,0x7040,4,0x10,40)};assertEquals(before,first.state())
  val second=f.bank(DramPairBank.ResponseSource{if(it==3)f.reply(it).also{a->a[0]=0}else f.reply(it)});f.throughPhy(second);val before2=second.state();assertFails{second.write(true,0x7360,4,0xff00c00d,80)};assertEquals(before2,second.state())
 }
 @Test fun acknowledgementIsBoundedAndItsUnqualifiedReadEffectsStayObservable() {
  val b=f.bank();f.throughPhy(b);b.write(true,0x7360,4,0xff00c00d,80);b.advance(100);b.write(true,0x7364,4,1,100);val before=b.state();assertFails{b.read(true,0x7364,4,100)};assertFails{b.write(true,0x7364,4,1,100)};assertEquals(before,b.state());assertEquals(true,b.state()["acknowledged"])
 }
 @Test fun closeInvalidatesEveryRetainedEntryAndFieldDescriptorsAreDefensive() {
  val b=f.bank();val copy=b.responseFields(0).toMutableList();copy.clear();assertEquals(4,b.responseFields(0).size);b.close();assertFails{b.read(false,0x2000,4,0)};assertFails{b.write(false,0x2000,4,0xb00,0)};assertFails{b.advance(0)};assertFails{b.nextDeadline()}
 }
}
