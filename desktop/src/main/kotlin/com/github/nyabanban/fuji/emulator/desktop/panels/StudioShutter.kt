package com.github.nyabanban.fuji.emulator.desktop.panels

/** Moves the two physical shutter contacts in guest time, even when the user taps faster than the camera scans. */
internal class StudioShutter(
    private val inputs: StudioInputState,
    private val clock: () -> Long,
    private val advancing: () -> Boolean,
    private val readReady: () -> java.util.concurrent.CompletableFuture<Boolean?>?,
    private val nanoTime: () -> Long = System::nanoTime,
    private val readBusy: () -> java.util.concurrent.CompletableFuture<Boolean?>? = { null },
    private val poll: (() -> Unit) -> AutoCloseable,
) {
    private enum class Phase { IDLE, WAIT_FOR_CAMERA, HALF, CHECK_FULL, FULL, FULL_UP, HALF_UP }

    private val heldSources = mutableSetOf<String>()
    private var phase = Phase.IDLE
    private var strokes = 0
    private var lastTick = 0L
    private var lastProgressNanos = 0L
    private var timer: AutoCloseable? = null
    private var readyRead: java.util.concurrent.CompletableFuture<Boolean?>? = null
    private var busyRead: java.util.concurrent.CompletableFuture<Boolean?>? = null
    private var sentFull = false
    private var waitSince = 0L
    private var sawCaptureBusy = false
    private var idleSince: Long? = null

    fun press(source: String) {
        if (!heldSources.add(source) || heldSources.size > 1) return
        strokes++
        if (phase == Phase.IDLE) {
            lastTick = clock()
            lastProgressNanos = nanoTime()
            beginStroke()
        }
        advance()
    }

    fun release(source: String) {
        if (heldSources.remove(source)) advance()
    }

    fun releaseMouse() {
        if (heldSources.removeAll { it.startsWith("mouse:") }) advance()
    }

    fun cancel() {
        timer?.close()
        timer = null
        readyRead = null
        busyRead = null
        heldSources.clear()
        strokes = 0
        phase = Phase.IDLE
        inputs.release(CONTACT_SOURCE, FULL)
        inputs.release(CONTACT_SOURCE, HALF)
    }

    private fun beginHalfPress() {
        phase = Phase.HALF
        readyRead = null
        inputs.press(CONTACT_SOURCE, HALF)
    }

    private fun beginStroke() {
        if (sentFull) {
            waitForCamera(clock(), busy = false)
        } else beginHalfPress()
    }

    private fun waitForCamera(now: Long, busy: Boolean) {
        phase = Phase.WAIT_FOR_CAMERA
        waitSince = now
        sawCaptureBusy = busy
        idleSince = null
        busyRead = null
    }

    private fun advance() {
        val now = clock()
        if (phase == Phase.IDLE) return
        val wall = nanoTime()
        if (now > lastTick) lastProgressNanos = wall
        val releasedWhileStalled = heldSources.isEmpty() && wall - lastProgressNanos >= STALL_NANOS
        val key = when (phase) {
            Phase.HALF, Phase.CHECK_FULL, Phase.HALF_UP -> HALF
            Phase.FULL, Phase.FULL_UP -> FULL
            Phase.IDLE, Phase.WAIT_FOR_CAMERA -> null
        }
        if (!advancing() || now < lastTick || releasedWhileStalled ||
            (key != null && inputs.lastRequest(key)?.submission == StudioInputState.Submission.FAILED)) {
            cancel()
            return
        }
        lastTick = now
        fun elapsed(key: String, down: Boolean, ticks: Long): Boolean =
            inputs.appliedAt(key, down)?.let { now - it >= ticks } == true

        when (phase) {
            Phase.WAIT_FOR_CAMERA -> {
                if (now - waitSince >= CAPTURE_WAIT_TICKS) {
                    cancel()
                    return
                }
                when (cameraBusy()) {
                    true -> {
                        sawCaptureBusy = true
                        idleSince = null
                    }
                    false -> {
                        val idle = idleSince ?: now.also { idleSince = it }
                        // The resumed still input scanner must sample both contacts up before a new S1.
                        if (!sawCaptureBusy || now - idle >= SCAN_TICKS) beginHalfPress()
                    }
                    null -> Unit
                }
            }
            Phase.HALF -> if (elapsed(HALF, true, SCAN_TICKS) && halfReady(now)) {
                phase = Phase.CHECK_FULL
                checkFullContact(now)
            }
            Phase.CHECK_FULL -> checkFullContact(now)
            Phase.FULL -> if ((strokes > 1 || heldSources.isEmpty()) && elapsed(FULL, true, SCAN_TICKS)) {
                phase = Phase.FULL_UP
                inputs.release(CONTACT_SOURCE, FULL)
            }
            Phase.FULL_UP -> if (elapsed(FULL, false, SCAN_TICKS)) {
                strokes--
                phase = Phase.HALF_UP
                inputs.release(CONTACT_SOURCE, HALF)
            }
            Phase.HALF_UP -> if (inputs.desired(HALF) || elapsed(HALF, false, SCAN_TICKS)) {
                if (strokes > 0) beginStroke() else phase = Phase.IDLE
            }
            Phase.IDLE -> return
        }
        if (phase != Phase.IDLE && timer == null) {
            timer = poll {
                timer = null
                advance()
            }
        }
    }

    private fun cameraBusy(): Boolean? {
        val read = busyRead ?: readBusy() ?: return false
        busyRead = read
        if (!read.isDone) return null
        busyRead = null
        return !read.isCompletedExceptionally && !read.isCancelled && read.getNow(null) == true
    }

    private fun checkFullContact(now: Long) {
        when (cameraBusy()) {
            true -> {
                inputs.release(CONTACT_SOURCE, HALF)
                waitForCamera(now, busy = true)
            }
            false -> {
                phase = Phase.FULL
                sentFull = true
                inputs.press(CONTACT_SOURCE, FULL)
            }
            null -> Unit
        }
    }

    private fun halfReady(now: Long): Boolean {
        val halfApplied = inputs.appliedAt(HALF, true) ?: return false
        if (now - halfApplied >= FOCUS_TIMEOUT_TICKS) return true
        val read = readyRead ?: readReady() ?: return now - halfApplied >= HALF_TRAVEL_TICKS
        readyRead = read
        if (!read.isDone) return false
        readyRead = null
        return if (read.isCompletedExceptionally || read.isCancelled) now - halfApplied >= HALF_TRAVEL_TICKS
        else read.getNow(null) ?: (now - halfApplied >= HALF_TRAVEL_TICKS)
    }

    private companion object {
        const val HALF = "shutterHalf"
        const val FULL = "shutterFull"
        const val CONTACT_SOURCE = "keyboard:studio-shutter-contacts"
        // Stock 2.13 accepts S1 after ~203 ms in the measured AF-S scene; allow two more scanner periods.
        const val HALF_TRAVEL_TICKS = 13_500_000L
        // Stock AF-S may scan the lens for multiple seconds after the preceding full readout.
        const val FOCUS_TIMEOUT_TICKS = 432_000_000L
        // Queue user contacts across a still readout and write, but discard them after 30 guest seconds.
        const val CAPTURE_WAIT_TICKS = 1_620_000_000L
        const val SCAN_TICKS = MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS
        const val STALL_NANOS = MomentaryHoldPolicy.DEFAULT_MAX_HOLD_WALL_MS * 1_000_000L
    }
}
