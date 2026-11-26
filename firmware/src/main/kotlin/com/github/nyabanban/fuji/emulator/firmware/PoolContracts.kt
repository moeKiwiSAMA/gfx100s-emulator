package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** FW-021: selected DAT producers/consumers, not measured DRAM geometry or completed initialization. */
object PoolContracts {
    const val POOL_PROFILE = "kernel-byte-pool-experimental-v7"
    const val TRACE_PROFILE = "kernel-trace-pools-experimental-v8"

    private fun word(kernel: ByteArray, address: Long): Long {
        require(address and 3 == 0L && address >= 0x40000 && address - 0x40000 <= kernel.size - 4L) {
            "DAT pool instruction/literal outside initialized kernel"
        }
        return ByteBuffer.wrap(kernel, (address - 0x40000).toInt(), 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    }
    private fun immediate(w: Long) = Integer.rotateRight((w and 255).toInt(), (((w ushr 8) and 15) * 2).toInt()).toLong() and 0xffffffffL
    private fun imm16(w: Long) = (w and 0xfff) or ((w ushr 4) and 0xf000)
    private fun pair(kernel: ByteArray, pc: Long) = imm16(word(kernel, pc)) or (imm16(word(kernel, pc + 4)) shl 16)
    private fun literal(kernel: ByteArray, pc: Long, rd: Int): Long {
        val w = word(kernel, pc)
        require(w and 0xfffff000L == (0xe59f0000L or (rd.toLong() shl 12))) { "DAT pool literal consumer differs" }
        return pc + 8 + (w and 0xfff)
    }
    private fun target(kernel: ByteArray, pc: Long): Long {
        val w = word(kernel, pc)
        require(w and 0xff000000L == 0xeb000000L) { "DAT record initializer call differs" }
        return pc + 8 + ((w.toInt() shl 8 shr 8).toLong() shl 2)
    }
    private fun check(kernel: ByteArray, expected: Map<Long, Long>, scope: String) {
        expected.forEach { (pc, value) -> require(word(kernel, pc) == value) { "DAT $scope differs at ${pc.toString(16)}" } }
    }
    private fun reportRegions(regions: List<MemoryRegion>) = regions.map {
        mapOf("id" to it.id, "address" to it.address, "size" to it.size, "permissions" to it.permissions)
    }
    private fun validRegions(regions: List<MemoryRegion>) {
        require(regions.map { it.id }.distinct().size == regions.size) { "duplicate pool RAM region id" }
        regions.forEach { require(it.id.isNotBlank() && it.address >= 0 && it.size > 0 && it.address <= 0x100000000L - it.size && it.address % 4096 == 0L && it.size % 4096 == 0L && it.permissions in 1..7) { "invalid pool RAM region" } }
    }

    data class BytePool(
        val initialLiteralAddress: Long, val initialLiteral: Long, val producerAdd: Long,
        val base: Long, val byteSize: Long, val footerOwnerAddress: Long,
        val footerBackLinkAddress: Long, val unusedPointerAddress: Long, val control: Long,
        val createdMarker: Long, val checkedInstructions: List<Long>
    ) {
        val endExclusive get() = base + byteSize
        fun region() = MemoryRegion("kernel-byte-pool", base and -4096L, ((endExclusive + 4095) and -4096L) - (base and -4096L), 3)
        fun report(): Map<String, Any> = mapOf(
            "initialLiteralAddress" to initialLiteralAddress, "initialLiteral" to initialLiteral, "producerAdd" to producerAdd,
            "base" to base, "byteSize" to byteSize, "endExclusive" to endExclusive,
            "footerOwnerAddress" to footerOwnerAddress, "footerBackLinkAddress" to footerBackLinkAddress,
            "unusedPointerAddress" to unusedPointerAddress, "control" to control, "createdMarker" to createdMarker,
            "roundedRegion" to reportRegions(listOf(region())).single(), "checkedInstructions" to checkedInstructions
        )
    }
    fun pool(kernel: ByteArray): BytePool {
        val expected = linkedMapOf(
            0x40614L to 0xe59f1014L, 0x40618L to 0xe59f2014L, 0x4061cL to 0xe2811008L, 0x40620L to 0xe5821000L,
            0x5569cL to 0xe2502000L, 0x556a4L to 0xe30d3148L, 0x556a8L to 0xe3403005L, 0x556acL to 0xe5832000L,
            0x556b0L to 0xe3a03a01L, 0x556bcL to 0xe30d014cL, 0x556c0L to 0xe3400005L, 0x556c4L to 0xebffae21L,
            0x40f58L to 0xe1a04000L, 0x40f60L to 0xe1a06002L, 0x40f64L to 0xe1a05003L,
            0x40f74L to 0xe3c53003L, 0x40f9cL to 0xe0863003L, 0x40fa0L to 0xe5034004L,
            0x40fa4L to 0xe5236008L, 0x40fa8L to 0xe5863000L,
            0x40fc4L to 0xe3053445L, 0x40fc8L to 0xe3443259L, 0x40fccL to 0xe5843000L
        )
        check(kernel, expected, "byte-pool producer/size/footer contract")
        val literalAddress = literal(kernel, 0x40614, 1)
        val initial = word(kernel, literalAddress)
        val add = immediate(word(kernel, 0x4061c))
        val base = initial + add
        val size = immediate(word(kernel, 0x556b0)) and immediate(word(kernel, 0x40f74)).inv() and 0xffffffffL
        val unusedPointer = word(kernel, literal(kernel, 0x40618, 2))
        val control = pair(kernel, 0x556bc)
        val marker = pair(kernel, 0x40fc4)
        val owner = base + size - (word(kernel, 0x40fa0) and 0xfff)
        val backLink = base + size - (word(kernel, 0x40fa4) and 0xfff)
        require(literalAddress == 0x40630L && initial == 0x1c4014L && add == 8L && base == 0x1c401cL && size == 4096L && owner == 0x1c5018L && backLink == 0x1c5014L && unusedPointer == 0x5b690L && control == 0x5d14cL && marker == 0x42595445L && target(kernel, 0x556c4) == 0x40f50L) {
            "DAT bounded byte-pool geometry differs"
        }
        return BytePool(literalAddress, initial, add, base, size, owner, backLink, unusedPointer, control, marker, expected.keys.toList())
    }

    data class RecordBuffer(val id: Int, val address: Long, val size: Long) {
        fun report() = mapOf("id" to id, "address" to address, "size" to size)
    }
    data class TraceBuffers(
        val buffers: List<RecordBuffer>, val strideBytes: Long, val firstAddress: Long,
        val endExclusive: Long, val initializer: Long, val zeroLoopEnd: Long,
        val headerCopyBytes: Long, val zeroStartOffset: Long, val zeroEndOffset: Long,
        val zeroIterations: Long, val zeroWordsPerIteration: Int, val zeroStepBytes: Long,
        val checkedInstructions: List<Long>
    ) {
        val totalBytes get() = endExclusive - firstAddress
        val zeroBytesPerBuffer get() = zeroIterations * zeroWordsPerIteration * 4
        fun region() = MemoryRegion("kernel-record-buffers", firstAddress and -4096L, ((endExclusive + 4095) and -4096L) - (firstAddress and -4096L), 3)
        fun report(): Map<String, Any> = mapOf(
            "buffers" to buffers.map { it.report() }, "count" to buffers.size, "strideBytes" to strideBytes,
            "firstAddress" to firstAddress, "endExclusive" to endExclusive, "totalBytes" to totalBytes,
            "initializer" to initializer, "zeroLoopEnd" to zeroLoopEnd, "headerCopyBytes" to headerCopyBytes,
            "zeroStartOffset" to zeroStartOffset, "zeroEndOffset" to zeroEndOffset, "zeroIterations" to zeroIterations,
            "zeroWordsPerIteration" to zeroWordsPerIteration, "zeroStepBytes" to zeroStepBytes,
            "zeroBytesPerBuffer" to zeroBytesPerBuffer, "checkedInstructions" to checkedInstructions
        )
    }
    fun trace(kernel: ByteArray): TraceBuffers {
        val expected = linkedMapOf(
            0x56608L to 0xe3a04001L, 0x56618L to 0xe3a01000L, 0x56620L to 0xe3040004L, 0x56624L to 0xe3400014L, 0x56628L to 0xebffffbaL,
            0x5662cL to 0xe1a01004L, 0x56630L to 0xe59f0094L, 0x56634L to 0xebffffb7L,
            0x56638L to 0xe3a01002L, 0x5663cL to 0xe59f008cL, 0x56640L to 0xebffffb4L,
            0x56644L to 0xe3a01003L, 0x56648L to 0xe59f0084L, 0x5664cL to 0xebffffb1L,
            0x56650L to 0xe3a01004L, 0x56654L to 0xe3040000L, 0x56658L to 0xe3400012L, 0x5665cL to 0xebffffadL,
            0x56520L to 0xe1a04000L, 0x56524L to 0xe1a05001L, 0x56528L to 0xe3a02014L,
            0x56584L to 0xe1a03004L, 0x56588L to 0xe2841b7fL, 0x5658cL to 0xe2811d0fL, 0x56590L to 0xe3a02000L,
            0x56594L to 0xe5832034L, 0x56598L to 0xe5832038L, 0x5659cL to 0xe583203cL, 0x565a0L to 0xe5832040L,
            0x565a4L to 0xe2833010L, 0x565a8L to 0xe1530001L, 0x565acL to 0x1afffff8L, 0x565b0L to 0xe8bd8830L
        )
        check(kernel, expected, "record IDs/initializer/zero loop")
        val ids = listOf(immediate(word(kernel, 0x56618)), immediate(word(kernel, 0x56608)), immediate(word(kernel, 0x56638)), immediate(word(kernel, 0x56644)), immediate(word(kernel, 0x56650)))
        require(ids == listOf(0L, 1L, 2L, 3L, 4L)) { "DAT record IDs differ; these are not CPU IDs" }
        val bases = listOf(pair(kernel, 0x56620), word(kernel, literal(kernel, 0x56630, 0)), word(kernel, literal(kernel, 0x5663c, 0)), word(kernel, literal(kernel, 0x56648, 0)), pair(kernel, 0x56654))
        val calls = listOf(0x56628L, 0x56634L, 0x56640L, 0x5664cL, 0x5665cL).map { target(kernel, it) }
        require(calls.distinct() == listOf(0x56518L)) { "DAT record calls do not share the verified initializer" }
        val ordered = bases.sorted()
        val stride = ordered[1] - ordered[0]
        val end = ordered.last() + stride
        require(ordered.first() == 0x124000L && stride == 0x20004L && ordered.zipWithNext().all { (a, b) -> b - a == stride } && end == word(kernel, 0x40630) && end == 0x1c4014L) { "DAT five-record pointer extents differ" }
        val step = immediate(word(kernel, 0x565a4))
        val limitOffset = immediate(word(kernel, 0x56588)) + immediate(word(kernel, 0x5658c))
        val offsets = listOf(0x56594L, 0x56598L, 0x5659cL, 0x565a0L).map { word(kernel, it) and 0xfff }
        require(step == 16L && limitOffset % step == 0L && offsets == listOf(0x34L, 0x38L, 0x3cL, 0x40L))
        val iterations = limitOffset / step
        val zeroEnd = limitOffset - step + offsets.last() + 4
        require(iterations == 8188L && zeroEnd == 0x1fff4L && zeroEnd < stride)
        return TraceBuffers(bases.mapIndexed { id, base -> RecordBuffer(id, base, stride) }, stride, ordered.first(), end, calls.first(), 0x565b0,
            immediate(word(kernel, 0x56528)), offsets.first(), zeroEnd, iterations, offsets.size, step, expected.keys.toList())
    }

    /** Pure layout calculations for public fixture tests; only verifyPool/verifyTrace authorize selected DAT plans. */
    fun poolRegions(base: List<MemoryRegion>, pool: BytePool): List<MemoryRegion> {
        validRegions(base)
        val extra = pool.region()
        require(base.none { it.address < extra.address + extra.size && extra.address < it.address + it.size }) { "byte-pool overlaps existing permission window" }
        return (base + extra).sortedBy { it.address }
    }
    fun traceRegions(poolRegions: List<MemoryRegion>, trace: TraceBuffers): List<MemoryRegion> {
        validRegions(poolRegions)
        val merged = ArrayList<MemoryRegion>()
        for (r in (poolRegions + trace.region()).sortedBy { it.address }) {
            val last = merged.lastOrNull()
            if (last != null && r.address <= last.address + last.size) {
                require(r.address == last.address + last.size || r.permissions == last.permissions) { "record-buffer permission overlap differs" }
                if (r.permissions == last.permissions) merged[merged.lastIndex] = last.copy(size = maxOf(last.address + last.size, r.address + r.size) - last.address)
                else merged.add(r)
            } else merged.add(r)
        }
        return merged.toList()
    }
    private fun requireV6(image: FirmwareImage, v6: SegmentsContracts.VerifiedPlan) {
        require(image.metadata.id == "gfx100s-213" && image.metadata.sections.single { it.name == "kernel" }.address == 0x40000L)
        require(v6.report["profile"] == ResetContracts.PROFILE && v6.regions == image.metadata.memoryProfiles.getValue(ResetContracts.PROFILE).sortedBy { it.address }) { "pool contract requires the selected verified v6 plan" }
    }
    fun verifyPool(image: FirmwareImage, regions: List<MemoryRegion>, v6: SegmentsContracts.VerifiedPlan): SegmentsContracts.VerifiedPlan {
        requireV6(image, v6)
        val p = pool(image.decode("kernel"))
        val all = poolRegions(v6.regions, p)
        require(all == regions.sortedBy { it.address }) { "selected byte-pool RAM differs from DAT extent/permissions" }
        return SegmentsContracts.VerifiedPlan(all, v6.report + mapOf("profile" to POOL_PROFILE, "bytePool" to p.report(), "regions" to reportRegions(all),
            "basis" to "Verified v6 plus literal+8/4096-byte pool rounded to 001C4000..001C5FFF. Zero bytes, 4KiB rounding and RW-NX remain HYP; intervening holes stay unmapped, no physical capacity or initialization-completion claim"))
    }
    fun verifyTrace(image: FirmwareImage, regions: List<MemoryRegion>, v6: SegmentsContracts.VerifiedPlan): SegmentsContracts.VerifiedPlan {
        requireV6(image, v6)
        val kernel = image.decode("kernel")
        val p = pool(kernel)
        val t = trace(kernel)
        val all = traceRegions(poolRegions(v6.regions, p), t)
        require(all == regions.sortedBy { it.address }) { "selected record RAM differs from DAT extent/permission merge" }
        return SegmentsContracts.VerifiedPlan(all, v6.report + mapOf("profile" to TRACE_PROFILE, "bytePool" to p.report(), "traceBuffers" to t.report(), "regions" to reportRegions(all),
            "basis" to "Verified v6 plus DAT pool and five record IDs0..4, each 0x20004 bytes, ending at literal001C4014. Merge only equal-permission touching/overlapping pages, retaining leftmost id (also adjacent stacks). RW-NX/zero bytes/4KiB rounding remain HYP; holes preserved. Declared five buffers do not imply five completed initializers or five CPUs"))
    }
}
