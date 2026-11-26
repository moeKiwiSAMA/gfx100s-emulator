#ifndef FUJI_VULKAN_OPTICS_H
#define FUJI_VULKAN_OPTICS_H

/*
 * FUJIFILM GFX100S Emulator - Cross-Platform Vulkan 1.1 Compute Optical Pipeline
 * Zero-bloat C ABI (`libfuji_vulkan_optics.so` / `fuji_vulkan_optics.dll`).
 *
 * Dynamically loads `libvulkan.so.1` (Linux) or `vulkan-1.dll` (Windows) at runtime
 * with zero static Vulkan linkage and zero extra runtime DLL dependencies.
 */

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  if defined(FUJI_VK_OPTICS_BUILD_DLL)
#    define FUJI_VK_EXPORT __declspec(dllexport)
#  else
#    define FUJI_VK_EXPORT __declspec(dllimport)
#  endif
#else
#  define FUJI_VK_EXPORT __attribute__((visibility("default")))
#endif

#define FUJI_VK_OPTICS_ABI_VERSION 1u

/* Device selection policy flags */
#define FUJI_VK_SELECT_PREFER_DISCRETE   (1u << 0)
#define FUJI_VK_SELECT_ALLOW_INTEGRATED  (1u << 1)
#define FUJI_VK_SELECT_ALLOW_SOFTWARE    (1u << 2)
#define FUJI_VK_SELECT_DEFAULT           (FUJI_VK_SELECT_PREFER_DISCRETE | FUJI_VK_SELECT_ALLOW_INTEGRATED)

/* Status codes */
#define FUJI_VK_OK                       0
#define FUJI_VK_ERR_INVALID_ARG         -1
#define FUJI_VK_ERR_NO_LOADER           -2
#define FUJI_VK_ERR_NO_DEVICE           -3
#define FUJI_VK_ERR_INIT_FAILED         -4
#define FUJI_VK_ERR_DISPATCH_FAILED     -5
#define FUJI_VK_ERR_UNSUPPORTED_EXT     -6
#define FUJI_VK_ERR_OUT_OF_MEMORY       -7

/* Entrance-pupil bokeh shapes */
#define FUJI_VK_PUPIL_CIRCLE             0u
#define FUJI_VK_PUPIL_POLYGON            1u
#define FUJI_VK_PUPIL_STAR_5             2u
#define FUJI_VK_PUPIL_HEART              3u
#define FUJI_VK_PUPIL_RING               4u

/* Bayer patterns */
#define FUJI_VK_BAYER_RGGB               0u
#define FUJI_VK_BAYER_GRBG               1u
#define FUJI_VK_BAYER_GBRG               2u
#define FUJI_VK_BAYER_BGGR               3u

/* Embedded SPIR-V shader IDs */
#define FUJI_VK_SHADER_OPTICS_WARP           0u
#define FUJI_VK_SHADER_PUPIL_BOKEH           1u
#define FUJI_VK_SHADER_CMOS_BAYER            2u
#define FUJI_VK_SHADER_OPTICAL_FILTER_STACK  3u
#define FUJI_VK_SHADER_ISP_DEMOSAIC          4u
#define FUJI_VK_SHADER_ISP_PEAKING           5u
#define FUJI_VK_SHADER_BOKEH_DOWNSAMPLE      6u
#define FUJI_VK_SHADER_COUNT                 7u

/* Phase 3 LiveView ISP output formats */
#define FUJI_VK_ISP_FMT_UYVY422              0u
#define FUJI_VK_ISP_FMT_NV16                 1u
#define FUJI_VK_ISP_FMT_NV12                 2u
#define FUJI_VK_ISP_FMT_YYUV422              3u

/* Phase 3 LiveView ISP feature flags */
#define FUJI_VK_ISP_FLAG_HAS_CCM             (1u << 0)
#define FUJI_VK_ISP_FLAG_HAS_YUV_MAT         (1u << 1)
#define FUJI_VK_ISP_FLAG_MONO_MODE           (1u << 2)
#define FUJI_VK_ISP_FLAG_PEAKING_ENABLED     (1u << 3)
#define FUJI_VK_ISP_FLAG_SRC_CROP            (1u << 4) /* offX/offY/fitW/fitH = source crop rect mapped to full dst */

