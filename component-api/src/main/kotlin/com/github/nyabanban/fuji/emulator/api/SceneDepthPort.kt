package com.github.nyabanban.fuji.emulator.api

/**
 * Optional read-only metric scene depth observation port (`lens.depth`) allowing hardware AF
 * engines (e.g. `soc-isp.pdaf` at `0xFFF71000`) to sample 3D subject depth in meters inside each
 * normalized sensor AF ROI without adding direct component dependencies on `cmos` or `lens`.
 */
interface SceneDepthPort {
    companion object {
        const val PORT_NAME = "lens.depth"
    }

    /** Fallback planar subject distance in meters when no 3D per-pixel depth buffer is active. */
    fun fallbackSubjectDistanceM(): Double

    /**
     * Returns the representative (median) metric subject depth in meters inside the normalized
     * sensor rectangle `[normX0, normX1) x [normY0, normY1)` (clamped to `[0.0, 1.0]`), or
     * [fallbackSubjectDistanceM] when the active scene has no 3D depth map.
     */
    fun sampleRegionDepthMeters(normX0: Double, normY0: Double, normX1: Double, normY1: Double): Double
}
