package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** DAT half-pair504/505 plus unchanged494..497 permission. Not physical purpose or geometry. */
object FlashWritableSetContracts {
 const val KEY="flashWritableSetScope"
 const val PROFILE="dat-secondary504-write-set-experimental-v1"
 const val PROFILE_OCTET="dat-bkup-octet-secondary504-write-set-experimental-v2"
 const val IMAGE_PROFILE="explicit-disjoint-copy-on-write-program-experimental-v3"
 private const val APP=0x1021000L
 private data class Span(val pc:Long,val bytes:Int,val sha:String)
 private val spans=listOf(
  Span(0x129f990,76,"430509a08754870ac106deb01f5d7c45d93a9716784284d7420e67aa2149b0e2"),
  Span(0x1747820,12,"4b9c7abe2b2b858f6039a12f35f228fbfb82ca4d442149ac54ac441e314dc55e"),
  Span(0x129df0c,84,"8d505bd6aaa2cf0a6f03753b2a65922f6fa070db1baea1f49f1a2b098884f1f7"),
  Span(0x11deb58,24,"326dd77a4c0af88dbc5edc8a28ffd96e2a4334c22cfa41cfd17a1fabe2ba5570"),
  Span(0x12a2c1c,280,"e86cf45e35e9d833f8ee573ba1de41ba51744cbbf51a5e0f48530a14b4904864"),
  Span(0x129dcfc,104,"0f220108c2abf6bc26e7a4996b8822b8343f0ca4e3994d2a86d707d92ceb06df"))
 val wordAddresses:List<Long> get()=spans.flatMap{(it.pc until it.pc+it.bytes step 4).toList()}
 fun selected(config:Map<String,*>):Boolean {if(!config.containsKey(KEY))return false;require(config[KEY] in listOf(PROFILE,PROFILE_OCTET)){"flashWritableSetScope requires exact explicit profile"};return true}
 fun observe(app:ByteArray):Map<String,Any>{
  fun slice(pc:Long,n:Int):ByteArray{require(pc>=APP&&pc%4==0L&&n>0&&pc-APP+n<=app.size);return app.copyOfRange((pc-APP).toInt(),(pc-APP+n).toInt())}
  fun word(pc:Long)=ByteBuffer.wrap(slice(pc,4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong()and 0xffffffffL
  fun half(w:Long)=(w and 4095)or(w ushr 4 and 0xf000)
  fun pointer(pc:Long)=half(word(pc))or(half(word(pc+4))shl 16)
  fun immediate(pc:Long):Long{val w=word(pc);return Integer.rotateRight((w and 255).toInt(),((w ushr 8 and 15)*2).toInt()).toLong()and 0xffffffffL}
  fun branch(pc:Long)=pc+8+((word(pc).toInt()shl 8)shr 6)
  for(s in spans)require(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(slice(s.pc,s.bytes)))==s.sha){"secondary write DAT differs at "+s.pc.toString(16)}
  val flag=pointer(0x129f990);val table=pointer(0x129f9b0);val row=table+(word(0x129f9b8)and 4095);val count=word(row);val shift=word(0x129f9bc)ushr 7 and 31;val first=word(row+4);val blockShift=word(0x129df38)ushr 7 and 31;val aperture=immediate(0x11deb60)
  require(flag==0x181b200L&&table==0x17476e8L&&row==0x1747820L&&count==2L&&shift==1L&&first==504L&&word(row+8)==3L&&blockShift==17L&&aperture==0xf0000000L)
  require(table+(word(0x129f9c0)and 4095)==row+4&&word(0x129f9c8)==0xe0202093L&&immediate(0x129f99c)==1L&&branch(0x129f9cc)==0x129df0cL&&branch(0x129df48)==0x11dea40L&&branch(0x12a2d00)==0x129f990L&&word(0x12a2cb4)==0x12a2cfcL)
  val perMode=count ushr shift.toInt();val start=first shl blockShift.toInt();val length=count shl blockShift.toInt()
  return linkedMapOf("profile" to PROFILE,"criticalWords" to wordAddresses.size,"selectorEntry" to 0x129f990L,"initializedFlag" to flag,"table" to table,"rowAddress" to row,"totalBlocks" to count,"blocksPerMode" to perMode,"firstBlock" to first,"halfCountShift" to shift,"logicalBlockShift" to blockShift,"apertureBase" to aperture,"writableOffset" to start,"writableLength" to length,"endExclusive" to start+length,"dispatchCase" to 4,"thirdWordUnknown" to word(row+8),"partitions" to(0..1).map{mode->mapOf("mode" to mode,"firstBlock" to first+perMode*mode,"countBlocks" to perMode,"offset" to((first+perMode*mode)shl blockShift.toInt()),"length" to(perMode shl blockShift.toInt()))},"basis" to "Complete DAT half-pair selector, row2/504/3, block/aperture consumer and parentcase4. Thirdword3, purpose/content/physical geometry unknown. Explicit disjoint copies, no fault-driven source/file/range growth.")
 }
 private val spans506=listOf(
  Span(0x12a0960,104,"6e6bdec333875b0e6114e4d8b3f8da6f916e9a64b1a2a54e69f3856e23773449"),
  Span(0x174782c,24,"312e2c1fac6bdb6d19f9f6a8e606e56d18cb5db5dac7528714ef8c29966627fb"))
 private val span508=Span(0x1747850,12,"ac66088673e757f3aa7304946b37af0eb810726cd8a07f8ec0a9cd728e4ddc5f")
 private val spanPart12Part13=Span(0x1747778,24,"12d73551a0774dbd62bd20037998a026b11b6e42d3967d3578a87d4461191bdf")
 private fun verifySpan(app:ByteArray,s:Span,label:String) {
  val slice=app.copyOfRange((s.pc-APP).toInt(),(s.pc-APP+s.bytes).toInt())
  require(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(slice))==s.sha){"$label write DAT differs at "+s.pc.toString(16)}
 }
 fun verify(image:FirmwareImage,set:WritableImageSetPort,state:Map<String,Any?>,regions:List<ImageRegionIdentity>,profile:String=PROFILE):Map<String,Any>{
  require(profile in listOf(PROFILE,PROFILE_OCTET)){"unsupported flashWritableSetScope profile"}
  require(image.metadata.id=="gfx100s-213"&&image.metadata.sha256=="ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12")
  val app=image.decode("section4")
  val plan=observe(app);val copies=set.regionSnapshots();val primary=set.snapshot()
  val expectedCount=if(profile==PROFILE_OCTET) copies.size.coerceIn(2,6) else 2
  require(copies.size==expectedCount&&copies.map{it.regionId}.distinct().size==copies.size&&set.primaryRegionId==primary.regionId&&copies.single{it.regionId==set.primaryRegionId}==primary)
  val others=copies.filter{it.regionId!=set.primaryRegionId}.sortedBy{it.offset}
  val beforePrimary=others.filter{it.offset<primary.offset}
  val afterPrimary=others.filter{it.offset>primary.offset}
  require(beforePrimary.size+afterPrimary.size==others.size){"writable set overlaps primary domain"}
  val lensCachePair=if(beforePrimary.isNotEmpty()){
   require(profile==PROFILE_OCTET&&beforePrimary.size==2){"pre-primary writable regions require exact DAT part-12 + part-13 lens cache pair under PROFILE_OCTET"}
   verifySpan(app,spanPart12Part13,"part12Part13")
   val p12=beforePrimary[0];val p13=beforePrimary[1]
   require(p12.offset==0x2fe0000L&&p12.length==0x500000L&&p12.writableOffset==0x2fe0000L&&p12.writableLength==0x500000L&&p13.offset==0x34e0000L&&p13.length==0x20000L&&p13.writableOffset==0x34e0000L&&p13.writableLength==0x20000L&&p13.offset+p13.length<=primary.offset){"lens cache write set requires exact DAT part-12 (0x02FE0000, 5 MiB) and part-13 (0x034E0000, 128 KiB) domains"}
   p12 to p13
  } else null
  require(afterPrimary.size in 1..3){"secondary write set requires 1..3 post-primary domains"}
  val secondary=afterPrimary.first();val start=plan.getValue("writableOffset")as Long;val size=plan.getValue("writableLength")as Long
  if(profile==PROFILE_OCTET) FlashPartitionOctetContracts.verify(image,primary) else FlashPartitionQuartetContracts.verify(image,primary)
  require(primary.offset==0x3d40000L&&primary.length==0x100000L&&secondary.offset==start&&secondary.length==size&&secondary.writableOffset==start&&secondary.writableLength==size&&primary.offset+primary.length<=secondary.offset){"secondary write set requires exact primary domain490-497 and504/505 secondary"}
  val tertiary=if(afterPrimary.size>=2){
   spans506.forEach{verifySpan(app,it,"secondary506")}
   val t=afterPrimary[1]
   require(t.offset==0x3f40000L&&t.length==0x40000L&&t.writableOffset==0x3f40000L&&t.writableLength==0x40000L&&secondary.offset+secondary.length<=t.offset){"tertiary write set requires exact DAT506/507 domain"}
   t
  } else null
  val quaternary=if(afterPrimary.size>=3){
   verifySpan(app,span508,"tail508")
   val q=afterPrimary[2]
   require(q.offset==0x3f80000L&&q.length==0x20000L&&q.writableOffset==0x3f80000L&&q.writableLength==0x20000L&&checkNotNull(tertiary).offset+tertiary.length<=q.offset){"quaternary write set requires exact DAT508 domain"}
   q
  } else null
  val aggregate=state["writableRegionSet"]as? Map<*,*>?:error("writable region-set state missing")
  require(state["mutationProfile"]==IMAGE_PROFILE&&aggregate["profile"]==IMAGE_PROFILE&&aggregate["primaryRegionId"]==primary.regionId&&aggregate["sequence"]==0L&&aggregate.containsKey("pendingRegionId")&&aggregate["pendingRegionId"]==null&&aggregate["closed"]==false&&aggregate["journal"]==emptyList<Any>())
  val details=(aggregate["regions"]as? List<*>)?.map{it as? Map<*,*>?:error("writable region state invalid")}?:error("writable region states missing")
  require(details.size==copies.size&&details.map{it["snapshot"]}==copies&&details.all{it["programLaw"]==FlashProgramContracts.PROGRAM_LAW&&(it["programPageBytes"]as? Number)?.toLong()==256L&&it["eraseHistory"]==emptyList<Any>()&&it["programHistory"]==emptyList<Any>()})
  val handles=copies.map{copy->require(!copy.pending&&!copy.closed&&copy.failure==null&&copy.generation>=0&&copy.currentSha256.matches(Regex("[0-9a-f]{64}")));val seed=regions.singleOrNull{it.id==copy.regionId}?:error("writable region seed missing");require(seed.offset==copy.offset&&seed.length==copy.length&&seed.sha256==copy.seedSha256&&seed.relativeFile!=copy.relativeFile);val port=set.regionForWrite(copy.writableOffset,copy.writableLength);require(port.snapshot()==copy&&set.regionForWrite(copy.writableOffset+copy.writableLength-1,1)===port){"writable set route differs"};port}
  for(i in handles.indices) for(j in i+1 until handles.size) require(handles[i]!==handles[j]){"writable set route aliases"}
  return plan+mapOf("profile" to profile,"primaryWritableInput" to primary,"secondaryWritableInput" to secondary)+(if(tertiary!=null)mapOf("tertiaryWritableInput" to tertiary)else emptyMap())+(if(quaternary!=null)mapOf("quaternaryWritableInput" to quaternary)else emptyMap())+(if(lensCachePair!=null)mapOf("lensCacheWritableInput" to lensCachePair.first,"bank13WritableInput" to lensCachePair.second)else emptyMap())+mapOf("scope" to if(profile==PROFILE_OCTET)"Exact disjoint typed writable domains before native allocation: optional DAT part-12/part-13 lens EEPROM cache (0x02FE0000/0x034E0000), full BKUP octet 490..497 (part-16..23), secondary 504/505, shutdown 506/507 (part-27/28), and tail config 508 (part-30)." else "Exact two disjoint typed writable domains before native allocation. Old494..497 ancestry remains; secondary504/505 derived from DAT halves. Generic component supports more, this firmware scope does not. No erase/program/boot/physical-purpose assertion.")
 }
}
