package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts
import com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts

/** Explicit native-page experiment. DAT byte writes do not give padding a field meaning. */
object ConfigMemoryPagesAdmission {
    const val PROFILE="kernel-config-record-pages-experimental-v15"
    const val REGION="config-record-pages"
    const val BASE=0x04e00000L
    const val SIZE=0x3000L
    val added=MemoryRegion(REGION,BASE,SIZE,3)
    data class Result(val legacyPlan:SegmentsContracts.VerifiedPlan,val liveRegions:List<MemoryRegion>,val liveReport:Map<String,Any>)
    fun regionSet(inherited:List<MemoryRegion>):List<MemoryRegion> {
        require(inherited.isNotEmpty() && inherited.none {it.id==REGION || it.address<BASE+SIZE && BASE<it.address+it.size}) {"Config record native pages overlap inherited RAM"}
        return (inherited+added).sortedBy {it.address}
    }
    fun marker():Map<String,Any?> = mapOf("profile" to PROFILE,"ancestorProfile" to FlashReadPairContracts.PROFILE,
        "region" to mapOf("id" to REGION,"address" to BASE,"size" to SIZE,"permissions" to 3),
        "nativePageBytes" to 4096,"initialContent" to "HYPOTHESIS: explicit zero native backing; initialization writes must be guest instructions",
        "permissions" to "HYPOTHESIS: page-wide read/write, non-executable; padding access is permitted by this selected page experiment",
        "paddingRanges" to listOf(mapOf("address" to BASE+0x140,"size" to 0x6c0),mapOf("address" to BASE+0x1400,"size" to 0xc00)),
        "paddingFieldMeaning" to "UNKNOWN","paddingInitializedByDat" to false,
        "recordHeaderFirstWord" to mapOf("address" to BASE+0x2000,"size" to 4,"initialSource" to "HYPOTHESIS_ZERO","guestInitializationQualified" to false),
        "retainedHole" to mapOf("address" to BASE+SIZE,"size" to 0x1000),"physicalRamCapacityMeasured" to false,"targetDescriptorPermissionsIndependentlyCaptured" to false,
        "workspaceLegacyProjection" to mapOf("excludedRegionId" to REGION,"checkedAddress" to 0xe20000L,"checkedBytes" to 0x400,"livePagesVerifiedSeparately" to true,"nativeMappingProjected" to false),
        "observation" to mapOf("profile" to "config-record-pages-after-stop-readonly-v1","coordinateKind" to "cpu-physical","maximumBytes" to SIZE,
            "windows" to (0..2).map {mapOf("id" to "config-page-$it","physicalAddress" to BASE+it*4096L,"bytes" to 4096)}))
    fun verify(image:FirmwareImage,selected:List<MemoryRegion>,v14:SegmentsContracts.VerifiedPlan):Result {
        require(v14.report["profile"]==FlashReadPairContracts.PROFILE && v14.regions==image.metadata.memoryProfiles.getValue(FlashReadPairContracts.PROFILE).sortedBy {it.address}) {"Config pages require the complete verified v14 ancestor"}
        val geometry=mapOf("nativeEnvelope" to mapOf("address" to BASE,"size" to SIZE,"endExclusive" to BASE+SIZE,"permissions" to 3L))
        val plan=regionSet(v14.regions)
        require(selected.sortedBy {it.address}==plan) {"Selected config page layout differs from exact v14 plus three RW/NX pages"}
        val inherited=v14.regions.map {mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)}
        return Result(v14,plan,v14.report+mapOf("profile" to PROFILE,"configRecordPages" to (geometry+marker()+mapOf("ancestorRegions" to inherited)),
            "regions" to plan.map {mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)},
            "basis" to "Complete verified v14 plus DAT-consumer-derived native page envelope04E00000..04E03000. Page-wide RW/NX and initial zero, including unqualified padding bytes, are explicit HYPOTHESIS. Guest performs all FF/copy writes.04E03000..04E04000 stays absent. Only the legacy E20000/400 workspace subcontract sees its unchanged verified v14 plan; live RAM is validated in full and mapped without projection. No alias, physical DRAM capacity, target descriptor AP or frame claim."))
    }
}
