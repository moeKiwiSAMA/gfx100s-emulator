package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.theme.UserProfile
import com.github.nyabanban.fuji.emulator.desktop.theme.UserProfileStore
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import javafx.application.Platform
import javafx.scene.control.TabPane
import javafx.stage.Stage
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EmulatorAppStartupSmokeTest {
    @Test
    fun emulatorAppStartAndStopInitializeAllTabsWithoutLateinitCrashEvenWithUnreachableTelemetry(@TempDir tempRoot: Path) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val home = IntegrationTest().fixture()
        val config = mapOf<String, Any>(
            "schema" to 1,
            "components" to "compoments",
            "httpPort" to 0,
            "executionMode" to RelaxedPeripheralFallback.MODE,
            "firmwareMetadata" to repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
            "firmwareFile" to tempRoot.resolve("unused.dat").toString(),
            "nativeLibrary" to tempRoot.resolve("unused.so").toString(),
            "instructionBudget" to 1000,
            "enabledDevices" to listOf("soc-timer"),
            // Explicitly point enabled telemetry at an unreachable loopback port to prove startup & shutdown never crash/hang
            "telemetry" to mapOf(
                "enabled" to true,
                "endpoint" to "http://127.0.0.1:1",
                "ingestToken" to "unreachable-smoke-token",
                "uploadLogOnExit" to true,
            ),
        )

        EmulatorRuntime(home, config, telemetryEnv = emptyMap()).use { rt ->
            assertNotNull(rt.telemetryPublisher, "Explicitly enabled telemetry should construct publisher")
            rt.telemetryPublisher.start()
            LaunchContext.runtime = rt
            LaunchContext.smokePath = null
            LaunchContext.snapshot = null
            LaunchContext.autoBoot = false
            LaunchContext.paused.set(false)
            LaunchContext.stopRequested.set(false)

            val task = FutureTask {
                I18n.resetToDefault()
                Platform.setImplicitExit(false)
                val app = EmulatorApp()
                val stage = Stage()
                try {
                    app.start(stage)
                    val scene = assertNotNull(stage.scene, "EmulatorApp.start() must attach Scene")
                    val tabPane = scene.root.lookup(".studio-sidebar") as? TabPane
                    assertNotNull(tabPane, "EmulatorApp.start() must create sidebar TabPane")
                    assertEquals(8, tabPane.tabs.size, "Expected all 8 sidebar tabs initialized (Controls, Lens, Audio, Wi-Fi, Components, Evidence, Diagnostics, Dictionary)")

                    // Exercise selecting Wi-Fi, Components & Diagnostics tabs so wifiTab, configTab, configurationScroll, and diagnosticsTab are proven live
                    tabPane.selectionModel.select(3)
                    tabPane.selectionModel.select(4)
                    tabPane.selectionModel.select(6)
                    tabPane.selectionModel.select(0)
                    assertTrue(stage.isShowing, "Main entry stage must be showing after start()")

                    // Verify GNOME/compact right-window header never overflows horizontally at 380px across EN and zh_CN
                    stage.width = 380.0
                    stage.height = 760.0
                    val headerControlIds = listOf(
                        "header-boot-btn",
                        "header-lens-profile-btn",
                        "header-input-bindings-btn",
                        "header-flash-menu-btn",
                        "header-dump-btn",
                        "theme-body-combo",
                        "theme-ui-combo",
                        "theme-reload-btn",
                        "header-lang-combo",
                    )
                    for (code in listOf(I18n.CODE_EN, I18n.CODE_ZH_CN)) {
                        I18n.setLocale(code)
                        scene.root.applyCss()
                        scene.root.layout()
                        val sceneW = scene.width.takeIf { it > 0.0 } ?: 380.0
                        for (cid in headerControlIds) {
                            val node = assertNotNull(scene.root.lookup("#$cid"), "Missing header control #$cid")
                            val maxX = node.localToScene(node.boundsInLocal).maxX
                            assertTrue(
                                maxX <= sceneW + 1.0,
                                "Header control #$cid overflowed right border in locale '$code': maxX=$maxX > sceneWidth=$sceneW",
                            )
                        }
                    }
                    I18n.resetToDefault()
                } finally {
                    app.stop()
                    stage.close()
                }
                "ok"
            }
            try {
                Platform.startup(task)
            } catch (_: IllegalStateException) {
                Platform.runLater(task)
            }
            assertEquals("ok", task.get(30, TimeUnit.SECONDS))
        }
        home.toFile().deleteRecursively()
    }

    @Test
    fun emulatorAppStartsCleanlyWithSavedVulkanGraphicsProfileOnOwnerThread(@TempDir tempRoot: Path) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val home = IntegrationTest().fixture()
        try {
            UserProfileStore.save(
                UserProfileStore.defaultPath(home),
                UserProfile(opticsBackend = "vulkan", vulkanDevice = "0"),
            )
            val config = mapOf<String, Any>(
                "schema" to 1,
                "components" to "compoments",
                "httpPort" to 0,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "firmwareMetadata" to repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
                "firmwareFile" to tempRoot.resolve("unused.dat").toString(),
                "nativeLibrary" to tempRoot.resolve("unused.so").toString(),
                "instructionBudget" to 1000,
                "enabledDevices" to listOf("soc-timer"),
                "telemetry" to mapOf("enabled" to false),
            )
            EmulatorRuntime(home, config, telemetryEnv = emptyMap()).use { rt ->
                assertEquals("vulkan", rt.themeController.profile.opticsBackend)
                assertEquals("0", rt.themeController.profile.vulkanDevice)
                LaunchContext.runtime = rt
                LaunchContext.smokePath = null
                LaunchContext.snapshot = null
                LaunchContext.autoBoot = false
                LaunchContext.paused.set(false)
                LaunchContext.stopRequested.set(false)

                val task = FutureTask {
                    I18n.resetToDefault()
                    Platform.setImplicitExit(false)
                    val app = EmulatorApp()
                    val stage = Stage()
                    try {
                        app.start(stage)
                        assertTrue(stage.isShowing, "Main entry stage must open cleanly with saved Vulkan profile")
                    } finally {
                        app.stop()
                        stage.close()
                    }
                    "ok"
                }
                try {
                    Platform.startup(task)
                } catch (_: IllegalStateException) {
                    Platform.runLater(task)
                }
                assertEquals("ok", task.get(30, TimeUnit.SECONDS))
                // Drain owner queue to ensure the async lens profile patch completed without off-owner failure.
                rt.owner.submit { "drained" }.get(10, TimeUnit.SECONDS)
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
