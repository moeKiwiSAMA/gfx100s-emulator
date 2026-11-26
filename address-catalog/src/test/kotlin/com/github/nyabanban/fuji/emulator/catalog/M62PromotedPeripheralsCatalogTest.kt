package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class M62PromotedPeripheralsCatalogTest {
    private val root = Path.of(System.getProperty("catalogRoot"))
    private fun catalog() = CatalogCompiler.compile(root)

    private val retiredCandidateIds = setOf(
        "gfx100s.fff7801c.poll.candidate",
        "gfx100s.fff4f008.setup.candidate",
        "gfx100s.fff4f00c.runtime-control.candidate",
        "gfx100s.fff4f010.setup-tail.candidate",
        "gfx100s.fff4f018.mode.candidate",
        "gfx100s.fff4f020.mode-setup.candidate",
        "gfx100s.fff78008.setup.candidate",
        "gfx100s.fff7800c.control.candidate",
        "gfx100s.fff78010.setup-tail.candidate",
        "gfx100s.fff78018.mode.candidate",
        "gfx100s.fff78020.mode-setup.candidate",
        "gfx100s.fff6c000.words.candidate",
        "gfx100s.fff6c204.words.candidate",
        "gfx100s.fff6c20c.config-set.candidate",
    )

    @Test
    fun retiredCandidatesAreAbsentAndPromotedAs23OptionalWindows() {
        val c = catalog()
        assertTrue(c.candidates.none { it.id in retiredCandidateIds })
        assertEquals(4, c.candidates.size)

        val expectedWindows = mapOf(
            "gfx100s.soc-isp-cmd.c000-words" to (0xfff6c000L to 516L),
            "gfx100s.soc-isp-cmd.c204-control" to (0xfff6c204L to 8L),
            "gfx100s.soc-isp-cmd.c20c-config" to (0xfff6c20cL to 20L),
            "gfx100s.soc-isp-cmd.reply" to (0xfff6c224L to 32L),
            "gfx100s.soc-isp-cmd.config-bank-0" to (0xfff6c280L to 28L),
            "gfx100s.soc-isp-cmd.config-bank-1" to (0xfff6c2c0L to 28L),
            "gfx100s.soc-isp-cmd.config-bank-2" to (0xfff6c300L to 28L),
            "gfx100s.soc-isp-cmd.config-bank-3" to (0xfff6c340L to 28L),
            "gfx100s.soc-isp-cmd.config-bank-4" to (0xfff6c380L to 28L),
            "gfx100s.soc-isp-cmd.config-bank-5" to (0xfff6c3c0L to 28L),
            "gfx100s.soc-isp-cmd.config-bank-6" to (0xfff6c400L to 44L),
            "gfx100s.soc-isp-setup.ctrl0-low" to (0xfff4f008L to 20L),
            "gfx100s.soc-isp-setup.ctrl0-high" to (0xfff4f020L to 12L),
            "gfx100s.soc-isp-setup.ctrl1-low" to (0xfff78008L to 20L),
            "gfx100s.soc-isp-setup.ctrl1-poll" to (0xfff7801cL to 4L),
            "gfx100s.soc-isp-setup.ctrl1-high" to (0xfff78020L to 12L),
            "gfx100s.soc-dma.ch7-enable" to (0xfff3c000L to 4L),
            "gfx100s.soc-dma.ch4-control" to (0xfff3c020L to 4L),
            "gfx100s.soc-dma.ch7-control" to (0xfff3c02cL to 4L),
            "gfx100s.soc-dma.ch11-control" to (0xfff3c03cL to 4L),
            "gfx100s.soc-dma.ch4-params" to (0xfff3c140L to 16L),
            "gfx100s.soc-dma.ch7-params" to (0xfff3c170L to 16L),
            "gfx100s.soc-dma.ch11-params" to (0xfff3c1b0L to 16L),
        )
        for ((windowId, span) in expectedWindows) {
            val w = c.windows.single { it.id == windowId }
            assertEquals(span.first, w.base, windowId)
            assertEquals(span.second, w.size, windowId)
            assertEquals("MMIO", w.kind, windowId)
            assertTrue(w.optional, windowId)
        }
    }

    @Test
    fun promotedDevicesPreserveHonestProvenanceAndF01cRemainsUnmapped() {
        val c = catalog()
        val forbiddenText = "\"VERIFIED\""
        for (componentId in listOf("soc-isp-cmd", "soc-isp-setup")) {
            val device = rows(c.data["devices"]).single { it["componentId"] == componentId }
            assertFalse(CatalogIO.canonical(device).toString(Charsets.UTF_8).contains(forbiddenText))
            assertEquals("UNKNOWN", device["physicalIdentity"])
        }
        val dmaDevice = rows(c.data["devices"]).single { it["componentId"] == "soc-dma" }
        assertFalse(CatalogIO.canonical(dmaDevice).toString(Charsets.UTF_8).contains(forbiddenText))

        // INV-SYS-F01C-001 (0xfff4f01c..0xfff4f020) MUST remain unmapped & disjoint from soc-isp-setup
        val f01cQuery = c.query("cpu-physical", 0xfff4f01cL, 4, "read")
        assertNull(f01cQuery.window)
        assertTrue(c.diagnosticProfiles.any { it.base <= 0xfff4f01cL && 0xfff4f020L <= it.endExclusive })
        assertTrue(c.windows.none { it.interval.overlaps(Interval(0xfff4f01cL, 0xfff4f020L)) })

        // Verify soc-isp-cmd holes/RO/WO and soc-isp-setup offset+4 high gaps
        assertEquals("KNOWN_DEVICE_UNKNOWN_REGISTER", c.query("cpu-physical", 0xfff6c0a0L, 4, "read").classification)
        assertTrue(c.query("cpu-physical", 0xfff6c028L, 4, "read").permitted)
        assertFalse(c.query("cpu-physical", 0xfff6c028L, 4, "write").permitted)
        assertFalse(c.query("cpu-physical", 0xfff6c144L, 4, "read").permitted)
        assertTrue(c.query("cpu-physical", 0xfff6c144L, 4, "write").permitted)

        assertEquals("KNOWN_DEVICE_UNKNOWN_REGISTER", c.query("cpu-physical", 0xfff4f024L, 4, "write").classification)
        assertEquals("KNOWN_DEVICE_UNKNOWN_REGISTER", c.query("cpu-physical", 0xfff78024L, 4, "write").classification)
        assertFalse(c.query("cpu-physical", 0xfff4f020L, 4, "read").permitted)
        assertTrue(c.query("cpu-physical", 0xfff4f020L, 4, "write").permitted)
    }

    @Test
    fun compiledArtifactsOnDiskMatchCatalogCompilerVerify() {
        CatalogCompiler.verify(root)
    }
}
