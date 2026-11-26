package com.github.nyabanban.fuji.emulator.runtime

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Dual-channel, non-blocking asynchronous telemetry publisher (`fuji-telemetry`):
 *
 * - **Channel 1 (Real-time batched ingest)**:
 *   Accepts events from [FirmwareWatchdog], [RelaxedPeripheralFallback], and [UserActionLogger]
 *   via a bounded non-blocking [ArrayBlockingQueue] (`offer()`, zero Owner thread backpressure).
 *   A single background daemon thread (`fuji-telemetry`) lazily registers the capture session
 *   (`POST /api/v1/sessions`) and flushes gzip-compressed JSON batches (`POST /api/v1/ingest/batch`)
 *   every [flushIntervalMs] (default 5s) or whenever [batchSize] (default 64) events accumulate.
 *
 * - **Channel 2 (Close-time flush + `logs/latest.log` upload + session PATCH)**:
 *   On [close], strictly bounded by [shutdownBudgetMs] (default 3s so JVM shutdown never hangs),
 *   drains remaining queued events, uploads `<home>/logs/latest.log` via
 *   `POST /api/v1/ingest/emulator-log` (if [TelemetryConfig.uploadLogOnExit] is enabled), and
 *   finalizes the session via `PATCH /api/v1/sessions/{id}` (`FINISHED` / `CRASHED` / `HUNG`).
 */
