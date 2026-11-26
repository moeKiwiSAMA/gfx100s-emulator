package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Software work buffer derived from selected DAT; physical purpose and initial RAM state unmeasured. */
object AuxBufferContracts {
    const val PROFILE="kernel-aux-buffer-experimental-v11"
    private const val APP_BASE=0x1021000L
    private val critical=linkedMapOf(
        0x12c806cL to 0xe92d48f0L,
        0x12c8070L to 0xe28db014L,
        0x12c8074L to 0xe3074f4cL,
        0x12c8078L to 0xe34041d0L,
        0x12c807cL to 0xe3a05000L,
        0x12c8080L to 0xe5c45000L,
        0x12c8084L to 0xe3a03001L,
        0x12c8088L to 0xe5c43001L,
        0x12c808cL to 0xe5c43002L,
        0x12c8090L to 0xe5845008L,
        0x12c8094L to 0xe309625cL,
        0x12c8098L to 0xe34061caL,
        0x12c809cL to 0xe3a00901L,
        0x12c80a0L to 0xe34004e0L,
        0x12c80a4L to 0xe5860000L,
        0x12c80a8L to 0xe3a02901L,
        0x12c80acL to 0xe1a01005L,
        0x12c80b0L to 0xebffc83fL,
        0x12c80b4L to 0xe5963000L,
        0x12c80b8L to 0xe5d42000L,
        0x12c80bcL to 0xe5c32006L,
        0x12c80c0L to 0xe3a03008L,
        0x12c80c4L to 0xe5843004L,
        0x12ba18cL to 0xe52db004L,
        0x12ba190L to 0xe28db000L,
        0x12ba194L to 0xe3520000L,
        0x12ba198L to 0xa000002L,
        0x12ba19cL to 0xe4801004L,
        0x12ba1a0L to 0xe2522001L,
        0x12ba1a4L to 0x1afffffcL,
        0x12ba1a8L to 0xe28bd000L,
        0x12ba1acL to 0xe49db004L,
        0x12ba1b0L to 0xe12fff1eL,
        0x12ba1b4L to 0xe1803002L,
        0x12ba1b8L to 0xe3130003L,
        0x12ba1bcL to 0xa000006L,
        0x12ba1c0L to 0xe3520000L,
        0x12ba1c4L to 0x12fff1eL,
        0x12ba1c8L to 0xe0802002L,
        0x12ba1ccL to 0xe4c01001L,
        0x12ba1d0L to 0xe1520000L,
        0x12ba1d4L to 0x1afffffcL,
        0x12ba1d8L to 0xe12fff1eL,
        0x12ba1dcL to 0xe92d4800L,
        0x12ba1e0L to 0xe28db004L,
        0x12ba1e4L to 0xe1a03801L,
        0x12ba1e8L to 0xe1833401L,
        0x12ba1ecL to 0xe1833001L,
        0x12ba1f0L to 0xe1a02122L,
        0x12ba1f4L to 0xe1831c01L,
        0x12ba1f8L to 0xebffffe3L,
        0x12ba1fcL to 0xe8bd8800L
    )
    val wordAddresses:List<Long> get()=critical.keys.toList()
    fun observe(app:ByteArray):Map<String,Any> {
        fun word(pc:Long):Long {require(pc>=APP_BASE&&pc%4==0L&&pc-APP_BASE<=app.size-4L){"aux buffer DAT consumer outside initialized section4"};return ByteBuffer.wrap(app,(pc-APP_BASE).toInt(),4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL}
        fun imm(w:Long)=Integer.rotateRight((w and 255).toInt(),((w ushr 8 and 15)*2).toInt()).toLong() and 0xffffffffL
        fun half(w:Long)=(w and 0xfff) or (w ushr 4 and 0xf000)
        critical.forEach{(pc,expected)->require(word(pc)==expected){"aux buffer DAT consumer differs at "+pc.toString(16)}}
        val base=imm(word(0x12c809c)) or (half(word(0x12c80a0)) shl 16)
        val bytes=imm(word(0x12c80a8));val pointer=half(word(0x12c8094)) or (half(word(0x12c8098)) shl 16)
        val fill=imm(word(0x12c807c));val shift=word(0x12ba1f0) ushr 7 and 31
        val step=word(0x12ba19c) and 0xfff;val words=bytes ushr shift.toInt()
        require(base==0x04e04000L&&bytes==0x4000L&&pointer==0x1ca925cL&&fill==0L&&shift==2L&&step==4L&&words*step==bytes)
        return linkedMapOf("profile" to PROFILE,"base" to base,"byteSize" to bytes,"endExclusive" to Math.addExact(base,bytes),"pointerAddress" to pointer,"fillByte" to fill,"wordCount" to words,"wordBytes" to step,"countShift" to shift,"criticalWords" to critical.size,
            "basis" to "Selected section4 producer passes4000 BYTES; aligned helper shifts by2 and writes4096 words. RW/NX/zero backing are HYP. No physical device identity or natural clear completion claim.")
    }
    fun regions(inherited:List<MemoryRegion>,geometry:Map<String,Any>):List<MemoryRegion> {
        require(geometry["profile"]==PROFILE&&geometry["base"]==0x04e04000L&&geometry["byteSize"]==0x4000L)
        val added=MemoryRegion("aux-work-buffer",0x04e04000,0x4000,3)
        require(inherited.none{it.id==added.id||it.address<added.address+added.size&&added.address<it.address+it.size}){"aux buffer overlaps inherited RAM"}
        val merged=ArrayList<MemoryRegion>()
        for(r in (inherited+added).sortedBy{it.address}) {val last=merged.lastOrNull();if(last!=null&&last.address+last.size==r.address&&last.permissions==r.permissions)merged[merged.lastIndex]=last.copy(size=last.size+r.size)else merged.add(r)}
        return merged.toList()
    }
    fun verify(image:FirmwareImage,selected:List<MemoryRegion>,v10:SegmentsContracts.VerifiedPlan):SegmentsContracts.VerifiedPlan {
        require(image.metadata.id=="gfx100s-213"&&v10.report["profile"]==FlashBufferContracts.PROFILE&&v10.regions==image.metadata.memoryProfiles.getValue(FlashBufferContracts.PROFILE).sortedBy{it.address}){"aux buffer requires verified selected v10"}
        val geometry=observe(image.decode("section4"));val all=regions(v10.regions,geometry)
        require(all==selected.sortedBy{it.address}){"selected aux buffer differs from DAT or inherited RAM permissions"}
        return SegmentsContracts.VerifiedPlan(all,v10.report+mapOf("profile" to PROFILE,"auxBuffer" to geometry,"regions" to all.map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)},"basis" to "Verified v10 plus exactly DAT-produced04E04000..04E07FFF, merged only with adjacent same-permission region. Other holes unchanged; RW/NX and zero backing remain HYP."))
    }
}
