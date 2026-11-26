package com.github.nyabanban.fuji.emulator.api

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Built-in entrance-pupil geometry shared by lens apertures and front-of-lens masks. */
class BuiltinOpticalPupilMask(
    override val shape: BokehMaskShape,
    override val angleDeg: Double = 0.0,
    override val scale: Double = 1.0,
) : OpticalPupilMask {
    override val active: Boolean get() = shape != BokehMaskShape.OFF
    private val clampedScale = scale.coerceIn(0.40, 1.0)
    private val angleRad = Math.toRadians(angleDeg)
    private val cosA = cos(angleRad)
    private val sinA = sin(angleRad)

    override val cacheKey: Long =
        ((shape.ordinal.toLong() and 0xFFL) shl 56) xor
            (angleDeg.toRawBits() * 0x9E3779B97F4A7C15UL.toLong()) xor
            (clampedScale.toRawBits() * 0xBF58476D1CE4E5B9UL.toLong())

    override fun pupilWeight(ux: Double, uy: Double): Double {
        if (!active) return 1.0
        val rx = ux * cosA + uy * sinA
        val ry = -ux * sinA + uy * cosA
        return when (shape) {
            BokehMaskShape.OFF -> 1.0
            BokehMaskShape.STAR_5 -> star5Weight(rx, ry, clampedScale)
            BokehMaskShape.HEART -> heartWeight(rx, ry, clampedScale)
            BokehMaskShape.HEXAGON -> hexagonWeight(rx, ry, clampedScale)
            BokehMaskShape.RING -> ringWeight(rx, ry, clampedScale)
            BokehMaskShape.CUSTOM -> 1.0
        }
    }

    companion object {
        private const val STAR_SECTOR = 2.0 * PI / 5.0
        private const val STAR_HALF_SECTOR = PI / 5.0
        private val SIN_STAR_HALF = sin(STAR_HALF_SECTOR)
        private const val HEX_SECTOR = PI / 3.0
        private const val HEX_HALF_SECTOR = PI / 6.0
        private val COS_HEX_HALF = cos(HEX_HALF_SECTOR)
        private const val AA_WIDTH = 0.045

        /**
         * Exact straight-edge polar 5-pointed star polygon inside the unit disk, with one tip
         * pointing straight UP (`uy < 0`, `ux = 0`) at `angleDeg = 0`.
         */
        fun star5Weight(rx: Double, ry: Double, scale: Double): Double {
            val r = hypot(rx, ry)
            val rOut = scale
            val rIn = scale * 0.382 // golden-ratio classic crisp 5-pointed star inner radius
            if (r >= rOut + AA_WIDTH) return 0.0
            if (r <= rIn - AA_WIDTH) return 1.0
            // Shift angle so theta=0 points straight UP (rx=0, ry=-1).
            val theta = atan2(ry, rx) + PI * 0.5
            var mod = theta % STAR_SECTOR
            if (mod < 0.0) mod += STAR_SECTOR
            val phi = if (mod <= STAR_HALF_SECTOR) mod else (STAR_SECTOR - mod)
            val denom = rIn * sin(STAR_HALF_SECTOR - phi) + rOut * sin(phi)
            val edgeR = if (denom > 1e-6) (rOut * rIn * SIN_STAR_HALF) / denom else rIn
            return ((edgeR - r) / AA_WIDTH + 0.5).coerceIn(0.0, 1.0)
        }

        /**
         * Full-size upright analytical heart cutout (`(x^2 + y^2 - 1)^3 - x^2 * y^3 <= 0`)
         * matching the Vulkan pupil shader: top lobes + cleft at `ry < 0`, pointed tip at `ry > 0`.
         */
        fun heartWeight(rx: Double, ry: Double, scale: Double): Double {
            val invS = 1.0 / scale
            val x = rx * (1.28 * invS)
            val y = -ry * (1.34 * invS) + 0.12
            val r2 = x * x + y * y
            if (r2 > 2.4) return 0.0
            val a = r2 - 1.0
            val f = a * a * a - x * x * y * y * y
            return ((-f) / 0.05 + 0.5).coerceIn(0.0, 1.0)
        }

        /** Regular hexagonal aperture cutout with crisp flat edges. */
        fun hexagonWeight(rx: Double, ry: Double, scale: Double): Double {
            val r = hypot(rx, ry)
            if (r >= scale + AA_WIDTH) return 0.0
            val theta = atan2(ry, rx) + PI * 0.5
            var mod = theta % HEX_SECTOR
            if (mod < 0.0) mod += HEX_SECTOR
            val local = abs(mod - HEX_HALF_SECTOR)
            val edgeR = scale * COS_HEX_HALF / cos(local).coerceAtLeast(0.1)
            return ((edgeR - r) / AA_WIDTH + 0.5).coerceIn(0.0, 1.0)
        }

        /** Catadioptric / mirror-lens donut ring cutout. */
        fun ringWeight(rx: Double, ry: Double, scale: Double): Double {
            val r = hypot(rx, ry)
            val rOut = scale
            val rIn = scale * 0.50
            val outer = ((rOut - r) / AA_WIDTH + 0.5).coerceIn(0.0, 1.0)
            val inner = ((r - rIn) / AA_WIDTH + 0.5).coerceIn(0.0, 1.0)
            return outer * inner
        }
    }
}
