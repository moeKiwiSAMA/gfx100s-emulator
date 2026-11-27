package com.github.nyabanban.fuji.emulator.desktop.input

import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scripted backend: each poll returns the next queued list (the last one repeats); can be told to throw. */
class FakeGamepadBackend(override val name: String = "fake", override val available: Boolean = true) : GamepadBackend {
    val script = ArrayDeque<List<GamepadFrame>>()
    var current: List<GamepadFrame> = emptyList()
    var failNext = 0
    var closed = false
    var polls = 0

    override fun poll(): List<GamepadFrame> {
        polls++
        if (failNext > 0) {
            failNext--
            throw IllegalStateException("scripted failure")
        }
        script.pollFirst()?.let { current = it }
        return current
    }

    override fun close() {
        closed = true
    }
}

class GamepadPollerTest {
    private fun frame(slot: Int, buttons: Int = 0, rt: Double = 0.0, name: String = "Pad $slot") =
        GamepadFrame(connected = true, slot = slot, name = name, buttons = buttons, rt = rt)

    private class Harness(backend: GamepadBackend, needsTick: () -> Boolean = { false }) {
        val frames = mutableListOf<GamepadFrame>()
        val ticks = mutableListOf<Long>()
        var clock = 0L
        val poller = GamepadPoller(
            backend = backend,
            dispatch = { it() },
            onFrame = { frames += it },
            onTick = { ticks += it },
            needsTick = needsTick,
            clock = { clock },
        )
    }

    @Test fun postsOnlyChangedFramesAndQuantizesNoise() {
        val backend = FakeGamepadBackend()
        val h = Harness(backend)
        backend.script.add(listOf(frame(0, rt = 0.5)))
        backend.script.add(listOf(frame(0, rt = 0.5 + 1e-5))) // sub-quantum noise
        backend.script.add(listOf(frame(0, rt = 0.75)))
        repeat(5) { h.poller.pollOnce() }
        assertEquals(listOf(0.5, 0.75), h.frames.map { it.rt })
    }

    @Test fun followsOneSlotUntilItDisconnects() {
        val backend = FakeGamepadBackend()
        val h = Harness(backend)
        backend.script.add(listOf(frame(1, buttons = GamepadFrame.A)))
        backend.script.add(listOf(frame(0, buttons = GamepadFrame.B), frame(1, buttons = GamepadFrame.A)))
        backend.script.add(listOf(frame(0, buttons = GamepadFrame.B), GamepadFrame(connected = false, slot = 1)))
        backend.script.add(emptyList())
        repeat(4) { h.poller.pollOnce() }
        assertEquals(listOf(1, 0, -1), h.frames.map { it.slot }, "keeps slot 1 while present, then falls back to slot 0")
        assertFalse(h.frames.last().connected)
    }

    @Test fun pollFailuresDegradeToDisconnected() {
        val backend = FakeGamepadBackend()
        val h = Harness(backend)
        backend.script.add(listOf(frame(0, buttons = GamepadFrame.X)))
        h.poller.pollOnce()
        backend.failNext = 2
        h.poller.pollOnce()
        h.poller.pollOnce()
        assertEquals(listOf(true, false), h.frames.map { it.connected })
        h.poller.pollOnce()
        assertEquals(listOf(true, false, true), h.frames.map { it.connected }, "recovers after failures")
    }

    @Test fun ticksOnlyWhenRequested() {
        var wanted = false
        val h = Harness(FakeGamepadBackend(), needsTick = { wanted })
        h.poller.pollOnce()
        assertTrue(h.ticks.isEmpty())
        wanted = true
        h.clock = 42L
        h.poller.pollOnce()
        assertEquals(listOf(42L), h.ticks)
    }

