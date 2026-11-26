package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.console.CommandResult
import com.github.nyabanban.fuji.emulator.desktop.console.ConsoleBackend
import com.github.nyabanban.fuji.emulator.desktop.console.ConsoleCommand
import com.github.nyabanban.fuji.emulator.desktop.console.EmulatorConsole
import com.github.nyabanban.fuji.emulator.runtime.AsyncPerfProfiler
import com.github.nyabanban.fuji.emulator.runtime.ConsoleAnsi
import com.github.nyabanban.fuji.emulator.runtime.FirmwareStage
import com.github.nyabanban.fuji.emulator.runtime.FirmwareTelemetry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.test.*

class EmulatorConsoleTest {

    private class FakeConsoleBackend(
        override val home: Path,
        override val telemetry: FirmwareTelemetry = FirmwareTelemetry(),
        override val profiler: AsyncPerfProfiler = AsyncPerfProfiler(telemetry),
        loadedComponents: List<String> = listOf(
            "battery",
            "buttons",
            "cmos",
            "display",
            "flash-image",
            "i2c-bus",
            "lens",
            "mount",
            "sdcard-reader",
            "soc-gpio",
        ),
    ) : ConsoleBackend {
        val paused = AtomicBoolean(false)
        var running: Boolean = true
        var bootCount: Int = 0
        var stopCount: Int = 0
        val components = loadedComponents.toMutableList()
        val states: MutableMap<String, MutableMap<String, Any?>> = linkedMapOf(
            "buttons" to linkedMapOf(
                "menu" to false,
                "play" to false,
                "back" to false,
                "power" to true,
                "movieMode" to false,
            ),
            "i2c-bus" to linkedMapOf(
                "eyeSensorNear" to false,
                "touchEnabled" to true,
                "touchPressed" to false,
                "touchX" to 0L,
                "touchY" to 0L,
            ),
            "display" to linkedMapOf(
                "width" to 1024L,
                "height" to 768L,
                "evfWidth" to 1024L,
                "evfHeight" to 768L,
                "brightness" to 1.0,
                "mainSequence" to 7L,
                "evfSequence" to 2L,
            ),
            "sdcard-reader" to linkedMapOf(
                "slot1Inserted" to true,
                "slot1Backend" to "directory",
                "slot1Image" to "sd-card-slot-1",
                "slot2Inserted" to false,
                "slot2Backend" to "raw",
                "slot2Image" to "",
            ),
            "battery" to linkedMapOf(
                "chargePercent" to 100.0,
                "voltageMillivolts" to 7400L,
                "present" to true,
            ),
            "mount" to linkedMapOf(
                "attached" to true,
                "lensPort" to "lens",
                "contactProtocol" to "gf-lenscom-frame32-experimental-v1",
            ),
            "lens" to linkedMapOf(
                "template" to "gf45-100",
                "templateDisplayName" to "GF 45-100mm F4 R LM OIS WR",
                "mountType" to "GF Native",
                "opticsEnabled" to true,
                "focalLengthMm" to 63.0,
                "aperture" to 4.0,
                "focusDistanceM" to 2.5,
                "focusMode" to "AF-S",
                "afTrigger" to 0L,
                "electronicContacts" to true,
                "oisEnabled" to true,
            ),
            "cmos" to linkedMapOf(
                "sourceKind" to "JPEG",
                "sourceFile" to "test-qr.jpg",
                "loop" to true,
                "framesExposed" to 42L,
                "opticsFilterActive" to true,
                "hasDepthBuffer" to false,
            ),
            "flash-image" to linkedMapOf(
                "regionsProfile" to "supplemental-regions-v1",
                "mutationProfile" to "writable-region-set-v1",
                "mutationFile" to "mutable.json",
                "tailProfile" to "strict",
            ),
        )
        val patchHistory = mutableListOf<Pair<String, Map<String, Any>>>()
        val frames = linkedMapOf<String, ConsoleBackend.CapturedFrame>()

        override fun isPaused(): Boolean = paused.get()
        override fun setPaused(paused: Boolean): Boolean {
            val prev = this.paused.getAndSet(paused)
            telemetry.paused = paused
            return prev != paused
        }
        override fun isRunning(): Boolean = running
        override fun requestBoot(): String {
            bootCount++
            running = true
            return "Firmware boot started (fake #$bootCount)."
        }
        override fun requestStop(): String {
            stopCount++
            running = false
            return "Stopping the emulator..."
        }
        override fun listComponents(): List<String> = components.toList()
        override fun componentState(componentId: String): Map<String, Any?>? =
            if (componentId in components) states[componentId]?.toMap() else null

        override fun patchComponent(componentId: String, changes: Map<String, Any>): Map<String, Any?> {
            val state = states[componentId] ?: error("Component \"$componentId\" is not loaded")
            state.putAll(changes)
            patchHistory += componentId to changes
            return state.toMap()
        }

        override fun captureDisplay(portName: String): ConsoleBackend.CapturedFrame? = frames[portName]
    }

    private fun newConsole(
        backend: FakeConsoleBackend,
        output: ByteArrayOutputStream,
        input: ByteArray = ByteArray(0),
        clockNanos: () -> Long = { 2_000_000_000L },
        delayedActions: MutableList<Pair<Long, () -> Unit>>? = null,
    ): EmulatorConsole = EmulatorConsole(
        backend = backend,
        input = ByteArrayInputStream(input),
        output = output,
        interactivePrompt = false,
        timeSupplier = { LocalTime.of(12, 34, 56) },
        clockNanos = clockNanos,
        scheduleDelayed = { delayMs, action ->
            if (delayedActions != null) {
                delayedActions += delayMs to action
            } else {
                action()
            }
        },
    )

