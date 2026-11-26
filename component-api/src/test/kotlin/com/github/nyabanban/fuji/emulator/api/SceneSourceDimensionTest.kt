package com.github.nyabanban.fuji.emulator.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SceneSourceDimensionTest {
    @Test
    fun cameraPose3DIdentityQuantizationAndOrientationIbisSampling() {
        assertTrue(CameraPose3D.IDENTITY.isIdentity)
        assertFalse(CameraPose3D.IDENTITY.hasViewTransform)
        assertEquals(CameraPose3D.IDENTITY, CameraPose3D.fromOrientationAndIbis(null, null, 0L))

        val disabledOrientation = object : CameraOrientationPort {
            override val accelEnabled: Boolean = false
            override val orientationPreset: String = "portrait-cw"
            override val rollDeg: Double = 90.0
            override val pitchDeg: Double = 25.0
            override val shakeAmpDeg: Double = 1.0
            override val lcdTiltDeg: Double = 0.0
            override fun bodyGravityG(tick: Long) = CameraGravityVector(1.0, 0.0, 0.0)
            override fun lcdGravityG(tick: Long) = CameraGravityVector(1.0, 0.0, 0.0)
            override fun handShakeDeltaDeg(tick: Long) = Triple(0.5, -0.2, 0.3)
        }
        assertEquals(CameraPose3D.IDENTITY, CameraPose3D.fromOrientationAndIbis(disabledOrientation, null, 100L))

        val activeOrientation = object : CameraOrientationPort {
            override val accelEnabled: Boolean = true
            override val orientationPreset: String = "portrait-cw"
            override val rollDeg: Double = 90.0
            override val pitchDeg: Double = 15.0
            override val shakeAmpDeg: Double = 0.5
            override val lcdTiltDeg: Double = 0.0
            override fun bodyGravityG(tick: Long) = CameraGravityVector(1.0, 0.0, 0.0)
            override fun lcdGravityG(tick: Long) = CameraGravityVector(1.0, 0.0, 0.0)
            override fun handShakeDeltaDeg(tick: Long) = Triple(0.25, -0.5, 0.75)
        }
        val activeIbis = object : IbisShiftPort {
            override val ibisMode: Int = IbisShiftPort.MODE_CONTINUOUS
            override val ibisModeName: String = "CONTINUOUS"
            override val stageLocked: Boolean = false
            override val stabilizationState: Int = 1
            override val stabilizationGain: Int = 100
            override val focalLengthMm: Double = 63.0
            override val pixelShiftTargetX: Int = 0
            override val pixelShiftTargetY: Int = 0
            override val shiftXUm: Double = 18.5
            override val shiftYUm: Double = -12.0
            override val rollDeg: Double = -0.35
            override fun sampleShiftPixels(pixelPitchUm: Double, tick: Long) = Triple(4.9, -3.2, -0.006)
        }

        val sampled = CameraPose3D.fromOrientationAndIbis(activeOrientation, activeIbis, 42L)
        assertFalse(sampled.isIdentity)
        assertTrue(sampled.hasViewTransform)
        // Active unlocked CONTINUOUS IBIS cancels 90% of handheld angular tremor (residual 0.10x).
        assertEquals(90.025, sampled.rollDeg, 1e-9)
        assertEquals(14.95, sampled.pitchDeg, 1e-9)
        assertEquals(0.075, sampled.yawDeg, 1e-9)
        assertEquals(18.5, sampled.ibisShiftXUm, 1e-9)
        assertEquals(-12.0, sampled.ibisShiftYUm, 1e-9)
        assertEquals(-0.35, sampled.ibisRollDeg, 1e-9)
        assertEquals(CameraPose3D.DEFAULT_FOCAL_LENGTH_MM, sampled.focalLengthMm, 1e-9)

        // When IBIS is OFF, locked, or absent, 100% of handheld angular tremor passes through.
        val offIbis = object : IbisShiftPort by activeIbis {
            override val ibisMode: Int = IbisShiftPort.MODE_OFF
            override val ibisModeName: String = "OFF"
            override val stageLocked: Boolean = true
        }
        val sampledOff = CameraPose3D.fromOrientationAndIbis(activeOrientation, offIbis, 42L)
        assertEquals(90.25, sampledOff.rollDeg, 1e-9)
        assertEquals(14.5, sampledOff.pitchDeg, 1e-9)
        assertEquals(0.75, sampledOff.yawDeg, 1e-9)
        val sampledNoIbis = CameraPose3D.fromOrientationAndIbis(activeOrientation, null, 42L)
        assertEquals(90.25, sampledNoIbis.rollDeg, 1e-9)
        assertEquals(14.5, sampledNoIbis.pitchDeg, 1e-9)
        assertEquals(0.75, sampledNoIbis.yawDeg, 1e-9)

        // Optical focal length differing from canonical 45 mm triggers hasViewTransform & unique quantizedViewKey.
        val widePose = CameraPose3D.fromOrientationAndIbis(null, null, 0L, focalLengthMm = 20.0)
        val telePose = CameraPose3D.fromOrientationAndIbis(null, null, 0L, focalLengthMm = 110.0)
        assertTrue(widePose.hasViewTransform)
        assertTrue(telePose.hasViewTransform)
        assertEquals(20.0, widePose.focalLengthMm, 1e-9)
        assertEquals(110.0, telePose.focalLengthMm, 1e-9)
        assertNotEquals(CameraPose3D.IDENTITY.quantizedViewKey(), widePose.quantizedViewKey())
        assertNotEquals(widePose.quantizedViewKey(), telePose.quantizedViewKey())

        // Sub-quantization jitter shares view key; meaningful tilt changes view key.
        val tinyJitter = sampled.copy(rollDeg = sampled.rollDeg + 0.005)
        val realTilt = sampled.copy(pitchDeg = sampled.pitchDeg + 5.0)
        assertEquals(sampled.quantizedViewKey(), tinyJitter.quantizedViewKey())
        assertNotEquals(sampled.quantizedViewKey(), realTilt.quantizedViewKey())
        assertNotEquals(sampled.quantizedFullKey(), sampled.copy(ibisShiftXUm = 40.0).quantizedFullKey())

        assertFailsWith<IllegalArgumentException> { CameraPose3D(rollDeg = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { CameraPose3D(focalLengthMm = 0.0) }
    }

    @Test
    fun linearRadianceFrameValidatesDimensionsAndDepthLength() {
        val frame2d = LinearRadianceFrame(
            width = 4,
            height = 2,
            red = FloatArray(8),
            green = FloatArray(8),
            blue = FloatArray(8),
            dimension = SceneDimension.DIM_2D,
            virtualSubjectDistanceM = 2.5,
        )
        assertEquals(SceneDimension.DIM_2D, frame2d.dimension)
        assertEquals(8, frame2d.pixelCount)
        assertEquals(2.5, frame2d.virtualSubjectDistanceM)

        assertFailsWith<IllegalArgumentException> {
            LinearRadianceFrame(4, 2, FloatArray(8), FloatArray(8), FloatArray(8), depthMeters = FloatArray(4))
        }
        assertFailsWith<IllegalArgumentException> {
            LinearRadianceFrame(4, 2, FloatArray(8), FloatArray(8), FloatArray(8), virtualSubjectDistanceM = 0.0)
        }
    }
}
