package com.github.nyabanban.fuji.emulator.runtime

import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.zip.GZIPOutputStream

/**
 * Log lifecycle and live level manager:
 * - Current session writes full forensic `DEBUG` logs to `<home>/logs/latest.log` while
 *   keeping the interactive `CONSOLE` channel calm at `INFO` by default.
 * - Before SLF4J initializes on startup, any existing non-empty `latest.log` is
 *   compressed into `<home>/logs/yyyy-MM-dd-N.log.gz` (using the file's last-modified
 *   date and strictly incrementing `N = 1, 2, ...` with zero overwrite).
 * - Supports startup CLI overrides (`--debug`, `--log-level=<LEVEL>`) and live runtime
 *   threshold adjustments without restarting.
 */
object LogArchiver {
    const val LATEST_LOG_FILE = "latest.log"
    const val PROP_LOG_DIR = "fuji.log.dir"
    const val PROP_CONSOLE_LEVEL = "fuji.log.console.level"
    const val PROP_FILE_LEVEL = "fuji.log.file.level"
    const val PROP_LOGGER_LEVEL = "fuji.log.level"
    const val EMULATOR_LOGGER_NAME = "com.github.nyabanban.fuji.emulator"
    const val HEADLESS_LOGGER_NAME = "FujiHeadless"
    const val CONSOLE_APPENDER_NAME = "CONSOLE"
    const val FILE_APPENDER_NAME = "ASYNC_FILE"

    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private val VALID_LEVEL_NAMES = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")

    private val loggedErrorsLock = Any()
    private val loggedErrors: MutableSet<Throwable> = Collections.newSetFromMap(WeakHashMap())

    /**
     * Returns `true` the first time [error] (or its unwrapped cause through [CompletionException] /
     * [ExecutionException]) is logged, and `false` on subsequent calls so rethrown crashes never
     * emit duplicate stacktraces across catch boundaries and uncaught-exception handlers.
     */
    fun markLoggedOnce(error: Throwable): Boolean {
        val root = unwrapWrapperException(error)
        synchronized(loggedErrorsLock) {
            if (error in loggedErrors || root in loggedErrors) {
                loggedErrors.add(error)
                return false
            }
            loggedErrors.add(error)
            if (root !== error) {
                loggedErrors.add(root)
            }
            return true
        }
    }

    private fun unwrapWrapperException(error: Throwable): Throwable {
        var current = error
        var depth = 0
        while (depth < 8 && (current is CompletionException || current is ExecutionException)) {
            val next = current.cause ?: break
            if (next === current) break
            current = next
            depth++
        }
        return current
    }

    /** Parses a log level string into its canonical uppercase name (`TRACE|DEBUG|INFO|WARN|ERROR|OFF`), or `null`. */
    fun parseLevelName(raw: String?): String? {
        val normalized = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
        return when (normalized) {
            "WARNING" -> "WARN"
            in VALID_LEVEL_NAMES -> normalized
            else -> null
        }
    }

    internal fun parseLevel(raw: String?): FujiLogLevel? = FujiLogLevel.parse(raw)

