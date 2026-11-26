package com.github.nyabanban.fuji.emulator.runtime

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Zero-dependency cross-platform ANSI color engine for interactive console output and SLF4J
 * console channels.
 *
 * - Supports `ColorMode.AUTO` (default), `ColorMode.ALWAYS`, and `ColorMode.NEVER` with live
 *   runtime toggling via [setMode] / [currentMode].
 * - Honors `NO_COLOR`, `CLICOLOR=0`, `TERM=dumb`, `FORCE_COLOR`, `CLICOLOR_FORCE`, and
 *   `-Dfuji.console.color=<auto|always|never>`.
 * - Activates Windows 10/11 ConHost VT100 processing (`ENABLE_VIRTUAL_TERMINAL_PROCESSING`) via
 *   JDK 25 FFM against `kernel32.dll` with zero JNI or third-party JARs.
 * - Guarantees plain-text output whenever disabled (pipes, redirects, CI, unit tests, and file
 *   log appenders).
 */
object ConsoleAnsi {
    enum class ColorMode {
        AUTO,
        ALWAYS,
        NEVER,
        ;

        companion object {
            fun parse(raw: String?): ColorMode? {
                val normalized = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
                return when (normalized) {
                    "auto" -> AUTO
                    "always", "on", "true", "1", "yes" -> ALWAYS
                    "never", "off", "false", "0", "no" -> NEVER
                    else -> null
                }
            }
        }
    }

    const val PROP_CONSOLE_COLOR: String = "fuji.console.color"

    const val RESET: String = "\u001B[0m"
    const val DIM_GRAY: String = "\u001B[90m"
    const val DIM_BLUE: String = "\u001B[34m"
    const val CYAN: String = "\u001B[36m"
    const val BRIGHT_CYAN: String = "\u001B[96m"
    const val BOLD_BRIGHT_CYAN: String = "\u001B[1;96m"
    const val BRIGHT_GREEN: String = "\u001B[92m"
    const val BOLD_BRIGHT_GREEN: String = "\u001B[1;92m"
    const val YELLOW: String = "\u001B[33m"
    const val BRIGHT_YELLOW: String = "\u001B[93m"
    const val BOLD_YELLOW: String = "\u001B[1;33m"
    const val BRIGHT_RED: String = "\u001B[91m"
    const val BOLD_RED: String = "\u001B[1;31m"
    const val BOLD_WHITE: String = "\u001B[1;97m"

    private const val STD_OUTPUT_HANDLE: Int = -11
    private const val ENABLE_VIRTUAL_TERMINAL_PROCESSING: Int = 0x0004
    private const val INVALID_HANDLE_VALUE: Long = -1L

    private val modeRef = AtomicReference(ColorMode.AUTO)

    private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val SECTION_HEADER_REGEX = Regex("^\\s*(?:===.*===|---.*---)\\s*$")

    // Single-pass semantic token scanner: avoids nested/corrupted escape codes and guarantees
    // stripAnsi(highlightMessage(msg, true)) == msg.
    private val SEMANTIC_TOKEN_REGEX = Regex(
        listOf(
            // 1: Usage:
            """(?<usage>\bUsage:)""",
            // 2: /command tokens (only after start-of-line or delimiter, not paths like /tmp/a.jpg)
            """(?<slashcmd>(?<=^|[\s"'(\[])/[A-Za-z][A-Za-z0-9_-]*(?=$|[\s"',;)\]]))""",
            // 3: key=value pairs (group kvKey + kvVal, including numeric values with spaced unit like frame=16.6 ms)
            """(?<kv>(?<![A-Za-z0-9_./:-])(?<kvKey>[A-Za-z][A-Za-z0-9_.-]*)=(?<kvVal>[0-9]+(?:\.[0-9]+)?x?\s+(?:MIPS|RTR|kHz|ms|mm)\b|[^\s,;)\]]*))""",
            // 4: Live numeric metrics (MIPS, RTR, kHz, ms, mm) and aperture (f/2.8)
            """(?<metric>\bf/[0-9]+(?:\.[0-9]+)?\b|\b[0-9]+(?:\.[0-9]+)?x\s+RTR\b|\b[0-9]+(?:\.[0-9]+)?\s?(?:MIPS|RTR|kHz|ms|mm)\b)""",
            // 5: Positive status badges
            """(?<good>\b(?:ON|ENABLED|READY|RUNNING|PASS|SHARP|OK)\b)""",
            // 6: Caution/idle status badges
            """(?<caution>\b(?:OFF|DISABLED|PAUSED|DEFOCUS|MUTED|STOPPED)\b)""",
            // 7: Error status badges
            """(?<bad>\b(?:ERROR|FAILED|UNSUPPORTED|DENIED)\b)""",
        ).joinToString("|"),
    )

