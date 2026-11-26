package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class Retained69000Test {
    private val base = 0xfff69000L
    private val offsets = listOf(0, 8, 12, 20, 24, 28, 32, 36, 40)
    private fun bus(fill: Int?): DiscoveryBus {
        val inputs = if (fill == null) emptyMap() else mapOf(RETAINED_69000_REGION to mapOf("strategy" to "retained-bytes", "fill" to fill))
        return checkNotNull(CatalogRuntime(AddressCatalog.bundled(), "discovery", Path.of("."),
            mapOf("discovery" to mapOf("inputs" to inputs))).createBus(object : DiscoveryContext {
            override fun core() = 1
            override fun tick() = 700761273L
            override fun pc() = 0x5afe0L
            override fun lr() = 0x11c7dccL
        }))
    }

    @Test fun selectedOffsetsHavePairedDatAccessors() {
        val root = Path.of(System.getProperty("componentProjectRoot"))
        val section = ByteBuffer.wrap(Files.readAllBytes(root.resolve("firmware-data/gfx100s/2.13/decoded/section4.bin"))).order(ByteOrder.LITTLE_ENDIAN)
        val pairs = listOf(0x11c7bc4 to 0x11c7af0, 0x11c7c54 to 0x11c7c84, 0x11c7d50 to 0x11c7dc0,
            0x11c7e60 to 0x11c7e98, 0x11c7f14 to 0x11c7f54, 0x11c7fd0 to 0x11c8010,
            0x11c808c to 0x11c80cc, 0x11c8138 to 0x11c8170, 0x11c81d4 to 0x11c820c)
        val selected = bus(165)
        for ((offset, pair) in offsets.zip(pairs)) {
            for ((pc, helper) in listOf(pair.first to 0x5af90, pair.second to 0x5afd8)) {
                val index = pc - 0x1021000
                assertEquals(if (offset == 0) 0xe3a00a09.toInt() else 0xe3090000.toInt() or offset, section.getInt(index))
                assertEquals(0xe34f0ff6.toInt(), section.getInt(index + 4))
                assertEquals(0xeb000000.toInt() or (((helper - pc - 16) shr 2) and 0xffffff), section.getInt(index + 8))
            }
            assertEquals(0xa5a5a5a5L, selected.access(1, base + offset, 4, false))
            selected.access(2, base + offset, 4, true, offset.toLong())
            assertEquals(offset.toLong(), selected.access(3, base + offset, 4, false))
        }
        for (offset in listOf(4, 16)) {
            assertFails { selected.access(1, base + offset, 4, false) }
            assertFails { selected.access(1, base + offset, 4, true, 0) }
        }
    }

    @Test fun snapshotRequiresExplicitPromotionAndPreservesPreviousWrite() {
        val legacy = bus(null)
        val prefix = legacy.saveSnapshot()
        assertFails { legacy.access(1, base + 12, 4, false) }
        val selected = bus(0)
        assertFails { selected.restoreSnapshot(prefix) }
        selected.restoreSnapshot(prefix, true)
        selected.access(2, base + 12, 4, true, 0x1000)
        val saved = selected.saveSnapshot()
        assertEquals(0x1000L, bus(0).apply { restoreSnapshot(saved) }.access(3, base + 12, 4, false))
        assertFails { bus(1).restoreSnapshot(saved, true) }
        assertFails { selected.access(4, base + 12, 2, false) }
    }
}
