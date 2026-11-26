package com.github.nyabanban.fuji.emulator.desktop.console

import com.github.nyabanban.fuji.emulator.api.BokehMaskShape
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.NdFilterPreset
import com.github.nyabanban.fuji.emulator.api.NdFilterSpec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotCodec
import com.github.nyabanban.fuji.emulator.api.UvFilterPreset
import com.github.nyabanban.fuji.emulator.desktop.EmulatorRuntime
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.AudioBackendPreference
import com.github.nyabanban.fuji.emulator.desktop.panels.AudioDeviceCatalogSnapshot
import com.github.nyabanban.fuji.emulator.desktop.panels.AudioInputMode
import com.github.nyabanban.fuji.emulator.desktop.panels.AudioIoStatusSnapshot
import com.github.nyabanban.fuji.emulator.desktop.panels.CmosSourceStatus
import com.github.nyabanban.fuji.emulator.desktop.panels.HostAudioRuntimeAdapter
import com.github.nyabanban.fuji.emulator.desktop.panels.LensProfileState
import com.github.nyabanban.fuji.emulator.desktop.panels.LensTemplateSpec
import com.github.nyabanban.fuji.emulator.desktop.panels.MomentaryHoldPolicy
import com.github.nyabanban.fuji.emulator.desktop.panels.OpticalFilterStatus
import com.github.nyabanban.fuji.emulator.runtime.AsyncPerfProfiler
import com.github.nyabanban.fuji.emulator.runtime.ConsoleAnsi
import com.github.nyabanban.fuji.emulator.runtime.FirmwareStage
import com.github.nyabanban.fuji.emulator.runtime.FirmwareTelemetry
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.LogArchiver
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * Abstraction between [EmulatorConsole] command handlers and the live [EmulatorRuntime],
 * enabling deterministic unit testing of core and component commands without launching Dynarmic or JavaFX.
 */
interface ConsoleBackend {
    val home: Path
    val telemetry: FirmwareTelemetry
    val profiler: AsyncPerfProfiler

    fun isPaused(): Boolean
    fun setPaused(paused: Boolean): Boolean
    fun isRunning(): Boolean
    fun requestBoot(): String
    fun requestStop(): String

    fun listComponents(): List<String>

    /** Returns combined config + live state map for [componentId], or `null` if not loaded. */
    fun componentState(componentId: String): Map<String, Any?>?

    /** Applies a validated live patch to [componentId] and returns the updated state/config map. */
    fun patchComponent(componentId: String, changes: Map<String, Any>): Map<String, Any?>

    /** Captures the live display surface (`display.main`, `display.evf`, or `display.shoulder`). */
    fun captureDisplay(portName: String): CapturedFrame?

    /** Returns live host audio I/O status map (output, input/loopback, VU peaks, latency, RX/TX stats). */
    fun audioStatus(): Map<String, Any?> = emptyMap()

    /** Returns host audio output/input/loopback device catalog map. */
    fun audioDevices(): Map<String, Any?> = emptyMap()

    /** Applies a live host audio I/O patch and returns the updated status map. */
    fun patchAudio(changes: Map<String, Any?>): Map<String, Any?> = audioStatus()

    /** Returns active user profile theme status and available body/UI themes. */
    fun themeStatus(): Map<String, Any?> = emptyMap()

    /** Applies a live camera body theme and/or UI window theme and persists `profile.yaml`. */
    fun applyTheme(bodyId: String? = null, uiId: String? = null): Map<String, Any?> =
        error("Theme controller is unavailable")

    /** Rescans external body/UI themes from disk and hot-reloads live windows. */
    fun reloadThemes(): Map<String, Any?> =
        error("Theme controller is unavailable")

    /** Returns live unified 64 MiB QSPI NOR Flash status map. */
    fun norFlashStatus(): Map<String, Any?> = emptyMap()

    /** Flashes a `.DAT` firmware update into the unified NOR Flash image and cold-reboots. */
    fun flashDatIntoNor(datPath: Path, preserveCalibration: Boolean = true): Map<String, Any?> =
        error("NOR Flash writer is unavailable")

    /** Exports the active 64 MiB NOR Flash image to [outputPath]. */
    fun exportNorFlash(outputPath: Path): Map<String, Any?> =
        error("NOR Flash exporter is unavailable")

    /** Imports a raw 64 MiB NOR Flash image from [inputPath] and cold-reboots. */
    fun importNorFlash(inputPath: Path): Map<String, Any?> =
        error("NOR Flash importer is unavailable")

    /** Restores the stock 2.13 factory NOR Flash image and cold-reboots. */
    fun restoreFactoryFlash(): Map<String, Any?> =
        error("Factory NOR Flash restore is unavailable")

    /** Persists user-selected optics backend / Vulkan GPU device into `profile.yaml`. */
    fun persistGraphicsPreferences(opticsBackend: String? = null, vulkanDevice: String? = null) {}

    data class CapturedFrame(
        val width: Int,
        val height: Int,
        val sequence: Long,
        val argbPixels: IntArray,
    )
}

/**
 * Live [ConsoleBackend] backed by an [EmulatorRuntime] and shared interactive pause/stop controls.
 */
class RuntimeConsoleBackend(
    private val runtime: EmulatorRuntime,
    private val paused: AtomicBoolean,
    private val stopRequested: AtomicBoolean,
    private val onBootRequested: () -> String,
    private val onStopRequested: () -> Unit,
    private val onPauseChanged: ((Boolean) -> Unit)? = null,
) : ConsoleBackend {
    override val home: Path get() = runtime.home
    override val telemetry: FirmwareTelemetry get() = runtime.telemetry
    override val profiler: AsyncPerfProfiler get() = runtime.profiler
    private val audioAdapter = HostAudioRuntimeAdapter(runtime)

    override fun isPaused(): Boolean = paused.get()

    override fun setPaused(paused: Boolean): Boolean {
        val prev = this.paused.getAndSet(paused)
        runtime.telemetry.paused = paused
        if (prev != paused) {
            onPauseChanged?.invoke(paused)
        }
        return prev != paused
    }

    override fun isRunning(): Boolean = runtime.telemetry.running

    override fun requestBoot(): String = onBootRequested()

    override fun requestStop(): String {
        stopRequested.set(true)
        paused.set(false)
        onStopRequested()
        return "Stopping the emulator..."
    }

    override fun listComponents(): List<String> = runtime.owner.submit {
        runtime.host.components().map { it.descriptor.id }.sorted()
    }.join()

    override fun componentState(componentId: String): Map<String, Any?>? = runtime.owner.submit {
        val comp = runtime.host.components().singleOrNull { it.descriptor.id == componentId }
            ?: return@submit null
        val merged = LinkedHashMap<String, Any?>()
        merged.putAll(comp.config)
        merged.putAll(comp.component.state())
        merged
    }.join()

    override fun patchComponent(componentId: String, changes: Map<String, Any>): Map<String, Any?> = runtime.owner.submit {
        if (componentId == "buttons") {
            runtime.telemetry.recordButtonEvent(changes.entries.joinToString(",") { "${it.key}=${it.value}" })
        }
        val comp = runtime.host.components().singleOrNull { it.descriptor.id == componentId }
            ?: error("Component \"$componentId\" is not loaded")
        runtime.host.patch(componentId, comp.revision, changes).join()
        val updated = runtime.host.components().single { it.descriptor.id == componentId }
        val merged = LinkedHashMap<String, Any?>()
        merged.putAll(updated.config)
        merged.putAll(updated.component.state())
        merged
    }.join()

    override fun captureDisplay(portName: String): ConsoleBackend.CapturedFrame? = runtime.owner.submit {
        val port = runtime.host.ports.find(portName, DisplayPort::class.java) ?: return@submit null
        val seq = port.sequence
        val w = port.width
        val h = port.height
        if (seq <= 0L || w <= 0 || h <= 0) return@submit null
        val px = IntArray(w * h)
        if (!port.copyArgb(px)) return@submit null
        ConsoleBackend.CapturedFrame(w, h, seq, px)
    }.join()

    override fun audioStatus(): Map<String, Any?> = audioAdapter.fetchStatus()

    override fun audioDevices(): Map<String, Any?> = audioAdapter.fetchDevices()

    override fun patchAudio(changes: Map<String, Any?>): Map<String, Any?> = audioAdapter.applyPatch(changes)

    override fun themeStatus(): Map<String, Any?> = runtime.themeController.statusMap()

    override fun applyTheme(bodyId: String?, uiId: String?): Map<String, Any?> {
        runtime.themeController.applyTheme(bodyId = bodyId, uiId = uiId)
        return runtime.themeController.statusMap()
    }

    override fun reloadThemes(): Map<String, Any?> {
        val r = runtime.themeController.reloadThemes()
        return runtime.themeController.statusMap() + mapOf(
            "reloaded" to true,
            "reloadGeneration" to r.reloadGeneration,
        )
    }

    override fun norFlashStatus(): Map<String, Any?> = runtime.norFlashStatus()

    override fun flashDatIntoNor(datPath: Path, preserveCalibration: Boolean): Map<String, Any?> =
        runtime.flashDatIntoNorLive(datPath, preserveCalibration).toMap()

    override fun exportNorFlash(outputPath: Path): Map<String, Any?> =
        runtime.exportNorFlashLive(outputPath).toMap()

    override fun importNorFlash(inputPath: Path): Map<String, Any?> =
        runtime.importNorFlashLive(inputPath).toMap()

    override fun restoreFactoryFlash(): Map<String, Any?> =
        runtime.restoreFactoryFlashLive().toMap()

    override fun persistGraphicsPreferences(opticsBackend: String?, vulkanDevice: String?) {
        runtime.themeController.updateGraphicsPreferences(
            opticsBackend = opticsBackend,
            vulkanDevice = vulkanDevice,
        )
    }
}

/**
 * Interactive stdin command console backed by [ConsoleCommandRegistry], with built-in profiler,
 * telemetry, lifecycle, and per-component hardware commands.
 *
 * - Runs on a daemon thread alongside both GUI mode and `--headless` / `--nogui` / `nogui`.
 * - Accepts optional leading `/` (`tps` and `/tps`, `profiler start` and `/profiler start`).
 * - Exits silently on EOF / closed stdin so CI, tests, and redirected runs never hang.
 */
