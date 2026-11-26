package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test

class Retained69100Test {
    private val base = 0xfff69100L
    private val readWrite = listOf(0) + (4..64 step 4) + listOf(0x50, 0x100) +
        (0x104..0x140 step 4) + listOf(0x150)
    private val readOnly = listOf(0x44, 0x48, 0x144, 0x148)
    private val holes = listOf(0x4c, 0x14c) + (0x54..0xfc step 4)

    private fun bus(fill: Int?): DiscoveryBus {
        val inputs = if (fill == null) emptyMap() else mapOf(RETAINED_69100_REGION to mapOf("strategy" to "retained-bytes", "fill" to fill))
        return checkNotNull(CatalogRuntime(AddressCatalog.bundled(), "discovery", Path.of("."),
            mapOf("discovery" to mapOf("inputs" to inputs))).createBus(object : DiscoveryContext {
            override fun core() = 1
            override fun tick() = 700763589L
            override fun pc() = 0x5afe0L
            override fun lr() = 0x11c82c0L
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

    @Test fun declaredWordsMatchSection4AccessorDirections() {
        val root = Path.of(System.getProperty("componentProjectRoot"))
        val bytes = Files.readAllBytes(root.resolve("firmware-data/gfx100s/2.13/decoded/section4.bin"))
        val words = IntArray(bytes.size / 4) { i ->
            ByteBuffer.wrap(bytes, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
        fun movwOffset(word: Int): Int? {
            val condition = word ushr 28
            if (condition > 1 || word and 0x0ff0f000 != 0x03000000) return null
            return ((word ushr 16) and 0xf) shl 12 or (word and 0xfff)
        }
        val observed = mutableMapOf<Int, MutableSet<String>>()
        for (i in 0 until words.size - 2) {
            val offset = movwOffset(words[i]) ?: continue
            val movtIndex = (i + 1..minOf(i + 2, words.size - 2)).firstOrNull { words[it] == 0xe34f0ff6.toInt() } ?: continue
            val target = blTarget(words, 0x01021000 + (movtIndex + 1) * 4) ?: continue
            val direction = when (target) {
                0x0005afd8L -> "read"
                0x0005af90L -> "write"
                else -> continue
            }
            val relative = offset - 0x9100
            if (0 <= relative && relative <= 0x150) observed.getOrPut(relative) { mutableSetOf() }.add(direction)
        }
        val expected = (readWrite + readOnly).filter { it != 0 && it != 0x100 }
            .associateWith { if (it in readWrite) setOf("read", "write") else setOf("read") }
        assertEquals(expected, observed.mapValues { it.value.toSet() }.toSortedMap())

        assertEquals(0x03a00c91, instructionAt(words, 0x011c8268))
        assertEquals(0x13a00c92, instructionAt(words, 0x011c826c))
        assertEquals(0xe34f0ff6.toInt(), instructionAt(words, 0x011c8270))
        assertEquals(0x0005af90L, blTarget(words, 0x011c8274))
        assertEquals(0x03a00c91, instructionAt(words, 0x011c82b0))
        assertEquals(0x13a00c92, instructionAt(words, 0x011c82b4))
        assertEquals(0xe34f0ff6.toInt(), instructionAt(words, 0x011c82b8))
        assertEquals(0x0005afd8L, blTarget(words, 0x011c82bc))
    }

    @Test fun selectedWordsPreserveDeclaredDirectionsAndRefuseHoles() {
        val selected = bus(0)
        for (offset in readWrite) {
            assertEquals(0L, selected.access(1, base + offset, 4, false))
            selected.access(2, base + offset, 4, true, 0x1000L + offset)
            assertEquals(0x1000L + offset, selected.access(3, base + offset, 4, false))
        }
        for (offset in readOnly) {
            assertEquals(0L, selected.access(1, base + offset, 4, false))
            assertFails { selected.access(1, base + offset, 4, true, 1) }
        }
        for (offset in holes) {
            assertFails { selected.access(1, base + offset, 4, false) }
            assertFails { selected.access(1, base + offset, 4, true, 0) }
        }
        assertFails { selected.access(1, base + 0x154, 4, false) }
        assertFails { selected.access(1, base + 4, 2, false) }
    }

    @Test fun snapshotRequiresExplicitPromotionAndPreservesPreviousWrite() {
        val legacy = bus(null)
        val prefix = legacy.saveSnapshot()
        assertFails { legacy.access(1, base + 4, 4, false) }
        val selected = bus(0)
        assertFails { selected.restoreSnapshot(prefix) }
        selected.restoreSnapshot(prefix, true)
        selected.access(2, base + 4, 4, true, 0x1234)
        val saved = selected.saveSnapshot()
        assertEquals(0x1234L, bus(0).apply { restoreSnapshot(saved) }.access(3, base + 4, 4, false))
        assertFails { bus(1).restoreSnapshot(saved, true) }
    }
}
