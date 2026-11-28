#ifndef FUJI_RENDER_H
#define FUJI_RENDER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32) || defined(__CYGWIN__)
  #if defined(FUJI_RENDER_BUILD_DLL)
    #define FUJI_RENDER_EXPORT __declspec(dllexport)
  #else
    #define FUJI_RENDER_EXPORT
  #endif
#else
  #define FUJI_RENDER_EXPORT __attribute__((visibility("default")))
#endif

#define FUJI_RENDER_ABI_VERSION 1

#define FUJI_RENDER_SIMD_SCALAR 0
#define FUJI_RENDER_SIMD_SSE2   1
#define FUJI_RENDER_SIMD_AVX2   2
#define FUJI_RENDER_SIMD_AVX512 3

/* Bit flag in cfg[19] for fuji_render_composite_planes_matrix / fuji_render_composite_lcdc_to_argb_pre:
 * when set, PIC0/PIC1 packed 4:2:2 buffers use real-silicon Milbeaut YYUV422 ([Y0, Y1, Cb, Cr],
 * LE u32 0xCrCbY1Y0) instead of legacy UYVY422 ([Cb, Y0, Cr, Y1]). */
#define FUJI_RENDER_CFG_FLAG_YYUV422 (1 << 8)

FUJI_RENDER_EXPORT int32_t     fuji_render_abi_version(void);
FUJI_RENDER_EXPORT int32_t     fuji_render_simd_level(void);
FUJI_RENDER_EXPORT const char* fuji_render_backend_name(void);

/**
 * Composites LCDC1 PIC1 (YUV422 UYVY, BT.601 with all-zero macro-pixel -> neutral black)
 * and OSD0 (RGBA8888 little-endian, low byte = alpha) into ARGB8888 (0xAARRGGBB).
 * Bit-identical to LcdcFirstScanout.compositePic1AndOsd0.
 */
FUJI_RENDER_EXPORT void fuji_render_composite_pic1_osd0(
    const uint8_t* osd_bytes,
    int32_t osd_base_offset,
    int32_t osd_stride_bytes,
    const uint8_t* pic1_bytes,
    int32_t pic1_x,
    int32_t pic1_y,
    int32_t pic1_width,
    int32_t pic1_height,
    int32_t pic1_stride_bytes,
    int32_t pic1_alpha,
    int32_t opaque_osd_backdrop,
    int32_t canvas_width,
    int32_t canvas_height,
    const int32_t* source_rows,
    const int32_t* source_cols,
    int32_t out_width,
    int32_t out_height,
    uint32_t* output_argb
);

/**
 * Decodes OSD0 RGBA8888 little-endian (low byte = alpha) into ARGB8888 (ror32 by 8),
 * supporting both 1:1 native copy and nearest-neighbor lookup via source_rows/source_cols.
 */
FUJI_RENDER_EXPORT void fuji_render_decode_rgba(
    const uint8_t* src_bytes,
    int32_t src_base_offset,
    int32_t src_stride_bytes,
    const int32_t* source_rows,
    const int32_t* source_cols,
    int32_t src_width,
    int32_t src_height,
    int32_t out_width,
    int32_t out_height,
    uint32_t* output_argb
);

/**
 * Fills byte_count bytes of dst with the little-endian 32-bit pattern_le.
 * Bit-identical to GraphicsFillExecution.fillPattern.
 */
FUJI_RENDER_EXPORT void fuji_render_fill_u32(
    uint8_t* dst,
    int32_t byte_count,
    uint32_t pattern_le
);

/**
 * Converts a linear RGBA8888 surface (width x height, both multiples of 8)
 * into 8x8 Morton Z-order tiles. Bit-identical to GraphicsCopyExecution.tiled.
 */
FUJI_RENDER_EXPORT void fuji_render_morton_tile_8x8(
    const uint8_t* linear_src,
    uint8_t* morton_dst,
    int32_t width,
    int32_t height,
    int32_t src_stride_bytes
);

/**
 * Blits an axis-aligned Morton 8x8 tiled RGBA8888 texture (low byte = alpha)
 * onto an RGBA8888 framebuffer with straight-alpha src-over blending.
 * Bit-identical to GraphicsCommandExecution.paint.
 */
