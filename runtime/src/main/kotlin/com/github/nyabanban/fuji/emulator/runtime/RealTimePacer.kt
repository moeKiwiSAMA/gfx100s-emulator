package com.github.nyabanban.fuji.emulator.runtime

import java.util.concurrent.locks.LockSupport

/**
 * Optional wall-clock real-time pacer for `relaxed` interactive sessions.
 *
 * Aligns virtual ticks (`54,000,000` ticks/s calibrated to GXUP0004.DAT `kernel.bin` `0x5A920`)
 * to host wall-clock time so WFI-heavy idle states converge to `RTR ~= 1.00x` instead of racing
 * ahead at full CPU speed. Strictly forbidden in `discovery`/`strict` modes.
 *
 * Uses OS-native high-resolution waitable timers when available (`CreateWaitableTimerExW`
 * `CREATE_WAITABLE_TIMER_HIGH_RESOLUTION` on Windows; `ppoll`/`eventfd` + `PR_SET_TIMERSLACK=1`
 * on Linux) with hybrid tail-spin (`Thread.onSpinWait`) and automatic fallback to
 * `LockSupport.parkNanos`. Supports instant wakeup via [wake] whenever UI input or external
 * control commands arrive.
 */
class RealTimePacer(
    val ticksPerRealSecond: Double = DEFAULT_TICKS_PER_SECOND,
    val targetRatio: Double = 1.0,
    val minSleepNanos: Long = DEFAULT_MIN_SLEEP_NANOS,
    val spinTailNanos: Long = DEFAULT_SPIN_TAIL_NANOS,
    val maxLagResyncNanos: Long = DEFAULT_MAX_LAG_RESYNC_NANOS,
    val maxParkChunkNanos: Long = DEFAULT_MAX_PARK_CHUNK_NANOS,
    private val nanoClock: () -> Long = System::nanoTime,
    private val parker: ((Long) -> Unit)? = null,
    private val spinWaitEnabled: Boolean = true,
) : AutoCloseable {
    companion object {
        const val DEFAULT_TICKS_PER_SECOND = 54_000_000.0
        const val DEFAULT_MIN_SLEEP_NANOS = 2_000_000L
        const val DEFAULT_MIN_IDLE_SLEEP_NANOS = 200_000L
        const val DEFAULT_SPIN_TAIL_NANOS = 250_000L
        const val DEFAULT_MAX_LAG_RESYNC_NANOS = 200_000_000L
        const val DEFAULT_MAX_PARK_CHUNK_NANOS = 10_000_000L
        const val DEFAULT_MAX_IDLE_PARK_CHUNK_NANOS = 500_000L
        const val MIN_TARGET_RATIO = 0.1
        const val MAX_TARGET_RATIO = 10.0
    }

    init {
        require(ticksPerRealSecond.isFinite() && ticksPerRealSecond > 0.0) { "ticksPerRealSecond must be positive" }
        require(targetRatio.isFinite() && targetRatio in MIN_TARGET_RATIO..MAX_TARGET_RATIO) {
            "targetRatio must be in $MIN_TARGET_RATIO..$MAX_TARGET_RATIO"
        }
        require(minSleepNanos >= 0L && spinTailNanos >= 0L && maxLagResyncNanos > 0L && maxParkChunkNanos > 0L)
    }

    private val nativeWaiter: NativeOsScheduler.Waiter? =
        if (parker == null) NativeOsScheduler.createWaiter() else null
    val usesNativeHighResTimer: Boolean
        get() = nativeWaiter != null

    private val nanosPerVirtualTick: Double = 1_000_000_000.0 / (ticksPerRealSecond * targetRatio)
    private var anchored = false
    private var wallAnchorNanos = 0L
    private var virtualAnchorTick = 0L

    @Volatile private var parkedThread: Thread? = null
    @Volatile private var wakeRequested = false

    @Volatile var totalSleptNanos: Long = 0L
        private set
    @Volatile var sleepCount: Long = 0L
        private set
    @Volatile var wakeCount: Long = 0L
        private set
    @Volatile var resyncCount: Long = 0L
        private set
    @Volatile var wfiIdleSleptNanos: Long = 0L
        private set
    @Volatile var wfiIdleParkCount: Long = 0L
        private set

    fun reset() {
        anchored = false
        wakeRequested = false
        nativeWaiter?.reset()
    }

    fun reanchor(virtualTick: Long) {
        wallAnchorNanos = nanoClock()
        virtualAnchorTick = virtualTick
        anchored = true
        wakeRequested = false
        nativeWaiter?.reset()
    }

    /** Immediately wakes a parked pacing thread (e.g., on button press, HTTP control, or pause/stop). */
    fun wake() {
        wakeRequested = true
        nativeWaiter?.wake()
        val t = parkedThread
        if (t != null) {
            LockSupport.unpark(t)
        }
    }

    private fun parkOnce(durationNanos: Long) {
        val customParker = parker
        if (customParker != null) {
            customParker(durationNanos)
            return
        }
        val waiter = nativeWaiter
        if (waiter != null) {
            val rc = waiter.waitNanos(durationNanos)
            if (rc >= 0) return
        }
        LockSupport.parkNanos(durationNanos)
    }

    /**
     * Sleeps if the emulated virtual clock leads host wall-clock time beyond [minSleepNanos].
     * Re-anchors smoothly if virtual time falls behind host wall-clock by more than [maxLagResyncNanos].
     *
     * @return nanoseconds spent waiting in this call (0 if behind or within tolerance).
     */
    fun pace(virtualTick: Long, shouldAbort: () -> Boolean = { false }): Long {
        val now = nanoClock()
        if (!anchored || virtualTick < virtualAnchorTick) {
            wallAnchorNanos = now
            virtualAnchorTick = virtualTick
            anchored = true
            wakeRequested = false
            nativeWaiter?.reset()
            return 0L
        }
        val expectedElapsedNanos = ((virtualTick - virtualAnchorTick).toDouble() * nanosPerVirtualTick).toLong()
        val targetWallNanos = wallAnchorNanos + expectedElapsedNanos
        val leadNanos = targetWallNanos - now
        if (leadNanos < -maxLagResyncNanos) {
            wallAnchorNanos = now
            virtualAnchorTick = virtualTick
            resyncCount++
            wakeRequested = false
            nativeWaiter?.reset()
            return 0L
        }
        if (leadNanos <= minSleepNanos || shouldAbort()) {
            wakeRequested = false
            nativeWaiter?.reset()
            return 0L
        }
        if (wakeRequested) {
            wakeRequested = false
            nativeWaiter?.reset()
            wakeCount++
            return 0L
        }

        val sleepStart = now
        val currentThread = Thread.currentThread()
        parkedThread = currentThread
        try {
            while (true) {
                if (shouldAbort()) break
                if (wakeRequested) {
                    wakeCount++
                    break
                }
                val remaining = targetWallNanos - nanoClock()
                if (remaining <= 0L) break
                val parkBudget = remaining - if (spinWaitEnabled) spinTailNanos else 0L
                if (parkBudget > 0L) {
                    parkOnce(minOf(parkBudget, maxParkChunkNanos))
                } else if (spinWaitEnabled) {
                    Thread.onSpinWait()
                } else {
                    parkOnce(remaining)
                }
            }
        } finally {
            parkedThread = null
            wakeRequested = false
            nativeWaiter?.reset()
        }
        val slept = (nanoClock() - sleepStart).coerceAtLeast(0L)
        if (slept > 0L) {
            totalSleptNanos += slept
            sleepCount++
        }
        return slept
    }

    /**
     * Returns how many nanoseconds the emulated virtual clock currently leads host wall-clock time
     * (positive when virtual time is ahead of real time, `<= 0` when behind or unanchored).
     */
    fun leadNanos(virtualTick: Long): Long {
        if (!anchored || virtualTick < virtualAnchorTick) return 0L
        val expectedElapsedNanos = ((virtualTick - virtualAnchorTick).toDouble() * nanosPerVirtualTick).toLong()
        return (wallAnchorNanos + expectedElapsedNanos) - nanoClock()
    }

    /**
     * True host-idle park used when all emulated CPU cores are in `WFI` and the virtual clock has
     * fast-forwarded ahead of host wall-clock time.
     *
     * Unlike [pace], this method:
     * - Parks as soon as virtual lead exceeds `min(minSleepNanos, 200 µs)` (zero wasted host slices).
     * - Uses bounded `<= 500 µs` park chunks ([DEFAULT_MAX_IDLE_PARK_CHUNK_NANOS]) with ZERO
     *   busy-spin tail (`Thread.onSpinWait()` is never called during WFI idle).
     * - Wakes immediately on [wake] (UI input, button press, HTTP control, IRQ/event) or [shouldAbort].
     */
    fun paceWfiIdle(virtualTick: Long, shouldAbort: () -> Boolean = { false }): Long {
        val now = nanoClock()
        if (!anchored || virtualTick < virtualAnchorTick) {
            wallAnchorNanos = now
            virtualAnchorTick = virtualTick
            anchored = true
            wakeRequested = false
            nativeWaiter?.reset()
            return 0L
        }
        val expectedElapsedNanos = ((virtualTick - virtualAnchorTick).toDouble() * nanosPerVirtualTick).toLong()
        val targetWallNanos = wallAnchorNanos + expectedElapsedNanos
        val leadNanos = targetWallNanos - now
        if (leadNanos < -maxLagResyncNanos) {
            wallAnchorNanos = now
            virtualAnchorTick = virtualTick
            resyncCount++
            wakeRequested = false
            nativeWaiter?.reset()
            return 0L
        }
        val idleThreshold = minOf(minSleepNanos, DEFAULT_MIN_IDLE_SLEEP_NANOS)
        if (leadNanos <= idleThreshold || shouldAbort()) {
            wakeRequested = false
            nativeWaiter?.reset()
            return 0L
        }
        if (wakeRequested) {
            wakeRequested = false
            nativeWaiter?.reset()
            wakeCount++
            return 0L
        }

        val chunkCap = minOf(maxParkChunkNanos, DEFAULT_MAX_IDLE_PARK_CHUNK_NANOS)
        val sleepStart = now
        val currentThread = Thread.currentThread()
        parkedThread = currentThread
        try {
            while (true) {
                if (shouldAbort()) break
                if (wakeRequested) {
                    wakeCount++
                    break
                }
                val remaining = targetWallNanos - nanoClock()
                if (remaining <= 0L) break
                parkOnce(minOf(remaining, chunkCap))
            }
        } finally {
            parkedThread = null
            wakeRequested = false
            nativeWaiter?.reset()
        }
        val slept = (nanoClock() - sleepStart).coerceAtLeast(0L)
        if (slept > 0L) {
            totalSleptNanos += slept
            sleepCount++
            wfiIdleSleptNanos += slept
            wfiIdleParkCount++
        }
        return slept
    }

    override fun close() {
        nativeWaiter?.close()
    }

    fun stats(): Map<String, Any> = linkedMapOf(
        "ticksPerRealSecond" to ticksPerRealSecond,
        "targetRatio" to targetRatio,
        "nativeHighResTimer" to usesNativeHighResTimer,
        "totalSleptMs" to (totalSleptNanos / 1_000_000.0),
        "sleepCount" to sleepCount,
        "wfiIdleSleptMs" to (wfiIdleSleptNanos / 1_000_000.0),
        "wfiIdleParkCount" to wfiIdleParkCount,
        "wakeCount" to wakeCount,
        "resyncCount" to resyncCount,
    )
}
