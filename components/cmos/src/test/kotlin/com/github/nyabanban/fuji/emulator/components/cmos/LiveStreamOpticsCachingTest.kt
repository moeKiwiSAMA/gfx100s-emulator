package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveStreamOpticsCachingTest {
    private val defaultExposure = Exposure(
        startTick = 0L,
        durationNanos = 1000L,
        iso = 100,
        lens = LensParameters("gf-63", 63.0, 2.8, 10.0, 0.9, 0.0),
    )

    private class Harness(
        private val dir: Path,
        private val lensFilter: LensPort? = null,
    ) {
        var tick = 0L
        lateinit var sensorPort: SensorPort
        val context = object : ComponentContext {
            override val directory: Path = dir
            override val clock = EmulationClock { tick }
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

            override fun dataPath(relative: String): Path = dir.resolve(relative).normalize()
        }
    }

    @Test
    fun movingFusedKeysTrackTheCameraClockAndExposure() {
        checkMovingFusedKey("milk-frog", 900_000L)
        checkMovingFusedKey("qr-ball", 900_001L)
    }

    private fun checkMovingFusedKey(sceneName: String, firstBoundary: Long) {
        val dir = Files.createTempDirectory("moving-scene-fused")
        val lens = object : LensPort, OpticalSceneFilter {
            override val enabled = true
            override fun parameters() = defaultExposure.lens
            override fun opticalStateVersion() = 1L
            override fun filterLinearRgbToBayer(
                srcWidth: Int, srcHeight: Int, red: FloatArray, green: FloatArray, blue: FloatArray, depthMeters: FloatArray?,
                exposure: Exposure, geometry: SensorGeometry, destination: ByteBuffer, frameIndex: Long,
            ) = 0
        }
        val harness = Harness(dir, lens)
        val cmos = CmosFactory().create(harness.context, mapOf(
            "sourceKind" to "optical-scene", "sourceFile" to "scene://$sceneName", "loop" to true,
        ))
        try {
            val sensor = harness.sensorPort
            val first = sensor.fusedFrameStateKey(defaultExposure)
            assertTrue(first >= 0)
            harness.tick = firstBoundary - 1L
            assertEquals(first, sensor.fusedFrameStateKey(defaultExposure))
            harness.tick = firstBoundary
            val next = sensor.fusedFrameStateKey(defaultExposure)
            assertTrue(next >= 0 && next != first)
            assertTrue(next != sensor.fusedFrameStateKey(defaultExposure.copy(iso = 200)))
            assertTrue(next != sensor.fusedFrameStateKey(defaultExposure.copy(lens = defaultExposure.lens.copy(focusDistanceM = 1.0))))
        } finally {
            cmos.close()
            dir.toFile().deleteRecursively()
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

    @Test
    fun cachedPackedBayerConverterReusesFrameForSameSequenceAndInvalidatesOnNewSequence() {
        val converter = CachedPackedBayerConverter()
        val geom = SensorGeometry(64, 36, 16, BayerPattern.RGGB, 3.76)
        val frame1 = PackedRawFrame(16, 16, RawPixelFormat.RGB24, solidRgb24(16, 16, 220, 40, 20), sequence = 1L)
        val frame2 = PackedRawFrame(16, 16, RawPixelFormat.RGB24, solidRgb24(16, 16, 20, 40, 220), sequence = 2L)

        val out1 = ByteBuffer.allocate(64 * 36 * 2)
        val out1Repeat = ByteBuffer.allocate(64 * 36 * 2)
        val out2 = ByteBuffer.allocate(64 * 36 * 2)

        converter.writeCached(frame1, defaultExposure, geom, out1)
        assertEquals(1L, converter.cacheMisses)
        assertEquals(0L, converter.cacheHits)

        converter.writeCached(frame1, defaultExposure, geom, out1Repeat)
        assertEquals(1L, converter.cacheMisses)
        assertEquals(1L, converter.cacheHits)
        assertContentEquals(out1.array(), out1Repeat.array())

        converter.writeCached(frame2, defaultExposure, geom, out2)
        assertEquals(2L, converter.cacheMisses)
        assertEquals(1L, converter.cacheHits)
        assertTrue(!out1.array().contentEquals(out2.array()))
    }

    @Test
    fun liveStreamAsyncOpticsWorkerKeepsExposeNonBlockingAndUpdatesDoubleBuffer() {
        val dir = Files.createTempDirectory("cmos-async-optics-test-")
        try {
            val invocations = AtomicInteger(0)
            val slowFilter = object : LensPort, OpticalSceneFilter {
                override fun parameters(): LensParameters = defaultExposure.lens
                override val enabled: Boolean = true
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
                    val call = invocations.incrementAndGet()
                    if (call > 1) {
                        Thread.sleep(60L)
                    }
                    val marker = (red[0] * 255f).toInt().coerceIn(0, 255).toByte()
                    val bytes = geometry.width * geometry.height * 2
                    val buf = ByteArray(bytes) { marker }
                    destination.put(buf)
                    return bytes
                }
            }

            val factory = CmosFactory()
            val harness = Harness(dir, lensFilter = slowFilter)
            val cmos = factory.create(
                harness.context,
                mapOf("sourceKind" to "stream-ingest", "sourceFile" to "tcp://127.0.0.1:0", "loop" to true),
            )
            try {
                val port = cmos.state()["streamIngestPort"] as Int
                val w = 16
                val h = 16
                Socket(InetAddress.getLoopbackAddress(), port).use { sock ->
                    val out = sock.getOutputStream()
                    // Push frame 1 (bright red)
                    val red = solidRgb24(w, h, 240, 10, 10)
                    out.write(StreamIngestSceneSource.encodeFscmHeader(w, h, RawPixelFormat.RGB24, red.size))
                    out.write(red)
                    out.flush()

                    val deadline1 = System.nanoTime() + 3_000_000_000L
                    while (System.nanoTime() < deadline1 && (cmos.state()["framesReceived"] as Long) < 1L) {
                        Thread.sleep(10L)
                    }
                    assertTrue((cmos.state()["framesReceived"] as Long) >= 1L)

                    // First live frame renders synchronously so output immediately reflects frame 1
                    val buf1 = ByteBuffer.allocate(1024 * 576 * 2)
                    harness.sensorPort.expose(defaultExposure, buf1)
                    val marker1 = buf1.array()[0].toInt() and 0xFF
                    assertTrue(marker1 > 150, "First live frame must render synchronously (marker=$marker1)")

                    // Push frame 2 (dark red = low marker)
                    val dark = solidRgb24(w, h, 20, 10, 240)
                    out.write(StreamIngestSceneSource.encodeFscmHeader(w, h, RawPixelFormat.RGB24, dark.size))
                    out.write(dark)
                    out.flush()

                    val deadline2 = System.nanoTime() + 3_000_000_000L
                    while (System.nanoTime() < deadline2 && (cmos.state()["framesReceived"] as Long) < 2L) {
                        Thread.sleep(10L)
                    }

                    // Expose immediately returns without waiting for the 60ms slow optical filter!
                    val buf2 = ByteBuffer.allocate(1024 * 576 * 2)
                    val startNs = System.nanoTime()
                    harness.sensorPort.expose(defaultExposure, buf2)
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
                    assertTrue(elapsedMs < 35.0, "Async double-buffered expose should not block on 60ms filter (took ${elapsedMs}ms)")

                    // Background worker converges to frame 2 cleanly
                    val deadline3 = System.nanoTime() + 3_000_000_000L
                    var sawDark = false
                    while (System.nanoTime() < deadline3) {
                        buf2.clear()
                        harness.sensorPort.expose(defaultExposure, buf2)
                        if ((buf2.array()[0].toInt() and 0xFF) < 20) {
                            sawDark = true
                            break
                        }
                        Thread.sleep(15L)
                    }
                    assertTrue(sawDark, "Double-buffered async optics worker must publish updated frame 2")
                }
            } finally {
                cmos.close()
            }
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun static2dSceneSourceUsesAsyncDoubleBufferOnLiveViewAndSyncDeterministicOnStillCapture() {
        val dir = Files.createTempDirectory("cmos-static2d-async-test-")
        try {
            val jpg = dir.resolve("portrait.jpg")
            val img = java.awt.image.BufferedImage(64, 36, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().apply {
                    color = java.awt.Color(180, 120, 80)
                    fillRect(0, 0, 64, 36)
                    dispose()
                }
            }
            javax.imageio.ImageIO.write(img, "JPEG", jpg.toFile())

            val owner = Thread.currentThread()
            val workerStarted = CountDownLatch(1)
            val workerFinished = CountDownLatch(1)
            val releaseWorker = CountDownLatch(1)
            val slowFilter = object : LensPort, OpticalSceneFilter {
                override fun parameters(): LensParameters = defaultExposure.lens
                override val enabled: Boolean = true
                override fun opticalStateVersion(): Long = 1L
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
                    if (exposure.lens.aperture == 1.4 && releaseWorker.count > 0L) {
                        assertTrue(Thread.currentThread() !== owner, "Slider rendering must run on the background worker")
                        workerStarted.countDown()
                        try {
                            check(releaseWorker.await(10, TimeUnit.SECONDS)) { "Background render waited for owner-thread exposure" }
                        } finally {
                            workerFinished.countDown()
                        }
                    }
                    val marker = (exposure.lens.aperture * 10.0).toInt().coerceIn(0, 255).toByte()
                    val bytes = geometry.width * geometry.height * 2
                    val buf = ByteArray(bytes) { marker }
                    destination.put(buf)
                    return bytes
                }
            }

            val factory = CmosFactory()
            val harness = Harness(dir, lensFilter = slowFilter)
            val cmos = factory.create(
                harness.context,
                mapOf("sourceKind" to "JPEG", "sourceFile" to "portrait.jpg", "loop" to true),
            )
            try {
                // 1. Cold-start LiveView frame renders synchronously with f/2.8 marker (28)
                val buf1 = ByteBuffer.allocate(1024 * 576 * 2)
                harness.sensorPort.expose(defaultExposure, buf1)
                assertEquals(28, buf1.array()[0].toInt() and 0xFF, "Cold-start static 2D frame must render synchronously")
                val gen1 = harness.sensorPort.currentFrameGeneration()
                assertTrue(gen1 > 0L, "CmosFactory must expose positive monotonic currentFrameGeneration()")

                // 2. Repeated LiveView frame with unchanged exposure keeps generation constant
                val buf1Repeat = ByteBuffer.allocate(1024 * 576 * 2)
                harness.sensorPort.expose(defaultExposure, buf1Repeat)
                assertEquals(gen1, harness.sensorPort.currentFrameGeneration(), "Unchanged static 2D frame must keep currentFrameGeneration constant")

                // Hold the background frame in flight while the owner serves 50 slider updates.
                val expWide = defaultExposure.copy(lens = defaultExposure.lens.copy(aperture = 1.4))
                val bufDrag = ByteBuffer.allocate(1024 * 576 * 2)
                try {
                    harness.sensorPort.expose(expWide, bufDrag)
                    assertTrue(workerStarted.await(10, TimeUnit.SECONDS), "Background worker must start rendering f/1.4")
                    bufDrag.clear()
                    harness.sensorPort.expose(defaultExposure, bufDrag)
                    assertContentEquals(buf1.array(), bufDrag.array(), "The published front buffer must remain readable")
                    val threadBean = ManagementFactory.getThreadMXBean()
                    val measureCpu = threadBean.isCurrentThreadCpuTimeSupported && threadBean.isThreadCpuTimeEnabled
                    val cpuStart = if (measureCpu) threadBean.currentThreadCpuTime else 0L
                    val start = System.nanoTime()
                    repeat(50) { i ->
                        bufDrag.clear()
                        val fStop = 1.4 + (i % 10) * 0.4
                        harness.sensorPort.expose(defaultExposure.copy(lens = defaultExposure.lens.copy(aperture = fStop)), bufDrag)
                        assertEquals(bufDrag.capacity(), bufDrag.position(), "Owner must return a complete frame while rendering is blocked")
                    }
                    val wallMs = (System.nanoTime() - start) / 50_000_000.0
                    if (measureCpu) {
                        val cpuMs = (threadBean.currentThreadCpuTime - cpuStart) / 50_000_000.0
                        println("[W10-BENCH] Static 2D slider-drag owner CPU: ${"%.3f".format(cpuMs)} ms/call")
                        assertTrue(cpuMs < 5.0, "Slider drag exceeded the owner CPU budget: ${"%.3f".format(cpuMs)} ms/call")
                    }
                    println("[W10-BENCH] Static 2D slider-drag owner wall: ${"%.3f".format(wallMs)} ms/call")
                    assertEquals(1L, workerFinished.count, "Owner must finish all exposures while the worker is paused")
                } finally {
                    releaseWorker.countDown()
                }

                // Wait for background worker to publish updated f/1.4 frame (marker 14) and bump generation
                val deadline = System.nanoTime() + 3_000_000_000L
                var sawWide = false
                while (System.nanoTime() < deadline) {
                    bufDrag.clear()
                    harness.sensorPort.expose(expWide, bufDrag)
                    if ((bufDrag.array()[0].toInt() and 0xFF) == 14) {
                        sawWide = true
                        break
                    }
                    Thread.sleep(10L)
                }
                assertTrue(sawWide, "Background worker must publish updated f/1.4 static 2D frame")
                assertTrue(harness.sensorPort.currentFrameGeneration() > gen1, "Published async swap must advance currentFrameGeneration()")

                // 4. Deterministic STILL capture (`exposeStill`) renders synchronously on first call with f/8.0 (marker 80)
                val expStill = defaultExposure.copy(lens = defaultExposure.lens.copy(aperture = 8.0))
                val bufStill = ByteBuffer.allocate(1024 * 576 * 2)
                harness.sensorPort.exposeStill(expStill, bufStill)
                assertEquals(80, bufStill.array()[0].toInt() and 0xFF, "exposeStill() must render synchronously and deterministically")

                val repeatedStill = ByteBuffer.allocate(bufStill.capacity())
                harness.sensorPort.exposeStill(expStill, repeatedStill)
                assertContentEquals(bufStill.array(), repeatedStill.array())
            } finally {
                cmos.close()
            }
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun unifiedRadiancePipelineSupportsLegacyLinearRgbFrameSourceAndCachesStatic2DWrapper() {
        val dir = Files.createTempDirectory("cmos-unified-pipeline-test")
        try {
            // 1. StaticImageSceneSource.samplePlanarRadiance() caches the wrapper (zero per-frame allocation)
            val jpg = dir.resolve("cached.jpg")
            val img = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB).apply {
                for (y in 0 until height) for (x in 0 until width) setRGB(x, y, 0xFF4080C0.toInt())
            }
            ImageIO.write(img, "JPEG", jpg.toFile())
            val staticSrc = StaticImageSceneSource.validateAndLoad(jpg, "cached.jpg", StaticImageSceneSource.Format.JPEG, loop = true)
            try {
                val f1 = staticSrc.samplePlanarRadiance()
                val f2 = staticSrc.samplePlanarRadiance()
                assertTrue(f1 === f2, "StaticImageSceneSource.samplePlanarRadiance() must cache LinearRadianceFrame wrapper")
            } finally {
                staticSrc.close()
            }

            // 2. External legacy LinearRgbFrameSource-only backend still works through unified expose() + tryExposeFusedIspYuv()
            val legacyKind = "LEGACY_LINEAR_ONLY"
            val legacyProvider = object : SceneSourceProvider {
                override val supportedKinds: Set<String> = setOf(legacyKind)
                override val capability: SceneSourceCapability = SceneSourceCapability.READY
                override fun pipelineStatus(kind: String): String = "legacy-linear-only"
                override fun validate(request: SceneSourceRequest) = Unit
                override fun open(request: SceneSourceRequest): SceneSource = object : SceneSource, LinearRgbFrameSource {
                    private val linear = LinearRgbFrame(
                        width = 4,
                        height = 4,
                        red = FloatArray(16) { 0.5f },
                        green = FloatArray(16) { 0.4f },
                        blue = FloatArray(16) { 0.3f },
                    )
                    override fun radiance(exposure: Exposure, geometry: SensorGeometry, destination: ByteBuffer) {
                        SceneFrameCodec.writeLinearRadianceToBayer(linear.toRadianceFrame(sequence = 1L), exposure, geometry, destination)
                    }
                    override fun latestLinearRgbFrame(): LinearRgbFrame = linear
                    override fun close() = Unit
                }
            }
            val filterCalls = AtomicInteger(0)
            val fusedCalls = AtomicInteger(0)
            val mockFilter = object : LensPort, OpticalSceneFilter {
                override val enabled: Boolean = true
                override fun parameters(): LensParameters = defaultExposure.lens
                override fun opticalStateVersion(): Long = 1L
                override fun filterLinearRgbToBayer(
                    srcWidth: Int, srcHeight: Int,
                    red: FloatArray, green: FloatArray, blue: FloatArray, depthMeters: FloatArray?,
                    exposure: Exposure, geometry: SensorGeometry,
                    destination: ByteBuffer, frameIndex: Long,
                ): Int {
                    filterCalls.incrementAndGet()
                    val bytes = geometry.width * geometry.height * 2
                    destination.put(ByteArray(bytes) { 0x2A })
                    return bytes
                }
                override fun tryFilterRadianceToIspYuv(
                    frame: LinearRadianceFrame, geometry: SensorGeometry, request: LiveViewIspFusionRequest,
                    outYOrPacked: ByteArray, outUvPlane: ByteArray?, frameIndex: Long,
                ): Boolean {
                    fusedCalls.incrementAndGet()
                    outYOrPacked.fill(0x55)
                    return true
                }
            }

            val factory = CmosFactory(SceneSourceRegistry.defaultRegistry().withProvider(legacyProvider))
            val harness = Harness(dir, lensFilter = mockFilter)
            val cmos = factory.create(
                harness.context,
                mapOf("sourceKind" to legacyKind, "sourceFile" to "inline://legacy", "loop" to true),
            )
            try {
                val out = ByteBuffer.allocate(1024 * 576 * 2)
                harness.sensorPort.expose(defaultExposure, out)
                assertEquals(1, filterCalls.get(), "Unified expose() must route legacy LinearRgbFrameSource through OpticalSceneFilter")
                assertEquals(0x2A, out.array()[0].toInt() and 0xFF)

                val yuv = ByteArray(16)
                val fusedOk = harness.sensorPort.tryExposeFusedIspYuv(
                    request = LiveViewIspFusionRequest(
                        exposure = defaultExposure,
                        dstWidth = 4, dstHeight = 2, dstStrideBytes = 8,
                        outputFormat = LiveViewIspFusionRequest.FMT_UYVY422,
                        flags = 0,
                        offX = 0, offY = 0, fitW = 4, fitH = 2,
                        gainR1024 = 1024, gainG1024 = 1024, gainB1024 = 1024,
                        yuvShift = 10,
                    ),
                    outYOrPacked = yuv,
                    outUvPlane = null,
                )
                assertTrue(fusedOk, "Unified tryExposeFusedIspYuv() must route legacy LinearRgbFrameSource cleanly")
                assertEquals(1, fusedCalls.get())
                assertEquals(0x55, yuv[0].toInt() and 0xFF)
            } finally {
                cmos.close()
            }
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun asyncOpticsWorkerReflectsExposureChangeOnFrameZeroWithZeroAeLag() {
        val dir = Files.createTempDirectory("cmos-async-ae-zero-lag-test-")
        try {
            val jpg = dir.resolve("gray.jpg")
            val img = BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().apply {
                    color = java.awt.Color(128, 128, 128)
                    fillRect(0, 0, 64, 36)
                    dispose()
                }
            }
            ImageIO.write(img, "JPEG", jpg.toFile())

            val blackLevel = 256
            val baseDurationNs = 10_000_000L
            val baseIso = 100
            val baseF = 2.8
            var hotStage1Cache = false
            val filterInvocations = AtomicInteger(0)

            val linearExposureFilter = object : LensPort, OpticalSceneFilter {
                override fun parameters(): LensParameters = defaultExposure.lens
                override val enabled: Boolean = true
                override fun opticalStateVersion(): Long = 1L
                override fun sensorBlackLevelAdu(bitDepth: Int): Int = blackLevel
                override fun hasHotStage1CacheForExposureOnlyChange(): Boolean = hotStage1Cache
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
                    val call = filterInvocations.incrementAndGet()
                    if (call > 1 && !hotStage1Cache) {
                        Thread.sleep(60L)
                    }
                    val invF = baseF / exposure.lens.aperture
                    val relScale = (exposure.durationNanos.toDouble() / baseDurationNs.toDouble()) *
                        (exposure.iso.toDouble() / baseIso.toDouble()) *
                        invF * invF
                    // Exact linear ADU above blackLevel: nominal signal = 10_000 ADU at base exposure
                    val adu = (blackLevel + (10_000.0 * relScale)).toInt().coerceIn(0, 65535)
                    val lo = (adu and 0xFF).toByte()
                    val hi = ((adu ushr 8) and 0xFF).toByte()
                    val bytes = geometry.width * geometry.height * 2
                    val out = ByteArray(bytes)
                    var i = 0
                    while (i < bytes) {
                        out[i] = lo
                        out[i + 1] = hi
                        i += 2
                    }
                    destination.put(out)
                    return bytes
                }
            }

            fun readFirstPixel16(buf: ByteBuffer): Int {
                val arr = buf.array()
                return (arr[0].toInt() and 0xFF) or ((arr[1].toInt() and 0xFF) shl 8)
            }

            val baseExp = Exposure(
                startTick = 0L,
                durationNanos = baseDurationNs,
                iso = baseIso,
                lens = LensParameters("gf-63", 63.0, baseF, 10.0, 0.9, 0.0),
            )
            val factory = CmosFactory()
            val harness = Harness(dir, lensFilter = linearExposureFilter)
            val cmos = factory.create(
                harness.context,
                mapOf("sourceKind" to "JPEG", "sourceFile" to "gray.jpg", "loop" to true),
            )
            try {
                // 1. Cold-start render at base exposure -> 10,256 ADU (256 BL + 10,000 linear)
                val buf = ByteBuffer.allocate(1024 * 576 * 2)
                harness.sensorPort.expose(baseExp, buf)
                assertEquals(10_256, readFirstPixel16(buf), "Baseline render must produce 10,256 ADU")
                val gen0 = harness.sensorPort.currentFrameGeneration()

                // 2. +1 EV shutter doubling (10ms -> 20ms): MUST reflect exact 2x signal on FRAME 0 with ZERO lag!
                buf.clear()
                val t0 = System.nanoTime()
                harness.sensorPort.expose(baseExp.copy(durationNanos = baseDurationNs * 2L), buf)
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
                val adu2x = readFirstPixel16(buf)
                assertTrue(elapsedMs < 30.0, "Zero-lag exposure scale must stay non-blocking (took ${"%.2f".format(elapsedMs)} ms)")
                assertTrue(
                    kotlin.math.abs(adu2x - (blackLevel + 20_000)) <= 2,
                    "Frame 0 after +1 EV duration change must immediately scale signal 2x (got $adu2x, expected 20256)",
                )
                val gen1 = harness.sensorPort.currentFrameGeneration()
                assertTrue(gen1 > gen0, "Frame 0 exposure scaling must bump currentFrameGeneration() immediately")

                // 3. ISO 100 -> ISO 200 (+1 EV) on Frame 0
                buf.clear()
                harness.sensorPort.expose(baseExp.copy(iso = baseIso * 2), buf)
                val aduIso2x = readFirstPixel16(buf)
                assertTrue(
                    kotlin.math.abs(aduIso2x - (blackLevel + 20_000)) <= 2,
                    "Frame 0 after +1 EV ISO change must immediately scale signal 2x (got $aduIso2x)",
                )

                // 4. Aperture f/2.8 -> f/5.6 (-2 EV, 0.25x) on Frame 0
                buf.clear()
                harness.sensorPort.expose(baseExp.copy(lens = baseExp.lens.copy(aperture = baseF * 2.0)), buf)
                val aduQuarter = readFirstPixel16(buf)
                assertTrue(
                    kotlin.math.abs(aduQuarter - (blackLevel + 2_500)) <= 2,
                    "Frame 0 after -2 EV aperture change must immediately scale signal 0.25x (got $aduQuarter)",
                )

                // 5. Large >1 EV exposure-only jump with hot Stage-1 cache -> instant synchronous re-readout (avoids scaling clipped highlights)
                // First let worker settle on saturated +3 EV frame (clipped at 65535)
                val callsBeforeHot = filterInvocations.get()
                hotStage1Cache = true
                buf.clear()
                harness.sensorPort.expose(baseExp.copy(durationNanos = baseDurationNs * 8L), buf)
                assertEquals(65535, readFirstPixel16(buf), "Hot Stage-1 large +3 EV jump must saturate at 65535")
                assertTrue(filterInvocations.get() > callsBeforeHot, "Hot Stage-1 >1 EV jump must execute sync Stage-2 re-readout")

                // Now drop -3 EV back to base exposure: sync Stage-2 re-readout recovers exact 10,256 ADU instead of scaling clipped 65535/8 = 8,416!
                buf.clear()
                harness.sensorPort.expose(baseExp, buf)
                val recoveredAdu = readFirstPixel16(buf)
                assertEquals(10_256, recoveredAdu, "Hot Stage-1 -3 EV jump must recover exact unclipped 10,256 ADU via sync Stage-2 re-readout")
            } finally {
                cmos.close()
            }
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
