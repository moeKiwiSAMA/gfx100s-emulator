package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.panels.CameraStudio
import com.github.nyabanban.fuji.emulator.desktop.panels.EvfViewfinderWindow
import com.github.nyabanban.fuji.emulator.desktop.panels.ViewfinderStatus
import com.github.nyabanban.fuji.emulator.desktop.theme.ThemeRuntimeController
import com.github.nyabanban.fuji.emulator.desktop.theme.UserProfile
import com.github.nyabanban.fuji.emulator.desktop.theme.UserProfileStore
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.image.ImageView
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EvfViewfinderWindowTest {
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

    private fun mouse(type: javafx.event.EventType<MouseEvent>) =
        MouseEvent(type, 1.0, 1.0, 1.0, 1.0, MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, null)

    @Test
    fun minimalEdgeToEdgeWindowPinTogglesAlwaysOnTopAndHudFadesWithPointer() {
        runOnFx {
            val eyeToggles = mutableListOf<Unit>()
            val viewModePulses = mutableListOf<Unit>()
            val visibility = mutableListOf<Boolean>()
            val pinEvents = mutableListOf<Boolean>()
            val owner = Stage().apply { scene = Scene(StackPane(), 200.0, 120.0) }
            val window = EvfViewfinderWindow(
                owner = owner,
                sourceEvfImageView = ImageView(),
                onToggleEyeSensor = { eyeToggles += Unit },
                onViewModePressed = {},
                onViewModePulse = { viewModePulses += Unit },
                onVisibilityChanged = { visibility += it },
                onKeyShortcut = {},
                onAlwaysOnTopChanged = { pinEvents += it },
            )
            try {
                // 1. Edge-to-edge optical viewport: StackPane root, no permanent BorderPane top toolbar, not owned by the studio.
                val root = window.stage.scene.root
                assertTrue(root is StackPane, "EVF root must be an edge-to-edge StackPane, was ${root.javaClass.simpleName}")
                assertFalse(BorderPane::class.java.isInstance(root))
                assertTrue("evf-window-root" in root.styleClass)
                assertNull(window.stage.owner, "EVF stage must not be owned so Always-on-Top survives a minimized studio")
                assertEquals(EvfViewfinderWindow.MIN_WIDTH, window.stage.minWidth)
                assertEquals(EvfViewfinderWindow.MIN_HEIGHT, window.stage.minHeight)
                assertEquals(EvfViewfinderWindow.DEFAULT_WIDTH, window.stage.scene.width)
                assertEquals(EvfViewfinderWindow.DEFAULT_HEIGHT, window.stage.scene.height)
                assertTrue(root.lookup("#evf-window-image") is ImageView)
                assertSame(window.hudBar, root.lookup("#evf-window-hud"))
                assertTrue(window.hudBar.styleClass.contains("evf-hud-bar"))

                window.setVisible(true)
                assertEquals(listOf(true), visibility)
                assertFalse(window.stage.isAlwaysOnTop)

                // 2. Pin toggles stage.isAlwaysOnTop false -> true -> false and reports each change for persistence.
                val pin = root.lookup("#evf-window-pin-toggle") as ToggleButton
                pin.fire()
                assertTrue(pin.isSelected)
                assertTrue(window.stage.isAlwaysOnTop)
                assertTrue(window.isAlwaysOnTop)
                pin.fire()
                assertFalse(pin.isSelected)
                assertFalse(window.stage.isAlwaysOnTop)
                assertEquals(listOf(true, false), pinEvents)

                // 3. Existing eye-sensor / VIEW MODE / status controls stay wired.
                (root.lookup("#evf-window-eye-toggle") as ToggleButton).fire()
                (root.lookup("#evf-window-view-mode") as Button).fire()
                assertEquals(1, eyeToggles.size)
                assertEquals(1, viewModePulses.size)
                window.updateStatus(ViewfinderStatus.derive(true, 0L, 5L, false, true, "display.evf"))
                val status = root.lookup("#evf-window-status") as Label
                assertTrue(status.text.contains("EVF (LCDC0)"), status.text)
                assertTrue((root.lookup("#evf-window-eye-toggle") as ToggleButton).isSelected)

                // 4. HUD peeks on open, hides once settled, and fades in/out with the pointer.
                assertTrue(window.hudTargetVisible)
                window.settleHud()
                assertFalse(window.hudTargetVisible)
                root.fireEvent(mouse(MouseEvent.MOUSE_ENTERED))
                assertTrue(window.hudTargetVisible)
                root.fireEvent(mouse(MouseEvent.MOUSE_EXITED))
                assertFalse(window.hudTargetVisible)

                // 5. Shutdown is not reported as a user "EVF closed" preference change.
                window.close()
                assertFalse(window.stage.isShowing)
                assertEquals(listOf(true), visibility)
            } finally {
                window.close()
                owner.close()
            }
        }
    }

    @Test
    fun restoredPinStateAppliesWithoutReEmittingPersistenceEvent() {
        runOnFx {
            val pinEvents = mutableListOf<Boolean>()
            val owner = Stage().apply { scene = Scene(StackPane(), 200.0, 120.0) }
            val window = EvfViewfinderWindow(
                owner = owner,
                sourceEvfImageView = ImageView(),
                onToggleEyeSensor = {},
                onViewModePressed = {},
                onVisibilityChanged = {},
                onKeyShortcut = {},
                initialAlwaysOnTop = true,
                onAlwaysOnTopChanged = { pinEvents += it },
            )
            try {
                assertTrue(window.stage.isAlwaysOnTop)
                assertTrue((window.stage.scene.root.lookup("#evf-window-pin-toggle") as ToggleButton).isSelected)
                assertEquals(emptyList(), pinEvents)
                window.setAlwaysOnTop(false)
                assertFalse(window.stage.isAlwaysOnTop)
                assertEquals(listOf(false), pinEvents)
            } finally {
                window.close()
                owner.close()
            }
        }
    }

    @Test
    fun evfAlwaysOnTopPersistsInProfileYamlAndDefaultsFalseForOlderFiles(@TempDir dir: Path) {
        val legacy = dir.resolve("legacy.yaml")
        Files.writeString(legacy, "schema: 1\nwindows:\n  fitScale: 0.8\n  evfWindowOpen: true\n")
        val loaded = UserProfileStore.load(legacy)
        assertFalse(loaded.evfAlwaysOnTop)
        assertTrue(loaded.evfWindowOpen)

        val invalid = dir.resolve("invalid.yaml")
        Files.writeString(invalid, "schema: 1\nwindows:\n  evfAlwaysOnTop: \"sometimes\"\n")
        assertFalse(UserProfileStore.load(invalid).evfAlwaysOnTop)

        val roundTrip = dir.resolve("round-trip.yaml")
        UserProfileStore.save(roundTrip, UserProfile(evfAlwaysOnTop = true))
        assertTrue(Files.readString(roundTrip).contains("evfAlwaysOnTop: true"))
        assertTrue(UserProfileStore.load(roundTrip).evfAlwaysOnTop)

        val home = Files.createDirectories(dir.resolve("home"))
        val controller = ThemeRuntimeController(home)
        assertFalse(controller.profile.evfAlwaysOnTop)
        controller.updateWindowPreferences(evfAlwaysOnTop = true)
        assertTrue(UserProfileStore.load(controller.profilePath).evfAlwaysOnTop)
        @Suppress("UNCHECKED_CAST")
        val windows = controller.statusMap()["windows"] as Map<String, Any?>
        assertEquals(true, windows["evfAlwaysOnTop"])
        assertTrue(ThemeRuntimeController(home).profile.evfAlwaysOnTop, "Pinned state must survive a restart")
    }

    @Test
    fun cameraStudioRestoresAndReportsEvfPinState() {
        val pinEvents = mutableListOf<Boolean>()
        var studio: CameraStudio? = null
        try {
            runOnFx {
                val owner = Stage().apply { scene = Scene(StackPane(), 360.0, 420.0) }
                val created = CameraStudio(
                    owner = owner,
                    sendButton = { _, _ -> CompletableFuture.completedFuture(Unit) },
                    configure = {},
                    inspect = {},
                    failure = {},
                    initialEvfAlwaysOnTop = true,
                    onEvfAlwaysOnTopChanged = { pinEvents += it },
                )
                studio = created
                assertTrue(created.isEvfAlwaysOnTop())
                created.setEvfWindowVisible(true)
                val evfStage = javafx.stage.Window.getWindows()
                    .filterIsInstance<Stage>()
                    .single { it.isShowing && it.title?.contains("EVF") == true }
                assertTrue(evfStage.isAlwaysOnTop)
                (evfStage.scene.lookup("#evf-window-pin-toggle") as ToggleButton).fire()
                assertFalse(evfStage.isAlwaysOnTop)
                assertFalse(created.isEvfAlwaysOnTop())
                created.setEvfAlwaysOnTop(true)
                assertTrue(evfStage.isAlwaysOnTop)
                assertEquals(listOf(false, true), pinEvents)
            }
        } finally {
            studio?.let { s -> runOnFx { s.close(releaseLatching = false) } }
        }
    }
}
