package com.github.nyabanban.fuji.emulator.runtime

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spark / Paper-style asynchronous sampling performance profiler (`fuji-profiler`).
 *
 * Design invariants:
 * - Zero locks shared with the emulation owner thread: the owner thread is the sole writer of
 *   primitive `@Volatile` slice counters and the fixed-size 4 KiB MMIO page table in
 *   [FirmwareTelemetry], while `AsyncPerfProfiler` captures monotonic baseline snapshots on
 *   [start] and computes deltas passively.
 * - Default OFF (`telemetry.perfProfilingEnabled == false`), adding zero allocations and zero
 *   determinism impact in `discovery` and `strict` modes.
 * - Periodically samples `ownerThread.stackTrace` (host Kotlin/JVM self & inclusive hotspots)
 *   alongside lock-free guest `currentPc`, `currentCore`, `currentPhase`, and `stageMask`.
 */
class AsyncPerfProfiler(
    private val telemetry: FirmwareTelemetry,
    private val ownerThreadProvider: () -> Thread? = { null },
    private val defaultSampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
    private val ticksPerRealSecond: Double = DEFAULT_TICKS_PER_REAL_SECOND,
    private val topN: Int = DEFAULT_TOP_N,
    private val clockNanos: () -> Long = System::nanoTime,
    private val pageNameResolver: ((Long) -> String?)? = null,
    private val logger: Logger = LoggerFactory.getLogger(AsyncPerfProfiler::class.java),
    private val infoSink: ((String) -> Unit)? = null,
) : AutoCloseable {
    companion object {
        const val DEFAULT_SAMPLE_INTERVAL_MS: Long = 20L
        const val MIN_SAMPLE_INTERVAL_MS: Long = 5L
        const val MAX_SAMPLE_INTERVAL_MS: Long = 1000L
        const val DEFAULT_TOP_N: Int = 15

        /**
         * Calibrated from GFX100S 2.13 `GXUP0004.DAT` `kernel.bin` `0x5A920..0x5A9FC`:
         * hardware `soc-timer` reference clock constant = 54,000,000 Hz (54.00 MHz),
         * matching 10 ms RTOS tick reload `540,000` (`ch7`) and 2.5 ms reload `135,000` (`ch6`).
         */
        const val DEFAULT_TICKS_PER_REAL_SECOND: Double = 54_000_000.0

        private val FILE_TS_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)
    }

    private data class CounterSnapshot(
        val wallNanos: Long,
        val attempts: Long,
        val virtualTick: Long,
        val publishedFrames: Long,
        val cpuNativeNanos: Long,
        val mmioUpcallNanos: Long,
        val wfiJumpNanos: Long,
        val wfiJumpCount: Long,
        val wfiSkippedTicks: Long,
        val sliceCount: Long,
        val sliceExecutedInstructions: Long,
        val mmioCalls: Long,
        val mmioReadCalls: Long,
        val mmioWriteCalls: Long,
        val pageTable: FirmwareTelemetry.MmioPageTableSnapshot,
    )

    private val profilerLock = Any()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    @Volatile private var activeSampleIntervalMs: Long = defaultSampleIntervalMs

    private var baseline: CounterSnapshot? = null
    private var frozenEnd: CounterSnapshot? = null
    private var totalSamples: Long = 0L
    private var activeOwnerSamples: Long = 0L
    private val phaseSamples = LongArray(5)
    private val stageSamples = LongArray(FirmwareStage.entries.size + 1)
    private val coreSamples = LongArray(4)
    private val hostSelfFrames = HashMap<String, Long>()
    private val hostInclusiveFrames = HashMap<String, Long>()
    private val guestPcSamples = HashMap<Long, Long>()

    fun isRunning(): Boolean = running.get()

    /**
     * Starts (or restarts) asynchronous profiling without touching or locking the owner thread.
     * Returns an initial status payload suitable for HTTP responses.
     */
    @Synchronized
    fun start(intervalMs: Long = defaultSampleIntervalMs): Map<String, Any?> {
        require(intervalMs in MIN_SAMPLE_INTERVAL_MS..MAX_SAMPLE_INTERVAL_MS) {
            "sampleIntervalMs must be in $MIN_SAMPLE_INTERVAL_MS..$MAX_SAMPLE_INTERVAL_MS"
        }
        check(!closed.get()) { "AsyncPerfProfiler is closed" }
        stopWorkerOnly()
        synchronized(profilerLock) {
            activeSampleIntervalMs = intervalMs
            totalSamples = 0L
            activeOwnerSamples = 0L
            phaseSamples.fill(0L)
            stageSamples.fill(0L)
            coreSamples.fill(0L)
            hostSelfFrames.clear()
            hostInclusiveFrames.clear()
            guestPcSamples.clear()
            frozenEnd = null
            baseline = captureCounters(clockNanos())
        }
        telemetry.perfProfilingEnabled = true
        running.set(true)
        val t = Thread({
            while (running.get() && !closed.get()) {
                try {
                    Thread.sleep(activeSampleIntervalMs)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get() || closed.get()) break
                runCatching { sampleOnce(clockNanos()) }
            }
        }, "fuji-profiler").apply {
            isDaemon = true
        }
        worker = t
        t.start()
        return linkedMapOf(
            "active" to true,
            "sampleIntervalMs" to activeSampleIntervalMs,
            "ticksPerRealSecond" to ticksPerRealSecond,
        )
    }

    /**
     * Stops the sampling daemon, disables hot-path telemetry collection, freezes counters,
     * and returns the final profiling report map.
     */
    @Synchronized
    fun stop(): Map<String, Any?> {
        val wasRunning = running.getAndSet(false)
        telemetry.perfProfilingEnabled = false
        stopWorkerOnly()
        synchronized(profilerLock) {
            if (wasRunning && baseline != null) {
                frozenEnd = captureCounters(clockNanos())
            }
        }
        return reportMap()
    }

    /**
     * Executes one deterministic lock-free sample (used by both the daemon loop and unit tests).
     * Never acquires the owner lock or mutates guest execution.
     */
    fun sampleOnce(nowNanos: Long = clockNanos()) {
        val thread = ownerThreadProvider()
        val trace = thread?.stackTrace
        val pc = telemetry.currentPc
        val core = telemetry.currentCore
        val phase = telemetry.currentPhase
        val stageMask = telemetry.stageMask
        val isActive = telemetry.running && !telemetry.paused

        synchronized(profilerLock) {
            if (baseline == null) {
                baseline = captureCounters(nowNanos)
            }
            totalSamples += 1L
            if (isActive) {
                activeOwnerSamples += 1L
            }
            val safePhase = if (phase in phaseSamples.indices) phase else FirmwareTelemetry.PHASE_HOST_OTHER
            phaseSamples[safePhase] += 1L

            val highestStage = FirmwareStage.highestReached(stageMask)
            val stageIdx = if (highestStage == null) 0 else highestStage.ordinal + 1
            stageSamples[stageIdx] += 1L

            if (core in 0..3) {
                coreSamples[core] += 1L
            }
            if (pc != 0L) {
                guestPcSamples[pc] = (guestPcSamples[pc] ?: 0L) + 1L
            }
            if (trace != null && trace.isNotEmpty()) {
                val relevant = extractRelevantFrames(trace)
                if (relevant.isNotEmpty()) {
                    val leaf = relevant[0]
                    hostSelfFrames[leaf] = (hostSelfFrames[leaf] ?: 0L) + 1L
                    val seen = HashSet<String>(relevant.size * 2)
                    for (frame in relevant) {
                        if (seen.add(frame)) {
                            hostInclusiveFrames[frame] = (hostInclusiveFrames[frame] ?: 0L) + 1L
                        }
                    }
                }
            }
        }
    }

    /** Produces the structured JSON-serializable profiling report (safe while running or stopped). */
    fun reportMap(): Map<String, Any?> {
        val isActive = running.get()
        val endSnap: CounterSnapshot
        val startSnap: CounterSnapshot
        val samples: Long
        val activeSamples: Long
        val phaseCopy: LongArray
        val stageCopy: LongArray
        val coreCopy: LongArray
        val selfSnapshot: List<Map.Entry<String, Long>>
        val inclusiveSnapshot: List<Map.Entry<String, Long>>
        val pcSnapshot: List<Map.Entry<Long, Long>>
        val intervalMs: Long

        synchronized(profilerLock) {
            intervalMs = activeSampleIntervalMs
            val base = baseline
            if (base == null) {
                val empty = captureCounters(clockNanos())
                startSnap = empty
                endSnap = empty
            } else {
                startSnap = base
                endSnap = if (isActive) captureCounters(clockNanos()) else frozenEnd ?: captureCounters(clockNanos())
            }
            samples = totalSamples
            activeSamples = activeOwnerSamples
            phaseCopy = phaseSamples.copyOf()
            stageCopy = stageSamples.copyOf()
            coreCopy = coreSamples.copyOf()
            selfSnapshot = hostSelfFrames.entries.sortedByDescending { it.value }.take(topN)
            inclusiveSnapshot = hostInclusiveFrames.entries.sortedByDescending { it.value }.take(topN)
            pcSnapshot = guestPcSamples.entries.sortedByDescending { it.value }.take(topN)
        }

        val wallNanos = (endSnap.wallNanos - startSnap.wallNanos).coerceAtLeast(0L)
        val wallSeconds = wallNanos / 1_000_000_000.0
        val attemptsDelta = (endSnap.attempts - startSnap.attempts).coerceAtLeast(0L)
        val virtualTicksDelta = (endSnap.virtualTick - startSnap.virtualTick).coerceAtLeast(0L)
        val framesDelta = (endSnap.publishedFrames - startSnap.publishedFrames).coerceAtLeast(0L)
        val virtualSeconds = if (ticksPerRealSecond > 0.0) virtualTicksDelta / ticksPerRealSecond else 0.0
        val rtr = if (wallSeconds > 0.0) virtualSeconds / wallSeconds else 0.0
        val avgMips = if (wallSeconds > 0.0) (attemptsDelta / wallSeconds) / 1_000_000.0 else 0.0
        val effectiveVirtualMips = if (wallSeconds > 0.0) (virtualTicksDelta / wallSeconds) / 1_000_000.0 else 0.0

        val cpuNativeNanos = (endSnap.cpuNativeNanos - startSnap.cpuNativeNanos).coerceAtLeast(0L)
        val mmioUpcallNanos = (endSnap.mmioUpcallNanos - startSnap.mmioUpcallNanos).coerceAtLeast(0L)
        val wfiJumpNanos = (endSnap.wfiJumpNanos - startSnap.wfiJumpNanos).coerceAtLeast(0L)
        val wfiJumpCount = (endSnap.wfiJumpCount - startSnap.wfiJumpCount).coerceAtLeast(0L)
        val wfiSkippedTicks = (endSnap.wfiSkippedTicks - startSnap.wfiSkippedTicks).coerceAtLeast(0L)
        val sliceCount = (endSnap.sliceCount - startSnap.sliceCount).coerceAtLeast(0L)
        val sliceExecInsns = (endSnap.sliceExecutedInstructions - startSnap.sliceExecutedInstructions).coerceAtLeast(0L)
        val mmioCalls = (endSnap.mmioCalls - startSnap.mmioCalls).coerceAtLeast(0L)
        val mmioReads = (endSnap.mmioReadCalls - startSnap.mmioReadCalls).coerceAtLeast(0L)
        val mmioWrites = (endSnap.mmioWriteCalls - startSnap.mmioWriteCalls).coerceAtLeast(0L)

        val avgSliceInsns = if (sliceCount > 0L) sliceExecInsns.toDouble() / sliceCount.toDouble() else 0.0
        val wfiRatioPct = if (virtualTicksDelta > 0L) (wfiSkippedTicks.toDouble() / virtualTicksDelta.toDouble()) * 100.0 else 0.0
        val mmioCallsPerSec = if (wallSeconds > 0.0) mmioCalls / wallSeconds else 0.0
        val mmioCallsPer10MTicks = if (virtualTicksDelta > 0L) (mmioCalls * 10_000_000.0) / virtualTicksDelta.toDouble() else 0.0
        val mmioCallsPer10MAttempts = if (attemptsDelta > 0L) (mmioCalls * 10_000_000.0) / attemptsDelta.toDouble() else 0.0

        val cpuNativePct = pct(cpuNativeNanos, wallNanos)
        val mmioUpcallPct = pct(mmioUpcallNanos, wallNanos)
        val wfiJumpPct = pct(wfiJumpNanos, wallNanos)
        val accountedNanos = cpuNativeNanos + mmioUpcallNanos + wfiJumpNanos
        val hostOtherNanos = (wallNanos - accountedNanos).coerceAtLeast(0L)
        val hostOtherPct = pct(hostOtherNanos, wallNanos)

        val topMmioPages = computeMmioPageDeltas(startSnap.pageTable, endSnap.pageTable, mmioCalls)
        val pageOverflowDelta = (endSnap.pageTable.overflowHits - startSnap.pageTable.overflowHits).coerceAtLeast(0L)

        val phaseBreakdown = linkedMapOf<String, Any>()
        for (i in phaseCopy.indices) {
            phaseBreakdown[FirmwareTelemetry.phaseName(i)] = linkedMapOf(
                "samples" to phaseCopy[i],
                "pct" to round2(pct(phaseCopy[i], samples)),
            )
        }

        val stageBreakdown = linkedMapOf<String, Any>()
        if (stageCopy[0] > 0L) {
            stageBreakdown["NONE"] = linkedMapOf("samples" to stageCopy[0], "pct" to round2(pct(stageCopy[0], samples)))
        }
        for (stage in FirmwareStage.entries) {
            val count = stageCopy[stage.ordinal + 1]
            if (count > 0L) {
                stageBreakdown[stage.label] = linkedMapOf("samples" to count, "pct" to round2(pct(count, samples)))
            }
        }

        val topSelfList = selfSnapshot.map { (frame, count) ->
            linkedMapOf(
                "frame" to frame,
                "samples" to count,
                "pct" to round2(pct(count, samples)),
            )
        }
        val topInclusiveList = inclusiveSnapshot.map { (frame, count) ->
            linkedMapOf(
                "frame" to frame,
                "samples" to count,
                "pct" to round2(pct(count, samples)),
            )
        }
        val topPcList = pcSnapshot.map { (pc, count) ->
            linkedMapOf(
                "pcHex" to String.format(Locale.ROOT, "0x%08X", pc and 0xffffffffL),
                "pc" to pc,
                "samples" to count,
                "pct" to round2(pct(count, samples)),
            )
        }

        return linkedMapOf(
            "schema" to 1,
            "active" to isActive,
            "smpHostThreads" to telemetry.smpHostThreadsEnabled,
            "sampleIntervalMs" to intervalMs,
            "totalSamples" to samples,
            "activeOwnerSamples" to activeSamples,
            "wallSeconds" to round4(wallSeconds),
            "virtualTicksDelta" to virtualTicksDelta,
            "virtualSeconds" to round4(virtualSeconds),
            "ticksPerRealSecond" to ticksPerRealSecond,
            "realTimeRatio" to round4(rtr),
            "attemptsDelta" to attemptsDelta,
            "publishedFramesDelta" to framesDelta,
            "avgMips" to round3(avgMips),
            "effectiveVirtualMips" to round3(effectiveVirtualMips),
            "sliceMetrics" to linkedMapOf(
                "sliceCount" to sliceCount,
                "avgNativeSliceInstructions" to round2(avgSliceInsns),
                "cpuNativeSeconds" to round4(cpuNativeNanos / 1e9),
                "cpuNativePct" to round2(cpuNativePct),
                "mmioUpcallSeconds" to round4(mmioUpcallNanos / 1e9),
                "mmioUpcallPct" to round2(mmioUpcallPct),
                "wfiJumpSeconds" to round4(wfiJumpNanos / 1e9),
                "wfiJumpPct" to round2(wfiJumpPct),
                "hostOtherSeconds" to round4(hostOtherNanos / 1e9),
                "hostOtherPct" to round2(hostOtherPct),
                "wfiJumpCount" to wfiJumpCount,
                "wfiSkippedTicks" to wfiSkippedTicks,
                "wfiRatioPct" to round2(wfiRatioPct),
            ),
            "mmioMetrics" to linkedMapOf(
                "totalCalls" to mmioCalls,
                "reads" to mmioReads,
                "writes" to mmioWrites,
                "callsPerSec" to round1(mmioCallsPerSec),
                "callsPer10MTicks" to round1(mmioCallsPer10MTicks),
                "callsPer10MAttempts" to round1(mmioCallsPer10MAttempts),
                "pageTableOverflowHits" to pageOverflowDelta,
            ),
            "coreSamples" to linkedMapOf(
                "core0" to coreCopy[0],
                "core1" to coreCopy[1],
                "core2" to coreCopy[2],
                "core3" to coreCopy[3],
            ),
            "phaseSamples" to phaseBreakdown,
            "stageSamples" to stageBreakdown,
            "topHostSelfFrames" to topSelfList,
            "topHostInclusiveFrames" to topInclusiveList,
            "topMmioPages" to topMmioPages,
            "topGuestPcs" to topPcList,
        )
    }

    /** Formats a readable ASCII performance summary report. */
    @Suppress("UNCHECKED_CAST")
    fun formatTextReport(): String {
        val data = reportMap()
        val slice = data["sliceMetrics"] as Map<String, Any?>
        val mmio = data["mmioMetrics"] as Map<String, Any?>
        val selfFrames = data["topHostSelfFrames"] as List<Map<String, Any?>>
        val inclFrames = data["topHostInclusiveFrames"] as List<Map<String, Any?>>
        val mmioPages = data["topMmioPages"] as List<Map<String, Any?>>
        val guestPcs = data["topGuestPcs"] as List<Map<String, Any?>>
        val phases = data["phaseSamples"] as Map<String, Map<String, Any?>>

        val sb = StringBuilder(2048)
        sb.appendLine("================ [FUJI-PROFILER SUMMARY] ================")
        sb.appendLine(
            String.format(
                Locale.ROOT,
                "State: %s | SMP-MT: %s | Interval: %dms | Samples: %d (active=%d)",
                if (data["active"] == true) "RUNNING" else "STOPPED",
                if (data["smpHostThreads"] == true) "ON" else "OFF",
                (data["sampleIntervalMs"] as Number).toLong(),
                (data["totalSamples"] as Number).toLong(),
                (data["activeOwnerSamples"] as Number).toLong(),
            ),
        )
        sb.appendLine(
            String.format(
                Locale.ROOT,
                "Wall Time: %.4fs | Virtual Time: %.4fs (%.0f ticks/s) | Real-Time Ratio (RTR): %.4fx",
                (data["wallSeconds"] as Number).toDouble(),
                (data["virtualSeconds"] as Number).toDouble(),
                (data["ticksPerRealSecond"] as Number).toDouble(),
                (data["realTimeRatio"] as Number).toDouble(),
            ),
        )
        sb.appendLine(
            String.format(
                Locale.ROOT,
                "Guest Attempts: %,d | Virtual Ticks: %,d | Avg MIPS: %.3f | Eff Virtual MIPS: %.3f | Frames: +%d",
                (data["attemptsDelta"] as Number).toLong(),
                (data["virtualTicksDelta"] as Number).toLong(),
                (data["avgMips"] as Number).toDouble(),
                (data["effectiveVirtualMips"] as Number).toDouble(),
                (data["publishedFramesDelta"] as Number).toLong(),
            ),
        )
        sb.appendLine(
            String.format(
                Locale.ROOT,
                "Slices: %,d (avg %.1f insns/slice) | WFI Jumps: %,d (skipped %,d ticks, %.2f%% of vticks)",
                (slice["sliceCount"] as Number).toLong(),
                (slice["avgNativeSliceInstructions"] as Number).toDouble(),
                (slice["wfiJumpCount"] as Number).toLong(),
                (slice["wfiSkippedTicks"] as Number).toLong(),
                (slice["wfiRatioPct"] as Number).toDouble(),
            ),
        )
        sb.appendLine(
            String.format(
                Locale.ROOT,
                "Wall Breakdown: CPU_NATIVE=%.2f%% (%.4fs) | MMIO_UPCALL=%.2f%% (%.4fs) | WFI_JUMP=%.2f%% (%.4fs) | HOST_OTHER=%.2f%% (%.4fs)",
                (slice["cpuNativePct"] as Number).toDouble(),
                (slice["cpuNativeSeconds"] as Number).toDouble(),
                (slice["mmioUpcallPct"] as Number).toDouble(),
                (slice["mmioUpcallSeconds"] as Number).toDouble(),
                (slice["wfiJumpPct"] as Number).toDouble(),
                (slice["wfiJumpSeconds"] as Number).toDouble(),
                (slice["hostOtherPct"] as Number).toDouble(),
                (slice["hostOtherSeconds"] as Number).toDouble(),
            ),
        )
        sb.appendLine(
            String.format(
                Locale.ROOT,
                "MMIO Upcalls: %,d (R=%,d, W=%,d) | %.1f calls/s | %.1f calls/10M vticks | %.1f calls/10M attempts",
                (mmio["totalCalls"] as Number).toLong(),
                (mmio["reads"] as Number).toLong(),
                (mmio["writes"] as Number).toLong(),
                (mmio["callsPerSec"] as Number).toDouble(),
                (mmio["callsPer10MTicks"] as Number).toDouble(),
                (mmio["callsPer10MAttempts"] as Number).toDouble(),
            ),
        )
        val phaseLine = phases.entries.joinToString(" | ") { (k, v) ->
            String.format(Locale.ROOT, "%s=%d (%.1f%%)", k, (v["samples"] as Number).toLong(), (v["pct"] as Number).toDouble())
        }
        sb.appendLine("Sampled Phases: $phaseLine")

        if (mmioPages.isNotEmpty()) {
            sb.appendLine("--- Top MMIO 4KiB Pages ---")
            for (row in mmioPages) {
                sb.appendLine(
                    String.format(
                        Locale.ROOT,
                        "  %-10s %-28s hits=%-10d (%5.1f%%) R=%-9d W=%-9d total=%.2fms avg=%.0fns",
                        row["pageBaseHex"],
                        (row["device"] as? String ?: "unknown").take(28),
                        (row["totalHits"] as Number).toLong(),
                        (row["pctOfMmio"] as Number).toDouble(),
                        (row["reads"] as Number).toLong(),
                        (row["writes"] as Number).toLong(),
                        (row["totalMicros"] as Number).toDouble() / 1000.0,
                        (row["avgNanos"] as Number).toDouble(),
                    ),
                )
            }
        }
        if (selfFrames.isNotEmpty()) {
            sb.appendLine("--- Top Host Stack Self Frames ---")
            for (row in selfFrames) {
                sb.appendLine(
                    String.format(
                        Locale.ROOT,
                        "  %5.1f%% (%5d)  %s",
                        (row["pct"] as Number).toDouble(),
                        (row["samples"] as Number).toLong(),
                        row["frame"],
                    ),
                )
            }
        }
        if (inclFrames.isNotEmpty()) {
            sb.appendLine("--- Top Host Stack Inclusive Frames ---")
            for (row in inclFrames) {
                sb.appendLine(
                    String.format(
                        Locale.ROOT,
                        "  %5.1f%% (%5d)  %s",
                        (row["pct"] as Number).toDouble(),
                        (row["samples"] as Number).toLong(),
                        row["frame"],
                    ),
                )
            }
        }
        if (guestPcs.isNotEmpty()) {
            sb.appendLine("--- Top Sampled Guest PCs ---")
            for (row in guestPcs) {
                sb.appendLine(
                    String.format(
                        Locale.ROOT,
                        "  %5.1f%% (%5d)  %s",
                        (row["pct"] as Number).toDouble(),
                        (row["samples"] as Number).toLong(),
                        row["pcHex"],
                    ),
                )
            }
        }
        sb.append("=================================================================")
        return sb.toString()
    }

    /**
     * Emits the Spark-style report to SLF4J (`logs/latest.log`) and optionally writes a standalone
     * `logs/profiler-<utc-timestamp>.txt` file when [logsDir] is provided.
     */
    fun exportReport(logsDir: Path? = null): Path? {
        val text = formatTextReport()
        val sink = infoSink
        if (sink != null) sink(text) else logger.info("\n{}", text)
        if (logsDir == null) return null
        return runCatching {
            Files.createDirectories(logsDir)
            val stamp = FILE_TS_FORMAT.format(Instant.now())
            val out = logsDir.resolve("profiler-$stamp.txt")
            Files.writeString(out, text + "\n", StandardCharsets.UTF_8)
            out
        }.getOrNull()
    }

    private fun captureCounters(nowNanos: Long): CounterSnapshot = CounterSnapshot(
        wallNanos = nowNanos,
        attempts = telemetry.attempts,
        virtualTick = telemetry.virtualTick,
        publishedFrames = telemetry.publishedFrames,
        cpuNativeNanos = telemetry.cpuNativeNanos,
        mmioUpcallNanos = telemetry.mmioUpcallNanos,
        wfiJumpNanos = telemetry.wfiJumpNanos,
        wfiJumpCount = telemetry.wfiJumpCount,
        wfiSkippedTicks = telemetry.wfiSkippedTicks,
        sliceCount = telemetry.sliceCount,
        sliceExecutedInstructions = telemetry.sliceExecutedInstructions,
        mmioCalls = telemetry.mmioCalls,
        mmioReadCalls = telemetry.mmioReadCalls,
        mmioWriteCalls = telemetry.mmioWriteCalls,
        pageTable = telemetry.snapshotMmioPages(),
    )

    private fun computeMmioPageDeltas(
        start: FirmwareTelemetry.MmioPageTableSnapshot,
        end: FirmwareTelemetry.MmioPageTableSnapshot,
        totalMmioCalls: Long,
    ): List<Map<String, Any?>> {
        val baseReads = HashMap<Long, Long>()
        val baseWrites = HashMap<Long, Long>()
        val baseNanos = HashMap<Long, Long>()
        for (i in start.pageKeys.indices) {
            val k = start.pageKeys[i]
            if (k >= 0L) {
                baseReads[k] = start.reads[i]
                baseWrites[k] = start.writes[i]
                baseNanos[k] = start.nanos[i]
            }
        }
        data class PageDelta(val pageBase: Long, val reads: Long, val writes: Long, val nanos: Long) {
            val total: Long get() = reads + writes
        }
        val deltas = ArrayList<PageDelta>()
        for (i in end.pageKeys.indices) {
            val k = end.pageKeys[i]
            if (k < 0L) continue
            val r = (end.reads[i] - (baseReads[k] ?: 0L)).coerceAtLeast(0L)
            val w = (end.writes[i] - (baseWrites[k] ?: 0L)).coerceAtLeast(0L)
            val ns = (end.nanos[i] - (baseNanos[k] ?: 0L)).coerceAtLeast(0L)
            if (r + w > 0L) {
                deltas.add(PageDelta(k shl 12, r, w, ns))
            }
        }
        deltas.sortByDescending { it.total }
        return deltas.take(topN).map { d ->
            val avgNs = if (d.total > 0L) d.nanos.toDouble() / d.total.toDouble() else 0.0
            val device = pageNameResolver?.invoke(d.pageBase) ?: "unmapped/unknown"
            linkedMapOf(
                "pageBaseHex" to String.format(Locale.ROOT, "0x%08X", d.pageBase and 0xffffffffL),
                "pageBase" to d.pageBase,
                "device" to device,
                "reads" to d.reads,
                "writes" to d.writes,
                "totalHits" to d.total,
                "pctOfMmio" to round2(pct(d.total, totalMmioCalls)),
                "totalMicros" to round2(d.nanos / 1000.0),
                "avgNanos" to round1(avgNs),
            )
        }
    }

    private fun extractRelevantFrames(trace: Array<StackTraceElement>): List<String> {
        val result = ArrayList<String>(minOf(trace.size, 24))
        for (el in trace) {
            val cls = el.className
            if (cls == "java.lang.Thread" && el.methodName == "getStackTrace") continue
            val shortCls = cls.removePrefix("com.github.nyabanban.fuji.emulator.")
            result.add("$shortCls.${el.methodName}")
            if (result.size >= 24) break
        }
        return result
    }

    private fun stopWorkerOnly() {
        val previous = worker ?: return
        running.set(false)
        telemetry.perfProfilingEnabled = false
        previous.interrupt()
        try {
            if (Thread.currentThread() !== previous) {
                previous.join(2_000L)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            worker = null
        }
    }

    private fun pct(part: Long, total: Long): Double = if (total > 0L) (part.toDouble() / total.toDouble()) * 100.0 else 0.0
    private fun round1(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0
    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0
    private fun round3(v: Double): Double = kotlin.math.round(v * 1000.0) / 1000.0
    private fun round4(v: Double): Double = kotlin.math.round(v * 10000.0) / 10000.0

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        telemetry.perfProfilingEnabled = false
        stopWorkerOnly()
    }
}
