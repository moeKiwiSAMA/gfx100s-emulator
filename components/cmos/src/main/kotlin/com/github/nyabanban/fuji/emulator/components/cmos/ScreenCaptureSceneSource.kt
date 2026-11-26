package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame
import com.github.nyabanban.fuji.emulator.api.SceneDimension
import com.github.nyabanban.fuji.emulator.api.SceneSource
import com.github.nyabanban.fuji.emulator.api.SceneSource2D
import com.github.nyabanban.fuji.emulator.api.SceneSourceCapability
import com.github.nyabanban.fuji.emulator.api.SceneSourceProvider
import com.github.nyabanban.fuji.emulator.api.SceneSourceRequest
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Validated capture descriptor for [ScreenCaptureSceneSource].
 */
sealed class ScreenCaptureTarget {
    abstract val fps: Int
    abstract val canonicalUri: String

    data class PrimaryScreen(override val fps: Int = DEFAULT_FPS) : ScreenCaptureTarget() {
        override val canonicalUri: String
            get() = if (fps == DEFAULT_FPS) "screen://primary" else "screen://primary?fps=$fps"
    }

    data class ScreenRegion(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        override val fps: Int = DEFAULT_FPS,
    ) : ScreenCaptureTarget() {
        override val canonicalUri: String
            get() = "screen://region/$x,$y,$width,$height?fps=$fps"
    }

    data class WindowTitle(
        val title: String,
        override val fps: Int = DEFAULT_FPS,
    ) : ScreenCaptureTarget() {
        override val canonicalUri: String
            get() = "window://$title?fps=$fps"
    }

    companion object {
        const val DEFAULT_FPS = 24
        const val MIN_FPS = 1
        const val MAX_FPS = 60
        const val MAX_REGION_DIMENSION = 16384
    }
}

/**
 * Injectable single-frame screen grabber (defaults to `java.awt.Robot.createScreenCapture` on
 * graphical desktops; mocked in headless CI unit tests).
 */
fun interface ScreenFrameGrabber {
    fun grab(target: ScreenCaptureTarget, maxWidth: Int, maxHeight: Int): PackedRawFrame?
}

/**
 * Cross-platform zero-dependency live screen / region / game-window [SceneSource] (`sourceKind = "screen-capture"`).
 *
 * Supported URIs:
 * - `screen://primary` (optional `?fps=24`) — captures the primary desktop monitor via `java.awt.Robot`
 * - `screen://region/x,y,w,h?fps=24` — captures an explicit desktop region via `java.awt.Robot`
 * - `window://<title>?fps=24` — captures a named game/application window via `ffmpeg` (`gdigrab` on Windows,
 *   `x11grab` on Linux, `avfoundation` on macOS)
 *
 * Design guarantees:
 * - **Non-blocking latest-frame slot**: a background daemon thread overwrites an atomic latest-frame
 *   reference (`AtomicReference<PackedRawFrame?>`) so the ARM emulation owner thread never stalls.
 * - **Headless-safe graceful fallback**: in headless CI / server environments (or when `ffmpeg` is
 *   absent for `window://`), records a descriptive [lastError] and seamlessly serves the built-in
 *   QR / test-card fallback source without throwing or hanging.
 */
