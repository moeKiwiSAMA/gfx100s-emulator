package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*
import org.junit.jupiter.api.Test

class ImageLiveViewBanksTest {
    private val catalog = AddressCatalog.bundled()
    private val fff72028 = ImageLiveViewBanks.FFF72028_REGION
    private val context = object : DiscoveryContext {
        override fun core() = 1
        override fun pc() = 0x5af98L
        override fun lr() = 0x11beac4L
        override fun tick() = 900_000_000L
    }

    private fun bus(
        liveView: String? = ImageLiveViewBanks.PROFILE,
        with72028: Boolean = true,
        mode: String = "discovery",
    ): DiscoveryBus {
        val discovery = linkedMapOf<String, Any>()
        if (with72028) {
            discovery["inputs"] = mapOf(fff72028 to mapOf("strategy" to "retained-bytes", "fill" to 0))
        }
        if (liveView != null) {
            discovery[ImageLiveViewBanks.KEY] = liveView
        }
        val config = if (discovery.isEmpty()) emptyMap() else mapOf("discovery" to discovery)
        return checkNotNull(CatalogRuntime(catalog, mode, Path.of("."), config).createBus(context))
    }

    @Test
    fun requiresExplicitOptInDiscoveryModeAndExactProfile() {
        val defaultBus = bus(liveView = null, with72028 = true)
        for (address in listOf(0xfff6d000L, 0xfff6d800L, 0xfff72000L, 0xfff720dcL, 0xfff73000L, 0xfff730dcL, 0xfff74000L, 0xfff74028L)) {
            assertFails { defaultBus.access(1, address, 4, false) }
            assertFails { defaultBus.access(2, address, 4, true, 0) }
        }
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(
                catalog, "strict", Path.of("."),
                mapOf("discovery" to mapOf(ImageLiveViewBanks.KEY to ImageLiveViewBanks.PROFILE)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            bus(liveView = "unknown-liveview-v0")
        }
    }

    @Test
    fun resetPollsAndIdleStatusFollowDatVerifiedSemantics() {
        val active = bus()
        var seq = 0L

        // 011BEAA0(0), 011BEAA0(1), 011BEB28: write 0, poll bit 0 == 1
        for (resetAddr in listOf(0xfff72000L, 0xfff73000L, 0xfff74000L)) {
            assertEquals(1L, active.access(++seq, resetAddr, 4, false))
            active.access(++seq, resetAddr, 4, true, 0L)
            assertEquals(1L, active.access(++seq, resetAddr, 4, false))
            active.access(++seq, resetAddr, 4, true, 0x1234_0000L)
            assertEquals(0x1234_0001L, active.access(++seq, resetAddr, 4, false))
        }

        // 011BED54(0/1) & 011C0288 RMW |= 1; 011BEAD8/011BFFE0 & 011BEB5C/011C0AF8 bit 28 enable <-> bit 8 idle poll
        val pairs = listOf(
            0xfff720d4L to 0xfff720dcL,
            0xfff730d4L to 0xfff730dcL,
            0xfff74024L to 0xfff74028L,
        )
        for ((enableAddr, statusAddr) in pairs) {
            // Initially disabled (bit 28 == 0) -> status bit 8 (0x100) is 1 (idle)
            val initial = active.access(++seq, statusAddr, 4, false)
            assertEquals(0x100L, initial)
            // RMW |= 1
            active.access(++seq, statusAddr, 4, true, initial or 1L)
            assertEquals(0x101L, active.access(++seq, statusAddr, 4, false))
            // Enable engine (bit 28 = 1) -> status bit 8 clears (running)
            active.access(++seq, enableAddr, 4, true, 0x1000_0000L)
            assertEquals(0x1000_0000L, active.access(++seq, enableAddr, 4, false))
            assertEquals(0x001L, active.access(++seq, statusAddr, 4, false))
            // Stop engine (bit 28 = 0) -> status bit 8 immediately asserts (idle) so poll exits
            active.access(++seq, enableAddr, 4, true, 0L)
            assertEquals(0x101L, active.access(++seq, statusAddr, 4, false))
        }

        // Verify journal attributes interior reads to ImageLiveViewBanks with HYPOTHESIS confidence
        val accesses = active.snapshot()["accesses"] as List<*>
        val interiorRead = accesses.map { it as Map<*, *> }
            .first { it["addressPhysical"] == 0xfff73000L && it["direction"] == "read" }
        assertEquals(ImageLiveViewBanks.PROFILE, interiorRead["valueSource"])
        assertEquals("HYPOTHESIS", interiorRead["confidence"])
        assertEquals(true, interiorRead["completed"])
    }

    @Test
    fun retainsAllDatVerifiedOffsetsAndCoexistsDisjointlyWithFff72028() {
        val active = bus(with72028 = true)
        // Strictly disjoint intervals in DiscoveryBus
        active.regions.zipWithNext().forEach { (a, b) ->
            assertTrue(a.endExclusive <= b.base, "Overlap between ${a.id} and ${b.id}")
        }
        val pages = MmioRouter(emptyList(), active).pageRanges()
        for (page in listOf(0xfff6d000L, 0xfff72000L, 0xfff73000L, 0xfff74000L)) {
            assertTrue(pages.any { (base, length) -> page >= base && page + 4096 <= base + length })
        }

        var seq = 0L
        // Existing fff72028 config words remain writable (and read-refused)
        active.access(++seq, 0xfff72028L, 4, true, 0x10001000L)
        active.access(++seq, 0xfff73028L, 4, true, 0x20002000L)
        active.access(++seq, 0xfff720b8L, 4, true, 0x00010000L)
        active.access(++seq, 0xfff730b8L, 4, true, 0x00020000L)

        // All DAT-verified non-poll registers across banks 0, 1, 2
        val retainedRegisters = buildList {
            for (bank in listOf(0xfff72000L, 0xfff73000L)) {
                for (off in 0x04..0x24 step 4) add(bank + off)
                add(bank + 0x2c)
                add(bank + 0x30)
                for (off in 0xc0..0xe4 step 4) if (off != 0xdc) add(bank + off)
                for (off in 0x100..0x108 step 4) add(bank + off)
                for (off in 0x110..0x12c step 4) add(bank + off)
            }
            for (off in 0x04..0x2c step 4) if (off != 0x28) add(0xfff74000L + off)
        }
        for ((index, addr) in retainedRegisters.withIndex()) {
            val pattern = 0xa500_0000L or index.toLong()
            assertEquals(0L, active.access(++seq, addr, 4, false))
            active.access(++seq, addr, 4, true, pattern)
            assertEquals(pattern, active.access(++seq, addr, 4, false))
        }

        // LUT banks 0xFFF6D000..0xFFF6D7FC and 0xFFF6D800..0xFFF6DFFC
        val lutAddresses = listOf(0xfff6d000L, 0xfff6d004L, 0xfff6d7fcL, 0xfff6d800L, 0xfff6d804L, 0xfff6dffcL)
        for ((index, addr) in lutAddresses.withIndex()) {
            val pattern = 0x3fff_0000L or (index.toLong() + 1L)
            assertEquals(0L, active.access(++seq, addr, 4, false))
            active.access(++seq, addr, 4, true, pattern)
            assertEquals(pattern, active.access(++seq, addr, 4, false))
        }

        // Without fff72028 enabled, fff72028 config words refuse while live-view interior + disjoint banks still work
        val without72028 = bus(with72028 = false)
        assertEquals(1L, without72028.access(1, 0xfff72000L, 4, false))
        assertEquals(0x100L, without72028.access(2, 0xfff720dcL, 4, false))
        assertEquals(1L, without72028.access(3, 0xfff73000L, 4, false))
        assertEquals(0x100L, without72028.access(4, 0xfff730dcL, 4, false))
        assertEquals(1L, without72028.access(5, 0xfff74000L, 4, false))
        assertEquals(0x100L, without72028.access(6, 0xfff74028L, 4, false))
        without72028.access(7, 0xfff6d000L, 4, true, 0x11223344L)
        assertEquals(0x11223344L, without72028.access(8, 0xfff6d000L, 4, false))
        assertFails { without72028.access(9, 0xfff72028L, 4, true, 0) }
    }

    @Test
    fun rejectsHolesUnalignedOtherWidthsAndInvalidValues() {
        val holes = listOf(
            0xfff6cffcL, 0xfff6e000L,
            0xfff7203cL, 0xfff720bcL, 0xfff720e8L, 0xfff720fcL, 0xfff7210cL, 0xfff72130L, 0xfff72800L, 0xfff72ffcL,
            0xfff7303cL, 0xfff730bcL, 0xfff730e8L, 0xfff730fcL, 0xfff7310cL, 0xfff73130L,
            0xfff74030L,
        )
        for (hole in holes) {
            assertFails("Expected read refusal at hole 0x${hole.toString(16)}") { bus().access(1, hole, 4, false) }
            assertFails("Expected write refusal at hole 0x${hole.toString(16)}") { bus().access(1, hole, 4, true, 0) }
        }
        // Existing fff72028 config words still refuse reads
        for (addr in listOf(0xfff72028L, 0xfff72034L, 0xfff72040L, 0xfff73028L, 0xfff730b8L)) {
            assertFails { bus().access(1, addr, 4, false) }
        }
        // Unaligned & wrong widths & out-of-range values
        for (addr in listOf(0xfff6d000L, 0xfff72000L, 0xfff720dcL, 0xfff73000L, 0xfff730dcL, 0xfff74000L)) {
            for (misalign in listOf(1L, 2L, 3L)) {
                assertFails { bus().access(1, addr + misalign, 4, false) }
                assertFails { bus().access(1, addr + misalign, 4, true, 0) }
            }
            for (width in listOf(1, 2, 8)) {
                assertFails { bus().access(1, addr, width, false) }
                assertFails { bus().access(1, addr, width, true, 0) }
            }
            assertFails { bus().access(1, addr, 4, true, -1L) }
            assertFails { bus().access(1, addr, 4, true, 0x1_0000_0000L) }
        }
    }

    @Test
    fun snapshotSaveRestoreAndPromotionPreserveStateStrictly() {
        // 1. Pre-live-view snapshot with 68 writes into gfx100s.fff72028.config-words.candidate (T79 state)
        val preBus = bus(liveView = null, with72028 = true)
        var seq = 0L
        for (base in listOf(0xfff72000L, 0xfff73000L)) {
            preBus.access(++seq, base + 0x28, 4, true, 0x10001000L)
            preBus.access(++seq, base + 0x34, 4, true, 0x11L)
            preBus.access(++seq, base + 0x38, 4, true, 0x22L)
            for (off in 0x40..0xb8 step 4) preBus.access(++seq, base + off, 4, true, 0x10000L + off)
        }
        val preSnap = preBus.saveSnapshot()

        // Promotion without allowPromotedRegions must fail
        assertFails { bus(liveView = ImageLiveViewBanks.PROFILE).restoreSnapshot(preSnap, allowPromotedRegions = false) }

        // Promotion WITH allowPromotedRegions = true succeeds and preserves all 68 fff72028 bytes
        val promoted = bus(liveView = ImageLiveViewBanks.PROFILE).apply {
            restoreSnapshot(preSnap, allowPromotedRegions = true)
        }
        val afterPromoteSnap = promoted.saveSnapshot()
        val fffRow = (afterPromoteSnap["regions"] as Map<*, *>)[fff72028] as Map<*, *>
        val fffBytes = ByteBuffer.wrap(Base64.getDecoder().decode(fffRow["mutatedBytesBase64"] as String))
            .order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x10001000, fffBytes.getInt(0))
        assertEquals(0x10001000, fffBytes.getInt(0x1000))
        assertEquals(0x100b8, fffBytes.getInt(0x1090))

        // Mutate live-view banks across all 4 regions + interior, including enable/idle state
        promoted.access(++seq, 0xfff6d004L, 4, true, 0x12345678L)
        promoted.access(++seq, 0xfff6dffcL, 4, true, 0x87654321L)
        promoted.access(++seq, 0xfff72004L, 4, true, 0x00000001L)
        promoted.access(++seq, 0xfff7202cL, 4, true, 0x00aa00bbL)
        promoted.access(++seq, 0xfff720d4L, 4, true, 0x10000000L) // bank 0 enabled -> 20DC idle bit 8 == 0
        promoted.access(++seq, 0xfff720dcL, 4, true, 0x00000001L)
        promoted.access(++seq, 0xfff73004L, 4, true, 0x00000001L)
        promoted.access(++seq, 0xfff730dcL, 4, true, 0x00000101L) // bank 1 disabled -> 30DC idle bit 8 == 1
        promoted.access(++seq, 0xfff74024L, 4, true, 0x10000000L) // bank 2 enabled -> 4028 idle bit 8 == 0
        promoted.access(++seq, 0xfff74028L, 4, true, 0x00000001L)
        promoted.access(++seq, 0xfff7402cL, 4, true, 0x00000001L)

        val liveSnap = promoted.saveSnapshot()

        // 2. Exact round-trip restore
        val restored = bus(liveView = ImageLiveViewBanks.PROFILE).apply {
            restoreSnapshot(liveSnap, allowPromotedRegions = false)
        }
        assertEquals(0x12345678L, restored.access(++seq, 0xfff6d004L, 4, false))
        assertEquals(0x87654321L, restored.access(++seq, 0xfff6dffcL, 4, false))
        assertEquals(1L, restored.access(++seq, 0xfff72000L, 4, false))
        assertEquals(1L, restored.access(++seq, 0xfff72004L, 4, false))
        assertEquals(0x00aa00bbL, restored.access(++seq, 0xfff7202cL, 4, false))
        assertEquals(0x001L, restored.access(++seq, 0xfff720dcL, 4, false))
        assertEquals(1L, restored.access(++seq, 0xfff73000L, 4, false))
        assertEquals(0x101L, restored.access(++seq, 0xfff730dcL, 4, false))
        assertEquals(1L, restored.access(++seq, 0xfff74000L, 4, false))
        assertEquals(0x001L, restored.access(++seq, 0xfff74028L, 4, false))
        assertEquals(1L, restored.access(++seq, 0xfff7402cL, 4, false))

        // Clearing bit 28 on bank 0 after restore re-asserts idle bit 8
        restored.access(++seq, 0xfff720d4L, 4, true, 0L)
        assertEquals(0x101L, restored.access(++seq, 0xfff720dcL, 4, false))

        // 3. Restoring a live-view snapshot onto a bus WITHOUT live-view must fail even with allowPromotedRegions = true
        assertFails { bus(liveView = null).restoreSnapshot(liveSnap, allowPromotedRegions = false) }
        assertFails { bus(liveView = null).restoreSnapshot(liveSnap, allowPromotedRegions = true) }

        // 4. Partial / corrupted live-view snapshots must fail
        val regionsCopy = LinkedHashMap<Any?, Any?>((liveSnap["regions"] as Map<*, *>))
        regionsCopy.remove(ImageLiveViewBanks.REGION_74000)
        assertFails {
            bus(liveView = ImageLiveViewBanks.PROFILE).restoreSnapshot(liveSnap + ("regions" to regionsCopy), allowPromotedRegions = true)
        }

        val badPrimary = LinkedHashMap<Any?, Any?>((liveSnap["regions"] as Map<*, *>)[ImageLiveViewBanks.PRIMARY_REGION] as Map<*, *>)
        val badState = LinkedHashMap<Any?, Any?>(badPrimary[ImageLiveViewBanks.KEY] as Map<*, *>)
        badState["registers"] = mapOf("0xfff7203c" to 1L) // hardware hole key!
        badPrimary[ImageLiveViewBanks.KEY] = badState
        val badRegions = LinkedHashMap<Any?, Any?>((liveSnap["regions"] as Map<*, *>))
        badRegions[ImageLiveViewBanks.PRIMARY_REGION] = badPrimary
        assertFails {
            bus(liveView = ImageLiveViewBanks.PROFILE).restoreSnapshot(liveSnap + ("regions" to badRegions))
        }
    }

