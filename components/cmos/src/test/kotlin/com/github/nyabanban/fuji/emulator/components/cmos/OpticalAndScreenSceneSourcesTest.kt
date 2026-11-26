package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class OpticalAndScreenSceneSourcesTest {
    private val defaultExposure = Exposure(
        startTick = 0L,
        durationNanos = 1000L,
        iso = 100,
        lens = LensParameters("gf-45", CameraPose3D.DEFAULT_FOCAL_LENGTH_MM, 4.0, 2.5, 0.9, 0.0),
    )

    private class Harness(
        private val dir: Path,
        private val lensFilter: LensPort? = null,
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
        val dir = Files.createTempDirectory("cmos-optical-screen-test-")
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

    @Test
    fun registryRegistersOpticalSceneAndScreenCaptureAndFfmpegAliasesAsReady() {
        val registry = SceneSourceRegistry.defaultRegistry()
        for (kind in listOf("optical-scene", "screen-capture", "VIDEO_FILE", "RTMP_RTSP", "WEBCAM", "ffmpeg-pipe", "stream-ingest", "JPEG", "PNG")) {
            assertTrue(kind in registry.registeredKinds(), "Missing sourceKind $kind")
            assertEquals(SceneSourceCapability.READY, registry.providerFor(kind).capability, "Expected READY for $kind")
        }
    }

    @Test
    fun opticalSceneSynthesizesThreeDepthPlanesAcrossAllPresetsAndHandsDepthToOpticalFilter() = withTempDir { dir ->
        // Reject invalid or unknown scene:// URIs
        assertFailsWith<IllegalArgumentException> { OpticalScenePreset.parse("") }
        assertFailsWith<IllegalArgumentException> { OpticalScenePreset.parse("http://depth-studio") }
        assertFailsWith<IllegalArgumentException> { OpticalScenePreset.parse("scene://unknown-preset") }
        assertFailsWith<IllegalArgumentException> { OpticalScenePreset.parse("scene://depth studio") }

        var lastDepthPlanes = emptySet<Float>()
        var filterCalls = 0
        val stubLens = object : LensPort, OpticalSceneFilter {
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
                filterCalls++
                assertNotNull(depthMeters, "optical-scene must supply non-null depthMeters")
                assertEquals(srcWidth * srcHeight, depthMeters.size)
                lastDepthPlanes = depthMeters.toSet()
                val bytes = geometry.width * geometry.height * 2
                destination.put(ByteArray(bytes) { 0x42.toByte() })
                return bytes
            }
        }

        val factory = CmosFactory()
        val hPlain = Harness(dir)
        val hOptics = Harness(dir, lensFilter = stubLens)
        val cmosPlain = factory.create(hPlain.context, mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://depth-studio", "loop" to true))
        val cmosOptics = factory.create(hOptics.context, mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://depth-studio", "loop" to true))

        try {
            val expectedPlanes = setOf(
                ProceduralOpticsSceneSource.FOREGROUND_DEPTH_M,
                ProceduralOpticsSceneSource.MIDGROUND_DEPTH_M,
                ProceduralOpticsSceneSource.BACKGROUND_DEPTH_M,
            )

            for (preset in OpticalScenePreset.entries) {
                val testUri = when (preset) {
                    OpticalScenePreset.MONA_LISA -> "${preset.uri}?motion=off"
                    OpticalScenePreset.MILK_FROG -> "${preset.uri}?motion=off&audio=off"
                    else -> preset.uri
                }
                val cfg = mapOf("sourceKind" to "optical-scene", "sourceFile" to testUri, "loop" to true)
                cmosPlain.validateSettings(cfg)
                cmosPlain.applySettings(cfg)
                cmosOptics.applySettings(cfg)

                val state = cmosPlain.state()
                assertEquals("optical-scene-procedural-depth-readout", state["pipeline"])
                assertEquals("READY", state["sourceCapability"])
                assertEquals(testUri, state["activeSourceRef"])
                assertEquals(1024, state["sourceWidth"])
                assertEquals(576, state["sourceHeight"])
                assertEquals(true, state["hasDepthBuffer"])
                assertNull(state["lastSourceError"])
                assertEquals(1L, state["framesReceived"])
                assertEquals(0L, state["framesDropped"])

                // Direct Bayer exposure produces non-uniform high-contrast signal and loops bit-identically
                val out1 = ByteBuffer.allocate(1024 * 576 * 2)
                val out2 = ByteBuffer.allocate(1024 * 576 * 2)
                assertEquals(1024 * 576 * 2, hPlain.sensorPort.expose(defaultExposure, out1))
                assertEquals(1024 * 576 * 2, hPlain.sensorPort.expose(defaultExposure, out2))
                assertContentEquals(out1.array(), out2.array())
                assertTrue(out1.array().toSet().size > 16, "Preset ${preset.uri} should contain rich radiance contrast")

                // Verify finite scene-linear RGB (including HDR emitters) and the expected metric depth distribution.
                val direct = ProceduralOpticsSceneSource(preset = preset, width = 320, height = 180, loop = true)
                val linear = direct.latestLinearRgbFrame()
                val distinctPlanes = ProceduralOpticsSceneSource.distinctDepthPlanes(linear)
                when (preset) {
                    OpticalScenePreset.QR_BALL -> {
                        assertTrue(distinctPlanes.size > 32, "QR_BALL must provide continuous 3D ray-traced depth (got ${distinctPlanes.size})")
                        assertTrue(distinctPlanes.min() in 0.75f..0.95f, "QR_BALL min depth should be near foreground sphere (got ${distinctPlanes.min()})")
                        assertTrue(distinctPlanes.max() >= 5.45f, "QR_BALL max depth should reach distant QR wall at 5.5m (got ${distinctPlanes.max()})")
                    }
                    OpticalScenePreset.MONA_LISA -> {
                        assertTrue(distinctPlanes.size > 16, "MONA_LISA must provide continuous 3D portrait relief depth (got ${distinctPlanes.size})")
                        assertTrue(distinctPlanes.min() in 1.45f..2.35f, "MONA_LISA min depth should be foreground portrait (got ${distinctPlanes.min()})")
                        assertTrue(distinctPlanes.max() >= 4.45f, "MONA_LISA max depth should reach distant Tuscan gallery backdrop (got ${distinctPlanes.max()})")
                    }
                    OpticalScenePreset.MILK_FROG -> {
                        assertTrue(distinctPlanes.size > 32, "MILK_FROG must provide continuous 3D SDF + studio depth (got ${distinctPlanes.size})")
                        assertTrue(distinctPlanes.min() in 0.55f..0.88f, "MILK_FROG min depth should reach foreground bokeh bulbs (got ${distinctPlanes.min()})")
                        assertTrue(distinctPlanes.max() >= 10.0f, "MILK_FROG max depth should reach 10.5m HDR bokeh backdrop (got ${distinctPlanes.max()})")
                    }
                    OpticalScenePreset.STAR_BOKEH -> {
                        assertTrue(distinctPlanes.size > 16, "STAR_BOKEH must provide multi-depth twilight fairy lights (got ${distinctPlanes.size})")
                        assertTrue(distinctPlanes.min() in 1.25f..1.45f, "STAR_BOKEH min depth should be foreground focus target at 1.35m (got ${distinctPlanes.min()})")
                        assertTrue(distinctPlanes.max() >= 8.5f, "STAR_BOKEH max depth should reach distant fairy lights at 8.5..9.0m (got ${distinctPlanes.max()})")
                    }
                    else -> {
                        assertEquals(expectedPlanes, distinctPlanes, "Preset ${preset.uri} depth planes")
                    }
                }
                val maxExpectedLin = when (preset) {
                    OpticalScenePreset.STAR_BOKEH,
                    OpticalScenePreset.BOKEH_CITY,
                    OpticalScenePreset.DEPTH_STUDIO -> 65f
                    OpticalScenePreset.QR_BALL -> 16f
                    OpticalScenePreset.MILK_FROG -> {
                        // The rear lights emit RGB (38, 26, 13) and (16, 32, 40). The core takes
                        // max(ambient, emission); even the additive halo is capped at that maximum.
                        assertEquals(38f, linear.red.max(), "Warm pin light must retain its red peak")
                        assertEquals(32f, linear.green.max(), "Cool pin light must retain its green peak")
                        assertEquals(40f, linear.blue.max(), "Cool pin light must retain its blue peak")
                        40f
                    }
                    else -> 1f
                }
                assertTrue(linear.red.all { it.isFinite() && it in 0f..maxExpectedLin })
                assertTrue(linear.green.all { it.isFinite() && it in 0f..maxExpectedLin })
                assertTrue(linear.blue.all { it.isFinite() && it in 0f..maxExpectedLin })
                direct.close()

                // OpticalSceneFilter receives accurate depth buffer cleanly
                val optOut = ByteBuffer.allocate(1024 * 576 * 2)
                hOptics.sensorPort.expose(defaultExposure, optOut)
                if (preset == OpticalScenePreset.QR_BALL || preset == OpticalScenePreset.MONA_LISA || preset == OpticalScenePreset.MILK_FROG || preset == OpticalScenePreset.STAR_BOKEH) {
                    assertTrue(lastDepthPlanes.size > 16, "OpticalSceneFilter must receive continuous 3D depth for ${preset.uri}")
                } else {
                    assertEquals(expectedPlanes, lastDepthPlanes, "OpticalSceneFilter must receive all 3 depth planes for ${preset.uri}")
                }
            }
            assertEquals(OpticalScenePreset.entries.size, filterCalls)

            // Snapshot round-trip preserves optical-scene cleanly
            val saved = (cmosPlain as StatefulComponent).saveSnapshot()
            val hRestored = Harness(dir)
            val cmosRestored = factory.create(hRestored.context, mapOf("sourceKind" to "JPEG", "sourceFile" to "", "loop" to false)) as StatefulComponent
            cmosRestored.restoreSnapshot(saved)
            assertEquals(cmosPlain.state(), cmosRestored.state())
            cmosRestored.close()
        } finally {
            cmosPlain.close()
            cmosOptics.close()
        }
    }

    @Test
    fun unifiedDepthStudioCombinesThreePlanesNightBokehOrbsAndStarPinpoints() {
        ProceduralOpticsSceneSource(preset = OpticalScenePreset.DEPTH_STUDIO, width = 640, height = 360, loop = true).use { src ->
            val frame = src.latestLinearRgbFrame()
            val depth = assertNotNull(frame.depthMeters)
            assertEquals(
                setOf(
                    ProceduralOpticsSceneSource.FOREGROUND_DEPTH_M,
                    ProceduralOpticsSceneSource.MIDGROUND_DEPTH_M,
                    ProceduralOpticsSceneSource.BACKGROUND_DEPTH_M,
                ),
                ProceduralOpticsSceneSource.distinctDepthPlanes(frame),
            )
            var fgHdrPx = 0
            var bgOrbPx = 0
            var bgStarPeakPx = 0
            for (i in 0 until frame.width * frame.height) {
                val peak = maxOf(frame.red[i], frame.green[i], frame.blue[i])
                when (depth[i]) {
                    ProceduralOpticsSceneSource.FOREGROUND_DEPTH_M -> if (peak > 3.5f) fgHdrPx++
                    ProceduralOpticsSceneSource.BACKGROUND_DEPTH_M -> {
                        if (peak > 4.0f) bgOrbPx++
                        if (peak > 7.5f) bgStarPeakPx++
                    }
                }
            }
            assertTrue(fgHdrPx >= 12, "Unified depth-studio should include warm foreground bokeh accents (got $fgHdrPx px)")
            assertTrue(bgOrbPx >= 250, "Unified depth-studio should include rich night bokeh orbs + window lights (got $bgOrbPx px)")
            assertTrue(bgStarPeakPx >= 20, "Unified depth-studio should include >7.5 radiance pinpoint star highlights (got $bgStarPeakPx px)")
        }
        // Legacy URIs remain 100% parseable and functional
        assertEquals(OpticalScenePreset.BOKEH_CITY, OpticalScenePreset.parse("scene://bokeh-city"))
        assertEquals(OpticalScenePreset.STAR_BOKEH, OpticalScenePreset.parse("scene://star-bokeh"))
    }

    @Test
    fun qrBall3dSceneHasCurvedForegroundSphereDepthAndDistantQrWall() {
        assertEquals(OpticalScenePreset.QR_BALL, OpticalScenePreset.parse("scene://qr-ball"))

        val w = 1024
        val h = 576
        val source = ProceduralOpticsSceneSource(preset = OpticalScenePreset.QR_BALL, width = w, height = h, loop = true)
        try {
            val frame = source.latestLinearRgbFrame()
            val depth = assertNotNull(frame.depthMeters, "scene://qr-ball must provide per-pixel depthMeters")
            assertEquals(w * h, depth.size)

            // 1) Foreground 3D sphere centered near cx=0.34, cy=0.56: curved depth in 0.75..1.20m with center < rim
            val sphereCx = (w * 0.34).toInt()
            val sphereCy = (h * 0.56).toInt()
            val centerDepth = depth[sphereCy * w + sphereCx]
            val rightRimDepth = depth[sphereCy * w + (sphereCx + 115)]
            val topRimDepth = depth[(sphereCy - 115) * w + sphereCx]
            assertTrue(centerDepth in 0.75f..0.95f, "Sphere front apex should be ~0.83m (got $centerDepth)")
            assertTrue(rightRimDepth in 0.90f..1.20f, "Sphere right rim should be curved deeper in 0.90..1.20m (got $rightRimDepth)")
            assertTrue(topRimDepth in 0.90f..1.20f, "Sphere top rim should be curved deeper in 0.90..1.20m (got $topRimDepth)")
            assertTrue(centerDepth + 0.08f < rightRimDepth, "Curved sphere must be nearer at center ($centerDepth) than rim ($rightRimDepth)")
            assertTrue(centerDepth + 0.08f < topRimDepth, "Curved sphere must be nearer at center ($centerDepth) than top rim ($topRimDepth)")

            // High-frequency spatial contrast across sphere surface for Contrast-Detect AF
            var sphereTransitions = 0
            for (x in (sphereCx - 80) until (sphereCx + 80)) {
                val g0 = frame.green[sphereCy * w + x]
                val g1 = frame.green[sphereCy * w + x + 1]
                if (kotlin.math.abs(g1 - g0) > 0.15f) sphereTransitions++
            }
            assertTrue(sphereTransitions >= 6, "Foreground sphere must contain high-contrast micro-grid edges (got $sphereTransitions)")

            // 2) Distant QR code wall at z = 5.5m (unobstructed center-right region)
            var darkModules = 0
            var lightModules = 0
            for (y in 160 until 380) {
                val row = y * w
                for (x in 570 until 810) {
                    val d = depth[row + x]
                    assertTrue(kotlin.math.abs(d - ProceduralOpticsSceneSource.QR_BALL_WALL_DEPTH_M) < 1e-3f, "QR wall pixel ($x,$y) depth=$d")
                    val luma = frame.green[row + x]
                    if (luma < 0.08f) darkModules++
                    if (luma > 0.85f) lightModules++
                }
            }
            assertTrue(darkModules > 5_000, "Distant QR wall must contain crisp dark QR modules (got $darkModules)")
            assertTrue(lightModules > 5_000, "Distant QR wall must contain crisp bright QR modules (got $lightModules)")

            // 3) Perspective floor depth smoothly increases toward the 5.5m back wall
            val nearFloorDepth = depth[565 * w + 700]
            val farFloorDepth = depth[490 * w + 700]
            assertTrue(nearFloorDepth < farFloorDepth, "Perspective studio floor must deepen toward back wall ($nearFloorDepth -> $farFloorDepth)")
            assertTrue(farFloorDepth <= 5.51f && nearFloorDepth >= 1.5f, "Floor depth bounds unexpected ($nearFloorDepth .. $farFloorDepth)")
        } finally {
            source.close()
        }
    }

    @Test
    fun screenCaptureParsesUrisAndBuildsCrossPlatformWindowFfmpegCommands() {
        val primary = ScreenCaptureSceneSource.parseAndValidateTarget("screen://primary")
        assertEquals(ScreenCaptureTarget.PrimaryScreen(24), primary)
        assertEquals("screen://primary", primary.canonicalUri)

        val primary30 = ScreenCaptureSceneSource.parseAndValidateTarget("screen://primary?fps=30")
        assertEquals(ScreenCaptureTarget.PrimaryScreen(30), primary30)

        val region = ScreenCaptureSceneSource.parseAndValidateTarget("screen://region/10,20,1280,720?fps=15")
        assertEquals(ScreenCaptureTarget.ScreenRegion(10, 20, 1280, 720, 15), region)

        val window = ScreenCaptureSceneSource.parseAndValidateTarget("window://Minecraft%201.21?fps=24")
        assertEquals(ScreenCaptureTarget.WindowTitle("Minecraft 1.21", 24), window)

        // Invalid URIs refused cleanly
        for (bad in listOf(
            "",
            "screen://secondary",
            "screen://region/10,20,30",
            "screen://region/-1,0,100,100",
            "screen://region/0,0,1,100",
            "screen://primary?fps=0",
            "screen://primary?fps=120",
            "screen://primary?unknown=1",
            "window://",
            "http://example.com",
        )) {
            assertFailsWith<IllegalArgumentException>("Expected failure for '$bad'") {
                ScreenCaptureSceneSource.parseAndValidateTarget(bad)
            }
        }

        // Cross-platform ffmpeg window capture command builder
        val winTarget = ScreenCaptureTarget.WindowTitle("Minecraft", fps = 30)
        val winCmd = ScreenCaptureSceneSource.buildWindowCaptureFfmpegCommand(winTarget, 1024, 576, osName = "Windows 11", ffmpegExecutable = "ffmpeg")
        assertTrue("gdigrab" in winCmd && "title=Minecraft" in winCmd && "30" in winCmd && winCmd.last() == "pipe:1")

        val linuxCmd = ScreenCaptureSceneSource.buildWindowCaptureFfmpegCommand(winTarget, 1024, 576, osName = "Linux", ffmpegExecutable = "ffmpeg", displayEnv = ":1.0")
        assertTrue("x11grab" in linuxCmd && ":1.0" in linuxCmd && "30" in linuxCmd && linuxCmd.last() == "pipe:1")

        val macCmd = ScreenCaptureSceneSource.buildWindowCaptureFfmpegCommand(winTarget, 1024, 576, osName = "Mac OS X", ffmpegExecutable = "ffmpeg")
        assertTrue("avfoundation" in macCmd && "30" in macCmd && macCmd.last() == "pipe:1")
    }

    @Test
    fun screenCaptureIngestsInjectedFramesTracksDropsAndFallsBackSafelyWhenHeadless() = withTempDir { dir ->
        val w = 32
        val h = 18
        val grabCounter = AtomicInteger(0)
        val greenFrame = PackedRawFrame(w, h, RawPixelFormat.RGB24, solidRgb24(w, h, 20, 235, 25))
        val blueFrame = PackedRawFrame(w, h, RawPixelFormat.RGB24, solidRgb24(w, h, 20, 25, 240))

        val provider = ScreenCaptureSceneSourceProvider(
            targetWidth = w,
            targetHeight = h,
            customGrabberFactory = {
                ScreenFrameGrabber { _, _, _ ->
                    val n = grabCounter.incrementAndGet()
                    if (n < 4) greenFrame else blueFrame
                }
            },
            reconnectDelayMs = 5L,
            maxReconnectAttempts = 2,
        )
        val registry = SceneSourceRegistry.defaultRegistry().withProvider(provider)
        val factory = CmosFactory(registry)
        val harness = Harness(dir)
        val cmos = factory.create(
            harness.context,
            mapOf("sourceKind" to "screen-capture", "sourceFile" to "screen://region/0,0,640,360?fps=60", "loop" to true),
        )
        try {
            // Let background capture loop produce several frames so unread overwrite increments framesDropped
            waitUntil {
                (cmos.state()["framesReceived"] as Long) >= 5L &&
                    (cmos.state()["framesDropped"] as Long) >= 1L
            }

            val buf = ByteBuffer.allocate(1024 * 576 * 2)
            assertEquals(1024 * 576 * 2, harness.sensorPort.expose(defaultExposure, buf))
            val (r, g, b) = readRggbCenterQuad(buf)
            assertTrue(b > r * 4 && b > g * 4, "Expected latest blue captured frame: R=$r G=$g B=$b")

            val st = cmos.state()
            assertEquals("screen-capture-live-readout", st["pipeline"])
            assertEquals("READY", st["sourceCapability"])
            assertEquals("screen://region/0,0,640,360?fps=60", st["activeSourceRef"])
            assertEquals(w, st["sourceWidth"])
            assertEquals(h, st["sourceHeight"])
            assertEquals(false, st["hasDepthBuffer"])
            assertNull(st["lastSourceError"])
        } finally {
            cmos.close()
        }

        // Headless / unavailable grabber gracefully falls back to built-in test card and records lastSourceError
        val headlessProvider = ScreenCaptureSceneSourceProvider(
            targetWidth = w,
            targetHeight = h,
            customGrabberFactory = {
                ScreenFrameGrabber { _, _, _ ->
                    throw UnsupportedOperationException("Simulated headless environment")
                }
            },
            reconnectDelayMs = 5L,
            maxReconnectAttempts = 1,
        )
        val headlessFactory = CmosFactory(SceneSourceRegistry.defaultRegistry().withProvider(headlessProvider))
        val hHeadless = Harness(dir)
        val cmosHeadless = headlessFactory.create(
            hHeadless.context,
            mapOf("sourceKind" to "screen-capture", "sourceFile" to "screen://primary", "loop" to true),
        )
        try {
            waitUntil { cmosHeadless.state()["lastSourceError"] != null }
            val errMsg = cmosHeadless.state()["lastSourceError"] as String
            assertTrue("headless" in errMsg.lowercase(), "Expected headless error telemetry, got $errMsg")

            val out = ByteBuffer.allocate(1024 * 576 * 2)
            assertEquals(1024 * 576 * 2, hHeadless.sensorPort.expose(defaultExposure, out))
            assertTrue(out.array().any { it != 0.toByte() }, "Headless fallback must serve visible test card")
        } finally {
            cmosHeadless.close()
        }
    }

    @Test
    fun screenCaptureWindowPipeIngestsFramesAndBufferedImageScalerPreservesColor() = withTempDir { dir ->
        // Verify BufferedImage -> RGB24 downscaler preserves aspect and color cleanly
        val img = BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(240, 30, 30)
        g.fillRect(0, 0, 80, 40)
        g.dispose()
        val packed = ScreenCaptureSceneSource.bufferedImageToPackedRgb24(img, maxWidth = 32, maxHeight = 32)
        assertEquals(32, packed.width)
        assertEquals(16, packed.height)
        assertEquals(240, packed.pixels[0].toInt() and 0xFF)
        assertEquals(30, packed.pixels[1].toInt() and 0xFF)
        assertEquals(30, packed.pixels[2].toInt() and 0xFF)

        // Verify window://<title> pipe ingest via injected FfmpegStreamOpener + OpticalSceneFilter integration
        val w = 32
        val hPx = 18
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, w * hPx * 3 * 4)
        val provider = ScreenCaptureSceneSourceProvider(
            targetWidth = w,
            targetHeight = hPx,
            customWindowOpenerFactory = {
                FfmpegStreamOpener { _, _ ->
                    object : FfmpegStreamHandle {
                        override val inputStream: InputStream = pipeIn
                        override fun close() {
                            runCatching { pipeIn.close() }
                        }
                    }
                }
            },
            reconnectDelayMs = 5L,
            maxReconnectAttempts = 1,
        )
        var filterSawWidth = 0
        val stubOptics = object : LensPort, OpticalSceneFilter {
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
                filterSawWidth = srcWidth
                val bytes = geometry.width * geometry.height * 2
                destination.put(ByteArray(bytes) { 0x33.toByte() })
                return bytes
            }
        }

        val factory = CmosFactory(SceneSourceRegistry.defaultRegistry().withProvider(provider))
        val harness = Harness(dir, lensFilter = stubOptics)
        val cmos = factory.create(
            harness.context,
            mapOf("sourceKind" to "screen-capture", "sourceFile" to "window://MyGame?fps=24", "loop" to true),
        )
        try {
            pipeOut.write(solidRgb24(w, hPx, 240, 20, 20))
            pipeOut.flush()
            waitUntil { (cmos.state()["framesReceived"] as Long) >= 1L }

            val out = ByteBuffer.allocate(1024 * 576 * 2)
            assertEquals(1024 * 576 * 2, harness.sensorPort.expose(defaultExposure, out))
            assertEquals(w, filterSawWidth)
            assertEquals("window://MyGame?fps=24", cmos.state()["activeSourceRef"])
        } finally {
            runCatching { pipeOut.close() }
            cmos.close()
        }
    }
}
