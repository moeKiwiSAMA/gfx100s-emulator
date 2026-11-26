package com.github.nyabanban.fuji.emulator.runtime

import org.slf4j.ILoggerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level as Slf4jLevel
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/** Canonical log severity levels for the pure-MIT SLF4J 2.0 provider. */
enum class FujiLogLevel(val priority: Int, val levelStr: String) {
    TRACE(10, "TRACE"),
    DEBUG(20, "DEBUG"),
    INFO(30, "INFO"),
    WARN(40, "WARN"),
    ERROR(50, "ERROR"),
    OFF(Int.MAX_VALUE, "OFF"),
    ;

    companion object {
        fun parse(raw: String?): FujiLogLevel? {
            val normalized = raw?.trim()?.uppercase(Locale.ROOT) ?: return null
            return when (normalized) {
                "TRACE" -> TRACE
                "DEBUG" -> DEBUG
                "INFO" -> INFO
                "WARN", "WARNING" -> WARN
                "ERROR" -> ERROR
                "OFF" -> OFF
                else -> null
            }
        }

        fun fromSlf4j(level: Slf4jLevel): FujiLogLevel = when (level) {
            Slf4jLevel.TRACE -> TRACE
            Slf4jLevel.DEBUG -> DEBUG
            Slf4jLevel.INFO -> INFO
            Slf4jLevel.WARN -> WARN
            Slf4jLevel.ERROR -> ERROR
        }
    }
}

/** Immutable normalized log event passed through async channels and test capture hooks. */
data class FujiLogEvent(
    val timestampMillis: Long,
    val level: FujiLogLevel,
    val loggerName: String,
    val threadName: String,
    val formattedMessage: String,
    val throwable: Throwable?,
)

/**
 * Pure-MIT formatter preserving exact console and file output parity:
 * - File header: `# Fuji emulator JVM log (UTF-8, LF)\n`
 * - File line: `yyyy-MM-dd HH:mm:ss,SSS %-5level [thread] logger{32} - msg\n` (plain UTF-8, LF-only, zero ANSI)
 * - Console line: `[HH:mm:ss LEVEL]: msg\n` (LF-only, optional `ConsoleAnsi` colorization)
 */
object FujiLogFormatter {
    const val FILE_HEADER: String = "# Fuji emulator JVM log (UTF-8, LF)\n"
    val FILE_HEADER_BYTES: ByteArray = FILE_HEADER.toByteArray(Charsets.UTF_8)

    private val FILE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS", Locale.ROOT)
    private val CONSOLE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val CONSOLE_PREFIX_REGEX = Regex("^\\[(\\d{2}:\\d{2}:\\d{2}) ([A-Z]+)\\]: (.*)$")
    private val SIZE_REGEX = Regex("^\\s*(\\d+)\\s*([KMG]?B?)\\s*$", RegexOption.IGNORE_CASE)

    fun formatFileEvent(event: FujiLogEvent, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val ts = FILE_TIME_FMT.format(Instant.ofEpochMilli(event.timestampMillis).atZone(zoneId))
        val levelPadded = event.level.levelStr.padEnd(5, ' ')
        val abbreviated = abbreviateLoggerName(event.loggerName, 32)
        val sb = StringBuilder(128 + event.formattedMessage.length)
        sb.append(ts).append(' ')
            .append(levelPadded).append(" [")
            .append(event.threadName).append("] ")
            .append(abbreviated).append(" - ")
            .append(event.formattedMessage).append('\n')
        appendThrowable(sb, event.throwable)
        return Formats.lf(sb.toString())
    }

    fun formatConsoleEvent(
        event: FujiLogEvent,
        ansiColor: Boolean = true,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val ts = CONSOLE_TIME_FMT.format(Instant.ofEpochMilli(event.timestampMillis).atZone(zoneId))
        val sb = StringBuilder(64 + event.formattedMessage.length)
        sb.append('[').append(ts).append(' ').append(event.level.levelStr).append("]: ")
            .append(event.formattedMessage).append('\n')
        appendThrowable(sb, event.throwable)
        val plain = Formats.lf(sb.toString())
        if (!ansiColor || !ConsoleAnsi.isEnabled()) {
            return plain
        }
        return colorizeConsoleOutput(plain)
    }

