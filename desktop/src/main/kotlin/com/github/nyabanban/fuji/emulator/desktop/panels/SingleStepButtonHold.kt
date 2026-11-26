package com.github.nyabanban.fuji.emulator.desktop.panels

/**
 * Policy governing how long a quick momentary button tap is held before releasing.
 *
 * On fast Fast-Step v2 Linux hosts (90M+ virtual ticks/s under idle-clock advancement), a
 * fixed long wall-clock hold (such as 350 ms) spans 32M+ virtual ticks and triggers GFX100S
 * 2.13 firmware auto-repeat (skipping 2+ menu rows or advancing multiple screens).
 *
 * Calibrated empirically & verified against GFX100S 2.13 `GXUP0004.DAT` (`S4 0x0123F120..0x0123DF50`):
 * - Periodic GPIO button scanner runs every exact `25.0 ms` (`1,350,000` ticks @ 54 MHz), and
 *   `DISP/BACK` (`GPIO(9,5)`, `deb=2`) + `VIEW MODE` (`GPIO(7,6)`, `deb=2`) require `2` consecutive
 *   matching polls (`25..50 ms` = `1,350,000..2,700,000` ticks worst-case).
 * - `<= 2,700,000` virtual ticks (`50 ms`): drops presses on unfavorable scanner phase (`9/10` live).
 * - `4,050,000 .. 25,000,000` virtual ticks (`75..463 ms`): 10/10 phase-safe single step across all
 *   modes (`STILL` `DISP/BACK` release-triggered HUD cycle, `VIEW MODE`, OOBE/MENU), while remaining
 *   20x below the `1,500 ms` (`81M` ticks) `DISP/BACK` long-press Bluetooth timer (`#16`).
 * - `>= 35,000,000` virtual ticks: auto-repeat (+2/+3 rows)
 *
 * Using [CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS] (`4,050,000` virtual ticks = `75 ms`, 3 full scanner
 * periods) with a hard [DEFAULT_MAX_HOLD_WALL_MS] (`3,000` ms) safety ceiling keeps quick taps crisp,
 * guarantees phase-safe single-step recognition on `DISP/BACK`, `VIEW MODE`, and `MENU`, and always
 * releases cleanly on stalled/paused sessions.
 */
