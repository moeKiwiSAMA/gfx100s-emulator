package com.github.nyabanban.fuji.emulator.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.test.*

class LoggingTest {
    @Test
    @Timeout(30)
    fun anAcceptedEventStaysPendingUntilTheSinkFinishes() {
        val delivered = AtomicInteger()
        val event = FujiLogEvent(0L, FujiLogLevel.INFO, "probe", "test", "message", null)
        AsyncLogChannel("drain-check", 16, 1000, { delivered.incrementAndGet() }, {}).use { channel ->
            repeat(4096) { index ->
                assertTrue(channel.enqueue(event))
                while (channel.pendingCount() != 0 && !Thread.currentThread().isInterrupted) Thread.onSpinWait()
                assertEquals(index + 1, delivered.get())
            }
        }
    }

    @Test fun productionFileHasUtf8LfHeaderWithoutEventsAndConsoleStaysEmpty(@TempDir directory: Path) {
        val console = ByteArrayOutputStream()
        val captured = PrintStream(console, true, Charsets.UTF_8)
        val context = FujiLoggerContext(
            propertyOverrides = mapOf(LogArchiver.PROP_LOG_DIR to directory.toString()),
            consoleStream = captured,
        )
        try {
            assertTrue(context.isStarted)
            assertTrue(context.isFileAsyncNeverBlock)
            assertTrue(context.isConsoleAsyncNeverBlock)
            assertTrue(context.emitFileHeader)
            assertFalse(context.fileAnsiColor)
            assertTrue(context.consoleAnsiColor)
            context.stop()
            assertContentEquals(
                "# Fuji emulator JVM log (UTF-8, LF)\n".toByteArray(Charsets.UTF_8),
                Files.readAllBytes(directory.resolve("latest.log")),
            )
            assertEquals("", console.toString(Charsets.UTF_8))
        } finally {
            context.stop()
            captured.close()
        }
    }

    @Test fun slf4jServiceProviderBindsToPureMitFujiLoggerContext() {
        val factory = LoggerFactory.getILoggerFactory()
        assertTrue(factory is FujiLoggerContext, "Expected SLF4J to bind to FujiLoggerContext, got: ${factory::class.java.name}")
    }

    @Test fun consoleLogUsesTimestampLevelFormat(@TempDir directory: Path) {
        val console = ByteArrayOutputStream()
        val captured = PrintStream(console, true, Charsets.UTF_8)
        val prevMode = ConsoleAnsi.currentMode()
        ConsoleAnsi.setMode(ConsoleAnsi.ColorMode.NEVER)
        val context = FujiLoggerContext(
            propertyOverrides = mapOf(LogArchiver.PROP_LOG_DIR to directory.toString()),
            consoleStream = captured,
        )
        try {
            val log = context.getLogger("console-format-check")
            log.info("GFX100S emulator ready")
            context.stop()
            val out = console.toString(Charsets.UTF_8)
            assertTrue(
                Regex("^\\[\\d{2}:\\d{2}:\\d{2} INFO]: GFX100S emulator ready\\n$").matches(out),
                "Expected [HH:mm:ss LEVEL]: console log line, got: $out",
            )
        } finally {
            context.stop()
            captured.close()
            ConsoleAnsi.setMode(prevMode)
        }
    }

