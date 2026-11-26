package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.PortBinding
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class M63PromotedCompatTest {
    private val catalog = AddressCatalog.bundled()
    private var currentPc = 0x5af98L
    private var currentLr = 0x11b811cL
    private val context = object : DiscoveryContext {
        override fun core() = 1
        override fun tick() = 100L
        override fun pc() = currentPc
        override fun lr() = currentLr
    }

    private fun promotedBindings(includeSocIsp: Boolean = true): List<PortBinding> = catalog.windows.filter { window ->
        !window.optional || (includeSocIsp && window.componentId == "soc-isp")
    }.map { PortBinding(it.componentId, it.portId, it.base, it.size) }

    private fun legacyConfiguredRuntime(): CatalogRuntime = CatalogRuntime(
        catalog,
        "discovery",
        Path.of("."),
        mapOf(
            "discovery" to mapOf(
                "image50000" to "retained-words-502b0-rmw-v1",
                "image50000Control" to DiscoveryStrategy.IMAGE_50000_CONTROL_REPEATABLE.id,
                "image51000" to DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE.id,
                "image59000" to "retained-tables-v1",
                "image68000" to DiscoveryStrategy.IMAGE_68000_REPEATABLE.id,
                "image6e000" to Image6e000.REPEATABLE_PROFILE,
                "image70000" to DiscoveryStrategy.IMAGE_70000_DRIVER.id,
                ImageIrq106Status.KEY to ImageIrq106Status.PROFILE,
                ImageLiveViewBanks.KEY to ImageLiveViewBanks.PROFILE,
                "inputs" to mapOf(
                    RETAINED_69000_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    RETAINED_69100_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    RETAINED_6B000_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    PromotedLegacyCandidates.LIVE_VIEW_CONFIG_WORDS_REGION to mapOf("strategy" to "refuse"),
                ),
            ),
        ),
    )

    private fun CatalogRuntime.bindPromoted(includeSocIsp: Boolean) {
        bindPorts(
            promotedBindings(includeSocIsp),
            catalog.defaultMemoryProfile,
            catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile),
        )
    }

    @Test
    fun legacyBridgeWorksWhenSocIspInactiveAndRejectsConflictsWhenActive() {
        val legacyRt = legacyConfiguredRuntime().apply { bindPromoted(includeSocIsp = false) }
        val legacyBus = checkNotNull(legacyRt.createBus(context))

        // Static bridged retired candidates #26, #27, #28
        legacyBus.access(1, 0xfff69000L, 4, true, 0x11223344L)
        assertEquals(0x11223344L, legacyBus.access(2, 0xfff69000L, 4, false))
        legacyBus.access(3, 0xfff69100L, 4, true, 0x55667788L)
        assertEquals(0x55667788L, legacyBus.access(4, 0xfff69100L, 4, false))
        legacyBus.access(5, 0xfff6b000L, 4, true, 0x99aabbccL)
        assertEquals(0x99aabbccL, legacyBus.access(6, 0xfff6b000L, 4, false))

        // Dynamic query bridge works for inactive soc-isp ports configured via legacy keys
        val q68 = legacyRt.query(mapOf("address" to "0xfff68000", "width" to "4", "direction" to "write"))
        assertEquals(true, q68["permitted"])
        val qLv0 = legacyRt.query(mapOf("address" to "0xfff72000", "width" to "4", "direction" to "write"))
        assertEquals(true, qLv0["permitted"])
        val qLv30 = legacyRt.query(mapOf("address" to "0xfff730c4", "width" to "4", "direction" to "write"))
        assertEquals(true, qLv30["permitted"])
        // #31 (0xfff72028) is explicitly configured as "refuse" in legacyConfiguredRuntime
        val qLvRefuse = legacyRt.query(mapOf("address" to "0xfff72028", "width" to "4", "direction" to "write"))
        assertEquals(false, qLvRefuse["permitted"])

        // When soc-isp ports ARE active, every legacy M6.3 key/input must fail fast on createBus()
        val conflictCases = listOf(
            mapOf("image70000" to "setup-polls-once-v1"),
            mapOf("image70000" to DiscoveryStrategy.IMAGE_70000_DRIVER.id, ImageIrq106Status.KEY to ImageIrq106Status.PROFILE),
            mapOf("image50000" to "retained-words-zero-v1"),
            mapOf("image50000Control" to DiscoveryStrategy.IMAGE_50000_CONTROL.id),
            mapOf("image51000" to DiscoveryStrategy.IMAGE_TABLE_PAIR.id),
            mapOf("image59000" to "retained-tables-v1"),
            mapOf("image68000" to DiscoveryStrategy.IMAGE_68000.id),
            mapOf("image6e000" to DiscoveryStrategy.IMAGE_6E_CONFIG.id),
            mapOf(ImageLiveViewBanks.KEY to ImageLiveViewBanks.PROFILE),
            mapOf("inputs" to mapOf(RETAINED_69000_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0))),
            mapOf("inputs" to mapOf(RETAINED_69100_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0))),
            mapOf("inputs" to mapOf(RETAINED_6B000_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0))),
            mapOf("inputs" to mapOf(PromotedLegacyCandidates.LIVE_VIEW_CONFIG_WORDS_REGION to mapOf("strategy" to "refuse"))),
        )
        for (disc in conflictCases) {
            val rt = CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to disc)).apply {
                bindPromoted(includeSocIsp = true)
            }
            assertFailsWith<IllegalArgumentException>("Expected conflict for $disc") {
                rt.createBus(context)
            }
        }
    }

    @Test
    fun snapshotPromotionRequiresActiveSocIspAndPromotedMigrationFlag() {
        val legacyRt = legacyConfiguredRuntime().apply { bindPromoted(includeSocIsp = false) }
        val legacyBus = checkNotNull(legacyRt.createBus(context))

        // Populate a stateful 0xfff730c4 word & 0xfff69000 retained word before snapshot
        legacyBus.access(1, 0xfff69000L, 4, true, 0xabcdef01L)
        legacyBus.access(2, 0xfff730c4L, 4, true, 0x89000000L)
        val snapshot = legacyBus.saveSnapshot()

        // Empty runtime with NO soc-isp and NO legacy config must reject active promoted snapshot
        val emptyRt = CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap()).apply {
            bindPromoted(includeSocIsp = false)
        }
        val emptyBus = checkNotNull(emptyRt.createBus(context))
        assertFails {
            emptyBus.restoreSnapshot(snapshot, allowPromotedRegions = true)
        }

        // Runtime WITH active soc-isp ports accepts promoted discovery regions when allowPromotedRegions = true
        // and rejects when allowPromotedRegions = false
        val promotedRt = CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap()).apply {
            bindPromoted(includeSocIsp = true)
        }
        val unmigratedBus = checkNotNull(promotedRt.createBus(context))
        assertFails {
            unmigratedBus.restoreSnapshot(snapshot, allowPromotedRegions = false)
        }

        val migratedRt = CatalogRuntime(catalog, "discovery", Path.of("."), emptyMap()).apply {
            bindPromoted(includeSocIsp = true)
        }
        val migratedBus = checkNotNull(migratedRt.createBus(context))
        migratedBus.restoreSnapshot(snapshot, allowPromotedRegions = true)

        // Strict mode never creates a discovery bus and refuses inactive promoted ports
        val strictRt = CatalogRuntime(catalog, "strict", Path.of("."), emptyMap()).apply {
            bindPromoted(includeSocIsp = false)
        }
        assertNull(strictRt.createBus(context))
        assertEquals(false, strictRt.query(mapOf("address" to "0xfff69000", "width" to "4", "direction" to "read"))["permitted"])
        assertEquals(false, strictRt.query(mapOf("address" to "0xfff11060", "width" to "4", "direction" to "read"))["permitted"])
        assertEquals(false, strictRt.query(mapOf("address" to "0xfff72028", "width" to "4", "direction" to "write"))["permitted"])
    }

    @Test
    fun promotedIspMigrationSupportExtractsLegacyLiveViewAnd6eBankState() {
        val legacyRt = legacyConfiguredRuntime().apply { bindPromoted(includeSocIsp = false) }
        val legacyBus = checkNotNull(legacyRt.createBus(context))
        legacyBus.access(1, 0xfff730c4L, 4, true, 0x89000000L)
        currentPc = 0x5af98L; currentLr = 0x11e3b48L
        legacyBus.access(2, 0xfff6e014L, 4, true, 0x002c0f30L)
        val snapshot = legacyBus.saveSnapshot()
        @Suppress("UNCHECKED_CAST")
        val regions = snapshot["regions"] as Map<String, Any?>

        val row6e = regions.getValue(Image6e000.REGION) as Map<*, *>
        val offsets6e = listOf(0x000, 0x008, 0x014, 0x018, 0x020, 0x024, 0x068, 0x06c)
        val extracted6e = PromotedIspMigrationSupport.extractBank6e000(row6e, offsets6e)
        assertEquals(0x002c0f30L, extracted6e[offsets6e.indexOf(0x014)])

        val rowLv = regions.getValue(ImageLiveViewBanks.PRIMARY_REGION) as Map<*, *>
        val extractedLv = PromotedIspMigrationSupport.extractLiveView(rowLv[ImageLiveViewBanks.KEY])
        assertEquals(0x89000000L, extractedLv.registersByAddress[0xfff730c4L])
    }

    @Test
    fun relaxedModeSupportsSocIspActivePortsAndLegacyBridgedQueries() {
        val relaxedActiveRt = CatalogRuntime(catalog, RelaxedPeripheralFallback.MODE, Path.of("."), emptyMap()).apply {
            bindPromoted(includeSocIsp = true)
        }
        assertNotNull(relaxedActiveRt.createBus(context))
        assertEquals(true, relaxedActiveRt.query(mapOf("address" to "0xfff702e4", "width" to "4", "direction" to "write"))["permitted"])
        assertEquals(true, relaxedActiveRt.query(mapOf("address" to "0xfff69000", "width" to "4", "direction" to "read"))["permitted"])

        val relaxedLegacyRt = CatalogRuntime(
            catalog,
            RelaxedPeripheralFallback.MODE,
            Path.of("."),
            mapOf(
                "discovery" to mapOf(
                    ImageLiveViewBanks.KEY to ImageLiveViewBanks.PROFILE,
                    "inputs" to mapOf(
                        RETAINED_69000_REGION to mapOf("strategy" to "retained-bytes", "fill" to 0),
                    ),
                ),
            ),
        ).apply { bindPromoted(includeSocIsp = false) }
        val bus = checkNotNull(relaxedLegacyRt.createBus(context))
        assertEquals(true, relaxedLegacyRt.query(mapOf("address" to "0xfff69000", "width" to "4", "direction" to "write"))["permitted"])
        assertEquals(true, relaxedLegacyRt.query(mapOf("address" to "0xfff730c4", "width" to "4", "direction" to "write"))["permitted"])

        val fallback = RelaxedPeripheralFallback()
        val router = MmioRouter(emptyList(), bus, fallback)
        router.write(0xfff69000L, 4, 0x12345678L)
        assertEquals(0x12345678L, router.read(0xfff69000L, 4))
        assertEquals(0L, fallback.summary()["reads"])
        // Unmodeled address falls back in relaxed mode instead of halting
        router.write(0xfff70108L, 4, 0xdeadbeefL)
        assertEquals(0xdeadbeefL, router.read(0xfff70108L, 4))
        assertEquals(1L, fallback.summary()["reads"])
        assertEquals(1L, fallback.summary()["writes"])
    }
}
