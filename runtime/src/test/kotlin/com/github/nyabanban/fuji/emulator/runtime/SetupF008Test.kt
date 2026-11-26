package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class SetupF008Test {
    private val address = 0xfff4f008L
    private val context = object : DiscoveryContext {
        override fun core() = 3
        override fun tick() = 550435139L
        override fun pc() = 0x5af98L
        override fun lr() = 0x11e17c0L
    }

    private fun bus(selected: Boolean): DiscoveryBus {
        val ids = listOf("gfx100s.fff4f008.setup.candidate","gfx100s.fff4f010.setup-tail.candidate",
            "gfx100s.fff4f018.mode.candidate","gfx100s.fff4f020.mode-setup.candidate","gfx100s.fff7800c.control.candidate",
            "gfx100s.fff78008.setup.candidate","gfx100s.fff78010.setup-tail.candidate",
            "gfx100s.fff78018.mode.candidate","gfx100s.fff78020.mode-setup.candidate")
        val inputs = if (selected) ids.associateWith { mapOf("strategy" to "retained-bytes", "fill" to 0) } else emptyMap()
        return checkNotNull(CatalogRuntime(AddressCatalog.bundled(), "discovery", Path.of("."),
            mapOf("discovery" to mapOf("inputs" to inputs))).createBus(context))
    }

    @Test fun selectedWordAcceptsOnlyAlignedFourByteWritesAndRestores() {
        assertFailsWith<IllegalStateException> { bus(false).access(1,address,4,true,0x1000254b) }
        val selected = bus(true)
        assertEquals(0x1000254bL, selected.access(1,address,4,true,0x1000254b))
        assertEquals(3L, selected.access(2,address+8,4,true,3))
        assertEquals(0L, selected.access(3,address+12,4,true,0))
        assertEquals(0L, selected.access(4,address+16,4,false))
        assertEquals(0x1010000L, selected.access(5,address+16,4,true,0x1010000))
        assertEquals(0x7d3d0f0fL, selected.access(6,address+24,4,true,0x7d3d0f0f))
        assertEquals(0x1000100L, selected.access(7,address+32,4,true,0x1000100))
        assertEquals(0L, selected.access(8,0xfff7800c,4,false))
        assertEquals(0x3e0L, selected.access(9,0xfff7800c,4,true,0x3e0))
        val snapshot = selected.saveSnapshot()
        val restored = bus(true).apply { restoreSnapshot(snapshot) }
        assertEquals(snapshot,restored.saveSnapshot())
        assertEquals(0x3e0L,restored.access(10,0xfff7800c,4,false))
        assertFailsWith<IllegalStateException> { restored.access(2,address,4,false) }
        for (width in listOf(1,2,8)) assertFailsWith<IllegalStateException> { restored.access(3,address,width,true) }
        assertEquals(0x1010000L,restored.access(8,address+16,4,false))
        for (offset in listOf(-4,1,2,4,20,28,36)) assertFailsWith<IllegalStateException> { restored.access(9,address+offset,4,true) }
        for (offset in listOf(8,12,24,28,32)) assertFailsWith<IllegalStateException> { restored.access(10,address+offset,4,false) }
    }

    @Test fun secondInstanceRetainsItsOwnWordsAndRefusesHoles() {
        val base = 0xfff78000L
        val writes = mapOf(0x08 to 0x1000252bL, 0x10 to 3L, 0x14 to 0L,
            0x20 to 0x7d3f0f0fL, 0x28 to 0x01000000L, 0x18 to 0x00010000L)
        val plain = bus(false)
        val selected = bus(true)
        for ((offset, value) in writes) {
            assertFailsWith<IllegalStateException> { plain.access(1,base+offset,4,true,value) }
            assertEquals(value,selected.access(1,base+offset,4,true,value))
        }
        val saved = selected.saveSnapshot()
        val restored = bus(true).apply { restoreSnapshot(saved) }
        assertEquals(saved,restored.saveSnapshot())
        assertEquals(0x10000L,restored.access(2,base+0x18,4,false))
        assertEquals(0L,restored.access(3,address+16,4,false))
        for (offset in listOf(0x04,0x24,0x2c)) {
            for (write in listOf(false,true)) assertFailsWith<IllegalStateException> { restored.access(4,base+offset,4,write) }
        }
        for (offset in writes.keys - 0x18) assertFailsWith<IllegalStateException> { restored.access(5,base+offset,4,false) }
        for (width in listOf(1,2,8)) assertFailsWith<IllegalStateException> { restored.access(6,base+8,width,true) }
        assertFailsWith<IllegalStateException> { restored.access(7,base+9,4,true) }
    }

    @Test fun sectionFourConstructsTheAddressAndCallsKernelWordStore() {
        val decoded = Path.of(System.getProperty("componentProjectRoot"), "firmware-data/gfx100s/2.13/decoded")
        val section = ByteBuffer.wrap(Files.readAllBytes(decoded.resolve("section4.bin"))).order(ByteOrder.LITTLE_ENDIAN)
        val kernel = ByteBuffer.wrap(Files.readAllBytes(decoded.resolve("kernel.bin"))).order(ByteOrder.LITTLE_ENDIAN)
        val instructions = listOf(0xe30f0008,0xe34f0ff4,0xe0850000,0xebb9e5f3)
        val poll = listOf(0xe30f401c,0xe34f4ff4,0xe0854004,0xe3a01001,0xe1a00004,0xebb9e611,
            0xe1a00004,0xebb9e621,0xe3100001,0x1afffffb)
        poll.forEachIndexed { index, word -> assertEquals(word.toInt(),section.getInt(0x11e1730 - 0x1021000 + 4*index)) }
        instructions.forEachIndexed { index, word ->
            assertEquals(word.toInt(),section.getInt(0x11e17b0 - 0x1021000 + 4*index))
        }
        assertEquals(0xe5801000.toInt(),kernel.getInt(0x5af98 - 0x40000))
        val tail = listOf(0xe3a01003,0xe245080b,0xe2400eff,0xebb9e5db,0xe3a01000,
            0xe30f0014,0xe34f0ff4,0xe0850000,0xebb9e5d6)
        tail.forEachIndexed { index, word -> assertEquals(word.toInt(),section.getInt(0x11e1810 - 0x1021000 + 4*index)) }
        val mode = listOf(0xe245080b,0xe2400efe,0xebb9e58c,0xe3a01c01,0xe3401100,0xe7d01814,0xe3c11001,
            0xe30f0028,0xe34f0ff4,0xe0850000,0xebb9e584,0xe30f4018,0xe34f4ff4,0xe0854004,0xe1a00004,
            0xebb9e591,0xe3801401,0xe3811801,0xe1a00004,0xebb9e57b)
        mode.forEachIndexed { index, word -> assertEquals(word.toInt(),section.getInt(0x11e1950 - 0x1021000 + 4*index)) }
        val instance = listOf(0xe30f400c,0xe34f4ff4,0xe3a03a29,0xe0244093,0xe1a00004,0xebb9e55a,
            0xe1a01000,0xe7c91295,0xe1a00004,0xebb9e544)
        instance.forEachIndexed { index, word -> assertEquals(word.toInt(),section.getInt(0x11e1a54 - 0x1021000 + 4*index)) }
    }
}
