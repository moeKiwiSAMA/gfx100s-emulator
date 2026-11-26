package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fixed DAT/SIM calc clear request; finite native backing/reset and RW/NX are HYPOTHESES. */
object CalcScratchAdmission {
    const val PROFILE="calc-fixed-workspace-experimental-v18"
    const val REGION="calc-fixed-workspace"
    const val BASE=0x2c590000L
    const val SIZE=0xe000L
    const val CLEAR_BASE=0x2c590b04L
    const val CLEAR_SIZE=0xd230
    val added=MemoryRegion(REGION,BASE,SIZE,3)
    fun regionSet(inherited:List<MemoryRegion>):List<MemoryRegion> {
        require(inherited.isNotEmpty()&&inherited.none{it.id==REGION||it.address<BASE+SIZE&&BASE<it.address+it.size}) {"Calc scratch overlaps inherited RAM"}
        return (inherited+added).sortedBy{it.address}
    }
    fun validateLayout(selected:List<MemoryRegion>,inherited:List<MemoryRegion>):List<MemoryRegion> {
        val expected=regionSet(inherited)
        require(selected.sortedBy{it.address}==expected) {"Calc scratch requires complete v17 plus exactly 2C590000..2C59E000 RW/NX"}
        return expected
    }
    fun verifyWords(words:List<Int>) {
        require(words==listOf(0xe30d2230.toInt(),0xe3a01000.toInt(),0xe3000b04.toInt(),0xe3420c59.toInt(),0xebbb31a7.toInt())) {"Calc fixed address/length/full call words differ"}
    }
    fun verify(image:FirmwareImage,selected:List<MemoryRegion>,ancestor:RuntimeFlashStagingAdmission.Result):RuntimeFlashStagingAdmission.Result {
        require(ancestor.liveReport["profile"]==PostLensObjectAdmission.PROFILE&&ancestor.liveRegions==image.metadata.memoryProfiles.getValue(PostLensObjectAdmission.PROFILE).sortedBy{it.address})
        val app=ByteBuffer.wrap(image.decode("section5")).order(ByteOrder.LITTLE_ENDIAN)
        verifyWords((0..4).map{app.getInt(0x245cf90-0x1f28000+4*it)})
        val plan=validateLayout(selected,ancestor.liveRegions)
        val rows:(List<MemoryRegion>)->List<Map<String,Any>> = {rs->rs.map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)}}
        return RuntimeFlashStagingAdmission.Result(ancestor.ancestor,plan,ancestor.liveReport+mapOf("profile" to PROFILE,"regions" to rows(plan),
            "calcScratch" to mapOf("profile" to PROFILE,"ancestorProfile" to PostLensObjectAdmission.PROFILE,"ancestorRegions" to rows(ancestor.liveRegions),"region" to rows(listOf(added)).single(),
                "literalVirtual" to 0x245cf90,"clearAddress" to CLEAR_BASE,"clearBytes" to CLEAR_SIZE,
                "initialContent" to "HYPOTHESIS: zero native backing; original calc instructions perform actual clear","permissions" to "HYPOTHESIS RW/NX; execution unqualified",
                "physicalGeometry" to "UNKNOWN","aliasesImplemented" to false,"clearInjected" to false),
            "basis" to "Complete verified v17 plus exact page envelope of DAT/SIM calc clear. No wider heap, executable permission, alias, success or CPU write is supplied."))
    }
}