    internal fun colorizeConsoleOutput(plain: String): String {
        val hasTrailingLf = plain.endsWith('\n')
        val body = if (hasTrailingLf) plain.substring(0, plain.length - 1) else plain
        val firstNewline = body.indexOf('\n')
        val firstLine = if (firstNewline >= 0) body.substring(0, firstNewline) else body
        val remainder = if (firstNewline >= 0) body.substring(firstNewline + 1) else null

        val match = CONSOLE_PREFIX_REGEX.matchEntire(firstLine)
        val styledFirst = if (match != null) {
            ConsoleAnsi.formatConsoleLine(
                time = match.groupValues[1],
                level = match.groupValues[2],
                message = match.groupValues[3],
                colorEnabled = true,
            )
        } else {
            ConsoleAnsi.highlightMessage(firstLine, colorEnabled = true)
        }

        val styled = if (remainder != null) {
            "$styledFirst\n${ConsoleAnsi.highlightMessage(remainder, colorEnabled = true)}"
        } else {
            styledFirst
        }
        return if (hasTrailingLf) "$styled\n" else styled
    }

    internal fun abbreviateLoggerName(name: String, maxLen: Int = 32): String {
        if (name.length <= maxLen || maxLen <= 0) return name
        val parts = name.split('.')
        if (parts.size <= 1) return name
        val shortened = parts.toMutableList()
        var currentLen = name.length
        for (i in 0 until shortened.size - 1) {
            if (currentLen <= maxLen) break
            val seg = shortened[i]
            if (seg.length > 1) {
                currentLen -= (seg.length - 1)
                shortened[i] = seg.substring(0, 1)
            }
        }
        return shortened.joinToString(".")
    }

    internal fun resolveProperty(
        key: String,
        defaultValue: String,
        overrides: Map<String, String> = emptyMap(),
    ): String {
        val raw = overrides[key] ?: System.getProperty(key) ?: System.getenv(key) ?: defaultValue
        return expandPlaceholders(raw, overrides)
    }

    internal fun expandPlaceholders(raw: String, overrides: Map<String, String> = emptyMap()): String {
        var current = raw
        repeat(8) {
            val start = current.indexOf("\${")
            if (start < 0) return current
            val sb = StringBuilder(current.length)
            var idx = 0
            var changed = false
            while (idx < current.length) {
                val open = current.indexOf("\${", idx)
                if (open < 0) {
                    sb.append(current, idx, current.length)
                    break
                }
                val close = findMatchingBrace(current, open + 2)
                if (close < 0) {
                    sb.append(current, idx, current.length)
                    break
                }
                sb.append(current, idx, open)
                val expr = current.substring(open + 2, close)
                val sep = expr.indexOf(":-")
                val propKey = if (sep >= 0) expr.substring(0, sep) else expr
                val fallback = if (sep >= 0) expr.substring(sep + 2) else null
                val resolved = overrides[propKey] ?: System.getProperty(propKey) ?: System.getenv(propKey)
                if (resolved != null) {
                    sb.append(resolved)
                    changed = true
                } else if (fallback != null) {
                    sb.append(fallback)
                    changed = true
                } else {
                    sb.append("\${").append(expr).append('}')
                }
                idx = close + 1
            }
            current = sb.toString()
            if (!changed) return current
        }
        return current
    }

    private fun findMatchingBrace(text: String, fromIndex: Int): Int {
        var depth = 0
        var i = fromIndex
        while (i < text.length) {
            if (i + 1 < text.length && text[i] == '$' && text[i + 1] == '{') {
                depth++
                i += 2
                continue
            }
            if (text[i] == '}') {
                if (depth == 0) return i
                depth--
            }
            i++
        }
        return -1
    }

    internal fun parseFileSizeBytes(raw: String?, defaultBytes: Long): Long {
        if (raw.isNullOrBlank()) return defaultBytes
        val match = SIZE_REGEX.matchEntire(raw) ?: return defaultBytes
        val value = match.groupValues[1].toLongOrNull() ?: return defaultBytes
        val unit = match.groupValues[2].uppercase(Locale.ROOT)
        val multiplier = when (unit) {
            "", "B" -> 1L
            "K", "KB" -> 1024L
            "M", "MB" -> 1024L * 1024L
            "G", "GB" -> 1024L * 1024L * 1024L
            else -> return defaultBytes
        }
        return runCatching { Math.multiplyExact(value, multiplier) }.getOrDefault(defaultBytes)
            .coerceAtLeast(1L)
    }

