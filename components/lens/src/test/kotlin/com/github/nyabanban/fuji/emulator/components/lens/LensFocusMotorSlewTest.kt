package com.github.nyabanban.fuji.emulator.components.lens

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class LensFocusMotorSlewTest {
    private class Harness {
        var nowTick: Long = 0L
        lateinit var lensPort: LensPort
        val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-lens-focus-slew-test")
            override val clock = EmulationClock { nowTick }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    if (name == "lens") lensPort = port as LensPort
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

    private fun makeFusionRequest(lens: LensParameters): LiveViewIspFusionRequest = LiveViewIspFusionRequest(
        exposure = Exposure(0L, 16_666_667L, 100, lens),
        dstWidth = 64,
        dstHeight = 48,
        dstStrideBytes = 128,
        outputFormat = LiveViewIspFusionRequest.FMT_UYVY422,
        flags = 0,
        offX = 0,
        offY = 0,
        fitW = 64,
        fitH = 48,
        gainR1024 = 1024,
        gainG1024 = 1024,
        gainB1024 = 1024,
        yuvShift = 0,
    )

    private fun makeDummyQrBallSpec(): GpuScene3dRenderSpec.QrBall = GpuScene3dRenderSpec.QrBall(
        width = 64,
        height = 48,
        pose = CameraPose3D.IDENTITY,
        sceneKey = 1L,
        sequence = 1L,
        qrKey = 1L,
        qrRowBits32 = IntArray(21),
        qrModules = 21,
        quietZone = 4,
        totalQrModules = 29,
        halfW = 32f,
        halfH = 24f,
        focal = 50f,
        ox = 0f,
        oy = 0f,
        oz = 0f,
        r00 = 1f,
        r01 = 0f,
        r02 = 0f,
        r10 = 0f,
        r11 = 1f,
        r12 = 0f,
        r20 = 0f,
        r21 = 0f,
        r22 = 1f,
        wallZ = 5.5f,
    )

    @Test
    fun focusTargetDistanceMSlewsProgressivelyOnMotorizedLensAndUpdatesOpticalVersion() {
        val factory = LensFactory()
        val h = Harness()
        val lens = factory.create(
            h.context,
            mapOf(
                "template" to "gf45-100",
                "opticsEnabled" to true,
                "opticsBackend" to "cpu",
                "focusDistanceM" to 10.0,
                "focusSweepSeconds" to 0.5,
                "focusMotorInstant" to false,
            ),
        ) as StatefulComponent
        val modular = h.lensPort as ModularLensPort
        val filter = h.lensPort as OpticalSceneFilter

        val initialDist = h.lensPort.parameters().focusDistanceM
        assertEquals(10.0, initialDist, 1e-6)
        assertFalse(modular.focusMotorBusy)
        val idleVer1 = filter.opticalStateVersion()
        val idleVer2 = filter.opticalStateVersion()
        assertEquals(idleVer1, idleVer2, "Idle opticalStateVersion must remain stable for caching")

        // Commanding focusTargetDistanceM starts a physical slew without snapping focusDistanceM immediately
        lens.applySettings(mapOf("focusTargetDistanceM" to 0.8))
        assertTrue(modular.focusMotorBusy, "Motor must report busy after focusTargetDistanceM command")
        assertEquals(10.0, h.lensPort.parameters().focusDistanceM, 1e-6, "focusDistanceM must start from current position")
        assertEquals(0.8, (lens.state()["focusTargetDistanceM"] as Number).toDouble(), 1e-6)
        assertEquals(true, lens.state()["focusMotorActive"])

        // Advance 100ms (10_000_000 ticks @ 100 MHz) -> intermediate focus position & bumped opticalStateVersion
        h.nowTick += 10_000_000L
        val midVer1 = filter.opticalStateVersion()
        val midDist1 = h.lensPort.parameters().focusDistanceM
        val midEnc1 = modular.focusEncoderPosition
        assertTrue(midDist1 < 9.5 && midDist1 > 0.9, "Expected intermediate distance after 100ms, got $midDist1")
        assertTrue(midEnc1 > 2_000 && midEnc1 < 28_000, "Expected intermediate encoder during slew, got $midEnc1")
        assertNotEquals(idleVer1, midVer1, "opticalStateVersion must change while motor slews")
        assertTrue(modular.focusMotorBusy, "Motor must remain busy mid-slew")

        // Advance another 100ms -> further progress toward 0.8m & new opticalStateVersion
        h.nowTick += 10_000_000L
        val midVer2 = filter.opticalStateVersion()
        val midDist2 = h.lensPort.parameters().focusDistanceM
        val midEnc2 = modular.focusEncoderPosition
        assertTrue(midDist2 < midDist1, "Focus distance must move monotonically toward near target: $midDist1 -> $midDist2")
        assertTrue(midEnc2 > midEnc1, "Focus encoder must increase monotonically toward near target: $midEnc1 -> $midEnc2")
        assertNotEquals(midVer1, midVer2, "opticalStateVersion must bump on each slew step")

        // Advance another 500ms -> reaches target 0.8m and clears busy
        h.nowTick += 50_000_000L
        assertFalse(modular.focusMotorBusy, "Motor must settle once full sweep time has elapsed")
        assertEquals(0.8, h.lensPort.parameters().focusDistanceM, 1e-4)
        val settledVer1 = filter.opticalStateVersion()
        val settledVer2 = filter.opticalStateVersion()
        assertEquals(settledVer1, settledVer2, "Settled opticalStateVersion must stabilize again for caching")
        lens.close()
    }

    @Test
    fun fusedIspPathsAdvanceFocusMotorByFallbackFrameStepEvenWithFrozenClock() {
        val factory = LensFactory()
        val h = Harness()
        val lens = factory.create(
            h.context,
            mapOf(
                "template" to "gf45-100",
                "opticsEnabled" to true,
                "opticsBackend" to "cpu",
                "focusDistanceM" to 8.0,
                "focusSweepSeconds" to 0.35,
                "focusMotorInstant" to false,
            ),
        ) as StatefulComponent
        val filter = h.lensPort as OpticalSceneFilter
        val geom = SensorGeometry(64, 48, 16, BayerPattern.RGGB, 3.76)
        val count = 64 * 48
        val frame = LinearRadianceFrame(64, 48, FloatArray(count) { 0.5f }, FloatArray(count) { 0.5f }, FloatArray(count) { 0.5f })
        val yOut = ByteArray(64 * 48 * 2)

        // 1. tryFilterRadianceToIspYuv must advance focus motor before opticalFilter call
        lens.applySettings(mapOf("focusTargetDistanceM" to 0.8))
        val beforeRadDist = h.lensPort.parameters().focusDistanceM
        filter.tryFilterRadianceToIspYuv(frame, geom, makeFusionRequest(h.lensPort.parameters()), yOut, null, 0L)
        val afterRadDist1 = h.lensPort.parameters().focusDistanceM
        filter.tryFilterRadianceToIspYuv(frame, geom, makeFusionRequest(h.lensPort.parameters()), yOut, null, 1L)
        val afterRadDist2 = h.lensPort.parameters().focusDistanceM
        assertTrue(afterRadDist1 < beforeRadDist, "tryFilterRadianceToIspYuv must advance focus motor ($beforeRadDist -> $afterRadDist1)")
        assertTrue(afterRadDist2 < afterRadDist1, "Repeated tryFilterRadianceToIspYuv calls must keep advancing motor ($afterRadDist1 -> $afterRadDist2)")

        // 2. tryFilterGpuScene3dToIspYuv must also advance focus motor before opticalFilter call
        val spec = makeDummyQrBallSpec()
        filter.tryFilterGpuScene3dToIspYuv(spec, geom, makeFusionRequest(h.lensPort.parameters()), yOut, null, 2L)
        val afterGpu3dDist = h.lensPort.parameters().focusDistanceM
        assertTrue(afterGpu3dDist < afterRadDist2, "tryFilterGpuScene3dToIspYuv must advance focus motor ($afterRadDist2 -> $afterGpu3dDist)")

        // 3. CPU filterRadianceFrame continues advancing to completion
        val bayerDst = ByteBuffer.allocate(count * 2)
        val exp = Exposure(0L, 16_666_667L, 100, h.lensPort.parameters())
        repeat(40) { idx ->
            bayerDst.clear()
            filter.filterRadianceFrame(frame, exp, geom, bayerDst, idx.toLong() + 3L)
        }
        assertEquals(0.8, h.lensPort.parameters().focusDistanceM, 1e-4)
        assertEquals(false, lens.state()["focusMotorActive"])
        lens.close()
    }

    @Test
    fun manualLensFocusMotorInstantAndDirectFocusDistanceMSnapImmediately() {
        val factory = LensFactory()
        val h = Harness()
        val lens = factory.create(
            h.context,
            mapOf(
                "template" to "gf45-100",
                "opticsEnabled" to false,
                "focusDistanceM" to 5.0,
                "focusSweepSeconds" to 0.5,
                "focusMotorInstant" to true,
            ),
        ) as StatefulComponent
        val modular = h.lensPort as ModularLensPort

        // focusMotorInstant = true -> snaps immediately on focusTargetDistanceM
        lens.applySettings(mapOf("focusTargetDistanceM" to 1.2))
        assertFalse(modular.focusMotorBusy)
        assertEquals(1.2, h.lensPort.parameters().focusDistanceM, 1e-6)

        // Direct focusDistanceM snaps immediately even when focusMotorInstant = false
        lens.applySettings(mapOf("focusMotorInstant" to false))
        lens.applySettings(mapOf("focusDistanceM" to 6.0))
        assertFalse(modular.focusMotorBusy)
        assertEquals(6.0, h.lensPort.parameters().focusDistanceM, 1e-6)
        assertEquals(6.0, (lens.state()["focusTargetDistanceM"] as Number).toDouble(), 1e-6)

        // MANUAL dumb adapter -> snaps immediately on focusTargetDistanceM
        lens.applySettings(mapOf("template" to "adapter-m42-dumb"))
        lens.applySettings(mapOf("focusTargetDistanceM" to 1.5))
        assertFalse(modular.focusMotorBusy)
        assertEquals(1.5, h.lensPort.parameters().focusDistanceM, 1e-6)
        lens.close()
    }
}
