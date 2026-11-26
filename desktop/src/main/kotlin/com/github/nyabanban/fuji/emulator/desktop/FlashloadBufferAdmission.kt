package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MemoryLayout
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FlashloadBufferAdmission {
    const val PROFILE="flashload-request-buffer-experimental-v24"
    const val REGION="flashload-request-buffer"
    const val BASE=0x00ca0000L
    const val SIZE=0x100000L
    const val SOURCE=0xf2c60000L
    private const val APP=0x01021000L
    val added=MemoryRegion(REGION,BASE,SIZE,3)
    private val code=listOf(
        0x012a0cc8L to """
            e92d4800 e28db004 e3000000 e34002c6 e3a03001 e3002000 e34020ca e3001008
            e3401000 e1a008a0 ebfff3b7 e8bd8800
        """,
        0x012c94c0L to "ebff5e00 e1a06000",
        0x0129db90L to """
            e30f3fff e34032fd e1500003 8a00000a e92d4830 e28db00c e1a05002 e1a04001
            ebfd03e8 e3a03001 e1a02005 e1a01000 e1a00004 ebfd0390 e8bd8830 e3e0001d
            e12fff1e e30b3200 e3403181 e5d33000 e3530001 1a000007 e92d4800 e28db004
            e1a03002 e1a02881 e1a01003 e1a00880 ebffffe2 e8bd8800 e3e000fe e12fff1e
        """,
        0x011deb58L to "e52db004 e28db000 e280020f e28bd000 e49db004 e12fff1e"
    ).map{(address,words)->address to words.trim().split(Regex("\\s+")).map{it.toLong(16)}}
    val wordAddresses=code.flatMap{(address,words)->words.indices.map{address+it*4L}}

    fun sourceGeometry(app:ByteArray):Map<String,Any> {
        val bytes=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
        code.forEach{(address,words)->words.forEachIndexed{index,expected->
            val pc=address+index*4L
            val offset=pc-APP
            require(offset>=0&&offset<=app.size.toLong()-4) {"Flashload source truncated at "+pc.toString(16)}
            require(bytes.getInt(offset.toInt()).toLong() and 0xffffffffL==expected) {"Flashload source word differs at "+pc.toString(16)}
        }}
        return mapOf("producerStart" to 0x012a0cc8L,"producerEndExclusive" to 0x012a0cf8L,
            "callsite" to 0x012c94c0L,"returnAddress" to 0x012c94c4L,
            "criticalWords" to wordAddresses.size,"sourceLogical" to 0x02c60000L,
            "sourcePhysical" to SOURCE,"destinationPhysical" to BASE,
            "blocks" to 8,"blockBytes" to 0x20000L,"requestBytes" to SIZE)
    }

    fun regionSet(inherited:List<MemoryRegion>):List<MemoryRegion> {
        require(inherited.isNotEmpty()&&inherited.map{it.id}.distinct().size==inherited.size) {"Flashload requires nonempty unique inherited RAM"}
        require(inherited.all{it.address>=0&&it.size>0&&it.size<=0x1_0000_0000L&&it.address<=0x1_0000_0000L-it.size}) {"Flashload inherited RAM bounds invalid"}
        require(inherited.none{it.id==REGION||it.address<BASE+SIZE&&BASE<it.address+it.size}) {"Flashload destination overlaps inherited RAM"}
        val plan=(inherited+added).sortedBy{it.address}
        require(plan.zipWithNext().all{(left,right)->left.address+left.size<=right.address}) {"Flashload inherited RAM overlaps"}
        return plan
    }

    fun validateLayout(selected:List<MemoryRegion>,inherited:List<MemoryRegion>):List<MemoryRegion> {
        val expected=regionSet(inherited)
        require(selected.sortedBy{it.address}==expected) {"Flashload requires complete v23 plus exactly CA0000..DA0000 RW/NX"}
        return expected
    }

    fun verify(image:FirmwareImage,selected:List<MemoryRegion>,ancestor:RuntimeFlashStagingAdmission.Result,layout:MemoryLayout):RuntimeFlashStagingAdmission.Result {
        require(ancestor.liveReport["profile"]==XlogBufferAdmission.ALL_PROFILE&&
            ancestor.liveRegions==image.metadata.memoryProfiles.getValue(XlogBufferAdmission.ALL_PROFILE).sortedBy{it.address}) {"Flashload requires complete verified v23 ancestry"}
        require(image.metadata.sections.single{it.name=="section4"}.address==APP) {"Flashload section4 address differs"}
        val geometry=sourceGeometry(image.decode("section4"))
        val plan=validateLayout(selected,ancestor.liveRegions)
        require(layout.profile==PROFILE&&layout.regions()==plan) {"Flashload live layout differs"}
        layout.check(BASE,SIZE,3)
        val rows:(List<MemoryRegion>)->List<Map<String,Any>> = {regions->regions.map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)}}
        return RuntimeFlashStagingAdmission.Result(ancestor.ancestor,plan,ancestor.liveReport+mapOf(
            "profile" to PROFILE,"regions" to rows(plan),
            "flashloadBuffer" to (geometry+mapOf("profile" to PROFILE,"ancestorProfile" to XlogBufferAdmission.ALL_PROFILE,
                "ancestorRegions" to rows(ancestor.liveRegions),"region" to rows(listOf(added)).single(),
                "sourceEvidence" to "evidence/refactor/a300-finite-tail-960/dma-frontier-review.generated.json",
                "requestConfidence" to "SIM_UNDER_HYPOTHESIS",
                "producerConfidence" to "DAT",
                "initialContent" to "HYPOTHESIS: explicit zero native backing; original guest Flash DMA supplies bytes",
                "permissions" to "HYPOTHESIS: exact request RW/NX backing",
                "physicalGeometry" to "UNKNOWN","objectPurpose" to "UNKNOWN",
                "aliasesImplemented" to false,"transfersInjected" to 0)),
            "basis" to "Complete verified v23 plus the exact observed task93 Flash DMA destination; all producer, wrapper and source-add words checked against DAT. No gap padding, execute, completion, IRQ or frame supplied."))
    }
}