    private fun appendThrowable(sb: StringBuilder, throwable: Throwable?) {
        if (throwable == null) return
        val sw = StringWriter(512)
        PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
        sb.append(sw.toString())
    }
}

/**
 * Clean synchronous test log capture hook replacing Logback reflection (`ListAppender`).
 * Listeners run synchronously on the logging thread so unit tests never race async queues.
 */
object FujiLogCapture {
    data class CapturedEvent(
        val level: FujiLogLevel,
        val loggerName: String,
        val formattedMessage: String,
        val throwable: Throwable?,
    )

    private data class Registration(
        val loggerFilter: String?,
        val listener: (CapturedEvent) -> Unit,
    )

    private val registrations = CopyOnWriteArrayList<Registration>()

    fun attach(loggerName: String? = null, listener: (CapturedEvent) -> Unit): AutoCloseable {
        val reg = Registration(loggerName, listener)
        registrations.add(reg)
        return AutoCloseable { registrations.remove(reg) }
    }

    fun capture(loggerName: String? = null, block: () -> Unit): List<CapturedEvent> {
        val collected = CopyOnWriteArrayList<CapturedEvent>()
        attach(loggerName) { collected.add(it) }.use { block() }
        return collected.toList()
    }

    fun capture(loggerClass: Class<*>, block: () -> Unit): List<CapturedEvent> =
        capture(loggerClass.name, block)

    fun captureWarnings(loggerClass: Class<*>, block: () -> Unit): List<String> =
        capture(loggerClass.name, block)
            .filter { it.level == FujiLogLevel.WARN }
            .map { it.formattedMessage }

    internal fun dispatch(event: FujiLogEvent) {
        if (registrations.isEmpty()) return
        val captured = CapturedEvent(
            level = event.level,
            loggerName = event.loggerName,
            formattedMessage = event.formattedMessage,
            throwable = event.throwable,
        )
        for (reg in registrations) {
            val filter = reg.loggerFilter
            if (filter == null || event.loggerName == filter || event.loggerName.startsWith("$filter.")) {
                runCatching { reg.listener(captured) }
            }
        }
    }
}

/**
 * Thread-safe size-and-time GZIP rolling file writer for `${LOG_DIR}/latest.log`:
 * - Writes UTF-8 LF header immediately upon opening/creating a fresh `latest.log`.
 * - Rolls over on date change or when `currentSize + eventBytes > maxFileSizeBytes`
 *   into `${LOG_DIR}/yyyy-MM-dd-i.log.gz` (atomic temp file, zero overwrite).
 * - Serializes archive pruning (`maxHistory` days + `totalSizeCap` bytes) on a single
 *   daemon cleanup thread while preserving `Thread.interrupted()`.
 */