/* Pass 0 Optical Filter Stack feature enable flags */
#define FUJI_VK_FILTER_ND                    (1u << 0)
#define FUJI_VK_FILTER_GND                   (1u << 1)
#define FUJI_VK_FILTER_BLACK_MIST            (1u << 2)
#define FUJI_VK_FILTER_STAR_CROSS            (1u << 3)
#define FUJI_VK_FILTER_UV                    (1u << 4)

/* External GPU interop handle types (Phase 2 Game Zero-Copy RGB+Depth) */
#define FUJI_VK_EXT_HANDLE_NONE               0u
#define FUJI_VK_EXT_HANDLE_WIN32_NT_HANDLE    1u
#define FUJI_VK_EXT_HANDLE_WIN32_OPAQUE       FUJI_VK_EXT_HANDLE_WIN32_NT_HANDLE
#define FUJI_VK_EXT_HANDLE_WIN32_KMT_HANDLE   2u
#define FUJI_VK_EXT_HANDLE_WIN32_D3D11_TEX    3u
#define FUJI_VK_EXT_HANDLE_OPAQUE_FD          4u
#define FUJI_VK_EXT_HANDLE_DMA_BUF_FD         5u
#define FUJI_VK_EXT_HANDLE_LINUX_DMA_BUF_FD   FUJI_VK_EXT_HANDLE_DMA_BUF_FD

/* External memory capability bits reported in FujiVkDeviceInfo.externalMemoryCaps */
#define FUJI_VK_EXT_CAP_MEMORY_WIN32     (1u << 0)
#define FUJI_VK_EXT_CAP_MEMORY_FD        (1u << 1)
#define FUJI_VK_EXT_CAP_DMA_BUF          (1u << 2)
#define FUJI_VK_EXT_CAP_SEMAPHORE_WIN32  (1u << 3)
#define FUJI_VK_EXT_CAP_SEMAPHORE_FD     (1u << 4)

typedef struct FujiVkContext FujiVkContext;
typedef struct FujiVkExternalFrame FujiVkExternalFrame;
typedef struct FujiVkIspStatsParams FujiVkIspStatsParams;
typedef struct FujiVkIspStatsOutput FujiVkIspStatsOutput;

typedef struct FujiVkDeviceInfo {
    uint32_t abiVersion;
    uint32_t apiVersion;
    uint32_t vendorId;
    uint32_t deviceId;
    uint32_t deviceType; /* VkPhysicalDeviceType: 1=iGPU, 2=dGPU, 3=vGPU, 4=CPU */
    uint32_t computeQueueFamily;
    uint32_t externalMemoryCaps;
    uint32_t deviceIndex; /* 0-based index in vkEnumeratePhysicalDevices (backward-compat slot) */
    char     deviceName[256];
    char     loaderName[128];
} FujiVkDeviceInfo;

/*
 * Modern engine pipeline telemetry: disk-persistent VkPipelineCache, specialization variants,
 * high-water-mark memory pool, HOST_CACHED readback, and Ring-2 async frames-in-flight.
 */
typedef struct FujiVkPipelineStats {
    uint32_t pipelineCacheHit;          /* 1 if warm disk VkPipelineCache loaded & validated, else 0 */
    uint32_t specializedPipelineCount;  /* Number of cached specialized VkPipeline variants */
    uint32_t bufferReallocCount;        /* Total Vulkan buffer allocations/grows since context init */
    uint32_t hostCachedReadback;        /* 1 if GPU->CPU readback buffers use HOST_CACHED memory */
    uint64_t pipelineCacheBytes;        /* Current serialized VkPipelineCache blob size in bytes */
    double   pipelineWarmupMs;          /* Context pipeline creation + pre-warm wall time in ms */
    uint64_t asyncFramesSubmitted;      /* Total Ring-2 async submissions */
    uint64_t asyncFramesCollected;      /* Total Ring-2 async collections */
} FujiVkPipelineStats;

/*
 * Optical warp + 2D entrance-pupil depth bokeh parameters (128 bytes, std140/push-constant aligned).
 */
