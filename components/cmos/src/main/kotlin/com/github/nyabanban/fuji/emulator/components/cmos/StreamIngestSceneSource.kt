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
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

/**
 * Transport mode for [StreamIngestSceneSource].
 */
enum class StreamIngestTransport {
    /** Loopback TCP server auto-detecting both binary `FSCM` frames and HTTP `POST /frame`. */
    TCP_HTTP,

    /** Loopback UDP datagram listener receiving `FSUD` single-packet or chunked frames. */
    UDP,
}

/**
 * Validated bind target for [StreamIngestSceneSource].
 */
data class StreamIngestEndpoint(
    val transport: StreamIngestTransport,
    val host: String,
    val port: Int,
)

/**
 * Low-latency local `stream-ingest` [SceneSource] allowing external 3D/capture bridges
 * (OBS, Minecraft, VRChat, Spout, FFmpeg, or Python scripts) to push live 30/60 fps frames
 * directly into the virtual GFX100S CMOS sensor over loopback TCP, HTTP, or UDP.
 *
 * ### Supported Wire Protocols (Zero Extra Dependencies)
 * 1. **Binary TCP (`FSCM`)** — persistent socket, 16-byte little-endian header:
 *    - `0..3`: ASCII `"FSCM"`
 *    - `4..5`: `width` (`u16`, `2..4096`)
 *    - `6..7`: `height` (`u16`, `2..4096`)
 *    - `8`: `pixelFormat` (`0 = RGB24`, `1 = RGBA32`, `2 = BGRA32`)
 *    - `9`: `flags` (`bit 0 = trailing Float32 depth-in-metres plane of width*height*4 bytes`)
 *    - `10..11`: reserved (`0`)
 *    - `12..15`: `payloadBytes` (`u32`)
 *    - Followed by `payloadBytes` raw frame (+ optional depth) bytes.
 * 2. **HTTP `POST /frame`** (auto-detected on the same TCP port):
 *    - `GET /status` -> `200 OK` JSON telemetry.
 *    - `POST /frame?width=W&height=H&format=rgb24|rgba|bgra` -> raw pixel body, OR
 *      `Content-Type: image/jpeg` / `image/png` decoded via `ImageIO`.
 * 3. **UDP Datagrams (`FSUD`)** (`udp://127.0.0.1:<port>`):
 *    - 20-byte little-endian header: `magic="FSUD"(4)`, `frameId(u32)`, `width(u16)`,
 *      `height(u16)`, `pixelFormat(u8)`, `chunkIndex(u8)`, `chunkCount(u8)`, `flags(u8)`,
 *      `chunkOffset(u32)` followed by chunk payload bytes.
 *
 * Frames overwrite an atomic latest-frame slot so the ARM emulation owner thread never blocks;
 * before the first valid frame arrives, the source gracefully renders the built-in QR/test card.
 */
