package com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.BokehMaskShape
import com.github.nyabanban.fuji.emulator.api.BuiltinOpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.ComponentEvent
import com.github.nyabanban.fuji.emulator.api.EmulationClock
import com.github.nyabanban.fuji.emulator.api.EventChannel
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.ExternalGpuColorFormat
import com.github.nyabanban.fuji.emulator.api.ExternalGpuDepthFormat
import com.github.nyabanban.fuji.emulator.api.ExternalGpuFrameDescriptor
import com.github.nyabanban.fuji.emulator.api.ExternalGpuMemoryHandleType
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.Ports
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import com.github.nyabanban.fuji.emulator.components.lens.LensFactory
import com.github.nyabanban.fuji.emulator.components.lens.optics.BorderMode
import com.github.nyabanban.fuji.emulator.components.lens.optics.CmosNoiseConfig
import com.github.nyabanban.fuji.emulator.components.lens.optics.CmosNoiseModel
import com.github.nyabanban.fuji.emulator.components.lens.optics.CpuOpticalPipeline
import com.github.nyabanban.fuji.emulator.components.lens.optics.LinearRgbImage
import com.github.nyabanban.fuji.emulator.components.lens.optics.OpticalLensProfile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VulkanOpticalBackendTest {

    private class CapturingLogHandler : Handler() {
        val records = mutableListOf<LogRecord>()
        override fun publish(record: LogRecord) {
            records += record
        }
        override fun flush() {}
        override fun close() {}
    }

    @Test
    fun missingNativeLibraryFallsBackToCpuWithExplicitWarnLog() {
        val logger = Logger.getLogger(VulkanOpticalBackend::class.java.name)
        val handler = CapturingLogHandler()
        logger.addHandler(handler)
        try {
            VulkanOpticalBackend(
                preference = OpticsBackendPreference.AUTO,
                explicitLibraryPath = Paths.get("/nonexistent/path/libfuji_vulkan_optics_missing.so"),
            ).use { backend ->
                val diag = backend.diagnostics()
                assertEquals(ActiveOpticsBackend.CPU, diag.activeBackend)
                assertFalse(diag.nativeBridgeLoaded)
                val warns = handler.records.filter { it.level == Level.WARNING && it.message.contains("[FALLBACK][vulkan]") }
                assertTrue(warns.isNotEmpty(), "Expected explicit WARN [FALLBACK][vulkan] log on CPU fallback")
                assertNotNull(diag.fallbackReason)

                val src = LinearRgbImage(
                    width = 16,
                    height = 16,
                    red = FloatArray(256) { 0.4f },
                    green = FloatArray(256) { 0.5f },
                    blue = FloatArray(256) { 0.6f },
                )
                val out = backend.renderLinearRgb(src, profile = OpticalLensProfile.IDENTITY)
                assertEquals(16, out.width)
                assertEquals(16, out.height)
                assertTrue(abs(out.red[120] - 0.4f) < 1e-4f)
                assertTrue(backend.diagnostics().framesRendered == 1L)
            }
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun cpuPreferenceSkipsNativeProbeWithoutWarning() {
        val logger = Logger.getLogger(VulkanOpticalBackend::class.java.name)
        val handler = CapturingLogHandler()
        logger.addHandler(handler)
        try {
            VulkanOpticalBackend(preference = OpticsBackendPreference.CPU).use { backend ->
                val diag = backend.diagnostics()
                assertEquals(ActiveOpticsBackend.CPU, diag.activeBackend)
                assertEquals(OpticsBackendPreference.CPU, diag.preference)
                assertFalse(diag.nativeBridgeLoaded)
                val warns = handler.records.filter { it.level == Level.WARNING }
                assertTrue(warns.isEmpty(), "CPU preference must not emit fallback warnings")
            }
        } finally {
            logger.removeHandler(handler)
        }
    }

    private fun withVulkanContext(test: (VulkanNativeOpticsBridge, MemorySegment) -> Unit) {
        val loaded = VulkanNativeOpticsBridge.tryLoad()
        org.junit.jupiter.api.Assumptions.assumeTrue(loaded.isSuccess, "Vulkan optics library unavailable")
        loaded.getOrThrow().use { bridge ->
            val opened = bridge.createContext(VulkanNativeOpticsBridge.FUJI_VK_SELECT_DEFAULT or
                VulkanNativeOpticsBridge.FUJI_VK_SELECT_ALLOW_SOFTWARE)
            org.junit.jupiter.api.Assumptions.assumeTrue(opened.status == VulkanNativeOpticsBridge.FUJI_VK_OK, opened.errorMessage)
            try {
                test(bridge, opened.contextHandle)
            } finally {
                bridge.destroyContext(opened.contextHandle)
            }
        }
    }

    @Test
    fun directBayerOutputHonorsWritableNativeSliceAndLifetime() = withVulkanContext { bridge, ctx ->
        val source = FloatArray(4 * 4 * 4) { 0.5f }
        val cmos = VulkanNativeOpticsBridge.CmosParamsStruct(
            width = 4, height = 4, bayerPattern = 0, bitDepth = 8, maxCode = 255,
            blackLevel = 0, enableNoise = 0, iso = 100, baseIso = 100f,
            fullWellElectrons = 1000f, opticalExposureScale = 1f,
            readNoiseBaseElectrons = 0f, readNoiseIsoSlope = 0f, seedLo = 0, seedHi = 0, frameIndexLo = 0,
        )
        val padded = ByteArray(19) { 0x55 }
        assertEquals(0, bridge.renderCmosBayer(ctx, source, padded, cmos).first)
        assertTrue(padded.take(16).any { it != 0x55.toByte() })
        assertTrue(padded.drop(16).all { it == 0x55.toByte() })
        fun render(dst: MemorySegment, count: Int) =
            bridge.renderCmosBayerDirect(ctx, source, source.size, dst, count, cmos).first

        Arena.ofConfined().use { arena ->
            val allocation = arena.allocate(20).fill(0x55.toByte())
            val middle = allocation.asSlice(2, 16)
            for ((destination, count) in listOf(
                middle to -1, middle to 0, middle.asSlice(0, 15) to 16,
                middle.asReadOnly() to 16, MemorySegment.ofArray(ByteArray(16)) to 16,
            )) {
                assertEquals(VulkanNativeOpticsBridge.FUJI_VK_ERR_INVALID_ARG, render(destination, count))
            }
            val wrongThread = java.util.concurrent.CompletableFuture.supplyAsync { render(middle, 16) }
            assertEquals(VulkanNativeOpticsBridge.FUJI_VK_ERR_INVALID_ARG, wrongThread.get(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(allocation.toArray(ValueLayout.JAVA_BYTE).all { it == 0x55.toByte() })
            assertEquals(VulkanNativeOpticsBridge.FUJI_VK_OK, render(middle, 16))
            for (offset in listOf(0L, 1L, 18L, 19L)) assertEquals(0x55.toByte(), allocation.get(ValueLayout.JAVA_BYTE, offset))
            assertTrue(middle.toArray(ValueLayout.JAVA_BYTE).any { it != 0x55.toByte() })
        }
        val closed = Arena.ofConfined().use { it.allocate(16) }
        assertEquals(VulkanNativeOpticsBridge.FUJI_VK_ERR_INVALID_ARG, render(closed, 16))
    }

    @Test
    fun opticsReadbackPreservesUnusedRgbaCapacity() = withVulkanContext { bridge, ctx ->
        val optics = VulkanNativeOpticsBridge.OpticsParamsStruct(
            srcWidth = 4, srcHeight = 4, dstWidth = 4, dstHeight = 4,
            k1 = 0f, k2 = 0f, p1 = 0f, p2 = 0f, caRedScale = 1f, caBlueScale = 1f,
            cos4Power = 0f, mechVignetting = 0f, focalLengthMm = 45f, sensorWidthMm = 44f,
            focusDistanceM = 2f, fNumber = 4f, invZoom = 1f, ibisShiftX = 0f,
            ibisShiftY = 0f, ibisCosRoll = 1f, ibisSinRoll = 0f, maxBokehPx = 0f,
            bladeRoundness = 1f, catsEye = 0f, onionRing = 0f, rimHighlight = 0f,
            planarDepthM = 2f, imageCircleMm = 55f, pupilShape = 0,
            bladeCount = 9, borderClamp = 1, flags = 0,
        )
        val source = FloatArray(4 * 4 * 4) { i -> if (i % 4 == 3) 2f else (i + 1) / 65f }
        val expected = FloatArray(source.size)
        assertEquals(0, bridge.renderOptics(ctx, source, expected, optics).first)
        assertTrue(expected.any { it > 0f })
        val sentinel = -7f
        val padded = FloatArray(source.size + 8) { sentinel }
        fun checkPixels() {
            assertTrue(expected.contentEquals(padded.copyOf(source.size)))
            assertTrue((source.size until padded.size).all { padded[it] == sentinel })
        }
        assertEquals(0, bridge.renderOptics(ctx, source, padded, optics).first)
        checkPixels()
        assertEquals(0, bridge.uploadScene(ctx, 42L, source, source.size / 4).first)
        padded.fill(sentinel)
        assertEquals(0, bridge.renderCachedSceneOptics(ctx, 42L, padded, optics).first)
        checkPixels()
        fun channel(index: Int) = FloatArray(16) { source[it * 4 + index] }
        val planar = LinearRgbImage(4, 4, channel(0), channel(1), channel(2))
        for ((index, depth) in listOf(channel(3), null).withIndex()) {
            val sceneKey = 43L + index
            for (i in 0 until 16) source[i * 4 + 3] = depth?.get(i) ?: 0f
            assertEquals(0, bridge.renderOptics(ctx, source, expected, optics).first)
            assertEquals(0, bridge.uploadPlanarScene(ctx, sceneKey, planar, depth).first)
            padded.fill(sentinel)
            assertEquals(0, bridge.renderCachedSceneOptics(ctx, sceneKey, padded, optics).first)
            checkPixels()
        }
    }

    @Test
    fun ispReadbackPreservesRowPaddingAndUnusedPlaneCapacity() = withVulkanContext { bridge, ctx ->
        val raw = ByteArray(4 * 4) { (it * 9).toByte() }
        for (format in listOf(VulkanNativeOpticsBridge.ISP_FMT_UYVY422, VulkanNativeOpticsBridge.ISP_FMT_YYUV422,
            VulkanNativeOpticsBridge.ISP_FMT_NV16, VulkanNativeOpticsBridge.ISP_FMT_NV12)) {
            val semiPlanar = format == VulkanNativeOpticsBridge.ISP_FMT_NV16 || format == VulkanNativeOpticsBridge.ISP_FMT_NV12
            val bytesPerRow = if (semiPlanar) 4 else 8
            val uvRows = if (format == VulkanNativeOpticsBridge.ISP_FMT_NV12) 2 else 3
            val params = VulkanNativeOpticsBridge.IspDemosaicParamsStruct(4, 4, 4, 3, is16Bit = false, outputFormat = format)
            val tightY = ByteArray(bytesPerRow * 3)
            val tightUv = if (semiPlanar) ByteArray(bytesPerRow * uvRows) else null
            assertEquals(0, bridge.ispDemosaic(ctx, raw, outYOrPacked = tightY, outUvPlane = tightUv, params = params).first)
            val stride = bytesPerRow + 5
            fun checkPlane(actual: ByteArray, active: ByteArray?) {
                for (i in actual.indices) {
                    val x = i % stride
                    val j = i / stride * bytesPerRow + x
                    val want = if (active != null && x < bytesPerRow && j < active.size) active[j] else 0x55.toByte()
                    assertEquals(want, actual[i], "format=$format offset=$i")
                }
            }
            val y = ByteArray(stride * 3 + 5) { 0x55 }
            val uv = ByteArray(stride * uvRows + 5) { 0x55 }
            assertEquals(0, bridge.ispDemosaic(ctx, raw, outYOrPacked = y, outUvPlane = uv,
                params = params.copy(dstStrideBytes = stride)).first)
            checkPlane(y, tightY)
            checkPlane(uv, tightUv)
        }
    }

    @Test
    fun nativeBridgeSpirvValidationAndRealVulkanGpuComputeParity() {
        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) {
            // Native bridge not built in this environment — verified gracefully via fallback test above.
            return
        }
        loaded.getOrThrow().use { bridge ->
            assertEquals(1, bridge.abiVersion())
            for (shaderId in listOf(
                VulkanNativeOpticsBridge.SHADER_OPTICS_WARP,
                VulkanNativeOpticsBridge.SHADER_PUPIL_BOKEH,
                VulkanNativeOpticsBridge.SHADER_CMOS_BAYER,
                VulkanNativeOpticsBridge.SHADER_OPTICAL_FILTER_STACK,
                VulkanNativeOpticsBridge.SHADER_ISP_DEMOSAIC,
                VulkanNativeOpticsBridge.SHADER_ISP_PEAKING,
            )) {
                val words = bridge.getSpirvWords(shaderId)
                val report = validateSpirv10Compute(words)
                assertTrue(
                    report.valid,
                    "Embedded native SPIR-V shader $shaderId failed validation: ${report.errors}",
                )
                assertEquals("main", report.entryPointName)
                assertEquals(8, report.localSizeX)
                assertEquals(8, report.localSizeY)
            }
        }

        // Execute real Vulkan 1.1 compute (allowing Lavapipe software ICD on headless hosts)
        VulkanOpticalBackend(
            preference = OpticsBackendPreference.VULKAN,
            allowSoftwareVulkan = true,
        ).use { gpu ->
            val diag = gpu.diagnostics()
            if (diag.activeBackend != ActiveOpticsBackend.VULKAN) {
                // Host has no Vulkan ICD installed; fallback already verified.
                return
            }
            assertTrue(diag.nativeBridgeLoaded)
            assertTrue(diag.deviceName.isNotBlank())

            // 1. Optical Warp + Vignetting parity vs CpuOpticalPipeline
            val w = 32
            val h = 32
            val src = LinearRgbImage(
                width = w,
                height = h,
                red = FloatArray(w * h) { 0.7f },
                green = FloatArray(w * h) { 0.4f },
                blue = FloatArray(w * h) { 0.2f },
            )
            val vigProfile = OpticalLensProfile(
                focalLengthMm = 50.0,
                referenceFocalLengthMm = 50.0,
                cos4Power = 4.0,
                mechanicalVignetting = 0.25,
                fNumber = 2.0,
                focusDistanceM = 2.0,
            )
            val gpuVig = gpu.renderLinearRgb(src, profile = vigProfile, maxBokehRadiusPx = 0, borderMode = BorderMode.CLAMP)
            val cpuVig = CpuOpticalPipeline(maxBokehRadiusPx = 0).renderLinearRgb(
                source = src,
                dstWidth = w,
                dstHeight = h,
                profile = vigProfile,
                borderMode = BorderMode.CLAMP,
            )
            val center = (h / 2) * w + (w / 2)
            assertTrue(gpuVig.red[0] < gpuVig.red[center] * 0.75f, "GPU vignetting must darken corner")
            assertTrue(abs(gpuVig.red[center] - cpuVig.red[center]) < 0.02f, "Center GPU vs CPU parity")

            // 2. True 2D Entrance-Pupil Bokeh across all 5 shapes (CIRCLE, POLYGON, STAR_5, HEART, RING)
            val dotR = FloatArray(w * h)
            val dotG = FloatArray(w * h)
            val dotB = FloatArray(w * h)
            val depth = FloatArray(w * h) { 20.0f }
            dotR[center] = 80f
            dotG[center] = 80f
            dotB[center] = 80f
            val dotSrc = LinearRgbImage(w, h, dotR, dotG, dotB)
            val bokehProfile = OpticalLensProfile(
                focalLengthMm = 85.0,
                referenceFocalLengthMm = 85.0,
                fNumber = 1.4,
                focusDistanceM = 1.0,
                apertureBladeCount = 5,
                apertureBladeRoundness = 0.0,
            )
            val circleImg = gpu.renderLinearRgb(dotSrc, profile = bokehProfile, depthMeters = depth, pupilShape = BokehPupilShape.CIRCLE, maxBokehRadiusPx = 10)
            val starImg = gpu.renderLinearRgb(dotSrc, profile = bokehProfile, depthMeters = depth, pupilShape = BokehPupilShape.STAR_5, maxBokehRadiusPx = 10)
            val heartImg = gpu.renderLinearRgb(dotSrc, profile = bokehProfile, depthMeters = depth, pupilShape = BokehPupilShape.HEART, maxBokehRadiusPx = 10)
            val ringImg = gpu.renderLinearRgb(dotSrc, profile = bokehProfile, depthMeters = depth, pupilShape = BokehPupilShape.RING, maxBokehRadiusPx = 10)

            assertTrue(ringImg.red[center] < 1e-3f, "RING pupil must produce dark central obstruction")
            var starDiff = 0.0
            var heartDiff = 0.0
            for (i in 0 until w * h) {
                starDiff += abs(starImg.red[i] - circleImg.red[i])
                heartDiff += abs(heartImg.red[i] - circleImg.red[i])
            }
            assertTrue(starDiff > 0.5, "STAR_5 bokeh PSF must differ from CIRCLE")
            assertTrue(heartDiff > 0.5, "HEART bokeh PSF must differ from CIRCLE")

            // 3. Single-submit chained Optics -> Stateless SplitMix64 CMOS Bayer parity
            val geom = SensorGeometry(width = w, height = h, bitDepth = 14, bayer = BayerPattern.RGGB, pitchMicrometres = 3.76)
            val exposure = Exposure(
                startTick = 0L,
                durationNanos = 10_000_000L,
                iso = 100,
                lens = LensParameters("GF50mmF3.5", 50.0, 2.8, 2.0, 1.0, 0.0),
            )
            val cleanNoise = CmosNoiseConfig(seed = 42L, enableNoise = false, blackLevel = 256)
            val gpuBuf = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
            val cpuBuf = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
            val gpuBytes = gpu.renderToBayer(
                source = src,
                geometry = geom,
                exposure = exposure,
                profile = OpticalLensProfile.IDENTITY,
                noiseConfig = cleanNoise,
                destination = gpuBuf,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
            )
            val cpuBytes = CmosNoiseModel.mosaicAndReadout(
                red = src.red,
                green = src.green,
                blue = src.blue,
                geometry = geom,
                exposure = exposure,
                config = cleanNoise,
                destination = cpuBuf,
                fNumber = OpticalLensProfile.IDENTITY.fNumber,
                transmission = OpticalLensProfile.IDENTITY.transmission,
            )
            assertEquals(cpuBytes, gpuBytes)
            var maxAduDiff = 0
            for (i in 0 until w * h) {
                val gSample = gpuBuf.getShort(i * 2).toInt() and 0xFFFF
                val cSample = cpuBuf.getShort(i * 2).toInt() and 0xFFFF
                maxAduDiff = maxOf(maxAduDiff, abs(gSample - cSample))
            }
            assertTrue(maxAduDiff <= 1, "Noiseless GPU vs CPU Bayer parity exceeded 1 ADU (maxDiff=$maxAduDiff)")

            // Noisy GPU SplitMix64 determinism & frame variation
            val highIsoExp = exposure.copy(durationNanos = 150_000L, iso = 3200)
            val noisyCfg = CmosNoiseConfig(seed = 0x112233445566L, enableNoise = true, blackLevel = 256)
            val nBuf1 = ByteBuffer.allocate(w * h * 2)
            val nBuf2 = ByteBuffer.allocate(w * h * 2)
            val nBuf3 = ByteBuffer.allocate(w * h * 2)
            gpu.renderToBayer(src, geom, highIsoExp, OpticalLensProfile.IDENTITY, noiseConfig = noisyCfg, destination = nBuf1, maxBokehRadiusPx = 0, borderMode = BorderMode.CLAMP, frameIndex = 7L)
            gpu.renderToBayer(src, geom, highIsoExp, OpticalLensProfile.IDENTITY, noiseConfig = noisyCfg, destination = nBuf2, maxBokehRadiusPx = 0, borderMode = BorderMode.CLAMP, frameIndex = 7L)
            gpu.renderToBayer(src, geom, highIsoExp, OpticalLensProfile.IDENTITY, noiseConfig = noisyCfg, destination = nBuf3, maxBokehRadiusPx = 0, borderMode = BorderMode.CLAMP, frameIndex = 8L)
            assertTrue(nBuf1.array().contentEquals(nBuf2.array()), "SplitMix64 GPU noise must be deterministic")
            assertFalse(nBuf1.array().contentEquals(nBuf3.array()), "SplitMix64 GPU noise must vary across frames")

            // 4. Pass 0 GPU Optical Filter Stack (ND, GND, Black Mist, Star 4/6/8-pt, zero-overhead bypass)
            val flat = LinearRgbImage.uniform(w, h, 0.8f, 0.8f, 0.8f)
            val ndOut = gpu.renderLinearRgb(
                flat,
                profile = OpticalLensProfile.IDENTITY,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                filterStack = OpticalFilterStackConfig(ndStops = 2.0),
            )
            assertTrue(abs(ndOut.red[center] - 0.20f) < 0.01f, "GPU ND 2-stop should attenuate 0.8 -> 0.2 (got ${ndOut.red[center]})")

            val gndOut = gpu.renderLinearRgb(
                flat,
                profile = OpticalLensProfile.IDENTITY,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                filterStack = OpticalFilterStackConfig(gndStops = 3.0, gndHorizonY = 0.5, gndFeather = 0.1),
            )
            val topPx = 1 * w + (w / 2)
            val botPx = (h - 2) * w + (w / 2)
            assertTrue(gndOut.red[topPx] < gndOut.red[botPx] * 0.25f, "GPU GND top sky must darken vs foreground")

            val starDotR = FloatArray(w * h)
            val starDotG = FloatArray(w * h)
            val starDotB = FloatArray(w * h)
            starDotR[center] = 20f
            starDotG[center] = 20f
            starDotB[center] = 20f
            val starDot = LinearRgbImage(w, h, starDotR, starDotG, starDotB)
            val mistOut = gpu.renderLinearRgb(
                starDot,
                profile = OpticalLensProfile.IDENTITY,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                filterStack = OpticalFilterStackConfig(blackMistStrength = 0.6, blackMistThreshold = 0.8, blackMistRadiusPx = 5.0, blackMistWarmth = 0.15),
            )
            val haloPx = (h / 2) * w + (w / 2 + 5)
            assertTrue(mistOut.red[haloPx] > 0.05f, "GPU Black Mist must produce highlight halation")
            assertTrue(mistOut.red[haloPx] > mistOut.blue[haloPx], "GPU Black Mist warmth must bias R > B")

            val star4 = gpu.renderLinearRgb(
                starDot,
                profile = OpticalLensProfile.IDENTITY,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                filterStack = OpticalFilterStackConfig(starPoints = 4, starStrength = 1.0, starThreshold = 0.8, starRadiusPx = 10.0),
            )
            val star8 = gpu.renderLinearRgb(
                starDot,
                profile = OpticalLensProfile.IDENTITY,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                filterStack = OpticalFilterStackConfig(starPoints = 8, starStrength = 1.0, starThreshold = 0.8, starRadiusPx = 10.0),
            )
            val horizSpike = (h / 2) * w + (w / 2 + 5)
            val diagSpike = (h / 2 + 4) * w + (w / 2 + 4)
            assertTrue(star4.red[horizSpike] > 0.05f, "GPU 4-pt Star must emit horizontal spike")
            assertTrue(star8.red[diagSpike] > star4.red[diagSpike] + 0.02f, "GPU 8-pt Star must emit diagonal spike")

            // 5. Phase 2 Game Zero-Copy ExternalGpuFrameDescriptor C ABI validation
            val badImportRc = gpu.validateExternalGpuImport(
                ExternalGpuFrameHandle(
                    handleType = ExternalGpuHandleType.NONE,
                    width = 0,
                    height = 0,
                    colorHandle = 0L,
                    colorMemorySizeBytes = 0L,
                ),
            )
            assertNotEquals(VulkanNativeOpticsBridge.FUJI_VK_OK, badImportRc)

            val dmaBufDesc = ExternalGpuFrameDescriptor(
                handleType = ExternalGpuMemoryHandleType.DMA_BUF_FD,
                width = w,
                height = h,
                colorFormat = ExternalGpuColorFormat.RGBA32_SFLOAT,
                colorHandle = 7L,
                colorMemorySizeBytes = w.toLong() * h.toLong() * 16L,
                depthFormat = ExternalGpuDepthFormat.D32_SFLOAT,
                depthHandle = 8L,
                depthMemorySizeBytes = w.toLong() * h.toLong() * 4L,
            )
            assertTrue(dmaBufDesc.validate().isEmpty())
            // Descriptor validation checks the handle-specific capabilities before import.
            val requiredCaps = mapOf(
                ExternalGpuMemoryHandleType.DMA_BUF_FD to 0x6,
                ExternalGpuMemoryHandleType.WIN32_NT_HANDLE to 0x1,
            )
            for ((handle, mask) in requiredCaps) {
                val rc = gpu.validateExternalFrameDescriptor(dmaBufDesc.copy(handleType = handle))
                val expected = if (diag.externalMemoryCaps and mask == mask) VulkanNativeOpticsBridge.FUJI_VK_OK else -6
                assertEquals(expected, rc, "handle=$handle caps=${diag.externalMemoryCaps}")
            }

            // 6. Phase 3 LiveView ISP Demosaic + Q10 Gain + 3x3 CCM + 1D Tone LUT + Focus Peaking parity
            val iw = 32
            val ih = 32
            val raw16 = ByteArray(iw * ih * 2)
            for (y in 0 until ih) {
                for (x in 0 until iw) {
                    val v12 = if (x < 16) 240 else if ((y and 1) == 0 && (x and 1) == 0) 3200 else 2100
                    val v16 = v12 shl 4
                    val o = (y * iw + x) * 2
                    raw16[o] = (v16 and 0xFF).toByte()
                    raw16[o + 1] = ((v16 ushr 8) and 0xFF).toByte()
                }
            }
            val customCcm = intArrayOf(1126, -51, -51, -30, 1084, -30, -20, -40, 1084)
            val customLut = IntArray(4096) { idx -> ((idx * 255 + 2047) / 4095).coerceIn(0, 255) }
            val ispParams = VulkanNativeOpticsBridge.IspDemosaicParamsStruct(
                srcWidth = iw,
                srcHeight = ih,
                dstWidth = iw,
                dstHeight = ih,
                is16Bit = true,
                outputFormat = VulkanNativeOpticsBridge.ISP_FMT_UYVY422,
                flags = VulkanNativeOpticsBridge.ISP_FLAG_HAS_CCM or VulkanNativeOpticsBridge.ISP_FLAG_PEAKING_ENABLED,
                gainR1024 = 1152,
                gainG1024 = 1024,
                gainB1024 = 960,
                ccm = customCcm,
                peakingThreshold = 48,
                peakingHiY = 76,
                peakingHiCb = 0x55,
                peakingHiCr = 0xFF,
            )
            val gpuUyvy = ByteArray(iw * ih * 2)
            assertTrue(
                gpu.tryIspDemosaic(
                    rawBayer = raw16,
                    lutR = customLut,
                    lutG = customLut,
                    lutB = customLut,
                    outYOrPacked = gpuUyvy,
                    params = ispParams,
                ),
            )
            // Compute exact LiveViewDmaEngine + MfAssistPeakingFilter CPU reference for comparison
            val refUyvy = referenceIspDemosaicAndPeaking(raw16, iw, ih, 1152, 1024, 960, customCcm, customLut, 48, 76, 0x55, 0xFF)
            var maxIspDiff = 0
            for (i in gpuUyvy.indices) {
                val d = abs((gpuUyvy[i].toInt() and 0xFF) - (refUyvy[i].toInt() and 0xFF))
                if (d > maxIspDiff) maxIspDiff = d
            }
            assertTrue(maxIspDiff <= 1, "GPU ISP Demosaic + CCM + Tone LUT + Focus Peaking exceeded 1 LSB (maxDiff=$maxIspDiff)")

            // Also verify NV16 semi-planar and YYUV422 Face/Eye-AF outputs match UYVY422 bit-for-bit
            val yPlane = ByteArray(iw * ih)
            val uvPlane = ByteArray(iw * ih)
            assertTrue(
                gpu.tryIspDemosaic(
                    rawBayer = raw16,
                    lutR = customLut,
                    lutG = customLut,
                    lutB = customLut,
                    outYOrPacked = yPlane,
                    outUvPlane = uvPlane,
                    params = ispParams.copy(outputFormat = VulkanNativeOpticsBridge.ISP_FMT_NV16),
                ),
            )
            val yyuv = ByteArray(iw * ih * 2)
            assertTrue(
                gpu.tryIspDemosaic(
                    rawBayer = raw16,
                    lutR = customLut,
                    lutG = customLut,
                    lutB = customLut,
                    outYOrPacked = yyuv,
                    params = ispParams.copy(outputFormat = VulkanNativeOpticsBridge.ISP_FMT_YYUV422),
                ),
            )
            for (y in 0 until ih) {
                var x = 0
                while (x < iw) {
                    val uOff = (y * iw + x) * 2
                    assertEquals(gpuUyvy[uOff + 1], yPlane[y * iw + x], "NV16 Y0 mismatch at ($x,$y)")
                    assertEquals(gpuUyvy[uOff + 3], yPlane[y * iw + x + 1], "NV16 Y1 mismatch at ($x,$y)")
                    assertEquals(gpuUyvy[uOff + 0], uvPlane[y * iw + x], "NV16 U mismatch at ($x,$y)")
                    assertEquals(gpuUyvy[uOff + 2], uvPlane[y * iw + x + 1], "NV16 V mismatch at ($x,$y)")
                    assertEquals(gpuUyvy[uOff + 1], yyuv[uOff + 0], "YYUV422 Y0 mismatch at ($x,$y)")
                    assertEquals(gpuUyvy[uOff + 3], yyuv[uOff + 1], "YYUV422 Y1 mismatch at ($x,$y)")
                    assertEquals(gpuUyvy[uOff + 0], yyuv[uOff + 2], "YYUV422 U mismatch at ($x,$y)")
                    assertEquals(gpuUyvy[uOff + 2], yyuv[uOff + 3], "YYUV422 V mismatch at ($x,$y)")
                    x += 2
                }
            }
            assertTrue(gpu.diagnostics().framesRendered >= 15L)
        }
    }

    @Test
    fun lensFactoryExposesOpticsBackendConfigAndDiagnostics(@TempDir tempDir: Path) {
        val ctx = object : ComponentContext {
            override val directory: Path = tempDir
            override val ports: Ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {}
                override fun <T : Any> find(name: String, type: Class<T>): T? = null
                override fun removeOwner(owner: String) {}
            }
            override val events: EventChannel = object : EventChannel {
                override fun publish(event: ComponentEvent) {}
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override val clock: EmulationClock = EmulationClock { 0L }
            override fun dataPath(relative: String): Path = tempDir.resolve(relative)
        }
        val lens = LensFactory().create(ctx, mapOf("template" to "gf45-100", "opticsBackend" to "cpu"))
        try {
            val st = lens.state()
            assertEquals("cpu", st["opticsBackend"])
            @Suppress("UNCHECKED_CAST")
            val diag = st["opticsBackendDiagnostics"] as? Map<String, Any?>
            assertNotNull(diag)
            assertEquals("CPU", diag["backend"])
            assertEquals("cpu", diag["preference"])

            lens.applySettings(mapOf("opticsBackend" to "auto"))
            val stAuto = lens.state()
            assertEquals("auto", stAuto["opticsBackend"])
            @Suppress("UNCHECKED_CAST")
            val diagAuto = stAuto["opticsBackendDiagnostics"] as? Map<String, Any?>
            assertNotNull(diagAuto)
            assertTrue(diagAuto["backend"] in setOf("VULKAN", "CPU"))
        } finally {
            lens.close()
        }
    }

    @Test
    fun lensOpticalSceneFilterWiresVulkanStage1Stage2AeCacheAndCpuFallbackWithParity() {
        val w = 48
        val h = 32
        val n = w * h
        val red = FloatArray(n) { i -> 0.15f + 0.55f * ((i % w).toFloat() / w) }
        val green = FloatArray(n) { i -> 0.20f + 0.45f * ((i / w).toFloat() / h) }
        val blue = FloatArray(n) { 0.30f }
        val depth = FloatArray(n) { i -> if ((i % w) in 16..32) 2.0f else 8.0f }
        val frame = com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame(
            width = w,
            height = h,
            red = red,
            green = green,
            blue = blue,
            depthMeters = depth,
            sequence = 1L,
        )
        val geom = SensorGeometry(w, h, 14, BayerPattern.RGGB, 3.76)
        val baseLens = LensParameters("GF50mmF3.5", 50.0, 2.8, 2.0, 1.0, 0.0)
        val expBase = Exposure(0L, 10_000_000L, 100, baseLens)
        val expAe = Exposure(0L, 20_000_000L, 200, baseLens)
        val cleanNoise = CmosNoiseConfig(seed = 42L, enableNoise = false, blackLevel = 256)

        // 1. Missing native library fallback via LensOpticalSceneFilter -> explicit WARN [FALLBACK][vulkan] + CPU parity
        val logger = Logger.getLogger(VulkanOpticalBackend::class.java.name)
        val handler = CapturingLogHandler()
        logger.addHandler(handler)
        try {
            VulkanOpticalBackend(
                preference = OpticsBackendPreference.AUTO,
                explicitLibraryPath = Paths.get("/nonexistent/path/libfuji_vulkan_optics_missing.so"),
            ).use { fallbackBackend ->
                val filter = com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter(
                    enabled = true,
                    profile = OpticalLensProfile.IDENTITY,
                    noiseConfig = cleanNoise,
                    maxBokehRadiusPx = 0,
                    borderMode = BorderMode.CLAMP,
                    computeBackendProvider = { fallbackBackend },
                )
                val out1 = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                val outAe = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                filter.filterRadianceFrame(frame, expBase, geom, out1, 0L)
                filter.filterRadianceFrame(frame, expAe, geom, outAe, 1L)
                assertEquals(ActiveOpticsBackend.CPU, fallbackBackend.activeBackend)
                assertEquals(0L, filter.vulkanStage1Frames)
                assertEquals(1L, filter.opticalCacheHits, "CPU fallback must still hit Stage-1 AE cache")
                assertFalse(out1.array().contentEquals(outAe.array()))
                assertTrue(
                    handler.records.any { it.level == Level.WARNING && it.message.contains("[FALLBACK][vulkan]") },
                    "Expected WARN [FALLBACK][vulkan] on fallback",
                )
            }
        } finally {
            logger.removeHandler(handler)
        }

        // 2. Real Lavapipe Vulkan GPU wiring inside LensOpticalSceneFilter (Stage-1 Warp+Bokeh + Stage-2 CMOS + AE Cache)
        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) return
        loaded.getOrThrow().close()

        VulkanOpticalBackend(
            preference = OpticsBackendPreference.VULKAN,
            allowSoftwareVulkan = true,
        ).use { gpuBackend ->
            if (gpuBackend.activeBackend != ActiveOpticsBackend.VULKAN) return

            // Identity warp + noiseless Bayer parity <= 1 ADU against CPU filter across initial frame AND AE update!
            val gpuFilter = com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter(
                enabled = true,
                profile = OpticalLensProfile.IDENTITY,
                noiseConfig = cleanNoise,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                computeBackendProvider = { gpuBackend },
            )
            val cpuFilter = com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter(
                enabled = true,
                profile = OpticalLensProfile.IDENTITY,
                noiseConfig = cleanNoise,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
            )

            val gpuOut1 = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            val cpuOut1 = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            gpuFilter.filterRadianceFrame(frame, expBase, geom, gpuOut1, 0L)
            cpuFilter.filterRadianceFrame(frame, expBase, geom, cpuOut1, 0L)
            assertEquals(1L, gpuFilter.vulkanStage1Frames, "Initial frame must execute Stage-1 on Vulkan GPU")
            assertEquals(1L, gpuFilter.vulkanStage2Frames, "Initial frame must execute Stage-2 CMOS on Vulkan GPU")

            var maxDiff1 = 0
            for (i in 0 until n) {
                val gS = gpuOut1.getShort(i * 2).toInt() and 0xFFFF
                val cS = cpuOut1.getShort(i * 2).toInt() and 0xFFFF
                maxDiff1 = maxOf(maxDiff1, abs(gS - cS))
            }
            assertTrue(maxDiff1 <= 1, "Initial GPU vs CPU LensOpticalSceneFilter parity exceeded 1 ADU (maxDiff=$maxDiff1)")

            // AE exposure change: Stage-1 MUST hit cache (vulkanStage1Frames stays 1), ONLY Stage-2 runs on Vulkan GPU!
            val gpuOutAe = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            val cpuOutAe = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            gpuFilter.filterRadianceFrame(frame, expAe, geom, gpuOutAe, 1L)
            cpuFilter.filterRadianceFrame(frame, expAe, geom, cpuOutAe, 1L)
            assertEquals(1L, gpuFilter.opticalCacheHits, "AE exposure change must hit Stage-1 optical cache on Vulkan path")
            assertEquals(1L, gpuFilter.vulkanStage1Frames, "Stage-1 Vulkan must NOT re-dispatch on AE-only update")
            assertEquals(2L, gpuFilter.vulkanStage2Frames, "Stage-2 Vulkan CMOS readout must execute on AE update")

            var maxDiffAe = 0
            for (i in 0 until n) {
                val gS = gpuOutAe.getShort(i * 2).toInt() and 0xFFFF
                val cS = cpuOutAe.getShort(i * 2).toInt() and 0xFFFF
                maxDiffAe = maxOf(maxDiffAe, abs(gS - cS))
            }
            assertTrue(maxDiffAe <= 1, "AE-cached GPU vs CPU parity exceeded 1 ADU (maxDiff=$maxDiffAe)")

            // Shaped 2D pupil bokeh (STAR_5 vs CIRCLE) via LensOpticalSceneFilter.update(pupilShape=...)
            val dotR = FloatArray(n); val dotG = FloatArray(n); val dotB = FloatArray(n)
            val center = (h / 2) * w + (w / 2)
            dotR[center] = 60f; dotG[center] = 60f; dotB[center] = 60f
            val dotFrame = com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame(
                width = w, height = h, red = dotR, green = dotG, blue = dotB, depthMeters = FloatArray(n) { 20f }, sequence = 2L,
            )
            val bokehProf = OpticalLensProfile(focalLengthMm = 85.0, referenceFocalLengthMm = 85.0, fNumber = 1.4, focusDistanceM = 1.0)
            val bokehFilter = com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter(
                enabled = true,
                profile = bokehProf,
                noiseConfig = cleanNoise,
                maxBokehRadiusPx = 10,
                computeBackendProvider = { gpuBackend },
                pupilShape = BokehPupilShape.CIRCLE,
            )
            val outCircle = ByteBuffer.allocate(n * 2)
            bokehFilter.filterRadianceFrame(dotFrame, expBase, geom, outCircle, 0L)
            bokehFilter.update(pupilShape = BokehPupilShape.STAR_5)
            val outStar = ByteBuffer.allocate(n * 2)
            bokehFilter.filterRadianceFrame(dotFrame, expBase, geom, outStar, 1L)
            assertEquals(2L, bokehFilter.vulkanStage1Frames, "Changing pupilShape must invalidate Stage-1 and dispatch STAR_5 on GPU")
            assertFalse(outCircle.array().contentEquals(outStar.array()), "STAR_5 GPU bokeh must differ from CIRCLE")
        }
    }

    private fun referenceIspDemosaicAndPeaking(
        raw: ByteArray,
        w: Int,
        h: Int,
        gainR: Int,
        gainG: Int,
        gainB: Int,
        ccm: IntArray,
        lut: IntArray,
        peakingThr: Int,
        hiY: Int,
        hiCb: Int,
        hiCr: Int,
    ): ByteArray {
        fun s12(x: Int, y: Int): Int {
            val o = (y * w + x) shl 1
            return ((raw[o].toInt() and 0xFF) or ((raw[o + 1].toInt() and 0xFF) shl 8)) ushr 4
        }
        val out = ByteArray(w * h * 2)
        val yPlane = ByteArray(w * h)
        for (y in 0 until h) {
            val yp = if (y > 0) y - 1 else 0
            val yn = if (y < h - 1) y + 1 else h - 1
            val yOdd = (y and 1) != 0
            var x = 0
            while (x < w) {
                fun px(cx: Int): Triple<Int, Int, Int> {
                    val xp = if (cx > 0) cx - 1 else 0
                    val xn = if (cx < w - 1) cx + 1 else w - 1
                    val xOdd = (cx and 1) != 0
                    val c = s12(cx, y)
                    val rL: Int; val gL: Int; val bL: Int
                    if (!yOdd && !xOdd) {
                        rL = c
                        gL = (s12(xp, y) + s12(xn, y) + s12(cx, yp) + s12(cx, yn) + 2) ushr 2
                        bL = (s12(xp, yp) + s12(xn, yp) + s12(xp, yn) + s12(xn, yn) + 2) ushr 2
                    } else if (!yOdd && xOdd) {
                        rL = (s12(xp, y) + s12(xn, y) + 1) ushr 1
                        gL = c
                        bL = (s12(cx, yp) + s12(cx, yn) + 1) ushr 1
                    } else if (yOdd && !xOdd) {
                        rL = (s12(cx, yp) + s12(cx, yn) + 1) ushr 1
                        gL = c
                        bL = (s12(xp, y) + s12(xn, y) + 1) ushr 1
                    } else {
                        rL = (s12(xp, yp) + s12(xn, yp) + s12(xp, yn) + s12(xn, yn) + 2) ushr 2
                        gL = (s12(xp, y) + s12(xn, y) + s12(cx, yp) + s12(cx, yn) + 2) ushr 2
                        bL = c
                    }
                    val rg = ((rL * gainR) ushr 10).coerceIn(0, 4095)
                    val gg = ((gL * gainG) ushr 10).coerceIn(0, 4095)
                    val bg = ((bL * gainB) ushr 10).coerceIn(0, 4095)
                    val ri = ((ccm[0] * rg + ccm[1] * gg + ccm[2] * bg + 512) shr 10).coerceIn(0, 4095)
                    val gi = ((ccm[3] * rg + ccm[4] * gg + ccm[5] * bg + 512) shr 10).coerceIn(0, 4095)
                    val bi = ((ccm[6] * rg + ccm[7] * gg + ccm[8] * bg + 512) shr 10).coerceIn(0, 4095)
                    return Triple(lut[ri], lut[gi], lut[bi])
                }
                val (r0, g0, b0) = px(x)
                val (r1, g1, b1) = px(x + 1)
                val y0 = ((77 * r0 + 150 * g0 + 29 * b0 + 128) shr 8).coerceIn(0, 255)
                val y1 = ((77 * r1 + 150 * g1 + 29 * b1 + 128) shr 8).coerceIn(0, 255)
                val ar = (r0 + r1) ushr 1
                val ag = (g0 + g1) ushr 1
                val ab = (b0 + b1) ushr 1
                val u = (((-43 * ar - 85 * ag + 128 * ab + 128) shr 8) + 128).coerceIn(0, 255)
                val v = (((128 * ar - 107 * ag - 21 * ab + 128) shr 8) + 128).coerceIn(0, 255)
                val o = (y * w + x) shl 1
                out[o] = u.toByte()
                out[o + 1] = y0.toByte()
                out[o + 2] = v.toByte()
                out[o + 3] = y1.toByte()
                yPlane[y * w + x] = y0.toByte()
                yPlane[y * w + x + 1] = y1.toByte()
                x += 2
            }
        }
        fun edgeMag(cx: Int, cy: Int): Int {
            fun ly(px: Int, py: Int) = yPlane[py * w + px].toInt() and 0xFF
            val gx1 = abs(ly(cx + 1, cy) - ly(cx - 1, cy))
            val gx2 = abs(ly(cx + 2, cy) - ly(cx - 2, cy))
            val gy1 = abs(ly(cx, cy + 1) - ly(cx, cy - 1))
            val gy2 = abs(ly(cx, cy + 2) - ly(cx, cy - 2))
            return maxOf(gx1, gx2) + maxOf(gy1, gy2)
        }
        for (y in 2 until h - 2) {
            var x = 0
            while (x < w) {
                val p0 = x in 2 until (w - 2) && edgeMag(x, y) > peakingThr
                val p1 = (x + 1) in 2 until (w - 2) && edgeMag(x + 1, y) > peakingThr
                if (p0 || p1) {
                    val o = (y * w + x) shl 1
                    out[o] = hiCb.toByte()
                    out[o + 2] = hiCr.toByte()
                    if (p0) out[o + 1] = hiY.toByte()
                    if (p1) out[o + 3] = hiY.toByte()
                }
                x += 2
            }
        }
        return out
    }

    @Test
    fun vulkanW2cEndToEndFilterStackStarBokehSingleSubmitAndAeCacheParity() {
        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) return
        loaded.getOrThrow().close()

        VulkanOpticalBackend(
            preference = OpticsBackendPreference.VULKAN,
            allowSoftwareVulkan = true,
        ).use { gpuBackend ->
            if (gpuBackend.activeBackend != ActiveOpticsBackend.VULKAN) return

            // Multi-depth star-bokeh scene: uniform sky+ground (for GND), sharp foreground at 1.35m,
            // and bright out-of-focus specular point light at 7.0m (for STAR_5 bokeh + Mist + Star 6pt).
            val w = 96
            val h = 64
            val n = w * h
            val red = FloatArray(n) { 0.25f }
            val green = FloatArray(n) { 0.25f }
            val blue = FloatArray(n) { 0.25f }
            val depth = FloatArray(n) { 7.0f }
            for (y in 24..48) {
                for (x in 8..24) {
                    depth[y * w + x] = 1.35f
                }
            }
            val lx = 64; val ly = 32
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val idx = (ly + dy) * w + (lx + dx)
                    red[idx] = 12.0f; green[idx] = 10.0f; blue[idx] = 5.0f
                }
            }
            val frame = com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame(
                width = w, height = h, red = red, green = green, blue = blue, depthMeters = depth, sequence = 1L,
            )
            val geom = SensorGeometry(w, h, 16, BayerPattern.RGGB, 3.76)
            val lensP = LensParameters("GF110mmF2", 110.0, 2.0, 1.35, 0.9, 0.0)
            val expBase = Exposure(0L, 10_000_000L, 100, lensP)
            val expAe1 = Exposure(0L, 15_000_000L, 160, lensP)
            val expAe2 = Exposure(0L, 20_000_000L, 200, lensP)
            val profile = OpticalLensProfile(
                focalLengthMm = 110.0,
                referenceFocalLengthMm = 110.0,
                fNumber = 2.0,
                focusDistanceM = 1.35,
                transmission = 0.9,
            )

            class TestStack : com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort {
                override var revision: Long = 1L
                override var enabled: Boolean = true
                var ndStops: Double = 0.0
                var gndStops: Double = 0.0
                var mistStrength: Double = 0.0
                var starStrength: Double = 0.0
                var starPoints: Int = 6
                var bokehShape: com.github.nyabanban.fuji.emulator.api.BokehMaskShape =
                    com.github.nyabanban.fuji.emulator.api.BokehMaskShape.OFF
                var customSpatial: Boolean = false

                override fun activeElements(): List<com.github.nyabanban.fuji.emulator.api.OpticalFilterElement> {
                    if (!enabled) return emptyList()
                    val out = mutableListOf<com.github.nyabanban.fuji.emulator.api.OpticalFilterElement>()
                    if (ndStops > 1e-4) {
                        out += object : com.github.nyabanban.fuji.emulator.api.OpticalFilterElement {
                            override val id = "nd"
                            override val displayName = "ND"
                            override val enabled = true
                            override val hasSpatialEffect = false
                            override val supportsVulkanBuiltin = true
                            override fun transmittanceMultiplier() = Math.pow(2.0, -ndStops)
                            override fun applySpatialLinearRgb(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch) {}
                            override fun summaryBadge() = "ND"
                            override fun stateMap() = mapOf("id" to "nd", "stops" to ndStops)
                        }
                    }
                    if (gndStops > 1e-4) {
                        out += object : com.github.nyabanban.fuji.emulator.api.OpticalFilterElement {
                            override val id = "gnd"
                            override val displayName = "GND"
                            override val enabled = true
                            override val hasSpatialEffect = true
                            override val supportsVulkanBuiltin = true
                            override fun transmittanceMultiplier() = 1.0
                            override fun applySpatialLinearRgb(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch) {}
                            override fun summaryBadge() = "GND"
                            override fun stateMap() = mapOf("id" to "gnd", "stops" to gndStops, "horizon" to 0.5, "feather" to 0.15, "angleDeg" to 0.0)
                        }
                    }
                    if (mistStrength > 1e-4) {
                        out += object : com.github.nyabanban.fuji.emulator.api.OpticalFilterElement {
                            override val id = "black-mist"
                            override val displayName = "Black Mist"
                            override val enabled = true
                            override val hasSpatialEffect = true
                            override val supportsVulkanBuiltin = true
                            override fun transmittanceMultiplier() = 1.0
                            override fun applySpatialLinearRgb(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch) {}
                            override fun summaryBadge() = "Mist"
                            override fun stateMap() = mapOf("id" to "black-mist", "strength" to mistStrength, "threshold" to 0.35)
                        }
                    }
                    if (starStrength > 1e-4) {
                        out += object : com.github.nyabanban.fuji.emulator.api.OpticalFilterElement {
                            override val id = "star"
                            override val displayName = "Star"
                            override val enabled = true
                            override val hasSpatialEffect = true
                            override val supportsVulkanBuiltin = true
                            override fun transmittanceMultiplier() = 1.0
                            override fun applySpatialLinearRgb(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch) {}
                            override fun summaryBadge() = "Star"
                            override fun stateMap() = mapOf("id" to "star", "points" to starPoints, "strength" to starStrength, "threshold" to 0.55, "angleDeg" to 15.0, "length" to 0.16)
                        }
                    }
                    if (customSpatial) {
                        out += object : com.github.nyabanban.fuji.emulator.api.OpticalFilterElement {
                            override val id = "custom-uv"
                            override val displayName = "Custom UV"
                            override val enabled = true
                            override val hasSpatialEffect = true
                            override fun transmittanceMultiplier() = 1.0
                            override fun applySpatialLinearRgb(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch) {}
                            override fun summaryBadge() = "UV"
                            override fun stateMap() = mapOf("id" to "custom-uv")
                        }
                    }
                    return out
                }

                override fun totalTransmittance(): Double = if (!enabled || ndStops <= 1e-4) 1.0 else Math.pow(2.0, -ndStops)
                override fun hasActiveSpatialEffects(): Boolean = enabled && (gndStops > 1e-4 || mistStrength > 1e-4 || starStrength > 1e-4 || customSpatial)
                override fun activePupilMask(): com.github.nyabanban.fuji.emulator.api.OpticalPupilMask? {
                    if (!enabled || bokehShape == com.github.nyabanban.fuji.emulator.api.BokehMaskShape.OFF) return null
                    val s = bokehShape
                    return object : com.github.nyabanban.fuji.emulator.api.OpticalPupilMask {
                        override val shape = s
                        override val angleDeg = 0.0
                        // This test covers the spatial stack; the transformed-pupil test covers legacy fallback.
                        override val scale = 1.0
                        override val active = true
                        override val cacheKey = 0x1000L + s.ordinal
                        override fun pupilWeight(ux: Double, uy: Double) = 1.0
                    }
                }
                override fun applySpatialStack(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch) {}
                override fun stackState(): Map<String, Any?> = emptyMap()
            }

            fun meanAduInRect(buf: ByteBuffer, x0: Int, y0: Int, x1: Int, y1: Int): Double {
                val bb = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                var cnt = 0
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        sum += (bb.getShort((y * w + x) * 2).toInt() and 0xFFFF)
                        cnt++
                    }
                }
                return sum / maxOf(1, cnt)
            }

            val stack = TestStack()
            val gpuFilter = com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter(
                enabled = true,
                profile = profile,
                noiseConfig = CmosNoiseConfig.NOISELESS,
                maxBokehRadiusPx = 10,
                borderMode = BorderMode.CLAMP,
                computeBackendProvider = { gpuBackend },
                opticalFilterStackProvider = { stack },
            )

            // 1. Baseline (no filters)
            val outBase = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            gpuFilter.filterRadianceFrame(frame, expBase, geom, outBase, 0L)
            val baseFgMean = meanAduInRect(outBase, 10, 26, 22, 46)
            assertTrue(baseFgMean > 1000.0, "Expected bright baseline foreground mean, got $baseFgMean")

            // 2. ND8 (-3.0 EV) + STAR_5 Bokeh: ONE single GPU submit + exact 8.0x attenuation (ZERO double-ND!)
            stack.ndStops = 3.0
            stack.bokehShape = com.github.nyabanban.fuji.emulator.api.BokehMaskShape.STAR_5
            stack.revision++
            val outNdStar = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            gpuFilter.filterRadianceFrame(frame, expBase, geom, outNdStar, 1L)
            assertEquals(1L, gpuFilter.vulkanSingleSubmitFrames, "Active ND8+STAR_5 must execute in ONE single GPU submit")
            val ndFgMean = meanAduInRect(outNdStar, 10, 26, 22, 46)
            val ndRatio = baseFgMean / ndFgMean
            assertTrue(ndRatio in 7.7..8.3, "Expected exact 8.0x ND8 attenuation (no double-ND), got ratio=$ndRatio (base=$baseFgMean nd8=$ndFgMean)")

            // Repeat identical call -> Stage-2 cache HIT
            val outNdStarRepeat = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            gpuFilter.filterRadianceFrame(frame, expBase, geom, outNdStarRepeat, 2L)
            assertEquals(1L, gpuFilter.cacheHits)
            assertTrue(outNdStar.array().contentEquals(outNdStarRepeat.array()))

            // AE updates on same static frame -> Call 3 promotes Stage-1 cache, Call 4 HITS Stage-1 cache (0 extra Stage-1 GPU dispatches!)
            val outAe1 = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            val outAe2 = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            val s1BeforeAe = gpuFilter.vulkanStage1Frames
            val s2BeforeAe = gpuFilter.vulkanStage2Frames
            gpuFilter.filterRadianceFrame(frame, expAe1, geom, outAe1, 3L)
            assertEquals(s1BeforeAe + 1L, gpuFilter.vulkanStage1Frames, "First AE update on static frame promotes Stage-1 cache")
            gpuFilter.filterRadianceFrame(frame, expAe2, geom, outAe2, 4L)
            assertEquals(1L, gpuFilter.opticalCacheHits, "Subsequent AE update must HIT Stage-1 optical cache")
            assertEquals(s1BeforeAe + 1L, gpuFilter.vulkanStage1Frames, "Stage-1 must NOT re-dispatch on AE cache hit")
            assertEquals(s2BeforeAe + 2L, gpuFilter.vulkanStage2Frames, "Stage-2 CMOS must run on GPU for each AE update")

            // 3. Full Pass 0 Spatial Stack (GND 3 EV + Black Mist 1/4 + Star 6pt) + STAR_5 / RING / HEART / HEXAGON
            stack.ndStops = 0.0
            stack.gndStops = 3.0
            stack.mistStrength = 0.25
            stack.starStrength = 0.85
            stack.starPoints = 6
            stack.bokehShape = com.github.nyabanban.fuji.emulator.api.BokehMaskShape.STAR_5
            stack.revision++
            assertTrue(gpuFilter.handlesSpatialFilterStack(stack), "GPU filter must claim native spatial stack (GND+Mist+Star)")

            val outFullStack = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            gpuFilter.filterRadianceFrame(frame, expBase, geom, outFullStack, 5L)
            assertEquals(2L, gpuFilter.vulkanSingleSubmitFrames, "Full GND+Mist+Star+STAR_5 stack must execute in single GPU submit")
            val skyMean = meanAduInRect(outFullStack, 4, 2, 40, 14)
            val groundMean = meanAduInRect(outFullStack, 4, 50, 40, 62)
            assertTrue(skyMean < groundMean * 0.35, "GND 3 EV on GPU Pass 0 must strongly darken upper sky vs ground (sky=$skyMean ground=$groundMean)")

            // Switch bokeh mask to RING, HEART, HEXAGON -> each produces distinct GPU Pass 2 pupil output
            stack.bokehShape = com.github.nyabanban.fuji.emulator.api.BokehMaskShape.RING
            stack.revision++
            val outRing = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            gpuFilter.filterRadianceFrame(frame, expBase, geom, outRing, 6L)
            assertFalse(outFullStack.array().contentEquals(outRing.array()), "Switching bokehMask STAR_5 -> RING must update GPU output")

            stack.bokehShape = com.github.nyabanban.fuji.emulator.api.BokehMaskShape.HEART
            stack.revision++
            val outHeart = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            gpuFilter.filterRadianceFrame(frame, expBase, geom, outHeart, 7L)
            assertFalse(outRing.array().contentEquals(outHeart.array()), "Switching bokehMask RING -> HEART must update GPU output")

            stack.bokehShape = com.github.nyabanban.fuji.emulator.api.BokehMaskShape.HEXAGON
            stack.revision++
            val outHex = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            gpuFilter.filterRadianceFrame(frame, expBase, geom, outHex, 8L)
            assertFalse(outHeart.array().contentEquals(outHex.array()), "Switching bokehMask HEART -> HEXAGON must update GPU output")

            // 4. Custom unsupported spatial filter (e.g. future UV plugin) -> handlesSpatialFilterStack returns false (zero double-apply)
            stack.customSpatial = true
            stack.revision++
            assertFalse(
                gpuFilter.handlesSpatialFilterStack(stack),
                "Unknown spatial filter element must yield handlesSpatialFilterStack=false so CPU applies it safely",
            )
        }
    }

    @Test
    fun directByteBufferZeroCopyAndGpuFusedOpticsIspMatchCpuReference() {
        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) return
        loaded.getOrThrow().close()

        VulkanOpticalBackend(
            preference = OpticsBackendPreference.VULKAN,
            allowSoftwareVulkan = true,
        ).use { gpu ->
            if (gpu.activeBackend != ActiveOpticsBackend.VULKAN) return

            val w = 64
            val h = 64
            val n = w * h
            val red = FloatArray(n) { i -> if ((i % w) >= w / 2) 0.78f else 0.10f }
            val green = FloatArray(n) { i -> if ((i % w) >= w / 2) 0.52f else 0.12f }
            val blue = FloatArray(n) { i -> if ((i % w) >= w / 2) 0.28f else 0.16f }
            val depth = FloatArray(n) { 2.0f }
            val src = LinearRgbImage(w, h, red, green, blue)
            val geom = SensorGeometry(w, h, 16, BayerPattern.RGGB, 3.76)
            val lensP = LensParameters("GF50mmF3.5", 50.0, 2.8, 2.0, 1.0, 0.0)
            val exp = Exposure(0L, 10_000_000L, 100, lensP)
            val profile = OpticalLensProfile(
                focalLengthMm = 50.0,
                referenceFocalLengthMm = 50.0,
                cos4Power = 2.0,
                mechanicalVignetting = 0.15,
                fNumber = 2.8,
                focusDistanceM = 2.0,
                transmission = 1.0,
            )
            val cleanNoise = CmosNoiseConfig(seed = 42L, enableNoise = false, blackLevel = 256)
            val filterCfg = OpticalFilterStackConfig(gndStops = 2.0, gndHorizonY = 0.5, gndFeather = 0.12)

            // 1. Direct ByteBuffer zero-copy matches heap ByteBuffer bit-for-bit on renderToBayer & renderCmosBayer
            val heapBayer = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            val directBayer = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            val nHeap = gpu.renderToBayer(src, geom, exp, profile, noiseConfig = cleanNoise, destination = heapBayer, depthMeters = depth, maxBokehRadiusPx = 0, borderMode = BorderMode.CLAMP, filterStack = filterCfg)
            val nDirect = gpu.renderToBayer(src, geom, exp, profile, noiseConfig = cleanNoise, destination = directBayer, depthMeters = depth, maxBokehRadiusPx = 0, borderMode = BorderMode.CLAMP, filterStack = filterCfg)
            assertEquals(nHeap, nDirect)
            assertEquals(nDirect, directBayer.position(), "Direct ByteBuffer position must advance by written bytes")
            val directBytes = ByteArray(n * 2)
            directBayer.flip()
            directBayer.get(directBytes)
            assertTrue(heapBayer.array().contentEquals(directBytes), "Direct zero-copy renderToBayer must match heap ByteBuffer bit-for-bit")

            val heapCmos = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            val directCmos = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(n * 2, gpu.renderCmosBayer(red, green, blue, geom, exp, profile, cleanNoise, heapCmos))
            assertEquals(n * 2, gpu.renderCmosBayer(red, green, blue, geom, exp, profile, cleanNoise, directCmos))
            assertEquals(n * 2, directCmos.position())
            val directCmosBytes = ByteArray(n * 2)
            directCmos.flip()
            directCmos.get(directCmosBytes)
            assertTrue(heapCmos.array().contentEquals(directCmosBytes), "Direct zero-copy renderCmosBayer must match heap ByteBuffer bit-for-bit")

            // 2. GPU-Resident Fused Optics -> CMOS Bayer -> LiveView ISP Demosaic (Q10 Gain + 3x3 CCM + Tone LUT)
            //    vs CPU Bayer -> ISP reference (<= 1 LSB across entire frame!)
            val customCcm = intArrayOf(1126, -51, -51, -30, 1084, -30, -20, -40, 1084)
            val customLut = IntArray(4096) { idx -> ((idx * 255 + 2047) / 4095).coerceIn(0, 255) }
            val ispParams = VulkanNativeOpticsBridge.IspDemosaicParamsStruct(
                srcWidth = w,
                srcHeight = h,
                dstWidth = w,
                dstHeight = h,
                is16Bit = true,
                outputFormat = VulkanNativeOpticsBridge.ISP_FMT_UYVY422,
                flags = VulkanNativeOpticsBridge.ISP_FLAG_HAS_CCM,
                gainR1024 = 1120,
                gainG1024 = 1024,
                gainB1024 = 960,
                ccm = customCcm,
            )
            val fusedUyvy = ByteArray(n * 2)
            assertTrue(
                gpu.tryRenderOpticsToIspYuv(
                    source = src,
                    geometry = geom,
                    exposure = exp,
                    profile = profile,
                    noiseConfig = cleanNoise,
                    depthMeters = depth,
                    maxBokehRadiusPx = 0,
                    borderMode = BorderMode.CLAMP,
                    filterStack = filterCfg,
                    lutR = customLut,
                    lutG = customLut,
                    lutB = customLut,
                    outYOrPacked = fusedUyvy,
                    ispParams = ispParams,
                ),
                "Fused GPU-resident Optics->ISP YUV dispatch must succeed on Vulkan backend",
            )
            val refUyvy = referenceIspDemosaicAndPeaking(
                heapBayer.array(), w, h, 1120, 1024, 960, customCcm, customLut, 9999, 76, 0x55, 0xFF,
            )
            var maxFusedDiff = 0
            for (i in fusedUyvy.indices) {
                val d = abs((fusedUyvy[i].toInt() and 0xFF) - (refUyvy[i].toInt() and 0xFF))
                if (d > maxFusedDiff) maxFusedDiff = d
            }
            assertTrue(maxFusedDiff <= 1, "Fused GPU Optics->ISP UYVY vs CPU Bayer->ISP reference exceeded 1 LSB (maxDiff=$maxFusedDiff)")

            // 2B. Fused Focus Peaking (Pass 5) stamps the sharp x=w/2 vertical edge RED while leaving flat regions untouched
            val fusedPeaked = ByteArray(n * 2)
            assertTrue(
                gpu.tryRenderOpticsToIspYuv(
                    source = src,
                    geometry = geom,
                    exposure = exp,
                    profile = profile,
                    noiseConfig = cleanNoise,
                    depthMeters = depth,
                    maxBokehRadiusPx = 0,
                    borderMode = BorderMode.CLAMP,
                    filterStack = filterCfg,
                    lutR = customLut,
                    lutG = customLut,
                    lutB = customLut,
                    outYOrPacked = fusedPeaked,
                    ispParams = ispParams.copy(
                        flags = VulkanNativeOpticsBridge.ISP_FLAG_HAS_CCM or VulkanNativeOpticsBridge.ISP_FLAG_PEAKING_ENABLED,
                        peakingThreshold = 45,
                        peakingHiY = 76,
                        peakingHiCb = 0x55,
                        peakingHiCr = 0xFF,
                    ),
                ),
            )
            val edgeOff = ((h * 3 / 4) * w + (w / 2)) * 2
            assertEquals(0x55.toByte(), fusedPeaked[edgeOff + 0], "Fused Focus Peaking must stamp edge Cb=0x55")
            assertEquals(76.toByte(), fusedPeaked[edgeOff + 1], "Fused Focus Peaking must stamp edge Y=76")
            assertEquals(0xFF.toByte(), fusedPeaked[edgeOff + 2], "Fused Focus Peaking must stamp edge Cr=0xFF")
            val flatOff = ((h * 3 / 4) * w + 8) * 2
            assertEquals(fusedUyvy[flatOff + 1], fusedPeaked[flatOff + 1], "Fused Focus Peaking must leave flat regions untouched")

            // 3. Also verify through LensOpticalSceneFilter.tryFilterRadianceToIspYuv!
            val sceneFilter = com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter(
                enabled = true,
                profile = profile,
                noiseConfig = cleanNoise,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                computeBackendProvider = { gpu },
            )
            val radFrame = com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame(
                width = w, height = h, red = red, green = green, blue = blue, depthMeters = depth, sequence = 1L,
            )
            val filterFusedUyvy = ByteArray(n * 2)
            val fusionReq = com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest(
                exposure = exp,
                dstWidth = w,
                dstHeight = h,
                dstStrideBytes = w * 2,
                outputFormat = com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest.FMT_UYVY422,
                flags = com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest.FLAG_HAS_CCM or
                    com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest.FLAG_PEAKING_ENABLED,
                offX = 0,
                offY = 0,
                fitW = w,
                fitH = h,
                gainR1024 = 1120,
                gainG1024 = 1024,
                gainB1024 = 960,
                yuvShift = 10,
                ccm = customCcm,
                yuvMat = null,
                lutR = customLut,
                lutG = customLut,
                lutB = customLut,
                peakingThreshold = 45,
                peakingHiY = 76,
                peakingHiCb = 0x55,
                peakingHiCr = 0xFF,
            )
            assertTrue(sceneFilter.tryFilterRadianceToIspYuv(radFrame, geom, fusionReq, filterFusedUyvy, null, 0L))
            assertEquals(1L, sceneFilter.vulkanFusedIspFrames)
        }
    }

    @Test
    fun lensOpticalSceneFilterSkipsFingerprintScanWhenSequenceIsPositive() {
        val w = 32
        val h = 32
        val n = w * h
        val r = FloatArray(n) { 0.4f }
        val g = FloatArray(n) { 0.5f }
        val b = FloatArray(n) { 0.6f }
        val geom = SensorGeometry(w, h, 16, BayerPattern.RGGB, 3.76)
        val exp = Exposure(0L, 10_000_000L, 100, LensParameters("GF50", 50.0, 2.8, 2.0, 0.9, 0.0))
        val filter = com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter(
            enabled = true,
            profile = OpticalLensProfile.IDENTITY,
            noiseConfig = CmosNoiseConfig.NOISELESS,
            maxBokehRadiusPx = 0,
        )
        val frameSeq1 = com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame(w, h, r, g, b, sequence = 1L)
        val frameSeq2 = com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame(w, h, r, g, b, sequence = 2L)
        val out = ByteBuffer.allocate(n * 2)

        filter.filterRadianceFrame(frameSeq1, exp, geom, out, 0L)
        out.clear()
        filter.filterRadianceFrame(frameSeq1, exp, geom, out, 1L)
        assertEquals(1L, filter.cacheHits)
        assertEquals(0L, filter.sourceFingerprintComputations, "Positive sequence token must skip computeSourceFingerprint()")

        // Bumping sequence invalidates cache cleanly without pixel-sampling fingerprint
        out.clear()
        filter.filterRadianceFrame(frameSeq2, exp, geom, out, 2L)
        assertEquals(2L, filter.cacheMisses)
        assertEquals(0L, filter.sourceFingerprintComputations)

        // Legacy sequence==0L path still computes source fingerprint
        out.clear()
        filter.filterLinearRgbToBayer(w, h, r, g, b, null, exp, geom, out, 3L)
        assertEquals(1L, filter.sourceFingerprintComputations)
    }

    @Test
    fun opticsZeroCopyAllocationAndLatencyMicroBenchmark() {
        val w = 256
        val h = 144
        val n = w * h
        val src = LinearRgbImage(
            width = w,
            height = h,
            red = FloatArray(n) { i -> 0.15f + 0.55f * ((i % w).toFloat() / w) },
            green = FloatArray(n) { i -> 0.20f + 0.45f * ((i / w).toFloat() / h) },
            blue = FloatArray(n) { 0.30f },
        )
        val geom = SensorGeometry(w, h, 16, BayerPattern.RGGB, 3.76)
        val exp = Exposure(0L, 10_000_000L, 100, LensParameters("GF50", 50.0, 2.8, 2.0, 0.9, 0.0))
        val prof = OpticalLensProfile(focalLengthMm = 50.0, referenceFocalLengthMm = 50.0, cos4Power = 2.0, mechanicalVignetting = 0.15, fNumber = 2.8, focusDistanceM = 2.0)
        val clean = CmosNoiseConfig.NOISELESS
        val threadBean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val tid = Thread.currentThread().threadId()

        fun measure(iters: Int, block: () -> Unit): Pair<Double, Long> {
            repeat(3) { block() }
            val alloc0 = threadBean?.getThreadAllocatedBytes(tid) ?: -1L
            val t0 = System.nanoTime()
            repeat(iters) { block() }
            val dtMs = (System.nanoTime() - t0) / 1_000_000.0 / iters
            val alloc1 = threadBean?.getThreadAllocatedBytes(tid) ?: -1L
            val bytesPerFrame = if (alloc0 >= 0 && alloc1 >= alloc0) (alloc1 - alloc0) / iters else -1L
            return dtMs to bytesPerFrame
        }

        // 1. CPU Fallback steady-state reuse (reused CpuOpticalPipeline + OpticalFrameWorkspace)
        VulkanOpticalBackend(preference = OpticsBackendPreference.CPU).use { cpuBackend ->
            val dst = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            val (cpuMs, cpuBytes) = measure(10) {
                dst.clear()
                cpuBackend.renderToBayer(src, geom, exp, prof, noiseConfig = clean, destination = dst, maxBokehRadiusPx = 0)
            }
            println("[W10-BENCH] VulkanOpticalBackend CPU fallback (256x144): ${"%.3f".format(cpuMs)} ms/f, alloc=$cpuBytes B/frame")
            if (cpuBytes >= 0) {
                assertTrue(cpuBytes < 8_192L, "Reused CPU fallback must avoid per-frame float/byte array allocations (got $cpuBytes B/frame)")
            }
        }

        // 2. Vulkan GPU Direct ByteBuffer Zero-Copy + Fused GPU-ISP
        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) return
        loaded.getOrThrow().close()
        VulkanOpticalBackend(preference = OpticsBackendPreference.VULKAN, allowSoftwareVulkan = true).use { gpu ->
            if (gpu.activeBackend != ActiveOpticsBackend.VULKAN) return
            val directDst = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            val (vkMs, vkBytes) = measure(10) {
                directDst.clear()
                gpu.renderToBayer(src, geom, exp, prof, noiseConfig = clean, destination = directDst, maxBokehRadiusPx = 0)
            }
            val fusedUyvy = ByteArray(n * 2)
            val ispP = VulkanNativeOpticsBridge.IspDemosaicParamsStruct(srcWidth = w, srcHeight = h, dstWidth = w, dstHeight = h)
            val (fusedMs, fusedBytes) = measure(10) {
                gpu.tryRenderOpticsToIspYuv(
                    source = src, geometry = geom, exposure = exp, profile = prof, noiseConfig = clean,
                    maxBokehRadiusPx = 0, outYOrPacked = fusedUyvy, ispParams = ispP,
                )
            }
            println(
                "[W10-BENCH] Vulkan direct zero-copy renderToBayer (256x144): ${"%.3f".format(vkMs)} ms/f, alloc=$vkBytes B/frame | " +
                    "Fused Optics->ISP YUV: ${"%.3f".format(fusedMs)} ms/f, alloc=$fusedBytes B/frame",
            )
            if (vkBytes >= 0) {
                assertTrue(vkBytes < 8_192L, "Steady-state direct Vulkan renderToBayer must avoid per-frame array allocations (got $vkBytes B/frame)")
            }
            if (fusedBytes >= 0) {
                assertTrue(fusedBytes < 8_192L, "Steady-state fused Vulkan Optics->ISP must avoid per-frame array allocations (got $fusedBytes B/frame)")
            }
        }
    }

    @Test
    fun vulkanDeviceResolutionAndExplicitSelectionWithFallback() {
        val sampleDevices = listOf(
            VulkanNativeOpticsBridge.NativeDeviceInfo(
                abiVersion = 1,
                apiVersion = (1 shl 22) or (3 shl 12),
                vendorId = 0x10DE,
                deviceId = 0x2684,
                deviceType = 2,
                computeQueueFamily = 0,
                externalMemoryCaps = 0x7,
                deviceName = "NVIDIA GeForce RTX 4090",
                loaderName = "vulkan-1.dll",
                deviceIndex = 0,
            ),
            VulkanNativeOpticsBridge.NativeDeviceInfo(
                abiVersion = 1,
                apiVersion = (1 shl 22) or (3 shl 12),
                vendorId = 0x8086,
                deviceId = 0xA780,
                deviceType = 1,
                computeQueueFamily = 0,
                externalMemoryCaps = 0x5,
                deviceName = "Intel(R) UHD Graphics 770",
                loaderName = "vulkan-1.dll",
                deviceIndex = 1,
            ),
        )

        assertEquals(null, VulkanOpticalBackend.resolveDeviceIndex("auto", sampleDevices))
        assertEquals(0, VulkanOpticalBackend.resolveDeviceIndex("0", sampleDevices))
        assertEquals(1, VulkanOpticalBackend.resolveDeviceIndex("1", sampleDevices))
        // Name-first resilience: index 0 in spec, but device name now lives at index 1
        assertEquals(1, VulkanOpticalBackend.resolveDeviceIndex("0:Intel(R) UHD Graphics 770", sampleDevices))
        assertEquals(0, VulkanOpticalBackend.resolveDeviceIndex("rtx 4090", sampleDevices))
        assertEquals(null, VulkanOpticalBackend.resolveDeviceIndex("Nonexistent GPU 9000", sampleDevices))

        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) return
        loaded.getOrThrow().close()

        val devices = VulkanOpticalBackend.enumerateDevices()
        if (devices.isEmpty()) return
        val first = devices.first()
        assertTrue(first.deviceIndex >= 0)
        assertTrue(first.deviceName.isNotBlank())

        // Explicit user-selected device must be honored even if it is a software ICD (allowSoftwareVulkan=false)
        VulkanOpticalBackend(
            preference = OpticsBackendPreference.VULKAN,
            allowSoftwareVulkan = false,
            preferredDevice = first.specString(),
        ).use { explicitBackend ->
            assertEquals(ActiveOpticsBackend.VULKAN, explicitBackend.activeBackend)
            val diag = explicitBackend.diagnostics()
            assertEquals(first.deviceIndex, diag.deviceIndex)
            assertEquals(first.deviceName, diag.deviceName)
            assertEquals(first.specString(), diag.requestedDevice)
        }

        // Out-of-range device index must emit WARN [FALLBACK][vulkan] and fall back cleanly
        val logger = Logger.getLogger(VulkanOpticalBackend::class.java.name)
        val handler = CapturingLogHandler()
        logger.addHandler(handler)
        try {
            VulkanOpticalBackend(
                preference = OpticsBackendPreference.AUTO,
                allowSoftwareVulkan = true,
                preferredDevice = "9999",
            ).use { badIdxBackend ->
                assertTrue(
                    handler.records.any { it.level == Level.WARNING && it.message.contains("[FALLBACK][vulkan]") },
                    "Expected WARN [FALLBACK][vulkan] for out-of-range device index",
                )
                assertEquals(ActiveOpticsBackend.VULKAN, badIdxBackend.activeBackend)
                assertNotNull(badIdxBackend.diagnostics().fallbackReason)
            }
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun lensFactoryHotSwitchesBackendAndVulkanDeviceAndReportsAvailableDevices(@TempDir tempDir: Path) {
        val ctx = object : ComponentContext {
            override val directory: Path = tempDir
            override val ports: Ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {}
                override fun <T : Any> find(name: String, type: Class<T>): T? = null
                override fun removeOwner(owner: String) {}
            }
            override val events: EventChannel = object : EventChannel {
                override fun publish(event: ComponentEvent) {}
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override val clock: EmulationClock = EmulationClock { 0L }
            override fun dataPath(relative: String): Path = tempDir.resolve(relative)
        }
        val lens = LensFactory().create(
            ctx,
            mapOf("template" to "gf45-100", "opticsBackend" to "cpu", "vulkanDevice" to "auto"),
        )
        try {
            val stCpu = lens.state()
            assertEquals("cpu", stCpu["opticsBackend"])
            assertEquals("auto", stCpu["vulkanDevice"])
            assertTrue(stCpu.containsKey("availableVulkanDevices"))
            assertTrue(stCpu.containsKey("activeVulkanDevice"))

            val loaded = VulkanNativeOpticsBridge.tryLoad()
            if (loaded.isSuccess) {
                loaded.getOrThrow().close()
                @Suppress("UNCHECKED_CAST")
                val avail = stCpu["availableVulkanDevices"] as? List<Map<String, Any?>>
                assertNotNull(avail)
                if (avail.isEmpty()) return
                val firstSpec = avail.first()["spec"] as String
                val firstName = avail.first()["name"] as String

                // Hot-switch to explicit Vulkan GPU without restarting
                lens.applySettings(mapOf("opticsBackend" to "vulkan", "vulkanDevice" to firstSpec))
                val stVk = lens.state()
                assertEquals("vulkan", stVk["opticsBackend"])
                assertEquals(firstSpec, stVk["vulkanDevice"])
                @Suppress("UNCHECKED_CAST")
                val activeDev = stVk["activeVulkanDevice"] as? Map<String, Any?>
                assertNotNull(activeDev)
                assertEquals("VULKAN", activeDev["backend"])
                assertEquals(firstName, activeDev["name"])
                @Suppress("UNCHECKED_CAST")
                val diagVk = stVk["opticsBackendDiagnostics"] as? Map<String, Any?>
                assertNotNull(diagVk)
                assertEquals("VULKAN", diagVk["backend"])
                assertEquals(firstSpec, diagVk["requestedDevice"])

                // Hot-switch back to CPU and auto device
                lens.applySettings(mapOf("opticsBackend" to "cpu", "vulkanDevice" to "auto"))
                val stBack = lens.state()
                assertEquals("cpu", stBack["opticsBackend"])
                assertEquals("auto", stBack["vulkanDevice"])
                @Suppress("UNCHECKED_CAST")
                val diagBack = stBack["opticsBackendDiagnostics"] as? Map<String, Any?>
                assertEquals("CPU", diagBack?.get("backend"))
            }
        } finally {
            lens.close()
        }
    }

    @Test
    fun shapedBokehPsfRecognizesUprightHeartSharpStar5HexagonAndRingOnVulkanAndCpu() {
        assertEquals(28, CpuOpticalPipeline.DEFAULT_MAX_BOKEH_RADIUS_PX, "Shaped bokeh radius cap must be 28 px")

        val w = 128
        val h = 128
        val cx = w / 2
        val cy = h / 2
        val rIn = FloatArray(w * h)
        val gIn = FloatArray(w * h)
        val bIn = FloatArray(w * h)
        val depth = FloatArray(w * h) { 12.0f }
        // Tight pinpoint impulse light at center (3x3 bright core, HDR radiance 50)
        for (dy in -1..1) {
            for (dx in -1..1) {
                val idx = (cy + dy) * w + (cx + dx)
                rIn[idx] = 50.0f
                gIn[idx] = 42.0f
                bIn[idx] = 28.0f
            }
        }
        val dotSrc = LinearRgbImage(w, h, rIn, gIn, bIn)
        val baseProfile = OpticalLensProfile(
            focalLengthMm = 110.0,
            referenceFocalLengthMm = 110.0,
            fNumber = 1.2,
            focusDistanceM = 0.65,
            sensorWidthMm = 43.8,
            apertureBladeCount = 6,
            apertureBladeRoundness = 0.0,
        )
        val cocPx = baseProfile.circleOfConfusionRadiusPixels(12.0, w).coerceAtMost(28.0)
        assertTrue(cocPx >= 18.0, "Expected large shaped-bokeh CoC >= 18px, got $cocPx")

        fun sample(img: LinearRgbImage, dx: Int, dy: Int): Float =
            img.red[(cy + dy).coerceIn(0, h - 1) * w + (cx + dx).coerceIn(0, w - 1)]

        fun harmonic6(img: LinearRgbImage, radius: Double): Double {
            var cSum = 0.0
            var sSum = 0.0
            var dc = 0.0
            val steps = 180
            for (k in 0 until steps) {
                val th = -Math.PI * 0.5 + (2.0 * Math.PI * k) / steps.toDouble()
                val px = (cx + radius * kotlin.math.cos(th)).toInt().coerceIn(0, w - 1)
                val py = (cy + radius * kotlin.math.sin(th)).toInt().coerceIn(0, h - 1)
                val v = img.red[py * w + px].toDouble()
                dc += v
                cSum += v * kotlin.math.cos(6.0 * th)
                sSum += v * kotlin.math.sin(6.0 * th)
            }
            return if (dc <= 1e-6) 0.0 else kotlin.math.hypot(cSum, sSum) / dc
        }

        fun assertAllFourShapes(
            label: String,
            circleImg: LinearRgbImage,
            heartImg: LinearRgbImage,
            starImg: LinearRgbImage,
            hexImg: LinearRgbImage,
            ringImg: LinearRgbImage,
        ) {
            // 1. HEART: upright orientation (top-center cleft notch < left/right upper lobes, pointed bottom tip > flanks)
            val cleftY = -kotlin.math.round(cocPx * 0.78).toInt()
            val lobeY = -kotlin.math.round(cocPx * 0.68).toInt()
            val lobeX = kotlin.math.round(cocPx * 0.44).toInt()
            val tipY = kotlin.math.round(cocPx * 0.74).toInt()
            val flankX = kotlin.math.round(cocPx * 0.38).toInt()
            val cleft = sample(heartImg, 0, cleftY)
            val leftLobe = sample(heartImg, -lobeX, lobeY)
            val rightLobe = sample(heartImg, lobeX, lobeY)
            val tip = sample(heartImg, 0, tipY)
            val flankL = sample(heartImg, -flankX, tipY)
            val flankR = sample(heartImg, flankX, tipY)
            assertTrue(
                leftLobe > 0.05f && rightLobe > 0.05f && cleft < leftLobe * 0.30f && cleft < rightLobe * 0.30f,
                "[$label] HEART must be upright with clear top cleft < upper lobes (cleft=$cleft, L=$leftLobe, R=$rightLobe)",
            )
            assertTrue(
                tip > 0.05f && flankL < tip * 0.30f && flankR < tip * 0.30f,
                "[$label] HEART must have sharp pointed bottom tip > bottom flanks (tip=$tip, fL=$flankL, fR=$flankR)",
            )

            // 2. STAR_5: 5 distinct sharp peaks vs 5 deep valleys (ratio >= 2.0x, tip #0 straight UP)
            val starR = cocPx * 0.64
            for (k in 0 until 5) {
                val tipA = -Math.PI * 0.5 + k * (2.0 * Math.PI / 5.0)
                val valA = tipA + Math.PI / 5.0
                val tipV = sample(
                    starImg,
                    kotlin.math.round(starR * kotlin.math.cos(tipA)).toInt(),
                    kotlin.math.round(starR * kotlin.math.sin(tipA)).toInt(),
                )
                val valV = sample(
                    starImg,
                    kotlin.math.round(starR * kotlin.math.cos(valA)).toInt(),
                    kotlin.math.round(starR * kotlin.math.sin(valA)).toInt(),
                )
                assertTrue(
                    tipV > 0.05f && tipV >= valV * 2.0f + 0.02f,
                    "[$label] STAR_5 tip #$k ($tipV) must exceed valley #$k ($valV) by >= 2.0x",
                )
            }

            // 3. HEXAGON: 6 sharp corners + 6 flat sides with strong 6-fold angular symmetry vs circle
            val hexR = cocPx * 0.91
            val hexH6 = harmonic6(hexImg, hexR)
            val circleH6 = harmonic6(circleImg, hexR)
            val hexCorner = sample(hexImg, 0, -kotlin.math.round(hexR).toInt())
            val hexFlatOut = sample(hexImg, kotlin.math.round(hexR).toInt(), 0)
            assertTrue(
                hexCorner > 0.05f && hexFlatOut < hexCorner * 0.35f && hexH6 > 0.15 && hexH6 > circleH6 * 3.0,
                "[$label] HEXAGON must show crisp 6-fold flat-side geometry vs circle " +
                    "(corner=$hexCorner, flatOut=$hexFlatOut, hexH6=$hexH6, circleH6=$circleH6)",
            )

            // 4. RING: dark center (< 0.15 of outer ring) vs bright outer annulus
            val ringCenter = sample(ringImg, 0, 0)
            val ringOuter = sample(ringImg, kotlin.math.round(cocPx * 0.74).toInt(), 0)
            assertTrue(
                ringOuter > 0.05f && ringCenter < ringOuter * 0.15f,
                "[$label] RING must have dark center < 0.15 of outer ring (center=$ringCenter, outer=$ringOuter)",
            )
        }

        VulkanOpticalBackend(preference = OpticsBackendPreference.CPU).use { cpu ->
            fun render(shape: BokehPupilShape, profile: OpticalLensProfile = baseProfile) =
                cpu.renderLinearRgb(dotSrc, profile = profile, depthMeters = depth, pupilShape = shape)
            val hex = CpuOpticalPipeline().renderLinearRgb(
                dotSrc, w, h, baseProfile, depthMeters = depth,
                pupilMask = BuiltinOpticalPupilMask(BokehMaskShape.HEXAGON),
            )
            assertAllFourShapes(
                "CPU", render(BokehPupilShape.CIRCLE, baseProfile.copy(apertureBladeRoundness = 1.0)),
                render(BokehPupilShape.HEART), render(BokehPupilShape.STAR_5), hex, render(BokehPupilShape.RING),
            )
        }

        // The fallback after a failed native dispatch must retain the same installed cutout.
        val transformedMask = BuiltinOpticalPupilMask(BokehMaskShape.HEART, 90.0, 0.55)
        val transformedStack = OpticalFilterStackConfig.NONE.withPupil(transformedMask)
        val expected = CpuOpticalPipeline().renderLinearRgb(
            dotSrc, w, h, baseProfile, depthMeters = depth, pupilMask = transformedMask,
        )
        VulkanOpticalBackend(preference = OpticsBackendPreference.CPU).use { fallback ->
            val image = fallback.renderLinearRgb(dotSrc, profile = baseProfile, depthMeters = depth,
                pupilShape = BokehPupilShape.HEART, filterStack = transformedStack)
            assertTrue(image.red.contentEquals(expected.red), "CPU fallback lost the installed pupil transform")
        }

        // Part B: Real Vulkan Compute GPU backend (Lavapipe or hardware ICD)
        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) return
        loaded.getOrThrow().close()

        VulkanOpticalBackend(preference = OpticsBackendPreference.VULKAN, allowSoftwareVulkan = true).use { gpu ->
            if (gpu.activeBackend != ActiveOpticsBackend.VULKAN) return
            val vkCircle = gpu.renderLinearRgb(dotSrc, profile = baseProfile, depthMeters = depth, pupilShape = BokehPupilShape.CIRCLE, maxBokehRadiusPx = 28)
            val vkHeart = gpu.renderLinearRgb(dotSrc, profile = baseProfile, depthMeters = depth, pupilShape = BokehPupilShape.HEART, maxBokehRadiusPx = 28)
            val vkStar = gpu.renderLinearRgb(dotSrc, profile = baseProfile, depthMeters = depth, pupilShape = BokehPupilShape.STAR_5, maxBokehRadiusPx = 28)
            val vkHex = gpu.renderLinearRgb(dotSrc, profile = baseProfile, depthMeters = depth, pupilShape = BokehPupilShape.POLYGON, maxBokehRadiusPx = 28)
            val vkRing = gpu.renderLinearRgb(dotSrc, profile = baseProfile, depthMeters = depth, pupilShape = BokehPupilShape.RING, maxBokehRadiusPx = 28)
            assertAllFourShapes("Vulkan", vkCircle, vkHeart, vkStar, vkHex, vkRing)

            if (!gpu.supportsPupilTransform()) {
                val legacy = gpu.renderLinearRgb(dotSrc, profile = baseProfile, depthMeters = depth,
                    pupilShape = BokehPupilShape.HEART, filterStack = transformedStack)
                assertTrue(legacy.red.contentEquals(expected.red), "Legacy Vulkan must route the full cutout to CPU")
            } else {
                // One 90-degree clockwise turn and a smaller physical cutout, through the public FFM entry.
                val mask = object : com.github.nyabanban.fuji.emulator.api.OpticalPupilMask {
                    override val shape = com.github.nyabanban.fuji.emulator.api.BokehMaskShape.HEART
                    override val active = true
                    override val angleDeg = 90.0
                    override val scale = 0.55
                    override val cacheKey = 1L
                    override fun pupilWeight(ux: Double, uy: Double): Double = error("GPU cutout used a CPU sampler")
                }
                val packet = OpticalFilterStackConfig.NONE.withPupil(mask)
                assertTrue(packet.isBypassed, "A cutout must not dispatch the spatial filter pass")
                val transformed = gpu.renderLinearRgb(dotSrc, profile = baseProfile, depthMeters = depth,
                    pupilShape = BokehPupilShape.HEART, maxBokehRadiusPx = 28, filterStack = packet)
                fun radiusMoment(image: LinearRgbImage): Double {
                    var weight = 0.0
                    var moment = 0.0
                    for (y in 0 until h) for (x in 0 until w) {
                        val signal = image.red[y * w + x].toDouble()
                        val dx = x - cx
                        val dy = y - cy
                        weight += signal
                        moment += (dx * dx + dy * dy) * signal
                    }
                    return moment / weight
                }
                assertTrue(radiusMoment(transformed) / radiusMoment(vkHeart) in 0.25..0.38)
                val restored = gpu.renderLinearRgb(dotSrc, profile = baseProfile, depthMeters = depth,
                    pupilShape = BokehPupilShape.HEART, maxBokehRadiusPx = 28)
                assertTrue(vkHeart.red.contentEquals(restored.red), "Unselecting the transform must restore the source PSF")
            }
        }
    }

    @Test
    fun gpuResidentVramSceneCacheAvoidsReuploadAcrossFocusSweepWithZeroLsbDiff() {
        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) return
        loaded.getOrThrow().close()

        VulkanOpticalBackend(
            preference = OpticsBackendPreference.VULKAN,
            allowSoftwareVulkan = true,
        ).use { gpu ->
            if (gpu.activeBackend != ActiveOpticsBackend.VULKAN) return

            val w = 64
            val h = 48
            val n = w * h
            val red = FloatArray(n) { i -> 0.15f + 0.65f * ((i % w).toFloat() / w) }
            val green = FloatArray(n) { i -> 0.20f + 0.50f * ((i / w).toFloat() / h) }
            val blue = FloatArray(n) { i -> 0.25f + 0.35f * (((i % w) xor (i / w)) and 15) / 15f }
            val depth = FloatArray(n) { i -> if ((i % w) in 16..48 && (i / w) in 10..38) 2.2f else 9.0f }
            val src = LinearRgbImage(w, h, red, green, blue)
            val geom = SensorGeometry(w, h, 14, BayerPattern.RGGB, 3.76)
            val cleanNoise = CmosNoiseConfig(seed = 77L, enableNoise = false, blackLevel = 256)
            val sceneKey = 0x55AA_1234_9876_CAFEUL.toLong()

            // Sweep 3 focus distances on the same sceneKey: 1 upload + 2 VRAM cache hits, 0 LSB diff vs uncached.
            for (focusM in listOf(1.5, 2.2, 4.5)) {
                val prof = OpticalLensProfile(
                    focalLengthMm = 80.0,
                    referenceFocalLengthMm = 80.0,
                    fNumber = 2.0,
                    focusDistanceM = focusM,
                )
                val exp = Exposure(0L, 10_000_000L, 100, LensParameters("GF80", 80.0, 2.0, focusM, 0.9, 0.0))
                val cachedBuf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                val directBuf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)

                gpu.renderToBayer(
                    source = src,
                    geometry = geom,
                    exposure = exp,
                    profile = prof,
                    depthMeters = depth,
                    noiseConfig = cleanNoise,
                    destination = cachedBuf,
                    maxBokehRadiusPx = 6,
                    borderMode = BorderMode.CLAMP,
                    sceneCacheKey = sceneKey,
                )
                gpu.renderToBayer(
                    source = src,
                    geometry = geom,
                    exposure = exp,
                    profile = prof,
                    depthMeters = depth,
                    noiseConfig = cleanNoise,
                    destination = directBuf,
                    maxBokehRadiusPx = 6,
                    borderMode = BorderMode.CLAMP,
                    sceneCacheKey = 0L,
                )
                var maxDiff = 0
                for (i in 0 until n) {
                    val c = cachedBuf.getShort(i * 2).toInt() and 0xFFFF
                    val d = directBuf.getShort(i * 2).toInt() and 0xFFFF
                    maxDiff = maxOf(maxDiff, abs(c - d))
                }
                assertEquals(0, maxDiff, "VRAM cached Bayer differed from direct render at focusM=$focusM")
            }
            assertEquals(1L, gpu.vramSceneCacheUploads, "Same scene must upload to DEVICE_LOCAL VRAM exactly once")
            assertEquals(2L, gpu.vramSceneCacheHits, "Subsequent focus-sweep frames must hit DEVICE_LOCAL VRAM cache")

            // Also verify LensOpticalSceneFilter triggers single-submit focus sweep even with NO filter stack mounted.
            val frame = com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame(
                width = w, height = h, red = red, green = green, blue = blue, depthMeters = depth, sequence = 99L,
            )
            val baseProf = OpticalLensProfile(focalLengthMm = 80.0, referenceFocalLengthMm = 80.0, fNumber = 2.0, focusDistanceM = 1.5)
            val filter = com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter(
                enabled = true,
                profile = baseProf,
                noiseConfig = cleanNoise,
                maxBokehRadiusPx = 6,
                borderMode = BorderMode.CLAMP,
                computeBackendProvider = { gpu },
            )
            val b1 = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            val b2 = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            val exp1 = Exposure(0L, 10_000_000L, 100, LensParameters("GF80", 80.0, 2.0, 1.5, 0.9, 0.0))
            val exp2 = Exposure(0L, 10_000_000L, 100, LensParameters("GF80", 80.0, 2.0, 2.8, 0.9, 0.0))
            filter.filterRadianceFrame(frame, exp1, geom, b1, 0L)
            filter.update(profile = baseProf.copy(focusDistanceM = 2.8))
            filter.filterRadianceFrame(frame, exp2, geom, b2, 1L)
            assertTrue(
                filter.vulkanSingleSubmitFrames >= 1L,
                "Focus sweep on same scene without optical filter must use single-submit GPU path",
            )
            assertFalse(b1.array().contentEquals(b2.array()), "Focus change must alter rendered Bayer output")
        }
    }

    @Test
    fun uvFilterOffloadsToVulkanPass0WithParityAndKeepsGpuIspFusionActive() {
        val uvElement = object : com.github.nyabanban.fuji.emulator.api.OpticalFilterElement {
            override val id = "uv"
            override val displayName = "UV Protective Filter"
            override val enabled = true
            override val hasSpatialEffect = true
            override val supportsVulkanBuiltin = true
            override fun transmittanceMultiplier() = 0.895
            override fun summaryBadge() = "UV: Cheap"
            override fun applySpatialLinearRgb(
                red: FloatArray,
                green: FloatArray,
                blue: FloatArray,
                width: Int,
                height: Int,
                scratch: com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch,
            ) = Unit
            override fun stateMap(): Map<String, Any?> = mapOf(
                "id" to "uv",
                "enabled" to true,
                "transmittance" to 0.895,
                "veilingGlare" to 0.25,
                "ghostIntensity" to 0.30,
                "softness" to 0.45,
                "tintR" to 0.96,
                "tintG" to 1.04,
                "tintB" to 0.94,
            )
        }
        val uvStack = object : com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort {
            override val revision = 1L
            override val enabled = true
            override fun activeElements() = listOf(uvElement)
            override fun totalTransmittance() = 0.895
            override fun hasActiveSpatialEffects() = true
            override fun activePupilMask() = null
            override fun applySpatialStack(
                red: FloatArray,
                green: FloatArray,
                blue: FloatArray,
                width: Int,
                height: Int,
                scratch: com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch,
            ) = Unit
            override fun stackState() = mapOf("enabled" to true)
        }

        // 1. Translator must accept UV filter for Vulkan Pass 0 without CPU fallback
        assertTrue(OpticalFilterStackTranslator.canOffloadSpatial(uvStack), "UV filter must offload to Vulkan Pass 0")
        val tr = OpticalFilterStackTranslator.translate(uvStack, 64, 64)
        assertTrue(tr.canOffloadSpatialToGpu, "UV filter translation must stay on GPU")
        assertTrue((tr.spatialOnlyConfig.enableFlags and OpticalFilterStackConfig.FLAG_UV) != 0)
        assertTrue((tr.fullConfigWithNd.enableFlags and OpticalFilterStackConfig.FLAG_UV) != 0)
        assertTrue((tr.fullConfigWithNd.enableFlags and OpticalFilterStackConfig.FLAG_ND) != 0, "UV Fresnel transmittance folds into ND stops")

        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) return
        loaded.getOrThrow().close()

        VulkanOpticalBackend(preference = OpticsBackendPreference.VULKAN, allowSoftwareVulkan = true).use { gpu ->
            if (gpu.activeBackend != ActiveOpticsBackend.VULKAN) return

            // 2. Vulkan Pass 0 UV filter physics check (ghosting, veiling glare, softness, tint)
            val w = 64
            val h = 64
            val r = FloatArray(w * h)
            val g = FloatArray(w * h)
            val b = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = if (x >= w / 2) 0.30f else 0.05f
                    val i = y * w + x
                    r[i] = v; g[i] = v; b[i] = v
                }
            }
            // Bright specular lamp in upper-left quadrant (12, 12)
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val i = (12 + dy) * w + (12 + dx)
                    r[i] = 6.0f; g[i] = 6.0f; b[i] = 6.0f
                }
            }
            val src = LinearRgbImage(w, h, r, g, b)
            val uvOut = gpu.renderLinearRgb(
                source = src,
                profile = OpticalLensProfile.IDENTITY,
                maxBokehRadiusPx = 0,
                filterStack = tr.spatialOnlyConfig,
            )
            val ghostIdx = (h - 1 - 12) * w + (w - 1 - 12)
            val bgIdx = (h - 1 - 12) * w + (w / 2 + 4)
            val shadowIdx = (h - 4) * w + 4
            val edgeDarkIdx = (h / 2) * w + (w / 2 - 1)
            assertTrue(uvOut.green[ghostIdx] > uvOut.green[bgIdx] + 0.15f, "Vulkan UV Pass 0 must produce point-symmetric ghost")
            assertTrue(uvOut.green[ghostIdx] > uvOut.red[ghostIdx], "Vulkan UV uncoated ghost + tint must boost G > R")
            assertTrue(uvOut.green[shadowIdx] > 0.055f, "Vulkan UV veiling glare must lift deep shadow above 0.05")
            assertTrue(uvOut.red[edgeDarkIdx] > uvOut.red[shadowIdx] + 0.02f, "Vulkan UV softness must soften high-contrast edge")

            // 3. GPU-ISP fusion MUST stay active with UV filter mounted + FLAG_SRC_CROP still crop!
            val filter = com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter(
                enabled = true,
                profile = OpticalLensProfile.IDENTITY,
                noiseConfig = CmosNoiseConfig.NOISELESS,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                computeBackendProvider = { gpu },
                opticalFilterStackProvider = { uvStack },
            )
            assertTrue(filter.handlesSpatialFilterStack(uvStack), "LensOpticalSceneFilter must keep UV filter on GPU")

            // Build frame with bright center box [16..48, 16..48] and dark outer border
            val cR = FloatArray(w * h)
            val cG = FloatArray(w * h)
            val cB = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val bright = x in 20 until 44 && y in 20 until 44
                    val v = if (bright) 0.85f else 0.05f
                    val i = y * w + x
                    cR[i] = v; cG[i] = v; cB[i] = v
                }
            }
            val radFrame = com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame(
                width = w,
                height = h,
                red = cR,
                green = cG,
                blue = cB,
                sequence = 42L,
            )
            val geom = SensorGeometry(w, h, 14, BayerPattern.RGGB, 3.76)
            val exp = Exposure(0L, 10_000_000L, 100, LensParameters("GF50", 50.0, 2.8, 2.0, 1.0, 0.0))
            // Crop tightly inside the bright center box [22..42, 22..42] -> entire 32x24 UYVY still must be bright!
            val dstW = 32
            val dstH = 24
            val req = com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest(
                exposure = exp,
                dstWidth = dstW,
                dstHeight = dstH,
                dstStrideBytes = dstW * 2,
                outputFormat = com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest.FMT_UYVY422,
                flags = com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest.FLAG_SRC_CROP,
                offX = 22,
                offY = 22,
                fitW = 20,
                fitH = 20,
                gainR1024 = 1024,
                gainG1024 = 1024,
                gainB1024 = 1024,
                yuvShift = 10,
            )
            val outUyvy = ByteArray(dstW * dstH * 2)
            assertTrue(
                filter.tryFilterRadianceToIspYuv(radFrame, geom, req, outUyvy, null, 1L),
                "Fused Vulkan Optics->ISP with UV filter + FLAG_SRC_CROP must succeed on GPU",
            )
            assertEquals(1L, filter.vulkanFusedIspFrames)
            // Corner pixel (0,0) of the cropped still must come from bright [22,22], NOT dark border [0,0]!
            val cornerY = outUyvy[1].toInt() and 0xFF
            val centerY = outUyvy[(dstH / 2) * (dstW * 2) + (dstW / 2) * 2 + 1].toInt() and 0xFF
            assertTrue(cornerY > 120 && centerY > 120, "FLAG_SRC_CROP still corner ($cornerY) & center ($centerY) must both sample bright crop box")
        }
    }

    @Test
    fun diskPersistentPipelineCacheTelemetryAndRing2AsyncParity(@TempDir tempDir: Path) {
        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) return
        loaded.getOrThrow().close()

        val cacheFile = tempDir.resolve("test-vulkan-pipeline.bin")

        // 1. Cold start -> pipelineCacheHit=false, serializes validated cache blob to disk
        VulkanOpticalBackend(
            preference = OpticsBackendPreference.VULKAN,
            allowSoftwareVulkan = true,
            pipelineCachePath = cacheFile,
        ).use { cold ->
            if (cold.activeBackend != ActiveOpticsBackend.VULKAN) return
            val dCold = cold.diagnostics()
            assertFalse(dCold.pipelineCacheHit, "Cold start must miss disk VkPipelineCache")
            assertTrue(dCold.pipelineCacheBytes > 0L, "Cold start must persist non-empty VkPipelineCache blob")
            assertTrue(dCold.specializedPipelineCount >= 5, "Pre-warm must compile default specialized pipelines")
            assertTrue(dCold.pipelineWarmupMs >= 0.0)
            val m = dCold.toMap()
            assertEquals(false, m["pipelineCacheHit"])
            assertTrue((m["pipelineCacheBytes"] as Long) > 0L)
        }
        assertTrue(java.nio.file.Files.isRegularFile(cacheFile), "Expected persistent VkPipelineCache file on disk")

        // 2. Warm start -> pipelineCacheHit=true + Ring-2 async submit/collect 0 LSB diff vs sync
        VulkanOpticalBackend(
            preference = OpticsBackendPreference.VULKAN,
            allowSoftwareVulkan = true,
            pipelineCachePath = cacheFile,
        ).use { warm ->
            assertEquals(ActiveOpticsBackend.VULKAN, warm.activeBackend)
            val dWarm = warm.diagnostics()
            assertTrue(dWarm.pipelineCacheHit, "Warm start must hit validated disk VkPipelineCache")
            assertTrue(dWarm.pipelineCacheBytes > 0L)

            val br = warm.loadedBridgeOrNull()!!
            assertTrue(br.supportsAsyncRing2, "Native bridge must export Ring-2 async submit/collect")
        }
    }

    @Test
    fun opticalFilterStackConfigCopyEqualsHashCodeAndMutableMask() {
        val heartA = BuiltinOpticalPupilMask(BokehMaskShape.HEART, angleDeg = 45.0, scale = 0.7)
        val heartB = BuiltinOpticalPupilMask(BokehMaskShape.HEART, angleDeg = 90.0, scale = 0.7)
        val hex = BuiltinOpticalPupilMask(BokehMaskShape.HEXAGON)

        val base = OpticalFilterStackConfig(ndStops = 1.0)
        val withA = base.withPupil(heartA)
        val withA2 = base.withPupil(heartA)
        val withB = base.withPupil(heartB)
        val withHex = base.withPupil(hex)

        assertNotEquals(base, withA, "Adding a pupil mask must affect equals()")
        assertNotEquals(withA, withB, "Different pupil transforms must not compare equal")
        assertNotEquals(base, withHex, "Hexagon cutout must affect equals()")
        assertEquals(withA, withA2)
        assertEquals(withA.hashCode(), withA2.hashCode())

        // copy() must preserve pupilMask and pupilTransformFlags
        val copied = withA.copy(ndStops = 2.0)
        assertEquals(heartA, copied.pupilMask)
        assertEquals(withA.pupilTransformFlags, copied.pupilTransformFlags)
        assertEquals(2.0, copied.ndStops)
        assertEquals(OpticalFilterStackConfig(ndStops = 2.0).withPupil(heartA), copied)

        // withPupil(null) must clear pupilMask and pupilTransformFlags
        val cleared = withA.withPupil(null)
        assertEquals(base, cleared)
        assertEquals(null, cleared.pupilMask)
        assertEquals(0, cleared.pupilTransformFlags)

        // In-place mutation of a custom OpticalPupilMask must recompute pupilTransformFlags
        class MutableMask : com.github.nyabanban.fuji.emulator.api.OpticalPupilMask {
            override var shape: BokehMaskShape = BokehMaskShape.HEART
            override var active: Boolean = true
            override var angleDeg: Double = 0.0
            override var scale: Double = 1.0
            override var cacheKey: Long = 1L
            override fun pupilWeight(ux: Double, uy: Double): Double = 1.0
        }
        val mutable = MutableMask()
        val before = base.withPupil(mutable)
        assertEquals(0, before.pupilTransformFlags)
        mutable.angleDeg = 90.0
        mutable.scale = 0.6
        val after = before.withPupil(mutable)
        assertNotEquals(0, after.pupilTransformFlags, "Mutating mask in place must not keep stale pupilTransformFlags")
        assertNotEquals(before, after)
    }

    @Test
    fun legacyVulkanBackendRejectsHexagonCutoutInSpatialFilterCheck() {
        val gndElement = object : com.github.nyabanban.fuji.emulator.api.OpticalFilterElement {
            override val id: String = "gnd"
            override val displayName: String = "GND"
            override val enabled: Boolean = true
            override val hasSpatialEffect: Boolean = true
            override val supportsVulkanBuiltin: Boolean = true
            override fun transmittanceMultiplier(): Double = 1.0
            override fun applySpatialLinearRgb(
                red: FloatArray,
                green: FloatArray,
                blue: FloatArray,
                width: Int,
                height: Int,
                scratch: com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch,
            ) {}
            override fun summaryBadge(): String = "GND"
            override fun stateMap(): Map<String, Any?> = mapOf(
                "stops" to 2.0,
                "horizonY" to 0.5,
                "feather" to 0.15,
                "angleDeg" to 0.0,
            )
        }
        val hexStack = object : com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort {
            override val revision: Long = 1L
            override val enabled: Boolean = true
            override fun activeElements(): List<com.github.nyabanban.fuji.emulator.api.OpticalFilterElement> = listOf(gndElement)
            override fun totalTransmittance(): Double = 1.0
            override fun hasActiveSpatialEffects(): Boolean = true
            override fun activePupilMask(): com.github.nyabanban.fuji.emulator.api.OpticalPupilMask =
                BuiltinOpticalPupilMask(BokehMaskShape.HEXAGON)
            override fun applySpatialStack(
                red: FloatArray,
                green: FloatArray,
                blue: FloatArray,
                width: Int,
                height: Int,
                scratch: com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch,
            ) {}
            override fun stackState(): Map<String, Any?> = emptyMap()
        }
        VulkanOpticalBackend(preference = OpticsBackendPreference.CPU).use { stubBase ->
            fun fakeVulkanBackend(supportsTransform: Boolean): OpticalComputeBackend =
                object : OpticalComputeBackend by stubBase {
                    override val activeBackend: ActiveOpticsBackend = ActiveOpticsBackend.VULKAN
                    override fun supportsPupilTransform(): Boolean = supportsTransform
                }

            val legacyFilter = com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter(
                enabled = true,
                profile = OpticalLensProfile.IDENTITY,
                computeBackendProvider = { fakeVulkanBackend(supportsTransform = false) },
                opticalFilterStackProvider = { hexStack },
            )
            assertFalse(
                legacyFilter.handlesSpatialFilterStack(hexStack),
                "Legacy Vulkan backend without fuji_vk_supports_pupil_transform must reject HEXAGON cutout",
            )

            val modernFilter = com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter(
                enabled = true,
                profile = OpticalLensProfile.IDENTITY,
                computeBackendProvider = { fakeVulkanBackend(supportsTransform = true) },
                opticalFilterStackProvider = { hexStack },
            )
            assertTrue(
                modernFilter.handlesSpatialFilterStack(hexStack),
                "Vulkan backend with fuji_vk_supports_pupil_transform must accept HEXAGON cutout",
            )
        }
    }

    @Test
    fun asyncBayerReadbackPreservesCallerPaddingWithoutPreCopy() = withVulkanContext { bridge, ctx ->
        org.junit.jupiter.api.Assumptions.assumeTrue(bridge.supportsAsyncRing2, "Async Ring-2 unavailable")
        val w = 8
        val h = 6
        val source = FloatArray(w * h * 4) { i -> if (i % 4 == 3) 2f else (i % 17 + 1) / 20f }
        val optics = VulkanNativeOpticsBridge.OpticsParamsStruct(
            srcWidth = w, srcHeight = h, dstWidth = w, dstHeight = h,
            k1 = 0f, k2 = 0f, p1 = 0f, p2 = 0f, caRedScale = 1f, caBlueScale = 1f,
            cos4Power = 0f, mechVignetting = 0f, focalLengthMm = 45f, sensorWidthMm = 44f,
            focusDistanceM = 2f, fNumber = 4f, invZoom = 1f, ibisShiftX = 0f,
            ibisShiftY = 0f, ibisCosRoll = 1f, ibisSinRoll = 0f, maxBokehPx = 0f,
            bladeRoundness = 1f, catsEye = 0f, onionRing = 0f, rimHighlight = 0f,
            planarDepthM = 2f, imageCircleMm = 55f, pupilShape = 0,
            bladeCount = 9, borderClamp = 1, flags = 0,
        )
        val cmos = VulkanNativeOpticsBridge.CmosParamsStruct(
            width = w, height = h, bayerPattern = 0, bitDepth = 14, maxCode = 16383,
            blackLevel = 256, enableNoise = 0, iso = 100, baseIso = 100f,
            fullWellElectrons = 51000f, opticalExposureScale = 1f,
            readNoiseBaseElectrons = 0f, readNoiseIsoSlope = 0f, seedLo = 0, seedHi = 0, frameIndexLo = 0,
        )
        val expectedBytes = w * h * 2
        val syncOut = ByteArray(expectedBytes)
        assertEquals(VulkanNativeOpticsBridge.FUJI_VK_OK, bridge.renderOpticsToBayer(ctx, source, syncOut, optics, cmos).first)

        val (subRc, ticket) = bridge.submitAsyncToBayer(ctx, 0L, source, optics, cmos)
        assertEquals(VulkanNativeOpticsBridge.FUJI_VK_OK, subRc)
        assertTrue(ticket != 0L)

        val sentinel = 0x5A.toByte()
        // Undersized collect must fail without consuming the ticket or modifying caller bytes
        val tooSmall = ByteArray(expectedBytes - 2) { sentinel }
        assertEquals(
            VulkanNativeOpticsBridge.FUJI_VK_ERR_INVALID_ARG,
            bridge.collectAsyncBayer(ctx, ticket, tooSmall).first,
        )
        assertTrue(tooSmall.all { it == sentinel })

        // Padded destination must receive exact Bayer bytes and keep all trailing padding intact
        val padded = ByteArray(expectedBytes + 37) { sentinel }
        assertEquals(
            VulkanNativeOpticsBridge.FUJI_VK_OK,
            bridge.collectAsyncBayer(ctx, ticket, padded).first,
        )
        assertTrue(syncOut.contentEquals(padded.copyOf(expectedBytes)), "Async Bayer must match sync output bit-for-bit")
        assertTrue(
            (expectedBytes until padded.size).all { padded[it] == sentinel },
            "Async Bayer readback must preserve all trailing caller padding bytes",
        )
    }

    private data class SpirvHeaderReport(
        val valid: Boolean,
        val entryPointName: String?,
        val localSizeX: Int,
        val localSizeY: Int,
        val errors: List<String>,
    )

    private fun validateSpirv10Compute(words: IntArray): SpirvHeaderReport {
        val errors = mutableListOf<String>()
        if (words.size < 5) {
            return SpirvHeaderReport(false, null, 0, 0, listOf("SPIR-V shorter than 5-word header"))
        }
        if (words[0] != 0x07230203) errors += "Bad magic 0x${words[0].toUInt().toString(16)}"
        if (words[1] != 0x00010000) errors += "Unexpected version 0x${words[1].toUInt().toString(16)}"
        if (words[3] <= 0) errors += "Non-positive idBound ${words[3]}"

        var entryName: String? = null
        var lsx = 0
        var lsy = 0
        var pos = 5
        while (pos < words.size) {
            val w0 = words[pos]
            val wc = w0 ushr 16
            val op = w0 and 0xFFFF
            if (wc <= 0 || pos + wc > words.size) {
                errors += "Truncated instruction at word $pos (wc=$wc)"
                break
            }
            if (op == 15 && wc >= 4 && words[pos + 1] == 5) { // OpEntryPoint GLCompute
                val bytes = ByteArray((wc - 3) * 4)
                var bLen = 0
                outer@ for (i in 3 until wc) {
                    val v = words[pos + i]
                    for (shift in 0..24 step 8) {
                        val c = (v ushr shift) and 0xFF
                        if (c == 0) break@outer
                        bytes[bLen++] = c.toByte()
                    }
                }
                entryName = String(bytes, 0, bLen, Charsets.UTF_8)
            } else if (op == 16 && wc >= 6 && words[pos + 2] == 17) { // OpExecutionMode LocalSize
                lsx = words[pos + 3]
                lsy = words[pos + 4]
            }
            pos += wc
        }
        return SpirvHeaderReport(errors.isEmpty() && entryName != null, entryName, lsx, lsy, errors)
    }
}