FUJI_RENDER_EXPORT void fuji_render_blit_morton_quad(
    const uint8_t* texture,
    uint8_t* frame,
    int32_t canvas_width,
    int32_t tex_width,
    int32_t tex_height,
    int32_t pitch_tiles,
    int32_t row_start,
    int32_t row_end,
    int32_t col_start,
    int32_t col_end,
    double left,
    double top,
    double scale_x,
    double scale_y
);

/** Filter an affine Morton image at pixel centers, then composite it over the guest framebuffer. */
FUJI_RENDER_EXPORT void fuji_render_blit_filtered_morton(
    const uint8_t* texture, uint8_t* frame, int32_t canvas_width, int32_t width, int32_t height,
    int32_t pitch_tiles, int32_t top, int32_t bottom, int32_t left, int32_t right,
    double u0, double v0, double du_dx, double dv_dx, double du_dy, double dv_dy
);

/**
 * Fills an axis-aligned rectangle [left, right) x [top, bottom) in an RGBA8888 framebuffer
 * (byte layout [alpha, blue, green, red]) from guest color 0xAABBGGRR.
 * Bit-identical to SolidCommandRects.Rect.paint.
 */
FUJI_RENDER_EXPORT void fuji_render_fill_rect_rgba(
    uint8_t* frame,
    int32_t canvas_width,
    int32_t left,
    int32_t top,
    int32_t right,
    int32_t bottom,
    uint32_t color_aabbggrr,
    int32_t src_over
);

/**
 * Scanline-fills an arbitrary polygon defined by edge_count directed segments
 * (edge_x0[i], edge_y0[i]) -> (edge_x1[i], edge_y1[i]) using the even-odd rule.
 * Bit-identical to SolidCommandRects.PolygonFill.paint.
 */
FUJI_RENDER_EXPORT void fuji_render_fill_polygon_rgba(
    uint8_t* frame,
    int32_t canvas_width,
    const double* edge_x0,
    const double* edge_y0,
    const double* edge_x1,
    const double* edge_y1,
    int32_t edge_count,
    int32_t top,
    int32_t bottom,
    int32_t clip_left,
    int32_t clip_right,
    uint32_t color_aabbggrr,
    int32_t src_over
);

/**
 * Converts ARGB8888 straight-alpha pixels to premultiplied INT_ARGB_PRE directly into dst_pre
 * (which may alias src_argb for in-place conversion).
 * Fast-streams opaque blocks without extra passes.
 * Returns 1 if every pixel had alpha == 0xFF, 0 if any translucent pixel was premultiplied.
 */
FUJI_RENDER_EXPORT int32_t fuji_render_argb_to_pre(
    const uint32_t* src_argb,
    uint32_t* dst_pre,
    int32_t pixel_count
);

/**
 * Fills an axis-aligned rectangle [left, right) x [top, bottom) in an RGBA8888 framebuffer
 * (byte layout [alpha, blue, green, red]) using a 1D linear gradient ramp LUT (0xAABBGGRR).
 * Bit-identical to SolidCommandRects.LinearGradientRect.paint (Q-Menu 16 tiles & UI bars).
 */
FUJI_RENDER_EXPORT void fuji_render_fill_linear_gradient_rect_rgba(
    uint8_t* frame,
    int32_t canvas_width,
    int32_t canvas_height,
    int32_t left,
    int32_t top,
    int32_t right,
    int32_t bottom,
    double m3,
    double m4,
    double m5,
    const uint32_t* ramp_lut,
    int32_t ramp_len,
    int32_t src_over
);

/**
 * Fused 3-plane LCDC1 compositor (PIC0 + PIC1 + OSD0 over arbitrary 32-bit ARGB backdrop)
 * with in-register 3x3 Q10 RGB Color/Brightness Matrix + SPI5 Backlight gain modulation.
 *
 * cfg (20 int32_t entries):
 *   [0]=osd_base_offset, [1]=osd_stride_bytes,
 *   [2]=pic0_x, [3]=pic0_y, [4]=pic0_width, [5]=pic0_height, [6]=pic0_stride_bytes,
 *   [7]=pic1_x, [8]=pic1_y, [9]=pic1_width, [10]=pic1_height, [11]=pic1_stride_bytes,
 *   [12]=pic1_alpha, [13]=opaque_osd_backdrop, [14]=(int32_t)backdrop_argb,
 *   [15]=canvas_width, [16]=canvas_height, [17]=out_width, [18]=out_height,
 *   [19]=matrix_mode (0=identity/bypass, 1=diagonal, 2=full 3x3)
 *
 * Bit-identical to LcdcFirstScanout.(compositeDualPicAndOsd0|compositePic1AndOsd0) + applyColorMatrixAndBacklight.
 */
