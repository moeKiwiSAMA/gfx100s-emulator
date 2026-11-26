/*
 * fuji_isp_simd.h - Native multi-threaded AVX-512 / AVX2+FMA / SSE2 / Scalar
 * CPU ISP Demosaic (+ WB Gain + 3x3 CCM + 16x16x16 3D FilmSim LUT + Tone LUT
 * + UYVY422/YYUV422/NV16/NV12 + 5-tap HPF Focus Peaking + 256-bin YRGB
 * Histogram + AF Contrast) and Heavy Spatial Optical Filter Kernels
 * (Black Mist, UV Cheap/Ghost/Glare/Softness, Star Cross-Screen).
 *
 * Zero external runtime dependencies:
 * - Linux:   glibc + libpthread + libm
 * - Windows: KERNEL32.dll + msvcrt.dll ONLY (native Win32 threads/condvars)
 */

#ifndef FUJI_ISP_SIMD_H
#define FUJI_ISP_SIMD_H

#include <stdint.h>

#if defined(_WIN32) || defined(__CYGWIN__)
#define FUJI_ISP_SIMD_EXPORT __declspec(dllexport)
#else
#define FUJI_ISP_SIMD_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define FUJI_ISP_SIMD_ABI_VERSION 1

#define FUJI_ISP_SIMD_SCALAR 0
#define FUJI_ISP_SIMD_SSE2   1
#define FUJI_ISP_SIMD_AVX2   2
#define FUJI_ISP_SIMD_AVX512 3

/* Bitmask values for cfg[15] (flags) in fuji_isp_demosaic_yuv_mt */
#define FUJI_ISP_FLAG_HAS_CCM     (1 << 0)
#define FUJI_ISP_FLAG_HAS_YUV_MAT (1 << 1)
#define FUJI_ISP_FLAG_MONO_MODE   (1 << 2)
#define FUJI_ISP_FLAG_PEAKING     (1 << 3)
#define FUJI_ISP_FLAG_HAS_LUT3D   (1 << 4)
#define FUJI_ISP_FLAG_YYUV422     (1 << 5)
#define FUJI_ISP_FLAG_AF_CONTRAST (1 << 6)

/* Returns FUJI_ISP_SIMD_ABI_VERSION (1). */
FUJI_ISP_SIMD_EXPORT int32_t fuji_isp_simd_abi_version(void);

/* Returns active runtime SIMD tier (0=Scalar, 1=SSE2, 2=AVX2, 3=AVX-512). */
FUJI_ISP_SIMD_EXPORT int32_t fuji_isp_simd_level(void);

/* Returns static NUL-terminated backend label (e.g. "avx512-mt", "avx2-mt", "sse2-mt", "scalar-mt"). */
FUJI_ISP_SIMD_EXPORT const char* fuji_isp_simd_backend_name(void);

/* Returns default worker thread count used when threads == 0. */
FUJI_ISP_SIMD_EXPORT int32_t fuji_isp_simd_default_threads(void);

/*
 * Multi-threaded SIMD Bayer RGGB -> YUV ISP pipeline.
 *
 * cfg[0..19]:
 *   [0]  src_w            Bayer input width (>= 2)
 *   [1]  src_h            Bayer input height (>= 2)
 *   [2]  is_16bit         1 = 16-bit LE (>>4 to 12-bit), 0 = 8-bit
 *   [3]  dst_w            Output width (>= 2, even)
 *   [4]  dst_h            Output height (>= 1)
 *   [5]  stride           Row stride in bytes
 *   [6]  is_semi_planar   1 = NV16/NV12 (y_or_uyvy_dst + uv_dst), 0 = packed UYVY422/YYUV422
 *   [7]  is_nv12          1 = NV12 4:2:0 (when is_semi_planar=1), 0 = NV16 4:2:2
 *   [8]  off_x            Active destination X offset
 *   [9]  off_y            Active destination Y offset
 *   [10] fit_w            Active destination width
 *   [11] fit_h            Active destination height
 *   [12] gain_r           Q10 red gain (1024 = 1.0x)
 *   [13] gain_g           Q10 green gain (1024 = 1.0x)
 *   [14] gain_b           Q10 blue gain (1024 = 1.0x)
 *   [15] flags            Bitmask of FUJI_ISP_FLAG_*
 *   [16] yuv_shift        Right-shift for custom yuv_mat (typically 10)
 *   [17] peaking_thr      Effective 5-tap HPF peaking threshold
 *   [18] peaking_packed   hiY | (hiCb << 8) | (hiCr << 16)
 *   [19] threads          0 = auto, 1 = single-threaded, >1 = max threads
 *
 * lut3d_16 (optional, NULL if unused):
 *   16 * 16 * 16 * 3 float32 entries in [0.0f, 1.0f], Adobe .cube R-fastest order:
 *   index = ((b * 16 + g) * 16 + r) * 3 + channel (0=R, 1=G, 2=B).
 *   Applied with trilinear interpolation after 1D tone LUT, before YUV conversion & histogram.
 *
 * hist_yrgb (optional, NULL if unused):
 *   int32_t[1024] layout: Y[0..255], R[256..511], G[512..767], B[768..1023].
 *
 * out_af_contrast (optional, NULL if unused):
 *   Single-pass 2D luma gradient energy sum over interior active pixels.
 *
 * Returns >= 0 (number of stamped focus-peaking pixels) on success, < 0 on invalid arguments.
 */