class ScreenCaptureSceneSource internal constructor(
    val target: ScreenCaptureTarget,
    val targetWidth: Int = DEFAULT_WIDTH,
    val targetHeight: Int = DEFAULT_HEIGHT,
    private val frameGrabber: ScreenFrameGrabber,
    private val windowStreamOpener: FfmpegStreamOpener?,
    private val fallback: FallbackSceneSource,
    private val reconnectDelayMs: Long = DEFAULT_RECONNECT_DELAY_MS,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    override val virtualSubjectDistanceM: Double? = null,
) : SceneSource2D, LiveStreamSceneSource {
    private val closed = AtomicBoolean(false)
    private val latestFrame = AtomicReference<PackedRawFrame?>(null)
    private val activeStreamHandle = AtomicReference<FfmpegStreamHandle?>(null)
    private val receivedCount = AtomicLong(0L)
    private val droppedCount = AtomicLong(0L)
    private val fallbackCount = AtomicLong(0L)
    private val consumedSeq = AtomicLong(-1L)
    private val bayerCache = CachedPackedBayerConverter()

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var cachedLinearSeq: Long = -1L

    @Volatile
    private var cachedLinearFrame: LinearRgbFrame? = null

    private val captureThread: Thread

    override val latestFrameSequence: Long get() = latestFrame.get()?.sequence ?: 0L

    override val activeSourceRef: String get() = target.canonicalUri
    override val sourceWidth: Int get() = latestFrame.get()?.width ?: targetWidth
    override val sourceHeight: Int get() = latestFrame.get()?.height ?: targetHeight
    override val hasDepthBuffer: Boolean get() = false
    override val lastSourceError: String? get() = lastError
    override val framesReceived: Long get() = receivedCount.get()
    override val framesDropped: Long get() = droppedCount.get()
    val fallbackFramesServed: Long get() = fallbackCount.get()

    init {
        require(targetWidth in 2..MAX_DIMENSION && targetWidth % 2 == 0) {
            "screen-capture width must be even in 2..$MAX_DIMENSION, got $targetWidth"
        }
        require(targetHeight in 2..MAX_DIMENSION && targetHeight % 2 == 0) {
            "screen-capture height must be even in 2..$MAX_DIMENSION, got $targetHeight"
        }
        require(reconnectDelayMs >= 0L) { "reconnectDelayMs must be non-negative" }
        require(maxReconnectAttempts >= 0) { "maxReconnectAttempts must be non-negative" }

        val runner = if (target is ScreenCaptureTarget.WindowTitle) ::windowPipeLoop else ::robotCaptureLoop
        captureThread = Thread(runner, "fuji-cmos-screen-capture").apply {
            isDaemon = true
            start()
        }
    }

    private fun publishFrame(frame: PackedRawFrame) {
        val seq = receivedCount.get() + 1
        val tagged = PackedRawFrame(
            width = frame.width,
            height = frame.height,
            format = frame.format,
            pixels = frame.pixels,
            depthMeters = frame.depthMeters,
            sequence = seq,
        )
        val prev = latestFrame.getAndSet(tagged)
        receivedCount.set(seq)
        if (prev != null && prev.sequence > consumedSeq.get()) {
            droppedCount.incrementAndGet()
        }
    }

    private fun robotCaptureLoop() {
        val frameIntervalNanos = 1_000_000_000L / target.fps.coerceIn(ScreenCaptureTarget.MIN_FPS, ScreenCaptureTarget.MAX_FPS)
        var consecutiveErrors = 0
        while (!closed.get()) {
            val started = System.nanoTime()
            try {
                val captured = frameGrabber.grab(target, targetWidth, targetHeight)
                if (captured != null) {
                    consecutiveErrors = 0
                    lastError = null
                    publishFrame(captured)
                }
            } catch (err: UnsupportedOperationException) {
                lastError = err.message ?: err.javaClass.simpleName
                break
            } catch (err: Throwable) {
                if (!closed.get()) {
                    lastError = err.message ?: err.javaClass.simpleName
                }
                consecutiveErrors++
                if (consecutiveErrors > maxReconnectAttempts) {
                    break
                }
                if (reconnectDelayMs > 0L) {
                    try {
                        Thread.sleep(reconnectDelayMs)
                    } catch (_: InterruptedException) {
                        if (closed.get()) break
                    }
                }
                continue
            }

            val elapsed = System.nanoTime() - started
            val remainingMs = (frameIntervalNanos - elapsed) / 1_000_000L
            if (remainingMs > 0L && !closed.get()) {
                try {
                    Thread.sleep(remainingMs)
                } catch (_: InterruptedException) {
                    if (closed.get()) break
                }
            }
        }
    }

    private fun windowPipeLoop() {
        val opener = windowStreamOpener
        if (opener == null) {
            lastError = "Window capture backend unavailable for ${target.canonicalUri}"
            return
        }
        val frameBytes = Math.multiplyExact(Math.multiplyExact(targetWidth, targetHeight), 3)
        var attempts = 0
        while (!closed.get()) {
            var handle: FfmpegStreamHandle? = null
            try {
                handle = opener.open(targetWidth, targetHeight)
                activeStreamHandle.set(handle)
                val input = handle.inputStream
                attempts = 0
                lastError = null
                while (!closed.get()) {
                    val buf = ByteArray(frameBytes)
                    if (!readFully(input, buf)) break
                    publishFrame(PackedRawFrame(targetWidth, targetHeight, RawPixelFormat.RGB24, buf))
                }
            } catch (err: Throwable) {
                if (!closed.get()) {
                    lastError = err.message ?: err.javaClass.simpleName
                }
            } finally {
                activeStreamHandle.compareAndSet(handle, null)
                runCatching { handle?.close() }
            }
            if (closed.get() || attempts >= maxReconnectAttempts) break
            attempts++
            if (reconnectDelayMs > 0L) {
                try {
                    Thread.sleep((reconnectDelayMs * (1L shl minOf(attempts - 1, 3))).coerceAtMost(MAX_BACKOFF_MS))
                } catch (_: InterruptedException) {
                    if (closed.get()) break
                }
            }
        }
    }

    private fun readFully(input: InputStream, target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) {
                if (offset == 0) return false
                throw EOFException("Truncated window capture frame ($offset/${target.size} bytes)")
            }
            offset += n
        }
        return true
    }

    override fun radiance(exposure: Exposure, geometry: SensorGeometry, destination: ByteBuffer) {
        check(!closed.get()) { "Screen capture scene source is closed" }
        val frame = latestFrame.get()
        if (frame == null) {
            fallbackCount.incrementAndGet()
            fallback.radiance(exposure, geometry, destination)
            return
        }
        consumedSeq.set(frame.sequence)
        bayerCache.writeCached(frame, exposure, geometry, destination)
    }

    override fun latestLinearRgbFrame(): LinearRgbFrame {
        check(!closed.get()) { "Screen capture scene source is closed" }
        val frame = latestFrame.get()
        if (frame == null) {
            fallbackCount.incrementAndGet()
            return fallback.latestLinearRgbFrame()
        }
        consumedSeq.set(frame.sequence)
        val cached = cachedLinearFrame
        if (cached != null && cachedLinearSeq == frame.sequence) {
            return cached
        }
        val converted = SceneFrameCodec.toLinearRgbFrame(frame)
        cachedLinearSeq = frame.sequence
        cachedLinearFrame = converted
        return converted
    }

    override fun samplePlanarRadiance(): LinearRadianceFrame =
        latestLinearRgbFrame().toRadianceFrame(
            dimension = SceneDimension.DIM_2D,
            virtualSubjectDistanceM = virtualSubjectDistanceM,
            sequence = latestFrameSequence,
        )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { activeStreamHandle.getAndSet(null)?.close() }
        captureThread.interrupt()
        runCatching { captureThread.join(1_000L) }
        latestFrame.set(null)
        cachedLinearFrame = null
        bayerCache.clear()
        fallback.close()
    }

    companion object {
        const val KIND = "screen-capture"
        const val DEFAULT_WIDTH = 1024
        const val DEFAULT_HEIGHT = 576
        private const val MAX_DIMENSION = 4096
        private const val DEFAULT_RECONNECT_DELAY_MS = 250L
        private const val MAX_BACKOFF_MS = 2_000L
        private const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 4

        /**
         * Parses and validates a `screen-capture` URI:
         * - `screen://primary` (optional `?fps=1..60`)
         * - `screen://region/x,y,w,h` (optional `?fps=1..60`)
         * - `window://<title>` (optional `?fps=1..60`)
         */
        fun parseAndValidateTarget(sourceRef: String): ScreenCaptureTarget {
            val trimmed = sourceRef.trim()
            require(trimmed.isNotEmpty()) {
                "screen-capture sourceFile must specify screen://primary, screen://region/x,y,w,h, or window://<title>"
            }
            require(trimmed.none { it.isISOControl() }) {
                "screen-capture URI must not contain control characters"
            }

            val queryRaw = trimmed.substringAfter('?', "")
            val body = trimmed.substringBefore('?')
            val fps = parseFps(queryRaw)

            return when {
                body.startsWith("screen://", ignoreCase = true) -> {
                    require(body.none { it.isWhitespace() }) {
                        "screen:// URI must not contain whitespace: $sourceRef"
                    }
                    val path = body.substringAfter("://")
                    when {
                        path.equals("primary", ignoreCase = true) -> ScreenCaptureTarget.PrimaryScreen(fps)
                        path.startsWith("region/", ignoreCase = true) -> {
                            val coords = path.substringAfter("region/").split(',')
                            require(coords.size == 4) {
                                "screen://region/ requires 4 comma-separated integers x,y,w,h: $sourceRef"
                            }
                            val x = coords[0].toIntOrNull()
                                ?: throw IllegalArgumentException("Invalid region x in $sourceRef")
                            val y = coords[1].toIntOrNull()
                                ?: throw IllegalArgumentException("Invalid region y in $sourceRef")
                            val w = coords[2].toIntOrNull()
                                ?: throw IllegalArgumentException("Invalid region width in $sourceRef")
                            val h = coords[3].toIntOrNull()
                                ?: throw IllegalArgumentException("Invalid region height in $sourceRef")
                            require(x >= 0 && y >= 0) {
                                "screen://region/ coordinates must be non-negative: x=$x, y=$y"
                            }
                            require(
                                w in 2..ScreenCaptureTarget.MAX_REGION_DIMENSION &&
                                    h in 2..ScreenCaptureTarget.MAX_REGION_DIMENSION,
                            ) {
                                "screen://region/ dimensions must be in 2..${ScreenCaptureTarget.MAX_REGION_DIMENSION}: ${w}x$h"
                            }
                            ScreenCaptureTarget.ScreenRegion(x, y, w, h, fps)
                        }
                        else -> throw IllegalArgumentException(
                            "Unsupported screen:// target '$path'; expected screen://primary or screen://region/x,y,w,h",
                        )
                    }
                }
                body.startsWith("window://", ignoreCase = true) -> {
                    val rawTitle = body.substringAfter("://")
                    val decodedTitle = try {
                        URLDecoder.decode(rawTitle, Charsets.UTF_8).trim()
                    } catch (err: Exception) {
                        throw IllegalArgumentException("Invalid percent-encoding in window:// URI: $sourceRef", err)
                    }
                    require(decodedTitle.isNotEmpty()) {
                        "window:// URI requires a non-empty window title: $sourceRef"
                    }
                    ScreenCaptureTarget.WindowTitle(decodedTitle, fps)
                }
                else -> throw IllegalArgumentException(
                    "Unsupported screen-capture URI '$sourceRef'; expected screen://primary, screen://region/x,y,w,h, or window://<title>",
                )
            }
        }

        private fun parseFps(queryRaw: String): Int {
            if (queryRaw.isEmpty()) return ScreenCaptureTarget.DEFAULT_FPS
            var fps = ScreenCaptureTarget.DEFAULT_FPS
            for (part in queryRaw.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                require(eq > 0) { "Malformed screen-capture query parameter: $part" }
                val key = part.substring(0, eq).trim().lowercase()
                val value = part.substring(eq + 1).trim()
                when (key) {
                    "fps" -> {
                        val parsed = value.toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid screen-capture fps value: '$value'")
                        require(parsed in ScreenCaptureTarget.MIN_FPS..ScreenCaptureTarget.MAX_FPS) {
                            "screen-capture fps must be in ${ScreenCaptureTarget.MIN_FPS}..${ScreenCaptureTarget.MAX_FPS}, got $parsed"
                        }
                        fps = parsed
                    }
                    else -> throw IllegalArgumentException("Unsupported screen-capture query parameter '$key'")
                }
            }
            return fps
        }

        /**
         * Builds the cross-platform `ffmpeg` window-capture command (`gdigrab` on Windows,
         * `x11grab` on Linux, `avfoundation` on macOS) outputting raw RGB24 frames to `pipe:1`.
         */
        fun buildWindowCaptureFfmpegCommand(
            target: ScreenCaptureTarget.WindowTitle,
            width: Int,
            height: Int,
            osName: String = System.getProperty("os.name").orEmpty(),
            ffmpegExecutable: String = FfmpegPipeSceneSource.resolveFfmpegExecutable(),
            displayEnv: String = System.getenv("DISPLAY").orEmpty().ifBlank { ":0.0" },
        ): List<String> {
            val cmd = mutableListOf(ffmpegExecutable, "-nostdin", "-loglevel", "error")
            val lowerOs = osName.lowercase()
            when {
                "win" in lowerOs -> {
                    cmd += listOf(
                        "-f", "gdigrab",
                        "-framerate", target.fps.toString(),
                        "-i", "title=${target.title}",
                    )
                }
                "mac" in lowerOs || "darwin" in lowerOs -> {
                    cmd += listOf(
                        "-f", "avfoundation",
                        "-framerate", target.fps.toString(),
                        "-i", "1:none",
                    )
                }
                else -> {
                    cmd += listOf(
                        "-f", "x11grab",
                        "-framerate", target.fps.toString(),
                        "-i", displayEnv,
                    )
                }
            }
            val filter = "scale=$width:$height:force_original_aspect_ratio=decrease," +
                "pad=$width:$height:(ow-iw)/2:(oh-ih)/2:color=black"
            cmd += listOf(
                "-an", "-sn", "-dn",
                "-vf", filter,
                "-pix_fmt", "rgb24",
                "-f", "rawvideo",
                "pipe:1",
            )
            return cmd
        }

        /**
         * Default [FfmpegStreamOpener] for `window://<title>` targets using `ffmpeg` `gdigrab`/`x11grab`.
         */
        fun windowProcessStreamOpener(
            target: ScreenCaptureTarget.WindowTitle,
            ffmpegExecutable: String = FfmpegPipeSceneSource.resolveFfmpegExecutable(),
        ): FfmpegStreamOpener = FfmpegStreamOpener { width, height ->
            val command = buildWindowCaptureFfmpegCommand(
                target = target,
                width = width,
                height = height,
                ffmpegExecutable = ffmpegExecutable,
            )
            val process = try {
                ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()
            } catch (err: IOException) {
                throw IOException("Unable to start ffmpeg window capture ($ffmpegExecutable): ${err.message}", err)
            }
            object : FfmpegStreamHandle {
                override val inputStream: InputStream = process.inputStream
                override fun close() {
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                    runCatching { process.outputStream.close() }
                    if (process.isAlive) {
                        process.destroyForcibly()
                    }
                }
            }
        }

        /**
         * Default zero-dependency desktop grabber using `java.awt.Robot.createScreenCapture`.
         * Detects headless JVM environments cleanly and signals fallback without crashing.
         */
        fun awtRobotGrabber(): ScreenFrameGrabber {
            var cachedRobot: Robot? = null
            return ScreenFrameGrabber { target, maxWidth, maxHeight ->
                if (GraphicsEnvironment.isHeadless()) {
                    throw UnsupportedOperationException(
                        "Headless JVM environment: AWT Robot screen capture unavailable (serving fallback frame)",
                    )
                }
                val robot = cachedRobot ?: Robot().also { cachedRobot = it }
                val screenSize = Toolkit.getDefaultToolkit().screenSize
                val screenBounds = Rectangle(0, 0, screenSize.width, screenSize.height)
                val captureRect = when (target) {
                    is ScreenCaptureTarget.PrimaryScreen -> screenBounds
                    is ScreenCaptureTarget.ScreenRegion -> {
                        val requested = Rectangle(target.x, target.y, target.width, target.height)
                        val clipped = requested.intersection(screenBounds)
                        require(!clipped.isEmpty) {
                            "screen://region/${target.x},${target.y},${target.width},${target.height} is outside display ${screenSize.width}x${screenSize.height}"
                        }
                        clipped
                    }
                    is ScreenCaptureTarget.WindowTitle -> screenBounds
                }
                val shot = robot.createScreenCapture(captureRect)
                bufferedImageToPackedRgb24(shot, maxWidth, maxHeight)
            }
        }

        /**
         * Scales (if larger than `maxWidth x maxHeight`, preserving aspect ratio) and packs a
         * [BufferedImage] into an even-dimensioned `RGB24` [PackedRawFrame].
         */
        internal fun bufferedImageToPackedRgb24(
            image: BufferedImage,
            maxWidth: Int,
            maxHeight: Int,
        ): PackedRawFrame {
            val srcW = image.width
            val srcH = image.height
            val scale = min(1.0, min(maxWidth.toDouble() / srcW.toDouble(), maxHeight.toDouble() / srcH.toDouble()))
            val outW = ((srcW * scale).roundToInt().coerceIn(2, maxWidth) and -2).coerceAtLeast(2)
            val outH = ((srcH * scale).roundToInt().coerceIn(2, maxHeight) and -2).coerceAtLeast(2)

            val argb = image.getRGB(0, 0, srcW, srcH, null, 0, srcW)
            val out = ByteArray(outW * outH * 3)
            val xMap = IntArray(outW) { dx -> ((dx.toLong() * srcW) / outW).toInt().coerceIn(0, srcW - 1) }
            val yMap = IntArray(outH) { dy -> ((dy.toLong() * srcH) / outH).toInt().coerceIn(0, srcH - 1) }
            var dst = 0
            for (y in 0 until outH) {
                val srcRow = yMap[y] * srcW
                for (x in 0 until outW) {
                    val px = argb[srcRow + xMap[x]]
                    out[dst++] = ((px ushr 16) and 0xFF).toByte()
                    out[dst++] = ((px ushr 8) and 0xFF).toByte()
                    out[dst++] = (px and 0xFF).toByte()
                }
            }
            return PackedRawFrame(outW, outH, RawPixelFormat.RGB24, out)
        }
    }
}

