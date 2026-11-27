package com.github.nyabanban.fuji.emulator.desktop.theme

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiThemeTest {
    private fun captureWarnings(block: () -> Unit): List<String> =
        ThemeTestLogCapture.captureWarnings(UiThemeRegistry::class.java, block)

    @Test
    fun allFiveBuiltInUiThemesResolveCleanly() {
        val themes = UiThemeRegistry.builtInThemes()
        assertEquals(
            listOf("origin", "nordic-minimal", "daylight-studio", "vintage-brass", "olive-field"),
            themes.map { it.id },
        )
        themes.forEach { theme ->
            assertTrue(theme.builtIn)
            assertTrue(theme.stylesheetUrls.isNotEmpty())
            assertTrue(theme.stylesheetUrls.first().endsWith("/panel.css"))
            if (theme.id == "origin") {
                assertEquals(1, theme.stylesheetUrls.size)
            } else {
                assertEquals(2, theme.stylesheetUrls.size)
                assertTrue(theme.stylesheetUrls[1].endsWith("/themes/ui/${theme.id}.css"))
            }
        }
    }

    @Test
    fun allBuiltInUiThemesStyleMenuButtonAndContextMenuPopupsConsistently() {
        val resourcePaths = listOf(
            "/panel.css",
            "/themes/ui/daylight-studio.css",
            "/themes/ui/nordic-minimal.css",
            "/themes/ui/olive-field.css",
            "/themes/ui/vintage-brass.css",
        )
        for (path in resourcePaths) {
            val css = requireNotNull(UiThemeRegistry::class.java.getResourceAsStream(path)) { "Missing $path" }
                .bufferedReader().use { it.readText() }
            assertTrue(css.contains(".menu-button.studio-header-btn-secondary > .label"), "$path must style MenuButton label")
            assertTrue(css.contains(".menu-button.studio-header-btn-secondary > .arrow-button > .arrow"), "$path must style MenuButton arrow")
            assertTrue(css.contains(".context-menu {"), "$path must style .context-menu popup")
            assertTrue(css.contains(".context-menu .menu-item:hover"), "$path must style .context-menu .menu-item:hover")
        }
    }

    @Test
    fun externalUserCssDiscoveredAndUnsafeCssRejectedWithWarn(@TempDir home: Path) {
        val uiDir = Files.createDirectories(home.resolve("themes/ui"))
        Files.writeString(
            uiDir.resolve("custom-slate.css"),
            "/* -fuji-backdrop: #0A141E; */\n.studio-shell { -fx-background-color: #0A141E; }\n",
        )
        Files.writeString(
            uiDir.resolve("evil-import.css"),
            "@import url('https://example.invalid/bad.css');\n.root {}\n",
        )

        lateinit var discovered: List<UiTheme>
        val warnings = captureWarnings {
            discovered = UiThemeRegistry.discoverAll(home)
        }
        val custom = discovered.firstOrNull { it.id == "custom-slate" }
        assertNotNull(custom)
        assertFalse(custom.builtIn)
        assertEquals("#0A141E", custom.backdropHex)
        assertNull(discovered.firstOrNull { it.id == "evil-import" })
        assertTrue(
            warnings.any { it.contains("[FALLBACK][theme]") && it.contains("@import") },
            "Expected WARN [FALLBACK][theme] for disallowed @import, got: $warnings",
        )
    }

    @Test
    fun unknownUiThemeFallsBackToOriginWithWarn(@TempDir home: Path) {
        lateinit var resolved: UiTheme
        val warnings = captureWarnings {
            resolved = UiThemeRegistry.resolveOrFallback("does-not-exist", home)
        }
        assertEquals("origin", resolved.id)
        assertTrue(
            warnings.any { it.contains("[FALLBACK][theme]") },
            "Expected WARN [FALLBACK][theme] on unknown UI theme, got: $warnings",
        )
    }

    @Test
    fun liveHotSwapUpdatesAllOpenJavaFxStages() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val task = FutureTask {
            Platform.setImplicitExit(false)
            val s1 = Stage().apply { scene = Scene(StackPane(), 200.0, 120.0) }
            val s2 = Stage().apply { scene = Scene(StackPane(), 200.0, 120.0) }
            try {
                s1.show()
                s2.show()
                val daylight = UiThemeRegistry.resolveOrFallback("daylight-studio")
                UiThemeRegistry.applyToAllOpenStages(daylight)
                assertEquals(daylight.stylesheetUrls, s1.scene.stylesheets.toList())
                assertEquals(daylight.stylesheetUrls, s2.scene.stylesheets.toList())

                val nordic = UiThemeRegistry.resolveOrFallback("nordic-minimal")
                UiThemeRegistry.applyToAllOpenStages(nordic)
                assertEquals(nordic.stylesheetUrls, s1.scene.stylesheets.toList())
                assertEquals(nordic.stylesheetUrls, s2.scene.stylesheets.toList())

                val origin = UiThemeRegistry.origin()
                UiThemeRegistry.applyToAllOpenStages(origin)
                assertEquals(origin.stylesheetUrls, s1.scene.stylesheets.toList())
                "ok"
            } finally {
                s1.close()
                s2.close()
            }
        }
        try {
            Platform.startup(task)
        } catch (_: IllegalStateException) {
            Platform.runLater(task)
        }
        assertEquals("ok", task.get(20, TimeUnit.SECONDS))
    }
}