class StreamIngestSceneSource internal constructor(
    val endpoint: StreamIngestEndpoint,
    private val fallback: FallbackSceneSource,
    override val virtualSubjectDistanceM: Double? = null,
) : SceneSource2D, LiveStreamSceneSource {
    private val closed = AtomicBoolean(false)
    private val latestFrame = AtomicReference<PackedRawFrame?>(null)
    private val receivedCount = AtomicLong(0L)
    private val droppedCount = AtomicLong(0L)
    private val fallbackCount = AtomicLong(0L)
    private val consumedSeq = AtomicLong(-1L)
    private val bayerCache = CachedPackedBayerConverter()

    override val latestFrameSequence: Long get() = latestFrame.get()?.sequence ?: 0L

    @Volatile
    var lastProtocol: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var cachedLinearSeq: Long = -1L

    @Volatile
    private var cachedLinearFrame: LinearRgbFrame? = null

    private val tcpServer: ServerSocket?
    private val udpSocket: DatagramSocket?
    private val activeClientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val listenerThread: Thread

    /** Actual local TCP/UDP port bound by this ingest listener (resolves ephemeral port `0`). */
    val boundPort: Int
    override val activeSourceRef: String
        get() {
            val scheme = if (endpoint.transport == StreamIngestTransport.UDP) "udp" else "tcp"
            return "$scheme://${endpoint.host}:$boundPort"
        }
    override val sourceWidth: Int get() = latestFrame.get()?.width ?: 0
    override val sourceHeight: Int get() = latestFrame.get()?.height ?: 0
    override val hasDepthBuffer: Boolean get() = latestFrame.get()?.depthMeters != null
    override val lastSourceError: String? get() = lastError
    override val framesReceived: Long get() = receivedCount.get()
    override val framesDropped: Long get() = droppedCount.get()
    val fallbackFramesServed: Long get() = fallbackCount.get()

    init {
        val bindAddress = InetAddress.getLoopbackAddress()
        when (endpoint.transport) {
            StreamIngestTransport.TCP_HTTP -> {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(bindAddress, endpoint.port), 16)
                tcpServer = server
                udpSocket = null
                boundPort = server.localPort
                listenerThread = Thread(::tcpAcceptLoop, "fuji-cmos-stream-tcp-$boundPort").apply {
                    isDaemon = true
                    start()
                }
            }
            StreamIngestTransport.UDP -> {
                val socket = DatagramSocket(InetSocketAddress(bindAddress, endpoint.port))
                tcpServer = null
                udpSocket = socket
                boundPort = socket.localPort
                listenerThread = Thread(::udpReceiveLoop, "fuji-cmos-stream-udp-$boundPort").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    private fun publishFrame(
        width: Int,
        height: Int,
        format: RawPixelFormat,
        pixels: ByteArray,
        depthMeters: FloatArray?,
        protocol: String,
    ): Long {
        val seq = receivedCount.incrementAndGet()
        lastProtocol = protocol
        val frame = PackedRawFrame(
            width = width,
            height = height,
            format = format,
            pixels = pixels,
            depthMeters = depthMeters,
            sequence = seq,
        )
        val prev = latestFrame.getAndSet(frame)
        if (prev != null && prev.sequence > consumedSeq.get()) {
            droppedCount.incrementAndGet()
        }
        return seq
    }

    private fun tcpAcceptLoop() {
        val server = checkNotNull(tcpServer)
        while (!closed.get()) {
            val client = try {
                server.accept()
            } catch (err: Throwable) {
                if (!closed.get()) lastError = err.message ?: err.javaClass.simpleName
                break
            }
            if (closed.get()) {
                runCatching { client.close() }
                break
            }
            activeClientSockets.add(client)
            Thread({ handleTcpClient(client) }, "fuji-cmos-stream-client-${client.port}").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleTcpClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = CLIENT_READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream(), 65536)
            val output = socket.getOutputStream()
            input.mark(8)
            val probe = ByteArray(4)
            if (!readFullyOrEof(input, probe)) return
            input.reset()

            val tag = String(probe, Charsets.US_ASCII)
            when {
                tag == FSCM_MAGIC -> handleFscmStream(input)
                tag == "POST" || tag == "GET " -> handleHttpRequest(input, output)
                else -> {
                    lastError = "Unknown stream-ingest prefix: $tag"
                    writeHttpJson(output, 400, """{"ok":false,"error":"unsupported protocol prefix"}""")
                }
            }
        } catch (err: Throwable) {
            if (!closed.get()) {
                lastError = err.message ?: err.javaClass.simpleName
            }
        } finally {
            activeClientSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun handleFscmStream(input: InputStream) {
        val header = ByteArray(FSCM_HEADER_BYTES)
        while (!closed.get()) {
            if (!readFullyOrEof(input, header)) return
            if (
                header[0] != 'F'.code.toByte() ||
                header[1] != 'S'.code.toByte() ||
                header[2] != 'C'.code.toByte() ||
                header[3] != 'M'.code.toByte()
            ) {
                throw IllegalArgumentException("Invalid FSCM frame magic")
            }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val width = bb.getShort(4).toInt() and 0xFFFF
            val height = bb.getShort(6).toInt() and 0xFFFF
            val format = RawPixelFormat.fromCode(bb.get(8).toInt() and 0xFF)
            val flags = bb.get(9).toInt() and 0xFF
            val payloadBytes = bb.getInt(12).toLong() and 0xFFFF_FFFFL

            validateDimensions(width, height)
            val pixelCount = Math.multiplyExact(width, height)
            val pixelBytes = Math.multiplyExact(pixelCount, format.bytesPerPixel)
            val hasDepth = (flags and FLAG_HAS_DEPTH_F32) != 0
            val depthBytes = if (hasDepth) Math.multiplyExact(pixelCount, 4) else 0
            val expectedPayload = pixelBytes.toLong() + depthBytes.toLong()
            require(payloadBytes == expectedPayload && payloadBytes <= MAX_PAYLOAD_BYTES) {
                "Invalid FSCM payloadBytes=$payloadBytes (expected $expectedPayload for ${width}x${height} $format)"
            }

            val pixels = ByteArray(pixelBytes)
            readFullyStrict(input, pixels)
            val depth = if (hasDepth) {
                val depthRaw = ByteArray(depthBytes)
                readFullyStrict(input, depthRaw)
                val depthBuf = ByteBuffer.wrap(depthRaw).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(pixelCount) { depthBuf.float }
            } else {
                null
            }
            publishFrame(width, height, format, pixels, depth, "tcp-fscm")
        }
    }

    private fun handleHttpRequest(input: InputStream, output: OutputStream) {
        val requestLine = readHttpLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            writeHttpJson(output, 400, """{"ok":false,"error":"malformed request line"}""")
            return
        }
        val method = parts[0]
        val rawTarget = parts[1]
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readHttpLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }

        val path = rawTarget.substringBefore('?')
        val query = parseQuery(rawTarget.substringAfter('?', ""))

        if (method == "GET" && path == "/status") {
            val cur = latestFrame.get()
            val json = """{"ok":true,"protocol":"stream-ingest","boundPort":$boundPort,""" +
                """"framesReceived":${receivedCount.get()},"framesDropped":${droppedCount.get()},""" +
                """"fallbackFrames":${fallbackCount.get()},"width":${cur?.width ?: 0},"height":${cur?.height ?: 0}}"""
            writeHttpJson(output, 200, json)
            return
        }

        if (method != "POST" || path != "/frame") {
            writeHttpJson(output, 404, """{"ok":false,"error":"expected POST /frame or GET /status"}""")
            return
        }

        val contentLength = headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength <= 0L || contentLength > MAX_PAYLOAD_BYTES) {
            writeHttpJson(output, 400, """{"ok":false,"error":"missing or out-of-range Content-Length"}""")
            return
        }
        val body = ByteArray(contentLength.toInt())
        readFullyStrict(input, body)

        val contentType = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val seq = if (contentType == "image/jpeg" || contentType == "image/png") {
            val decoded = ImageIO.read(ByteArrayInputStream(body))
                ?: return writeHttpJson(output, 400, """{"ok":false,"error":"invalid image payload"}""")
            val w = decoded.width
            val h = decoded.height
            validateDimensions(w, h)
            val argb = decoded.getRGB(0, 0, w, h, null, 0, w)
            val rgb24 = ByteArray(w * h * 3)
            var dst = 0
            for (px in argb) {
                rgb24[dst++] = ((px ushr 16) and 0xFF).toByte()
                rgb24[dst++] = ((px ushr 8) and 0xFF).toByte()
                rgb24[dst++] = (px and 0xFF).toByte()
            }
            publishFrame(w, h, RawPixelFormat.RGB24, rgb24, null, "http-image")
        } else {
            val width = query["width"]?.toIntOrNull()
            val height = query["height"]?.toIntOrNull()
            if (width == null || height == null) {
                return writeHttpJson(output, 400, """{"ok":false,"error":"raw POST /frame requires width and height query params"}""")
            }
            validateDimensions(width, height)
            val format = query["format"]?.let { RawPixelFormat.fromName(it) } ?: RawPixelFormat.RGB24
            val expected = Math.multiplyExact(Math.multiplyExact(width, height), format.bytesPerPixel)
            if (body.size != expected) {
                return writeHttpJson(
                    output,
                    400,
                    """{"ok":false,"error":"body length ${body.size} != expected $expected"}""",
                )
            }
            publishFrame(width, height, format, body, null, "http-raw")
        }

        writeHttpJson(output, 200, """{"ok":true,"frameSeq":$seq}""")
    }

    private fun udpReceiveLoop() {
        val socket = checkNotNull(udpSocket)
        val packetBuf = ByteArray(65507)
        val packet = DatagramPacket(packetBuf, packetBuf.size)

        var currentFrameId = -1
        var currentWidth = 0
        var currentHeight = 0
        var currentFormat = RawPixelFormat.RGB24
        var currentChunkCount = 0
        var receivedChunks = 0
        var chunkBitmap = BooleanArray(0)
        var assemblyBuf = ByteArray(0)

        while (!closed.get()) {
            try {
                socket.receive(packet)
            } catch (err: Throwable) {
                if (!closed.get()) lastError = err.message ?: err.javaClass.simpleName
                break
            }
            val len = packet.length
            if (len < FSUD_HEADER_BYTES) continue
            if (
                packetBuf[0] != 'F'.code.toByte() ||
                packetBuf[1] != 'S'.code.toByte() ||
                packetBuf[2] != 'U'.code.toByte() ||
                packetBuf[3] != 'D'.code.toByte()
            ) {
                continue
            }
            try {
                val bb = ByteBuffer.wrap(packetBuf, 0, FSUD_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                val frameId = bb.getInt(4)
                val width = bb.getShort(8).toInt() and 0xFFFF
                val height = bb.getShort(10).toInt() and 0xFFFF
                val format = RawPixelFormat.fromCode(bb.get(12).toInt() and 0xFF)
                val chunkIdx = bb.get(13).toInt() and 0xFF
                val chunkCount = bb.get(14).toInt() and 0xFF
                val chunkOffset = bb.getInt(16)
                validateDimensions(width, height)
                if (chunkCount <= 0 || chunkIdx >= chunkCount || chunkOffset < 0) continue

                val totalBytes = Math.multiplyExact(Math.multiplyExact(width, height), format.bytesPerPixel)
                val sliceLen = len - FSUD_HEADER_BYTES
                if (chunkOffset.toLong() + sliceLen.toLong() > totalBytes.toLong()) continue

                if (frameId != currentFrameId || totalBytes != assemblyBuf.size || chunkCount != currentChunkCount) {
                    currentFrameId = frameId
                    currentWidth = width
                    currentHeight = height
                    currentFormat = format
                    currentChunkCount = chunkCount
                    receivedChunks = 0
                    chunkBitmap = BooleanArray(chunkCount)
                    assemblyBuf = ByteArray(totalBytes)
                }
                System.arraycopy(packetBuf, FSUD_HEADER_BYTES, assemblyBuf, chunkOffset, sliceLen)
                if (!chunkBitmap[chunkIdx]) {
                    chunkBitmap[chunkIdx] = true
                    receivedChunks++
                }
                if (receivedChunks == currentChunkCount) {
                    publishFrame(currentWidth, currentHeight, currentFormat, assemblyBuf.copyOf(), null, "udp-fsud")
                    currentFrameId = -1
                }
            } catch (err: Throwable) {
                lastError = err.message ?: err.javaClass.simpleName
            }
        }
    }

    override fun radiance(exposure: Exposure, geometry: SensorGeometry, destination: ByteBuffer) {
        check(!closed.get()) { "Stream ingest scene source is closed" }
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
        check(!closed.get()) { "Stream ingest scene source is closed" }
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
            dimension = if (hasDepthBuffer) SceneDimension.DIM_3D else SceneDimension.DIM_2D,
            virtualSubjectDistanceM = virtualSubjectDistanceM,
            sequence = latestFrameSequence,
        )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { tcpServer?.close() }
        runCatching { udpSocket?.close() }
        for (client in activeClientSockets) {
            runCatching { client.close() }
        }
        activeClientSockets.clear()
        listenerThread.interrupt()
        runCatching { listenerThread.join(1_000L) }
        latestFrame.set(null)
        cachedLinearFrame = null
        bayerCache.clear()
        fallback.close()
    }

    private fun readFullyOrEof(input: InputStream, target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) {
                if (offset == 0) return false
                throw EOFException("Truncated stream frame ($offset/${target.size} bytes)")
            }
            offset += n
        }
        return true
    }

    private fun readFullyStrict(input: InputStream, target: ByteArray) {
        if (!readFullyOrEof(input, target)) {
            throw EOFException("Unexpected EOF while reading ${target.size} bytes")
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val sb = StringBuilder(64)
        while (sb.length < 4096) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                    sb.setLength(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(b.toChar())
        }
        throw IllegalArgumentException("HTTP header line too long")
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = URLDecoder.decode(pair.substring(0, eq), Charsets.UTF_8)
                val v = URLDecoder.decode(pair.substring(eq + 1), Charsets.UTF_8)
                result[k] = v
            }
        }
        return result
    }

    private fun writeHttpJson(output: OutputStream, status: Int, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        val header = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    companion object {
        const val KIND = "stream-ingest"
        const val FSCM_MAGIC = "FSCM"
        const val FSUD_MAGIC = "FSUD"
        const val FSCM_HEADER_BYTES = 16
        const val FSUD_HEADER_BYTES = 20
        const val FLAG_HAS_DEPTH_F32 = 1
        private const val MAX_DIMENSION = 4096
        private const val MAX_PIXELS = 16_777_216L
        private const val MAX_PAYLOAD_BYTES = 64L * 1024L * 1024L
        private const val CLIENT_READ_TIMEOUT_MS = 10_000
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

        private fun validateDimensions(width: Int, height: Int) {
            require(width in 2..MAX_DIMENSION && height in 2..MAX_DIMENSION) {
                "Stream frame dimensions out of range: ${width}x$height"
            }
            require(width.toLong() * height.toLong() <= MAX_PIXELS) {
                "Stream frame pixel count exceeds $MAX_PIXELS: ${width}x$height"
            }
        }

        /**
         * Parses and validates a `stream-ingest` bind descriptor (loopback-only for security).
         *
         * Accepted forms:
         * - `tcp://127.0.0.1:9555`, `http://127.0.0.1:9555`
         * - `udp://127.0.0.1:9555`
         * - `127.0.0.1:9555`, `localhost:9555`, `:9555` (port `0` binds an ephemeral local port)
         */
        fun parseAndValidateEndpoint(sourceRef: String): StreamIngestEndpoint {
            val trimmed = sourceRef.trim()
            require(trimmed.isNotEmpty()) { "stream-ingest sourceFile must specify a bind endpoint (e.g., tcp://127.0.0.1:9555)" }
            require(trimmed.none { it.isISOControl() || it.isWhitespace() }) {
                "stream-ingest endpoint must not contain whitespace or control characters"
            }

            val normalized = when {
                trimmed.startsWith(":") -> "tcp://127.0.0.1$trimmed"
                "://" !in trimmed -> "tcp://$trimmed"
                else -> trimmed
            }
            val uri = try {
                URI(normalized)
            } catch (err: Exception) {
                throw IllegalArgumentException("Invalid stream-ingest endpoint: $sourceRef", err)
            }
            val scheme = uri.scheme?.lowercase()
            val transport = when (scheme) {
                "tcp", "http" -> StreamIngestTransport.TCP_HTTP
                "udp" -> StreamIngestTransport.UDP
                else -> throw IllegalArgumentException(
                    "Unsupported stream-ingest scheme '$scheme'; expected tcp://, http://, or udp://",
                )
            }
            val host = uri.host?.lowercase()
                ?: throw IllegalArgumentException("stream-ingest endpoint requires a host: $sourceRef")
            require(host in LOOPBACK_HOSTS) {
                "stream-ingest binds loopback only (127.0.0.1 / localhost); rejected host '$host'"
            }
            val port = uri.port
            require(port in 0..65535) {
                "stream-ingest endpoint requires an explicit port in 0..65535: $sourceRef"
            }
            return StreamIngestEndpoint(transport, host, port)
        }

        /** Builds a 16-byte `FSCM` binary TCP frame header. */
        fun encodeFscmHeader(
            width: Int,
            height: Int,
            format: RawPixelFormat,
            payloadBytes: Int,
            hasDepthF32: Boolean = false,
        ): ByteArray {
            val out = ByteArray(FSCM_HEADER_BYTES)
            out[0] = 'F'.code.toByte()
            out[1] = 'S'.code.toByte()
            out[2] = 'C'.code.toByte()
            out[3] = 'M'.code.toByte()
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.putShort(4, width.toShort())
            bb.putShort(6, height.toShort())
            bb.put(8, format.code.toByte())
            bb.put(9, (if (hasDepthF32) FLAG_HAS_DEPTH_F32 else 0).toByte())
            bb.putShort(10, 0)
            bb.putInt(12, payloadBytes)
            return out
        }

        /** Builds a 20-byte `FSUD` binary UDP chunk header. */
        fun encodeFsudHeader(
            frameId: Int,
            width: Int,
            height: Int,
            format: RawPixelFormat,
            chunkIndex: Int,
            chunkCount: Int,
            chunkOffset: Int,
        ): ByteArray {
            val out = ByteArray(FSUD_HEADER_BYTES)
            out[0] = 'F'.code.toByte()
            out[1] = 'S'.code.toByte()
            out[2] = 'U'.code.toByte()
            out[3] = 'D'.code.toByte()
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(4, frameId)
            bb.putShort(8, width.toShort())
            bb.putShort(10, height.toShort())
            bb.put(12, format.code.toByte())
            bb.put(13, chunkIndex.toByte())
            bb.put(14, chunkCount.toByte())
            bb.put(15, 0)
            bb.putInt(16, chunkOffset)
            return out
        }
    }
}

/**
 * Built-in M-Optics P1 `stream-ingest` [SceneSourceProvider] registered in [SceneSourceRegistry].
 */
class StreamIngestSceneSourceProvider : SceneSourceProvider {
    override val supportedKinds: Set<String> = setOf(StreamIngestSceneSource.KIND)
    override val capability: SceneSourceCapability = SceneSourceCapability.READY

    override fun pipelineStatus(kind: String): String = "stream-ingest-live-readout"

    override fun validate(request: SceneSourceRequest) {
        require(request.kind == StreamIngestSceneSource.KIND) {
            "Provider for ${StreamIngestSceneSource.KIND} cannot validate ${request.kind}"
        }
        StreamIngestSceneSource.parseAndValidateEndpoint(request.sourceRef)
    }

    override fun open(request: SceneSourceRequest): SceneSource {
        val endpoint = StreamIngestSceneSource.parseAndValidateEndpoint(request.sourceRef)
        val fallback = SceneFrameCodec.createFallbackSource(request.resolveDataPath)
        return StreamIngestSceneSource(endpoint = endpoint, fallback = fallback)
    }
}
