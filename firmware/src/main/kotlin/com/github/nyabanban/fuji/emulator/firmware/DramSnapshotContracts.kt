package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Selected DAT11FADF8 snapshot/configuration/poll/final-write geometry; external word purpose remains UNKNOWN. */
object DramSnapshotContracts {
 const val PROFILE="gfx100s-dram-snapshot-functional-experimental-v3"
 const val FUNCTION_SHA="d588c842f406b79b22d66f0fd986a0d360789b8472f3710a833a528d59a1fb1c"
 const val CONTRACT_SHA="fcd9993882a029184c90f6914e4235ca944265ee83f93cde8238cee000045765"
 fun port(bank:Int):String {require(bank in 0..3);return "soc-dram.snapshot-ack-$bank"}
 private fun hash(b:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
 fun observe(app:ByteArray):Map<String,Any> {
  require(hash(app)==DramContracts.SECTION_SHA){"DRAM snapshot selected section identity differs"}
  val buffer=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN);val base=0x1021000L
  fun word(pc:Long):Long {require(pc>=base&&pc%4L==0L&&pc-base<=app.size-4);return buffer.getInt((pc-base).toInt()).toLong()and 0xffffffffL}
  fun immediate(w:Long)=Integer.rotateRight((w and 255).toInt(),((w ushr 8)and 15).toInt()*2).toLong()and 0xffffffffL
  fun wide(w:Long)=((w ushr 4)and 0xf000L)or(w and 0xfffL)
  fun address(pc:Long,rd:Int):Long {val lo=word(pc);val hi=word(pc+4);require(lo ushr 28==14L&&hi ushr 28==14L&&lo and 0x0ff00000L==0x03000000L&&hi and 0x0ff00000L==0x03400000L&&(lo ushr 12)and 15L==rd.toLong()&&(hi ushr 12)and 15L==rd.toLong());return wide(lo)or(wide(hi)shl 16)}
  fun branch(pc:Long):Long {val w=word(pc);require(w ushr 28==14L&&w and 0x0f000000L==0x0b000000L);return(pc+8+((w.toInt()shl 8)shr 6))and 0xffffffffL}
  val function=app.copyOfRange(0x11fadf8-base.toInt(),0x11faf28-base.toInt());require(hash(function)==FUNCTION_SHA)
  require(word(0x11fadf8)==0xe3500003L);val bankCount=immediate(word(0x11fadf8)).toInt()+1;require(bankCount==4)
  val phyTable=address(0x11fae10,3);val controllerTable=address(0x11fae7c,3);val configurationTable=address(0x11fae88,4);val requestTable=address(0x11faec0,3)
  require(phyTable==0x15b1838L&&controllerTable==0x15b1848L&&configurationTable==0x15b1750L&&requestTable==0x15b173cL&&address(0x11faed8,4)==requestTable)
  val readProducers=listOf(0x11fae1cL,0x11fae2cL,0x11fae3cL,0x11fae4cL,0x11fae5cL,0x11fae6cL)
  val phyOffsets=readProducers.map{pc->require(branch(pc+8)==0x5afd8L);immediate(word(pc))+immediate(word(pc+4))}
  require(phyOffsets==listOf(0x4660L,0x4664L,0x4480L,0x4484L,0x4488L,0x448cL))
  val configCalls=(0x11fae90L until 0x11faec0L step 4).filter{word(it)and 0x0f000000L==0x0b000000L};require(configCalls.size==3&&configCalls.all{branch(it)==0x5af90L})
  val configuration=configCalls.indices.map{index->word(configurationTable+index*8)to word(configurationTable+index*8+4)}
  require(branch(0x11faed4)==0x5af90L&&branch(0x11faeec)==0x5afd8L&&branch(0x11faf14)==0x5af90L)
  val request=(0..4).map{word(requestTable+it*4)}
  require(word(0x11faf04)==0xe1a00206L&&word(0x11faf0c)==0xe2400ab6L&&word(0x11faf10)==0xe2400b01L)
  val shift=(word(0x11faf04)ushr 7)and 31;val subtract=immediate(word(0x11faf0c))+immediate(word(0x11faf10))
  val ack=(0 until bankCount).map{((it.toLong()shl shift.toInt())-subtract)and 0xffffffffL}
  val canonical=ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN);configuration.forEach{(o,v)->canonical.putInt(o.toInt());canonical.putInt(v.toInt())};request.forEach{canonical.putInt(it.toInt())};phyOffsets.forEach{canonical.putInt(it.toInt())};ack.forEach{canonical.putInt(it.toInt())};require(hash(canonical.array())==CONTRACT_SHA)
  val windows=ack.mapIndexed{bank,at->mapOf("port" to port(bank),"address" to at,"size" to 4L)}
  return linkedMapOf("matches" to true,"entry" to 0x11fadf8L,"functionSha256" to hash(function),"contractBytes" to 84,"contractSha256" to hash(canonical.array()),"bankCount" to bankCount,"phyReadOffsets" to phyOffsets,"phyBases" to(0 until bankCount).map{word(phyTable+it*4)},"controllerBases" to(0 until bankCount).map{word(controllerTable+it*4)},"configurationTable" to configurationTable,"configurationWords" to configuration.map{mapOf("offset" to it.first,"value" to it.second)},"requestTable" to requestTable,"requestOffset" to request[0],"requestValue" to request[1],"statusOffset" to request[2],"statusMask" to request[3],"statusExpected" to request[4],"finalExternalWrites" to windows,"basis" to "DAT6 PHY reads into output,3 configuration writes,request/masked poll and final external word. External word purpose UNKNOWN; ack is a functional protocol label. Samples/delay/pending status and retention are explicit HYP. No physical training/reset/IRQ/scanout/menu claim.")
 }
}