    private val VALUE_METRIC_REGEX = Regex(
        """^(?:f/[0-9]+(?:\.[0-9]+)?|[0-9]+(?:\.[0-9]+)?x(?:\s+RTR)?|[0-9]+(?:\.[0-9]+)?\s*(?:MIPS|RTR|kHz|ms|mm))$""",
    )
    private val GOOD_BADGES = setOf("ON", "ENABLED", "READY", "RUNNING", "PASS", "SHARP", "OK")
    private val CAUTION_BADGES = setOf("OFF", "DISABLED", "PAUSED", "DEFOCUS", "MUTED", "STOPPED")
    private val BAD_BADGES = setOf("ERROR", "FAILED", "UNSUPPORTED", "DENIED")

    private val windowsVtEnabled: Boolean by lazy { enableWindowsVirtualTerminal() }

    fun currentMode(): ColorMode = modeRef.get()

    fun setMode(mode: ColorMode) {
        modeRef.set(mode)
    }

    fun resetMode() {
        modeRef.set(ColorMode.AUTO)
    }

    fun isWindowsOs(osName: String = System.getProperty("os.name", "")): Boolean =
        osName.lowercase(Locale.ROOT).contains("win")

    fun ensureWindowsVtEnabled(): Boolean = if (isWindowsOs()) windowsVtEnabled else false

    fun defaultIsTty(): Boolean = System.console() != null

    fun isEnabled(isTty: Boolean = defaultIsTty()): Boolean = resolveEnabled(
        mode = modeRef.get(),
        isTty = isTty,
        sysProp = System.getProperty(PROP_CONSOLE_COLOR),
        env = System.getenv(),
        windowsVtActive = ensureWindowsVtEnabled(),
    )

    internal fun resolveEnabled(
        mode: ColorMode,
        isTty: Boolean,
        sysProp: String?,
        env: Map<String, String>,
        windowsVtActive: Boolean,
    ): Boolean {
        when (mode) {
            ColorMode.ALWAYS -> return true
            ColorMode.NEVER -> return false
            ColorMode.AUTO -> Unit
        }
        when (ColorMode.parse(sysProp)) {
            ColorMode.ALWAYS -> return true
            ColorMode.NEVER -> return false
            ColorMode.AUTO, null -> Unit
        }
        // Standard opt-out checks (NO_COLOR per https://no-color.org/ requires non-empty value)
        if (!env["NO_COLOR"].isNullOrEmpty()) return false
        if (env["CLICOLOR"]?.trim() == "0") return false
        if (env["TERM"]?.trim()?.equals("dumb", ignoreCase = true) == true) return false
        // Standard opt-in checks
        if (isTruthyEnv(env["FORCE_COLOR"])) return true
        if (isTruthyEnv(env["CLICOLOR_FORCE"])) return true
        return isTty || windowsVtActive
    }

    private fun isTruthyEnv(value: String?): Boolean {
        val trimmed = value?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (trimmed.isEmpty()) return false
        return trimmed != "0" && trimmed != "false" && trimmed != "no" && trimmed != "off" && trimmed != "never"
    }

    fun statusSummary(isTty: Boolean = defaultIsTty()): String {
        val enabled = isEnabled(isTty)
        val state = if (enabled) "ENABLED" else "DISABLED"
        val mode = currentMode().name
        val vt = if (isWindowsOs()) " windowsVt=$windowsVtEnabled" else ""
        return "Console color=$state mode=$mode tty=$isTty$vt"
    }

    /**
     * Formats a console log line matching `[HH:mm:ss LEVEL]: message`.
     * Returns byte-identical plain text when [colorEnabled] is `false`.
     */
    fun formatConsoleLine(
        time: String,
        level: String,
        message: String,
        colorEnabled: Boolean = isEnabled(),
    ): String {
        if (!colorEnabled) {
            return "[$time $level]: $message"
        }
        val levelColor = levelAnsiColor(level)
        val highlightedBody = highlightMessage(message, colorEnabled = true)
        return "$DIM_GRAY[$time$RESET $levelColor$level$RESET$DIM_GRAY]:$RESET $highlightedBody"
    }

    /**
     * Formats the interactive stdin console prompt.
     * Bold bright green `fuji` + dim `> ` when enabled; plain `> ` when disabled.
     */
    fun formatPrompt(colorEnabled: Boolean = isEnabled()): String =
        if (colorEnabled) {
            "${BOLD_BRIGHT_GREEN}fuji$RESET$DIM_GRAY> $RESET"
        } else {
            "> "
        }

