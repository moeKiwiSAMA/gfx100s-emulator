package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.MemoryLayout
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** DAT allocation and actual pending last block; native backing/reset and RW/NX remain HYPOTHESES. */
object RawSegment2TailAdmission {
    const val PROFILE="raw-segment2-tail-experimental-v19"
    const val SPAN_POLICY="contiguous-compatible-v1"
    const val REGION="segment2-allocation-tail"
    const val BASE=0x984000L
    const val SIZE=0x1c000L
    const val BLOCK_BASE=0x980000L
    const val BLOCK_SIZE=0x20000L
    val added=MemoryRegion(REGION,BASE,SIZE,3)
    fun regionSet(inherited:List<MemoryRegion>):List<MemoryRegion> {
        require(inherited.isNotEmpty()&&inherited.none{it.id==REGION||it.address<BASE+SIZE&&BASE<it.address+it.size}) {"Raw allocation tail overlaps inherited RAM"}
        return (inherited+added).sortedBy{it.address}
    }
    fun validateLayout(selected:List<MemoryRegion>,inherited:List<MemoryRegion>):List<MemoryRegion> {
        val expected=regionSet(inherited)
        require(selected.sortedBy{it.address}==expected) {"Raw allocation tail requires complete v18 plus exactly 984000..9A0000 RW/NX"}
        return expected
    }
    fun verifyRecord(words:List<Long>) {
        require(words==listOf(2L,0x8e0000L,0xa3ebdL,0L,0L,0x1dc0000L,0xc0000L,0L,0L)) {"ID2 allocation record differs"}
    }
    fun verify(image:FirmwareImage,selected:List<MemoryRegion>,ancestor:RuntimeFlashStagingAdmission.Result,layout:MemoryLayout):RuntimeFlashStagingAdmission.Result {
        require(ancestor.liveReport["profile"]==CalcScratchAdmission.PROFILE&&ancestor.liveRegions==image.metadata.memoryProfiles.getValue(CalcScratchAdmission.PROFILE).sortedBy{it.address})
        val bytes=ByteBuffer.allocate(1024);image.inputPort().read(0x140274,bytes)
        for(i in 0..<1024)bytes.put(i,(bytes.get(i).toInt() xor 255).toByte())
        val record=SegmentsContracts.derive(bytes.array()).records.single{it.id==2L}
        bytes.order(ByteOrder.LITTLE_ENDIAN)
        val words=(0..8).map{bytes.getInt(0x88+it*4).toLong() and 0xffffffffL}
        verifyRecord(words)
        require(words.take(7)==listOf(record.id,record.initializedAddress,record.initializedSize,record.bssAddress,record.bssSize,record.flashOffset,record.flashAllocation))
        val plan=validateLayout(selected,ancestor.liveRegions)
        // The final complete live geometry is checked before native mapping by FirmwareSession.
        try { layout.check(BLOCK_BASE,BLOCK_SIZE,3) } catch(e:IllegalArgumentException) {
            throw IllegalArgumentException("Raw allocation tail requires actual contiguous-compatible RW preflight",e)
        }
        val rows:(List<MemoryRegion>)->List<Map<String,Any>> = {rs->rs.map{mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions)}}
        return RuntimeFlashStagingAdmission.Result(ancestor.ancestor,plan,ancestor.liveReport+mapOf("profile" to PROFILE,"regions" to rows(plan),
            "rawSegment2Tail" to mapOf("profile" to PROFILE,"ancestorProfile" to CalcScratchAdmission.PROFILE,"ancestorRegions" to rows(ancestor.liveRegions),"region" to rows(listOf(added)).single(),
                "recordVirtual" to 0xe20088,"sourceLogical" to 0x1e60000,"lastBlockAddress" to BLOCK_BASE,"lastBlockBytes" to BLOCK_SIZE,"lastBlockRwPreflight" to true,
                "initialContent" to "HYPOTHESIS: zero native backing; original Flash DMA supplies allocation bytes","permissions" to "HYPOTHESIS RW/NX; no execute expansion",
                "physicalGeometry" to "UNKNOWN","aliasesImplemented" to false,"transfersInjected" to 0),
            "basis" to "Complete verified v18 plus the actual ID2 raw allocation tail. Actual selected MemoryLayout preflight covers the whole last block; no source bytes, completion or execute supplied."))
    }
}
