package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.CmosSourceStatus
import com.github.nyabanban.fuji.emulator.desktop.panels.InputSourceCategory
import com.github.nyabanban.fuji.emulator.desktop.panels.SourceSceneDimension
import com.github.nyabanban.fuji.emulator.desktop.panels.SourceSelectorPane
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceSelectorPaneTest {

    @Test
    fun taxonomySeparates3DAnd2DCategoriesAndPresetsCleanly() {
        assertEquals(listOf(InputSourceCategory.OPTICAL_SCENE), InputSourceCategory.categoriesFor(SourceSceneDimension.DIM_3D))
        assertEquals(
            listOf(
                InputSourceCategory.STATIC_IMAGE,
                InputSourceCategory.LOCAL_VIDEO,
                InputSourceCategory.GAME_SCREEN,
                InputSourceCategory.WEBCAM,
                InputSourceCategory.NETWORK_STREAM,
            ),
            InputSourceCategory.categoriesFor(SourceSceneDimension.DIM_2D),
        )
        assertTrue(InputSourceCategory.TWO_D_CATEGORIES.none { it.dimension == SourceSceneDimension.DIM_3D })

        val presets3d = CmosSourceStatus.presetsFor(SourceSceneDimension.DIM_3D)
        assertEquals(
            listOf(
                "scene://qr-ball", "scene://mona-lisa", "scene://milk-frog",
                "scene://depth-studio", "scene://star-bokeh", "scene://bokeh-city",
            ),
            presets3d.map { it.sourceFile },
        )
        assertTrue(presets3d.all { it.dimension == SourceSceneDimension.DIM_3D })

        val presets2d = CmosSourceStatus.presetsFor(SourceSceneDimension.DIM_2D)
        assertEquals(
            listOf("test-qr.jpg", "screen://primary", "webcam:default", "tcp://127.0.0.1:9555"),
            presets2d.map { it.sourceFile },
        )
        assertTrue(presets2d.all { it.dimension == SourceSceneDimension.DIM_2D })
    }

    @Test
    fun compactStatusFormats3D2DAndErrorStatesCleanly() {
        I18n.resetToDefault()
        try {
            val s3d = CmosSourceStatus.fromMaps(
                config = mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://mona-lisa", "loop" to true),
                state = mapOf("hasDepthBuffer" to true, "opticsFilterActive" to true, "sourceWidth" to 960, "sourceHeight" to 720, "framesExposed" to 42L),
            )
            assertEquals(SourceSceneDimension.DIM_3D, s3d.dimension)
            assertEquals("3D · Mona Lisa Eye-AF 3D · 960×720 · Optics ON", s3d.compactStatusText())

            val s2d = CmosSourceStatus.fromMaps(
                config = mapOf("sourceKind" to "JPEG", "sourceFile" to "test-qr.jpg", "loop" to true),
                state = mapOf("hasDepthBuffer" to false, "opticsFilterActive" to false, "sourceWidth" to 1024, "sourceHeight" to 768),
            )
            assertEquals(SourceSceneDimension.DIM_2D, s2d.dimension)
            assertEquals("2D · test-qr.jpg · 1024×768 · Optics OFF", s2d.compactStatusText())

            val sErr = s2d.copy(lastSourceError = "FFmpeg stream disconnected")
            assertTrue(sErr.compactStatusText().startsWith("Error:"))
            assertTrue(sErr.summaryText().contains("FFmpeg stream disconnected"))

            // Verify live zh_CN formatting parity as well
            I18n.setLocale("zh_CN")
            assertEquals("3D · 蒙娜丽莎眼控 3D · 960×720 · 光学 ON", s3d.compactStatusText())
            assertTrue(sErr.compactStatusText().startsWith("异常:"))
        } finally {
            I18n.resetToDefault()
        }
    }

    @Test
    fun twoLevelMenuTogglesContextualWidgetsDispatchesPatchesAndSyncsSnapshots() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val checkUi = FutureTask {
            Platform.setImplicitExit(false)
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder) }
            val patches = mutableListOf<Map<String, Any>>()
            val pane = SourceSelectorPane(
                ownerWindow = { owner },
                patchCmos = { patch ->
                    patches += patch
                    CompletableFuture.completedFuture(Unit)
                },
            )
            holder.children += pane.root
            holder.applyCss()

            fun toggle(id: String) = holder.lookup("#$id") as ToggleButton
            fun btn(id: String) = holder.lookup("#$id") as Button
            fun vbox(id: String) = holder.lookup("#$id") as VBox
            fun label(id: String) = holder.lookup("#$id") as Label
            fun field(id: String) = holder.lookup("#$id") as TextField
            @Suppress("UNCHECKED_CAST")
            fun combo(id: String) = holder.lookup("#$id") as ComboBox<InputSourceCategory>

            I18n.resetToDefault()
            // Default: Level 1 = 3D optical scene
            assertEquals(SourceSceneDimension.DIM_3D, pane.activeDimension)
            assertTrue(toggle("source-mode-3d").isSelected)
            assertFalse(toggle("source-mode-2d").isSelected)
            assertTrue(vbox("source-panel-3d").isVisible && vbox("source-panel-3d").isManaged)
            assertFalse(vbox("source-panel-2d").isVisible || vbox("source-panel-2d").isManaged)
            assertTrue(label("source-3d-depth-badge").text.contains("True 3D depth map"))

            // Clicking a 3D scene card dispatches the exact optical-scene patch
            for (slug in listOf("mona-lisa", "star-bokeh", "bokeh-city")) {
                btn("source-3d-card-$slug").fire()
                assertEquals(
                    mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://$slug", "loop" to true),
                    patches.last(),
                )
            }

            // Switch Level 1 -> 2D planar / external input
            toggle("source-mode-2d").fire()
            assertEquals(SourceSceneDimension.DIM_2D, pane.activeDimension)
            assertFalse(vbox("source-panel-3d").isVisible || vbox("source-panel-3d").isManaged)
            assertTrue(vbox("source-panel-2d").isVisible && vbox("source-panel-2d").isManaged)
            assertEquals(InputSourceCategory.TWO_D_CATEGORIES, combo("source-category-combo").items.toList())
            assertTrue(label("source-2d-distance-hint").text.contains("2D planar input"))

            // Contextual fields: STATIC_IMAGE shows FileChooser, hides screen region
            assertTrue(btn("source-browse-btn").isVisible && btn("source-browse-btn").isManaged)
            assertFalse(vbox("source-screen-region-box").isVisible || vbox("source-screen-region-box").isManaged)

            // Switch 2D Level-2 category -> GAME_SCREEN hides FileChooser, shows screen region
            combo("source-category-combo").value = InputSourceCategory.GAME_SCREEN
            combo("source-category-combo").fireEvent(javafx.event.ActionEvent())
            assertFalse(btn("source-browse-btn").isVisible || btn("source-browse-btn").isManaged)
            assertTrue(vbox("source-screen-region-box").isVisible && vbox("source-screen-region-box").isManaged)
            assertEquals("screen://primary", field("source-ref-field").text)

            // Clicking 2D preset chip dispatches patch cleanly
            btn("source-2d-chip-jpeg").fire()
            assertEquals(
                mapOf("sourceKind" to "JPEG", "sourceFile" to "test-qr.jpg", "loop" to true),
                patches.last(),
            )
            assertEquals(InputSourceCategory.STATIC_IMAGE, pane.active2DCategory)
            assertTrue(btn("source-browse-btn").isVisible)

            // Telemetry detail starts collapsed, auto-expands on source error
            pane.observeSnapshot(
                mapOf(
                    "components" to listOf(
                        mapOf(
                            "id" to "cmos",
                            "config" to mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://depth-studio", "loop" to true),
                            "state" to mapOf("hasDepthBuffer" to true, "opticsFilterActive" to true, "sourceWidth" to 1024, "sourceHeight" to 768, "framesExposed" to 10L),
                        ),
                    ),
                ),
            )
            assertEquals(SourceSceneDimension.DIM_3D, pane.activeDimension)
            assertTrue(label("source-status-label").text.contains("3D · 3D Optical Bokeh Studio"))
            assertFalse(label("source-telemetry-detail").isVisible)

            // Live locale switch updates bound labels and dynamic status text without restart
            I18n.setLocale("zh_CN")
            try {
                assertTrue(label("source-3d-depth-badge").text.contains("真实 3D 深度图"))
                assertTrue(label("source-status-label").text.contains("3D · 3D 光学散景棚"))
            } finally {
                I18n.resetToDefault()
            }

            pane.observeSnapshot(
                mapOf(
                    "components" to listOf(
                        mapOf(
                            "id" to "cmos",
                            "config" to mapOf("sourceKind" to "ffmpeg-pipe", "sourceFile" to "webcam:default", "loop" to true),
                            "state" to mapOf("hasDepthBuffer" to false, "lastSourceError" to "device busy"),
                        ),
                    ),
                ),
            )
            assertEquals(SourceSceneDimension.DIM_2D, pane.activeDimension)
            assertEquals(InputSourceCategory.WEBCAM, pane.active2DCategory)
            assertTrue(label("source-status-label").text.contains("device busy"))
            assertTrue(label("source-telemetry-detail").isVisible)
        }
        try {
            Platform.startup(checkUi)
        } catch (_: IllegalStateException) {
            Platform.runLater(checkUi)
        }
        checkUi.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun scene3dMenuStaysCompactWithoutHorizontalOverflowAcrossAllSixLocales() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val checkLayout = FutureTask {
            Platform.setImplicitExit(false)
            val sidebar = VBox().apply {
                styleClass += "studio-sidebar"
                prefWidth = 370.0
                maxWidth = 370.0
            }
            val pane = SourceSelectorPane(ownerWindow = { null }, patchCmos = null)
            sidebar.children += pane.root
            val scene = Scene(sidebar, 370.0, 520.0)
            com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry.applyToScene(
                scene,
                com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry.origin(),
            )
            val panel3d = sidebar.lookup("#source-panel-3d") as VBox
            val cardIds = listOf(
                "source-3d-card-qr-ball",
                "source-3d-card-mona-lisa",
                "source-3d-card-milk-frog",
                "source-3d-card-depth-studio",
                "source-3d-card-star-bokeh",
                "source-3d-card-bokeh-city",
            )
            try {
                for (code in I18n.supportedLanguageCodes()) {
                    I18n.setLocale(code)
                    sidebar.applyCss()
                    sidebar.layout()
                    val panelHeight = panel3d.boundsInParent.height
                    assertTrue(
                        panelHeight in 45.0..145.0,
                        "Expected compact #source-panel-3d height <= 145px for locale '$code', got $panelHeight",
                    )
                    assertTrue(
                        pane.root.boundsInParent.width <= 370.5,
                        "Expected zero horizontal overflow at 370px for locale '$code', got ${pane.root.boundsInParent.width}",
                    )
                    for (cid in cardIds) {
                        val card = sidebar.lookup("#$cid") as Button
                        assertTrue(card.text.isNotBlank(), "Blank text on #$cid for '$code'")
                        assertTrue(card.graphic == null, "Expected sleek single-line button without VBox graphic on #$cid")
                        assertTrue(
                            card.boundsInParent.height in 18.0..32.0,
                            "Expected compact single-line height on #$cid for '$code', got ${card.boundsInParent.height}",
                        )
                        assertTrue(
                            card.tooltip?.text?.contains("\n") == true,
                            "Expected rich multi-line tooltip with subtitle on #$cid for '$code'",
                        )
                    }
                }
            } finally {
                I18n.resetToDefault()
            }
        }
        try {
            Platform.startup(checkLayout)
        } catch (_: IllegalStateException) {
            Platform.runLater(checkLayout)
        }
        checkLayout.get(30, TimeUnit.SECONDS)
    }
}
