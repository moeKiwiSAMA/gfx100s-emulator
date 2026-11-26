package com.github.nyabanban.fuji.emulator.api

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/**
 * Declarative descriptor for one side-effect-free MMIO register slot inside a [MmioMirrorWindowSpec].
 *
 * The native CPU bridge serves guest MMIO reads matching `(physBase + offset, size == widthBytes)`
 * lock-free directly from the shared off-heap [MemorySegment] whenever the slot's bit in `validMask`
 * is `1`. Any non-matching access width, unmirrored offset (e.g., FIFO pop registers), or cleared
 * valid bit falls straight through to the component's standard Java [MmioDevice.read].
 */
data class MmioMirrorSlotSpec(
    val offset: Long,
    val widthBytes: Int,
) {
    init {
        require(offset in 0L..0xFFFF_FFFFL) { "MMIO mirror slot offset out of u32 range: $offset" }
        require(widthBytes == 1 || widthBytes == 2 || widthBytes == 4) {
            "MMIO mirror slot widthBytes must be 1, 2, or 4 (got $widthBytes)"
        }
        require((offset and (widthBytes - 1).toLong()) == 0L) {
            "MMIO mirror slot offset 0x${offset.toString(16)} must be aligned to $widthBytes bytes"
        }
    }
}

/**
 * Declarative specification of one peripheral MMIO window eligible for lock-free native read mirroring.
 *
 * Strictly opt-in and component-declared: the native bridge contains zero hardcoded peripheral logic.
 */
data class MmioMirrorWindowSpec(
    val portName: String,
    val windowSize: Long,
    val slots: List<MmioMirrorSlotSpec>,
) {
    init {
        require(portName.isNotBlank()) { "MMIO mirror window portName must not be blank" }
        require(windowSize in 1L..0x1_0000_0000L) { "MMIO mirror windowSize out of range: $windowSize" }
        require(slots.size in 1..MAX_SLOTS) { "MMIO mirror slot count must be in 1..$MAX_SLOTS (got ${slots.size})" }
        val seen = HashSet<Long>(slots.size * 2)
        for (slot in slots) {
            require(slot.offset + slot.widthBytes <= windowSize) {
                "MMIO mirror slot [0x${slot.offset.toString(16)}..+${slot.widthBytes}] exceeds windowSize 0x${windowSize.toString(16)}"
            }
            require(seen.add(slot.offset)) {
                "Duplicate MMIO mirror slot offset 0x${slot.offset.toString(16)} in $portName"
            }
        }
    }

    companion object {
        const val MAX_SLOTS: Int = 64
    }
}

/**
 * Shared off-heap mirror block handle bound to a single [MmioMirrorWindowSpec].
 *
 * Binary ABI layout (little-endian, 8-byte aligned, matching C++ `FujiDynMmioMirrorHeader` +
 * `FujiDynMmioMirrorSlot`):
 * - `+0x00`: `u64 valid_mask` (bit `i` = 1 enables lock-free read of slot `i`)
 * - `+0x08`: `u32 slot_count` (`1..64`)
 * - `+0x0C`: `u32 reserved` (`0`)
 * - `+0x10 + i * 0x10`: slot `i`:
 *   - `+0x00`: `u32 offset`
 *   - `+0x04`: `u32 width_bytes` (`1`, `2`, or `4`)
 *   - `+0x08`: `u32 value` (zero-extended current register value)
 *   - `+0x0C`: `u32 reserved` (`0`)
 *
 * All value & mask updates use release semantics (`VarHandle.setRelease`) pairing with C++
 * `std::atomic_ref::load(std::memory_order_acquire)` so concurrent SMP host threads never tear.
 */
