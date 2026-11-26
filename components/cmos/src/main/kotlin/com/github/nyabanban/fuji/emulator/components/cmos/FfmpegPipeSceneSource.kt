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
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Handle for an open raw RGB24 byte stream (either a real `ffmpeg` child process or an offline
 * mock stream in unit tests).
 */
interface FfmpegStreamHandle : AutoCloseable {
    val inputStream: InputStream
}

/**
 * Factory opening a raw packed RGB24 byte stream at `[width] x [height]`.
 */
fun interface FfmpegStreamOpener {
    fun open(width: Int, height: Int): FfmpegStreamHandle
}

/**
 * Classified external input target for [FfmpegPipeSceneSource].
 */
sealed class FfmpegInputTarget {
    data class LocalVideoFile(val path: Path, val displayRef: String) : FfmpegInputTarget()
    data class NetworkStream(val uri: String, val scheme: String) : FfmpegInputTarget()
    data class WebcamDevice(val device: String) : FfmpegInputTarget()
}

/**
 * Cross-platform M-Optics P1 `ffmpeg-pipe` [SceneSource] ingesting local video files
 * (MP4/MKV/MOV/etc.), local webcams (V4L2 on Linux, DirectShow on Windows, AVFoundation on macOS),
 * or live RTSP/RTMP/HTTP/UDP streams via an asynchronous `ffmpeg` rawvideo RGB24 pipe.
 *
 * Design guarantees:
 * - **Non-blocking latest-frame slot**: background reader overwrites stale unread frames so
 *   network jitter or decode stalls never backpressure the ARM emulation owner thread.
 * - **Graceful fallback**: before the first frame arrives, or if `ffmpeg` is missing/crashes/disconnects,
 *   transparently falls back to the built-in QR / test-card fallback source while attempting
 *   bounded reconnection.
 * - **Zero native JNI dependencies**: uses only standard JVM [ProcessBuilder].
 */
