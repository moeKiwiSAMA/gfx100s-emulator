package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

class SceneSource2D3DPoseCouplingTest {
    private val defaultExposure = Exposure(
        startTick = 0L,
        durationNanos = 1000L,
        iso = 100,
        lens = LensParameters("gf-63", 63.0, 2.8, 2.5, 0.9, 0.0),
    )

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("cmos-2d-3d-pose-test-")
        try {
            block(dir)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun allBuiltInSourcesImplementExplicitSceneSource2DOrSceneSource3DContract() = withTempDir { dir ->
        val jpg = dir.resolve("card.jpg")
        val img = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                color = Color(210, 90, 40)
                fillRect(0, 0, 32, 24)
                dispose()
            }
        }
        ImageIO.write(img, "JPEG", jpg.toFile())

        val staticSrc = StaticImageSceneSource.validateAndLoad(jpg, "card.jpg", StaticImageSceneSource.Format.JPEG, loop = true)
        val jpegSrc = JpegSceneSource.validateAndLoad(jpg, "card.jpg", loop = true)
        val fallback = SceneFrameCodec.createFallbackSource { dir.resolve(it) }
        val ffmpegSrc = FfmpegPipeSceneSource(
            target = FfmpegInputTarget.NetworkStream("rtsp://127.0.0.1/test", "rtsp"),
            targetWidth = 32,
            targetHeight = 24,
            loop = true,
            streamOpener = { _, _ ->
                object : FfmpegStreamHandle {
                    override val inputStream = ByteArrayInputStream(ByteArray(0))
                    override fun close() {}
                }
            },
            fallback = fallback,
            reconnectDelayMs = 0L,
            maxReconnectAttempts = 0,
        )
        val screenSrc = ScreenCaptureSceneSource(
            target = ScreenCaptureTarget.PrimaryScreen(10),
            targetWidth = 32,
            targetHeight = 24,
            frameGrabber = { _, _, _ -> null },
            windowStreamOpener = null,
            fallback = SceneFrameCodec.createFallbackSource { dir.resolve(it) },
            reconnectDelayMs = 0L,
            maxReconnectAttempts = 0,
        )
        val ingestSrc = StreamIngestSceneSource(
            endpoint = StreamIngestEndpoint(StreamIngestTransport.TCP_HTTP, "127.0.0.1", 0),
            fallback = SceneFrameCodec.createFallbackSource { dir.resolve(it) },
        )
        val procedural3d = ProceduralOpticsSceneSource(OpticalScenePreset.QR_BALL, width = 160, height = 90, loop = true)