    @Test fun closeReleasesHeldInputsAndClosesBackend() {
        val backend = FakeGamepadBackend()
        val sink = mutableListOf<String>()
        val controller = InputBindingController(InputBindingConfig.default(), object : CameraInputSink {
            override fun press(action: CameraInputAction, source: String) { sink += "+$action" }
            override fun release(action: CameraInputAction, source: String) { sink += "-$action" }
            override fun step(action: CameraInputAction) { sink += "step:$action" }
        })
        val poller = GamepadPoller(backend, dispatch = { it() }, onFrame = controller::handleGamepadFrame)
        backend.script.add(listOf(frame(0, buttons = GamepadFrame.RB, rt = 1.0)))
        poller.pollOnce()
        assertEquals(listOf("+SHUTTER_FULL", "+AF_ON").sorted(), sink.sorted())
        sink.clear()
        poller.close()
        assertEquals(listOf("-AF_ON", "-SHUTTER_FULL").sorted(), sink.sorted())
        assertTrue(backend.closed)
    }

    @Test fun backgroundThreadPollsAt60HzAndStops() {
        val backend = FakeGamepadBackend()
        backend.script.add(listOf(frame(0)))
        val seen = java.util.concurrent.CountDownLatch(1)
        val poller = GamepadPoller(backend, dispatch = { it() }, onFrame = { if (it.connected) seen.countDown() })
        poller.start()
        assertTrue(seen.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(poller.isRunning)
        poller.close()
        assertFalse(poller.isRunning)
        val after = backend.polls
        Thread.sleep(60)
        assertTrue(backend.polls - after <= 1, "no polling after close")
    }

    @Test fun pinnedSlotIgnoresOtherControllers() {
        val backend = FakeGamepadBackend()
        var selection: GamepadSelection = GamepadSelection.Slot(1)
        val frames = mutableListOf<GamepadFrame>()
        val devices = mutableListOf<List<GamepadDevice>>()
        val poller = GamepadPoller(backend, dispatch = { it() }, onFrame = { frames += it },
            selection = { selection }, onDevices = { devices += it })
        backend.script.add(listOf(frame(0, buttons = GamepadFrame.A, name = "Virtual Pad")))
        backend.script.add(listOf(frame(0, buttons = GamepadFrame.A, name = "Virtual Pad"), frame(1, buttons = GamepadFrame.B, name = "Xbox")))
        backend.script.add(listOf(frame(0, buttons = GamepadFrame.A, name = "Virtual Pad")))
        repeat(3) { poller.pollOnce() }
        assertEquals(listOf(false, true, false), frames.map { it.connected }, "slot 0 is never consumed while slot 1 is pinned")
        assertEquals(GamepadFrame.B, frames[1].buttons)
        assertEquals(
            listOf(
                listOf(GamepadDevice(0, "Virtual Pad")),
                listOf(GamepadDevice(0, "Virtual Pad"), GamepadDevice(1, "Xbox")),
                listOf(GamepadDevice(0, "Virtual Pad")),
            ),
            devices,
            "hot-plug and unplug publish the live device list",
        )
        frames.clear()
        selection = GamepadSelection.Auto
        poller.pollOnce()
        assertEquals(listOf("Virtual Pad"), frames.map { it.name }, "switching back to auto takes the first connected pad")
    }

    @Test fun pinnedDeviceIsFollowedAcrossReconnectToAnotherSlot() {
        val backend = FakeGamepadBackend()
        val sink = mutableListOf<String>()
        val controller = InputBindingController(InputBindingConfig.default().copy(selectedGamepad = "1:Xbox"), object : CameraInputSink {
            override fun press(action: CameraInputAction, source: String) { sink += "+$action" }
            override fun release(action: CameraInputAction, source: String) { sink += "-$action" }
            override fun step(action: CameraInputAction) {}
        })
        val poller = GamepadPoller(backend, dispatch = { it() }, onFrame = controller::handleGamepadFrame,
            selection = { controller.config.gamepadSelection }, onDevices = controller::updateDevices)
        backend.script.add(listOf(frame(0, buttons = GamepadFrame.Y, name = "Virtual Pad"), frame(1, buttons = GamepadFrame.RB, name = "Xbox")))
        poller.pollOnce()
        assertEquals(listOf("+AF_ON"), sink.toList(), "only the pinned Xbox pad drives the camera")
        assertEquals(2, controller.status.devices.size)
        backend.script.add(listOf(frame(0, buttons = GamepadFrame.Y, name = "Virtual Pad")))
        poller.pollOnce()
        assertEquals(listOf("+AF_ON", "-AF_ON"), sink.toList(), "selected pad unplugged: everything released")
        assertFalse(controller.status.connected)
        backend.script.add(listOf(frame(0, buttons = GamepadFrame.Y, name = "Virtual Pad"), frame(2, buttons = GamepadFrame.B, name = "Xbox")))
        poller.pollOnce()
        assertEquals(listOf("+AF_ON", "-AF_ON", "+MENU_OK"), sink.toList(), "re-plugged into slot 2: re-bound automatically")
        assertEquals(2, controller.status.slot)
        sink.clear()
        controller.updateConfig(controller.config.copy(selectedGamepad = "slot:0"))
        assertEquals(listOf("-MENU_OK"), sink.toList(), "changing the selection releases the previous pad")
        poller.pollOnce()
        assertEquals(listOf("-MENU_OK", "+DELETE"), sink.toList())
    }

    @Test fun selectionParsingAndValidation() {
        assertEquals(GamepadSelection.Auto, GamepadSelection.parse("auto"))
        assertEquals(GamepadSelection.Auto, GamepadSelection.parse(" AUTO "))
        assertEquals(GamepadSelection.Slot(2), GamepadSelection.parse("slot:2"))
        assertEquals(GamepadSelection.Device(1, "Xbox Wireless Controller"), GamepadSelection.parse("1:Xbox Wireless Controller"))
        assertEquals(GamepadSelection.Device(0, "8BitDo: Pro 2"), GamepadSelection.parse("0:8BitDo: Pro 2"), "only the first colon splits")
        listOf("slot:9", "slot:x", "Xbox", "7:Pad", "1:", "").forEach { bad ->
            assertTrue(runCatching { GamepadSelection.parse(bad) }.isFailure, bad)
        }
        listOf("auto", "slot:3", "2:Pad").forEach { assertEquals(it, GamepadSelection.parse(it).encode()) }
        assertTrue(runCatching { InputBindingConfig(selectedGamepad = "nonsense") }.isFailure)
    }

    @Test fun unavailableBackendNeverStarts() {
        val poller = GamepadPoller(UnavailableGamepadBackend, dispatch = { it() }, onFrame = {})
        poller.start()
        assertFalse(poller.isRunning)
        poller.close()
    }

    @Test fun missingNativeLibraryFallsBackGracefully() {
        val backend = NativeGamepadBridge.tryLoad(Files.createTempDirectory("no-gamepad-home"))
        // Either the repo-local build was found (developer machine) or we fell back; never throws.
        if (!backend.available) assertEquals(UnavailableGamepadBackend, backend)
        backend.close()
    }

    @Test fun nativeBridgeLoadsAndPollsWhenBuilt() {
        val lib = listOf(Path.of(".tools/gamepad-bridge/build-linux/libfuji_gamepad.so"), Path.of("../.tools/gamepad-bridge/build-linux/libfuji_gamepad.so"))
            .firstOrNull { Files.isRegularFile(it) }
        assumeTrue(lib != null && System.getProperty("os.name").lowercase().contains("linux"), "native gamepad bridge not built")
        val previous = System.getProperty("fuji.gamepad.library")
        System.setProperty("fuji.gamepad.library", lib!!.toAbsolutePath().toString())
        try {
            val backend = NativeGamepadBridge.tryLoad(null)
            assertTrue(backend.available, "built bridge must load")
            assertTrue(backend.name.isNotBlank())
            repeat(3) { backend.poll().forEach { f -> assertTrue(f.slot in 0..3 || !f.connected) } }
            backend.close()
        } finally {
            if (previous == null) System.clearProperty("fuji.gamepad.library") else System.setProperty("fuji.gamepad.library", previous)
        }
    }
}
