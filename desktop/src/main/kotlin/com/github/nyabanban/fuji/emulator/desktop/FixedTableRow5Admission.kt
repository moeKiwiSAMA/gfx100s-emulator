package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MemoryLayout
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Observed table row5 load; backing/reset, RW/NX and physical identity remain HYPOTHESIS/UNKNOWN. */
object FixedTableRow5Admission {
    const val PROFILE="fixed-table-row5-buffer-experimental-v20"
    const val REGION="fixed-table-row5-buffer"
    const val BASE=0x8a0000L
    const val SIZE=0x40000L
    const val SOURCE=0x1fc0000L
    const val ROW=0x17474a8
    val added=MemoryRegion(REGION,BASE,SIZE,3)
    fun regionSet(inherited:List<MemoryRegion>):List<MemoryRegion> {
        require(inherited.isNotEmpty()&&inherited.none{it.id==REGION||it.address<BASE+SIZE&&BASE<it.address+it.size}) {"Fixed table row5 overlaps inherited RAM"}
        return (inherited+added).sortedBy{it.address}
    }
    fun validateLayout(selected:List<MemoryRegion>,inherited:List<MemoryRegion>):List<MemoryRegion> {
        val expected=regionSet(inherited)
        require(selected.sortedBy{it.address}==expected) {"Fixed table row5 requires complete v19 plus exactly 8A0000..8E0000 RW/NX"}
        return expected
    }
    fun verifyRow(words:List<Long>) {
        require(words==listOf(SOURCE,2L,BASE)&&words[1] shl 17==SIZE) {"Fixed table row5 source/block-count/destination differs"}
    }
    fun verifyWords(words:List<Int>,caller:Int) {
        require(words==listOf(0xe307346c.toInt(),0xe3403174.toInt(),0xe3a0000c.toInt(),0xe0040490.toInt(),0xe0831004.toInt(),0xe7930004.toInt(),0xe3a03000.toInt(),0xe5912008.toInt(),0xe5911004.toInt(),0xe1a008a0.toInt(),0xebfffa22.toInt())&&caller==0xebff57c0.toInt()) {"Fixed table row5 full normal-branch/caller words differ"}
    }
    fun verify(image:FirmwareImage,selected:List<MemoryRegion>,ancestor:RuntimeFlashStagingAdmission.Result,layout:MemoryLayout):RuntimeFlashStagingAdmission.Result {
        require(ancestor.liveReport["profile"]==RawSegment2TailAdmission.PROFILE&&ancestor.liveRegions==image.metadata.memoryProfiles.getValue(RawSegment2TailAdmission.PROFILE).sortedBy{it.address})
        val app=ByteBuffer.wrap(image.decode("section4")).order(ByteOrder.LITTLE_ENDIAN)
        verifyWords((0..10).map{app.getInt(0x129f31c-0x1021000+4*it)},app.getInt(0x12c93c8-0x1021000))
        verifyRow((0..2).map{app.getInt(ROW-0x1021000+4*it).toLong() and 0xffffffffL})
        val plan=validateLayout(selected,ancestor.liveRegions)
        // FirmwareSession checks the final complete selected live geometry before native mapping.
        layout.check(BASE,SIZE,3)
        val rows:(List<MemoryRegion>)->List<Map<String,Any>> = {rs->rs.map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)}}
        return RuntimeFlashStagingAdmission.Result(ancestor.ancestor,plan,ancestor.liveReport+mapOf("profile" to PROFILE,"regions" to rows(plan),
            "fixedTableRow5" to mapOf("profile" to PROFILE,"ancestorProfile" to RawSegment2TailAdmission.PROFILE,"ancestorRegions" to rows(ancestor.liveRegions),"region" to rows(listOf(added)).single(),
                "tableVirtual" to 0x174746c,"rowVirtual" to ROW,"rowIndex" to 5,"sourceLogical" to SOURCE,"blockCount" to 2,"transferBytes" to SIZE,"fullRequestRwPreflight" to true,
                "initialContent" to "HYPOTHESIS: zero native backing; original Flash DMA supplies bytes","permissions" to "HYPOTHESIS RW/NX; no execute expansion",
                "physicalGeometry" to "UNKNOWN","objectPurpose" to "UNKNOWN","aliasesImplemented" to false,"transfersInjected" to 0),
            "basis" to "Complete verified v19 plus only the actual table-row5 request. No gap padding, source bytes, completion or executable permission supplied."))
    }
}
