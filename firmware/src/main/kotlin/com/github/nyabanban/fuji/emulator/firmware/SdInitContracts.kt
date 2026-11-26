package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Selected DAT software fields; SOURCE names are candidates, input values/retention remain HYP. */
object SdInitContracts {
 const val PROFILE="gfx100s-sd-host-init-registers-experimental-v4"
 const val PHY_PROFILE="gfx100s-sd-host-phy-word-retention-experimental-v5"
 val PHY_SPAN=SdHostContracts.Span(0x11f1cd8,312,"141b7a3e56eaa98db53e57f73fb55a321929ee426b8f085c10fe8d15cae8767d",false)
 val fields=listOf("capability6244","present4024","present6224","signal6238","block4006","host6228","host4028")
 val widths=listOf(4,4,4,2,2,2,2)
 val addedSpans=listOf(
  SdHostContracts.Span(0x11f0818,108,"83086a91b4f164b41ed17a38a3ab746bc3b9708666723253cfc69e88c955dcf9",false),
  SdHostContracts.Span(0x11f09cc,156,"f180cccbff84aee72ddecf463f1a499352ff960d0746dc11639ccdba6de56c11",false),
  SdHostContracts.Span(0x11f1ec8,248,"1ba1f92243573258144aad088f9e078039df32bda83788d0974936034335f4a7",false))
 val spans=SdClockContracts.spans+addedSpans
 fun initWindows()=(0..1).flatMap{slot->listOf(0xfffc6244L,0xfffc4024L,0xfffc6224L,0xfffc6238L,0xfffc4006L,0xfffc6228L,0xfffc4028L).mapIndexed{field,address->DeviceWindow("soc-sd-host",address+slot*0x4000,widths[field].toLong(),"soc-sd-host."+fields[field]+"-"+slot,true)}}
 fun windows()=SdClockContracts.windows()+initWindows()
 private fun hash(b:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
 @JvmOverloads
 fun observe(app:ByteArray,kernel:ByteArray,profile:String=PROFILE):Map<String,Any>{
  require(profile in setOf(PROFILE,PHY_PROFILE))
  val prior=SdClockContracts.observe(app,kernel,if(profile==PHY_PROFILE)profile else SdClockContracts.PROFILE)
  val selectedSpans=if(profile==PHY_PROFILE)spans+PHY_SPAN else spans
  val reports=selectedSpans.map{s->val code=if(s.kernel)kernel else app;val base=if(s.kernel)0x40000L else 0x1021000L;val at=(s.start-base).toInt();require(at>=0&&at<=code.size-s.size);val bytes=code.copyOfRange(at,at+s.size);require(hash(bytes)==s.sha256){"SD init DAT span differs"};mapOf("address" to s.start,"bytes" to s.size,"sha256" to hash(bytes))}
  fun word(pc:Long)=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN).getInt((pc-0x1021000L).toInt()).toLong()and 0xffffffffL
  require(word(0x11f1ee4)==0xe7e001d0L&&word(0x11f1f94)==0xe3130702L&&word(0x11f1f08)==0xe6ff1071L&&word(0x11f1f28)==0xe6ff1071L&&word(0x11f0868)==0xe7cf3692L&&word(0x11f0a04)==0xe7cb3495L&&word(0x11f0a4c)==0xe7c23115L)
  return prior+mapOf("profile" to profile,"criticalWords" to selectedSpans.sumOf{it.size/4},"spans" to reports,"initWindows" to initWindows().map{mapOf("port" to it.port,"address" to it.address,"size" to it.size)},"capabilityBit" to 3,"presentBit" to 19,"signalTable" to 0x18743ecL,"limitation" to if(profile==PHY_PROFILE)"DAT complete PHY consumer and explicit word-write profile; u16 lane/u32 retention remain HYP. No physical reset/ready, contacts, card protocol, IRQ or DMA." else "DAT software accesses/native fixtures only; SOURCE SDHCI/UHS-II naming is not measured GFX100S inputs. Explicit readonly values and u16 retention HYP, no contacts, card protocol, IRQ or DMA.")
 }
 @Suppress("UNCHECKED_CAST")
 fun verify(image:FirmwareImage,state:Map<String,Any?>,claims:List<String>,active:List<DeviceWindow>):Map<String,Any> = verifyDeclared(image,state,claims,active,image.metadata.devices.filter{it.id=="soc-sd-host"})
 internal fun verifyDeclared(image:FirmwareImage,state:Map<String,Any?>,claims:List<String>,active:List<DeviceWindow>,declared:List<DeviceWindow>):Map<String,Any>{
  require(image.metadata.id=="gfx100s-213"&&image.metadata.sha256==SdHostContracts.FIRMWARE_SHA)
  val profile=state["profile"] as? String ?: error("SD init profile missing")
  require(profile in setOf(PROFILE,PHY_PROFILE)&&state["enabled"]==true&&state["closed"]==false&&state["cardProtocolQualified"]==false)
  val expected=windows();val selected=active.filter{it.id=="soc-sd-host"}
  require(claims==expected.map{it.port}&&declared.size==28&&declared.toSet()==expected.toSet()&&selected.size==28&&selected.toSet()==expected.toSet())
  val clock=(state["clockState"] as? Map<*, *>)?.takeIf { m -> m.keys.all { it is String } }?.entries?.associate { (k, v) -> k as String to v } ?: error("Clock projection missing")
  val oldPorts=SdClockContracts.windows().map{it.port};SdClockContracts.verifyDeclared(image,clock,oldPorts,active.filter{it.port in oldPorts},if(profile==PHY_PROFILE)profile else SdClockContracts.PROFILE,declared)
  val initial=state["initialInitWords"]as? Map<*,*>?:error("Init inputs missing");require(initial.keys==setOf("0","1")&&state["initWords"]==initial)
  for(slot in 0..1){val row=initial[slot.toString()]as? Map<*,*>?:error("Init slot missing");require(row.keys==fields.toSet());for((i,key)in fields.withIndex()){val v=row[key];require(v is Byte||v is Short||v is Int||v is Long);require((v as Number).toLong()in 0..(if(widths[i]==2)65535L else 0xffffffffL))}}
  require(listOf("initReadCount","initWriteCount","initAccessCount").all{(state[it]as? Number)?.toLong()==0L})
  return observe(image.decode("section4"),image.decode("kernel"),profile)+mapOf("inputIdentityVerified" to true,"inputSha256" to image.metadata.sha256,"initialInitWords" to initial)
 }
}
