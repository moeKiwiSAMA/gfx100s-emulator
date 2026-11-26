package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exact DAT/SIM load destination. Backing/reset and RW/NX permission are explicit hypotheses. */
object PostLensObjectAdmission {
    const val PROFILE="post-lens-object-loading-experimental-v17"
    const val REGION="post-lens-object"
    const val BASE=0xb00000L
    const val SIZE=0x1a0000L
    val added=MemoryRegion(REGION,BASE,SIZE,3)
    fun regionSet(inherited:List<MemoryRegion>):List<MemoryRegion> {
        require(inherited.isNotEmpty()&&inherited.none{it.id==REGION||it.address<BASE+SIZE&&BASE<it.address+it.size}) {"Post-lens destination overlaps inherited RAM"}
        return (inherited+added).sortedBy{it.address}
    }
    fun validateLayout(selected:List<MemoryRegion>,inherited:List<MemoryRegion>):List<MemoryRegion> {
        val expected=regionSet(inherited)
        require(selected.sortedBy{it.address}==expected) {"Post-lens layout requires complete v16 plus exactly B00000..CA0000 RW/NX"}
        return expected
    }
    fun verify(image:FirmwareImage,selected:List<MemoryRegion>,ancestor:RuntimeFlashStagingAdmission.Result):RuntimeFlashStagingAdmission.Result {
        require(ancestor.liveReport["profile"]==RuntimeFlashStagingAdmission.PROFILE&&ancestor.liveRegions==image.metadata.memoryProfiles.getValue(RuntimeFlashStagingAdmission.PROFILE).sortedBy{it.address})
        val app=ByteBuffer.wrap(image.decode("section4")).order(ByteOrder.LITTLE_ENDIAN)
        require(listOf(0x1760000,13,BASE.toInt())==(0..2).map{app.getInt(0x174740c-0x1021000+4*it)}) {"Post-lens DAT descriptor differs"}
        val plan=validateLayout(selected,ancestor.liveRegions)
        val rows: (List<MemoryRegion>) -> List<Map<String,Any>> = {rs->rs.map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)}}
        return RuntimeFlashStagingAdmission.Result(ancestor.ancestor,plan,ancestor.liveReport+mapOf("profile" to PROFILE,"regions" to rows(plan),
            "postLensObject" to mapOf("profile" to PROFILE,"ancestorProfile" to RuntimeFlashStagingAdmission.PROFILE,"ancestorRegions" to rows(ancestor.liveRegions),
                "region" to rows(listOf(added)).single(),"descriptorVirtual" to 0x174740c,"sourceLogical" to 0x1760000,"blocks" to 13,
                "initialContent" to "HYPOTHESIS: explicit zero native backing; actual guest DMA supplies bytes","permissions" to "HYPOTHESIS RW/NX; execution unqualified",
                "physicalGeometry" to "UNKNOWN","aliasesImplemented" to false,"transfersInjected" to 0),
            "basis" to "Complete verified v16 plus exact DAT/SIM-requested B00000..CA0000 RW/NX destination. Existing loader/flash/DMA performs the actual transfer; no completion or executable permission supplied."))
    }
}
