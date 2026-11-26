package com.github.nyabanban.fuji.emulator.firmware
import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** DAT-qualified v4 configuration. Page/bit/accept/array timing rules remain explicit HYP. */
object FlashProgramContracts {
 const val PROFILE="gfx100s-program-dma-functional-experimental-v4"
 const val IMAGE_PROFILE="explicit-copy-on-write-program-experimental-v2"
 const val PROGRAM_LAW="strict-one-to-zero-no-page-wrap-experimental-v1"
 const val ROUTES_PROFILE="named-destination-ports-experimental-v1"
 private const val APP=0x1021000L
 private data class Span(val address:Long,val bytes:Int,val sha:String)
 private val spans=listOf(
  Span(0x11e02a4,236,"f2abf34c2e2cc36d6a3dcc89682fdf6d3366d3e44222e6d6a92f973d9c1954d0"),
  Span(0x11df01c,260,"a954e96bad7a6115dec7deda5a80ac790ac549b20c58bed5e3c522fc3b1cf882"),
  Span(0x11dde70,292,"7a3ebc68bff06266c6b48625bd2bafa2fdaa869b9fa7b16a1c4c4d4ba3da44a2"),
  Span(0x18122bc,28,"9950910c35818b7626aa41430d698cec77a78b690d8e2a3100198b39b7ac3207"),
  Span(0x11dd524,56,"b82a08558040083c8ab6de7b612e7957c7f76e21b7319aa9823624d73a36bb22"),
  Span(0x11dd55c,580,"51f464f8653d87d68d2f2de79e9890e1ab2216f81aa3caefcd343084885bd9d4"),
  Span(0x11e6244,128,"89668474649b87c721d11cecba07798b3719eb1dae94c7e4698bc204116459cb"),
  Span(0x15a80f8,24,"13bbff38549eca7f7afe98d57a1cc1eeef683d519403e2d8fd00aaea13ce9d9a"),
  Span(0x11e4330,704,"a7774f2f289951d1eb3c429fe08aa6f3aaf4f31c65ee417f04bfa9bc16d5c962"))
 val wordAddresses:List<Long> get()=spans.flatMap{(it.address until it.address+it.bytes step 4).toList()}
 private fun integer(v:Any?):Long {require(v is Byte||v is Short||v is Int||v is Long);return(v as Number).toLong()}
 fun observe(app:ByteArray):Map<String,Any> {
  fun slice(pc:Long,n:Int):ByteArray{require(pc>=APP&&pc%4==0L&&n>0&&pc-APP+n<=app.size);return app.copyOfRange((pc-APP).toInt(),(pc-APP+n).toInt())}
  fun word(pc:Long)=ByteBuffer.wrap(slice(pc,4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
  fun immediate(pc:Long):Long{val w=word(pc);return Integer.rotateRight((w and 255).toInt(),((w ushr 8 and 15)*2).toInt()).toLong() and 0xffffffffL}
  fun half(w:Long)=(w and 4095) or (w ushr 4 and 0xf000)
  fun pointer(pc:Long)=(if(word(pc) and 0x0ff00000L==0x03000000L)half(word(pc))else immediate(pc)) or (half(word(pc+4)) shl 16)
  fun branch(pc:Long)=pc+8+((word(pc).toInt() shl 8) shr 6)
  for(s in spans)require(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(slice(s.address,s.bytes)))==s.sha){"flash program DAT differs at "+s.address.toString(16)}
  val base=pointer(0x11e02c0);val address=pointer(0x11e02f0);val count=pointer(0x11e0320);val control=pointer(0x11e033c);val statusBit=immediate(0x11e0368);val startBit=immediate(0x11e034c)
  val geometry=half(word(0x11e030c));val requestBase=pointer(0x11e6250);val requestStride=immediate(0x11e6258);val index=immediate(0x11dd584);val row=requestBase+requestStride*index;val selector=app[(row-APP+1).toInt()].toInt() and 255
  require(base==0xfffd8000L&&address==base+0x78&&count==base+0x7c&&control==base+0x70&&statusBit==32L&&startBit==1L&&immediate(0x11e0370)==32L&&geometry==0x202L)
  require(requestBase==0x15a7fe4L&&requestStride==12L&&index==23L&&row==0x15a80f8L&&selector==43&&branch(0x11dd588)==0x11dd524L&&branch(0x11dd530)==0x11e6244L&&branch(0x11dd538)==0x11e4330L)
  val tablePointer=pointer(0x11df024);val record=0x18122bcL;val opcode=word(record);val commandOffset=word(0x11df094) and 4095;val validOffset=word(0x11df030) and 4095
  require(tablePointer==0x1870d28L&&opcode==0x12L&&commandOffset==308L&&validOffset==332L&&record==FlashPioEnableContracts.TABLE_ADDRESS+commandOffset&&word(record+12)==4L&&word(record+24)==1L)
  require(branch(0x11ddf34)==0x11dd55cL&&branch(0x11dd5d4)==0x11e45f0L&&branch(0x11dd62c)==0x11e48e0L&&branch(0x11dd750)==0x11e02a4L&&branch(0x11ddf10)==0x11df01cL)
  val software=pointer(0x11dde8c);val pageField=software+(word(0x11ddf28) and 4095)
  require(software==0x1870cd0L&&pageField==0x1870ce0L)
  return linkedMapOf("profile" to PROFILE,"criticalWords" to wordAddresses.size,"controllerBase" to base,"targetAddressRegister" to address,"byteCountRegister" to count,"controlRegister" to control,"acceptBit" to statusBit,"startBit" to startBit,"programGeometry" to geometry,"programOpcode" to opcode,"descriptorIndex" to 11,"descriptorAddress" to record,"descriptorPointer" to tablePointer,"softwarePageField" to pageField,"softwareRequestIndex" to index,"requestRecord" to row,"requestRecordStride" to requestStride,"muxRequest" to selector,"starter" to 0x11e02a4L,"starterAfter" to 0x11dd754L,"basis" to "DAT complete controller starter, descriptor setup, page loop, row11 and transfer wrapper; request lookup/constructor spans include bounded adjacent code. Software field256 is separately observed SIM, not physical NOR/page measurement. Explicit HYP storage law and timing required.")
 }
 fun verify(image:FirmwareImage,flash:Map<String,Any?>,dma:Map<String,Any?>,imageState:Map<String,Any?>,copy:WritableImageState,regions:List<ImageRegionIdentity>,tailProfile:String,routes:Map<*,*>,program:ProgrammableImagePort?,peers:Map<String,DmaDestinationPeer?>):Map<String,Any> {
  require(flash["profile"]==PROFILE&&image.metadata.id=="gfx100s-213"&&image.metadata.sha256=="ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12"){"program requires selected qualified firmware/profile"}
  require(imageState["mutationProfile"]==IMAGE_PROFILE&&imageState["programLaw"]==PROGRAM_LAW&&integer(imageState["programPageBytes"])==256L&&imageState["programHistory"]==emptyList<Any>()&&program!=null){"program requires explicit matching optional storage capability/law"}
  val p=flash["program"] as? Map<*,*> ?: error("program state missing")
  require(integer(p["commandTicks"])==8L&&integer(p["arrayTicksAfterObservedDma"])==4096L&&integer(p["committed"])==0L&&p.containsKey("failure")&&p["failure"]==null&&p["closed"]==false&&p.containsKey("pending")&&p["pending"]==null&&!copy.pending&&!copy.closed&&copy.failure==null){"program already consumed or unsupported timing/lifecycle"}
  require(dma["profile"]=="standalone-experimental-v1"&&dma["memoryPort"]=="cpu.memory"&&dma["flashPeerPort"]=="soc-flash.dma"&&dma["transmitPeerPort"]=="soc-serial-engine.tx-1"&&(dma["destinationPeersFile"] as? String)?.isNotBlank()==true){"program DMA selection differs"}
  require(routes.keys==setOf("schema","profile","ports")&&integer(routes["schema"])==1L&&routes["profile"]==ROUTES_PROFILE&&(routes["ports"]==listOf("soc-flash.tx")||routes["ports"]==listOf("soc-flash.tx","soc-isp-setup.i2s0-dma-tx","soc-isp-setup.i2s1-dma-tx"))){"program routes differ from qualified flash and serial pair"}
  require(peers.keys==setOf("soc-flash.tx","soc-serial-engine.tx-1")&&peers.values.all{it is RangedDmaDestinationPeer}&&peers.values.toList()[0]!==peers.values.toList()[1]){"program destination missing/alias/range capability"}
  val legacy=FlashEraseContracts.verify(image,flash+mapOf("profile" to FlashEraseContracts.PROFILE),copy,regions,tailProfile)
  return observe(image.decode("section4"))+mapOf("eraseAncestor" to legacy,"programLaw" to PROGRAM_LAW,"pageBytesHypothesis" to 256,"commandTicksHypothesis" to 8,"arrayTicksAfterObservedDmaHypothesis" to 4096,"routes" to routes,"scope" to "Explicit v4 qualification plus unchanged v3 erase-window/identity/seed ancestry, before native allocation. Ancestor profile view is for static checks only, never device/guest mutation. No accepted DMA, program array or physical law assertion.")
 }
}
