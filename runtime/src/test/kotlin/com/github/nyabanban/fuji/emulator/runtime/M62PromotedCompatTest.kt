package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.PortBinding
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class M62PromotedCompatTest {
    private val catalog = AddressCatalog.bundled()
    private var currentPc = 0x5af98L
    private var currentLr = 0x02394e9cL
    private val context = object : DiscoveryContext {
        override fun core() = 1
        override fun tick() = 100L
        override fun pc() = currentPc
        override fun lr() = currentLr
    }

    private fun promotedBindings(
        includeIspCmd: Boolean = true,
        includeIspSetup: Boolean = true,
        includeDmaCh: Boolean = true,
    ): List<PortBinding> = catalog.windows.filter { window ->
        !window.optional ||
            (includeIspCmd && window.componentId == "soc-isp-cmd") ||
            (includeIspSetup && window.componentId == "soc-isp-setup") ||
            (includeDmaCh && window.portId.startsWith("soc-dma.ch"))
    }.map { PortBinding(it.componentId, it.portId, it.base, it.size) }

    private fun legacyConfiguredRuntime(): CatalogRuntime = CatalogRuntime(
        catalog,
        "discovery",
        Path.of("."),
        mapOf(
            "discovery" to mapOf(
                "imageReply820001" to "zero-once-v1",
                "imageReply820040" to "zero-alternating-16-pairs-v1",
                ProgrammedImageReply.KEY to ProgrammedImageReply.PROFILE,
                "imageConfigPairs" to "retained-words-zero-v1",
                "channel4Programming" to "control-readback-after-write-v1",
                "channel7Programming" to "retained-words-zero-v1",
                "channel11Programming" to "control-readback-after-write-v1",
                "inputs" to mapOf(
                    "gfx100s.fff7801c.poll.candidate" to mapOf("strategy" to DiscoveryStrategy.SETUP_REPEAT_7801C.id),
                    "gfx100s.fff4f008.setup.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    "gfx100s.fff4f00c.runtime-control.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    "gfx100s.fff4f010.setup-tail.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    "gfx100s.fff4f018.mode.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    "gfx100s.fff4f020.mode-setup.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    "gfx100s.fff78008.setup.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    "gfx100s.fff7800c.control.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    "gfx100s.fff78010.setup-tail.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    "gfx100s.fff78018.mode.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    "gfx100s.fff78020.mode-setup.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    RETAINED_6C000_REGION to mapOf(
                        "strategy" to "retained-bytes",
                        "fill" to 0xa5,
                        "completion" to C000StopReply.PROFILE,
                    ),
                    RETAINED_6C204_REGION to mapOf(
                        "strategy" to "retained-bytes",
                        "fill" to 0,
                        "completion" to C204_COMPLETION,
                    ),
                    RETAINED_6C20C_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0),
                ),
            ),
        ),
    )

    @Test
    fun legacyBridgeWorksWhenOptionalPortsInactiveAndRejectsConflictsWhenActive() {
        val legacyBus = checkNotNull(legacyConfiguredRuntime().createBus(context))
        legacyBus.access(1, 0xfff4f008L, 4, true, 0x10252bL)
        legacyBus.access(2, 0xfff4f00cL, 4, true, 0x7ffL)
        assertEquals(0x7ffL, legacyBus.access(3, 0xfff4f00cL, 4, false))
        legacyBus.access(4, 0xfff7801cL, 4, true, 1L)
        assertEquals(0L, legacyBus.access(5, 0xfff7801cL, 4, false))
        currentPc = 0x5af98L; currentLr = 0x11b34f8L
        legacyBus.access(6, 0xfff6c000L, 4, true, 0xa5a5a5a4L)
        currentPc = 0x5afe0L; currentLr = 0x11b3508L
        assertEquals(0xa5a5a5a4L, legacyBus.access(7, 0xfff6c000L, 4, false))
        assertEquals(0xa5a5a5a5L, legacyBus.access(8, 0xfff6c000L, 4, false))
        currentPc = 0x5af98L; currentLr = 0x02394e9cL
        legacyBus.access(9, 0xfff6c280L, 4, true, 1L)
        assertEquals(1L, legacyBus.access(10, 0xfff6c280L, 4, false))
        legacyBus.access(11, 0xfff3c020L, 4, true, 0x15000L)
        assertEquals(0x15000L, legacyBus.access(12, 0xfff3c020L, 4, false))
        val savedLegacy = legacyBus.saveSnapshot()

        // Active soc-isp-cmd / soc-isp-setup / soc-dma.ch* reject conflicting legacy settings
        for (bindings in listOf(
            promotedBindings(includeIspCmd = true, includeIspSetup = false, includeDmaCh = false),
            promotedBindings(includeIspCmd = false, includeIspSetup = true, includeDmaCh = false),
            promotedBindings(includeIspCmd = false, includeIspSetup = false, includeDmaCh = true),
        )) {
            val conflicting = legacyConfiguredRuntime().apply {
                bindPorts(bindings, catalog.defaultMemoryProfile, catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile))
            }
            assertFailsWith<IllegalArgumentException> { conflicting.createBus(context) }
        }

        // Clean promoted runtime restores legacy snapshot ONLY with allowPromotedRegions = true
        val promotedRuntime = CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap()).apply {
            bindPorts(promotedBindings(), catalog.defaultMemoryProfile, catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile))
        }
        val busDisallowed = checkNotNull(promotedRuntime.createBus(context))
        assertFailsWith<IllegalArgumentException> {
            busDisallowed.restoreSnapshot(savedLegacy, allowPromotedRegions = false)
        }
        val busAllowed = checkNotNull(promotedRuntime.createBus(context))
        busAllowed.restoreSnapshot(savedLegacy, allowPromotedRegions = true)

        // Dynamic/retired M6.2 regions with active state CANNOT be silently dropped when neither component nor DiscoveryBus bridges them
        val unbridgedRuntime = CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap()).apply {
            bindPorts(promotedBindings(includeIspCmd = false, includeIspSetup = true, includeDmaCh = true), catalog.defaultMemoryProfile, catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile))
        }
        val unbridgedBus = checkNotNull(unbridgedRuntime.createBus(context))
        assertFails {
            unbridgedBus.restoreSnapshot(savedLegacy, allowPromotedRegions = true)
        }
    }

    @Test
    fun bridgedInactiveM62PortsSurfacePermittedInDiscoveryQueryAndObservation() {
        val runtime = legacyConfiguredRuntime().apply {
            bindPorts(emptyList(), catalog.defaultMemoryProfile, catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile))
        }
        val writeQ = runtime.query(mapOf("space" to "cpu-physical", "address" to 0xfff4f008L.toString(), "width" to "4", "direction" to "write"))
        assertEquals(true, writeQ["permitted"])
        for (addr in listOf(0xfff4f00cL, 0xfff7801cL, 0xfff6c000L, 0xfff6c204L, 0xfff6c224L, 0xfff6c280L, 0xfff3c020L)) {
            val q = runtime.query(mapOf("space" to "cpu-physical", "address" to addr.toString(), "width" to "4", "direction" to "read"))
            assertEquals(true, q["permitted"], "Expected permitted=true for bridged address 0x${addr.toString(16)}")
        }
        val writeReply = runtime.query(mapOf("space" to "cpu-physical", "address" to 0xfff6c224L.toString(), "width" to "4", "direction" to "write"))
        assertEquals(false, writeReply["permitted"])

        // Unconfigured inactive M6.2 ports stay refused
        val bare = CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap()).apply {
            bindPorts(emptyList(), catalog.defaultMemoryProfile, catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile))
        }
        assertEquals(false, bare.query(mapOf("space" to "cpu-physical", "address" to 0xfff4f008L.toString(), "width" to "4", "direction" to "write"))["permitted"])
        for (addr in listOf(0xfff4f00cL, 0xfff7801cL, 0xfff6c000L, 0xfff6c224L, 0xfff3c020L)) {
            val q = bare.query(mapOf("space" to "cpu-physical", "address" to addr.toString(), "width" to "4", "direction" to "read"))
            assertEquals(false, q["permitted"], "Expected permitted=false for unconfigured address 0x${addr.toString(16)}")
        }
    }
}
