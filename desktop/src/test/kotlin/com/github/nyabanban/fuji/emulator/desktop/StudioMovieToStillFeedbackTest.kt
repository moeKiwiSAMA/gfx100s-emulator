package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.CameraStudio
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
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

class StudioMovieToStillFeedbackTest {
    @Test
    fun switchingFeedbackTracksFirmwareIndependentlyOfPhysicalModeSwitch() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val task = FutureTask {
            I18n.resetToDefault()
            val panel = StackPane()
            val stage = Stage().apply { scene = Scene(panel) }
            val commands = mutableListOf<Pair<String, Boolean>>()
            CameraStudio(stage, { key, down ->
                commands.add(key to down)
                CompletableFuture.completedFuture(Unit)
            }, {}, {}, {}).use { studio ->
                panel.children.add(studio.root)
                val message = I18n.t("studio.mode.switching_to_still")
                fun observe(movie: Boolean, switching: Boolean) {
                    studio.observeSnapshot(mapOf(
                        "components" to listOf(mapOf("id" to "buttons", "state" to PanelCatalog.buttonKeys.associateWith { it == "movieMode" && movie })),
                        "switchingToStill" to switching,
                    ))
                }
                fun statusShown() = panel.lookupAll(".label").filterIsInstance<Label>().any { it.text == message }
                observe(movie = true, switching = false)
                assertFalse(statusShown())
                observe(movie = false, switching = true)
                assertFalse(studio.buttons.getValue("movieMode").isSelected, "The physical switch already reads STILL")
                assertTrue(statusShown(), "Studio must distinguish the observed STILL switch from firmware still switching")
                observe(movie = true, switching = true)
                assertFalse(statusShown(), "Returning the lever to MOVIE clears a stale transition snapshot")
                observe(movie = false, switching = true)
                observe(movie = false, switching = false)
                assertFalse(statusShown(), "The camera has returned to its shooting screen")
                assertEquals(emptyList(), commands, "Observing a transition must never send a shutter contact")
            }
        }
        try {
            Platform.startup(task)
        } catch (_: IllegalStateException) {
            Platform.runLater(task)
        }
        task.get(45, TimeUnit.SECONDS)
    }
}
