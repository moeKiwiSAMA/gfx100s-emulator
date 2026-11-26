package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.WritableImageState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Selected DAT software partitions, not physical geometry or an inferred reset image. */
object FlashPartitionPairContracts {
 const val KEY="flashPartitionScope"
 const val PROFILE="dat-partition-pair-experimental-v1"
 private const val APP=0x1021000L
 private data class Span(val start:Long,val size:Int,val sha256:String)
 private val spans=listOf(
  Span(0x129fdc8,104,"b1e99ed03c69f9ba94d5a986fdcaf72412574cc6a2ab0017df16d0b95632f496"),
  Span(0x129df0c,84,"8d505bd6aaa2cf0a6f03753b2a65922f6fa070db1baea1f49f1a2b098884f1f7"),
  Span(0x17477d8,24,"bb7639423138a57ae789586ca0d4be1e52feaa52aa5f3429d9a0543905a9e9e1"),
  Span(0x11deb58,24,"326dd77a4c0af88dbc5edc8a28ffd96e2a4334c22cfa41cfd17a1fabe2ba5570"))
 val wordAddresses:List<Long> get()=spans.flatMap{(it.start until it.start+it.size step 4).toList()}
 fun selected(config:Map<String,*>):Boolean {if(!config.containsKey(KEY))return false;require(config[KEY] in listOf(PROFILE,FlashPartitionQuartetContracts.PROFILE,FlashPartitionOctetContracts.PROFILE)){"flashPartitionScope requires explicit qualified profile"};return true}
 fun observe(app:ByteArray):Map<String,Any> {
  fun bytes(pc:Long,n:Int):ByteArray{require(pc>=APP&&pc%4==0L&&n>0&&pc-APP+n<=app.size){"partition-pair DAT span outside initialized app"};return app.copyOfRange((pc-APP).toInt(),(pc-APP+n).toInt())}
  fun word(pc:Long)=ByteBuffer.wrap(bytes(pc,4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong()and 0xffffffffL
  fun half(w:Long)=(w and 4095)or(w ushr 4 and 0xf000)
  fun pointer(pc:Long)=half(word(pc))or(half(word(pc+4))shl 16)
  fun immediate(pc:Long):Long{val w=word(pc);return Integer.rotateRight((w and 255).toInt(),((w ushr 8 and 15)*2).toInt()).toLong()and 0xffffffffL}
  fun branch(pc:Long)=pc+8+((word(pc).toInt()shl 8)shr 6)
  for(s in spans)require(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(s.start,s.size)))==s.sha256){"partition-pair DAT span differs at "+s.start.toString(16)}
  val table=pointer(0x129fdf4);require(pointer(0x129fe14)==table&&table==0x17476e8L)
  val row0=table+(word(0x129fe00)and 4095);val row1=table+(word(0x129fe20)and 4095)
  require(table+(word(0x129fdfc)and 4095)==row0+4&&table+(word(0x129fe1c)and 4095)==row1+4&&row1-row0==12L)
  val shift=word(0x129df38)ushr 7 and 31;require(shift==17L&&word(0x129df44)ushr 7 and 31==shift)
  val aperture=immediate(0x11deb60);val flag=pointer(0x129fdc8)
  require(immediate(0x129fddc)==0L&&immediate(0x129fde4)==1L&&branch(0x129fe0c)==0x129df0cL&&branch(0x129df48)==0x11dea40L&&branch(0x129df3c)==0x11deb58L&&aperture==0xf0000000L&&flag==0x181b200L)
  val partitions=listOf(row0,row1).mapIndexed{mode,row->val count=word(row);val first=word(row+4);val offset=first shl shift.toInt();val length=count shl shift.toInt();mapOf("mode" to mode,"rowAddress" to row,"countBlocks" to count,"firstBlock" to first,"thirdWordUnknown" to word(row+8),"offset" to offset,"length" to length,"rawAddress" to aperture+offset)}
  val start=partitions[0].getValue("offset")as Long;val firstLength=partitions[0].getValue("length")as Long;val secondStart=partitions[1].getValue("offset")as Long;val secondLength=partitions[1].getValue("length")as Long
  require(partitions.map{it["firstBlock"]}==listOf(494L,495L)&&partitions.all{it["countBlocks"]==1L&&it["thirdWordUnknown"]==3L}&&start+firstLength==secondStart&&start==0x3dc0000L&&firstLength+secondLength==0x40000L)
  return linkedMapOf("profile" to PROFILE,"criticalWords" to wordAddresses.size,"selectorEntry" to 0x129fdc8L,"initializedFlag" to flag,"table" to table,"logicalBlockShift" to shift,"apertureBase" to aperture,"partitions" to partitions,"writableOffset" to start,"writableLength" to firstLength+secondLength,"endExclusive" to secondStart+secondLength,
   "basis" to "DAT complete mode selector/block converter, selected two12-byte rows and aperture conversion. Explicit permission only on derived494/495; thirdWord3/purpose/physical geometry/content remain UNKNOWN. No fault-driven range, RAM or input growth.")
 }
 fun verify(image:FirmwareImage,copy:WritableImageState):Map<String,Any> {
  require(image.metadata.id=="gfx100s-213"&&image.metadata.sha256=="ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12"){"partition-pair requires qualified selected firmware"}
  val plan=observe(image.decode("section4"));val start=plan.getValue("writableOffset")as Long;val size=plan.getValue("writableLength")as Long
  require(copy.writableOffset==start&&copy.writableLength==size&&copy.offset>=0&&copy.length in 1..0x8000000L&&copy.offset<=0x8000000L-copy.length&&copy.offset<=start&&Math.addExact(copy.offset,copy.length)>=start+size&&!copy.pending&&!copy.closed&&copy.failure==null&&copy.generation>=0){"selected partition-pair writable range/lifecycle differs from DAT"}
  return plan+mapOf("selectedWritableInput" to copy,"scope" to "Explicit private copy permission only; parent erase contract still validates DAT/seed/windows/tail, readonly profiles and all other holes remain. No program, physical persistence or firmware boot/menu qualification.")
 }
}