class MmioMirrorHandle(
    val spec: MmioMirrorWindowSpec,
    val segment: MemorySegment,
) {
    val slotCount: Int = spec.slots.size
    val allSlotsMask: Long = if (slotCount == 64) -1L else (1L shl slotCount) - 1L
    private var validMask: Long = 0L

    init {
        val needed = requiredBytes(slotCount)
        require(segment.byteSize() >= needed) {
            "MMIO mirror MemorySegment too small: ${segment.byteSize()} < $needed"
        }
        require((segment.address() and 7L) == 0L) {
            "MMIO mirror MemorySegment must be 8-byte aligned"
        }
        segment.fill(0.toByte())
        INT_VH.setRelease(segment, OFF_SLOT_COUNT, slotCount)
        for (i in 0 until slotCount) {
            val base = HEADER_BYTES + i.toLong() * SLOT_BYTES
            val slot = spec.slots[i]
            INT_VH.setRelease(segment, base + SLOT_OFF_OFFSET, slot.offset.toInt())
            INT_VH.setRelease(segment, base + SLOT_OFF_WIDTH, slot.widthBytes)
            INT_VH.setRelease(segment, base + SLOT_OFF_VALUE, 0)
        }
        LONG_VH.setRelease(segment, OFF_VALID_MASK, 0L)
    }

    /** Publishes [value] to [slotIndex] with release semantics without altering `validMask`. */
    fun writeSlotRaw(slotIndex: Int, value: Int) {
        val off = HEADER_BYTES + slotIndex.toLong() * SLOT_BYTES + SLOT_OFF_VALUE
        INT_VH.setRelease(segment, off, value)
    }

    /** Publishes [value] to [slotIndex] with release semantics and ensures its valid bit is set. */
    fun writeSlot(slotIndex: Int, value: Int) {
        val off = HEADER_BYTES + slotIndex.toLong() * SLOT_BYTES + SLOT_OFF_VALUE
        INT_VH.setRelease(segment, off, value)
        val bit = 1L shl slotIndex
        if ((validMask and bit) == 0L) {
            validMask = validMask or bit
            LONG_VH.setRelease(segment, OFF_VALID_MASK, validMask)
        }
    }

    /** Enables or disables native mirroring for a single slot. */
    fun setSlotValid(slotIndex: Int, valid: Boolean) {
        val bit = 1L shl slotIndex
        val next = if (valid) (validMask or bit) else (validMask and bit.inv())
        if (next != validMask) {
            validMask = next
            LONG_VH.setRelease(segment, OFF_VALID_MASK, next)
        }
    }

    /** Atomically replaces the entire 64-bit `validMask` with release semantics. */
    fun setValidMask(mask: Long) {
        val masked = mask and allSlotsMask
        if (masked != validMask) {
            validMask = masked
            LONG_VH.setRelease(segment, OFF_VALID_MASK, masked)
        }
    }

    /** Clears all valid bits so every read falls back to Java until re-populated. */
    fun invalidateAll() {
        if (validMask != 0L) {
            validMask = 0L
            LONG_VH.setRelease(segment, OFF_VALID_MASK, 0L)
        }
    }

    /** Acquire-reads the current mirrored 32-bit value for [slotIndex]. */
    fun readSlot(slotIndex: Int): Int {
        val off = HEADER_BYTES + slotIndex.toLong() * SLOT_BYTES + SLOT_OFF_VALUE
        return INT_VH.getAcquire(segment, off) as Int
    }

    /** Acquire-reads the current 64-bit `validMask`. */
    fun currentValidMask(): Long = LONG_VH.getAcquire(segment, OFF_VALID_MASK) as Long

    companion object {
        const val HEADER_BYTES: Long = 16L
        const val SLOT_BYTES: Long = 16L
        const val OFF_VALID_MASK: Long = 0L
        const val OFF_SLOT_COUNT: Long = 8L
        const val SLOT_OFF_OFFSET: Long = 0L
        const val SLOT_OFF_WIDTH: Long = 4L
        const val SLOT_OFF_VALUE: Long = 8L

        private val LONG_VH: VarHandle =
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN).varHandle()
        private val INT_VH: VarHandle =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN).varHandle()

        fun requiredBytes(slotCount: Int): Long {
            require(slotCount in 1..MmioMirrorWindowSpec.MAX_SLOTS) {
                "Invalid MMIO mirror slotCount: $slotCount"
            }
            return HEADER_BYTES + slotCount.toLong() * SLOT_BYTES
        }
    }
}

/**
 * Optional capability implemented by peripheral [HardwareComponent]s whose side-effect-free status,
 * control, or data registers can be read lock-free by the native CPU bridge in `relaxed` mode.
 *
 * `strict` and `discovery` modes NEVER attach mirrors so qualification auditing remains 100% on Java.
 */
interface NativeMmioMirrorPort {
    /** Returns all MMIO windows & safe side-effect-free register slots this component can mirror. */
    fun describeMmioMirrorWindows(): List<MmioMirrorWindowSpec>

    /** Attaches an allocated shared off-heap [MmioMirrorHandle] for [portName] and populates initial values. */
    fun attachMmioMirror(portName: String, handle: MmioMirrorHandle)

    /** Detaches all previously attached mirrors (called on session reset/close before freeing segments). */
    fun detachMmioMirrors()
}
