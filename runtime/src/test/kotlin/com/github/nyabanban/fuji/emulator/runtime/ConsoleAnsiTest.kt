package com.github.nyabanban.fuji.emulator.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleAnsiTest {
    @AfterEach
    fun resetGlobalColorMode() {
        ConsoleAnsi.resetMode()
    }

    @Test
    fun colorModeEnvironmentAndPropertyResolution() {
        // AUTO respects TTY and Windows VT state
        assertTrue(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = true, sysProp = null, env = emptyMap(), windowsVtActive = false))
        assertFalse(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = false, sysProp = null, env = emptyMap(), windowsVtActive = false))
        assertTrue(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = false, sysProp = null, env = emptyMap(), windowsVtActive = true))

        // NO_COLOR disables even on TTY (empty NO_COLOR does NOT disable per no-color.org)
        assertFalse(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = true, sysProp = null, env = mapOf("NO_COLOR" to "1"), windowsVtActive = true))
        assertTrue(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = true, sysProp = null, env = mapOf("NO_COLOR" to ""), windowsVtActive = false))

        // CLICOLOR=0 and TERM=dumb disable color
        assertFalse(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = true, sysProp = null, env = mapOf("CLICOLOR" to "0"), windowsVtActive = false))
        assertFalse(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = true, sysProp = null, env = mapOf("TERM" to "dumb"), windowsVtActive = false))
        assertFalse(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = true, sysProp = null, env = mapOf("TERM" to "DUMB"), windowsVtActive = false))

        // FORCE_COLOR and CLICOLOR_FORCE enable even on non-TTY pipes (unless NO_COLOR wins)
        assertTrue(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = false, sysProp = null, env = mapOf("FORCE_COLOR" to "1"), windowsVtActive = false))
        assertTrue(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = false, sysProp = null, env = mapOf("CLICOLOR_FORCE" to "1"), windowsVtActive = false))
        assertFalse(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = false, sysProp = null, env = mapOf("FORCE_COLOR" to "0"), windowsVtActive = false))
        assertFalse(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = false, sysProp = null, env = mapOf("NO_COLOR" to "1", "FORCE_COLOR" to "1"), windowsVtActive = false))

        // -Dfuji.console.color overrides environment in AUTO mode
        assertTrue(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = false, sysProp = "always", env = mapOf("NO_COLOR" to "1"), windowsVtActive = false))
        assertFalse(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.AUTO, isTty = true, sysProp = "never", env = mapOf("FORCE_COLOR" to "1"), windowsVtActive = true))

        // Explicit runtime ALWAYS / NEVER overrides everything
        assertTrue(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.ALWAYS, isTty = false, sysProp = "never", env = mapOf("NO_COLOR" to "1"), windowsVtActive = false))
        assertFalse(ConsoleAnsi.resolveEnabled(ConsoleAnsi.ColorMode.NEVER, isTty = true, sysProp = "always", env = mapOf("FORCE_COLOR" to "1"), windowsVtActive = true))
    }

    @Test
    fun runtimeModeOverrideAndParserWork() {
        assertEquals(ConsoleAnsi.ColorMode.AUTO, ConsoleAnsi.currentMode())
        assertEquals(ConsoleAnsi.ColorMode.ALWAYS, ConsoleAnsi.ColorMode.parse("on"))
        assertEquals(ConsoleAnsi.ColorMode.NEVER, ConsoleAnsi.ColorMode.parse("off"))
        assertEquals(ConsoleAnsi.ColorMode.AUTO, ConsoleAnsi.ColorMode.parse(" AUTO "))
        assertEquals(null, ConsoleAnsi.ColorMode.parse("invalid"))

        ConsoleAnsi.setMode(ConsoleAnsi.ColorMode.ALWAYS)
        assertTrue(ConsoleAnsi.isEnabled(isTty = false))
        assertTrue(ConsoleAnsi.statusSummary(isTty = false).contains("ENABLED"))

        ConsoleAnsi.setMode(ConsoleAnsi.ColorMode.NEVER)
        assertFalse(ConsoleAnsi.isEnabled(isTty = true))
        assertTrue(ConsoleAnsi.statusSummary(isTty = true).contains("DISABLED"))
    }

    @Test
    fun formatsConsoleLinesAndPromptsWithExactRoundTrip() {
        val plain = ConsoleAnsi.formatConsoleLine("12:34:56", "INFO", "Camera READY speed=67.63MIPS", colorEnabled = false)
        assertEquals("[12:34:56 INFO]: Camera READY speed=67.63MIPS", plain)
        assertFalse(plain.contains("\u001B"))

        val infoColored = ConsoleAnsi.formatConsoleLine("12:34:56", "INFO", "Camera READY speed=67.63MIPS", colorEnabled = true)
        assertTrue(infoColored.contains("${ConsoleAnsi.DIM_GRAY}[12:34:56${ConsoleAnsi.RESET}"))
        assertTrue(infoColored.contains("${ConsoleAnsi.BRIGHT_CYAN}INFO${ConsoleAnsi.RESET}"))
        assertEquals(plain, ConsoleAnsi.stripAnsi(infoColored))

        val warnColored = ConsoleAnsi.formatConsoleLine("12:34:56", "WARN", "Lens DEFOCUS", colorEnabled = true)
        assertTrue(warnColored.contains("${ConsoleAnsi.BOLD_YELLOW}WARN${ConsoleAnsi.RESET}"))
        assertEquals("[12:34:56 WARN]: Lens DEFOCUS", ConsoleAnsi.stripAnsi(warnColored))

        val errorColored = ConsoleAnsi.formatConsoleLine("12:34:56", "ERROR", "Operation FAILED", colorEnabled = true)
        assertTrue(errorColored.contains("${ConsoleAnsi.BOLD_RED}ERROR${ConsoleAnsi.RESET}"))
        assertEquals("[12:34:56 ERROR]: Operation FAILED", ConsoleAnsi.stripAnsi(errorColored))

        val debugColored = ConsoleAnsi.formatConsoleLine("12:34:56", "DEBUG", "tick=100", colorEnabled = true)
        assertTrue(debugColored.contains("${ConsoleAnsi.DIM_BLUE}DEBUG${ConsoleAnsi.RESET}"))
        assertEquals("[12:34:56 DEBUG]: tick=100", ConsoleAnsi.stripAnsi(debugColored))

        assertEquals("> ", ConsoleAnsi.formatPrompt(colorEnabled = false))
        val coloredPrompt = ConsoleAnsi.formatPrompt(colorEnabled = true)
        assertTrue(coloredPrompt.contains("${ConsoleAnsi.BOLD_BRIGHT_GREEN}fuji${ConsoleAnsi.RESET}"))
        assertTrue(coloredPrompt.contains("${ConsoleAnsi.DIM_GRAY}> ${ConsoleAnsi.RESET}"))
        assertEquals("fuji> ", ConsoleAnsi.stripAnsi(coloredPrompt))
    }

    @Test
    fun semanticHighlightingCoversHeadersCommandsKeysBadgesAndMetrics() {
        val header = "=== Hardware Telemetry ==="
        assertEquals("${ConsoleAnsi.BOLD_BRIGHT_CYAN}$header${ConsoleAnsi.RESET}", ConsoleAnsi.highlightMessage(header, true))

        val subHeader = "--- Audio Bridge ---"
        assertEquals("${ConsoleAnsi.BOLD_BRIGHT_CYAN}$subHeader${ConsoleAnsi.RESET}", ConsoleAnsi.highlightMessage(subHeader, true))

        val usage = "Usage: /lens set 80mm f/1.7 (path=/tmp/out.jpg)"
        val styledUsage = ConsoleAnsi.highlightMessage(usage, true)
        assertTrue(styledUsage.contains("${ConsoleAnsi.BRIGHT_YELLOW}Usage:${ConsoleAnsi.RESET}"))
        assertTrue(styledUsage.contains("${ConsoleAnsi.BRIGHT_YELLOW}/lens${ConsoleAnsi.RESET}"))
        assertTrue(styledUsage.contains("${ConsoleAnsi.BOLD_WHITE}80mm${ConsoleAnsi.RESET}"))
        assertTrue(styledUsage.contains("${ConsoleAnsi.BOLD_WHITE}f/1.7${ConsoleAnsi.RESET}"))
        assertTrue(styledUsage.contains("${ConsoleAnsi.CYAN}path${ConsoleAnsi.RESET}"))
        assertEquals(usage, ConsoleAnsi.stripAnsi(styledUsage))

        val badges = "state=READY focus=SHARP audio=ON mic=MUTED cap=PAUSED test=PASS net=OFF err=FAILED perm=DENIED"
        val styledBadges = ConsoleAnsi.highlightMessage(badges, true)
        assertTrue(styledBadges.contains("${ConsoleAnsi.BRIGHT_GREEN}READY${ConsoleAnsi.RESET}"))
        assertTrue(styledBadges.contains("${ConsoleAnsi.BRIGHT_GREEN}SHARP${ConsoleAnsi.RESET}"))
        assertTrue(styledBadges.contains("${ConsoleAnsi.BRIGHT_GREEN}ON${ConsoleAnsi.RESET}"))
        assertTrue(styledBadges.contains("${ConsoleAnsi.BRIGHT_GREEN}PASS${ConsoleAnsi.RESET}"))
        assertTrue(styledBadges.contains("${ConsoleAnsi.YELLOW}MUTED${ConsoleAnsi.RESET}"))
        assertTrue(styledBadges.contains("${ConsoleAnsi.YELLOW}PAUSED${ConsoleAnsi.RESET}"))
        assertTrue(styledBadges.contains("${ConsoleAnsi.YELLOW}OFF${ConsoleAnsi.RESET}"))
        assertTrue(styledBadges.contains("${ConsoleAnsi.BRIGHT_RED}FAILED${ConsoleAnsi.RESET}"))
        assertTrue(styledBadges.contains("${ConsoleAnsi.BRIGHT_RED}DENIED${ConsoleAnsi.RESET}"))
        assertEquals(badges, ConsoleAnsi.stripAnsi(styledBadges))

        val metrics = "Perf 67.63 MIPS (8.69x RTR), rate=48kHz, frame=16.6 ms, focal=45 mm, ap=f/2.8"
        val styledMetrics = ConsoleAnsi.highlightMessage(metrics, true)
        assertTrue(styledMetrics.contains("${ConsoleAnsi.BOLD_WHITE}67.63 MIPS${ConsoleAnsi.RESET}"))
        assertTrue(styledMetrics.contains("${ConsoleAnsi.BOLD_WHITE}8.69x RTR${ConsoleAnsi.RESET}"))
        assertTrue(styledMetrics.contains("${ConsoleAnsi.BOLD_WHITE}48kHz${ConsoleAnsi.RESET}"))
        assertTrue(styledMetrics.contains("${ConsoleAnsi.BOLD_WHITE}16.6 ms${ConsoleAnsi.RESET}"))
        assertTrue(styledMetrics.contains("${ConsoleAnsi.BOLD_WHITE}45 mm${ConsoleAnsi.RESET}"))
        assertTrue(styledMetrics.contains("${ConsoleAnsi.BOLD_WHITE}f/2.8${ConsoleAnsi.RESET}"))
        assertEquals(metrics, ConsoleAnsi.stripAnsi(styledMetrics))

        // Disabled mode returns exact original string without escapes
        assertEquals(metrics, ConsoleAnsi.highlightMessage(metrics, false))
    }

    @Test
    fun formatterColorsConsoleOnlyAndKeepsFileLogsPlainText() {
        val event = FujiLogEvent(
            timestampMillis = 1_764_000_000_000L,
            level = FujiLogLevel.WARN,
            loggerName = "com.github.nyabanban.fuji.emulator.runtime.Test",
            threadName = "main",
            formattedMessage = "Battery gauge=OK temp=32.5 ms\nline2 status=PAUSED",
            throwable = null,
        )

        // Even with ColorMode.ALWAYS, FILE formatter must emit zero ANSI escapes!
        ConsoleAnsi.setMode(ConsoleAnsi.ColorMode.ALWAYS)
        val fileText = FujiLogFormatter.formatFileEvent(event)
        assertFalse(fileText.contains("\u001B"), "FILE formatter must never emit ANSI escapes: $fileText")
        assertTrue(fileText.endsWith("\n"))
        assertFalse(fileText.contains("\r"))

        val consoleColored = FujiLogFormatter.formatConsoleEvent(event, ansiColor = true)
        assertTrue(consoleColored.contains("\u001B"), "CONSOLE formatter should emit ANSI escapes when ALWAYS")
        assertTrue(consoleColored.contains("${ConsoleAnsi.BOLD_YELLOW}WARN${ConsoleAnsi.RESET}"))
        assertTrue(consoleColored.contains("${ConsoleAnsi.BRIGHT_GREEN}OK${ConsoleAnsi.RESET}"))
        assertTrue(consoleColored.contains("${ConsoleAnsi.YELLOW}PAUSED${ConsoleAnsi.RESET}"))
        assertTrue(consoleColored.endsWith("\n"))
        assertFalse(consoleColored.contains("\r"))

        // With ColorMode.NEVER, CONSOLE formatter emits exact plain text matching stripped colored output
        ConsoleAnsi.setMode(ConsoleAnsi.ColorMode.NEVER)
        val consolePlain = FujiLogFormatter.formatConsoleEvent(event, ansiColor = true)
        assertFalse(consolePlain.contains("\u001B"), "CONSOLE formatter must emit plain text when NEVER")
        assertEquals(consolePlain, ConsoleAnsi.stripAnsi(consoleColored))
    }

    @Test
    fun fujiLoggerContextEnablesAnsiOnlyOnConsoleNotFile(@TempDir dir: Path) {
        val ctx = FujiLoggerContext(propertyOverrides = mapOf(LogArchiver.PROP_LOG_DIR to dir.toString()))
        try {
            assertFalse(ctx.fileAnsiColor, "FILE channel must keep plain text")
            assertTrue(ctx.consoleAnsiColor, "CONSOLE channel must opt into ansiColor")
        } finally {
            ctx.stop()
        }
    }
}
