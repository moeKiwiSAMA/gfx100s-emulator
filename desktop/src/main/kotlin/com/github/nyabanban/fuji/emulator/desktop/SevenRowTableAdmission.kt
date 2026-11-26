package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MemoryLayout
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Observed seven-row table row2 load; backing/reset, RW/NX and physical identity remain HYPOTHESIS/UNKNOWN. */
object SevenRowTableAdmission {
    const val PROFILE="seven-row-table-buffer-experimental-v21"
    const val REGION="seven-row-table-buffer"
    const val BASE=0x9a0000L
    const val SIZE=0xe0000L
    const val SOURCE=0x2900000L
    const val ROW=0x1747430
    val added=MemoryRegion(REGION,BASE,SIZE,3)
    fun regionSet(inherited:List<MemoryRegion>):List<MemoryRegion> {
        require(inherited.isNotEmpty()&&inherited.none{it.id==REGION||it.address<BASE+SIZE&&BASE<it.address+it.size}) {"Seven-row table overlaps inherited RAM"}
        return (inherited+added).sortedBy{it.address}
    }
    fun validateLayout(selected:List<MemoryRegion>,inherited:List<MemoryRegion>):List<MemoryRegion> {
        val expected=regionSet(inherited)
        require(selected.sortedBy{it.address}==expected) {"Seven-row table requires complete v20 plus exactly 9A0000..A80000 RW/NX"}
        return expected
    }
    fun verifyRow(words:List<Long>) {
        require(words==listOf(SOURCE,7L,BASE)&&words[1] shl 17==SIZE) {"Seven-row table source/block-count/destination differs"}
    }
    fun verifyWords(words:List<Int>,caller:Int,bounds:List<Int>) {
        require(words==listOf(0xe3072418.toInt(),0xe3402174.toInt(),0xe3a0300c.toInt(),0xe0000093.toInt(),0xe0821000.toInt(),0xe7920000.toInt(),0xe3a03000.toInt(),0xe5912008.toInt(),0xe5911004.toInt(),0xe1a008a0.toInt(),0xebfff9eb.toInt())&&caller==0xebff57f6.toInt()&&bounds==listOf(0xe3500006.toInt(),0x8a00000d.toInt())) {"Seven-row table full normal-branch/caller words differ"}
    }
    fun verify(image:FirmwareImage,selected:List<MemoryRegion>,ancestor:RuntimeFlashStagingAdmission.Result,layout:MemoryLayout):RuntimeFlashStagingAdmission.Result {
        require(ancestor.liveReport["profile"]==FixedTableRow5Admission.PROFILE&&ancestor.liveRegions==image.metadata.memoryProfiles.getValue(FixedTableRow5Admission.PROFILE).sortedBy{it.address})
        val app=ByteBuffer.wrap(image.decode("section4")).order(ByteOrder.LITTLE_ENDIAN)
        verifyWords((0..10).map{app.getInt(0x129f3f8-0x1021000+4*it)},app.getInt(0x12c9408-0x1021000),(0..1).map{app.getInt(0x129f3e8-0x1021000+4*it)})
        verifyRow((0..2).map{app.getInt(ROW-0x1021000+4*it).toLong() and 0xffffffffL})
        val plan=validateLayout(selected,ancestor.liveRegions)
        layout.check(BASE,SIZE,3)
        val rows:(List<MemoryRegion>)->List<Map<String,Any>> = {rs->rs.map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)}}
        return RuntimeFlashStagingAdmission.Result(ancestor.ancestor,plan,ancestor.liveReport+mapOf("profile" to PROFILE,"regions" to rows(plan),
            "sevenRowTable" to mapOf("profile" to PROFILE,"ancestorProfile" to FixedTableRow5Admission.PROFILE,"ancestorRegions" to rows(ancestor.liveRegions),"region" to rows(listOf(added)).single(),
                "tableVirtual" to 0x1747418,"rowVirtual" to ROW,"rowIndex" to 2,"sourceLogical" to SOURCE,"blockCount" to 7,"transferBytes" to SIZE,"fullRequestRwPreflight" to true,
                "initialContent" to "HYPOTHESIS: zero native backing; original Flash DMA supplies bytes","permissions" to "HYPOTHESIS RW/NX; no execute expansion",
                "physicalGeometry" to "UNKNOWN","objectPurpose" to "UNKNOWN","aliasesImplemented" to false,"transfersInjected" to 0),
            "basis" to "Complete verified v20 plus only the actual seven-row-table row2 request. No gap padding, source bytes, completion or executable permission supplied."))
    }
}
