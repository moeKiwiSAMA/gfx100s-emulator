package com.github.nyabanban.fuji.emulator.desktop.theme

import com.github.nyabanban.fuji.emulator.desktop.parseProfileLaunchArgs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserProfileTest {
    private fun captureWarnings(loggerClass: Class<*>, block: () -> Unit): List<String> =
        ThemeTestLogCapture.captureWarnings(loggerClass, block)

    @Test
    fun autoCreatesProfileYamlWithDefaultsAndZeroHardwareKeys(@TempDir dir: Path) {
        val profilePath = UserProfileStore.defaultPath(dir)
        assertFalse(Files.exists(profilePath))

        val loaded = UserProfileStore.loadOrCreate(profilePath)
        assertTrue(Files.exists(profilePath))
        assertEquals(UserProfile(), loaded)
        assertEquals("en", loaded.language)
        assertEquals("origin", loaded.bodyTheme)
        assertEquals("origin", loaded.uiTheme)
        assertEquals(1.0, loaded.fitScale)
        assertEquals(false, loaded.evfWindowOpen)

        val text = Files.readString(profilePath)
        assertTrue(text.contains("Global Photographer Studio Profile (profile.yaml)"), text)
        assertTrue(text.contains("language: \"en\""), text)
        assertTrue(text.contains("body: \"origin\""), text)
        assertTrue(text.contains("ui: \"origin\""), text)
        // Strict decoupling: parsed profile.yaml must ONLY contain shell/graphics preference keys (zero hardware/component/ND/scene keys).
        val parsedMap = com.github.nyabanban.fuji.emulator.runtime.Formats.read(profilePath)
        assertEquals(setOf("schema", "language", "theme", "windows", "graphics"), parsedMap.keys)
        assertEquals("auto", loaded.opticsBackend)
        assertEquals("auto", loaded.vulkanDevice)
    }

    @Test
    fun saveAndReloadPreservesPreferences(@TempDir dir: Path) {
        val profilePath = dir.resolve("custom-profile.yaml")
        val saved = UserProfile(
            schema = 1,
            language = "zh_CN",
            bodyTheme = "hasselblad-x2d2",
            uiTheme = "nordic-minimal",
            fitScale = 0.85,
            evfWindowOpen = true,
            opticsBackend = "vulkan",
            vulkanDevice = "0:NVIDIA GeForce RTX 4090",
        )
        UserProfileStore.save(profilePath, saved)

        val reloaded = UserProfileStore.load(profilePath)
        assertEquals(saved, reloaded)
    }

    @Test
    fun invalidValuesFallBackSafelyWithWarnLog(@TempDir dir: Path) {
        val profilePath = dir.resolve("profile.yaml")
        Files.writeString(
            profilePath,
            """
            schema: 99
            language: "??bad!!"
            theme:
              body: "../escape"
              ui: ""
            windows:
              fitScale: 42.0
              evfWindowOpen: "not-a-boolean"
            graphics:
              opticsBackend: "metal"
              vulkanDevice: ""
            """.trimIndent() + "\n",
        )
        lateinit var parsed: UserProfile
        val warnings = captureWarnings(UserProfileStore::class.java) {
            parsed = UserProfileStore.load(profilePath)
        }
        assertEquals(UserProfile(), parsed)
        assertTrue(
            warnings.any { it.contains("[FALLBACK][profile]") },
            "Expected WARN [FALLBACK][profile], got: $warnings",
        )
    }

    @Test
    fun corruptedYamlFallsBackToDefaultsWithWarnLog(@TempDir dir: Path) {
        val profilePath = dir.resolve("broken.yaml")
        Files.writeString(profilePath, ":\n  - [not valid yaml {{{")
        lateinit var parsed: UserProfile
        val warnings = captureWarnings(UserProfileStore::class.java) {
            parsed = UserProfileStore.load(profilePath)
        }
        assertEquals(UserProfile(), parsed)
        assertTrue(
            warnings.any { it.contains("[FALLBACK][profile]") },
            "Expected WARN [FALLBACK][profile] on broken YAML",
        )
    }

    @Test
    fun cliProfilePathCoexistsCleanlyWithProfilerFlag(@TempDir dir: Path) {
        val (pathOnly, profilerFromPath) = parseProfileLaunchArgs(arrayOf("--profile", "my-profile.yaml", "--headless"), dir)
        assertEquals(dir.resolve("my-profile.yaml").normalize(), pathOnly)
        assertFalse(profilerFromPath)

        val (pathFromProfilerOnly, profilerOnly) = parseProfileLaunchArgs(arrayOf("--profile", "--headless"), dir)
        assertNull(pathFromProfilerOnly)
        assertTrue(profilerOnly)

        val (explicitPath, profilerBoth) = parseProfileLaunchArgs(arrayOf("--user-profile", "alt.yaml", "--profile"), dir)
        assertEquals(dir.resolve("alt.yaml").normalize(), explicitPath)
        assertTrue(profilerBoth)
    }
}
