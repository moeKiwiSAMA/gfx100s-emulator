package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class M61PromotedPeripheralsCatalogTest {
    private val root = Path.of(System.getProperty("catalogRoot"))
    private fun catalog() = CatalogCompiler.compile(root)

    private val promotedCandidateIds = setOf(
        "gfx100s.system-control.status-a200.candidate",
        "gfx100s.hdmi.init-words.candidate",
        "gfx100s.panel.init-words.candidate",
        "gfx100s.fff49530.write-pair.candidate",
        "gfx100s.fff3a000.uart-bank.candidate",
        "gfx100s.fff3a030.uart-config.candidate",
        "gfx100s.fff3a034.uart-tail.candidate",
        "gfx100s.fff49500.write-tail.candidate",
    )

    @Test
    fun promotedCandidatesAreAbsentFromCandidatesAndPresentAsOptionalWindows() {
        val c = catalog()
        assertTrue(c.candidates.none { it.id in promotedCandidateIds })

        val expectedWindows = mapOf(
            "gfx100s.soc-hdmi" to (0xfff7b000L to 2704L),
            "gfx100s.soc-panel" to (0xfffab000L to 24L),
            "gfx100s.soc-uart.uart-bank" to (0xfff3a000L to 48L),
            "gfx100s.soc-uart.uart-config" to (0xfff3a030L to 4L),
            "gfx100s.soc-uart.uart-tail" to (0xfff3a034L to 8L),
            "gfx100s.soc-system-control.status-a200" to (0xfff4a200L to 4L),
            "gfx100s.soc-system-control.fff49530-pair" to (0xfff49530L to 8L),
            "gfx100s.soc-system-control.fff49500-tail" to (0xfff49500L to 4L),
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
    fun promotedDevicesPreserveHonestProvenanceAndNeverClaimVerified() {
        val c = catalog()
        val forbiddenText = "\"VERIFIED\""
        for (componentId in listOf("soc-hdmi", "soc-panel", "soc-uart")) {
            val device = rows(c.data["devices"]).single { it["componentId"] == componentId }
            assertFalse(CatalogIO.canonical(device).toString(Charsets.UTF_8).contains(forbiddenText))
            assertEquals("UNKNOWN", device["physicalIdentity"])
        }
        val sysCtrl = rows(c.data["devices"]).single { it["componentId"] == "soc-system-control" }
        val promotedPortIds = setOf(
            "definition.soc-system-control.status-a200",
            "definition.soc-system-control.fff49530-pair",
            "definition.soc-system-control.fff49500-tail",
        )
        for (port in rows(sysCtrl["ports"]).filter { it["id"] in promotedPortIds }) {
            assertFalse(CatalogIO.canonical(port).toString(Charsets.UTF_8).contains(forbiddenText))
        }
    }

    @Test
    fun promotedWindowsEnforceProhibitedHolesAndDirectionalWidths() {
        val c = catalog()
        // soc-hdmi: 11 prohibited holes, link-status RO at +0x08, write-only runs, RW runs
        for (hole in listOf(0xfff7b000L, 0xfff7b00cL, 0xfff7b01cL, 0xfff7b030L, 0xfff7b0a0L, 0xfff7b134L, 0xfff7b1c0L, 0xfff7b1fcL, 0xfff7b25cL, 0xfff7b300L, 0xfff7b3fcL)) {
            val q = c.query("cpu-physical", hole, 4, "read")
            assertEquals("KNOWN_DEVICE_UNKNOWN_REGISTER", q.classification)
            assertFalse(q.permitted)
        }
        assertTrue(c.query("cpu-physical", 0xfff7b008L, 4, "read").permitted)
        assertFalse(c.query("cpu-physical", 0xfff7b008L, 4, "write").permitted)
        assertTrue(c.query("cpu-physical", 0xfff7b020L, 4, "read").permitted)
        assertTrue(c.query("cpu-physical", 0xfff7b020L, 4, "write").permitted)
        assertFalse(c.query("cpu-physical", 0xfff7ba8cL, 4, "read").permitted)
        assertTrue(c.query("cpu-physical", 0xfff7ba8cL, 4, "write").permitted)

        // soc-panel: +0/+4 RW, +8..+14 WO
        assertTrue(c.query("cpu-physical", 0xfffab000L, 4, "read").permitted)
        assertTrue(c.query("cpu-physical", 0xfffab004L, 4, "write").permitted)
        assertFalse(c.query("cpu-physical", 0xfffab008L, 4, "read").permitted)
        assertTrue(c.query("cpu-physical", 0xfffab014L, 4, "write").permitted)

        // soc-uart: exact RW/RO/WO and holes 0x04..0x17, 0x1c..0x23
        assertFalse(c.query("cpu-physical", 0xfff3a000L, 4, "read").permitted)
        assertTrue(c.query("cpu-physical", 0xfff3a000L, 4, "write").permitted)
        assertTrue(c.query("cpu-physical", 0xfff3a018L, 4, "read").permitted)
        assertFalse(c.query("cpu-physical", 0xfff3a018L, 4, "write").permitted)
        assertEquals("KNOWN_DEVICE_UNKNOWN_REGISTER", c.query("cpu-physical", 0xfff3a004L, 4, "read").classification)
        assertEquals("KNOWN_DEVICE_UNKNOWN_REGISTER", c.query("cpu-physical", 0xfff3a01cL, 4, "write").classification)
        assertTrue(c.query("cpu-physical", 0xfff3a02cL, 4, "read").permitted)
        assertTrue(c.query("cpu-physical", 0xfff3a030L, 4, "write").permitted)
        assertTrue(c.query("cpu-physical", 0xfff3a038L, 4, "read").permitted)

        // soc-system-control: fff49530-pair & fff49500-tail WO
        assertFalse(c.query("cpu-physical", 0xfff49530L, 4, "read").permitted)
        assertTrue(c.query("cpu-physical", 0xfff49534L, 4, "write").permitted)
        assertFalse(c.query("cpu-physical", 0xfff49500L, 4, "read").permitted)
        assertTrue(c.query("cpu-physical", 0xfff49500L, 4, "write").permitted)
    }
}
