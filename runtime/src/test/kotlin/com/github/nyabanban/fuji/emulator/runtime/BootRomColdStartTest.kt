package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class BootRomColdStartTest {
    private val catalog = AddressCatalog.bundled()
    private val context = object : DiscoveryContext {
        override fun core() = 0
        override fun pc() = 0xfff00464L
        override fun lr() = 0xfff00770L
        override fun tick() = 3821L
    }

    private fun bus(profile: String? = BootRomColdStart.PROFILE): DiscoveryBus {
        val config = if (profile == null) emptyMap() else mapOf("discovery" to mapOf(BootRomColdStart.KEY to profile))
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), config).createBus(context))
    }

    @Test
    fun strictModeAndUnknownProfilesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(
                catalog,
                "strict",
                Path.of("."),
                mapOf("discovery" to mapOf(BootRomColdStart.KEY to BootRomColdStart.PROFILE)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(
                catalog,
                "discovery",
                Path.of("."),
                mapOf("discovery" to mapOf(BootRomColdStart.KEY to "unknown-profile")),
            )
        }
    }

    @Test
    fun modelsInitTableRegistersMaskedGpioDramCommitAndBootStatusWithFailClosedBoundaries() {
        val b = bus()
        var seq = 1L

        // 0xFFF41500: masked 16-bit write (upper 16 bits = write enable mask, lower 16 bits = data)
        assertEquals(0L, b.access(seq++, 0xfff41500L, 4, false))
        b.access(seq++, 0xfff41500L, 4, true, 0xe3f4e3f4L)
        assertEquals(0x0000e3f4L, b.access(seq++, 0xfff41500L, 4, false))
        // Clear bit 2 only (mask=0x0004, data=0x0000)
        b.access(seq++, 0xfff41500L, 4, true, 0x00040000L)
        assertEquals(0x0000e3f0L, b.access(seq++, 0xfff41500L, 4, false))
        // Restore bit 2 (mask=0x0004, data=0x0004)
        b.access(seq++, 0xfff41500L, 4, true, 0x00040004L)
        assertEquals(0x0000e3f4L, b.access(seq++, 0xfff41500L, 4, false))

        // 0xFFF48180: retained 32-bit word
        b.access(seq++, 0xfff48180L, 4, true, 0x5L)
        assertEquals(0x5L, b.access(seq++, 0xfff48180L, 4, false))

        // 0xFF505200..0xFF50525C: 24 retained words
        for (i in 0 until BootRomColdStart.DRAM0_PREINIT_WORDS) {
            val addr = 0xff505200L + i * 4L
            val word = if (i in setOf(5, 6, 8, 9)) 0L else 1L
            b.access(seq++, addr, 4, true, word)
            assertEquals(word, b.access(seq++, addr, 4, false))
        }

        // 0xFF505384: write 1 arms pending commit, poll read returns 0 and increments commit counter
        assertEquals(0L, b.access(seq++, 0xff505384L, 4, false))
        b.access(seq++, 0xff505384L, 4, true, 1L)
        assertEquals(0L, b.access(seq++, 0xff505384L, 4, false))

        // 0xFFF4A100: boot status mailbox (0x900D = normal handoff to 0xFFF10000)
        b.access(seq++, 0xfff4a100L, 4, true, 0x900dL)
        assertEquals(0x900dL, b.access(seq++, 0xfff4a100L, 4, false))

        // Verify receipt contents
        val regions = b.snapshot()["regions"] as List<*>
        val primary = regions.map { it as Map<*, *> }.single { it["id"] == BootRomColdStart.PRIMARY_REGION }
        val receipt = primary[BootRomColdStart.KEY] as Map<*, *>
        assertEquals(BootRomColdStart.PROFILE, receipt["profile"])
        assertEquals("HYPOTHESIS", receipt["confidence"])
        assertEquals(0xe3f4L, receipt["gpioGlobalFff41500"])
        assertEquals(0x5L, receipt["sysCtrlFff48180"])
        assertEquals(20, receipt["dram0PreInitNonZeroCount"])
        assertEquals(false, receipt["dram0CommitPending"])
        assertEquals(1L, receipt["dram0CommitCount"])
        assertEquals(0x900dL, receipt["bootStatusFff4a100"])

        // Snapshot save & restore round-trip
        val saved = b.saveSnapshot()
        val restored = bus()
        restored.prepareSnapshotRestore(saved, allowPromotedRegions = false)()
        assertEquals(0xe3f4L, restored.access(seq++, 0xfff41500L, 4, false))
        assertEquals(0x5L, restored.access(seq++, 0xfff48180L, 4, false))
        assertEquals(1L, restored.access(seq++, 0xff505200L, 4, false))
        assertEquals(0L, restored.access(seq++, 0xff505214L, 4, false))
        assertEquals(0x900dL, restored.access(seq++, 0xfff4a100L, 4, false))

        // Fail-closed boundaries: invalid commit value, adjacent DRAM offsets, and non-4-byte widths must refuse
        assertFails { bus().access(1L, 0xff505384L, 4, true, 2L) }
        assertFails { bus().access(1L, 0xff505260L, 4, false) }
        assertFails { bus().access(1L, 0xff507c18L, 4, true, 0x40000000L) }
        assertFails { bus().access(1L, 0xfff41500L, 2, false) }
        assertFails { bus().access(1L, 0xfff41502L, 4, false) }
        assertFails { bus().access(1L, 0xfb000000L, 4, true, 1L) }
    }

    @Test
    fun promotesFromEmptySnapshotOnlyWhenExplicitlyAllowed() {
        val emptySnap = bus(profile = null).saveSnapshot()
        val target = bus()
        assertFailsWith<IllegalArgumentException> {
            target.prepareSnapshotRestore(emptySnap, allowPromotedRegions = false)
        }
        target.prepareSnapshotRestore(emptySnap, allowPromotedRegions = true)()
        assertEquals(0L, target.access(1L, 0xfff4a100L, 4, false))
    }

    @Test
    fun stage1DdrProfileModelsFullDramPhyCtlFabricStateMachineAndFailClosedBoundaries() {
        // Strict mode rejects v2 just like v1
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(
                catalog,
                "strict",
                Path.of("."),
                mapOf("discovery" to mapOf(BootRomColdStart.KEY to BootRomColdStart.STAGE1_DDR_PROFILE)),
            )
        }

        val b = bus(BootRomColdStart.STAGE1_DDR_PROFILE)
        assertTrue(b.hasBootRomStage1DdrInit)
        var seq = 1L

        // Pre-DDR flag 0xFB000000
        assertEquals(0L, b.access(seq++, 0xfb000000L, 4, false))
        b.access(seq++, 0xfb000000L, 4, true, 1L)
        assertEquals(1L, b.access(seq++, 0xfb000000L, 4, false))

        // Before 0xFF507C18=0x40000000: PLL locks and CTL0 ready bit are NOT set
        assertEquals(0L, b.access(seq++, 0xff507c18L, 4, false))
        assertEquals(0L, b.access(seq++, 0xff604670L, 4, false))
        assertEquals(0L, b.access(seq++, 0xff604674L, 4, false))
        assertEquals(0L, b.access(seq++, 0xff604678L, 4, false))

        // Stage 0 -> 1: write PHY0 table words + trigger 0xFF507C18=0x40000000
        b.access(seq++, 0xff602000L, 4, true, 0xb00L)
        b.access(seq++, 0xff604670L, 4, true, 0x924L)
        b.access(seq++, 0xff507c18L, 4, true, 0x40000000L)
        assertEquals(0x00000001L, b.access(seq++, 0xff507c18L, 4, false))
        assertEquals(0x00030924L, b.access(seq++, 0xff604670L, 4, false))
        assertEquals(0x00030003L, b.access(seq++, 0xff604674L, 4, false))
        assertEquals(0x00030003L, b.access(seq++, 0xff604678L, 4, false))

        // Stage 1 -> 2: CTL0 config/timing + mode commands 0xFF507040 -> 0xFF507044 == 0
        b.access(seq++, 0xff507c30L, 4, true, 0x40L)
        assertEquals(0x40L, b.access(seq++, 0xff507c30L, 4, false))
        b.access(seq++, 0xff507040L, 4, true, 0x3L)
        assertEquals(0L, b.access(seq++, 0xff507044L, 4, false))
        b.access(seq++, 0xff507040L, 4, true, 0x10L)
        assertEquals(0L, b.access(seq++, 0xff507044L, 4, false))

        // Before training trigger (0xFF602000 bit 0): training done & calibration ready are NOT set
        assertEquals(0L, b.access(seq++, 0xff602110L, 4, false))
        assertEquals(0L, b.access(seq++, 0xff60444cL, 4, false))
        assertEquals(0L, b.access(seq++, 0xff6040dcL, 4, false))

        // Stage 2 -> 3: PHY0 training trigger 0xFF602000 = 0xB01
        b.access(seq++, 0xff602000L, 4, true, 0xb01L)
        assertEquals(1L, b.access(seq++, 0xff602110L, 4, false))
        assertEquals(0x00010001L, b.access(seq++, 0xff60444cL, 4, false))
        assertEquals(0x00010001L, b.access(seq++, 0xff604620L, 4, false))
        assertEquals(0x00000300L, b.access(seq++, 0xff6040dcL, 4, false))
        assertEquals(0x00000300L, b.access(seq++, 0xff6042dcL, 4, false))

        // Stage 3 -> 4: CTL0 ACK 0xFF507360 = 0xFF00C00D -> 0xFF507364 == 1 -> W1C clears to 0
        assertEquals(0L, b.access(seq++, 0xff507364L, 4, false))
        b.access(seq++, 0xff507360L, 4, true, 0xff00c00dL)
        assertEquals(1L, b.access(seq++, 0xff507364L, 4, false))
        b.access(seq++, 0xff507364L, 4, true, 1L)
        assertEquals(0L, b.access(seq++, 0xff507364L, 4, false))

        // DDR fabric bridge + arbiter retained writes
        b.access(seq++, 0xff480000L, 4, true, 0xffffL)
        assertEquals(0xffffL, b.access(seq++, 0xff480000L, 4, false))
        b.access(seq++, 0xff483004L, 4, true, 0xffL)
        assertEquals(0xffL, b.access(seq++, 0xff483004L, 4, false))
        b.access(seq++, 0xff400010L, 4, true, 0L)
        b.access(seq++, 0xff402c90L, 4, true, 0x12345678L)
        assertEquals(0x12345678L, b.access(seq++, 0xff402c90L, 4, false))

        // Verify v2 receipt fields
        val regions = b.snapshot()["regions"] as List<*>
        val primary = regions.map { it as Map<*, *> }.single { it["id"] == BootRomColdStart.PRIMARY_REGION }
        val receipt = primary[BootRomColdStart.KEY] as Map<*, *>
        assertEquals(BootRomColdStart.STAGE1_DDR_PROFILE, receipt["profile"])
        assertEquals(1L, receipt["stage1FlagFb000000"])
        assertEquals(4, receipt["dramInitStage"])
        assertEquals(false, receipt["dramCtl0AckPending"])
        assertTrue((receipt["dramCtl0NonZeroCount"] as Int) >= 4)
        assertTrue((receipt["dramPhy0NonZeroCount"] as Int) >= 2)
        assertEquals(1, receipt["ddrArbiterNonZeroCount"])
        assertEquals(2, receipt["ddrBridgeNonZeroCount"])

        // v2 -> v2 snapshot round-trip
        val savedV2 = b.saveSnapshot()
        val restoredV2 = bus(BootRomColdStart.STAGE1_DDR_PROFILE)
        restoredV2.prepareSnapshotRestore(savedV2, allowPromotedRegions = false)()
        assertEquals(1L, restoredV2.access(seq++, 0xfb000000L, 4, false))
        assertEquals(0x00000001L, restoredV2.access(seq++, 0xff507c18L, 4, false))
        assertEquals(0x00010001L, restoredV2.access(seq++, 0xff60444cL, 4, false))
        assertEquals(0x12345678L, restoredV2.access(seq++, 0xff402c90L, 4, false))
        assertEquals(0xffffL, restoredV2.access(seq++, 0xff480000L, 4, false))

        // v2 -> v1 downgrade must be refused even with allowPromotedRegions = true
        assertFailsWith<IllegalArgumentException> {
            bus(BootRomColdStart.PROFILE).prepareSnapshotRestore(savedV2, allowPromotedRegions = true)
        }

        // v1 -> v2 promotion requires explicit allowPromotedRegions = true and preserves v1 state
        val v1Bus = bus(BootRomColdStart.PROFILE)
        v1Bus.access(1L, 0xfff4a100L, 4, true, 0x900dL)
        val savedV1 = v1Bus.saveSnapshot()
        val promotedV2 = bus(BootRomColdStart.STAGE1_DDR_PROFILE)
        assertFailsWith<IllegalArgumentException> {
            promotedV2.prepareSnapshotRestore(savedV1, allowPromotedRegions = false)
        }
        promotedV2.prepareSnapshotRestore(savedV1, allowPromotedRegions = true)()
        assertEquals(0x900dL, promotedV2.access(2L, 0xfff4a100L, 4, false))
        assertEquals(0L, promotedV2.access(3L, 0xfb000000L, 4, false))

        // Fail-closed boundaries: unlisted CTL0/arbiter/bridge offsets and PHY0 prohibited gap must refuse
        assertFails { bus(BootRomColdStart.STAGE1_DDR_PROFILE).access(1L, 0xff507010L, 4, false) }
        assertFails { bus(BootRomColdStart.STAGE1_DDR_PROFILE).access(1L, 0xff60235cL, 4, false) }
        assertFails { bus(BootRomColdStart.STAGE1_DDR_PROFILE).access(1L, 0xff603ffcL, 4, false) }
        assertFails { bus(BootRomColdStart.STAGE1_DDR_PROFILE).access(1L, 0xff400000L, 4, false) }
        assertFails { bus(BootRomColdStart.STAGE1_DDR_PROFILE).access(1L, 0xff400008L, 4, false) }
        assertFails { bus(BootRomColdStart.STAGE1_DDR_PROFILE).access(1L, 0xff480008L, 4, false) }
        assertFails { bus(BootRomColdStart.STAGE1_DDR_PROFILE).access(1L, 0xfb000004L, 4, false) }
        assertFails { bus(BootRomColdStart.STAGE1_DDR_PROFILE).access(1L, 0xfff4a104L, 4, true, 0x1000900dL) }
    }

    @Test
    fun qspiKernelProfileModelsSecondaryBootStatusMailboxesAndSnapshotPromotion() {
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(
                catalog,
                "strict",
                Path.of("."),
                mapOf("discovery" to mapOf(BootRomColdStart.KEY to BootRomColdStart.QSPI_KERNEL_PROFILE)),
            )
        }

        val b = bus(BootRomColdStart.QSPI_KERNEL_PROFILE)
        assertTrue(b.hasBootRomStage1DdrInit)
        var seq = 1L

        b.access(seq++, 0xfff4a100L, 4, true, 0x900dL)
        b.access(seq++, 0xfff4a104L, 4, true, 0x1000900dL)
        b.access(seq++, 0xfff4a108L, 4, true, 0x2000900dL)
        b.access(seq++, 0xfff4a10cL, 4, true, 0x3000900dL)
        assertEquals(0x1000900dL, b.access(seq++, 0xfff4a104L, 4, false))
        assertEquals(0x2000900dL, b.access(seq++, 0xfff4a108L, 4, false))
        assertEquals(0x3000900dL, b.access(seq++, 0xfff4a10cL, 4, false))

        val savedV3 = b.saveSnapshot()
        assertFails { b.access(seq++, 0xfff4a110L, 4, false) }
        val restoredV3 = bus(BootRomColdStart.QSPI_KERNEL_PROFILE)
        restoredV3.prepareSnapshotRestore(savedV3, allowPromotedRegions = false)()
        assertEquals(0x1000900dL, restoredV3.access(seq++, 0xfff4a104L, 4, false))
        assertEquals(0x2000900dL, restoredV3.access(seq++, 0xfff4a108L, 4, false))
        assertEquals(0x3000900dL, restoredV3.access(seq++, 0xfff4a10cL, 4, false))

        // v2 -> v3 promotion requires allowPromotedRegions = true
        val v2Bus = bus(BootRomColdStart.STAGE1_DDR_PROFILE)
        v2Bus.access(1L, 0xfb000000L, 4, true, 1L)
        val savedV2 = v2Bus.saveSnapshot()
        val promotedV3 = bus(BootRomColdStart.QSPI_KERNEL_PROFILE)
        assertFailsWith<IllegalArgumentException> {
            promotedV3.prepareSnapshotRestore(savedV2, allowPromotedRegions = false)
        }
        promotedV3.prepareSnapshotRestore(savedV2, allowPromotedRegions = true)()
        assertEquals(1L, promotedV3.access(2L, 0xfb000000L, 4, false))
        assertEquals(0L, promotedV3.access(3L, 0xfff4a104L, 4, false))
    }

    @Test
    fun releaseColdStartProfileV4OmitsDram0AndPhy0ToCoexistWithSocDramAndRoundTripsSnapshot() {
        val b = bus(BootRomColdStart.RELEASE_COLDSTART_PROFILE)
        assertTrue(b.hasBootRomStage1DdrInit)
        var seq = 1L

        // Covered non-DRAM0 regions work cleanly
        b.access(seq++, 0xfff41500L, 4, true, 0xe3f4e3f4L)
        assertEquals(0xe3f4L, b.access(seq++, 0xfff41500L, 4, false))
        b.access(seq++, 0xfff48180L, 4, true, 5L)
        assertEquals(5L, b.access(seq++, 0xfff48180L, 4, false))
        b.access(seq++, 0xfff4a100L, 4, true, 0x900dL)
        b.access(seq++, 0xfff4a104L, 4, true, 0x1000900dL)
        b.access(seq++, 0xfb000000L, 4, true, 1L)
        b.access(seq++, 0xff400010L, 4, true, 0x12345678L)
        b.access(seq++, 0xff480000L, 4, true, 0x87654321L)
        assertEquals(0x12345678L, b.access(seq++, 0xff400010L, 4, false))
        assertEquals(0x87654321L, b.access(seq++, 0xff480000L, 4, false))

        // Snapshot round-trip (must snapshot before inducing fail-closed refusals)
        val saved = b.saveSnapshot()
        val restored = bus(BootRomColdStart.RELEASE_COLDSTART_PROFILE)
        restored.prepareSnapshotRestore(saved, allowPromotedRegions = false)()
        assertEquals(0xe3f4L, restored.access(seq++, 0xfff41500L, 4, false))
        assertEquals(0x1000900dL, restored.access(seq++, 0xfff4a104L, 4, false))
        assertEquals(1L, restored.access(seq++, 0xfb000000L, 4, false))
        assertEquals(0x12345678L, restored.access(seq++, 0xff400010L, 4, false))

        // DRAM0 / PHY0 regions MUST NOT be claimed by v4 (owned by soc-dram coldStartChannel0)
        assertFails { b.access(seq++, 0xff505200L, 4, false) }
        assertFails { bus(BootRomColdStart.RELEASE_COLDSTART_PROFILE).access(1L, 0xff505384L, 4, false) }
        assertFails { bus(BootRomColdStart.RELEASE_COLDSTART_PROFILE).access(1L, 0xff507c18L, 4, false) }
        assertFails { bus(BootRomColdStart.RELEASE_COLDSTART_PROFILE).access(1L, 0xff602000L, 4, false) }
    }
}
