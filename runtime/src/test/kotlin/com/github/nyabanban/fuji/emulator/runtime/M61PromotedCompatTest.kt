package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.PortBinding
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class M61PromotedCompatTest {
    private val catalog = AddressCatalog.bundled()
    private val context = object : DiscoveryContext {
        override fun core() = 1
        override fun tick() = 100L
        override fun pc() = 0x5af98L
        override fun lr() = 0x02394e9cL
    }

    private fun promotedBindings(): List<PortBinding> =
        catalog.windows.filter { !it.optional || it.componentId in setOf("soc-hdmi", "soc-panel", "soc-uart") ||
            it.portId in setOf("soc-system-control.status-a200", "soc-system-control.fff49530-pair", "soc-system-control.fff49500-tail") }
            .map { PortBinding(it.componentId, it.portId, it.base, it.size) }

    private fun legacyConfiguredRuntime(): CatalogRuntime = CatalogRuntime(
        catalog,
        "discovery",
        Path.of("."),
        mapOf(
            "discovery" to mapOf(
                "hdmiStatusPairs" to listOf(listOf(1L, 2L)),
                "hdmiLinkStatusReads" to listOf(0L, 0x18L),
                "inputs" to mapOf(
                    "gfx100s.system-control.status-a200.candidate" to mapOf("strategy" to "fixed-input", "value" to 1),
                    HdmiStatusSamples.REGION to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    PanelInitOnce.REGION to mapOf("strategy" to "panel-init-once-v1"),
                    WRITE_49530_REGION to mapOf("strategy" to DiscoveryStrategy.WRITE_49530.id),
                    TAIL_49500_REGION to mapOf("strategy" to DiscoveryStrategy.TAIL_49500.id),
                ),
            ),
        ),
    )

    @Test
    fun activePromotedPortsRejectLegacyDiscoveryInputsAndAllowPromotedSnapshotRestore() {
        val legacyBus = checkNotNull(legacyConfiguredRuntime().createBus(context))
        legacyBus.access(1, 0xfff49530L, 4, true, 0x10000704L)
        legacyBus.access(2, 0xfff7b090L, 4, false)
        val savedLegacy = legacyBus.saveSnapshot()

        // Active promoted ports reject conflicting legacy discovery.inputs
        val conflicting = legacyConfiguredRuntime().apply {
            bindPorts(promotedBindings(), catalog.defaultMemoryProfile, catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile),
                mapOf("soc-hdmi" to "hdmi-init-retained-experimental-v1", "soc-panel" to "panel-init-once-v1", "soc-uart" to "retained-uart-experimental-v1"))
        }
        assertFailsWith<IllegalArgumentException> { conflicting.createBus(context) }

        // Clean runtime with promoted optional ports active restores legacy snapshot ONLY with allowPromotedRegions = true
        val promotedRuntime = CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap()).apply {
            bindPorts(promotedBindings(), catalog.defaultMemoryProfile, catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile),
                mapOf("soc-hdmi" to "hdmi-init-retained-experimental-v1", "soc-panel" to "panel-init-once-v1", "soc-uart" to "retained-uart-experimental-v1"))
        }
        val promotedBus1 = checkNotNull(promotedRuntime.createBus(context))
        assertFails { promotedBus1.restoreSnapshot(savedLegacy, allowPromotedRegions = false) }
        val promotedBus2 = checkNotNull(promotedRuntime.createBus(context))
        promotedBus2.restoreSnapshot(savedLegacy, allowPromotedRegions = true)

        // Unconfigured inactive optional windows remain refused as KNOWN
        val unconfiguredRuntime = CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap())
        val unconfiguredBus = checkNotNull(unconfiguredRuntime.createBus(context))
        for (addr in listOf(0xfff4a200L, 0xfff7b020L, 0xfffab004L, 0xfff3a030L, 0xfff49530L, 0xfff49500L)) {
            assertFails { unconfiguredBus.access(1, addr, 4, false) }
        }
    }

    @Test
    fun legacySnapshotWithUntouchedRefusePromotedUartRestoresWithoutAllowPromotedRegions() {
        // Simulates snap2300k: legacy inputs configure #5/#6/#7/#18/#29 while savedRegions also carries untouched refuse UART rows
        val bus = checkNotNull(legacyConfiguredRuntime().createBus(context))
        val baseSnap = bus.saveSnapshot()
        @Suppress("UNCHECKED_CAST")
        val regions = (baseSnap["regions"] as Map<String, Any?>).toMutableMap()
        val refuseUartRow = mapOf(
            "base" to 0xfff3a000L,
            "endExclusive" to 0xfff3a030L,
            "strategy" to "refuse",
            "position" to 0,
            "retainedWritten" to false,
            "retainedValue" to 0L,
            "accessAttempts" to 0L,
            "fixedSelected" to false,
            "selectedValue" to 0L,
            "mutatedBytesBase64" to null,
            "scriptTailSelection" to null,
        )
        regions["gfx100s.fff3a000.uart-bank.candidate"] = refuseUartRow
        val snapWithUntouchedUart = baseSnap + ("regions" to regions)

        checkNotNull(legacyConfiguredRuntime().createBus(context)).restoreSnapshot(snapWithUntouchedUart, allowPromotedRegions = false)

        // Touched refuse row (accessAttempts > 0) MUST still fail when allowPromotedRegions = false
        val touchedSnap = baseSnap + ("regions" to (regions + ("gfx100s.fff3a000.uart-bank.candidate" to (refuseUartRow + ("accessAttempts" to 1L)))))
        assertFails { checkNotNull(legacyConfiguredRuntime().createBus(context)).restoreSnapshot(touchedSnap, allowPromotedRegions = false) }
    }

    @Test
    fun allowPromotedRegionsRejectsActiveLegacyStateWhenNeitherComponentNorDiscoveryBridgesIt() {
        val legacyBus = checkNotNull(legacyConfiguredRuntime().createBus(context))
        legacyBus.access(1L, 0xfffab004L, 4, false) // advances panel position to 1
        val savedLegacy = legacyBus.saveSnapshot()

        // Unconfigured runtime has neither soc-panel active nor panel in discovery.inputs
        val unconfiguredBus = checkNotNull(CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap()).createBus(context))
        val error = assertFailsWith<IllegalArgumentException> {
            unconfiguredBus.restoreSnapshot(savedLegacy, allowPromotedRegions = true)
        }
        assertTrue(
            error.message.orEmpty().contains("has active discovery state") ||
                error.message.orEmpty().contains("HDMI status requires") ||
                error.message.orEmpty().contains("Snapshot requires selected"),
            "Unexpected rejection message: ${error.message}",
        )
    }
}
