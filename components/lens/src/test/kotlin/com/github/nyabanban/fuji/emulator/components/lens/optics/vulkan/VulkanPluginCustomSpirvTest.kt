package com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan

import com.github.nyabanban.fuji.emulator.api.BayerPattern
import com.github.nyabanban.fuji.emulator.api.CustomSpirvPassSpec
import com.github.nyabanban.fuji.emulator.api.CustomSpirvStage
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensOpticalHook
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.LinearRadianceFrame
import com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest
import com.github.nyabanban.fuji.emulator.api.OpticalFilterElement
import com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch
import com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort
import com.github.nyabanban.fuji.emulator.api.OpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.SensorGeometry
import com.github.nyabanban.fuji.emulator.components.lens.optics.BorderMode
import com.github.nyabanban.fuji.emulator.components.lens.optics.CmosNoiseConfig
import com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter
import com.github.nyabanban.fuji.emulator.components.lens.optics.LinearRgbImage
import com.github.nyabanban.fuji.emulator.components.lens.optics.OpticalLensProfile
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for Plugin SPI v1.0 Custom SPIR-V (`.spv`) compute shader execution
 * across [VulkanOpticalBackend], [OpticalFilterStackTranslator], and [LensOpticalSceneFilter].
 */
class VulkanPluginCustomSpirvTest {

    private class SpvAsm {
        private val words = mutableListOf(0x07230203, 0x00010000, 0, 0, 0)
        private var nextId = 1

        fun id(): Int = nextId++

        fun op(opcode: Int, vararg args: Int) {
            words.add(((1 + args.size) shl 16) or opcode)
            for (a in args) words.add(a)
        }

        fun opStr(opcode: Int, prefix: IntArray, s: String, suffix: IntArray = IntArray(0)) {
            val raw = s.toByteArray(Charsets.UTF_8)
            val slen = raw.size + 1
            val swords = (slen + 3) / 4
            words.add(((1 + prefix.size + swords + suffix.size) shl 16) or opcode)
            for (a in prefix) words.add(a)
            for (i in 0 until swords) {
                var w = 0
                for (b in 0 until 4) {
                    val idx = i * 4 + b
                    val byteVal = if (idx < raw.size) (raw[idx].toInt() and 0xFF) else 0
                    w = w or (byteVal shl (b * 8))
                }
                words.add(w)
            }
            for (a in suffix) words.add(a)
        }

        fun finishBytes(): ByteArray {
            words[3] = nextId
            val bb = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (w in words) bb.putInt(w)
            return bb.array()
        }
    }

