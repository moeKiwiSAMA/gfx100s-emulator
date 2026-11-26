package com.github.nyabanban.fuji.emulator.components.isp

internal object IspCoreOffsets {
    private fun rangeSteps(first: Int, lastInclusive: Int): List<Int> =
        (first..lastInclusive step 4).toList()

    /** Legacy M6.3 discovery offset list (176 words) retained for backward-compatible snapshot restore. */
    val CORE_70000_LEGACY_OFFSETS: IntArray = (
        rangeSteps(0x000, 0x104) +
            rangeSteps(0x124, 0x2d0) +
            listOf(0x2e4, 0x2e8)
        ).toIntArray()

    /**
     * Full DAT-evidenced `0xFFF70000..0xFFF702E8` (`soc-isp.core` 20-ch 3A/CDAF/RLFS/ROB stats DMA engine,
     * 187 aligned u32 registers): includes `+0x108..+0x120` (S4 0x011BD3A8 channel/filter config)
     * and `+0x2D4..+0x2E0` (S4 0x011BCA24/0x011BCB58 RLFS/RLFL sel 24/25 34-bit DDR DMA bases).
     */
    val CORE_70000_OFFSETS: IntArray = rangeSteps(0x000, 0x2e8).toIntArray()

    val CORE_70000_INDEX: Map<Int, Int> = CORE_70000_OFFSETS.withIndex().associate { (i, o) -> o to i }

    val BANK_50000_READ_OFFSETS: Set<Int> = (
        listOf(0x000) +
            rangeSteps(4, 16) +
            rangeSteps(36, 40) +
            listOf(76, 248) +
            rangeSteps(356, 368) +
            rangeSteps(388, 392) +
            listOf(548, 568, 0x248) +
            rangeSteps(588, 592) +
            listOf(612, 676, 0x2b0, 812, 828)
        ).toSet()

    val BANK_50000_WRITE_OFFSETS: Set<Int> = (
        listOf(0x000) +
            rangeSteps(4, 76) +
            rangeSteps(144, 432) +
            rangeSteps(548, 612) +
            rangeSteps(672, 980) +
            rangeSteps(4096, 4100)
        ).toSet()

    val BANK_50000_OFFSETS: IntArray = (BANK_50000_READ_OFFSETS + BANK_50000_WRITE_OFFSETS).sorted().toIntArray()
    val BANK_50000_INDEX: Map<Int, Int> = BANK_50000_OFFSETS.withIndex().associate { (i, o) -> o to i }

    val LEGACY_BANK_68000_OFFSETS: IntArray = (
        rangeSteps(0x000, 0x010) +
            rangeSteps(0x108, 0x118) +
            rangeSteps(0x200, 0x26c) +
            rangeSteps(0x300, 0x314) +
            rangeSteps(0x400, 0x4d0) +
            rangeSteps(0x500, 0x518) +
            rangeSteps(0x534, 0x568) +
            rangeSteps(0x600, 0x66c) +
            rangeSteps(0x700, 0x720) +
            rangeSteps(0x800, 0x858) +
            rangeSteps(0x900, 0x950) +
            rangeSteps(0xa00, 0xaec) +
            rangeSteps(0xc00, 0xca0) +
            listOf(0xd00)
        ).toIntArray()

    val BANK_68000_OFFSETS: IntArray = (
        LEGACY_BANK_68000_OFFSETS.toList() +
            rangeSteps(0xb00, 0xb2c)
        ).sorted().toIntArray()
    val BANK_68000_INDEX: Map<Int, Int> = BANK_68000_OFFSETS.withIndex().associate { (i, o) -> o to i }

    val CONFIG_69000_OFFSETS: IntArray = (
        listOf(0x00) + rangeSteps(0x08, 0x0c) + rangeSteps(0x14, 0x28)
        ).toIntArray()
    val CONFIG_69000_INDEX: Map<Int, Int> = CONFIG_69000_OFFSETS.withIndex().associate { (i, o) -> o to i }

