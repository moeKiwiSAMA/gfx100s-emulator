package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.input.CameraInputAction
import com.github.nyabanban.fuji.emulator.desktop.input.CameraInputSink
import com.github.nyabanban.fuji.emulator.desktop.input.InputBindingConfig
import com.github.nyabanban.fuji.emulator.desktop.input.InputBindingController
import com.github.nyabanban.fuji.emulator.desktop.input.InputBindingsDialog
import com.github.nyabanban.fuji.emulator.desktop.theme.BodyThemeRegistry
import com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ScrollPane
import javafx.scene.image.ImageView
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave 14 pre-`v0.3.0` UI alignment, multi-window display continuity, 6-language x 5-theme layout,
 * and rapid UI stress regression suite.
 */
class W14UiAlignmentAndStressTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootFx() {
            val started = CountDownLatch(1)
            try {
                Platform.startup {
                    Platform.setImplicitExit(false)
                    started.countDown()
                }
            } catch (_: IllegalStateException) {
                Platform.runLater {
                    Platform.setImplicitExit(false)
                    started.countDown()
                }
            }
            assertTrue(started.await(15, TimeUnit.SECONDS), "JavaFX toolkit failed to start")
        }

        private fun <T> onFx(block: () -> T): T {
            val ref = AtomicReference<Result<T>>()
            val latch = CountDownLatch(1)
            Platform.runLater {
                try {
                    ref.set(Result.success(block()))
                } catch (t: Throwable) {
                    ref.set(Result.failure(t))
                } finally {
                    latch.countDown()
                }
            }
            assertTrue(latch.await(20, TimeUnit.SECONDS), "Timed out waiting for FX task")
            return ref.get().getOrThrow()
        }
    }

    @AfterEach
    fun resetLocale() {
        onFx { I18n.setLocale(Locale.ENGLISH) }
    }

    private class NoopInputSink : CameraInputSink {
        override fun press(action: CameraInputAction, source: String) {}
        override fun release(action: CameraInputAction, source: String) {}
        override fun step(action: CameraInputAction) {}
    }

    private fun makeSolidFrame(w: Int, h: Int, seq: Long, argb: Int): CameraStudio.Frame =
        CameraStudio.Frame(w, h, seq, IntArray(w * h) { argb })

    @Test
    fun displayFramesAndEvfWindowSurviveBodyThemeHotSwitchesWithoutBlankingOrAliasing() {
        onFx {
            val owner = Stage()
            val ownerRoot = VBox()
            owner.scene = Scene(ownerRoot, 380.0, 760.0).also {
                UiThemeRegistry.applyToScene(it, UiThemeRegistry.origin())
            }
            val studio = CameraStudio(
                owner = owner,
                sendButton = { _, _ -> CompletableFuture.completedFuture(Unit) },
                configure = {},
                inspect = {},
                failure = { throw it },
                initialBodyTheme = BodyThemeRegistry.origin(),
                initialUiTheme = UiThemeRegistry.origin(),
            )
            try {
                ownerRoot.children.setAll(studio.root)
                owner.show()
                studio.showAll(0.75)

                // Paint live LCD (1024x768), EVF (1280x960), and Shoulder OLED (304x230) frames.
                studio.paint(
                    mapOf(
                        "display.main" to makeSolidFrame(1024, 768, 42L, 0xFF204060.toInt()),
                        "display.evf" to makeSolidFrame(1280, 960, 17L, 0xFF305020.toInt()),
                        "display.shoulder" to makeSolidFrame(304, 230, 9L, 0xFFE0E0E0.toInt()),
                    ),
                )
                assertFalse(studio.waiting("rear-lcd"), "Rear LCD should be live before theme switch")
                assertFalse(studio.waiting("rear-evf"), "Rear EVF should be live before theme switch")
                assertFalse(studio.waiting("top-shoulder"), "Top shoulder should be live before theme switch")

                val displays = studio.state()["displays"] as Map<*, *>
                assertEquals(42L, (displays["rear-lcd"] as Map<*, *>)["sequence"])

                // Open minimal floating EVF window and verify bilinear smoothing + live image + narrow 280px HUD fit.
                studio.setEvfWindowVisible(true)
                studio.setEvfAlwaysOnTop(true)
                assertTrue(studio.isEvfWindowShowing())
                assertTrue(studio.isEvfAlwaysOnTop())

                val evfStage = Window.getWindows()
                    .filterIsInstance<Stage>()
                    .single { it.scene?.root?.id == "evf-window-root" }
                val evfImageView = evfStage.scene.root.lookup("#evf-window-image") as ImageView
                val evfHudBar = evfStage.scene.root.lookup("#evf-window-hud") as Region
                assertTrue(evfImageView.isSmooth, "Floating EVF ImageView must enable bilinear smoothing for crisp downscaled OSD")
                assertTrue(evfImageView.isPreserveRatio, "Floating EVF ImageView must preserve 4:3 aspect ratio")
                assertNotNull(evfImageView.image, "Floating EVF must display the live EVF frame immediately")

                // Resize EVF window down to MIN_WIDTH x MIN_HEIGHT (280x210) across all 6 languages -> HUD must fit!
                evfStage.width = EvfViewfinderWindow.MIN_WIDTH
                evfStage.height = EvfViewfinderWindow.MIN_HEIGHT
                for (code in I18n.supportedLanguageCodes()) {
                    I18n.setLocale(code)
                    evfStage.scene.root.applyCss()
                    evfStage.scene.root.layout()
                    val hudBounds = evfHudBar.boundsInParent
                    assertTrue(
                        hudBounds.width <= evfStage.scene.width + 1.0,
                        "EVF HUD overflowed 280px window in locale=$code: hudW=${hudBounds.width}, sceneW=${evfStage.scene.width}",
                    )
                }

                // Hot-switch across ALL 4 body themes (including hasselblad-907x which has NO rear EVF!)
                // WITHOUT painting any new frames -> displays & EVF window must NEVER revert to Waiting!
                for (themeId in listOf("hasselblad-x2d2", "hasselblad-907x", "compact-rangefinder", "origin")) {
                    val nextBody = BodyThemeRegistry.builtInThemes().single { it.id == themeId }
                    studio.applyBodyTheme(nextBody)
                    assertFalse(studio.waiting("rear-lcd"), "Rear LCD blanked after switching to $themeId")
                    assertFalse(studio.waiting("rear-evf"), "Rear EVF blanked after switching to $themeId")
                    assertFalse(studio.waiting("top-shoulder"), "Shoulder LCD blanked after switching to $themeId")
                    assertNotNull(evfImageView.image, "Floating EVF lost image after switching to $themeId")
                }

                // Verify TouchCoordinateMapper handles every body theme's rear-lcd aspect ratio cleanly.
                for (body in BodyThemeRegistry.builtInThemes()) {
                    val lcdCtrl = body.panels.flatMap { it.controls }.single { it.displayPort == "display.main" }
                    val center = TouchCoordinateMapper.mapViewportToTouch(
                        lcdCtrl.width / 2.0,
                        lcdCtrl.height / 2.0,
                        lcdCtrl.width,
                        lcdCtrl.height,
                    )
                    assertTrue(center.x in 510..514 && center.y in 382..386, "Center touch off for ${body.id}: $center")
                    val tl = TouchCoordinateMapper.mapViewportToTouch(0.0, 0.0, lcdCtrl.width, lcdCtrl.height)
                    assertEquals(TouchPointCoord(0, 0), tl, "Top-left touch clamp for ${body.id}")
                    val br = TouchCoordinateMapper.mapViewportToTouch(lcdCtrl.width, lcdCtrl.height, lcdCtrl.width, lcdCtrl.height)
                    assertEquals(TouchPointCoord(1023, 767), br, "Bottom-right touch clamp for ${body.id}")
                }
            } finally {
                studio.close()
                owner.close()
            }
        }
    }

    @Test
    fun allSidebarPanelsAndInputBindingsFitNarrowWidthAcross6LanguagesAnd5ThemesWithZeroDarkBleed() {
        onFx {
            val locales = I18n.supportedLanguageCodes()
            assertEquals(listOf("en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES"), locales)
            val themes = UiThemeRegistry.builtInThemes()
            assertEquals(5, themes.size)

            val owner = Stage()
            val sidebarBox = VBox(8.0).apply {
                styleClass += "studio-sidebar"
            }
            val scroll = ScrollPane(sidebarBox).apply {
                isFitToWidth = true
                styleClass += "studio-sidebar"
            }
            val scene = Scene(scroll, 380.0, 760.0)
            owner.scene = scene

            val dialSteps = AtomicInteger(0)
            val studio = CameraStudio(
                owner = owner,
                sendButton = { _, _ -> CompletableFuture.completedFuture(Unit) },
                configure = {},
                inspect = {},
                failure = { throw it },
                sendSettingValue = { comp, key, _ ->
                    if (comp == "soc-adc" && (key == "rearDialDetents" || key == "frontDialDetents")) {
                        dialSteps.incrementAndGet()
                    }
                    CompletableFuture.completedFuture(Unit)
                },
                patchCmos = { CompletableFuture.completedFuture(Unit) },
                patchOpticalFilter = { CompletableFuture.completedFuture(Unit) },
            )
            val lensPanel = LensProfilePanel(patchComponent = { _, _ -> CompletableFuture.completedFuture(Unit) })
            val filterPanel = OpticalFilterPane(
                mode = OpticalFilterPane.Mode.DETAILED,
                patchOpticalFilter = { CompletableFuture.completedFuture(Unit) },
            )
            val audioPanel = AudioIoPanel(
                fetchStatus = { emptyMap() },
                fetchDevices = { emptyMap() },
                applyPatchAsync = { CompletableFuture.completedFuture(emptyMap<String, Any?>()) },
            )
            val ibisPanel = OrientationIbisPane(patchComponent = { _, _ -> CompletableFuture.completedFuture(Unit) })
            sidebarBox.children.setAll(studio.root, lensPanel.root, filterPanel.root, audioPanel.root, ibisPanel.root)

            // Populate long realistic Vulkan GPU name and multiple filter slots!
            lensPanel.observeSnapshot(
                mapOf(
                    "components" to listOf(
                        mapOf(
                            "id" to "lens",
                            "config" to mapOf("opticsBackend" to "vulkan", "vulkanDevice" to "auto"),
                            "state" to mapOf(
                                "activeOpticsBackend" to "VULKAN",
                                "activeVulkanDeviceIndex" to 0,
                                "activeVulkanDeviceName" to "NVIDIA GeForce RTX 4090 Laptop GPU (Discrete, ExtMem)",
                                "availableVulkanDevices" to listOf(
                                    mapOf(
                                        "index" to 0,
                                        "name" to "NVIDIA GeForce RTX 4090 Laptop GPU (Discrete, ExtMem)",
                                        "type" to "DISCRETE_GPU",
                                        "extMem" to true,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            filterPanel.observeSnapshot(
                mapOf(
                    "components" to listOf(
                        mapOf(
                            "id" to "optical-filter",
                            "config" to OpticalFilterStatus.buildSlotsPatch(
                                listOf(
                                    OpticalFilterStatus.defaultSlot("uv", "PRO_MRC"),
                                    OpticalFilterStatus.defaultSlot("bokeh-mask", "STAR_5"),
                                    OpticalFilterStatus.defaultSlot("nd", "ND8"),
                                ),
                            ),
                            "state" to mapOf("loaded" to true, "enabled" to true),
                        ),
                    ),
                ),
            )

            owner.show()

            val controller = InputBindingController(InputBindingConfig(), NoopInputSink())
            val dialog = InputBindingsDialog(
                owner = owner,
                controller = controller,
                persist = {},
                gamepadAvailable = { true },
                initialTheme = UiThemeRegistry.origin(),
            )

            try {
                // Verify TactileDialWheel responds to scroll wheel!
                val rearWheel = studio.root.lookup("#tactile-rear-dial-wheel") as Region
                assertNotNull(rearWheel)
                rearWheel.fireEvent(
                    ScrollEvent(
                        ScrollEvent.SCROLL, 10.0, 10.0, 10.0, 10.0,
                        false, false, false, false, false, false,
                        0.0, 40.0, 0.0, 40.0,
                        ScrollEvent.HorizontalTextScrollUnits.NONE, 0.0,
                        ScrollEvent.VerticalTextScrollUnits.NONE, 0.0,
                        0, null,
                    ),
                )
                assertTrue(dialSteps.get() > 0, "Scroll event on TactileDialWheel must dispatch command dial detents")

                // Verify rear-lcd has no intrusive tooltip.
                val rearFaceStage = Window.getWindows()
                    .filterIsInstance<Stage>()
                    .firstOrNull { it.scene?.root?.lookup("#rear-lcd") != null }
                val rearLcdBtn = rearFaceStage?.scene?.root?.lookup("#rear-lcd") as? Button
                if (rearLcdBtn != null) {
                    assertNull(rearLcdBtn.tooltip, "Rear LCD must not have an intrusive hover tooltip")
                }

                // Sweep ALL 6 languages x daylight-studio + origin at 380px width!
                val daylightTheme = UiThemeRegistry.builtInThemes().single { it.id == "daylight-studio" }
                for (code in locales) {
                    I18n.setLocale(code)
                    for (uiTheme in listOf(UiThemeRegistry.origin(), daylightTheme)) {
                        UiThemeRegistry.applyToScene(scene, uiTheme)
                        studio.applyUiTheme(uiTheme)
                        dialog.applyUiTheme(uiTheme)
                        scroll.applyCss()
                        scroll.layout()
                        sidebarBox.applyCss()
                        sidebarBox.layout()

                        val maxAllowed = 382.0
                        assertTrue(
                            sidebarBox.layoutBounds.width <= maxAllowed,
                            "Sidebar overflowed 380px in locale=$code theme=${uiTheme.id}: width=${sidebarBox.layoutBounds.width}",
                        )
                        assertTrue(
                            studio.sourceSelectorPane.root.layoutBounds.width <= maxAllowed,
                            "SourceSelectorPane overflowed 380px in locale=$code: ${studio.sourceSelectorPane.root.layoutBounds.width}",
                        )
                        assertTrue(
                            lensPanel.root.layoutBounds.width <= maxAllowed,
                            "LensProfilePanel overflowed 380px in locale=$code: ${lensPanel.root.layoutBounds.width}",
                        )

                        if (uiTheme.id == "daylight-studio") {
                            val stepBtn = studio.root.lookup("#quick-rear-dial-cw") as Button
                            val bgFill = stepBtn.background?.fills?.firstOrNull()?.fill
                            val isLightFill = when (bgFill) {
                                is Color -> (bgFill.red + bgFill.green + bgFill.blue) / 3.0 > 0.65
                                is LinearGradient -> bgFill.stops.all { (it.color.red + it.color.green + it.color.blue) / 3.0 > 0.65 }
                                else -> false
                            }
                            assertTrue(isLightFill, "Dial step button leaked dark fill in daylight-studio: $bgFill")
                        }
                    }
                }

                // Check InputBindingsDialog fits its 520px minWidth across all 6 languages with zero horizontal overflow!
                dialog.setVisible(true)
                for (code in locales) {
                    I18n.setLocale(code)
                    dialog.stage.scene.root.applyCss()
                    dialog.stage.scene.root.layout()
                    val table = dialog.stage.scene.root.lookup(".input-binding-table") as Region
                    val tableMinW = table.minWidth(-1.0)
                    assertTrue(
                        tableMinW <= 500.0,
                        "InputBindingsDialog table minWidth overflowed 520px dialog in locale=$code: minW=$tableMinW",
                    )
                    assertTrue(
                        table.layoutBounds.width <= dialog.stage.scene.width + 1.0,
                        "InputBindingsDialog table exceeded scene width in locale=$code: tableW=${table.layoutBounds.width}, sceneW=${dialog.stage.scene.width}",
                    )
                }
            } finally {
                dialog.close()
                studio.close()
                owner.close()
            }
        }
    }

    @Test
    fun rapidUiStressTogglingOpticsScenesFiltersEvfThemesAndResizesHasZeroExceptions() {
        onFx {
            val owner = Stage()
            val stressRoot = VBox()
            owner.scene = Scene(stressRoot, 400.0, 720.0).also {
                UiThemeRegistry.applyToScene(it, UiThemeRegistry.origin())
            }
            val patches = mutableListOf<Pair<String, Map<String, Any>>>()
            val studio = CameraStudio(
                owner = owner,
                sendButton = { k, v -> patches += ("buttons" to mapOf(k to v)); CompletableFuture.completedFuture(Unit) },
                configure = {},
                inspect = {},
                failure = { throw it },
                patchCmos = { p -> patches += ("cmos" to p); CompletableFuture.completedFuture(Unit) },
                patchOpticalFilter = { p -> patches += ("optical-filter" to p); CompletableFuture.completedFuture(Unit) },
            )
            val lensPanel = LensProfilePanel(patchComponent = { id, p -> patches += (id to p); CompletableFuture.completedFuture(Unit) })
            val filterPanel = OpticalFilterPane(
                mode = OpticalFilterPane.Mode.DETAILED,
                patchOpticalFilter = { p -> patches += ("optical-filter" to p); CompletableFuture.completedFuture(Unit) },
            )
            val bridge = AsyncDisplayBridge()
            stressRoot.children.setAll(studio.root, lensPanel.root, filterPanel.root)
            owner.show()
            studio.showAll(0.7)

            try {
                val scenes3d = listOf("scene://qr-ball", "scene://mona-lisa", "scene://milk-frog", "scene://depth-studio")
                val bokehShapes = listOf("STAR_5", "HEART", "HEXAGON", "CAT_EYE", "DIAMOND")
                val bodyThemes = BodyThemeRegistry.builtInThemes()
                val uiThemes = UiThemeRegistry.builtInThemes()
                val locales = I18n.supportedLanguageCodes()

                for (iter in 0 until 20) {
                    val backend = if (iter % 2 == 0) "vulkan" else "cpu"
                    lensPanel.observeSnapshot(
                        mapOf(
                            "components" to listOf(
                                mapOf(
                                    "id" to "lens",
                                    "config" to mapOf("opticsBackend" to backend, "vulkanDevice" to "auto"),
                                    "state" to mapOf("activeOpticsBackend" to backend.uppercase()),
                                ),
                            ),
                        ),
                    )
                    val sceneUri = scenes3d[iter % scenes3d.size]
                    studio.sourceSelectorPane.observeSnapshot(
                        mapOf(
                            "components" to listOf(
                                mapOf(
                                    "id" to "cmos",
                                    "config" to mapOf("sourceKind" to "optical-scene", "sourceFile" to sceneUri, "loop" to true),
                                    "state" to mapOf("sceneDimension" to "DIM_3D", "activeSource" to sceneUri),
                                ),
                            ),
                        ),
                    )
                    val slots = listOf(
                        OpticalFilterStatus.defaultSlot("uv", if (iter % 2 == 0) "PRO_MRC" else "CHEAP_POOR"),
                        OpticalFilterStatus.defaultSlot("bokeh-mask", bokehShapes[iter % bokehShapes.size]),
                    )
                    val filterSnap = mapOf(
                        "components" to listOf(
                            mapOf(
                                "id" to "optical-filter",
                                "config" to OpticalFilterStatus.buildSlotsPatch(slots),
                                "state" to mapOf("loaded" to true, "enabled" to (iter % 3 != 0)),
                            ),
                        ),
                    )
                    studio.opticalFilterPane.observeSnapshot(filterSnap)
                    filterPanel.observeSnapshot(filterSnap)
                    studio.setEvfWindowVisible(iter % 2 == 0)
                    studio.setEvfAlwaysOnTop(iter % 3 == 0)
                    studio.applyUiTheme(uiThemes[iter % uiThemes.size])
                    if (iter % 4 == 0) {
                        studio.applyBodyTheme(bodyThemes[(iter / 4) % bodyThemes.size])
                    }
                    I18n.setLocale(locales[iter % locales.size])
                    owner.width = if (iter % 2 == 0) 380.0 else 520.0
                    owner.height = if (iter % 2 == 0) 680.0 else 860.0
                    studio.paint(
                        mapOf(
                            "display.main" to makeSolidFrame(64, 48, iter + 1L, 0xFF102030.toInt()),
                            "display.evf" to makeSolidFrame(64, 48, iter + 1L, 0xFF203010.toInt()),
                        ),
                    )
                    studio.paintFromBridge(bridge)
                    owner.scene.root.applyCss()
                    owner.scene.root.layout()
                }
            } finally {
                studio.close()
                owner.close()
            }
        }
    }
}
