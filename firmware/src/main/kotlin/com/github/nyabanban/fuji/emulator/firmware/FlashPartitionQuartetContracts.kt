package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.WritableImageState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Extends explicit pair only with the independently decoded second erase selector/rows. */
object FlashPartitionQuartetContracts {
 const val PROFILE="dat-partition-quartet-experimental-v2"
 private const val APP=0x1021000L
 private data class Span(val start:Long,val size:Int,val sha256:String)
 private val spans=listOf(Span(0x12a001c,104,"3b4387e7c899727f66a55818e389bd9fd103e89a9e7b182db3f54048c837af31"),Span(0x17477f0,24,"c45d72fe5ae913b999b4f56484a7b886e45bb54c37ba471755dc8b15eb7293b3"))
 val wordAddresses:List<Long> get()=FlashPartitionPairContracts.wordAddresses+spans.flatMap{(it.start until it.start+it.size step 4).toList()}
 fun observe(app:ByteArray):Map<String,Any> {
  val parent=FlashPartitionPairContracts.observe(app)
  fun bytes(pc:Long,n:Int):ByteArray{require(pc>=APP&&pc%4==0L&&n>0&&pc-APP+n<=app.size){"partition-quartet DAT span outside initialized app"};return app.copyOfRange((pc-APP).toInt(),(pc-APP+n).toInt())}
  fun word(pc:Long)=ByteBuffer.wrap(bytes(pc,4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong()and 0xffffffffL
  fun half(w:Long)=(w and 4095)or(w ushr 4 and 0xf000)
  fun pointer(pc:Long)=half(word(pc))or(half(word(pc+4))shl 16)
  fun immediate(pc:Long):Long{val w=word(pc);return Integer.rotateRight((w and 255).toInt(),((w ushr 8 and 15)*2).toInt()).toLong()and 0xffffffffL}
  fun branch(pc:Long)=pc+8+((word(pc).toInt()shl 8)shr 6)
  for(s in spans)require(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(s.start,s.size)))==s.sha256){"partition-quartet DAT span differs at "+s.start.toString(16)}
  val table=pointer(0x12a0048);val shift=parent.getValue("logicalBlockShift")as Long;require(pointer(0x12a0068)==table&&table==parent["table"]&&pointer(0x12a001c)==parent["initializedFlag"]&&immediate(0x12a0030)==0L&&immediate(0x12a0038)==1L&&branch(0x12a0060)==0x129df0cL)
  val rows=listOf(table+(word(0x12a0054)and 4095),table+(word(0x12a0074)and 4095))
  require(table+(word(0x12a0050)and 4095)==rows[0]+4&&table+(word(0x12a0070)and 4095)==rows[1]+4&&rows[1]-rows[0]==12L)
  val second=rows.mapIndexed{mode,row->val count=word(row);val first=word(row+4);mapOf("selectorEntry" to 0x12a001cL,"mode" to mode,"rowAddress" to row,"countBlocks" to count,"firstBlock" to first,"thirdWordUnknown" to word(row+8),"offset" to(first shl shift.toInt()),"length" to(count shl shift.toInt()),"rawAddress" to((parent.getValue("apertureBase")as Long)+(first shl shift.toInt())))}
  require(second.map{it["firstBlock"]}==listOf(496L,497L)&&second.all{it["countBlocks"]==1L&&it["thirdWordUnknown"]==3L})
  val first=(parent.getValue("partitions")as List<*>).map{it as Map<*,*>};val partitions=first.map{it+mapOf("selectorEntry" to 0x129fdc8L)}+second
  val start=parent.getValue("writableOffset")as Long;val end=(second.last().getValue("offset")as Long)+(second.last().getValue("length")as Long)
  require(second.first()["offset"]==parent["endExclusive"]&&end-start==0x80000L&&end==0x3e40000L)
  return parent+mapOf("profile" to PROFILE,"criticalWords" to wordAddresses.size,"secondarySelectorEntry" to 0x12a001cL,"partitions" to partitions,"writableLength" to end-start,"endExclusive" to end,
   "basis" to "Verified primary selector494/495 plus distinct complete secondary erase selector496/497 and exact two rows; scope is explicit selected half of same1MiB seed. ThirdWord3/purpose/physical geometry/content remain unknown; blocks490..493 and every other hole remain outside permission.")
 }
 fun verify(image:FirmwareImage,copy:WritableImageState):Map<String,Any> {
  require(image.metadata.id=="gfx100s-213"&&image.metadata.sha256=="ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12"){"partition-quartet requires qualified selected firmware"}
  val plan=observe(image.decode("section4"));val start=plan.getValue("writableOffset")as Long;val size=plan.getValue("writableLength")as Long
  require(copy.writableOffset==start&&copy.writableLength==size&&copy.offset>=0&&copy.length in 1..0x8000000L&&copy.offset<=0x8000000L-copy.length&&copy.offset<=start&&Math.addExact(copy.offset,copy.length)>=start+size&&!copy.pending&&!copy.closed&&copy.failure==null&&copy.generation>=0){"selected partition-quartet writable range/lifecycle differs from DAT"}
  return plan+mapOf("selectedWritableInput" to copy,"scope" to "Explicit private copy permission for four DAT erase rows only; parent erase contract validates seed/DAT/windows/tail. No storage input/RAM growth, program support, power-loss or boot/menu qualification.")
 }
}
