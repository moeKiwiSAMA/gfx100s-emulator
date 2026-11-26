package com.github.nyabanban.fuji.emulator.desktop.theme

import com.github.nyabanban.fuji.emulator.desktop.panels.CameraStudio
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyThemeTest {
    private fun captureWarnings(block: () -> Unit): List<String> =
        ThemeTestLogCapture.captureWarnings(BodyThemeRegistry::class.java, block)

    @Test
    fun allFourBuiltInBodyThemesPassSvgSanitizerAndHitboxValidation() {
        val themes = BodyThemeRegistry.builtInThemes()
        assertEquals(
            listOf("origin", "hasselblad-x2d2", "hasselblad-907x", "compact-rangefinder"),
            themes.map { it.id },
        )
        val expectedCounts = mapOf(
            "origin" to 6,
            "hasselblad-x2d2" to 3,
            "hasselblad-907x" to 3,
            "compact-rangefinder" to 2,
        )
        themes.forEach { theme ->
            assertEquals(expectedCounts[theme.id], theme.panelCount, "Unexpected panel count for ${theme.id}")
            assertEquals(theme.panels.map { it.id }.toSet(), theme.layout.faces.keys)
            theme.panels.forEach { panel ->
                val svg = theme.validateArtwork(panel)
                assertEquals("svg", svg.localName)
            }
        }

        // Verify Hasselblad X2D II signature 5D joystick + displays are wired to real GFX100S bindings.
        val x2d = BodyThemeRegistry.resolveOrFallback("hasselblad-x2d2")
        val x2dControls = x2d.panels.flatMap { it.controls }
        val x2dKeys = x2dControls.mapNotNull { it.buttonKey }.toSet()
        assertTrue(setOf("up", "down", "left", "right", "focusStickPress", "menu", "shutterFull", "shutterHalf", "power").all { it in x2dKeys })
        assertEquals(setOf("display.main", "display.evf", "display.shoulder"), x2dControls.mapNotNull { it.displayPort }.toSet())
    }

    @Test
    fun externalSinglePanelBodyThemeLoadsAndMaliciousSvgFallsBackWithWarn(@TempDir home: Path) {
        val validDir = Files.createDirectories(home.resolve("themes/body/custom-single"))
        Files.writeString(
            validDir.resolve("body-theme.json"),
            """
            {
              "schema": 1,
              "id": "custom-single",
              "name": "Single-Panel Studio",
              "views": [
                {
                  "id": "rear",
                  "title": "背面 / REAR · SINGLE",
                  "svg": "rear.svg",
                  "width": 800,
                  "height": 500,
                  "controls": [
                    { "id": "rear-lcd", "viewId": "rear", "label": "LCD", "labelZh": "主屏", "x": 40, "y": 40, "width": 500, "height": 400, "kind": "display", "displayPort": "display.main" },
                    { "id": "rear-menu", "viewId": "rear", "label": "MENU", "labelZh": "菜单", "x": 600, "y": 200, "width": 80, "height": 60, "kind": "button", "buttonKey": "menu", "interaction": "momentary" }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            validDir.resolve("rear.svg"),
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 500" width="800" height="500">
              <rect x="0" y="0" width="800" height="500" fill="#202020"/>
              <g id="rear-lcd" data-control-id="rear-lcd" data-view-id="rear">
                <rect x="40" y="40" width="500" height="400" fill="#050505"/>
                <rect data-hitbox="true" x="40" y="40" width="500" height="400" fill="none" stroke="none"/>
              </g>
              <g id="rear-menu" data-control-id="rear-menu" data-view-id="rear">
                <rect x="600" y="200" width="80" height="60" fill="#404040"/>
                <rect data-hitbox="true" x="600" y="200" width="80" height="60" fill="none" stroke="none"/>
              </g>
            </svg>
            """.trimIndent(),
        )

        val badDir = Files.createDirectories(home.resolve("themes/body/unsafe-svg"))
        Files.writeString(
            badDir.resolve("body-theme.json"),
            """
            {
              "schema": 1,
              "id": "unsafe-svg",
              "name": "Unsafe SVG",
              "views": [
                {
                  "id": "rear",
                  "title": "Bad",
                  "svg": "rear.svg",
                  "width": 400,
                  "height": 300,
                  "controls": [
                    { "id": "rear-menu", "viewId": "rear", "label": "MENU", "labelZh": "菜单", "x": 10, "y": 10, "width": 50, "height": 50, "kind": "button", "buttonKey": "menu" }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        Files.writeString(
            badDir.resolve("rear.svg"),
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 300"><script>alert(1)</script></svg>""",
        )

        val warnings = captureWarnings {
            val loaded = BodyThemeRegistry.findExact("custom-single", home)
            assertNotNull(loaded)
            assertEquals(1, loaded.panelCount)
            assertFalse(loaded.builtIn)
            assertNull(BodyThemeRegistry.findExact("unsafe-svg", home))
            val fallback = BodyThemeRegistry.resolveOrFallback("unsafe-svg", home)
            assertEquals("origin", fallback.id)
        }
        assertTrue(
            warnings.any { it.contains("[FALLBACK][theme]") },
            "Expected WARN [FALLBACK][theme] when external pack fails validation, got: $warnings",
        )
    }

    @Test
    fun cameraStudioHotSwapsBodyAndUiThemesLiveAcross6To3To3To2Panels(@TempDir home: Path) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val controller = ThemeRuntimeController(home)
        val studioRef = AtomicReference<CameraStudio?>()
        val commands = mutableListOf<Pair<String, Boolean>>()
        val task = FutureTask {
            Platform.setImplicitExit(false)
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder, 360.0, 420.0) }
            val studio = CameraStudio(
                owner = owner,
                sendButton = { key, down ->
                    commands += key to down
                    CompletableFuture.completedFuture(Unit)
                },
                configure = {},
                inspect = {},
                failure = {},
                initialBodyTheme = controller.bodyTheme,
                initialUiTheme = controller.uiTheme,
            )
            studioRef.set(studio)
            holder.children += studio.root
            owner.show()
            val shootingFaces = setOf("rear", "top")
            fun showingFaces() = studio.bodyTheme.layout.faces.keys.filter { studio.faceStage(it)?.isShowing == true }.toSet()
            studio.showDefault()
            assertEquals(shootingFaces, showingFaces())
            assertEquals("gfx-shooting-workspace", studio.state()["layoutMode"])
            val extraFaces = setOf("front", "ports-left", "storage-right", "battery-bottom")
            for (accessory in extraFaces) {
                studio.setFaceVisible(accessory, true)
                assertEquals(shootingFaces + accessory, showingFaces())
                studio.setFaceVisible(accessory, false)
                assertEquals(shootingFaces, showingFaces())
            }
            studio.setFaceVisible("front", true)
            studio.setFaceVisible("storage-right", true)
            studio.setFaceVisible("front", false)
            assertEquals(shootingFaces + "storage-right", showingFaces())
            studio.setFaceVisible("storage-right", false)
            assertEquals("gfx-shooting-workspace", studio.state()["layoutMode"])
            studio.showAll()
            assertEquals(studio.bodyTheme.layout.faces.keys, showingFaces())
            assertEquals("three-column-face-windows", studio.state()["layoutMode"])

            // 1. Default origin = 6 panels
            assertEquals("origin", studio.bodyTheme.id)
            assertEquals(6, studio.state()["createdFaceStageCount"])

            // 2. Hot-switch to Hasselblad X2D II = 3 panels + independent Nordic Minimal UI theme
            val x2dResult = controller.applyTheme(bodyId = "hasselblad-x2d2", uiId = "nordic-minimal")
            studio.applyBodyTheme(x2dResult.bodyTheme)
            studio.applyUiTheme(x2dResult.uiTheme)
            assertEquals("hasselblad-x2d2", studio.bodyTheme.id)
            assertEquals(3, studio.state()["createdFaceStageCount"])
            assertEquals(listOf("rear", "top", "front"), studio.state()["viewIds"])
            assertEquals(x2dResult.uiTheme.stylesheetUrls, owner.scene.stylesheets.toList())
            assertEquals(x2dResult.bodyTheme.layout.faces.keys, showingFaces())

            // Fire X2D II 5D haptic joystick press on the live hot-swapped body!
            studio.observeSnapshot(
                mapOf("components" to listOf(mapOf("id" to "buttons", "state" to PanelCatalog.buttonKeys.associateWith { false }))),
            )
            studio.buttons.getValue("focusStickPress").fireEvent(
                MouseEvent(MouseEvent.MOUSE_PRESSED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null),
            )
            studio.buttons.getValue("focusStickPress").fireEvent(
                MouseEvent(MouseEvent.MOUSE_RELEASED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null),
            )
            assertTrue(("focusStickPress" to true) in commands)
            assertTrue(("focusStickPress" to false) in commands)

            // 3. Hot-switch to Hasselblad 907X (3 panels, NO rear-evf on body -> detached EVF still paints!)
            val h907Result = controller.applyTheme(bodyId = "hasselblad-907x", uiId = "daylight-studio")
            studio.applyBodyTheme(h907Result.bodyTheme)
            studio.applyUiTheme(h907Result.uiTheme)
            assertEquals(3, studio.state()["createdFaceStageCount"])
            studio.paint(mapOf("display.evf" to CameraStudio.Frame(4, 4, 7L, IntArray(16) { 0xFF224466.toInt() })))
            assertEquals(7L, studio.viewfinderStatus().evfSequence)

            // 4. Hot-switch to Compact Rangefinder = 2 panels only
            val rfResult = controller.applyTheme(bodyId = "compact-rangefinder", uiId = "vintage-brass")
            studio.applyBodyTheme(rfResult.bodyTheme)
            studio.applyUiTheme(rfResult.uiTheme)
            assertEquals(2, studio.state()["createdFaceStageCount"])
            assertEquals(listOf("rear", "top"), studio.state()["viewIds"])

            // 5. Switch back to origin = 6 panels restored cleanly + profile.yaml persisted!
            showingFaces().forEach { studio.setFaceVisible(it, false) }
            val backResult = controller.applyTheme(bodyId = "origin", uiId = "olive-field")
            studio.applyBodyTheme(backResult.bodyTheme)
            studio.applyUiTheme(backResult.uiTheme)
            assertEquals(6, studio.state()["createdFaceStageCount"])
            assertEquals(shootingFaces, showingFaces())
            val persisted = UserProfileStore.load(controller.profilePath)
            assertEquals("origin", persisted.bodyTheme)
            assertEquals("olive-field", persisted.uiTheme)
            "ok"
        }
        try {
            Platform.startup(task)
        } catch (_: IllegalStateException) {
            Platform.runLater(task)
        }
        try {
            assertEquals("ok", task.get(30, TimeUnit.SECONDS))
        } finally {
            val close = FutureTask { studioRef.get()?.close(releaseLatching = false) }
            Platform.runLater(close)
            close.get(10, TimeUnit.SECONDS)
        }
    }
}
