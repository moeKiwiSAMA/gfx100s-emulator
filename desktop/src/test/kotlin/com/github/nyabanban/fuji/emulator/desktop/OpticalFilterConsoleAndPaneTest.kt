package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.BokehMaskShape
import com.github.nyabanban.fuji.emulator.api.NdFilterPreset
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotCodec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotSpec
import com.github.nyabanban.fuji.emulator.api.UvFilterPreset
import com.github.nyabanban.fuji.emulator.desktop.console.ConsoleBackend
import com.github.nyabanban.fuji.emulator.desktop.console.EmulatorConsole
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.LensProfilePanel
import com.github.nyabanban.fuji.emulator.desktop.panels.LensTemplateSpec
import com.github.nyabanban.fuji.emulator.desktop.panels.OpticalFilterPane
import com.github.nyabanban.fuji.emulator.desktop.panels.OpticalFilterStatus
import com.github.nyabanban.fuji.emulator.runtime.AsyncPerfProfiler
import com.github.nyabanban.fuji.emulator.runtime.FirmwareTelemetry
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.MenuButton
import javafx.scene.control.Slider
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.layout.FlowPane
import javafx.scene.layout.VBox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpticalFilterConsoleAndPaneTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootFxToolkit() {
            try {
                val latch = CountDownLatch(1)
                Platform.startup { latch.countDown() }
                latch.await(5, TimeUnit.SECONDS)
            } catch (_: IllegalStateException) {
                // Toolkit already started by another test in this JVM.
            }
        }

        private fun <T> onFx(block: () -> T): T {
            val future = CompletableFuture<T>()
            Platform.runLater {
                try {
                    future.complete(block())
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                }
            }
            return future.get(5, TimeUnit.SECONDS)
        }
    }

    @AfterEach
    fun resetLocale() {
        I18n.resetToDefault()
    }

    private class StubFilterBackend : ConsoleBackend {
        override val home: Path = Path.of(".")
        override val telemetry: FirmwareTelemetry = FirmwareTelemetry()
        override val profiler: AsyncPerfProfiler = AsyncPerfProfiler(telemetry)

        val filterState = linkedMapOf<String, Any?>(
            "enabled" to true,
            "slots" to "",
            "uvPreset" to "OFF",
            "uvTransmittance" to 1.0,
            "ndPreset" to "OFF",
            "ndStops" to 0.0,
            "bokehMask" to "OFF",
            "bokehMaskAngleDeg" to 0.0,
            "starEnabled" to false,
            "starPoints" to 6L,
            "starStrength" to 0.75,
            "mistEnabled" to false,
            "mistPreset" to "OFF",
            "mistStrength" to 0.0,
            "gndEnabled" to false,
            "gndStops" to 0.0,
            "gndHorizon" to 0.50,
            "activeSummary" to "OFF",
            "totalTransmittance" to 1.0,
            "availableFilterProviders" to listOf(
                mapOf("type" to "uv", "displayName" to "UV Protective Filter", "loadedFromJar" to false),
                mapOf("type" to "nd", "displayName" to "ND Neutral Density", "loadedFromJar" to false),
                mapOf("type" to "custom-warm", "displayName" to "Custom Warm JAR Filter", "loadedFromJar" to true),
            ),
        )

        override fun isPaused(): Boolean = false
        override fun setPaused(paused: Boolean): Boolean = paused
        override fun isRunning(): Boolean = true
        override fun requestBoot(): String = "ok"
        override fun requestStop(): String = "ok"
        override fun listComponents(): List<String> = listOf("optical-filter", "lens", "cmos")
        override fun componentState(componentId: String): Map<String, Any?>? =
            if (componentId == "optical-filter") filterState.toMap() else null
        override fun patchComponent(componentId: String, changes: Map<String, Any>): Map<String, Any?> {
            require(componentId == "optical-filter")
            filterState.putAll(changes)
            return filterState.toMap()
        }
        override fun captureDisplay(portName: String): ConsoleBackend.CapturedFrame? = null
        override fun audioStatus(): Map<String, Any?> = emptyMap()
        override fun audioDevices(): Map<String, Any?> = emptyMap()
        override fun patchAudio(changes: Map<String, Any?>): Map<String, Any?> = emptyMap()
    }

    @Test
    fun `console filter uv and multi-slot commands update standalone optical-filter component cleanly`() {
        val backend = StubFilterBackend()
        val out = ByteArrayOutputStream()
        val console = EmulatorConsole(
            backend = backend,
            input = ByteArrayInputStream(ByteArray(0)),
            output = out,
            interactivePrompt = false,
        )
        console.use {
            assertTrue(it.executeLine("/filter status"))
            assertTrue(it.executeLine("/filter nd nd8"))
            assertEquals("ND8", backend.filterState["ndPreset"])
            assertEquals(3.0, (backend.filterState["ndStops"] as Number).toDouble(), 1e-6)

            assertTrue(it.executeLine("/filter uv cheap"))
            assertEquals("CHEAP_POOR", backend.filterState["uvPreset"])
            assertEquals(0.895, (backend.filterState["uvTransmittance"] as Number).toDouble(), 1e-6)

            assertTrue(it.executeLine("/filter bokeh star 30"))
            assertEquals("STAR_5", backend.filterState["bokehMask"])
            assertEquals(30.0, (backend.filterState["bokehMaskAngleDeg"] as Number).toDouble(), 1e-6)

            assertTrue(it.executeLine("/filter star 6 0.9"))
            assertEquals(true, backend.filterState["starEnabled"])
            assertEquals(6L, (backend.filterState["starPoints"] as Number).toLong())

            assertTrue(it.executeLine("/filter mist 1/4"))
            assertEquals(true, backend.filterState["mistEnabled"])
            assertEquals("1/4", backend.filterState["mistPreset"])

            assertTrue(it.executeLine("/filter gnd 2.0 0.45"))
            assertEquals(true, backend.filterState["gndEnabled"])
            assertEquals(2.0, (backend.filterState["gndStops"] as Number).toDouble(), 1e-6)

            assertTrue(it.executeLine("/nd stops 4.5"))
            assertEquals(4.5, (backend.filterState["ndStops"] as Number).toDouble(), 1e-6)
            assertEquals("VARIABLE", backend.filterState["ndPreset"])

            assertTrue(it.executeLine("/filter clear"))
            assertEquals("OFF", backend.filterState["ndPreset"])
            assertEquals("OFF", backend.filterState["uvPreset"])
            assertEquals("OFF", backend.filterState["bokehMask"])
            assertEquals(false, backend.filterState["starEnabled"])
            assertEquals(false, backend.filterState["mistEnabled"])
            assertEquals(false, backend.filterState["gndEnabled"])

            // Multi-slot matte box commands: add UV + ND8 + ND64 + external plugin, reorder, toggle, remove
            assertTrue(it.executeLine("/filter add uv cheap"))
            assertTrue(it.executeLine("/filter add nd nd8"))
            assertTrue(it.executeLine("/filter add nd nd64"))
            assertTrue(it.executeLine("/filter add custom-warm"))
            var slots = OpticalFilterSlotCodec.parse(backend.filterState["slots"] as? String)
            assertEquals(listOf("uv", "nd", "nd", "custom-warm"), slots.map { s -> s.type })

            assertTrue(it.executeLine("/filter move 3 up"))
            slots = OpticalFilterSlotCodec.parse(backend.filterState["slots"] as? String)
            assertEquals(6.0, (slots[1].params["stops"] as Number).toDouble(), 1e-6)
            assertEquals(3.0, (slots[2].params["stops"] as Number).toDouble(), 1e-6)

            assertTrue(it.executeLine("/filter toggle 1"))
            slots = OpticalFilterSlotCodec.parse(backend.filterState["slots"] as? String)
            assertFalse(slots[0].enabled)

            assertTrue(it.executeLine("/filter remove 4"))
            slots = OpticalFilterSlotCodec.parse(backend.filterState["slots"] as? String)
            assertEquals(3, slots.size)
        }
    }

    @Test
    fun `OpticalFilterStatus builds valid patches and formats telemetry`() {
        val nd8 = OpticalFilterStatus.buildNdPresetPatch(NdFilterPreset.ND8)
        assertEquals("ND8", nd8["ndPreset"])
        assertEquals(3.0, (nd8["ndStops"] as Number).toDouble(), 1e-6)

        val uvCheap = OpticalFilterStatus.buildUvPresetPatch(UvFilterPreset.CHEAP_POOR)
        assertEquals("CHEAP_POOR", uvCheap["uvPreset"])
        assertEquals(0.895, (uvCheap["uvTransmittance"] as Number).toDouble(), 1e-6)

        val starMask = OpticalFilterStatus.buildBokehMaskPatch(BokehMaskShape.STAR_5, 15.0)
        assertEquals("STAR_5", starMask["bokehMask"])
        assertEquals(15.0, (starMask["bokehMaskAngleDeg"] as Number).toDouble(), 1e-6)

        val status = OpticalFilterStatus.fromMaps(
            mapOf("enabled" to true, "ndPreset" to "ND8", "ndStops" to 3.0, "bokehMask" to "STAR_5"),
            mapOf("activeSummary" to "ND8 (3.0 EV) + Bokeh ★5", "totalTransmittance" to 0.125),
        )
        assertTrue(status.ndTelemetryText().contains("ND8"))
        assertTrue(status.ndTelemetryText().contains("8×"))
        assertEquals(BokehMaskShape.STAR_5, status.bokehMask)
    }

    @Test
    fun `OpticalFilterPane compact and detailed modes render ordered slots external JAR menu and switch I18n live`() {
        val patches = mutableListOf<Map<String, Any>>()
        val snapshotSlots = listOf(
            OpticalFilterSlotSpec("uv", true, linkedMapOf("preset" to "CHEAP_POOR", "transmittance" to 0.895)),
            OpticalFilterSlotSpec("nd", true, linkedMapOf("preset" to "ND8", "stops" to 3.0)),
            OpticalFilterSlotSpec("nd", true, linkedMapOf("preset" to "ND64", "stops" to 6.0)),
        )
        val snapshot = mapOf(
            "components" to listOf(
                mapOf(
                    "id" to "optical-filter",
                    "config" to mapOf("enabled" to true, "slots" to OpticalFilterSlotCodec.encode(snapshotSlots)),
                    "state" to mapOf(
                        "enabled" to true,
                        "slotMode" to true,
                        "slots" to snapshotSlots.map { it.toMap() },
                        "slotsJson" to OpticalFilterSlotCodec.encode(snapshotSlots),
                        "totalTransmittance" to 0.895 / 512.0,
                        "totalEvLoss" to 9.16,
                        "effectiveNdStops" to 9.0,
                        "activeSummary" to "UV: Cheap + ND8 + ND64",
                        "availableFilterProviders" to listOf(
                            mapOf("type" to "uv", "displayName" to "UV Protective Filter", "loadedFromJar" to false),
                            mapOf("type" to "nd", "displayName" to "ND Neutral Density", "loadedFromJar" to false),
                            mapOf("type" to "custom-warm", "displayName" to "Custom Warm JAR Filter", "loadedFromJar" to true),
                        ),
                    ),
                ),
            ),
        )

        onFx {
            I18n.setLocale("en")
            val compact = OpticalFilterPane(
                mode = OpticalFilterPane.Mode.COMPACT,
                patchOpticalFilter = { p -> patches += p; CompletableFuture.completedFuture(Unit) },
                onOpenFilterRack = {},
            )
            val detailed = OpticalFilterPane(
                mode = OpticalFilterPane.Mode.DETAILED,
                patchOpticalFilter = { p -> patches += p; CompletableFuture.completedFuture(Unit) },
            )

            compact.observeSnapshot(snapshot)
            detailed.observeSnapshot(snapshot)

            // Compact pill chain shows 3 pills + 2 arrows = 5 children
            val chain = assertNotNull(compact.root.lookup("#filter-compact-chain") as? FlowPane)
            assertEquals(5, chain.children.size, "Expected 3 pills + 2 arrows in compact filter chain")

            // Detailed rack shows 3 ordered slot cards (#1 UV, #2 ND8, #3 ND64)
            val slotsBox = assertNotNull(detailed.root.lookup("#filter-detail-slots") as? VBox)
            assertEquals(3, slotsBox.children.size, "Expected 3 ordered slot cards in detailed matte box")

            // Dynamic external JAR plugin auto-registered in '+ Add Filter' menu
            val addMenu = assertNotNull(detailed.root.lookup("#filter-detail-add") as? MenuButton)
            assertTrue(
                addMenu.items.any { it.id == "filter-add-plugin-custom-warm" && it.text.contains("Custom Warm JAR Filter") },
                "External JAR filter provider must auto-register into '+ Add Filter' menu: ${addMenu.items.map { it.text }}",
            )

            // Live I18n toggle en -> zh_CN updates master toggle and add button reactively
            val masterBtn = assertNotNull(detailed.root.lookup("#filter-detail-master") as? ToggleButton)
            val enMaster = masterBtn.text
            val enAdd = addMenu.text
            assertEquals("IN PATH", enMaster)
            assertEquals("+ Add Filter", enAdd)

            I18n.setLocale("zh_CN")
            assertNotEquals(enMaster, masterBtn.text)
            assertNotEquals(enAdd, addMenu.text)
            assertEquals("+ 添加滤镜", addMenu.text)
        }
    }

    @Test
    fun `adding a mask to a full rack opens and replaces the original mask slot`() {
        onFx {
            val maskIndex = 1
            val slots = List(OpticalFilterSlotCodec.MAX_SLOTS) { i ->
                if (i == maskIndex) OpticalFilterStatus.defaultSlot("bokeh-mask", "HEART")
                else OpticalFilterStatus.defaultSlot("nd", "ND8")
            }
            val patches = mutableListOf<Map<String, Any>>()
            val pane = OpticalFilterPane(
                mode = OpticalFilterPane.Mode.DETAILED,
                patchOpticalFilter = { patch ->
                    patches += patch
                    CompletableFuture.completedFuture(Unit)
                },
            )
            pane.observeSnapshot(mapOf("components" to listOf(mapOf(
                "id" to "optical-filter",
                "config" to mapOf("enabled" to true, "slots" to OpticalFilterSlotCodec.encode(slots)),
                "state" to mapOf("enabled" to true, "slotMode" to true, "slots" to slots.map { it.toMap() }),
            ))))
            val add = assertNotNull(pane.root.lookup("#filter-detail-add") as? MenuButton)
            add.items.first { it.text == I18n.t("filter.add.bokeh") }.fire()
            val expected = slots.toMutableList().apply { this[maskIndex] = OpticalFilterStatus.defaultSlot("bokeh-mask", "STAR_5") }
            assertEquals(OpticalFilterStatus.buildSlotsPatch(expected), patches.single())
            assertNotNull(pane.root.lookup("#filter-slot-${maskIndex}-bokeh-heart"))
        }
    }

    @Test
    fun `SPI v2 schema UI auto-generates controls for third-party filter plugin and supports live rescan in UI and CLI`() {
        val patches = mutableListOf<Pair<String, Map<String, Any>>>()
        val pluginProviderMap = mapOf(
            "type" to "cinema-halation",
            "displayName" to "Cinema Halation Pro",
            "loadedFromJar" to true,
            "metadata" to mapOf(
                "apiVersion" to 2,
                "version" to "2.4.0",
                "author" to "CineLab",
                "description" to "Red-orange film halation glow",
                "descriptionZh" to "胶片红橙色光晕特效",
            ),
            "parameterSchema" to listOf(
                mapOf(
                    "key" to "strength",
                    "type" to "NUMBER",
                    "labelEn" to "Glow Intensity",
                    "labelZh" to "光晕强度",
                    "defaultValue" to 0.4,
                    "min" to 0.0,
                    "max" to 1.0,
                    "step" to 0.05,
                    "unit" to "",
                ),
                mapOf(
                    "key" to "mode",
                    "type" to "ENUM",
                    "labelEn" to "Film Stock",
                    "labelZh" to "胶片型号",
                    "defaultValue" to "250D",
                    "choices" to listOf(
                        mapOf("value" to "250D", "labelEn" to "Vision3 250D", "labelZh" to "250D 日光片"),
                        mapOf("value" to "500T", "labelEn" to "Vision3 500T", "labelZh" to "500T 灯光片"),
                    ),
                ),
                mapOf(
                    "key" to "preserveSkin",
                    "type" to "BOOLEAN",
                    "labelEn" to "Protect Skin",
                    "labelZh" to "肤色保护",
                    "defaultValue" to true,
                ),
                mapOf(
                    "key" to "tag",
                    "type" to "TEXT",
                    "labelEn" to "Label",
                    "labelZh" to "标签",
                    "defaultValue" to "warm",
                ),
            ),
        )
        val pluginSlot = OpticalFilterSlotSpec(
            type = "cinema-halation",
            enabled = true,
            params = linkedMapOf(
                "strength" to 0.4,
                "mode" to "250D",
                "preserveSkin" to true,
                "tag" to "warm",
            ),
        )
        val snapshot = mapOf(
            "components" to listOf(
                mapOf(
                    "id" to "optical-filter",
                    "config" to mapOf("enabled" to true, "slots" to OpticalFilterSlotCodec.encode(listOf(pluginSlot)), "rescanPlugins" to 0L),
                    "state" to mapOf(
                        "enabled" to true,
                        "slotMode" to true,
                        "rescanPlugins" to 0L,
                        "slots" to listOf(pluginSlot.toMap()),
                        "slotsJson" to OpticalFilterSlotCodec.encode(listOf(pluginSlot)),
                        "activeSummary" to "Cinema Halation Pro",
                        "totalTransmittance" to 1.0,
                        "availableFilterProviders" to listOf(pluginProviderMap),
                    ),
                ),
                mapOf(
                    "id" to "lens",
                    "config" to mapOf("template" to "gf63", "rescanPlugins" to 0L),
                    "state" to mapOf(
                        "template" to "gf63",
                        "rescanPlugins" to 0L,
                        "discoveredLensModules" to listOf(
                            mapOf(
                                "id" to "ts-50-macro",
                                "displayName" to "TS 50mm F2.8 Tilt-Shift",
                                "mountType" to "GF",
                                "minFocalLengthMm" to 50.0,
                                "maxFocalLengthMm" to 50.0,
                                "minAperture" to 2.8,
                                "maxAperture" to 22.0,
                                "minFocusDistanceM" to 0.30,
                                "electronicContacts" to true,
                            ),
                        ),
                        "discoveredAdapterModules" to listOf(
                            mapOf(
                                "id" to "adapter-pl-gfx",
                                "displayName" to "PL Cinema Adapter",
                                "communicationMode" to "MANUAL",
                            ),
                        ),
                    ),
                ),
            ),
        )

        onFx {
            I18n.setLocale("en")
            val filterPane = OpticalFilterPane(
                mode = OpticalFilterPane.Mode.DETAILED,
                patchOpticalFilter = { p -> patches += ("optical-filter" to p); CompletableFuture.completedFuture(Unit) },
            )
            filterPane.observeSnapshot(snapshot)

            // Verify auto-generated controls from FilterParamSpec (ZERO hardcoded per-filter UI!)
            val slider = assertNotNull(filterPane.root.lookup("#filter-slot-0-param-strength") as? Slider)
            assertEquals(0.4, slider.value, 1e-6)
            val combo = assertNotNull(filterPane.root.lookup("#filter-slot-0-param-mode") as? ComboBox<*>)
            assertEquals(2, combo.items.size)
            val check = assertNotNull(filterPane.root.lookup("#filter-slot-0-param-preserveSkin") as? CheckBox)
            assertTrue(check.isSelected)
            assertEquals("Protect Skin", check.text)
            val textField = assertNotNull(filterPane.root.lookup("#filter-slot-0-param-tag") as? TextField)
            assertEquals("warm", textField.text)

            // Toggle I18n -> zh_CN updates auto-generated schema control label live!
            I18n.setLocale("zh_CN")
            val checkZh = assertNotNull(filterPane.root.lookup("#filter-slot-0-param-preserveSkin") as? CheckBox)
            assertEquals("肤色保护", checkZh.text)

            // Fire checkbox -> patches updated slot params
            checkZh.fire()
            val lastFilterPatch = patches.last { it.first == "optical-filter" }.second
            val patchedSlots = OpticalFilterSlotCodec.parse(lastFilterPatch["slots"] as String)
            assertEquals(false, patchedSlots.single().params["preserveSkin"])

            // Rescan Plugins button in OpticalFilterPane patches rescanPlugins = 1
            val filterRescanBtn = assertNotNull(filterPane.root.lookup("#filter-detail-rescan") as? Button)
            filterRescanBtn.fire()
            assertEquals(1L, patches.last { it.first == "optical-filter" }.second["rescanPlugins"])

            // Rescan Plugins button + dynamically discovered external lens/adapter in LensProfilePanel
            val lensPanel = LensProfilePanel(
                patchComponent = { cid, p -> patches += (cid to p); CompletableFuture.completedFuture(Unit) },
            )
            lensPanel.observeSnapshot(snapshot)
            val templateCombo = assertNotNull(lensPanel.root.lookup("#lens-template-combo") as? ComboBox<*>)
            assertTrue(
                templateCombo.items.any { (it as? LensTemplateSpec)?.id == "ts-50-macro" },
                "Discovered external lens module must appear in LensProfilePanel templateCombo",
            )
            val lensRescanBtn = assertNotNull(lensPanel.root.lookup("#lens-rescan-plugins-btn") as? Button)
            lensRescanBtn.fire()
            assertEquals(1L, patches.last { it.first == "lens" }.second["rescanPlugins"])
        }

        // Verify /filter rescan and /lens rescan CLI commands
        val lensState = linkedMapOf<String, Any?>(
            "template" to "gf63",
            "rescanPlugins" to 0L,
            "pluginJarCount" to 1,
            "discoveredLensModules" to listOf(mapOf("id" to "ts-50-macro", "displayName" to "TS 50mm")),
            "discoveredAdapterModules" to emptyList<Any>(),
        )
        val filterBackend = StubFilterBackend()
        val combinedBackend = object : ConsoleBackend by filterBackend {
            override fun componentState(componentId: String): Map<String, Any?>? = when (componentId) {
                "optical-filter" -> filterBackend.componentState(componentId)
                "lens" -> lensState.toMap()
                else -> null
            }
            override fun patchComponent(componentId: String, changes: Map<String, Any>): Map<String, Any?> = when (componentId) {
                "optical-filter" -> filterBackend.patchComponent(componentId, changes)
                "lens" -> {
                    lensState.putAll(changes)
                    lensState.toMap()
                }
                else -> error("unexpected component $componentId")
            }
        }
        val out = ByteArrayOutputStream()
        EmulatorConsole(
            backend = combinedBackend,
            input = ByteArrayInputStream(ByteArray(0)),
            output = out,
            interactivePrompt = false,
        ).use { console ->
            assertTrue(console.executeLine("/filter rescan"))
            assertEquals(1L, filterBackend.filterState["rescanPlugins"])
            assertTrue(console.executeLine("/lens rescan"))
            assertEquals(1L, lensState["rescanPlugins"])
            assertTrue(console.executeLine("/lens template ts-50-macro"))
            assertEquals("ts-50-macro", lensState["template"])
        }
    }

    @Test
    fun `LensProfilePanel and OpticalFilterPane live-switch localized descriptions and preserve English fallback`() {
        val patches = mutableListOf<Pair<String, Map<String, Any>>>()
        val multiLangLensMeta = mapOf(
            "apiVersion" to 2,
            "version" to "2.0.0",
            "author" to "Fujifilm GFX100S Emulator",
            "description" to "GFX flagship medium-format telephoto prime",
            "descriptionZh" to "GFX 旗舰级中画幅长焦定焦镜头",
            "descriptions" to mapOf(
                "en" to "GFX flagship medium-format telephoto prime",
                "zh_CN" to "GFX 旗舰级中画幅长焦定焦镜头",
                "ja_JP" to "GFX フラッグシップ中判望遠単焦点レンズ",
                "de_DE" to "GFX-Flaggschiff-Mittelformat-Teleobjektiv",
                "fr_FR" to "Téléobjectif moyen format phare GFX",
                "es_ES" to "Teleobjetivo de formato medio insignia GFX",
            ),
        )
        val multiLangAdapterMeta = mapOf(
            "apiVersion" to 2,
            "version" to "2.0.0",
            "author" to "Fujifilm GFX100S Emulator",
            "description" to "Electronic EF-to-GFX smart adapter with AF",
            "descriptionZh" to "带自动对焦的电子 EF-GFX 转接环",
            "names" to mapOf(
                "en" to "Fringer EF-GFX Smart Adapter",
                "zh_CN" to "Fringer EF-GFX 电子转接环",
            ),
            "descriptions" to mapOf(
                "en" to "Electronic EF-to-GFX smart adapter with AF",
                "zh_CN" to "带自动对焦的电子 EF-GFX 转接环",
            ),
        )
        val enOnlyFilterProvider = mapOf(
            "type" to "en-only-glow",
            "displayName" to "English Only Glow",
            "loadedFromJar" to true,
            "metadata" to mapOf(
                "apiVersion" to 2,
                "version" to "1.0.0",
                "author" to "Indie",
                "description" to "English-only third-party glow filter",
                "descriptionZh" to "",
            ),
            "parameterSchema" to listOf(
                mapOf(
                    "key" to "softness",
                    "type" to "BOOLEAN",
                    "labelEn" to "Soft Highlight Roll-Off",
                    "labelZh" to "",
                    "defaultValue" to true,
                ),
            ),
        )
        val enOnlySlot = OpticalFilterSlotSpec("en-only-glow", true, linkedMapOf("softness" to true))
        val snapshotMulti = mapOf(
            "components" to listOf(
                mapOf(
                    "id" to "lens",
                    "config" to mapOf("template" to "gf110", "adapterId" to "adapter-fringer-ef-gfx"),
                    "state" to mapOf(
                        "template" to "gf110",
                        "templateDisplayName" to "GF 110mm F2 R LM WR",
                        "adapterId" to "adapter-fringer-ef-gfx",
                        "activeLensMetadata" to multiLangLensMeta,
                        "activeAdapterMetadata" to multiLangAdapterMeta,
                        "discoveredAdapterModules" to listOf(
                            mapOf(
                                "id" to "adapter-fringer-ef-gfx",
                                "displayName" to "Fringer EF-GFX Smart Adapter",
                                "communicationMode" to "GENERIC_SIMULATED",
                                "metadata" to multiLangAdapterMeta,
                            ),
                        ),
                    ),
                ),
                mapOf(
                    "id" to "optical-filter",
                    "config" to mapOf("enabled" to true, "slots" to OpticalFilterSlotCodec.encode(listOf(enOnlySlot))),
                    "state" to mapOf(
                        "enabled" to true,
                        "slotMode" to true,
                        "slots" to listOf(enOnlySlot.toMap()),
                        "slotsJson" to OpticalFilterSlotCodec.encode(listOf(enOnlySlot)),
                        "activeSummary" to "English Only Glow",
                        "totalTransmittance" to 1.0,
                        "availableFilterProviders" to listOf(enOnlyFilterProvider),
                    ),
                ),
            ),
        )
        val snapshotEnOnlyLens = mapOf(
            "components" to listOf(
                mapOf(
                    "id" to "lens",
                    "config" to mapOf("template" to "ext-en-lens"),
                    "state" to mapOf(
                        "template" to "ext-en-lens",
                        "templateDisplayName" to "External 50mm F1.2",
                        "activeLensMetadata" to mapOf(
                            "apiVersion" to 2,
                            "version" to "1.0.0",
                            "author" to "ThirdParty",
                            "description" to "English-only external lens description",
                            "descriptionZh" to "",
                        ),
                    ),
                ),
            ),
        )

        onFx {
            I18n.setLocale("en")
            val lensPanel = LensProfilePanel(
                patchComponent = { cid, p -> patches += (cid to p); CompletableFuture.completedFuture(Unit) },
            )
            val filterPane = OpticalFilterPane(
                mode = OpticalFilterPane.Mode.DETAILED,
                patchOpticalFilter = { p -> patches += ("optical-filter" to p); CompletableFuture.completedFuture(Unit) },
            )
            lensPanel.observeSnapshot(snapshotMulti)
            filterPane.observeSnapshot(snapshotMulti)

            val templateCombo = assertNotNull(lensPanel.root.lookup("#lens-template-combo") as? ComboBox<*>)
            val adapterCombo = assertNotNull(lensPanel.root.lookup("#lens-adapter-combo") as? ComboBox<*>)
            assertEquals("GFX flagship medium-format telephoto prime", templateCombo.tooltip?.text)
            assertEquals("Electronic EF-to-GFX smart adapter with AF", adapterCombo.tooltip?.text)

            // Live switch EN -> zh_CN updates lens & adapter tooltips immediately!
            I18n.setLocale("zh_CN")
            assertEquals("GFX 旗舰级中画幅长焦定焦镜头", templateCombo.tooltip?.text)
            assertEquals("带自动对焦的电子 EF-GFX 转接环", adapterCombo.tooltip?.text)

            // English-only external filter stays English (never blank!) even while GUI is in zh_CN
            val check = assertNotNull(filterPane.root.lookup("#filter-slot-0-param-softness") as? CheckBox)
            assertEquals("Soft Highlight Roll-Off", check.text)

            // English-only external lens stays English (never blank!) even while GUI is in zh_CN
            lensPanel.observeSnapshot(snapshotEnOnlyLens)
            assertEquals("English-only external lens description", templateCombo.tooltip?.text)
        }
    }
}
