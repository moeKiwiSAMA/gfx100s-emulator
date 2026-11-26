package com.github.nyabanban.fuji.emulator.desktop.theme

import com.github.nyabanban.fuji.emulator.desktop.console.CommandResult
import com.github.nyabanban.fuji.emulator.desktop.console.ConsoleBackend
import com.github.nyabanban.fuji.emulator.desktop.console.EmulatorConsole
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.CameraStudio
import com.github.nyabanban.fuji.emulator.desktop.panels.ContinuousMaterial
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelResources
import com.github.nyabanban.fuji.emulator.desktop.panels.SourceSelectorPane
import com.github.nyabanban.fuji.emulator.desktop.panels.SvgArtwork
import com.github.nyabanban.fuji.emulator.runtime.AsyncPerfProfiler
import com.github.nyabanban.fuji.emulator.runtime.ControlServer
import com.github.nyabanban.fuji.emulator.runtime.FirmwareTelemetry
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.api.ControlClient
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.effect.DropShadow
import javafx.scene.image.ImageView
import javafx.scene.layout.StackPane
import javafx.scene.paint.ImagePattern
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle
import javafx.scene.shape.Shape
import javafx.scene.transform.Rotate
import javafx.stage.Stage
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color as AwtColor
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SvgArtworkAndThemeV2Test {
    private fun makeTinyPng(width: Int = 16, height: Int = 16, rgb: Int = 0x336699): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = AwtColor(rgb)
        g.fillRect(0, 0, width, height)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun <T> runOnFx(block: () -> T): T {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val task = FutureTask {
            Platform.setImplicitExit(false)
            block()
        }
        try {
            Platform.startup(task)
        } catch (_: IllegalStateException) {
            Platform.runLater(task)
        }
        return task.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun svgSanitizerAndRendererSupportV2FeaturesAndRejectUnsafeReferences() {
        val validSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 120">
              <defs>
                <pattern id="leather" width="16" height="16" patternUnits="userSpaceOnUse" data-texture="textures/leather.png"/>
                <clipPath id="clip-box"><rect x="10" y="10" width="80" height="80" rx="8"/></clipPath>
                <circle id="bolt" cx="0" cy="0" r="4" fill="#cccccc"/>
              </defs>
              <rect id="textured" x="10" y="10" width="80" height="80" fill="url(#leather)" clip-path="url(#clip-box)" data-effect="shadow" data-shadow-radius="5"/>
              <circle id="knurl" cx="140" cy="60" r="30" fill="none" stroke="#aaaaaa" stroke-width="2" stroke-dasharray="3 4"/>
              <use id="bolt-copy" href="#bolt" x="140" y="60"/>
            </svg>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val root = PanelResources.document(validSvg)
        assertEquals("svg", root.localName)

        // Verify unsafe <use href="https://..."> and path-traversal data-texture are rejected at sanitize time.
        assertFailsWith<IllegalArgumentException> {
            PanelResources.document(
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><use href="https://evil.invalid/a.svg#x"/></svg>"""
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PanelResources.document(
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><defs><pattern id="p" width="16" height="16" patternUnits="userSpaceOnUse" data-texture="../escape.png"/></defs></svg>"""
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }

        // Verify ContinuousMaterial new machined metal kinds work deterministically.
        val brushed = ContinuousMaterial.pixels(ContinuousMaterial.Spec("brushed-metal", 42L))
        val anodized = ContinuousMaterial.pixels(ContinuousMaterial.Spec("anodized", 42L))
        assertEquals(ContinuousMaterial.WIDTH * ContinuousMaterial.HEIGHT, brushed.size)
        assertEquals(ContinuousMaterial.WIDTH * ContinuousMaterial.HEIGHT, anodized.size)
        assertTrue(brushed.any { (it ushr 24) > 0 })
        assertTrue(anodized.any { (it ushr 24) > 0 })

        // Verify JavaFX rendering of clipPath, stroke-dasharray, shadow, <use>, and bitmap material pattern.
        val pngBytes = makeTinyPng(16, 16, 0x443322)
        runOnFx {
            val group = SvgArtwork.render(root) { rel ->
                assertEquals("textures/leather.png", rel)
                pngBytes
            }
            val textured = group.lookup("#svg-textured") as Shape
            assertTrue(textured.fill is ImagePattern, "Expected ImagePattern fill from data-texture")
            assertNotNull(textured.clip, "Expected clipPath node applied")
            assertTrue(textured.effect is DropShadow, "Expected DropShadow from data-effect=\"shadow\"")
            val knurl = group.lookup("#svg-knurl") as Shape
            assertEquals(listOf(3.0, 4.0), knurl.strokeDashArray.toList())
            val boltCopy = group.lookup("#svg-bolt-copy")
            assertNotNull(boltCopy, "Expected <use> rendered node")
        }
    }

    @Test
    fun externalRasterBodyThemeLoadsAndRejectsInvalidRasterPacks(@TempDir home: Path) {
        val okDir = Files.createDirectories(home.resolve("themes/body/raster-studio"))
        Files.write(okDir.resolve("rear.png"), makeTinyPng(120, 80, 0x223344))
        Files.writeString(
            okDir.resolve("body-theme.json"),
            """
            {
              "schema": 2,
              "id": "raster-studio",
              "name": "Raster Prototype Body",
              "description": "Optional raster-artwork body pack.",
              "views": [
                {
                  "id": "rear",
                  "title": "REAR",
                  "artwork": "rear.png",
                  "width": 800,
                  "height": 500,
                  "controls": [
                    { "id": "custom-screen", "kind": "display", "displayPort": "display.main", "cornerRadius": 10, "x": 50, "y": 50, "width": 500, "height": 380, "label": "Main Display" },
                    { "id": "custom-menu", "kind": "button", "buttonKey": "menu", "x": 620, "y": 200, "width": 80, "height": 60, "label": "Menu" }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val badDir = Files.createDirectories(home.resolve("themes/body/bad-raster"))
        Files.writeString(badDir.resolve("rear.png"), "not-a-real-png-header")
        Files.writeString(
            badDir.resolve("body-theme.json"),
            """
            {
              "schema": 2,
              "id": "bad-raster",
              "name": "Corrupt Raster",
              "views": [
                {
                  "id": "rear",
                  "title": "REAR",
                  "artwork": "rear.png",
                  "width": 800,
                  "height": 500,
                  "controls": [
                    { "id": "custom-menu", "kind": "button", "buttonKey": "menu", "x": 10, "y": 10, "width": 60, "height": 60, "label": "Menu" }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val loaded = BodyThemeRegistry.findExact("raster-studio", home)
        assertNotNull(loaded)
        assertTrue(loaded.panels.single().isRasterArtwork())
        assertNull(BodyThemeRegistry.findExact("bad-raster", home))

        runOnFx {
            val rendered = loaded.renderPanelArtwork(loaded.panels.single())
            val iv = rendered.children.single() as ImageView
            assertEquals(800.0, iv.fitWidth)
            assertEquals(500.0, iv.fitHeight)
        }
    }

    @Test
    fun uiThemeV2ManifestPackLoadsAndRejectsTraversalAndImport(@TempDir home: Path) {
        val packDir = Files.createDirectories(home.resolve("themes/ui/tokyo-indigo"))
        Files.writeString(
            packDir.resolve("theme.css"),
            ".root { -fuji-backdrop: #111827; -fuji-accent: #6366F1; }\n",
        )
        Files.writeString(
            packDir.resolve("ui-theme.json"),
            """
            {
              "schema": 2,
              "id": "tokyo-indigo",
              "name": "Tokyo Indigo",
              "description": "Deep indigo darkroom chrome.",
              "author": "Studio Author",
              "version": "2.1",
              "isDark": true,
              "fontFamily": "Inter",
              "recommendedBodyTheme": "hasselblad-x2d2",
              "css": "theme.css",
              "i18n": {
                "names": { "zh_CN": "东京靛蓝", "ja_JP": "東京インディゴ" },
                "descriptions": { "zh_CN": "深靛蓝暗房界面。", "ja_JP": "深いインディゴの暗室テーマ。" }
              }
            }
            """.trimIndent(),
        )

        val escapeDir = Files.createDirectories(home.resolve("themes/ui/bad-escape"))
        Files.writeString(
            escapeDir.resolve("ui-theme.json"),
            """{"schema":2,"id":"bad-escape","name":"Escape","css":"../escape.css"}""",
        )

        val loaded = UiThemeRegistry.findExact("tokyo-indigo", home)
        assertNotNull(loaded)
        assertFalse(loaded.builtIn)
        assertEquals("Studio Author", loaded.author)
        assertEquals("2.1", loaded.version)
        assertEquals("#111827", loaded.backdropHex)
        assertEquals("hasselblad-x2d2", loaded.recommendedBodyTheme)
        assertEquals("Tokyo Indigo", loaded.localizedName("en"))
        assertEquals("Deep indigo darkroom chrome.", loaded.localizedDescription("en"))
        assertEquals("Deep indigo darkroom chrome.", loaded.localizedDescription("ko-KR"))
        assertNotEquals(loaded.localizedDescription("en"), loaded.localizedDescription("zh_CN"))
        assertNotEquals(loaded.localizedDescription("en"), loaded.localizedDescription("ja_JP"))
        assertNull(UiThemeRegistry.findExact("bad-escape", home))
    }

    @Test
    fun allBuiltInThemesSupportSixLanguagesWithStrictEnglishFallback() {
        val previous = I18n.currentLocale
        try {
            // Even when global GUI locale is zh_CN, requesting an unsupported locale like ko-KR MUST fall back to English!
            I18n.setLocale(I18n.CODE_ZH_CN)
            val sixLocales = listOf("en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES")

            BodyThemeRegistry.builtInThemes().forEach { body ->
                val enName = body.localizedName("en")
                val enDesc = body.localizedDescription("en")
                assertTrue(enName.isNotBlank())
                assertTrue(enDesc.isNotBlank())
                sixLocales.forEach { loc ->
                    assertTrue(body.localizedName(loc).isNotBlank(), "Blank name for ${body.id} @ $loc")
                    assertTrue(body.localizedDescription(loc).isNotBlank(), "Blank desc for ${body.id} @ $loc")
                }
                assertNotEquals(enDesc, body.localizedDescription("zh_CN"), "Expected distinct zh_CN desc for ${body.id}")
                assertNotEquals(enDesc, body.localizedDescription("ja_JP"), "Expected distinct ja_JP desc for ${body.id}")
                assertNotEquals(enDesc, body.localizedDescription("de_DE"), "Expected distinct de_DE desc for ${body.id}")
                assertNotEquals(enDesc, body.localizedDescription("fr_FR"), "Expected distinct fr_FR desc for ${body.id}")
                assertNotEquals(enDesc, body.localizedDescription("es_ES"), "Expected distinct es_ES desc for ${body.id}")
                assertEquals(enName, body.localizedName("ko-KR"), "Unsupported locale must fall back to English name")
                assertEquals(enDesc, body.localizedDescription("ko-KR"), "Unsupported locale must fall back to English desc")
            }

            UiThemeRegistry.builtInThemes().forEach { ui ->
                val enName = ui.localizedName("en")
                val enDesc = ui.localizedDescription("en")
                assertTrue(enName.isNotBlank())
                assertTrue(enDesc.isNotBlank())
                sixLocales.forEach { loc ->
                    assertTrue(ui.localizedName(loc).isNotBlank(), "Blank name for ${ui.id} @ $loc")
                    assertTrue(ui.localizedDescription(loc).isNotBlank(), "Blank desc for ${ui.id} @ $loc")
                }
                assertNotEquals(enDesc, ui.localizedDescription("zh_CN"), "Expected distinct zh_CN desc for ${ui.id}")
                assertNotEquals(enDesc, ui.localizedDescription("ja_JP"), "Expected distinct ja_JP desc for ${ui.id}")
                assertEquals(enName, ui.localizedName("ko-KR"), "Unsupported locale must fall back to English name")
                assertEquals(enDesc, ui.localizedDescription("ko-KR"), "Unsupported locale must fall back to English desc")
            }
        } finally {
            I18n.setLocale(previous)
        }
    }

    @Test
    fun themeRuntimeControllerSupportsMulticastListenersAndHotReload(@TempDir home: Path) {
        val controller = ThemeRuntimeController(home)
        val countA = AtomicInteger(0)
        val countB = AtomicInteger(0)
        val handleA = controller.addThemeListener { _, _, _ -> countA.incrementAndGet() }
        val handleB = controller.addThemeListener { _, _, _ -> countB.incrementAndGet() }

        controller.applyTheme(uiId = "daylight-studio")
        assertEquals(1, countA.get())
        assertEquals(1, countB.get())

        // Add a brand new external UI theme on disk and hot-reload without restarting.
        val uiDir = Files.createDirectories(home.resolve("themes/ui"))
        Files.writeString(uiDir.resolve("fresh-amber.css"), ".root { -fuji-backdrop: #1C1610; }\n")
        val r1 = controller.reloadThemes()
        assertEquals(1L, r1.reloadGeneration)
        assertTrue(controller.availableUiThemes().any { it.id == "fresh-amber" })
        assertEquals(2, countA.get())
        assertEquals(2, countB.get())

        // Unsubscribe A, switch to external theme, delete it on disk, and reload -> clean fallback to origin!
        handleA.close()
        controller.applyTheme(uiId = "fresh-amber")
        assertEquals("fresh-amber", controller.uiTheme.id)
        assertEquals(2, countA.get())
        assertEquals(3, countB.get())

        Files.delete(uiDir.resolve("fresh-amber.css"))
        val r2 = controller.reloadThemes()
        assertEquals(2L, r2.reloadGeneration)
        assertEquals("origin", r2.uiTheme.id)
        assertEquals(4, countB.get())
        handleB.close()
    }

    @Test
    fun cameraStudioSemanticDispatchDynamicStatesAndDisplayClipping(@TempDir home: Path) {
        val packDir = Files.createDirectories(home.resolve("themes/body/semantic-v2"))
        Files.writeString(
            packDir.resolve("body-theme.json"),
            """
            {
              "schema": 2,
              "id": "semantic-v2",
              "name": "Semantic V2 Studio",
              "views": [
                {
                  "id": "rear",
                  "title": "REAR",
                  "svg": "rear.svg",
                  "width": 600,
                  "height": 400,
                  "dynamicStates": [
                    {
                      "id": "dial-rotor",
                      "mode": "rotate-map",
                      "componentId": "soc-adc",
                      "settingKey": "modeDial",
                      "pivotX": 500,
                      "pivotY": 100,
                      "angleMap": { "P": -45, "M": 45 }
                    },
                    {
                      "id": "power-led",
                      "mode": "led-binding",
                      "componentId": "battery",
                      "settingKey": "charging",
                      "activeOpacity": 1.0,
                      "inactiveOpacity": 0.1
                    }
                  ],
                  "controls": [
                    { "id": "primary-screen", "kind": "display", "displayPort": "display.main", "cornerRadius": 12, "x": 40, "y": 40, "width": 320, "height": 240, "label": "Primary Screen" },
                    { "id": "round-evf", "kind": "display", "displayPort": "display.evf", "clipShape": "circle", "x": 400, "y": 40, "width": 70, "height": 70, "label": "Round EVF" },
                    { "id": "custom-vm-btn", "role": "view-mode", "kind": "button", "x": 400, "y": 140, "width": 70, "height": 40, "label": "VM" }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            packDir.resolve("rear.svg"),
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 400">
              <rect x="0" y="0" width="600" height="400" fill="#222222"/>
              <g id="dial-rotor"><line x1="500" y1="80" x2="500" y2="100" stroke="#ffffff" stroke-width="3"/></g>
              <circle id="power-led" cx="540" cy="100" r="6" fill="#00ff88"/>
              <g id="primary-screen" data-control-id="primary-screen" data-view-id="rear">
                <rect x="40" y="40" width="320" height="240" fill="#000000"/>
                <rect data-hitbox="true" x="40" y="40" width="320" height="240" fill="none" stroke="none"/>
              </g>
              <g id="round-evf" data-control-id="round-evf" data-view-id="rear">
                <circle cx="435" cy="75" r="35" fill="#000000"/>
                <rect data-hitbox="true" x="400" y="40" width="70" height="70" fill="none" stroke="none"/>
              </g>
              <g id="custom-vm-btn" data-control-id="custom-vm-btn" data-view-id="rear">
                <rect x="400" y="140" width="70" height="40" fill="#444444"/>
                <rect data-hitbox="true" x="400" y="140" width="70" height="40" fill="none" stroke="none"/>
              </g>
            </svg>
            """.trimIndent(),
        )
        val semanticTheme = requireNotNull(BodyThemeRegistry.findExact("semantic-v2", home))
        val studioRef = AtomicReference<CameraStudio?>()
        try {
            runOnFx {
                val owner = Stage().apply { scene = Scene(StackPane(), 360.0, 420.0) }
                val studio = CameraStudio(
                    owner = owner,
                    sendButton = { _, _ -> CompletableFuture.completedFuture(Unit) },
                    configure = {},
                    inspect = {},
                    failure = {},
                    initialBodyTheme = semanticTheme,
                    initialUiTheme = UiThemeRegistry.origin(),
                )
                studioRef.set(studio)
                // Verify semantic display dispatch works even with custom control IDs!
                studio.paint(
                    mapOf(
                        "display.main" to CameraStudio.Frame(4, 4, 11L, IntArray(16) { 0xFF112233.toInt() }),
                        "display.evf" to CameraStudio.Frame(4, 4, 22L, IntArray(16) { 0xFF445566.toInt() }),
                    ),
                )
                assertEquals(11L, studio.viewfinderStatus().lcdSequence)
                assertEquals(22L, studio.viewfinderStatus().evfSequence)

                // Verify dynamicStates: rotate-map & led-binding update live on snapshot!
                studio.observeSnapshot(
                    mapOf(
                        "components" to listOf(
                            mapOf("id" to "soc-adc", "state" to mapOf("modeDial" to "M")),
                            mapOf("id" to "battery", "state" to mapOf("charging" to true)),
                        ),
                    ),
                )
                val rearStage = requireNotNull(studio.faceStage("rear"))
                val dialNode = rearStage.scene.root.lookup("#svg-dial-rotor")
                val ledNode = rearStage.scene.root.lookup("#svg-power-led")
                assertNotNull(dialNode)
                assertNotNull(ledNode)
                val rot = dialNode.transforms.filterIsInstance<Rotate>().firstOrNull()
                assertNotNull(rot)
                assertEquals(45.0, rot.angle, 0.001)
                assertEquals(1.0, ledNode.opacity, 0.001)

                // Verify cornerRadius & circle clipShape applied on display viewports and display hotspots suppress white highlight!
                val mainHotspot = rearStage.scene.root.lookup("#inspect-primary-screen")
                val evfHotspot = rearStage.scene.root.lookup("#inspect-round-evf")
                val vmHotspot = rearStage.scene.root.lookup("#inspect-custom-vm-btn")
                assertTrue(mainHotspot.parent.clip is Rectangle)
                assertEquals(24.0, (mainHotspot.parent.clip as Rectangle).arcWidth, 0.001)
                assertTrue(evfHotspot.parent.clip is Circle)
                assertTrue("studio-display-hotspot" in mainHotspot.styleClass, "Main display hotspot must carry studio-display-hotspot")
                assertTrue("studio-display-hotspot" in evfHotspot.styleClass, "EVF display hotspot must carry studio-display-hotspot")
                assertFalse("studio-display-hotspot" in vmHotspot.styleClass, "Non-display button must not carry studio-display-hotspot")
            }
        } finally {
            studioRef.get()?.let { s -> runOnFx { s.close(releaseLatching = false) } }
        }
    }

    @Test
    fun displayHotspotsStayTransparentAcrossInteractiveStatesAndSharedAssetsResolveSafely() {
        val css = requireNotNull(CameraStudio::class.java.getResourceAsStream("/panel.css"))
            .use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
        val blockStart = css.indexOf(".studio-hotspot.studio-display-hotspot")
        assertTrue(blockStart >= 0, "panel.css must define .studio-hotspot.studio-display-hotspot rule")
        val blockEnd = css.indexOf('}', blockStart)
        assertTrue(blockEnd > blockStart, "panel.css .studio-display-hotspot rule must be closed")
        val ruleBlock = css.substring(blockStart, blockEnd + 1)
        for (selector in listOf(
            ".studio-hotspot.studio-display-hotspot,",
            ".studio-hotspot.studio-display-hotspot:hover,",
            ".studio-hotspot.studio-display-hotspot:pressed,",
            ".studio-hotspot.studio-display-hotspot:selected,",
            ".studio-hotspot.studio-display-hotspot:focused,",
            ".studio-hotspot.studio-display-hotspot:focus-visible",
        )) {
            assertTrue(ruleBlock.contains(selector), "Expected selector '$selector' in panel.css rule:\n$ruleBlock")
        }
        for (prop in listOf(
            "-fx-background-color: transparent;",
            "-fx-border-color: transparent;",
            "-fx-focus-color: transparent;",
            "-fx-faint-focus-color: transparent;",
        )) {
            assertTrue(ruleBlock.contains(prop), "Expected property '$prop' in panel.css rule:\n$ruleBlock")
        }

        // Verify deduplicated shared body textures resolve across built-in themes while traversal stays rejected.
        assertTrue(PanelResources.isSafeRelativeAssetPath("shared/leatherette.png"))
        assertFalse(PanelResources.isSafeRelativeAssetPath("shared/../escape.png"))
        assertFalse(PanelResources.isSafeRelativeAssetPath("../shared/leatherette.png"))
        val origin = BodyThemeRegistry.origin()
        val h907 = requireNotNull(BodyThemeRegistry.findExact("hasselblad-907x"))
        val x2d2 = requireNotNull(BodyThemeRegistry.findExact("hasselblad-x2d2"))
        val rf = requireNotNull(BodyThemeRegistry.findExact("compact-rangefinder"))
        assertTrue(origin.loadAssetBytes("shared/leatherette.png").isNotEmpty())
        assertTrue(rf.loadAssetBytes("shared/brushed-chrome.png").isNotEmpty())
        assertTrue(h907.loadAssetBytes("shared/xcd-glass.png").isNotEmpty())
        assertTrue(x2d2.loadAssetBytes("shared/xcd-glass.png").isNotEmpty())
        assertTrue(x2d2.loadAssetBytes("textures/leatherette.png").isNotEmpty())
    }

    @Test
    fun sourceSelectorPaneUsesSemanticCssClassesWithoutDarkInlineBleed() {
        runOnFx {
            val pane = SourceSelectorPane(ownerWindow = { null }, patchCmos = null, onStatus = {}, onError = {})
            val scene = Scene(pane.root, 360.0, 500.0)
            UiThemeRegistry.applyToScene(scene, UiThemeRegistry.resolveOrFallback("daylight-studio"))
            scene.root.applyCss()
            val badge3d = pane.root.lookup("#source-3d-depth-badge")
            val hint2d = pane.root.lookup("#source-2d-distance-hint")
            val mode3d = pane.root.lookup("#source-mode-3d")
            val mode2d = pane.root.lookup("#source-mode-2d")
            assertNotNull(badge3d)
            assertNotNull(hint2d)
            assertTrue("source-badge-3d" in badge3d.styleClass)
            assertTrue("source-badge-2d" in hint2d.styleClass)
            assertTrue("source-mode-btn" in mode3d.styleClass)
            assertTrue("source-mode-btn" in mode2d.styleClass)
            assertTrue(badge3d.style.isNullOrBlank(), "Expected zero inline dark style on 3D badge, got: ${badge3d.style}")
            assertTrue(hint2d.style.isNullOrBlank(), "Expected zero inline dark style on 2D hint, got: ${hint2d.style}")
            assertTrue(mode3d.style.isNullOrBlank(), "Expected zero inline dark style on mode3d toggle, got: ${mode3d.style}")
            assertTrue(mode2d.style.isNullOrBlank(), "Expected zero inline dark style on mode2d toggle, got: ${mode2d.style}")
        }
    }

    @Test
    fun allBuiltInBodyThemesRenderNonEmptyArtworkOnJavaFx() {
        runOnFx {
            BodyThemeRegistry.builtInThemes().forEach { theme ->
                theme.panels.forEach { panel ->
                    val group = theme.renderPanelArtwork(panel)
                    assertTrue(group.children.isNotEmpty(), "Rendered SVG for ${theme.id}/${panel.id} is empty")
                    val bounds = group.boundsInLocal
                    assertTrue(bounds.width > 100.0 && bounds.height > 100.0, "Collapsed bounds for ${theme.id}/${panel.id}: $bounds")
                }
            }
        }
    }

    @Test
    fun embeddedSvgImageAndMechanicalModeDialAndFocusLeverRotorsWorkAcrossThemes() {
        val pngBytes = makeTinyPng(32, 32, 0x336699)
        val svgWithImg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">
              <image id="mount-img" href="artwork/mount.png" x="20" y="20" width="160" height="160"/>
            </svg>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val root = PanelResources.document(svgWithImg)
        assertFailsWith<IllegalArgumentException> {
            PanelResources.document(
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><image href="../escape.png" x="0" y="0" width="50" height="50"/></svg>"""
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PanelResources.document(
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><image href="https://evil.invalid/a.png" x="0" y="0" width="50" height="50"/></svg>"""
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }
        runOnFx {
            val group = SvgArtwork.render(root) { rel ->
                assertEquals("artwork/mount.png", rel)
                pngBytes
            }
            val iv = group.lookup("#svg-mount-img") as ImageView
            assertEquals(20.0, iv.x, 0.001)
            assertEquals(160.0, iv.fitWidth, 0.001)
            assertTrue(iv.isSmooth)
            assertTrue(iv.isMouseTransparent)
        }

        for (theme in BodyThemeRegistry.builtInThemes()) {
            val topPanel = theme.panels.single { it.id == "top" }
            val rotors = topPanel.dynamicStates.filter { it.mode == "rotate-map" && it.settingKey == "modeDial" }
            val step = when (theme.id) {
                "origin" -> 36.0
                "compact-rangefinder" -> -36.0
                else -> {
                    assertTrue(rotors.isEmpty(), "${theme.name} has no mechanical exposure dial")
                    continue
                }
            }
            val stops = rotors.single().angleMap
            listOf("P", "S", "A", "M").forEachIndexed { index, mode ->
                assertEquals(if (index == 0) 0.0 else index * step, stops[mode])
            }
        }

        val studioRef = AtomicReference<CameraStudio?>()
        try {
            runOnFx {
                val owner = Stage().apply { scene = Scene(StackPane(), 360.0, 420.0) }
                val studio = CameraStudio(
                    owner = owner,
                    sendButton = { _, _ -> CompletableFuture.completedFuture(Unit) },
                    configure = {},
                    inspect = {},
                    failure = {},
                    initialBodyTheme = BodyThemeRegistry.origin(),
                    initialUiTheme = UiThemeRegistry.origin(),
                )
                studioRef.set(studio)
                // Observe modeDial=M and focusMode=MF on origin GFX100S -> real physical rotors move, no clunky focus badge.
                studio.observeSnapshot(
                    mapOf(
                        "components" to listOf(
                            mapOf("id" to "soc-adc", "state" to mapOf("modeDial" to "M")),
                            mapOf("id" to "soc-gpio", "state" to mapOf("focusMode" to "MF")),
                        ),
                    ),
                )
                val topStage = requireNotNull(studio.faceStage("top"))
                val rearStage = requireNotNull(studio.faceStage("rear"))
                val gfxModeRotor = topStage.scene.root.lookup("#svg-gfx-mode-dial-rotor")
                val gfxFocusRotor = rearStage.scene.root.lookup("#svg-gfx-focus-lever-rotor")
                assertNotNull(gfxModeRotor)
                assertNotNull(gfxFocusRotor)
                assertEquals(108.0, gfxModeRotor.transforms.filterIsInstance<Rotate>().first().angle, 0.01)
                assertEquals(-25.9, gfxFocusRotor.transforms.filterIsInstance<Rotate>().first().angle, 0.01)
                assertNull(rearStage.scene.root.lookup("#body-focus-mode-indicator"), "Floating focus badge must be hidden when real rotor exists")

                // The X2D selects exposure on the M button and exposes GFX focus modes on the top auxiliary panel.
                studio.applyBodyTheme(BodyThemeRegistry.builtInThemes().single { it.id == "hasselblad-x2d2" })
                studio.observeSnapshot(
                    mapOf(
                        "components" to listOf(
                            mapOf("id" to "soc-adc", "state" to mapOf("modeDial" to "S")),
                            mapOf("id" to "soc-gpio", "state" to mapOf("focusMode" to "AF-S")),
                        ),
                    ),
                )
                val x2dTop = requireNotNull(studio.faceStage("top"))
                val x2dRear = requireNotNull(studio.faceStage("rear"))
                assertNull(x2dTop.scene.root.lookup("#svg-x2d-mode-dial-rotor"))
                assertNull(x2dRear.scene.root.lookup("#svg-x2d-focus-lever-rotor"))
                assertNull(x2dRear.scene.root.lookup("#button-focusMode"))
                assertNotNull(x2dTop.scene.root.lookup("#button-focusMode"))
                assertNull(x2dRear.scene.root.lookup("#body-focus-mode-indicator"))
            }
        } finally {
            studioRef.get()?.let { s -> runOnFx { s.close(releaseLatching = false) } }
        }
    }

    @Test
    fun openingBodyCoversRevealsTheirPortsAndMovesTheHandleToTheDoor() = runOnFx {
        val studio = CameraStudio(
            owner = Stage().apply { scene = Scene(StackPane(), 360.0, 420.0) },
            sendButton = { _, _ -> error("mechanical covers do not dispatch electrical input") },
            configure = {}, inspect = {}, failure = {},
        )
        try {
            val panels = BodyThemeRegistry.origin().panels
            assertEquals(5, panels.sumOf { it.covers.size })
            for (panel in panels.filter { it.covers.isNotEmpty() }) {
                val root = requireNotNull(studio.faceStage(panel.id)).scene.root
                for (cover in panel.covers) {
                    val handle = root.lookup("#inspect-" + cover.id) as javafx.scene.control.Button
                    val opener = cover.latch?.let { root.lookup("#inspect-$it") as javafx.scene.control.Button } ?: handle
                    val closedPosition = handle.layoutX to handle.layoutY
                    val closed = assertNotNull(root.lookup("#svg-${cover.id}-closed"))
                    val open = assertNotNull(root.lookup("#svg-${cover.id}-open"))
                    val inside = cover.inside.map { assertNotNull(root.lookup("#inspect-$it")) }
                    assertTrue(closed.isVisible && !open.isVisible && inside.none { it.isVisible }, cover.id)
                    opener.fire()
                    assertTrue(!closed.isVisible && open.isVisible && inside.all { it.isVisible }, cover.id)
                    assertEquals(cover.openHitbox.minX to cover.openHitbox.minY, handle.layoutX to handle.layoutY)
                    assertEquals(cover.openHitbox.width to cover.openHitbox.height, handle.width to handle.height)
                    handle.fire()
                    assertTrue(closed.isVisible && !open.isVisible && inside.none { it.isVisible }, cover.id)
                    assertEquals(closedPosition, handle.layoutX to handle.layoutY)
                }
            }
        } finally {
            studio.close(releaseLatching = false)
        }
    }

    @Test
    fun hasselbladFrontFollowsMountOnHotSwapAndDetach() = runOnFx {
        val studio = CameraStudio(
            owner = Stage().apply { scene = Scene(StackPane(), 360.0, 420.0) },
            sendButton = { _, _ -> CompletableFuture.completedFuture(Unit) },
            configure = {}, inspect = {}, failure = {},
        )
        fun observeMount(attached: Boolean) = studio.observeSnapshot(
            mapOf("components" to listOf(mapOf("id" to "mount", "state" to mapOf("attached" to attached)))),
        )
        try {
            for (theme in BodyThemeRegistry.builtInThemes().filter { it.id.startsWith("hasselblad-") }) {
                observeMount(true)
                studio.applyBodyTheme(theme)
                val front = theme.panels.single { it.id == "front" }
                val layers = front.layers.filter { it.componentId == "mount" && it.settingKey == "attached" }
                val root = requireNotNull(studio.faceStage("front")).scene.root
                val lens = assertNotNull(root.lookup("#svg-" + layers.single { it.expected }.id))
                val bareMount = assertNotNull(root.lookup("#svg-" + layers.single { !it.expected }.id))
                assertTrue(lens.isVisible && !bareMount.isVisible, theme.id)
                observeMount(false)
                assertTrue(!lens.isVisible && bareMount.isVisible, theme.id)
                observeMount(true)
                assertTrue(lens.isVisible && !bareMount.isVisible, theme.id)
            }
        } finally {
            studio.close(releaseLatching = false)
        }
    }

    @Test
    fun x2dTopFocusSegmentsSelectTheNamedModeAndFollowReadback() = runOnFx {
        val theme = BodyThemeRegistry.builtInThemes().single { it.id == "hasselblad-x2d2" }
        val owner = Stage().apply { scene = Scene(StackPane(), 360.0, 420.0) }
        val writes = mutableListOf<Triple<String, String, Any>>()
        val studio = CameraStudio(owner, { _, _ -> CompletableFuture.completedFuture(Unit) }, {}, {}, {},
            initialBodyTheme = theme, sendSettingValue = { component, key, value ->
                writes += Triple(component, key, value)
                CompletableFuture.completedFuture(Unit)
            })
        try {
            val top = requireNotNull(studio.faceStage("top")).scene
            val rear = requireNotNull(studio.faceStage("rear")).scene
            fun segment(id: String) = top.lookup("#focus-mode-segment-$id") as javafx.scene.control.ToggleButton
            assertNull(rear.lookup("#button-focusMode"))
            assertNotNull(rear.lookup("#button-afOn"))
            segment("mf").fire()
            segment("af-c").fire()
            assertEquals(listOf(Triple<String, String, Any>("soc-gpio", "focusMode", "MF"),
                Triple<String, String, Any>("soc-gpio", "focusMode", "AF-C")), writes)
            assertTrue(segment("af-c").isSelected)
            studio.observeSnapshot(mapOf("components" to listOf(
                mapOf("id" to "soc-gpio", "state" to mapOf("focusMode" to "AF-S", "effectiveFocusMode" to "AF-S")))))
            assertTrue(segment("af-s").isSelected)
        } finally {
            studio.close(releaseLatching = false)
        }
    }

    @Test
    fun physicalExposureButtonWaitsForAChoiceAndSendsItOnce() = runOnFx {
        val theme = BodyThemeRegistry.builtInThemes().single { it.id == "hasselblad-x2d2" }
        val panel = theme.panels.single { it.controls.any { control -> control.role == "exposure-mode-button" } }
        val modeButton = panel.controls.single { it.role == "exposure-mode-button" }
        val writes = mutableListOf<Triple<String, String, Any>>()
        val owner = Stage().apply { scene = Scene(StackPane(), 360.0, 420.0) }
        val studio = CameraStudio(owner, { _, _ -> CompletableFuture.completedFuture(Unit) }, {}, {}, {},
            initialBodyTheme = theme, sendSettingValue = { component, key, value ->
                writes += Triple(component, key, value)
                CompletableFuture.completedFuture(Unit)
            })
        try {
            studio.setFaceVisible(panel.id, true)
            val stage = requireNotNull(studio.faceStage(panel.id))
            val control = stage.scene.lookup("#inspect-${modeButton.id}") as javafx.scene.control.Button
            assertNull(stage.scene.lookup("#body-mode-dial-indicator"))
            control.fire()
            assertTrue(writes.isEmpty())
            val picker = javafx.stage.Window.getWindows().filterIsInstance<javafx.scene.control.ContextMenu>()
                .single { it.isShowing && it.ownerWindow === stage }
            picker.items.single { it.text == "A" }.fire()
            assertEquals(listOf(Triple<String, String, Any>("soc-adc", "modeDial", "A")), writes)
            picker.hide()
        } finally {
            studio.close(releaseLatching = false)
        }
    }

    @Test
    fun httpApiAndConsoleThemeReloadEndpointsWorkEndToEnd(@TempDir home: Path) {
        val controller = ThemeRuntimeController(home)
        val dummyControl = object : ControlClient {
            override fun snapshot(): CompletableFuture<Map<String, Any?>> =
                CompletableFuture.completedFuture(mapOf<String, Any?>("components" to emptyList<Any>()))
            override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> =
                CompletableFuture.completedFuture(emptyMap<String, Any?>())
        }
        ControlServer(
            control = dummyControl,
            port = 0,
            themeStatus = { controller.statusMap() },
            themeUpdate = { body ->
                controller.applyTheme(bodyId = body["body"] as? String, uiId = body["ui"] as? String)
                controller.statusMap()
            },
            themeReload = {
                val r = controller.reloadThemes()
                controller.statusMap() + mapOf("reloaded" to true, "reloadGeneration" to r.reloadGeneration)
            },
        ).use { server ->
            val client = HttpClient.newHttpClient()
            val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.address.port}/api/theme/reload"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, resp.statusCode())
            val parsed = Formats.json.readTree(resp.body())
            assertTrue(parsed.path("reloaded").asBoolean())
            assertEquals(1L, parsed.path("reloadGeneration").asLong())
        }

        // Verify /theme reload console command
        val telemetry = FirmwareTelemetry()
        val outBuf = ByteArrayOutputStream()
        val console = EmulatorConsole(
            backend = object : ConsoleBackend {
                override val home: Path = home
                override val telemetry: FirmwareTelemetry = telemetry
                override val profiler: AsyncPerfProfiler = AsyncPerfProfiler(telemetry)
                override fun isPaused() = false
                override fun setPaused(paused: Boolean) = paused
                override fun isRunning() = true
                override fun requestBoot() = "booted"
                override fun requestStop() = "stopped"
                override fun listComponents() = emptyList<String>()
                override fun componentState(componentId: String) = null
                override fun patchComponent(componentId: String, changes: Map<String, Any>) = emptyMap<String, Any?>()
                override fun captureDisplay(portName: String) = null
                override fun themeStatus() = controller.statusMap()
                override fun applyTheme(bodyId: String?, uiId: String?): Map<String, Any?> {
                    controller.applyTheme(bodyId, uiId)
                    return controller.statusMap()
                }
                override fun reloadThemes(): Map<String, Any?> {
                    val r = controller.reloadThemes()
                    return controller.statusMap() + mapOf("reloaded" to true, "reloadGeneration" to r.reloadGeneration)
                }
            },
            output = outBuf,
            interactivePrompt = false,
        )
        val keepRunning = console.executeLine("/theme reload")
        assertTrue(keepRunning)
        val outputText = outBuf.toString(StandardCharsets.UTF_8)
        assertTrue(outputText.contains("Reloaded themes") && outputText.contains("gen=2"), "Unexpected /theme reload output: $outputText")
    }
}
