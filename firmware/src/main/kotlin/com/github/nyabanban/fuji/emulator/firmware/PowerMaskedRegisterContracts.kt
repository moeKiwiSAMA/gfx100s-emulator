package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Exact GFX100S213 producer/consumer, not a physical register or panel-state law. */
object PowerMaskedRegisterContracts {
 const val PROFILE="masked-retained-registers-experimental-v2"
 private const val APP=0x1021000L
 private data class Span(val address:Long,val bytes:Int,val sha256:String)
 private val spans=listOf(
  Span(0x124cd10,248,"725c25a1e2355c590d95505227c39817419123b4543d16aecc3825088bfa746b"),
  Span(0x120bd88,608,"9988d025cc98c5b214828500f541f12c28cf04113554518661621feefbfce809"),
  Span(0x120bfe8,204,"66f06de430c87fea3788329a6517def92dc8cf3424c1940a1721aa882509b3c0"))
 val wordAddresses:List<Long> get()=spans.flatMap{(it.address until it.address+it.bytes step 4).toList()}
 fun observe(app:ByteArray):Map<String,Any> {
  fun bytes(pc:Long,size:Int):ByteArray{require(pc>=APP&&pc%4==0L&&size>0&&pc-APP+size<=app.size){"masked power DAT span outside initialized app"};return app.copyOfRange((pc-APP).toInt(),(pc-APP+size).toInt())}
  fun word(pc:Long)=ByteBuffer.wrap(bytes(pc,4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong()and 0xffffffffL
  fun immediate(pc:Long):Long{val w=word(pc);return Integer.rotateRight((w and 255).toInt(),((w ushr 8 and 15)*2).toInt()).toLong()and 0xffffffffL}
  fun branch(pc:Long)=pc+8+((word(pc).toInt()shl 8)shr 6)
  fun half(w:Long)=(w and 4095)or(w ushr 4 and 0xf000)
  fun pointer(pc:Long)=half(word(pc))or(half(word(pc+4))shl 16)
  fun width(pc:Long):Long{val w=word(pc);return (w ushr 16 and 31)-(w ushr 7 and 31)+1}
  for(s in spans)require(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(s.address,s.bytes)))==s.sha256){"masked power DAT span differs at "+s.address.toString(16)}
  val register=immediate(0x124cd38);val mask=immediate(0x124cd4c);val producerMask=immediate(0x124cd8c)
  val lowRead=word(0x124cd88)and 4095;val lowWrite=word(0x124cd90)and 4095;val load=word(0x124cd94);val halfRead=(load ushr 4 and 0xf0)or(load and 15)
  val readWrapper=branch(0x124cd3c);val writer=branch(0x124cd9c);val formatter=branch(0x120c0ac)
  val writeFlag=immediate(0x120c008)ushr 24;val flag=pointer(0x124cdb0)+(word(0x124cdbc)and 4095)
  require(register==2L&&immediate(0x124cd98)==register&&mask==4L&&producerMask==mask&&lowRead==16L&&lowWrite==lowRead&&halfRead==lowRead&&load==0xe15b11b0L)
  require(readWrapper==0x120be60L&&writer==0x120c0a4L&&formatter==0x120bfe8L&&branch(0x124cd6c)==0x124c8e0L&&branch(0x124cd7c)==0x124c9f4L&&branch(0x124cd84)==0x124cde8L&&branch(0x124cda4)==0x124cdf8L)
  require(width(0x120c004)==7L&&width(0x120c00c)==16L&&width(0x120c020)==8L&&writeFlag==128L&&flag==0x187afb1L&&immediate(0x124cdb8)==1L)
  return linkedMapOf("profile" to PROFILE,"register" to register,"writeMask" to mask,"dataBits" to 16,"lowByteStackOffset" to lowRead,"halfwordStackOffset" to halfRead,
   "reader" to readWrapper,"writer" to writer,"formatter" to formatter,"transport" to branch(0x120c060),"callerEntry" to 0x124cd10L,"callerWrite" to 0x124cd9cL,"callerAfterWrite" to 0x124cda0L,
   "clearCall" to 0x124cd6cL,"builderCall" to 0x124cd7cL,"builderAfter" to 0x124cd80L,"softwareFlag" to flag,"softwareFlagValue" to 1,"criticalWords" to wordAddresses.size,
   "basis" to "DAT full248-byte consumer plus608-byte read/cache range and204-byte writer/wrapper. Low-byte OR4 preserves high byte, full halfword write follows successful builder. Masked retention/initial word/readback are explicit HYP, not hardware power/panel identity or measured reset law.")
 }
 fun verify(image:FirmwareImage,input:Map<*,*>):Map<String,Any> {
  require(image.metadata.id=="gfx100s-213"){"masked power profile is not qualified for selected firmware"}
  require(input["profile"]==PROFILE){"masked power input profile differs"}
  val masks=requireNotNull(input["write_masks"]as? Map<*,*>);require(masks.keys==setOf("2")){"masked power selected register mask differs from DAT"}
  val mask=masks["2"];require(mask is Byte||mask is Short||mask is Int||mask is Long);require((mask as Number).toLong()==4L){"masked power selected write mask differs from DAT"}
  val initial=(input["inputs"]as? Map<*,*>)?.get("2");require(initial is Byte||initial is Short||initial is Int||initial is Long);val word=(initial as Number).toLong();require(word in 0..65535)
  return observe(image.decode("section4"))+mapOf("selectedInitialWord" to word,"selectedWriteMask" to 4L,"modelLaw" to "HYP full-word read with only explicitly masked bit writes; no independent power/display/ready effect, clock, battery change or persistence")
 }
}
