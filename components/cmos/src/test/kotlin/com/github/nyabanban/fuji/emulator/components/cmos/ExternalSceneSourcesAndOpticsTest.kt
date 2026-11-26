package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

class ExternalSceneSourcesAndOpticsTest {
    private val defaultExposure = Exposure(
        startTick = 0L,
        durationNanos = 1000L,
        iso = 100,
        lens = LensParameters("gf-63", 63.0, 2.8, 10.0, 0.9, 0.0),
    )

    private class Harness(
        private val dir: Path,
        private val lensFilter: (LensPort)? = null,
    ) {
        lateinit var sensorPort: SensorPort
        val context = object : ComponentContext {
            override val directory: Path = dir
            override val clock = EmulationClock { 0L }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    if (type == SensorPort::class.java) {
                        sensorPort = port as SensorPort
                    }
                }

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> find(name: String, type: Class<T>): T? {
                    if (name == "lens" && type == LensPort::class.java && lensFilter != null) {
                        return lensFilter as T
                    }
                    return null
                }

                override fun removeOwner(owner: String) {}
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) {}
                override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = AutoCloseable {}
            }

            override fun dataPath(relative: String): Path {
                val resolved = dir.resolve(relative).normalize()
                require(resolved.startsWith(dir) && resolved != dir) { "path outside dir" }
                return resolved
            }
        }
    }

    private fun withTempDir(action: (Path) -> Unit) {
        val dir = Files.createTempDirectory("cmos-external-optics-test-")
        try {
            action(dir)
        } finally {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private fun solidRgb24(w: Int, h: Int, r: Int, g: Int, b: Int): ByteArray {
        val out = ByteArray(w * h * 3)
        var i = 0
        while (i < out.size) {
            out[i++] = r.toByte()
            out[i++] = g.toByte()
            out[i++] = b.toByte()
        }
        return out
    }

    private fun solidBgra32(w: Int, h: Int, r: Int, g: Int, b: Int, a: Int = 255): ByteArray {
        val out = ByteArray(w * h * 4)
        var i = 0
        while (i < out.size) {
            out[i++] = b.toByte()
            out[i++] = g.toByte()
            out[i++] = r.toByte()
            out[i++] = a.toByte()
        }
        return out
    }

    private fun readRggbCenterQuad(buf: ByteBuffer, width: Int = 1024, height: Int = 576): Triple<Int, Int, Int> {
        val x = (width / 2) and -2
        val y = (height / 2) and -2
        val bb = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val r = bb.getShort((y * width + x) * 2).toInt() and 0xFFFF
        val g = bb.getShort((y * width + x + 1) * 2).toInt() and 0xFFFF
        val b = bb.getShort(((y + 1) * width + x + 1) * 2).toInt() and 0xFFFF
        return Triple(r, g, b)
    }

    private fun waitUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(10L)
        }
        assertTrue(predicate(), "Condition did not become true within ${timeoutMs}ms")
    }

    private fun writeSolidJpeg(path: Path, width: Int, height: Int, color: Color) {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        ImageIO.write(img, "JPEG", path.toFile())
    }

    private fun encodeSolidPngBytes(width: Int, height: Int, color: Color): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", baos)
        return baos.toByteArray()
    }

    @Test
    fun registryIncludesFfmpegPipeAndStreamIngestAsReady() {
        val registry = SceneSourceRegistry.defaultRegistry()
        val kinds = registry.registeredKinds()
        assertTrue("ffmpeg-pipe" in kinds)
        assertTrue("stream-ingest" in kinds)
        assertEquals(SceneSourceCapability.READY, registry.providerFor("ffmpeg-pipe").capability)
        assertEquals(SceneSourceCapability.READY, registry.providerFor("stream-ingest").capability)
    }

    @Test
    fun ffmpegPipeBuildsCrossPlatformCommandsForFilesStreamsAndWebcams() = withTempDir { dir ->
        val clip = dir.resolve("demo.mp4")
        Files.writeString(clip, "dummy")
        val fileTarget = FfmpegInputTarget.LocalVideoFile(clip, "demo.mp4")
        val fileCmd = FfmpegPipeSceneSource.buildFfmpegCommand(fileTarget, 1024, 576, loop = true, ffmpegExecutable = "ffmpeg")
        assertTrue("-stream_loop" in fileCmd && "-1" in fileCmd)
        assertTrue("-re" in fileCmd)
        assertTrue("-pix_fmt" in fileCmd && "rgb24" in fileCmd)
        assertEquals("pipe:1", fileCmd.last())

        val rtspTarget = FfmpegInputTarget.NetworkStream("rtsp://127.0.0.1:8554/live", "rtsp")
        val rtspCmd = FfmpegPipeSceneSource.buildFfmpegCommand(rtspTarget, 640, 360, loop = false, ffmpegExecutable = "ffmpeg")
        assertTrue("-rtsp_transport" in rtspCmd && "tcp" in rtspCmd)
        assertTrue("rtsp://127.0.0.1:8554/live" in rtspCmd)

        val camTarget = FfmpegInputTarget.WebcamDevice("default")
        val linuxCmd = FfmpegPipeSceneSource.buildFfmpegCommand(camTarget, 640, 360, loop = true, osName = "Linux")
        assertTrue("v4l2" in linuxCmd && "/dev/video0" in linuxCmd)

        val winCmd = FfmpegPipeSceneSource.buildFfmpegCommand(camTarget, 640, 360, loop = true, osName = "Windows 11")
        assertTrue("dshow" in winCmd && "video=Integrated Camera" in winCmd)

        val winNamedCmd = FfmpegPipeSceneSource.buildFfmpegCommand(
            FfmpegInputTarget.WebcamDevice("OBS Virtual Camera"),
            640,
            360,
            loop = true,
            osName = "Windows 10",
        )
        assertTrue("dshow" in winNamedCmd && "video=OBS Virtual Camera" in winNamedCmd)

        val macCmd = FfmpegPipeSceneSource.buildFfmpegCommand(camTarget, 640, 360, loop = true, osName = "Mac OS X")
        assertTrue("avfoundation" in macCmd && "0" in macCmd)
    }

    @Test
    fun ffmpegPipeValidationRejectsInvalidTargetsAndAcceptsValidOnes() = withTempDir { dir ->
        val clip = dir.resolve("video.mp4")
        Files.writeString(clip, "mp4-placeholder")
        val resolve: (String) -> Path = { rel -> dir.resolve(rel).normalize() }

        assertFailsWith<IllegalArgumentException> { FfmpegPipeSceneSource.parseAndValidateTarget("", resolve) }
        assertFailsWith<IllegalArgumentException> { FfmpegPipeSceneSource.parseAndValidateTarget("missing.mp4", resolve) }
        assertFailsWith<IllegalArgumentException> { FfmpegPipeSceneSource.parseAndValidateTarget("webcam:", resolve) }
        assertFailsWith<IllegalArgumentException> { FfmpegPipeSceneSource.parseAndValidateTarget("ftp://127.0.0.1/v", resolve) }
        assertFailsWith<IllegalArgumentException> { FfmpegPipeSceneSource.parseAndValidateTarget("rtsp://bad host/live", resolve) }
        assertFailsWith<IllegalArgumentException> { FfmpegPipeSceneSource.parseAndValidateTarget("rtsp:///nohost", resolve) }

        assertTrue(FfmpegPipeSceneSource.parseAndValidateTarget("video.mp4", resolve) is FfmpegInputTarget.LocalVideoFile)
        assertTrue(FfmpegPipeSceneSource.parseAndValidateTarget(clip.toUri().toString(), resolve) is FfmpegInputTarget.LocalVideoFile)
        assertTrue(FfmpegPipeSceneSource.parseAndValidateTarget("rtsp://127.0.0.1:8554/stream", resolve) is FfmpegInputTarget.NetworkStream)
        assertTrue(FfmpegPipeSceneSource.parseAndValidateTarget("rtmp://127.0.0.1:1935/live", resolve) is FfmpegInputTarget.NetworkStream)
        assertTrue(FfmpegPipeSceneSource.parseAndValidateTarget("webcam:default", resolve) is FfmpegInputTarget.WebcamDevice)
    }

    @Test
    fun ffmpegPipeIngestsMultiFrameRawVideoDropsStaleFramesAndRecoversFromDisconnect() = withTempDir { dir ->
        val w = 32
        val h = 18
        val redFrame = solidRgb24(w, h, 240, 20, 20)
        val greenFrame = solidRgb24(w, h, 20, 240, 20)
        val blueFrame = solidRgb24(w, h, 20, 20, 240)

        val openCount = AtomicInteger(0)
        val pipeOut1 = PipedOutputStream()
        val pipeIn1 = PipedInputStream(pipeOut1, w * h * 3 * 12)
        val pipeOut2 = PipedOutputStream()
        val pipeIn2 = PipedInputStream(pipeOut2, w * h * 3 * 4)

        val opener = FfmpegStreamOpener { _, _ ->
            val attempt = openCount.incrementAndGet()
            when (attempt) {
                1 -> object : FfmpegStreamHandle {
                    override val inputStream: InputStream = pipeIn1
                    override fun close() {
                        runCatching { pipeIn1.close() }
                    }
                }
                2 -> throw IOException("simulated transient stream disconnect")
                else -> object : FfmpegStreamHandle {
                    override val inputStream: InputStream = pipeIn2
                    override fun close() {
                        runCatching { pipeIn2.close() }
                    }
                }
            }
        }

        val provider = FfmpegPipeSceneSourceProvider(
            targetWidth = w,
            targetHeight = h,
            customOpenerFactory = { _, _ -> opener },
            reconnectDelayMs = 15L,
            maxReconnectAttempts = 6,
        )
        val registry = SceneSourceRegistry.defaultRegistry().withProvider(provider)
        val factory = CmosFactory(registry)
        val hCmos = Harness(dir)
        val cmos = factory.create(
            hCmos.context,
            mapOf("sourceKind" to "ffmpeg-pipe", "sourceFile" to "rtsp://127.0.0.1:8554/mock", "loop" to true),
        )

        try {
            // Before first frame arrives: graceful fallback to procedural QR/test card
            val fallbackBuf = ByteBuffer.allocate(1024 * 576 * 2)
            assertEquals(1024 * 576 * 2, hCmos.sensorPort.expose(defaultExposure, fallbackBuf))
            assertTrue(fallbackBuf.array().any { it != 0.toByte() }, "Fallback frame must contain non-zero test card")

            // Push burst of 6 frames faster than consumer to verify non-blocking latest-frame overwrite
            repeat(5) { pipeOut1.write(redFrame) }
            pipeOut1.write(greenFrame)
            pipeOut1.flush()

            // Wait until reader ingests the latest green frame
            val bufGreen = ByteBuffer.allocate(1024 * 576 * 2)
            waitUntil {
                bufGreen.clear()
                hCmos.sensorPort.expose(defaultExposure, bufGreen)
                val (r, g, b) = readRggbCenterQuad(bufGreen)
                g > r * 4 && g > b * 4
            }

            // Close first stream -> triggers attempt #2 (transient IOException) -> reconnects to attempt #3
            pipeOut1.close()
            pipeOut2.write(blueFrame)
            pipeOut2.flush()

            val bufBlue = ByteBuffer.allocate(1024 * 576 * 2)
            waitUntil {
                bufBlue.clear()
                hCmos.sensorPort.expose(defaultExposure, bufBlue)
                val (r, g, b) = readRggbCenterQuad(bufBlue)
                b > r * 4 && b > g * 4
            }
            assertTrue(openCount.get() >= 3, "Expected reconnect attempts after disconnect, got ${openCount.get()}")
        } finally {
            runCatching { pipeOut1.close() }
            runCatching { pipeOut2.close() }
            cmos.close()
        }
    }

    @Test
    fun identicalFfmpegDeliveriesSharePixelsUntilEvenOneChannelChanges() = withTempDir { dir ->
        val output = PipedOutputStream()
        val input = PipedInputStream(output, 4096)
        val opener = FfmpegStreamOpener { _, _ ->
            object : FfmpegStreamHandle {
                override val inputStream: InputStream = input
                override fun close() = input.close()
            }
        }
        val source = FfmpegPipeSceneSource(
            FfmpegInputTarget.NetworkStream("rtsp://localhost/source", "rtsp"), 32, 18, false,
            opener, SceneFrameCodec.createFallbackSource { dir.resolve(it) },
        )
        output.use {
            source.use {
                val white = solidRgb24(32, 18, 255, 255, 255)
                fun deliver(pixels: ByteArray, sequence: Long) {
                    output.write(pixels)
                    output.flush()
                    waitUntil { source.latestFrameSequence == sequence }
                }
                deliver(white, 1)
                val first = source.samplePlanarRadiance()
                deliver(white, 2)
                val twin = source.samplePlanarRadiance()
                assertEquals(first.sequence, source.fusedFrameSequence)
                assertEquals(first.sequence, twin.sequence)
                assertSame(first.red, twin.red)
                assertSame(first.green, twin.green)
                assertSame(first.blue, twin.blue)
                val changed = white.copyOf().also { it[it.lastIndex] = 254.toByte() }
                deliver(changed, 3)
                val next = source.samplePlanarRadiance()
                assertNotEquals(first.sequence, next.sequence)
                assertEquals(next.sequence, source.fusedFrameSequence)
                assertTrue(next.blue.last() < first.blue.last())
                deliver(white, 4)
                assertEquals(1f, source.samplePlanarRadiance().blue.last())
                assertTrue(next.blue.last() < 1f)
                assertEquals(4L, source.framesReceived)
                assertEquals(0L, source.framesDropped)
            }
        }
    }

    @Test
    fun ffmpegPipeFallsBackGracefullyWhenExecutableMissing() = withTempDir { dir ->
        val fallback = SceneFrameCodec.createFallbackSource { rel -> dir.resolve(rel) }
        val source = FfmpegPipeSceneSource(
            target = FfmpegInputTarget.NetworkStream("rtsp://127.0.0.1:8554/missing", "rtsp"),
            targetWidth = 32,
            targetHeight = 18,
            loop = true,
            streamOpener = FfmpegPipeSceneSource.processStreamOpener(
                target = FfmpegInputTarget.NetworkStream("rtsp://127.0.0.1:8554/missing", "rtsp"),
                loop = true,
                ffmpegExecutable = "definitely-missing-fuji-ffmpeg-binary-xyz",
            ),
            fallback = fallback,
            reconnectDelayMs = 5L,
            maxReconnectAttempts = 1,
        )
        try {
            waitUntil { source.lastError != null }
            val out = ByteBuffer.allocate(128 * 128 * 2)
            val geom = SensorGeometry(128, 128, 16, BayerPattern.RGGB, 3.76)
            source.radiance(defaultExposure, geom, out)
            assertTrue(source.fallbackFramesServed >= 1L)
            assertTrue(out.array().any { it != 0.toByte() })
        } finally {
            source.close()
        }
    }

    @Test
    fun streamIngestValidationEnforcesLoopbackOnly() {
        assertFailsWith<IllegalArgumentException> { StreamIngestSceneSource.parseAndValidateEndpoint("") }
        assertFailsWith<IllegalArgumentException> { StreamIngestSceneSource.parseAndValidateEndpoint("tcp://0.0.0.0:9555") }
        assertFailsWith<IllegalArgumentException> { StreamIngestSceneSource.parseAndValidateEndpoint("tcp://192.168.1.50:9555") }
        assertFailsWith<IllegalArgumentException> { StreamIngestSceneSource.parseAndValidateEndpoint("ftp://127.0.0.1:9555") }
        assertFailsWith<IllegalArgumentException> { StreamIngestSceneSource.parseAndValidateEndpoint("tcp://127.0.0.1") }

        val tcp = StreamIngestSceneSource.parseAndValidateEndpoint("tcp://127.0.0.1:0")
        assertEquals(StreamIngestTransport.TCP_HTTP, tcp.transport)
        assertEquals(0, tcp.port)

        val udp = StreamIngestSceneSource.parseAndValidateEndpoint("udp://localhost:9556")
        assertEquals(StreamIngestTransport.UDP, udp.transport)
        assertEquals(9556, udp.port)

        val short = StreamIngestSceneSource.parseAndValidateEndpoint(":9557")
        assertEquals(StreamIngestTransport.TCP_HTTP, short.transport)
        assertEquals(9557, short.port)
    }

    @Test
    fun streamIngestTcpFscmSupportsRgbBgraAndDepthPlane() = withTempDir { dir ->
        val factory = CmosFactory()
        val h = Harness(dir)
        val cmos = factory.create(
            h.context,
            mapOf("sourceKind" to "stream-ingest", "sourceFile" to "tcp://127.0.0.1:0", "loop" to true),
        )
        try {
            val port = cmos.state()["streamIngestPort"] as Int
            assertTrue(port > 0)

            // Pre-frame graceful fallback
            val preBuf = ByteBuffer.allocate(1024 * 576 * 2)
            assertEquals(1024 * 576 * 2, h.sensorPort.expose(defaultExposure, preBuf))
            assertTrue(preBuf.array().any { it != 0.toByte() })

            val w = 32
            val hPx = 18
            Socket(InetAddress.getLoopbackAddress(), port).use { sock ->
                val out = sock.getOutputStream()
                // 1) RGB24 bright red
                val rgb = solidRgb24(w, hPx, 240, 10, 10)
                out.write(StreamIngestSceneSource.encodeFscmHeader(w, hPx, RawPixelFormat.RGB24, rgb.size))
                out.write(rgb)
                out.flush()

                val redBuf = ByteBuffer.allocate(1024 * 576 * 2)
                waitUntil {
                    redBuf.clear()
                    h.sensorPort.expose(defaultExposure, redBuf)
                    val (r, g, b) = readRggbCenterQuad(redBuf)
                    r > g * 4 && r > b * 4
                }

                // 2) BGRA32 bright blue with Float32 depth plane
                val bgra = solidBgra32(w, hPx, 10, 10, 245)
                val depthBytes = ByteBuffer.allocate(w * hPx * 4).order(ByteOrder.LITTLE_ENDIAN)
                repeat(w * hPx) { depthBytes.putFloat(3.5f) }
                val totalPayload = bgra.size + depthBytes.capacity()
                out.write(
                    StreamIngestSceneSource.encodeFscmHeader(
                        width = w,
                        height = hPx,
                        format = RawPixelFormat.BGRA32,
                        payloadBytes = totalPayload,
                        hasDepthF32 = true,
                    ),
                )
                out.write(bgra)
                out.write(depthBytes.array())
                out.flush()

                val blueBuf = ByteBuffer.allocate(1024 * 576 * 2)
                waitUntil {
                    blueBuf.clear()
                    h.sensorPort.expose(defaultExposure, blueBuf)
                    val (r, g, b) = readRggbCenterQuad(blueBuf)
                    b > r * 4 && b > g * 4
                }
            }

            // Re-applying settings on the same fixed port reuses listener cleanly without BindException
            val fixedCfg = mapOf("sourceKind" to "stream-ingest", "sourceFile" to "tcp://127.0.0.1:$port", "loop" to true)
            cmos.close()
            val h2 = Harness(dir)
            val cmos2 = factory.create(h2.context, fixedCfg)
            try {
                cmos2.applySettings(mapOf("loop" to false))
                cmos2.applySettings(mapOf("loop" to true))
                assertEquals(port, cmos2.state()["streamIngestPort"])
            } finally {
                cmos2.close()
            }
        } finally {
            cmos.close()
        }
    }

    @Test
    fun streamIngestHttpPostFrameAndStatusEndpoint() = withTempDir { dir ->
        val factory = CmosFactory()
        val h = Harness(dir)
        val cmos = factory.create(
            h.context,
            mapOf("sourceKind" to "stream-ingest", "sourceFile" to "http://127.0.0.1:0", "loop" to true),
        )
        try {
            val port = cmos.state()["streamIngestPort"] as Int
            assertTrue(port > 0)

            // GET /status
            Socket(InetAddress.getLoopbackAddress(), port).use { sock ->
                sock.getOutputStream().write("GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                sock.getOutputStream().flush()
                val resp = sock.getInputStream().readAllBytes().toString(Charsets.UTF_8)
                assertTrue(resp.startsWith("HTTP/1.1 200 OK"), resp)
                assertTrue("\"protocol\":\"stream-ingest\"" in resp, resp)
            }

            // POST /frame raw RGB24 green
            val green = solidRgb24(16, 16, 10, 240, 10)
            Socket(InetAddress.getLoopbackAddress(), port).use { sock ->
                val req = "POST /frame?width=16&height=16&format=rgb24 HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: ${green.size}\r\nConnection: close\r\n\r\n"
                sock.getOutputStream().write(req.toByteArray())
                sock.getOutputStream().write(green)
                sock.getOutputStream().flush()
                val resp = sock.getInputStream().readAllBytes().toString(Charsets.UTF_8)
                assertTrue(resp.startsWith("HTTP/1.1 200 OK"), resp)
            }
            val bufGreen = ByteBuffer.allocate(1024 * 576 * 2)
            waitUntil {
                bufGreen.clear()
                h.sensorPort.expose(defaultExposure, bufGreen)
                val (r, g, b) = readRggbCenterQuad(bufGreen)
                g > r * 4 && g > b * 4
            }

            // POST /frame image/png red
            val png = encodeSolidPngBytes(16, 16, Color(240, 10, 10))
            Socket(InetAddress.getLoopbackAddress(), port).use { sock ->
                val req = "POST /frame HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\nContent-Type: image/png\r\n" +
                    "Content-Length: ${png.size}\r\nConnection: close\r\n\r\n"
                sock.getOutputStream().write(req.toByteArray())
                sock.getOutputStream().write(png)
                sock.getOutputStream().flush()
                val resp = sock.getInputStream().readAllBytes().toString(Charsets.UTF_8)
                assertTrue(resp.startsWith("HTTP/1.1 200 OK"), resp)
            }
            val bufRed = ByteBuffer.allocate(1024 * 576 * 2)
            waitUntil {
                bufRed.clear()
                h.sensorPort.expose(defaultExposure, bufRed)
                val (r, g, b) = readRggbCenterQuad(bufRed)
                r > g * 4 && r > b * 4
            }
        } finally {
            cmos.close()
        }
    }

    @Test
    fun streamIngestUdpFsudReassemblesMultiChunkFrames() = withTempDir { dir ->
        val factory = CmosFactory()
        val h = Harness(dir)
        val cmos = factory.create(
            h.context,
            mapOf("sourceKind" to "stream-ingest", "sourceFile" to "udp://127.0.0.1:0", "loop" to true),
        )
        try {
            val port = cmos.state()["streamIngestPort"] as Int
            assertTrue(port > 0)

            val w = 32
            val hPx = 32
            val blue = solidRgb24(w, hPx, 12, 16, 244)
            val chunkCount = 4
            val chunkSize = blue.size / chunkCount
            DatagramSocket().use { sock ->
                val addr = InetAddress.getLoopbackAddress()
                // Send chunks out of order to verify offset-based reassembly
                for (idx in listOf(2, 0, 3, 1)) {
                    val offset = idx * chunkSize
                    val hdr = StreamIngestSceneSource.encodeFsudHeader(
                        frameId = 7,
                        width = w,
                        height = hPx,
                        format = RawPixelFormat.RGB24,
                        chunkIndex = idx,
                        chunkCount = chunkCount,
                        chunkOffset = offset,
                    )
                    val pktBytes = hdr + blue.copyOfRange(offset, offset + chunkSize)
                    sock.send(DatagramPacket(pktBytes, pktBytes.size, addr, port))
                }
            }

            val buf = ByteBuffer.allocate(1024 * 576 * 2)
            waitUntil {
                buf.clear()
                h.sensorPort.expose(defaultExposure, buf)
                val (r, g, b) = readRggbCenterQuad(buf)
                b > r * 4 && b > g * 4
            }
        } finally {
            cmos.close()
        }
    }

    @Test
    fun cmosOpticalSceneFilterBypassedWhenDisabledAndInvokedAcrossAllSourcesWhenEnabled() = withTempDir { dir ->
        val jpg = dir.resolve("card.jpg")
        writeSolidJpeg(jpg, 32, 32, Color(200, 120, 60))

        var filterEnabled = false
        var filterInvocations = 0
        var lastSawDepth = false
        var lastSrcWidth = 0

        val stubLensFilter = object : LensPort, OpticalSceneFilter {
            override fun parameters(): LensParameters = defaultExposure.lens
            override val enabled: Boolean get() = filterEnabled
            override fun filterLinearRgbToBayer(
                srcWidth: Int,
                srcHeight: Int,
                red: FloatArray,
                green: FloatArray,
                blue: FloatArray,
                depthMeters: FloatArray?,
                exposure: Exposure,
                geometry: SensorGeometry,
                destination: ByteBuffer,
                frameIndex: Long,
            ): Int {
                filterInvocations++
                lastSrcWidth = srcWidth
                lastSawDepth = depthMeters != null && depthMeters.size == srcWidth * srcHeight
                val bytes = geometry.width * geometry.height * 2
                // Write a recognizable marker pattern (0x1234 LE)
                repeat(geometry.width * geometry.height) {
                    destination.put(0x34.toByte())
                    destination.put(0x12.toByte())
                }
                return bytes
            }
        }

        val factory = CmosFactory()
        val hPlain = Harness(dir, lensFilter = null)
        val cmosPlain = factory.create(hPlain.context, mapOf("sourceKind" to "JPEG", "sourceFile" to "card.jpg", "loop" to true))
        val hFiltered = Harness(dir, lensFilter = stubLensFilter)
        val cmosFiltered = factory.create(hFiltered.context, mapOf("sourceKind" to "JPEG", "sourceFile" to "card.jpg", "loop" to true))

        try {
            // Disabled filter -> bit-exact match with unfiltered CMOS, zero filter calls
            assertEquals(false, cmosFiltered.state()["opticsFilterActive"])
            val plainOut = ByteBuffer.allocate(1024 * 576 * 2)
            val bypassedOut = ByteBuffer.allocate(1024 * 576 * 2)
            hPlain.sensorPort.expose(defaultExposure, plainOut)
            hFiltered.sensorPort.expose(defaultExposure, bypassedOut)
            assertContentEquals(plainOut.array(), bypassedOut.array())
            assertEquals(0, filterInvocations)

            // Enable optical filter live on static JPEG
            filterEnabled = true
            assertEquals(true, cmosFiltered.state()["opticsFilterActive"])
            val filteredJpegOut = ByteBuffer.allocate(1024 * 576 * 2)
            hFiltered.sensorPort.expose(defaultExposure, filteredJpegOut)
            assertEquals(1, filterInvocations)
            assertEquals(32, lastSrcWidth)
            assertFalse(lastSawDepth)
            assertEquals(0x34.toByte(), filteredJpegOut.array()[0])
            assertEquals(0x12.toByte(), filteredJpegOut.array()[1])

            // Switch live to stream-ingest and push a frame with depth buffer -> filter receives depthMeters!
            cmosFiltered.applySettings(
                mapOf("sourceKind" to "stream-ingest", "sourceFile" to "tcp://127.0.0.1:0", "loop" to true),
            )
            val port = cmosFiltered.state()["streamIngestPort"] as Int
            val w = 16
            val hPx = 16
            val rgb = solidRgb24(w, hPx, 100, 150, 200)
            val depthRaw = ByteBuffer.allocate(w * hPx * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(w * hPx) { depthRaw.putFloat(5.0f) }
            Socket(InetAddress.getLoopbackAddress(), port).use { sock ->
                val out = sock.getOutputStream()
                out.write(
                    StreamIngestSceneSource.encodeFscmHeader(
                        width = w,
                        height = hPx,
                        format = RawPixelFormat.RGB24,
                        payloadBytes = rgb.size + depthRaw.capacity(),
                        hasDepthF32 = true,
                    ),
                )
                out.write(rgb)
                out.write(depthRaw.array())
                out.flush()
            }
            val filteredStreamOut = ByteBuffer.allocate(1024 * 576 * 2)
            waitUntil {
                filteredStreamOut.clear()
                hFiltered.sensorPort.expose(defaultExposure, filteredStreamOut)
                lastSawDepth && lastSrcWidth == w
            }
            assertTrue(lastSawDepth)
            assertEquals(w, lastSrcWidth)
        } finally {
            cmosPlain.close()
            cmosFiltered.close()
        }
    }
}