FUJI_ISP_SIMD_EXPORT int32_t fuji_isp_demosaic_yuv_mt(
    const uint8_t* raw,
    uint8_t* y_or_uyvy_dst,
    uint8_t* uv_dst,
    const int32_t* src_x_map,
    const int32_t* ccm,
    const int32_t* lut_r,
    const int32_t* lut_g,
    const int32_t* lut_b,
    const float* lut3d_16,
    const int32_t* yuv_mat,
    int32_t* hist_yrgb,
    int64_t* out_af_contrast,
    const int32_t* cfg
);

/*
 * Spatial Optical Filter 1: Black Mist Diffusion (highlight extraction + 2-pass separable
 * box blur approximating Gaussian halation + additive bloom & shadow lift).
 * Operates in-place on planar linear float32 RGB buffers using caller-provided scratch buffers.
 * Returns 0 on success, < 0 on invalid arguments.
 */
FUJI_ISP_SIMD_EXPORT int32_t fuji_optics_black_mist_f32(
    float* red,
    float* green,
    float* blue,
    float* hi_r,
    float* hi_g,
    float* hi_b,
    float* tmp_r,
    float* tmp_g,
    float* tmp_b,
    int32_t width,
    int32_t height,
    float threshold,
    float strength,
    int32_t radius,
    int32_t threads
);

/*
 * Spatial Optical Filter 2: UV Protective Filter (micro-softness MTF blur + point-symmetric
 * internal double-reflection chromatic ghosting + veiling glare contrast washout + glass tint).
 * Operates in-place on planar linear float32 RGB buffers using caller-provided scratch buffers.
 * Returns 0 on success, < 0 on invalid arguments.
 */
FUJI_ISP_SIMD_EXPORT int32_t fuji_optics_uv_filter_f32(
    float* red,
    float* green,
    float* blue,
    float* scratch_r,
    float* scratch_g,
    float* scratch_b,
    float* aux_r,
    float* aux_g,
    float* aux_b,
    int32_t width,
    int32_t height,
    float softness,
    float ghost_intensity,
    float veiling_glare,
    float tint_r,
    float tint_g,
    float tint_b,
    int32_t has_tint,
    int32_t threads
);

/*
 * Spatial Optical Filter 3: Star Cross-Screen (4/6/8-point specular diffraction spikes).
 * Operates in-place on planar linear float32 RGB buffers using caller-provided acc buffers.
 * Returns >= 0 (count of specular hot pixels stamped) on success, < 0 on invalid arguments.
 */
FUJI_ISP_SIMD_EXPORT int32_t fuji_optics_star_filter_f32(
    float* red,
    float* green,
    float* blue,
    float* acc_r,
    float* acc_g,
    float* acc_b,
    int32_t width,
    int32_t height,
    int32_t points,
    float strength,
    float threshold,
    float length_frac,
    double angle_deg,
    int32_t threads
);

#ifdef __cplusplus
}
#endif

#endif /* FUJI_ISP_SIMD_H */
