package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** DAT-qualified secondary read pair and one buffer. Input bytes/RW-NX/zero backing are explicit HYP. */
object FlashReadPairContracts {
 const val KEY="flashReadPairScope"
 const val READ_SCOPE="dat-secondary-read-pair-experimental-v1"
 const val PROFILE="kernel-paired-flash-read-buffer-experimental-v14"
 private const val APP=0x1021000L
 private data class Span(val address:Long,val bytes:Int,val sha:String)
 private val spans=listOf(
  Span(0x119ec40,344,"c6db7490740fb5d5e226ce554f950cbe8fa5a096d845413f614d035a86be1d98"),
  Span(0x12a0774,148,"b65f54758e90b46d18f5285e1f04d61eef6f656cadd6b9731cf842ad41d2e713"),
  Span(0x12a08c0,160,"990906892506981d0092f86c9acfe2b833ab1481b68a2a15cda2fe15ada7da72"),
  Span(0x174782c,24,"312e2c1fac6bdb6d19f9f6a8e606e56d18cb5db5dac7528714ef8c29966627fb"),
  Span(0x11deb58,24,"326dd77a4c0af88dbc5edc8a28ffd96e2a4334c22cfa41cfd17a1fabe2ba5570"),
  Span(0x129dc10,136,"812c0b8ca58f8d5f767cc558d1e94f499fb7df9a442b1e56bffbd09d5c59157c"))
 val wordAddresses:List<Long> get()=spans.flatMap{(it.address until it.address+it.bytes step 4).toList()}
 fun selected(config:Map<*,*>):Boolean{if(!config.containsKey(KEY))return false;require(config[KEY] is String&&config[KEY]==READ_SCOPE){"invalid explicit secondary flash read scope"};return true}
 private fun qualified(image:FirmwareImage){require(image.metadata.id=="gfx100s-213"&&image.metadata.sha256=="ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12"){"secondary flash read requires qualified selected firmware"}}
 fun observe(app:ByteArray):Map<String,Any>{
  fun slice(pc:Long,size:Int):ByteArray{require(pc>=APP&&pc%4==0L&&size>0&&pc-APP+size<=app.size){"secondary flash read DAT span outside initialized app"};return app.copyOfRange((pc-APP).toInt(),(pc-APP+size).toInt())}
  fun word(pc:Long)=ByteBuffer.wrap(slice(pc,4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
  fun immediate(pc:Long):Long{val w=word(pc);return Integer.rotateRight((w and 255).toInt(),((w ushr 8 and 15)*2).toInt()).toLong() and 0xffffffffL}
  fun half(w:Long)=(w and 4095) or (w ushr 4 and 0xf000)
  fun pointer(pc:Long)=half(word(pc)) or (half(word(pc+4)) shl 16)
  fun branch(pc:Long)=pc+8+((word(pc).toInt() shl 8) shr 6)
  fun shift(pc:Long)=word(pc) ushr 7 and 31
  for(s in spans)require(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(slice(s.address,s.bytes)))==s.sha){"secondary flash read DAT span differs at "+s.address.toString(16)}
  val flag=pointer(0x12a077c);val table=pointer(0x12a07a8);val exponent=shift(0x12a07b4)
  val rows=listOf(table+(word(0x12a07bc) and 4095),table+(word(0x12a07ec) and 4095))
  require(flag==0x181b200L&&pointer(0x12a08c0)==flag&&table==0x17476e8L&&pointer(0x12a07d8)==table&&exponent==17L&&shift(0x12a07c0)==exponent&&shift(0x12a07e4)==exponent&&shift(0x12a07f0)==exponent&&shift(0x129dc88)==exponent)
  require(rows==listOf(0x174782cL,0x1747838L)&&table+(word(0x12a07b0) and 4095)==rows[0]+4&&table+(word(0x12a07e0) and 4095)==rows[1]+4)
  val partitions=rows.mapIndexed{mode,row->val count=word(row);val first=word(row+4);mapOf("mode" to mode,"rowAddress" to row,"firstBlock" to first,"countBlocks" to count,"thirdWordUnknown" to word(row+8),"offset" to(first shl exponent.toInt()),"length" to(count shl exponent.toInt()))}
  require(partitions.map{it["firstBlock"]}==listOf(506L,507L)&&partitions.all{it["countBlocks"]==1L&&it["thirdWordUnknown"]==3L})
  val base=immediate(0x119ec90) or (half(word(0x119ec94)) shl 16);val size=partitions.first().getValue("length")as Long;val global=pointer(0x119ec88);val aperture=immediate(0x11deb60)
  require(base==0x19082000L&&size==0x20000L&&global==0x186f884L&&aperture==0xf0000000L&&branch(0x119eca8)==0x12a0774L&&branch(0x119ecf4)==0x12a0774L&&branch(0x119ecb4)==0x12a08c0L&&branch(0x119ed08)==0x12a08c0L)
  require(shift(0x12a08dc)==exponent&&immediate(0x12a0944)==1L&&branch(0x12a0948)==0x129dc7cL&&branch(0x129dc90)==0x129dc10L&&branch(0x129dc50)==0x11deb58L&&branch(0x129dc64)==0x11dea0cL)
  return linkedMapOf("profile" to PROFILE,"readScope" to READ_SCOPE,"criticalWords" to wordAddresses.size,"initializedFlag" to flag,"table" to table,"partitions" to partitions,"logicalBlockShift" to exponent,
   "inputOffset" to 0x3f40000L,"inputLength" to 0x40000L,"inputEndExclusive" to 0x3f80000L,"base" to base,"byteSize" to size,"endExclusive" to base+size,"pointerGlobal" to global,
   "selectorEntry" to 0x12a0774L,"readerEntry" to 0x12a08c0L,"readerChild" to 0x129dc7cL,"readerAfter" to 0x12a094cL,"apertureBase" to aperture,"callerEntry" to 0x119ec40L,
   "basis" to "DAT complete selector/read-wrapper/two rows/generic block converter/aperture and full344-byte initial consumer. One-block buffer reused for primary/secondary. ThirdWord3, physical device/content/geometry unknown. RW-NX/zero backing and explicit finite input remain HYP.")
 }
 fun regions(inherited:List<MemoryRegion>,geometry:Map<String,Any>):List<MemoryRegion>{
  require(geometry["profile"]==PROFILE&&geometry["base"]==0x19082000L&&geometry["byteSize"]==0x20000L)
  val added=MemoryRegion("paired-flash-read-buffer",0x19082000,0x20000,3)
  require(inherited.none{it.id==added.id||it.address<added.address+added.size&&added.address<it.address+it.size}){"secondary flash read buffer overlaps inherited RAM"}
  return(inherited+added).sortedBy{it.address}
 }
 fun verifyMemory(image:FirmwareImage,selected:List<MemoryRegion>,v13:SegmentsContracts.VerifiedPlan):SegmentsContracts.VerifiedPlan{
  qualified(image);require(v13.report["profile"]==AuxiliaryTaskPairContracts.PROFILE&&v13.regions==image.metadata.memoryProfiles.getValue(AuxiliaryTaskPairContracts.PROFILE).sortedBy{it.address}){"secondary flash buffer requires verified selected v13"}
  val geometry=observe(image.decode("section4"));val plan=regions(v13.regions,geometry);require(selected.sortedBy{it.address}==plan){"selected secondary flash buffer differs from DAT or inherited RAM permissions"}
  return SegmentsContracts.VerifiedPlan(plan,v13.report+mapOf("profile" to PROFILE,"pairedFlashReadBuffer" to geometry,"regions" to plan.map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)},"basis" to "Verified v13 plus only DAT-derived19082000/20000 RW-NX zero backing HYP. No alias, physical DRAM geometry, parser validity or fault-driven mapping."))
 }
 fun verifyInputs(image:FirmwareImage,identities:List<ImageRegionIdentity>,mutable:WritableImageState?):Map<String,Any>{
  qualified(image);val p=observe(image.decode("section4"));val start=p.getValue("inputOffset")as Long;val size=p.getValue("inputLength")as Long
  require(identities.all{it.offset>=0&&it.length in 1..0x8000000L&&it.offset<=0x8000000L-it.length}){"secondary flash source identity bounds differ"}
  require(mutable==null||mutable.writableOffset>=0&&mutable.writableLength in 1..0x8000000L&&mutable.writableOffset<=0x8000000L-mutable.writableLength&&!mutable.pending&&!mutable.closed&&mutable.failure==null){"secondary flash mutable lifecycle/bounds differ"}
  val matching=identities.filter{it.offset<start+size&&start<it.offset+it.length}
  require(matching.size==1&&matching.single().let{it.offset==start&&it.length==size&&it.sha256.matches(Regex("[0-9a-f]{64}"))&&it.relativeFile.isNotBlank()}){"secondary flash read requires one exact explicit506/507 input"}
  require(mutable==null||mutable.writableOffset>=start+size||mutable.writableOffset+mutable.writableLength<=start){"secondary flash read input must remain outside writable permission"}
  return p+mapOf("selectedInput" to matching.single(),"scope" to "Explicit immutable source only, not a successful DMA/accepted flash request or camera dump. Destination profile qualified independently; no automatic media/RAM expansion or relaxed writes.")
 }
}