    /**
     * Assembles a Khronos SPIR-V 1.0 compute shader implementing the fixed `set = 0` Plugin ABI:
     *   binding 0 = SrcRgba (vec4[]), binding 1 = DstRgba (vec4[]),
     *   binding 2 = DepthMeters (float[]), binding 3 = PluginUbo (32B header + vec4 userTint),
     *   push_constant = { float pushGain }.
     * Computes: `dst[idx] = vec4(src[idx].rgb * userTint.rgb * pushGain, depth[idx])`.
     */
    private fun buildTintPushSpirv(entryPoint: String = "main"): ByteArray {
        val s = SpvAsm()
        val fnMain = s.id()
        val tVoid = s.id(); val tFn = s.id()
        val tFloat = s.id(); val tVec4 = s.id(); val tUint = s.id(); val tVec3U = s.id(); val tBool = s.id()
        val tArrVec4 = s.id(); val tRgbaStruct = s.id(); val tPtrRgbaStruct = s.id(); val tPtrVec4 = s.id()
        val tArrFloat = s.id(); val tDepthStruct = s.id(); val tPtrDepthStruct = s.id(); val tPtrFloat = s.id()
        val tUboStruct = s.id(); val tPtrUboStruct = s.id(); val tPtrUboUint = s.id(); val tPtrUboVec4 = s.id()
        val tPushStruct = s.id(); val tPtrPushStruct = s.id(); val tPtrPushFloat = s.id()
        val tPtrInVec3U = s.id(); val tPtrInUint = s.id()
        val vSrc = s.id(); val vDst = s.id(); val vDepth = s.id(); val vUbo = s.id(); val vPush = s.id(); val vGid = s.id()
        val c0 = s.id(); val c1 = s.id(); val c8 = s.id()
        val lblEntry = s.id(); val lblBody = s.id(); val lblExit = s.id()
        val rPtrGx = s.id(); val rX = s.id(); val rPtrGy = s.id(); val rY = s.id()
        val rPtrW = s.id(); val rW = s.id(); val rPtrH = s.id(); val rH = s.id()
        val rCmpX = s.id(); val rCmpY = s.id(); val rInBounds = s.id()
        val rRow = s.id(); val rIdx = s.id()
        val rPtrSrc = s.id(); val rSrc = s.id(); val rPtrDep = s.id(); val rDep = s.id()
        val rPtrTint = s.id(); val rTint = s.id(); val rPtrPush = s.id(); val rGain = s.id()
        val rTinted = s.id(); val rScaled = s.id()
        val rR = s.id(); val rG = s.id(); val rB = s.id(); val rOut = s.id(); val rPtrDst = s.id()

        s.op(17, 1) // OpCapability Shader
        s.op(14, 0, 1) // OpMemoryModel Logical GLSL450
        s.opStr(15, intArrayOf(5, fnMain), entryPoint, intArrayOf(vGid)) // OpEntryPoint GLCompute
        s.op(16, fnMain, 17, 8, 8, 1) // LocalSize 8x8x1

        s.op(71, tArrVec4, 6, 16)
        s.op(72, tRgbaStruct, 0, 35, 0)
        s.op(71, tRgbaStruct, 3)
        s.op(71, vSrc, 34, 0); s.op(71, vSrc, 33, 0)
        s.op(71, vDst, 34, 0); s.op(71, vDst, 33, 1)

        s.op(71, tArrFloat, 6, 4)
        s.op(72, tDepthStruct, 0, 35, 0)
        s.op(71, tDepthStruct, 3)
        s.op(71, vDepth, 34, 0); s.op(71, vDepth, 33, 2)

        for (i in 0..8) s.op(72, tUboStruct, i, 35, i * 4)
        s.op(71, tUboStruct, 2)
        s.op(71, vUbo, 34, 0); s.op(71, vUbo, 33, 3)

        s.op(72, tPushStruct, 0, 35, 0)
        s.op(71, tPushStruct, 2)
        s.op(71, vGid, 11, 28)

        s.op(19, tVoid)
        s.op(33, tFn, tVoid)
        s.op(22, tFloat, 32)
        s.op(23, tVec4, tFloat, 4)
        s.op(21, tUint, 32, 0)
        s.op(23, tVec3U, tUint, 3)
        s.op(20, tBool)

        s.op(29, tArrVec4, tVec4)
        s.op(30, tRgbaStruct, tArrVec4)
        s.op(32, tPtrRgbaStruct, 2, tRgbaStruct)
        s.op(32, tPtrVec4, 2, tVec4)

        s.op(29, tArrFloat, tFloat)
        s.op(30, tDepthStruct, tArrFloat)
        s.op(32, tPtrDepthStruct, 2, tDepthStruct)
        s.op(32, tPtrFloat, 2, tFloat)

        s.op(30, tUboStruct, tUint, tUint, tFloat, tFloat, tFloat, tUint, tUint, tUint, tVec4)
        s.op(32, tPtrUboStruct, 2, tUboStruct)
        s.op(32, tPtrUboUint, 2, tUint)
        s.op(32, tPtrUboVec4, 2, tVec4)

        s.op(30, tPushStruct, tFloat)
        s.op(32, tPtrPushStruct, 9, tPushStruct)
        s.op(32, tPtrPushFloat, 9, tFloat)

        s.op(32, tPtrInVec3U, 1, tVec3U)
        s.op(32, tPtrInUint, 1, tUint)

        s.op(43, tUint, c0, 0)
        s.op(43, tUint, c1, 1)
        s.op(43, tUint, c8, 8)

        s.op(59, tPtrRgbaStruct, vSrc, 2)
        s.op(59, tPtrRgbaStruct, vDst, 2)
        s.op(59, tPtrDepthStruct, vDepth, 2)
        s.op(59, tPtrUboStruct, vUbo, 2)
        s.op(59, tPtrPushStruct, vPush, 9)
        s.op(59, tPtrInVec3U, vGid, 1)

        s.op(54, tVoid, fnMain, 0, tFn)
        s.op(248, lblEntry)
        s.op(65, tPtrInUint, rPtrGx, vGid, c0); s.op(61, tUint, rX, rPtrGx)
        s.op(65, tPtrInUint, rPtrGy, vGid, c1); s.op(61, tUint, rY, rPtrGy)
        s.op(65, tPtrUboUint, rPtrW, vUbo, c0); s.op(61, tUint, rW, rPtrW)
        s.op(65, tPtrUboUint, rPtrH, vUbo, c1); s.op(61, tUint, rH, rPtrH)
        s.op(176, tBool, rCmpX, rX, rW) // OpULessThan
        s.op(176, tBool, rCmpY, rY, rH) // OpULessThan
        s.op(167, tBool, rInBounds, rCmpX, rCmpY)
        s.op(247, lblExit, 0)
        s.op(250, rInBounds, lblBody, lblExit)

        s.op(248, lblBody)
        s.op(132, tUint, rRow, rY, rW)
        s.op(128, tUint, rIdx, rRow, rX)
        s.op(65, tPtrVec4, rPtrSrc, vSrc, c0, rIdx); s.op(61, tVec4, rSrc, rPtrSrc)
        s.op(65, tPtrFloat, rPtrDep, vDepth, c0, rIdx); s.op(61, tFloat, rDep, rPtrDep)
        s.op(65, tPtrUboVec4, rPtrTint, vUbo, c8); s.op(61, tVec4, rTint, rPtrTint)
        s.op(65, tPtrPushFloat, rPtrPush, vPush, c0); s.op(61, tFloat, rGain, rPtrPush)
        s.op(133, tVec4, rTinted, rSrc, rTint)
        s.op(142, tVec4, rScaled, rTinted, rGain)
        s.op(81, tFloat, rR, rScaled, 0)
        s.op(81, tFloat, rG, rScaled, 1)
        s.op(81, tFloat, rB, rScaled, 2)
        s.op(80, tVec4, rOut, rR, rG, rB, rDep)
        s.op(65, tPtrVec4, rPtrDst, vDst, c0, rIdx)
        s.op(62, rPtrDst, rOut)
        s.op(249, lblExit)

        s.op(248, lblExit)
        s.op(253)
        s.op(56)
        return s.finishBytes()
    }

