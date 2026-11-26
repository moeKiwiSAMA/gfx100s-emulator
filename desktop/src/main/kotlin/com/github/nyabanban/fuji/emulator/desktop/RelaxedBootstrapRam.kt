package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog

/**
 * Explicit runtime DDR bootstrap regions used ONLY in user-facing `relaxed` mode when
 * `relaxedBootstrapRam: gfx100s-full-boot-ddr-v1` is configured on a session with
 * `system-memory` `profile: flashload-request-buffer-experimental-v24` and
 * `additionalRamProfile: finite-rw-or-rwx-experimental-v2`.
 *
 * Research modes (`discovery` and `strict`) NEVER accept or invoke this bootstrap.
 */
object RelaxedBootstrapRam {
    const val CONFIG_KEY = "relaxedBootstrapRam"
    const val PROFILE = "gfx100s-full-boot-ddr-v1"
    private const val SOURCE_PROFILE = "relaxed-full-boot-ddr-v1"
    private val ZERO_COMMIT = "0".repeat(40)

    val REGIONS: List<MemoryRegion> = listOf(
        MemoryRegion("relaxed-cjk-font-gap-00a80000", 0x00a80000L, 0x80000L, 3),
        MemoryRegion("relaxed-cjk-font-gap-00da0000", 0x00da0000L, 0x80000L, 3),
        MemoryRegion("relaxed-cjk-font-gap-00e7e000", 0x00e7e000L, 0xa2000L, 3),
        MemoryRegion(Method4OutputAdmission.REGION, Method4OutputAdmission.BASE, Method4OutputAdmission.SIZE, 7),
        MemoryRegion("ui-text-scratch-05608000", 0x05608000L, 0x2000L, 3),
        MemoryRegion("ui-text-scratch-tail-0560a000", 0x0560a000L, 0x49000L, 3),
        MemoryRegion("guest-zero-buffer-0569e000", 0x0569e000L, 0x100000L, 3),
        // DAT Face Detection Engine (0xFFF7C000) Pass 2 candidate DMA workspace (0x0593d200..0x05941200).
        MemoryRegion("face-engine-dram-05901000", 0x05901000L, 0xec000L, 3),
        MemoryRegion("graphics-copy-destination-059ed000", 0x059ed000L, 0x753000L, 3),
        MemoryRegion("graphics-copy-destination-06140000", 0x06140000L, 0x753000L, 3),
        MemoryRegion("graphics-fill-surface-06893000", 0x06893000L, 0x753000L, 3),
        MemoryRegion("ui-graphics-heap-06fe6000", 0x06fe6000L, 0xa00000L, 3),
        // Real GFX100S LPDDR4 gaps between xlog-second (0x08113000) and staging (0x2a69c000),
        // split at the 0x10000000 / 0x20000000 boundaries so linux-contiguous-256m filters
        // contained regions cleanly. Covers capture pool tails mapped via ARMv7 supersections
        // (e.g. Shot #2 TTBR0[0x928]=0x0c156c16 -> 0x0c8fb328 and 0x198fb328).
        MemoryRegion("ddr-pool-08113000", 0x08113000L, 0x07eed000L, 3),
        MemoryRegion("ddr-pool-10000000", 0x10000000L, 0x09082000L, 3),
        MemoryRegion("ddr-pool-190a2000", 0x190a2000L, 0x01028000L, 3),
        MemoryRegion("setup-menu-dram-1a0ca000", 0x1a0ca000L, 0x2d2000L, 3),
        MemoryRegion("ddr-pool-1a59c000", 0x1a59c000L, 0x05a64000L, 3),
        MemoryRegion("ddr-pool-20000000", 0x20000000L, 0x0a69c000L, 3),
        MemoryRegion("still-capture-and-play-disp-2abdc000", 0x2abdc000L, 0xac0000L, 3),
        MemoryRegion("still-capture-and-play-di-2bbdc000", 0x2bbdc000L, 0x9b4000L, 3),
        // Real GFX100S 32-bit LPDDR4 address space mapped 1:1 in TTBR0 (0x00120000) above
        // calc-fixed-workspace (0x2c59e000) up to the 0xF0000000 flash/MMIO boundary. Covers all
        // STILL/EVF/MOVIE live-view rings, Play/Zoom/EXIF workspaces, multi-shot/Burst/RAW+FINE/
        // Multi-Exposure/Pixel-Shift capture ring pools (section5 0x02728a70..0x02735000, including
        // Shot #2 0x928eb240 / 0x928fb328), and Movie recording pools without unmapped holes.
        // Backed by demand-paged zero OS virtual memory in Dynarmic so untouched pages consume 0 RSS.
        MemoryRegion("ddr-pool-2c59e000", 0x2c59e000L, 0x13a62000L, 3),
        MemoryRegion("ddr-pool-40000000", 0x40000000L, 0x20000000L, 3),
        MemoryRegion("ddr-pool-60000000", 0x60000000L, 0x20000000L, 3),
        MemoryRegion("ddr-pool-80000000", 0x80000000L, 0x20000000L, 3),
        MemoryRegion("ddr-pool-a0000000", 0xa0000000L, 0x20000000L, 3),
        MemoryRegion("ddr-pool-c0000000", 0xc0000000L, 0x20000000L, 3),
        MemoryRegion("ddr-pool-e0000000", 0xe0000000L, 0x10000000L, 3),
    )