class EmulatorConsole(
    private val backend: ConsoleBackend,
    private val input: InputStream = System.`in`,
    output: OutputStream = System.out,
    private val interactivePrompt: Boolean = System.console() != null,
    private val timeSupplier: () -> LocalTime = LocalTime::now,
    private val clockNanos: () -> Long = System::nanoTime,
    private val scheduleDelayed: (Long, () -> Unit) -> Unit = { delayMs, action ->
        Thread.ofVirtual().name("fuji-console-delay").start {
            try {
                Thread.sleep(delayMs)
                action()
            } catch (_: InterruptedException) {
                // Ignore cancellation on shutdown.
            }
        }
    },
    private val logControl: LogLevelControl = DefaultLogLevelControl,
) : AutoCloseable {
    interface LogLevelControl {
        fun statusSummary(): String
        fun getConsoleLevel(): String
        fun setConsoleLevel(levelName: String): String
        fun getFileLevel(): String
        fun setFileLevel(levelName: String): String
    }

    private object DefaultLogLevelControl : LogLevelControl {
        override fun statusSummary(): String = LogArchiver.statusSummary()
        override fun getConsoleLevel(): String = LogArchiver.getConsoleLevelName()
        override fun setConsoleLevel(levelName: String): String = LogArchiver.setConsoleLevel(levelName)
        override fun getFileLevel(): String = LogArchiver.getFileLevelName()
        override fun setFileLevel(levelName: String): String = LogArchiver.setFileLevel(levelName)
    }
    companion object {
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
        private val FILE_TS_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

        private const val COMMANDS_PER_PAGE: Int = 9
        private const val DEFAULT_TAP_MS: Long = 150L
        private const val MIN_TAP_MS: Long = 20L
        private const val MAX_TAP_MS: Long = 5_000L
        private const val DEFAULT_TOUCH_MS: Long = 120L
        private const val DEFAULT_SWIPE_STEP_MS: Long = 20L

        internal val SWIPE_DIRECTION_PRESETS: Map<String, IntArray> = mapOf(
            "up" to intArrayOf(512, 560, 512, 180),
            "down" to intArrayOf(512, 180, 512, 560),
            "left" to intArrayOf(760, 384, 240, 384),
            "right" to intArrayOf(240, 384, 760, 384),
        )

        internal val BUTTON_ALIASES: Map<String, String> = mapOf(
            "menu" to "menu",
            "ok" to "menu",
            "menu/ok" to "menu",
            "back" to "back",
            "disp" to "back",
            "disp/back" to "back",
            "play" to "play",
            "up" to "up",
            "down" to "down",
            "left" to "left",
            "right" to "right",
            "shutter" to "shutterFull",
            "shutterfull" to "shutterFull",
            "shutter_full" to "shutterFull",
            "s2" to "shutterFull",
            "shutterhalf" to "shutterHalf",
            "shutter_half" to "shutterHalf",
            "half" to "shutterHalf",
            "s1" to "shutterHalf",
            "power" to "power",
            "pwr" to "power",
            "movie" to "movieMode",
            "moviemode" to "movieMode",
            "movie_mode" to "movieMode",
            "delete" to "delete",
            "del" to "delete",
            "reardialpress" to "rearDialPress",
            "rear_dial_press" to "rearDialPress",
            "drive" to "drive",
            "afon" to "afOn",
            "af-on" to "afOn",
            "af_on" to "afOn",
            "focus" to "focusMode",
            "focusmode" to "focusMode",
            "focus_mode" to "focusMode",
            "frontdialpress" to "frontDialPress",
            "front_dial_press" to "frontDialPress",
            "front-dial-press" to "frontDialPress",
            "fn4" to "fn4",
            "focusstickpress" to "focusStickPress",
            "focus_stick_press" to "focusStickPress",
            "focus-stick-press" to "focusStickPress",
            "stickpress" to "focusStickPress",
            "stick_press" to "focusStickPress",
            "stick-press" to "focusStickPress",
        )

        private val LATCHING_BUTTONS = setOf("power", "movieMode", "focusMode")

        internal val ADC_BUTTON_ALIASES: Map<String, String> = mapOf(
            "fn1" to "fn1Button",
            "ev" to "fn1Button",
            "expcomp" to "fn1Button",
            "exp_comp" to "fn1Button",
            "exp-comp" to "fn1Button",
            "+-" to "fn1Button",
            "±" to "fn1Button",
            "fn2" to "fn2Button",
            "fn3" to "fn3Button",
            "q" to "qButton",
            "ael" to "aelButton",
            "ae-l" to "aelButton",
            "ae_l" to "aelButton",
            "light" to "lightButton",
        )

        internal val DISPLAY_ALIASES: Map<String, String> = mapOf(
            "main" to "display.main",
            "lcd" to "display.main",
            "evf" to "display.evf",
            "sub" to "display.shoulder",
            "shoulder" to "display.shoulder",
        )

        /** Parses a console line supporting double/single-quoted path arguments and optional leading `/`. */
        fun tokenize(rawLine: String): List<String> {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return emptyList()
            val cleaned = if (trimmed.startsWith('/')) trimmed.substring(1).trimStart() else trimmed
            if (cleaned.isEmpty()) return emptyList()

            val tokens = ArrayList<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            var i = 0
            while (i < cleaned.length) {
                val c = cleaned[i]
                if (quote != null) {
                    if (c == quote) {
                        quote = null
                    } else {
                        cur.append(c)
                    }
                } else when {
                    c == '"' || c == '\'' -> quote = c
                    c.isWhitespace() -> {
                        if (cur.isNotEmpty()) {
                            tokens.add(cur.toString())
                            cur.setLength(0)
                        }
                    }
                    else -> cur.append(c)
                }
                i++
            }
            if (cur.isNotEmpty()) tokens.add(cur.toString())
            return tokens
        }
    }

    private val out = PrintWriter(OutputStreamWriter(output, StandardCharsets.UTF_8), true)
    private val running = AtomicBoolean(false)
    @Volatile private var readerThread: Thread? = null

    val registry: ConsoleCommandRegistry = ConsoleCommandRegistry()

    init {
        registerCoreCommands()
        registerLoadedComponentCommands()
    }

    fun start(): EmulatorConsole {
        if (!running.compareAndSet(false, true)) return this
        val thread = Thread({ runLoop() }, "fuji-console").apply {
            isDaemon = true
        }
        readerThread = thread
        thread.start()
        return this
    }

    private fun runLoop() {
        emitInfo("Done! Type \"help\" for console commands (or \"stop\" to quit).")
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        try {
            while (running.get()) {
                if (interactivePrompt) {
                    out.print(ConsoleAnsi.formatPrompt(isColorEnabled()))
                    out.flush()
                }
                val line = try {
                    reader.readLine()
                } catch (_: Throwable) {
                    null
                }
                if (line == null) {
                    // Graceful EOF (CI, redirected stdin, or closed pipe) — never stop emulator or hang.
                    break
                }
                if (!running.get()) break
                val shouldContinue = executeLine(line)
                if (!shouldContinue) break
            }
        } finally {
            running.set(false)
        }
    }

    /**
     * Executes a single command line synchronously through [registry].
     *
     * @return `false` if the command requested emulator shutdown (`stop` / `quit`), `true` otherwise.
     */
    fun executeLine(rawLine: String): Boolean {
        val tokens = tokenize(rawLine)
        if (tokens.isEmpty()) return true
        val result = registry.dispatch(
            rawLabel = tokens[0],
            args = tokens.drop(1),
            info = ::emitInfo,
            warn = ::emitWarn,
        )
        if (result is CommandResult.StopConsole) {
            running.set(false)
            return false
        }
        return true
    }

    private fun registerCoreCommands() {
        registry.register(
            ConsoleCommand(
                name = "help",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                aliases = listOf("?"),
                description = "Show paginated command list, command details, or commands owned by a component",
                usage = "/help [page | <command> | <component>]",
                executor = ::execHelp,
            ),
        )
        registry.register(
            ConsoleCommand(
                name = "tps",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                aliases = listOf("status"),
                description = "Show live RTR, average/effective MIPS, WFI%, stage, frames, state, and JVM heap",
                usage = "/tps",
                executor = ::execTps,
            ),
        )
        registry.register(
            ConsoleCommand(
                name = "profiler",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                aliases = listOf("prof", "timings"),
                description = "Control the asynchronous sampling performance profiler and view reports",
                usage = "/profiler <start [--interval <ms>] | stop [--save <path.json>] | info | report [--save <path.json>] | health>",
                executor = ::execProfiler,
            ),
        )
        registry.register(
            ConsoleCommand(
                name = "health",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                aliases = listOf("gc"),
                description = "Show JVM heap, GC stats, thread counts, OS/CPU load, and emulator health",
                usage = "/health",
                executor = ::execHealth,
            ),
        )
        registry.register(
            ConsoleCommand(
                name = "components",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                aliases = listOf("plugins", "pl"),
                description = "List loaded hardware components and their registered console command counts",
                usage = "/components",
                executor = ::execComponents,
            ),
        )
        registry.register(
            ConsoleCommand(
                name = "boot",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                aliases = listOf("start"),
                description = "Start firmware execution if not already booted (or resume if paused)",
                usage = "/boot",
                executor = { ctx ->
                    ctx.info(backend.requestBoot())
                    CommandResult.Ok
                },
            ),
        )
        registry.register(
            ConsoleCommand(
                name = "pause",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                description = "Pause live firmware execution",
                usage = "/pause",
                executor = ::execPause,
            ),
        )
        registry.register(
            ConsoleCommand(
                name = "resume",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                aliases = listOf("unpause", "continue"),
                description = "Resume paused firmware execution",
                usage = "/resume",
                executor = ::execResume,
            ),
        )
        registry.register(
            ConsoleCommand(
                name = "audio",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                aliases = listOf("sound"),
                description = "Inspect or configure host audio output, mic/loopback input, latency, backend, and 3.5mm jacks",
                usage = "/audio [status | devices | out <on|off|mute|unmute|vol <0..100>|device <id|default>> | in <off|mic|loopback|tone> | in-device <id|default> | gain <-24..24> | in-mute <on|off> | monitor <on|off> | latency <10..500> | backend <auto|native|javasound> | hp <on|off> | mic-jack <on|off>]",
                executor = ::execAudio,
            ),
        )
        registry.register(
            ConsoleCommand(
                name = "color",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                aliases = listOf("ansi", "colours"),
                description = "Inspect or toggle ANSI colored console output (auto/on/off/status)",
                usage = "/color [auto | on | off | status]",
                executor = ::execColor,
            ),
        )
        registry.register(
            ConsoleCommand(
                name = "log",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                aliases = listOf("loglevel", "logger"),
                description = "Inspect or adjust live console/file log threshold (TRACE/DEBUG/INFO/WARN/ERROR/OFF)",
                usage = "/log [status | level <TRACE|DEBUG|INFO|WARN|ERROR|OFF> | file <TRACE|DEBUG|INFO|WARN|ERROR|OFF>]",
                executor = ::execLog,
            ),
        )
        registry.register(
            ConsoleCommand(
                name = "lang",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                aliases = listOf("language", "locale"),
                description = "Inspect or switch the Studio UI language live (en / zh_CN / ja_JP / de_DE / fr_FR / es_ES)",
                usage = "/lang [status | list | en | zh_CN | ja_JP | de_DE | fr_FR | es_ES]",
                executor = ::execLang,
            ),
        )
        registry.register(
            ConsoleCommand(
                name = "stop",
                owner = ConsoleCommandRegistry.CORE_OWNER,
                aliases = listOf("quit", "exit", "end"),
                description = "Gracefully stop the emulator and flush persistent storage and logs",
                usage = "/stop",
                executor = { ctx ->
                    ctx.info(backend.requestStop())
                    CommandResult.StopConsole
                },
            ),
        )
    }

    /**
     * Registers commands for each loaded hardware component reported by [ConsoleBackend.listComponents].
     */
    private fun registerLoadedComponentCommands() {
        val loaded = runCatching { backend.listComponents().toSet() }.getOrDefault(emptySet())

        if ("buttons" in loaded) {
            registry.register(
                ConsoleCommand(
                    name = "button",
                    owner = "buttons",
                    aliases = listOf("press", "btn", "key"),
                    description = "Tap, hold, or release a camera button or switch",
                    usage = "/button <name> [tap|hold|release] [ms]",
                    executor = ::execButton,
                ),
            )
            registry.register(
                ConsoleCommand(
                    name = "power",
                    owner = "buttons",
                    description = "Inspect or toggle the camera power switch",
                    usage = "/power [status | on | off]",
                    executor = ::execPower,
                ),
            )
            registry.register(
                ConsoleCommand(
                    name = "mode",
                    owner = "buttons",
                    description = "Inspect or switch between STILL and MOVIE shooting mode",
                    usage = "/mode [status | still | movie]",
                    executor = ::execMode,
                ),
            )
        }

        if ("soc-gpio" in loaded) {
            registry.register(
                ConsoleCommand(
                    name = "focus",
                    owner = "soc-gpio",
                    aliases = listOf("focusmode", "afmode"),
                    description = "Inspect or switch the 3-way physical Focus Mode lever (AF-S / AF-C / MF)",
                    usage = "/focus [status | af-s | af-c | mf | default]",
                    executor = ::execFocus,
                ),
            )
        }

        if ("soc-adc" in loaded) {
            registry.register(
                ConsoleCommand(
                    name = "dial",
                    owner = "soc-adc",
                    aliases = listOf("modedial", "mode-dial"),
                    description = "Inspect or switch the physical Mode Dial (P / S / A / M / C1..C6 / default)",
                    usage = "/dial [status | P | S | A | M | C1..C6 | default]",
                    executor = ::execModeDial,
                ),
            )
        }

        if ("i2c-bus" in loaded) {
            registry.register(
                ConsoleCommand(
                    name = "touch",
                    owner = "i2c-bus",
                    description = "Inject a rear LCD capacitive touchscreen tap or 4-way/coordinate swipe",
                    usage = "/touch <x> <y> [ms] | /touch swipe <up|down|left|right | x1 y1 x2 y2> [ms]",
                    executor = ::execTouch,
                ),
            )
            registry.register(
                ConsoleCommand(
                    name = "evf",
                    owner = "i2c-bus",
                    description = "Inspect or switch the EVF eye sensor proximity (near = EVF, far = rear LCD)",
                    usage = "/evf [status | near | far]",
                    executor = ::execEvf,
                ),
            )
        }

        if ("display" in loaded) {
            registry.register(
                ConsoleCommand(
                    name = "screenshot",
                    owner = "display",
                    aliases = listOf("ss", "snap"),
                    description = "Capture a live display surface framebuffer to PNG",
                    usage = "/screenshot [main|evf|sub] [path.png]",
                    executor = ::execScreenshot,
                ),
            )
            registry.register(
                ConsoleCommand(
                    name = "display",
                    owner = "display",
                    description = "Inspect display dimensions/frame counters or set backlight brightness",
                    usage = "/display [status | brightness <0.0-1.0>]",
                    executor = ::execDisplay,
                ),
            )
        }

        if ("sdcard-reader" in loaded) {
            registry.register(
                ConsoleCommand(
                    name = "sd",
                    owner = "sdcard-reader",
                    aliases = listOf("sdcard"),
                    description = "Show Slot 1 / Slot 2 SD card insertion state, backend, and path",
                    usage = "/sd [status]",
                    executor = ::execSd,
                ),
            )
        }

        if ("battery" in loaded) {
            registry.register(
                ConsoleCommand(
                    name = "battery",
                    owner = "battery",
                    aliases = listOf("bat"),
                    description = "Inspect or adjust virtual battery charge level, voltage, or presence",
                    usage = "/battery [status | level <0-100> | mv <0-12000> | present <on|off>]",
                    executor = ::execBattery,
                ),
            )
        }

        if ("mount" in loaded) {
            registry.register(
                ConsoleCommand(
                    name = "mount",
                    owner = "mount",
                    description = "Inspect G-mount lens status or attach/detach the virtual lens",
                    usage = "/mount [status | attach | detach]",
                    executor = ::execMount,
                ),
            )
        }

        if ("lens" in loaded) {
            registry.register(
                ConsoleCommand(
                    name = "lens",
                    owner = "lens",
                    aliases = listOf("optics"),
                    description = "Inspect or control virtual lens template, aperture ring (A/C/f), focal length, focus, OIS, AF, optics, and Vulkan GPU",
                    usage = "/lens [status | templates | template <id> | rescan | ring <A|C|MANUAL> | aperture <A|C|f> | focal <mm> | focus <m|inf> | ois <on|off> | af | optics <on|off> | gpu [list | auto | vulkan | cpu | <index|name>]]",
                    executor = ::execLens,
                ),
            )
            registry.register(
                ConsoleCommand(
                    name = "gpu",
                    owner = "lens",
                    aliases = listOf("vulkan"),
                    description = "List or select optical compute backend and Vulkan physical GPU (game zero-copy requires same GPU as the game)",
                    usage = "/gpu [list | auto | vulkan | cpu | <index|name>]",
                    executor = ::execGpu,
                ),
            )
        }

        if ("cmos" in loaded) {
            registry.register(
                ConsoleCommand(
                    name = "cmos",
                    owner = "cmos",
                    description = "Inspect virtual CMOS image sensor source and exposure status",
                    usage = "/cmos [status]",
                    executor = ::execCmos,
                ),
            )
            registry.register(
                ConsoleCommand(
                    name = "source",
                    owner = "cmos",
                    aliases = listOf("src"),
                    description = "Inspect or switch CMOS input source (image, video, stream, webcam, screen, 3D scene)",
                    usage = "/source [status | set <kind> <ref> [loop]]",
                    executor = ::execSource,
                ),
            )
        }

        if ("optical-filter" in loaded) {
            registry.register(
                ConsoleCommand(
                    name = "filter",
                    owner = "optical-filter",
                    aliases = listOf("filters", "optical-filter"),
                    description = "Inspect or configure decoupled optical filter matte box (ordered slots, UV, ND, Bokeh Mask, Star, Black Mist, GND)",
                    usage = "/filter [status | clear | on | bypass | rescan | uv <off|pro|standard|cheap> | add <uv|nd|bokeh|star|mist|gnd|plugin> [preset] | remove <1..N> | move <1..N> <up|down|target> | toggle <1..N> | nd <preset|stops> | bokeh <off|star|heart|hexagon|ring> [angle] | star <off|4|6|8> [strength] [angle] | mist <off|1/8|1/4|1/2|1|strength> | gnd <off|stops> [horizon] [feather] [angle]]",
                    executor = ::execFilter,
                ),
            )
            registry.register(
                ConsoleCommand(
                    name = "nd",
                    owner = "optical-filter",
                    description = "Shorthand for Neutral Density filter preset (OFF, ND2..ND1000) or continuous EV stops (0.0..10.0)",
                    usage = "/nd [status | off | set <preset> | stops <0.0..10.0> | <preset> | <stops>]",
                    executor = ::execNd,
                ),
            )
        }
        registry.register(
            ConsoleCommand(
                name = "theme",
                owner = "core",
                aliases = listOf("themes"),
                description = "Inspect, hot-reload, or live-switch camera Body Theme and UI Window Theme",
                usage = "/theme [status | list | body <id> | ui <id> | reload]",
                executor = ::execTheme,
            ),
        )

        if ("flash-image" in loaded) {
            registry.register(
                ConsoleCommand(
                    name = "flash",
                    owner = "flash-image",
                    description = "Inspect or flash the unified 64 MiB QSPI NOR Flash image (status / write .DAT / export / import / restore-factory)",
                    usage = "/flash [status | write <file.DAT> [--no-preserve-cal] | export <out.bin> | import <in.bin> | restore-factory]",
                    executor = ::execFlash,
                ),
            )
        }
    }

    // ==================== Core Commands ====================

    @Suppress("UNCHECKED_CAST")
    private fun execTheme(ctx: ConsoleCommandContext): CommandResult {
        val sub = ctx.args.firstOrNull()?.lowercase(Locale.ROOT) ?: "status"
        return when (sub) {
            "status" -> {
                val st = backend.themeStatus()
                if (st.isEmpty()) return CommandResult.Error("Theme controller is unavailable.")
                val bodyId = st["body"]?.toString() ?: "origin"
                val uiId = st["ui"]?.toString() ?: "origin"
                val bodyInfo = st["bodyTheme"] as? Map<String, Any?>
                val uiInfo = st["uiTheme"] as? Map<String, Any?>
                val panelCount = bodyInfo?.get("panelCount") ?: "?"
                val profilePath = st["profilePath"]?.toString() ?: "profile.yaml"
                ctx.info("Theme status:")
                ctx.info("  body    : $bodyId (${bodyInfo?.get("name") ?: bodyId}, panels=$panelCount)")
                ctx.info("  ui      : $uiId (${uiInfo?.get("name") ?: uiId})")
                ctx.info("  profile : $profilePath")
                CommandResult.Ok
            }
            "list", "ls" -> {
                val st = backend.themeStatus()
                if (st.isEmpty()) return CommandResult.Error("Theme controller is unavailable.")
                val activeBody = st["body"]?.toString()
                val activeUi = st["ui"]?.toString()
                val bodies = (st["availableBody"] as? List<Map<String, Any?>>).orEmpty()
                val uis = (st["availableUi"] as? List<Map<String, Any?>>).orEmpty()
                ctx.info("Camera Body Themes (${bodies.size}):")
                bodies.forEach { b ->
                    val id = b["id"]?.toString().orEmpty()
                    val marker = if (id == activeBody) "*" else " "
                    val tag = if (b["builtIn"] == true) "built-in" else "external"
                    ctx.info("  $marker $id — ${b["name"]} (${b["panelCount"]} panels, $tag)")
                }
                ctx.info("UI Window Themes (${uis.size}):")
                uis.forEach { u ->
                    val id = u["id"]?.toString().orEmpty()
                    val marker = if (id == activeUi) "*" else " "
                    val tag = if (u["builtIn"] == true) "built-in" else "external"
                    ctx.info("  $marker $id — ${u["name"]} ($tag)")
                }
                CommandResult.Ok
            }
            "body" -> {
                val id = ctx.args.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return CommandResult.Error("Usage: /theme body <id> (run '/theme list' to see available body themes)")
                return try {
                    val updated = backend.applyTheme(bodyId = id)
                    val bodyInfo = updated["bodyTheme"] as? Map<String, Any?>
                    ctx.info("Switched body theme to '${updated["body"]}' (${bodyInfo?.get("name") ?: id}, panels=${bodyInfo?.get("panelCount") ?: "?"}). Saved to profile.yaml.")
                    CommandResult.Ok
                } catch (e: IllegalArgumentException) {
                    CommandResult.Error(e.message ?: "Unknown body theme: $id")
                }
            }
            "ui" -> {
                val id = ctx.args.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return CommandResult.Error("Usage: /theme ui <id> (run '/theme list' to see available UI themes)")
                return try {
                    val updated = backend.applyTheme(uiId = id)
                    val uiInfo = updated["uiTheme"] as? Map<String, Any?>
                    ctx.info("Switched UI theme to '${updated["ui"]}' (${uiInfo?.get("name") ?: id}). Saved to profile.yaml.")
                    CommandResult.Ok
                } catch (e: IllegalArgumentException) {
                    CommandResult.Error(e.message ?: "Unknown UI theme: $id")
                }
            }
            "reload" -> {
                return try {
                    val updated = backend.reloadThemes()
                    val bodies = (updated["availableBody"] as? List<*>).orEmpty().size
                    val uis = (updated["availableUi"] as? List<*>).orEmpty().size
                    val gen = updated["reloadGeneration"] ?: "?"
                    ctx.info("Reloaded themes (gen=$gen): body='${updated["body"]}' ($bodies available), ui='${updated["ui"]}' ($uis available).")
                    CommandResult.Ok
                } catch (e: Exception) {
                    CommandResult.Error(e.message ?: "Theme reload failed")
                }
            }
            else -> CommandResult.Error("Usage: /theme [status | list | body <id> | ui <id> | reload]")
        }
    }

    private fun execHelp(ctx: ConsoleCommandContext): CommandResult {
        val all = ctx.registry.allCommands()
        val totalPages = ((all.size + COMMANDS_PER_PAGE - 1) / COMMANDS_PER_PAGE).coerceAtLeast(1)

        if (ctx.args.isEmpty()) {
            printHelpPage(ctx, all, page = 1, totalPages = totalPages)
            return CommandResult.Ok
        }

        val topic = ctx.args[0].trim().removePrefix("/")
        val pageNum = topic.toIntOrNull()
        if (pageNum != null) {
            if (pageNum !in 1..totalPages) {
                return CommandResult.Error("Help page $pageNum out of range (1..$totalPages).")
            }
            printHelpPage(ctx, all, page = pageNum, totalPages = totalPages)
            return CommandResult.Ok
        }

        // Exact component/owner query takes priority when matching a component owner with commands
        // and the user did not supply a namespaced `owner:cmd` label.
        val lowerTopic = topic.lowercase(Locale.ROOT)
        val exactCommand = ctx.registry.resolve(topic)
        val ownerCommands = ctx.registry.commandsByOwner(lowerTopic)

        // If the topic matches an owner AND is not the same name as a command (or has >1 command),
        // show owner command index; if it matches a command directly (like `battery` or `mount`),
        // show the command details and note owner when helpful.
        if (ownerCommands.isNotEmpty() && (exactCommand == null || ownerCommands.size > 1 && exactCommand.name != lowerTopic)) {
            ctx.info("--------- Help: Component \"$lowerTopic\" (${ownerCommands.size} command${if (ownerCommands.size == 1) "" else "s"}) ---------")
            for (cmd in ownerCommands) {
                ctx.info("/${cmd.name}: ${cmd.description}")
            }
            return CommandResult.Ok
        }

        if (exactCommand != null) {
            ctx.info("--------- Help: /${exactCommand.name} ---------")
            ctx.info("Description: ${exactCommand.description}")
            ctx.info("Usage: ${exactCommand.usage}")
            if (exactCommand.aliases.isNotEmpty()) {
                ctx.info("Aliases: ${exactCommand.aliases.joinToString(", ")}")
            }
            ctx.info("Owner: ${exactCommand.owner} (${exactCommand.qualifiedName})")
            return CommandResult.Ok
        }

        val suggestion = ctx.registry.suggestClosest(topic)
        val hint = if (suggestion != null) " Did you mean \"$suggestion\"?" else ""
        return CommandResult.Error("No help found for \"$topic\".$hint Type \"help\" for all commands.")
    }

    private fun printHelpPage(
        ctx: ConsoleCommandContext,
        all: List<ConsoleCommand>,
        page: Int,
        totalPages: Int,
    ) {
        ctx.info("--------- Help: Fuji GFX100S Console (Page $page/$totalPages) ---------")
        ctx.info("Use \"help <page>\", \"help <command>\", or \"help <component>\" for details.")
        val start = (page - 1) * COMMANDS_PER_PAGE
        val end = (start + COMMANDS_PER_PAGE).coerceAtMost(all.size)
        for (i in start until end) {
            val cmd = all[i]
            ctx.info("/${cmd.name} [${cmd.owner}]: ${cmd.description}")
        }
    }

    private fun execTps(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isNotEmpty()) {
            return CommandResult.UsageError("Unexpected arguments: ${ctx.args.joinToString(" ")}")
        }
        val t = backend.telemetry
        val now = clockNanos()
        val elapsedNanos = if (t.startedAtNanos > 0L) (now - t.startedAtNanos).coerceAtLeast(0L) else 0L
        val wallSec = elapsedNanos / 1_000_000_000.0
        val rtr = if (wallSec > 0.0) (t.virtualTick / AsyncPerfProfiler.DEFAULT_TICKS_PER_REAL_SECOND) / wallSec else 0.0
        val avgMips = if (wallSec > 0.0) (t.attempts / wallSec) / 1_000_000.0 else 0.0
        val effMips = if (wallSec > 0.0) (t.virtualTick / wallSec) / 1_000_000.0 else 0.0
        val wfiPct = if (t.virtualTick > 0L) (t.wfiSkippedTicks.toDouble() / t.virtualTick.toDouble()) * 100.0 else 0.0
        val stage = FirmwareStage.highestReached(t.stageMask)?.label ?: "PRE_BOOT"
        val stateLabel = when {
            backend.isPaused() -> "PAUSED"
            t.running -> "RUNNING"
            else -> t.status.uppercase(Locale.ROOT)
        }
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        val maxMb = rt.maxMemory() / (1024L * 1024L)
        ctx.info(
            String.format(
                Locale.ROOT,
                "TPS/Perf: RTR=%.3fx | Avg=%.2f MIPS | Eff=%.2f vMIPS | WFI=%.1f%% | SMP-MT=%s | Stage=%s | Frames=%d | State=%s | Heap=%d/%d MB",
                rtr,
                avgMips,
                effMips,
                wfiPct,
                if (t.smpHostThreadsEnabled) "ON" else "OFF",
                stage,
                t.publishedFrames,
                stateLabel,
                usedMb,
                maxMb,
            ),
        )
        return CommandResult.Ok
    }

    private fun execProfiler(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty()) {
            return CommandResult.UsageError()
        }
        val sub = when (val raw = ctx.args[0].lowercase(Locale.ROOT)) {
            "on" -> "start"
            "off" -> "stop"
            "status" -> "info"
            "paste" -> "report"
            else -> raw
        }
        val rest = ctx.args.drop(1)
        return when (sub) {
            "start" -> {
                val intervalMs = parseIntervalOption(rest)
                    ?: return CommandResult.UsageError(
                        "Invalid --interval value. Expected integer in ${AsyncPerfProfiler.MIN_SAMPLE_INTERVAL_MS}..${AsyncPerfProfiler.MAX_SAMPLE_INTERVAL_MS}.",
                    )
                val started = backend.profiler.start(intervalMs)
                ctx.info("[Profiler] Started (interval=${started["sampleIntervalMs"]}ms). Run \"profiler stop\" to view the report.")
                CommandResult.Ok
            }
            "stop" -> {
                val savePath = parseSaveOption(rest)
                if (savePath == INVALID_OPTION_SENTINEL) {
                    return CommandResult.UsageError("Invalid option for profiler stop. Expected: --save <path.json>")
                }
                val wasRunning = backend.profiler.isRunning()
                val report = backend.profiler.stop()
                if (!wasRunning) {
                    ctx.info("[Profiler] Profiler was not running; showing last captured report:")
                }
                emitMultiline(backend.profiler.formatTextReport())
                saveProfilerJsonIfRequested(ctx, savePath, report)
                CommandResult.Ok
            }
            "info" -> {
                if (rest.isNotEmpty()) {
                    return CommandResult.UsageError("Unexpected arguments for profiler info.")
                }
                val report = backend.profiler.reportMap()
                @Suppress("UNCHECKED_CAST")
                val slice = report["sliceMetrics"] as Map<String, Any?>
                val active = report["active"] == true
                ctx.info(
                    String.format(
                        Locale.ROOT,
                        "[Profiler] State: %s | SMP-MT=%s | interval=%sms | wall=%.2fs | samples=%s | RTR=%.3fx | Avg=%.2f MIPS | Eff=%.2f vMIPS | WFI=%.1f%%",
                        if (active) "RUNNING" else "STOPPED",
                        if (report["smpHostThreads"] == true) "ON" else "OFF",
                        report["sampleIntervalMs"],
                        (report["wallSeconds"] as Number).toDouble(),
                        report["totalSamples"],
                        (report["realTimeRatio"] as Number).toDouble(),
                        (report["avgMips"] as Number).toDouble(),
                        (report["effectiveVirtualMips"] as Number).toDouble(),
                        (slice["wfiRatioPct"] as Number).toDouble(),
                    ),
                )
                CommandResult.Ok
            }
            "report" -> {
                val savePath = parseSaveOption(rest)
                if (savePath == INVALID_OPTION_SENTINEL) {
                    return CommandResult.UsageError("Invalid option for profiler report. Expected: --save <path.json>")
                }
                val report = backend.profiler.reportMap()
                emitMultiline(backend.profiler.formatTextReport())
                saveProfilerJsonIfRequested(ctx, savePath, report)
                CommandResult.Ok
            }
            "health" -> execHealth(
                ConsoleCommandContext(ctx.label, rest, ctx.registry, ctx.info, ctx.warn),
            )
            else -> CommandResult.UsageError("Unknown profiler subcommand \"${ctx.args[0]}\".")
        }
    }

    private fun parseIntervalOption(args: List<String>): Long? {
        var i = 0
        var interval = AsyncPerfProfiler.DEFAULT_SAMPLE_INTERVAL_MS
        while (i < args.size) {
            val tok = args[i]
            when {
                tok == "--interval" || tok == "-i" -> {
                    val parsed = args.getOrNull(i + 1)?.toLongOrNull() ?: return null
                    interval = parsed
                    i += 2
                }
                tok.startsWith("--interval=") -> {
                    val parsed = tok.substringAfter('=').toLongOrNull() ?: return null
                    interval = parsed
                    i++
                }
                else -> {
                    val parsed = tok.toLongOrNull() ?: return null
                    interval = parsed
                    i++
                }
            }
        }
        return interval.takeIf { it in AsyncPerfProfiler.MIN_SAMPLE_INTERVAL_MS..AsyncPerfProfiler.MAX_SAMPLE_INTERVAL_MS }
    }

    private fun parseSaveOption(args: List<String>): Path? {
        var i = 0
        while (i < args.size) {
            val tok = args[i]
            when {
                tok == "--save" || tok == "-s" -> {
                    val raw = args.getOrNull(i + 1)
                    return if (raw.isNullOrBlank() || raw.startsWith('-')) {
                        val stamp = FILE_TS_FMT.format(Instant.now())
                        backend.home.resolve("logs").resolve("profiler-$stamp.json")
                    } else {
                        resolveUserPath(raw)
                    }
                }
                tok.startsWith("--save=") -> {
                    val raw = tok.substringAfter('=')
                    if (raw.isBlank()) return INVALID_OPTION_SENTINEL
                    return resolveUserPath(raw)
                }
                else -> return INVALID_OPTION_SENTINEL
            }
        }
        return null
    }

    private fun saveProfilerJsonIfRequested(ctx: ConsoleCommandContext, savePath: Path?, report: Map<String, Any?>) {
        if (savePath == null) return
        savePath.parent?.let { Files.createDirectories(it) }
        Formats.writeJson(savePath, report)
        ctx.info("[Profiler] Saved JSON report to: $savePath")
    }

    private fun execHealth(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isNotEmpty()) {
            return CommandResult.UsageError("Unexpected arguments: ${ctx.args.joinToString(" ")}")
        }
        val memMx = ManagementFactory.getMemoryMXBean()
        val heap = memMx.heapMemoryUsage
        val nonHeap = memMx.nonHeapMemoryUsage
        val usedMb = heap.used / (1024L * 1024L)
        val committedMb = heap.committed / (1024L * 1024L)
        val maxMb = if (heap.max > 0L) heap.max / (1024L * 1024L) else committedMb
        val heapPct = if (maxMb > 0L) (usedMb * 100.0) / maxMb else 0.0
        val nonHeapMb = nonHeap.used / (1024L * 1024L)

        val gcs = ManagementFactory.getGarbageCollectorMXBeans().joinToString(" | ") { gc ->
            "${gc.name}: count=${gc.collectionCount}, time=${gc.collectionTime}ms"
        }.ifEmpty { "none" }

        val threadMx = ManagementFactory.getThreadMXBean()
        val osMx = ManagementFactory.getOperatingSystemMXBean()
        val rtMx = ManagementFactory.getRuntimeMXBean()
        val uptimeSec = rtMx.uptime / 1000L
        val loadStr = if (osMx.systemLoadAverage >= 0.0) {
            String.format(Locale.ROOT, "%.2f", osMx.systemLoadAverage)
        } else {
            "n/a"
        }

        val t = backend.telemetry
        val stage = FirmwareStage.highestReached(t.stageMask)?.label ?: "PRE_BOOT"
        ctx.info("[Profiler] --- System Health Report ---")
        ctx.info(
            String.format(
                Locale.ROOT,
                "  Heap: %d / %d MB (%.1f%%, committed=%d MB) | Non-Heap: %d MB",
                usedMb,
                maxMb,
                heapPct,
                committedMb,
                nonHeapMb,
            ),
        )
        ctx.info("  GC: $gcs")
        ctx.info(
            "  Threads: live=${threadMx.threadCount}, daemon=${threadMx.daemonThreadCount}, peak=${threadMx.peakThreadCount}",
        )
        ctx.info(
            "  OS/CPU: ${osMx.name} (${osMx.arch}), cores=${osMx.availableProcessors}, load=$loadStr | JVM=${System.getProperty("java.version")} | Uptime=${uptimeSec}s",
        )
        ctx.info(
            "  Emulator: running=${t.running}, paused=${backend.isPaused()}, stage=$stage, frames=${t.publishedFrames}, attempts=${t.attempts}",
        )
        return CommandResult.Ok
    }

    private fun execComponents(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isNotEmpty()) {
            return CommandResult.UsageError("Unexpected arguments: ${ctx.args.joinToString(" ")}")
        }
        val components = backend.listComponents()
        val formatted = components.joinToString(", ") { id ->
            val count = ctx.registry.commandCountForOwner(id)
            if (count > 0) "$id ($count cmd${if (count == 1) "" else "s"})" else id
        }
        ctx.info("Components (${components.size}): $formatted")
        return CommandResult.Ok
    }

    private fun execPause(ctx: ConsoleCommandContext): CommandResult {
        if (!backend.isRunning()) {
            return CommandResult.Error("Emulator is not currently running.")
        }
        if (backend.setPaused(true)) {
            ctx.info("Firmware execution paused.")
        } else {
            ctx.info("Firmware execution is already paused.")
        }
        return CommandResult.Ok
    }

    private fun execResume(ctx: ConsoleCommandContext): CommandResult {
        if (!backend.isRunning()) {
            return CommandResult.Error("Emulator is not currently running. Use \"boot\" to start.")
        }
        if (backend.setPaused(false)) {
            ctx.info("Firmware execution resumed.")
        } else {
            ctx.info("Firmware execution is already running.")
        }
        return CommandResult.Ok
    }

    // ==================== Component Commands ====================

    private fun execButton(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty()) {
            return CommandResult.UsageError(
                "Missing button name. Available: MENU, PLAY, BACK, UP, DOWN, LEFT, RIGHT, SHUTTER, SHUTTERHALF, POWER, MOVIE.",
            )
        }
        val rawBtn = ctx.args[0].lowercase(Locale.ROOT)
        val gpioKey = BUTTON_ALIASES[rawBtn]
        val adcKey = if (gpioKey == null) ADC_BUTTON_ALIASES[rawBtn] else null
        val componentId = when {
            gpioKey != null -> "buttons"
            adcKey != null -> "soc-adc"
            else -> return CommandResult.UsageError(
                "Unknown button \"${ctx.args[0]}\". Available: MENU, PLAY, BACK, UP, DOWN, LEFT, RIGHT, SHUTTER, SHUTTERHALF, POWER, MOVIE, FN1/EV, FN2, FN3, Q, AEL, LIGHT.",
            )
        }
        val buttonKey = gpioKey ?: adcKey!!

        val second = ctx.args.getOrNull(1)?.lowercase(Locale.ROOT)
        val action = when {
            second == null -> if (componentId == "buttons" && buttonKey in LATCHING_BUTTONS) "toggle" else "tap"
            second == "tap" || second == "press" -> "tap"
            second == "hold" || second == "down" || second == "on" -> "hold"
            second == "release" || second == "up" || second == "off" -> "release"
            second == "toggle" -> "toggle"
            second.toLongOrNull() != null -> "tap"
            else -> return CommandResult.UsageError("Unknown button action \"$second\". Expected: tap, hold, release, or duration in ms.")
        }

        val explicitDurationMs = when {
            second?.toLongOrNull() != null -> second.toLong()
            ctx.args.getOrNull(2)?.toLongOrNull() != null -> ctx.args[2].toLong()
            else -> null
        }

        when (action) {
            "hold" -> {
                backend.patchComponent(componentId, mapOf(buttonKey to true))
                ctx.info("Button $buttonKey -> PRESSED (held)")
            }
            "release" -> {
                backend.patchComponent(componentId, mapOf(buttonKey to false))
                ctx.info("Button $buttonKey -> RELEASED")
            }
            "toggle" -> {
                val state = backend.componentState(componentId)
                val current = (state?.get(buttonKey) as? Boolean) ?: false
                val next = !current
                backend.patchComponent(componentId, mapOf(buttonKey to next))
                ctx.info("Switch $buttonKey -> ${if (next) "ON" else "OFF"}")
            }
            else -> {
                backend.patchComponent(componentId, mapOf(buttonKey to true))
                val releaseAction: () -> Unit = {
                    runCatching { backend.patchComponent(componentId, mapOf(buttonKey to false)) }
                }
                if (explicitDurationMs != null) {
                    val durationMs = explicitDurationMs.coerceIn(MIN_TAP_MS, MAX_TAP_MS)
                    ctx.info("Button $buttonKey -> TAP (${durationMs}ms)")
                    scheduleDelayed(durationMs, releaseAction)
                } else {
                    val minTicks = MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS
                    ctx.info("Button $buttonKey -> TAP (calibrated >=${minTicks} ticks / 75ms virtual)")
                    scheduleCalibratedTapRelease(
                        minVirtualTicks = minTicks,
                        fallbackWallMs = DEFAULT_TAP_MS,
                        release = releaseAction,
                    )
                }
            }
        }
        return CommandResult.Ok
    }

    private fun execPower(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val state = backend.componentState("buttons")
                ?: return CommandResult.Error("buttons component is not loaded.")
            val on = (state["power"] as? Boolean) ?: false
            ctx.info("Power switch: ${if (on) "ON" else "OFF"}")
            return CommandResult.Ok
        }
        val target = parseOnOff(ctx.args[0])
            ?: return CommandResult.UsageError("Expected: status, on, or off.")
        backend.patchComponent("buttons", mapOf("power" to target))
        ctx.info("Power switch -> ${if (target) "ON" else "OFF"}")
        return CommandResult.Ok
    }

    private fun execMode(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val state = backend.componentState("buttons")
                ?: return CommandResult.Error("buttons component is not loaded.")
            val movie = (state["movieMode"] as? Boolean) ?: false
            ctx.info("Shooting mode: ${if (movie) "MOVIE" else "STILL"}")
            return CommandResult.Ok
        }
        val movie = when (ctx.args[0].lowercase(Locale.ROOT)) {
            "movie", "video" -> true
            "still", "photo" -> false
            else -> return CommandResult.UsageError("Expected: status, still, or movie.")
        }
        backend.patchComponent("buttons", mapOf("movieMode" to movie))
        ctx.info("Shooting mode -> ${if (movie) "MOVIE" else "STILL"}")
        return CommandResult.Ok
    }

    private fun execLang(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val active = I18n.localeCode()
            val supported = I18n.supportedLanguageCodes().joinToString(", ")
            ctx.info("Language: $active (supported: $supported)")
            return CommandResult.Ok
        }
        val arg = ctx.args[0]
        if (arg.equals("list", ignoreCase = true)) {
            val active = I18n.localeCode()
            val formatted = I18n.supportedLanguageCodes().joinToString(", ") { code ->
                "$code (${I18n.nativeDisplayName(code)})"
            }
            ctx.info("Supported languages: $formatted [active: $active]")
            return CommandResult.Ok
        }
        if (ctx.args.size > 1) {
            return CommandResult.UsageError("Too many arguments for /lang")
        }
        if (!I18n.isSupportedCode(arg)) {
            return CommandResult.UsageError(
                "Unsupported language '$arg' (supported: ${I18n.supportedLanguageCodes().joinToString(", ")})",
            )
        }
        val resolved = I18n.setLocale(arg)
        ctx.info("Language -> ${I18n.localeCode(resolved)}")
        return CommandResult.Ok
    }

    private fun execFocus(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val state = backend.componentState("soc-gpio")
                ?: return CommandResult.Error("soc-gpio component is not loaded.")
            val configured = state["focusMode"]?.toString() ?: "default"
            val effective = state["effectiveFocusMode"]?.toString() ?: if (configured != "default") configured else "AF-S"
            ctx.info("Focus mode lever: $effective (setting=$configured)")
            return CommandResult.Ok
        }
        val normalized = when (ctx.args[0].trim().lowercase(Locale.ROOT)) {
            "af-s", "afs", "s", "single" -> "AF-S"
            "af-c", "afc", "c", "continuous", "cont" -> "AF-C"
            "mf", "m", "manual" -> "MF"
            "default", "auto" -> "default"
            else -> return CommandResult.UsageError("Expected: status, af-s, af-c, mf, or default.")
        }
        backend.patchComponent("soc-gpio", mapOf("focusMode" to normalized))
        ctx.info("Focus mode lever -> $normalized")
        return CommandResult.Ok
    }

    private fun execModeDial(ctx: ConsoleCommandContext): CommandResult {
        val validModes = listOf("default", "P", "S", "A", "M", "C1", "C2", "C3", "C4", "C5", "C6")
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val state = backend.componentState("soc-adc")
                ?: return CommandResult.Error("soc-adc component is not loaded.")
            val mode = state["modeDial"]?.toString() ?: "default"
            ctx.info("Mode dial: $mode")
            return CommandResult.Ok
        }
        val raw = ctx.args[0].trim()
        val matched = validModes.firstOrNull { it.equals(raw, ignoreCase = true) }
            ?: return CommandResult.UsageError("Expected: status, P, S, A, M, C1..C6, or default.")
        backend.patchComponent("soc-adc", mapOf("modeDial" to matched))
        ctx.info("Mode dial -> $matched")
        return CommandResult.Ok
    }

    private fun execTouch(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty()) {
            return CommandResult.UsageError("Expected <x> <y> [ms] or swipe <up|down|left|right | x1 y1 x2 y2> [ms].")
        }
        if (ctx.args[0].equals("swipe", ignoreCase = true) || ctx.args[0].equals("flick", ignoreCase = true)) {
            return execTouchSwipe(ctx.args.drop(1), ctx)
        }
        if (ctx.args.size < 2) {
            return CommandResult.UsageError("Expected x and y coordinates.")
        }
        val x = ctx.args[0].toIntOrNull()
        val y = ctx.args[1].toIntOrNull()
        if (x == null || y == null || x < 0 || y < 0 || x > 65535 || y > 65535) {
            return CommandResult.UsageError("Touch coordinates must be integers in 0..65535.")
        }
        val explicitDurationMs = ctx.args.getOrNull(2)?.toLongOrNull()
        val touchComponent = resolveTouchComponent()
        backend.patchComponent(
            touchComponent,
            mapOf("touchEnabled" to true, "touchX" to x.toLong(), "touchY" to y.toLong(), "touchPressed" to true),
        )
        val releaseAction: () -> Unit = {
            runCatching {
                backend.patchComponent(
                    touchComponent,
                    mapOf("touchEnabled" to true, "touchX" to x.toLong(), "touchY" to y.toLong(), "touchPressed" to false),
                )
            }
        }
        if (explicitDurationMs != null) {
            val durationMs = explicitDurationMs.coerceIn(MIN_TAP_MS, MAX_TAP_MS)
            ctx.info("Touch ($x, $y) -> TAP (${durationMs}ms)")
            scheduleDelayed(durationMs, releaseAction)
        } else {
            val minTicks = MomentaryHoldPolicy.CALIBRATED_TOUCH_TAP_VIRTUAL_TICKS
            ctx.info("Touch ($x, $y) -> TAP (calibrated >=${minTicks} ticks / 105ms virtual)")
            scheduleCalibratedTapRelease(
                minVirtualTicks = minTicks,
                fallbackWallMs = DEFAULT_TOUCH_MS,
                release = releaseAction,
            )
        }
        return CommandResult.Ok
    }

    private fun resolveTouchComponent(): String =
        if (backend.componentState("rear-touch-ic")?.get("enabled") == true) "rear-touch-ic" else "i2c-bus"

    private fun execTouchSwipe(args: List<String>, ctx: ConsoleCommandContext): CommandResult {
        if (args.isEmpty()) {
            return CommandResult.UsageError("Expected direction (up|down|left|right) or <x1> <y1> <x2> <y2> [ms].")
        }
        val presetKey = args[0].lowercase(Locale.ROOT)
        val preset = SWIPE_DIRECTION_PRESETS[presetKey]
        val x1: Int
        val y1: Int
        val x2: Int
        val y2: Int
        val explicitTotalMs: Long?
        val label: String
        if (preset != null) {
            if (args.size > 2) {
                return CommandResult.UsageError("Usage: /touch swipe <up|down|left|right> [ms]")
            }
            x1 = preset[0]
            y1 = preset[1]
            x2 = preset[2]
            y2 = preset[3]
            explicitTotalMs = args.getOrNull(1)?.let { raw ->
                raw.toLongOrNull() ?: return CommandResult.UsageError("Swipe duration must be an integer in ms.")
            }
            label = presetKey.uppercase(Locale.ROOT)
        } else {
            if (args.size < 4 || args.size > 5) {
                return CommandResult.UsageError("Usage: /touch swipe <up|down|left|right | x1 y1 x2 y2> [ms]")
            }
            x1 = args[0].toIntOrNull() ?: return CommandResult.UsageError("Touch coordinates must be integers in 0..65535.")
            y1 = args[1].toIntOrNull() ?: return CommandResult.UsageError("Touch coordinates must be integers in 0..65535.")
            x2 = args[2].toIntOrNull() ?: return CommandResult.UsageError("Touch coordinates must be integers in 0..65535.")
            y2 = args[3].toIntOrNull() ?: return CommandResult.UsageError("Touch coordinates must be integers in 0..65535.")
            if (x1 !in 0..65535 || y1 !in 0..65535 || x2 !in 0..65535 || y2 !in 0..65535) {
                return CommandResult.UsageError("Touch coordinates must be integers in 0..65535.")
            }
            explicitTotalMs = args.getOrNull(4)?.let { raw ->
                raw.toLongOrNull() ?: return CommandResult.UsageError("Swipe duration must be an integer in ms.")
            }
            label = "CUSTOM"
        }

        val touchComponent = resolveTouchComponent()
        // DAT s4:0x0124ef18 & s5:0x02092120: 4 steps x 20ms (=100ms virtual) stays well under LiveView
        // T-Fn's 260ms flick window while giving >=390px post-m1 drag travel for Playback slide-commit.
        val steps = 4
        val waypoints = ArrayList<Triple<Int, Int, Boolean>>(steps + 2)
        waypoints += Triple(x1, y1, true)
        for (i in 1..steps) {
            val sx = x1 + ((x2 - x1) * i) / steps
            val sy = y1 + ((y2 - y1) * i) / steps
            waypoints += Triple(sx, sy, true)
        }
        waypoints += Triple(x2, y2, false)

        fun sendPoint(p: Triple<Int, Int, Boolean>) {
            runCatching {
                backend.patchComponent(
                    touchComponent,
                    mapOf(
                        "touchEnabled" to true,
                        "touchX" to p.first.toLong(),
                        "touchY" to p.second.toLong(),
                        "touchPressed" to p.third,
                    ),
                )
            }
        }

        val stepTicks = MomentaryHoldPolicy.CALIBRATED_TOUCH_SWIPE_STEP_VIRTUAL_TICKS
        val stepWallMs = explicitTotalMs?.coerceIn(MIN_TAP_MS, MAX_TAP_MS)?.let { (it / (steps + 1)).coerceAtLeast(5L) }
        fun scheduleNext(index: Int) {
            if (index >= waypoints.size) return
            val action = {
                sendPoint(waypoints[index])
                scheduleNext(index + 1)
            }
            if (stepWallMs != null) {
                scheduleDelayed(stepWallMs, action)
            } else {
                scheduleCalibratedTapRelease(
                    minVirtualTicks = stepTicks,
                    fallbackWallMs = DEFAULT_SWIPE_STEP_MS,
                    release = action,
                )
            }
        }

        sendPoint(waypoints[0])
        scheduleNext(1)
        if (explicitTotalMs != null) {
            val totalMs = explicitTotalMs.coerceIn(MIN_TAP_MS, MAX_TAP_MS)
            ctx.info("Touch SWIPE $label ($x1, $y1) -> ($x2, $y2) (${totalMs}ms, $steps steps)")
        } else {
            ctx.info("Touch SWIPE $label ($x1, $y1) -> ($x2, $y2) (calibrated ${steps + 1}x20ms virtual)")
        }
        return CommandResult.Ok
    }

    /**
     * Releases a default momentary button/touch tap once guest virtual time has advanced by at least
     * [minVirtualTicks] (matching Studio's [MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS]
     * so taps never drop under low-RTR Movie/Menu load), bounded by a hard [maxHoldWallMs] wall-clock
     * safety cap and falling back to [fallbackWallMs] when the session is paused/idle.
     */
    private fun scheduleCalibratedTapRelease(
        minVirtualTicks: Long,
        fallbackWallMs: Long,
        maxHoldWallMs: Long = MomentaryHoldPolicy.DEFAULT_MAX_HOLD_WALL_MS,
        pollMs: Long = MomentaryHoldPolicy.DEFAULT_POLL_INTERVAL_MS,
        release: () -> Unit,
    ) {
        val startTick = backend.telemetry.virtualTick
        if (!backend.isRunning() || backend.isPaused() || (!backend.telemetry.running && startTick <= 0L)) {
            scheduleDelayed(fallbackWallMs, release)
            return
        }
        val targetTick = startTick + minVirtualTicks
        val deadlineNanos = clockNanos() + maxHoldWallMs * 1_000_000L
        fun poll() {
            if (
                !backend.isRunning() ||
                backend.isPaused() ||
                backend.telemetry.virtualTick >= targetTick ||
                clockNanos() >= deadlineNanos
            ) {
                release()
            } else {
                scheduleDelayed(pollMs, ::poll)
            }
        }
        scheduleDelayed(pollMs, ::poll)
    }

    private fun execEvf(ctx: ConsoleCommandContext): CommandResult {
        val tmdState = backend.componentState("tmd2772")
        val targetId = if (tmdState?.get("enabled") == true) "tmd2772" else "i2c-bus"
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val state = if (targetId == "tmd2772") tmdState else backend.componentState("i2c-bus")
            if (state == null) {
                return CommandResult.Error("tmd2772/i2c-bus eye-sensor component is not loaded.")
            }
            val near = state["eyeSensorNear"] == true
            ctx.info("EVF eye sensor ($targetId): ${if (near) "NEAR (EVF active)" else "FAR (rear LCD active)"}")
            return CommandResult.Ok
        }
        val near = when (ctx.args[0].lowercase(Locale.ROOT)) {
            "near", "on", "evf", "true" -> true
            "far", "off", "lcd", "false" -> false
            else -> return CommandResult.UsageError("Expected: status, near, or far.")
        }
        backend.patchComponent(targetId, mapOf("eyeSensorNear" to near))
        ctx.info("EVF eye sensor ($targetId) -> ${if (near) "NEAR (EVF)" else "FAR (rear LCD)"}")
        return CommandResult.Ok
    }

    private fun execScreenshot(ctx: ConsoleCommandContext): CommandResult {
        var surfaceAlias = "main"
        var pathArg: String? = null
        when (ctx.args.size) {
            0 -> Unit
            1 -> {
                val tok = ctx.args[0].lowercase(Locale.ROOT)
                if (tok in DISPLAY_ALIASES) {
                    surfaceAlias = tok
                } else {
                    pathArg = ctx.args[0]
                }
            }
            2 -> {
                val tok = ctx.args[0].lowercase(Locale.ROOT)
                if (tok !in DISPLAY_ALIASES) {
                    return CommandResult.UsageError("Unknown display surface \"${ctx.args[0]}\". Use: main, evf, or sub.")
                }
                surfaceAlias = tok
                pathArg = ctx.args[1]
            }
            else -> return CommandResult.UsageError("Too many arguments for screenshot.")
        }
        val portName = DISPLAY_ALIASES.getValue(surfaceAlias)
        val frame = backend.captureDisplay(portName)
            ?: return CommandResult.Error("No live frame available yet on $portName.")
        val outPath = if (pathArg != null) {
            resolveUserPath(pathArg)
        } else {
            val stamp = FILE_TS_FMT.format(Instant.now())
            backend.home.resolve("logs").resolve("screenshot-$surfaceAlias-$stamp.png")
        }
        outPath.parent?.let { Files.createDirectories(it) }
        val img = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, frame.width, frame.height, frame.argbPixels, 0, frame.width)
        ImageIO.write(img, "png", outPath.toFile())
        ctx.info("Saved $portName (${frame.width}x${frame.height}, seq=${frame.sequence}) -> $outPath")
        return CommandResult.Ok
    }

    private fun execDisplay(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val state = backend.componentState("display")
                ?: return CommandResult.Error("display component is not loaded.")
            val w = state["width"] ?: "?"
            val h = state["height"] ?: "?"
            val ew = state["evfWidth"] ?: "?"
            val eh = state["evfHeight"] ?: "?"
            val brightness = (state["brightness"] as? Number)?.toDouble() ?: 1.0
            val mainSeq = state["mainSequence"] ?: 0
            val evfSeq = state["evfSequence"] ?: 0
            ctx.info(
                String.format(
                    Locale.ROOT,
                    "Display: main=%sx%s (seq=%s) | evf=%sx%s (seq=%s) | brightness=%.2f",
                    w,
                    h,
                    mainSeq,
                    ew,
                    eh,
                    evfSeq,
                    brightness,
                ),
            )
            return CommandResult.Ok
        }
        if (ctx.args[0].equals("brightness", ignoreCase = true) && ctx.args.size == 2) {
            val value = ctx.args[1].toDoubleOrNull()
            if (value == null || value !in 0.0..1.0) {
                return CommandResult.UsageError("Brightness must be a number in 0.0..1.0.")
            }
            backend.patchComponent("display", mapOf("brightness" to value))
            ctx.info(String.format(Locale.ROOT, "Display brightness -> %.2f", value))
            return CommandResult.Ok
        }
        return CommandResult.UsageError()
    }

    private fun execSd(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isNotEmpty() && !ctx.args[0].equals("status", ignoreCase = true)) {
            return CommandResult.UsageError("Unknown subcommand \"${ctx.args[0]}\".")
        }
        val state = backend.componentState("sdcard-reader")
            ?: return CommandResult.Error("sdcard-reader component is not loaded.")
        for (slot in 1..2) {
            val inserted = state["slot${slot}Inserted"] == true
            val backendKind = (state["slot${slot}Backend"] as? String) ?: "raw"
            val image = (state["slot${slot}Image"] as? String) ?: ""
            val label = if (inserted) "INSERTED" else "EJECTED"
            ctx.info("SD Slot $slot: $label | backend=$backendKind | path=$image")
        }
        return CommandResult.Ok
    }

    private fun execBattery(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val state = backend.componentState("battery")
                ?: return CommandResult.Error("battery component is not loaded.")
            val charge = (state["chargePercent"] as? Number)?.toDouble() ?: 0.0
            val mv = (state["voltageMillivolts"] as? Number)?.toLong() ?: 0L
            val present = state["present"] != false
            ctx.info(
                String.format(
                    Locale.ROOT,
                    "Battery: present=%s | charge=%.1f%% | voltage=%d mV",
                    present,
                    charge,
                    mv,
                ),
            )
            return CommandResult.Ok
        }
        return when (ctx.args[0].lowercase(Locale.ROOT)) {
            "level", "charge" -> {
                val pct = ctx.args.getOrNull(1)?.toDoubleOrNull()
                if (pct == null || pct !in 0.0..100.0) {
                    return CommandResult.UsageError("Battery level must be in 0..100.")
                }
                backend.patchComponent("battery", mapOf("chargePercent" to pct))
                ctx.info(String.format(Locale.ROOT, "Battery charge -> %.1f%%", pct))
                CommandResult.Ok
            }
            "mv", "voltage" -> {
                val mv = ctx.args.getOrNull(1)?.toLongOrNull()
                if (mv == null || mv !in 0L..12000L) {
                    return CommandResult.UsageError("Battery voltage must be an integer in 0..12000 mV.")
                }
                backend.patchComponent("battery", mapOf("voltageMillivolts" to mv))
                ctx.info("Battery voltage -> $mv mV")
                CommandResult.Ok
            }
            "present" -> {
                val present = ctx.args.getOrNull(1)?.let(::parseOnOff)
                    ?: return CommandResult.UsageError("Expected: battery present <on|off>.")
                backend.patchComponent("battery", mapOf("present" to present))
                ctx.info("Battery present -> $present")
                CommandResult.Ok
            }
            else -> CommandResult.UsageError("Unknown battery subcommand \"${ctx.args[0]}\".")
        }
    }

    private fun execMount(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val state = backend.componentState("mount")
                ?: return CommandResult.Error("mount component is not loaded.")
            val attached = state["attached"] == true
            val lensPort = (state["lensPort"] as? String).orEmpty()
            val protocol = (state["contactProtocol"] as? String)
                ?: (state["protocolProfile"] as? String).orEmpty()
            ctx.info("G-Mount: ${if (attached) "ATTACHED" else "DETACHED"} | lensPort=$lensPort | protocol=$protocol")
            return CommandResult.Ok
        }
        val attach = when (ctx.args[0].lowercase(Locale.ROOT)) {
            "attach", "on", "true" -> true
            "detach", "off", "false" -> false
            else -> return CommandResult.UsageError("Expected: status, attach, or detach.")
        }
        backend.patchComponent("mount", mapOf("attached" to attach))
        ctx.info("G-Mount lens -> ${if (attach) "ATTACHED" else "DETACHED"}")
        return CommandResult.Ok
    }

    private fun execLens(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val lensState = backend.componentState("lens")
                ?: return CommandResult.Error("lens component is not loaded.")
            val mountState = backend.componentState("mount")
            val view = LensProfileState.fromMaps(lensState, lensState, mountState, mountState)
            val dofFar = if (!view.dofFarM.isFinite() || view.dofFarM >= 500.0) "inf" else String.format(Locale.ROOT, "%.2fm", view.dofFarM)
            val syncTag = if (view.bodyIrisSynced) "SYNCED" else "LOCAL"
            ctx.info(
                String.format(
                    Locale.ROOT,
                    "Lens: template=%s (%s) | mount=%s | optics=%s | focal=%.1fmm (eq35=%.1fmm) | ring=%s aperture=f/%.1f [%s] | focus=%s | mode=%s | contacts=%s | ois=%s | CoC=%.1fum (%.2fpx) | DoF=%.2fm~%s | inFocus=%s",
                    view.template,
                    view.templateDisplayName,
                    view.mountType,
                    if (view.opticsEnabled) "ON" else "OFF",
                    view.focalLengthMm,
                    view.equivalent35mmFocalMm,
                    view.apertureRingMode,
                    view.aperture,
                    syncTag,
                    LensProfileState.formatDistance(view.focusDistanceM),
                    view.focusMode,
                    if (view.electronicContacts) "ON" else "OFF",
                    if (view.oisEnabled) "ON" else "OFF",
                    view.cocMicrons,
                    view.cocPixels,
                    view.dofNearM,
                    dofFar,
                    view.inFocus,
                ),
            )
            return CommandResult.Ok
        }

        return when (ctx.args[0].lowercase(Locale.ROOT)) {
            "templates", "list" -> {
                ctx.info("Available lens templates (${LensTemplateSpec.BUILTIN_TEMPLATES.size}):")
                for (tpl in LensTemplateSpec.BUILTIN_TEMPLATES) {
                    val rangeStr = if (tpl.isZoom) {
                        String.format(Locale.ROOT, "%.0f-%.0fmm", tpl.minFocalMm, tpl.maxFocalMm)
                    } else {
                        String.format(Locale.ROOT, "%.0fmm", tpl.defaultFocalMm)
                    }
                    ctx.info(
                        String.format(
                            Locale.ROOT,
                            "  %-20s %-42s %s f/%.1f-%.0f MFD=%.2fm [%s]",
                            tpl.id,
                            tpl.displayName,
                            rangeStr,
                            tpl.minAperture,
                            tpl.maxAperture,
                            tpl.minFocusM,
                            tpl.mountType,
                        ),
                    )
                }
                CommandResult.Ok
            }
            "template", "preset" -> {
                val id = ctx.args.getOrNull(1)?.trim()?.lowercase(Locale.ROOT)
                    ?: return CommandResult.UsageError("Missing lens template ID. Run \"lens templates\" to list presets.")
                val lensState = backend.componentState("lens")
                val discoveredIds = (lensState?.get("discoveredLensModules") as? List<*>)
                    ?.mapNotNull { ((it as? Map<*, *>)?.get("id") as? String)?.lowercase(Locale.ROOT) }
                    ?.toSet()
                    .orEmpty()
                if (!LensTemplateSpec.isKnownTemplate(id) && id !in discoveredIds) {
                    return CommandResult.UsageError("Unknown lens template \"$id\". Run \"lens templates\" to list presets.")
                }
                val updated = backend.patchComponent("lens", LensProfileState.buildTemplatePatch(id))
                val displayName = if (LensTemplateSpec.isKnownTemplate(id)) {
                    LensTemplateSpec.find(id).displayName
                } else {
                    (updated["templateDisplayName"] as? String)?.takeIf { it.isNotBlank() } ?: id
                }
                ctx.info("Lens template -> $id ($displayName), opticsEnabled=true")
                CommandResult.Ok
            }
            "rescan" -> {
                val state = backend.componentState("lens")
                    ?: return CommandResult.Error("lens component is not loaded.")
                val currentRescan = (state["rescanPlugins"] as? Number)?.toLong() ?: 0L
                val updated = backend.patchComponent("lens", LensProfileState.buildRescanPluginsPatch(currentRescan))
                val lenses = (updated["discoveredLensModules"] as? List<*>)?.size ?: 0
                val adapters = (updated["discoveredAdapterModules"] as? List<*>)?.size ?: 0
                val jars = (updated["pluginJarCount"] as? Number)?.toInt() ?: 0
                ctx.info("Lens plugins rescanned: jars=$jars | lenses=$lenses | adapters=$adapters")
                CommandResult.Ok
            }
            "focus" -> {
                val raw = ctx.args.getOrNull(1)?.trim()?.lowercase(Locale.ROOT)
                    ?: return CommandResult.UsageError("Missing focus distance in meters (or \"inf\").")
                val meters = if (raw == "inf" || raw == "infinity" || raw == "∞") {
                    1000.0
                } else {
                    raw.removeSuffix("m").toDoubleOrNull()
                }
                if (meters == null || !meters.isFinite() || meters < 0.01) {
                    return CommandResult.UsageError("Focus distance must be >= 0.01 meters or \"inf\".")
                }
                backend.patchComponent("lens", mapOf("focusDistanceM" to meters))
                ctx.info("Lens focusDistanceM -> ${LensProfileState.formatDistance(meters)}")
                CommandResult.Ok
            }
            "af", "autofocus" -> {
                val state = backend.componentState("lens")
                    ?: return CommandResult.Error("lens component is not loaded.")
                val currentTrigger = (state["afTrigger"] as? Number)?.toLong() ?: 0L
                val updated = backend.patchComponent("lens", LensProfileState.buildAfTriggerPatch(currentTrigger))
                val focused = (updated["focusDistanceM"] as? Number)?.toDouble()
                val suffix = if (focused != null) " (focus=${LensProfileState.formatDistance(focused)})" else ""
                ctx.info("Lens AF triggered (afTrigger=${currentTrigger + 1L})$suffix")
                CommandResult.Ok
            }
            "ring", "aperture-ring" -> {
                val mode = ctx.args.getOrNull(1)?.trim()?.uppercase(Locale.ROOT)
                    ?: return CommandResult.UsageError("Expected: lens ring <A|C|MANUAL>.")
                if (mode !in setOf("A", "C", "MANUAL")) {
                    return CommandResult.UsageError("Aperture ring mode must be A, C, or MANUAL.")
                }
                backend.patchComponent("lens", mapOf("apertureRingMode" to mode))
                ctx.info("Lens apertureRingMode -> $mode")
                CommandResult.Ok
            }
            "aperture", "f" -> {
                val raw = ctx.args.getOrNull(1)?.trim()
                    ?: return CommandResult.UsageError("Missing aperture value (e.g. A, C, 2.8, 5.6).")
                val upper = raw.uppercase(Locale.ROOT)
                if (upper == "A" || upper == "C") {
                    backend.patchComponent("lens", mapOf("apertureRingMode" to upper))
                    ctx.info("Lens apertureRingMode -> $upper")
                    return CommandResult.Ok
                }
                val fNum = raw.lowercase(Locale.ROOT).removePrefix("f/").toDoubleOrNull()
                if (fNum == null || !fNum.isFinite() || fNum !in 0.5..64.0) {
                    return CommandResult.UsageError("Aperture must be A, C, or an f-number in 0.5..64.0.")
                }
                backend.patchComponent("lens", mapOf("apertureRingMode" to "MANUAL", "aperture" to fNum))
                ctx.info(String.format(Locale.ROOT, "Lens aperture -> f/%.1f (ring=MANUAL)", fNum))
                CommandResult.Ok
            }
            "ois" -> {
                val enabled = ctx.args.getOrNull(1)?.let(::parseOnOff)
                    ?: return CommandResult.UsageError("Expected: lens ois <on|off>.")
                backend.patchComponent("lens", mapOf("oisEnabled" to enabled))
                ctx.info("Lens oisEnabled -> ${if (enabled) "ON" else "OFF"}")
                CommandResult.Ok
            }
            "focal", "zoom" -> {
                val raw = ctx.args.getOrNull(1)?.trim()?.lowercase(Locale.ROOT)?.removeSuffix("mm")
                    ?: return CommandResult.UsageError("Missing focal length in mm (e.g. 45, 85, 100).")
                val mm = raw.toDoubleOrNull()
                if (mm == null || !mm.isFinite() || mm !in 1.0..2000.0) {
                    return CommandResult.UsageError("Focal length must be in 1.0..2000.0 mm.")
                }
                backend.patchComponent("lens", mapOf("focalLengthMm" to mm))
                ctx.info(String.format(Locale.ROOT, "Lens focalLengthMm -> %.1f mm", mm))
                CommandResult.Ok
            }
            "optics" -> {
                val enabled = ctx.args.getOrNull(1)?.let(::parseOnOff)
                    ?: return CommandResult.UsageError("Expected: lens optics <on|off>.")
                backend.patchComponent("lens", mapOf("opticsEnabled" to enabled))
                ctx.info("Lens opticsEnabled -> ${if (enabled) "ON" else "OFF"}")
                CommandResult.Ok
            }
            "gpu", "vulkan" -> execGpuArgs(ctx, ctx.args.drop(1))
            else -> CommandResult.UsageError("Unknown lens subcommand \"${ctx.args[0]}\".")
        }
    }

    private fun execGpu(ctx: ConsoleCommandContext): CommandResult = execGpuArgs(ctx, ctx.args)

    private fun execGpuArgs(ctx: ConsoleCommandContext, args: List<String>): CommandResult {
        val lensState = backend.componentState("lens")
            ?: return CommandResult.Error("lens component is not loaded.")
        val view = LensProfileState.fromMaps(lensState, lensState, null, null)

        if (args.isEmpty() || args[0].equals("list", ignoreCase = true) || args[0].equals("status", ignoreCase = true)) {
            val activeDesc = if (view.activeOpticsBackend.equals("VULKAN", ignoreCase = true)) {
                "VULKAN [${view.activeVulkanDeviceIndex}] ${view.activeVulkanDeviceName} (${view.activeVulkanDeviceType}${if (view.activeVulkanExtMem) ", ExtMem" else ""})"
            } else {
                "CPU (${if (view.vulkanFallbackReason.isNotBlank()) view.vulkanFallbackReason else "selected"})"
            }
            ctx.info("Optics Compute: active=$activeDesc | configuredBackend=${view.opticsBackend} | configuredDevice=${view.vulkanDevice}")
            if (view.availableVulkanDevices.isEmpty()) {
                ctx.info("  No Vulkan compute devices enumerated on this host.")
            } else {
                ctx.info("  Enumerated Vulkan GPUs (${view.availableVulkanDevices.size}):")
                for (dev in view.availableVulkanDevices) {
                    val mark = if (view.activeOpticsBackend.equals("VULKAN", ignoreCase = true) && view.activeVulkanDeviceIndex == dev.index) "*" else " "
                    val ext = if (dev.supportsExternalMemory) "ExtMem" else "no-ExtMem"
                    ctx.info("  $mark [${dev.index}] ${dev.name} (${dev.deviceType}, $ext)")
                }
            }
            ctx.info("  Note: For Minecraft/game GPU External Memory zero-copy capture, select the SAME GPU that the game runs on.")
            return CommandResult.Ok
        }

        val target = args.joinToString(" ").trim()
        val lower = target.lowercase(Locale.ROOT)
        val patch: Map<String, Any>
        val nextBackend: String?
        val nextDevice: String?
        when (lower) {
            "cpu" -> {
                patch = LensProfileState.buildOpticsBackendPatch("cpu")
                nextBackend = "cpu"
                nextDevice = null
            }
            "auto" -> {
                patch = LensProfileState.buildVulkanDevicePatch("auto", backendOverride = "auto")
                nextBackend = "auto"
                nextDevice = "auto"
            }
            "vulkan", "gpu" -> {
                patch = LensProfileState.buildOpticsBackendPatch("vulkan")
                nextBackend = "vulkan"
                nextDevice = null
            }
            else -> {
                val matched = target.toIntOrNull()?.let { idx ->
                    view.availableVulkanDevices.firstOrNull { it.index == idx }
                } ?: view.availableVulkanDevices.firstOrNull {
                    it.spec.equals(target, ignoreCase = true) ||
                        it.name.equals(target, ignoreCase = true) ||
                        it.name.contains(target, ignoreCase = true)
                }
                val chosenSpec = matched?.spec ?: target
                patch = LensProfileState.buildVulkanDevicePatch(chosenSpec, backendOverride = "vulkan")
                nextBackend = "vulkan"
                nextDevice = chosenSpec
            }
        }
        val updated = backend.patchComponent("lens", patch)
        backend.persistGraphicsPreferences(opticsBackend = nextBackend, vulkanDevice = nextDevice)
        val after = LensProfileState.fromMaps(updated, updated, null, null)
        val activeSummary = if (after.activeOpticsBackend.equals("VULKAN", ignoreCase = true)) {
            "VULKAN [${after.activeVulkanDeviceIndex}] ${after.activeVulkanDeviceName}"
        } else {
            "CPU${if (after.vulkanFallbackReason.isNotBlank()) " (${after.vulkanFallbackReason})" else ""}"
        }
        ctx.info("Optics GPU -> backend=${after.opticsBackend} | device=${after.vulkanDevice} | active=$activeSummary")
        if (!after.opticsBackend.equals("cpu", ignoreCase = true)) {
            ctx.info("  Reminder: External game zero-copy interop (GPU External Memory) requires selecting the SAME GPU the game runs on.")
        }
        return CommandResult.Ok
    }

    private fun execCmos(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isNotEmpty() && !ctx.args[0].equals("status", ignoreCase = true)) {
            return CommandResult.UsageError("Unknown subcommand \"${ctx.args[0]}\".")
        }
        val state = backend.componentState("cmos")
            ?: return CommandResult.Error("cmos component is not loaded.")
        val status = CmosSourceStatus.fromMaps(state, state)
        val extra = buildString {
            if (status.opticsFilterActive) append(" | optics=ON")
            if (status.hasDepthBuffer) append(" | depth=3D")
            if (status.streamIngestPort != null && status.streamIngestPort > 0) append(" | port=${status.streamIngestPort}")
            if (!status.lastSourceError.isNullOrBlank()) append(" | error=${status.lastSourceError}")
        }
        ctx.info(
            "CMOS sensor: sourceKind=${status.sourceKind} | sourceFile=${status.activeSourceRef} | loop=${status.loop} | framesExposed=${status.framesExposed}$extra",
        )
        return CommandResult.Ok
    }

    private fun execSource(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val state = backend.componentState("cmos")
                ?: return CommandResult.Error("cmos component is not loaded.")
            val status = CmosSourceStatus.fromMaps(state, state)
            ctx.info(
                "Source: kind=${status.sourceKind} | ref=${status.activeSourceRef} | loop=${status.loop} | pipeline=${status.pipeline.ifEmpty { "default" }} | frames=${status.framesExposed} | optics=${if (status.opticsFilterActive) "ON" else "OFF"} | depth=${status.hasDepthBuffer}" +
                    (if (status.streamIngestPort != null && status.streamIngestPort > 0) " | port=${status.streamIngestPort}" else "") +
                    (if (!status.lastSourceError.isNullOrBlank()) " | error=${status.lastSourceError}" else ""),
            )
            return CommandResult.Ok
        }
        if (ctx.args[0].equals("set", ignoreCase = true)) {
            val rawKind = ctx.args.getOrNull(1)
                ?: return CommandResult.UsageError("Missing source kind and reference.")
            val ref = ctx.args.getOrNull(2)
                ?: return CommandResult.UsageError("Missing source reference (file path or URI).")
            val resolvedKind = normalizeSourceKind(rawKind)
                ?: return CommandResult.UsageError(
                    "Unknown source kind \"$rawKind\". Supported: JPEG, PNG, ffmpeg-pipe, stream-ingest, screen-capture, optical-scene.",
                )
            val loopArg = ctx.args.getOrNull(3)
            val loop = if (loopArg == null) {
                true
            } else if (loopArg.equals("loop", ignoreCase = true)) {
                true
            } else if (loopArg.equals("noloop", ignoreCase = true) || loopArg.equals("once", ignoreCase = true)) {
                false
            } else {
                parseOnOff(loopArg)
                    ?: return CommandResult.UsageError("Invalid loop flag \"$loopArg\". Expected: true/false/on/off/loop.")
            }
            backend.patchComponent("cmos", linkedMapOf("sourceKind" to resolvedKind, "sourceFile" to ref, "loop" to loop))
            ctx.info("Source -> kind=$resolvedKind | ref=$ref | loop=$loop")
            return CommandResult.Ok
        }
        return CommandResult.UsageError("Unknown source subcommand \"${ctx.args[0]}\".")
    }

    private fun normalizeSourceKind(raw: String): String? = when (raw.trim().lowercase(Locale.ROOT)) {
        "jpeg", "jpg", "image" -> "JPEG"
        "png" -> "PNG"
        "ffmpeg-pipe", "ffmpeg", "video", "webcam", "rtsp", "rtmp" -> "ffmpeg-pipe"
        "stream-ingest", "stream", "ingest", "tcp", "udp", "http" -> "stream-ingest"
        "screen-capture", "screen", "game", "capture" -> "screen-capture"
        "optical-scene", "scene", "3d", "optics" -> "optical-scene"
        else -> null
    }

    private fun execFlash(ctx: ConsoleCommandContext): CommandResult {
        val sub = ctx.args.firstOrNull()?.lowercase(Locale.ROOT) ?: "status"
        return when (sub) {
            "status", "info" -> {
                val nor = backend.norFlashStatus()
                val state = backend.componentState("flash-image")
                if (nor["unifiedNorMode"] == true) {
                    val path = nor["norFlashFile"] ?: ""
                    val size = (nor["length"] as? Number)?.toLong() ?: 0L
                    val sha = nor["sha256"]?.toString()?.take(16) ?: "n/a"
                    val writable = nor["writable"] == true
                    val factory = nor["factoryFlashFile"] ?: "none"
                    val mode = state?.get("mode") ?: state?.get("imageProfile") ?: "raw-nor-image-v1"
                    val erases = (state?.get("eraseCount") as? Number)?.toLong()
                        ?: (nor["eraseCommitted"] as? Number)?.toLong() ?: 0L
                    val programs = (state?.get("programCount") as? Number)?.toLong()
                        ?: (nor["programCommitted"] as? Number)?.toLong() ?: 0L
                    ctx.info(
                        "NOR Flash [$mode]: path=$path | size=${size / (1024 * 1024)} MiB | writable=$writable | sha256=$sha... | erases=$erases | programs=$programs | factory=$factory",
                    )
                    return CommandResult.Ok
                }
                if (state == null) {
                    return CommandResult.Error("flash-image component is not loaded.")
                }
                val regions = (state["regionsProfile"] as? String) ?: "strict"
                val mutation = (state["mutationProfile"] as? String) ?: "strict"
                val mutationFile = (state["mutationFile"] as? String).orEmpty()
                val tail = (state["tailProfile"] as? String) ?: "strict"
                ctx.info("Flash image: regions=$regions | mutation=$mutation | mutationFile=$mutationFile | tail=$tail")
                CommandResult.Ok
            }
            "write", "flash", "dat" -> {
                val rawPath = ctx.args.getOrNull(1)
                    ?: return CommandResult.UsageError("Usage: /flash write <file.DAT> [--no-preserve-cal]")
                val preserveCal = ctx.args.drop(2).none {
                    it.equals("--no-preserve-cal", ignoreCase = true) || it.equals("--wipe-cal", ignoreCase = true)
                }
                val datPath = resolveUserPath(rawPath)
                return try {
                    val res = backend.flashDatIntoNor(datPath, preserveCalibration = preserveCal)
                    val sha = (res["norSha256"] ?: res["sha256"])?.toString()?.take(16) ?: "n/a"
                    val fwBytes = ((res["seg0Length"] ?: res["firmwareBytes"]) as? Number)?.toLong() ?: 0L
                    ctx.info(
                        "Flashed ${datPath.fileName} ($fwBytes bytes) into NOR Flash (preserveCalibration=$preserveCal, sha256=$sha...). Camera cold-rebooted.",
                    )
                    CommandResult.Ok
                } catch (e: Exception) {
                    CommandResult.Error("Flash write failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            "export", "dump" -> {
                val rawPath = ctx.args.getOrNull(1)
                    ?: return CommandResult.UsageError("Usage: /flash export <output.bin>")
                val outPath = resolveUserPath(rawPath)
                return try {
                    val res = backend.exportNorFlash(outPath)
                    val sha = res["sha256"]?.toString()?.take(16) ?: "n/a"
                    ctx.info("Exported 64 MiB NOR Flash -> $outPath (sha256=$sha...)")
                    CommandResult.Ok
                } catch (e: Exception) {
                    CommandResult.Error("Flash export failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            "import", "load" -> {
                val rawPath = ctx.args.getOrNull(1)
                    ?: return CommandResult.UsageError("Usage: /flash import <input.bin>")
                val inPath = resolveUserPath(rawPath)
                return try {
                    val res = backend.importNorFlash(inPath)
                    val sha = res["sha256"]?.toString()?.take(16) ?: "n/a"
                    ctx.info("Imported 64 MiB NOR Flash from $inPath (sha256=$sha...). Camera cold-rebooted.")
                    CommandResult.Ok
                } catch (e: Exception) {
                    CommandResult.Error("Flash import failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            "restore-factory", "restore", "factory-reset" -> {
                return try {
                    val res = backend.restoreFactoryFlash()
                    val sha = res["sha256"]?.toString()?.take(16) ?: "n/a"
                    ctx.info("Restored stock 2.13 factory NOR Flash (sha256=$sha...). Camera cold-rebooted.")
                    CommandResult.Ok
                } catch (e: Exception) {
                    CommandResult.Error("Factory restore failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            else -> CommandResult.UsageError(
                "Unknown subcommand \"${ctx.args[0]}\". Expected: status, write <file.DAT> [--no-preserve-cal], export <out.bin>, import <in.bin>, or restore-factory.",
            )
        }
    }

    private fun execAudio(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val st = AudioIoStatusSnapshot.fromMap(backend.audioStatus())
            val volPct = (st.volume * 100.0).toInt()
            val outState = when {
                !st.enabled -> "OFF"
                st.muted -> "MUTED ($volPct%)"
                else -> "ON ($volPct%)"
            }
            ctx.info(
                String.format(
                    Locale.ROOT,
                    "Audio I/O: backend=%s (pref=%s) | latency=%dms (target=%dms) | rate=%dHz | wm8962=%s",
                    st.activeBackend,
                    st.backendPreference.id,
                    st.actualLatencyMs,
                    st.bufferMs,
                    st.sampleRateHz,
                    st.wm8962ActiveSink,
                ),
            )
            ctx.info(
                String.format(
                    Locale.ROOT,
                    "  Output: %s | device=%s | hpJack=%s | peak=(L:%s, R:%s) | txFrames=%d",
                    outState,
                    st.outputDeviceName,
                    if (st.headphonePlugged) "PLUGGED" else "UNPLUGGED",
                    AudioIoStatusSnapshot.formatPeakDb(st.outputPeakLeft),
                    AudioIoStatusSnapshot.formatPeakDb(st.outputPeakRight),
                    st.totalTxFrames,
                ),
            )
            ctx.info(
                String.format(
                    Locale.ROOT,
                    "  Input:  mode=%s (%s) | device=%s | gain=%+.1fdB | muted=%s | monitor=%s | micJack=%s | peak=(L:%s, R:%s) | rxFrames=%d (q=%d, ovr=%d)",
                    st.inputMode.id,
                    st.inputBackendMode,
                    st.inputDeviceName,
                    st.inputGainDb,
                    st.inputMuted,
                    st.inputMonitor,
                    if (st.extMicPlugged) "PLUGGED" else "UNPLUGGED",
                    AudioIoStatusSnapshot.formatPeakDb(st.inputPeakLeft),
                    AudioIoStatusSnapshot.formatPeakDb(st.inputPeakRight),
                    st.totalRxFrames,
                    st.queuedRxFrames,
                    st.rxOverruns,
                ),
            )
            if (!st.degradationReason.isNullOrBlank()) {
                ctx.info("  Output note: ${st.degradationReason}")
            }
            if (!st.inputDegradationReason.isNullOrBlank()) {
                ctx.info("  Input note: ${st.inputDegradationReason}")
            }
            return CommandResult.Ok
        }

        return when (ctx.args[0].lowercase(Locale.ROOT)) {
            "devices", "list" -> {
                val cat = AudioDeviceCatalogSnapshot.fromMap(backend.audioDevices())
                val nativeDesc = if (cat.nativeAvailable) cat.nativeBackend.ifBlank { "available" } else "unavailable (JavaSound fallback)"
                ctx.info("Audio devices (native=$nativeDesc): outputs=${cat.outputs.size}, inputs=${cat.inputs.size}, loopbacks=${cat.loopbacks.size}")
                ctx.info("  [Outputs]")
                if (cat.outputs.isEmpty()) {
                    ctx.info("    (none detected; use \"default\")")
                } else {
                    cat.outputs.forEach { d ->
                        ctx.info("    id=${d.id} | ${d.displayLabel()}")
                    }
                }
                ctx.info("  [Inputs / Microphones]")
                if (cat.inputs.isEmpty()) {
                    ctx.info("    (none detected; use \"default\")")
                } else {
                    cat.inputs.forEach { d ->
                        ctx.info("    id=${d.id} | ${d.displayLabel()}")
                    }
                }
                ctx.info("  [Output Loopback Targets]")
                val loopbacks = if (cat.loopbacks.isNotEmpty()) cat.loopbacks else cat.outputs
                if (loopbacks.isEmpty()) {
                    ctx.info("    (none detected; use \"default\")")
                } else {
                    loopbacks.forEach { d ->
                        ctx.info("    id=${d.id} | ${d.displayLabel()}")
                    }
                }
                CommandResult.Ok
            }
            "out", "output" -> {
                val sub = ctx.args.getOrNull(1)?.lowercase(Locale.ROOT)
                    ?: return CommandResult.UsageError("Expected: audio out <on|off|mute|unmute|vol <0..100>|device <id|default>>.")
                when (sub) {
                    "on" -> {
                        backend.patchAudio(AudioIoStatusSnapshot.buildOutputPatch(enabled = true))
                        ctx.info("Audio output -> ON")
                        CommandResult.Ok
                    }
                    "off" -> {
                        backend.patchAudio(AudioIoStatusSnapshot.buildOutputPatch(enabled = false))
                        ctx.info("Audio output -> OFF")
                        CommandResult.Ok
                    }
                    "mute" -> {
                        backend.patchAudio(AudioIoStatusSnapshot.buildOutputPatch(muted = true))
                        ctx.info("Audio output -> MUTED")
                        CommandResult.Ok
                    }
                    "unmute" -> {
                        backend.patchAudio(AudioIoStatusSnapshot.buildOutputPatch(muted = false))
                        ctx.info("Audio output -> UNMUTED")
                        CommandResult.Ok
                    }
                    "vol", "volume" -> {
                        val raw = ctx.args.getOrNull(2)?.removeSuffix("%")?.toDoubleOrNull()
                            ?: return CommandResult.UsageError("Expected volume 0..100 (e.g. audio out vol 80).")
                        if (!raw.isFinite() || raw !in 0.0..100.0) {
                            return CommandResult.UsageError("Volume must be in 0..100.")
                        }
                        backend.patchAudio(AudioIoStatusSnapshot.buildOutputPatch(volume = raw / 100.0))
                        ctx.info(String.format(Locale.ROOT, "Audio output volume -> %.0f%%", raw))
                        CommandResult.Ok
                    }
                    "device", "dev" -> {
                        val rawId = ctx.args.drop(2).joinToString(" ").trim()
                        if (rawId.isEmpty()) {
                            return CommandResult.UsageError("Expected output device ID or \"default\".")
                        }
                        val id = if (rawId.equals("default", ignoreCase = true)) "" else rawId
                        backend.patchAudio(AudioIoStatusSnapshot.buildOutputPatch(outputDeviceId = id))
                        ctx.info("Audio output device -> ${id.ifEmpty { "default" }}")
                        CommandResult.Ok
                    }
                    else -> CommandResult.UsageError("Unknown audio out subcommand \"$sub\".")
                }
            }
            "in", "input" -> {
                val raw = ctx.args.getOrNull(1)?.lowercase(Locale.ROOT)
                    ?: return CommandResult.UsageError("Expected: audio in <off|mic|loopback|tone|scene>.")
                val mode = when (raw) {
                    "off", "disabled", "none" -> AudioInputMode.DISABLED
                    "mic", "host_mic", "host-mic", "microphone" -> AudioInputMode.HOST_MIC
                    "loopback", "output_loopback", "output-loopback", "loop", "desktop" -> AudioInputMode.OUTPUT_LOOPBACK
                    "tone", "test_tone", "test-tone", "sine" -> AudioInputMode.TEST_TONE
                    "scene", "scene_audio", "scene-audio", "frog", "milk-frog" -> AudioInputMode.SCENE_AUDIO
                    else -> return CommandResult.UsageError("Unknown audio input mode \"$raw\". Expected: off, mic, loopback, tone, or scene.")
                }
                val updated = AudioIoStatusSnapshot.fromMap(
                    backend.patchAudio(AudioIoStatusSnapshot.buildInputPatch(inputMode = mode)),
                )
                ctx.info("Audio input mode -> ${mode.id} (backend=${updated.inputBackendMode})")
                CommandResult.Ok
            }
            "in-device", "indevice", "input-device" -> {
                val rawId = ctx.args.drop(1).joinToString(" ").trim()
                if (rawId.isEmpty()) {
                    return CommandResult.UsageError("Expected input device ID or \"default\".")
                }
                val id = if (rawId.equals("default", ignoreCase = true)) "" else rawId
                backend.patchAudio(AudioIoStatusSnapshot.buildInputPatch(inputDeviceId = id))
                ctx.info("Audio input device -> ${id.ifEmpty { "default" }}")
                CommandResult.Ok
            }
            "gain" -> {
                val raw = ctx.args.getOrNull(1)?.lowercase(Locale.ROOT)?.removeSuffix("db")?.toDoubleOrNull()
                    ?: return CommandResult.UsageError("Expected input gain in dB (-24..24).")
                if (!raw.isFinite() || raw !in -24.0..24.0) {
                    return CommandResult.UsageError("Input gain must be in -24..24 dB.")
                }
                backend.patchAudio(AudioIoStatusSnapshot.buildInputPatch(inputGainDb = raw))
                ctx.info(String.format(Locale.ROOT, "Audio input gain -> %+.1f dB", raw))
                CommandResult.Ok
            }
            "in-mute", "inmute", "input-mute" -> {
                val muted = ctx.args.getOrNull(1)?.let(::parseOnOff)
                    ?: return CommandResult.UsageError("Expected: audio in-mute <on|off>.")
                backend.patchAudio(AudioIoStatusSnapshot.buildInputPatch(inputMuted = muted))
                ctx.info("Audio input mute -> ${if (muted) "ON" else "OFF"}")
                CommandResult.Ok
            }
            "monitor" -> {
                val monitor = ctx.args.getOrNull(1)?.let(::parseOnOff)
                    ?: return CommandResult.UsageError("Expected: audio monitor <on|off>.")
                backend.patchAudio(AudioIoStatusSnapshot.buildInputPatch(inputMonitor = monitor))
                ctx.info("Audio input monitor -> ${if (monitor) "ON" else "OFF"}")
                CommandResult.Ok
            }
            "latency", "buffer" -> {
                val ms = ctx.args.getOrNull(1)?.lowercase(Locale.ROOT)?.removeSuffix("ms")?.toIntOrNull()
                    ?: return CommandResult.UsageError("Expected buffer latency in ms (10..500).")
                if (ms !in 10..500) {
                    return CommandResult.UsageError("Audio latency must be in 10..500 ms.")
                }
                val updated = AudioIoStatusSnapshot.fromMap(
                    backend.patchAudio(AudioIoStatusSnapshot.buildOutputPatch(bufferMs = ms)),
                )
                ctx.info("Audio latency -> target=${ms}ms (actual=${updated.actualLatencyMs}ms)")
                CommandResult.Ok
            }
            "backend", "engine" -> {
                val raw = ctx.args.getOrNull(1)?.lowercase(Locale.ROOT)
                    ?: return CommandResult.UsageError("Expected: audio backend <auto|native|javasound>.")
                val pref = when (raw) {
                    "auto" -> AudioBackendPreference.AUTO
                    "native", "miniaudio", "wasapi", "pipewire" -> AudioBackendPreference.NATIVE
                    "javasound", "java", "jvm" -> AudioBackendPreference.JAVASOUND
                    else -> return CommandResult.UsageError("Unknown audio backend \"$raw\". Expected: auto, native, or javasound.")
                }
                val updated = AudioIoStatusSnapshot.fromMap(
                    backend.patchAudio(AudioIoStatusSnapshot.buildOutputPatch(backendPreference = pref)),
                )
                ctx.info("Audio backend preference -> ${pref.id} (active=${updated.activeBackend})")
                CommandResult.Ok
            }
            "hp", "headphone" -> {
                val plugged = ctx.args.getOrNull(1)?.let(::parseOnOff)
                    ?: return CommandResult.UsageError("Expected: audio hp <on|off>.")
                backend.patchAudio(AudioIoStatusSnapshot.buildOutputPatch(headphonePlugged = plugged))
                ctx.info("Audio 3.5mm headphone jack (GPIO 8.4) -> ${if (plugged) "PLUGGED" else "UNPLUGGED"}")
                CommandResult.Ok
            }
            "mic-jack", "micjack", "ext-mic" -> {
                val plugged = ctx.args.getOrNull(1)?.let(::parseOnOff)
                    ?: return CommandResult.UsageError("Expected: audio mic-jack <on|off>.")
                backend.patchAudio(AudioIoStatusSnapshot.buildInputPatch(extMicPlugged = plugged))
                ctx.info("Audio 3.5mm external mic jack (GPIO 6.4) -> ${if (plugged) "PLUGGED" else "UNPLUGGED"}")
                CommandResult.Ok
            }
            else -> CommandResult.UsageError("Unknown audio subcommand \"${ctx.args[0]}\".")
        }
    }

    private fun execColor(ctx: ConsoleCommandContext): CommandResult {
        val sub = ctx.args.firstOrNull()?.lowercase(Locale.ROOT) ?: "status"
        return when (sub) {
            "status" -> {
                ctx.info(ConsoleAnsi.statusSummary(isTty = interactivePrompt))
                CommandResult.Ok
            }
            "auto" -> {
                ConsoleAnsi.setMode(ConsoleAnsi.ColorMode.AUTO)
                val state = if (isColorEnabled()) "ENABLED" else "DISABLED"
                ctx.info("Console color mode -> AUTO ($state)")
                CommandResult.Ok
            }
            "on", "always", "true", "1" -> {
                ConsoleAnsi.setMode(ConsoleAnsi.ColorMode.ALWAYS)
                ctx.info("Console color mode -> ALWAYS (ENABLED)")
                CommandResult.Ok
            }
            "off", "never", "false", "0" -> {
                ConsoleAnsi.setMode(ConsoleAnsi.ColorMode.NEVER)
                ctx.info("Console color mode -> NEVER (DISABLED)")
                CommandResult.Ok
            }
            else -> CommandResult.UsageError("Unknown color mode \"${ctx.args[0]}\". Expected: auto, on, off, or status.")
        }
    }

    private fun execLog(ctx: ConsoleCommandContext): CommandResult {
        val first = ctx.args.firstOrNull()?.lowercase(Locale.ROOT) ?: "status"
        return when (first) {
            "status" -> {
                ctx.info(logControl.statusSummary())
                CommandResult.Ok
            }
            "level", "console" -> {
                val rawLevel = ctx.args.getOrNull(1)
                    ?: return CommandResult.UsageError("Missing log level. Expected: TRACE, DEBUG, INFO, WARN, ERROR, or OFF.")
                val parsed = LogArchiver.parseLevelName(rawLevel)
                    ?: return CommandResult.UsageError("Unknown log level \"$rawLevel\". Expected: TRACE, DEBUG, INFO, WARN, ERROR, or OFF.")
                val applied = logControl.setConsoleLevel(parsed)
                ctx.info("Console log level -> $applied (file=${logControl.getFileLevel()})")
                CommandResult.Ok
            }
            "file" -> {
                val rawLevel = ctx.args.getOrNull(1)
                    ?: return CommandResult.UsageError("Missing file log level. Expected: TRACE, DEBUG, INFO, WARN, ERROR, or OFF.")
                val parsed = LogArchiver.parseLevelName(rawLevel)
                    ?: return CommandResult.UsageError("Unknown file log level \"$rawLevel\". Expected: TRACE, DEBUG, INFO, WARN, ERROR, or OFF.")
                val applied = logControl.setFileLevel(parsed)
                ctx.info("File log level -> $applied (console=${logControl.getConsoleLevel()})")
                CommandResult.Ok
            }
            else -> {
                val direct = LogArchiver.parseLevelName(first)
                if (direct != null) {
                    val applied = logControl.setConsoleLevel(direct)
                    ctx.info("Console log level -> $applied (file=${logControl.getFileLevel()})")
                    CommandResult.Ok
                } else {
                    CommandResult.UsageError("Unknown log subcommand \"${ctx.args[0]}\". Expected: status, level <LEVEL>, or file <LEVEL>.")
                }
            }
        }
    }

    // ==================== Utilities ====================

    private fun execFilter(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val state = backend.componentState("optical-filter")
                ?: return CommandResult.Error("optical-filter component is not loaded.")
            val view = OpticalFilterStatus.fromMaps(state, state)
            val slotsDesc = if (view.slots.isEmpty()) {
                "empty"
            } else {
                view.slots.mapIndexed { idx, s ->
                    "#${idx + 1}[${if (s.enabled) "ON" else "OFF"}] ${OpticalFilterStatus.formatSlotBadge(s)}"
                }.joinToString(" -> ")
            }
            ctx.info(
                String.format(
                    Locale.ROOT,
                    "OpticalFilter: enabled=%s | stack=[%s] | slots=[%s] | uv=%s | ND=%s | bokeh=%s(%.0f°) | star=%s | mist=%s | gnd=%s | T=%.2f%% (-%.2f EV)",
                    if (view.enabled) "ON" else "BYPASS",
                    view.activeSummary,
                    slotsDesc,
                    view.uvPreset.id,
                    view.ndTelemetryText(),
                    view.bokehMask.id,
                    view.bokehMaskAngleDeg,
                    if (view.starEnabled) "${view.starPoints}pt(str=${String.format(Locale.ROOT, "%.2f", view.starStrength)})" else "OFF",
                    if (view.mistEnabled) "${view.mistPreset}(${String.format(Locale.ROOT, "%.2f", view.mistStrength)})" else "OFF",
                    if (view.gndEnabled) String.format(Locale.ROOT, "%.1fEV@%.0f%%", view.gndStops, view.gndHorizon * 100.0) else "OFF",
                    view.totalTransmittance * 100.0,
                    view.totalEvLoss,
                ),
            )
            return CommandResult.Ok
        }

        val sub = ctx.args[0].lowercase(Locale.ROOT)
        if (sub == "rescan") {
            val curState = backend.componentState("optical-filter")
                ?: return CommandResult.Error("optical-filter component is not loaded.")
            val currentRescan = (curState["rescanPlugins"] as? Number)?.toLong() ?: 0L
            val updated = backend.patchComponent("optical-filter", OpticalFilterStatus.buildRescanPluginsPatch(currentRescan))
            val jarCount = (updated["pluginJarCount"] as? Number)?.toInt() ?: 0
            val pluginTypes = (updated["pluginFilterTypes"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            val allTypes = (updated["availableFilterTypes"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            ctx.info(
                "OpticalFilter plugins rescanned: jars=$jarCount | plugins=${if (pluginTypes.isEmpty()) "none" else pluginTypes.joinToString(",")} | total=${allTypes.size}",
            )
            return CommandResult.Ok
        }
        val patch: Map<String, Any> = when (sub) {
            "clear", "reset", "off" -> OpticalFilterStatus.buildClearAllPatch()
            "on", "enable" -> mapOf("enabled" to true)
            "bypass", "disable" -> mapOf("enabled" to false)
            "uv" -> {
                if (ctx.args.size < 2) return CommandResult.UsageError("Usage: /filter uv <off|pro|standard|cheap>")
                val preset = UvFilterPreset.parse(ctx.args[1])
                    ?: return CommandResult.UsageError("Unknown UV preset '${ctx.args[1]}'. Expected: off, pro, standard, cheap.")
                OpticalFilterStatus.buildUvPresetPatch(preset)
            }
            "add" -> {
                if (ctx.args.size < 2) return CommandResult.UsageError("Usage: /filter add <uv|nd|bokeh|star|mist|gnd> [preset]")
                val curState = backend.componentState("optical-filter")
                    ?: return CommandResult.Error("optical-filter component is not loaded.")
                val view = OpticalFilterStatus.fromMaps(curState, curState)
                if (view.slots.size >= OpticalFilterSlotCodec.MAX_SLOTS) {
                    return CommandResult.Error("Matte box is full (max ${OpticalFilterSlotCodec.MAX_SLOTS} slots).")
                }
                val rawType = ctx.args[1].lowercase(Locale.ROOT)
                val canonicalType = when (rawType) {
                    "uv" -> "uv"
                    "nd" -> "nd"
                    "bokeh", "bokeh-mask", "pupil", "mask" -> "bokeh-mask"
                    "star", "cross" -> "star"
                    "mist", "black-mist", "blackmist" -> "black-mist"
                    "gnd", "grad" -> "gnd"
                    else -> if (rawType in view.availableFilterTypes) {
                        rawType
                    } else {
                        return CommandResult.UsageError(
                            "Unknown filter type '$rawType'. Expected: uv, nd, bokeh, star, mist, gnd" +
                                if (view.pluginProviders.isNotEmpty()) ", or plugin: ${view.pluginProviders.joinToString { it.type }}." else ".",
                        )
                    }
                }
                val slot = OpticalFilterStatus.defaultSlot(canonicalType, ctx.args.getOrNull(2), provider = view.providerFor(canonicalType))
                view.withAddedSlot(slot)
            }
            "remove", "rm", "del" -> {
                if (ctx.args.size < 2) return CommandResult.UsageError("Usage: /filter remove <1..N>")
                val curState = backend.componentState("optical-filter")
                    ?: return CommandResult.Error("optical-filter component is not loaded.")
                val view = OpticalFilterStatus.fromMaps(curState, curState)
                val slotNum = ctx.args[1].toIntOrNull()
                    ?: return CommandResult.UsageError("Slot number must be 1..${view.slots.size}.")
                val idx = slotNum - 1
                if (idx !in view.slots.indices) {
                    return CommandResult.UsageError("Slot #$slotNum out of range (active slots: ${view.slots.size}).")
                }
                view.withRemovedSlot(idx)
            }
            "move", "mv" -> {
                if (ctx.args.size < 3) return CommandResult.UsageError("Usage: /filter move <1..N> <up|down|targetIndex>")
                val curState = backend.componentState("optical-filter")
                    ?: return CommandResult.Error("optical-filter component is not loaded.")
                val view = OpticalFilterStatus.fromMaps(curState, curState)
                val slotNum = ctx.args[1].toIntOrNull()
                    ?: return CommandResult.UsageError("Slot number must be 1..${view.slots.size}.")
                val idx = slotNum - 1
                if (idx !in view.slots.indices) {
                    return CommandResult.UsageError("Slot #$slotNum out of range (active slots: ${view.slots.size}).")
                }
                val dir = ctx.args[2].lowercase(Locale.ROOT)
                val targetIdx = when (dir) {
                    "up", "front" -> (idx - 1).coerceAtLeast(0)
                    "down", "back", "rear" -> (idx + 1).coerceAtMost(view.slots.lastIndex)
                    else -> {
                        val parsed = dir.toIntOrNull()
                            ?: return CommandResult.UsageError("Move target must be 'up', 'down', or 1..${view.slots.size}.")
                        (parsed - 1).coerceIn(0, view.slots.lastIndex)
                    }
                }
                view.withMovedSlot(idx, targetIdx - idx)
            }
            "toggle" -> {
                if (ctx.args.size < 2) return CommandResult.UsageError("Usage: /filter toggle <1..N>")
                val curState = backend.componentState("optical-filter")
                    ?: return CommandResult.Error("optical-filter component is not loaded.")
                val view = OpticalFilterStatus.fromMaps(curState, curState)
                val slotNum = ctx.args[1].toIntOrNull()
                    ?: return CommandResult.UsageError("Slot number must be 1..${view.slots.size}.")
                val idx = slotNum - 1
                if (idx !in view.slots.indices) {
                    return CommandResult.UsageError("Slot #$slotNum out of range (active slots: ${view.slots.size}).")
                }
                view.withToggledSlot(idx)
            }
            "nd" -> {
                if (ctx.args.size < 2) return CommandResult.UsageError("Usage: /filter nd <off|ND2..ND1000|stops>")
                parseNdArgsToPatch(ctx.args.drop(1))
                    ?: return CommandResult.UsageError("Invalid ND argument: ${ctx.args.drop(1).joinToString(" ")}")
            }
            "bokeh", "pupil", "mask" -> {
                if (ctx.args.size < 2) return CommandResult.UsageError("Usage: /filter bokeh <off|star|heart|hexagon|ring> [angleDeg]")
                val shape = BokehMaskShape.parse(ctx.args[1])
                    ?: return CommandResult.UsageError("Unknown bokeh mask '${ctx.args[1]}'. Expected: off, star, heart, hexagon, ring.")
                val angle = ctx.args.getOrNull(2)?.toDoubleOrNull()
                if (ctx.args.size >= 3 && angle == null) return CommandResult.UsageError("bokeh angleDeg must be numeric.")
                OpticalFilterStatus.buildBokehMaskPatch(shape, angle)
            }
            "star", "cross" -> {
                if (ctx.args.size < 2) return CommandResult.UsageError("Usage: /filter star <off|4|6|8> [strength] [angleDeg]")
                val arg1 = ctx.args[1].lowercase(Locale.ROOT).removeSuffix("pt")
                if (arg1 == "off" || arg1 == "0" || arg1 == "none") {
                    mapOf("starEnabled" to false)
                } else {
                    val pts = arg1.toIntOrNull()
                        ?: return CommandResult.UsageError("Expected off, 4, 6, or 8 for star points.")
                    if (pts !in setOf(4, 6, 8)) return CommandResult.UsageError("Star points must be 4, 6, or 8.")
                    val strength = ctx.args.getOrNull(2)?.toDoubleOrNull()
                    if (ctx.args.size >= 3 && strength == null) return CommandResult.UsageError("star strength must be numeric (0.0..2.0).")
                    val angle = ctx.args.getOrNull(3)?.toDoubleOrNull()
                    if (ctx.args.size >= 4 && angle == null) return CommandResult.UsageError("star angleDeg must be numeric.")
                    linkedMapOf<String, Any>("enabled" to true, "starEnabled" to true, "starPoints" to pts.toLong()).apply {
                        if (strength != null) put("starStrength", strength.coerceIn(0.0, 2.0))
                        if (angle != null) put("starAngleDeg", angle.coerceIn(0.0, 180.0))
                    }
                }
            }
            "mist", "black-mist", "blackmist" -> {
                if (ctx.args.size < 2) return CommandResult.UsageError("Usage: /filter mist <off|1/8|1/4|1/2|1|0.0..1.0>")
                val raw = ctx.args[1].trim()
                val num = raw.toDoubleOrNull()
                when {
                    raw.equals("off", ignoreCase = true) || raw.equals("none", ignoreCase = true) || raw == "0" ->
                        mapOf("mistEnabled" to false, "mistPreset" to "OFF", "mistStrength" to 0.0)
                    raw in setOf("1/8", "1/4", "1/2", "1") -> OpticalFilterStatus.buildMistPatch(raw)
                    num != null && num in 0.0..1.0 ->
                        mapOf("enabled" to true, "mistEnabled" to (num > 1e-4), "mistStrength" to num)
                    else -> return CommandResult.UsageError("Unknown mist value '$raw'. Expected off, 1/8, 1/4, 1/2, 1, or 0.0..1.0.")
                }
            }
            "gnd", "grad" -> {
                if (ctx.args.size < 2) return CommandResult.UsageError("Usage: /filter gnd <off|0.0..5.0> [horizon] [feather] [angleDeg]")
                val raw = ctx.args[1].trim()
                if (raw.equals("off", ignoreCase = true) || raw.equals("none", ignoreCase = true) || raw == "0") {
                    mapOf("gndEnabled" to false, "gndStops" to 0.0)
                } else {
                    val stops = raw.toDoubleOrNull()
                        ?: return CommandResult.UsageError("GND stops must be numeric (0.0..5.0) or off.")
                    val horizon = ctx.args.getOrNull(2)?.toDoubleOrNull()
                    val feather = ctx.args.getOrNull(3)?.toDoubleOrNull()
                    val angle = ctx.args.getOrNull(4)?.toDoubleOrNull()
                    linkedMapOf<String, Any>(
                        "enabled" to true,
                        "gndEnabled" to (stops > 1e-4),
                        "gndStops" to stops.coerceIn(0.0, 5.0),
                    ).apply {
                        if (horizon != null) put("gndHorizon", horizon.coerceIn(0.0, 1.0))
                        if (feather != null) put("gndFeather", feather.coerceIn(0.02, 1.0))
                        if (angle != null) put("gndAngleDeg", angle.coerceIn(-180.0, 180.0))
                    }
                }
            }
            else -> return CommandResult.UsageError(
                "Unknown subcommand '$sub'. Expected: status, clear, on, bypass, rescan, uv, add, remove, move, toggle, nd, bokeh, star, mist, gnd.",
            )
        }

        val updated = backend.patchComponent("optical-filter", patch)
        val view = OpticalFilterStatus.fromMaps(updated, updated)
        ctx.info("OpticalFilter -> [${view.activeSummary}] (enabled=${if (view.enabled) "ON" else "BYPASS"}, T=${String.format(Locale.ROOT, "%.2f%%", view.totalTransmittance * 100.0)}, -${String.format(Locale.ROOT, "%.2f EV", view.totalEvLoss)})")
        return CommandResult.Ok
    }

    private fun execNd(ctx: ConsoleCommandContext): CommandResult {
        if (ctx.args.isEmpty() || ctx.args[0].equals("status", ignoreCase = true)) {
            val state = backend.componentState("optical-filter")
                ?: return CommandResult.Error("optical-filter component is not loaded.")
            val view = OpticalFilterStatus.fromMaps(state, state)
            ctx.info("ND Filter: ${view.ndTelemetryText()}")
            return CommandResult.Ok
        }
        val patch = parseNdArgsToPatch(ctx.args)
            ?: return CommandResult.UsageError("Usage: /nd [status | off | set <OFF|ND2..ND1000> | stops <0.0..10.0> | <preset|stops>]")
        val updated = backend.patchComponent("optical-filter", patch)
        val view = OpticalFilterStatus.fromMaps(updated, updated)
        ctx.info("ND Filter -> ${view.ndTelemetryText()}")
        return CommandResult.Ok
    }

    private fun parseNdArgsToPatch(args: List<String>): Map<String, Any>? {
        if (args.isEmpty()) return null
        val first = args[0].lowercase(Locale.ROOT)
        return when (first) {
            "off", "none", "clear" -> OpticalFilterStatus.buildNdPresetPatch(NdFilterPreset.OFF)
            "set", "preset" -> {
                val raw = args.getOrNull(1) ?: return null
                val preset = NdFilterSpec.parsePreset(raw) ?: return null
                OpticalFilterStatus.buildNdPresetPatch(preset)
            }
            "stops", "ev", "var", "variable" -> {
                val v = args.getOrNull(1)?.toDoubleOrNull() ?: return null
                if (!v.isFinite() || v !in NdFilterSpec.MIN_STOPS..NdFilterSpec.MAX_STOPS) return null
                OpticalFilterStatus.buildNdStopsPatch(v)
            }
            else -> {
                val preset = NdFilterSpec.parsePreset(args[0])
                if (preset != null) {
                    OpticalFilterStatus.buildNdPresetPatch(preset)
                } else {
                    val v = args[0].toDoubleOrNull() ?: return null
                    if (!v.isFinite() || v !in NdFilterSpec.MIN_STOPS..NdFilterSpec.MAX_STOPS) return null
                    OpticalFilterStatus.buildNdStopsPatch(v)
                }
            }
        }
    }

    private fun isColorEnabled(): Boolean = ConsoleAnsi.isEnabled(isTty = interactivePrompt)

    private fun parseOnOff(raw: String): Boolean? = when (raw.lowercase(Locale.ROOT)) {
        "on", "true", "1", "yes" -> true
        "off", "false", "0", "no" -> false
        else -> null
    }

    private fun resolveUserPath(raw: String): Path {
        val p = Path.of(raw)
        return if (p.isAbsolute) p.normalize() else backend.home.resolve(p).toAbsolutePath().normalize()
    }

    private fun emitInfo(msg: String) {
        out.println(ConsoleAnsi.formatConsoleLine(TIME_FMT.format(timeSupplier()), "INFO", msg, colorEnabled = isColorEnabled()))
        out.flush()
    }

    private fun emitWarn(msg: String) {
        out.println(ConsoleAnsi.formatConsoleLine(TIME_FMT.format(timeSupplier()), "WARN", msg, colorEnabled = isColorEnabled()))
        out.flush()
    }

    private fun emitMultiline(block: String) {
        val time = TIME_FMT.format(timeSupplier())
        val color = isColorEnabled()
        for (line in block.lines()) {
            out.println(ConsoleAnsi.formatConsoleLine(time, "INFO", line, colorEnabled = color))
        }
        out.flush()
    }

    fun isAlive(): Boolean = readerThread?.isAlive == true

    override fun close() {
        running.set(false)
        readerThread?.interrupt()
    }

    private val INVALID_OPTION_SENTINEL: Path = Path.of("__fuji_invalid_option__")
}
