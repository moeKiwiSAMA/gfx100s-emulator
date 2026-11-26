package com.github.nyabanban.fuji.emulator.components.lens.optics

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.components.lens.LensFactory
import com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.BokehPupilShape
import com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.OpticsBackendPreference
import com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.VulkanOpticalBackend
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.Arrays
import kotlin.math.abs
import kotlin.test.*

class LensOpticalSceneFilterTest {
    private class LensHarness {
        lateinit var lensPort: LensPort
        val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-lens-optics-test")
            override val clock = EmulationClock { 0L }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    lensPort = port as LensPort
                }

                override fun <T : Any> find(name: String, type: Class<T>): T? = null
                override fun removeOwner(owner: String) {}
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) {}
                override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = AutoCloseable {}
            }

            override fun dataPath(relative: String): Path = error("unused")
        }
    }

    private fun readSample16(buf: ByteBuffer, width: Int, x: Int, y: Int): Int {
        val bb = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return bb.getShort((y * width + x) * 2).toInt() and 0xFFFF
    }

    @Test
    fun configuredPupilReachesBothCpuBayerPathsWithNoFrontFilter() {
        val width = 96
        val geometry = SensorGeometry(width, width, 16, BayerPattern.RGGB, 3.76)
        val pixels = FloatArray(width * width)
        pixels[(width / 2) * width + width / 2] = 1f
        val source = LinearRgbImage(width, width, pixels, pixels, pixels)
        val depth = FloatArray(pixels.size) { 12f }
        val param = LensParameters("Test", 110.0, 1.2, 0.65, 1.0, 0.0)
        val exposure = Exposure(0L, 10_000_000L, 100, param)
        val profile = OpticalLensProfile.fromLensParameters(param)
        val filter = LensOpticalSceneFilter(enabled = true, profile = profile)
        val pipeline = CpuOpticalPipeline()
        VulkanOpticalBackend(preference = OpticsBackendPreference.CPU).use { backend ->
            for ((shape, mask) in listOf(
                BokehPupilShape.HEART to BokehMaskShape.HEART,
                BokehPupilShape.STAR_5 to BokehMaskShape.STAR_5,
                BokehPupilShape.RING to BokehMaskShape.RING,
                BokehPupilShape.CIRCLE to BokehMaskShape.OFF,
            )) {
                val expected = ByteBuffer.allocate(pixels.size * 2)
                pipeline.render(
                    source = source, geometry = geometry, exposure = exposure, profile = profile,
                    depthMeters = depth, noiseConfig = CmosNoiseConfig.NOISELESS, destination = expected,
                    pupilMask = if (mask == BokehMaskShape.OFF) null else BuiltinOpticalPupilMask(mask),
                )
                val throughLens = ByteBuffer.allocate(expected.capacity())
                filter.update(pupilShape = shape)
                assertEquals(throughLens.capacity(), filter.filterLinearRgbToBayer(
                    width, width, pixels, pixels, pixels, depth, exposure, geometry, throughLens, 0L,
                ))
                val throughBackend = ByteBuffer.allocate(expected.capacity())
                assertEquals(throughBackend.capacity(), backend.renderToBayer(
                    source = source, geometry = geometry, exposure = exposure, profile = profile,
                    depthMeters = depth, noiseConfig = CmosNoiseConfig.NOISELESS, destination = throughBackend,
                    pupilShape = shape,
                ))
                assertEquals(-1, Arrays.mismatch(expected.array(), throughLens.array()), "First lens Bayer mismatch for $shape")
                assertEquals(-1, Arrays.mismatch(expected.array(), throughBackend.array()), "First backend Bayer mismatch for $shape")
            }
        }
    }

    @Test
    fun configuredBlackLevelIsTheDarkBayerReadoutIncludingExplicitZero() {
        val harness = LensHarness()
        val lens = LensFactory().create(
            harness.context, mapOf("opticsEnabled" to true, "opticsBackend" to "cpu"),
        ) as StatefulComponent
        val filter = assertIs<OpticalSceneFilter>(harness.lensPort)
        val geometry = SensorGeometry(16, 16, 16, BayerPattern.RGGB, 3.76)
        val dark = FloatArray(geometry.width * geometry.height)
        val image = ByteBuffer.allocate(dark.size * 2)
        val exposure = Exposure(0L, 1000L, 100, harness.lensPort.parameters())
        try {
            for (level in listOf(256L, 64L, 0L)) {
                lens.applySettings(mapOf("blackLevel" to level))
                assertEquals(level.toInt(), filter.sensorBlackLevelAdu(16))
                assertEquals(image.capacity(), filter.filterLinearRgbToBayer(
                    16, 16, dark, dark, dark, null, exposure, geometry, image, 0L,
                ))
                for (y in 0 until geometry.height) for (x in 0 until geometry.width) {
                    assertEquals(level.toInt(), readSample16(image, geometry.width, x, y))
                }
                image.clear()
            }
        } finally {
            lens.close()
        }
    }

    @Test
    fun lensFactoryDefaultsOpticsDisabledAndLiveSettingsDriveVignettingIbisBokehAndNoise() {
        val factory = LensFactory()
        val h = LensHarness()
        val lens = factory.create(h.context, emptyMap()) as StatefulComponent
        val filter = h.lensPort as? OpticalSceneFilter
        assertNotNull(filter, "Registered LensPort must implement OpticalSceneFilter")
        assertFalse(filter.enabled, "Optics filter must default to disabled for zero-overhead default QR path")

        val geom = SensorGeometry(128, 72, 16, BayerPattern.RGGB, 3.76)
        val exposure = Exposure(startTick = 0L, durationNanos = 1000L, iso = 100, lens = h.lensPort.parameters())

        // 1) Enable vignetting live -> corners darker than center on uniform gray scene
        lens.applySettings(
            mapOf(
                "opticsEnabled" to true,
                "vignettingCos4" to 4.0,
                "vignettingMechanical" to 0.5,
            ),
        )
        assertTrue(filter.enabled)

        val count = geom.width * geom.height
        val uniform = FloatArray(count) { 0.6f }
        val vigBuf = ByteBuffer.allocate(count * 2)
        val written = filter.filterLinearRgbToBayer(
            srcWidth = geom.width,
            srcHeight = geom.height,
            red = uniform,
            green = uniform,
            blue = uniform,
            depthMeters = null,
            exposure = exposure,
            geometry = geom,
            destination = vigBuf,
            frameIndex = 0L,
        )
        assertEquals(count * 2, written)
        val centerR = readSample16(vigBuf, geom.width, 64, 36)
        val cornerR = readSample16(vigBuf, geom.width, 2, 2)
        assertTrue(centerR > cornerR * 2, "Expected vignetting to darken corners (center=$centerR, corner=$cornerR)")

        // 2) Live IBIS shift translates bright center square deterministically
        lens.applySettings(
            mapOf(
                "vignettingCos4" to 0.0,
                "vignettingMechanical" to 0.0,
                "ibisShiftXPx" to 16.0,
                "ibisShiftYPx" to 0.0,
            ),
        )
        val spot = FloatArray(count)
        for (y in 30 until 42) {
            for (x in 58 until 70) {
                spot[y * geom.width + x] = 0.9f
            }
        }
        val ibisBuf = ByteBuffer.allocate(count * 2)
        filter.filterLinearRgbToBayer(
            srcWidth = geom.width,
            srcHeight = geom.height,
            red = spot,
            green = spot,
            blue = spot,
            depthMeters = null,
            exposure = exposure,
            geometry = geom,
            destination = ibisBuf,
            frameIndex = 1L,
        )
        val shiftedSpotLeft = readSample16(ibisBuf, geom.width, 48, 36)
        val shiftedSpotRight = readSample16(ibisBuf, geom.width, 80, 36)
        assertTrue(
            abs(shiftedSpotLeft - shiftedSpotRight) > 15_000,
            "IBIS horizontal shift should break center symmetry (left=$shiftedSpotLeft, right=$shiftedSpotRight)",
        )

        // 3) Live depth-buffer CoC bokeh blurs out-of-focus plane while keeping focused plane sharp
        lens.applySettings(
            mapOf(
                "ibisShiftXPx" to 0.0,
                "focalLengthMm" to 80.0,
                "referenceFocalLengthMm" to 80.0,
                "aperture" to 1.4,
                "focusDistanceM" to 2.0,
            ),
        )
        val expWideOpen = Exposure(startTick = 0L, durationNanos = 1000L, iso = 100, lens = h.lensPort.parameters())
        val stripes = FloatArray(count) { i -> if ((i % geom.width) % 4 < 2) 0.8f else 0.05f }
        val depthNear = FloatArray(count) { 2.0f }
        val depthFar = FloatArray(count) { 20.0f }
        val sharpBuf = ByteBuffer.allocate(count * 2)
        val blurBuf = ByteBuffer.allocate(count * 2)
        filter.filterLinearRgbToBayer(geom.width, geom.height, stripes, stripes, stripes, depthNear, expWideOpen, geom, sharpBuf, 2L)
        filter.filterLinearRgbToBayer(geom.width, geom.height, stripes, stripes, stripes, depthFar, expWideOpen, geom, blurBuf, 2L)

        fun rowContrast(buf: ByteBuffer): Double {
            var sum = 0.0
            val y = 36
            for (x in 20 until 108 step 2) {
                sum += abs(readSample16(buf, geom.width, x, y) - readSample16(buf, geom.width, x + 2, y))
            }
            return sum
        }
        assertTrue(
            rowContrast(sharpBuf) > rowContrast(blurBuf) * 1.5,
            "Out-of-focus depth plane must have lower high-frequency contrast than in-focus plane",
        )

        // 4) Live CMOS noise + black level pedestal is bit-exact deterministic for same seed & frameIndex
        lens.applySettings(
            mapOf(
                "noiseEnabled" to true,
                "noiseSeed" to 12345L,
                "blackLevel" to 256L,
            ),
        )
        val dark = FloatArray(count)
        val n1 = ByteBuffer.allocate(count * 2)
        val n2 = ByteBuffer.allocate(count * 2)
        filter.filterLinearRgbToBayer(geom.width, geom.height, dark, dark, dark, null, exposure, geom, n1, 7L)
        filter.filterLinearRgbToBayer(geom.width, geom.height, dark, dark, dark, null, exposure, geom, n2, 7L)
        assertContentEquals(n1.array(), n2.array(), "Deterministic CMOS noise must match for identical seed/frameIndex")
        val darkAvg = (0 until geom.width step 2).map { readSample16(n1, geom.width, it, 36) }.average()
        assertTrue(darkAvg >= 200.0, "Black level pedestal 256 must lift dark frame average (got $darkAvg)")
    }

    @Test
    fun lensFactorySnapshotPreservesOpticsSettingsAndAcceptsLegacySixKeySnapshot() {
        val factory = LensFactory()
        val h1 = LensHarness()
        val lens1 = factory.create(h1.context, emptyMap()) as StatefulComponent
        lens1.applySettings(
            mapOf(
                "opticsEnabled" to true,
                "distortionK1" to -0.12,
                "caRedScale" to 1.02,
                "vignettingCos4" to 2.5,
                "ibisShiftXPx" to 4.5,
                "noiseEnabled" to true,
                "noiseSeed" to 99L,
                "blackLevel" to 128L,
            ),
        )
        val snap = lens1.saveSnapshot()

        val h2 = LensHarness()
        val lens2 = factory.create(h2.context, emptyMap()) as StatefulComponent
        lens2.restoreSnapshot(snap)
        assertEquals(lens1.saveSnapshot(), lens2.saveSnapshot())
        assertTrue((h2.lensPort as OpticalSceneFilter).enabled)

        // Legacy 6-key snapshot restores cleanly without error
        val legacySnap = mapOf(
            "schema" to 1,
            "componentId" to "lens",
            "scalarValues" to mapOf(
                "model" to "gf-45mm",
                "focalLengthMm" to 45.0,
                "aperture" to 2.8,
                "focusDistanceM" to 3.0,
                "transmission" to 0.92,
                "focusBreathing" to 0.0,
            ),
        )
        lens2.restoreSnapshot(legacySnap)
        assertEquals("gf-45mm", h2.lensPort.parameters().model)
        assertEquals(45.0, h2.lensPort.parameters().focalLengthMm)
    }

    @Test
    fun lensOpticalSceneFilterReusesWorkspaceAcrossRepeatedRenders() {
        val filter = LensOpticalSceneFilter(
            enabled = true,
            profile = OpticalLensProfile(
                focalLengthMm = 63.0,
                referenceFocalLengthMm = 63.0,
                k1 = -0.08,
                caRedScale = 1.01,
                caBlueScale = 0.99,
                cos4Power = 2.0,
            ),
            ibis = IbisShiftState(shiftXPixels = 2.5, shiftYPixels = -1.5, rollRadians = 0.01),
            noiseConfig = CmosNoiseConfig(seed = 7L, enableNoise = true, blackLevel = 64),
        )
        val geom = SensorGeometry(256, 144, 16, BayerPattern.RGGB, 3.76)
        val count = geom.width * geom.height
        val r = FloatArray(count) { 0.5f }
        val g = FloatArray(count) { 0.4f }
        val b = FloatArray(count) { 0.3f }
        val exposure = Exposure(startTick = 0L, durationNanos = 1000L, iso = 400, lens = LensParameters("gf-63", 63.0, 2.8, 10.0, 0.9, 0.0))
        val out = ByteBuffer.allocate(count * 2)

        repeat(10) { idx ->
            out.clear()
            val written = filter.filterLinearRgbToBayer(geom.width, geom.height, r, g, b, null, exposure, geom, out, idx.toLong())
            assertEquals(count * 2, written)
        }
        assertEquals(10L, filter.framesFiltered)
    }

    @Test
    fun liveIbisShiftPortShiftsRenderedBayerOutputAndInvalidatesCache() {
        var shiftXPx = 0.0
        val liveIbis = object : IbisShiftPort {
            override val ibisMode: Int = IbisShiftPort.MODE_CONTINUOUS
            override val ibisModeName: String = "CONTINUOUS"
            override val stageLocked: Boolean = false
            override val stabilizationState: Int = 3
            override val stabilizationGain: Int = 100
            override val focalLengthMm: Double = 63.0
            override val pixelShiftTargetX: Int = IbisShiftPort.HALL_CENTER
            override val pixelShiftTargetY: Int = IbisShiftPort.HALL_CENTER
            override val shiftXUm: Double get() = shiftXPx * 3.76
            override val shiftYUm: Double = 0.0
            override val rollDeg: Double = 0.0
            override fun sampleShiftPixels(pixelPitchUm: Double, tick: Long): Triple<Double, Double, Double> =
                Triple(shiftXPx, 0.0, 0.0)
        }
        val filter = LensOpticalSceneFilter(
            enabled = true,
            profile = OpticalLensProfile.IDENTITY,
            ibisShiftPortProvider = { liveIbis },
            clockTickProvider = { 0L },
        )
        val geom = SensorGeometry(64, 48, 16, BayerPattern.RGGB, 3.76)
        val count = geom.width * geom.height
        // Horizontal ramp: shifting X moves pixel intensities deterministically
        val ramp = FloatArray(count) { i -> (i % geom.width).toFloat() / geom.width.toFloat() }
        val exp = Exposure(0L, 1000L, 100, LensParameters("gf-63", 63.0, 2.8, 10.0, 1.0, 0.0))

        val buf0 = ByteBuffer.allocate(count * 2)
        filter.filterLinearRgbToBayer(geom.width, geom.height, ramp, ramp, ramp, null, exp, geom, buf0, 0L)
        val buf0Repeat = ByteBuffer.allocate(count * 2)
        filter.filterLinearRgbToBayer(geom.width, geom.height, ramp, ramp, ramp, null, exp, geom, buf0Repeat, 1L)
        assertContentEquals(buf0.array(), buf0Repeat.array())
        assertEquals(1L, filter.cacheHits)

        // Moving the biscom IBIS stage must invalidate cache and visibly shift the Bayer output!
        shiftXPx = 4.0
        val bufShifted = ByteBuffer.allocate(count * 2)
        filter.filterLinearRgbToBayer(geom.width, geom.height, ramp, ramp, ramp, null, exp, geom, bufShifted, 2L)
        assertFalse(buf0.array().contentEquals(bufShifted.array()), "IBIS shift must alter rendered Bayer frame")
        assertEquals(2L, filter.cacheMisses)
    }

    @Test
    fun filterRadianceFrame3DSkipsDoubleZoomAndDoubleHandheldShake() {
        val shakingIbis = object : IbisShiftPort {
            override val ibisMode: Int = IbisShiftPort.MODE_OFF
            override val ibisModeName: String = "OFF"
            override val stageLocked: Boolean = true
            override val stabilizationState: Int = 0
            override val stabilizationGain: Int = 0
            override val focalLengthMm: Double = 100.0
            override val pixelShiftTargetX: Int = IbisShiftPort.HALL_CENTER
            override val pixelShiftTargetY: Int = IbisShiftPort.HALL_CENTER
            override val shiftXUm: Double = 0.0
            override val shiftYUm: Double = 0.0
            override val rollDeg: Double = 0.0
            override fun sampleShiftPixels(pixelPitchUm: Double, tick: Long): Triple<Double, Double, Double> =
                Triple(25.0, -15.0, 0.05) // 2D handheld tremor offset
        }
        // Zoomed profile (100mm vs 45mm reference -> 2.22x zoom on 2D, 1.0x when 3D already rendered at 100mm)
        val zoomedProfile = OpticalLensProfile(
            focalLengthMm = 100.0,
            referenceFocalLengthMm = 45.0,
            fNumber = 4.0,
            focusDistanceM = 2.0,
        )
        val filterWithIbis = LensOpticalSceneFilter(
            enabled = true,
            profile = zoomedProfile,
            ibisShiftPortProvider = { shakingIbis },
            clockTickProvider = { 100L },
        )
        val filterRefNoZoom = LensOpticalSceneFilter(
            enabled = true,
            profile = zoomedProfile.copy(referenceFocalLengthMm = 100.0),
            ibisShiftPortProvider = { null },
        )
        val geom = SensorGeometry(64, 48, 16, BayerPattern.RGGB, 3.76)
        val count = geom.width * geom.height
        val ramp = FloatArray(count) { i -> (i % geom.width).toFloat() / geom.width.toFloat() }
        val depth = FloatArray(count) { 2.0f }
        val exp = Exposure(0L, 1000L, 100, LensParameters("gf45-100", 100.0, 4.0, 2.0, 1.0, 0.0))

        val frame3d = LinearRadianceFrame(
            width = geom.width,
            height = geom.height,
            red = ramp,
            green = ramp,
            blue = ramp,
            depthMeters = depth,
            dimension = SceneDimension.DIM_3D,
            pose = CameraPose3D(focalLengthMm = 100.0),
        )
        val out3d = ByteBuffer.allocate(count * 2)
        val outRef = ByteBuffer.allocate(count * 2)
        filterWithIbis.filterRadianceFrame(frame3d, exp, geom, out3d, 0L)
        filterRefNoZoom.filterRadianceFrame(frame3d, exp, geom, outRef, 0L)
        assertContentEquals(
            outRef.array(),
            out3d.array(),
            "DIM_3D frame rendered at 100mm must neither double-zoom nor double-apply 2D sampleShiftPixels shake",
        )
    }
}
