package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class Image59000Test {
    @Test fun retainsOnlyTheSelectedWriteSpansAndPromotesTheExistingControlWord() {
        val old = mapOf("image50000" to "retained-words-zero-v1")
        val selected = old + ("image59000" to "retained-tables-v1")
        val catalog = AddressCatalog.bundled()
        val context = object : DiscoveryContext {
            override fun core() = 1
            override fun pc() = 0x5af98L
            override fun lr() = 0x11c44f0L
            override fun tick() = 833252378L
        }
        fun runtime(config: Map<String, String>, mode: String = "discovery") =
            CatalogRuntime(catalog, mode, Path.of("."), mapOf("discovery" to config))
        fun bus(config: Map<String, String> = selected) = checkNotNull(runtime(config).createBus(context))
        assertNull(runtime(selected, "strict").createBus(context))
        assertFails { bus(old + ("image59000" to "retained-bytes")) }
        val previous = bus(old)
        previous.access(1, 0xfff50248, 4, true, 0x9240abcd)
        val legacy = previous.saveSnapshot()
        assertFails { previous.access(2, 0xfff50248, 4, false) }
        assertFails { bus().restoreSnapshot(legacy) }
        val active = bus().apply { restoreSnapshot(legacy, true) }
        assertEquals(0x9240abcdL, active.access(2, 0xfff50248, 4, false))
        val base = 0xfff59000L
        val offsets = listOf(0x2bfc, 0x2000, 0x1bfc, 0x1000, 0x8fc, 0, 0x2bfc)
        for ((index, offset) in offsets.withIndex())
            active.access(index + 3L, base + offset, 4, true, 0xffff0000L + index)
        val saved = active.saveSnapshot()
        fun restored() = bus().apply { restoreSnapshot(saved) }
        assertEquals(saved, restored().saveSnapshot())
        assertFails { bus(old).restoreSnapshot(saved, true) }
        val row = (saved["regions"] as Map<*, *>)[IMAGE_59000_TABLES] as Map<*, *>
        val bytes = ByteBuffer.wrap(Base64.getDecoder().decode(row["mutatedBytesBase64"] as String)).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in offsets.distinct()) assertEquals(0xffff0000L + offsets.lastIndexOf(offset), bytes.getInt(offset).toUInt().toLong())
        val negative = restored()
        for (offset in listOf(-4, 0x900, 0xffc, 0x1c00, 0x1ffc, 0x2c00, 1))
            assertFails { negative.access(1, base + offset, 4, true, 1) }
        for (offset in offsets.distinct()) assertFails { negative.access(1, base + offset, 4, false) }
        for (width in listOf(1, 2, 8)) assertFails { negative.access(1, base, width, true, 1) }
        assertFails { negative.access(1, 0xfff50244, 4, false) }
    }
}
