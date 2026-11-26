package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Qualified DAT transfer shape; retained words are explicitly selected HYP, not physical NVRAM/RTC identity. */
object PowerBlockContracts {
 const val KEY="powerBlockScope"
 const val PROFILE="dat-checksummed-four-halfword-experimental-v1"
 private const val APP=0x1021000L
 private data class Span(val address:Long,val bytes:Int,val sha256:String)
 private val spans=listOf(
  Span(0x120d288,112,"105de154f593e1966bb3e886609498ebb5a6f9a2dffedd92f06a180b646ce045"),
  Span(0x12a2ef8,140,"f907927a428568dce8c4864d6e0b6a73398c3b4257cb6e9d5d2ff5799794c4a7"))
 val wordAddresses:List<Long> get()=PowerMaskedRegisterContracts.wordAddresses+spans.flatMap{(it.address until it.address+it.bytes step 4).toList()}
 fun selected(config:Map<*,*>):Boolean {
  if(!config.containsKey(KEY))return false
  require(config[KEY] is String&&config[KEY]==PROFILE){"invalid explicit power block scope"}
  return true
 }
 fun observe(app:ByteArray):Map<String,Any> {
  val register2=PowerMaskedRegisterContracts.observe(app)
  fun bytes(pc:Long,size:Int):ByteArray{require(pc>=APP&&pc%4==0L&&size>0&&pc-APP+size<=app.size){"power block DAT span outside initialized app"};return app.copyOfRange((pc-APP).toInt(),(pc-APP+size).toInt())}
  fun word(pc:Long)=ByteBuffer.wrap(bytes(pc,4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
  fun immediate(pc:Long):Long{val w=word(pc);return Integer.rotateRight((w and 255).toInt(),((w ushr 8 and 15)*2).toInt()).toLong() and 0xffffffffL}
  fun branch(pc:Long)=pc+8+((word(pc).toInt() shl 8) shr 6)
  fun half(w:Long)=(w and 4095) or (w ushr 4 and 0xf000)
  fun pointer(pc:Long)=half(word(pc)) or (half(word(pc+4)) shl 16)
  for(s in spans)require(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(s.address,s.bytes)))==s.sha256){"power block DAT span differs at "+s.address.toString(16)}
  val first=immediate(0x120d2a0);val end=immediate(0x120d2b8);val stride=(word(0x120d2a4) ushr 4 and 0xf0) or (word(0x120d2a4) and 15)
  val source=pointer(0x12a2f28);val shadow=word(0x12a2f80)+1;val count=end-first
  val control=immediate(0x120d2c4);val lowOffset=word(0x120d2d8) and 4095;val highOffset=word(0x120d2cc) and 4095
  val clearBit=(word(0x120d2d0) ushr 7 and 31).toInt();val clearWidth=(word(0x120d2d0) ushr 16 and 31)-clearBit+1
  val clearMask=(1L shl clearBit) shl ((lowOffset-highOffset)*8).toInt();val setMask=immediate(0x120d2dc)
  require(first==31L&&end==35L&&count==4L&&stride==2L&&word(0x120d2a4)==0xe1f510b2L&&immediate(0x120d29c)==stride)
  require(branch(0x120d2ac)==register2["writer"]&&branch(0x120d2ec)==register2["writer"]&&branch(0x120d2c8)==register2["reader"]&&branch(0x12a2f74)==0x120d288L)
  require(control==39L&&immediate(0x120d2e8)==control&&lowOffset==16L&&highOffset==15L&&clearBit==1&&clearWidth==1L&&clearMask==0x200L&&setMask==1L&&word(0x120d2e4)==0xe15b11b0L)
  require(source==0x1bf6970L&&word(0x12a2f7c)+1==source&&pointer(0x12a2f6c)==source&&shadow==0x1c7cc34L)
  require(immediate(0x12a2f08)==7L&&word(0x12a2f34) and 4095==7L&&immediate(0x12a2f3c)==7L)
  return linkedMapOf("profile" to PROFILE,"criticalWords" to wordAddresses.size,"writerEntry" to 0x120d288L,"source" to source,"shadow" to shadow,
   "bytes" to count*stride,"checksumBytes" to 7,"checksumOffset" to 7,"firstRegister" to first,"endRegisterExclusive" to end,"registers" to(first until end).toList(),
   "stride" to stride,"dataBits" to 16,"writeCall" to 0x120d2acL,"afterWrite" to 0x120d2b0L,"afterLoop" to 0x120d2c0L,"callerEntry" to 0x12a2ef8L,"callerWrite" to 0x12a2f74L,"callerAfterWrite" to 0x12a2f78L,
   "controlRegister" to control,"controlReadCall" to 0x120d2c8L,"controlWriteCall" to 0x120d2ecL,"controlClearMask" to clearMask,"controlSetMask" to setMask,
   "basis" to "DAT complete112-byte block writer and140-byte checksum/shadow consumer including two data literals; inherited265-word register2 contract. Native TEST qualifies halfwords/control transforms separately; retention/reset/readback/electrical identity are HYP, not hardware.")
 }
 fun verify(image:FirmwareImage,input:Map<*,*>):Map<String,Any> {
  require(image.metadata.id=="gfx100s-213"&&image.metadata.sha256=="ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12"){"power block requires qualified selected firmware"}
  require(input["profile"]==PowerMaskedRegisterContracts.PROFILE){"power block requires explicit masked peer"}
  val masks=input["write_masks"]as? Map<*,*>?:error("power block masks missing")
  val wanted=mapOf("2" to 4L)+(31..34).associate{it.toString() to 65535L}
  require(masks.keys==wanted.keys){"power block selected register set differs from DAT"}
  fun integer(value:Any?):Long{require(value is Byte||value is Short||value is Int||value is Long){"power block exact integer required"};return(value as Number).toLong()}
  require(masks.all{(key,value)->integer(value)==wanted[key]}){"power block selected masks differ from DAT"}
  val words=input["inputs"]as? Map<*,*>?:error("power block initial words missing")
  val initial=wanted.keys.associateWith{integer(words[it]).also{v->require(v in 0..65535){"power block initial word outside u16"}}}
  val old=PowerMaskedRegisterContracts.verify(image,input+mapOf("write_masks" to mapOf("2" to 4)))
  return observe(image.decode("section4"))+mapOf("selectedInitialWords" to initial,"selectedWriteMasks" to wanted,"register2Contract" to old,
   "modelLaw" to "Explicit HYP checksum-committed full-word retention/readback at31..34, existing masked bit2 only at2; no NVRAM/power/RTC/display coupling, physical reset, ready, persistence or changed runtime clock law.")
 }
}