        try {
            val all2d: List<SceneSource> = listOf(staticSrc, jpegSrc, ffmpegSrc, screenSrc, ingestSrc)
            for (s in all2d) {
                assertIs<SceneSource2D>(s, "${s.javaClass.simpleName} must implement SceneSource2D")
                assertEquals(SceneDimension.DIM_2D, s.dimension)
                val frame = s.samplePlanarRadiance()
                assertEquals(SceneDimension.DIM_2D, frame.dimension)
                assertTrue(frame.width > 0 && frame.height > 0)
            }

            assertIs<SceneSource3D>(procedural3d)
            assertEquals(SceneDimension.DIM_3D, procedural3d.dimension)
            assertEquals(CameraPose3D.IDENTITY, procedural3d.currentPose)
            val f3d = procedural3d.renderForPose(CameraPose3D.IDENTITY)
            assertEquals(SceneDimension.DIM_3D, f3d.dimension)
            assertNotNull(f3d.depthMeters)
        } finally {
            staticSrc.close()
            jpegSrc.close()
            ffmpegSrc.close()
            screenSrc.close()
            ingestSrc.close()
            procedural3d.close()
        }
    }

    @Test
    fun sceneSource3DReactsTo6DofRollPitchYawAndInverseDepthParallax() {
        // 1. Ray-traced qr-ball 3D studio
        val qrBall = ProceduralOpticsSceneSource(OpticalScenePreset.QR_BALL, width = 320, height = 180, loop = true)
        try {
            val fIdentity = qrBall.renderForPose(CameraPose3D.IDENTITY)
            val dIdentity = assertNotNull(fIdentity.depthMeters)
            val fRolled = qrBall.renderForPose(CameraPose3D(rollDeg = 90.0))
            val dRolled = assertNotNull(fRolled.depthMeters)
            val fPitched = qrBall.renderForPose(CameraPose3D(pitchDeg = 22.0))
            val dPitched = assertNotNull(fPitched.depthMeters)

            // Sphere apex at canonical pose sits near (0.34*w, 0.56*h) with depth ~0.83m.
            val sphereIdx = (180 * 0.56).toInt() * 320 + (320 * 0.34).toInt()
            assertTrue(dIdentity[sphereIdx] < 1.0f, "Expected sphere near apex at canonical pose: ${dIdentity[sphereIdx]}")
            // Under 90° portrait roll or +22° upward pitch, the sphere moves off that pixel!
            assertTrue(dRolled[sphereIdx] > 1.5f, "Expected 90° roll to rotate 3D sphere off canonical pixel: ${dRolled[sphereIdx]}")
            assertTrue(dPitched[sphereIdx] > 1.5f, "Expected +22° pitch to tilt 3D sphere off canonical pixel: ${dPitched[sphereIdx]}")

            // Returning to IDENTITY restores canonical bit-exact frame
            val fBack = qrBall.renderForPose(CameraPose3D.IDENTITY)
            assertContentEquals(fIdentity.red, fBack.red)
            assertContentEquals(dIdentity, assertNotNull(fBack.depthMeters))

            // Cross-lens 3D FoV scaling: count foreground sphere pixels (z < 1.10m inside the central band,
            // sampled immediately after each renderForPose since ProceduralOpticsSceneSource reuses its
            // zero-allocation poseCanvas). 20 mm wide renders a much smaller sphere than 45 mm, while
            // 110 mm telephoto magnifies the sphere dramatically larger than 45 mm.
            fun centerBandSpherePixels(depth: FloatArray): Int {
                var count = 0
                for (y in (180 / 3) until (180 * 2 / 3)) {
                    val row = y * 320
                    for (x in 0 until 320) {
                        if (depth[row + x] < 1.10f) count++
                    }
                }
                return count
            }
            val sphere45 = centerBandSpherePixels(dIdentity)
            val sphere20 = centerBandSpherePixels(assertNotNull(qrBall.renderForPose(CameraPose3D(focalLengthMm = 20.0)).depthMeters))
            val sphere110 = centerBandSpherePixels(assertNotNull(qrBall.renderForPose(CameraPose3D(focalLengthMm = 110.0)).depthMeters))
            assertTrue(
                sphere20 in 1 until (sphere45 / 2),
                "20 mm wide lens must show much smaller sphere coverage than 45 mm: 20mm=$sphere20, 45mm=$sphere45",
            )
            assertTrue(
                sphere110 > (sphere45 * 3) / 2,
                "110 mm telephoto lens must show much larger sphere coverage than 45 mm: 110mm=$sphere110, 45mm=$sphere45",
            )
        } finally {
            qrBall.close()
        }

        // 2. Multi-plane depth-studio parallax, roll, and cross-lens FoV scaling
        val depthStudio = ProceduralOpticsSceneSource(OpticalScenePreset.DEPTH_STUDIO, width = 320, height = 180, loop = true)
        try {
            val f0 = depthStudio.renderForPose(CameraPose3D.IDENTITY)
            val shiftGreen = depthStudio.renderForPose(CameraPose3D(posXM = 0.25)).green.copyOf()
            val rollGreen = depthStudio.renderForPose(CameraPose3D(rollDeg = 45.0)).green.copyOf()
            val wideGreen = depthStudio.renderForPose(CameraPose3D(focalLengthMm = 20.0)).green.copyOf()
            val teleGreen = depthStudio.renderForPose(CameraPose3D(focalLengthMm = 110.0)).green.copyOf()

            var shiftDiff = 0.0
            var rollDiff = 0.0
            var fovDiff = 0.0
            for (i in f0.green.indices) {
                shiftDiff += abs(f0.green[i] - shiftGreen[i])
                rollDiff += abs(f0.green[i] - rollGreen[i])
                fovDiff += abs(wideGreen[i] - teleGreen[i])
            }
            assertTrue(shiftDiff / f0.pixelCount > 0.02, "Expected 3D translation parallax in depth-studio: avgDiff=${shiftDiff / f0.pixelCount}")
            assertTrue(rollDiff / f0.pixelCount > 0.05, "Expected 45° roll rotation in depth-studio: avgDiff=${rollDiff / f0.pixelCount}")
            assertTrue(fovDiff / f0.pixelCount > 0.05, "Expected 20mm vs 110mm FoV framing change in depth-studio: avgDiff=${fovDiff / f0.pixelCount}")
        } finally {
            depthStudio.close()
        }
    }

    @Test
    fun cmosFactoryCouplesCameraOrientationPortToSceneSource3DAndAsyncDoubleBuffersHandshake() = withTempDir { dir ->
        var currentRoll = 0.0
        var currentPitch = 0.0
        var currentShake = 0.0
        var tickNow = 0L

        val orientation = object : CameraOrientationPort {
            override val accelEnabled: Boolean = true
            override val orientationPreset: String get() = if (currentRoll == 90.0) "portrait-cw" else "landscape"
            override val rollDeg: Double get() = currentRoll
            override val pitchDeg: Double get() = currentPitch
            override val shakeAmpDeg: Double get() = currentShake
            override val lcdTiltDeg: Double = 0.0
            override fun bodyGravityG(tick: Long) = CameraGravityVector(0.0, 1.0, 0.0)
            override fun lcdGravityG(tick: Long) = CameraGravityVector(0.0, 1.0, 0.0)
            override fun handShakeDeltaDeg(tick: Long) =
                if (currentShake > 0.0) Triple(currentShake * kotlin.math.sin(tick * 0.01), currentShake * kotlin.math.cos(tick * 0.01), 0.0)
                else Triple(0.0, 0.0, 0.0)
        }

        val capturedPoses = mutableListOf<CameraPose3D>()
        val filterCalls = AtomicInteger(0)
        val filterLens = object : LensPort, OpticalSceneFilter {
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
                val bytes = geometry.width * geometry.height * 2
                destination.put(ByteArray(bytes))
                return bytes
            }

            override fun filterRadianceFrame(
                frame: LinearRadianceFrame,
                exposure: Exposure,
                geometry: SensorGeometry,
                destination: ByteBuffer,
                frameIndex: Long,
            ): Int {
                val call = filterCalls.incrementAndGet()
                synchronized(capturedPoses) { capturedPoses.add(frame.pose) }
                if (currentShake > 0.0 && call > 2) {
                    Thread.sleep(50L)
                }
                val marker = ((frame.pose.rollDeg.toInt() and 0xFF)).toByte()
                val bytes = geometry.width * geometry.height * 2
                destination.put(ByteArray(bytes) { marker })
                return bytes
            }
        }

        lateinit var sensorPort: SensorPort
        val ctx = object : ComponentContext {
            override val directory: Path = dir
            override val clock = EmulationClock { tickNow }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    if (type == SensorPort::class.java) sensorPort = port as SensorPort
                }

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> find(name: String, type: Class<T>): T? = when {
                    name == "lens" && type == LensPort::class.java -> filterLens as T
                    name == CameraOrientationPort.MMA8452Q_PORT_NAME && type == CameraOrientationPort::class.java -> orientation as T
                    else -> null
                }

                override fun removeOwner(owner: String) {}
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) {}
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override fun dataPath(relative: String): Path = dir.resolve(relative).normalize()
        }

        // Start with a stationary subject so only the later camera shake enables asynchronous rendering.
        val cmos = CmosFactory().create(
            ctx,
            mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://qr-ball?motion=off", "loop" to true),
        )
        try {
            val buf = ByteBuffer.allocate(1024 * 576 * 2)
            sensorPort.expose(defaultExposure, buf)
            assertEquals("DIM_3D", cmos.state()["sceneDimension"])

            currentRoll = 90.0
            currentPitch = 12.0
            buf.clear()
            // Capture the new pose synchronously before asserting its exact coordinates.
            sensorPort.exposeStill(defaultExposure, buf)
            @Suppress("UNCHECKED_CAST")
            val poseState = cmos.state()["cameraPose3D"] as Map<String, Any?>
            assertEquals(90.0, poseState["rollDeg"])
            assertEquals(12.0, poseState["pitchDeg"])
            assertEquals(90.0, capturedPoses.last().rollDeg)

            // Enable handheld micro-shake -> engages async double-buffer (first frame sync, subsequent non-blocking!)
            currentShake = 1.5
            tickNow = 100L
            buf.clear()
            sensorPort.expose(defaultExposure, buf) // first async3d frame renders synchronously
            tickNow = 250L
            buf.clear()
            val t0 = System.nanoTime()
            sensorPort.expose(defaultExposure, buf) // non-blocking double-buffered return!
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            assertTrue(elapsedMs < 30.0, "Dynamic 3D handheld shake expose must not block on 50ms filter (took ${elapsedMs}ms)")
        } finally {
            cmos.close()
        }
    }
}