data class MomentaryHoldPolicy(
    val minHoldVirtualTicks: Long = 0L,
    val maxHoldWallMs: Long = 0L,
    val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    val directionMinHoldVirtualTicks: Long = minHoldVirtualTicks,
) {
    init {
        require(minHoldVirtualTicks >= 0L) { "minHoldVirtualTicks must be non-negative" }
        require(directionMinHoldVirtualTicks >= 0L) { "directionMinHoldVirtualTicks must be non-negative" }
        require(maxHoldWallMs >= 0L) { "maxHoldWallMs must be non-negative" }
        require(pollIntervalMs > 0L) { "pollIntervalMs must be positive" }
    }

    val enabled: Boolean get() = minHoldVirtualTicks > 0L || directionMinHoldVirtualTicks > 0L || maxHoldWallMs > 0L

    fun minHoldVirtualTicksFor(key: String): Long =
        if (key in SingleStepButtonHold.DIRECTION_KEYS) directionMinHoldVirtualTicks else minHoldVirtualTicks

    companion object {
        /** DAT-verified 25 ms scanner x deb=2 (50 ms = 2.7M ticks worst-case) + full 25 ms scan-phase margin = 75 ms. */
        const val CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS: Long = 4_050_000L

        /**
         * DAT-verified rear touchscreen tap hold (`GXUP0004.DAT` `S4 0x01813180[3]=80 ms` + `17.5 ms` `T91`
         * periodic timer tick = `97.5 ms` worst-case to advance `state 1 -> state 3` and bypass unpopulated
         * `NV[0x11BBC]` short-tap drop, while remaining well below `300 ms` `state 2` long-press).
         */
        const val CALIBRATED_TOUCH_TAP_VIRTUAL_TICKS: Long = 5_670_000L

        /**
         * DAT-verified rear touchscreen swipe inter-packet step pacing (`20 ms` @ 54 MHz = `1,080,000` ticks).
         * Guarantees `T91` polls `IRQ 153` (`0x10`/`0x11`) between `DOWN -> MOVE -> UP` packets while keeping
         * a 2-step swipe (`~60 ms` virtual total) well inside both Playback (`150 ms` `NV[0x11B78]`) and
         * LiveView `T-Fn1..4` (`260 ms` `NV[0x11B6B]`) flick windows (`S4 0x0124EF18..0x0124FA98`).
         */
        const val CALIBRATED_TOUCH_SWIPE_STEP_VIRTUAL_TICKS: Long = 1_080_000L

        /** DAT-verified `T91` flick/drag start radius (`S4 0x01813180[6..7] = 20 px` in `1024x768` space). */
        const val TOUCH_FLICK_THRESHOLD_PX: Int = 20

        /** DAT-verified 25 ms 8-way D-pad debounce (1.35M ticks @ 54 MHz) + scan-phase safety margin. */
        const val CALIBRATED_DIRECTION_SINGLE_STEP_VIRTUAL_TICKS: Long = 2_200_000L

        const val DEFAULT_MAX_HOLD_WALL_MS: Long = 3_000L
        const val DEFAULT_POLL_INTERVAL_MS: Long = 5L

        val NONE: MomentaryHoldPolicy = MomentaryHoldPolicy(0L, 0L)

        fun wallClockOnly(holdMs: Long): MomentaryHoldPolicy =
            if (holdMs <= 0L) NONE else MomentaryHoldPolicy(0L, holdMs)

        fun firmwareCalibratedTouch(
            minHoldVirtualTicks: Long = CALIBRATED_TOUCH_TAP_VIRTUAL_TICKS,
            maxHoldWallMs: Long = DEFAULT_MAX_HOLD_WALL_MS,
            pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        ): MomentaryHoldPolicy = MomentaryHoldPolicy(
            minHoldVirtualTicks = minHoldVirtualTicks,
            maxHoldWallMs = maxHoldWallMs,
            pollIntervalMs = pollIntervalMs,
            directionMinHoldVirtualTicks = minHoldVirtualTicks,
        )

        fun firmwareCalibrated(
            minHoldVirtualTicks: Long = CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS,
            maxHoldWallMs: Long = DEFAULT_MAX_HOLD_WALL_MS,
            pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
            directionMinHoldVirtualTicks: Long = if (minHoldVirtualTicks == CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS) {
                CALIBRATED_DIRECTION_SINGLE_STEP_VIRTUAL_TICKS
            } else {
                minHoldVirtualTicks
            },
        ): MomentaryHoldPolicy = MomentaryHoldPolicy(
            minHoldVirtualTicks = minHoldVirtualTicks,
            maxHoldWallMs = maxHoldWallMs,
            pollIntervalMs = pollIntervalMs,
            directionMinHoldVirtualTicks = directionMinHoldVirtualTicks,
        )
    }
}

/**
 * UI-thread-serialized momentary hold manager providing:
 * 1. Dual-bounded per-key DAT-calibrated firmware-tick + hard wall-clock ceiling single-step holds.
 * 2. Immediate cancellation & release of any pending/active opposite-direction hold
 *    (`up`<->`down`, `left`<->`right`) and stale post-release D-pad holds before pressing a new key.
 * 3. Stuck-free idempotent re-press (OS keyboard auto-repeat / rapid clicks preserve `appliedAtTick`).
 * 4. Immediate release when the firmware session is paused or not advancing.
 */