typedef struct FujiVkOpticsParams {
    uint32_t srcWidth;
    uint32_t srcHeight;
    uint32_t dstWidth;
    uint32_t dstHeight;

    float    k1;
    float    k2;
    float    p1;
    float    p2;

    float    caRedScale;
    float    caBlueScale;
    float    cos4Power;
    float    mechVignetting;

    float    focalLengthMm;
    float    sensorWidthMm;
    float    focusDistanceM;
    float    fNumber;

    float    invZoom;
    float    ibisShiftX;
    float    ibisShiftY;
    float    ibisCosRoll;

    float    ibisSinRoll;
    float    maxBokehPx;
    float    bladeRoundness;
    float    catsEye;

    float    onionRing;
    float    rimHighlight;
    float    planarDepthM;
    float    imageCircleMm;

    uint32_t pupilShape;    /* FUJI_VK_PUPIL_* */
    uint32_t bladeCount;    /* 5..11 for POLYGON */
    uint32_t borderClamp;   /* 0=BLACK, 1=CLAMP */
    uint32_t flags;         /* bit0=depth, bit1=bokeh, bit2=half-resolution, bit3=external polygon cutout. Bits 4..17: clockwise angle in
                              turns/16384. Bits 18..31: cutout shrink (1 - scale) in 0.6/16383 units. */
} FujiVkOpticsParams;

/*
 * Stateless SplitMix64 CMOS noise + Bayer mosaic readout parameters (64 bytes).
 */
typedef struct FujiVkCmosParams {
    uint32_t width;
    uint32_t height;
    uint32_t bayerPattern;          /* FUJI_VK_BAYER_* */
    uint32_t bitDepth;              /* 8..16 */

    uint32_t maxCode;               /* (1 << bitDepth) - 1 */
    uint32_t blackLevel;
    uint32_t enableNoise;           /* 0 or 1 */
    uint32_t iso;

    float    baseIso;
    float    fullWellElectrons;
    float    opticalExposureScale;
    float    readNoiseBaseElectrons;

    float    readNoiseIsoSlope;
    uint32_t seedLo;
    uint32_t seedHi;
    uint32_t frameIndexLo;
} FujiVkCmosParams;

/*
 * Pass 0 Front-of-Lens Optical Filter Stack parameters (112 bytes, std140 aligned).
 * When enableFlags == 0 (or pointer is NULL), Pass 0 is bypassed with zero GPU overhead.
 */
typedef struct FujiVkFilterStackParams {
    uint32_t width;
    uint32_t height;
    uint32_t enableFlags;        /* bit0=ND, bit1=GND, bit2=BLACK_MIST, bit3=STAR_CROSS, bit4=UV */
    uint32_t starPoints;         /* 4, 6, or 8 diffraction spikes */

    float    ndTransmission;     /* scalar ND attenuation in [0..1] */
    float    gndTopTransmission; /* GND top attenuation in [0..1] */
    float    gndHorizonY;        /* normalized horizon Y in [0..1] */
    float    gndFeather;         /* half-width feather in [0.005..0.5] */

    float    gndSinAngle;        /* sin(gndAngleRad) */
    float    gndCosAngle;        /* cos(gndAngleRad) */
    float    mistStrength;       /* Black Mist strength [0..1] */
    float    mistThreshold;      /* highlight threshold in linear radiance */

    float    mistRadiusPx;       /* Black Mist halo radius in pixels */
    float    starStrength;       /* Star Cross-Screen strength [0..2] */
    float    starThreshold;      /* highlight threshold in linear radiance */
    float    starRadiusPx;       /* Star diffraction arm length in pixels */

    float    starAngleRad;       /* Star rotation angle in radians */
    float    mistWarmth;         /* warm amber halation shift [0..0.3] */
    float    uvVeilingGlare;     /* UV stray-light veiling glare [0..0.5] */
    float    uvGhostIntensity;   /* UV point-symmetric internal ghosting [0..0.5] */

    float    uvSoftness;         /* UV flat-glass micro-softness blend [0..1] */
    float    uvTintR;            /* UV luminance-normalized R tint multiplier (default 1.0) */
    float    uvTintG;            /* UV luminance-normalized G tint multiplier (default 1.0) */
    float    uvTintB;            /* UV luminance-normalized B tint multiplier (default 1.0) */

    float    uvGhostRadiusPx;    /* UV ghost blur radius in pixels [2..16] */
    uint32_t reserved0;
    uint32_t reserved1;
    uint32_t reserved2;
} FujiVkFilterStackParams;

/*
 * Phase 3 LiveView ISP Demosaic + CCM + 1D Tone LUT + Focus Peaking parameters (160 bytes, std140).
 */