internal class RollingLogFileSink(
    val logDir: Path,
    val activeFile: Path = logDir.resolve(LogArchiver.LATEST_LOG_FILE),
    private val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
    private val maxHistoryDays: Int = DEFAULT_MAX_HISTORY_DAYS,
    private val totalSizeCapBytes: Long = DEFAULT_TOTAL_SIZE_CAP_BYTES,
    private val emitFileHeader: Boolean = true,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : AutoCloseable {
    private val lock = Any()
    private var output: OutputStream? = null
    private var currentSizeBytes: Long = 0L
    private var currentDate: LocalDate = LocalDate.now(zoneId)
    private var closed: Boolean = false
    private val cleanupExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fuji-log-cleanup").apply { isDaemon = true }
    }

    init {
        synchronized(lock) {
            openActiveFileLocked()
        }
    }

    fun write(event: FujiLogEvent) {
        val bytes = FujiLogFormatter.formatFileEvent(event, zoneId).toByteArray(Charsets.UTF_8)
        val eventDate = Instant.ofEpochMilli(event.timestampMillis).atZone(zoneId).toLocalDate()
        synchronized(lock) {
            if (closed) return
            if (output == null) {
                openActiveFileLocked()
            }
            val needsDateRoll = currentSizeBytes > headerLength() && eventDate != currentDate
            val needsSizeRoll = currentSizeBytes > headerLength() && currentSizeBytes + bytes.size > maxFileSizeBytes
            if (needsDateRoll || needsSizeRoll) {
                rolloverLocked(currentDate)
                currentDate = eventDate
            }
            val out = output ?: return
            out.write(bytes)
            currentSizeBytes += bytes.size
        }
    }

    fun flush() {
        synchronized(lock) {
            runCatching { output?.flush() }
        }
    }

    override fun close() {
        val wasInterrupted = Thread.interrupted()
        try {
            synchronized(lock) {
                if (closed) return
                closed = true
                runCatching { output?.flush() }
                runCatching { output?.close() }
                output = null
            }
            cleanupExecutor.shutdown()
            runCatching { cleanupExecutor.awaitTermination(5L, TimeUnit.SECONDS) }
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun headerLength(): Long = if (emitFileHeader) FujiLogFormatter.FILE_HEADER_BYTES.size.toLong() else 0L

    private fun openActiveFileLocked() {
        Files.createDirectories(logDir)
        val existingSize = if (Files.isRegularFile(activeFile)) Files.size(activeFile) else 0L
        currentDate = if (existingSize > 0L) {
            Files.getLastModifiedTime(activeFile).toInstant().atZone(zoneId).toLocalDate()
        } else {
            LocalDate.now(zoneId)
        }
        val stream = BufferedOutputStream(
            Files.newOutputStream(
                activeFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            ),
            16 * 1024,
        )
        currentSizeBytes = existingSize
        if (emitFileHeader && existingSize == 0L) {
            stream.write(FujiLogFormatter.FILE_HEADER_BYTES)
            stream.flush()
            currentSizeBytes = FujiLogFormatter.FILE_HEADER_BYTES.size.toLong()
        }
        output = stream
    }

    private fun rolloverLocked(archiveDate: LocalDate) {
        val wasInterrupted = Thread.interrupted()
        try {
            runCatching { output?.flush() }
            runCatching { output?.close() }
            output = null
            if (Files.isRegularFile(activeFile) && Files.size(activeFile) > 0L) {
                val datePrefix = DATE_FORMATTER.format(archiveDate)
                val target = nextArchivePath(logDir, datePrefix)
                val temp = Files.createTempFile(logDir, ".$datePrefix-", ".log.gz.tmp")
                try {
                    BufferedInputStream(Files.newInputStream(activeFile)).use { input ->
                        GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(temp))).use { gzip ->
                            input.copyTo(gzip, bufferSize = 16 * 1024)
                        }
                    }
                    try {
                        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temp, target)
                    }
                    Files.deleteIfExists(activeFile)
                } catch (_: Throwable) {
                    runCatching { Files.deleteIfExists(temp) }
                }
                scheduleArchivePruning(archiveDate, target)
            }
            openActiveFileLocked()
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun scheduleArchivePruning(referenceDate: LocalDate, newestProtected: Path) {
        if (cleanupExecutor.isShutdown) return
        runCatching {
            cleanupExecutor.submit {
                pruneArchives(logDir, referenceDate, maxHistoryDays, totalSizeCapBytes, newestProtected)
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_FILE_SIZE_BYTES: Long = 16L * 1024L * 1024L
        const val DEFAULT_MAX_HISTORY_DAYS: Int = 14
        const val DEFAULT_TOTAL_SIZE_CAP_BYTES: Long = 512L * 1024L * 1024L

        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val ARCHIVE_NAME_REGEX = Regex("^(\\d{4}-\\d{2}-\\d{2})-(\\d+)\\.log\\.gz$")

        internal fun nextArchivePath(logDir: Path, datePrefix: String): Path {
            var index = 1
            while (true) {
                val candidate = logDir.resolve("$datePrefix-$index.log.gz")
                if (!Files.exists(candidate)) return candidate
                index++
            }
        }

        internal fun pruneArchives(
            logDir: Path,
            referenceDate: LocalDate,
            maxHistoryDays: Int,
            totalSizeCapBytes: Long,
            protectedPath: Path? = null,
        ) {
            if (!Files.isDirectory(logDir)) return
            data class ArchiveEntry(val path: Path, val date: LocalDate, val index: Int, val size: Long)
            val entries = mutableListOf<ArchiveEntry>()
            runCatching {
                Files.list(logDir).use { stream ->
                    stream.forEach { p ->
                        val name = p.fileName?.toString() ?: return@forEach
                        val m = ARCHIVE_NAME_REGEX.matchEntire(name) ?: return@forEach
                        val date = runCatching { LocalDate.parse(m.groupValues[1], DATE_FORMATTER) }.getOrNull() ?: return@forEach
                        val idx = m.groupValues[2].toIntOrNull() ?: return@forEach
                        val size = runCatching { Files.size(p) }.getOrDefault(0L)
                        entries += ArchiveEntry(p, date, idx, size)
                    }
                }
            }
            if (entries.isEmpty()) return
            entries.sortWith(compareBy<ArchiveEntry> { it.date }.thenBy { it.index })

            val cutoff = referenceDate.minusDays(maxHistoryDays.coerceAtLeast(1).toLong())
            val remaining = mutableListOf<ArchiveEntry>()
            for (entry in entries) {
                if (entry.path != protectedPath && entry.date.isBefore(cutoff)) {
                    runCatching { Files.deleteIfExists(entry.path) }
                } else {
                    remaining += entry
                }
            }

            if (totalSizeCapBytes > 0L) {
                var total = remaining.sumOf { it.size }
                for (entry in remaining) {
                    if (total <= totalSizeCapBytes) break
                    if (entry.path == protectedPath) continue
                    if (runCatching { Files.deleteIfExists(entry.path) }.getOrDefault(false)) {
                        total -= entry.size
                    }
                }
            }
        }
    }
}

/**
 * Bounded, non-blocking (`neverBlock = true`) asynchronous log dispatch channel with
 * accurate drain tracking and graceful bounded flush on shutdown.
 */
internal class AsyncLogChannel(
    name: String,
    queueSize: Int,
    private val maxFlushTimeMs: Long,
    private val sink: (FujiLogEvent) -> Unit,
    private val flusher: () -> Unit,
) : AutoCloseable {
    private val queue = ArrayBlockingQueue<FujiLogEvent>(queueSize.coerceAtLeast(16))
    private val running = AtomicBoolean(true)
    private val pending = AtomicInteger(0)
    private val worker: Thread = Thread({ drainLoop() }, name).apply {
        isDaemon = true
        start()
    }

    val isNeverBlock: Boolean = true
    val isRunning: Boolean get() = running.get()

    fun enqueue(event: FujiLogEvent): Boolean {
        if (!running.get()) return false
        // Reserve before publishing: a worker can take the event as soon as offer succeeds.
        pending.incrementAndGet()
        if (running.get() && queue.offer(event)) return true
        pending.decrementAndGet()
        return false
    }

    fun pendingCount(): Int = pending.get()

    fun flush(maxWaitMs: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs.coerceAtLeast(10L))
        while (pendingCount() > 0 && System.nanoTime() < deadline && worker.isAlive) {
            try {
                Thread.sleep(5L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        runCatching { flusher() }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        val wasInterrupted = Thread.interrupted()
        try {
            worker.interrupt()
            runCatching { worker.join(maxFlushTimeMs.coerceAtLeast(100L)) }
            runCatching { flusher() }
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun drainLoop() {
        while (running.get() || pending.get() != 0) {
            val first = try {
                queue.poll(25L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // close() wakes the poll. Recheck the outstanding accepted events.
                continue
            } ?: continue
            processEvent(first)
            // Batch-drain any immediately ready events before flushing the stream.
            while (true) {
                val next = queue.poll() ?: break
                processEvent(next)
            }
            runCatching { flusher() }
        }
    }

    private fun processEvent(event: FujiLogEvent) {
        try {
            sink(event)
        } catch (_: Throwable) {
            // Logging sink failures must never crash application threads.
        } finally {
            pending.decrementAndGet()
        }
    }
}

/**
 * Self-contained pure-MIT logging context implementing SLF4J 2.0's [ILoggerFactory].
 * Supports both the global process-wide singleton (via [FujiSlf4jServiceProvider]) and
 * isolated per-test instances with explicit property overrides and captured console streams.
 */
class FujiLoggerContext(
    private val propertyOverrides: Map<String, String> = emptyMap(),
    consoleStream: PrintStream? = null,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ILoggerFactory, AutoCloseable {
    val logDir: Path
    val activeLogFile: Path
    val emitFileHeader: Boolean = true
    val fileAnsiColor: Boolean = false
    val consoleAnsiColor: Boolean = true
    val isFileAsyncNeverBlock: Boolean get() = fileChannel.isNeverBlock
    val isConsoleAsyncNeverBlock: Boolean get() = consoleChannel.isNeverBlock

    private val loggers = ConcurrentHashMap<String, FujiLogger>()
    private val explicitLevels = ConcurrentHashMap<String, FujiLogLevel>()

    @Volatile private var consoleThreshold: FujiLogLevel
    @Volatile private var fileThreshold: FujiLogLevel
    @Volatile private var consoleEnabled: Boolean = true
    @Volatile private var fileEnabled: Boolean = true
    private val stopped = AtomicBoolean(false)

    private val targetConsoleStream: PrintStream = consoleStream ?: System.out
    private val fileSink: RollingLogFileSink
    private val fileChannel: AsyncLogChannel
    private val consoleChannel: AsyncLogChannel

    val isStarted: Boolean get() = !stopped.get()

    init {
        val resolvedDir = FujiLogFormatter.resolveProperty(LogArchiver.PROP_LOG_DIR, "logs", propertyOverrides)
        logDir = Path.of(resolvedDir).toAbsolutePath().normalize()
        activeLogFile = logDir.resolve(LogArchiver.LATEST_LOG_FILE)

        val maxFileSize = FujiLogFormatter.parseFileSizeBytes(
            FujiLogFormatter.resolveProperty("fuji.log.maxFileSize", "16MB", propertyOverrides),
            RollingLogFileSink.DEFAULT_MAX_FILE_SIZE_BYTES,
        )
        val maxHistory = FujiLogFormatter.resolveProperty("fuji.log.maxHistory", "14", propertyOverrides)
            .toIntOrNull()?.coerceAtLeast(1) ?: RollingLogFileSink.DEFAULT_MAX_HISTORY_DAYS
        val totalSizeCap = FujiLogFormatter.parseFileSizeBytes(
            FujiLogFormatter.resolveProperty("fuji.log.totalSizeCap", "512MB", propertyOverrides),
            RollingLogFileSink.DEFAULT_TOTAL_SIZE_CAP_BYTES,
        )

        val rootLevel = FujiLogLevel.parse(
            FujiLogFormatter.resolveProperty("fuji.log.root.level", "INFO", propertyOverrides),
        ) ?: FujiLogLevel.INFO
        val emulatorLevel = FujiLogLevel.parse(
            FujiLogFormatter.resolveProperty(LogArchiver.PROP_LOGGER_LEVEL, "DEBUG", propertyOverrides),
        ) ?: FujiLogLevel.DEBUG
        consoleThreshold = FujiLogLevel.parse(
            FujiLogFormatter.resolveProperty(LogArchiver.PROP_CONSOLE_LEVEL, "INFO", propertyOverrides),
        ) ?: FujiLogLevel.INFO
        fileThreshold = FujiLogLevel.parse(
            FujiLogFormatter.resolveProperty(LogArchiver.PROP_FILE_LEVEL, "DEBUG", propertyOverrides),
        ) ?: FujiLogLevel.DEBUG

        explicitLevels[Logger.ROOT_LOGGER_NAME] = rootLevel
        explicitLevels[LogArchiver.EMULATOR_LOGGER_NAME] = emulatorLevel
        explicitLevels[LogArchiver.HEADLESS_LOGGER_NAME] = emulatorLevel

        fileSink = RollingLogFileSink(
            logDir = logDir,
            activeFile = activeLogFile,
            maxFileSizeBytes = maxFileSize,
            maxHistoryDays = maxHistory,
            totalSizeCapBytes = totalSizeCap,
            emitFileHeader = emitFileHeader,
            zoneId = zoneId,
        )
        fileChannel = AsyncLogChannel(
            name = "fuji-log-file",
            queueSize = 1024,
            maxFlushTimeMs = 5000L,
            sink = { ev -> fileSink.write(ev) },
            flusher = { fileSink.flush() },
        )
        consoleChannel = AsyncLogChannel(
            name = "fuji-log-console",
            queueSize = 1024,
            maxFlushTimeMs = 1000L,
            sink = { ev ->
                val bytes = FujiLogFormatter.formatConsoleEvent(ev, ansiColor = consoleAnsiColor, zoneId = zoneId)
                    .toByteArray(Charsets.UTF_8)
                synchronized(targetConsoleStream) {
                    targetConsoleStream.write(bytes)
                }
            },
            flusher = {
                synchronized(targetConsoleStream) {
                    targetConsoleStream.flush()
                }
            },
        )
    }

    override fun getLogger(name: String): FujiLogger =
        loggers.computeIfAbsent(name) { FujiLogger(it, this) }

    fun getLogger(clazz: Class<*>): FujiLogger = getLogger(clazz.name)

    fun setLoggerLevel(name: String, level: FujiLogLevel) {
        explicitLevels[name] = level
    }

    fun getEffectiveLevel(name: String): FujiLogLevel {
        explicitLevels[name]?.let { return it }
        var dot = name.lastIndexOf('.')
        while (dot > 0) {
            val prefix = name.substring(0, dot)
            explicitLevels[prefix]?.let { return it }
            dot = name.lastIndexOf('.', dot - 1)
        }
        return explicitLevels[Logger.ROOT_LOGGER_NAME] ?: FujiLogLevel.INFO
    }

    fun getConsoleLevel(): FujiLogLevel = consoleThreshold

    fun setConsoleLevel(level: FujiLogLevel) {
        consoleThreshold = level
    }

    fun getFileLevel(): FujiLogLevel = fileThreshold

    fun setFileLevel(level: FujiLogLevel) {
        fileThreshold = level
    }

    fun setConsoleEnabled(enabled: Boolean) {
        consoleEnabled = enabled
    }

    fun setFileEnabled(enabled: Boolean) {
        fileEnabled = enabled
    }

    internal fun dispatch(event: FujiLogEvent) {
        FujiLogCapture.dispatch(event)
        if (stopped.get()) return
        if (fileEnabled && event.level.priority >= fileThreshold.priority) {
            fileChannel.enqueue(event)
        }
        if (consoleEnabled && event.level.priority >= consoleThreshold.priority) {
            consoleChannel.enqueue(event)
        }
    }

    fun flushAsync(maxWaitMs: Long = 200L) {
        if (stopped.get()) return
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs.coerceAtLeast(10L))
        while (System.nanoTime() < deadline) {
            if (fileChannel.pendingCount() == 0 && consoleChannel.pendingCount() == 0) {
                fileSink.flush()
                synchronized(targetConsoleStream) { targetConsoleStream.flush() }
                return
            }
            try {
                Thread.sleep(5L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        fileSink.flush()
        synchronized(targetConsoleStream) { targetConsoleStream.flush() }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        consoleChannel.close()
        fileChannel.close()
        fileSink.close()
    }

    override fun close() = stop()
}

/** SLF4J 2.0 [Logger] implementation backed by [FujiLoggerContext]. */
class FujiLogger internal constructor(
    loggerName: String,
    private val context: FujiLoggerContext,
) : LegacyAbstractLogger() {
    init {
        this.name = loggerName
    }

    private fun isLevelEnabled(level: FujiLogLevel): Boolean =
        level.priority >= context.getEffectiveLevel(name).priority

    override fun isTraceEnabled(): Boolean = isLevelEnabled(FujiLogLevel.TRACE)
    override fun isDebugEnabled(): Boolean = isLevelEnabled(FujiLogLevel.DEBUG)
    override fun isInfoEnabled(): Boolean = isLevelEnabled(FujiLogLevel.INFO)
    override fun isWarnEnabled(): Boolean = isLevelEnabled(FujiLogLevel.WARN)
    override fun isErrorEnabled(): Boolean = isLevelEnabled(FujiLogLevel.ERROR)

    override fun getFullyQualifiedCallerName(): String = FujiLogger::class.java.name

    override fun handleNormalizedLoggingCall(
        level: Slf4jLevel,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        val fujiLevel = FujiLogLevel.fromSlf4j(level)
        val formatted = when {
            messagePattern == null -> ""
            arguments == null || arguments.isEmpty() -> messagePattern
            else -> MessageFormatter.basicArrayFormat(messagePattern, arguments) ?: messagePattern
        }
        context.dispatch(
            FujiLogEvent(
                timestampMillis = System.currentTimeMillis(),
                level = fujiLevel,
                loggerName = name,
                threadName = Thread.currentThread().name,
                formattedMessage = formatted,
                throwable = throwable,
            ),
        )
    }
}