class SingleStepButtonHold(
    private val policy: MomentaryHoldPolicy,
    private val onPress: (source: String, key: String) -> Unit,
    private val onRelease: (source: String, key: String) -> Unit,
    private val onPulse: (key: String) -> Unit = { key ->
        onPress("keyboard:pulse", key)
        onRelease("keyboard:pulse", key)
    },
    private val virtualTick: (() -> Long)? = null,
    private val isSessionAdvancing: (() -> Boolean)? = null,
    private val awaitAppliedTick: Boolean = false,
    private val nanoTime: () -> Long = System::nanoTime,
    private val scheduleDelay: (delayMs: Long, action: () -> Unit) -> AutoCloseable,
) {
    companion object {
        val DIRECTION_KEYS: Set<String> = setOf("up", "down", "left", "right")
        val OPPOSITE_KEYS: Map<String, String> = mapOf(
            "up" to "down",
            "down" to "up",
            "left" to "right",
            "right" to "left",
        )

        fun oppositeKeyOf(key: String): String? = OPPOSITE_KEYS[key]
    }

    private data class HoldStart(
        val pressedAtNanos: Long,
        val pressedAtTick: Long?,
        var appliedAtTick: Long? = null,
    )

    private val activeHolds = hashMapOf<Pair<String, String>, HoldStart>()
    private val activeMouseSources = linkedSetOf<Pair<String, String>>()
    private val pendingReleaseTimers = hashMapOf<Pair<String, String>, AutoCloseable>()

    fun pendingReleaseCount(): Int = pendingReleaseTimers.size

    fun markApplied(key: String, appliedTick: Long) {
        var dueTokens: MutableList<Pair<String, String>>? = null
        val nowNanos = nanoTime()
        activeHolds.forEach { (token, start) ->
            if (token.second == key && start.appliedAtTick == null) {
                start.appliedAtTick = appliedTick
                if (pendingReleaseTimers.containsKey(token) && remainingDelayMs(key, start, nowNanos) <= 0L) {
                    if (dueTokens == null) dueTokens = arrayListOf()
                    dueTokens.add(token)
                }
            }
        }
        dueTokens?.forEach { finishRelease(it) }
    }

    fun press(source: String, key: String) {
        val nowNanos = nanoTime()
        cancelAndReleaseOpposite(key)
        if (key in DIRECTION_KEYS) {
            cancelPendingDirectionReleasesExcept(key)
        }
        flushDuePendingReleases(nowNanos)
        val token = source to key
        val existing = activeHolds[token]
        if (existing != null) {
            // Re-press while already held (OS keyboard auto-repeat or rapid re-click during post-release hold):
            // cancel any pending release timer and preserve appliedAtTick so we never stall on maxHoldWallMs.
            pendingReleaseTimers.remove(token)?.close()
            if (source.startsWith("mouse:")) activeMouseSources += token
            return
        }
        pendingReleaseTimers.remove(token)?.close()
        val nowTick = virtualTick?.invoke()
        activeHolds[token] = HoldStart(
            pressedAtNanos = nowNanos,
            pressedAtTick = nowTick,
            appliedAtTick = if (awaitAppliedTick) null else nowTick,
        )
        if (source.startsWith("mouse:")) activeMouseSources += token
        onPress(source, key)
    }

    fun release(source: String, key: String) {
        val token = source to key
        activeMouseSources.remove(token)
        if (!policy.enabled) {
            pendingReleaseTimers.remove(token)?.close()
            activeHolds.remove(token)
            onRelease(source, key)
            return
        }
        val start = activeHolds[token]
        if (start == null) {
            pendingReleaseTimers.remove(token)?.close()
            onRelease(source, key)
            return
        }
        val delayMs = remainingDelayMs(key, start, nanoTime())
        if (delayMs <= 0L) {
            finishRelease(token)
        } else if (!pendingReleaseTimers.containsKey(token)) {
            scheduleReleasePoll(token, start, delayMs)
        }
    }

    fun pulse(key: String) {
        if (!policy.enabled) {
            cancelAndReleaseOpposite(key)
            onPulse(key)
        } else {
            press("keyboard:pulse", key)
            release("keyboard:pulse", key)
        }
    }

    fun releaseMouseTransient(fallbackReleaseAllMouse: () -> Unit) {
        if (!policy.enabled) {
            activeMouseSources.clear()
            fallbackReleaseAllMouse()
            return
        }
        val snapshot = activeMouseSources.toList().asReversed()
        if (snapshot.isEmpty()) {
            fallbackReleaseAllMouse()
        } else {
            snapshot.forEach { (source, key) -> release(source, key) }
        }
    }

    fun cancelPendingReleases() {
        pendingReleaseTimers.values.forEach { it.close() }
        pendingReleaseTimers.clear()
        activeHolds.clear()
        activeMouseSources.clear()
    }

    /** Completes any post-release hold timers waiting on [key] so explicit unlock releases immediately. */
    fun flushPendingReleasesForKey(key: String) {
        if (pendingReleaseTimers.isEmpty()) return
        val tokens = pendingReleaseTimers.keys.filter { it.second == key }
        for (token in tokens) {
            finishRelease(token)
        }
    }

    fun cancelAndReleaseOpposite(key: String) {
        val opposite = OPPOSITE_KEYS[key] ?: return
        val toRelease = linkedSetOf<Pair<String, String>>()
        pendingReleaseTimers.keys.filterTo(toRelease) { it.second == opposite }
        activeHolds.keys.filterTo(toRelease) { it.second == opposite }
        activeMouseSources.filterTo(toRelease) { it.second == opposite }
        for (token in toRelease) {
            pendingReleaseTimers.remove(token)?.close()
            activeHolds.remove(token)
            activeMouseSources.remove(token)
            onRelease(token.first, token.second)
        }
    }

    private fun cancelPendingDirectionReleasesExcept(key: String) {
        if (pendingReleaseTimers.isEmpty()) return
        val toRelease = pendingReleaseTimers.keys.filter { it.second in DIRECTION_KEYS && it.second != key }
        for (token in toRelease) {
            finishRelease(token)
        }
    }

    private fun flushDuePendingReleases(nowNanos: Long) {
        if (pendingReleaseTimers.isEmpty()) return
        val due = pendingReleaseTimers.keys.filter { token ->
            val start = activeHolds[token]
            start == null || remainingDelayMs(token.second, start, nowNanos) <= 0L
        }
        for (token in due) {
            finishRelease(token)
        }
    }

    private fun remainingDelayMs(key: String, start: HoldStart, nowNanos: Long): Long {
        val elapsedWallMs = ((nowNanos - start.pressedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        val hasWallCap = policy.maxHoldWallMs > 0L
        val remainingWallMs = if (hasWallCap) (policy.maxHoldWallMs - elapsedWallMs) else Long.MAX_VALUE
        if (hasWallCap && remainingWallMs <= 0L) {
            return 0L
        }
        val tickSource = virtualTick
        val minTicks = policy.minHoldVirtualTicksFor(key)
        if (minTicks > 0L && tickSource != null) {
            if (isSessionAdvancing != null && !isSessionAdvancing.invoke()) {
                return 0L
            }
            val anchorTick = start.appliedAtTick
            if (anchorTick == null) {
                return if (hasWallCap) minOf(policy.pollIntervalMs, remainingWallMs) else policy.pollIntervalMs
            }
            val currentTick = tickSource.invoke()
            if (currentTick < anchorTick) {
                // Virtual clock reset backward (e.g. Sub-MCU cold-reset wake from standby sleep):
                // release immediately instead of trapping the button until maxHoldWallMs expires.
                return 0L
            }
            val elapsedTicks = currentTick - anchorTick
            if (elapsedTicks >= minTicks) {
                return 0L
            }
            return if (hasWallCap) minOf(policy.pollIntervalMs, remainingWallMs) else policy.pollIntervalMs
        }
        return if (hasWallCap) remainingWallMs else 0L
    }

    private fun scheduleReleasePoll(token: Pair<String, String>, start: HoldStart, delayMs: Long) {
        pendingReleaseTimers.remove(token)?.close()
        lateinit var handle: AutoCloseable
        handle = scheduleDelay(delayMs) {
            if (pendingReleaseTimers[token] !== handle) return@scheduleDelay
            val currentStart = activeHolds[token]
            if (currentStart !== start) {
                pendingReleaseTimers.remove(token)
                return@scheduleDelay
            }
            val nextDelay = remainingDelayMs(token.second, start, nanoTime())
            if (nextDelay <= 0L) {
                finishRelease(token)
            } else {
                scheduleReleasePoll(token, start, nextDelay)
            }
        }
        pendingReleaseTimers[token] = handle
    }

    private fun finishRelease(token: Pair<String, String>) {
        pendingReleaseTimers.remove(token)?.close()
        activeHolds.remove(token)
        activeMouseSources.remove(token)
        onRelease(token.first, token.second)
    }
}