    @Test
    fun tokenizeStripsLeadingSlashAndPreservesQuotedPaths() {
        assertEquals(emptyList(), EmulatorConsole.tokenize(""))
        assertEquals(emptyList(), EmulatorConsole.tokenize("   /   "))
        assertEquals(listOf("tps"), EmulatorConsole.tokenize("tps"))
        assertEquals(listOf("tps"), EmulatorConsole.tokenize("/tps"))
        assertEquals(
            listOf("profiler", "stop", "--save", "my reports/profile 1.json"),
            EmulatorConsole.tokenize("/profiler stop --save \"my reports/profile 1.json\""),
        )
        assertEquals(
            listOf("screenshot", "main", "shot 01.png"),
            EmulatorConsole.tokenize("screenshot main 'shot 01.png'"),
        )
    }

    @Test
    fun parseConsoleLaunchOptionsRecognizesNoguiAndNoConsole() {
        assertEquals(ConsoleLaunchOptions(headless = false, consoleEnabled = true), parseConsoleLaunchOptions(emptyArray(), false))
        assertEquals(ConsoleLaunchOptions(headless = true, consoleEnabled = true), parseConsoleLaunchOptions(arrayOf("--headless"), false))
        assertEquals(ConsoleLaunchOptions(headless = true, consoleEnabled = true), parseConsoleLaunchOptions(arrayOf("--nogui"), false))
        assertEquals(ConsoleLaunchOptions(headless = true, consoleEnabled = true), parseConsoleLaunchOptions(arrayOf("nogui"), false))
        assertEquals(ConsoleLaunchOptions(headless = true, consoleEnabled = true), parseConsoleLaunchOptions(arrayOf("NOGUI"), false))
        assertEquals(ConsoleLaunchOptions(headless = true, consoleEnabled = false), parseConsoleLaunchOptions(arrayOf("--nogui", "--no-console"), false))
        assertEquals(ConsoleLaunchOptions(headless = false, consoleEnabled = false), parseConsoleLaunchOptions(emptyArray(), consoleDisabledProperty = true))
    }

    @Test
    fun registryHandlesNamespacesAliasesSuggestionsUsageErrorsAndPaginatedHelp(@TempDir dir: Path) {
        val backend = FakeConsoleBackend(dir)
        val out = ByteArrayOutputStream()
        val console = newConsole(backend, out)

        // Custom command registration via public registry
        console.registry.register(
            ConsoleCommand(
                name = "ping",
                owner = "diag",
                aliases = listOf("pong"),
                description = "Echo test command",
                usage = "/ping [msg]",
                executor = { ctx ->
                    ctx.info("pong:${ctx.args.joinToString(" ")}")
                    CommandResult.Ok
                },
            ),
        )

        // Paginated help + command help + component help
        assertTrue(console.executeLine("help"))
        assertTrue(console.executeLine("help 2"))
        assertTrue(console.executeLine("help 99"))
        assertTrue(console.executeLine("/help profiler"))
        assertTrue(console.executeLine("help buttons"))
        assertTrue(console.executeLine("help fuji"))

        // Namespaced & alias dispatch
        assertTrue(console.executeLine("fuji:tps"))
        assertTrue(console.executeLine("diag:ping hello"))
        assertTrue(console.executeLine("pong world"))

        // Unknown command with typo suggestion vs no suggestion
        assertTrue(console.executeLine("profler"))
        assertTrue(console.executeLine("battry"))
        assertTrue(console.executeLine("zzzzunknowncmd"))

        // Structured UsageError output
        assertTrue(console.executeLine("profiler"))
        assertTrue(console.executeLine("profiler start --interval 99999"))
        assertTrue(console.executeLine("battery level 250"))
        assertTrue(console.executeLine("touch 100"))

        val text = out.toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("[12:34:56 INFO]: --------- Help: Fuji GFX100S Console (Page 1/"), text)
        assertTrue(text.contains("[12:34:56 INFO]: --------- Help: Fuji GFX100S Console (Page 2/"), text)
        assertTrue(text.contains("Help page 99 out of range"), text)
        assertTrue(text.contains("--------- Help: /profiler ---------"), text)
        assertTrue(text.contains("Description: Control the asynchronous sampling performance profiler"), text)
        assertTrue(text.contains("Aliases: prof, timings"), text)
        assertTrue(text.contains("Owner: fuji (fuji:profiler)"), text)
        assertTrue(text.contains("--------- Help: Component \"buttons\" (3 commands) ---------"), text)
        assertTrue(text.contains("--------- Help: Component \"fuji\""), text)
        assertTrue(text.contains("pong:hello"), text)
        assertTrue(text.contains("pong:world"), text)
        assertTrue(text.contains("Unknown command \"profler\". Did you mean \"profiler\"?"), text)
        assertTrue(text.contains("Unknown command \"battry\". Did you mean \"battery\"?"), text)
        assertTrue(text.contains("Unknown command \"zzzzunknowncmd\". Type \"help\" for help."), text)
        assertTrue(text.contains("Usage: /profiler <start"), text)
        assertTrue(text.contains("Usage: /battery [status | level <0-100>"), text)
        assertTrue(text.contains("Usage: /touch <x> <y> [ms]"), text)

