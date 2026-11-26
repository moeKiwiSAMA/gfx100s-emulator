package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** FW-013 / HYP-014. Derive explicit windows; never preload the declared destinations. */
object SegmentsContracts {
    const val PROFILE = "loader-segments-experimental-v4"
    private const val WORKSPACE = 0xe20000L
    private const val STAGING = 0x2a69c000L
    data class Record(val id: Long, val initializedAddress: Long, val initializedSize: Long,
                      val bssAddress: Long, val bssSize: Long, val flashOffset: Long, val flashAllocation: Long) {
        fun report() = mapOf("id" to id,"initializedAddress" to initializedAddress,"initializedSize" to initializedSize,
            "bssAddress" to bssAddress,"bssSize" to bssSize,"flashOffset" to flashOffset,"flashAllocation" to flashAllocation)
    }
    class VerifiedPlan internal constructor(val regions: List<MemoryRegion>, val report: Map<String, Any>)
    data class Derived(val records: List<Record>, val regions: List<MemoryRegion>, val stagingSize: Long)

    /** Pure parser also used by malformed-table tests. It grants no runtime mapping. */
    fun derive(metadata: ByteArray): Derived {
        require(metadata.size == 1024) { "segment metadata must be 1024 bytes" }
        val table = ByteBuffer.wrap(metadata).order(ByteOrder.LITTLE_ENDIAN)
        fun word(at: Int) = table.getInt(at).toLong() and 0xffffffffL
        require(word(0) == WORKSPACE + 0x40) { "segment table pointer differs" }
        val rows = ArrayList<Record>(); var ended = false
        val spans = arrayListOf(WORKSPACE to WORKSPACE + 1024)
        for (at in 0x40..(1024 - 36) step 36) {
            if (word(at) == 255L) { ended = true; break }
            val r = Record(word(at),word(at+4),word(at+8),word(at+12),word(at+16),word(at+20),word(at+24))
            rows.add(r)
            for ((address,size) in listOf(r.initializedAddress to r.initializedSize,r.bssAddress to r.bssSize)) {
                // ID7's nonzero size with address zero is a record fact, not an address-zero RAM span.
                if (address == 0L || size == 0L) continue
                require(address >= 0x20000 && address <= 0xf0000000L - size) { "invalid segment RAM span" }
                spans.add(address to address + size)
            }
        }
        require(ended && rows.map { it.id } == listOf(0L,1L,2L,9L,3L,4L,5L,6L,7L,8L)) { "segment record IDs/count/terminator differ" }
        val stagingSize = rows.filter { it.id == 5L || it.id == 6L }.maxOf { it.flashAllocation }
        require(stagingSize in 1..0x1000000L) { "invalid compressed staging allocation" }
        spans.add(STAGING to STAGING + stagingSize)
        val merged = ArrayList<Pair<Long, Long>>()
        spans.map { (lo,hi) -> (lo and -4096L) to ((hi + 4095) and -4096L) }.sortedBy { it.first }.forEach { span ->
            val last = merged.lastOrNull()
            if (last != null && span.first <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second,span.second)
            else merged.add(span)
        }
        val regions = listOf(MemoryRegion("loader",0,0x20000,7)) + merged.mapIndexed { i,(lo,hi) ->
            val id = when(lo) { 0x40000L -> "kernel"; WORKSPACE -> "metadata-workspace"; STAGING -> "staging"; else -> "segment-$i" }
            MemoryRegion(id,lo,hi-lo,if(lo == STAGING) 3 else 7)
        }
        return Derived(rows.toList(),regions,stagingSize)
    }

    fun verify(image: FirmwareImage, regions: List<MemoryRegion>): VerifiedPlan {
        require(image.metadata.id == "gfx100s-213") { "segment contract requires qualified selected firmware" }
        val metadata = ByteBuffer.allocate(1024); image.inputPort().read(0x140274,metadata)
        repeat(1024) { metadata.put(it,metadata.get(it).toInt().inv().toByte()) }
        val plan = derive(metadata.array())
        require(regions.sortedBy { it.address } == plan.regions) { "selected segment RAM layout differs from DAT-derived union" }
        val input = image.inputPort()
        fun sourceHash(offset: Long, size: Long): String {
            require(offset >= 0 && size > 0 && size <= input.size - 0x274 - offset)
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var done = 0L
            while(done < size) {
                val count = minOf(buffer.size.toLong(),size-done).toInt()
                input.read(0x274 + offset + done,ByteBuffer.wrap(buffer,0,count))
                repeat(count) { buffer[it] = buffer[it].toInt().inv().toByte() }
                digest.update(buffer,0,count); done += count
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun zerosHash(size: Long): String {
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536); var left = size
            while(left > 0) { val count = minOf(left,buffer.size.toLong()).toInt(); digest.update(buffer,0,count); left -= count }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        val auxiliary = plan.records.filter { it.id == 3L || it.id == 4L }.map { r ->
            require(r.initializedAddress + r.initializedSize == r.bssAddress && r.initializedSize <= r.flashAllocation)
            r.report() + mapOf("initializedSha256" to sourceHash(r.flashOffset,r.initializedSize),
                "allocationSha256" to sourceHash(r.flashOffset,r.flashAllocation),"bssSha256" to zerosHash(r.bssSize))
        }
        val metadataHash = MessageDigest.getInstance("SHA-256").digest(metadata.array()).joinToString("") { "%02x".format(it) }
        return VerifiedPlan(plan.regions,mapOf("matches" to true,"profile" to PROFILE,"metadataSha256" to metadataHash,
            "records" to plan.records.map { it.report() },"stagingSize" to plan.stagingSize,"auxiliary" to auxiliary,
            "regions" to plan.regions.map { mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions) },
            "basis" to "Selected DAT spans and maximum ID5/6 allocation; page rounding, zero initial bytes and RWX images/RW-NX staging are HYPOTHESIS, not physical DRAM geometry"))
    }
}
