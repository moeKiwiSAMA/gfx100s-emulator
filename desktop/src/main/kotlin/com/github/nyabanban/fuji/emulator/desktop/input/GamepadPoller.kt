package com.github.nyabanban.fuji.emulator.desktop.input

import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Source of raw controller snapshots (index == slot). Implementations are polled from one thread. */
interface GamepadBackend : AutoCloseable {
    val name: String
    val available: Boolean get() = true
    fun poll(): List<GamepadFrame>
    override fun close() {}
}

/** Used when the native bridge is missing: never reports a controller. */
object UnavailableGamepadBackend : GamepadBackend {
    override val name: String = "unavailable"
    override val available: Boolean = false
    override fun poll(): List<GamepadFrame> = emptyList()
}

/**
 * 60 Hz daemon poller. It follows one controller (keeps the same slot while it stays connected, otherwise the lowest
 * connected slot), forwards a frame to the UI thread only when it changed, and drives STEP auto-repeat ticks.
 * [close] stops the thread and delivers a final disconnected frame so every held action is released.
 *
 * Tests call [pollOnce] directly with a synchronous [dispatch].
 */
class GamepadPoller(
    private val backend: GamepadBackend,
    private val dispatch: (() -> Unit) -> Unit,
    private val onFrame: (GamepadFrame) -> Unit,
    private val onTick: (Long) -> Unit = {},
    private val needsTick: () -> Boolean = { false },
    private val clock: () -> Long = System::nanoTime,
    private val periodMs: Long = DEFAULT_PERIOD_MS,
    /** Which controller to follow; read on the poller thread every cycle (must be thread-safe). */
    private val selection: () -> GamepadSelection = { GamepadSelection.Auto },
    /** Receives the connected-controller list (on the dispatch thread) whenever it changes (hot-plug / unplug). */
    private val onDevices: (List<GamepadDevice>) -> Unit = {},
) : AutoCloseable {
    private var lastDevices: List<GamepadDevice>? = null
    private val log = LoggerFactory.getLogger(GamepadPoller::class.java)
    @Volatile private var running = false
    @Volatile private var closed = false
    private var thread: Thread? = null
    private var followedSlot = -1
    private var lastPosted: GamepadFrame? = null
    private var failureLogged = false
    private var consecutiveFailures = 0

    val isRunning: Boolean get() = running

    fun start() {
        check(!closed) { "GamepadPoller closed" }
        if (running || !backend.available) return
        running = true
        thread = Thread({
            while (running) {
                val started = System.nanoTime()
                pollOnce()
                val backoff = if (consecutiveFailures > 0) minOf(1_000L, periodMs shl minOf(consecutiveFailures, 6)) else periodMs
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                val sleep = (backoff - elapsedMs).coerceAtLeast(1L)
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "fuji-gamepad-poller").apply {
            isDaemon = true
            start()
        }
    }

    /** One poll cycle. Returns the frame that represents the followed controller. */
    fun pollOnce(): GamepadFrame {
        val frames = try {
            backend.poll().also {
                consecutiveFailures = 0
            }
        } catch (error: Throwable) {
            consecutiveFailures++
            if (!failureLogged) {
                failureLogged = true
                log.warn("[FALLBACK][gamepad] Gamepad poll failed on backend {}: {}", backend.name, error.message ?: error.javaClass.simpleName)
            }
            emptyList()
        }
        val connected = frames.filter { it.connected }.sortedBy { it.slot }
        val devices = connected.map { GamepadDevice(it.slot, it.name) }
        if (devices != lastDevices) {
            lastDevices = devices
            dispatch { onDevices(devices) }
        }
        val chosen = runCatching(selection).getOrDefault(GamepadSelection.Auto).choose(connected, followedSlot)
        followedSlot = chosen?.slot ?: -1
        val frame = chosen?.let(::quantize) ?: GamepadFrame.DISCONNECTED
        if (frame != lastPosted) {
            lastPosted = frame
            dispatch { onFrame(frame) }
        }
        if (needsTick()) {
            val now = clock()
            dispatch { onTick(now) }
        }
        return frame
    }

    override fun close() {
        if (closed) return
        closed = true
        running = false
        thread?.let { t ->
            t.interrupt()
            runCatching { t.join(500) }
        }
        thread = null
        if (lastPosted?.connected == true) {
            lastPosted = GamepadFrame.DISCONNECTED
            dispatch { onFrame(GamepadFrame.DISCONNECTED) }
        }
        runCatching { backend.close() }
    }

    companion object {
        const val DEFAULT_PERIOD_MS: Long = 16L
        private const val AXIS_STEPS = 512.0

        /** Drops sub-1/512 axis noise so an idle controller does not flood the UI thread with frames. */
        internal fun quantize(frame: GamepadFrame): GamepadFrame {
            fun q(v: Double) = if (!v.isFinite()) 0.0 else ((v.coerceIn(-1.0, 1.0) * AXIS_STEPS).roundToInt() / AXIS_STEPS)
            return frame.copy(
                lx = q(frame.lx), ly = q(frame.ly), rx = q(frame.rx), ry = q(frame.ry),
                lt = q(frame.lt).coerceAtLeast(0.0), rt = q(frame.rt).coerceAtLeast(0.0),
            )
        }
    }
}
