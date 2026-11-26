package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlashDictionaryTest {
    private val root: Path = Path.of(System.getProperty("catalogRoot", ".")).toAbsolutePath().normalize()

    @Test
    fun compilesVerifiesFlashDictionaryAndTemplateAlignment() {
        val catalog = CatalogCompiler.compile(root)
        val flash = catalog.data["flash"] as? Map<*, *>
        assertNotNull(flash, "Compiled catalog must include flash dictionary")
        assertEquals("gfx100s-213-flash-layout-v1", flash["id"])
        assertEquals(32, (flash["partitions"] as List<*>).size)
        assertEquals(7, (flash["domains"] as List<*>).size)
        assertEquals(3, (flash["checksums"] as List<*>).size)
        assertTrue((flash["fields"] as List<*>).size >= 20)

        @Suppress("UNCHECKED_CAST")
        FlashDictionary.verifyReleaseTemplate(flash as Map<String, Any?>, root)

        val md = FlashDictionary.renderMarkdown(flash, catalog)
        assertTrue(md.contains("part-14-bank14-factory-config"))
        assertTrue(md.contains("bkup-blocka-parity2d"))
        assertTrue(md.contains("bank14.battery_end_adc_threshold"))
    }

    @Test
    fun rejectsOverlappingFieldsAndBadConfidence() {
        val catalog = CatalogCompiler.compile(root)
        @Suppress("UNCHECKED_CAST")
        val firmware = catalog.data["firmware"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val baseFlash = catalog.data["flash"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val baseFields = baseFlash["fields"] as List<Map<String, Any?>>

        // Overlapping field in blank-ff-experiment (overlaps bank14.header_magic @ 0x200..0x202)
        val overlap = baseFields + mapOf(
            "id" to "bank14.overlap_test",
            "domainId" to "blank-ff-experiment",
            "relativeOffset" to 0x201L,
            "length" to 2L,
            "type" to "u16le",
            "confidence" to "HYPOTHESIS",
            "factoryDefaultHex" to "0000",
            "applyInFactoryTemplate" to false,
            "evidence" to "unit test",
            "description" to "overlap rejection check",
        )
        assertFailsWith<IllegalArgumentException> {
            FlashDictionary.validate(baseFlash + ("fields" to overlap), firmware)
        }

        // Invalid confidence class
        val badConfidence = baseFields.mapIndexed { idx, row ->
            if (idx == 0) row + ("confidence" to "GUESS") else row
        }
        assertFailsWith<IllegalArgumentException> {
            FlashDictionary.validate(baseFlash + ("fields" to badConfidence), firmware)
        }
    }

    @Test
    fun parity2dMatchesSection4Law() {
        val block = ByteArray(0x1efc)
        // Row 0: bit 0 (byte 0 = 0x01) and bit 31 (byte 3 = 0x80) -> even number of 1-bits in row 0 => rowParity[0] bit 0 = 0
        block[0] = 0x01
        block[3] = 0x80.toByte()
        // Row 1: bit 8 (byte 5 = 0x01) -> odd number of 1-bits in row 1 => rowParity[0] bit 1 = 1
        block[5] = 0x01
        val (rowPar, colPar) = FlashDictionary.computeParity2d(block)
        assertEquals(256, rowPar.size)
        assertEquals(4, colPar.size)
        assertEquals(0x02.toByte(), rowPar[0])
        assertContentEquals(byteArrayOf(0x01, 0x01, 0x00, 0x80.toByte()), colPar)

        // 6 identical copies (like BlockB C1..C6) produce zero column parity
        val blockB = ByteArray(0x1efc)
        for (slot in 0 until 6) {
            val base = 0x100 + slot * 0x400
            blockB[base] = 0x5a
            blockB[base + 3] = 0xa5.toByte()
        }
        val (_, colParB) = FlashDictionary.computeParity2d(blockB)
        assertContentEquals(byteArrayOf(0, 0, 0, 0), colParB)
    }

    @Test
    fun rejectsDriftedFactoryTemplate() {
        val catalog = CatalogCompiler.compile(root)
        @Suppress("UNCHECKED_CAST")
        val flash = catalog.data["flash"] as Map<String, Any?>
        val tmp = Files.createTempDirectory("fuji-flash-drift-")
        try {
            val target = tmp.resolve("tools/release/factory-flash-template.yaml")
            Files.createDirectories(target.parent)
            val original = Files.readString(root.resolve("tools/release/factory-flash-template.yaml"))
            assertTrue(original.contains("hex: \"f501\""))
            Files.writeString(target, original.replace("hex: \"f501\"", "hex: \"ffff\""))
            assertFailsWith<IllegalArgumentException> {
                FlashDictionary.verifyReleaseTemplate(flash, tmp)
            }
        } finally {
            Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
