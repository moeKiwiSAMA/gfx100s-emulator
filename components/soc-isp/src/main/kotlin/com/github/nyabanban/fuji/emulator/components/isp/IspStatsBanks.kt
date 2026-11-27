package com.github.nyabanban.fuji.emulator.components.isp

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder

/** Physical statistics DMA destinations visible to the CPU after a frame completes. */
internal class IspStatsBanks {
    private val heap = MemorySegment.ofArray(ByteArray(SIZE))
    private var storage = heap
    val nativeBound: Boolean get() = storage.isNative

    fun bind(native: MemorySegment?) {
        val target = native ?: heap
        require(target.byteSize() == SIZE.toLong())
        if (storage !== target) {
            target.copyFrom(storage)
            storage = target
        }
    }

    fun publish(source: MemorySegment, coreWords: LongArray, aeReady: Boolean, histogramReady: Boolean) {
        for (group in groups) {
            val ready = if (group.offset >= IspStatsSramEngine.SEL33_HIST_Y_OFF) histogramReady else aeReady
            if (!ready) continue
            val index = group.register / Int.SIZE_BYTES
            val address = ((coreWords[index + 1] and 3L) shl 32) or coreWords[index]
            val destination = address - BASE
            if (destination < 0 || destination > SIZE - group.size) continue
            MemorySegment.copy(source, group.offset.toLong(), storage, destination, group.size.toLong())
        }
    }

    fun read(bank: Int, offset: Int, size: Int): Long {
        val index = (bank * IspStatsSramEngine.BANK_SIZE + offset).toLong()
        return when (size) {
            1 -> storage.get(ValueLayout.JAVA_BYTE, index).toUByte().toLong()
            2 -> storage.get(shortLayout, index).toUShort().toLong()
            4 -> storage.get(intLayout, index).toUInt().toLong()
            else -> error("Invalid stats SRAM read width $size")
        }
    }

    fun save(): ByteArray = storage.toArray(ValueLayout.JAVA_BYTE)

    fun restore(bytes: ByteArray?) {
        if (bytes == null) storage.fill(0) else storage.copyFrom(MemorySegment.ofArray(bytes))
    }

    companion object {
        const val SIZE = 2 * IspStatsSramEngine.BANK_SIZE
        private const val BASE = 0xfff10000L
        private val shortLayout = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
        private val intLayout = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
        private data class Group(val register: Int, val offset: Int, val size: Int)

        // S5 0x02370170 selects the content. 0x02370390 programs the corresponding
        // low/high DMA address pair. Selectors 24 and 25 use the separate PDAF DDR ring.
        private val groups = arrayOf(
            Group(0x22c, 0x0000, 0x1000), Group(0x234, 0x1000, 0x0060),
            Group(0x254, 0x1060, 0x1000), Group(0x25c, 0x2060, 0x0140),
            Group(0x244, 0x21a0, 0x0400), Group(0x24c, 0x25a0, 0x0400),
            Group(0x274, 0x29a0, 0x0020), Group(0x27c, 0x29c0, 0x0020),
            Group(0x284, 0x29e0, 0x0020), Group(0x28c, 0x2a00, 0x0200),
            Group(0x294, 0x2c00, 0x0200), Group(0x29c, 0x2e00, 0x0200),
            Group(0x2a4, 0x3000, 0x0200), Group(0x2ac, 0x3200, 0x0800),
            Group(0x264, 0x3a00, 0x0300), Group(0x26c, 0x3d00, 0x0300),
            Group(0x23c, 0x4000, 0x2000), Group(0x2b4, 0x6000, 0x0400),
            Group(0x2bc, 0x6400, 0x0400), Group(0x2c4, 0x6800, 0x0400),
            Group(0x2cc, 0x6c00, 0x0400),
        )
    }
}
