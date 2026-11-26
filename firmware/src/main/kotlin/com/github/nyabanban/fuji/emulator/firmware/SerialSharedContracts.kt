package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Selected-DAT shared-word geometry. It does not qualify a physical serial controller, IRQ, readiness or reset. */
object SerialSharedContracts {
 const val FIRMWARE_ID="gfx100s-213"
 const val FIRMWARE_SHA="ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12"
 const val SECTION_SHA="eb5372dfda41009b969ccf148f031b3976230a6a9d249d0ea3d0588e6feab157"
 const val PROFILE="gfx100s-serial-shared-functional-experimental-v1"
 const val FUNCTION_SHA="8c3dfc817be8bf5820be6493a9a43af6239d5a46972ce5f667e3b5072c80e4ba"
 fun port(group:Int):String {require(group in 0..1);return "soc-serial-engine.shared-"+group}
 private fun hash(bytes:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
 fun observe(app:ByteArray):Map<String,Any> {
  require(hash(app)==SECTION_SHA){"serial shared selected section identity differs"}
  val bytes=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN);val base=0x1021000L
  fun word(pc:Long):Long {require(pc%4L==0L&&pc>=base&&pc-base<=app.size-4L);return bytes.getInt((pc-base).toInt()).toLong()and 0xffffffffL}
  fun immediate(pc:Long):Long {val w=word(pc);return Integer.rotateRight((w and 255).toInt(),((w ushr 8)and 15).toInt()*2).toLong()and 0xffffffffL}
  fun branch(pc:Long):Long {val w=word(pc);require(w ushr 28==14L&&w and 0x0f000000L==0x0b000000L);return(pc+8+((w.toInt()shl 8)shr 6))and 0xffffffffL}
  val function=app.copyOfRange((0x11f9828L-base).toInt(),(0x11f989cL-base).toInt());require(hash(function)==FUNCTION_SHA)
  require(word(0x11f9828)==0xe3500007L&&word(0x11f9830)==0xe3510007L&&word(0x11f9834)==0x93520001L&&word(0x11f984c)==0xe3500004L&&word(0x11f9850)==0x93a04000L&&word(0x11f9854)==0x83a04001L&&word(0x11f9858)==0xe1a04604L&&word(0x11f985c)==0xe2444acfL&&word(0x11f9860)==0xe2444ef6L)
  require(word(0x11f9874)==0x01c01611L&&word(0x11f9878)==0x11801611L&&word(0x11f9888)==0xe8bd88f0L&&word(0x11f9898)==0xe12fff1eL)
  val channelCount=immediate(0x11f9828).toInt()+1;val bitCount=immediate(0x11f9830).toInt()+1;val enableMaximum=immediate(0x11f9834);val threshold=immediate(0x11f984c)
  val groupCount=immediate(0x11f9854).toInt()+1;val shift=((word(0x11f9858)ushr 7)and 31).toInt();val subtract=immediate(0x11f985c)+immediate(0x11f9860);val addresses=(0 until groupCount).map{((it.toLong()shl shift)-subtract)and 0xffffffffL}
  val mask=(1L shl bitCount)-1;require(channelCount==8&&bitCount==8&&enableMaximum==1L&&threshold==4L&&groupCount==2&&addresses==listOf(0xfff300a0L,0xfff310a0L)&&mask==255L&&branch(0x11f9868)==0x5afd8L&&branch(0x11f9880)==0x5af90L)
  return linkedMapOf("matches" to true,"entry" to 0x11f9828L,"functionBytes" to 116,"functionSha256" to hash(function),"section4Sha256" to hash(app),"channelCount" to channelCount,"bitCount" to bitCount,"enableMaximum" to enableMaximum,"firstGroupMaximumChannel" to threshold,"groupCount" to groupCount,"mutableMask" to mask,"windows" to addresses.mapIndexed{index,address->mapOf("port" to port(index),"address" to address,"size" to 4L)},"readHelper" to branch(0x11f9868),"writeHelper" to branch(0x11f9880),"basis" to "DAT116-byte routine including final invalid-parameter BX, unsigned domains, <=4 group selection, address arithmetic and low8-bit operation. Initial words/retention are explicit functional HYP; no physical reset, IRQ, ready, transport, scanout or menu claim.")
 }
 fun verify(meta:FirmwareMetadata,state:Map<String,Any?>,claims:List<String>,active:List<DeviceWindow>,application:()->ByteArray):Map<String,Any> {
  require(meta.id==FIRMWARE_ID&&meta.sha256==FIRMWARE_SHA){"serial shared profile is not qualified for selected firmware identity"}
  require(state["enabled"]==true&&state["profile"]in listOf(PROFILE,SerialStatusContracts.PROFILE,SerialTransmitContracts.PROFILE)&&state["closed"]==false&&state["mutableMask"]==255L){"serial shared live profile differs"}
  val groups=state["selectedGroups"];require(groups is List<*>&&groups.isNotEmpty()&&groups.size<=2&&groups.all{it is Int&&it in 0..1}){"serial shared explicit group state required"};val indices=groups.map{it as Int};require(indices==indices.distinct().sorted()){"serial shared ordered groups required"}
  for(key in listOf("initialWords","words")){val words=state[key];require(words is Map<*,*>&&words.keys==indices.map{it.toString()}.toSet()&&words.values.all{it==null||((it is Byte||it is Short||it is Int||it is Long)&&(it as Number).toLong()in 0..0xffffffffL)}){"serial shared word state differs"}}
  val statusChannels=if(state["profile"]!=PROFILE)SerialStatusContracts.channels(state)else emptyList()
  val transmitChannels=if(state["profile"]==SerialTransmitContracts.PROFILE)SerialTransmitContracts.selected(state)else emptyList()
  val expectedClaims=indices.map(::port)+statusChannels.map(SerialStatusContracts::port)+transmitChannels.map(SerialTransmitContracts::controlPort)+transmitChannels.map(SerialTransmitContracts::dataPort);require(claims==expectedClaims){"serial shared claims differ from selected groups"}
  val app=application();val report=observe(app);val expected=(report["windows"]as List<*>).map{val w=it as Map<*,*>;DeviceWindow("soc-serial-engine",w["address"]as Long,w["size"]as Long,w["port"]as String,true)}
  val declared=meta.devices.filter{it.id=="soc-serial-engine"};val expandedStatus=expected+SerialStatusContracts.windows();val expanded=expandedStatus+SerialTransmitContracts.windows();val allowed=if(transmitChannels.isNotEmpty())listOf(expanded)else if(statusChannels.isNotEmpty())listOf(expandedStatus,expanded)else listOf(expected,expandedStatus,expanded);require(allowed.any{declared.size==it.size&&declared.toSet()==it.toSet()}){"serial shared metadata differs from DAT words"}
  val selected=expanded.filter{it.port in expectedClaims};val actual=active.filter{it.id=="soc-serial-engine"};require(actual.size==selected.size&&actual.toSet()==selected.toSet()){"serial shared active windows differ from explicit claims"}
  return report+mapOf("selectedGroups" to indices,"selectedPorts" to claims.toList(),"profile" to state["profile"]!!)+(if(statusChannels.isEmpty())emptyMap()else mapOf("statusContract" to(SerialStatusContracts.observe(app)+mapOf("selectedChannels" to statusChannels))))+(if(transmitChannels.isEmpty())emptyMap()else mapOf("transmitContract" to(SerialTransmitContracts.observe(app)+mapOf("selectedChannels" to transmitChannels))))
 }
}