/**
 * Built-in M-Optics `screen-capture` [SceneSourceProvider] registered in [SceneSourceRegistry].
 */
class ScreenCaptureSceneSourceProvider(
    private val targetWidth: Int = ScreenCaptureSceneSource.DEFAULT_WIDTH,
    private val targetHeight: Int = ScreenCaptureSceneSource.DEFAULT_HEIGHT,
    private val customGrabberFactory: ((ScreenCaptureTarget) -> ScreenFrameGrabber)? = null,
    private val customWindowOpenerFactory: ((ScreenCaptureTarget.WindowTitle) -> FfmpegStreamOpener)? = null,
    private val reconnectDelayMs: Long = 250L,
    private val maxReconnectAttempts: Int = 4,
) : SceneSourceProvider {
    override val supportedKinds: Set<String> = setOf(ScreenCaptureSceneSource.KIND)
    override val capability: SceneSourceCapability = SceneSourceCapability.READY

    override fun pipelineStatus(kind: String): String = "screen-capture-live-readout"

    override fun validate(request: SceneSourceRequest) {
        require(request.kind == ScreenCaptureSceneSource.KIND) {
            "Provider for ${ScreenCaptureSceneSource.KIND} cannot validate ${request.kind}"
        }
        ScreenCaptureSceneSource.parseAndValidateTarget(request.sourceRef)
    }

    override fun open(request: SceneSourceRequest): SceneSource {
        val target = ScreenCaptureSceneSource.parseAndValidateTarget(request.sourceRef)
        val grabber = customGrabberFactory?.invoke(target) ?: ScreenCaptureSceneSource.awtRobotGrabber()
        val windowOpener = if (target is ScreenCaptureTarget.WindowTitle) {
            customWindowOpenerFactory?.invoke(target) ?: ScreenCaptureSceneSource.windowProcessStreamOpener(target)
        } else {
            null
        }
        val fallback = SceneFrameCodec.createFallbackSource(request.resolveDataPath)
        return ScreenCaptureSceneSource(
            target = target,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            frameGrabber = grabber,
            windowStreamOpener = windowOpener,
            fallback = fallback,
            reconnectDelayMs = reconnectDelayMs,
            maxReconnectAttempts = maxReconnectAttempts,
        )
    }
}
