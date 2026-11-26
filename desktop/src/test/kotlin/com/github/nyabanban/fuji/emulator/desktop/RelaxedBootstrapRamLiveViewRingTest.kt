package com.github.nyabanban.fuji.emulator.desktop

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Regression test for BUG-13: RelaxedBootstrapRam must cover ALL 4 STILL/EVF PIC1 live-view ring
 * buffer slots (0x3d99a000, 0x3dbf2000, 0x3de4a000, 0x3e0a2000) and ALL MOVIE/EVF PIC1 ring slots
 * (0x8999c000..0x89e5b000 + 0x258000) without gaps or overlaps, preventing periodic black flashes
 * under the live OSD0 HUD.
 */
class RelaxedBootstrapRamLiveViewRingTest {
    private fun isCovered(addr: Long, size: Long): Boolean =
        RelaxedBootstrapRam.REGIONS.any { r -> r.address <= addr && addr + size <= r.address + r.size }

    @Test
    fun relaxedBootstrapRamCoversAllStillMovieAndEvfPic1RingSlotsWithoutOverlap() {
        val sorted = RelaxedBootstrapRam.REGIONS.sortedBy { it.address }
        sorted.zipWithNext().forEach { (a, b) ->
            assertTrue(
                a.address + a.size <= b.address,
                "Overlap between ${a.id} [0x${a.address.toString(16)}..0x${(a.address + a.size).toString(16)}) " +
                    "and ${b.id} [0x${b.address.toString(16)}..0x${(b.address + b.size).toString(16)})",
            )
        }

        val evfSlotBytes = 1280L * 960L * 2L // 0x258000
        // STILL + STILL EVF 4-slot ring
        for (slot in listOf(0x3d99_a000L, 0x3dbf_2000L, 0x3de4_a000L, 0x3e0a_2000L)) {
            assertTrue(isCovered(slot, evfSlotBytes), "STILL/EVF PIC1 ring slot 0x${slot.toString(16)}+$evfSlotBytes must be mapped")
        }
        // MOVIE + MOVIE EVF ring
        for (slot in listOf(0x8999_c000L, 0x89bf_4000L, 0x89e4_c000L, 0x899a_b000L, 0x89c0_3000L, 0x89e5_b000L)) {
            assertTrue(isCovered(slot, evfSlotBytes), "MOVIE/EVF PIC1 ring slot 0x${slot.toString(16)}+$evfSlotBytes must be mapped")
        }
        // Multi-shot STILL / RAW+FINE / Burst / Pixel-Shift / Movie high-DDR capture pools
        // (BUG-A5-2 / BUG-A2-1 / BUG-A2-4 / BUG-A4-1: Shot #2 TTBR0[0x928]=0x0c156c16 -> 0x0c8fb328
        // and 0x928fb328; Movie Rec 0x78ff6000 & 0x43b0b0f4)
        for (addr in listOf(
            0x0c8f_b328L, 0x198f_b328L, 0x1a69_c000L, 0x2000_0000L,
            0x43b0_b0f4L, 0x5a00_0000L, 0x6700_0000L, 0x6b00_0000L,
            0x78ff_6000L, 0x7fb0_0000L,
            0x8600_0000L, 0x8900_0000L, 0x928f_b328L, 0x958f_b328L,
            0x9600_0000L, 0xa28f_b328L, 0xc000_0000L, 0xcc8f_b328L,
            0xd98f_b328L, 0xe68f_b328L, 0xee00_0000L,
        )) {
            assertTrue(isCovered(addr, 0x4_0000L), "Capture/Movie pool address 0x${addr.toString(16)}+256KiB must be mapped")
        }
    }
}
