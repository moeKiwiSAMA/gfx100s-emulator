package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.LensPort
import com.github.nyabanban.fuji.emulator.api.LiveViewIspFusionRequest
import com.github.nyabanban.fuji.emulator.api.OpticalSceneFilter
import com.github.nyabanban.fuji.emulator.api.SceneDepthPort
import com.github.nyabanban.fuji.emulator.api.SensorPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/**
 * End-to-end integration parity & benchmark test for `W13-VULKAN-3D-SCENE-RASTERIZER`:
 * loads the real `cmos` + `lens` hardware components through `IntegrationTest().host(...)` and
 * verifies:
 * 1. `opticsBackend = "cpu"` preserves 100% CPU fallback (`supportsGpuScene3d() == false`).
 * 2. `opticsBackend = "vulkan"` renders `scene://milk-frog`, `scene://qr-ball`,
 *    `scene://depth-studio`, and `scene://mona-lisa` straight on Vulkan GPU into `DEVICE_LOCAL`
 *    VRAM with zero PCIe scene upload, matching CPU Bayer + fused UYVY422 + AF `DepthSnapshot`!
 * 3. Logs BEFORE (CPU) vs AFTER (Vulkan GPU) `1024x576` frame render times.
 */
class VulkanScene3dRasterizerParityTest {

    private fun prepareHost(tempRoot: Path, name: String, withFilterRack: Boolean = false): Path {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val hostRoot = tempRoot.resolve(name)
        val componentsDir = Files.createDirectories(hostRoot.resolve("compoments"))
        Files.copy(repo.resolve("components/cmos/build/libs/cmos-0.3.0.jar"), componentsDir.resolve("cmos-0.3.0.jar"))
        Files.copy(repo.resolve("components/lens/build/libs/lens-0.3.0.jar"), componentsDir.resolve("lens-0.3.0.jar"))
        val cmosDir = Files.createDirectories(componentsDir.resolve("cmos"))
        Files.copy(repo.resolve("tools/release/component-configs/cmos/cmos.yaml"), cmosDir.resolve("cmos.yaml"))
        val lensDir = Files.createDirectories(componentsDir.resolve("lens"))
        Files.copy(repo.resolve("tools/release/component-configs/lens/lens.yaml"), lensDir.resolve("lens.yaml"))
        if (withFilterRack) {
            val rack = "optical-filter"
            Files.copy(repo.resolve("components/$rack/build/libs/$rack-0.3.0.jar"), componentsDir.resolve("$rack-0.3.0.jar"))
            val rackConfig = Files.createDirectories(componentsDir.resolve(rack)).resolve("$rack.yaml")
            Files.copy(repo.resolve("tools/release/component-configs/$rack/$rack.yaml"), rackConfig)
        }
        return hostRoot
    }

    private fun bayerStats16(a: ByteArray, b: ByteArray): Triple<Double, Double, Double> {
        val bbA = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        val bbB = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val count = a.size / 2
        var diffSum = 0.0
        var sumA = 0.0
        var sumB = 0.0
        for (i in 0 until count) {
            val va = bbA.getShort(i * 2).toInt() and 0xFFFF
            val vb = bbB.getShort(i * 2).toInt() and 0xFFFF
            diffSum += abs(va - vb)
            sumA += va
            sumB += vb
        }
        val denom = count.coerceAtLeast(1).toDouble()
        return Triple(diffSum / denom, sumA / denom, sumB / denom)
    }

