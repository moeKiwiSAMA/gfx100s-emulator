package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MemoryLayout
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

object XlogBufferAdmission {
    const val PROFILE="xlog-first-buffer-experimental-v22"
    const val ALL_PROFILE="xlog-all-buffers-experimental-v23"
    const val REGION="xlog-first-buffer"
    const val BASE=0x07f13000L
    const val SIZE=0x100000L
    private const val APP=0x1021000L
    val added=MemoryRegion(REGION,BASE,SIZE,3)
    val remainingAdded=listOf(
        MemoryRegion("xlog-second-buffer",0x08013000L,SIZE,3),
        MemoryRegion("xlog-third-buffer",0x1a39c000L,SIZE,3),
        MemoryRegion("xlog-fourth-buffer",0x1a49c000L,SIZE,3))
    val allAdded=listOf(added)+remainingAdded
    private val code=listOf(
        0x12e8bc4L to """
            e92d4830 e28db00c e30239b4 e34031a2 e3a04000 e5c34000 e3a00a03 e34007f1
            e5830004 e5834008 e5c3400c e5834010 e5c34014 e3a02601 e1a01004 ebff456b
        """,
        0x12ba18cL to """
            e52db004 e28db000 e3520000 0a000002 e4801004 e2522001 1afffffc e28bd000
            e49db004 e12fff1e e1803002 e3130003 0a000006 e3520000 012fff1e e0802002
            e4c01001 e1520000 1afffffc e12fff1e e92d4800 e28db004 e1a03801 e1833401
            e1833001 e1a02122 e1831c01 ebffffe3 e8bd8800
        """
    ).map{(address,words)->address to words.trim().split(Regex("\\s+")).map{it.toLong(16)}}
    private val laterCode=listOf(0x12e8c04L to """
        e3a02601 e1a01004 e3a00a03 e3400801 ebff4566 e3a02601 e1a01004 e3a00903
        e3410a39 ebff4561 e3a02601 e1a01004 e3a00903 e3410a49 ebff455c e8bd8830
    """.trim().split(Regex("\\s+")).map{it.toLong(16)})
    val wordAddresses=code.flatMap{(address,words)->words.indices.map{address+it*4L}}
    val allWordAddresses=(code+laterCode).flatMap{(address,words)->words.indices.map{address+it*4L}}.sorted()
    fun sourceAllGeometry(app:ByteArray):Map<String,Any> = sourceGeometry(app,ALL_PROFILE)
    fun allRegionSet(inherited:List<MemoryRegion>):List<MemoryRegion> = regionSet(inherited,ALL_PROFILE)
    fun verifyAllLayout(selected:List<MemoryRegion>,inherited:List<MemoryRegion>):List<MemoryRegion> = verifyLayout(selected,inherited,ALL_PROFILE)

    fun sourceGeometry(app:ByteArray,profile:String=PROFILE):Map<String,Any> {
        require(profile==PROFILE||profile==ALL_PROFILE) {"Unknown xlog profile"}
        val bytes=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
        val selectedCode=if(profile==ALL_PROFILE)code+laterCode else code
        selectedCode.forEach{(address,words)->words.forEachIndexed{index,expected->
            val pc=address+index*4L
            val offset=pc-APP
            require(offset>=0&&offset<=app.size.toLong()-4) {"Xlog source truncated at "+pc.toString(16)}
            require(bytes.getInt(offset.toInt()).toLong() and 0xffffffffL==expected) {"Xlog source word differs at "+pc.toString(16)}
        }}
        val first=mapOf("producerStart" to 0x12e8bc4L,"producerEndExclusive" to 0x12e8c04L,
            "callsite" to 0x12e8c00L,"fillEntry" to 0x12ba1b4L,"wordLoop" to 0x12ba19cL,
            "helperStart" to 0x12ba18cL,"helperEndExclusive" to 0x12ba200L,"criticalWords" to wordAddresses.size,
            "pointerAddressVirtual" to 0x1a229b8L,"requestAddressVirtual" to BASE,"requestBytes" to SIZE,
            "fillByte" to 0L,"wordCount" to (SIZE ushr 2),"wordBytes" to 4L,"endExclusiveVirtual" to (BASE+SIZE))
        if(profile==PROFILE)return first
        return first+mapOf("producerEndExclusive" to 0x12e8c44L,"criticalWords" to allWordAddresses.size,
            "requests" to allAdded.mapIndexed{index,region->mapOf("callsite" to (0x12e8c00L+index*0x14L),
                "returnAddress" to (0x12e8c04L+index*0x14L),"requestAddressVirtual" to region.address,
                "requestBytes" to region.size,"endExclusiveVirtual" to (region.address+region.size),"fillByte" to 0L,
                "wordCount" to (region.size ushr 2))},"totalRequestBytes" to allAdded.sumOf{it.size})
    }

    fun regionSet(inherited:List<MemoryRegion>,profile:String=PROFILE):List<MemoryRegion> {
        require(profile==PROFILE||profile==ALL_PROFILE) {"Unknown xlog profile"}
        val additions=if(profile==ALL_PROFILE)remainingAdded else listOf(added)
        if(profile==ALL_PROFILE)require(inherited.singleOrNull{it.id==REGION}==added) {"Xlog v23 requires the unchanged v22 first buffer"}
        require(inherited.isNotEmpty()&&inherited.map{it.id}.distinct().size==inherited.size) {"Xlog requires nonempty unique inherited RAM"}
        require(inherited.all{it.address>=0&&it.size>0&&it.size<=0x1_0000_0000L&&it.address<=0x1_0000_0000L-it.size}) {"Xlog inherited RAM bounds invalid"}
        require(additions.all{region->inherited.none{it.id==region.id||it.address<region.address+region.size&&region.address<it.address+it.size}}) {"Xlog overlaps inherited RAM"}
        val plan=(inherited+additions).sortedBy{it.address}
        require(plan.zipWithNext().all{(left,right)->left.address+left.size<=right.address}) {"Xlog inherited RAM overlaps"}
        return plan
    }

