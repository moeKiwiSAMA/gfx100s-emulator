package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class M63PromotedPeripheralsCatalogTest {
    private val root = Path.of(System.getProperty("catalogRoot"))
    private fun catalog() = CatalogCompiler.compile(root)

    private val retiredCandidateIds = setOf(
        "gfx100s.fff69000.config.candidate",
        "gfx100s.fff69100.config-set.candidate",
        "gfx100s.fff6b000.config.candidate",
        "gfx100s.fff11060.result-surface.candidate",
        "gfx100s.fff72028.config-words.candidate",
    )

    private val remainingSdCandidateIds = setOf(
        "gfx100s.sd.slot0.a.candidate",
        "gfx100s.sd.slot0.phy.candidate",
        "gfx100s.sd.slot1.a.candidate",
        "gfx100s.sd.slot1.phy.candidate",
    )

    @Test
    fun retiredM63CandidatesAreAbsentAndOnly4SdCandidatesRemain() {
        val c = catalog()
        assertTrue(c.candidates.none { it.id in retiredCandidateIds })
        assertEquals(remainingSdCandidateIds, c.candidates.map { it.id }.toSet())
        assertEquals(4, c.candidates.size)

        val expectedWindows = mapOf(
            "gfx100s.soc-isp.core" to (0xfff70000L to 0x2ecL),
            "gfx100s.soc-isp.bank-50000" to (0xfff50000L to 0x1008L),
            "gfx100s.soc-isp.lut-51000" to (0xfff51008L to 0x3ff8L),
            "gfx100s.soc-isp.lut-55000" to (0xfff55000L to 0x4000L),
            "gfx100s.soc-isp.lut-59000" to (0xfff59000L to 0x2c00L),
            "gfx100s.soc-isp.bank-68000" to (0xfff68000L to 0xd04L),
            "gfx100s.soc-isp.config-69000" to (0xfff69000L to 0x2cL),
            "gfx100s.soc-isp.config-69100" to (0xfff69100L to 0x154L),
            "gfx100s.soc-isp.config-6b000" to (0xfff6b000L to 0xf8L),
            "gfx100s.soc-isp.lut-6d000" to (0xfff6d000L to 0x1000L),
            "gfx100s.soc-isp.bank-6e000" to (0xfff6e000L to 0x74L),
            "gfx100s.soc-isp.result-surface" to (0xfff11060L to 0x1980L),
            "gfx100s.soc-isp.stats-0" to (0xfff16000L to 0x1000L),
            "gfx100s.soc-isp.stats-1" to (0xfff1d000L to 0x1000L),
            "gfx100s.soc-isp.live-view-0" to (0xfff72000L to 0x130L),
            "gfx100s.soc-isp.live-view-1" to (0xfff73000L to 0x130L),
            "gfx100s.soc-isp.live-view-2" to (0xfff74000L to 0x30L),
        )
        for ((windowId, span) in expectedWindows) {
            val w = c.windows.single { it.id == windowId }
            assertEquals(span.first, w.base, windowId)
            assertEquals(span.second, w.size, windowId)
            assertEquals("MMIO", w.kind, windowId)
            assertTrue(w.optional, windowId)
        }
        assertEquals(212, c.windows.size)
        assertTrue("optical-filter" in c.componentIds)
        assertEquals(49, c.componentIds.size)
    }

    @Test
    fun socIspPreservesHonestProvenanceHolesAndDirectionalRules() {
        val c = catalog()
        val device = rows(c.data["devices"]).single { it["componentId"] == "soc-isp" }
        val json = CatalogIO.canonical(device).toString(Charsets.UTF_8)
        assertFalse(json.contains("\"VERIFIED\""))
        assertEquals("UNKNOWN", device["physicalIdentity"])

        // Real-silicon filter/PDAF registers, holes, and directional checks in core (0xfff70000), bank-6e000 (0xfff6e000), result surface (0xfff11060), and live-view (0xfff72000)
        assertEquals("REGISTER", c.query("cpu-physical", 0xfff70108L, 4, "read").classification)
        assertTrue(c.query("cpu-physical", 0xfff70108L, 4, "write").permitted)
        assertEquals("REGISTER", c.query("cpu-physical", 0xfff702d4L, 4, "read").classification)
        assertTrue(c.query("cpu-physical", 0xfff702d4L, 4, "write").permitted)
        assertEquals("KNOWN_DEVICE_UNKNOWN_REGISTER", c.query("cpu-physical", 0xfff6e028L, 4, "read").classification)
        assertTrue(c.query("cpu-physical", 0xfff6e070L, 4, "write").permitted)
        assertTrue(c.query("cpu-physical", 0xfff702e8L, 4, "read").permitted)
        assertFalse(c.query("cpu-physical", 0xfff702e8L, 4, "write").permitted)
        assertTrue(c.query("cpu-physical", 0xfff702e4L, 4, "write").permitted)
        assertTrue(c.query("cpu-physical", 0xfff11060L, 4, "read").permitted)
        assertFalse(c.query("cpu-physical", 0xfff11060L, 4, "write").permitted)
        assertEquals("KNOWN_DEVICE_UNKNOWN_REGISTER", c.query("cpu-physical", 0xfff121a0L, 4, "read").classification)
        assertFalse(c.query("cpu-physical", 0xfff72028L, 4, "read").permitted)
        assertTrue(c.query("cpu-physical", 0xfff72028L, 4, "write").permitted)
        assertEquals("UNASSIGNED", c.query("cpu-physical", 0xfff72130L, 4, "read").classification)
    }

    @Test
    fun compiledArtifactsOnDiskMatchCatalogCompilerVerify() {
        CatalogCompiler.verify(root)
    }
}
