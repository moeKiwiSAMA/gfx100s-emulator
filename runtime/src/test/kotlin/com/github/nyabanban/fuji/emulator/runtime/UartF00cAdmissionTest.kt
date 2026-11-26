package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class UartF00cAdmissionTest {
    private val uart = 0xfff3a000L
    private val control = 0xfff4f00cL
    private val reads = setOf(0x18, 0x2c, 0x30, 0x34, 0x38)
    private val writes = setOf(0x00, 0x24, 0x28, 0x2c, 0x30, 0x34, 0x38)
    private val context = object : DiscoveryContext {
        override fun core() = 0
        override fun tick() = 542117645L
        override fun pc() = 0x5af98L
        override fun lr() = 0x11dd328L
    }

    private fun bus(selected: Boolean): DiscoveryBus {
        val ids = listOf("gfx100s.fff3a000.uart-bank.candidate", "gfx100s.fff3a030.uart-config.candidate",
            "gfx100s.fff3a034.uart-tail.candidate", "gfx100s.fff4f00c.runtime-control.candidate")
        val inputs = if (selected) ids.associateWith { mapOf("strategy" to "retained-bytes", "fill" to 0) } else emptyMap()
        return checkNotNull(CatalogRuntime(AddressCatalog.bundled(), "discovery", Path.of("."),
            mapOf("discovery" to mapOf("inputs" to inputs))).createBus(context))
    }

    @Test fun explicitSelectionAdmitsOnlyObservedWordDirections() {
        val selected = bus(true)
        for (offset in 0..0x40 step 4) {
            for (write in listOf(false, true)) {
                val allowed = offset in (if (write) writes else reads)
                if (allowed) assertEquals(0L, selected.access(1, uart + offset, 4, write))
                else assertFailsWith<IllegalStateException> { selected.access(1, uart + offset, 4, write) }
            }
        }
        assertEquals(0L, selected.access(2, control, 4, false))
        assertEquals(0x3e0L, selected.access(3, control, 4, true, 0x3e0))
        assertEquals(0x3e0L, selected.access(4, control, 4, false))
    }

    @Test fun defaultPolicyWidthsAlignmentAndBoundsRefuse() {
        val ordinary = bus(false)
        val selected = bus(true)
        for (write in listOf(false, true)) {
            for (address in (if (write) writes else reads).map { uart + it } + control) {
                assertFailsWith<IllegalStateException> { ordinary.access(1, address, 4, write) }
                for (width in listOf(1, 2, 8)) {
                    assertFailsWith<IllegalStateException> { selected.access(2, address, width, write) }
                }
                assertFailsWith<IllegalStateException> { selected.access(3, address + 2, 4, write) }
            }
            for (address in listOf(control - 4, control + 4, uart - 0x1000 + 0x30, uart + 0x1000 + 0x30)) {
                assertFailsWith<IllegalStateException> { selected.access(4, address, 4, write) }
            }
        }
    }

    @Test fun sectionFourCallSitesSelectWordReadAndWriteHelpers() {
        val file = Path.of(System.getProperty("componentProjectRoot"), "firmware-data/gfx100s/2.13/decoded/section4.bin")
        val section = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN)
        fun word(pc: Int) = section.getInt(pc - 0x1021000)
        val readSites = listOf(0x11dcf74, 0x11dcd30, 0x11dd35c, 0x11dd244, 0x11dd3e8, 0x11dcde4, 0x11e1a68)
        val writeSites = listOf(0x11dce88, 0x11dccf4, 0x11dcd24, 0x11dcd3c, 0x11dd324, 0x11dd264, 0x11dd3fc, 0x11dcdf0, 0x11e1a78)
        for ((sites, helper) in listOf(readSites to 0x5afd8, writeSites to 0x5af90)) {
            for (pc in sites) assertEquals(0xeb000000.toInt() or (((helper - pc - 8) shr 2) and 0xffffff), word(pc))
        }
        assertEquals(0xe30f400c.toInt(), word(0x11e1a54))
        assertEquals(0xe34f4ff4.toInt(), word(0x11e1a58))
        assertEquals(0xe7c91295.toInt(), word(0x11e1a70))
    }

    @Test fun narrowedUartTailPromotesOnlyWithZeroPadding() {
        val current = bus(true)
        current.access(1, uart + 0x38L, 4, true, 0x07d0L)
        val saved = current.saveSnapshot()
        @Suppress("UNCHECKED_CAST")
        val regions = (saved["regions"] as Map<String, Map<String, Any?>>).toMutableMap()
        val tail = regions.getValue(UART_TAIL_REGION).toMutableMap()
        val decoder = Base64.getDecoder()
        val encoder = Base64.getEncoder()
        val eight = decoder.decode(tail["mutatedBytesBase64"] as String)
        assertEquals(8, eight.size)
        val twelveZero = eight + byteArrayOf(0, 0, 0, 0)
        tail["endExclusive"] = 0xfff3a040L
        tail["mutatedBytesBase64"] = encoder.encodeToString(twelveZero)
        regions[UART_TAIL_REGION] = tail
        val legacySnapshot = saved + ("regions" to regions)

        assertFails { bus(true).restoreSnapshot(legacySnapshot) }
        val restored = bus(true).apply { restoreSnapshot(legacySnapshot, allowPromotedRegions = true) }
        assertEquals(0L, restored.access(2, uart + 0x34L, 4, false))
        assertEquals(0x07d0L, restored.access(3, uart + 0x38L, 4, false))

        val nonZeroTail = tail.toMutableMap()
        nonZeroTail["mutatedBytesBase64"] = encoder.encodeToString(eight + byteArrayOf(1, 0, 0, 0))
        val badSnapshot = saved + ("regions" to (regions + (UART_TAIL_REGION to nonZeroTail)))
        assertFails { bus(true).restoreSnapshot(badSnapshot, allowPromotedRegions = true) }
    }
}