typedef struct FujiVkIspDemosaicParams {
    uint32_t srcWidth;
    uint32_t srcHeight;
    uint32_t dstWidth;          /* must be even */
    uint32_t dstHeight;

    uint32_t is16Bit;           /* 0=8-bit Bayer, 1=16-bit LE Bayer */
    uint32_t outputFormat;      /* FUJI_VK_ISP_FMT_* (0=UYVY422, 1=NV16, 2=NV12, 3=YYUV422) */
    uint32_t dstStrideBytes;    /* 0 = tight (dstWidth*2 for packed, dstWidth for semi-planar) */
    uint32_t flags;             /* FUJI_VK_ISP_FLAG_* */

    uint32_t offX;
    uint32_t offY;
    uint32_t fitW;              /* 0 = auto aspect-fit from src->dst */
    uint32_t fitH;

    uint32_t gainR1024;         /* Q10 R gain (1024 = 1.0x) */
    uint32_t gainG1024;         /* Q10 G gain (1024 = 1.0x) */
    uint32_t gainB1024;         /* Q10 B gain (1024 = 1.0x) */
    uint32_t yuvShift;          /* YUV matrix right-shift (default 10) */

    int32_t  ccm[9];            /* 3x3 Q10 RGB CCM */
    int32_t  yuvMat[9];         /* 3x3 Q-shift RGB->YCbCr matrix */

    uint32_t peakingThreshold;  /* Effective 5-tap HPF threshold */
    uint32_t peakingHiY;        /* Highlight Y (0..255) */
    uint32_t peakingHiCb;       /* Highlight Cb (0..255) */
    uint32_t peakingHiCr;       /* Highlight Cr (0..255) */

    uint32_t reserved0;
    uint32_t reserved1;
} FujiVkIspDemosaicParams;

/*
 * Phase 2 Game Zero-Copy RGB+Depth External GPU Frame descriptor.
 */
typedef struct FujiVkExternalFrameDesc {
    uint32_t handleType;             /* FUJI_VK_EXT_HANDLE_* */
    uint32_t width;
    uint32_t height;
    uint32_t colorPitchBytes;
    uint32_t depthPitchBytes;
    uint32_t colorVkFormat;
    uint32_t depthVkFormat;
    uint32_t flags;
    uint64_t colorHandle;            /* Win32 HANDLE or Linux fd (cast to uint64_t) */
    uint64_t depthHandle;            /* Optional depth HANDLE/fd (0 if none) */
    uint64_t waitSemaphoreHandle;    /* Optional producer sync semaphore */
    uint64_t signalSemaphoreHandle;  /* Optional consumer sync semaphore */
    uint64_t timelineWaitValue;
    uint64_t timelineSignalValue;
    uint64_t colorMemorySizeBytes;
    uint64_t depthMemorySizeBytes;
} FujiVkExternalFrameDesc;

FUJI_VK_EXPORT uint32_t fuji_vk_abi_version(void);
/* Optional ABI-1 capability. Older libraries ignore the transform bits. */
FUJI_VK_EXPORT uint32_t fuji_vk_supports_pupil_transform(void);

FUJI_VK_EXPORT int32_t fuji_vk_enumerate_devices(
    FujiVkDeviceInfo* out_devices,
    int32_t max_devices,
    int32_t* out_total,
    char* err_buf,
    int32_t err_buf_len);

FUJI_VK_EXPORT int32_t fuji_vk_create_for_device(
    int32_t device_index,
    uint32_t select_flags,
    FujiVkContext** out_ctx,
    FujiVkDeviceInfo* out_info,
    char* err_buf,
    size_t err_buf_len);

/*
 * Sets the default disk path (file or directory) for persistent VkPipelineCache storage
 * used by subsequent fuji_vk_create / fuji_vk_create_for_device calls. Pass NULL or "" to reset.
 */
FUJI_VK_EXPORT void fuji_vk_set_pipeline_cache_path(const char* cache_path);

/*
 * Creates a Vulkan compute context with an explicit disk VkPipelineCache path (file or directory;
 * NULL falls back to fuji_vk_set_pipeline_cache_path, FUJI_VULKAN_PIPELINE_CACHE_PATH, or user cache dir).
 */