    /**
     * Applies semantic ANSI highlighting to [message] when [colorEnabled] is `true`.
     * Returns [message] untouched with zero regex work when [colorEnabled] is `false`.
     */
    fun highlightMessage(message: String, colorEnabled: Boolean = isEnabled()): String {
        if (!colorEnabled || message.isEmpty()) return message
        if (!message.contains('\n')) {
            return highlightSingleLine(message)
        }
        val lines = message.split('\n')
        val out = StringBuilder(message.length + lines.size * 16)
        for (i in lines.indices) {
            if (i > 0) out.append('\n')
            out.append(highlightSingleLine(lines[i]))
        }
        return out.toString()
    }

    fun stripAnsi(text: String): String = ANSI_ESCAPE_REGEX.replace(text, "")

    internal fun levelAnsiColor(level: String): String = when (level.trim().uppercase(Locale.ROOT)) {
        "INFO" -> BRIGHT_CYAN
        "WARN", "WARNING" -> BOLD_YELLOW
        "ERROR", "SEVERE", "FATAL" -> BOLD_RED
        "DEBUG" -> DIM_BLUE
        "TRACE" -> DIM_GRAY
        else -> RESET
    }

    private fun highlightSingleLine(line: String): String {
        if (line.isEmpty()) return line
        if (SECTION_HEADER_REGEX.matches(line)) {
            return "$BOLD_BRIGHT_CYAN$line$RESET"
        }
        return SEMANTIC_TOKEN_REGEX.replace(line) { match ->
            when {
                match.groups["usage"] != null -> "$BRIGHT_YELLOW${match.value}$RESET"
                match.groups["slashcmd"] != null -> "$BRIGHT_YELLOW${match.value}$RESET"
                match.groups["kv"] != null -> {
                    val key = match.groups["kvKey"]!!.value
                    val value = match.groups["kvVal"]!!.value
                    val styledVal = styleKeyValue(value)
                    "$CYAN$key$RESET$DIM_GRAY=$RESET$styledVal"
                }
                match.groups["metric"] != null -> "$BOLD_WHITE${match.value}$RESET"
                match.groups["good"] != null -> "$BRIGHT_GREEN${match.value}$RESET"
                match.groups["caution"] != null -> "$YELLOW${match.value}$RESET"
                match.groups["bad"] != null -> "$BRIGHT_RED${match.value}$RESET"
                else -> match.value
            }
        }
    }

    private fun styleKeyValue(value: String): String {
        if (value.isEmpty()) return value
        return when {
            value in GOOD_BADGES -> "$BRIGHT_GREEN$value$RESET"
            value in CAUTION_BADGES -> "$YELLOW$value$RESET"
            value in BAD_BADGES -> "$BRIGHT_RED$value$RESET"
            VALUE_METRIC_REGEX.matches(value) -> "$BOLD_WHITE$value$RESET"
            else -> value
        }
    }

    /**
     * Activates Windows 10/11 ConHost ANSI/VT100 escape processing via JDK 25 FFM (`kernel32.dll`).
     * Returns `true` only when stdout is an attached Windows console handle and VT processing is
     * enabled; returns `false` on non-Windows platforms, piped/redirected output, or binding errors.
     */
    private fun enableWindowsVirtualTerminal(): Boolean {
        if (!isWindowsOs()) return false
        return try {
            val libArena = Arena.ofAuto()
            val kernel32 = SymbolLookup.libraryLookup("kernel32.dll", libArena)
            val linker = Linker.nativeLinker()

            val getStdHandleSym = kernel32.find("GetStdHandle").orElse(null) ?: return false
            val getConsoleModeSym = kernel32.find("GetConsoleMode").orElse(null) ?: return false
            val setConsoleModeSym = kernel32.find("SetConsoleMode").orElse(null) ?: return false

            val getStdHandle = linker.downcallHandle(
                getStdHandleSym,
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            )
            val getConsoleMode = linker.downcallHandle(
                getConsoleModeSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            )
            val setConsoleMode = linker.downcallHandle(
                setConsoleModeSym,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            )

            val handle = getStdHandle.invokeExact(STD_OUTPUT_HANDLE) as MemorySegment
            val rawHandle = handle.address()
            if (rawHandle == 0L || rawHandle == INVALID_HANDLE_VALUE) {
                return false
            }

            Arena.ofConfined().use { callArena ->
                val modePtr = callArena.allocate(ValueLayout.JAVA_INT)
                val okRead = getConsoleMode.invokeExact(handle, modePtr) as Int
                if (okRead == 0) {
                    return false
                }
                val currentMode = modePtr.get(ValueLayout.JAVA_INT, 0)
                if ((currentMode and ENABLE_VIRTUAL_TERMINAL_PROCESSING) != 0) {
                    return true
                }
                val okWrite = setConsoleMode.invokeExact(
                    handle,
                    currentMode or ENABLE_VIRTUAL_TERMINAL_PROCESSING,
                ) as Int
                okWrite != 0
            }
        } catch (_: Throwable) {
            false
        }
    }
}