    /**
     * Real GFX100S upper 36-bit LPDDR4 physical RAM bank (`PA 0x1_0000_0000..0x2_0000_0000`)
     * accessed by SPX4 Cortex-A7 via ARMv7-A 16 MiB Short-Descriptor Supersections
     * (`kernel.bin @ 0x000585e4`, `L1[23:20] = PA[35:32]`, `L1[8:5] = PA[39:36]`).
     *
     * Covers DAT `section5 @ 0x02743368` & `0x02725320` Bank 2/3 pools:
     * - `VA 0xD3000000..0xD8FFFFFF -> PA 0x1_01000000..0x1_06FFFFFF` (4K Movie raw/defect-pixel buffers)
     * - `VA 0xD9000000..0xD9FFFFFF -> PA 0x1_7F000000..0x1_7FFFFFFF`
     * - `VA 0xDA000000..0xDAFFFFFF -> PA 0x1_9F000000..0x1_9FFFFFFF`
     * - Capture/Burst pools at `PA 0x1_0C000000` and `PA 0x1_A0000000..0x1_BFFFFFFF`
     *
     * Mapped ONLY in user-facing `relaxed` mode with demand-paged zero OS virtual memory
     * (`MAP_NORESERVE` / `VirtualAlloc`, 0 bytes RSS for untouched pages) so high-bank writes
     * never alias onto low 32-bit `PA 0x01000000..0x06FFFFFF` executable firmware (`BUG-V3-7-1`).
     */
    val HIGH_PA_REGIONS: List<MemoryRegion> = listOf(
        MemoryRegion("ddr-high-100000000", 0x1_0000_0000L, 0x2000_0000L, 3),
        MemoryRegion("ddr-high-120000000", 0x1_2000_0000L, 0x2000_0000L, 3),
        MemoryRegion("ddr-high-140000000", 0x1_4000_0000L, 0x2000_0000L, 3),
        MemoryRegion("ddr-high-160000000", 0x1_6000_0000L, 0x2000_0000L, 3),
        MemoryRegion("ddr-high-180000000", 0x1_8000_0000L, 0x2000_0000L, 3),
        MemoryRegion("ddr-high-1a0000000", 0x1_a000_0000L, 0x2000_0000L, 3),
        MemoryRegion("ddr-high-1c0000000", 0x1_c000_0000L, 0x2000_0000L, 3),
        MemoryRegion("ddr-high-1e0000000", 0x1_e000_0000L, 0x2000_0000L, 3),
    )

    fun selected(config: Map<String, Any?>, relaxed: Boolean): Boolean {
        if (!config.containsKey(CONFIG_KEY)) return false
        require(relaxed) { "$CONFIG_KEY requires executionMode: relaxed" }
        require(config[CONFIG_KEY] == PROFILE) { "Unknown $CONFIG_KEY profile: ${config[CONFIG_KEY]}" }
        return true
    }

    fun buildPlan(
        catalog: AddressCatalog,
        baseProfile: String,
        baseRegions: List<MemoryRegion>,
    ): AdditionalRamPlan = AdditionalRamPlan(
        baseProfile = baseProfile,
        sourceProfile = SOURCE_PROFILE,
        sourceCommit = ZERO_COMMIT,
        catalogSha256 = catalog.artifactSha256,
        baseRegions = baseRegions,
        additionalRegions = REGIONS.filter { candidate ->
            baseRegions.none { base ->
                base.address <= candidate.address && candidate.address + candidate.size <= base.address + base.size
            }
        },
        profile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE,
    )
}