FUJI_VK_EXPORT int32_t fuji_vk_create_with_cache(
    int32_t device_index,
    uint32_t select_flags,
    const char* cache_path,
    FujiVkContext** out_ctx,
    FujiVkDeviceInfo* out_info,
    char* err_buf,
    size_t err_buf_len);

FUJI_VK_EXPORT int32_t fuji_vk_create(
    uint32_t select_flags,
    FujiVkContext** out_ctx,
    FujiVkDeviceInfo* out_info,
    char* err_buf,
    size_t err_buf_len);

FUJI_VK_EXPORT void fuji_vk_destroy(FujiVkContext* ctx);

FUJI_VK_EXPORT int32_t fuji_vk_get_device_info(
    const FujiVkContext* ctx,
    FujiVkDeviceInfo* out_info);

FUJI_VK_EXPORT int32_t fuji_vk_get_pipeline_stats(
    const FujiVkContext* ctx,
    FujiVkPipelineStats* out_stats);

FUJI_VK_EXPORT const char* fuji_vk_last_error(const FujiVkContext* ctx);

FUJI_VK_EXPORT int32_t fuji_vk_get_spirv_words(
    uint32_t shader_id,
    const uint32_t** out_words,
    uint32_t* out_word_count);

/*
 * Executes Optical Warp + 2D Entrance-Pupil Depth Bokeh on Vulkan Compute.
 * in_rgba_depth: srcWidth * srcHeight * 4 floats (R, G, B, depthMeters)
 * out_rgba:      dstWidth * dstHeight * 4 floats (R, G, B, depthMeters)
 */
FUJI_VK_EXPORT int32_t fuji_vk_render_optics(
    FujiVkContext* ctx,
    const float* in_rgba_depth,
    float* out_rgba,
    const FujiVkOpticsParams* params,
    double* out_gpu_ms);

/*
 * Executes Stateless SplitMix64 CMOS Noise + Bayer Mosaic Readout on Vulkan Compute.
 * in_rgba:   width * height * 4 floats (R, G, B, unused)
 * out_bayer: packed little-endian Bayer output (1 B/px for 8-bit, 2 B/px for 9..16-bit)
 */
FUJI_VK_EXPORT int32_t fuji_vk_render_cmos_bayer(
    FujiVkContext* ctx,
    const float* in_rgba,
    uint8_t* out_bayer,
    size_t out_bayer_bytes,
    const FujiVkCmosParams* params,
    double* out_gpu_ms);

/*
 * Chained single-submit GPU execution: Optics Warp -> 2D Pupil Bokeh -> CMOS Bayer Readout.
 * Keeps intermediate RGB+Depth buffers strictly in GPU memory (zero host round-trip).
 */
FUJI_VK_EXPORT int32_t fuji_vk_render_optics_to_bayer(
    FujiVkContext* ctx,
    const float* in_rgba_depth,
    uint8_t* out_bayer,
    size_t out_bayer_bytes,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    double* out_gpu_ms);

/*
 * Single-submit GPU execution with optional Pass 0 Optical Filter Stack (ND, GND, Black Mist, Star):
 *   [Pass 0 Filter Stack (skipped if filter==NULL || enableFlags==0)] -> Pass 1 Warp -> [Pass 2 Bokeh].
 */
FUJI_VK_EXPORT int32_t fuji_vk_render_optics_filtered(
    FujiVkContext* ctx,
    const float* in_rgba_depth,
    float* out_rgba,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    double* out_gpu_ms);

/*
 * Single-submit GPU execution with optional Pass 0 Optical Filter Stack -> Optics Warp ->
 * 2D Pupil Bokeh -> CMOS SplitMix64 Bayer Readout (zero intermediate host round-trips).
 */
FUJI_VK_EXPORT int32_t fuji_vk_render_optics_to_bayer_filtered(
    FujiVkContext* ctx,
    const float* in_rgba_depth,
    uint8_t* out_bayer,
    size_t out_bayer_bytes,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    double* out_gpu_ms);

/*
 * Phase 3 Vulkan Compute LiveView ISP Demosaic + Q10 Gain + 3x3 CCM + 1D Tone LUT +
 * optional 5-tap HPF Focus Peaking -> Packed UYVY422 / NV16 / NV12 / YYUV422.
 */
