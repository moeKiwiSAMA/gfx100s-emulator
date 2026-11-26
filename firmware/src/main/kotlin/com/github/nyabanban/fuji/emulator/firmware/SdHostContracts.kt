package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Selected-DAT exact word80 geometry, not a SD protocol/reset specification. */
object SdHostContracts {
 const val CONTROL_PROFILE="gfx100s-sd-host-controls-retention-experimental-v2"
 const val PROFILE="gfx100s-sd-host-word80-retention-experimental-v1"
 const val FIRMWARE_SHA="ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12"
 data class Span(val start:Long,val size:Int,val sha256:String,val kernel:Boolean)
 val spans=listOf(Span(0x11f0120,652,"f368582776b9b83949d3f2ca192a524429faa52c2ffafcda6414364dde1ff74d",false),Span(0x5af90,96,"01f36fa410ee9efd16803734623320eed762cccd5b6cb1e0a4e4d64b0655658c",true))
 fun port(slot:Int):String {require(slot in 0..1);return "soc-sd-host.word80-"+slot}
 fun windows()=(0..1).map{DeviceWindow("soc-sd-host",0xfffc6080L+it*0x4000,4,port(it),true)}
 val controlSpans=spans+listOf(Span(0x11f0b3c,356,"aaffdefd7ae26cf09a747823f53c0c55537ac290875e02e4e80e9018b36fd694",false),Span(0x5aff0,24,"18cebc532f9f3653af3d62e0c67fbad8fcae0f638dc364da3a33e29042408d32",true))
 val controlFields=listOf("word80","control623e","mixed6078","control403e","word60ec")
 fun controlWindows():List<DeviceWindow> = windows()+(0..1).flatMap{slot->listOf(
  DeviceWindow("soc-sd-host",0xfffc623eL+slot*0x4000,2,"soc-sd-host.control623e-"+slot,true),
  DeviceWindow("soc-sd-host",0xfffc6078L+slot*0x4000,4,"soc-sd-host.mixed6078-"+slot,true),
  DeviceWindow("soc-sd-host",0xfffc403eL+slot*0x4000,2,"soc-sd-host.control403e-"+slot,true),
  DeviceWindow("soc-sd-host",0xfffc60ecL+slot*0x4000,4,"soc-sd-host.word60ec-"+slot,true))}
 @JvmOverloads
 fun observeControls(app:ByteArray,kernel:ByteArray,profile:String=CONTROL_PROFILE):Map<String,Any> {
  require(profile in setOf(CONTROL_PROFILE,SdInitContracts.PHY_PROFILE))
  val selectedSpans=if(profile==SdInitContracts.PHY_PROFILE)controlSpans+SdInitContracts.PHY_SPAN else controlSpans
  val legacy=observe(app,kernel)
  val reports=selectedSpans.map{s->val code=if(s.kernel)kernel else app;val base=if(s.kernel)0x40000L else 0x1021000L;val at=(s.start-base).toInt();require(at>=0&&at<=code.size-s.size);val bytes=code.copyOfRange(at,at+s.size);require(hash(bytes)==s.sha256){"SD controls DAT span differs"};mapOf("address" to s.start,"bytes" to s.size,"sha256" to hash(bytes))}
  return legacy+(if(profile==SdInitContracts.PHY_PROFILE)mapOf("mixed6078WriteWidths" to listOf(2,4))else emptyMap())+mapOf("profile" to profile,"criticalWords" to selectedSpans.sumOf{it.size/4},"spans" to reports,"windows" to controlWindows().map{mapOf("port" to it.port,"address" to it.address,"size" to it.size)},"limitation" to "Pinned full DAT routines; software widths/bit operations native-tested separately. Explicit inputs and retention HYP; no physical identity/ready/card protocol.")
 }
 private fun hash(b:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
 fun observe(app:ByteArray,kernel:ByteArray):Map<String,Any> {
  val reports=spans.map{s->val code=if(s.kernel)kernel else app;val base=if(s.kernel)0x40000L else 0x1021000L;val at=(s.start-base).toInt();require(at>=0&&at<=code.size-s.size);val bytes=code.copyOfRange(at,at+s.size);require(hash(bytes)==s.sha256){"SD host DAT span differs"};mapOf("address" to s.start,"bytes" to s.size,"sha256" to hash(bytes))}
  fun word(pc:Long):Long {val code=if(pc<0x100000L)kernel else app;val base=if(pc<0x100000L)0x40000 else 0x1021000;return ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN).getInt((pc-base).toInt()).toLong()and 0xffffffffL}
  fun immediate(pc:Long):Long {val w=word(pc);return Integer.rotateRight((w and 255).toInt(),((w ushr 8)and 15).toInt()*2).toLong()and 0xffffffffL}
  require(word(0x11f01f0)==0xe1a05704L&&word(0x11f01f4)==0xe2455be7L&&word(0x11f01f8)==0xe2455d0eL&&word(0x11f0204)==0xe3801c0fL&&word(0x5afe0)==0xe5900000L&&word(0x5af98)==0xe5801000L)
  val shift=((word(0x11f01f0) ushr 7)and 31).toInt();val offset=(0L-immediate(0x11f01f4)-immediate(0x11f01f8))and 0xffffffffL;val mask=immediate(0x11f0204)
  require(shift==14&&offset==0xfffc6080L&&mask==0xf00L)
  return mapOf("profile" to PROFILE,"criticalWords" to spans.sumOf{it.size/4},"spans" to reports,"slotCount" to 2,"slotShift" to shift,"readWidth" to 4,"writeWidth" to 4,"orMask" to mask,"windows" to windows().map{mapOf("port" to it.port,"address" to it.address,"size" to it.size)},"limitation" to "DAT software geometry only; explicit initial values/full-word retention HYP, physical reset/bit meanings/SD protocol unknown")
 }
 fun verify(image:FirmwareImage,state:Map<String,Any?>,claims:List<String>,active:List<DeviceWindow>):Map<String,Any> {
  require(image.metadata.id=="gfx100s-213"&&image.metadata.sha256==FIRMWARE_SHA)
  if(state["profile"] in setOf(SdTransactionContracts.PROFILE,SdTransactionContracts.ADDRESS_PROFILE,SdTransactionContracts.VENDOR_PHY_PROFILE,SdTransactionContracts.UHS_PHY_PROFILE))return SdTransactionContracts.verify(image,state,claims,active)
  if(state["profile"] in setOf(SdInitContracts.PROFILE,SdInitContracts.PHY_PROFILE))return SdInitContracts.verify(image,state,claims,active)
  if(state["profile"]==SdClockContracts.PROFILE)return SdClockContracts.verify(image,state,claims,active)
  if(state["profile"]==CONTROL_PROFILE)return verifyControls(image,state,claims,active)
  require(state["profile"]==PROFILE&&state["enabled"]==true&&state["closed"]==false&&state["cardProtocolQualified"]==false)
  val expected=windows();require(claims==expected.map{it.port}&&validCatalog(image.metadata.devices.filter{it.id=="soc-sd-host"},false)&&active.filter{it.id=="soc-sd-host"}.toSet()==expected.toSet()&&active.count{it.id=="soc-sd-host"}==2)
  val initial=state["initialWords"]as? Map<*,*>?:error("SD host words missing");require(initial.keys==setOf("0","1")&&initial.values.all{it is Byte||it is Short||it is Int||it is Long});require(initial.values.all{(it as Number).toLong()in 0..0xffffffffL}&&state["words"]==initial)
  require(listOf("readCount","writeCount","accessCount").all{(state[it]as? Number)?.toLong()==0L})
  return observe(image.decode("section4"),image.decode("kernel"))+mapOf("inputIdentityVerified" to true,"inputSha256" to image.metadata.sha256,"initialWords" to initial)
 }
 private fun validCatalog(declared:List<DeviceWindow>,controls:Boolean):Boolean {
  val catalogs=if(controls)listOf(controlWindows(),SdClockContracts.windows(),SdInitContracts.windows())else listOf(windows(),controlWindows(),SdClockContracts.windows(),SdInitContracts.windows())
  return catalogs.any{declared.size==it.size&&declared.toSet()==it.toSet()}
 }
 internal fun verifyControls(image:FirmwareImage,state:Map<String,Any?>,claims:List<String>,active:List<DeviceWindow>,expectedProfile:String=CONTROL_PROFILE,declared:List<DeviceWindow> = image.metadata.devices.filter{it.id=="soc-sd-host"}):Map<String,Any> {
  require(expectedProfile in setOf(CONTROL_PROFILE,SdInitContracts.PHY_PROFILE) && state["profile"]==expectedProfile) { "SD control profile projection differs" }
  require(state["enabled"]==true&&state["closed"]==false&&state["cardProtocolQualified"]==false)
  val expected=controlWindows();require(claims==expected.map{it.port}&&validCatalog(declared,true)&&active.filter{it.id=="soc-sd-host"}.size==10&&active.filter{it.id=="soc-sd-host"}.toSet()==expected.toSet())
  val initial=state["initialRegisters"]as? Map<*,*>?:error("SD controls missing");require(initial.keys==setOf("0","1"))
  for(slot in 0..1){val fields=initial[slot.toString()]as? Map<*,*>?:error("SD slot missing");require(fields.keys==controlFields.toSet());for((key,value)in fields){require(value is Byte||value is Short||value is Int||value is Long);require((value as Number).toLong()in 0..(if(key=="control623e"||key=="control403e")0xffffL else 0xffffffffL))}}
  require(state["registers"]==initial&&listOf("readCount","writeCount","accessCount").all{(state[it]as? Number)?.toLong()==0L})
  return observeControls(image.decode("section4"),image.decode("kernel"),expectedProfile)+mapOf("inputIdentityVerified" to true,"inputSha256" to image.metadata.sha256,"initialRegisters" to initial)
 }
}