    @Test fun productionPolicyRollsAndCompressesWithBoundedTestSize() {
        val directory = Files.createTempDirectory("fuji-logs")
        try {
            val context = FujiLoggerContext(
                propertyOverrides = mapOf(
                    LogArchiver.PROP_LOG_DIR to directory.toString(),
                    "fuji.log.maxFileSize" to "4KB",
                ),
            )
            context.setConsoleEnabled(false)
            val log = context.getLogger("synthetic-rollover")
            repeat(500) { log.info("compression-check-{} {}", it, "x".repeat(500)) }
            log.error("multiline\r\nmessage\rwith CR", IllegalStateException("test\r\nfailure"))
            context.stop() // flushes async queue and waits for pending compression/cleanup jobs
            val archives = Files.walk(directory).use { it.filter { file -> file.toString().endsWith(".log.gz") }.toList() }
            assertTrue(archives.isNotEmpty(), "Expected rolled .log.gz archive(s) in $directory")
            val text = GZIPInputStream(Files.newInputStream(archives.first())).use { it.readAllBytes().toString(Charsets.UTF_8) }
            assertTrue(text.startsWith("# Fuji emulator JVM log (UTF-8, LF)\n"))
            assertTrue(text.contains("compression-check-"))
            assertFalse(text.contains('\r'))
            assertTrue(text.endsWith("\n"))
            val current = Files.readString(directory.resolve("latest.log"))
            assertTrue(current.startsWith("# Fuji emulator JVM log (UTF-8, LF)\n"))
            assertFalse(current.contains('\r'))
            assertTrue(current.endsWith("\n"))
            assertTrue(current.contains("test\nfailure"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun rollingArchivePrunerEnforcesMaxHistoryAndTotalSizeCap(@TempDir directory: Path) {
        val refDate = LocalDate.of(2026, 11, 25)
        val expired = directory.resolve("2026-11-01-1.log.gz")
        val oldInWindow = directory.resolve("2026-11-20-1.log.gz")
        val recent = directory.resolve("2026-11-24-1.log.gz")
        val newest = directory.resolve("2026-11-25-1.log.gz")
        Files.write(expired, ByteArray(100))
        Files.write(oldInWindow, ByteArray(200))
        Files.write(recent, ByteArray(200))
        Files.write(newest, ByteArray(200))

        RollingLogFileSink.pruneArchives(
            logDir = directory,
            referenceDate = refDate,
            maxHistoryDays = 14,
            totalSizeCapBytes = 450L,
            protectedPath = newest,
        )

        assertFalse(Files.exists(expired), "Archive older than 14 days must be pruned")
        assertFalse(Files.exists(oldInWindow), "Oldest in-window archive must be pruned to honor totalSizeCap")
        assertTrue(Files.exists(recent), "Recent archive within cap must be kept")
        assertTrue(Files.exists(newest), "Freshly rolled protected archive must be kept")
    }

    @Test fun startupArchiverCompressesExistingLatestLogWithIncrementingIndicesAndZeroOverwrite(@TempDir directory: Path) {
        val zone = ZoneOffset.UTC
        val fixedTimestamp = FileTime.from(Instant.parse("2026-11-20T08:15:00Z"))

        // Missing latest.log -> no archive
        assertNull(LogArchiver.archivePreviousLatestLog(directory, zone))

        // Empty latest.log -> removed without producing an empty .log.gz
        val latest = directory.resolve(LogArchiver.LATEST_LOG_FILE)
        Files.write(latest, ByteArray(0))
        assertNull(LogArchiver.archivePreviousLatestLog(directory, zone))
        assertFalse(Files.exists(latest))

        // First non-empty run -> 2026-11-20-1.log.gz
        Files.writeString(latest, "first-session-line\n")
        Files.setLastModifiedTime(latest, fixedTimestamp)
        val firstArchive = assertNotNull(LogArchiver.archivePreviousLatestLog(directory, zone))
        assertEquals("2026-11-20-1.log.gz", firstArchive.fileName.toString())
        assertFalse(Files.exists(latest))
        assertEquals("first-session-line\n", gunzip(firstArchive))

        // Second non-empty run on same date -> 2026-11-20-2.log.gz, preserving -1
        Files.writeString(latest, "second-session-line\n")
        Files.setLastModifiedTime(latest, fixedTimestamp)
        val secondArchive = assertNotNull(LogArchiver.archivePreviousLatestLog(directory, zone))
        assertEquals("2026-11-20-2.log.gz", secondArchive.fileName.toString())
        assertEquals("first-session-line\n", gunzip(firstArchive))
        assertEquals("second-session-line\n", gunzip(secondArchive))

        // Existing -3.log.gz must never be overwritten; archiver skips to -4.log.gz
        val preExisting = directory.resolve("2026-11-20-3.log.gz")
        Files.writeString(preExisting, "sentinel-existing")
        Files.writeString(latest, "third-session-line\n")
        Files.setLastModifiedTime(latest, fixedTimestamp)
        val fourthArchive = assertNotNull(LogArchiver.archivePreviousLatestLog(directory, zone))
        assertEquals("2026-11-20-4.log.gz", fourthArchive.fileName.toString())
        assertEquals("sentinel-existing", Files.readString(preExisting))
        assertEquals("third-session-line\n", gunzip(fourthArchive))
    }

    private fun gunzip(path: Path): String =
        GZIPInputStream(Files.newInputStream(path)).use { it.readAllBytes().toString(Charsets.UTF_8) }

    @Test fun consoleDefaultsToInfoWhileFileCapturesDebugAndLiveLevelToggleWorks(@TempDir directory: Path) {
        val console = ByteArrayOutputStream()
        val captured = PrintStream(console, true, Charsets.UTF_8)
        val prevConsoleProp = System.getProperty(LogArchiver.PROP_CONSOLE_LEVEL)
        val prevFileProp = System.getProperty(LogArchiver.PROP_FILE_LEVEL)
        val prevMode = ConsoleAnsi.currentMode()
        ConsoleAnsi.setMode(ConsoleAnsi.ColorMode.NEVER)
        val context = FujiLoggerContext(
            propertyOverrides = mapOf(
                LogArchiver.PROP_LOG_DIR to directory.toString(),
                LogArchiver.PROP_CONSOLE_LEVEL to "INFO",
                LogArchiver.PROP_FILE_LEVEL to "DEBUG",
                LogArchiver.PROP_LOGGER_LEVEL to "DEBUG",
            ),
            consoleStream = captured,
        )
        try {
            val emuLog = context.getLogger("com.github.nyabanban.fuji.emulator.runtime.TaxonomyCheck")
            val thirdPartyLog = context.getLogger("org.thirdparty.NoisyLibrary")

            assertEquals(FujiLogLevel.INFO, LogArchiver.getConsoleLevel(context))
            assertEquals(FujiLogLevel.DEBUG, LogArchiver.getFileLevel(context))

            // Default: INFO reaches both console and file; DEBUG reaches file ONLY; third-party DEBUG stays filtered
            emuLog.info("milestone-visible-everywhere")
            emuLog.debug("forensic-file-only-detail")
            thirdPartyLog.debug("third-party-noise-suppressed")

            // Switch console live to DEBUG -> subsequent DEBUG reaches both console and file
            LogArchiver.setConsoleLevel(FujiLogLevel.DEBUG, context)
            assertEquals(FujiLogLevel.DEBUG, LogArchiver.getConsoleLevel(context))
            emuLog.debug("live-console-debug-enabled")

            // Switch console back to INFO -> subsequent DEBUG hidden from console again
            LogArchiver.setConsoleLevel(FujiLogLevel.INFO, context)
            emuLog.debug("hidden-from-console-again")

            context.stop()
            val consoleText = console.toString(Charsets.UTF_8)
            val fileText = Files.readString(directory.resolve("latest.log"), Charsets.UTF_8)

            assertTrue(consoleText.contains("milestone-visible-everywhere"), consoleText)
            assertFalse(consoleText.contains("forensic-file-only-detail"), consoleText)
            assertTrue(consoleText.contains("DEBUG]: live-console-debug-enabled"), consoleText)
            assertFalse(consoleText.contains("hidden-from-console-again"), consoleText)
            assertFalse(consoleText.contains("third-party-noise-suppressed"), consoleText)

            assertTrue(fileText.contains("INFO") && fileText.contains("milestone-visible-everywhere"), fileText)
            assertTrue(fileText.contains("DEBUG") && fileText.contains("forensic-file-only-detail"), fileText)
            assertTrue(fileText.contains("live-console-debug-enabled"), fileText)
            assertTrue(fileText.contains("hidden-from-console-again"), fileText)
            assertFalse(fileText.contains("third-party-noise-suppressed"), fileText)
        } finally {
            context.stop()
            captured.close()
            ConsoleAnsi.setMode(prevMode)
            if (prevConsoleProp == null) System.clearProperty(LogArchiver.PROP_CONSOLE_LEVEL) else System.setProperty(LogArchiver.PROP_CONSOLE_LEVEL, prevConsoleProp)
            if (prevFileProp == null) System.clearProperty(LogArchiver.PROP_FILE_LEVEL) else System.setProperty(LogArchiver.PROP_FILE_LEVEL, prevFileProp)
        }
    }

    @Test fun loggerNameAbbreviationPlaceholderExpansionAndLogCaptureWork() {
        assertEquals(
            "c.g.n.f.e.r.RelaxedPeripheralFallback",
            FujiLogFormatter.abbreviateLoggerName("com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback", 32),
        )
        assertEquals("ShortLogger", FujiLogFormatter.abbreviateLoggerName("ShortLogger", 32))
        assertEquals(
            "/tmp/test-logs/worker-7",
            FujiLogFormatter.expandPlaceholders("/tmp/test-logs/worker-\${org.gradle.test.worker}", mapOf("org.gradle.test.worker" to "7")),
        )
        assertEquals(
            "16MB",
            FujiLogFormatter.expandPlaceholders("\${missing.prop:-16MB}"),
        )
        assertEquals(16L * 1024L * 1024L, FujiLogFormatter.parseFileSizeBytes("16MB", 1L))
        assertEquals(4L * 1024L, FujiLogFormatter.parseFileSizeBytes("4KB", 1L))

        val events = FujiLogCapture.capture("fuji.capture.test") {
            LoggerFactory.getLogger("fuji.capture.test").warn("captured {} value={}", "ok", 42)
        }
        assertEquals(1, events.size)
        assertEquals(FujiLogLevel.WARN, events[0].level)
        assertEquals("captured ok value=42", events[0].formattedMessage)
    }

    @Test fun parseCliLogLevelSupportsDebugFlagAndExplicitLevelForms() {
        assertNull(LogArchiver.parseCliLogLevel(arrayOf("--headless", "--auto-boot")))
        assertEquals("DEBUG", LogArchiver.parseCliLogLevel(arrayOf("--debug")))
        assertEquals("TRACE", LogArchiver.parseCliLogLevel(arrayOf("--log-level=trace")))
        assertEquals("WARN", LogArchiver.parseCliLogLevel(arrayOf("--log-level", "WARN")))
        assertEquals("ERROR", LogArchiver.parseCliLogLevel(arrayOf("--debug", "--log-level=ERROR")))
        assertFailsWith<IllegalArgumentException> { LogArchiver.parseCliLogLevel(arrayOf("--log-level=BOGUS")) }
        assertFailsWith<IllegalArgumentException> { LogArchiver.parseCliLogLevel(arrayOf("--log-level")) }
    }

    @Test fun markLoggedOnceDeduplicatesSameAndWrappedExceptions() {
        val root = IllegalStateException("session crash")
        assertTrue(LogArchiver.markLoggedOnce(root))
        assertFalse(LogArchiver.markLoggedOnce(root))
        assertFalse(LogArchiver.markLoggedOnce(java.util.concurrent.CompletionException(root)))
        assertFalse(LogArchiver.markLoggedOnce(java.util.concurrent.ExecutionException(root)))

        val wrappedFirst = java.util.concurrent.CompletionException(IllegalArgumentException("wrapped first"))
        assertTrue(LogArchiver.markLoggedOnce(wrappedFirst))
        assertFalse(LogArchiver.markLoggedOnce(wrappedFirst))
        assertFalse(LogArchiver.markLoggedOnce(wrappedFirst.cause!!))

        val distinct = IllegalStateException("distinct error")
        assertTrue(LogArchiver.markLoggedOnce(distinct))
    }
}
