package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Distinct ID4 software buffer after the verified ID3 extent; no measured DRAM capacity. */
object AuxiliaryTaskPairContracts {
    const val PROFILE="kernel-auxiliary-task-pair-experimental-v13"
    private const val APP=0x1021000L
    private const val AUX=0xf20400L
    private data class Span(val start:Long,val size:Int,val sha:String)
    private val spans=listOf(
        Span(0x12b2368,124,"e3617c846435f2fa344100da6dfdb9c7ce885f73f883e1fde6a8d0493683c839"),
        Span(0xf32aa4,188,"65678fbd15e8b0bafb48933394baa8e7ce80ee30f2dad560f8d0ae1f1739f629"),
        Span(0xf32c8c,44,"b6dea11bc3bf76518b3b69777819a164566f9902859536a8c98336ddb2d8b008"))
    val wordAddresses:List<Long> get()=listOf(AUX,0x12b2360L,0x12b2364L)+spans.flatMap{(it.start until it.start+it.size step 4).toList()}
    private fun hash(b:ByteArray)=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
    fun auxiliaryImage(image:FirmwareImage):ByteArray {
        require(image.metadata.id=="gfx100s-213")
        val port=image.inputPort();val metadata=ByteArray(1024);port.read(0x140274,ByteBuffer.wrap(metadata));metadata.indices.forEach{metadata[it]=metadata[it].toInt().inv().toByte()}
        val record=SegmentsContracts.derive(metadata).records.single{it.id==4L}
        require(record.initializedAddress==AUX&&record.initializedSize==149888L&&record.flashOffset==0x1e0000L){"ID4 DAT initialized record differs"}
        val bytes=ByteArray(record.initializedSize.toInt());port.read(0x274+record.flashOffset,ByteBuffer.wrap(bytes));bytes.indices.forEach{bytes[it]=bytes[it].toInt().inv().toByte()}
        require(hash(bytes)=="c57f7c9656700f67b1f2813ece4222babefd0afb3be20e01efe29b22c075b6e3"){"ID4 initialized image differs"}
        return bytes
    }
    fun observe(app:ByteArray,auxiliary:ByteArray):Map<String,Any> {
        fun slice(pc:Long,size:Int):ByteArray {val base=if(pc<APP)AUX else APP;val b=if(pc<APP)auxiliary else app
            require(pc>=base&&size>0&&pc-base+size<=b.size){"ID4 DAT consumer outside initialized image"};return b.copyOfRange((pc-base).toInt(),(pc-base+size).toInt())}
        fun word(pc:Long)=ByteBuffer.wrap(slice(pc,4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong()and 0xffffffffL
        fun imm(w:Long)=Integer.rotateRight((w and 255).toInt(),((w ushr 8 and 15)*2).toInt()).toLong()and 0xffffffffL
        fun half(w:Long)=(w and 4095)or(w ushr 4 and 0xf000)
        fun pointer(pc:Long)=half(word(pc))or(half(word(pc+4))shl 16)
        for(s in spans)require(hash(slice(s.start,s.size))==s.sha){"ID4 DAT span differs at "+s.start.toString(16)}
        require(word(AUX)==0xf32aa4L&&word(0x12b2360)==0xe3500000L&&word(0x12b2364)==0x18bd8830L)
        val base=imm(word(0x12b23a8))or(half(word(0x12b23ac))shl 16);val size=imm(word(0x12b23b4))
        val structure=pointer(0x12b2368);val argumentOffset=imm(word(0x12b23dc))
        val baseField=word(0xf32b28)and 4095;val sizeField=word(0xf32b38)and 4095
        val baseStore=word(0x12b23b0)and 4095;val sizeStore=word(0x12b23b8)and 4095
        val baseGlobal=pointer(0xf32b20);val sizeGlobal=pointer(0xf32b30);val table=pointer(0x12b23cc);val slot=word(0x12b23d4)and 4095
        val fill=imm(word(0xf32cac));val shift=word(0x12ba1f0)ushr 7 and 31;val stride=word(0x12ba19c)and 4095
        val call=word(0xf32cb4);val target=0xf32cb4L+8+((call.toInt()shl 8)shr 6)
        require(base==0x58d0000L&&size==0x31000L&&structure==0x19d7018L&&argumentOffset==44L&&baseField==28L&&sizeField==32L&&baseStore==72L&&sizeStore==76L)
        require(argumentOffset+baseField==baseStore&&argumentOffset+sizeField==sizeStore&&baseGlobal==0xf7d554L&&sizeGlobal==0xf7d560L&&table==0x19d7270L&&slot==4L&&fill==0L&&shift==2L&&stride==4L&&target==0x12ba1b4L&&(size ushr shift.toInt())*stride==size)
        return linkedMapOf("profile" to PROFILE,"imageId" to 4L,"initializedAddress" to AUX,"initializedBytes" to 149888L,
            "initializedSha256" to "c57f7c9656700f67b1f2813ece4222babefd0afb3be20e01efe29b22c075b6e3",
            "entry" to word(AUX),"base" to base,"byteSize" to size,"endExclusive" to base+size,"structureAddress" to structure,
            "argumentOffset" to argumentOffset,"argumentAddress" to structure+argumentOffset,"baseFieldOffset" to baseField,"sizeFieldOffset" to sizeField,"upstreamBaseOffset" to baseStore,"upstreamSizeOffset" to sizeStore,
            "baseGlobal" to baseGlobal,"sizeGlobal" to sizeGlobal,"entryTable" to table,"entrySlotOffset" to slot,"fillEntry" to target,"fillByte" to fill,"wordCount" to(size ushr shift.toInt()),"wordBytes" to stride,"countShift" to shift,"criticalWords" to wordAddresses.size,
            "basis" to "DAT distinct ID4 record/entry, app struct+48/+4C with argument+2C, ID4 globals and F32CB4 fill. Buffer extent derives from producer, not fault; RW/NX/zero backing HYP, physical purpose/capacity unknown.")
    }
    fun regions(inherited:List<MemoryRegion>,geometry:Map<String,Any>):List<MemoryRegion> {
        require(geometry["profile"]==PROFILE&&geometry["base"]==0x58d0000L&&geometry["byteSize"]==0x31000L)
        val added=MemoryRegion("auxiliary-task-buffer-id4",0x58d0000,0x31000,3)
        require(inherited.none{it.id==added.id||it.address<added.address+added.size&&added.address<it.address+it.size}){"ID4 buffer overlaps inherited RAM"}
        val merged=ArrayList<MemoryRegion>()
        for(r in(inherited+added).sortedBy{it.address}){val last=merged.lastOrNull();if(last!=null&&last.address+last.size==r.address&&last.permissions==r.permissions)merged[merged.lastIndex]=last.copy(size=last.size+r.size)else merged.add(r)}
        return merged.toList()
    }
    fun verify(image:FirmwareImage,selected:List<MemoryRegion>,v12:SegmentsContracts.VerifiedPlan):SegmentsContracts.VerifiedPlan {
        require(image.metadata.id=="gfx100s-213"&&v12.report["profile"]==AuxiliaryTaskBufferContracts.PROFILE&&v12.regions==image.metadata.memoryProfiles.getValue(AuxiliaryTaskBufferContracts.PROFILE).sortedBy{it.address}){"ID4 task buffer requires verified selected v12"}
        val geometry=observe(image.decode("section4"),auxiliaryImage(image));val plan=regions(v12.regions,geometry)
        require(selected.sortedBy{it.address}==plan){"selected ID4 task buffer differs from DAT or inherited RAM permissions"}
        return SegmentsContracts.VerifiedPlan(plan,v12.report+mapOf("profile" to PROFILE,"auxiliaryTaskPair" to mapOf("id3" to v12.report.getValue("auxiliaryTaskBuffer"),"id4" to geometry),
            "regions" to plan.map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)},
            "basis" to "Verified v12 plus only DAT ID4 buffer058D0000..05900FFF, adjacent equal-permission backing merged; logical ID3/ID4 identities retained separately. No physical DRAM claim or auto-map." ))
    }
}