FUJI_RENDER_EXPORT void fuji_render_composite_planes_matrix(
    const uint8_t* osd_bytes,
    const uint8_t* pic0_bytes,
    const uint8_t* pic1_bytes,
    const int32_t* source_rows,
    const int32_t* source_cols,
    const int32_t* color_matrix_q10,
    const int32_t* cfg,
    uint32_t* output_argb
);

/**
 * Fused single-pass 3-plane LCDC1 compositor (PIC0 + PIC1 + OSD0 over arbitrary 32-bit ARGB backdrop)
 * with in-register 3x3 Q10 RGB Color/Brightness Matrix + SPI5 Backlight gain modulation AND
 * in-register straight-ARGB -> premultiplied INT_ARGB_PRE output directly into output_argb_pre
 * (e.g. a direct off-heap JavaFX PixelBuffer MemorySegment).
 *
 * cfg (20 int32_t entries): identical layout to fuji_render_composite_planes_matrix.
 * Returns 1 if every output pixel had alpha == 0xFF, 0 if any translucent pixel was premultiplied.
 * Bit-identical to fuji_render_composite_planes_matrix followed by fuji_render_argb_to_pre.
 */
FUJI_RENDER_EXPORT int32_t fuji_render_composite_lcdc_to_argb_pre(
    const uint8_t* osd_bytes,
    const uint8_t* pic0_bytes,
    const uint8_t* pic1_bytes,
    const int32_t* source_rows,
    const int32_t* source_cols,
    const int32_t* color_matrix_q10,
    const int32_t* cfg,
    uint32_t* output_argb_pre
);

/**
 * Fused single-pass LiveView ISP pipeline:
 *   RGGB Bayer (16-bit LE or 8-bit) -> bilinear demosaic -> Q10 exposure gain ->
 *   3x3 Q10 RGB CCM -> 4096-entry composed DR/Tone LUT -> RGB->YCbCr / Monochrome ->
 *   Packed UYVY422 or Semi-Planar NV16/NV12 + optional 4x256-bin Y/R/G/B Histogram +
 *   fused 5-tap HPF Focus Peaking (ring-buffered in L1, zero extra frame pass).
 *
 * cfg (19 int32_t entries):
 *   [0]=src_w, [1]=src_h, [2]=is_16bit, [3]=dst_w, [4]=dst_h, [5]=stride,
 *   [6]=is_semi_planar, [7]=is_nv12, [8]=off_x, [9]=off_y, [10]=fit_w, [11]=fit_h,
 *   [12]=gain_r_1024, [13]=gain_g_1024, [14]=gain_b_1024,
 *   [15]=flags (bit0=has_ccm, bit1=has_yuv_mat, bit2=mono_mode, bit3=peaking_enabled),
 *   [16]=yuv_shift,
 *   [17]=peaking_threshold,
 *   [18]=(peaking_hi_y & 0xff) | ((peaking_hi_cb & 0xff) << 8) | ((peaking_hi_cr & 0xff) << 16)
 *
 * Returns the number of peaking-stamped pixels (0 when peaking is disabled).
 * Bit-identical to LiveViewDmaEngine.(demosaicBayerToUyvy|demosaicBayerToSemiPlanarYuv) + MfAssistPeakingFilter.
 */
FUJI_RENDER_EXPORT int32_t fuji_render_isp_bayer_to_yuv_peaking(
    const uint8_t* raw,
    uint8_t* y_or_uyvy_dst,
    uint8_t* uv_dst,
    const int32_t* src_x_map,
    const int32_t* ccm,
    const int32_t* lut_r,
    const int32_t* lut_g,
    const int32_t* lut_b,
    const int32_t* yuv_mat,
    int32_t* hist_yrgb,
    const int32_t* cfg
);

#ifdef __cplusplus
}
#endif

#endif /* FUJI_RENDER_H */