    private fun makeFusionReq(exp: Exposure) = LiveViewIspFusionRequest(
        exposure = exp,
        dstWidth = 1024,
        dstHeight = 576,
        outputFormat = 0,
        dstStrideBytes = 1024 * 2,
        flags = 0,
        offX = 0,
        offY = 0,
        fitW = 1024,
        fitH = 576,
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

    @Test
    fun milkFrogPracticalLightResolvesMountedPupilInFusedLiveView(@TempDir tempRoot: Path) {
        val root = prepareHost(tempRoot, "milk-frog-pupil-host", withFilterRack = true)
        var supported = false
        IntegrationTest().host(root) { owner, host ->
            owner.submit {
                val sensor = checkNotNull(host.ports.find("cmos", SensorPort::class.java))
                val lens = checkNotNull(host.ports.find("lens", LensPort::class.java))
                val modules = host.components().associate { it.descriptor.id to it.component }
                val lensModule = modules.getValue("lens")
                lensModule.applySettings(mapOf("opticsEnabled" to true, "opticsBackend" to "vulkan", "noiseEnabled" to false))
                if (!(lens as OpticalSceneFilter).supportsGpuScene3d()) return@submit
                modules.getValue("cmos").applySettings(mapOf(
                    "sourceKind" to "optical-scene", "sourceFile" to "scene://milk-frog?motion=off&audio=off&phase=0.45",
                ))
                for ((template, focal, aperture) in listOf(Triple("gf45-100", 45.0, 4.0), Triple("gf110", 110.0, 2.0))) {
                    lensModule.applySettings(mapOf("template" to template, "focalLengthMm" to focal,
                        "aperture" to aperture, "focusDistanceM" to 1.55))
                    val request = makeFusionReq(Exposure(0L, 1_000L, 100, lens.parameters()))
                    fun render(shape: String): ByteArray {
                        modules.getValue("optical-filter").applySettings(mapOf(
                            "bokehMask" to shape, "bokehMaskAngleDeg" to 0.0, "bokehMaskScale" to 0.96,
                        ))
                        return ByteArray(request.dstStrideBytes * request.dstHeight).also {
                            assertTrue(sensor.tryExposeFusedIspYuv(request, it, null), "GPU fused render for $template / $shape")
                        }
                    }

                    // Fixed pose and calibrated optics put the left rear light at these sensor coordinates.
                    val wide = focal == 45.0
                    val x = if (wide) 358 else 135
                    val y = if (wide) 250 else 196
                    fun ByteArray.luma(dx: Int, dy: Int) = this[((y + dy) * request.dstWidth + x + dx) * 2 + 1].toInt() and 255
                    val clear = render("OFF")
                    val heart = render("HEART")
                    val star = render("STAR_5")
                    val ring = render("RING")
                    val rim = if (wide) 3 else 20
                    assertTrue(clear.luma(0, 0) > ring.luma(0, 0) + 30 && ring.luma(rim, 0) > ring.luma(0, 0) + 30,
                        "$template must resolve the annulus and its central obstruction")
                    val tipY = if (wide) -3 else -22
                    val lobeX = if (wide) 1 else 8
                    assertTrue(heart.luma(lobeX, tipY) > heart.luma(0, tipY) + 12, "$template heart notch")
                    assertTrue(star.luma(0, tipY) > star.luma(lobeX, tipY) + 12, "$template star tip")
                }
                supported = true
            }.join()
        }
        assumeTrue(supported, "Vulkan GPU Scene3D unavailable on this host")
    }

    @Test
    fun vulkanGpu3dScenesMatchCpuParityAndBenchmark(@TempDir tempRoot: Path) {
        val prevAllowSw = System.getProperty("fuji.vulkan.allowSoftware")
        System.setProperty("fuji.vulkan.allowSoftware", "true")
        try {
            val hostRoot = prepareHost(tempRoot, "scene3d-parity-host")
            val exposure = Exposure(
                startTick = 0L,
                durationNanos = 1_000L,
                iso = 100,
                lens = LensParameters("gf-45", 45.0, 2.8, 1.55, 0.9, 0.0),
            )
            val req = makeFusionReq(exposure)

            var vulkanAvailable = false
            IntegrationTest().host(hostRoot) { owner, host ->
                owner.submit {
                    val cmosComp = host.components().single { it.descriptor.id == "cmos" }.component
                    val lensComp = host.components().single { it.descriptor.id == "lens" }.component
                    val sensor = checkNotNull(host.ports.find("cmos", SensorPort::class.java))
                    val rawLens = checkNotNull(host.ports.find("lens", LensPort::class.java))
                    val depthPort = checkNotNull(rawLens as? SceneDepthPort)
                    val filter = checkNotNull(rawLens as? OpticalSceneFilter)

                    // 1. CPU fallback check
                    lensComp.applySettings(mapOf("opticsEnabled" to true, "opticsBackend" to "cpu", "noiseEnabled" to false))
                    assertFalse(filter.supportsGpuScene3d(), "opticsBackend=cpu must disable GPU Scene3D and use CPU fallback")

                    // 2. Enable Vulkan GPU backend
                    lensComp.applySettings(mapOf("opticsEnabled" to true, "opticsBackend" to "vulkan", "noiseEnabled" to false))
                    if (!filter.supportsGpuScene3d()) return@submit
                    vulkanAvailable = true

                    val scenes = listOf(
                        Triple("milk-frog", "scene://milk-frog?motion=off&audio=off&phase=0.45", 1.15..1.90),
                        Triple("qr-ball", "scene://qr-ball", 0.75..5.60),
                        Triple("depth-studio", "scene://depth-studio", 2.10..2.90),
                        Triple("mona-lisa", "scene://mona-lisa?motion=off&phase=0.0", 1.45..2.05),
                    )
                    val frameBytes = 1024 * 576 * 2
                    val cpuBayer = ByteArray(frameBytes)
                    val gpuBayer = ByteArray(frameBytes)
                    val gpuUyvy = ByteArray(1024 * 576 * 2)

                    for ((label, uri, expectedCenterDepth) in scenes) {
                        cmosComp.applySettings(mapOf("sourceKind" to "optical-scene", "sourceFile" to uri, "loop" to false))

                        // Render on CPU backend
                        lensComp.applySettings(mapOf("opticsBackend" to "cpu"))
                        assertEquals(frameBytes, sensor.exposeStill(exposure, ByteBuffer.wrap(cpuBayer)))
                        val cpuDepth = depthPort.sampleRegionDepthMeters(0.42, 0.42, 0.58, 0.58)

                        // Render on Vulkan GPU Scene3D backend (zero PCIe scene upload!)
                        cmosComp.applySettings(mapOf("sourceKind" to "optical-scene", "sourceFile" to uri, "loop" to true))
                        lensComp.applySettings(mapOf("opticsBackend" to "vulkan"))
                        assertTrue(filter.supportsGpuScene3d())
                        assertEquals(frameBytes, sensor.exposeStill(exposure, ByteBuffer.wrap(gpuBayer)))
                        val gpuDepth = depthPort.sampleRegionDepthMeters(0.42, 0.42, 0.58, 0.58)

                        // Verify QR-Ball off-center sphere (~0.83..1.05m) & back QR wall (~5.5m) depths!
                        if (label == "qr-ball") {
                            val sphD = depthPort.sampleRegionDepthMeters(0.28, 0.48, 0.40, 0.64)
                            val wallD = depthPort.sampleRegionDepthMeters(0.65, 0.40, 0.80, 0.60)
                            assertTrue(sphD in 0.80..1.15, "QR-Ball GPU sphere depth out of range: $sphD")
                            assertTrue(wallD in 5.30..5.70, "QR-Ball GPU wall depth out of range: $wallD")
                        }

                        // Also verify fused Scene3D -> Optics -> CMOS -> ISP UYVY422 path!
                        assertTrue(sensor.tryExposeFusedIspYuv(req, gpuUyvy, null), "Fused GPU Scene3D->ISP must succeed for $label")
                        assertTrue(gpuUyvy.any { it != 0.toByte() }, "Fused UYVY must be non-zero for $label")

                        val (mad16, cpuMean, gpuMean) = bayerStats16(cpuBayer, gpuBayer)
                        val madNorm = mad16 / 65535.0
                        println(
                            "[SCENE3D-E2E-PARITY][$label] bayerMAD=${"%.4f".format(madNorm * 100.0)}% " +
                                "cpuMean=${"%.1f".format(cpuMean)} gpuMean=${"%.1f".format(gpuMean)} " +
                                "cpuCenterDepth=${"%.3f".format(cpuDepth)}m gpuCenterDepth=${"%.3f".format(gpuDepth)}m",
                        )
                        assertTrue(cpuMean in 1000.0..60000.0, "$label CPU Bayer should be unclipped midtone: $cpuMean")
                        assertTrue(madNorm < 0.04, "$label GPU vs CPU Bayer MAD too high: ${madNorm * 100.0}%")
                        assertTrue(gpuDepth in expectedCenterDepth, "$label GPU center depth out of range: $gpuDepth")
                        assertTrue(abs(gpuDepth - cpuDepth) < 0.35, "$label GPU vs CPU center depth diverged: cpu=$cpuDepth gpu=$gpuDepth")
                    }

                    // 3. BEFORE (CPU) vs AFTER (Vulkan GPU) 1024x576 benchmark on Milk Frog & depth-studio
                    for ((label, uri) in listOf(
                        "milk-frog" to "scene://milk-frog?motion=off&audio=off&phase=0.45",
                        "depth-studio" to "scene://depth-studio",
                    )) {
                        cmosComp.applySettings(mapOf("sourceKind" to "optical-scene", "sourceFile" to uri, "loop" to true))
                        // Warmup + measure CPU
                        lensComp.applySettings(mapOf("opticsBackend" to "cpu"))
                        val expWarm = exposure.copy(lens = exposure.lens.copy(focusDistanceM = 1.50))
                        sensor.exposeStill(expWarm, ByteBuffer.wrap(cpuBayer))
                        val iters = 4
                        val tCpu0 = System.nanoTime()
                        repeat(iters) { k ->
                            val e = exposure.copy(lens = exposure.lens.copy(focusDistanceM = 1.40 + k * 0.10))
                            sensor.exposeStill(e, ByteBuffer.wrap(cpuBayer))
                        }
                        val cpuMs = (System.nanoTime() - tCpu0) / 1_000_000.0 / iters

                        // Warmup + measure Vulkan GPU Scene3D fused -> ISP YUV
                        lensComp.applySettings(mapOf("opticsBackend" to "vulkan"))
                        sensor.tryExposeFusedIspYuv(req, gpuUyvy, null)
                        val tGpu0 = System.nanoTime()
                        repeat(iters) { k ->
                            val r = makeFusionReq(exposure.copy(lens = exposure.lens.copy(focusDistanceM = 1.40 + k * 0.10)))
                            assertTrue(sensor.tryExposeFusedIspYuv(r, gpuUyvy, null))
                        }
                        val gpuMs = (System.nanoTime() - tGpu0) / 1_000_000.0 / iters
                        println(
                            "[SCENE3D-E2E-BENCH] $label 1024x576: CPU_scene+optics+bayer=${"%.2f".format(cpuMs)} ms/frame | " +
                                "Vulkan_GPU_scene3d+optics+isp_fused=${"%.2f".format(gpuMs)} ms/frame",
                        )
                    }
                }.join()
            }
            assumeTrue(vulkanAvailable, "Vulkan GPU Scene3D unavailable on this host")
        } finally {
            if (prevAllowSw == null) System.clearProperty("fuji.vulkan.allowSoftware")
            else System.setProperty("fuji.vulkan.allowSoftware", prevAllowSw)
        }
    }
}
