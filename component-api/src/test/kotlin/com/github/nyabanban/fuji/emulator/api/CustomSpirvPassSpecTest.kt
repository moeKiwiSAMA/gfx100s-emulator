package com.github.nyabanban.fuji.emulator.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CustomSpirvPassSpecTest {
    private fun minimalSpvHeader(): ByteArray = byteArrayOf(
        0x03, 0x02, 0x23, 0x07, // SPIR-V magic 0x07230203 LE
        0x00, 0x00, 0x01, 0x00, // version 1.0
        0x00, 0x00, 0x00, 0x00, // generator
        0x02, 0x00, 0x00, 0x00, // bound
        0x00, 0x00, 0x00, 0x00, // schema
    )

    @Test
    fun `validates bounds header helper equality and effectiveCacheKey`() {
        val spv = minimalSpvHeader()
        val ubo = byteArrayOf(1, 2, 3, 4)
        val push = byteArrayOf(9, 8, 7, 6)
        val specA = CustomSpirvPassSpec(
            shaderId = "my-filter",
            spirvBytes = spv,
            entryPoint = "main",
            workgroupSizeX = 8,
            workgroupSizeY = 8,
            uniformData = ubo,
            pushConstants = push,
            stage = CustomSpirvStage.PRE_OPTICS_FILTER,
        )
        val specB = CustomSpirvPassSpec(
            shaderId = "my-filter",
            spirvBytes = spv.copyOf(),
            entryPoint = "main",
            workgroupSizeX = 8,
            workgroupSizeY = 8,
            uniformData = ubo.copyOf(),
            pushConstants = push.copyOf(),
            stage = CustomSpirvStage.PRE_OPTICS_FILTER,
        )
        assertTrue(specA.hasValidSpirvHeader())
        assertEquals(specA, specB)
        assertEquals(specA.hashCode(), specB.hashCode())
        assertEquals(specA.effectiveCacheKey(), specB.effectiveCacheKey())
        assertNotEquals(0L, specA.effectiveCacheKey())

        val badMagic = CustomSpirvPassSpec(
            shaderId = "bad-magic",
            spirvBytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
        )
        assertFalse(badMagic.hasValidSpirvHeader())

        assertThrows<IllegalArgumentException> {
            CustomSpirvPassSpec(shaderId = "  ", spirvBytes = spv)
        }
        assertThrows<IllegalArgumentException> {
            CustomSpirvPassSpec(shaderId = "x", spirvBytes = ByteArray(0))
        }
        assertThrows<IllegalArgumentException> {
            CustomSpirvPassSpec(shaderId = "x", spirvBytes = ByteArray(CustomSpirvPassSpec.MAX_SPIRV_BYTES + 4))
        }
        assertThrows<IllegalArgumentException> {
            CustomSpirvPassSpec(shaderId = "x", spirvBytes = spv, uniformData = ByteArray(CustomSpirvPassSpec.MAX_UNIFORM_BYTES + 1))
        }
        assertThrows<IllegalArgumentException> {
            CustomSpirvPassSpec(shaderId = "x", spirvBytes = spv, pushConstants = ByteArray(CustomSpirvPassSpec.MAX_PUSH_CONSTANT_BYTES + 1))
        }
        assertThrows<IllegalArgumentException> {
            CustomSpirvPassSpec(shaderId = "x", spirvBytes = spv, workgroupSizeX = 0)
        }
        assertThrows<IllegalArgumentException> {
            CustomSpirvPassSpec(shaderId = "x", spirvBytes = spv, workgroupSizeY = 65)
        }
    }

    @Test
    fun `fromResource loads embedded spv bytes from custom ClassLoader`() {
        val spv = minimalSpvHeader()
        val loader = object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): InputStream? =
                if (name == "shaders/test_plugin.comp.spv") ByteArrayInputStream(spv) else null
        }
        val spec = CustomSpirvPassSpec.fromResource(
            classLoader = loader,
            resourcePath = "/shaders/test_plugin.comp.spv",
            stage = CustomSpirvStage.POST_OPTICS_RGB,
            workgroupSizeX = 16,
            workgroupSizeY = 16,
        )
        assertEquals("shaders/test_plugin.comp.spv", spec.shaderId)
        assertEquals(CustomSpirvStage.POST_OPTICS_RGB, spec.stage)
        assertContentEquals(spv, spec.spirvBytes)
        assertTrue(spec.hasValidSpirvHeader())

        assertThrows<IllegalArgumentException> {
            CustomSpirvPassSpec.fromResource(loader, "shaders/missing.spv")
        }
    }
}