    val CONFIG_69100_READ_OFFSETS: Set<Int> = (
        rangeSteps(0x000, 0x048) +
            listOf(0x050) +
            rangeSteps(0x100, 0x148) +
            listOf(0x150)
        ).toSet()
    val CONFIG_69100_WRITE_OFFSETS: Set<Int> = (
        rangeSteps(0x000, 0x040) +
            listOf(0x050) +
            rangeSteps(0x100, 0x140) +
            listOf(0x150)
        ).toSet()
    val CONFIG_69100_OFFSETS: IntArray = CONFIG_69100_READ_OFFSETS.sorted().toIntArray()
    val CONFIG_69100_INDEX: Map<Int, Int> = CONFIG_69100_OFFSETS.withIndex().associate { (i, o) -> o to i }

    val CONFIG_6B000_OFFSETS: IntArray = (
        rangeSteps(0x00, 0x14) + rangeSteps(0x20, 0x2c) + rangeSteps(0xf0, 0xf4)
        ).toIntArray()
    val CONFIG_6B000_INDEX: Map<Int, Int> = CONFIG_6B000_OFFSETS.withIndex().associate { (i, o) -> o to i }

    /** Legacy M6.3 discovery offset list (8 words) retained for backward-compatible snapshot restore. */
    val BANK_6E000_LEGACY_OFFSETS: IntArray = intArrayOf(0x000, 0x008, 0x014, 0x018, 0x020, 0x024, 0x068, 0x06c)

    /**
     * Full DAT-evidenced `0xFFF6E000..0xFFF6E070` (`CCDIF` Live RAW+OB Write-DMA, S4 0x011E333C..0x011E4224,
     * 22 aligned u32 registers): adds `+0x004`, `+0x00C/+0x010` active crop, `+0x01C`, `+0x040..+0x060`
     * start/strides/inline-34-bit bases/RAW packing/order, and `+0x070` sub-format.
     */
    val BANK_6E000_OFFSETS: IntArray = (
        rangeSteps(0x000, 0x024) +
            rangeSteps(0x040, 0x060) +
            listOf(0x068, 0x06c, 0x070)
        ).toIntArray()
    val BANK_6E000_INDEX: Map<Int, Int> = BANK_6E000_OFFSETS.withIndex().associate { (i, o) -> o to i }

    val LIVE_VIEW_CH_READ_OFFSETS: Set<Int> = (
        rangeSteps(0x00, 0x24) +
            rangeSteps(0x2c, 0x30) +
            rangeSteps(0xc0, 0xe4) +
            rangeSteps(0x100, 0x108) +
            rangeSteps(0x110, 0x12c)
        ).toSet()
    val LIVE_VIEW_CH_WRITE_OFFSETS: Set<Int> = (
        LIVE_VIEW_CH_READ_OFFSETS +
            listOf(0x28) +
            rangeSteps(0x34, 0x38) +
            rangeSteps(0x40, 0xb8)
        ).toSet()
    val LIVE_VIEW_CH_OFFSETS: IntArray = LIVE_VIEW_CH_WRITE_OFFSETS.sorted().toIntArray()
    val LIVE_VIEW_CH_INDEX: Map<Int, Int> = LIVE_VIEW_CH_OFFSETS.withIndex().associate { (i, o) -> o to i }

    val LIVE_VIEW_2_OFFSETS: IntArray = rangeSteps(0x00, 0x2c).toIntArray()
    val LIVE_VIEW_2_INDEX: Map<Int, Int> = LIVE_VIEW_2_OFFSETS.withIndex().associate { (i, o) -> o to i }

    fun validResultSurfaceOffset(offset: Int): Boolean =
        offset % 4 == 0 && (offset in 0x0000..0x113c || offset in 0x1940..0x197c)

    fun validResultSurfaceV2Offset(offset: Int): Boolean =
        offset % 4 == 0 && offset in 0x0000..0x4f9c

    fun validLut59000Offset(offset: Int): Boolean =
        offset % 4 == 0 && (offset in 0x0000..0x08fc || offset in 0x1000..0x1bfc || offset in 0x2000..0x2bfc)

    fun valid6e000ConfigWord(offset: Int, value: Long): Boolean {
        val maxLow = if (offset == 0x14 || offset == 0x20) 0x1000L else 0x4000L
        return value in 0L..0xffffffffL && (value ushr 16) <= 0x3fffL && (value and 0xffffL) in 1L..maxLow
    }
}