FUJI_VK_EXPORT int32_t fuji_vk_isp_demosaic(
    FujiVkContext* ctx,
    const uint8_t* raw_bayer,
    size_t raw_bayer_bytes,
    const int32_t* lut_r_4096,
    const int32_t* lut_g_4096,
    const int32_t* lut_b_4096,
    uint8_t* out_y_or_packed,
    size_t out_y_or_packed_bytes,
    uint8_t* out_uv_plane,
    size_t out_uv_plane_bytes,
    const FujiVkIspDemosaicParams* params,
    double* out_gpu_ms);

/*
 * Fused GPU-Resident Optics -> CMOS Bayer -> LiveView ISP Demosaic [+ Focus Peaking] -> YUV.
 * Executes Pass 0 (Optional Filter Stack) -> Pass 1 (Warp) -> Pass 2 (Optional Pupil Bokeh) ->
 * Pass 3 (CMOS Bayer written directly at offset 16384 inside ISP input SSBO in VRAM) ->
 * Pass 4 (ISP Demosaic + Q10 Gain + 3x3 CCM + 1D Tone LUT + RGB->YCbCr) ->
 * Pass 5 (Optional 5-tap HPF Focus Peaking) in ONE single command-buffer submission with
 * ZERO intermediate Bayer host readback.
 */
FUJI_VK_EXPORT int32_t fuji_vk_render_optics_to_isp_yuv(
    FujiVkContext* ctx,
    const float* in_rgba_depth,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    const int32_t* lut_r_4096,
    const int32_t* lut_g_4096,
    const int32_t* lut_b_4096,
    uint8_t* out_y_or_packed,
    size_t out_y_or_packed_bytes,
    uint8_t* out_uv_plane,
    size_t out_uv_plane_bytes,
    const FujiVkIspDemosaicParams* isp_params,
    double* out_gpu_ms);

/*
 * Single-submit fused Pass 0..6: Pass 0..5 (Filter -> Warp -> Bokeh -> CMOS Bayer -> ISP Demosaic
 * [+ Peaking] -> YUV) PLUS Pass 6 (isp_stats_reduce.comp directly on the GPU-resident 12-bit Bayer
 * buffer in VRAM, returning the compact 9,280-byte FujiVkIspStatsOutput alongside YUV with ZERO
 * 1.2 MB Bayer host readback). When stats_params or out_stats is NULL, Pass 6 is skipped.
 */
FUJI_VK_EXPORT int32_t fuji_vk_render_optics_to_isp_yuv_with_stats(
    FujiVkContext* ctx,
    const float* in_rgba_depth,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    const int32_t* lut_r_4096,
    const int32_t* lut_g_4096,
    const int32_t* lut_b_4096,
    uint8_t* out_y_or_packed,
    size_t out_y_or_packed_bytes,
    uint8_t* out_uv_plane,
    size_t out_uv_plane_bytes,
    const FujiVkIspDemosaicParams* isp_params,
    const FujiVkIspStatsParams* stats_params,
    FujiVkIspStatsOutput* out_stats,
    double* out_gpu_ms);

/*
 * GPU-Resident VRAM Scene Cache (zero-upload focus sweeps / AF-ON):
 * Uploads (R, G, B, depthMeters) once into a DEVICE_LOCAL VRAM buffer keyed by scene_key.
 * Repeated calls with the same non-zero scene_key and pixel count return FUJI_VK_OK immediately.
 */
FUJI_VK_EXPORT int32_t fuji_vk_upload_scene(
    FujiVkContext* ctx,
    uint64_t scene_key,
    const float* in_rgba_depth,
    size_t src_pixel_count,
    double* out_gpu_ms);

/*
 * Executes Pass 0..2 (optional Filter -> Warp -> Bokeh) directly from the VRAM-resident scene
 * uploaded via fuji_vk_upload_scene(scene_key) with ZERO per-frame host->GPU upload.
 */
FUJI_VK_EXPORT int32_t fuji_vk_render_cached_scene_optics(
    FujiVkContext* ctx,
    uint64_t scene_key,
    float* out_rgba,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    double* out_gpu_ms);

/*
 * Executes Pass 0..3 (optional Filter -> Warp -> Bokeh -> CMOS Bayer) directly from the
 * VRAM-resident scene uploaded via fuji_vk_upload_scene(scene_key) with ZERO per-frame upload.
 */