    /**
     * Parses CLI logging flags (`--debug`, `--log-level=<LEVEL>`, `--log-level <LEVEL>`).
     * Explicit `--log-level` takes precedence over `--debug` when both are supplied.
     */
    fun parseCliLogLevel(args: Array<String>): String? {
        var explicitLevel: String? = null
        var debugFlag = false
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--debug" -> debugFlag = true
                arg.startsWith("--log-level=") -> {
                    val value = arg.substringAfter('=')
                    explicitLevel = parseLevelName(value)
                        ?: throw IllegalArgumentException("Invalid --log-level value \"$value\". Expected: TRACE, DEBUG, INFO, WARN, ERROR, or OFF.")
                }
                arg == "--log-level" -> {
                    require(i + 1 < args.size) { "--log-level requires a level value (TRACE, DEBUG, INFO, WARN, ERROR, or OFF)" }
                    val value = args[++i]
                    explicitLevel = parseLevelName(value)
                        ?: throw IllegalArgumentException("Invalid --log-level value \"$value\". Expected: TRACE, DEBUG, INFO, WARN, ERROR, or OFF.")
                }
            }
            i++
        }
        return explicitLevel ?: if (debugFlag) "DEBUG" else null
    }

    /**
     * Archives an existing non-empty `latest.log` in [logDir] to `yyyy-MM-dd-N.log.gz`.
     *
     * @return the archived `.log.gz` path, or `null` if no non-empty `latest.log` existed.
     */
    fun archivePreviousLatestLog(logDir: Path, zoneId: ZoneId = ZoneId.systemDefault()): Path? {
        Files.createDirectories(logDir)
        val latest = logDir.resolve(LATEST_LOG_FILE)
        if (!Files.isRegularFile(latest)) return null
        val size = Files.size(latest)
        if (size <= 0L) {
            Files.deleteIfExists(latest)
            return null
        }

        val fileDate = Files.getLastModifiedTime(latest).toInstant().atZone(zoneId).toLocalDate()
        val datePrefix = DATE_FORMATTER.format(fileDate)
        val target = nextArchivePath(logDir, datePrefix)
        val temp = Files.createTempFile(logDir, ".$datePrefix-", ".log.gz.tmp")
        try {
            BufferedInputStream(Files.newInputStream(latest)).use { input ->
                GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(temp))).use { gzip ->
                    input.copyTo(gzip, bufferSize = 16 * 1024)
                }
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target)
            }
            Files.deleteIfExists(latest)
            return target
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            throw error
        }
    }

    /**
     * Prepares `<home>/logs` before [FujiSlf4jServiceProvider] initializes:
     * archives any prior non-empty `latest.log`, sets `fuji.log.dir`, and applies optional
     * startup console/file level overrides.
     */
    fun initializeLogging(
        home: Path,
        zoneId: ZoneId = ZoneId.systemDefault(),
        consoleLevel: String? = null,
        fileLevel: String? = null,
    ): Path? {
        val parsedConsole = consoleLevel?.let {
            parseLevel(it) ?: throw IllegalArgumentException("Invalid console log level \"$it\"")
        }
        val parsedFile = fileLevel?.let {
            parseLevel(it) ?: throw IllegalArgumentException("Invalid file log level \"$it\"")
        }
        val logDir = home.resolve("logs")
        val archived = archivePreviousLatestLog(logDir, zoneId)
        System.setProperty(PROP_LOG_DIR, logDir.toString())
        if (parsedConsole != null) {
            System.setProperty(PROP_CONSOLE_LEVEL, parsedConsole.levelStr)
        }
        if (parsedFile != null) {
            System.setProperty(PROP_FILE_LEVEL, parsedFile.levelStr)
        }
        val effectiveConsole = parsedConsole ?: parseLevel(System.getProperty(PROP_CONSOLE_LEVEL)) ?: FujiLogLevel.INFO
        val effectiveFile = parsedFile ?: parseLevel(System.getProperty(PROP_FILE_LEVEL)) ?: FujiLogLevel.DEBUG
        val neededLoggerLevel = minLevel(effectiveConsole, effectiveFile)
        val configuredLogger = parseLevel(System.getProperty(PROP_LOGGER_LEVEL)) ?: FujiLogLevel.DEBUG
        if (neededLoggerLevel.priority < configuredLogger.priority) {
            System.setProperty(PROP_LOGGER_LEVEL, neededLoggerLevel.levelStr)
        }
        return archived
    }

    /** Updates the live console channel threshold without restarting logging. */
    fun setConsoleLevel(levelName: String): String {
        val level = parseLevel(levelName)
            ?: throw IllegalArgumentException("Unknown log level \"$levelName\". Expected: TRACE, DEBUG, INFO, WARN, ERROR, or OFF.")
        return setConsoleLevel(level, activeLoggerContext()).levelStr
    }

    /** Returns the active console channel threshold name (defaults to `INFO`). */
    fun getConsoleLevelName(): String = getConsoleLevel(activeLoggerContext()).levelStr

    /** Updates the live file channel (`logs/latest.log`) threshold without restarting logging. */
    fun setFileLevel(levelName: String): String {
        val level = parseLevel(levelName)
            ?: throw IllegalArgumentException("Unknown file log level \"$levelName\". Expected: TRACE, DEBUG, INFO, WARN, ERROR, or OFF.")
        return setFileLevel(level, activeLoggerContext()).levelStr
    }

    /** Returns the active file channel (`logs/latest.log`) threshold name (defaults to `DEBUG`). */
    fun getFileLevelName(): String = getFileLevel(activeLoggerContext()).levelStr

    /** Returns the effective level name of the `com.github.nyabanban.fuji.emulator` logger tree. */
    fun getEmulatorLoggerLevelName(): String = getEmulatorLoggerLevel(activeLoggerContext()).levelStr

    /** Concise single-line status summary for interactive console `/log status`. */
    fun statusSummary(): String = statusSummary(activeLoggerContext())

    internal fun setConsoleLevel(level: FujiLogLevel, context: FujiLoggerContext?): FujiLogLevel {
        System.setProperty(PROP_CONSOLE_LEVEL, level.levelStr)
        if (context != null) {
            context.setConsoleLevel(level)
            syncEmulatorLoggerFloor(context, minLevel(level, getFileLevel(context)))
        }
        return level
    }

    internal fun getConsoleLevel(context: FujiLoggerContext?): FujiLogLevel {
        if (context != null) {
            return context.getConsoleLevel()
        }
        return parseLevel(System.getProperty(PROP_CONSOLE_LEVEL)) ?: FujiLogLevel.INFO
    }

    internal fun setFileLevel(level: FujiLogLevel, context: FujiLoggerContext?): FujiLogLevel {
        System.setProperty(PROP_FILE_LEVEL, level.levelStr)
        if (context != null) {
            context.setFileLevel(level)
            syncEmulatorLoggerFloor(context, minLevel(getConsoleLevel(context), level))
        }
        return level
    }

    internal fun getFileLevel(context: FujiLoggerContext?): FujiLogLevel {
        if (context != null) {
            return context.getFileLevel()
        }
        return parseLevel(System.getProperty(PROP_FILE_LEVEL)) ?: FujiLogLevel.DEBUG
    }

    internal fun getEmulatorLoggerLevel(context: FujiLoggerContext?): FujiLogLevel {
        if (context != null) {
            return context.getEffectiveLevel(EMULATOR_LOGGER_NAME)
        }
        return parseLevel(System.getProperty(PROP_LOGGER_LEVEL)) ?: FujiLogLevel.DEBUG
    }

    internal fun statusSummary(context: FujiLoggerContext?): String {
        val console = getConsoleLevel(context).levelStr
        val file = getFileLevel(context).levelStr
        val logger = getEmulatorLoggerLevel(context).levelStr
        return "Logging: console=$console | file=$file | logger=$logger (file=logs/$LATEST_LOG_FILE)"
    }

    /** Best-effort bounded wait for async log queues to drain to `latest.log` and console. */
    fun flushAsyncLoggers(maxWaitMs: Long = 200L) {
        activeLoggerContext()?.flushAsync(maxWaitMs)
    }

    /** Flushes and closes async console/file log channels cleanly on exit. */
    fun shutdownLogging() {
        activeLoggerContext()?.stop()
    }

    private fun activeLoggerContext(): FujiLoggerContext? =
        LoggerFactory.getILoggerFactory() as? FujiLoggerContext

    private fun syncEmulatorLoggerFloor(context: FujiLoggerContext, targetFloor: FujiLogLevel) {
        val desired = minLevel(targetFloor, FujiLogLevel.DEBUG)
        if (context.getEffectiveLevel(EMULATOR_LOGGER_NAME).priority > desired.priority ||
            targetFloor.priority < FujiLogLevel.DEBUG.priority
        ) {
            context.setLoggerLevel(EMULATOR_LOGGER_NAME, desired)
            context.setLoggerLevel(HEADLESS_LOGGER_NAME, desired)
        }
    }

    private fun minLevel(a: FujiLogLevel, b: FujiLogLevel): FujiLogLevel =
        if (a.priority <= b.priority) a else b

    private fun nextArchivePath(logDir: Path, datePrefix: String): Path =
        RollingLogFileSink.nextArchivePath(logDir, datePrefix)
}
