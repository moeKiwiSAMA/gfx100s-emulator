package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ImageResultWordsTest {
    @TempDir lateinit var home: Path
    private val catalog = AddressCatalog.bundled()
    private val base = 0xfff11060L
    private val file get() = home.resolve("surface.bin")
    private fun image() = mapOf("strategy" to "image-backed", "file" to "surface.bin", "sha256" to sha256(file))
    private fun selector10() = image() + ("strategy" to DiscoveryStrategy.IMAGE_RESULT_SELECTOR10.id)
    private fun selector10Repeatable() = image() + ("strategy" to DiscoveryStrategy.IMAGE_RESULT_SELECTOR10_REPEATABLE.id)
    private fun bus(input: Map<String, String>? = null): DiscoveryBus {
        val inputs = input?.let { mapOf(IMAGE_RESULT_SURFACE to it) }.orEmpty()
        return checkNotNull(CatalogRuntime(catalog, "discovery", home, mapOf("discovery" to mapOf("inputs" to inputs)))
            .createBus(object : DiscoveryContext {
                override fun core() = 1
                override fun tick() = 833336759L
                override fun pc() = 0x02371990L
                override fun lr() = 0x0129d2f0L
            }))
    }

    @Test fun readsOneSurfaceAcrossPromotionAndRestore() {
        val bytes = ByteBuffer.allocate(0x1980).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in 0 until bytes.capacity() step 4) bytes.putInt(offset, 0x12340000 + offset)
        Files.write(file, bytes.array())
        val prior = bus().saveSnapshot()
        assertFails { bus().access(1, 0xfff129a4, 4, false) }
        val selected = bus(image())
        assertFails { bus(image()).restoreSnapshot(prior) }
        selected.restoreSnapshot(prior, true)
        var sequence = 1L
        fun read(bus: DiscoveryBus, offset: Int) {
            assertEquals(0x12340000L + offset, bus.access(sequence++, base + offset, 4, false))
        }
        val rejected = bus(image())
        for (offset in listOf(-4, 0x1000, 0x193c, 0x1980, 1)) {
            assertFails { rejected.access(sequence++, base + offset, 4, false) }
        }
        for (width in listOf(1, 2, 8)) assertFails { rejected.access(sequence++, base, width, false) }
        assertFails { rejected.access(sequence++, base, 4, true, 1) }
        for (offset in 0x1940 until 0x1980 step 8) {
            read(selected, offset + 4)
            read(selected, offset)
        }
        val saved = selected.saveSnapshot()
        val resumed = bus(image()).apply { restoreSnapshot(saved) }
        for (offset in 0 until 0x1000 step 4) read(resumed, offset)
        assertFails { resumed.access(sequence++, base, 4, false) }
        assertFails { bus().restoreSnapshot(saved, true) }
        Files.write(file, ByteArray(0x1980))
        assertFails { bus(image()).restoreSnapshot(saved, true) }
    }

    @Test fun rejectsUndeclaredBackingOrIncorrectImageIdentity() {
        assertFails { bus(mapOf("strategy" to "fixed-input", "value" to "0")) }
        Files.write(file, ByteArray(0x197f))
        assertFails { bus(image()) }
        Files.write(file, ByteArray(0x1980))
        assertFails { bus(image() + ("sha256" to "0".repeat(64))) }
    }

    @Test fun checksSelectorsCopyBoundsAndParameterPairLoadsAgainstDat() {
        val root = Path.of(System.getProperty("componentProjectRoot"))
        val section = ByteBuffer.wrap(Files.readAllBytes(root.resolve("firmware-data/gfx100s/2.13/decoded/section5.bin")))
            .order(ByteOrder.LITTLE_ENDIAN)
        fun word(pc: Int) = section.getInt(pc - 0x01f28000)
        for ((selector, target) in mapOf(6 to 0x237024c, 7 to 0x2370258, 8 to 0x2370264, 9 to 0x2370270,
            10 to 0x237027c, 16 to 0x23702c4, 17 to 0x23702d0)) {
            assertEquals(target, word(0x2370184 + selector * 4))
            val offset = when (selector) { 16 -> 0x29a0; 17 -> 0x29c0; else -> 0x1060 + (selector - 6) * 0x400 }
            val immediate = (offset - if (selector == 17) 0 else 32) shr 6
            assertEquals(0xe2800d00.toInt() or immediate, word(target))
            if (selector != 17) assertEquals(0xe2800020.toInt(), word(target + 4))
        }
        for ((pc, instruction) in mapOf(0x237193c to 0xe2887008, 0x2371944 to 0xe2888028, 0x2371978 to 0xe2877008,
            0x237197c to 0xe1570008, 0x2371990 to 0xe5173004, 0x23719a8 to 0xe517c008,
            0x24b8e94 to 0xe2411004, 0x24b8e98 to 0xe2802b01, 0x24b8e9c to 0xe5b13004,
            0x24b8ea0 to 0xe4803004, 0x24b8ea4 to 0xe1500002, 0x24b8ea8 to 0x1afffffb,
            0x24b8ec8 to 0xe14c40d8, 0x24b8ecc to 0xe1cc80d0, 0x24b8ed0 to 0xe1cc60d8,
            0x24b8ed4 to 0xe1cc21d0, 0x24b8ef4 to 0x1afffff3)) {
            assertEquals(instruction.toInt(), word(pc))
        }
        for (call in listOf(0x24b96ec, 0x24b9708, 0x24b9724, 0x24b9740)) {
            assertEquals(0xeb000000.toInt() or (((0x24b8e8c - call - 8) shr 2) and 0xffffff), word(call))
        }
    }

    @Test fun promotesConsumedSurfaceAndRestoresBetweenTwoWordCallbacks() {
        Files.write(file, ByteArray(0x1980))
        val old = bus(image())
        repeat(1040) { old.access(it + 1L, base, 4, false) }
        val exhausted = old.saveSnapshot()
        assertFails { bus(selector10()).restoreSnapshot(bus().saveSnapshot(), true) }
        assertFails { bus(selector10()).restoreSnapshot(exhausted) }
        val promoted = bus(selector10()).apply { restoreSnapshot(exhausted, true) }
        val first = 0xfff12060L
        assertEquals(0L, promoted.access(1, first, 4, false))
        val saved = promoted.saveSnapshot()
        val rows = saved["regions"] as Map<*, *>
        val row = rows[IMAGE_RESULT_SURFACE] as Map<*, *>
        assertEquals(1040, row["position"])
        assertEquals(1, row["selector10Reads"])
        assertFails { bus(image()).restoreSnapshot(saved, true) }
        for (bad in listOf(-1, 0, 81, "1", 0x100000001L)) {
            assertFails { bus(selector10()).restoreSnapshot(saved + ("regions" to rows +
                (IMAGE_RESULT_SURFACE to row + ("selector10Reads" to bad))), true) }
        }
        fun resume() = bus(selector10()).apply { restoreSnapshot(saved) }
        assertFails { resume().access(2, base, 4, false) }
        val resumed = resume()
        for (index in 1 until 80) assertEquals(0L, resumed.access(index + 1L, first + index * 4, 4, false))
        val done = resumed.saveSnapshot()
        assertFails { bus(selector10()).apply { restoreSnapshot(done) }.access(81, first, 4, false) }
    }

    @Test fun limitsNewSliceWithoutChangingLegacyWordsOrPermissions() {
        val bytes = ByteArray(0x1980)
        bytes[0] = 37
        Files.write(file, bytes)
        val first = 0xfff12060L
        val partial = bus(image()).apply { access(1, base, 4, false) }.saveSnapshot()
        fun permitted(input: Map<String, String>, address: Long, direction: String): Boolean {
            val runtime = CatalogRuntime(catalog, "discovery", home, mapOf("discovery" to mapOf("inputs" to
                mapOf(IMAGE_RESULT_SURFACE to input))))
            // Exercise the discovery fallback, with the promoted ISP result-surface device inactive.
            runtime.bindPorts(emptyList(), catalog.defaultMemoryProfile, catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile))
            return runtime.query(mapOf("address" to address.toString(), "width" to "4", "direction" to direction))["permitted"] == true
        }
        assertFalse(permitted(image(), first, "read"))
        assertTrue(permitted(selector10(), first, "read"))
        assertFalse(permitted(selector10(), first, "write"))
        assertFalse(permitted(selector10(), 0xfff121a0L, "read"))
        val active = bus(selector10()).apply { restoreSnapshot(partial, true) }
        assertEquals(0, ((active.saveSnapshot()["regions"] as Map<*, *>)[IMAGE_RESULT_SURFACE] as Map<*, *>)["selector10Reads"])
        repeat(80) { active.access(it + 1L, first, 4, false) }
        assertEquals(37L, active.access(81, base, 4, false))
        assertFails { bus(image()).access(1, first, 4, false) }
        for (width in listOf(1, 2, 8)) assertFails { bus(selector10()).access(1, first, width, false) }
        for (address in listOf(first + 1, 0xfff121a0, 0xfff1299c)) assertFails { bus(selector10()).access(1, address, 4, false) }
        assertFails { bus(selector10()).access(1, first, 4, true, 0) }
        bytes[0x1000] = 1
        Files.write(file, bytes)
        assertFails { bus(selector10()) }
        bytes[0x1000] = 0
        bytes[0] = 38
        Files.write(file, bytes)
        assertFails { bus(selector10()).restoreSnapshot(partial, true) }
    }

    @Test fun repeatableSelector10PromotesFromZeroV1AndAllowsUnlimitedMultiFrameReads() {
        val bytes = ByteArray(0x1980)
        bytes[0] = 42
        Files.write(file, bytes)
        val first = 0xfff12060L

        // Query permissions match read-only selector10 rules.
        fun permitted(address: Long, direction: String): Boolean {
            val runtime = CatalogRuntime(catalog, "discovery", home, mapOf("discovery" to mapOf("inputs" to
                mapOf(IMAGE_RESULT_SURFACE to selector10Repeatable()))))
            return runtime.query(mapOf("address" to address.toString(), "width" to "4", "direction" to direction))["permitted"] == true
        }
        assertTrue(permitted(base, "read"))
        assertTrue(permitted(first, "read"))
        assertFalse(permitted(first, "write"))
        assertFalse(permitted(base, "write"))
        assertFalse(permitted(0xfff121a0L, "read"))

        // Exhaust image-backed-selector10-zero-v1 to 1040 surface reads + 80 selector10 reads.
        val old = bus(image())
        repeat(1040) { old.access(it + 1L, base, 4, false) }
        val exhaustedLegacy = bus(selector10()).apply { restoreSnapshot(old.saveSnapshot(), true) }
        repeat(80) { exhaustedLegacy.access(it + 1041L, first + (it % 80) * 4, 4, false) }
        val exhaustedSnap = exhaustedLegacy.saveSnapshot()

        // Promotion requires allowPromotedRegions = true.
        assertFails { bus(selector10Repeatable()).restoreSnapshot(exhaustedSnap, false) }
        val promoted = bus(selector10Repeatable()).apply { restoreSnapshot(exhaustedSnap, true) }

        // Continue reading well past 1040 surface reads and 80 selector10 reads.
        repeat(500) { assertEquals(42L, promoted.access(it + 2000L, base, 4, false)) }
        repeat(120) { assertEquals(0L, promoted.access(it + 3000L, first + (it % 80) * 4, 4, false)) }

        // Width, alignment, write, and prohibited hole rules remain enforced.
        for (width in listOf(1, 2, 8)) assertFails { bus(selector10Repeatable()).access(1, first, width, false) }
        for (address in listOf(first + 1, 0xfff121a0L, 0xfff1299cL)) {
            assertFails { bus(selector10Repeatable()).access(1, address, 4, false) }
        }
        assertFails { bus(selector10Repeatable()).access(1, first, 4, true, 0) }
        assertFails { bus(selector10Repeatable()).access(1, base, 4, true, 0) }

        // Save and restore with position=1540 (>1040) and selector10Reads=200 (>80).
        val saved = promoted.saveSnapshot()
        val row = (saved["regions"] as Map<*, *>)[IMAGE_RESULT_SURFACE] as Map<*, *>
        assertEquals(1540, row["position"])
        assertEquals(200, row["selector10Reads"])
        assertEquals(1740L, row["accessAttempts"])
        val resumed = bus(selector10Repeatable()).apply { restoreSnapshot(saved) }
        assertEquals(saved, resumed.saveSnapshot())
        assertEquals(42L, resumed.access(4000, base, 4, false))
        assertEquals(0L, resumed.access(4001, first, 4, false))

        // Downgrade to image-backed-selector10-zero-v1 or image-backed is rejected.
        assertFails { bus(selector10()).restoreSnapshot(saved, true) }
        assertFails { bus(image()).restoreSnapshot(saved, true) }

        // Non-zero bytes in selector10 slice are still rejected.
        bytes[0x1000] = 1
        Files.write(file, bytes)
        assertFails { bus(selector10Repeatable()) }
    }
}
