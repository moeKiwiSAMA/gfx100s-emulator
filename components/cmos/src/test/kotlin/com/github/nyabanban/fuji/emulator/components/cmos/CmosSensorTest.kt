package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.*

class CmosSensorTest {
    private class Harness(private val rootDirectory: Path) {
        lateinit var sensorPort: SensorPort
        val context = object : ComponentContext {
            override val directory: Path = rootDirectory
            override val clock = EmulationClock { 0L }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    sensorPort = port as SensorPort
                }
                override fun <T : Any> find(name: String, type: Class<T>): T? = null
                override fun removeOwner(owner: String) {}
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) {}
                override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = AutoCloseable {}
            }
            override fun dataPath(relative: String): Path {
                val resolved = rootDirectory.resolve(relative).normalize()
                require(resolved.startsWith(rootDirectory) && resolved != rootDirectory) { "path outside directory" }
                return resolved
            }
        }
    }

    private fun withTempDir(action: (Path) -> Unit) {
        val dir = Files.createTempDirectory("cmos-sensor-test-")
        try {
            action(dir)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun writeTestJpeg(path: Path, width: Int = 256, height: Int = 256) {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, width, height)
        // High-contrast checkerboard with dark corner squares
        g.color = Color.BLACK
        g.fillRect(24, 24, 64, 64)
        g.fillRect(width - 88, 24, 64, 64)
        g.fillRect(24, height - 88, 64, 64)
        g.color = Color(220, 40, 40)
        g.fillRect(width / 2 - 20, height / 2 - 20, 40, 40)
        g.dispose()
        check(ImageIO.write(img, "jpg", path.toFile()))
    }

    private val defaultExposure = Exposure(
        startTick = 100L,
        durationNanos = 1000L,
        iso = 100,
        lens = LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0),
    )

    @Test
    fun rejectsUnconfiguredSourceUndersizedBufferAndCorruptedJpegs() = withTempDir { dir ->
        val factory = CmosFactory()
        val h = Harness(dir)
        val cmos = factory.create(h.context, mapOf("sourceKind" to "JPEG", "sourceFile" to "", "loop" to false))
        assertEquals("unconfigured", cmos.state()["pipeline"])

        // Unconfigured sourceFile fails (preserves IntegrationTest.kt:52)
        assertFails { h.sensorPort.expose(defaultExposure, ByteBuffer.allocate(640 * 480 * 4)) }

        // Corrupted JPEG bytes (bad.jpg) rejected
        Files.write(dir.resolve("bad.jpg"), byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertFailsWith<IllegalArgumentException> {
            cmos.validateSettings(mapOf("sourceKind" to "JPEG", "sourceFile" to "bad.jpg", "loop" to false))
        }

        // Truncated JPEG missing EOI rejected
        val good = dir.resolve("good.jpg")
        writeTestJpeg(good)
        val goodBytes = Files.readAllBytes(good)
        Files.write(dir.resolve("truncated.jpg"), goodBytes.copyOf(goodBytes.size - 4))
        assertFailsWith<IllegalArgumentException> {
            cmos.validateSettings(mapOf("sourceKind" to "JPEG", "sourceFile" to "truncated.jpg", "loop" to false))
        }

        // Valid JPEG succeeds, undersized buffer still fails
        val settings = mapOf("sourceKind" to "JPEG", "sourceFile" to "good.jpg", "loop" to true)
        cmos.validateSettings(settings)
        cmos.applySettings(settings)
        assertEquals("jpeg-radiance-readout", cmos.state()["pipeline"])
        assertFailsWith<IllegalArgumentException> {
            h.sensorPort.expose(defaultExposure, ByteBuffer.allocate(10))
        }
        assertFailsWith<IllegalArgumentException> {
            h.sensorPort.expose(defaultExposure.copy(durationNanos = 0L), ByteBuffer.allocate(640 * 480 * 4))
        }
        cmos.close()
    }

    @Test
    fun loopTrueRepeatsAndPatchToLoopFalseResetsSingleExposure() = withTempDir { dir ->
        writeTestJpeg(dir.resolve("scene.jpg"))
        val factory = CmosFactory()
        val h = Harness(dir)
        val cmos = factory.create(h.context, mapOf("sourceKind" to "JPEG", "sourceFile" to "scene.jpg", "loop" to true))

        val buf1 = ByteBuffer.allocate(640 * 480 * 4)
        val buf2 = ByteBuffer.allocate(640 * 480 * 4)
        val n1 = h.sensorPort.expose(defaultExposure, buf1)
        val n2 = h.sensorPort.expose(defaultExposure, buf2)
        assertTrue(n1 > 0 && n1 == n2)
        assertEquals(n1, buf1.position())
        assertContentEquals(buf1.array().copyOf(n1), buf2.array().copyOf(n2))
        assertEquals(2L, cmos.state()["framesExposed"])
        assertEquals(false, cmos.state()["exhausted"])

        // Patching loop=false on the SAME sourceFile resets exhaustion for one fresh exposure
        val loopFalse = mapOf("sourceKind" to "JPEG", "sourceFile" to "scene.jpg", "loop" to false)
        cmos.validateSettings(loopFalse)
        cmos.applySettings(loopFalse)
        assertEquals(0L, cmos.state()["framesExposed"])
        assertEquals(false, cmos.state()["exhausted"])

        val buf3 = ByteBuffer.allocate(640 * 480 * 4)
        val single = h.sensorPort.expose(defaultExposure, buf3)
        assertEquals(n1, single)
        assertEquals(1L, cmos.state()["framesExposed"])
        assertEquals(true, cmos.state()["exhausted"])

        val buf4 = ByteBuffer.allocate(640 * 480 * 4)
        val after = h.sensorPort.expose(defaultExposure, buf4)
        assertEquals(0, after)
        assertEquals(0, buf4.position())
        cmos.close()
    }

    @Test
    fun legacyThreeKeySnapshotPreservesConfiguredSceneWhileFiveKeyRestoresState() = withTempDir { dir ->
        writeTestJpeg(dir.resolve("live.jpg"))
        val factory = CmosFactory()
        val hLive = Harness(dir)
        val cmosLive = factory.create(hLive.context, mapOf("sourceKind" to "JPEG", "sourceFile" to "live.jpg", "loop" to true)) as StatefulComponent

        val emptyV1Snapshot = mapOf(
            "schema" to 1,
            "componentId" to "cmos",
            "scalarValues" to mapOf("sourceKind" to "JPEG", "sourceFile" to "", "loop" to false),
        )
        cmosLive.restoreSnapshot(emptyV1Snapshot)
        assertEquals("live.jpg", cmosLive.state()["sourceFile"])
        assertEquals(true, cmosLive.state()["loop"])
        val buf = ByteBuffer.allocate(640 * 480 * 4)
        assertTrue((cmosLive as SensorPort).expose(defaultExposure, buf) > 0)

        // Five-key snapshot captures and restores framesExposed + exhausted
        cmosLive.applySettings(mapOf("sourceKind" to "JPEG", "sourceFile" to "live.jpg", "loop" to false))
        assertTrue(cmosLive.expose(defaultExposure, ByteBuffer.allocate(640 * 480 * 4)) > 0)
        val saved = cmosLive.saveSnapshot()

        val hRestored = Harness(dir)
        val cmosRestored = factory.create(hRestored.context, mapOf("sourceKind" to "JPEG", "sourceFile" to "", "loop" to false)) as StatefulComponent
        cmosRestored.restoreSnapshot(saved)
        assertEquals(cmosLive.state(), cmosRestored.state())
        assertEquals(0, (cmosRestored as SensorPort).expose(defaultExposure, ByteBuffer.allocate(640 * 480 * 4)))

        // Five-key snapshot with sourceFile == "" MUST restore over a configured cmos (not no-op!)
        val unconfiguredFiveKey = mapOf(
            "schema" to 1,
            "componentId" to "cmos",
            "scalarValues" to mapOf("sourceKind" to "JPEG", "sourceFile" to "", "loop" to false),
            "framesExposed" to 0L,
            "exhausted" to false,
        )
        cmosLive.restoreSnapshot(unconfiguredFiveKey)
        assertEquals("", cmosLive.state()["sourceFile"])
        assertEquals("unconfigured", cmosLive.state()["pipeline"])
        assertEquals(0L, cmosLive.state()["framesExposed"])
        assertEquals(false, cmosLive.state()["exhausted"])
        assertFailsWith<IllegalStateException> {
            cmosLive.expose(defaultExposure, ByteBuffer.allocate(640 * 480 * 4))
        }

        // Contradictory 5-key snapshots are rejected atomically
        cmosLive.applySettings(mapOf("sourceKind" to "JPEG", "sourceFile" to "live.jpg", "loop" to true))
        val baselineState = cmosLive.state()
        val contradictoryCandidates = listOf(
            mapOf(
                "schema" to 1,
                "componentId" to "cmos",
                "scalarValues" to mapOf("sourceKind" to "JPEG", "sourceFile" to "live.jpg", "loop" to true),
                "framesExposed" to 1L,
                "exhausted" to true,
            ),
            mapOf(
                "schema" to 1,
                "componentId" to "cmos",
                "scalarValues" to mapOf("sourceKind" to "JPEG", "sourceFile" to "", "loop" to false),
                "framesExposed" to 1L,
                "exhausted" to false,
            ),
            mapOf(
                "schema" to 1,
                "componentId" to "cmos",
                "scalarValues" to mapOf("sourceKind" to "JPEG", "sourceFile" to "", "loop" to false),
                "framesExposed" to 0L,
                "exhausted" to true,
            ),
        )
        for (candidate in contradictoryCandidates) {
            assertFailsWith<IllegalArgumentException> { cmosLive.restoreSnapshot(candidate) }
            assertEquals(baselineState, cmosLive.state())
        }
        assertTrue(cmosLive.expose(defaultExposure, ByteBuffer.allocate(640 * 480 * 4)) > 0)

        cmosLive.close()
        cmosRestored.close()
    }

    @Test
    fun rejectsDimensionBombJpegBeforeDecodingAndHandlesTinyApertureWithoutNan() = withTempDir { dir ->
        val tiny = dir.resolve("tiny.jpg")
        writeTestJpeg(tiny, width = 16, height = 16)
        val bombBytes = Files.readAllBytes(tiny)
        // Walk JPEG markers from SOI (0xFF 0xD8) to SOF0 (0xFF 0xC0) and patch height/width to 25000x25000
        var offset = 2
        var patchedSof0 = false
        while (offset + 4 < bombBytes.size) {
            if (bombBytes[offset] != 0xFF.toByte()) break
            val marker = bombBytes[offset + 1].toInt() and 0xff
            if (marker == 0xD9 || marker == 0xDA) break
            val segLen = ((bombBytes[offset + 2].toInt() and 0xff) shl 8) or (bombBytes[offset + 3].toInt() and 0xff)
            if (marker == 0xC0 && offset + 9 < bombBytes.size) {
                bombBytes[offset + 5] = (25000 ushr 8).toByte()
                bombBytes[offset + 6] = (25000 and 0xff).toByte()
                bombBytes[offset + 7] = (25000 ushr 8).toByte()
                bombBytes[offset + 8] = (25000 and 0xff).toByte()
                patchedSof0 = true
                break
            }
            offset += 2 + segLen
        }
        assertTrue(patchedSof0, "Expected SOF0 marker in generated tiny JPEG")
        Files.write(dir.resolve("bomb25000.jpg"), bombBytes)
        writeTestJpeg(dir.resolve("scene256.jpg"))

        val factory = CmosFactory()
        val h = Harness(dir)
        val cmos = factory.create(h.context, mapOf("sourceKind" to "JPEG", "sourceFile" to "scene256.jpg", "loop" to true))
        assertFailsWith<IllegalArgumentException> {
            cmos.validateSettings(mapOf("sourceKind" to "JPEG", "sourceFile" to "bomb25000.jpg", "loop" to false))
        }

        // Tiny aperture (1e-200) with transmission=0.0 or >0.0 must not throw NaN crash
        val zeroTransmission = defaultExposure.copy(
            lens = LensParameters("Extreme", 63.0, 1e-200, 1.0, 0.0, 0.0),
        )
        val zeroBuf = ByteBuffer.allocate(640 * 480 * 4).order(ByteOrder.LITTLE_ENDIAN)
        val zeroBytes = h.sensorPort.expose(zeroTransmission, zeroBuf)
        assertTrue(zeroBytes > 0)
        zeroBuf.flip()
        while (zeroBuf.hasRemaining()) {
            assertEquals(0, zeroBuf.get().toInt())
        }

        val brightTinyAperture = defaultExposure.copy(
            lens = LensParameters("Extreme", 63.0, 1e-200, 1.0, 0.9, 0.0),
        )
        val brightBuf = ByteBuffer.allocate(640 * 480 * 4).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(h.sensorPort.expose(brightTinyAperture, brightBuf) > 0)
        cmos.close()
    }

    @Test
    fun radiancePreservesAspectRatioAndExposureResponseAcrossBayerPatterns() = withTempDir { dir ->
        val jpg = dir.resolve("square.jpg")
        writeTestJpeg(jpg, width = 200, height = 200)
        val source = JpegSceneSource.validateAndLoad(jpg, "square.jpg", loop = true)
        val geom = SensorGeometry(1024, 576, 16, BayerPattern.RGGB, 3.76)
        val nominalBuf = ByteBuffer.allocate(1024 * 576 * 2).order(ByteOrder.LITTLE_ENDIAN)
        source.radiance(defaultExposure, geom, nominalBuf)
        nominalBuf.flip()

        // Left quiet surround (x=100, y=288) should be near max white (65535)
        val surroundR = nominalBuf.getShort((288 * 1024 + 100) * 2).toInt() and 0xffff
        assertTrue(surroundR > 60000, "Expected bright white surround, got $surroundR")

        // Top-left black square inside centered 576x576 region: offX=224, scaled x=24->69 -> ~224+70..224+250
        val darkR = nominalBuf.getShort((100 * 1024 + 320) * 2).toInt() and 0xffff
        assertTrue(darkR < 2000, "Expected dark black square sample, got $darkR")

        // Red center square (512, 288): R (even,even) >> B (odd,odd)
        val centerR = nominalBuf.getShort((288 * 1024 + 512) * 2).toInt() and 0xffff
        val centerB = nominalBuf.getShort((289 * 1024 + 513) * 2).toInt() and 0xffff
        assertTrue(centerR > centerB * 4, "Expected red dominance at center: R=$centerR B=$centerB")

        // Shorter exposure dims the bright surround proportionally
        val dimExposure = defaultExposure.copy(durationNanos = 250L)
        val dimBuf = ByteBuffer.allocate(1024 * 576 * 2).order(ByteOrder.LITTLE_ENDIAN)
        source.radiance(dimExposure, geom, dimBuf)
        dimBuf.flip()
        val dimWhite = dimBuf.getShort((288 * 1024 + 100) * 2).toInt() and 0xffff
        assertTrue(dimWhite in 15000..18000, "Expected ~1/4 linear radiance for 1/4 duration, got $dimWhite")

        // 8-bit GRBG packing also succeeds cleanly
        val buf8 = ByteBuffer.allocate(640 * 480)
        source.radiance(defaultExposure, SensorGeometry(640, 480, 8, BayerPattern.GRBG, 3.76), buf8)
        assertEquals(640 * 480, buf8.position())
        val lin = ((buf8.get(240 * 640 + 40).toInt() and 0xff) / 255.0).pow(1.0)
        assertTrue((lin * 255.0).roundToInt() > 240)
        source.close()
    }

    private fun writeTestPng(path: Path, width: Int = 256, height: Int = 256, withAlpha: Boolean = false) {
        val imgType = if (withAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val img = BufferedImage(width, height, imgType)
        val g = img.createGraphics()
        if (withAlpha) {
            g.composite = java.awt.AlphaComposite.Clear
            g.fillRect(0, 0, width, height)
            g.composite = java.awt.AlphaComposite.SrcOver
        } else {
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
        }
        g.color = Color.BLACK
        g.fillRect(24, 24, 64, 64)
        g.color = Color(220, 40, 40)
        g.fillRect(width / 2 - 20, height / 2 - 20, 40, 40)
        g.dispose()
        check(ImageIO.write(img, "png", path.toFile()))
    }

    @Test
    fun staticImageCacheReusesBayerBufferAcrossLoopingExposuresAndReleasesOnClose() = withTempDir { dir ->
        val jpg = dir.resolve("loop.jpg")
        writeTestJpeg(jpg, width = 200, height = 200)
        val source = StaticImageSceneSource.validateAndLoad(
            path = jpg,
            relativePath = "loop.jpg",
            format = StaticImageSceneSource.Format.JPEG,
            loop = true,
        )
        val geom = SensorGeometry(1024, 576, 16, BayerPattern.RGGB, 3.76)
        val first = ByteBuffer.allocate(1024 * 576 * 2)
        source.radiance(defaultExposure, geom, first)
        assertEquals(1L, source.cacheMisses)
        assertEquals(0L, source.cacheHits)
        assertTrue(source.hasCachedBuffer())

        // Repeat 32 times across advancing ticks — must hit cache every time and stay bit-identical
        val reuseBuf = ByteBuffer.allocate(1024 * 576 * 2)
        val expectedBytes = first.array().copyOf()
        for (i in 1..32) {
            reuseBuf.clear()
            source.radiance(defaultExposure.copy(startTick = 100L + i * 16_666L), geom, reuseBuf)
            assertEquals(expectedBytes.size, reuseBuf.position())
            assertContentEquals(expectedBytes, reuseBuf.array())
            assertFalse(source.exhausted)
        }
        assertEquals(1L, source.cacheMisses)
        assertEquals(32L, source.cacheHits)

        // Exposure gain change invalidates cache once, then subsequent calls hit again
        val dimExposure = defaultExposure.copy(durationNanos = 250L)
        reuseBuf.clear()
        source.radiance(dimExposure, geom, reuseBuf)
        val dimBytes = reuseBuf.array().copyOf()
        assertFalse(expectedBytes.contentEquals(dimBytes))
        assertEquals(2L, source.cacheMisses)
        assertEquals(32L, source.cacheHits)

        reuseBuf.clear()
        source.radiance(dimExposure, geom, reuseBuf)
        assertContentEquals(dimBytes, reuseBuf.array())
        assertEquals(2L, source.cacheMisses)
        assertEquals(33L, source.cacheHits)

        // Geometry change also re-renders deterministically
        val geom8 = SensorGeometry(640, 480, 8, BayerPattern.BGGR, 3.76)
        val buf8 = ByteBuffer.allocate(640 * 480)
        source.radiance(defaultExposure, geom8, buf8)
        assertEquals(3L, source.cacheMisses)
        assertEquals(640 * 480, buf8.position())

        source.close()
        assertFalse(source.hasCachedBuffer())
        assertFailsWith<IllegalStateException> {
            source.radiance(defaultExposure, geom, ByteBuffer.allocate(1024 * 576 * 2))
        }
    }

    @Test
    fun pngStaticImageSupportDecodesValidatesBoundsAndCompositesAlphaOverWhite() = withTempDir { dir ->
        val png = dir.resolve("alpha.png")
        writeTestPng(png, width = 200, height = 200, withAlpha = true)
        Files.write(dir.resolve("bad.png"), byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07))

        // Build dimension-bomb PNG by patching IHDR width/height (bytes 16..23) to 25000x25000 with valid CRC
        val tinyPng = dir.resolve("tiny.png")
        writeTestPng(tinyPng, width = 16, height = 16)
        val bombBytes = Files.readAllBytes(tinyPng)
        val dim = 25000
        bombBytes[16] = (dim ushr 24).toByte()
        bombBytes[17] = (dim ushr 16).toByte()
        bombBytes[18] = (dim ushr 8).toByte()
        bombBytes[19] = (dim and 0xff).toByte()
        bombBytes[20] = (dim ushr 24).toByte()
        bombBytes[21] = (dim ushr 16).toByte()
        bombBytes[22] = (dim ushr 8).toByte()
        bombBytes[23] = (dim and 0xff).toByte()
        val crc = java.util.zip.CRC32().apply { update(bombBytes, 12, 17) }.value
        bombBytes[29] = (crc ushr 24).toByte()
        bombBytes[30] = (crc ushr 16).toByte()
        bombBytes[31] = (crc ushr 8).toByte()
        bombBytes[32] = (crc and 0xff).toByte()
        Files.write(dir.resolve("bomb.png"), bombBytes)

        val factory = CmosFactory()
        val h = Harness(dir)
        val cmos = factory.create(h.context, mapOf("sourceKind" to "JPEG", "sourceFile" to "", "loop" to false))

        assertFailsWith<IllegalArgumentException> {
            cmos.validateSettings(mapOf("sourceKind" to "PNG", "sourceFile" to "bad.png", "loop" to true))
        }
        assertFailsWith<IllegalArgumentException> {
            cmos.validateSettings(mapOf("sourceKind" to "PNG", "sourceFile" to "bomb.png", "loop" to true))
        }

        val pngSettings = mapOf("sourceKind" to "PNG", "sourceFile" to "alpha.png", "loop" to true)
        cmos.validateSettings(pngSettings)
        cmos.applySettings(pngSettings)
        assertEquals("png-radiance-readout", cmos.state()["pipeline"])
        assertEquals("READY", cmos.state()["sourceCapability"])

        val buf = ByteBuffer.allocate(1024 * 576 * 2).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1024 * 576 * 2, h.sensorPort.expose(defaultExposure, buf))
        buf.flip()
        // Transparent background inside fitted PNG region composites to white (>60000)
        val bgR = buf.getShort((288 * 1024 + 300) * 2).toInt() and 0xffff
        assertTrue(bgR > 60000, "Expected transparent PNG background composited over white, got $bgR")
        // Top-left black square stays dark (<2000)
        val darkR = buf.getShort((100 * 1024 + 320) * 2).toInt() and 0xffff
        assertTrue(darkR < 2000, "Expected dark square in PNG, got $darkR")
        // Center red square has R >> B
        val centerR = buf.getShort((288 * 1024 + 512) * 2).toInt() and 0xffff
        val centerB = buf.getShort((289 * 1024 + 513) * 2).toInt() and 0xffff
        assertTrue(centerR > centerB * 4, "Expected red dominance in PNG: R=$centerR B=$centerB")

        // Snapshot round-trip preserves PNG config cleanly
        val saved = (cmos as StatefulComponent).saveSnapshot()
        val h2 = Harness(dir)
        val cmos2 = factory.create(h2.context, mapOf("sourceKind" to "JPEG", "sourceFile" to "", "loop" to false)) as StatefulComponent
        cmos2.restoreSnapshot(saved)
        assertEquals(cmos.state(), cmos2.state())
        assertEquals(1024 * 576 * 2, h2.sensorPort.expose(defaultExposure, ByteBuffer.allocate(1024 * 576 * 2)))

        cmos.close()
        cmos2.close()
    }

    @Test
    fun sceneSourceRegistryExposesPlannedExtensionPointsAndAllowsPluginOverride() = withTempDir { dir ->
        Files.writeString(dir.resolve("clip.mp4"), "placeholder-video")
        val factory = CmosFactory()
        val h = Harness(dir)
        val cmos = factory.create(h.context, mapOf("sourceKind" to "JPEG", "sourceFile" to "", "loop" to true))

        val kinds = factory.descriptor.settings.single { it.key == "sourceKind" }.choices
        assertTrue(
            listOf(
                "JPEG",
                "PNG",
                "optical-scene",
                "screen-capture",
                "ffmpeg-pipe",
                "stream-ingest",
                "VIDEO_FILE",
                "RTMP_RTSP",
                "WEBCAM",
                "SHARED_TEXTURE",
                "H264",
                "RAF",
            ).all { it in kinds },
        )

        // VIDEO_FILE / RTMP_RTSP / WEBCAM are now READY via FfmpegPipeSceneSourceProvider with strict validation
        assertFailsWith<IllegalArgumentException> {
            cmos.validateSettings(mapOf("sourceKind" to "VIDEO_FILE", "sourceFile" to "missing.mp4", "loop" to true))
        }
        assertFailsWith<IllegalArgumentException> {
            cmos.validateSettings(mapOf("sourceKind" to "VIDEO_FILE", "sourceFile" to "rtsp://127.0.0.1:8554/live", "loop" to true))
        }
        cmos.validateSettings(mapOf("sourceKind" to "VIDEO_FILE", "sourceFile" to "clip.mp4", "loop" to true))
        assertEquals(SceneSourceCapability.READY, SceneSourceRegistry.defaultRegistry().providerFor("VIDEO_FILE").capability)

        // RTMP_RTSP validates stream URI scheme/host and refuses non-RTMP/RTSP URIs
        assertFailsWith<IllegalArgumentException> {
            cmos.validateSettings(mapOf("sourceKind" to "RTMP_RTSP", "sourceFile" to "http://example.com/live", "loop" to true))
        }
        cmos.validateSettings(mapOf("sourceKind" to "RTMP_RTSP", "sourceFile" to "rtsp://127.0.0.1:8554/camera", "loop" to true))
        assertEquals(SceneSourceCapability.READY, SceneSourceRegistry.defaultRegistry().providerFor("RTMP_RTSP").capability)

        // WEBCAM validates non-empty device identifiers and routes through ffmpeg-pipe
        cmos.validateSettings(mapOf("sourceKind" to "WEBCAM", "sourceFile" to "/dev/video0", "loop" to true))
        assertEquals(SceneSourceCapability.READY, SceneSourceRegistry.defaultRegistry().providerFor("WEBCAM").capability)

        // H264 / RAF report NOT_IMPLEMENTED with zero fake frames
        Files.writeString(dir.resolve("raw.h264"), "placeholder-h264")
        val h264Cfg = mapOf("sourceKind" to "H264", "sourceFile" to "raw.h264", "loop" to true)
        cmos.validateSettings(h264Cfg)
        cmos.applySettings(h264Cfg)
        assertEquals("NOT_IMPLEMENTED", cmos.state()["sourceCapability"])
        assertTrue((cmos.state()["pipeline"] as String).contains("NOT_IMPLEMENTED"))
        val h264Err = assertFailsWith<IllegalStateException> {
            h.sensorPort.expose(defaultExposure, ByteBuffer.allocate(1024 * 576 * 2))
        }
        assertTrue(h264Err.message!!.contains("NOT_IMPLEMENTED"))

        // SHARED_TEXTURE reports UNSUPPORTED cleanly with zero fake outputs
        val sharedCfg = mapOf("sourceKind" to "SHARED_TEXTURE", "sourceFile" to "VRChat-StreamCamera", "loop" to true)
        cmos.validateSettings(sharedCfg)
        cmos.applySettings(sharedCfg)
        assertEquals("UNSUPPORTED", cmos.state()["sourceCapability"])
        val sharedErr = assertFailsWith<IllegalStateException> {
            h.sensorPort.expose(defaultExposure, ByteBuffer.allocate(1024 * 576 * 2))
        }
        assertTrue(sharedErr.message!!.contains("UNSUPPORTED"))
        cmos.close()

        // Custom plugin provider seamlessly upgrades SHARED_TEXTURE to READY without modifying CmosFactory
        val customProvider = object : SceneSourceProvider {
            override val supportedKinds: Set<String> = setOf("SHARED_TEXTURE")
            override val capability: SceneSourceCapability = SceneSourceCapability.READY
            override fun pipelineStatus(kind: String): String = "shared-texture-test-plugin"
            override fun validate(request: SceneSourceRequest) {
                require(request.sourceRef == "TestChannel") { "Unknown channel ${request.sourceRef}" }
            }
            override fun open(request: SceneSourceRequest): SceneSource {
                validate(request)
                return object : SceneSource {
                    override fun radiance(exposure: Exposure, geometry: SensorGeometry, destination: ByteBuffer) {
                        val bytes = ByteArray(geometry.width * geometry.height * 2) { 0x5A.toByte() }
                        destination.put(bytes)
                    }
                    override fun close() {}
                }
            }
        }
        val customRegistry = SceneSourceRegistry.defaultRegistry().withProvider(customProvider)
        val customFactory = CmosFactory(customRegistry)
        val hCustom = Harness(dir)
        val cmosCustom = customFactory.create(
            hCustom.context,
            mapOf("sourceKind" to "SHARED_TEXTURE", "sourceFile" to "TestChannel", "loop" to true),
        )
        assertEquals("READY", cmosCustom.state()["sourceCapability"])
        assertEquals("shared-texture-test-plugin", cmosCustom.state()["pipeline"])
        val out = ByteBuffer.allocate(1024 * 576 * 2)
        assertEquals(1024 * 576 * 2, hCustom.sensorPort.expose(defaultExposure, out))
        assertEquals(0x5A.toByte(), out.array()[0])
        assertEquals(0x5A.toByte(), out.array()[out.array().lastIndex])
        cmosCustom.close()
    }
}