    private fun floatsToLeBytes(vararg vals: Float): ByteArray {
        val bb = ByteBuffer.allocate(vals.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vals) bb.putFloat(v)
        return bb.array()
    }

    @Test
    fun `custom OpticalFilterElement spv runs on Vulkan GPU including fused ISP YUV matches CPU within 1e-3 and hits LRU cache`() {
        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) return
        loaded.getOrThrow().close()

        VulkanOpticalBackend(
            preference = OpticsBackendPreference.VULKAN,
            allowSoftwareVulkan = true,
        ).use { gpu ->
            if (gpu.activeBackend != ActiveOpticsBackend.VULKAN) return

            val spvBytes = buildTintPushSpirv("main")
            val tintR = 1.4f
            val tintG = 0.6f
            val tintB = 0.3f
            val pushGain = 1.25f

            val customFilter = object : OpticalFilterElement {
                override val id: String = "plugin-custom-tint"
                override val displayName: String = "Plugin Custom Tint"
                override val enabled: Boolean = true
                override val hasSpatialEffect: Boolean = true
                override fun transmittanceMultiplier(): Double = 1.0
                override fun vulkanSpirvPass(width: Int, height: Int): CustomSpirvPassSpec = CustomSpirvPassSpec(
                    shaderId = "plugin-custom-tint-v1",
                    spirvBytes = spvBytes,
                    entryPoint = "main",
                    workgroupSizeX = 8,
                    workgroupSizeY = 8,
                    uniformData = floatsToLeBytes(tintR, tintG, tintB, 1.0f),
                    pushConstants = floatsToLeBytes(pushGain),
                    stage = CustomSpirvStage.PRE_OPTICS_FILTER,
                )
                override fun applySpatialLinearRgb(
                    red: FloatArray,
                    green: FloatArray,
                    blue: FloatArray,
                    width: Int,
                    height: Int,
                    scratch: OpticalFilterScratch,
                ) {
                    for (i in 0 until width * height) {
                        red[i] *= tintR * pushGain
                        green[i] *= tintG * pushGain
                        blue[i] *= tintB * pushGain
                    }
                }
                override fun summaryBadge(): String = "CustomTint"
                override fun stateMap(): Map<String, Any?> = mapOf("id" to id)
            }

            val stack = object : OpticalFilterStackPort {
                override val revision: Long = 1L
                override val enabled: Boolean = true
                override fun activeElements(): List<OpticalFilterElement> = listOf(customFilter)
                override fun totalTransmittance(): Double = 1.0
                override fun hasActiveSpatialEffects(): Boolean = true
                override fun activePupilMask(): OpticalPupilMask? = null
                override fun applySpatialStack(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: OpticalFilterScratch) {
                    customFilter.applySpatialLinearRgb(red, green, blue, width, height, scratch)
                }
                override fun stackState(): Map<String, Any?> = emptyMap()
            }

            assertTrue(OpticalFilterStackTranslator.canOffloadSpatial(stack), "Translator must offload custom .spv OpticalFilterElement to GPU")

            val w = 32
            val h = 24
            val n = w * h
            val red = FloatArray(n) { 0.30f }
            val green = FloatArray(n) { 0.40f }
            val blue = FloatArray(n) { 0.50f }
            val depth = FloatArray(n) { 2.5f }
            val src = LinearRgbImage(w, h, red, green, blue)
            val profile = OpticalLensProfile.IDENTITY.copy(focusDistanceM = 2.5, fNumber = 2.8, focalLengthMm = 50.0, referenceFocalLengthMm = 50.0)

            // 1. Direct GPU LinearRgb parity vs CPU reference (< 1e-3!) + LRU cache hit on frame 2
            val passSpec = customFilter.vulkanSpirvPass(w, h)
            val gpuFrame1 = gpu.renderLinearRgb(
                source = src,
                dstWidth = w,
                dstHeight = h,
                profile = profile,
                depthMeters = depth,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                customSpirvPasses = listOf(passSpec),
                frameIndex = 1L,
            )
            val cpuR = red.copyOf()
            val cpuG = green.copyOf()
            val cpuB = blue.copyOf()
            customFilter.applySpatialLinearRgb(cpuR, cpuG, cpuB, w, h, OpticalFilterScratch())

            var maxErr = 0.0f
            for (i in 0 until n) {
                maxErr = maxOf(
                    maxErr,
                    abs(gpuFrame1.red[i] - cpuR[i]),
                    abs(gpuFrame1.green[i] - cpuG[i]),
                    abs(gpuFrame1.blue[i] - cpuB[i]),
                )
            }
            assertTrue(maxErr < 1e-3f, "Custom SPIR-V GPU output must match CPU reference within 1e-3 (maxErr=$maxErr)")

            // Frame 2 -> LRU cache HIT!
            val gpuFrame2 = gpu.renderLinearRgb(
                source = src,
                dstWidth = w,
                dstHeight = h,
                profile = profile,
                depthMeters = depth,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                customSpirvPasses = listOf(passSpec),
                frameIndex = 2L,
            )
            assertEquals(gpuFrame1.red[0], gpuFrame2.red[0], 1e-5f)
            val cacheStats = assertNotNull(gpu.customSpirvCacheStats())
            assertEquals(1, cacheStats.entries, "Single shader must occupy 1 LRU cache entry")
            assertEquals(1L, cacheStats.misses, "First frame compiles once (1 miss)")
            assertTrue(cacheStats.hits >= 1L, "Second frame must HIT LRU pipeline cache (hits=${cacheStats.hits})")

            // 2. End-to-end inside LensOpticalSceneFilter.tryFilterRadianceToIspYuv (fused single submit!)
            val sceneFilter = LensOpticalSceneFilter(
                enabled = true,
                profile = profile,
                noiseConfig = CmosNoiseConfig.NOISELESS,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                computeBackendProvider = { gpu },
                opticalFilterStackProvider = { stack },
            )
            assertTrue(sceneFilter.handlesSpatialFilterStack(stack), "LensOpticalSceneFilter must claim valid custom .spv filter stack for GPU")

            val geom = SensorGeometry(w, h, 16, BayerPattern.RGGB, 3.76)
            val exp = Exposure(0L, 10_000_000L, 100, LensParameters("GF50", 50.0, 2.8, 2.5, 1.0, 0.0))
            val fusionReq = LiveViewIspFusionRequest(
                exposure = exp,
                dstWidth = w,
                dstHeight = h,
                dstStrideBytes = w * 2,
                outputFormat = LiveViewIspFusionRequest.FMT_UYVY422,
                flags = 0,
                offX = 0,
                offY = 0,
                fitW = w,
                fitH = h,
                gainR1024 = 1024,
                gainG1024 = 1024,
                gainB1024 = 1024,
                yuvShift = 10,
                ccm = null,
                yuvMat = null,
                lutR = null,
                lutG = null,
                lutB = null,
                peakingThreshold = 0,
                peakingHiY = 0,
                peakingHiCb = 0,
                peakingHiCr = 0,
            )
            val radFrame = LinearRadianceFrame(w, h, red, green, blue, depthMeters = depth, sequence = 1L)
            val outWithCustom = ByteArray(n * 2)
            assertTrue(
                sceneFilter.tryFilterRadianceToIspYuv(radFrame, geom, fusionReq, outWithCustom, null, 1L),
                "Fused GPU tryFilterRadianceToIspYuv must execute custom .spv OpticalFilterElement cleanly",
            )
            assertEquals(1L, sceneFilter.vulkanFusedIspFrames)

            // Compare against fused output without custom filter -> must differ measurably (R boosted, G/B attenuated)
            val baselineFilter = LensOpticalSceneFilter(
                enabled = true,
                profile = profile,
                noiseConfig = CmosNoiseConfig.NOISELESS,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                computeBackendProvider = { gpu },
            )
            val outBaseline = ByteArray(n * 2)
            assertTrue(baselineFilter.tryFilterRadianceToIspYuv(radFrame, geom, fusionReq, outBaseline, null, 1L))
            assertFalse(outWithCustom.contentEquals(outBaseline), "Custom .spv pass must measurably alter fused ISP YUV pixels")

            // Separate image sources can both publish their first frame at sequence 1.
            val dim = LinearRadianceFrame(
                w, h, FloatArray(n) { red[it] / 16f }, FloatArray(n) { green[it] / 16f },
                FloatArray(n) { blue[it] / 16f }, depthMeters = depth, sequence = 1L,
            )
            val outDim = ByteArray(n * 2)
            assertTrue(baselineFilter.tryFilterRadianceToIspYuv(dim, geom, fusionReq, outDim, null, 2L))
            assertFalse(outBaseline.contentEquals(outDim), "Fused GPU reused the old source after a lighting change")
            val outRestored = ByteArray(n * 2)
            assertTrue(baselineFilter.tryFilterRadianceToIspYuv(radFrame, geom, fusionReq, outRestored, null, 3L))
            kotlin.test.assertContentEquals(outBaseline, outRestored)

        }
    }

    @Test
    fun `custom LensOpticalHook spv executes POST_OPTICS_RGB on Vulkan GPU cleanly`() {
        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) return
        loaded.getOrThrow().close()

        VulkanOpticalBackend(
            preference = OpticsBackendPreference.VULKAN,
            allowSoftwareVulkan = true,
        ).use { gpu ->
            if (gpu.activeBackend != ActiveOpticsBackend.VULKAN) return

            val spvBytes = buildTintPushSpirv("main")
            val hookWithSpv = object : LensOpticalHook {
                override fun vulkanSpirvPass(
                    width: Int,
                    height: Int,
                    focusDistanceM: Double,
                    fNumber: Double,
                    focalLengthMm: Double,
                ): CustomSpirvPassSpec = CustomSpirvPassSpec(
                    shaderId = "plugin-lens-post-optics",
                    spirvBytes = spvBytes,
                    workgroupSizeX = 8,
                    workgroupSizeY = 8,
                    uniformData = floatsToLeBytes(0.5f, 0.5f, 0.5f, 1.0f),
                    pushConstants = floatsToLeBytes(1.0f),
                    stage = CustomSpirvStage.POST_OPTICS_RGB,
                    cacheKey = 0x5151L,
                )
                override fun cacheKey(): Long = 0x5151L
            }

            val w = 32
            val h = 24
            val n = w * h
            val red = FloatArray(n) { 0.6f }
            val green = FloatArray(n) { 0.6f }
            val blue = FloatArray(n) { 0.6f }
            val depth = FloatArray(n) { 2.0f }
            val profile = OpticalLensProfile.IDENTITY.copy(focusDistanceM = 2.0, fNumber = 2.8, focalLengthMm = 50.0, referenceFocalLengthMm = 50.0)
            val geom = SensorGeometry(w, h, 16, BayerPattern.RGGB, 3.76)
            val exp = Exposure(0L, 10_000_000L, 100, LensParameters("GF50", 50.0, 2.8, 2.0, 1.0, 0.0))

            val sceneFilter = LensOpticalSceneFilter(
                enabled = true,
                profile = profile,
                noiseConfig = CmosNoiseConfig.NOISELESS,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                computeBackendProvider = { gpu },
            )
            sceneFilter.update(lensOpticalHook = hookWithSpv)

            val radFrame = LinearRadianceFrame(w, h, red, green, blue, depthMeters = depth, sequence = 10L)
            val outHook = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            sceneFilter.filterRadianceFrame(radFrame, exp, geom, outHook, 0L)
            assertEquals(1L, sceneFilter.vulkanSingleSubmitFrames, "LensOpticalHook with valid .spv must execute on Vulkan GPU")

            val baseFilter = LensOpticalSceneFilter(
                enabled = true,
                profile = profile,
                noiseConfig = CmosNoiseConfig.NOISELESS,
                maxBokehRadiusPx = 0,
                borderMode = BorderMode.CLAMP,
                computeBackendProvider = { gpu },
            )
            val outBase = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            baseFilter.filterRadianceFrame(radFrame, exp, geom, outBase, 0L)

            val hookAdu = (outHook.getShort(0).toInt() and 0xFFFF)
            val baseAdu = (outBase.getShort(0).toInt() and 0xFFFF)
            val ratio = baseAdu.toDouble() / hookAdu.coerceAtLeast(1).toDouble()
            assertTrue(ratio in 1.9..2.1, "POST_OPTICS_RGB 0.5x gain must halve Bayer ADU on GPU (base=$baseAdu hook=$hookAdu ratio=$ratio)")
        }
    }

    @Test
    fun `malformed spv bytes emit WARN FALLBACK vulkan and fall back cleanly to CPU without crashing or poisoning GPU`() {
        val loaded = VulkanNativeOpticsBridge.tryLoad()
        if (loaded.isFailure) return
        loaded.getOrThrow().close()

        val warnings = CopyOnWriteArrayList<String>()
        val vkLogger = Logger.getLogger(VulkanOpticalBackend::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.message ?: "")
                }
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        vkLogger.addHandler(handler)
        try {
            VulkanOpticalBackend(
                preference = OpticsBackendPreference.VULKAN,
                allowSoftwareVulkan = true,
            ).use { gpu ->
                if (gpu.activeBackend != ActiveOpticsBackend.VULKAN) return

                val malformedSpv = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 1, 2, 3, 4)
                val badFilter = object : OpticalFilterElement {
                    override val id: String = "buggy-jar-filter"
                    override val displayName: String = "Buggy JAR Filter"
                    override val enabled: Boolean = true
                    override val hasSpatialEffect: Boolean = true
                    override fun transmittanceMultiplier(): Double = 1.0
                    override fun vulkanSpirvPass(width: Int, height: Int): CustomSpirvPassSpec = CustomSpirvPassSpec(
                        shaderId = "buggy-jar-spv",
                        spirvBytes = malformedSpv,
                        stage = CustomSpirvStage.PRE_OPTICS_FILTER,
                    )
                    override fun applySpatialLinearRgb(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: OpticalFilterScratch) {
                        for (i in 0 until width * height) {
                            red[i] *= 0.25f; green[i] *= 0.25f; blue[i] *= 0.25f
                        }
                    }
                    override fun summaryBadge(): String = "Buggy"
                    override fun stateMap(): Map<String, Any?> = emptyMap()
                }
                val badStack = object : OpticalFilterStackPort {
                    override val revision: Long = 1L
                    override val enabled: Boolean = true
                    override fun activeElements(): List<OpticalFilterElement> = listOf(badFilter)
                    override fun totalTransmittance(): Double = 1.0
                    override fun hasActiveSpatialEffects(): Boolean = true
                    override fun activePupilMask(): OpticalPupilMask? = null
                    override fun applySpatialStack(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: OpticalFilterScratch) {
                        badFilter.applySpatialLinearRgb(red, green, blue, width, height, scratch)
                    }
                    override fun stackState(): Map<String, Any?> = emptyMap()
                }

                val profile = OpticalLensProfile.IDENTITY.copy(focusDistanceM = 2.0, fNumber = 2.8, focalLengthMm = 50.0, referenceFocalLengthMm = 50.0)
                val sceneFilter = LensOpticalSceneFilter(
                    enabled = true,
                    profile = profile,
                    noiseConfig = CmosNoiseConfig.NOISELESS,
                    maxBokehRadiusPx = 0,
                    borderMode = BorderMode.CLAMP,
                    computeBackendProvider = { gpu },
                    opticalFilterStackProvider = { badStack },
                )

                // 1. Malformed OpticalFilterElement .spv -> handlesSpatialFilterStack=false + WARN [FALLBACK][vulkan] + GPU stays VULKAN!
                assertFalse(
                    sceneFilter.handlesSpatialFilterStack(badStack),
                    "Malformed .spv filter must fall back cleanly to CPU (handlesSpatialFilterStack=false)",
                )
                assertTrue(
                    warnings.any { it.contains("[FALLBACK][vulkan]") && it.contains("buggy-jar-spv") },
                    "Expected WARN [FALLBACK][vulkan] for malformed .spv, got: $warnings",
                )
                assertEquals(ActiveOpticsBackend.VULKAN, gpu.activeBackend, "Malformed plugin .spv must NEVER poison or demote Vulkan backend")

                // 2. Malformed LensOpticalHook .spv -> cleanly routes to CPU LensOpticalHook without crashing!
                var cpuHookInvoked = false
                val badHook = object : LensOpticalHook {
                    override fun vulkanSpirvPass(
                        width: Int,
                        height: Int,
                        focusDistanceM: Double,
                        fNumber: Double,
                        focalLengthMm: Double,
                    ): CustomSpirvPassSpec = CustomSpirvPassSpec(
                        shaderId = "buggy-hook-spv",
                        spirvBytes = malformedSpv,
                        stage = CustomSpirvStage.POST_OPTICS_RGB,
                    )
                    override fun warpUv(nx: Double, ny: Double, focalLengthMm: Double, focusDistanceM: Double, aperture: Double, outUv: DoubleArray): Boolean {
                        cpuHookInvoked = true
                        outUv[0] = nx
                        outUv[1] = ny
                        return true
                    }
                    override fun cacheKey(): Long = 0xBAD1L
                }
                val hookFilter = LensOpticalSceneFilter(
                    enabled = true,
                    profile = profile,
                    noiseConfig = CmosNoiseConfig.NOISELESS,
                    maxBokehRadiusPx = 0,
                    borderMode = BorderMode.CLAMP,
                    computeBackendProvider = { gpu },
                )
                hookFilter.update(lensOpticalHook = badHook)

                val w = 16; val h = 16; val n = w * h
                val frame = LinearRadianceFrame(w, h, FloatArray(n) { 0.5f }, FloatArray(n) { 0.5f }, FloatArray(n) { 0.5f }, sequence = 20L)
                val geom = SensorGeometry(w, h, 16, BayerPattern.RGGB, 3.76)
                val exp = Exposure(0L, 10_000_000L, 100, LensParameters("GF50", 50.0, 2.8, 2.0, 1.0, 0.0))
                val outBuf = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                hookFilter.filterRadianceFrame(frame, exp, geom, outBuf, 0L)
                assertTrue(cpuHookInvoked, "Malformed LensOpticalHook .spv must fall back to CPU LensOpticalHook.warpUv()")
                assertEquals(ActiveOpticsBackend.VULKAN, gpu.activeBackend, "Vulkan backend must remain healthy after malformed hook .spv")
                assertTrue(
                    warnings.any { it.contains("[FALLBACK][vulkan]") && it.contains("buggy-hook-spv") },
                    "Expected WARN [FALLBACK][vulkan] for malformed hook .spv",
                )
            }
        } finally {
            vkLogger.removeHandler(handler)
        }
    }
}
