package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*
import org.junit.jupiter.api.Test

class Image72000Test {
    private val catalog = AddressCatalog.bundled()
    private val region = "gfx100s.fff72028.config-words.candidate"
    private val first = 0xfff72028L
    private val context = object : DiscoveryContext {
        override fun core() = 1
        override fun pc() = 0x5af98L
        override fun lr() = 0x11bf424L
        override fun tick() = 833367107L
    }
    private fun bus(selected: Boolean = true, capacity: Int = 128): DiscoveryBus {
        val inputs = if (selected) mapOf(region to mapOf("strategy" to "retained-bytes", "fill" to 0)) else emptyMap()
        return checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."),
            mapOf("discovery" to mapOf("inputs" to inputs, "logCapacity" to capacity))).createBus(context))
    }

    @Test fun bothChannelsRetainWritesAndAllowReprogramming() {
        val active = bus()
        val pages = MmioRouter(emptyList(), active).pageRanges()
        for (page in listOf(0xfff72000L, 0xfff73000L)) {
            assertTrue(pages.any { (base, length) -> page >= base && page + 4096 <= base + length })
        }
        var sequence = 0L
        for (base in listOf(0xfff72000L, 0xfff73000L)) {
            active.access(++sequence, base + 0x28, 4, true, 0x10001000)
            active.access(++sequence, base + 0x34, 4, true, 0)
            active.access(++sequence, base + 0x38, 4, true, 0)
            for (offset in 0x40..0xb8 step 4) active.access(++sequence, base + offset, 4, true, 0x10000)
        }
        val saved = active.saveSnapshot()
        val writes = active.snapshot()["accesses"] as List<*>
        assertEquals(68, writes.size)
        val firstWrite = writes.first() as Map<*, *>
        assertEquals(first, firstWrite["addressPhysical"])
        assertEquals(0x10001000L, firstWrite["value"])
        assertEquals(true, firstWrite["completed"])
        val restored = bus().apply { restoreSnapshot(saved) }
        restored.access(++sequence, first, 4, true, 0xffffffffL)
        val row = (restored.saveSnapshot()["regions"] as Map<*, *>)[region] as Map<*, *>
        val bytes = ByteBuffer.wrap(Base64.getDecoder().decode(row["mutatedBytesBase64"] as String)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-1, bytes.getInt(0))
        assertEquals(0x10001000, bytes.getInt(0x1000))
        assertEquals(0x10000, bytes.getInt(0x1090))
        assertEquals(69L, (row["accessAttempts"] as Number).toLong())
    }

    @Test fun rejectsReadsHolesOtherWidthsAndLogOverflow() {
        assertFails { bus(false).access(1, first, 4, true, 0x10001000) }
        for (base in listOf(0xfff72000L, 0xfff73000L)) {
            for (offset in listOf(0x28, 0x34, 0x38, 0x40, 0xb8)) assertFails { bus().access(1, base + offset, 4, false) }
            for (offset in listOf(0x24, 0x29, 0x2c, 0x30, 0x3c, 0xbc, 0x800)) assertFails {
                bus().access(1, base + offset, 4, true, 0)
            }
        }
        for (width in listOf(1, 2, 8)) assertFails { bus().access(1, first, width, true, 0) }
        val capped = bus(capacity = 2)
        repeat(2) { capped.access(it + 1L, first, 4, true, it.toLong()) }
        assertFails { capped.access(3, first, 4, true, 2) }
    }
}