    @Test
    fun pumpLiveViewDmaWritesUyvyOnlyWhenArmedAndSensorConfigured() {
        val dmaBase = 0x8999_c000L
        val stride = 2560
        val width = 1024
        val height = 576
        val expectedBytes = (height - 1) * stride + width * 2
        val ram = ByteArray(expectedBytes)
        var writes = 0

        val memory = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                assertTrue(write)
                assertEquals(dmaBase, address)
                assertEquals(expectedBytes.toLong(), size)
            }

            override fun read(address: Long, destination: ByteBuffer) = error("unexpected read")

            override fun write(address: Long, source: ByteBuffer) {
                check(address, source.remaining().toLong(), true)
                source.get(ram, 0, source.remaining())
                writes++
            }
        }

        var unconfigured = true
        var exhausted = false
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                if (unconfigured) error("CMOS sourceFile is not configured")
                if (exhausted) return 0
                // Top half (y < 288) = bright white (65535), bottom half = dark black (0)
                for (y in 0 until height) {
                    val sample = if (y < height / 2) 0xffff else 0
                    for (x in 0 until width) {
                        destination.put((sample and 0xff).toByte())
                        destination.put(((sample ushr 8) and 0xff).toByte())
                    }
                }
                return width * height * 2
            }
        }

        val liveCtx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11beac4L
            override fun tick() = 900_000_000L
            override fun sensorPort() = sensor
            override fun dmaMemory() = memory
            override fun lensParameters() = LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)
        }
        val config = mapOf(
            "discovery" to mapOf(
                "inputs" to mapOf(fff72028 to mapOf("strategy" to "retained-bytes", "fill" to 0)),
                ImageLiveViewBanks.KEY to ImageLiveViewBanks.PROFILE,
            ),
        )
        val liveBus = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), config).createBus(liveCtx))
        var seq = 0L

        // Not armed yet -> no-op
        assertFalse(liveBus.pumpLiveViewDma(900_000_001L))
        assertEquals(0, writes)

        // Program T80 geometry, destination, stride, and enable bit 28 while sensor is unconfigured -> graceful no-op
        liveBus.access(++seq, 0xfff73018L, 4, true, 0x023f_03ffL)
        liveBus.access(++seq, 0xfff730c4L, 4, true, dmaBase)
        liveBus.access(++seq, 0xfff730d0L, 4, true, stride.toLong())
        liveBus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)
        assertEquals(0, writes)

        // Configure sensor and re-enable bit 28 -> immediate DMA pump succeeds
        unconfigured = false
        liveBus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)
        assertEquals(1, writes)

        // Verify BT.601 YYUV422 output: top row (y=10) is white (Y0=255, Y1=255, U=128, V=128)
        val topOff = 10 * stride
        assertEquals(255, ram[topOff].toInt() and 0xff)
        assertEquals(255, ram[topOff + 1].toInt() and 0xff)
        assertEquals(128, ram[topOff + 2].toInt() and 0xff)
        assertEquals(128, ram[topOff + 3].toInt() and 0xff)

        // Bottom row (y=400) is black (Y0=0, Y1=0, U=128, V=128)
        val botOff = 400 * stride
        assertEquals(0, ram[botOff].toInt() and 0xff)
        assertEquals(0, ram[botOff + 1].toInt() and 0xff)
        assertEquals(128, ram[botOff + 2].toInt() and 0xff)
        assertEquals(128, ram[botOff + 3].toInt() and 0xff)

        // Exhausted (loop=false) -> retains last valid frame without writing
        exhausted = true
        assertFalse(liveBus.pumpLiveViewDma(900_000_002L))
        assertEquals(1, writes)
        assertEquals(255, ram[topOff].toInt() and 0xff)

        // Clearing bit 28 stops DMA even when sensor has frames
        exhausted = false
        liveBus.access(++seq, 0xfff730d4L, 4, true, 0x0005_0000L)
        assertFalse(liveBus.pumpLiveViewDma(900_000_003L))
        assertEquals(1, writes)
    }

    @Test
    fun rejectsOversizedStrideAndFailsSafeOnUnmappedDmaWithoutConsumingExposure() {
        val dmaBase = 0x8999_c000L
        var exposeCalls = 0
        var exhausted = false
        var allowMapped = false
        var writes = 0

        val memory = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                if (!allowMapped) throw IllegalArgumentException("Unmapped DMA region: 0x${address.toString(16)} size=$size")
            }

            override fun read(address: Long, destination: ByteBuffer) = error("unexpected read")

            override fun write(address: Long, source: ByteBuffer) {
                check(address, source.remaining().toLong(), true)
                writes++
            }
        }
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                exposeCalls++
                if (exhausted) return 0
                destination.position(destination.position() + 1024 * 576 * 2)
                exhausted = true // simulates loop=false single-frame sensor
                return 1024 * 576 * 2
            }
        }
        val liveCtx = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11beac4L
            override fun tick() = 900_000_000L
            override fun sensorPort() = sensor
            override fun dmaMemory() = memory
            override fun lensParameters() = LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)
        }
        val config = mapOf(
            "discovery" to mapOf(
                "inputs" to mapOf(fff72028 to mapOf("strategy" to "retained-bytes", "fill" to 0)),
                ImageLiveViewBanks.KEY to ImageLiveViewBanks.PROFILE,
            ),
        )
        val liveBus = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), config).createBus(liveCtx))
        var seq = 0L
        liveBus.access(++seq, 0xfff73018L, 4, true, 0x023f_03ffL)
        liveBus.access(++seq, 0xfff730c4L, 4, true, dmaBase)

        // 1. Huge stride (0x01000000 and 0x40000000) must return false cleanly without Int overflow or sensor.expose()
        allowMapped = true
        for (badStride in listOf(0x0100_0000L, 0x4000_0000L, 0x8000_0000L)) {
            liveBus.access(++seq, 0xfff730d0L, 4, true, badStride)
            liveBus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)
            assertFalse(liveBus.pumpLiveViewDma(900_000_010L))
        }
        assertEquals(0, exposeCalls)
        assertEquals(0, writes)

        // 2. Unmapped DmaMemory must return false cleanly without calling sensor.expose() or exhausting loop=false
        allowMapped = false
        liveBus.access(++seq, 0xfff730d0L, 4, true, 2560L)
        liveBus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)
        assertFalse(liveBus.pumpLiveViewDma(900_000_020L))
        assertEquals(0, exposeCalls)
        assertFalse(exhausted)
        assertEquals(0, writes)

        // 3. Once RAM is mapped, the single loop=false frame is still available and writes cleanly
        allowMapped = true
        assertTrue(liveBus.pumpLiveViewDma(900_000_030L))
        assertEquals(1, exposeCalls)
        assertTrue(exhausted)
        assertEquals(1, writes)
    }

    @Test
    fun pumpLiveViewDmaPreservesAspectRatioForFourByThreeT80Outputs() {
        val srcW = 1024
        val srcH = 576
        // Draw a bright 288x288 centered square on a dark 1024x576 Bayer background
        val sqSize = 288
        val sqX0 = (srcW - sqSize) / 2
        val sqY0 = (srcH - sqSize) / 2
        val sensor = object : SensorPort {
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                for (y in 0 until srcH) {
                    val inY = y in sqY0 until (sqY0 + sqSize)
                    for (x in 0 until srcW) {
                        val sample = if (inY && x in sqX0 until (sqX0 + sqSize)) 0xffff else 0
                        destination.put((sample and 0xff).toByte())
                        destination.put(((sample ushr 8) and 0xff).toByte())
                    }
                }
                return srcW * srcH * 2
            }
        }

        for ((dstW, dstH) in listOf(1024 to 768, 640 to 480)) {
            val stride = dstW * 2
            val ram = ByteArray(dstH * stride)
            val memory = object : DmaMemory {
                override fun check(address: Long, size: Long, write: Boolean) {}
                override fun read(address: Long, destination: ByteBuffer) = error("unexpected read")
                override fun write(address: Long, source: ByteBuffer) {
                    source.get(ram, 0, source.remaining())
                }
            }
            val liveCtx = object : DiscoveryContext {
                override fun core() = 1
                override fun pc() = 0x5af98L
                override fun lr() = 0x11beac4L
                override fun tick() = 900_000_000L
                override fun sensorPort() = sensor
                override fun dmaMemory() = memory
                override fun lensParameters() = LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)
            }
            val config = mapOf(
                "discovery" to mapOf(
                    "inputs" to mapOf(fff72028 to mapOf("strategy" to "retained-bytes", "fill" to 0)),
                    ImageLiveViewBanks.KEY to ImageLiveViewBanks.PROFILE,
                ),
            )
            val liveBus = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), config).createBus(liveCtx))
            var seq = 0L
            val sizeWord = (((dstH - 1).toLong() and 0xffffL) shl 16) or ((dstW - 1).toLong() and 0xffffL)
            liveBus.access(++seq, 0xfff73018L, 4, true, sizeWord)
            liveBus.access(++seq, 0xfff730c4L, 4, true, 0x8999_c000L)
            liveBus.access(++seq, 0xfff730d0L, 4, true, stride.toLong())
            liveBus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)

            var minX = dstW
            var maxX = -1
            var minY = dstH
            var maxY = -1
            for (y in 0 until dstH) {
                for (x in 0 until dstW) {
                    val pairOff = y * stride + (x and 1.inv()) * 2
                    val luma = ram[pairOff + (x and 1)].toInt() and 0xff
                    if (luma > 200) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            val expectedBox = if (dstW == 1024) 288 else 180
            assertTrue(
                kotlin.math.abs(boxW - expectedBox) <= 2 && kotlin.math.abs(boxH - expectedBox) <= 2,
                "Expected ~${expectedBox}x${expectedBox} square for ${dstW}x${dstH}, got ${boxW}x${boxH}",
            )
        }
    }
}