        // Zero third-party branding strings anywhere in user-facing output
        val lower = text.lowercase()
        assertFalse(lower.contains("spigot"), "Unexpected branding string in output: $text")
        assertFalse(lower.contains("spark"), "Unexpected branding string in output: $text")
    }

    @Test
    fun componentCommandsOnlyRegisterForLoadedComponents(@TempDir dir: Path) {
        val minimalBackend = FakeConsoleBackend(dir, loadedComponents = listOf("battery", "sdcard-reader"))
        val out = ByteArrayOutputStream()
        val console = newConsole(minimalBackend, out)

        assertNotNull(console.registry.resolve("battery"))
        assertNotNull(console.registry.resolve("battery:battery"))
        assertNotNull(console.registry.resolve("sd"))
        assertNotNull(console.registry.resolve("sdcard-reader:sd"))
        assertNull(console.registry.resolve("mount"))
        assertNull(console.registry.resolve("button"))
        assertNull(console.registry.resolve("evf"))
        assertNull(console.registry.resolve("cmos"))
    }

    @Test
    fun profilerTpsHealthAndComponentsProduceCleanOutputWithoutThirdPartyBranding(@TempDir dir: Path) {
        val telemetry = FirmwareTelemetry().apply {
            startedAtNanos = 1_000_000_000L
            running = true
            markStage(FirmwareStage.STAGE_5_FIRST_FRAME)
            attempts = 30_000_000L
            virtualTick = 54_000_000L
            wfiJumpCount = 250L
            wfiSkippedTicks = 13_500_000L
            publishedFrames = 12L
        }
        val backend = FakeConsoleBackend(dir, telemetry)
        val out = ByteArrayOutputStream()
        val console = newConsole(backend, out, clockNanos = { 2_000_000_000L })

        assertTrue(console.executeLine("/tps"))
        assertTrue(console.executeLine("components"))
        assertTrue(console.executeLine("/profiler start --interval 10"))
        assertTrue(backend.profiler.isRunning())
        telemetry.attempts += 5_000_000L
        telemetry.virtualTick += 13_500_000L
        telemetry.recordMmioAccess(0xFFFC_0000L, isWrite = false, elapsedNanos = 5_000L)
        Thread.sleep(35)

        assertTrue(console.executeLine("fuji:profiler info"))
        assertTrue(console.executeLine("profiler report"))
        assertTrue(console.executeLine("profiler health"))
        assertTrue(console.executeLine("gc"))

        val savedJson = dir.resolve("reports").resolve("profiler-out.json")
        assertTrue(console.executeLine("profiler stop --save \"${savedJson}\""))
        assertFalse(backend.profiler.isRunning())
        assertTrue(Files.exists(savedJson), "Expected profiler JSON export at $savedJson")
        val jsonText = Files.readString(savedJson, StandardCharsets.UTF_8)
        assertTrue(jsonText.contains("\"schema\""), jsonText)
        assertTrue(jsonText.contains("\"realTimeRatio\""), jsonText)

        // Timings alias also maps cleanly to profiler
        assertTrue(console.executeLine("timings on 15"))
        assertTrue(backend.profiler.isRunning())
        assertTrue(console.executeLine("timings status"))
        assertTrue(console.executeLine("timings off"))
        assertFalse(backend.profiler.isRunning())

        val text = out.toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("RTR=1.000x"), text)
        assertTrue(text.contains("Avg=30.00 MIPS"), text)
        assertTrue(text.contains("Eff=54.00 vMIPS"), text)
        assertTrue(text.contains("WFI=25.0%"), text)
        assertTrue(text.contains("Stage=STAGE_5_FIRST_FRAME"), text)
        assertTrue(text.contains("Frames=12"), text)
        assertTrue(text.contains("State=RUNNING"), text)
        assertTrue(text.contains("Components (10):"), text)
        assertTrue(text.contains("buttons (3 cmds)"), text)
        assertTrue(text.contains("cmos (2 cmds)"), text)
        assertTrue(text.contains("lens (2 cmds)"), text)
        assertTrue(text.contains("battery (1 cmd)"), text)
        assertTrue(text.contains("[Profiler] Started (interval=10ms)"), text)
        assertTrue(text.contains("[Profiler] State: RUNNING"), text)
        assertTrue(text.contains("================ [FUJI-PROFILER SUMMARY] ================"), text)
        assertTrue(text.contains("[Profiler] --- System Health Report ---"), text)
        assertTrue(text.contains("[Profiler] Saved JSON report to:"), text)

        val lower = text.lowercase()
        assertFalse(lower.contains("spigot"), "Unexpected branding string in output: $text")
        assertFalse(lower.contains("spark"), "Unexpected branding string in output: $text")
    }

    @Test
    fun allComponentCommandsAndLifecycleControlsWorkEndToEnd(@TempDir dir: Path) {
        val backend = FakeConsoleBackend(dir)
        val pixels = IntArray(4 * 2) { 0xFF112233.toInt() }
        backend.frames["display.main"] = ConsoleBackend.CapturedFrame(width = 4, height = 2, sequence = 7L, argbPixels = pixels)

        val delayed = mutableListOf<Pair<Long, () -> Unit>>()
        val out = ByteArrayOutputStream()
        val console = newConsole(backend, out, delayedActions = delayed)

        // buttons: button/press, power, mode
        assertTrue(console.executeLine("press MENU 80"))
        assertEquals(true, backend.states.getValue("buttons")["menu"])
        assertEquals(1, delayed.size)
        assertEquals(80L, delayed[0].first)
        delayed[0].second.invoke()
        assertEquals(false, backend.states.getValue("buttons")["menu"])

        assertTrue(console.executeLine("buttons:button PLAY hold"))
        assertEquals(true, backend.states.getValue("buttons")["play"])
        assertTrue(console.executeLine("button PLAY release"))
        assertEquals(false, backend.states.getValue("buttons")["play"])

        assertTrue(console.executeLine("power status"))
        assertTrue(console.executeLine("power off"))
        assertEquals(false, backend.states.getValue("buttons")["power"])
        assertTrue(console.executeLine("power on"))
        assertEquals(true, backend.states.getValue("buttons")["power"])

        assertTrue(console.executeLine("mode movie"))
        assertEquals(true, backend.states.getValue("buttons")["movieMode"])
        assertTrue(console.executeLine("mode still"))
        assertEquals(false, backend.states.getValue("buttons")["movieMode"])

        // i2c-bus: touch + touch swipe + evf
        assertTrue(console.executeLine("touch 512 384 60"))
        assertEquals(true, backend.states.getValue("i2c-bus")["touchPressed"])
        assertEquals(512L, backend.states.getValue("i2c-bus")["touchX"])
        assertEquals(384L, backend.states.getValue("i2c-bus")["touchY"])
        assertEquals(2, delayed.size)
        delayed[1].second.invoke()
        assertEquals(false, backend.states.getValue("i2c-bus")["touchPressed"])

        val swipeStartIdx = delayed.size
        assertTrue(console.executeLine("touch swipe left 100"))
        assertEquals(true, backend.states.getValue("i2c-bus")["touchPressed"])
        assertEquals(760L, backend.states.getValue("i2c-bus")["touchX"])
        assertEquals(384L, backend.states.getValue("i2c-bus")["touchY"])
        // Step 1..4 (630, 500, 370, 240) + release
        delayed[swipeStartIdx].second.invoke()
        assertEquals(630L, backend.states.getValue("i2c-bus")["touchX"])
        assertEquals(true, backend.states.getValue("i2c-bus")["touchPressed"])
        delayed[swipeStartIdx + 1].second.invoke()
        assertEquals(500L, backend.states.getValue("i2c-bus")["touchX"])
        delayed[swipeStartIdx + 2].second.invoke()
        assertEquals(370L, backend.states.getValue("i2c-bus")["touchX"])
        delayed[swipeStartIdx + 3].second.invoke()
        assertEquals(240L, backend.states.getValue("i2c-bus")["touchX"])
        assertEquals(true, backend.states.getValue("i2c-bus")["touchPressed"])
        delayed[swipeStartIdx + 4].second.invoke()
        assertEquals(240L, backend.states.getValue("i2c-bus")["touchX"])
        assertEquals(false, backend.states.getValue("i2c-bus")["touchPressed"])

        val customSwipeIdx = delayed.size
        assertTrue(console.executeLine("touch swipe 512 560 512 180 100"))
        assertEquals(560L, backend.states.getValue("i2c-bus")["touchY"])
        assertEquals(true, backend.states.getValue("i2c-bus")["touchPressed"])
        repeat(5) { offset -> delayed[customSwipeIdx + offset].second.invoke() }
        assertEquals(180L, backend.states.getValue("i2c-bus")["touchY"])
        assertEquals(false, backend.states.getValue("i2c-bus")["touchPressed"])

        assertTrue(console.executeLine("evf status"))
        assertTrue(console.executeLine("evf near"))
        assertEquals(true, backend.states.getValue("i2c-bus")["eyeSensorNear"])
        assertTrue(console.executeLine("evf far"))
        assertEquals(false, backend.states.getValue("i2c-bus")["eyeSensorNear"])

        // display: screenshot + display status/brightness
        val shotPath = dir.resolve("captures").resolve("lcd-test.png")
        assertTrue(console.executeLine("screenshot main \"$shotPath\""))
        assertTrue(Files.exists(shotPath), "Expected PNG screenshot at $shotPath")
        val img = ImageIO.read(shotPath.toFile())
        assertNotNull(img)
        assertEquals(4, img.width)
        assertEquals(2, img.height)

        assertTrue(console.executeLine("display status"))
        assertTrue(console.executeLine("display brightness 0.65"))
        assertEquals(0.65, backend.states.getValue("display")["brightness"])

        // battery: status, level, mv, present
        assertTrue(console.executeLine("battery status"))
        assertTrue(console.executeLine("battery level 42.5"))
        assertEquals(42.5, backend.states.getValue("battery")["chargePercent"])
        assertTrue(console.executeLine("battery mv 7200"))
        assertEquals(7200L, backend.states.getValue("battery")["voltageMillivolts"])
        assertTrue(console.executeLine("battery present off"))
        assertEquals(false, backend.states.getValue("battery")["present"])

        // mount: status, detach, attach
        assertTrue(console.executeLine("mount status"))
        assertTrue(console.executeLine("mount detach"))
        assertEquals(false, backend.states.getValue("mount")["attached"])
        assertTrue(console.executeLine("mount attach"))
        assertEquals(true, backend.states.getValue("mount")["attached"])

        // sd, cmos, flash, lens, source
        assertTrue(console.executeLine("sd"))
        assertTrue(console.executeLine("cmos"))
        assertTrue(console.executeLine("flash"))

        assertTrue(console.executeLine("lens status"))
        assertTrue(console.executeLine("lens templates"))
        assertTrue(console.executeLine("lens template gf20-35"))
        assertEquals("gf20-35", backend.states.getValue("lens")["template"])
        assertEquals(true, backend.states.getValue("lens")["opticsEnabled"])
        assertTrue(console.executeLine("lens focal 28"))
        assertEquals(28.0, backend.states.getValue("lens")["focalLengthMm"])
        assertTrue(console.executeLine("lens aperture f/5.6"))
        assertEquals(5.6, backend.states.getValue("lens")["aperture"])
        assertTrue(console.executeLine("lens focus 1.5m"))
        assertEquals(1.5, backend.states.getValue("lens")["focusDistanceM"])
        assertTrue(console.executeLine("lens focus inf"))
        assertEquals(1000.0, backend.states.getValue("lens")["focusDistanceM"])
        assertTrue(console.executeLine("lens af"))
        assertEquals(1L, backend.states.getValue("lens")["afTrigger"])
        assertTrue(console.executeLine("lens optics off"))
        assertEquals(false, backend.states.getValue("lens")["opticsEnabled"])
        assertTrue(console.executeLine("gpu list"))
        assertTrue(console.executeLine("gpu cpu"))
        assertEquals("cpu", backend.states.getValue("lens")["opticsBackend"])
        assertTrue(console.executeLine("lens gpu 0"))
        assertEquals("vulkan", backend.states.getValue("lens")["opticsBackend"])
        assertEquals("0", backend.states.getValue("lens")["vulkanDevice"])
        assertTrue(console.executeLine("gpu auto"))
        assertEquals("auto", backend.states.getValue("lens")["opticsBackend"])
        assertEquals("auto", backend.states.getValue("lens")["vulkanDevice"])
        assertTrue(console.executeLine("lens template unknown-xyz"))

        assertTrue(console.executeLine("source status"))
        assertTrue(console.executeLine("source set optical-scene scene://depth-studio loop"))
        assertEquals("optical-scene", backend.states.getValue("cmos")["sourceKind"])
        assertEquals("scene://depth-studio", backend.states.getValue("cmos")["sourceFile"])
        assertEquals(true, backend.states.getValue("cmos")["loop"])
        assertTrue(console.executeLine("src set screen screen://primary false"))
        assertEquals("screen-capture", backend.states.getValue("cmos")["sourceKind"])
        assertEquals("screen://primary", backend.states.getValue("cmos")["sourceFile"])
        assertEquals(false, backend.states.getValue("cmos")["loop"])
        assertTrue(console.executeLine("cmos:source set stream tcp://127.0.0.1:9555"))
        assertEquals("stream-ingest", backend.states.getValue("cmos")["sourceKind"])
        assertEquals("tcp://127.0.0.1:9555", backend.states.getValue("cmos")["sourceFile"])
        assertEquals(true, backend.states.getValue("cmos")["loop"])

        // lifecycle: boot, pause, resume, stop
        assertTrue(console.executeLine("boot"))
        assertEquals(1, backend.bootCount)
        assertTrue(console.executeLine("pause"))
        assertTrue(backend.isPaused())
        assertTrue(console.executeLine("resume"))
        assertFalse(backend.isPaused())
        assertFalse(console.executeLine("stop"))
        assertEquals(1, backend.stopCount)

        val text = out.toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("SD Slot 1: INSERTED | backend=directory | path=sd-card-slot-1"), text)
        assertTrue(text.contains("CMOS sensor: sourceKind=JPEG | sourceFile=test-qr.jpg"), text)
        assertTrue(text.contains("Flash image: regions=supplemental-regions-v1 | mutation=writable-region-set-v1"), text)
        assertTrue(text.contains("Battery: present=true | charge=100.0% | voltage=7400 mV"), text)
        assertTrue(text.contains("G-Mount: ATTACHED | lensPort=lens"), text)
        assertTrue(text.contains("Available lens templates (10):"), text)
        assertTrue(text.contains("Lens template -> gf20-35 (GF 20-35mm F4 R WR)"), text)
        assertTrue(text.contains("Lens AF triggered (afTrigger=1)"), text)
        assertTrue(text.contains("Source -> kind=optical-scene | ref=scene://depth-studio | loop=true"), text)
        assertTrue(text.contains("Usage: /lens [status | templates"), text)
        val lower = text.lowercase()
        assertFalse(lower.contains("spigot"), "Unexpected branding in output: $text")
        assertFalse(lower.contains("spark"), "Unexpected branding in output: $text")
    }

    @Test
    fun daemonLoopExitsCleanlyOnEofWithoutStoppingEmulator(@TempDir dir: Path) {
        val backend = FakeConsoleBackend(dir)
        val out = ByteArrayOutputStream()
        val console = newConsole(backend, out, input = "tps\n".toByteArray(StandardCharsets.UTF_8))

        console.start()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (console.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertFalse(console.isAlive(), "Console daemon thread should exit cleanly upon EOF")
        assertEquals(0, backend.stopCount, "EOF on stdin must never stop the emulator")
        assertTrue(backend.isRunning())
        val text = out.toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("Done! Type \"help\" for console commands"), text)
        assertTrue(text.contains("RTR="), text)
        console.close()
    }

    @Test
    fun colorCommandTogglesAnsiOutputAndDefaultsPlainWhenNonTty(@TempDir dir: Path) {
        ConsoleAnsi.resetMode()
        try {
            val backend = FakeConsoleBackend(dir)
            val out = ByteArrayOutputStream()
            val console = newConsole(backend, out)

            // 1. Default non-TTY (interactivePrompt=false) must emit 100% plain text with zero ANSI escapes
            assertTrue(console.executeLine("color status"))
            assertTrue(console.executeLine("tps"))
            val defaultOut = out.toString(StandardCharsets.UTF_8)
            assertFalse(defaultOut.contains("\u001B"), "Non-TTY default output must be plain text: $defaultOut")
            assertTrue(defaultOut.contains("Console color=DISABLED mode=AUTO tty=false"), defaultOut)

            // 2. Enabling color via 'color on' emits ANSI escapes for INFO/WARN/metrics/badges with exact stripAnsi parity
            out.reset()
            assertTrue(console.executeLine("color on"))
            assertTrue(console.executeLine("ansi status"))
            assertTrue(console.executeLine("tps"))
            assertTrue(console.executeLine("color bogus"))
            val coloredOut = out.toString(StandardCharsets.UTF_8)
            assertTrue(coloredOut.contains("\u001B"), "Expected ANSI escapes after 'color on': $coloredOut")
            assertTrue(coloredOut.contains("${ConsoleAnsi.BRIGHT_CYAN}INFO${ConsoleAnsi.RESET}"), coloredOut)
            assertTrue(coloredOut.contains("${ConsoleAnsi.BOLD_YELLOW}WARN${ConsoleAnsi.RESET}"), coloredOut)
            assertTrue(coloredOut.contains("${ConsoleAnsi.BRIGHT_GREEN}ENABLED${ConsoleAnsi.RESET}"), coloredOut)
            val stripped = ConsoleAnsi.stripAnsi(coloredOut)
            assertTrue(stripped.contains("Console color mode -> ALWAYS (ENABLED)"), stripped)
            assertTrue(stripped.contains("Console color=ENABLED mode=ALWAYS tty=false"), stripped)
            assertTrue(stripped.contains("Usage: /color [auto | on | off | status]"), stripped)

            // 3. Disabling via alias 'colours off' and restoring via 'color auto' returns to plain text
            out.reset()
            assertTrue(console.executeLine("colours off"))
            assertTrue(console.executeLine("tps"))
            assertTrue(console.executeLine("color auto"))
            val plainAgain = out.toString(StandardCharsets.UTF_8)
            assertFalse(plainAgain.contains("\u001B"), "Expected zero ANSI escapes after 'colours off': $plainAgain")
            assertTrue(plainAgain.contains("Console color mode -> NEVER (DISABLED)"), plainAgain)
            assertTrue(plainAgain.contains("Console color mode -> AUTO (DISABLED)"), plainAgain)

            // 4. Interactive TTY prompt uses ConsoleAnsi.formatPrompt (colored 'fuji> ' when ON, plain '> ' when OFF)
            val ttyOut = ByteArrayOutputStream()
            val ttyConsole = EmulatorConsole(
                backend = backend,
                input = "color off\ntps\n".toByteArray(StandardCharsets.UTF_8).inputStream(),
                output = ttyOut,
                interactivePrompt = true,
                timeSupplier = { LocalTime.of(12, 34, 56) },
            )
            ConsoleAnsi.setMode(ConsoleAnsi.ColorMode.ALWAYS)
            ttyConsole.start()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (ttyConsole.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            ttyConsole.close()
            val ttyText = ttyOut.toString(StandardCharsets.UTF_8)
            assertTrue(ttyText.contains(ConsoleAnsi.formatPrompt(true)), "Expected colored TTY prompt: $ttyText")
            assertTrue(ttyText.contains("\n> "), "Expected plain '> ' prompt after 'color off': $ttyText")
        } finally {
            ConsoleAnsi.resetMode()
        }
    }

    @Test
    fun logCommandInspectsAndSwitchesConsoleAndFileThresholdsLive(@TempDir dir: Path) {
        var consoleLevel = "INFO"
        var fileLevel = "DEBUG"
        val fakeLogControl = object : EmulatorConsole.LogLevelControl {
            override fun statusSummary(): String = "Logging: console=$consoleLevel | file=$fileLevel | logger=DEBUG (file=logs/latest.log)"
            override fun getConsoleLevel(): String = consoleLevel
            override fun setConsoleLevel(levelName: String): String {
                consoleLevel = levelName
                return consoleLevel
            }
            override fun getFileLevel(): String = fileLevel
            override fun setFileLevel(levelName: String): String {
                fileLevel = levelName
                return fileLevel
            }
        }
        val backend = FakeConsoleBackend(dir)
        val out = ByteArrayOutputStream()
        val console = EmulatorConsole(
            backend = backend,
            input = ByteArray(0).inputStream(),
            output = out,
            interactivePrompt = false,
            timeSupplier = { LocalTime.of(12, 34, 56) },
            logControl = fakeLogControl,
        )

        assertTrue(console.executeLine("log status"))
        assertTrue(console.executeLine("log level debug"))
        assertEquals("DEBUG", consoleLevel)
        assertTrue(console.executeLine("loglevel info"))
        assertEquals("INFO", consoleLevel)
        assertTrue(console.executeLine("log file warn"))
        assertEquals("WARN", fileLevel)
        assertTrue(console.executeLine("log level bogus"))

        val text = out.toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("Logging: console=INFO | file=DEBUG | logger=DEBUG"), text)
        assertTrue(text.contains("Console log level -> DEBUG (file=DEBUG)"), text)
        assertTrue(text.contains("Console log level -> INFO (file=DEBUG)"), text)
        assertTrue(text.contains("File log level -> WARN (console=INFO)"), text)
        assertTrue(text.contains("Unknown log level \"bogus\""), text)
        console.close()
    }

    @Test
    fun defaultButtonAndTouchTapsCalibrateHoldToVirtualTicksUnderLowRtr(@TempDir dir: Path) {
        val telemetry = FirmwareTelemetry().apply {
            running = true
            virtualTick = 100_000_000L
        }
        val backend = FakeConsoleBackend(dir, telemetry)
        var nowNanos = 1_000_000_000L
        val delayed = ArrayDeque<Pair<Long, () -> Unit>>()
        val out = ByteArrayOutputStream()
        val console = EmulatorConsole(
            backend = backend,
            input = ByteArrayInputStream(ByteArray(0)),
            output = out,
            interactivePrompt = false,
            timeSupplier = { LocalTime.of(12, 34, 56) },
            clockNanos = { nowNanos },
            scheduleDelayed = { delayMs, action -> delayed.addLast(delayMs to action) },
        )

        // 1. Default `/button MENU tap` at 0.25x RTR (67,500 ticks per 5ms wall step = 13.5M ticks/s):
        //    Old 150ms wall-clock tap only reached 2,025,000 ticks (< 2,700,000 deb=2 threshold -> dropped!).
        //    Calibrated tap MUST stay pressed past 150ms wall-clock and release once virtualTick >= +4,050,000.
        assertTrue(console.executeLine("button MENU tap"))
        assertEquals(true, backend.states.getValue("buttons")["menu"])

        var wallMs = 0L
        while (wallMs < 150L) {
            val (stepMs, action) = delayed.removeFirst()
            wallMs += stepMs
            nowNanos += stepMs * 1_000_000L
            telemetry.virtualTick += stepMs * 13_500L // 0.25x RTR @ 54 MHz
            action()
        }
        assertEquals(true, backend.states.getValue("buttons")["menu"], "Default tap must still be held at 150ms wall-clock under 0.25x RTR")

        var releaseWallMs = -1L
        val startTick = 100_000_000L
        while (delayed.isNotEmpty() && wallMs < 1_000L) {
            val (stepMs, action) = delayed.removeFirst()
            wallMs += stepMs
            nowNanos += stepMs * 1_000_000L
            telemetry.virtualTick += stepMs * 13_500L
            action()
            if (backend.states.getValue("buttons")["menu"] == false) {
                releaseWallMs = wallMs
                break
            }
        }
        assertEquals(false, backend.states.getValue("buttons")["menu"])
        assertTrue(releaseWallMs >= 300L, "Expected release around 300ms wall-clock at 0.25x RTR, got ${releaseWallMs}ms")
        assertTrue(
            telemetry.virtualTick - startTick >= 4_050_000L,
            "Expected >= 4,050,000 virtual ticks held, got ${telemetry.virtualTick - startTick}",
        )

        // 2. Default `/touch 512 384` (no explicit ms) honors >= 5,670,000 virtual ticks (105 ms @ 54 MHz)
        val touchStartTick = telemetry.virtualTick
        assertTrue(console.executeLine("touch 512 384"))
        assertEquals(true, backend.states.getValue("i2c-bus")["touchPressed"])
        while (delayed.isNotEmpty()) {
            val (stepMs, action) = delayed.removeFirst()
            nowNanos += stepMs * 1_000_000L
            telemetry.virtualTick += stepMs * 13_500L
            action()
        }
        assertEquals(false, backend.states.getValue("i2c-bus")["touchPressed"])
        assertTrue(telemetry.virtualTick - touchStartTick >= 5_670_000L)

        // 3. Stalled session (virtualTick frozen) still releases cleanly at the 3,000ms wall-clock safety ceiling
        assertTrue(console.executeLine("button BACK tap"))
        assertEquals(true, backend.states.getValue("buttons")["back"])
        var stalledMs = 0L
        while (delayed.isNotEmpty() && stalledMs < 5_000L) {
            val (stepMs, action) = delayed.removeFirst()
            stalledMs += stepMs
            nowNanos += stepMs * 1_000_000L
            action()
        }
        assertEquals(false, backend.states.getValue("buttons")["back"], "Stalled tap must release at safety ceiling")
        assertTrue(stalledMs in 3_000L..3_010L, "Expected release at 3,000ms safety ceiling, got ${stalledMs}ms")

        val text = out.toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("calibrated >=4050000 ticks"), text)
        assertTrue(text.contains("calibrated >=5670000 ticks"), text)
        console.close()
    }

    @Test
    fun focusAndDialCommandsControlGpioFocusLeverAndAdcModeDial(@TempDir tempDir: Path) {
        val backend = FakeConsoleBackend(
            home = tempDir,
            loadedComponents = listOf("buttons", "soc-gpio", "soc-adc"),
        ).apply {
            states["soc-gpio"] = linkedMapOf("focusMode" to "default", "effectiveFocusMode" to "AF-S")
            states["soc-adc"] = linkedMapOf("modeDial" to "P")
        }
        val out = ByteArrayOutputStream()
        val console = EmulatorConsole(
            backend = backend,
            input = ByteArrayInputStream(ByteArray(0)),
            output = java.io.PrintStream(out, true, StandardCharsets.UTF_8),
        )

        assertTrue(console.executeLine("focus status"))
        assertTrue(console.executeLine("focus af-c"))
        assertEquals("AF-C", backend.states.getValue("soc-gpio")["focusMode"])
        assertTrue(console.executeLine("focus mf"))
        assertEquals("MF", backend.states.getValue("soc-gpio")["focusMode"])
        assertTrue(console.executeLine("focus s"))
        assertEquals("AF-S", backend.states.getValue("soc-gpio")["focusMode"])

        assertTrue(console.executeLine("dial status"))
        assertTrue(console.executeLine("dial a"))
        assertEquals("A", backend.states.getValue("soc-adc")["modeDial"])
        assertTrue(console.executeLine("modedial c3"))
        assertEquals("C3", backend.states.getValue("soc-adc")["modeDial"])

        val text = out.toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("Focus mode lever: AF-S"), text)
        assertTrue(text.contains("Focus mode lever -> AF-C"), text)
        assertTrue(text.contains("Mode dial -> A"), text)
        assertTrue(text.contains("Mode dial -> C3"), text)
        console.close()
    }

    @Test
    fun evfCommandPrefersStandaloneTmd2772WhenEnabled(@TempDir dir: Path) {
        val backend = FakeConsoleBackend(dir, loadedComponents = listOf("i2c-bus", "tmd2772"))
        backend.states["i2c-bus"] = mutableMapOf("eyeSensorNear" to false)
        backend.states["tmd2772"] = mutableMapOf("enabled" to true, "eyeSensorNear" to true)
        val out = ByteArrayOutputStream()
        val console = EmulatorConsole(
            backend = backend,
            input = ByteArrayInputStream(ByteArray(0)),
            output = java.io.PrintStream(out, true, StandardCharsets.UTF_8),
        )
        assertTrue(console.executeLine("evf status"))
        val afterStatus = out.toString(StandardCharsets.UTF_8)
        assertTrue(afterStatus.contains("tmd2772"), afterStatus)
        assertTrue(afterStatus.contains("NEAR"), afterStatus)

        assertTrue(console.executeLine("evf far"))
        assertEquals(false, backend.states.getValue("tmd2772")["eyeSensorNear"])
        assertEquals(false, backend.states.getValue("i2c-bus")["eyeSensorNear"])

        assertTrue(console.executeLine("evf near"))
        assertEquals(true, backend.states.getValue("tmd2772")["eyeSensorNear"])
        assertEquals(false, backend.states.getValue("i2c-bus")["eyeSensorNear"], "Legacy i2c-bus should not be mutated when tmd2772 owns eyeSensorNear")
        console.close()
    }

    @Test
    fun themeCommandListsAndSwitchesBodyAndUiThemesLive(@TempDir tempDir: Path) {
        val controller = com.github.nyabanban.fuji.emulator.desktop.theme.ThemeRuntimeController(tempDir)
        val backend = object : ConsoleBackend by FakeConsoleBackend(tempDir) {
            override fun themeStatus(): Map<String, Any?> = controller.statusMap()
            override fun applyTheme(bodyId: String?, uiId: String?): Map<String, Any?> {
                controller.applyTheme(bodyId = bodyId, uiId = uiId)
                return controller.statusMap()
            }
        }
        val out = ByteArrayOutputStream()
        val console = EmulatorConsole(
            backend = backend,
            input = ByteArrayInputStream(ByteArray(0)),
            output = java.io.PrintStream(out, true, StandardCharsets.UTF_8),
        )

        assertTrue(console.executeLine("theme status"))
        assertTrue(console.executeLine("theme list"))
        assertTrue(console.executeLine("theme body hasselblad-x2d2"))
        assertTrue(console.executeLine("theme ui daylight-studio"))
        assertTrue(console.executeLine("theme body nonexistent-theme"))

        assertEquals("hasselblad-x2d2", controller.bodyTheme.id)
        assertEquals("daylight-studio", controller.uiTheme.id)
        val text = out.toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("Camera Body Themes (4):"), text)
        assertTrue(text.contains("UI Window Themes (5):"), text)
        assertTrue(text.contains("hasselblad-x2d2"), text)
        assertTrue(text.contains("hasselblad-907x"), text)
        assertTrue(text.contains("compact-rangefinder"), text)
        assertTrue(text.contains("Switched body theme to 'hasselblad-x2d2'"), text)
        assertTrue(text.contains("Switched UI theme to 'daylight-studio'"), text)
        assertTrue(text.contains("Unknown body theme 'nonexistent-theme'"), text)
        console.close()
    }

    @Test
    fun buttonCommandRoutesTopPlateEvAndAdcButtonsToSocAdc(@TempDir dir: Path) {
        val backend = FakeConsoleBackend(
            dir,
            loadedComponents = listOf("buttons", "soc-adc"),
        ).apply {
            states["soc-adc"] = linkedMapOf(
                "fn1Button" to false,
                "fn2Button" to false,
                "fn3Button" to false,
                "qButton" to false,
                "aelButton" to false,
                "lightButton" to false,
            )
        }
        val out = ByteArrayOutputStream()
        val delayed = mutableListOf<Pair<Long, () -> Unit>>()
        val console = newConsole(backend, out, delayedActions = delayed)

        assertTrue(console.executeLine("/button ev hold"))
        assertEquals(true, backend.states.getValue("soc-adc")["fn1Button"])
        assertTrue(console.executeLine("/button fn1 release"))
        assertEquals(false, backend.states.getValue("soc-adc")["fn1Button"])

        assertTrue(console.executeLine("/button +- hold"))
        assertEquals(true, backend.states.getValue("soc-adc")["fn1Button"])
        assertTrue(console.executeLine("/button expcomp release"))
        assertEquals(false, backend.states.getValue("soc-adc")["fn1Button"])

        assertTrue(console.executeLine("/button ael tap 150"))
        assertEquals(true, backend.states.getValue("soc-adc")["aelButton"])
        assertEquals(1, delayed.size)
        assertEquals(150L, delayed.single().first)
        delayed.single().second.invoke()
        assertEquals(false, backend.states.getValue("soc-adc")["aelButton"])

        val adcPatches = backend.patchHistory.filter { it.first == "soc-adc" }.map { it.second }
        assertEquals(
            listOf(
                mapOf("fn1Button" to true),
                mapOf("fn1Button" to false),
                mapOf("fn1Button" to true),
                mapOf("fn1Button" to false),
                mapOf("aelButton" to true),
                mapOf("aelButton" to false),
            ),
            adcPatches,
        )
        console.close()
    }
}
