package com.github.nyabanban.fuji.emulator.api

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Optional zero-copy direct guest-RAM capability implemented by native-backed [DmaMemory]
 * adapters (such as Dynarmic dual-view fastmem or off-heap guest RAM arenas).
 *
 * Allows bulk DMA engines (`soc-dma` RAM-to-RAM and HDMAC LLI scatter-gather transfers) to copy
 * directly between off-heap guest RAM segments via native [MemorySegment.copy] without
 * intermediate JVM heap bounce buffers, while preserving guest JIT code-cache coherency via
 * [notifyDirectWrite].
 */
interface DirectGuestRamPort : DmaMemory {
    /**
     * Returns a bounded off-heap [MemorySegment] covering `[address, address + size)` when the
     * range lies within a single contiguous host-accessible guest RAM backing, or `null` when
     * the caller must fall back to [DmaMemory.read]/[DmaMemory.write] (e.g., cross-region spans,
     * single-view fastmem, 40-bit high RAM, or heap mock memories).
     *
     * Implementations MUST enforce the same bounds and owner-thread checks as [check].
     */
    fun sliceDirect(address: Long, size: Long, write: Boolean): MemorySegment?

    /**
     * Notifies the CPU backend that `[address, address + size)` was mutated through a direct
     * [MemorySegment] write so JIT translation caches (e.g. `fuji_dyn_invalidate_range`) and
     * exclusive monitors remain coherent with guest memory.
     */
    fun notifyDirectWrite(address: Long, size: Long)

    /**
     * Performs a zero-copy RAM-to-RAM transfer `[srcAddress, srcAddress + size)` ->
     * `[dstAddress, dstAddress + size)` via native off-heap [MemorySegment.copy] (`memmove`
     * semantics for overlapping ranges). Returns `true` when completed directly or `false` if
     * callers should fall back to chunked [read]/[write].
     */
    fun copyWithin(srcAddress: Long, dstAddress: Long, size: Long): Boolean {
        if (size <= 0L) return true
        val srcSeg = sliceDirect(srcAddress, size, write = false) ?: return false
        val dstSeg = sliceDirect(dstAddress, size, write = true) ?: return false
        MemorySegment.copy(srcSeg, 0L, dstSeg, 0L, size)
        notifyDirectWrite(dstAddress, size)
        return true
    }

    /**
     * Performs a zero-copy fixed-address pattern fill into `[dstAddress, dstAddress + size)`
     * repeating the `width`-byte value at `srcAddress` (`width` in `1..8`). Returns `true` when
     * completed directly or `false` to fall back to chunked [read]/[write].
     */
    fun fillPatternDirect(srcAddress: Long, dstAddress: Long, width: Int, size: Long): Boolean {
        if (size <= 0L) return true
        if (width !in 1..8) return false
        val srcSeg = sliceDirect(srcAddress, width.toLong(), write = false) ?: return false
        val dstSeg = sliceDirect(dstAddress, size, write = true) ?: return false
        if (width == 1) {
            dstSeg.fill(srcSeg.get(ValueLayout.JAVA_BYTE, 0L))
        } else {
            var filled = minOf(width.toLong(), size)
            MemorySegment.copy(srcSeg, 0L, dstSeg, 0L, filled)
            while (filled < size) {
                val step = minOf(filled, size - filled)
                MemorySegment.copy(dstSeg, 0L, dstSeg, filled, step)
                filled += step
            }
        }
        notifyDirectWrite(dstAddress, size)
        return true
    }
}