FUJI_VK_EXPORT int32_t fuji_vk_render_cached_scene_to_bayer(
    FujiVkContext* ctx,
    uint64_t scene_key,
    uint8_t* out_bayer,
    size_t out_bayer_bytes,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    double* out_gpu_ms);

/*
 * Executes fused Pass 0..5 (Filter -> Warp -> Bokeh -> CMOS -> ISP Demosaic [+ Peaking] -> YUV)
 * directly from the VRAM-resident scene uploaded via fuji_vk_upload_scene(scene_key).
 */
FUJI_VK_EXPORT int32_t fuji_vk_render_cached_scene_to_isp_yuv(
    FujiVkContext* ctx,
    uint64_t scene_key,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    const int32_t* lut_r_4096,
    const int32_t* lut_g_4096,
    const int32_t* lut_b_4096,
    uint8_t* out_y_or_packed,
    size_t out_y_or_packed_bytes,
    uint8_t* out_uv_plane,
    size_t out_uv_plane_bytes,
    const FujiVkIspDemosaicParams* isp_params,
    double* out_gpu_ms);

/*
 * Single-submit fused Pass 0..6 directly from VRAM-resident scene (2D cached or Vulkan 3D):
 * Filter -> Warp -> Bokeh -> CMOS Bayer -> ISP Demosaic [+ Peaking] + GPU ISP Stats Reduction.
 */
FUJI_VK_EXPORT int32_t fuji_vk_render_cached_scene_to_isp_yuv_with_stats(
    FujiVkContext* ctx,
    uint64_t scene_key,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    const int32_t* lut_r_4096,
    const int32_t* lut_g_4096,
    const int32_t* lut_b_4096,
    uint8_t* out_y_or_packed,
    size_t out_y_or_packed_bytes,
    uint8_t* out_uv_plane,
    size_t out_uv_plane_bytes,
    const FujiVkIspDemosaicParams* isp_params,
    const FujiVkIspStatsParams* stats_params,
    FujiVkIspStatsOutput* out_stats,
    double* out_gpu_ms);

/*
 * Ring-2 Double-Buffered Async Frames-in-Flight (Pass 0..3 -> Bayer):
 * Submits a frame into the next free FrameSlot (slots[0..1]) without blocking on GPU completion.
 * Either scene_key (VRAM-cached scene) or in_rgba_depth (host upload) must be provided.
 * Collect the completed Bayer output later via fuji_vk_collect_async_bayer(ctx, ticket, ...).
 */
FUJI_VK_EXPORT int32_t fuji_vk_submit_async_to_bayer(
    FujiVkContext* ctx,
    uint64_t scene_key,
    const float* in_rgba_depth,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    uint64_t* out_ticket);

/*
 * Waits on the Ring-2 slot fence associated with `ticket`, invalidates HOST_CACHED readback memory
 * if non-coherent, packs the Bayer output into `out_bayer`, and releases the slot.
 */
FUJI_VK_EXPORT int32_t fuji_vk_collect_async_bayer(
    FujiVkContext* ctx,
    uint64_t ticket,
    uint8_t* out_bayer,
    size_t out_bayer_bytes,
    double* out_gpu_ms);

/*
 * Phase 2 Game Zero-Copy RGB+Depth external GPU memory import & direct GPU dispatch hooks.
 */
FUJI_VK_EXPORT int32_t fuji_vk_validate_external_frame_desc(
    FujiVkContext* ctx,
    const FujiVkExternalFrameDesc* desc);

FUJI_VK_EXPORT int32_t fuji_vk_import_external_frame(
    FujiVkContext* ctx,
    const FujiVkExternalFrameDesc* desc,
    FujiVkExternalFrame** out_frame);

FUJI_VK_EXPORT int32_t fuji_vk_render_external_frame_to_bayer(
    FujiVkContext* ctx,
    const FujiVkExternalFrame* ext_frame,
    uint8_t* out_bayer,
    size_t out_bayer_bytes,
    const FujiVkFilterStackParams* filter,
    const FujiVkOpticsParams* optics,
    const FujiVkCmosParams* cmos,
    double* out_gpu_ms);

FUJI_VK_EXPORT void fuji_vk_release_external_frame(
    FujiVkContext* ctx,
    FujiVkExternalFrame* frame);

#ifdef __cplusplus
}
#endif

#endif /* FUJI_VULKAN_OPTICS_H */
