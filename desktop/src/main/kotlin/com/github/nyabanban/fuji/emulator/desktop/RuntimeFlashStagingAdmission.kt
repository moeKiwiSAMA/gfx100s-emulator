package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A finite SOURCE/SIM-request experiment, not physical capacity or an address alias. */
object RuntimeFlashStagingAdmission {
    const val PROFILE="kernel-runtime-flash-staging-experimental-v16"
    const val REGION="runtime-flash-staging"
    const val BASE=0x2b69c000L
    const val SIZE=0x540000L
    const val OWNER_PUBLICATION="b1763dfcaedbca1733af6ca856003f672fa445ae"
    const val OWNER_SOURCE="97640848b1bc7e05668500f075987e1e37e76110"
    private const val APP=0x1021000L
    val added=MemoryRegion(REGION,BASE,SIZE,3)
    data class Result(val ancestor:ConfigMemoryPagesAdmission.Result,val liveRegions:List<MemoryRegion>,val liveReport:Map<String,Any>)
    private val code=listOf(
        0x129e228L to """
            e92d49f0 e28db018 e24dd00c e3a00006 eb00adcd e2504000 1a000000 eafffffe e5948014 e5947018
            e30703dc e3400174 ebb6e4ee e594000c e3100003 13a06000 1a000003 e5942010 e3120003 13a06000
            0a00002f e1a088a8 e30f3fff e3403001 e0873003 e1a058a3 eb00adce e3500000 1a000051 e30703e4
            e3400174 ebb6e4db e3a07903 e3427b69 e1a01007 e5940004 ebfd2b98 e3a03001 e58d3000 e30932a0
            e340311e e1a02007 e1a01005 e1a00008 ebfffed4 e1a05000 e30703ec e3400174 ebb6e4ca e30703f4
            e3400174 ebb6e4c7 ebfd2c3b e30703fc e3400174 ebb6e4c3 e3560000 0a00003e e3033cf4 e340318c
            e5932000 e352000f 1afffffc e3070404 e3400174 ebb6e4b9 e1a00005 e24bd018 e8bd89f0 e3033cf4
            e340318c e3a06000 e5836000 e30d3e04 e3403129 e1a02122 e1a01006 ebfd1a9e e1a05000 e5940010
            e1a00120 e594c00c e30d3dd8 e3403129 e1a02000 e1a01006 e080000c ebfd1a94 e1855000 e5940010
            e594c00c e30d3dac e3403129 e1a02120 e1a01006 e08c00a0 ebfd1a8b e1855000 e5942010 e1a02122
            e1a00082 e0800002 e594c00c e30d3d80 e3403129 e1a01006 e080000c ebfd1a80 e1953000 03a06001
            eaffffa5 e3a03001 e58d3000 e3a03000 e3a02903 e3422b69 e1a01005 e1a00008 ebfffe8a e1a05000
            eaffffbe e5942010 e3a01000 e594000c eb006f65 eb006bb3 eaffffbf
        """,
        0x129de30L to """
            e92d4ff0 e28db020 e24dd01c e1a07003 e30b3200 e3403181 e5d33000 e3530001 1a000024 e1a08001
            e1a05002 e300317e e1500003 8a000022 e1a00880 ebfd0339 e1a06000 e1a08888 e3580802 3a00001f
            e3a04802 e1a0a004 e3a09000 e50b5034 e50b6030 e50ba02c e3a03001 e54b3028 e54b9027 e24b0034
            ebfd0216 e2503000 e50b3038 1a000008 e2855802 e2866802 e3570000 0a000001 e1a00004 e12fff37
            e2844802 e1580004 2affffeb e51b0038 e24bd020 e8bd8ff0 e3e030fe e50b3038 eafffff9 e3e0301d
            e50b3038 eafffff6 e3a03000 e50b3038 eafffff3
        """,
        0x11deb58L to "e52db004 e28db000 e280020f e28bd000 e49db004 e12fff1e"
    ).map { (pc,words)->pc to words.trim().split(Regex("\\s+")).map {it.toLong(16)} }
    /** Accepts decoded bytes so refusal tests reach the instruction gate, not a file-hash gate. */
    fun sourceGeometry(app:ByteArray):Map<String,Any> {
        val bytes=ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
        fun word(pc:Long):Long {
            val offset=pc-APP
            require(offset>=0 && offset<=app.size.toLong()-4) {"Runtime staging source truncated at ${pc.toString(16)}"}
            return bytes.getInt(offset.toInt()).toLong() and 0xffffffffL
        }
        for((start,words) in code)for((i,expected) in words.withIndex()) {
            val pc=start+4L*i
            require(word(pc)==expected) {"Runtime staging source word differs at ${pc.toString(16)}"}
        }
        fun immediate(pc:Long)=word(pc).let {Integer.rotateRight((it and 255).toInt(),((it ushr 8 and 15)*2).toInt()).toLong() and 0xffffffffL}
        fun half(pc:Long)=word(pc).let {(it and 0xfffL) or (it ushr 4 and 0xf000L)}
        val destination=(half(0x129e2ac) shl 16) or immediate(0x129e2a8)
        val fallback=(half(0x129e3f4) shl 16) or immediate(0x129e3f0)
        val unit=immediate(0x129de80)
        val count=42L // P59 saved normal-frame candidate; never a DAT constant or all-state limit.
        val index=49L
        val total=Math.multiplyExact(count,unit)
        val offset=Math.multiplyExact(index,unit)
        val end=Math.addExact(destination,total)
        require(destination==BASE && fallback==BASE && unit==0x20000L && total==SIZE && end==0x2bbdc000L)
        require(offset==0x620000L && offset+total==0xb60000L && 0xf0000000L+offset+total<=0x1_0000_0000L)
        return mapOf("sourceWords" to code.map {(pc,words)->mapOf("address" to pc,"words" to words.size)},"completeWordsGated" to code.sumOf{it.second.size},
            "destinationLiteral" to destination,"unitBytes" to unit,"candidateUnits" to count,"candidateSourceIndex" to index,
            "requestedBytes" to total,"endExclusive" to end,"sourceLogicalRange" to listOf(offset,offset+total),
            "sourceApertureRange" to listOf(0xf0000000L+offset,0xf0000000L+offset+total),
            "countBasis" to "SOURCE/SIM conditional normal-frame candidate;42 is not a DAT constant. Descriptor payload was not directly read. This finite selected profile does not cover every possible state.",
            "generalArithmetic" to "source index and helper count shift17 modulo2^32; initializer count=((descriptor[+18]+1FFFF) modulo2^32) LSR17; these concrete candidates do not wrap",
            "completion" to "UNKNOWN: nonzero callee result exits; zero advances source/destination by20000, optional callback then progress comparison; no transfer or completion is injected",
            "sourcePublications" to mapOf("literal" to "6dc7f42b3ff3f59b619ee6e8dae646571af52cfe","helper" to "fcc6fa39c8763fa1f7e89aeddb3d08e11dc28327","conditionalOwner" to OWNER_PUBLICATION,"conditionalOwnerSource" to OWNER_SOURCE))
    }
    fun regionSet(inherited:List<MemoryRegion>):List<MemoryRegion> {
        require(inherited.isNotEmpty() && inherited.none {it.id==REGION || it.address<BASE+SIZE && BASE<it.address+it.size}) {"Runtime staging overlaps inherited RAM"}
        return (inherited+added).sortedBy {it.address}
    }
    fun validateLayout(selected:List<MemoryRegion>,inherited:List<MemoryRegion>):List<MemoryRegion> {
        val expected=regionSet(inherited)
        require(selected.sortedBy {it.address}==expected) {"Runtime staging layout must be complete v15 plus exactly540000 RW/NX bytes at2B69C000"}
        return expected
    }
    fun marker():Map<String,Any> = mapOf("profile" to PROFILE,"ancestorProfile" to ConfigMemoryPagesAdmission.PROFILE,
        "region" to mapOf("id" to REGION,"address" to BASE,"size" to SIZE,"permissions" to 3),
        "initialContent" to "HYPOTHESIS: explicit zero native backing; guest/device performs actual copies",
        "permissions" to "HYPOTHESIS: selected finite range read/write and non-executable",
        "physicalRamGeometry" to "UNKNOWN","physicalAliasRelation" to "UNKNOWN","aliasesImplemented" to false,
        "oldStaging" to mapOf("id" to "staging","address" to 0x2a69c000L,"size" to SIZE,"independentNativeBacking" to true),
        "nativeMappingProjected" to false,"allStateCapacityQualified" to false,"guestTransfersCompletedByAdmission" to 0)
    fun verify(image:FirmwareImage,selected:List<MemoryRegion>,ancestor:ConfigMemoryPagesAdmission.Result):Result {
        require(ancestor.liveReport["profile"]==ConfigMemoryPagesAdmission.PROFILE &&
            ancestor.liveRegions==image.metadata.memoryProfiles.getValue(ConfigMemoryPagesAdmission.PROFILE).sortedBy {it.address} &&
            ancestor.legacyPlan.report["profile"]==FlashReadPairContracts.PROFILE &&
            ancestor.legacyPlan.regions==image.metadata.memoryProfiles.getValue(FlashReadPairContracts.PROFILE).sortedBy {it.address}) {"Runtime staging requires complete verified v15 and v14 ancestry"}
        val geometry=sourceGeometry(image.decode("section4"))
        val plan=validateLayout(selected,ancestor.liveRegions)
        fun rows(regions:List<MemoryRegion>)=regions.map {mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)}
        return Result(ancestor,plan,ancestor.liveReport+mapOf("profile" to PROFILE,"runtimeFlashStaging" to (geometry+marker()+mapOf("ancestorRegions" to rows(ancestor.liveRegions))),
            "regions" to rows(plan),"basis" to "Complete verified v15 plus finite SOURCE/SIM-request staging2B69C000..2BBDC000. RW/NX and zero backing are HYPOTHESIS. Old2A69C000 staging remains independent. Physical geometry/alias UNKNOWN. Conditional42-unit request is not every-state capacity, a directly read descriptor, unique invocation, or completed transfer. All15 live regions map natively; unchanged legacy workspace uses verified v14 only."))
    }
}
