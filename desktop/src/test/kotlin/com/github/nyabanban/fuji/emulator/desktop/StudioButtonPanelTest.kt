package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.ArgbFrameBuffer
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.AsyncDisplayBridge
import com.github.nyabanban.fuji.emulator.desktop.panels.CameraStudio
import com.github.nyabanban.fuji.emulator.desktop.panels.MomentaryHoldPolicy
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import java.awt.GraphicsEnvironment
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import kotlin.test.*

class StudioButtonPanelTest {
    @Test fun controlsShowSubmissionAndOwnerReadbackIndependently() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val checkPanel = FutureTask {
            I18n.resetToDefault()
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder) }
            val commands = mutableListOf<Pair<String, Boolean>>()
            val replies = mutableListOf<CompletableFuture<Unit>>()
            CameraStudio(owner, { key, down ->
                commands += key to down
                CompletableFuture<Unit>().also(replies::add)
            }, {}, {}, {}).use { studio ->
                holder.children += studio.root
                fun control(key: String) = holder.lookup("#input-$key") as ToggleButton
                fun label(id: String) = (holder.lookup("#$id") as Label).text
                fun observe(vararg held: String) = studio.observeSnapshot(mapOf("components" to listOf(
                    mapOf("id" to "buttons", "state" to PanelCatalog.buttonKeys.associateWith { it in held }))))
                assertEquals(PanelCatalog.buttonKeys.size, holder.lookupAll(".studio-input-key").size)
                for (key in PanelCatalog.buttonKeys) {
                    assertTrue(control(key).text.isNotBlank())
                    assertEquals(I18n.t("studio.state.unread"), label("readback-$key"))
                }
                control("menu").fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false))
                assertEquals(listOf("menu" to true), commands)
                assertEquals("Pressed · Pending", label("request-menu"))
                assertFalse(control("menu").isSelected)
                replies.single().complete(Unit)
                assertEquals("Pressed · Applied", label("request-menu"))
                assertEquals("Unread", label("readback-menu"))
                observe("menu", "play")
                assertTrue(control("menu").isSelected)
                assertTrue(studio.buttons.getValue("menu").isSelected)
                assertEquals("Pressed", label("readback-play"))
                assertEquals("—", label("request-play"))
                control("menu").fireEvent(KeyEvent(KeyEvent.KEY_RELEASED, " ", " ", KeyCode.SPACE, false, false, false, false))
                assertEquals("menu" to false, commands.last())
                replies.last().completeExceptionally(IllegalStateException("rejected release"))
                assertEquals("Released · Failed", label("request-menu"))
                assertEquals("Pressed", label("readback-menu"))
                // Live locale switch updates readback & request labels instantaneously without restarting
                I18n.setLocale(I18n.CODE_ZH_CN)
                assertEquals(I18n.t("studio.state.pressed"), label("readback-menu"))
                assertEquals(I18n.t("studio.submission.failed", I18n.t("studio.state.released")), label("request-menu"))
                I18n.resetToDefault()
                studio.releaseAll()
                assertEquals(PanelCatalog.buttonKeys.map { it to false }, commands.drop(2))
                observe()
                assertEquals("Released", label("readback-menu"))
                assertEquals("OFF", label("readback-power"))
                assertFalse(control("menu").isSelected)
                assertFalse(studio.buttons.getValue("menu").isSelected)
            }
        }
        try {
            Platform.startup(checkPanel)
        } catch (_: IllegalStateException) {
            Platform.runLater(checkPanel)
        }
        checkPanel.get(30, TimeUnit.SECONDS)
    }

    @Test fun latchingPowerAndEyeSensorUseOnOffCopyAndToggleDirectlyOnFirstClick() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val checkSwitches = FutureTask {
            I18n.resetToDefault()
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder) }
            val buttonCommands = mutableListOf<Pair<String, Boolean>>()
            val settingCommands = mutableListOf<Triple<String, String, Boolean>>()
            val inspected = mutableListOf<String>()
            CameraStudio(
                owner,
                { key, down ->
                    buttonCommands += key to down
                    CompletableFuture.completedFuture(Unit)
                },
                {},
                { control -> inspected += control.id },
                {},
                sendSetting = { componentId, settingKey, value ->
                    settingCommands += Triple(componentId, settingKey, value)
                    CompletableFuture.completedFuture(Unit)
                },
            ).use { studio ->
                holder.children += studio.root
                fun buttonControl(key: String) = holder.lookup("#input-$key") as ToggleButton
                fun eyeControl() = holder.lookup("#setting-toggle-rear-eye-sensor") as ToggleButton
                fun label(id: String) = (holder.lookup("#$id") as Label).text

                studio.observeSnapshot(
                    mapOf(
                        "components" to listOf(
                            mapOf("id" to "buttons", "state" to PanelCatalog.buttonKeys.associateWith { it == "power" }),
                            mapOf("id" to "i2c-bus", "state" to mapOf("eyeSensorNear" to false)),
                        ),
                    ),
                )
                assertEquals("ON", label("readback-power"))
                assertEquals("OFF", label("readback-rear-eye-sensor"))
                assertTrue(buttonControl("power").isSelected)
                assertFalse(eyeControl().isSelected)

                // First click on power when started ON must toggle OFF immediately.
                buttonControl("power").fire()
                assertEquals(listOf("power" to false), buttonCommands)
                assertEquals("OFF · Applied", label("request-power"))

                // Direct click on rear-eye-sensor toggles i2c-bus.eyeSensorNear and still records inspect.
                eyeControl().fire()
                assertEquals(listOf(Triple("i2c-bus", "eyeSensorNear", true)), settingCommands)
                assertEquals("ON · Applied", label("request-rear-eye-sensor"))
                assertEquals(listOf("rear-eye-sensor"), inspected)

                studio.observeSnapshot(
                    mapOf(
                        "components" to listOf(
                            mapOf("id" to "buttons", "state" to PanelCatalog.buttonKeys.associateWith { false }),
                            mapOf("id" to "i2c-bus", "state" to mapOf("eyeSensorNear" to true)),
                        ),
                    ),
                )
                assertEquals("OFF", label("readback-power"))
                assertEquals("ON", label("readback-rear-eye-sensor"))
                assertTrue(eyeControl().isSelected)

                // Second click on rear-eye-sensor toggles OFF.
                eyeControl().fire()
                assertEquals(
                    listOf(
                        Triple("i2c-bus", "eyeSensorNear", true),
                        Triple("i2c-bus", "eyeSensorNear", false),
                    ),
                    settingCommands,
                )
                assertEquals("OFF · Applied", label("request-rear-eye-sensor"))
            }
        }
        try {
            Platform.startup(checkSwitches)
        } catch (_: IllegalStateException) {
            Platform.runLater(checkSwitches)
        }
        checkSwitches.get(30, TimeUnit.SECONDS)
    }

    @Test fun minimumMomentaryHoldFloorKeepsQuickMouseTapHeldBeforeReleasing() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val commands = mutableListOf<Pair<String, Boolean>>()
        val releasedLatch = CompletableFuture<Unit>()
        val studioRef = CompletableFuture<CameraStudio>()
        val startTap = FutureTask {
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder) }
            val studio = CameraStudio(
                owner,
                { key, down ->
                    commands += key to down
                    if (key == "down" && !down) releasedLatch.complete(Unit)
                    CompletableFuture.completedFuture(Unit)
                },
                {},
                {},
                {},
                minMomentaryHoldMs = 150L,
            )
            studioRef.complete(studio)
            holder.children += studio.root
            val downBtn = holder.lookup("#input-down") as ToggleButton
            downBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            downBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            // Immediately after an instant click, release must still be deferred by the minimum hold floor
            assertEquals(listOf("down" to true), commands)
        }
        try {
            Platform.startup(startTap)
        } catch (_: IllegalStateException) {
            Platform.runLater(startTap)
        }
        startTap.get(30, TimeUnit.SECONDS)
        try {
            releasedLatch.get(5, TimeUnit.SECONDS)
            val verifyAndClose = FutureTask {
                assertEquals(listOf("down" to true, "down" to false), commands)
                studioRef.get().close(releaseLatching = false)
            }
            Platform.runLater(verifyAndClose)
            verifyAndClose.get(15, TimeUnit.SECONDS)
        } finally {
            if (studioRef.isDone) {
                val closeTask = FutureTask { studioRef.get().close(releaseLatching = false) }
                Platform.runLater(closeTask)
                runCatching { closeTask.get(5, TimeUnit.SECONDS) }
            }
        }
    }

    @Test fun firmwareTickHoldReleasesOnTickWindowCancelsOppositePendingHoldAndPaintsBridgeFrames() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val commands = mutableListOf<Pair<String, Boolean>>()
        val virtualTick = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val firstReleased = CompletableFuture<Unit>()
        val studioRef = CompletableFuture<CameraStudio>()

        val startTap = FutureTask {
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder) }
            val studio = CameraStudio(
                owner,
                { key, down ->
                    commands += key to down
                    if (key == "down" && !down && !firstReleased.isDone) firstReleased.complete(Unit)
                    CompletableFuture.completedFuture(Unit)
                },
                {},
                {},
                {},
                momentaryHoldPolicy = MomentaryHoldPolicy.firmwareCalibrated(
                    minHoldVirtualTicks = 4_000_000L,
                    maxHoldWallMs = 3_000L,
                    pollIntervalMs = 5L,
                ),
                virtualTick = { virtualTick.get() },
                isSessionAdvancing = { true },
            )
            studioRef.complete(studio)
            holder.children += studio.root
            Platform.setImplicitExit(false)
            owner.show()
            studio.setFaceVisible("rear", true)

            // 1. Verify zero-copy PixelBuffer bridge painting across dynamic resolutions (640x480 -> 1024x768)
            val bridge = AsyncDisplayBridge()
            val port640 = ArgbFrameBuffer(640, 480)
            port640.presentArgb(IntArray(640 * 480) { 0xFF123456.toInt() }, 640, 480)
            assertTrue(bridge.publishIfChanged("display.main", port640))
            assertEquals(1, studio.paintFromBridge(bridge))
            assertFalse(studio.waiting("rear-lcd"))
            assertEquals(0, studio.paintFromBridge(bridge))

            val port1024 = ArgbFrameBuffer(1024, 768)
            port1024.presentArgb(IntArray(1024 * 768) { 0xFF654321.toInt() }, 1024, 768)
            assertTrue(bridge.publishIfChanged("display.main", port1024))
            assertEquals(1, studio.paintFromBridge(bridge))

            // 2. Quick DOWN tap stays held while tick delta < 4M
            val downBtn = holder.lookup("#input-down") as ToggleButton
            downBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            downBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            assertEquals(listOf("down" to true), commands)
        }
        try {
            Platform.startup(startTap)
        } catch (_: IllegalStateException) {
            Platform.runLater(startTap)
        }
        startTap.get(30, TimeUnit.SECONDS)
        try {
            // Advance virtual ticks past calibrated 4M threshold -> must release quickly (< 500ms, NOT waiting for 3000ms wall cap!)
            virtualTick.addAndGet(4_000_000L)
            firstReleased.get(1, TimeUnit.SECONDS)

            val verifyOpposite = FutureTask {
                assertEquals(listOf("down" to true, "down" to false), commands)
                commands.clear()
                val studio = studioRef.get()
                val downBtn = studio.root.lookup("#input-down") as ToggleButton
                val upBtn = studio.root.lookup("#input-up") as ToggleButton
                // Quick DOWN tap (enters pending hold), immediately interrupted by UP press
                downBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                downBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
                upBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                assertEquals(listOf("down" to true, "down" to false, "up" to true), commands)
                studio.close(releaseLatching = false)
            }
            Platform.runLater(verifyOpposite)
            verifyOpposite.get(15, TimeUnit.SECONDS)
        } finally {
            if (studioRef.isDone) {
                val closeTask = FutureTask { studioRef.get().close(releaseLatching = false) }
                Platform.runLater(closeTask)
                runCatching { closeTask.get(5, TimeUnit.SECONDS) }
            }
        }
    }

    @Test fun rearLcdRoutesMouseTouchOnlyWhenTouchEnabledAndPreservesInspectWhenDisabled() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val checkTouch = FutureTask {
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder) }
            val inspected = mutableListOf<String>()
            val configured = mutableListOf<String>()
            val touchEvents = mutableListOf<Triple<Int, Int, Boolean>>()
            CameraStudio(
                owner,
                { _, _ -> CompletableFuture.completedFuture(Unit) },
                { id -> configured += id },
                { control -> inspected += control.id },
                {},
                sendTouch = { x, y, pressed ->
                    touchEvents += Triple(x, y, pressed)
                    CompletableFuture.completedFuture(Unit)
                },
            ).use { studio ->
                holder.children += studio.root
                Platform.setImplicitExit(false)
                owner.show()
                studio.setFaceVisible("rear", true)
                val rearStage = javafx.stage.Window.getWindows()
                    .filterIsInstance<Stage>()
                    .single { it !== owner && it.title.contains("REAR") }
                rearStage.scene.root.applyCss()
                rearStage.scene.root.layout()
                val lcdButton = rearStage.scene.lookup("#inspect-rear-lcd") as javafx.scene.control.Button
                assertTrue(lcdButton.width > 100.0 && lcdButton.height > 100.0)

                // 1. Default OFF (touchEnabled = false): left-click inspects rear-lcd silently without auto-configuring or sending touch
                lcdButton.fire()
                assertEquals(listOf("rear-lcd"), inspected)
                assertTrue(configured.isEmpty(), "Left-clicking rear-lcd must never auto-open component config")
                assertTrue(touchEvents.isEmpty())

                // 2. Enable via i2c-bus snapshot observation -> primary press/release/click maps coordinates to 1024x768 and never inspects or configures
                inspected.clear()
                studio.observeSnapshot(
                    mapOf(
                        "components" to listOf(
                            mapOf("id" to "i2c-bus", "state" to mapOf("touchEnabled" to true)),
                        ),
                    ),
                )
                var primaryClickReachedHandler = false
                lcdButton.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                    if (event.button == MouseButton.PRIMARY) {
                        primaryClickReachedHandler = true
                    }
                }
                val sceneCenter = lcdButton.localToScene(lcdButton.width / 2.0, lcdButton.height / 2.0)
                val cx = sceneCenter.x
                val cy = sceneCenter.y
                lcdButton.fireEvent(MouseEvent(lcdButton, lcdButton, MouseEvent.MOUSE_PRESSED, cx, cy, cx, cy, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                lcdButton.fireEvent(MouseEvent(lcdButton, lcdButton, MouseEvent.MOUSE_RELEASED, cx, cy, cx, cy, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
                lcdButton.fireEvent(MouseEvent(lcdButton, lcdButton, MouseEvent.MOUSE_CLICKED, cx, cy, cx, cy, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
                assertFalse(primaryClickReachedHandler, "Primary click on rear-lcd must be consumed when touchEnabled=true")
                assertTrue(inspected.isEmpty(), "Primary touch press/click must not trigger inspect when touchEnabled=true")
                assertTrue(configured.isEmpty(), "Primary touch press/click must not auto-open component config")
                assertEquals(2, touchEvents.size)
                assertEquals(true, touchEvents[0].third)
                assertEquals(false, touchEvents[1].third)
                assertTrue(touchEvents[0].first in 505..518, "Expected center X near 512, got ${touchEvents[0].first}")
                assertTrue(touchEvents[0].second in 378..390, "Expected center Y near 384, got ${touchEvents[0].second}")

                // 3. Secondary right-click updates inspect silently without auto-configuring even when touchEnabled=true
                lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_CLICKED, cx, cy, cx, cy, MouseButton.SECONDARY, 1, false, false, false, false, false, false, true, false, false, true, null))
                assertEquals(listOf("rear-lcd"), inspected)
                assertTrue(configured.isEmpty(), "Right-clicking rear-lcd must not auto-pop component config")
            }
        }
        try {
            Platform.startup(checkTouch)
        } catch (_: IllegalStateException) {
            Platform.runLater(checkTouch)
        }
        checkTouch.get(30, TimeUnit.SECONDS)
    }

    @Test fun evfViewfinderWindowShortcutsAndViewModeSwitchShareZeroCopyEvfSurface() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val checkEvf = FutureTask {
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder) }
            val settingCommands = mutableListOf<Triple<String, String, Boolean>>()
            val viewModeCommands = mutableListOf<Boolean>()
            val inspected = mutableListOf<String>()
            CameraStudio(
                owner,
                { _, _ -> CompletableFuture.completedFuture(Unit) },
                {},
                { control -> inspected += control.id },
                {},
                sendSetting = { componentId, settingKey, value ->
                    settingCommands += Triple(componentId, settingKey, value)
                    CompletableFuture.completedFuture(Unit)
                },
                sendViewMode = { pressed ->
                    viewModeCommands += pressed
                    CompletableFuture.completedFuture(Unit)
                },
            ).use { studio ->
                holder.children += studio.root
                Platform.setImplicitExit(false)
                owner.show()
                studio.setFaceVisible("rear", true)

                // 1. Enlarged EVF window is opt-in and hidden by default (does not alter default stage count)
                assertFalse(studio.isEvfWindowShowing())
                assertEquals(false, studio.state()["evfWindowShowing"])
                assertEquals(com.github.nyabanban.fuji.emulator.desktop.panels.ActiveViewfinder.WAITING, studio.viewfinderStatus().active)

                // 2. LCDC1 publishes frame -> viewfinder status becomes LCD
                val bridge = AsyncDisplayBridge()
                val mainPort = ArgbFrameBuffer(1024, 768)
                mainPort.presentArgb(IntArray(1024 * 768) { 0xFFFF0000.toInt() }, 1024, 768)
                assertTrue(bridge.publishIfChanged("display.main", mainPort))
                assertEquals(1, studio.paintFromBridge(bridge))
                studio.observeSnapshot(
                    mapOf(
                        "components" to listOf(
                            mapOf("id" to "i2c-bus", "state" to mapOf("eyeSensorNear" to false)),
                            mapOf(
                                "id" to "soc-vglib-init",
                                "state" to mapOf(
                                    "scanout" to mapOf("outputActive" to true),
                                    "evfScanoutState" to mapOf("outputActive" to false),
                                ),
                            ),
                        ),
                    ),
                )
                assertEquals(com.github.nyabanban.fuji.emulator.desktop.panels.ActiveViewfinder.LCD, studio.viewfinderStatus().active)
                assertTrue((holder.lookup("#viewfinder-active-status") as Label).text.contains("LCD (LCDC1)"))

                // 3. F6 toggles eye sensor, F7 presses/releases VIEW MODE, F8 opens enlarged EVF window
                owner.scene.root.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F6, false, false, false, false))
                assertEquals(listOf(Triple("i2c-bus", "eyeSensorNear", true)), settingCommands)

                owner.scene.root.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F7, false, false, false, false))
                owner.scene.root.fireEvent(KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.F7, false, false, false, false))
                assertEquals(listOf(true, false), viewModeCommands)

                owner.scene.root.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F8, false, false, false, false))
                assertTrue(studio.isEvfWindowShowing())
                assertTrue((holder.lookup("#quick-evf-window-toggle") as ToggleButton).isSelected)

                // 4. Even when rear face window is hidden, open EVF window still consumes display.evf via zero-copy bridge
                studio.setFaceVisible("rear", false)
                assertTrue("display.evf" in studio.sequences())
                val evfPort = ArgbFrameBuffer(1024, 768)
                evfPort.presentArgb(IntArray(1024 * 768) { 0xFF0000FF.toInt() }, 1024, 768)
                assertTrue(bridge.publishIfChanged("display.evf", evfPort))
                assertEquals(1, studio.paintFromBridge(bridge))
                assertFalse(studio.waiting("rear-evf"))

                studio.observeSnapshot(
                    mapOf(
                        "components" to listOf(
                            mapOf("id" to "i2c-bus", "state" to mapOf("eyeSensorNear" to true)),
                            mapOf(
                                "id" to "soc-vglib-init",
                                "state" to mapOf(
                                    "scanout" to mapOf("outputActive" to false),
                                    "evfScanoutState" to mapOf("outputActive" to true),
                                ),
                            ),
                        ),
                    ),
                )
                assertEquals(com.github.nyabanban.fuji.emulator.desktop.panels.ActiveViewfinder.EVF, studio.viewfinderStatus().active)
                val evfStage = javafx.stage.Window.getWindows()
                    .filterIsInstance<Stage>()
                    .single { it.isShowing && it.title?.contains("EVF") == true }
                val evfImageView = evfStage.scene.lookup("#evf-window-image") as javafx.scene.image.ImageView
                assertNotNull(evfImageView.image, "EVF enlarged window must share zero-copy PixelBuffer image")
                assertEquals(1024, evfImageView.image.width.toInt())
                assertEquals(768, evfImageView.image.height.toInt())
                assertTrue((evfStage.scene.lookup("#evf-window-status") as Label).text.contains("EVF (LCDC0)"))

                // 5. Rear body VIEW MODE hotspot routes primary click to sendViewMode and secondary click to inspect
                studio.setFaceVisible("rear", true)
                val rearStage = javafx.stage.Window.getWindows()
                    .filterIsInstance<Stage>()
                    .single { it !== owner && it.title?.contains("REAR") == true }
                val vmHotspot = rearStage.scene.lookup("#inspect-rear-view-mode") as javafx.scene.control.Button
                val vmCenter = vmHotspot.localToScene(vmHotspot.width / 2.0, vmHotspot.height / 2.0)
                viewModeCommands.clear()
                inspected.clear()
                vmHotspot.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, vmCenter.x, vmCenter.y, vmCenter.x, vmCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                vmHotspot.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, vmCenter.x, vmCenter.y, vmCenter.x, vmCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
                assertEquals(listOf(true, false), viewModeCommands)
                vmHotspot.fireEvent(MouseEvent(MouseEvent.MOUSE_CLICKED, vmCenter.x, vmCenter.y, vmCenter.x, vmCenter.y, MouseButton.SECONDARY, 1, false, false, false, false, false, false, true, false, false, true, null))
                assertEquals(listOf("rear-view-mode"), inspected)

                // 6. Pressing F8 again closes enlarged EVF window cleanly
                owner.scene.root.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F8, false, false, false, false))
                assertFalse(studio.isEvfWindowShowing())
            }
        }
        try {
            Platform.startup(checkEvf)
        } catch (_: IllegalStateException) {
            Platform.runLater(checkEvf)
        }
        checkEvf.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun viewModeButtonsAndF7RespectCalibratedSingleStepVirtualTickHold() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val viewModeCommands = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val virtualTick = java.util.concurrent.atomic.AtomicLong(2_000_000_000L)
        val releasedLatch = java.util.concurrent.atomic.AtomicReference<CompletableFuture<Unit>>(CompletableFuture())
        val studioRef = CompletableFuture<CameraStudio>()

        val setup = FutureTask {
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder) }
            val studio = CameraStudio(
                owner,
                { _, _ -> CompletableFuture.completedFuture(Unit) },
                {},
                {},
                {},
                momentaryHoldPolicy = MomentaryHoldPolicy.firmwareCalibrated(pollIntervalMs = 5L),
                virtualTick = { virtualTick.get() },
                isSessionAdvancing = { true },
                sendViewMode = { pressed ->
                    viewModeCommands += pressed
                    if (!pressed) releasedLatch.get().complete(Unit)
                    CompletableFuture.completedFuture(mapOf("ok" to true, "pressed" to pressed))
                },
            )
            studioRef.complete(studio)
            holder.children += studio.root
            Platform.setImplicitExit(false)
            owner.show()
            studio.setFaceVisible("rear", true)
            studio.setEvfWindowVisible(true)
        }
        try {
            Platform.startup(setup)
        } catch (_: IllegalStateException) {
            Platform.runLater(setup)
        }
        setup.get(30, TimeUnit.SECONDS)

        fun verifyCalibratedPulse(name: String, trigger: (CameraStudio) -> Unit) {
            val releaseFuture = CompletableFuture<Unit>()
            releasedLatch.set(releaseFuture)
            val fireTask = FutureTask {
                viewModeCommands.clear()
                trigger(studioRef.get())
                // Immediately after quick tap / .fire(), VIEW MODE must stay held (NOT 0-tick released!)
                assertEquals(listOf(true), viewModeCommands.toList(), "$name must hold VIEW MODE active across 25ms x deb=2 floor")
            }
            Platform.runLater(fireTask)
            fireTask.get(10, TimeUnit.SECONDS)

            // Below calibrated 4.05M floor -> still held
            virtualTick.addAndGet(3_000_000L)
            Thread.sleep(25)
            assertFalse(releaseFuture.isDone, "$name must not release below calibrated 4.05M virtual-tick floor")

            // Cross calibrated 4.05M floor -> releases cleanly
            virtualTick.addAndGet(1_100_000L)
            releaseFuture.get(1, TimeUnit.SECONDS)
            assertEquals(listOf(true, false), viewModeCommands.toList(), "$name must release once calibrated floor elapses")
        }

        try {
            // 1. Rear body VIEW MODE hotspot quick mouse click (must survive scene MOUSE_RELEASED filter!)
            verifyCalibratedPulse("rear-view-mode click") { _ ->
                val rearStage = javafx.stage.Window.getWindows()
                    .filterIsInstance<Stage>()
                    .single { it.isShowing && it.title?.contains("REAR") == true }
                val btn = rearStage.scene.lookup("#inspect-rear-view-mode") as javafx.scene.control.Button
                val c = btn.localToScene(btn.width / 2.0, btn.height / 2.0)
                btn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, c.x, c.y, c.x, c.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                btn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, c.x, c.y, c.x, c.y, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            }
            // 2. Rear body VIEW MODE .fire() (keyboard Space/Enter / accessibility action)
            verifyCalibratedPulse("rear-view-mode action") { _ ->
                val rearStage = javafx.stage.Window.getWindows()
                    .filterIsInstance<Stage>()
                    .single { it.isShowing && it.title?.contains("REAR") == true }
                (rearStage.scene.lookup("#inspect-rear-view-mode") as javafx.scene.control.Button).fire()
            }
            // 3. Quick Viewfinder card button .fire()
            verifyCalibratedPulse("quick-view-mode action") { studio ->
                (studio.root.lookup("#quick-view-mode-btn") as javafx.scene.control.Button).fire()
            }
            // 4. Keyboard F7 quick tap
            verifyCalibratedPulse("F7 shortcut") { studio ->
                val root = studio.root.scene.root
                root.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F7, false, false, false, false))
                root.fireEvent(KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.F7, false, false, false, false))
            }
            // 5. Enlarged EVF window VIEW MODE button .fire()
            verifyCalibratedPulse("evf-window-view-mode action") { _ ->
                val evfStage = javafx.stage.Window.getWindows()
                    .filterIsInstance<Stage>()
                    .single { it.isShowing && it.title?.contains("EVF") == true }
                (evfStage.scene.lookup("#evf-window-view-mode") as javafx.scene.control.Button).fire()
            }
        } finally {
            val close = FutureTask { studioRef.get().close(releaseLatching = false) }
            Platform.runLater(close)
            close.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun modeDialRearDialRotationAndAdcButtonsDispatchCalibratedSettings() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val settingCommands = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, Any>>())
        val buttonCommands = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
        val virtualTick = java.util.concurrent.atomic.AtomicLong(3_000_000_000L)
        val releasedLatch = java.util.concurrent.atomic.AtomicReference<CompletableFuture<Unit>>(CompletableFuture())
        val studioRef = CompletableFuture<CameraStudio>()

        val setup = FutureTask {
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder) }
            val studio = CameraStudio(
                owner,
                { key, down ->
                    buttonCommands += key to down
                    CompletableFuture.completedFuture(Unit)
                },
                {},
                {},
                {},
                sendSetting = { componentId, settingKey, value ->
                    settingCommands += Triple(componentId, settingKey, value)
                    if (!value) releasedLatch.get().complete(Unit)
                    CompletableFuture.completedFuture(Unit)
                },
                sendSettingValue = { componentId, settingKey, value ->
                    settingCommands += Triple(componentId, settingKey, value)
                    if (value == false) releasedLatch.get().complete(Unit)
                    CompletableFuture.completedFuture(Unit)
                },
                momentaryHoldPolicy = MomentaryHoldPolicy.firmwareCalibrated(pollIntervalMs = 5L),
                virtualTick = { virtualTick.get() },
                isSessionAdvancing = { true },
            )
            studioRef.complete(studio)
            holder.children += studio.root
            Platform.setImplicitExit(false)
            owner.show()
            studio.setFaceVisible("rear", true)
            studio.setFaceVisible("top", true)

            // 1. Observe initial soc-adc state -> syncs quick mode dial ComboBox & rear dial detent readout
            studio.observeSnapshot(
                mapOf(
                    "components" to listOf(
                        mapOf("id" to "buttons", "state" to PanelCatalog.buttonKeys.associateWith { it == "power" }),
                        mapOf("id" to "soc-adc", "state" to mapOf("modeDial" to "A", "rearDialDetents" to 5L)),
                    ),
                ),
            )
            @Suppress("UNCHECKED_CAST")
            val modeBox = holder.lookup("#quick-mode-dial") as javafx.scene.control.ComboBox<String>
            assertEquals("A", modeBox.value)
            assertEquals("5", (holder.lookup("#readback-rear-dial-detents") as Label).text)
            assertEquals("A", studio.state()["modeDial"])
            assertEquals(5L, studio.state()["rearDialDetents"])

            // 2. Top body mode dial click cycles A -> M, and quick ComboBox selects C1
            val topStage = javafx.stage.Window.getWindows()
                .filterIsInstance<Stage>()
                .single { it.isShowing && it.title?.contains("TOP") == true }
            (topStage.scene.lookup("#inspect-top-mode-dial") as javafx.scene.control.Button).fire()
            assertEquals(listOf(Triple<String, String, Any>("soc-adc", "modeDial", "M")), settingCommands.toList())
            assertEquals("M", modeBox.value)

            modeBox.value = "C1"
            assertEquals(Triple<String, String, Any>("soc-adc", "modeDial", "C1"), settingCommands.last())

            // 3. Rear command dial quick CW/CCW buttons & body scroll wheel step cumulative rearDialDetents
            settingCommands.clear()
            (holder.lookup("#quick-rear-dial-cw") as javafx.scene.control.Button).fire()
            (holder.lookup("#quick-rear-dial-ccw") as javafx.scene.control.Button).fire()
            (holder.lookup("#quick-rear-dial-ccw") as javafx.scene.control.Button).fire()
            assertEquals(
                listOf(
                    Triple<String, String, Any>("soc-adc", "rearDialDetents", 6L),
                    Triple<String, String, Any>("soc-adc", "rearDialDetents", 5L),
                    Triple<String, String, Any>("soc-adc", "rearDialDetents", 4L),
                ),
                settingCommands.toList(),
            )
            assertEquals("4", (holder.lookup("#readback-rear-dial-detents") as Label).text)

            // 4. New GPIO controls (drive, afOn, focusMode) present in side grid & latching focusMode toggles cleanly
            buttonCommands.clear()
            (holder.lookup("#input-focusMode") as ToggleButton).fire()
            assertEquals(listOf("focusMode" to true), buttonCommands.toList())
        }
        try {
            Platform.startup(setup)
        } catch (_: IllegalStateException) {
            Platform.runLater(setup)
        }
        setup.get(30, TimeUnit.SECONDS)

        try {
            // 5. Momentary ADC buttons (rear-q, top-fn1) honor calibrated 4.05M virtual-tick hold
            for ((faceTitle, nodeId, settingKey) in listOf(
                Triple("REAR", "#inspect-rear-q", "qButton"),
                Triple("TOP", "#inspect-top-fn1", "fn1Button"),
            )) {
                val focused = CompletableFuture<Stage>()
                Platform.runLater {
                    val stage = javafx.stage.Window.getWindows().filterIsInstance<Stage>()
                        .single { it.isShowing && it.title?.contains(faceTitle) == true }
                    if (stage.isFocused) {
                        focused.complete(stage)
                    } else {
                        stage.focusedProperty().addListener { _, _, hasFocus ->
                            if (hasFocus) focused.complete(stage)
                        }
                        stage.toFront()
                        stage.requestFocus()
                    }
                }
                val stage = focused.get(10, TimeUnit.SECONDS)
                val releaseFuture = CompletableFuture<Unit>()
                releasedLatch.set(releaseFuture)
                val fireTask = FutureTask {
                    settingCommands.clear()
                    val btn = stage.scene.lookup(nodeId) as javafx.scene.control.Button
                    val c = btn.localToScene(btn.width / 2.0, btn.height / 2.0)
                    btn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, c.x, c.y, c.x, c.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                    btn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, c.x, c.y, c.x, c.y, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
                    assertEquals(listOf(Triple<String, String, Any>("soc-adc", settingKey, true)), settingCommands.toList())
                }
                Platform.runLater(fireTask)
                fireTask.get(10, TimeUnit.SECONDS)

                virtualTick.addAndGet(3_000_000L)
                Thread.sleep(25)
                assertFalse(releaseFuture.isDone, "$settingKey must stay held below 4.05M virtual-tick floor")

                virtualTick.addAndGet(1_100_000L)
                releaseFuture.get(1, TimeUnit.SECONDS)
                assertEquals(
                    listOf(
                        Triple<String, String, Any>("soc-adc", settingKey, true),
                        Triple<String, String, Any>("soc-adc", settingKey, false),
                    ),
                    settingCommands.toList(),
                )
            }
        } finally {
            val close = FutureTask { studioRef.get().close(releaseLatching = false) }
            Platform.runLater(close)
            close.get(10, TimeUnit.SECONDS)
        }
    }

    @Test fun rearLcdCalibratedTouchHoldSurvivesQuickClickAndSupportsDragAndRapidRetap() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val virtualTick = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val touchEvents = java.util.Collections.synchronizedList(mutableListOf<Triple<Int, Int, Boolean>>())
        val releaseFutureRef = java.util.concurrent.atomic.AtomicReference<CompletableFuture<Unit>?>(null)
        val studioRef = CompletableFuture<CameraStudio>()
        val setup = FutureTask {
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder) }
            val studio = CameraStudio(
                owner,
                { _, _ -> CompletableFuture.completedFuture(Unit) },
                {},
                {},
                {},
                momentaryHoldPolicy = MomentaryHoldPolicy.firmwareCalibrated(),
                virtualTick = { virtualTick.get() },
                isSessionAdvancing = { true },
                sendTouch = { x, y, pressed ->
                    touchEvents += Triple(x, y, pressed)
                    if (!pressed) releaseFutureRef.getAndSet(null)?.complete(Unit)
                    CompletableFuture.completedFuture(Unit)
                },
            )
            studioRef.complete(studio)
            holder.children += studio.root
            Platform.setImplicitExit(false)
            owner.show()
            studio.setFaceVisible("rear", true)
            // Sync touchEnabled from standalone rear-touch-ic snapshot (overrides disabled legacy i2c-bus flag)
            studio.observeSnapshot(
                mapOf(
                    "components" to listOf(
                        mapOf("id" to "i2c-bus", "state" to mapOf("touchEnabled" to false)),
                        mapOf("id" to "rear-touch-ic", "state" to mapOf("enabled" to true, "touchEnabled" to true)),
                    ),
                ),
            )
            val rearStage = javafx.stage.Window.getWindows()
                .filterIsInstance<Stage>()
                .single { it !== owner && it.title?.contains("REAR") == true }
            rearStage.scene.root.applyCss()
            rearStage.scene.root.layout()
            val lcdButton = rearStage.scene.lookup("#inspect-rear-lcd") as javafx.scene.control.Button
            assertTrue(lcdButton.width > 100.0 && lcdButton.height > 100.0)

            // 1. Quick click near (260, 260) on 1024x768 must stay held across immediate MOUSE_RELEASED
            val p1 = lcdButton.localToScene(lcdButton.width * (260.0 / 1024.0), lcdButton.height * (260.0 / 768.0))
            val release1 = CompletableFuture<Unit>()
            releaseFutureRef.set(release1)
            lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, p1.x, p1.y, p1.x, p1.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, p1.x, p1.y, p1.x, p1.y, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            assertEquals(1, touchEvents.size, "Quick click on rear-lcd must hold pressed=true until calibrated virtual-tick floor")
            assertEquals(true, touchEvents[0].third)
            assertTrue(touchEvents[0].first in 252..268, "Expected X near 260, got ${touchEvents[0].first}")
            assertTrue(touchEvents[0].second in 252..268, "Expected Y near 260, got ${touchEvents[0].second}")
            release1
        }
        try {
            Platform.startup(setup)
        } catch (_: IllegalStateException) {
            Platform.runLater(setup)
        }
        val release1 = setup.get(30, TimeUnit.SECONDS)

        try {
            // Below 5.67M DAT-calibrated touch floor (105 ms @ 54 MHz > 80 ms state1->state3 gate): must remain pressed
            virtualTick.addAndGet(5_000_000L)
            Thread.sleep(25)
            assertFalse(release1.isDone, "Touch must remain held below calibrated 5.67M virtual-tick floor")

            // Cross 5.67M floor -> touch releases promptly
            virtualTick.addAndGet(800_000L)
            release1.get(1, TimeUnit.SECONDS)
            assertEquals(2, touchEvents.size)
            assertEquals(false, touchEvents[1].third)

            // 2. Rapid re-tap at (740, 520) while prior tap is still in hold tail flushes old release then presses new point
            val rapidRetap = FutureTask {
                touchEvents.clear()
                val rearStage = javafx.stage.Window.getWindows()
                    .filterIsInstance<Stage>()
                    .single { it.isShowing && it.title?.contains("REAR") == true }
                val lcdButton = rearStage.scene.lookup("#inspect-rear-lcd") as javafx.scene.control.Button
                val p1 = lcdButton.localToScene(lcdButton.width * (260.0 / 1024.0), lcdButton.height * (260.0 / 768.0))
                val p2 = lcdButton.localToScene(lcdButton.width * (740.0 / 1024.0), lcdButton.height * (520.0 / 768.0))
                lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, p1.x, p1.y, p1.x, p1.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, p1.x, p1.y, p1.x, p1.y, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
                assertEquals(1, touchEvents.size)
                // Immediately click second focus point (740, 520) before hold timer finishes
                lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, p2.x, p2.y, p2.x, p2.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                assertEquals(3, touchEvents.size, "Rapid re-tap must flush prior tap release and dispatch new touch down")
                assertEquals(true, touchEvents[0].third)
                assertEquals(false, touchEvents[1].third)
                assertEquals(true, touchEvents[2].third)
                assertTrue(touchEvents[2].first in 732..748, "Expected second tap X near 740, got ${touchEvents[2].first}")
                assertTrue(touchEvents[2].second in 512..528, "Expected second tap Y near 520, got ${touchEvents[2].second}")

                // 3. Smooth MOUSE_DRAGGED while held updates coordinates without releasing
                Thread.sleep(20)
                val p3 = lcdButton.localToScene(lcdButton.width * (512.0 / 1024.0), lcdButton.height * (384.0 / 768.0))
                lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_DRAGGED, p3.x, p3.y, p3.x, p3.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                assertEquals(4, touchEvents.size, "Dragged touch must emit updated pressed=true coordinate")
                assertEquals(true, touchEvents[3].third)
                assertTrue(touchEvents[3].first in 505..518, "Expected dragged X near 512, got ${touchEvents[3].first}")
                assertTrue(touchEvents[3].second in 378..390, "Expected dragged Y near 384, got ${touchEvents[3].second}")

                // 4. releaseAll() cancels pending hold and immediately releases active touch
                studioRef.get().releaseAll(includeLatching = false)
                assertEquals(5, touchEvents.size)
                assertEquals(false, touchEvents[4].third)

                // 5. setTouchEnabled(false) cancels pending hold and immediately releases active touch
                touchEvents.clear()
                lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, p1.x, p1.y, p1.x, p1.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, p1.x, p1.y, p1.x, p1.y, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
                assertEquals(1, touchEvents.size)
                studioRef.get().setTouchEnabled(false)
                assertEquals(2, touchEvents.size)
                assertEquals(false, touchEvents[1].third)

                // 6. Dragged swipe > 20 px dispatches MOVE immediately (bypassing 16ms wall throttle) and
                //    releases immediately on MOUSE_RELEASED (bypassing 105ms stationary-tap hold floor & tiny <20px tail)
                studioRef.get().setTouchEnabled(true)
                touchEvents.clear()
                val swipeStart = lcdButton.localToScene(lcdButton.width * (760.0 / 1024.0), lcdButton.height * (384.0 / 768.0))
                val swipeEnd = lcdButton.localToScene(lcdButton.width * (240.0 / 1024.0), lcdButton.height * (384.0 / 768.0))
                val swipeTail = lcdButton.localToScene(lcdButton.width * (235.0 / 1024.0), lcdButton.height * (384.0 / 768.0))
                lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, swipeStart.x, swipeStart.y, swipeStart.x, swipeStart.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_DRAGGED, swipeEnd.x, swipeEnd.y, swipeEnd.x, swipeEnd.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, swipeTail.x, swipeTail.y, swipeTail.x, swipeTail.y, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
                assertEquals(3, touchEvents.size, "Dragged swipe must emit DOWN -> MOVE -> immediate UP without 105ms hold or sub-20px tail MOVE")
                assertEquals(true, touchEvents[0].third)
                assertEquals(true, touchEvents[1].third)
                assertEquals(false, touchEvents[2].third)
                assertTrue(touchEvents[0].first in 750..770 && touchEvents[1].first in 230..250 && touchEvents[2].first in 230..250)

                // 7. Ultra-fast <16ms flick (PRESS -> RELEASE > 20 px with no DRAGGED event) synthesizes
                //    final MOVE(pressed=true) before UP and releases after 1.08M ticks (20ms virtual), not 5.67M ticks!
                touchEvents.clear()
                val flickRelease = CompletableFuture<Unit>()
                releaseFutureRef.set(flickRelease)
                lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, swipeStart.x, swipeStart.y, swipeStart.x, swipeStart.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
                lcdButton.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, swipeEnd.x, swipeEnd.y, swipeEnd.x, swipeEnd.y, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
                assertEquals(2, touchEvents.size, "Ultra-fast flick must synthesize MOVE(pressed=true) before UP")
                assertEquals(true, touchEvents[0].third)
                assertEquals(true, touchEvents[1].third)
                assertTrue(touchEvents[1].first in 230..250)
                flickRelease
            }
            Platform.runLater(rapidRetap)
            val flickRelease = rapidRetap.get(10, TimeUnit.SECONDS)
            virtualTick.addAndGet(MomentaryHoldPolicy.CALIBRATED_TOUCH_SWIPE_STEP_VIRTUAL_TICKS + 100_000L)
            flickRelease.get(1, TimeUnit.SECONDS)
            assertEquals(3, touchEvents.size)
            assertEquals(false, touchEvents[2].third)
        } finally {
            val close = FutureTask { studioRef.get().close(releaseLatching = false) }
            Platform.runLater(close)
            close.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun shootingControlsPaneWiresModeDialFocusModeAndStillMovieAndCollapsesPhysicalMatrixByDefault() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val buttonCommands = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Boolean>>()
        val valueSettings = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, Any>>()
        val studioRef = java.util.concurrent.atomic.AtomicReference<CameraStudio>()

        val task = FutureTask {
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder, 420.0, 720.0) }
            val studio = CameraStudio(
                owner = owner,
                sendButton = { key, down ->
                    buttonCommands += key to down
                    CompletableFuture.completedFuture(Unit)
                },
                configure = {},
                inspect = {},
                failure = { throw AssertionError(it) },
                sendSettingValue = { comp, key, value ->
                    valueSettings += Triple(comp, key, value)
                    CompletableFuture.completedFuture(Unit)
                },
            )
            studioRef.set(studio)
            holder.children += studio.root
            holder.applyCss()
            holder.layout()

            val matrixBody = holder.lookup("#physical-controls-body") as javafx.scene.layout.VBox
            val matrixToggle = holder.lookup("#physical-controls-toggle") as javafx.scene.control.Button
            val readbackBody = holder.lookup("#raw-readback-body") as javafx.scene.layout.VBox
            val readbackToggle = holder.lookup("#raw-readback-toggle") as javafx.scene.control.Button
            assertTrue(matrixBody.isVisible, "Physical buttons panel must be expanded by default for photographer-first priority")
            assertTrue(matrixBody.isManaged)
            assertTrue(readbackBody.isVisible, "Raw hardware readback card must also be expanded by default")
            assertTrue(readbackBody.isManaged)
            assertNull(matrixBody.lookup("#quick-mode-dial"), "Redundant mode dial dropdown must not clutter Physical Buttons card")
            assertNotNull(readbackBody.lookup("#quick-mode-dial"), "Mode dial raw override must reside in Raw Readback card")
            assertNotNull(matrixBody.lookup("#physical-dpad-grid"), "Physical buttons must present a tactile 3x3 D-pad cluster")
            matrixToggle.fire()
            assertFalse(matrixBody.isVisible, "Clicking toggle must collapse physical buttons panel")
            assertFalse(matrixBody.isManaged)
            matrixToggle.fire()
            assertTrue(matrixBody.isVisible, "Clicking toggle again must re-expand physical buttons panel")
            assertTrue(matrixBody.isManaged)
            readbackToggle.fire()
            assertFalse(readbackBody.isVisible, "Clicking readback toggle must collapse raw readback card")
            assertFalse(readbackBody.isManaged)
            readbackToggle.fire()
            assertTrue(readbackBody.isVisible, "Clicking readback toggle again must re-expand raw readback card")
            assertTrue(readbackBody.isManaged)

            // Verify photographer-first sidebar card order: Shooting Controls (#0) -> Physical Buttons (#1) -> Raw Readback (#2) -> Viewfinder (#3) -> Faces (#4) -> Source (#5)
            val centerCards = (studio.root.center as javafx.scene.layout.VBox).children
            assertEquals("shooting-controls-card", centerCards[0].id)
            assertNotNull(centerCards[1].lookup("#physical-controls-body"), "Physical buttons card must be #2 in sidebar")
            assertNotNull(centerCards[2].lookup("#raw-readback-body"), "Raw readback card must be #3 in sidebar")
            assertNotNull(centerCards[3].lookup("#viewfinder-active-status"), "Viewfinder card must be #4 in sidebar")
            assertNotNull(centerCards[4].lookup("#show-face-rear"), "Camera faces card must be #5 in sidebar")
            assertNotNull(centerCards[5].lookup("#source-mode-3d"), "Scene source card must be #6 in sidebar")

            studio.observeSnapshot(
                mapOf(
                    "components" to listOf(
                        mapOf("id" to "buttons", "state" to PanelCatalog.buttonKeys.associateWith { false }),
                        mapOf("id" to "soc-gpio", "state" to mapOf("focusMode" to "AF-C", "effectiveFocusMode" to "AF-C")),
                        mapOf("id" to "soc-adc", "state" to mapOf("modeDial" to "M")),
                        mapOf("id" to "rear-touch-ic", "state" to mapOf("enabled" to true, "touchEnabled" to true)),
                    ),
                ),
            )
            assertEquals("STILL · M · AF-C", (holder.lookup("#shooting-controls-badge") as Label).text)
            assertTrue((holder.lookup("#shooting-mode-still") as ToggleButton).isSelected)
            assertFalse((holder.lookup("#shooting-mode-movie") as ToggleButton).isSelected)
            assertTrue((holder.lookup("#shooting-focus-afc") as ToggleButton).isSelected)
            assertTrue((holder.lookup("#shooting-dial-M") as ToggleButton).isSelected)

            (holder.lookup("#shooting-dial-A") as ToggleButton).fire()
            (holder.lookup("#shooting-focus-mf") as ToggleButton).fire()
            (holder.lookup("#shooting-mode-movie") as ToggleButton).fire()

            assertTrue(Triple("soc-adc", "modeDial", "A") in valueSettings, "Expected soc-adc.modeDial=A, got $valueSettings")
            assertTrue(Triple("soc-gpio", "focusMode", "MF") in valueSettings, "Expected soc-gpio.focusMode=MF, got $valueSettings")
            assertTrue(Pair("movieMode", true) in buttonCommands, "Expected buttons.movieMode=true, got $buttonCommands")
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

    @Test fun rightSideButtonLockUnlockSupportsGpioAdcAndViewModeCombosWithBilingualUi() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val studioRef = java.util.concurrent.atomic.AtomicReference<CameraStudio?>()
        val task = FutureTask {
            I18n.resetToDefault()
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder) }
            val buttonCommands = mutableListOf<Pair<String, Boolean>>()
            val settingCommands = mutableListOf<Triple<String, String, Any>>()
            val viewModeCommands = mutableListOf<Boolean>()
            val studio = CameraStudio(
                owner,
                { key, down ->
                    buttonCommands += key to down
                    CompletableFuture.completedFuture(Unit)
                },
                {},
                {},
                {},
                sendSettingValue = { comp, key, value ->
                    settingCommands += Triple(comp, key, value)
                    CompletableFuture.completedFuture(Unit)
                },
                sendViewMode = { pressed ->
                    viewModeCommands += pressed
                    CompletableFuture.completedFuture(Unit)
                },
            )
            studioRef.set(studio)
            holder.children += studio.root
            Platform.setImplicitExit(false)
            owner.show()
            studio.setFaceVisible("rear", true)
            holder.applyCss()
            holder.layout()

            // 1. Momentary GPIO keys have inline lock toggles; latching switches do not
            for (key in PanelCatalog.buttonKeys) {
                val lockNode = holder.lookup("#lock-$key")
                if (key in PanelCatalog.latchingButtonKeys) {
                    assertNull(lockNode, "Latching switch '$key' must not show a momentary lock toggle")
                } else {
                    assertNotNull(lockNode, "Momentary GPIO key '$key' must provide inline #lock-$key toggle")
                }
            }
            val banner = holder.lookup("#combo-lock-active-strip") as javafx.scene.layout.HBox
            val bannerLabel = holder.lookup("#combo-lock-banner-label") as Label
            val summaryLabel = holder.lookup("#combo-lock-summary") as Label
            val toggleBtn = holder.lookup("#physical-controls-toggle") as javafx.scene.control.Button
            val unlockAllBtn = holder.lookup("#combo-lock-unlock-all") as javafx.scene.control.Button
            assertFalse(banner.isVisible, "Locked banner must be hidden when zero keys are locked")
            assertTrue(unlockAllBtn.isDisable)

            // 2. Lock DISP/BACK & MENU/OK via inline lock icons -> both held simultaneously + visual accent
            (holder.lookup("#lock-back") as ToggleButton).fire()
            (holder.lookup("#lock-menu") as ToggleButton).fire()
            assertEquals(listOf("back" to true, "menu" to true), buttonCommands)
            assertTrue(studio.isButtonLocked("back") && studio.isButtonLocked("menu"))
            assertTrue("studio-key-locked" in (holder.lookup("#input-back") as ToggleButton).styleClass)
            assertTrue("studio-key-locked" in studio.buttons.getValue("back").styleClass)
            assertTrue(banner.isVisible)
            assertTrue(toggleBtn.text.contains("2"), "Collapsed toggle must surface locked count: ${toggleBtn.text}")
            assertFalse(unlockAllBtn.isDisable)

            // 3. Right-click momentary GPIO button locks shutterHalf directly
            val shutterBtn = holder.lookup("#input-shutterHalf") as ToggleButton
            shutterBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 1.0, 1.0, 1.0, 1.0, MouseButton.SECONDARY, 1, false, false, false, false, false, false, true, false, false, true, null))
            shutterBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 1.0, 1.0, 1.0, 1.0, MouseButton.SECONDARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            assertTrue(studio.isButtonLocked("shutterHalf"))
            assertEquals("shutterHalf" to true, buttonCommands.last())

            // 4. Shift+Click locks UP; locking opposite DOWN automatically unlocks UP
            val upBtn = holder.lookup("#input-up") as ToggleButton
            upBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, true, false, false, false, true, false, false, false, false, true, null))
            upBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, true, false, false, false, false, false, false, false, false, true, null))
            assertTrue(studio.isButtonLocked("up"))
            (holder.lookup("#lock-down") as ToggleButton).fire()
            assertFalse(studio.isButtonLocked("up"))
            assertTrue(studio.isButtonLocked("down"))

            // 5. Lock ADC buttons (inline icon on Fn1 + right-click on Q) and VIEW MODE lock icon
            (holder.lookup("#lock-adc-top-fn1") as ToggleButton).fire()
            val qBtn = holder.lookup("#quick-adc-rear-q") as javafx.scene.control.Button
            qBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 1.0, 1.0, 1.0, 1.0, MouseButton.SECONDARY, 1, false, false, false, false, false, false, true, false, false, true, null))
            qBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 1.0, 1.0, 1.0, 1.0, MouseButton.SECONDARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            (holder.lookup("#lock-view-mode") as ToggleButton).fire()

            assertTrue(studio.isMomentarySettingLocked("top-fn1"))
            assertTrue(studio.isMomentarySettingLocked("rear-q"))
            assertTrue(studio.isViewModeLocked())
            assertTrue(Triple<String, String, Any>("soc-adc", "fn1Button", true) in settingCommands)
            assertTrue(Triple<String, String, Any>("soc-adc", "qButton", true) in settingCommands)
            assertEquals(listOf(true), viewModeCommands)
            assertTrue("studio-key-locked" in (holder.lookup("#quick-adc-top-fn1") as javafx.scene.control.Button).styleClass)
            assertTrue("studio-key-locked" in (holder.lookup("#quick-view-mode-btn") as javafx.scene.control.Button).styleClass)

            // 6. Switching camera views and hiding rear face MUST preserve all locked GPIO, ADC, and VIEW MODE buttons
            val btnCountBefore = buttonCommands.size
            val settingCountBefore = settingCommands.size
            val vmCountBefore = viewModeCommands.size
            studio.selectView("top")
            studio.setFaceVisible("rear", false)
            assertEquals(btnCountBefore, buttonCommands.size, "View switch/hide must not release locked GPIO keys")
            assertEquals(settingCountBefore, settingCommands.size, "View switch/hide must not release locked ADC keys")
            assertEquals(vmCountBefore, viewModeCommands.size, "View switch/hide must not release locked VIEW MODE")

            // 7. Left-clicking an already-locked button unlocks and releases it cleanly
            val menuBtn = holder.lookup("#input-menu") as ToggleButton
            menuBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            menuBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            assertFalse(studio.isButtonLocked("menu"))
            assertEquals("menu" to false, buttonCommands.last())

            // 8. Live bilingual switch updates lock banner, summary, and Unlock All button text
            I18n.setLocale(I18n.CODE_ZH_CN)
            assertEquals(I18n.t("studio.lock.unlock_all"), unlockAllBtn.text)
            assertTrue(bannerLabel.text.contains("已锁定"), "Expected Chinese locked banner, got: ${bannerLabel.text}")
            assertTrue(summaryLabel.text.contains("已锁定"), "Expected Chinese locked summary, got: ${summaryLabel.text}")
            I18n.resetToDefault()
            assertEquals(I18n.t("studio.lock.unlock_all"), unlockAllBtn.text)
            assertTrue(bannerLabel.text.contains("Locked"), "Expected English locked banner, got: ${bannerLabel.text}")

            // 9. One-click Unlock All releases every remaining locked GPIO, ADC, and VIEW MODE button
            (holder.lookup("#combo-lock-banner-unlock-all") as javafx.scene.control.Button).fire()
            assertTrue(studio.lockedButtonSummaryIds().isEmpty())
            assertFalse(banner.isVisible)
            assertTrue(unlockAllBtn.isDisable)
            assertFalse("studio-key-locked" in (holder.lookup("#input-back") as ToggleButton).styleClass)
            assertFalse("studio-key-locked" in (holder.lookup("#quick-view-mode-btn") as javafx.scene.control.Button).styleClass)
            assertTrue(Triple<String, String, Any>("soc-adc", "fn1Button", false) in settingCommands)
            assertTrue(Triple<String, String, Any>("soc-adc", "qButton", false) in settingCommands)
            assertEquals(listOf(true, false), viewModeCommands)
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
            I18n.resetToDefault()
            val close = FutureTask { studioRef.get()?.close(releaseLatching = false) }
            Platform.runLater(close)
            close.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun evHoldToggleLatchesAndReleasesTopPlateFn1AdcButtonForSinglePointerRearDialComp() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val settingCommands = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, Boolean>>()
        val studioRef = java.util.concurrent.atomic.AtomicReference<CameraStudio>()

        val task = FutureTask {
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder, 420.0, 720.0) }
            val studio = CameraStudio(
                owner = owner,
                sendButton = { _, _ -> CompletableFuture.completedFuture(Unit) },
                configure = {},
                inspect = {},
                failure = { throw AssertionError(it) },
                sendSetting = { comp, key, value ->
                    settingCommands += Triple(comp, key, value)
                    CompletableFuture.completedFuture(Unit)
                },
            )
            studioRef.set(studio)
            holder.children += studio.root
            holder.applyCss()
            holder.layout()

            val evHold = holder.lookup("#quick-adc-ev-hold") as ToggleButton
            assertEquals("± Hold", evHold.text)
            assertFalse(evHold.isSelected)

            // First click latches soc-adc.fn1Button=true so user can turn Rear Dial with single pointer
            evHold.fire()
            assertTrue(evHold.isSelected)
            assertEquals(listOf(Triple("soc-adc", "fn1Button", true)), settingCommands)

            // Second click releases soc-adc.fn1Button=false
            evHold.fire()
            assertFalse(evHold.isSelected)
            assertEquals(
                listOf(
                    Triple("soc-adc", "fn1Button", true),
                    Triple("soc-adc", "fn1Button", false),
                ),
                settingCommands,
            )
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

    @Test
    fun bodyLeversAndDraggableDialsSwitchCleanlyAcrossBodyAndSidebarWithPendingGuards() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val buttonCommands = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Boolean>>()
        val valueSettings = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, Any>>()
        val pendingButtonReplies = java.util.concurrent.CopyOnWriteArrayList<CompletableFuture<Unit>>()
        val pendingSettingReplies = java.util.concurrent.CopyOnWriteArrayList<CompletableFuture<Unit>>()
        var deferReplies = false
        val studioRef = java.util.concurrent.atomic.AtomicReference<CameraStudio>()

        val task = FutureTask {
            I18n.resetToDefault()
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder, 420.0, 780.0) }
            val studio = CameraStudio(
                owner = owner,
                sendButton = { key, down ->
                    buttonCommands += key to down
                    if (deferReplies) CompletableFuture<Unit>().also(pendingButtonReplies::add)
                    else CompletableFuture.completedFuture(Unit)
                },
                configure = {},
                inspect = {},
                failure = { throw AssertionError(it) },
                sendSetting = { comp, key, value ->
                    valueSettings += Triple(comp, key, value)
                    CompletableFuture.completedFuture(Unit)
                },
                sendSettingValue = { comp, key, value ->
                    valueSettings += Triple(comp, key, value)
                    if (deferReplies) CompletableFuture<Unit>().also(pendingSettingReplies::add)
                    else CompletableFuture.completedFuture(Unit)
                },
            )
            studioRef.set(studio)
            holder.children += studio.root
            Platform.setImplicitExit(false)
            owner.show()
            studio.setFaceVisible("rear", true)
            studio.setFaceVisible("top", true)
            studio.setFaceVisible("front", true)
            holder.applyCss()
            holder.layout()

            studio.observeSnapshot(
                mapOf(
                    "components" to listOf(
                        mapOf("id" to "buttons", "state" to PanelCatalog.buttonKeys.associateWith { it == "power" }),
                        mapOf("id" to "soc-gpio", "state" to mapOf("focusMode" to "AF-S", "effectiveFocusMode" to "AF-S", "frontDialDetents" to 0L)),
                        mapOf("id" to "soc-adc", "state" to mapOf("modeDial" to "P", "rearDialDetents" to 0L)),
                    ),
                ),
            )

            val topStage = javafx.stage.Window.getWindows().filterIsInstance<Stage>().single { it.isShowing && it.title?.contains("TOP") == true }
            val rearStage = javafx.stage.Window.getWindows().filterIsInstance<Stage>().single { it.isShowing && it.title?.contains("REAR") == true }
            val frontStage = javafx.stage.Window.getWindows().filterIsInstance<Stage>().single { it.isShowing && it.title?.contains("FRONT") == true }

            // 0. Rear LCD touch surface must not show an intrusive hover tooltip; control tooltips must be free of stale Phase-1 metadata
            val lcdHotspot = rearStage.scene.lookup("#inspect-rear-lcd") as javafx.scene.control.Button
            assertNull(lcdHotspot.tooltip, "Rear LCD touchscreen must not show a hover tooltip over the viewfinder")
            val menuTip = (rearStage.scene.lookup("#button-menu") as ToggleButton).tooltip?.text.orEmpty()
            assertFalse("UNKNOWN" in menuTip || "host-logic-only" in menuTip || "waiting-for-firmware-frame" in menuTip, "Unexpected stale metadata in tooltip: $menuTip")

            // 1. top-dial-lock center button delegates to modeDial stepping instead of swallowing clicks!
            valueSettings.clear()
            val dialLock = topStage.scene.lookup("#inspect-top-dial-lock") as javafx.scene.control.Button
            dialLock.fire()
            assertEquals(listOf(Triple<String, String, Any>("soc-adc", "modeDial", "S")), valueSettings.toList())
            val modeDialRotor = topStage.scene.lookup("#svg-gfx-mode-dial-rotor")
            assertNotNull(modeDialRotor)
            assertEquals(36.0, modeDialRotor.transforms.filterIsInstance<javafx.scene.transform.Rotate>().first().angle, 0.001)

            // 2. Dragging top-mode-dial horizontally turns the dial cleanly without extra post-drag click step
            valueSettings.clear()
            val modeDialBtn = topStage.scene.lookup("#inspect-top-mode-dial") as javafx.scene.control.Button
            val mdCenter = modeDialBtn.localToScene(modeDialBtn.width / 2.0, modeDialBtn.height / 2.0)
            modeDialBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, modeDialBtn.width / 2.0, modeDialBtn.height / 2.0, mdCenter.x, mdCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            modeDialBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_DRAGGED, modeDialBtn.width / 2.0 + 44.0, modeDialBtn.height / 2.0, mdCenter.x + 44.0, mdCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            modeDialBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, modeDialBtn.width / 2.0 + 44.0, modeDialBtn.height / 2.0, mdCenter.x + 44.0, mdCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            assertTrue(valueSettings.size >= 2, "Expected >=2 modeDial detents on 44px drag, got $valueSettings")
            assertEquals(Triple<String, String, Any>("soc-adc", "modeDial", "M"), valueSettings.last())
            assertEquals(108.0, modeDialRotor.transforms.filterIsInstance<javafx.scene.transform.Rotate>().first().angle, 0.001)

            // 3. rear-focus-selector left-click & drag switch 3-state soc-gpio.focusMode (NOT legacy buttons.focusMode!)
            valueSettings.clear()
            buttonCommands.clear()
            val focusLever = rearStage.scene.lookup("#button-focusMode") as ToggleButton
            val focusRotor = rearStage.scene.lookup("#svg-gfx-focus-lever-rotor")
            assertNotNull(focusRotor)
            focusLever.fire()
            assertEquals(listOf(Triple<String, String, Any>("soc-gpio", "focusMode", "AF-C")), valueSettings.toList())
            assertTrue(buttonCommands.isEmpty(), "Body rear-focus-selector must dispatch soc-gpio.focusMode, not buttons.focusMode")
            assertEquals(0.0, focusRotor.transforms.filterIsInstance<javafx.scene.transform.Rotate>().first().angle, 0.001)
            focusLever.fire()
            assertEquals(Triple<String, String, Any>("soc-gpio", "focusMode", "MF"), valueSettings.last())
            assertEquals(-25.9, focusRotor.transforms.filterIsInstance<javafx.scene.transform.Rotate>().first().angle, 0.001)

            // 4. top-still-movie horizontal drag switches MOVIE (left) / STILL (right) + updates body badge
            buttonCommands.clear()
            val stillMovieLever = topStage.scene.lookup("#button-movieMode") as ToggleButton
            val smCenter = stillMovieLever.localToScene(stillMovieLever.width / 2.0, stillMovieLever.height / 2.0)
            stillMovieLever.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, stillMovieLever.width / 2.0, stillMovieLever.height / 2.0, smCenter.x, smCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            stillMovieLever.fireEvent(MouseEvent(MouseEvent.MOUSE_DRAGGED, stillMovieLever.width / 2.0 - 20.0, stillMovieLever.height / 2.0, smCenter.x - 20.0, smCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            stillMovieLever.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, stillMovieLever.width / 2.0 - 20.0, stillMovieLever.height / 2.0, smCenter.x - 20.0, smCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            assertEquals(listOf("movieMode" to true), buttonCommands.toList())
            assertNull(topStage.scene.lookup("#body-still-movie-indicator"))
            assertEquals(1.0, topStage.scene.lookup("#svg-gfx-movie-selector-movie").opacity, 0.001)
            assertEquals(0.0, topStage.scene.lookup("#svg-gfx-movie-selector-still").opacity, 0.001)

            // 5. Dragging body front-command-dial & rear-command-dial turns detents without firing dialPress; short click pulses dialPress
            valueSettings.clear()
            buttonCommands.clear()
            val frontDialBody = frontStage.scene.lookup("#button-frontDialPress") as ToggleButton
            val fdCenter = frontDialBody.localToScene(frontDialBody.width / 2.0, frontDialBody.height / 2.0)
            frontDialBody.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 10.0, 10.0, fdCenter.x, fdCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            frontDialBody.fireEvent(MouseEvent(MouseEvent.MOUSE_DRAGGED, 46.0, 10.0, fdCenter.x + 36.0, fdCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            frontDialBody.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 46.0, 10.0, fdCenter.x + 36.0, fdCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            assertEquals(
                listOf(
                    Triple<String, String, Any>("soc-gpio", "frontDialDetents", 1L),
                    Triple<String, String, Any>("soc-gpio", "frontDialDetents", 2L),
                ),
                valueSettings.toList(),
            )
            assertTrue(buttonCommands.isEmpty(), "Dragging front-command-dial must not accidentally fire frontDialPress")

            // Short click (<6px) on front-command-dial pulses frontDialPress
            frontDialBody.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 10.0, 10.0, fdCenter.x, fdCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            frontDialBody.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 10.0, 10.0, fdCenter.x, fdCenter.y, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            assertEquals(listOf("frontDialPress" to true, "frontDialPress" to false), buttonCommands.toList())

            val topFrontDial = topStage.scene.lookup("#proxy-top-front-command-dial") as ToggleButton
            val topHitbox = topStage.scene.lookup("#svg-top-front-command-dial") as javafx.scene.shape.Rectangle
            assertEquals(topHitbox.x to topHitbox.y, topFrontDial.layoutX to topFrontDial.layoutY)
            assertEquals(topHitbox.width to topHitbox.height, topFrontDial.width to topFrontDial.height)
            assertSame(frontDialBody, studio.buttons["frontDialPress"])
            studio.setFaceVisible("front", false)
            valueSettings.clear()
            buttonCommands.clear()
            fun proxyGesture(type: javafx.event.EventType<MouseEvent>, x: Double, down: Boolean) {
                topFrontDial.fireEvent(MouseEvent(type, x, 15.0, x, 15.0, MouseButton.PRIMARY, 1,
                    false, false, false, false, down, false, false, false, false, true, null))
            }
            proxyGesture(MouseEvent.MOUSE_PRESSED, 80.0, true)
            proxyGesture(MouseEvent.MOUSE_DRAGGED, 44.0, true)
            proxyGesture(MouseEvent.MOUSE_RELEASED, 44.0, false)
            assertEquals(listOf(1L, 0L), valueSettings.map { it.third })
            assertTrue(valueSettings.all { it.first == "soc-gpio" && it.second == "frontDialDetents" })
            assertTrue(buttonCommands.isEmpty())
            proxyGesture(MouseEvent.MOUSE_PRESSED, 80.0, true)
            proxyGesture(MouseEvent.MOUSE_RELEASED, 80.0, false)
            assertEquals(listOf("frontDialPress" to true, "frontDialPress" to false), buttonCommands.toList())

            // 6. Sidebar tactile knurled wheels drag-to-turn cleanly
            valueSettings.clear()
            val rearWheel = holder.lookup("#tactile-rear-dial-wheel")!!
            rearWheel.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 20.0, 10.0, 20.0, 10.0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            rearWheel.fireEvent(MouseEvent(MouseEvent.MOUSE_DRAGGED, 50.0, 10.0, 50.0, 10.0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            rearWheel.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 50.0, 10.0, 50.0, 10.0, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            assertEquals(
                listOf(
                    Triple<String, String, Any>("soc-adc", "rearDialDetents", 1L),
                    Triple<String, String, Any>("soc-adc", "rearDialDetents", 2L),
                ),
                valueSettings.toList(),
            )
            assertEquals("2", (holder.lookup("#readback-rear-dial-detents") as Label).text)

            // 7. Pending guards prevent stale periodic observeSnapshot from snapping STILL/MOVIE & MODE DIAL back while patch is in flight
            deferReplies = true
            (holder.lookup("#shooting-mode-still") as ToggleButton).fire()
            (holder.lookup("#shooting-dial-C2") as ToggleButton).fire()
            studio.observeSnapshot(
                mapOf(
                    "components" to listOf(
                        mapOf("id" to "buttons", "state" to PanelCatalog.buttonKeys.associateWith { it == "movieMode" || it == "power" }),
                        mapOf("id" to "soc-gpio", "state" to mapOf("focusMode" to "MF", "effectiveFocusMode" to "MF")),
                        mapOf("id" to "soc-adc", "state" to mapOf("modeDial" to "M")),
                    ),
                ),
            )
            assertTrue((holder.lookup("#shooting-mode-still") as ToggleButton).isSelected, "Pending STILL must not snap back to stale MOVIE")
            assertTrue((holder.lookup("#shooting-dial-C2") as ToggleButton).isSelected, "Pending C2 must not snap back to stale M")
            assertEquals("STILL · C2 · MF", (holder.lookup("#shooting-controls-badge") as Label).text)
            pendingButtonReplies.forEach { it.complete(Unit) }
            pendingSettingReplies.forEach { it.complete(Unit) }
            deferReplies = false

            // 8. Theme v2 bodies (hasselblad-x2d2) use valid soc-adc keys (qButton/fn1Button/fn2Button) and fallback default -> P on rotate-map
            val x2d = com.github.nyabanban.fuji.emulator.desktop.theme.BodyThemeRegistry.builtInThemes().single { it.id == "hasselblad-x2d2" }
            studio.applyBodyTheme(x2d)
            studio.observeSnapshot(
                mapOf(
                    "components" to listOf(
                        mapOf("id" to "buttons", "state" to PanelCatalog.buttonKeys.associateWith { it == "power" }),
                        mapOf("id" to "soc-adc", "state" to mapOf("modeDial" to "default")),
                    ),
                ),
            )
            val x2dTop = studio.faceStage("top")!!
            assertNull(x2dTop.scene.root.lookup("#body-mode-dial-indicator"))
            assertNull(x2dTop.scene.root.lookup("#svg-x2d-mode-dial-rotor"))
            val modeButton = x2dTop.scene.lookup("#inspect-top-mode-dial") as javafx.scene.control.Button
            modeButton.fire()
            val popup = javafx.stage.Window.getWindows().filterIsInstance<javafx.scene.control.ContextMenu>()
                .single { it.ownerWindow === x2dTop && it.isShowing }
            val selected = popup.items.filterIsInstance<javafx.scene.control.RadioMenuItem>().single { it.isSelected }
            assertEquals("P", selected.text)
            popup.hide()
            valueSettings.clear()
            (x2dTop.scene.lookup("#inspect-top-fn1") as javafx.scene.control.Button).fire()
            assertTrue(Triple<String, String, Any>("soc-adc", "fn1Button", true) in valueSettings, "hasselblad-x2d2 top-fn1 must dispatch fn1Button, got $valueSettings")
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
            I18n.resetToDefault()
            val close = FutureTask { studioRef.get()?.close(releaseLatching = false) }
            Platform.runLater(close)
            close.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun shutterFullCouplesTwoStageShutterHalfWithoutUnlockingIndependentLock() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val buttonCommands = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Boolean>>()
        val studioRef = java.util.concurrent.atomic.AtomicReference<CameraStudio>()

        val task = FutureTask {
            I18n.resetToDefault()
            val holder = StackPane()
            val owner = Stage().apply { scene = Scene(holder, 420.0, 720.0) }
            val studio = CameraStudio(
                owner = owner,
                sendButton = { key, down ->
                    buttonCommands += key to down
                    CompletableFuture.completedFuture(Unit)
                },
                configure = {},
                inspect = {},
                failure = { throw AssertionError(it) },
            )
            studioRef.set(studio)
            holder.children += studio.root
            Platform.setImplicitExit(false)
            owner.show()
            studio.setFaceVisible("top", true)

            val fullBtn = holder.lookup("#input-shutterFull") as ToggleButton
            val halfBtn = holder.lookup("#input-shutterHalf") as ToggleButton

            // 1. Direct mouse press/release on shutterFull engages S1 -> S2 and releases S2 -> S1
            buttonCommands.clear()
            fullBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            fullBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            assertEquals(
                listOf("shutterHalf" to true, "shutterFull" to true, "shutterFull" to false, "shutterHalf" to false),
                buttonCommands.toList(),
            )

            // 2. Pulse (.fire()) on shutterFull engages S1 -> S2 -> S2 release -> S1 release
            buttonCommands.clear()
            fullBtn.fire()
            assertEquals(
                listOf("shutterHalf" to true, "shutterFull" to true, "shutterFull" to false, "shutterHalf" to false),
                buttonCommands.toList(),
            )

            // 3. Keyboard/gamepad binding press/release on shutterFull also couples S1 -> S2
            buttonCommands.clear()
            studio.pressBindingButton("keyboard:shutter", "shutterFull")
            studio.releaseBindingButton("keyboard:shutter", "shutterFull")
            assertEquals(
                listOf("shutterHalf" to true, "shutterFull" to true, "shutterFull" to false, "shutterHalf" to false),
                buttonCommands.toList(),
            )

            // 4. Independently locking shutterHalf is preserved across shutterFull press & release
            buttonCommands.clear()
            (holder.lookup("#lock-shutterHalf") as ToggleButton).fire()
            assertEquals(listOf("shutterHalf" to true), buttonCommands.toList())
            assertTrue(studio.isButtonLocked("shutterHalf"))
            buttonCommands.clear()
            fullBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            fullBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            assertEquals(listOf("shutterFull" to true, "shutterFull" to false), buttonCommands.toList())
            assertTrue(studio.isButtonLocked("shutterHalf"), "Releasing shutterFull must preserve an independently locked shutterHalf")

            // 5. Standalone shutterHalf press/release never engages shutterFull
            studio.unlockButton("shutterHalf")
            buttonCommands.clear()
            halfBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_PRESSED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null))
            halfBtn.fireEvent(MouseEvent(MouseEvent.MOUSE_RELEASED, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1, false, false, false, false, false, false, false, false, false, true, null))
            assertEquals(listOf("shutterHalf" to true, "shutterHalf" to false), buttonCommands.toList())
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
            I18n.resetToDefault()
            val close = FutureTask { studioRef.get()?.close(releaseLatching = false) }
            Platform.runLater(close)
            close.get(10, TimeUnit.SECONDS)
        }
    }
}
