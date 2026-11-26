package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class Retained6b000Test {
    private val base = 0xfff6b000L
    private val calls = listOf(
        Triple(0x00, 0x11c6f88, 0x11c6fa8), Triple(0x04, 0x11c7020, 0x11c7060),
        Triple(0x08, 0x11c7218, 0x11c72dc), Triple(0x0c, 0x11c742c, 0x11c749c),
        Triple(0x10, 0x11c7718, 0x11c77ac), Triple(0x14, 0x11c7728, 0x11c77bc),
        Triple(0x20, 0x11c7858, 0x11c7888), Triple(0x24, 0x11c78c0, 0x11c78e8),
        Triple(0x28, 0x11c792c, 0x11c795c), Triple(0x2c, 0x11c79a8, 0x11c79d8),
        Triple(0xf0, 0x11c7a10, 0x11c7a38), Triple(0xf4, 0x11c7a7c, 0x11c7aac),
    )
    private val catalog = AddressCatalog.bundled()
    private fun bus(fill: Int?): DiscoveryBus {
        val inputs = if (fill != null) mapOf(RETAINED_6B000_REGION to mapOf("strategy" to "retained-bytes", "fill" to fill)) else emptyMap()
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to
            mapOf("inputs" to inputs))).createBus(object : DiscoveryContext {
            override fun core() = 1
            override fun tick() = 700760780L
            override fun pc() = 0x5afe0L
            override fun lr() = 0x11c7064L
        }))
    }

    @Test fun wordRunsMatchDatAddressesAndReadWriteHelpers() {
        val root = Path.of(System.getProperty("componentProjectRoot"))
        val bytes = Files.readAllBytes(root.resolve("firmware-data/gfx100s/2.13/decoded/section4.bin"))
        val section = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for ((offset, write, read) in calls) {
            for ((pc, helper) in listOf(write to 0x5af90, read to 0x5afd8)) {
                val index = pc - 0x1021000
                val lowWord = if (offset == 0) 0xe3a00a0b.toInt() else 0xe30b0000.toInt() or offset
                assertEquals(lowWord, section.getInt(index - 8))
                assertEquals(0xe34f0ff6.toInt(), section.getInt(index - 4))
                assertEquals(0xeb000000.toInt() or (((helper - pc - 8) shr 2) and 0xffffff), section.getInt(index))
            }
        }
        val selected = bus(165)
        for (offset in 0 until 248 step 4) {
            if (calls.any { it.first == offset }) {
                assertEquals(0xa5a5a5a5L, selected.access(1, base + offset, 4, false))
                assertEquals(offset.toLong(), selected.access(2, base + offset, 4, true, offset.toLong()))
            } else {
                assertFails { selected.access(3, base + offset, 4, false) }
                assertFails { selected.access(4, base + offset, 4, true, 0) }
            }
        }
    }

    @Test fun preservesSelectionAndWrittenWordsAcrossRestore() {
        val legacy = bus(null).saveSnapshot()
        assertFails { bus(null).access(1, base + 4, 4, false) }
        assertFails { bus(0).restoreSnapshot(legacy) }
        val selected = bus(0).apply { restoreSnapshot(legacy, true) }
        selected.access(1, base + 4, 4, true, 0x0503)
        val saved = selected.saveSnapshot()
        assertEquals(0x0503L, bus(0).apply { restoreSnapshot(saved) }.access(2, base + 4, 4, false))
        assertFails { bus(1).restoreSnapshot(saved, true) }
        assertFails { bus(null).restoreSnapshot(saved, true) }
        assertFails { bus(0).access(1, base + 4, 2, true, 1) }
        assertFails { bus(0).access(1, base + 248, 4, false) }
    }
}