    fun verifyLayout(selected:List<MemoryRegion>,inherited:List<MemoryRegion>,profile:String=PROFILE):List<MemoryRegion> {
        val expected=regionSet(inherited,profile)
        require(selected.sortedBy{it.address}==expected) {"Xlog requires complete ancestry plus exactly the declared $profile RW/NX buffers"}
        return expected
    }

    fun verifyAll(image:FirmwareImage,selected:List<MemoryRegion>,ancestor:RuntimeFlashStagingAdmission.Result,layout:MemoryLayout):RuntimeFlashStagingAdmission.Result {
        require(ancestor.liveReport["profile"]==SevenRowTableAdmission.PROFILE&&
            ancestor.liveRegions==image.metadata.memoryProfiles.getValue(SevenRowTableAdmission.PROFILE).sortedBy{it.address}) {"Xlog requires complete verified v21 ancestry"}
        require(image.metadata.sections.single{it.name=="section4"}.address==APP) {"Xlog section4 address differs"}
        val firstPlan=verifyLayout(image.metadata.memoryProfiles.getValue(PROFILE),ancestor.liveRegions)
        val geometry=sourceAllGeometry(image.decode("section4"))
        val plan=verifyAllLayout(selected,firstPlan)
        if(layout.profile==FlashloadBufferAdmission.PROFILE) {
            val complete=FlashloadBufferAdmission.validateLayout(image.metadata.memoryProfiles.getValue(FlashloadBufferAdmission.PROFILE),plan)
            require(layout.regions()==complete) {"Flashload complete live layout differs before xlog validation"}
        } else require(layout.profile==ALL_PROFILE&&layout.regions()==plan) {"Xlog v23 live layout differs"}
        allAdded.forEach{layout.check(it.address,it.size,3)}
        val rows:(List<MemoryRegion>)->List<Map<String,Any>> = {regions->regions.map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)}}
        return RuntimeFlashStagingAdmission.Result(ancestor.ancestor,plan,ancestor.liveReport+mapOf("profile" to ALL_PROFILE,"regions" to rows(plan),
            "xlogBuffer" to (geometry+mapOf("profile" to ALL_PROFILE,"ancestorProfile" to PROFILE,
                "ancestorRegions" to rows(firstPlan),"addedRegions" to rows(remainingAdded),"regions" to rows(allAdded),
                "fullRequestRwPreflight" to true,"v22CriticalWords" to wordAddresses.size,
                "initialContent" to "HYPOTHESIS: explicit zero native backing; all four clears execute original guest instructions",
                "permissions" to "HYPOTHESIS: finite RW/NX backing for the four source requests",
                "physicalGeometry" to "UNKNOWN","objectPurpose" to "xlog initialization buffers; payload format and display use UNKNOWN",
                "sourceEvidence" to listOf("evidence/refactor/display-status-953/README.md","evidence/refactor/xlog-first-buffer-954/README.md"),
                "aliasesImplemented" to false,"laterBuffersMapped" to true,"transfersInjected" to 0)),
            "basis" to "Complete verified v22 plus exactly three further 1MiB requests. All 32 producer and 29 helper words checked against DAT. No gap padding, executable permission, guest patch, IRQ or frame supplied."))
    }

    fun verify(image:FirmwareImage,selected:List<MemoryRegion>,ancestor:RuntimeFlashStagingAdmission.Result,layout:MemoryLayout):RuntimeFlashStagingAdmission.Result {
        require(ancestor.liveReport["profile"]==SevenRowTableAdmission.PROFILE&&
            ancestor.liveRegions==image.metadata.memoryProfiles.getValue(SevenRowTableAdmission.PROFILE).sortedBy{it.address}) {"Xlog requires complete verified v21 ancestry"}
        require(image.metadata.sections.single{it.name=="section4"}.address==APP) {"Xlog section4 address differs"}
        val geometry=sourceGeometry(image.decode("section4"))
        val plan=verifyLayout(selected,ancestor.liveRegions)
        require(layout.profile==PROFILE&&layout.regions()==plan) {"Xlog live layout differs"}
        layout.check(BASE,SIZE,3)
        val rows:(List<MemoryRegion>)->List<Map<String,Any>> = {regions->regions.map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)}}
        return RuntimeFlashStagingAdmission.Result(ancestor.ancestor,plan,ancestor.liveReport+mapOf("profile" to PROFILE,"regions" to rows(plan),
            "xlogBuffer" to (geometry+mapOf("profile" to PROFILE,"ancestorProfile" to SevenRowTableAdmission.PROFILE,
                "ancestorRegions" to rows(ancestor.liveRegions),"region" to rows(listOf(added)).single(),"fullRequestRwPreflight" to true,
                "initialContent" to "HYPOTHESIS: explicit zero native backing; the original guest performs the fill",
                "permissions" to "HYPOTHESIS: finite RW/NX backing selected from the P953 request and same-point page tables",
                "physicalGeometry" to "UNKNOWN","objectPurpose" to "xlog first initialization buffer; payload format and display use UNKNOWN",
                "sourceEvidence" to "evidence/refactor/display-status-953/README.md","aliasesImplemented" to false,
                "laterBuffersMapped" to false,"transfersInjected" to 0)),
            "basis" to "Complete verified v21 plus only the first xlog 1MiB request. Full producer and fill instruction words are checked against DAT. No section padding, later buffers, executable permission, guest patch, IRQ or frame supplied."))
    }
}