class TelemetrySessionPublisher(
    val config: TelemetryConfig,
    private val logFile: Path,
    private val client: TelemetryHubClient = TelemetryHubClient(config),
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val shutdownBudgetMs: Long = DEFAULT_SHUTDOWN_BUDGET_MS,
    private val logger: Logger = LoggerFactory.getLogger(TelemetrySessionPublisher::class.java),
) : AutoCloseable {
    private data class QueuedEvent(
        val seq: Long,
        val wallTimeMs: Long,
        val virtualTick: Long,
        val eventType: String,
        val payload: Map<String, Any?>,
    )

    private val queue = ArrayBlockingQueue<QueuedEvent>(queueCapacity.coerceAtLeast(16))
    private val seqCounter = AtomicLong(0L)
    private val droppedCounter = AtomicLong(0L)
    private val sentEventsCounter = AtomicLong(0L)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val sessionLock = Any()

    private val startedAt: Instant = Instant.now()
    @Volatile private var activeSessionId: String = UUID.randomUUID().toString()
    @Volatile private var sessionCreatedOnHub: Boolean = false
    @Volatile private var hubUnreachable: Boolean = false
    @Volatile private var stallDetected: Boolean = false
    @Volatile private var rawFinalStatus: String? = null
    @Volatile private var worker: Thread? = null

    val sessionId: String get() = activeSessionId
    val droppedEvents: Long get() = droppedCounter.get()
    val sentEvents: Long get() = sentEventsCounter.get()
    val queuedEvents: Int get() = queue.size

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        val t = Thread({ runWorkerLoop() }, "fuji-telemetry").apply {
            isDaemon = true
        }
        worker = t
        t.start()
    }

    /** Non-blocking O(1) enqueue for [FirmwareWatchdog] boot milestones. */
    fun recordMilestone(
        stage: FirmwareStage,
        elapsedSec: Double,
        attempts: Long,
        virtualTick: Long,
        frames: Long,
    ) {
        enqueue(
            eventType = EVENT_BOOT_AND_CRASH,
            virtualTick = virtualTick,
            payload = linkedMapOf(
                "kind" to "MILESTONE",
                "stageName" to stage.label,
                "elapsedSec" to elapsedSec,
                "attempts" to attempts,
                "frames" to frames,
                "message" to String.format(
                    Locale.ROOT,
                    "Milestone %s reached in %.2fs (attempts=%d, tick=%d, frames=%d)",
                    stage.label,
                    elapsedSec,
                    attempts,
                    virtualTick,
                    frames,
                ),
            ),
        )
    }

    /** Non-blocking O(1) enqueue for [FirmwareWatchdog] owner/stage stall warnings. */
    fun recordStall(
        stallKind: String,
        stallSeconds: Double,
        currentStage: String,
        stagesPassed: String,
        mips: Double,
        attempts: Long,
        virtualTick: Long,
        frames: Long,
        lastButton: String,
        status: String,
        message: String,
    ) {
        stallDetected = true
        enqueue(
            eventType = EVENT_BOOT_AND_CRASH,
            virtualTick = virtualTick,
            payload = linkedMapOf(
                "kind" to "WATCHDOG_STALL",
                "stallKind" to stallKind,
                "stageName" to currentStage,
                "stagesPassed" to stagesPassed,
                "stallSeconds" to stallSeconds,
                "mips" to mips,
                "attempts" to attempts,
                "frames" to frames,
                "lastButton" to lastButton,
                "status" to status,
                "message" to message,
            ),
        )
    }

    /** Non-blocking O(1) enqueue for deduplicated first `[RELAXED/MMIO]` fallback hits. */
    fun recordMmioFallback(
        source: String,
        write: Boolean,
        address: Long,
        size: Int,
        value: Long,
        pc: Long,
        tick: Long,
        stage: String,
        reason: String?,
    ) {
        val payload = linkedMapOf<String, Any?>(
            "category" to "UNMODELED_MMIO",
            "truthLevel" to "SIM",
            "source" to source,
            "op" to if (write) "WRITE" else "READ",
            "addressStart" to (address and 0xffffffffL),
            "addressHex" to RelaxedPeripheralFallback.hex32(address),
            "sizeBytes" to size,
            "valueHex" to RelaxedPeripheralFallback.hexValue(value),
            "guestPcHex" to RelaxedPeripheralFallback.hex32(pc),
            "stageName" to stage,
            "hits" to 1,
        )
        if (!reason.isNullOrBlank()) {
            payload["reason"] = RelaxedPeripheralFallback.sanitizeReason(reason)
        }
        enqueue(EVENT_HARDWARE_PROBE, tick, payload)
    }

    /** Non-blocking O(1) enqueue for deduplicated first `[RELAXED/FLASH]` operations/refusals. */
    fun recordFlashEvent(
        op: String,
        flashOffset: Long,
        sector: Long,
        size: Long,
        refused: Boolean,
        pc: Long,
        tick: Long,
        stage: String,
        reason: String?,
    ) {
        val payload = linkedMapOf<String, Any?>(
            "category" to "FLASH_OP",
            "truthLevel" to "SIM",
            "op" to op,
            "addressStart" to (flashOffset and 0xffffffffL),
            "addressHex" to RelaxedPeripheralFallback.hex32(flashOffset),
            "sectorOffset" to (sector and 0xffffffffL),
            "sectorHex" to RelaxedPeripheralFallback.hex32(sector),
            "sizeBytes" to size,
            "refused" to refused,
            "guestPcHex" to RelaxedPeripheralFallback.hex32(pc),
            "stageName" to stage,
            "hits" to 1,
        )
        if (!reason.isNullOrBlank()) {
            payload["reason"] = RelaxedPeripheralFallback.sanitizeReason(reason)
        }
        enqueue(EVENT_HARDWARE_PROBE, tick, payload)
    }

    /** Non-blocking O(1) enqueue for `[USER/ACTION]` interactive events. */
    fun recordUserAction(
        action: String,
        componentId: String?,
        changes: Map<String, Any?>,
        stage: String,
        tick: Long,
        frame: Long,
        message: String,
    ) {
        val payload = linkedMapOf<String, Any?>(
            "action" to action,
            "stageName" to stage,
            "frame" to frame,
            "message" to message,
        )
        if (!componentId.isNullOrBlank()) payload["componentId"] = componentId
        if (changes.isNotEmpty()) payload["changes"] = LinkedHashMap(changes)
        enqueue(EVENT_USER_ACTION, tick, payload)
    }

    /** Records the emulator's terminal status string so [close] patches the right hub status. */
    fun markFinalStatus(status: String?) {
        if (!status.isNullOrBlank()) {
            rawFinalStatus = status
        }
    }

    /**
     * On-demand upload of `<home>/logs/latest.log` exposed via `ControlServer`
     * (`POST /api/telemetry/upload-log`). Never blocks the Owner thread.
     */
    fun uploadCurrentLogNow(notes: String? = null, timeoutMs: Long = 5_000L): Map<String, Any?> {
        if (!Files.isRegularFile(logFile)) {
            return mapOf(
                "uploaded" to false,
                "sessionId" to activeSessionId,
                "error" to "log file not found",
            )
        }
        val sizeBytes = runCatching { Files.size(logFile) }.getOrDefault(0L)
        if (sizeBytes <= 0L) {
            return mapOf(
                "uploaded" to false,
                "sessionId" to activeSessionId,
                "error" to "log file is empty",
            )
        }
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(500L))
        ensureSessionCreated(minOf(1_500L, remainingMs(deadlineNanos).coerceAtLeast(250L)))
        val outcome = client.uploadEmulatorLog(
            logFile = logFile,
            sessionId = activeSessionId,
            notes = notes ?: "manual-control-api-upload",
            timeoutMs = remainingMs(deadlineNanos).coerceAtLeast(250L),
        ) ?: return mapOf(
            "uploaded" to false,
            "sessionId" to activeSessionId,
            "sizeBytes" to sizeBytes,
            "error" to "telemetry hub unavailable or rejected upload",
        )
        return linkedMapOf(
            "uploaded" to true,
            "sessionId" to (outcome.sessionId ?: activeSessionId),
            "filename" to (outcome.filename ?: logFile.fileName?.toString()),
            "sizeBytes" to sizeBytes,
            "totalLines" to outcome.totalLines,
            "accepted" to outcome.accepted,
            "deduplicated" to outcome.deduplicated,
            "rejected" to outcome.rejected,
            "mmioEvents" to outcome.mmioEvents,
            "flashEvents" to outcome.flashEvents,
            "userActionEvents" to outcome.userActionEvents,
            "watchdogAndMilestoneEvents" to outcome.watchdogAndMilestoneEvents,
        )
    }

    private fun enqueue(eventType: String, virtualTick: Long, payload: Map<String, Any?>) {
        if (closed.get()) return
        val event = QueuedEvent(
            seq = seqCounter.incrementAndGet(),
            wallTimeMs = System.currentTimeMillis(),
            virtualTick = virtualTick,
            eventType = eventType,
            payload = payload,
        )
        if (!queue.offer(event)) {
            droppedCounter.incrementAndGet()
            return
        }
        if (queue.size >= batchSize) {
            worker?.let { LockSupport.unpark(it) }
        }
    }

    private fun runWorkerLoop() {
        try {
            ensureSessionCreated(minOf(1_500L, flushIntervalMs.coerceAtLeast(500L)))
            var lastFlushNanos = System.nanoTime()
            val flushIntervalNanos = TimeUnit.MILLISECONDS.toNanos(flushIntervalMs.coerceAtLeast(50L))
            while (!closed.get()) {
                val now = System.nanoTime()
                val shouldFlush = queue.size >= batchSize ||
                    (queue.isNotEmpty() && (now - lastFlushNanos) >= flushIntervalNanos)
                if (shouldFlush) {
                    flushBatch(maxEvents = maxOf(batchSize * 2, 128), timeoutMs = TelemetryHubClient.DEFAULT_REQUEST_TIMEOUT_MS)
                    lastFlushNanos = System.nanoTime()
                    continue
                }
                val waitNanos = if (queue.isEmpty()) {
                    minOf(flushIntervalNanos, TimeUnit.MILLISECONDS.toNanos(250L))
                } else {
                    (flushIntervalNanos - (now - lastFlushNanos)).coerceIn(
                        TimeUnit.MILLISECONDS.toNanos(20L),
                        TimeUnit.MILLISECONDS.toNanos(250L),
                    )
                }
                LockSupport.parkNanos(waitNanos)
            }
        } catch (error: Throwable) {
            logger.warn("[Telemetry] Worker loop degraded cleanly: {}: {}", error.javaClass.simpleName, error.message ?: "error")
        }
    }

    private fun ensureSessionCreated(timeoutMs: Long): Boolean {
        if (sessionCreatedOnHub) return true
        synchronized(sessionLock) {
            if (sessionCreatedOnHub) return true
            val createdId = client.createSession(
                sessionId = activeSessionId,
                startedAt = startedAt,
                timeoutMs = timeoutMs,
            )
            if (createdId == null) {
                hubUnreachable = true
                return false
            }
            activeSessionId = createdId
            sessionCreatedOnHub = true
            hubUnreachable = false
            return true
        }
    }

    private fun flushBatch(maxEvents: Int, timeoutMs: Long): Boolean {
        val drained = ArrayList<QueuedEvent>(minOf(queue.size, maxEvents).coerceAtLeast(1))
        queue.drainTo(drained, maxEvents)
        if (drained.isEmpty()) return true
        if (!ensureSessionCreated(minOf(timeoutMs, 1_000L))) {
            // Re-queue if capacity permits so transient startup outages recover on next flush.
            for (event in drained) {
                if (!queue.offer(event)) droppedCounter.incrementAndGet()
            }
            return false
        }
        val sid = activeSessionId
        val envelopes = drained.map { event ->
            linkedMapOf<String, Any?>(
                "schemaVersion" to SCHEMA_VERSION,
                "sessionId" to sid,
                "sourceType" to config.sourceType,
                "seq" to event.seq,
                "wallTimeMs" to event.wallTimeMs,
                "virtualTick" to event.virtualTick,
                "eventType" to event.eventType,
                "payload" to event.payload,
            )
        }
        val outcome = client.ingestBatch(envelopes, timeoutMs = timeoutMs)
        if (outcome != null) {
            hubUnreachable = false
            sentEventsCounter.addAndGet(drained.size.toLong())
            return true
        }
        hubUnreachable = true
        return false
    }

    override fun close() {
        close(null)
    }

    fun close(finalStatusOverride: String?) {
        if (!closed.compareAndSet(false, true)) return
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownBudgetMs.coerceAtLeast(250L))

        val t = worker
        worker = null
        if (t != null && Thread.currentThread() !== t) {
            LockSupport.unpark(t)
            val joinMs = minOf(600L, remainingMs(deadlineNanos))
            if (joinMs > 0L) {
                runCatching { t.join(joinMs) }
            }
            if (t.isAlive) {
                t.interrupt()
                val secondJoinMs = minOf(200L, remainingMs(deadlineNanos))
                if (secondJoinMs > 0L) {
                    runCatching { t.join(secondJoinMs) }
                }
            }
        }

        // Fast-exit when the hub was already unreachable during session creation so offline shutdown never hangs.
        if (!sessionCreatedOnHub && hubUnreachable) {
            logger.debug(
                "[Telemetry] Skipping close-time flush/upload for unreachable hub (session={}, queued={}, dropped={})",
                activeSessionId,
                queue.size,
                droppedCounter.get(),
            )
            return
        }

        runCatching {
            // 1. Flush remaining queued events within shutdown budget.
            var flushOk = true
            while (flushOk && queue.isNotEmpty() && remainingMs(deadlineNanos) >= 200L) {
                val reqTimeout = minOf(remainingMs(deadlineNanos) - 100L, 1_000L).coerceAtLeast(150L)
                flushOk = flushBatch(maxEvents = MAX_CLOSE_BATCH_SIZE, timeoutMs = reqTimeout)
            }

            // 2. Upload <home>/logs/latest.log (Channel 2) if enabled and hub has not already proven unreachable.
            if (config.uploadLogOnExit && !(hubUnreachable && !sessionCreatedOnHub) && remainingMs(deadlineNanos) >= 250L) {
                val hasLog = runCatching { Files.isRegularFile(logFile) && Files.size(logFile) > 0L }.getOrDefault(false)
                if (hasLog) {
                    val sessionReady = if (!sessionCreatedOnHub && remainingMs(deadlineNanos) >= 400L) {
                        ensureSessionCreated(minOf(500L, remainingMs(deadlineNanos) / 2))
                    } else {
                        sessionCreatedOnHub
                    }
                    if (sessionReady || !hubUnreachable) {
                        val uploadTimeout = minOf(remainingMs(deadlineNanos) - 150L, 1_500L).coerceAtLeast(200L)
                        client.uploadEmulatorLog(
                            logFile = logFile,
                            sessionId = activeSessionId,
                            notes = "auto-exit-upload",
                            timeoutMs = uploadTimeout,
                        )?.sessionId?.let { returnedId ->
                            if (!sessionCreatedOnHub && returnedId.isNotBlank()) {
                                activeSessionId = returnedId
                                sessionCreatedOnHub = true
                                hubUnreachable = false
                            }
                        }
                    }
                }
            }

            // 3. Finalize session status via PATCH /api/v1/sessions/{id}.
            if (sessionCreatedOnHub && remainingMs(deadlineNanos) >= 150L) {
                val hubStatus = finalStatusOverride ?: resolveHubStatus(rawFinalStatus, stallDetected)
                val dropped = droppedCounter.get()
                val notes = if (dropped > 0L) "droppedEvents=$dropped" else null
                client.updateSession(
                    sessionId = activeSessionId,
                    status = hubStatus,
                    endedAt = Instant.now(),
                    notes = notes,
                    timeoutMs = remainingMs(deadlineNanos).coerceAtLeast(150L),
                )
            }
        }.onFailure { error ->
            logger.warn("[Telemetry] Close-time cleanup degraded cleanly: {}: {}", error.javaClass.simpleName, error.message ?: "error")
        }
        logger.debug(
            "[Telemetry] Closed session {} (sent={}, dropped={}, hubCreated={})",
            activeSessionId,
            sentEventsCounter.get(),
            droppedCounter.get(),
            sessionCreatedOnHub,
        )
    }

    private fun remainingMs(deadlineNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0L))

    companion object {
        const val SCHEMA_VERSION = 1
        const val DEFAULT_QUEUE_CAPACITY = 2048
        const val DEFAULT_BATCH_SIZE = 64
        const val DEFAULT_FLUSH_INTERVAL_MS = 5_000L
        const val DEFAULT_SHUTDOWN_BUDGET_MS = 3_000L
        private const val MAX_CLOSE_BATCH_SIZE = 512

        const val EVENT_HARDWARE_PROBE = "HARDWARE_PROBE"
        const val EVENT_BOOT_AND_CRASH = "BOOT_AND_CRASH"
        const val EVENT_USER_ACTION = "USER_ACTION"

        fun resolveHubStatus(rawStatus: String?, stallDetected: Boolean): String {
            val lower = rawStatus?.lowercase(Locale.ROOT).orEmpty()
            return when {
                lower.contains("crash") || lower.contains("fault") || lower.contains("panic") || lower.contains("error") -> "CRASHED"
                stallDetected || lower.contains("hung") || lower.contains("stall") || lower.contains("timeout") -> "HUNG"
                else -> "FINISHED"
            }
        }
    }
}
