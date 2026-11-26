package com.github.nyabanban.fuji.emulator.runtime

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asynchronous read-only firmware milestone & stall watchdog (`fuji-watchdog`).
 *
 * Polls [FirmwareTelemetry] without acquiring the emulation owner lock:
 * - Emits one crisp `INFO` line the first time each [FirmwareStage] milestone is crossed.
 * - Emits a rate-limited Paper-style `WARN` report when either:
 *   1. The emulation owner thread heartbeat stalls beyond [ownerHeartbeatTimeoutNanos]
 *      (includes `ownerThread.stackTrace` to diagnose deadlocks/JNI hangs), or
 *   2. Boot progression stalls before `STAGE_5_FIRST_FRAME` for longer than
 *      [stageStallTimeoutNanos] while running and unpaused.
 */
class FirmwareWatchdog(
    private val telemetry: FirmwareTelemetry,
    private val ownerThreadProvider: () -> Thread? = { null },
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val ownerHeartbeatTimeoutNanos: Long = DEFAULT_OWNER_HEARTBEAT_TIMEOUT_NANOS,
    private val stageStallTimeoutNanos: Long = DEFAULT_STAGE_STALL_TIMEOUT_NANOS,
    private val stallReportCooldownNanos: Long = DEFAULT_STALL_COOLDOWN_NANOS,
    private val heartbeatLogIntervalNanos: Long = DEFAULT_HEARTBEAT_LOG_INTERVAL_NANOS,
    private val clockNanos: () -> Long = System::nanoTime,
    private val logger: Logger = LoggerFactory.getLogger(FirmwareWatchdog::class.java),
    private val infoSink: ((String) -> Unit)? = null,
    private val warnSink: ((String) -> Unit)? = null,
    private val debugSink: ((String) -> Unit)? = null,
    private val summaryProvider: (() -> String?)? = null,
    private val telemetryPublisher: TelemetrySessionPublisher? = null,
) : AutoCloseable {
    companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 500L
        val DEFAULT_OWNER_HEARTBEAT_TIMEOUT_NANOS: Long = TimeUnit.SECONDS.toNanos(8L)
        val DEFAULT_STAGE_STALL_TIMEOUT_NANOS: Long = TimeUnit.SECONDS.toNanos(30L)
        val DEFAULT_STALL_COOLDOWN_NANOS: Long = TimeUnit.SECONDS.toNanos(15L)
        val DEFAULT_HEARTBEAT_LOG_INTERVAL_NANOS: Long = TimeUnit.SECONDS.toNanos(10L)
    }

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val stateLock = Any()
    @Volatile private var worker: Thread? = null

    private var announcedMask: Int = 0
    private var lastStageAdvanceNanos: Long = 0L
    private var lastPollNanos: Long = 0L
    private var lastAttempts: Long = 0L
    private var lastOwnerStallReportNanos: Long = 0L
    private var lastStageStallReportNanos: Long = 0L
    private var lastHeartbeatDebugLogNanos: Long = 0L
    private var lastObservedStartedAtNanos: Long = 0L
    private var bootStartAnchorNanos: Long = 0L

    /**
     * Resets milestone tracking and stall timers for a Sub-MCU cold-reset wake so the new boot
     * gets a fresh stall window and announces `STAGE_1..STAGE_6` milestones cleanly.
     */
    fun resetForColdReset(nowNanos: Long = clockNanos()) {
        synchronized(stateLock) {
            announcedMask = 0
            lastStageAdvanceNanos = nowNanos
            lastPollNanos = nowNanos
            lastAttempts = 0L
            lastOwnerStallReportNanos = 0L
            lastStageStallReportNanos = 0L
            lastHeartbeatDebugLogNanos = 0L
            lastObservedStartedAtNanos = telemetry.startedAtNanos
            bootStartAnchorNanos = nowNanos
        }
    }

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        val now = clockNanos()
        synchronized(stateLock) {
            lastStageAdvanceNanos = now
            lastPollNanos = now
            lastAttempts = telemetry.attempts
            lastObservedStartedAtNanos = telemetry.startedAtNanos
            bootStartAnchorNanos = if (telemetry.startedAtNanos > 0L) telemetry.startedAtNanos else now
        }
        val t = Thread({
            while (!closed.get()) {
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (_: InterruptedException) {
                    if (closed.get()) break
                }
                if (closed.get()) break
                runCatching { pollOnce(clockNanos()) }
            }
        }, "fuji-watchdog").apply {
            isDaemon = true
        }
        worker = t
        t.start()
    }

    /** Executes one deterministic observation cycle (used by both the daemon loop and unit tests). */
    fun pollOnce(nowNanos: Long = clockNanos()) {
        synchronized(stateLock) {
            pollOnceLocked(nowNanos)
        }
    }

    private fun pollOnceLocked(nowNanos: Long) {
        val stageMask = telemetry.stageMask
        val attempts = telemetry.attempts
        val virtualTick = telemetry.virtualTick
        val frames = telemetry.publishedFrames
        val heartbeat = telemetry.ownerHeartbeatNanos
        val running = telemetry.running
        val paused = telemetry.paused
        val status = telemetry.status
        val lastButton = telemetry.lastButtonEvent
        val startedAt = telemetry.startedAtNanos

        val startedAtChanged =
            startedAt > 0L && lastObservedStartedAtNanos > 0L && startedAt != lastObservedStartedAtNanos
        val stageMaskCleared = stageMask == 0 && announcedMask != 0
        if (startedAtChanged || stageMaskCleared) {
            // If startedAtNanos itself advanced to a new boot timestamp, anchor on it; if only
            // stageMask cleared via resetForColdReset() while startedAtNanos is still from Boot #1,
            // anchor on nowNanos so the stall window and milestone elapsed time start fresh.
            val anchor = if (startedAtChanged && startedAt <= nowNanos) startedAt else nowNanos
            announcedMask = 0
            lastStageAdvanceNanos = anchor
            lastPollNanos = anchor
            lastAttempts = attempts
            lastOwnerStallReportNanos = 0L
            lastStageStallReportNanos = 0L
            lastHeartbeatDebugLogNanos = 0L
            lastObservedStartedAtNanos = startedAt
            bootStartAnchorNanos = anchor
        } else if (lastObservedStartedAtNanos == 0L && startedAt > 0L) {
            lastObservedStartedAtNanos = startedAt
            if (bootStartAnchorNanos == 0L) bootStartAnchorNanos = startedAt
        }

        if (lastPollNanos == 0L) {
            lastPollNanos = if (startedAt > 0L) startedAt else nowNanos
            lastStageAdvanceNanos = lastPollNanos
            lastAttempts = attempts
            if (bootStartAnchorNanos == 0L) bootStartAnchorNanos = lastPollNanos
        }

        val deltaNanos = (nowNanos - lastPollNanos).coerceAtLeast(0L)
        val deltaAttempts = (attempts - lastAttempts).coerceAtLeast(0L)
        val mips = if (deltaNanos > 0L) (deltaAttempts.toDouble() / (deltaNanos.toDouble() / 1_000_000_000.0)) / 1_000_000.0 else 0.0
        lastPollNanos = nowNanos
        lastAttempts = attempts

        val newlyReached = stageMask and announcedMask.inv()
        if (newlyReached != 0) {
            val effectiveStart = if (bootStartAnchorNanos > 0L) bootStartAnchorNanos else startedAt
            val elapsedSec = if (effectiveStart > 0L && nowNanos >= effectiveStart) {
                (nowNanos - effectiveStart).toDouble() / 1_000_000_000.0
            } else {
                0.0
            }
            for (stage in FirmwareStage.entries) {
                if ((newlyReached and stage.bit) != 0) {
                    announcedMask = announcedMask or stage.bit
                    emitInfo(
                        String.format(
                            Locale.ROOT,
                            "[Watchdog] Milestone %s reached in %.2fs (attempts=%d, tick=%d, frames=%d)",
                            stage.label,
                            elapsedSec,
                            attempts,
                            virtualTick,
                            frames,
                        ),
                    )
                    telemetryPublisher?.recordMilestone(stage, elapsedSec, attempts, virtualTick, frames)
                }
            }
            lastStageAdvanceNanos = nowNanos
            lastStageStallReportNanos = 0L
        }

        if (!running || paused) {
            lastStageAdvanceNanos = nowNanos
            return
        }

        val heartbeatAge = if (heartbeat > 0L && nowNanos >= heartbeat) nowNanos - heartbeat else 0L
        if (heartbeat > 0L && heartbeatAge >= ownerHeartbeatTimeoutNanos) {
            if (lastOwnerStallReportNanos == 0L || (nowNanos - lastOwnerStallReportNanos) >= stallReportCooldownNanos) {
                lastOwnerStallReportNanos = nowNanos
                val stackSummary = formatOwnerStack(ownerThreadProvider())
                val stallSeconds = heartbeatAge.toDouble() / 1_000_000_000.0
                val report = buildStallReport(
                    kind = "OWNER HEARTBEAT STALL",
                    stallSeconds = stallSeconds,
                    stageMask = stageMask,
                    mips = mips,
                    attempts = attempts,
                    virtualTick = virtualTick,
                    frames = frames,
                    lastButton = lastButton,
                    status = status,
                    ownerStack = stackSummary,
                )
                emitWarn(report)
                telemetryPublisher?.recordStall(
                    stallKind = "OWNER HEARTBEAT STALL",
                    stallSeconds = stallSeconds,
                    currentStage = FirmwareStage.highestReached(stageMask)?.label ?: "NONE",
                    stagesPassed = FirmwareStage.formatReached(stageMask),
                    mips = mips,
                    attempts = attempts,
                    virtualTick = virtualTick,
                    frames = frames,
                    lastButton = if (lastButton.isBlank()) "none" else lastButton,
                    status = status,
                    message = report,
                )
            }
            return
        }

        val reachedDisplay = (stageMask and FirmwareStage.STAGE_5_FIRST_FRAME.bit) != 0
        val stageAge = if (nowNanos >= lastStageAdvanceNanos) nowNanos - lastStageAdvanceNanos else 0L
        if (!reachedDisplay && stageAge >= stageStallTimeoutNanos) {
            if (lastStageStallReportNanos == 0L || (nowNanos - lastStageStallReportNanos) >= stallReportCooldownNanos) {
                lastStageStallReportNanos = nowNanos
                val stallSeconds = stageAge.toDouble() / 1_000_000_000.0
                val report = buildStallReport(
                    kind = "STAGE PROGRESSION STALL",
                    stallSeconds = stallSeconds,
                    stageMask = stageMask,
                    mips = mips,
                    attempts = attempts,
                    virtualTick = virtualTick,
                    frames = frames,
                    lastButton = lastButton,
                    status = status,
                    ownerStack = null,
                )
                emitWarn(report)
                telemetryPublisher?.recordStall(
                    stallKind = "STAGE PROGRESSION STALL",
                    stallSeconds = stallSeconds,
                    currentStage = FirmwareStage.highestReached(stageMask)?.label ?: "NONE",
                    stagesPassed = FirmwareStage.formatReached(stageMask),
                    mips = mips,
                    attempts = attempts,
                    virtualTick = virtualTick,
                    frames = frames,
                    lastButton = if (lastButton.isBlank()) "none" else lastButton,
                    status = status,
                    message = report,
                )
            }
            return
        }

        if (heartbeatLogIntervalNanos > 0L) {
            if (lastHeartbeatDebugLogNanos == 0L) {
                lastHeartbeatDebugLogNanos = nowNanos
            } else if ((nowNanos - lastHeartbeatDebugLogNanos) >= heartbeatLogIntervalNanos) {
                lastHeartbeatDebugLogNanos = nowNanos
                val currentStage = FirmwareStage.highestReached(stageMask)?.label ?: "NONE"
                emitDebug(
                    String.format(
                        Locale.ROOT,
                        "[WATCHDOG/HEARTBEAT] stage=%s mips=%.2f attempts=%d tick=%d frames=%d status=%s",
                        currentStage,
                        mips,
                        attempts,
                        virtualTick,
                        frames,
                        status,
                    ),
                )
            }
        }
    }

    private fun buildStallReport(
        kind: String,
        stallSeconds: Double,
        stageMask: Int,
        mips: Double,
        attempts: Long,
        virtualTick: Long,
        frames: Long,
        lastButton: String,
        status: String,
        ownerStack: String?,
    ): String {
        val currentStage = FirmwareStage.highestReached(stageMask)?.label ?: "NONE"
        val stagesPassed = FirmwareStage.formatReached(stageMask)
        val buttonPart = if (lastButton.isBlank()) "none" else lastButton
        val header = String.format(
            Locale.ROOT,
            "[Watchdog] %s (%.1fs): currentStage=%s, stagesPassed=[%s], mips=%.2f, attempts=%d, tick=%d, frames=%d, lastButton=%s, status=%s",
            kind,
            stallSeconds,
            currentStage,
            stagesPassed,
            mips,
            attempts,
            virtualTick,
            frames,
            buttonPart,
            status,
        )
        val extraSummary = summaryProvider?.let { provider -> runCatching { provider() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        val sb = StringBuilder(header)
        if (!ownerStack.isNullOrBlank()) {
            sb.append('\n').append(ownerStack)
        }
        if (extraSummary != null) {
            sb.append('\n').append(extraSummary)
        }
        return sb.toString()
    }

    private fun formatOwnerStack(thread: Thread?): String {
        if (thread == null) return "Owner thread stack: unavailable"
        val trace = thread.stackTrace
        if (trace.isEmpty()) return "Owner thread '${thread.name}' state=${thread.state} (empty stack)"
        val sb = StringBuilder()
        sb.append("Owner thread '").append(thread.name).append("' state=").append(thread.state).append(':')
        val limit = minOf(trace.size, 16)
        for (i in 0 until limit) {
            sb.append("\n  at ").append(trace[i])
        }
        return sb.toString()
    }

    private fun emitInfo(message: String) {
        val sink = infoSink
        if (sink != null) sink(message) else logger.info(message)
    }

    private fun emitWarn(message: String) {
        val sink = warnSink
        if (sink != null) sink(message) else logger.warn(message)
    }

    private fun emitDebug(message: String) {
        val sink = debugSink
        if (sink != null) sink(message) else logger.debug(message)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val t = worker
        worker = null
        if (t != null) {
            t.interrupt()
            if (Thread.currentThread() !== t) {
                runCatching { t.join(2_000L) }
            }
        }
    }
}
