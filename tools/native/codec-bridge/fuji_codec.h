#ifndef FUJI_CODEC_H
#define FUJI_CODEC_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32) || defined(__CYGWIN__)
  #if defined(FUJI_CODEC_BUILD_DLL)
    #define FUJI_CODEC_EXPORT __declspec(dllexport)
  #else
    #define FUJI_CODEC_EXPORT
  #endif
#else
  #define FUJI_CODEC_EXPORT __attribute__((visibility("default")))
#endif

#define FUJI_CODEC_ABI_VERSION 1

/* Capability flags returned by fuji_codec_capabilities() */
#define FUJI_CODEC_CAP_LSRAW_MT       (1 << 0) /* Multi-threaded C Lsraw strip codec */
#define FUJI_CODEC_CAP_JPEG_TURBO     (1 << 1) /* Dynamic libturbojpeg loaded */
#define FUJI_CODEC_CAP_JPEG_LIBJPEG8  (1 << 2) /* Dynamic libjpeg.so.8 (libjpeg-turbo v80) loaded */
#define FUJI_CODEC_CAP_SIMD_SSE2      (1 << 3) /* x86_64 SSE2 enabled */
#define FUJI_CODEC_CAP_SIMD_AVX2      (1 << 4) /* Runtime AVX2 detected */
#define FUJI_CODEC_CAP_JPEG_LIBJPEG62 (1 << 5) /* Dynamic libjpeg.so.62 (libjpeg-turbo/IJG v62) loaded */
#define FUJI_CODEC_CAP_JPEG_BUILTIN   (1 << 6) /* Built-in zero-dependency pure-C baseline JPEG encoder */

/* Error codes returned by codec functions (non-negative = byte count / success) */
#define FUJI_CODEC_ERR_INVALID_ARGS (-1)
#define FUJI_CODEC_ERR_OVERFLOW     (-2)
#define FUJI_CODEC_ERR_CODEC        (-3)
#define FUJI_CODEC_ERR_NO_BACKEND   (-4)

FUJI_CODEC_EXPORT int32_t     fuji_codec_abi_version(void);
FUJI_CODEC_EXPORT int32_t     fuji_codec_capabilities(void);
FUJI_CODEC_EXPORT int32_t     fuji_codec_default_threads(void);
FUJI_CODEC_EXPORT const char* fuji_codec_backend_name(void);

/**
 * Frees a native heap buffer allocated by fuji_jpeg_encode_*_alloc().
 */
FUJI_CODEC_EXPORT void fuji_codec_free(void* ptr);

/**
 * Multi-threaded Fujifilm LSRAW Bayer 2x2 gradient-adaptive Golomb-Rice strip encoder.
 * Produces bit-for-bit identical output (0-byte diff) to LsrawCodecEngine.kt.
 *
 * Returns encoded byte length (>0) on success, or negative FUJI_CODEC_ERR_*.
 * On FUJI_CODEC_ERR_OVERFLOW (-2), *out_required_bytes (if non-null) receives the exact byte count.
 */
FUJI_CODEC_EXPORT int32_t fuji_lsraw_encode_bayer(
    const uint8_t* src_raw_le16,
    int32_t src_stride_bytes,
    int32_t active_width,
    int32_t active_height,
    int32_t blocks_x,
    int32_t blocks_y,
    int32_t strip_pixels,
    int32_t raw_bits,
    int32_t lossy_compressed,
    int32_t max_cap_bytes,
    int32_t max_threads,
    uint8_t* dst_stream,
    int32_t dst_capacity,
    int32_t* out_required_bytes
);

/**
 * Multi-threaded Fujifilm LSRAW Bayer 2x2 strip decoder.
 * Produces bit-for-bit identical 16-bit LE Bayer pixels to LsrawCodecEngine.kt.
 *
 * dst_stride_bytes: output row pitch in bytes (>= active_width * 2, or 0 for active_width * 2).
 * out_meta (optional, 5 int32_t entries if non-null):
 *   [0]=active_width, [1]=active_height, [2]=raw_bits, [3]=version, [4]=blocks_x
 *
 * Returns decoded byte length (dst_stride_bytes * active_height) on success,
 * or negative FUJI_CODEC_ERR_*.
 */
FUJI_CODEC_EXPORT int32_t fuji_lsraw_decode_bayer(
    const uint8_t* src_stream,
    int32_t src_stream_len,
    int32_t dst_stride_bytes,
    int32_t max_threads,
    uint8_t* dst_raw_le16,
    int32_t dst_capacity,
    int32_t* out_meta
);

