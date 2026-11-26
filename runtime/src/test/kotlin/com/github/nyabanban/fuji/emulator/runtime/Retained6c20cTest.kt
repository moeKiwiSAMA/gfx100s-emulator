package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class Retained6c20cTest {
    private val base = 0xfff6c20cL
    private val accessors = listOf(
        0x011b397c to 0xc21c,
        0x011b3990 to 0xc20c,
        0x011b39a0 to 0xc210,
        0x011b39b4 to 0xc214,
        0x011b39c4 to 0xc218,
    )

    private fun bus(fill: Int?): DiscoveryBus {
        val inputs = if (fill == null) emptyMap() else mapOf(RETAINED_6C20C_REGION to mapOf("strategy" to "retained-bytes", "fill" to fill))
        return checkNotNull(CatalogRuntime(AddressCatalog.bundled(), "discovery", Path.of("."),
            mapOf("discovery" to mapOf("inputs" to inputs))).createBus(object : DiscoveryContext {
            override fun core() = 1
            override fun tick() = 505662552L
            override fun pc() = 0x5af98L
            override fun lr() = 0x11b3988L
        }))
    }

    private fun instructionAt(words: IntArray, pc: Int) = words[(pc - 0x01021000) / 4]

    private fun blTarget(words: IntArray, pc: Int): Long? {
        val word = instructionAt(words, pc)
        if ((word ushr 24) != 0xeb) return null
        var displacement = (word and 0x00ffffff).toLong()
        if (displacement and 0x00800000L != 0L) displacement -= 0x01000000L
        return pc + 8L + displacement * 4
    }

    @Test fun burstWritesMatchSection4AddressLoads() {
        val root = Path.of(System.getProperty("componentProjectRoot"))
        val bytes = Files.readAllBytes(root.resolve("firmware-data/gfx100s/2.13/decoded/section4.bin"))
        val words = IntArray(bytes.size / 4) { i -> ByteBuffer.wrap(bytes, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int }
        for ((pc, literal) in accessors) {
            val encoded = 0xe0000000.toInt() or 0x03000000 or (((literal ushr 12) and 0xf) shl 16) or (literal and 0xfff)
            assertEquals(encoded, instructionAt(words, pc))
            assertEquals(0xe34f0ff6.toInt(), instructionAt(words, pc + 4))
            assertEquals(0x0005af90L, blTarget(words, pc + 8))
        }
    }

    @Test fun selectedWordsRetainWritesAndRefuseOutsideTheSet() {
        val selected = bus(0)
        for (offset in 0..16 step 4) {
            assertEquals(0L, selected.access(1, base + offset, 4, false))
            selected.access(2, base + offset, 4, true, 0x2000L + offset)
            assertEquals(0x2000L + offset, selected.access(3, base + offset, 4, false))
        }
        assertFails { selected.access(1, base + 20, 4, false) }
        assertFails { selected.access(1, base, 2, false) }
    }

    @Test fun snapshotRequiresExplicitPromotionAndPreservesBurstWords() {
        val legacy = bus(null)
        val prefix = legacy.saveSnapshot()
        assertFails { legacy.access(1, base, 4, false) }
        val selected = bus(0)
        assertFails { selected.restoreSnapshot(prefix) }
        selected.restoreSnapshot(prefix, true)
        for (offset in 0..16 step 4) selected.access(2, base + offset, 4, true, 0x3000L + offset)
        val saved = selected.saveSnapshot()
        val restored = bus(0).apply { restoreSnapshot(saved) }
        for (offset in 0..16 step 4) assertEquals(0x3000L + offset, restored.access(1, base + offset, 4, false))
        assertFails { bus(1).restoreSnapshot(saved, true) }
    }
}