class FfmpegPipeSceneSource internal constructor(
    val target: FfmpegInputTarget,
    val targetWidth: Int = DEFAULT_WIDTH,
    val targetHeight: Int = DEFAULT_HEIGHT,
    val loop: Boolean = true,
    private val streamOpener: FfmpegStreamOpener,
    private val fallback: FallbackSceneSource,
    private val reconnectDelayMs: Long = DEFAULT_RECONNECT_DELAY_MS,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    override val virtualSubjectDistanceM: Double? = null,
) : SceneSource2D, LiveStreamSceneSource {
    private val closed = AtomicBoolean(false)
    // A decoder can deliver the same RGB image for many consecutive video timestamps.
    private data class Delivery(val sequence: Long, val image: PackedRawFrame)

    private val latestFrame = AtomicReference<Delivery?>(null)
    private val activeHandle = AtomicReference<FfmpegStreamHandle?>(null)
    private val receivedCount = AtomicLong(0L)
    private val droppedCount = AtomicLong(0L)
    private val fallbackCount = AtomicLong(0L)
    private val reconnectCount = AtomicLong(0L)
    private val consumedSeq = AtomicLong(-1L)
    private val bayerCache = CachedPackedBayerConverter()

    @Volatile
    var lastError: String? = null
        private set

    private data class ConvertedFrame(val sequence: Long, val image: LinearRgbFrame)

    @Volatile
    private var cachedLinearFrame: ConvertedFrame? = null

    private val readerThread: Thread

    override val latestFrameSequence: Long get() = latestFrame.get()?.sequence ?: 0L
    override val fusedFrameSequence: Long? get() = latestFrame.get()?.image?.sequence

    override val activeSourceRef: String
        get() = when (target) {
            is FfmpegInputTarget.LocalVideoFile -> target.displayRef
            is FfmpegInputTarget.NetworkStream -> target.uri
            is FfmpegInputTarget.WebcamDevice -> "webcam:${target.device}"
        }
    override val sourceWidth: Int get() = latestFrame.get()?.image?.width ?: targetWidth
    override val sourceHeight: Int get() = latestFrame.get()?.image?.height ?: targetHeight
    override val hasDepthBuffer: Boolean get() = latestFrame.get()?.image?.depthMeters != null
    override val lastSourceError: String? get() = lastError
    override val framesReceived: Long get() = receivedCount.get()
    override val framesDropped: Long get() = droppedCount.get()
    val fallbackFramesServed: Long get() = fallbackCount.get()
    val reconnectAttempts: Long get() = reconnectCount.get()

    init {
        require(targetWidth in 2..MAX_DIMENSION && targetWidth % 2 == 0) {
            "ffmpeg-pipe width must be even in 2..$MAX_DIMENSION, got $targetWidth"
        }
        require(targetHeight in 2..MAX_DIMENSION && targetHeight % 2 == 0) {
            "ffmpeg-pipe height must be even in 2..$MAX_DIMENSION, got $targetHeight"
        }
        require(reconnectDelayMs >= 0L) { "reconnectDelayMs must be non-negative" }
        require(maxReconnectAttempts >= 0) { "maxReconnectAttempts must be non-negative" }

        readerThread = Thread(::readerLoop, "fuji-cmos-ffmpeg-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readerLoop() {
        val frameByteCount = Math.multiplyExact(Math.multiplyExact(targetWidth, targetHeight), 3)
        var attempts = 0
        while (!closed.get()) {
            var handle: FfmpegStreamHandle? = null
            try {
                handle = streamOpener.open(targetWidth, targetHeight)
                activeHandle.set(handle)
                val input = handle.inputStream
                attempts = 0
                lastError = null

                var buf = ByteArray(frameByteCount)
                while (!closed.get()) {
                    if (!readFully(input, buf)) {
                        break
                    }
                    val seq = receivedCount.incrementAndGet()
                    val previous = latestFrame.get()
                    val image = if (previous != null && previous.image.pixels.contentEquals(buf)) {
                        previous.image
                    } else {
                        val next = PackedRawFrame(targetWidth, targetHeight, RawPixelFormat.RGB24, buf, sequence = seq)
                        // Readers may retain any published image. Only an unpublished buffer may be refilled.
                        buf = ByteArray(frameByteCount)
                        next
                    }
                    latestFrame.set(Delivery(seq, image))
                    if (previous != null && previous.sequence > consumedSeq.get()) {
                        droppedCount.incrementAndGet()
                    }
                }
            } catch (err: Throwable) {
                if (!closed.get()) {
                    lastError = err.message ?: err.javaClass.simpleName
                }
            } finally {
                activeHandle.compareAndSet(handle, null)
                runCatching { handle?.close() }
            }

            if (closed.get()) break
            if (!loop && receivedCount.get() > 0L) {
                break
            }
            if (attempts >= maxReconnectAttempts) {
                break
            }
            attempts++
            reconnectCount.incrementAndGet()
            if (reconnectDelayMs > 0L) {
                try {
                    val delay = (reconnectDelayMs * (1L shl minOf(attempts - 1, 3))).coerceAtMost(MAX_BACKOFF_MS)
                    Thread.sleep(delay)
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
                throw EOFException("Truncated ffmpeg rawvideo frame ($offset/${target.size} bytes)")
            }
            offset += n
        }
        return true
    }

    override fun radiance(exposure: Exposure, geometry: SensorGeometry, destination: ByteBuffer) {
        check(!closed.get()) { "FFmpeg pipe scene source is closed" }
        val frame = latestFrame.get()
        if (frame == null) {
            fallbackCount.incrementAndGet()
            fallback.radiance(exposure, geometry, destination)
            return
        }
        consumedSeq.set(frame.sequence)
        bayerCache.writeCached(frame.image, exposure, geometry, destination)
    }

    override fun latestLinearRgbFrame(): LinearRgbFrame = linearFrame(latestFrame.get())

    private fun linearFrame(frame: Delivery?): LinearRgbFrame {
        check(!closed.get()) { "FFmpeg pipe scene source is closed" }
        if (frame == null) {
            fallbackCount.incrementAndGet()
            return fallback.latestLinearRgbFrame()
        }
        consumedSeq.set(frame.sequence)
        val cached = cachedLinearFrame
        if (cached != null && cached.sequence == frame.image.sequence) {
            return cached.image
        }
        val converted = SceneFrameCodec.toLinearRgbFrame(frame.image)
        cachedLinearFrame = ConvertedFrame(frame.image.sequence, converted)
        return converted
    }

    override fun samplePlanarRadiance(): LinearRadianceFrame {
        val frame = latestFrame.get()
        return linearFrame(frame).toRadianceFrame(
            dimension = SceneDimension.DIM_2D,
            virtualSubjectDistanceM = virtualSubjectDistanceM,
            sequence = frame?.image?.sequence ?: 0L,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { activeHandle.getAndSet(null)?.close() }
        readerThread.interrupt()
        runCatching { readerThread.join(1_000L) }
        latestFrame.set(null)
        cachedLinearFrame = null
        bayerCache.clear()
        fallback.close()
    }

    companion object {
        const val KIND = "ffmpeg-pipe"
        const val DEFAULT_WIDTH = 1024
        const val DEFAULT_HEIGHT = 576
        private const val MAX_DIMENSION = 4096
        private const val DEFAULT_RECONNECT_DELAY_MS = 250L
        private const val MAX_BACKOFF_MS = 2_000L
        private const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 8
        private val NETWORK_SCHEMES = setOf("rtsp", "rtsps", "rtmp", "rtmps", "http", "https", "udp")

        /**
         * Parses and validates an `ffmpeg-pipe` `sourceRef` into a strongly typed [FfmpegInputTarget].
         */
        fun parseAndValidateTarget(
            sourceRef: String,
            resolveDataPath: (String) -> Path,
        ): FfmpegInputTarget {
            val trimmed = sourceRef.trim()
            require(trimmed.isNotEmpty()) { "ffmpeg-pipe sourceFile must not be empty" }
            require(trimmed.none { it.isISOControl() }) {
                "ffmpeg-pipe sourceFile must not contain control characters"
            }

            if (trimmed.startsWith("webcam:", ignoreCase = true)) {
                val device = trimmed.substringAfter(':').trim()
                require(device.isNotEmpty()) { "ffmpeg-pipe webcam: target requires a device name or 'default'" }
                return FfmpegInputTarget.WebcamDevice(device)
            }

            val schemeEnd = trimmed.indexOf("://")
            if (schemeEnd > 0) {
                val scheme = trimmed.substring(0, schemeEnd).lowercase()
                if (scheme == "file") {
                    val uri = try {
                        URI(trimmed)
                    } catch (err: Exception) {
                        throw IllegalArgumentException("Invalid file URI for ffmpeg-pipe: $trimmed", err)
                    }
                    val path = Path.of(uri).normalize()
                    require(Files.isRegularFile(path)) { "ffmpeg-pipe local video file missing: $path" }
                    return FfmpegInputTarget.LocalVideoFile(path, trimmed)
                }
                require(scheme in NETWORK_SCHEMES) {
                    "Unsupported ffmpeg-pipe URI scheme '$scheme'; expected one of $NETWORK_SCHEMES or file://"
                }
                require(trimmed.none { it.isWhitespace() }) {
                    "Invalid ffmpeg-pipe stream URI: contains whitespace"
                }
                val parsed = try {
                    URI(trimmed)
                } catch (err: Exception) {
                    throw IllegalArgumentException("Invalid ffmpeg-pipe stream URI: $trimmed", err)
                }
                require(!parsed.host.isNullOrBlank()) {
                    "ffmpeg-pipe stream URI must include a non-empty host"
                }
                return FfmpegInputTarget.NetworkStream(trimmed, scheme)
            }

            // Relative path inside cmos data directory (or explicit existing file path)
            val resolved = resolveDataPath(trimmed)
            require(Files.isRegularFile(resolved)) { "CMOS video source missing: $trimmed" }
            return FfmpegInputTarget.LocalVideoFile(resolved, trimmed)
        }

        /**
         * Resolves the configured `ffmpeg` binary name or path across Linux/Windows/macOS.
         */
        fun resolveFfmpegExecutable(): String =
            System.getProperty("fuji.ffmpeg.bin")?.takeIf { it.isNotBlank() }
                ?: System.getenv("FUJI_FFMPEG_BIN")?.takeIf { it.isNotBlank() }
                ?: "ffmpeg"

        /**
         * Builds the cross-platform `ffmpeg` argument list outputting raw RGB24 frames to `pipe:1`.
         */
        fun buildFfmpegCommand(
            target: FfmpegInputTarget,
            width: Int,
            height: Int,
            loop: Boolean,
            osName: String = System.getProperty("os.name").orEmpty(),
            ffmpegExecutable: String = resolveFfmpegExecutable(),
            realTimePacing: Boolean = true,
        ): List<String> {
            val cmd = mutableListOf(ffmpegExecutable, "-nostdin", "-loglevel", "error")
            when (target) {
                is FfmpegInputTarget.LocalVideoFile -> {
                    if (loop) {
                        cmd += listOf("-stream_loop", "-1")
                    }
                    if (realTimePacing) {
                        cmd += "-re"
                    }
                    cmd += listOf("-i", target.path.toAbsolutePath().normalize().toString())
                }
                is FfmpegInputTarget.NetworkStream -> {
                    if (target.scheme == "rtsp" || target.scheme == "rtsps") {
                        cmd += listOf("-rtsp_transport", "tcp")
                    }
                    cmd += listOf("-i", target.uri)
                }
                is FfmpegInputTarget.WebcamDevice -> {
                    val lowerOs = osName.lowercase()
                    when {
                        "win" in lowerOs -> {
                            val dev = if (target.device.equals("default", ignoreCase = true)) {
                                "video=Integrated Camera"
                            } else if (target.device.startsWith("video=", ignoreCase = true)) {
                                target.device
                            } else {
                                "video=${target.device}"
                            }
                            cmd += listOf("-f", "dshow", "-i", dev)
                        }
                        "mac" in lowerOs || "darwin" in lowerOs -> {
                            val dev = if (target.device.equals("default", ignoreCase = true)) "0" else target.device
                            cmd += listOf("-f", "avfoundation", "-i", dev)
                        }
                        else -> {
                            val dev = if (target.device.equals("default", ignoreCase = true)) "/dev/video0" else target.device
                            cmd += listOf("-f", "v4l2", "-i", dev)
                        }
                    }
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
         * Default [FfmpegStreamOpener] spawning a child `ffmpeg` process via [ProcessBuilder].
         */
        fun processStreamOpener(
            target: FfmpegInputTarget,
            loop: Boolean,
            ffmpegExecutable: String = resolveFfmpegExecutable(),
        ): FfmpegStreamOpener = FfmpegStreamOpener { width, height ->
            val command = buildFfmpegCommand(
                target = target,
                width = width,
                height = height,
                loop = loop,
                ffmpegExecutable = ffmpegExecutable,
            )
            val process = try {
                ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()
            } catch (err: IOException) {
                throw IOException("Unable to start ffmpeg ($ffmpegExecutable): ${err.message}", err)
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
    }
}

/**
 * Built-in M-Optics P1 `ffmpeg-pipe` (and alias kinds `VIDEO_FILE`, `RTMP_RTSP`, `WEBCAM`)
 * [SceneSourceProvider] registered in [SceneSourceRegistry].
 */
class FfmpegPipeSceneSourceProvider(
    private val targetWidth: Int = FfmpegPipeSceneSource.DEFAULT_WIDTH,
    private val targetHeight: Int = FfmpegPipeSceneSource.DEFAULT_HEIGHT,
    private val customOpenerFactory: ((FfmpegInputTarget, Boolean) -> FfmpegStreamOpener)? = null,
    private val reconnectDelayMs: Long = 250L,
    private val maxReconnectAttempts: Int = 8,
) : SceneSourceProvider {
    override val supportedKinds: Set<String> = linkedSetOf(
        FfmpegPipeSceneSource.KIND,
        KIND_VIDEO_FILE,
        KIND_RTMP_RTSP,
        KIND_WEBCAM,
    )
    override val capability: SceneSourceCapability = SceneSourceCapability.READY

    override fun pipelineStatus(kind: String): String = when (kind) {
        FfmpegPipeSceneSource.KIND -> "ffmpeg-pipe-rawvideo-readout"
        KIND_VIDEO_FILE -> "video-file-ffmpeg-readout"
        KIND_RTMP_RTSP -> "rtmp-rtsp-ffmpeg-readout"
        KIND_WEBCAM -> "webcam-ffmpeg-readout"
        else -> throw IllegalArgumentException("Unsupported ffmpeg-backed kind: $kind")
    }

    private fun parseTargetForKind(request: SceneSourceRequest): FfmpegInputTarget = when (request.kind) {
        FfmpegPipeSceneSource.KIND ->
            FfmpegPipeSceneSource.parseAndValidateTarget(request.sourceRef, request.resolveDataPath)
        KIND_VIDEO_FILE -> {
            val target = FfmpegPipeSceneSource.parseAndValidateTarget(request.sourceRef, request.resolveDataPath)
            require(target is FfmpegInputTarget.LocalVideoFile) {
                "VIDEO_FILE sourceFile must reference a local video file or file:// URI: ${request.sourceRef}"
            }
            target
        }
        KIND_RTMP_RTSP -> {
            val target = FfmpegPipeSceneSource.parseAndValidateTarget(request.sourceRef, request.resolveDataPath)
            require(target is FfmpegInputTarget.NetworkStream && target.scheme in RTMP_RTSP_SCHEMES) {
                "RTMP_RTSP sourceFile must use rtmp://, rtmps://, rtsp://, or rtsps:// scheme: ${request.sourceRef}"
            }
            target
        }
        KIND_WEBCAM -> {
            val trimmed = request.sourceRef.trim()
            require(trimmed.isNotEmpty() && trimmed.none { it.isISOControl() }) {
                "WEBCAM sourceFile must specify a valid camera device name or 'default'"
            }
            val normalized = if (trimmed.startsWith("webcam:", ignoreCase = true)) {
                trimmed
            } else {
                "webcam:$trimmed"
            }
            FfmpegPipeSceneSource.parseAndValidateTarget(normalized, request.resolveDataPath)
        }
        else -> throw IllegalArgumentException(
            "Provider for $supportedKinds cannot handle ${request.kind}",
        )
    }

    override fun validate(request: SceneSourceRequest) {
        parseTargetForKind(request)
    }

    override fun open(request: SceneSourceRequest): SceneSource {
        val target = parseTargetForKind(request)
        val opener = customOpenerFactory?.invoke(target, request.loop)
            ?: FfmpegPipeSceneSource.processStreamOpener(target, request.loop)
        val fallback = SceneFrameCodec.createFallbackSource(request.resolveDataPath)
        return FfmpegPipeSceneSource(
            target = target,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            loop = request.loop,
            streamOpener = opener,
            fallback = fallback,
            reconnectDelayMs = reconnectDelayMs,
            maxReconnectAttempts = maxReconnectAttempts,
        )
    }

    companion object {
        const val KIND_VIDEO_FILE = "VIDEO_FILE"
        const val KIND_RTMP_RTSP = "RTMP_RTSP"
        const val KIND_WEBCAM = "WEBCAM"
        private val RTMP_RTSP_SCHEMES = setOf("rtmp", "rtmps", "rtsp", "rtsps")
    }
}