/**
 * Probes a JPEG bitstream header and computes Milbeaut MemYc decode geometry.
 *
 * out_meta (7 int32_t entries):
 *   [0]=src_width, [1]=src_height, [2]=out_width, [3]=out_height,
 *   [4]=stride_bytes (aligned_w * 2), [5]=total_bytes (stride_bytes * aligned_h),
 *   [6]=is_420 (1 if 4:2:0 else 0)
 *
 * Returns 0 on success, or negative FUJI_CODEC_ERR_*.
 */
FUJI_CODEC_EXPORT int32_t fuji_jpeg_probe_header(
    const uint8_t* src_jpeg,
    int32_t src_jpeg_len,
    int32_t downscale_shift,
    int32_t crop_enabled,
    int32_t crop_x0,
    int32_t crop_x1,
    int32_t crop_y0,
    int32_t crop_y1,
    int32_t* out_meta
);

/**
 * Decodes a JPEG bitstream directly into aligned packed UYVY422 (MemYc) using
 * native libjpeg-turbo (JCS_YCbCr direct upsample + hardware IDCT 1/2/4/8 downscaling,
 * zero RGB round-trip).
 *
 * out_meta (optional, 5 int32_t entries if non-null):
 *   [0]=out_width, [1]=out_height, [2]=stride_bytes, [3]=total_bytes, [4]=is_420
 *
 * Returns total_bytes (>0) on success, or negative FUJI_CODEC_ERR_*.
 */
FUJI_CODEC_EXPORT int32_t fuji_jpeg_decode_to_uyvy(
    const uint8_t* src_jpeg,
    int32_t src_jpeg_len,
    int32_t downscale_shift,
    int32_t crop_enabled,
    int32_t crop_x0,
    int32_t crop_x1,
    int32_t crop_y0,
    int32_t crop_y1,
    uint8_t* dst_uyvy,
    int32_t dst_capacity,
    int32_t* out_meta
);

/**
 * Encodes packed UYVY422 (MemYc) directly into a 4:2:2 JPEG bitstream with zero RGB
 * round-trip (via libjpeg-turbo raw planar YUV422 FDCT or TurboJPEG tjCompressFromYUVPlanes).
 * Maps Milbeaut unwritten/black fill words (0x00000000, [00 00 80 80], [80 80 00 00])
 * to neutral black (Y=0, Cb=128, Cr=128).
 *
 * Allocates the encoded JPEG buffer on the native heap and stores it in *out_buf.
 * Caller MUST free *out_buf via fuji_codec_free(*out_buf) when return value > 0.
 * If encoded_size > max_bytes (when max_bytes > 0), frees internal buffer, sets *out_buf = NULL,
 * sets *out_actual_size = encoded_size, and returns FUJI_CODEC_ERR_OVERFLOW (-2).
 */
FUJI_CODEC_EXPORT int32_t fuji_jpeg_encode_uyvy_alloc(
    const uint8_t* src_uyvy,
    int32_t width,
    int32_t height,
    int32_t src_stride_bytes,
    int32_t quality,
    int32_t max_bytes,
    uint8_t** out_buf,
    int32_t* out_actual_size
);

/**
 * Encodes BGR24 pixels directly into a JPEG bitstream (for optical CMOS sensor capture).
 * Same allocation and overflow contract as fuji_jpeg_encode_uyvy_alloc().
 */
FUJI_CODEC_EXPORT int32_t fuji_jpeg_encode_bgr24_alloc(
    const uint8_t* src_bgr24,
    int32_t width,
    int32_t height,
    int32_t src_stride_bytes,
    int32_t quality,
    int32_t max_bytes,
    uint8_t** out_buf,
    int32_t* out_actual_size
);

/**
 * Scales a cropped BGR24 source region to (dst_w, dst_h) in L2-hot MCU row strips
 * and encodes directly into dst_jpeg[0..dst_cap) with zero full-frame intermediate
 * allocation. Uses dynamic libjpeg when available, or the built-in multi-threaded
 * pure-C baseline YCbCr 4:2:2 JPEG encoder otherwise.
 *
 * Returns encoded byte length (>0) on success and writes *out_len (if non-null).
 * Returns FUJI_CODEC_ERR_OVERFLOW (-2) and writes required bytes to *out_len if
 * encoded_len > dst_cap, or negative FUJI_CODEC_ERR_* on invalid arguments / failure.
 */
FUJI_CODEC_EXPORT int32_t fuji_codec_jpeg_encode_scaled_bgr24(
    const uint8_t* src_bgr,
    int32_t src_w,
    int32_t src_h,
    int32_t crop_x,
    int32_t crop_y,
    int32_t crop_w,
    int32_t crop_h,
    int32_t dst_w,
    int32_t dst_h,
    int32_t quality,
    uint8_t* dst_jpeg,
    int32_t dst_cap,
    int32_t* out_len
);

#ifdef __cplusplus
}
#endif

#endif /* FUJI_CODEC_H */
