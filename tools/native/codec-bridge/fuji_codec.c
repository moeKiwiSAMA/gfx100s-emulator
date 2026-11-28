/*
 * fuji_codec.c — High-speed native codec bridge for Fujifilm GFX100S emulator.
 *
 * License: MIT (100% permissive; optional dynamic libjpeg-turbo / libjpeg v80 via
 *          dlopen/LoadLibraryA is BSD-3-Clause / IJG / zlib — zero GPL/LGPL).
 *
 * Components:
 *   1. Multi-threaded C Fujifilm LSRAW Bayer 2x2 gradient-adaptive Golomb-Rice strip
 *      encoder & decoder (bit-for-bit identical to LsrawCodecEngine.kt, 0-byte diff).
 *      Uses POSIX pthreads on Linux and native Win32 _beginthreadex on Win64
 *      (zero external DLL dependencies beyond KERNEL32.dll + msvcrt.dll).
 *   2. Dynamic zero-dependency native JPEG YUV422 / BGR24 codec using host
 *      libjpeg-turbo (libjpeg.so.8 v80, libjpeg.so.62 v62 streaming raw YUV422 FDCT
 *      & direct JCS_YCbCr IDCT 1/2/4/8 downscaling, or libturbojpeg.so.0 / turbojpeg.dll).
 */

#ifndef _WIN32
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#define _POSIX_C_SOURCE 200809L
#endif

#include "fuji_codec.h"

#include <limits.h>
#include <setjmp.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
  #define WIN32_LEAN_AND_MEAN
  #include <windows.h>
  #include <process.h>
#else
  #include <dlfcn.h>
  #include <pthread.h>
  #include <unistd.h>
#endif

#if defined(__x86_64__) || defined(_M_X64)
  #include <immintrin.h>
#endif

/* ============================================================================
 * 1. Platform CPU / SIMD / Threading Helpers
 * ============================================================================ */

static int32_t detect_cpu_cores(void) {
#if defined(_WIN32)
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    int32_t n = (int32_t)si.dwNumberOfProcessors;
    return n > 0 ? n : 1;
#else
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    return n > 0 ? (int32_t)n : 1;
#endif
}

static int32_t detect_simd_caps(void) {
    int32_t caps = 0;
#if defined(__SSE2__) || defined(_M_X64)
    caps |= FUJI_CODEC_CAP_SIMD_SSE2;
#endif
#if (defined(__GNUC__) || defined(__clang__)) && (defined(__x86_64__) || defined(__i386__))
    __builtin_cpu_init();
    if (__builtin_cpu_supports("avx2")) {
        caps |= FUJI_CODEC_CAP_SIMD_AVX2;
    }
#endif
    return caps;
}

static int32_t clamp_i32(int32_t v, int32_t lo, int32_t hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

static inline uint16_t read_u16_be(const uint8_t* p) {
    return (uint16_t)(((uint16_t)p[0] << 8) | (uint16_t)p[1]);
}

static inline uint32_t read_u32_be(const uint8_t* p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8)  | (uint32_t)p[3];
}

static inline void write_u16_be(uint8_t* p, uint16_t v) {
    p[0] = (uint8_t)(v >> 8);
    p[1] = (uint8_t)(v & 0xFFu);
}

static inline void write_u32_be(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)((v >> 16) & 0xFFu);
    p[2] = (uint8_t)((v >> 8) & 0xFFu);
    p[3] = (uint8_t)(v & 0xFFu);
}

/* ============================================================================
 * 2. Dynamic libjpeg.so.8 (v80), libjpeg.so.62 (v62) & libturbojpeg Loader
 * ============================================================================ */

/* Standard libjpeg v80 (JPEG_LIB_VERSION == 80) and v62 (JPEG_LIB_VERSION == 62) ABI declarations */
#define FJ_JPEG_LIB_VERSION    80
#define FJ_JPEG_LIB_VERSION_62 62
#define FJ_DCTSIZE          8
#define FJ_DCTSIZE2         64
#define FJ_NUM_QUANT_TBLS   4
#define FJ_NUM_HUFF_TBLS    4
#define FJ_NUM_ARITH_TBLS   16
#define FJ_MAX_COMPS_SCAN   4
#define FJ_JMSG_STR_MAX     80

typedef unsigned char fj_JSAMPLE;
typedef unsigned int  fj_JDIMENSION;
typedef int           fj_boolean;
typedef fj_JSAMPLE*   fj_JSAMPROW;
typedef fj_JSAMPROW*  fj_JSAMPARRAY;
typedef fj_JSAMPARRAY* fj_JSAMPIMAGE;

typedef enum {
    FJ_JCS_UNKNOWN = 0,
    FJ_JCS_GRAYSCALE = 1,
    FJ_JCS_RGB = 2,
    FJ_JCS_YCbCr = 3,
    FJ_JCS_CMYK = 4,
    FJ_JCS_YCCK = 5,
    FJ_JCS_EXT_RGB = 6,
    FJ_JCS_EXT_RGBX = 7,
    FJ_JCS_EXT_BGR = 8
} fj_J_COLOR_SPACE;

typedef enum { FJ_JDCT_ISLOW = 0, FJ_JDCT_IFAST = 1, FJ_JDCT_FLOAT = 2 } fj_J_DCT_METHOD;
typedef enum { FJ_JDITHER_NONE = 0, FJ_JDITHER_ORDERED = 1, FJ_JDITHER_FS = 2 } fj_J_DITHER_MODE;

struct fj_jpeg_common_struct {
    struct fj_jpeg_error_mgr* err;
    void* mem;
    void* progress;
    void* client_data;
    fj_boolean is_decompressor;
    int global_state;
};

typedef struct fj_jpeg_common_struct* fj_j_common_ptr;
struct fj_jpeg_compress_struct;
typedef struct fj_jpeg_compress_struct* fj_j_compress_ptr;
struct fj_jpeg_decompress_struct;
typedef struct fj_jpeg_decompress_struct* fj_j_decompress_ptr;

struct fj_jpeg_error_mgr {
    void (*error_exit)(fj_j_common_ptr cinfo);
    void (*emit_message)(fj_j_common_ptr cinfo, int msg_level);
    void (*output_message)(fj_j_common_ptr cinfo);
    void (*format_message)(fj_j_common_ptr cinfo, char* buffer);
    void (*reset_error_mgr)(fj_j_common_ptr cinfo);
    int msg_code;
    union {
        int i[8];
        char s[FJ_JMSG_STR_MAX];
    } msg_parm;
    int trace_level;
    long num_warnings;
    const char* const* jpeg_message_table;
    int last_jpeg_message;
    const char* const* addon_message_table;
    int first_addon_message;
    int last_addon_message;
};

typedef struct {
    uint16_t quantval[FJ_DCTSIZE2];
    fj_boolean sent_table;
} fj_JQUANT_TBL;

typedef struct {
    uint8_t bits[17];
    uint8_t huffval[256];
    fj_boolean sent_table;
} fj_JHUFF_TBL;

typedef struct {
    int component_id;
    int component_index;
    int h_samp_factor;
    int v_samp_factor;
    int quant_tbl_no;
    int dc_tbl_no;
    int ac_tbl_no;
    fj_JDIMENSION width_in_blocks;
    fj_JDIMENSION height_in_blocks;
    int DCT_h_scaled_size;
    int DCT_v_scaled_size;
    fj_JDIMENSION downsampled_width;
    fj_JDIMENSION downsampled_height;
    fj_boolean component_needed;
    int MCU_width;
    int MCU_height;
    int MCU_blocks;
    int MCU_sample_width;
    int last_col_width;
    int last_row_height;
    fj_JQUANT_TBL* quant_table;
    void* dct_table;
} fj_jpeg_component_info;

typedef struct {
    int comps_in_scan;
    int component_index[FJ_MAX_COMPS_SCAN];
    int Ss, Se;
    int Ah, Al;
} fj_jpeg_scan_info;

struct fj_jpeg_compress_struct {
    struct fj_jpeg_error_mgr* err;
    void* mem;
    void* progress;
    void* client_data;
    fj_boolean is_decompressor;
    int global_state;

    void* dest;
    fj_JDIMENSION image_width;
    fj_JDIMENSION image_height;
    int input_components;
    fj_J_COLOR_SPACE in_color_space;
    double input_gamma;

    unsigned int scale_num, scale_denom;
    fj_JDIMENSION jpeg_width;
    fj_JDIMENSION jpeg_height;

    int data_precision;
    int num_components;
    fj_J_COLOR_SPACE jpeg_color_space;
    fj_jpeg_component_info* comp_info;
    fj_JQUANT_TBL* quant_tbl_ptrs[FJ_NUM_QUANT_TBLS];
    int q_scale_factor[FJ_NUM_QUANT_TBLS];
    fj_JHUFF_TBL* dc_huff_tbl_ptrs[FJ_NUM_HUFF_TBLS];
    fj_JHUFF_TBL* ac_huff_tbl_ptrs[FJ_NUM_HUFF_TBLS];
    uint8_t arith_dc_L[FJ_NUM_ARITH_TBLS];
    uint8_t arith_dc_U[FJ_NUM_ARITH_TBLS];
    uint8_t arith_ac_K[FJ_NUM_ARITH_TBLS];
    int num_scans;
    const fj_jpeg_scan_info* scan_info;
    fj_boolean raw_data_in;
    fj_boolean arith_code;
    fj_boolean optimize_coding;
    fj_boolean CCIR601_sampling;
    fj_boolean do_fancy_downsampling;
    int smoothing_factor;
    fj_J_DCT_METHOD dct_method;
    unsigned int restart_interval;
    int restart_in_rows;
    fj_boolean write_JFIF_header;
    uint8_t JFIF_major_version;
    uint8_t JFIF_minor_version;
    uint8_t density_unit;
    uint16_t X_density;
    uint16_t Y_density;
    fj_boolean write_Adobe_marker;
    fj_JDIMENSION next_scanline;
    fj_boolean progressive_mode;
    int max_h_samp_factor;
    int max_v_samp_factor;
    int min_DCT_h_scaled_size;
    int min_DCT_v_scaled_size;
    fj_JDIMENSION total_iMCU_rows;
    int comps_in_scan;
    fj_jpeg_component_info* cur_comp_info[FJ_MAX_COMPS_SCAN];
    fj_JDIMENSION MCUs_per_row;
    fj_JDIMENSION MCU_rows_in_scan;
    int blocks_in_MCU;
    int MCU_membership[10];
    int Ss, Se, Ah, Al;
    int block_size;
    const int* natural_order;
    int lim_Se;
    void* master;
    void* main;
    void* prep;
    void* coef;
    void* marker;
    void* cconvert;
    void* downsample;
    void* fdct;
    void* entropy;
    fj_jpeg_scan_info* script_space;
    int script_space_size;
};

struct fj_jpeg_decompress_struct {
    struct fj_jpeg_error_mgr* err;
    void* mem;
    void* progress;
    void* client_data;
    fj_boolean is_decompressor;
    int global_state;

    void* src;
    fj_JDIMENSION image_width;
    fj_JDIMENSION image_height;
    int num_components;
    fj_J_COLOR_SPACE jpeg_color_space;
    fj_J_COLOR_SPACE out_color_space;
    unsigned int scale_num, scale_denom;
    double output_gamma;
    fj_boolean buffered_image;
    fj_boolean raw_data_out;
    fj_J_DCT_METHOD dct_method;
    fj_boolean do_fancy_upsampling;
    fj_boolean do_block_smoothing;
    fj_boolean quantize_colors;
    fj_J_DITHER_MODE dither_mode;
    fj_boolean two_pass_quantize;
    int desired_number_of_colors;
    fj_boolean enable_1pass_quant;
    fj_boolean enable_external_quant;
    fj_boolean enable_2pass_quant;
    fj_JDIMENSION output_width;
    fj_JDIMENSION output_height;
    int out_color_components;
    int output_components;
    int rec_outbuf_height;
    int actual_number_of_colors;
    fj_JSAMPARRAY colormap;
    fj_JDIMENSION output_scanline;
    int input_scan_number;
    fj_JDIMENSION input_iMCU_row;
    int output_scan_number;
    fj_JDIMENSION output_iMCU_row;
    int (*coef_bits)[FJ_DCTSIZE2];
    fj_JQUANT_TBL* quant_tbl_ptrs[FJ_NUM_QUANT_TBLS];
    fj_JHUFF_TBL* dc_huff_tbl_ptrs[FJ_NUM_HUFF_TBLS];
    fj_JHUFF_TBL* ac_huff_tbl_ptrs[FJ_NUM_HUFF_TBLS];
    int data_precision;
    fj_jpeg_component_info* comp_info;
    fj_boolean is_baseline;
    fj_boolean progressive_mode;
    fj_boolean arith_code;
    uint8_t arith_dc_L[FJ_NUM_ARITH_TBLS];
    uint8_t arith_dc_U[FJ_NUM_ARITH_TBLS];
    uint8_t arith_ac_K[FJ_NUM_ARITH_TBLS];
    unsigned int restart_interval;
    fj_boolean saw_JFIF_marker;
    uint8_t JFIF_major_version;
    uint8_t JFIF_minor_version;
    uint8_t density_unit;
    uint16_t X_density;
    uint16_t Y_density;
    fj_boolean saw_Adobe_marker;
    uint8_t Adobe_transform;
    fj_boolean CCIR601_sampling;
    void* marker_list;
    int max_h_samp_factor;
    int max_v_samp_factor;
    int min_DCT_h_scaled_size;
    int min_DCT_v_scaled_size;
    fj_JDIMENSION total_iMCU_rows;
    fj_JSAMPLE* sample_range_limit;
    int comps_in_scan;
    fj_jpeg_component_info* cur_comp_info[FJ_MAX_COMPS_SCAN];
    fj_JDIMENSION MCUs_per_row;
    fj_JDIMENSION MCU_rows_in_scan;
    int blocks_in_MCU;
    int MCU_membership[10];
    int Ss, Se, Ah, Al;
    int block_size;
    const int* natural_order;
    int lim_Se;
    int unread_marker;
    void* master;
    void* main;
    void* coef;
    void* post;
    void* inputctl;
    void* marker;
    void* entropy;
    void* idct;
    void* upsample;
    void* cconvert;
    void* cquantize;
};

/* Standard libjpeg v62 (JPEG_LIB_VERSION == 62, IJG 6b / Fedora/RHEL/Arch/openSUSE libjpeg.so.62) */
typedef struct {
    int component_id;
    int component_index;
    int h_samp_factor;
    int v_samp_factor;
    int quant_tbl_no;
    int dc_tbl_no;
    int ac_tbl_no;
    fj_JDIMENSION width_in_blocks;
    fj_JDIMENSION height_in_blocks;
    int DCT_scaled_size;
    fj_JDIMENSION downsampled_width;
    fj_JDIMENSION downsampled_height;
    fj_boolean component_needed;
    int MCU_width;
    int MCU_height;
    int MCU_blocks;
    int MCU_sample_width;
    int last_col_width;
    int last_row_height;
    fj_JQUANT_TBL* quant_table;
    void* dct_table;
} fj62_jpeg_component_info;

struct fj62_jpeg_compress_struct {
    struct fj_jpeg_error_mgr* err;
    void* mem;
    void* progress;
    void* client_data;
    fj_boolean is_decompressor;
    int global_state;

    void* dest;
    fj_JDIMENSION image_width;
    fj_JDIMENSION image_height;
    int input_components;
    fj_J_COLOR_SPACE in_color_space;
    double input_gamma;

    int data_precision;
    int num_components;
    fj_J_COLOR_SPACE jpeg_color_space;
    fj62_jpeg_component_info* comp_info;
    fj_JQUANT_TBL* quant_tbl_ptrs[FJ_NUM_QUANT_TBLS];
    fj_JHUFF_TBL* dc_huff_tbl_ptrs[FJ_NUM_HUFF_TBLS];
    fj_JHUFF_TBL* ac_huff_tbl_ptrs[FJ_NUM_HUFF_TBLS];
    uint8_t arith_dc_L[FJ_NUM_ARITH_TBLS];
    uint8_t arith_dc_U[FJ_NUM_ARITH_TBLS];
    uint8_t arith_ac_K[FJ_NUM_ARITH_TBLS];
    int num_scans;
    const fj_jpeg_scan_info* scan_info;
    fj_boolean raw_data_in;
    fj_boolean arith_code;
    fj_boolean optimize_coding;
    fj_boolean CCIR601_sampling;
    int smoothing_factor;
    fj_J_DCT_METHOD dct_method;
    unsigned int restart_interval;
    int restart_in_rows;
    fj_boolean write_JFIF_header;
    uint8_t JFIF_major_version;
    uint8_t JFIF_minor_version;
    uint8_t density_unit;
    uint16_t X_density;
    uint16_t Y_density;
    fj_boolean write_Adobe_marker;
    fj_JDIMENSION next_scanline;
    fj_boolean progressive_mode;
    int max_h_samp_factor;
    int max_v_samp_factor;
    fj_JDIMENSION total_iMCU_rows;
    int comps_in_scan;
    fj62_jpeg_component_info* cur_comp_info[FJ_MAX_COMPS_SCAN];
    fj_JDIMENSION MCUs_per_row;
    fj_JDIMENSION MCU_rows_in_scan;
    int blocks_in_MCU;
    int MCU_membership[10];
    int Ss, Se, Ah, Al;
    void* master;
    void* main;
    void* prep;
    void* coef;
    void* marker;
    void* cconvert;
    void* downsample;
    void* fdct;
    void* entropy;
    fj_jpeg_scan_info* script_space;
    int script_space_size;
};

struct fj62_jpeg_decompress_struct {
    struct fj_jpeg_error_mgr* err;
    void* mem;
    void* progress;
    void* client_data;
    fj_boolean is_decompressor;
    int global_state;

    void* src;
    fj_JDIMENSION image_width;
    fj_JDIMENSION image_height;
    int num_components;
    fj_J_COLOR_SPACE jpeg_color_space;
    fj_J_COLOR_SPACE out_color_space;
    unsigned int scale_num, scale_denom;
    double output_gamma;
    fj_boolean buffered_image;
    fj_boolean raw_data_out;
    fj_J_DCT_METHOD dct_method;
    fj_boolean do_fancy_upsampling;
    fj_boolean do_block_smoothing;
    fj_boolean quantize_colors;
    fj_J_DITHER_MODE dither_mode;
    fj_boolean two_pass_quantize;
    int desired_number_of_colors;
    fj_boolean enable_1pass_quant;
    fj_boolean enable_external_quant;
    fj_boolean enable_2pass_quant;
    fj_JDIMENSION output_width;
    fj_JDIMENSION output_height;
    int out_color_components;
    int output_components;
    int rec_outbuf_height;
    int actual_number_of_colors;
    fj_JSAMPARRAY colormap;
    fj_JDIMENSION output_scanline;
    int input_scan_number;
    fj_JDIMENSION input_iMCU_row;
    int output_scan_number;
    fj_JDIMENSION output_iMCU_row;
    int (*coef_bits)[FJ_DCTSIZE2];
    fj_JQUANT_TBL* quant_tbl_ptrs[FJ_NUM_QUANT_TBLS];
    fj_JHUFF_TBL* dc_huff_tbl_ptrs[FJ_NUM_HUFF_TBLS];
    fj_JHUFF_TBL* ac_huff_tbl_ptrs[FJ_NUM_HUFF_TBLS];
    int data_precision;
    fj62_jpeg_component_info* comp_info;
    fj_boolean progressive_mode;
    fj_boolean arith_code;
    uint8_t arith_dc_L[FJ_NUM_ARITH_TBLS];
    uint8_t arith_dc_U[FJ_NUM_ARITH_TBLS];
    uint8_t arith_ac_K[FJ_NUM_ARITH_TBLS];
    unsigned int restart_interval;
    fj_boolean saw_JFIF_marker;
    uint8_t JFIF_major_version;
    uint8_t JFIF_minor_version;
    uint8_t density_unit;
    uint16_t X_density;
    uint16_t Y_density;
    fj_boolean saw_Adobe_marker;
    uint8_t Adobe_transform;
    fj_boolean CCIR601_sampling;
    void* marker_list;
    int max_h_samp_factor;
    int max_v_samp_factor;
    int min_DCT_scaled_size;
    fj_JDIMENSION total_iMCU_rows;
    fj_JSAMPLE* sample_range_limit;
    int comps_in_scan;
    fj62_jpeg_component_info* cur_comp_info[FJ_MAX_COMPS_SCAN];
    fj_JDIMENSION MCUs_per_row;
    fj_JDIMENSION MCU_rows_in_scan;
    int blocks_in_MCU;
    int MCU_membership[10];
    int Ss, Se, Ah, Al;
    int unread_marker;
    void* master;
    void* main;
    void* coef;
    void* post;
    void* inputctl;
    void* marker;
    void* entropy;
    void* idct;
    void* upsample;
    void* cconvert;
    void* cquantize;
};

/* TurboJPEG C API types (opaque handle, ABI-stable across Linux & Windows) */
typedef void* tjhandle;
enum {
    FJ_TJSAMP_444 = 0,
    FJ_TJSAMP_422 = 1,
    FJ_TJSAMP_420 = 2,
    FJ_TJSAMP_GRAY = 3
};
enum {
    FJ_TJPF_RGB = 0,
    FJ_TJPF_BGR = 1,
    FJ_TJPF_GRAY = 6
};

typedef struct {
    int has_libjpeg8;
    int has_libjpeg62;
    int has_turbojpeg;
    int32_t simd_caps;
    int32_t cpu_cores;
    char backend_name[128];

    /* libjpeg v80 / v62 function pointers (struct pointer passed as void* for clean ABI dispatch) */
    struct fj_jpeg_error_mgr* (*std_error)(struct fj_jpeg_error_mgr*);
    void (*create_compress)(void*, int, size_t);
    void (*destroy_compress)(void*);
    void (*mem_dest)(void*, unsigned char**, unsigned long*);
    void (*set_defaults)(void*);
    void (*set_quality)(void*, int, fj_boolean);
    void (*start_compress)(void*, fj_boolean);
    fj_JDIMENSION (*write_raw_data)(void*, fj_JSAMPIMAGE, fj_JDIMENSION);
    fj_JDIMENSION (*write_scanlines)(void*, fj_JSAMPARRAY, fj_JDIMENSION);
    void (*finish_compress)(void*);

    void (*create_decompress)(void*, int, size_t);
    void (*destroy_decompress)(void*);
    void (*mem_src)(void*, const unsigned char*, unsigned long);
    int  (*read_header)(void*, fj_boolean);
    fj_boolean (*start_decompress)(void*);
    fj_JDIMENSION (*read_scanlines)(void*, fj_JSAMPARRAY, fj_JDIMENSION);
    fj_boolean (*finish_decompress)(void*);

    /* TurboJPEG function pointers (optional, preferred on Windows when turbojpeg.dll present) */
    tjhandle (*tj_init_compress)(void);
    tjhandle (*tj_init_decompress)(void);
    int (*tj_destroy)(tjhandle);
    void (*tj_free)(unsigned char*);
    int (*tj_compress2)(tjhandle, const unsigned char*, int, int, int, int,
                        unsigned char**, unsigned long*, int, int, int);
    int (*tj_compress_from_yuv_planes)(tjhandle, const unsigned char**, int, const int*, int, int,
                                       unsigned char**, unsigned long*, int, int);
    int (*tj_decompress_header3)(tjhandle, const unsigned char*, unsigned long,
                                 int*, int*, int*, int*);
    int (*tj_decompress2)(tjhandle, const unsigned char*, unsigned long,
                          unsigned char*, int, int, int, int, int);
} fuji_codec_runtime_t;

static fuji_codec_runtime_t g_rt;

struct fj_err_ctx {
    struct fj_jpeg_error_mgr pub;
    jmp_buf env;
};

static void fj_jpeg_error_exit(fj_j_common_ptr cinfo) {
    struct fj_err_ctx* ctx = (struct fj_err_ctx*)cinfo->err;
    longjmp(ctx->env, 1);
}

static void fj_jpeg_emit_message(fj_j_common_ptr cinfo, int msg_level) {
    (void)cinfo;
    (void)msg_level;
}

static void* load_sym(void* lib, const char* name) {
#if defined(_WIN32)
    return (void*)(uintptr_t)GetProcAddress((HMODULE)lib, name);
#else
    return dlsym(lib, name);
#endif
}

static void* open_dyn_lib(const char* name) {
#if defined(_WIN32)
    return (void*)LoadLibraryA(name);
#else
    return dlopen(name, RTLD_LAZY | RTLD_LOCAL);
#endif
}

static void close_dyn_lib(void* lib) {
    if (!lib) return;
#if defined(_WIN32)
    FreeLibrary((HMODULE)lib);
#else
    dlclose(lib);
#endif
}

static void clear_libjpeg_syms(void) {
    g_rt.std_error = NULL;
    g_rt.create_compress = NULL;
    g_rt.destroy_compress = NULL;
    g_rt.mem_dest = NULL;
    g_rt.set_defaults = NULL;
    g_rt.set_quality = NULL;
    g_rt.start_compress = NULL;
    g_rt.write_raw_data = NULL;
    g_rt.write_scanlines = NULL;
    g_rt.finish_compress = NULL;
    g_rt.create_decompress = NULL;
    g_rt.destroy_decompress = NULL;
    g_rt.mem_src = NULL;
    g_rt.read_header = NULL;
    g_rt.start_decompress = NULL;
    g_rt.read_scanlines = NULL;
    g_rt.finish_decompress = NULL;
}

static int try_load_libjpeg_abi(const char* libname, int want_version) {
    if (!libname || !libname[0]) return 0;
    void* h = open_dyn_lib(libname);
    if (!h) return 0;

    g_rt.std_error          = (struct fj_jpeg_error_mgr* (*)(struct fj_jpeg_error_mgr*))load_sym(h, "jpeg_std_error");
    g_rt.create_compress    = (void (*)(void*, int, size_t))load_sym(h, "jpeg_CreateCompress");
    g_rt.destroy_compress   = (void (*)(void*))load_sym(h, "jpeg_destroy_compress");
    g_rt.mem_dest           = (void (*)(void*, unsigned char**, unsigned long*))load_sym(h, "jpeg_mem_dest");
    g_rt.set_defaults       = (void (*)(void*))load_sym(h, "jpeg_set_defaults");
    g_rt.set_quality        = (void (*)(void*, int, fj_boolean))load_sym(h, "jpeg_set_quality");
    g_rt.start_compress     = (void (*)(void*, fj_boolean))load_sym(h, "jpeg_start_compress");
    g_rt.write_raw_data     = (fj_JDIMENSION (*)(void*, fj_JSAMPIMAGE, fj_JDIMENSION))load_sym(h, "jpeg_write_raw_data");
    g_rt.write_scanlines    = (fj_JDIMENSION (*)(void*, fj_JSAMPARRAY, fj_JDIMENSION))load_sym(h, "jpeg_write_scanlines");
    g_rt.finish_compress    = (void (*)(void*))load_sym(h, "jpeg_finish_compress");

    g_rt.create_decompress  = (void (*)(void*, int, size_t))load_sym(h, "jpeg_CreateDecompress");
    g_rt.destroy_decompress = (void (*)(void*))load_sym(h, "jpeg_destroy_decompress");
    g_rt.mem_src            = (void (*)(void*, const unsigned char*, unsigned long))load_sym(h, "jpeg_mem_src");
    g_rt.read_header        = (int (*)(void*, fj_boolean))load_sym(h, "jpeg_read_header");
    g_rt.start_decompress   = (fj_boolean (*)(void*))load_sym(h, "jpeg_start_decompress");
    g_rt.read_scanlines     = (fj_JDIMENSION (*)(void*, fj_JSAMPARRAY, fj_JDIMENSION))load_sym(h, "jpeg_read_scanlines");
    g_rt.finish_decompress  = (fj_boolean (*)(void*))load_sym(h, "jpeg_finish_decompress");

    if (!g_rt.std_error || !g_rt.create_compress || !g_rt.destroy_compress || !g_rt.mem_dest ||
        !g_rt.set_defaults || !g_rt.set_quality || !g_rt.start_compress || !g_rt.write_raw_data ||
        !g_rt.write_scanlines || !g_rt.finish_compress || !g_rt.create_decompress ||
        !g_rt.destroy_decompress || !g_rt.mem_src || !g_rt.read_header || !g_rt.start_decompress ||
        !g_rt.read_scanlines || !g_rt.finish_decompress) {
        clear_libjpeg_syms();
        close_dyn_lib(h);
        return 0;
    }

    /* Validate ABI version & struct sizes live using setjmp error recovery */
    struct fj_err_ctx jerr;
    if (want_version == FJ_JPEG_LIB_VERSION) {
        struct fj_jpeg_compress_struct cinfo;
        struct fj_jpeg_decompress_struct dinfo;
        memset(&cinfo, 0, sizeof(cinfo));
        memset(&dinfo, 0, sizeof(dinfo));

        cinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;
        if (setjmp(jerr.env) != 0) {
            clear_libjpeg_syms();
            close_dyn_lib(h);
            return 0;
        }
        g_rt.create_compress(&cinfo, FJ_JPEG_LIB_VERSION, sizeof(cinfo));
        g_rt.destroy_compress(&cinfo);

        dinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;
        if (setjmp(jerr.env) != 0) {
            clear_libjpeg_syms();
            close_dyn_lib(h);
            return 0;
        }
        g_rt.create_decompress(&dinfo, FJ_JPEG_LIB_VERSION, sizeof(dinfo));
        g_rt.destroy_decompress(&dinfo);

        g_rt.has_libjpeg8 = 1;
        return 1;
    } else if (want_version == FJ_JPEG_LIB_VERSION_62) {
        struct fj62_jpeg_compress_struct cinfo;
        struct fj62_jpeg_decompress_struct dinfo;
        memset(&cinfo, 0, sizeof(cinfo));
        memset(&dinfo, 0, sizeof(dinfo));

        cinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;
        if (setjmp(jerr.env) != 0) {
            clear_libjpeg_syms();
            close_dyn_lib(h);
            return 0;
        }
        g_rt.create_compress(&cinfo, FJ_JPEG_LIB_VERSION_62, sizeof(cinfo));
        g_rt.destroy_compress(&cinfo);

        dinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;
        if (setjmp(jerr.env) != 0) {
            clear_libjpeg_syms();
            close_dyn_lib(h);
            return 0;
        }
        g_rt.create_decompress(&dinfo, FJ_JPEG_LIB_VERSION_62, sizeof(dinfo));
        g_rt.destroy_decompress(&dinfo);

        g_rt.has_libjpeg62 = 1;
        return 1;
    }

    clear_libjpeg_syms();
    close_dyn_lib(h);
    return 0;
}

static int try_load_libjpeg_any(const char* libname) {
    if (try_load_libjpeg_abi(libname, FJ_JPEG_LIB_VERSION)) return 1;
    if (try_load_libjpeg_abi(libname, FJ_JPEG_LIB_VERSION_62)) return 1;
    return 0;
}

static int try_load_turbojpeg(const char* libname) {
    if (!libname || !libname[0]) return 0;
    void* h = open_dyn_lib(libname);
    if (!h) return 0;

    g_rt.tj_init_compress   = (tjhandle (*)(void))load_sym(h, "tjInitCompress");
    g_rt.tj_init_decompress = (tjhandle (*)(void))load_sym(h, "tjInitDecompress");
    g_rt.tj_destroy         = (int (*)(tjhandle))load_sym(h, "tjDestroy");
    g_rt.tj_free            = (void (*)(unsigned char*))load_sym(h, "tjFree");
    g_rt.tj_compress2       = (int (*)(tjhandle, const unsigned char*, int, int, int, int,
                                       unsigned char**, unsigned long*, int, int, int))load_sym(h, "tjCompress2");
    g_rt.tj_compress_from_yuv_planes = (int (*)(tjhandle, const unsigned char**, int, const int*, int, int,
                                                unsigned char**, unsigned long*, int, int))load_sym(h, "tjCompressFromYUVPlanes");
    g_rt.tj_decompress_header3 = (int (*)(tjhandle, const unsigned char*, unsigned long,
                                          int*, int*, int*, int*))load_sym(h, "tjDecompressHeader3");
    g_rt.tj_decompress2     = (int (*)(tjhandle, const unsigned char*, unsigned long,
                                       unsigned char*, int, int, int, int, int))load_sym(h, "tjDecompress2");

    if (!g_rt.tj_init_compress || !g_rt.tj_init_decompress || !g_rt.tj_destroy || !g_rt.tj_free ||
        !g_rt.tj_compress2 || !g_rt.tj_compress_from_yuv_planes ||
        !g_rt.tj_decompress_header3 || !g_rt.tj_decompress2) {
        g_rt.tj_init_compress = NULL;
        g_rt.tj_init_decompress = NULL;
        g_rt.tj_destroy = NULL;
        g_rt.tj_free = NULL;
        g_rt.tj_compress2 = NULL;
        g_rt.tj_compress_from_yuv_planes = NULL;
        g_rt.tj_decompress_header3 = NULL;
        g_rt.tj_decompress2 = NULL;
        close_dyn_lib(h);
        return 0;
    }
    g_rt.has_turbojpeg = 1;
    return 1;
}

static int get_module_dir(char* out, size_t cap) {
    if (!out || cap < 2) return 0;
    out[0] = '\0';
#if defined(_WIN32)
    HMODULE mod = NULL;
    if (!GetModuleHandleExA(
            GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            (LPCSTR)(uintptr_t)&fuji_codec_abi_version,
            &mod)) {
        return 0;
    }
    DWORD len = GetModuleFileNameA(mod, out, (DWORD)cap);
    if (len == 0 || len >= cap) return 0;
    char* slash = strrchr(out, '\\');
    char* fslash = strrchr(out, '/');
    if (fslash && (!slash || fslash > slash)) slash = fslash;
    if (!slash) return 0;
    *slash = '\0';
    return 1;
#else
    Dl_info info;
    if (!dladdr((const void*)(uintptr_t)&fuji_codec_abi_version, &info) || !info.dli_fname) {
        return 0;
    }
    size_t n = strlen(info.dli_fname);
    if (n == 0 || n >= cap) return 0;
    memcpy(out, info.dli_fname, n + 1);
    char* slash = strrchr(out, '/');
    if (!slash) return 0;
    if (slash == out) {
        out[1] = '\0';
    } else {
        *slash = '\0';
    }
    return 1;
#endif
}

static int join_path(char* dst, size_t cap, const char* dir, const char* name) {
    size_t dlen = strlen(dir);
    size_t nlen = strlen(name);
    if (dlen + 1 + nlen + 1 > cap) return 0;
    memcpy(dst, dir, dlen);
#if defined(_WIN32)
    dst[dlen] = '\\';
#else
    dst[dlen] = '/';
#endif
    memcpy(dst + dlen + 1, name, nlen + 1);
    return 1;
}

static void init_runtime_impl(void) {
    memset(&g_rt, 0, sizeof(g_rt));
    g_rt.cpu_cores = detect_cpu_cores();
    g_rt.simd_caps = detect_simd_caps();

    const char* no_ext = getenv("FUJI_CODEC_NO_EXT_JPEG");
    if (!no_ext || no_ext[0] != '1') {
    /* 1. Explicit user/runtime override via FUJI_JPEG_LIBRARY */
    const char* env_jpeg = getenv("FUJI_JPEG_LIBRARY");
    if (env_jpeg && env_jpeg[0]) {
        if (!try_load_libjpeg_any(env_jpeg)) {
            try_load_turbojpeg(env_jpeg);
        }
    }

    /* 2. Optional bundled JPEG library in the same directory as libfuji_codec */
    char mod_dir[1024];
    char path_buf[1280];
    if (!g_rt.has_libjpeg8 && !g_rt.has_libjpeg62 && !g_rt.has_turbojpeg &&
        get_module_dir(mod_dir, sizeof(mod_dir))) {
#if defined(_WIN32)
        if (join_path(path_buf, sizeof(path_buf), mod_dir, "turbojpeg.dll")) {
            try_load_turbojpeg(path_buf);
        }
        if (!g_rt.has_turbojpeg && join_path(path_buf, sizeof(path_buf), mod_dir, "libjpeg-8.dll")) {
            try_load_libjpeg_abi(path_buf, FJ_JPEG_LIB_VERSION);
        }
        if (!g_rt.has_turbojpeg && !g_rt.has_libjpeg8 && join_path(path_buf, sizeof(path_buf), mod_dir, "libjpeg-62.dll")) {
            try_load_libjpeg_abi(path_buf, FJ_JPEG_LIB_VERSION_62);
        }
#else
        if (join_path(path_buf, sizeof(path_buf), mod_dir, "libjpeg.so.8")) {
            try_load_libjpeg_abi(path_buf, FJ_JPEG_LIB_VERSION);
        }
        if (!g_rt.has_libjpeg8 && join_path(path_buf, sizeof(path_buf), mod_dir, "libjpeg.so.62")) {
            try_load_libjpeg_abi(path_buf, FJ_JPEG_LIB_VERSION_62);
        }
        if (!g_rt.has_libjpeg8 && !g_rt.has_libjpeg62 && join_path(path_buf, sizeof(path_buf), mod_dir, "libturbojpeg.so.0")) {
            try_load_turbojpeg(path_buf);
        }
#endif
    }

    /* 3. System dynamic library search across Ubuntu/Debian (v80), Fedora/RHEL/Arch/openSUSE (v62), and TurboJPEG */
#if defined(_WIN32)
    if (!g_rt.has_turbojpeg && !g_rt.has_libjpeg8 && !g_rt.has_libjpeg62) {
        try_load_turbojpeg("turbojpeg.dll");
    }
    if (!g_rt.has_turbojpeg && !g_rt.has_libjpeg8 && !g_rt.has_libjpeg62) {
        try_load_libjpeg_abi("libjpeg-8.dll", FJ_JPEG_LIB_VERSION);
    }
    if (!g_rt.has_turbojpeg && !g_rt.has_libjpeg8 && !g_rt.has_libjpeg62) {
        try_load_libjpeg_abi("libjpeg-62.dll", FJ_JPEG_LIB_VERSION_62);
    }
#else
    if (!g_rt.has_libjpeg8 && !g_rt.has_libjpeg62) {
        static const char* const kLibjpeg8Candidates[] = {
            "libjpeg.so.8",
            "/usr/lib/x86_64-linux-gnu/libjpeg.so.8",
            "/usr/lib64/libjpeg.so.8",
            "/usr/lib/libjpeg.so.8",
            "libjpeg.so",
            NULL
        };
        for (int i = 0; kLibjpeg8Candidates[i] != NULL; ++i) {
            if (try_load_libjpeg_abi(kLibjpeg8Candidates[i], FJ_JPEG_LIB_VERSION)) break;
        }
    }
    if (!g_rt.has_libjpeg8 && !g_rt.has_libjpeg62) {
        static const char* const kLibjpeg62Candidates[] = {
            "libjpeg.so.62",
            "/usr/lib64/libjpeg.so.62",
            "/usr/lib/x86_64-linux-gnu/libjpeg.so.62",
            "/usr/lib/libjpeg.so.62",
            "libjpeg.so",
            NULL
        };
        for (int i = 0; kLibjpeg62Candidates[i] != NULL; ++i) {
            if (try_load_libjpeg_abi(kLibjpeg62Candidates[i], FJ_JPEG_LIB_VERSION_62)) break;
        }
    }
    if (!g_rt.has_turbojpeg) {
        static const char* const kTurboCandidates[] = {
            "libturbojpeg.so.0",
            "libturbojpeg.so",
            "/usr/lib64/libturbojpeg.so.0",
            "/usr/lib/x86_64-linux-gnu/libturbojpeg.so.0",
            "/usr/lib/libturbojpeg.so.0",
            NULL
        };
        for (int i = 0; kTurboCandidates[i] != NULL; ++i) {
            if (try_load_turbojpeg(kTurboCandidates[i])) break;
        }
    }
#endif
    } /* !FUJI_CODEC_NO_EXT_JPEG */

    const char* simd = (g_rt.simd_caps & FUJI_CODEC_CAP_SIMD_AVX2) ? "avx2" :
                       (g_rt.simd_caps & FUJI_CODEC_CAP_SIMD_SSE2) ? "sse2" : "scalar";
    const char* jtag = g_rt.has_libjpeg8  ? "libjpeg8-yuv422"  :
                       g_rt.has_libjpeg62 ? "libjpeg62-yuv422" :
                       g_rt.has_turbojpeg ? "turbojpeg"        : "builtin-jpeg";

    /* Format backend_name without stdio overhead */
    size_t pos = 0;
    const char* parts[5] = { "native-mt-", simd, "+", jtag, NULL };
    for (int i = 0; parts[i] != NULL; ++i) {
        size_t len = strlen(parts[i]);
        if (pos + len < sizeof(g_rt.backend_name) - 1) {
            memcpy(g_rt.backend_name + pos, parts[i], len);
            pos += len;
        }
    }
    g_rt.backend_name[pos] = '\0';
}

#if defined(_WIN32)
static INIT_ONCE g_init_once = INIT_ONCE_STATIC_INIT;
static BOOL CALLBACK win_init_once_cb(PINIT_ONCE once, PVOID param, PVOID* ctx) {
    (void)once; (void)param; (void)ctx;
    init_runtime_impl();
    return TRUE;
}
static void ensure_runtime_init(void) {
    InitOnceExecuteOnce(&g_init_once, win_init_once_cb, NULL, NULL);
}
#else
static pthread_once_t g_init_once = PTHREAD_ONCE_INIT;
static void ensure_runtime_init(void) {
    pthread_once(&g_init_once, init_runtime_impl);
}
#endif

FUJI_CODEC_EXPORT int32_t fuji_codec_abi_version(void) {
    return FUJI_CODEC_ABI_VERSION;
}

FUJI_CODEC_EXPORT int32_t fuji_codec_capabilities(void) {
    ensure_runtime_init();
    int32_t caps = FUJI_CODEC_CAP_LSRAW_MT | FUJI_CODEC_CAP_JPEG_BUILTIN | g_rt.simd_caps;
    if (g_rt.has_turbojpeg) caps |= FUJI_CODEC_CAP_JPEG_TURBO;
    if (g_rt.has_libjpeg8)  caps |= FUJI_CODEC_CAP_JPEG_LIBJPEG8;
    if (g_rt.has_libjpeg62) caps |= FUJI_CODEC_CAP_JPEG_LIBJPEG62;
    return caps;
}

FUJI_CODEC_EXPORT int32_t fuji_codec_default_threads(void) {
    ensure_runtime_init();
    int32_t n = g_rt.cpu_cores;
    if (n < 1) n = 1;
    if (n > 16) n = 16;
    return n;
}

FUJI_CODEC_EXPORT const char* fuji_codec_backend_name(void) {
    ensure_runtime_init();
    return g_rt.backend_name;
}

FUJI_CODEC_EXPORT void fuji_codec_free(void* ptr) {
    if (ptr) free(ptr);
}

/* ============================================================================
 * 3. Multi-Threaded Fujifilm LSRAW Bayer Strip Codec (Bit-Exact!)
 * ============================================================================ */

#define LSRAW_BLOCK_LINES       6
#define LSRAW_ESCAPE_LIMIT      31
#define LSRAW_RAW_TYPE_BAYER    0
#define LSRAW_LEGACY_RAW_TYPE   16
#define LSRAW_MAX_THREADS       16

static inline int32_t lsraw_header_bytes(int32_t blocks_x) {
    int32_t m = blocks_x - 1;
    if (m < 0) m = 0;
    return (((m >> 2) + 2) << 4);
}

static inline int64_t lsraw_align_output_size(
    int64_t raw_size, int32_t blocks_x, int32_t blocks_y, int32_t lossy_compressed
) {
    int64_t hdr_bytes = (int64_t)lsraw_header_bytes(blocks_x);
    int64_t base_offset;
    if (!lossy_compressed) {
        base_offset = hdr_bytes;
    } else {
        int32_t by_m1 = (blocks_y - 1 > 0) ? (blocks_y - 1) : 0;
        base_offset = hdr_bytes + (int64_t)((by_m1 >> 4) + 1) * ((int64_t)blocks_x << 4);
    }
    int64_t min_req = (raw_size > base_offset + 256LL) ? raw_size : (base_offset + 256LL);
    int64_t delta = min_req - base_offset;
    int64_t aligned_delta = (delta + 255LL) & ~255LL;
    return base_offset + aligned_delta;
}

static inline int32_t lsraw_median_predict(int32_t a, int32_t b, int32_t c) {
    int32_t mn = (a < b) ? a : b;
    int32_t mx = (a > b) ? a : b;
    if (c >= mx) return mn;
    if (c <= mn) return mx;
    return a + b - c;
}

static inline int32_t lsraw_bit_diff(int32_t a, int32_t n) {
    if (n >= a) return 0;
#if defined(__GNUC__) || defined(__clang__)
    int32_t k = __builtin_clz((unsigned int)n) - __builtin_clz((unsigned int)a);
    if ((n << k) < a) k++;
    return (k > 15) ? 15 : k;
#else
    int32_t k = 0;
    int32_t scaled = n;
    while (scaled < a && k < 15) {
        scaled <<= 1;
        k++;
    }
    return k;
#endif
}

typedef struct {
    uint8_t* data;
    size_t   len;
    size_t   cap;
    int      oom;
} lsraw_strip_buf_t;

static int lsraw_strip_buf_reserve(lsraw_strip_buf_t* sb, size_t extra) {
    if (sb->len + extra <= sb->cap) return 1;
    size_t next_cap = sb->cap ? sb->cap * 2 : 65536;
    while (next_cap < sb->len + extra) {
        if (next_cap > (size_t)INT32_MAX / 2) {
            sb->oom = 1;
            return 0;
        }
        next_cap *= 2;
    }
    uint8_t* p = (uint8_t*)realloc(sb->data, next_cap);
    if (!p) {
        sb->oom = 1;
        return 0;
    }
    sb->data = p;
    sb->cap = next_cap;
    return 1;
}

typedef struct {
    const uint8_t* src_raw_le16;
    int32_t src_stride_bytes;
    int32_t active_width;
    int32_t active_height;
    int32_t blocks_x;
    int32_t blocks_y;
    int32_t strip_pixels;
    int32_t raw_bits;
    int32_t lossy_compressed;
    lsraw_strip_buf_t* strips; /* array of length blocks_x */
    int32_t num_threads;
} lsraw_enc_job_t;

typedef struct {
    const lsraw_enc_job_t* job;
    int32_t thread_idx;
} lsraw_enc_worker_arg_t;

/* Fuji Bayer strips share adaptive residual statistics between the two CFA
 * colors of a physical row. Statistics are independent per strip, parity and
 * row modulo three. Prediction uses the two preceding lines of that color. */
typedef struct {
    int sum;
    int count;
} lsraw_context_t;

typedef struct {
    lsraw_strip_buf_t *output;
    const uint8_t *input;
    size_t input_size;
    size_t position;
    uint64_t bits;
    int available;
    int failed;
} lsraw_bits_t;

static inline void lsraw_put_bits(lsraw_bits_t *io, int count, uint32_t value) {
    io->bits = (io->bits << count) | value;
    io->available += count;
    while (io->available >= 8) {
        io->available -= 8;
        io->output->data[io->output->len++] = (uint8_t)(io->bits >> io->available);
    }
}

static inline uint32_t lsraw_get_bits(lsraw_bits_t *io, int count) {
    while (io->available < count) {
        if (io->position == io->input_size) {
            io->failed = 1;
            return 0;
        }
        io->bits = (io->bits << 8) | io->input[io->position++];
        io->available += 8;
    }
    io->available -= count;
    return (uint32_t)(io->bits >> io->available) & ((1u << count) - 1u);
}

static inline int lsraw_gradient_bin(int diff) {
    int magnitude = abs(diff);
    int bin = (magnitude > 0) + (magnitude >= 18) + (magnitude >= 67) + (magnitude >= 276);
    return diff < 0 ? -bin : bin;
}

static inline int lsraw_bayer_sample(lsraw_bits_t *io, int depth, int odd, int x,
        uint16_t *current, const uint16_t *previous, const uint16_t *older,
        lsraw_context_t *contexts, int value) {
    int center = previous[x], left = previous[x - 1], right = previous[x + 1];
    int prediction, gradient;
    if (odd) {
        int sides = current[x - 1] + current[x + 1];
        prediction = ((center > left && center > right) || (center < left && center < right))
            ? (sides + 2 * center) / 4 : sides / 2;
        gradient = 9 * lsraw_gradient_bin(center - left) + lsraw_gradient_bin(left - current[x - 1]);
    } else {
        int up = older[x], dl = abs(left - center), dr = abs(right - center), du = abs(up - center);
        int sides = dl > du && dl > dr ? up + right : dr > dl && dr > du ? up + left : left + right;
        prediction = (2 * center + sides) / 4;
        gradient = 9 * lsraw_gradient_bin(center - up) + lsraw_gradient_bin(left - center);
    }
    lsraw_context_t *ctx = &contexts[abs(gradient)];
    int k = lsraw_bit_diff(ctx->sum, ctx->count);
    int escape = 3 * depth - 1, range = 1 << depth, mask = range - 1;
    int residual;
    if (io->output) {
        int directed = gradient < 0 ? prediction - value : value - prediction;
        residual = ((directed + range / 2) & mask) - range / 2;
        uint32_t code = residual < 0 ? (uint32_t)(-2 * residual - 1) : (uint32_t)(2 * residual);
        uint32_t quotient = code >> k;
        if (quotient < (unsigned)escape) {
            lsraw_put_bits(io, (int)quotient + 1, 1);
            lsraw_put_bits(io, k, code & ((1u << k) - 1u));
        } else {
            lsraw_put_bits(io, escape + 1, 1);
            lsraw_put_bits(io, depth, code - 1);
        }
    } else {
        int zeros = 0;
        while (!lsraw_get_bits(io, 1)) {
            if (io->failed || ++zeros > escape) { io->failed = 1; return 0; }
        }
        uint32_t code = zeros == escape ? lsraw_get_bits(io, depth) + 1
            : ((uint32_t)zeros << k) | lsraw_get_bits(io, k);
        if (code > (unsigned)mask) { io->failed = 1; return 0; }
        residual = code & 1 ? -(int)(code / 2) - 1 : (int)(code / 2);
        value = (prediction + (gradient < 0 ? -residual : residual)) & mask;
    }
    ctx->sum += abs(residual);
    if (ctx->count == 64) { ctx->sum /= 2; ctx->count /= 2; }
    ++ctx->count;
    current[x] = (uint16_t)value;
    return value;
}

static int lsraw_process_bayer_strip(lsraw_bits_t *io, int depth, int lossy,
        int strip_width, int strip_index, int bands, int width, int height, int stride,
        const uint8_t *source, uint8_t *destination) {
    const int n = strip_width / 2;
    if (strip_width < 4 || (strip_width % 4) != 0) return 0;
    int pitch = n + 2;
    uint16_t *storage = (uint16_t*)calloc((size_t)9 * pitch, sizeof(uint16_t));
    if (!storage) return 0;
    uint16_t *lines[3][3];
    for (int c = 0; c < 3; ++c)
        for (int r = 0; r < 3; ++r) lines[c][r] = storage + (3 * c + r) * pitch + 1;
    lsraw_context_t contexts[2][3][41];
    for (int p = 0; p < 2; ++p)
        for (int r = 0; r < 3; ++r)
            for (int i = 0; i < 41; ++i) contexts[p][r][i] = (lsraw_context_t){(1 << depth) / 64, 1};
    const int mask = ((1 << depth) - 1) & (lossy ? ~3 : ~0);
    int x_base = strip_index * strip_width;
    for (int y = 0; y < bands * 6 && !io->failed; ++y) {
        /* Maximum of 4*depth bits/pixel including the escape prefix. */
        if (io->output && !lsraw_strip_buf_reserve(io->output, (size_t)strip_width * 8 + 32)) {
            io->failed = 1; break;
        }
        int first_color = y & 1;
        for (int p = 0; p < 2; ++p) {
            uint16_t **l = lines[first_color + p];
            l[2][-1] = l[1][0];
            l[2][n] = l[1][n - 1];
        }
        const uint8_t *src_row = source && y < height ? source + (size_t)y * stride : NULL;
        uint8_t *dst_row = destination && y < height ? destination + (size_t)y * stride : NULL;
        for (int t = 0; t < n + 8; t += 2) {
            for (int odd = 0; odd <= 1; ++odd) {
                int x = odd ? t - 7 : t;
                if (x < 0 || x >= n) continue;
                for (int p = 0; p < 2; ++p) {
                    int pixel = x_base + x * 2 + p, input = 0;
                    if (src_row) {
                        int sx = pixel < width ? pixel : width - 2 + p;
                        if (pixel < width || width - x_base >= 2) {
                            input = (src_row[2*sx] | (src_row[2*sx+1] << 8)) & mask;
                        }
                    }
                    uint16_t **l = lines[first_color + p];
                    int out = lsraw_bayer_sample(io, depth, odd, x, l[2], l[1], l[0],
                        contexts[odd][y % 3], input);
                    if (dst_row && pixel < width) {
                        dst_row[2*pixel] = (uint8_t)out;
                        dst_row[2*pixel+1] = (uint8_t)(out >> 8);
                    }
                }
            }
        }
        for (int p = 0; p < 2; ++p) {
            uint16_t **l = lines[first_color + p];
            uint16_t *spare = l[0]; l[0] = l[1]; l[1] = l[2]; l[2] = spare;
        }
    }
    if (io->output && !io->failed) {
        if (io->available) lsraw_put_bits(io, 8 - io->available, 0);
        while (io->output->len % 16) io->output->data[io->output->len++] = 0;
    }
    free(storage);
    return !io->failed;
}

static void lsraw_encode_one_strip(const lsraw_enc_job_t* job, int32_t strip_idx) {
    lsraw_bits_t io = {0};
    io.output = &job->strips[strip_idx];
    if (!lsraw_process_bayer_strip(&io, job->raw_bits, job->lossy_compressed, job->strip_pixels,
            strip_idx, job->blocks_y, job->active_width, job->active_height, job->src_stride_bytes,
            job->src_raw_le16, NULL)) io.output->oom = 1;
}

#if defined(_WIN32)
static unsigned __stdcall lsraw_enc_worker_fn(void* arg) {
    lsraw_enc_worker_arg_t* w = (lsraw_enc_worker_arg_t*)arg;
    for (int32_t s = w->thread_idx; s < w->job->blocks_x; s += w->job->num_threads) {
        lsraw_encode_one_strip(w->job, s);
    }
    return 0;
}
#else
static void* lsraw_enc_worker_fn(void* arg) {
    lsraw_enc_worker_arg_t* w = (lsraw_enc_worker_arg_t*)arg;
    for (int32_t s = w->thread_idx; s < w->job->blocks_x; s += w->job->num_threads) {
        lsraw_encode_one_strip(w->job, s);
    }
    return NULL;
}
#endif

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
) {
    if (!src_raw_le16 || !dst_stream || active_width <= 0 || active_height <= 0 ||
        blocks_x <= 0 || blocks_x > 255 || blocks_y <= 0 || blocks_y > 65535 ||
        strip_pixels < 4 || strip_pixels % 4 || active_width > 65535 || active_height > 65535 ||
        (int64_t)strip_pixels * blocks_x > 65535 || (int64_t)strip_pixels * blocks_x < active_width ||
        (int64_t)blocks_y * 6 < active_height || src_stride_bytes < active_width * 2 || (raw_bits != 12 && raw_bits != 14 && raw_bits != 16) ||
        dst_capacity <= 0) {
        return FUJI_CODEC_ERR_INVALID_ARGS;
    }

    ensure_runtime_init();

    int32_t n_threads = (max_threads > 0) ? max_threads : fuji_codec_default_threads();
    if (n_threads > LSRAW_MAX_THREADS) n_threads = LSRAW_MAX_THREADS;
    if (n_threads > blocks_x) n_threads = blocks_x;
    /* Avoid thread spawn overhead on tiny test frames */
    if ((int64_t)active_width * (int64_t)active_height < 65536LL) {
        n_threads = 1;
    }

    lsraw_strip_buf_t* strips = (lsraw_strip_buf_t*)calloc((size_t)blocks_x, sizeof(lsraw_strip_buf_t));
    if (!strips) return FUJI_CODEC_ERR_CODEC;

    lsraw_enc_job_t job;
    job.src_raw_le16     = src_raw_le16;
    job.src_stride_bytes = src_stride_bytes;
    job.active_width     = active_width;
    job.active_height    = active_height;
    job.blocks_x         = blocks_x;
    job.blocks_y         = blocks_y;
    job.strip_pixels     = strip_pixels;
    job.raw_bits         = raw_bits;
    job.lossy_compressed = lossy_compressed ? 1 : 0;
    job.strips           = strips;
    job.num_threads      = n_threads;

    if (n_threads <= 1) {
        for (int32_t s = 0; s < blocks_x; ++s) {
            lsraw_encode_one_strip(&job, s);
        }
    } else {
        lsraw_enc_worker_arg_t args[LSRAW_MAX_THREADS];
#if defined(_WIN32)
        HANDLE threads[LSRAW_MAX_THREADS];
        int spawned = 0;
        for (int32_t t = 1; t < n_threads; ++t) {
            args[t].job = &job;
            args[t].thread_idx = t;
            uintptr_t h = _beginthreadex(NULL, 0, lsraw_enc_worker_fn, &args[t], 0, NULL);
            if (h != 0) {
                threads[spawned++] = (HANDLE)h;
            } else {
                /* Fallback: execute on main thread */
                lsraw_enc_worker_fn(&args[t]);
            }
        }
        args[0].job = &job;
        args[0].thread_idx = 0;
        lsraw_enc_worker_fn(&args[0]);
        if (spawned > 0) {
            WaitForMultipleObjects((DWORD)spawned, threads, TRUE, INFINITE);
            for (int i = 0; i < spawned; ++i) CloseHandle(threads[i]);
        }
#else
        pthread_t threads[LSRAW_MAX_THREADS];
        int spawned[LSRAW_MAX_THREADS];
        memset(spawned, 0, sizeof(spawned));
        for (int32_t t = 1; t < n_threads; ++t) {
            args[t].job = &job;
            args[t].thread_idx = t;
            if (pthread_create(&threads[t], NULL, lsraw_enc_worker_fn, &args[t]) == 0) {
                spawned[t] = 1;
            } else {
                lsraw_enc_worker_fn(&args[t]);
            }
        }
        args[0].job = &job;
        args[0].thread_idx = 0;
        lsraw_enc_worker_fn(&args[0]);
        for (int32_t t = 1; t < n_threads; ++t) {
            if (spawned[t]) pthread_join(threads[t], NULL);
        }
#endif
    }

    int32_t hdr_bytes = lsraw_header_bytes(blocks_x);
    int32_t data_start = hdr_bytes + (lossy_compressed ? ((blocks_y + 15) & ~15) * blocks_x : 0);
    int64_t total_payload = data_start;
    int any_oom = 0;
    for (int32_t s = 0; s < blocks_x; ++s) {
        if (strips[s].oom) any_oom = 1;
        total_payload += (int64_t)strips[s].len;
    }

    if (any_oom || total_payload > (int64_t)INT32_MAX - 512) {
        for (int32_t s = 0; s < blocks_x; ++s) free(strips[s].data);
        free(strips);
        return FUJI_CODEC_ERR_CODEC;
    }

    int64_t target_total = lsraw_align_output_size(
        total_payload, blocks_x, blocks_y, lossy_compressed ? 1 : 0
    );
    if (target_total <= 0 || target_total > (int64_t)INT32_MAX) {
        for (int32_t s = 0; s < blocks_x; ++s) free(strips[s].data);
        free(strips);
        return FUJI_CODEC_ERR_CODEC;
    }
    int32_t final_size = (int32_t)target_total;
    if (out_required_bytes) *out_required_bytes = final_size;

    if (final_size > dst_capacity || (max_cap_bytes > 0 && final_size > max_cap_bytes)) {
        for (int32_t s = 0; s < blocks_x; ++s) free(strips[s].data);
        free(strips);
        return FUJI_CODEC_ERR_OVERFLOW;
    }

    /* Assemble container header + strip table + concatenated strips + 256-byte tail pad */
    memset(dst_stream, 0, (size_t)data_start);
    dst_stream[0] = (uint8_t)'I';
    dst_stream[1] = (uint8_t)'S';
    dst_stream[2] = (uint8_t)(lossy_compressed ? 0 : 1);
    dst_stream[3] = (uint8_t)LSRAW_RAW_TYPE_BAYER;
    dst_stream[4] = (uint8_t)raw_bits;
    write_u16_be(dst_stream + 5, (uint16_t)active_height);
    write_u16_be(dst_stream + 7, (uint16_t)(blocks_x * strip_pixels));
    write_u16_be(dst_stream + 9, (uint16_t)active_width);
    write_u16_be(dst_stream + 11, (uint16_t)strip_pixels);
    dst_stream[13] = (uint8_t)blocks_x;
    write_u16_be(dst_stream + 14, (uint16_t)blocks_y);

    int32_t cursor = data_start;
    for (int32_t s = 0; s < blocks_x; ++s) {
        int32_t slen = (int32_t)strips[s].len;
        int32_t reported_len = s == blocks_x - 1 ? final_size - cursor : slen;
        write_u32_be(dst_stream + 16 + s * 4, (uint32_t)reported_len);
        memcpy(dst_stream + cursor, strips[s].data, (size_t)slen);
        cursor += slen;
        free(strips[s].data);
    }
    free(strips);

    if (final_size > cursor) {
        memset(dst_stream + cursor, 0, (size_t)(final_size - cursor));
    }
    return final_size;
}

/* ---- LSRAW Multi-Threaded Strip Decoder ---- */

typedef struct {
    const uint8_t* src_stream;
    const int32_t* strip_offsets;
    const int32_t* strip_lengths;
    uint8_t* dst_raw_le16;
    int32_t dst_stride_bytes;
    int32_t active_width;
    int32_t active_height;
    int32_t blocks_x;
    int32_t blocks_y;
    int32_t strip_pixels;
    int32_t raw_bits;
    int32_t lossy_compressed;
    int32_t num_threads;
    uint8_t strip_errors[256];
} lsraw_dec_job_t;

typedef struct {
    lsraw_dec_job_t* job;
    int32_t thread_idx;
} lsraw_dec_worker_arg_t;

static void lsraw_decode_legacy_strip(lsraw_dec_job_t* job, int32_t strip_idx) {
    const int32_t strip_pixels = job->strip_pixels;
    const int32_t blocks_y     = job->blocks_y;
    const int32_t active_w     = job->active_width;
    const int32_t active_h     = job->active_height;
    const int32_t dst_stride   = job->dst_stride_bytes;
    const int32_t raw_bits     = job->raw_bits;
    const int32_t q_shift      = job->lossy_compressed ? 2 : 0;
    const int32_t eff_bits     = (raw_bits - q_shift > 10) ? (raw_bits - q_shift) : 10;
    const int32_t eff_range    = 1 << eff_bits;
    const int32_t half_range   = eff_range >> 1;
    const int32_t eff_mask     = eff_range - 1;
    const int32_t grad_shift   = (eff_bits - 12 > 0) ? (eff_bits - 12) : 0;
    const int32_t x_base       = strip_idx * strip_pixels;
    int32_t strip_active_w     = active_w - x_base;
    if (strip_active_w < 0) strip_active_w = 0;
    if (strip_active_w > strip_pixels) strip_active_w = strip_pixels;

    const uint8_t* p   = job->src_stream + job->strip_offsets[strip_idx];
    const uint8_t* end = p + job->strip_lengths[strip_idx];

    int32_t* block = (int32_t*)malloc((size_t)(LSRAW_BLOCK_LINES + 2) * (size_t)strip_pixels * sizeof(int32_t));
    if (!block) {
        job->strip_errors[strip_idx] = 1;
        return;
    }
    int32_t* prev_rows = block + (size_t)LSRAW_BLOCK_LINES * (size_t)strip_pixels;
    memset(prev_rows, 0, (size_t)2u * (size_t)strip_pixels * sizeof(int32_t));

    int32_t grad_n[16];
    int32_t grad_a[16];
    int32_t init_a = (eff_range + 32) >> 6;
    if (init_a < 4) init_a = 4;
    for (int i = 0; i < 16; ++i) {
        grad_n[i] = 1;
        grad_a[i] = init_a;
    }

    uint64_t bit_buf = 0;
    int bit_cnt = 0;

    for (int32_t by = 0; by < blocks_y; ++by) {
        int32_t y_base = by * LSRAW_BLOCK_LINES;

        for (int32_t r = 0; r < LSRAW_BLOCK_LINES; ++r) {
            int32_t* cur_row = block + (size_t)r * (size_t)strip_pixels;
            const int32_t has_prev2 = (by > 0) || (r >= 2);
            const int32_t* prev2 = (r >= 2)
                ? (block + (size_t)(r - 2) * (size_t)strip_pixels)
                : (has_prev2 ? (prev_rows + (size_t)r * (size_t)strip_pixels) : NULL);
            const int32_t row_color_base = (r & 1) << 1;

            for (int32_t x = 0; x < strip_pixels; ++x) {
                /* Refill bit_buf to >= 48 bits whenever bytes remain (max pixel code is 48 bits!) */
                if (bit_cnt < 48) {
                    if (p + 8 <= end) {
                        while (bit_cnt <= 56) {
                            bit_buf |= ((uint64_t)(*p++)) << (56 - bit_cnt);
                            bit_cnt += 8;
                        }
                    } else {
                        while (bit_cnt <= 56 && p < end) {
                            bit_buf |= ((uint64_t)(*p++)) << (56 - bit_cnt);
                            bit_cnt += 8;
                        }
                    }
                }

                int32_t color_idx = row_color_base | (x & 1);
                int32_t left = (x >= 2) ? cur_row[x - 2] : (prev2 ? prev2[x] : 0);
                int32_t top  = prev2 ? prev2[x] : left;
                int32_t top_left = (prev2 && x >= 2) ? prev2[x - 2] : top;
                int32_t pred = lsraw_median_predict(left, top, top_left);

                int32_t d1 = left - top_left;
                if (d1 < 0) d1 = -d1;
                int32_t d2 = top - top_left;
                if (d2 < 0) d2 = -d2;
                int32_t g = (d1 + d2) >> grad_shift;
                int32_t bucket = (g < 4) ? 0 : (g < 16) ? 1 : (g < 64) ? 2 : 3;
                int32_t ctx = (color_idx << 2) | bucket;

                int32_t k = lsraw_bit_diff(grad_a[ctx], grad_n[ctx]);

                int32_t q;
                if (bit_buf == 0ULL) {
                    q = LSRAW_ESCAPE_LIMIT;
                } else {
#if defined(__GNUC__) || defined(__clang__)
                    q = __builtin_clzll(bit_buf);
#else
                    q = 0;
                    uint64_t tmp = bit_buf;
                    while (q < LSRAW_ESCAPE_LIMIT && (tmp & (1ULL << 63)) == 0ULL) {
                        tmp <<= 1;
                        q++;
                    }
#endif
                }

                int32_t cur;
                int32_t diff;
                if (q >= LSRAW_ESCAPE_LIMIT) {
                    bit_buf <<= 32;
                    bit_cnt -= 32;
                    if (bit_cnt < 0) bit_cnt = 0;
                    if (bit_cnt < eff_bits) {
                        while (bit_cnt <= 56 && p < end) {
                            bit_buf |= ((uint64_t)(*p++)) << (56 - bit_cnt);
                            bit_cnt += 8;
                        }
                    }
                    cur = (int32_t)(bit_buf >> (64 - eff_bits));
                    bit_buf <<= eff_bits;
                    bit_cnt -= eff_bits;
                    if (bit_cnt < 0) bit_cnt = 0;
                    diff = (cur - pred) & eff_mask;
                    if (diff >= half_range) diff -= eff_range;
                } else {
                    int shift1 = q + 1;
                    bit_buf <<= shift1;
                    bit_cnt -= shift1;
                    if (bit_cnt < 0) bit_cnt = 0;
                    int32_t rem = 0;
                    if (k > 0) {
                        if (bit_cnt < k) {
                            while (bit_cnt <= 56 && p < end) {
                                bit_buf |= ((uint64_t)(*p++)) << (56 - bit_cnt);
                                bit_cnt += 8;
                            }
                        }
                        rem = (int32_t)(bit_buf >> (64 - k));
                        bit_buf <<= k;
                        bit_cnt -= k;
                        if (bit_cnt < 0) bit_cnt = 0;
                    }
                    int32_t mapped = (q << k) | rem;
                    diff = ((mapped & 1) == 0) ? (mapped >> 1) : -((mapped + 1) >> 1);
                    cur = (pred + diff) & eff_mask;
                }

                cur_row[x] = cur;

                int32_t abs_diff = (diff < 0) ? -diff : diff;
                int32_t na = grad_a[ctx] + abs_diff;
                int32_t nn = grad_n[ctx] + 1;
                if (nn >= 0x40) {
                    na >>= 1;
                    nn >>= 1;
                }
                grad_a[ctx] = na;
                grad_n[ctx] = nn;
            }

            /* Write decoded active pixels of this row directly to dst_raw_le16 (disjoint x range per strip!) */
            int32_t y = y_base + r;
            if (y < active_h && strip_active_w > 0) {
                uint8_t* dst_row = job->dst_raw_le16 + (size_t)y * (size_t)dst_stride + (size_t)x_base * 2u;
                if (q_shift == 0) {
                    for (int32_t x = 0; x < strip_active_w; ++x) {
                        int32_t v = cur_row[x];
                        dst_row[x * 2]     = (uint8_t)(v & 0xFF);
                        dst_row[x * 2 + 1] = (uint8_t)((v >> 8) & 0xFF);
                    }
                } else {
                    for (int32_t x = 0; x < strip_active_w; ++x) {
                        int32_t v = cur_row[x] << q_shift;
                        dst_row[x * 2]     = (uint8_t)(v & 0xFF);
                        dst_row[x * 2 + 1] = (uint8_t)((v >> 8) & 0xFF);
                    }
                }
                /* Zero right-hand stride padding past active_w on the rightmost strip */
                if (x_base + strip_active_w == active_w && dst_stride > active_w * 2) {
                    memset(
                        job->dst_raw_le16 + (size_t)y * (size_t)dst_stride + (size_t)active_w * 2u,
                        0,
                        (size_t)(dst_stride - active_w * 2)
                    );
                }
            }
        }
        memcpy(prev_rows, block + (size_t)4u * (size_t)strip_pixels, (size_t)2u * (size_t)strip_pixels * sizeof(int32_t));
    }

    free(block);
}

static void lsraw_decode_one_strip(lsraw_dec_job_t* job, int32_t strip_idx) {
    if (job->src_stream[3] == LSRAW_LEGACY_RAW_TYPE) {
        lsraw_decode_legacy_strip(job, strip_idx);
        return;
    }
    lsraw_bits_t io = {0};
    io.input = job->src_stream + job->strip_offsets[strip_idx];
    io.input_size = (size_t)job->strip_lengths[strip_idx];
    if (!lsraw_process_bayer_strip(&io, job->raw_bits, job->lossy_compressed, job->strip_pixels,
            strip_idx, job->blocks_y, job->active_width, job->active_height, job->dst_stride_bytes,
            NULL, job->dst_raw_le16)) job->strip_errors[strip_idx] = 1;
}

#if defined(_WIN32)
static unsigned __stdcall lsraw_dec_worker_fn(void* arg) {
    lsraw_dec_worker_arg_t* w = (lsraw_dec_worker_arg_t*)arg;
    for (int32_t s = w->thread_idx; s < w->job->blocks_x; s += w->job->num_threads) {
        lsraw_decode_one_strip(w->job, s);
    }
    return 0;
}
#else
static void* lsraw_dec_worker_fn(void* arg) {
    lsraw_dec_worker_arg_t* w = (lsraw_dec_worker_arg_t*)arg;
    for (int32_t s = w->thread_idx; s < w->job->blocks_x; s += w->job->num_threads) {
        lsraw_decode_one_strip(w->job, s);
    }
    return NULL;
}
#endif

FUJI_CODEC_EXPORT int32_t fuji_lsraw_decode_bayer(
    const uint8_t* src_stream,
    int32_t src_stream_len,
    int32_t dst_stride_bytes,
    int32_t max_threads,
    uint8_t* dst_raw_le16,
    int32_t dst_capacity,
    int32_t* out_meta
) {
    if (!src_stream || !dst_raw_le16 || src_stream_len < 32 || dst_capacity <= 0) {
        return FUJI_CODEC_ERR_INVALID_ARGS;
    }
    if (src_stream[0] != (uint8_t)'I' || src_stream[1] != (uint8_t)'S') {
        return FUJI_CODEC_ERR_INVALID_ARGS;
    }

    int32_t version       = (int32_t)src_stream[2];
    int32_t raw_type      = (int32_t)src_stream[3];
    int32_t raw_bits      = (int32_t)src_stream[4];
    int32_t active_height = (int32_t)read_u16_be(src_stream + 5);
    int32_t active_width  = (int32_t)read_u16_be(src_stream + 9);
    int32_t strip_pixels  = (int32_t)read_u16_be(src_stream + 11);
    int32_t blocks_x      = (int32_t)src_stream[13];
    int32_t blocks_y      = (int32_t)read_u16_be(src_stream + 14);

    int is_legacy = raw_type == LSRAW_LEGACY_RAW_TYPE && (version == 1 || version == 2);
    int is_bayer = raw_type == LSRAW_RAW_TYPE_BAYER && (version == 0 || version == 1);
    if ((is_bayer && (strip_pixels < 4 || strip_pixels % 4)) ||
        (int64_t)strip_pixels * blocks_x < active_width || (int64_t)blocks_y * 6 < active_height ||
        (!is_legacy && !is_bayer) ||
        (raw_bits != 12 && raw_bits != 14 && raw_bits != 16) ||
        active_width <= 0 || active_height <= 0 || blocks_x <= 0 || blocks_y <= 0 || strip_pixels <= 0) {
        return FUJI_CODEC_ERR_INVALID_ARGS;
    }

    int32_t eff_stride = (dst_stride_bytes > 0) ? dst_stride_bytes : (active_width * 2);
    if (eff_stride < active_width * 2) {
        return FUJI_CODEC_ERR_INVALID_ARGS;
    }

    int32_t hdr_bytes = lsraw_header_bytes(blocks_x);
    if (hdr_bytes > src_stream_len || 16 + blocks_x * 4 > src_stream_len) {
        return FUJI_CODEC_ERR_OVERFLOW;
    }

    int64_t required_dst = (int64_t)eff_stride * (int64_t)active_height;
    if (required_dst <= 0 || required_dst > (int64_t)dst_capacity) {
        return FUJI_CODEC_ERR_OVERFLOW;
    }

    int32_t strip_offsets[256];
    int32_t strip_lengths[256];
    int64_t cursor = hdr_bytes;
    if (is_bayer && version == 0) {
        int64_t qtable_bytes = (int64_t)((blocks_y + 15) & ~15) * blocks_x;
        if (cursor + qtable_bytes > src_stream_len) return FUJI_CODEC_ERR_OVERFLOW;
        for (int i = 0; i < blocks_x; ++i) {
            for (int y = 0; y < blocks_y; ++y) {
                if (src_stream[cursor + i * ((blocks_y + 15) & ~15) + y] != 0)
                    return FUJI_CODEC_ERR_CODEC;
            }
        }
        cursor += qtable_bytes;
    }
    for (int32_t s = 0; s < blocks_x; ++s) {
        uint32_t slen = read_u32_be(src_stream + 16 + s * 4);
        if (slen == 0 || cursor + (int64_t)slen > (int64_t)src_stream_len) {
            return FUJI_CODEC_ERR_OVERFLOW;
        }
        strip_offsets[s] = (int32_t)cursor;
        strip_lengths[s] = (int32_t)slen;
        cursor += (int64_t)slen;
    }

    ensure_runtime_init();

    int32_t n_threads = (max_threads > 0) ? max_threads : fuji_codec_default_threads();
    if (n_threads > LSRAW_MAX_THREADS) n_threads = LSRAW_MAX_THREADS;
    if (n_threads > blocks_x) n_threads = blocks_x;
    if ((int64_t)active_width * (int64_t)active_height < 65536LL) {
        n_threads = 1;
    }

    lsraw_dec_job_t job;
    job.src_stream       = src_stream;
    job.strip_offsets    = strip_offsets;
    job.strip_lengths    = strip_lengths;
    job.dst_raw_le16     = dst_raw_le16;
    job.dst_stride_bytes = eff_stride;
    job.active_width     = active_width;
    job.active_height    = active_height;
    job.blocks_x         = blocks_x;
    job.blocks_y         = blocks_y;
    job.strip_pixels     = strip_pixels;
    job.raw_bits         = raw_bits;
    job.lossy_compressed = version != 1;
    job.num_threads      = n_threads;
    memset(job.strip_errors, 0, sizeof(job.strip_errors));

    if (n_threads <= 1) {
        for (int32_t s = 0; s < blocks_x; ++s) {
            lsraw_decode_one_strip(&job, s);
        }
    } else {
        lsraw_dec_worker_arg_t args[LSRAW_MAX_THREADS];
#if defined(_WIN32)
        HANDLE threads[LSRAW_MAX_THREADS];
        int spawned = 0;
        for (int32_t t = 1; t < n_threads; ++t) {
            args[t].job = &job;
            args[t].thread_idx = t;
            uintptr_t h = _beginthreadex(NULL, 0, lsraw_dec_worker_fn, &args[t], 0, NULL);
            if (h != 0) {
                threads[spawned++] = (HANDLE)h;
            } else {
                lsraw_dec_worker_fn(&args[t]);
            }
        }
        args[0].job = &job;
        args[0].thread_idx = 0;
        lsraw_dec_worker_fn(&args[0]);
        if (spawned > 0) {
            WaitForMultipleObjects((DWORD)spawned, threads, TRUE, INFINITE);
            for (int i = 0; i < spawned; ++i) CloseHandle(threads[i]);
        }
#else
        pthread_t threads[LSRAW_MAX_THREADS];
        int spawned[LSRAW_MAX_THREADS];
        memset(spawned, 0, sizeof(spawned));
        for (int32_t t = 1; t < n_threads; ++t) {
            args[t].job = &job;
            args[t].thread_idx = t;
            if (pthread_create(&threads[t], NULL, lsraw_dec_worker_fn, &args[t]) == 0) {
                spawned[t] = 1;
            } else {
                lsraw_dec_worker_fn(&args[t]);
            }
        }
        args[0].job = &job;
        args[0].thread_idx = 0;
        lsraw_dec_worker_fn(&args[0]);
        for (int32_t t = 1; t < n_threads; ++t) {
            if (spawned[t]) pthread_join(threads[t], NULL);
        }
#endif
    }

    for (int32_t s = 0; s < blocks_x; ++s) {
        if (job.strip_errors[s]) return FUJI_CODEC_ERR_CODEC;
    }

    if (out_meta) {
        out_meta[0] = active_width;
        out_meta[1] = active_height;
        out_meta[2] = raw_bits;
        out_meta[3] = version == 1 ? 1 : 2;
        out_meta[4] = blocks_x;
    }
    return (int32_t)required_dst;
}

/* ============================================================================
 * 4. Native Fast JPEG YUV422 & BGR24 Codec (libjpeg-turbo v80 / TurboJPEG)
 * ============================================================================ */

static int compute_decode_geometry(
    int32_t src_w, int32_t src_h, int32_t is_420,
    int32_t downscale_shift, int32_t crop_enabled,
    int32_t crop_x0, int32_t crop_x1, int32_t crop_y0, int32_t crop_y1,
    int32_t* out_raw_x0, int32_t* out_raw_y0, int32_t* out_scale_factor,
    int32_t* out_w, int32_t* out_h, int32_t* out_aligned_w, int32_t* out_aligned_h,
    int32_t* out_stride, int64_t* out_total_bytes
) {
    int32_t scale_factor = 1 << (downscale_shift & 3);
    int32_t rx0, rx1, ry0, ry1;
    if (crop_enabled) {
        if (crop_x0 < 0 || crop_x1 < crop_x0 || crop_x1 >= src_w ||
            crop_y0 < 0 || crop_y1 < crop_y0 || crop_y1 >= src_h) {
            return 0;
        }
        rx0 = crop_x0;
        rx1 = crop_x1;
        ry0 = crop_y0;
        ry1 = crop_y1;
    } else {
        rx0 = 0;
        rx1 = src_w - 1;
        ry0 = 0;
        ry1 = src_h - 1;
    }

    int32_t crop_w = rx1 - rx0 + 1;
    int32_t crop_h = ry1 - ry0 + 1;
    if (crop_w < 1 || crop_h < 1) return 0;

    int32_t ow = (crop_w + scale_factor - 1) / scale_factor;
    int32_t oh = (crop_h + scale_factor - 1) / scale_factor;
    if (ow < 1) ow = 1;
    if (oh < 1) oh = 1;

    int32_t aligned_w = (ow + 15) & ~15;
    int32_t v_align = is_420 ? 16 : 8;
    int32_t aligned_h = (oh + v_align - 1) & ~(v_align - 1);
    int32_t stride = aligned_w * 2;
    int64_t total = (int64_t)stride * (int64_t)aligned_h;

    if (out_raw_x0) *out_raw_x0 = rx0;
    if (out_raw_y0) *out_raw_y0 = ry0;
    if (out_scale_factor) *out_scale_factor = scale_factor;
    if (out_w) *out_w = ow;
    if (out_h) *out_h = oh;
    if (out_aligned_w) *out_aligned_w = aligned_w;
    if (out_aligned_h) *out_aligned_h = aligned_h;
    if (out_stride) *out_stride = stride;
    if (out_total_bytes) *out_total_bytes = total;
    return 1;
}

static inline void fill_neutral_422_span(uint8_t* dst, int32_t pixel_pairs, int is_yyuv) {
    if (is_yyuv) {
        /* Neutral Milbeaut YYUV422 black: [0x00, 0x00, 0x80, 0x80] (0x80800000 LE) */
        for (int32_t i = 0; i < pixel_pairs; ++i) {
            dst[i * 4 + 0] = 0x00;
            dst[i * 4 + 1] = 0x00;
            dst[i * 4 + 2] = 0x80;
            dst[i * 4 + 3] = 0x80;
        }
    } else {
        /* Neutral UYVY black: [0x80, 0x00, 0x80, 0x00] */
        for (int32_t i = 0; i < pixel_pairs; ++i) {
            dst[i * 4 + 0] = 0x80;
            dst[i * 4 + 1] = 0x00;
            dst[i * 4 + 2] = 0x80;
            dst[i * 4 + 3] = 0x00;
        }
    }
}

/* Unpack one row of packed 4:2:2 (UYVY422 or Milbeaut YYUV422) into planar Y, Cb, Cr with black-fill sanitization */
static void unpack_422_row_to_planar422(
    const uint8_t* src_row, int32_t width, int is_yyuv,
    uint8_t* y_dst, uint8_t* cb_dst, uint8_t* cr_dst,
    int32_t y_stride, int32_t c_stride
) {
    int32_t pairs = width >> 1;
    if (is_yyuv) {
        for (int32_t p = 0; p < pairs; ++p) {
            uint8_t y0 = src_row[p * 4 + 0];
            uint8_t y1 = src_row[p * 4 + 1];
            uint8_t u  = src_row[p * 4 + 2];
            uint8_t v  = src_row[p * 4 + 3];
            /* Unwritten zero DRAM (0x00000000) & alternate-endian black ([80 80 00 00]) -> neutral black.
             * Native Milbeaut black 0x80800000 ([00 00 80 80]) is already y0=0, y1=0, u=0x80, v=0x80. */
            if ((y0 == 0 && y1 == 0 && u == 0 && v == 0) ||
                (y0 == 0x80 && y1 == 0x80 && u == 0 && v == 0)) {
                y0 = 0; y1 = 0; u = 0x80; v = 0x80;
            }
            y_dst[p * 2]     = y0;
            y_dst[p * 2 + 1] = y1;
            cb_dst[p]        = u;
            cr_dst[p]        = v;
        }
        if (width & 1) {
            int32_t p = pairs;
            uint8_t y0 = src_row[p * 4 + 0];
            uint8_t u  = src_row[p * 4 + 2];
            uint8_t v  = src_row[p * 4 + 3];
            if ((y0 == 0 && u == 0 && v == 0) || (y0 == 0x80 && u == 0 && v == 0)) {
                y0 = 0; u = 0x80; v = 0x80;
            }
            y_dst[width - 1] = y0;
            cb_dst[p] = u;
            cr_dst[p] = v;
            pairs++;
        }
    } else {
        for (int32_t p = 0; p < pairs; ++p) {
            uint8_t u  = src_row[p * 4 + 0];
            uint8_t y0 = src_row[p * 4 + 1];
            uint8_t v  = src_row[p * 4 + 2];
            uint8_t y1 = src_row[p * 4 + 3];

            /* Sanitize Milbeaut unwritten zero DRAM (0x00000000) & Playback black words ([00 00 80 80], [80 80 00 00]) */
            if ((u == 0 && y0 == 0 && v == 0 && y1 == 0) ||
                (u == 0 && y0 == 0 && v == 0x80 && y1 == 0x80) ||
                (u == 0x80 && y0 == 0x80 && v == 0 && y1 == 0)) {
                u = 0x80; y0 = 0; v = 0x80; y1 = 0;
            }
            y_dst[p * 2]     = y0;
            y_dst[p * 2 + 1] = y1;
            cb_dst[p]        = u;
            cr_dst[p]        = v;
        }
        if (width & 1) {
            int32_t p = pairs;
            uint8_t u  = src_row[p * 4 + 0];
            uint8_t y0 = src_row[p * 4 + 1];
            uint8_t v  = src_row[p * 4 + 2];
            if (u == 0 && y0 == 0 && v == 0) {
                u = 0x80; y0 = 0; v = 0x80;
            }
            y_dst[width - 1] = y0;
            cb_dst[p] = u;
            cr_dst[p] = v;
            pairs++;
        }
    }
    /* Edge-replicate to MCU-aligned planar stride */
    uint8_t last_y  = (width > 0) ? y_dst[width - 1] : 0;
    uint8_t last_cb = (pairs > 0) ? cb_dst[pairs - 1] : 128;
    uint8_t last_cr = (pairs > 0) ? cr_dst[pairs - 1] : 128;
    for (int32_t x = width; x < y_stride; ++x) y_dst[x] = last_y;
    for (int32_t x = pairs; x < c_stride; ++x) {
        cb_dst[x] = last_cb;
        cr_dst[x] = last_cr;
    }
}

static inline void store_422_pair(uint8_t* dst4, uint8_t y0, uint8_t y1, uint8_t u, uint8_t v, int is_yyuv) {
    if (is_yyuv) {
        dst4[0] = y0;
        dst4[1] = y1;
        dst4[2] = u;
        dst4[3] = v;
    } else {
        dst4[0] = u;
        dst4[1] = y0;
        dst4[2] = v;
        dst4[3] = y1;
    }
}

static inline void rgb_pair_to_422(
    int32_t r0, int32_t g0, int32_t b0,
    int32_t r1, int32_t g1, int32_t b1,
    uint8_t* dst4, int is_yyuv
) {
    int32_t y0 = clamp_i32((77 * r0 + 150 * g0 + 29 * b0 + 128) >> 8, 0, 255);
    int32_t y1 = clamp_i32((77 * r1 + 150 * g1 + 29 * b1 + 128) >> 8, 0, 255);
    int32_t r_avg = (r0 + r1) >> 1;
    int32_t g_avg = (g0 + g1) >> 1;
    int32_t b_avg = (b0 + b1) >> 1;
    int32_t u = clamp_i32(((-43 * r_avg - 85 * g_avg + 128 * b_avg + 128) >> 8) + 128, 0, 255);
    int32_t v = clamp_i32(((128 * r_avg - 107 * g_avg - 21 * b_avg + 128) >> 8) + 128, 0, 255);
    store_422_pair(dst4, (uint8_t)y0, (uint8_t)y1, (uint8_t)u, (uint8_t)v, is_yyuv);
}

static void pack_ycbcr_scanline_to_422(
    const uint8_t* src_ycc, int32_t src_w,
    int32_t x_start, int32_t x_step, int32_t out_w, int32_t aligned_w,
    uint8_t* dst_row, int is_yyuv
) {
    if (x_step == 1 && x_start == 0) {
        int32_t pairs = out_w >> 1;
        const uint8_t* s = src_ycc;
        uint8_t* d = dst_row;
        for (int32_t p = 0; p < pairs; ++p) {
            uint8_t y0  = s[0], cb0 = s[1], cr0 = s[2];
            uint8_t y1  = s[3], cb1 = s[4], cr1 = s[5];
            uint8_t u = (uint8_t)(((int32_t)cb0 + (int32_t)cb1 + 1) >> 1);
            uint8_t v = (uint8_t)(((int32_t)cr0 + (int32_t)cr1 + 1) >> 1);
            store_422_pair(d, y0, y1, u, v, is_yyuv);
            s += 6;
            d += 4;
        }
        if (out_w & 1) {
            int32_t idx = (out_w - 1) * 3;
            store_422_pair(d, src_ycc[idx + 0], 0, src_ycc[idx + 1], src_ycc[idx + 2], is_yyuv);
            pairs++;
        }
        if (aligned_w > pairs * 2) {
            fill_neutral_422_span(dst_row + pairs * 4, (aligned_w - pairs * 2) >> 1, is_yyuv);
        }
        return;
    }

    int32_t pairs = out_w >> 1;
    uint8_t* d = dst_row;
    for (int32_t p = 0; p < pairs; ++p) {
        int32_t sx0 = clamp_i32(x_start + (p * 2) * x_step, 0, src_w - 1) * 3;
        int32_t sx1 = clamp_i32(x_start + (p * 2 + 1) * x_step, 0, src_w - 1) * 3;
        uint8_t u = (uint8_t)(((int32_t)src_ycc[sx0 + 1] + (int32_t)src_ycc[sx1 + 1] + 1) >> 1);
        uint8_t v = (uint8_t)(((int32_t)src_ycc[sx0 + 2] + (int32_t)src_ycc[sx1 + 2] + 1) >> 1);
        store_422_pair(d, src_ycc[sx0], src_ycc[sx1], u, v, is_yyuv);
        d += 4;
    }
    if (out_w & 1) {
        int32_t sx = clamp_i32(x_start + (out_w - 1) * x_step, 0, src_w - 1) * 3;
        store_422_pair(d, src_ycc[sx], 0, src_ycc[sx + 1], src_ycc[sx + 2], is_yyuv);
        pairs++;
    }
    if (aligned_w > pairs * 2) {
        fill_neutral_422_span(dst_row + pairs * 4, (aligned_w - pairs * 2) >> 1, is_yyuv);
    }
}

static void pack_gray_scanline_to_422(
    const uint8_t* src_gray, int32_t src_w,
    int32_t x_start, int32_t x_step, int32_t out_w, int32_t aligned_w,
    uint8_t* dst_row, int is_yyuv
) {
    int32_t pairs = out_w >> 1;
    uint8_t* d = dst_row;
    for (int32_t p = 0; p < pairs; ++p) {
        int32_t sx0 = clamp_i32(x_start + (p * 2) * x_step, 0, src_w - 1);
        int32_t sx1 = clamp_i32(x_start + (p * 2 + 1) * x_step, 0, src_w - 1);
        store_422_pair(d, src_gray[sx0], src_gray[sx1], 0x80, 0x80, is_yyuv);
        d += 4;
    }
    if (out_w & 1) {
        int32_t sx = clamp_i32(x_start + (out_w - 1) * x_step, 0, src_w - 1);
        store_422_pair(d, src_gray[sx], 0, 0x80, 0x80, is_yyuv);
        pairs++;
    }
    if (aligned_w > pairs * 2) {
        fill_neutral_422_span(dst_row + pairs * 4, (aligned_w - pairs * 2) >> 1, is_yyuv);
    }
}

static void pack_rgb_scanline_to_422(
    const uint8_t* src_rgb, int32_t src_w,
    int32_t x_start, int32_t x_step, int32_t out_w, int32_t aligned_w,
    uint8_t* dst_row, int is_yyuv
) {
    int32_t pairs = out_w >> 1;
    uint8_t* d = dst_row;
    for (int32_t p = 0; p < pairs; ++p) {
        int32_t sx0 = clamp_i32(x_start + (p * 2) * x_step, 0, src_w - 1) * 3;
        int32_t sx1 = clamp_i32(x_start + (p * 2 + 1) * x_step, 0, src_w - 1) * 3;
        rgb_pair_to_422(
            src_rgb[sx0], src_rgb[sx0 + 1], src_rgb[sx0 + 2],
            src_rgb[sx1], src_rgb[sx1 + 1], src_rgb[sx1 + 2],
            d, is_yyuv
        );
        d += 4;
    }
    if (out_w & 1) {
        int32_t sx = clamp_i32(x_start + (out_w - 1) * x_step, 0, src_w - 1) * 3;
        rgb_pair_to_422(
            src_rgb[sx], src_rgb[sx + 1], src_rgb[sx + 2],
            src_rgb[sx], src_rgb[sx + 1], src_rgb[sx + 2],
            d, is_yyuv
        );
        if (is_yyuv) d[1] = 0; else d[3] = 0;
        pairs++;
    }
    if (aligned_w > pairs * 2) {
        fill_neutral_422_span(dst_row + pairs * 4, (aligned_w - pairs * 2) >> 1, is_yyuv);
    }
}

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
) {
    if (!src_jpeg || src_jpeg_len < 4 || !out_meta) {
        return FUJI_CODEC_ERR_INVALID_ARGS;
    }
    ensure_runtime_init();

    int32_t src_w = 0, src_h = 0, is_420 = 0;

    if (g_rt.has_libjpeg8) {
        struct fj_jpeg_decompress_struct dinfo;
        struct fj_err_ctx jerr;
        memset(&dinfo, 0, sizeof(dinfo));
        dinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;

        if (setjmp(jerr.env) != 0) {
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_CODEC;
        }
        g_rt.create_decompress(&dinfo, FJ_JPEG_LIB_VERSION, sizeof(dinfo));
        g_rt.mem_src(&dinfo, src_jpeg, (unsigned long)src_jpeg_len);
        if (g_rt.read_header(&dinfo, 1) != 1) {
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_CODEC;
        }
        src_w = (int32_t)dinfo.image_width;
        src_h = (int32_t)dinfo.image_height;
        is_420 = (dinfo.num_components >= 3 && dinfo.comp_info != NULL &&
                  dinfo.comp_info[0].v_samp_factor > dinfo.comp_info[1].v_samp_factor) ? 1 : 0;
        g_rt.destroy_decompress(&dinfo);
    } else if (g_rt.has_libjpeg62) {
        struct fj62_jpeg_decompress_struct dinfo;
        struct fj_err_ctx jerr;
        memset(&dinfo, 0, sizeof(dinfo));
        dinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;

        if (setjmp(jerr.env) != 0) {
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_CODEC;
        }
        g_rt.create_decompress(&dinfo, FJ_JPEG_LIB_VERSION_62, sizeof(dinfo));
        g_rt.mem_src(&dinfo, src_jpeg, (unsigned long)src_jpeg_len);
        if (g_rt.read_header(&dinfo, 1) != 1) {
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_CODEC;
        }
        src_w = (int32_t)dinfo.image_width;
        src_h = (int32_t)dinfo.image_height;
        is_420 = (dinfo.num_components >= 3 && dinfo.comp_info != NULL &&
                  dinfo.comp_info[0].v_samp_factor > dinfo.comp_info[1].v_samp_factor) ? 1 : 0;
        g_rt.destroy_decompress(&dinfo);
    } else if (g_rt.has_turbojpeg) {
        tjhandle tj = g_rt.tj_init_decompress();
        if (!tj) return FUJI_CODEC_ERR_CODEC;
        int w = 0, h = 0, subsamp = 0, colorspace = 0;
        int rc = g_rt.tj_decompress_header3(tj, src_jpeg, (unsigned long)src_jpeg_len, &w, &h, &subsamp, &colorspace);
        g_rt.tj_destroy(tj);
        if (rc != 0 || w <= 0 || h <= 0) return FUJI_CODEC_ERR_CODEC;
        src_w = w;
        src_h = h;
        is_420 = (subsamp == FJ_TJSAMP_420) ? 1 : 0;
    } else {
        return FUJI_CODEC_ERR_NO_BACKEND;
    }

    if (src_w <= 0 || src_h <= 0) return FUJI_CODEC_ERR_CODEC;

    int32_t out_w, out_h, stride;
    int64_t total_bytes;
    if (!compute_decode_geometry(
        src_w, src_h, is_420, downscale_shift, crop_enabled, crop_x0, crop_x1, crop_y0, crop_y1,
        NULL, NULL, NULL, &out_w, &out_h, NULL, NULL, &stride, &total_bytes
    )) {
        return FUJI_CODEC_ERR_INVALID_ARGS;
    }
    if (total_bytes <= 0 || total_bytes > (int64_t)INT32_MAX) {
        return FUJI_CODEC_ERR_OVERFLOW;
    }

    out_meta[0] = src_w;
    out_meta[1] = src_h;
    out_meta[2] = out_w;
    out_meta[3] = out_h;
    out_meta[4] = stride;
    out_meta[5] = (int32_t)total_bytes;
    out_meta[6] = is_420;
    return 0;
}

static int32_t jpeg_decode_to_422(
    const uint8_t* src_jpeg,
    int32_t src_jpeg_len,
    int32_t downscale_shift,
    int32_t crop_enabled,
    int32_t crop_x0,
    int32_t crop_x1,
    int32_t crop_y0,
    int32_t crop_y1,
    uint8_t* dst_422,
    int32_t dst_capacity,
    int32_t* out_meta,
    int is_yyuv
) {
    if (!src_jpeg || src_jpeg_len < 4 || !dst_422 || dst_capacity <= 0) {
        return FUJI_CODEC_ERR_INVALID_ARGS;
    }
    ensure_runtime_init();

    if (g_rt.has_libjpeg8) {
        struct fj_jpeg_decompress_struct dinfo;
        struct fj_err_ctx jerr;
        uint8_t* volatile scanline = NULL;
        memset(&dinfo, 0, sizeof(dinfo));
        dinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;

        if (setjmp(jerr.env) != 0) {
            free(scanline);
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_CODEC;
        }

        g_rt.create_decompress(&dinfo, FJ_JPEG_LIB_VERSION, sizeof(dinfo));
        g_rt.mem_src(&dinfo, src_jpeg, (unsigned long)src_jpeg_len);
        if (g_rt.read_header(&dinfo, 1) != 1) {
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_CODEC;
        }

        int32_t src_w = (int32_t)dinfo.image_width;
        int32_t src_h = (int32_t)dinfo.image_height;
        int32_t is_420 = (dinfo.num_components >= 3 && dinfo.comp_info != NULL &&
                          dinfo.comp_info[0].v_samp_factor > dinfo.comp_info[1].v_samp_factor) ? 1 : 0;

        int32_t rx0, ry0, scale_factor, out_w, out_h, aligned_w, aligned_h, stride;
        int64_t total_bytes;
        if (!compute_decode_geometry(
            src_w, src_h, is_420, downscale_shift, crop_enabled, crop_x0, crop_x1, crop_y0, crop_y1,
            &rx0, &ry0, &scale_factor, &out_w, &out_h, &aligned_w, &aligned_h, &stride, &total_bytes
        )) {
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_INVALID_ARGS;
        }

        if (out_meta) {
            out_meta[0] = out_w;
            out_meta[1] = out_h;
            out_meta[2] = stride;
            out_meta[3] = (total_bytes <= (int64_t)INT32_MAX) ? (int32_t)total_bytes : INT32_MAX;
            out_meta[4] = is_420;
        }
        if (total_bytes <= 0 || total_bytes > (int64_t)dst_capacity) {
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_OVERFLOW;
        }

        /* Choose direct color space: JCS_YCbCr avoids RGB round-trip completely! */
        int mode = 0; /* 0 = YCbCr, 1 = Grayscale, 2 = RGB fallback */
        if (dinfo.jpeg_color_space == FJ_JCS_YCbCr) {
            dinfo.out_color_space = FJ_JCS_YCbCr;
            mode = 0;
        } else if (dinfo.jpeg_color_space == FJ_JCS_GRAYSCALE) {
            dinfo.out_color_space = FJ_JCS_GRAYSCALE;
            mode = 1;
        } else {
            dinfo.out_color_space = FJ_JCS_RGB;
            mode = 2;
        }
        dinfo.dct_method = FJ_JDCT_ISLOW;
        /* Avoid redundant triangle chroma upsampling when packing directly back to UYVY422 */
        dinfo.do_fancy_upsampling = 0;

        /* Use hardware libjpeg IDCT 1/2, 1/4, 1/8 downscaling whenever crop origin aligns cleanly */
        int32_t x_start = rx0;
        int32_t y_start = ry0;
        int32_t step    = scale_factor;
        if (scale_factor > 1 && (!crop_enabled || ((rx0 % scale_factor) == 0 && (ry0 % scale_factor) == 0))) {
            dinfo.scale_num   = 1;
            dinfo.scale_denom = (unsigned int)scale_factor;
            x_start = rx0 / scale_factor;
            y_start = ry0 / scale_factor;
            step    = 1;
        }

        g_rt.start_decompress(&dinfo);

        int32_t dec_w = (int32_t)dinfo.output_width;
        int32_t dec_h = (int32_t)dinfo.output_height;
        int32_t comps = dinfo.output_components;

        const int32_t batch_cap = 16;
        size_t row_bytes = (size_t)dec_w * (size_t)comps;
        scanline = (uint8_t*)malloc(row_bytes * (size_t)batch_cap);
        if (!scanline) {
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_CODEC;
        }
        fj_JSAMPROW row_ptrs[16];
        for (int32_t i = 0; i < batch_cap; ++i) {
            row_ptrs[i] = scanline + (size_t)i * row_bytes;
        }

        int32_t target_y = 0;
        int32_t next_src_y = y_start;
        while ((int32_t)dinfo.output_scanline < dec_h && target_y < out_h) {
            int32_t cur_base_y = (int32_t)dinfo.output_scanline;
            int32_t want = dec_h - cur_base_y;
            if (want > batch_cap) want = batch_cap;
            fj_JDIMENSION got = g_rt.read_scanlines(&dinfo, row_ptrs, (fj_JDIMENSION)want);
            if (got == 0) break;
            for (int32_t bi = 0; bi < (int32_t)got && target_y < out_h; ++bi) {
                int32_t cur_y = cur_base_y + bi;
                const uint8_t* line = row_ptrs[bi];
                while (target_y < out_h && cur_y == next_src_y) {
                    uint8_t* dst_row = dst_422 + (size_t)target_y * (size_t)stride;
                    if (mode == 0) {
                        pack_ycbcr_scanline_to_422(line, dec_w, x_start, step, out_w, aligned_w, dst_row, is_yyuv);
                    } else if (mode == 1) {
                        pack_gray_scanline_to_422(line, dec_w, x_start, step, out_w, aligned_w, dst_row, is_yyuv);
                    } else {
                        pack_rgb_scanline_to_422(line, dec_w, x_start, step, out_w, aligned_w, dst_row, is_yyuv);
                    }
                    target_y++;
                    next_src_y = y_start + target_y * step;
                    if (next_src_y >= dec_h) next_src_y = dec_h - 1;
                }
            }
        }

        /* Drain remaining scanlines only if needed, or destroy directly (jpeg_destroy_decompress safely aborts) */
        if ((int32_t)dinfo.output_scanline >= dec_h) {
            g_rt.finish_decompress(&dinfo);
        }
        g_rt.destroy_decompress(&dinfo);
        free(scanline);

        if (target_y < out_h) {
            /* Corrupt/truncated JPEG did not produce requested lines */
            return FUJI_CODEC_ERR_CODEC;
        }

        /* Fill bottom alignment padding rows with neutral packed 4:2:2 black */
        for (int32_t y = out_h; y < aligned_h; ++y) {
            fill_neutral_422_span(dst_422 + (size_t)y * (size_t)stride, aligned_w >> 1, is_yyuv);
        }

        return (int32_t)total_bytes;
    }

    if (g_rt.has_libjpeg62) {
        struct fj62_jpeg_decompress_struct dinfo;
        struct fj_err_ctx jerr;
        uint8_t* volatile scanline = NULL;
        memset(&dinfo, 0, sizeof(dinfo));
        dinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;

        if (setjmp(jerr.env) != 0) {
            free(scanline);
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_CODEC;
        }

        g_rt.create_decompress(&dinfo, FJ_JPEG_LIB_VERSION_62, sizeof(dinfo));
        g_rt.mem_src(&dinfo, src_jpeg, (unsigned long)src_jpeg_len);
        if (g_rt.read_header(&dinfo, 1) != 1) {
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_CODEC;
        }

        int32_t src_w = (int32_t)dinfo.image_width;
        int32_t src_h = (int32_t)dinfo.image_height;
        int32_t is_420 = (dinfo.num_components >= 3 && dinfo.comp_info != NULL &&
                          dinfo.comp_info[0].v_samp_factor > dinfo.comp_info[1].v_samp_factor) ? 1 : 0;

        int32_t rx0, ry0, scale_factor, out_w, out_h, aligned_w, aligned_h, stride;
        int64_t total_bytes;
        if (!compute_decode_geometry(
            src_w, src_h, is_420, downscale_shift, crop_enabled, crop_x0, crop_x1, crop_y0, crop_y1,
            &rx0, &ry0, &scale_factor, &out_w, &out_h, &aligned_w, &aligned_h, &stride, &total_bytes
        )) {
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_INVALID_ARGS;
        }

        if (out_meta) {
            out_meta[0] = out_w;
            out_meta[1] = out_h;
            out_meta[2] = stride;
            out_meta[3] = (total_bytes <= (int64_t)INT32_MAX) ? (int32_t)total_bytes : INT32_MAX;
            out_meta[4] = is_420;
        }
        if (total_bytes <= 0 || total_bytes > (int64_t)dst_capacity) {
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_OVERFLOW;
        }

        int mode = 0;
        if (dinfo.jpeg_color_space == FJ_JCS_YCbCr) {
            dinfo.out_color_space = FJ_JCS_YCbCr;
            mode = 0;
        } else if (dinfo.jpeg_color_space == FJ_JCS_GRAYSCALE) {
            dinfo.out_color_space = FJ_JCS_GRAYSCALE;
            mode = 1;
        } else {
            dinfo.out_color_space = FJ_JCS_RGB;
            mode = 2;
        }
        dinfo.dct_method = FJ_JDCT_ISLOW;
        dinfo.do_fancy_upsampling = 0;

        int32_t x_start = rx0;
        int32_t y_start = ry0;
        int32_t step    = scale_factor;
        if (scale_factor > 1 && (!crop_enabled || ((rx0 % scale_factor) == 0 && (ry0 % scale_factor) == 0))) {
            dinfo.scale_num   = 1;
            dinfo.scale_denom = (unsigned int)scale_factor;
            x_start = rx0 / scale_factor;
            y_start = ry0 / scale_factor;
            step    = 1;
        }

        g_rt.start_decompress(&dinfo);

        int32_t dec_w = (int32_t)dinfo.output_width;
        int32_t dec_h = (int32_t)dinfo.output_height;
        int32_t comps = dinfo.output_components;

        const int32_t batch_cap = 16;
        size_t row_bytes = (size_t)dec_w * (size_t)comps;
        scanline = (uint8_t*)malloc(row_bytes * (size_t)batch_cap);
        if (!scanline) {
            g_rt.destroy_decompress(&dinfo);
            return FUJI_CODEC_ERR_CODEC;
        }
        fj_JSAMPROW row_ptrs[16];
        for (int32_t i = 0; i < batch_cap; ++i) {
            row_ptrs[i] = scanline + (size_t)i * row_bytes;
        }

        int32_t target_y = 0;
        int32_t next_src_y = y_start;
        while ((int32_t)dinfo.output_scanline < dec_h && target_y < out_h) {
            int32_t cur_base_y = (int32_t)dinfo.output_scanline;
            int32_t want = dec_h - cur_base_y;
            if (want > batch_cap) want = batch_cap;
            fj_JDIMENSION got = g_rt.read_scanlines(&dinfo, row_ptrs, (fj_JDIMENSION)want);
            if (got == 0) break;
            for (int32_t bi = 0; bi < (int32_t)got && target_y < out_h; ++bi) {
                int32_t cur_y = cur_base_y + bi;
                const uint8_t* line = row_ptrs[bi];
                while (target_y < out_h && cur_y == next_src_y) {
                    uint8_t* dst_row = dst_422 + (size_t)target_y * (size_t)stride;
                    if (mode == 0) {
                        pack_ycbcr_scanline_to_422(line, dec_w, x_start, step, out_w, aligned_w, dst_row, is_yyuv);
                    } else if (mode == 1) {
                        pack_gray_scanline_to_422(line, dec_w, x_start, step, out_w, aligned_w, dst_row, is_yyuv);
                    } else {
                        pack_rgb_scanline_to_422(line, dec_w, x_start, step, out_w, aligned_w, dst_row, is_yyuv);
                    }
                    target_y++;
                    next_src_y = y_start + target_y * step;
                    if (next_src_y >= dec_h) next_src_y = dec_h - 1;
                }
            }
        }

        if ((int32_t)dinfo.output_scanline >= dec_h) {
            g_rt.finish_decompress(&dinfo);
        }
        g_rt.destroy_decompress(&dinfo);
        free(scanline);

        if (target_y < out_h) {
            return FUJI_CODEC_ERR_CODEC;
        }

        for (int32_t y = out_h; y < aligned_h; ++y) {
            fill_neutral_422_span(dst_422 + (size_t)y * (size_t)stride, aligned_w >> 1, is_yyuv);
        }

        return (int32_t)total_bytes;
    }

    if (g_rt.has_turbojpeg) {
        tjhandle tj = g_rt.tj_init_decompress();
        if (!tj) return FUJI_CODEC_ERR_CODEC;
        int w = 0, h = 0, subsamp = 0, colorspace = 0;
        if (g_rt.tj_decompress_header3(tj, src_jpeg, (unsigned long)src_jpeg_len, &w, &h, &subsamp, &colorspace) != 0 ||
            w <= 0 || h <= 0) {
            g_rt.tj_destroy(tj);
            return FUJI_CODEC_ERR_CODEC;
        }
        int32_t is_420 = (subsamp == FJ_TJSAMP_420) ? 1 : 0;
        int32_t rx0, ry0, scale_factor, out_w, out_h, aligned_w, aligned_h, stride;
        int64_t total_bytes;
        if (!compute_decode_geometry(
            w, h, is_420, downscale_shift, crop_enabled, crop_x0, crop_x1, crop_y0, crop_y1,
            &rx0, &ry0, &scale_factor, &out_w, &out_h, &aligned_w, &aligned_h, &stride, &total_bytes
        )) {
            g_rt.tj_destroy(tj);
            return FUJI_CODEC_ERR_INVALID_ARGS;
        }
        if (out_meta) {
            out_meta[0] = out_w;
            out_meta[1] = out_h;
            out_meta[2] = stride;
            out_meta[3] = (total_bytes <= (int64_t)INT32_MAX) ? (int32_t)total_bytes : INT32_MAX;
            out_meta[4] = is_420;
        }
        if (total_bytes <= 0 || total_bytes > (int64_t)dst_capacity) {
            g_rt.tj_destroy(tj);
            return FUJI_CODEC_ERR_OVERFLOW;
        }

        int is_gray = (subsamp == FJ_TJSAMP_GRAY);
        int pf = is_gray ? FJ_TJPF_GRAY : FJ_TJPF_RGB;
        int bpp = is_gray ? 1 : 3;
        uint8_t* rgb = (uint8_t*)malloc((size_t)w * (size_t)h * (size_t)bpp);
        if (!rgb) {
            g_rt.tj_destroy(tj);
            return FUJI_CODEC_ERR_CODEC;
        }
        int rc = g_rt.tj_decompress2(tj, src_jpeg, (unsigned long)src_jpeg_len, rgb, w, w * bpp, h, pf, 0);
        g_rt.tj_destroy(tj);
        if (rc != 0) {
            free(rgb);
            return FUJI_CODEC_ERR_CODEC;
        }

        for (int32_t y = 0; y < out_h; ++y) {
            int32_t sy = clamp_i32(ry0 + y * scale_factor, 0, h - 1);
            const uint8_t* src_row = rgb + (size_t)sy * (size_t)w * (size_t)bpp;
            uint8_t* dst_row = dst_422 + (size_t)y * (size_t)stride;
            if (is_gray) {
                pack_gray_scanline_to_422(src_row, w, rx0, scale_factor, out_w, aligned_w, dst_row, is_yyuv);
            } else {
                pack_rgb_scanline_to_422(src_row, w, rx0, scale_factor, out_w, aligned_w, dst_row, is_yyuv);
            }
        }
        for (int32_t y = out_h; y < aligned_h; ++y) {
            fill_neutral_422_span(dst_422 + (size_t)y * (size_t)stride, aligned_w >> 1, is_yyuv);
        }
        free(rgb);
        return (int32_t)total_bytes;
    }

    return FUJI_CODEC_ERR_NO_BACKEND;
}

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
) {
    return jpeg_decode_to_422(
        src_jpeg, src_jpeg_len, downscale_shift,
        crop_enabled, crop_x0, crop_x1, crop_y0, crop_y1,
        dst_uyvy, dst_capacity, out_meta, 0
    );
}

FUJI_CODEC_EXPORT int32_t fuji_jpeg_decode_to_yyuv(
    const uint8_t* src_jpeg,
    int32_t src_jpeg_len,
    int32_t downscale_shift,
    int32_t crop_enabled,
    int32_t crop_x0,
    int32_t crop_x1,
    int32_t crop_y0,
    int32_t crop_y1,
    uint8_t* dst_yyuv,
    int32_t dst_capacity,
    int32_t* out_meta
) {
    return jpeg_decode_to_422(
        src_jpeg, src_jpeg_len, downscale_shift,
        crop_enabled, crop_x0, crop_x1, crop_y0, crop_y1,
        dst_yyuv, dst_capacity, out_meta, 1
    );
}

/* ============================================================================
 * 5. Built-In Zero-Dependency Pure-C Baseline YCbCr 4:2:2 JPEG Encoder (MIT)
 *    - ISO 10918-1 Annex K quant/Huffman tables + fast AAN 8x8 FDCT
 *    - Multi-threaded DRI restart-interval strips + L2-hot 8-row streaming scale
 * ============================================================================ */

static const uint8_t kJpegZigZag[64] = {
     0,  1,  8, 16,  9,  2,  3, 10,
    17, 24, 32, 25, 18, 11,  4,  5,
    12, 19, 26, 33, 40, 48, 41, 34,
    27, 20, 13,  6,  7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36,
    29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46,
    53, 60, 61, 54, 47, 55, 62, 63
};

static const uint8_t kStdLumaQuant[64] = {
    16, 11, 10, 16, 24, 40, 51, 61,
    12, 12, 14, 19, 26, 58, 60, 55,
    14, 13, 16, 24, 40, 57, 69, 56,
    14, 17, 22, 29, 51, 87, 80, 62,
    18, 22, 37, 56, 68,109,103, 77,
    24, 35, 55, 64, 81,104,113, 92,
    49, 64, 78, 87,103,121,120,101,
    72, 92, 95, 98,112,100,103, 99
};

static const uint8_t kStdChromaQuant[64] = {
    17, 18, 24, 47, 99, 99, 99, 99,
    18, 21, 26, 66, 99, 99, 99, 99,
    24, 26, 56, 99, 99, 99, 99, 99,
    47, 66, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99
};

static const uint8_t kDcLumaBits[16] = { 0,1,5,1,1,1,1,1,1,0,0,0,0,0,0,0 };
static const uint8_t kDcLumaVals[12] = { 0,1,2,3,4,5,6,7,8,9,10,11 };
static const uint8_t kDcChromaBits[16] = { 0,3,1,1,1,1,1,1,1,1,1,0,0,0,0,0 };
static const uint8_t kDcChromaVals[12] = { 0,1,2,3,4,5,6,7,8,9,10,11 };

static const uint8_t kAcLumaBits[16] = { 0,2,1,3,3,2,4,3,5,5,4,4,0,0,1,125 };
static const uint8_t kAcLumaVals[162] = {
    0x01,0x02,0x03,0x00,0x04,0x11,0x05,0x12,0x21,0x31,0x41,0x06,0x13,0x51,0x61,0x07,
    0x22,0x71,0x14,0x32,0x81,0x91,0xa1,0x08,0x23,0x42,0xb1,0xc1,0x15,0x52,0xd1,0xf0,
    0x24,0x33,0x62,0x72,0x82,0x09,0x0a,0x16,0x17,0x18,0x19,0x1a,0x25,0x26,0x27,0x28,
    0x29,0x2a,0x34,0x35,0x36,0x37,0x38,0x39,0x3a,0x43,0x44,0x45,0x46,0x47,0x48,0x49,
    0x4a,0x53,0x54,0x55,0x56,0x57,0x58,0x59,0x5a,0x63,0x64,0x65,0x66,0x67,0x68,0x69,
    0x6a,0x73,0x74,0x75,0x76,0x77,0x78,0x79,0x7a,0x83,0x84,0x85,0x86,0x87,0x88,0x89,
    0x8a,0x92,0x93,0x94,0x95,0x96,0x97,0x98,0x99,0x9a,0xa2,0xa3,0xa4,0xa5,0xa6,0xa7,
    0xa8,0xa9,0xaa,0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xb9,0xba,0xc2,0xc3,0xc4,0xc5,
    0xc6,0xc7,0xc8,0xc9,0xca,0xd2,0xd3,0xd4,0xd5,0xd6,0xd7,0xd8,0xd9,0xda,0xe1,0xe2,
    0xe3,0xe4,0xe5,0xe6,0xe7,0xe8,0xe9,0xea,0xf1,0xf2,0xf3,0xf4,0xf5,0xf6,0xf7,0xf8,
    0xf9,0xfa
};

static const uint8_t kAcChromaBits[16] = { 0,2,1,2,4,4,3,4,7,5,4,4,0,1,2,119 };
static const uint8_t kAcChromaVals[162] = {
    0x00,0x01,0x02,0x03,0x11,0x04,0x05,0x21,0x31,0x06,0x12,0x41,0x51,0x07,0x61,0x71,
    0x13,0x22,0x32,0x81,0x08,0x14,0x42,0x91,0xa1,0xb1,0xc1,0x09,0x23,0x33,0x52,0xf0,
    0x15,0x62,0x72,0xd1,0x0a,0x16,0x24,0x34,0xe1,0x25,0xf1,0x17,0x18,0x19,0x1a,0x26,
    0x27,0x28,0x29,0x2a,0x35,0x36,0x37,0x38,0x39,0x3a,0x43,0x44,0x45,0x46,0x47,0x48,
    0x49,0x4a,0x53,0x54,0x55,0x56,0x57,0x58,0x59,0x5a,0x63,0x64,0x65,0x66,0x67,0x68,
    0x69,0x6a,0x73,0x74,0x75,0x76,0x77,0x78,0x79,0x7a,0x82,0x83,0x84,0x85,0x86,0x87,
    0x88,0x89,0x8a,0x92,0x93,0x94,0x95,0x96,0x97,0x98,0x99,0x9a,0xa2,0xa3,0xa4,0xa5,
    0xa6,0xa7,0xa8,0xa9,0xaa,0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xb9,0xba,0xc2,0xc3,
    0xc4,0xc5,0xc6,0xc7,0xc8,0xc9,0xca,0xd2,0xd3,0xd4,0xd5,0xd6,0xd7,0xd8,0xd9,0xda,
    0xe2,0xe3,0xe4,0xe5,0xe6,0xe7,0xe8,0xe9,0xea,0xf2,0xf3,0xf4,0xf5,0xf6,0xf7,0xf8,
    0xf9,0xfa
};

static const float kAanScale[8] = {
    1.000000000f, 1.387039845f, 1.306562965f, 1.175875602f,
    1.000000000f, 0.785694958f, 0.541196100f, 0.275899379f
};

typedef struct {
    uint16_t code[256];
    uint8_t  len[256];
} fj_huff_lut_t;

typedef struct {
    uint8_t* data;
    size_t   len;
    size_t   cap;
    int      oom;
    uint64_t bit_buf;
    int      bit_cnt;
} fj_bitwriter_t;

static void fj_huff_build(fj_huff_lut_t* lut, const uint8_t bits[16], const uint8_t* vals) {
    memset(lut, 0, sizeof(*lut));
    uint32_t code = 0;
    int idx = 0;
    for (int blen = 1; blen <= 16; ++blen) {
        int count = bits[blen - 1];
        for (int i = 0; i < count; ++i) {
            uint8_t sym = vals[idx++];
            lut->code[sym] = (uint16_t)code;
            lut->len[sym]  = (uint8_t)blen;
            code++;
        }
        code <<= 1;
    }
}

static int fj_bw_reserve(fj_bitwriter_t* bw, size_t extra) {
    if (bw->oom) return 0;
    if (bw->len + extra <= bw->cap) return 1;
    size_t new_cap = bw->cap ? bw->cap * 2u : 65536u;
    while (new_cap < bw->len + extra) new_cap *= 2u;
    uint8_t* p = (uint8_t*)realloc(bw->data, new_cap);
    if (!p) {
        bw->oom = 1;
        return 0;
    }
    bw->data = p;
    bw->cap = new_cap;
    return 1;
}

static inline void fj_bw_put_raw_byte(fj_bitwriter_t* bw, uint8_t b) {
    if (bw->len < bw->cap || fj_bw_reserve(bw, 16u)) {
        bw->data[bw->len++] = b;
    }
}

static inline void fj_bw_put_stuffed_byte(fj_bitwriter_t* bw, uint8_t b) {
    if (bw->len + 2u <= bw->cap || fj_bw_reserve(bw, 64u)) {
        bw->data[bw->len++] = b;
        if (b == 0xFFu) {
            bw->data[bw->len++] = 0x00u;
        }
    }
}

static inline void fj_bw_put_bits(fj_bitwriter_t* bw, uint32_t bits, int nbits) {
    bw->bit_buf = (bw->bit_buf << nbits) | (uint64_t)(bits & ((1u << nbits) - 1u));
    bw->bit_cnt += nbits;
    while (bw->bit_cnt >= 8) {
        bw->bit_cnt -= 8;
        uint8_t b = (uint8_t)(bw->bit_buf >> bw->bit_cnt);
        fj_bw_put_stuffed_byte(bw, b);
    }
}

static inline void fj_bw_flush_rst(fj_bitwriter_t* bw, int emit_rst, int rst_idx) {
    int rem = bw->bit_cnt & 7;
    if (rem > 0) {
        int pad = 8 - rem;
        fj_bw_put_bits(bw, (1u << pad) - 1u, pad);
    }
    bw->bit_buf = 0;
    bw->bit_cnt = 0;
    if (emit_rst) {
        fj_bw_put_raw_byte(bw, 0xFFu);
        fj_bw_put_raw_byte(bw, (uint8_t)(0xD0u + (rst_idx & 7)));
    }
}

static inline int fj_bit_category(int v) {
    uint32_t a = (uint32_t)(v < 0 ? -v : v);
    if (a == 0u) return 0;
#if defined(__GNUC__) || defined(__clang__)
    return 32 - __builtin_clz(a);
#else
    int n = 0;
    while (a) { a >>= 1; ++n; }
    return n;
#endif
}

static inline void fj_bw_put_bits_unchecked(fj_bitwriter_t* bw, uint32_t bits, int nbits) {
    uint64_t bbuf = (bw->bit_buf << nbits) | (uint64_t)(bits & ((1u << nbits) - 1u));
    int bcnt = bw->bit_cnt + nbits;
    uint8_t* dst = bw->data;
    size_t len = bw->len;
    while (bcnt >= 8) {
        bcnt -= 8;
        uint8_t b = (uint8_t)(bbuf >> bcnt);
        dst[len++] = b;
        if (b == 0xFFu) {
            dst[len++] = 0x00u;
        }
    }
    bw->bit_buf = bbuf;
    bw->bit_cnt = bcnt;
    bw->len = len;
}

static inline void fj_emit_dc_only(
    int32_t dc_q,
    int32_t* dc_pred,
    const fj_huff_lut_t* dc_huff,
    const fj_huff_lut_t* ac_huff,
    fj_bitwriter_t* bw
) {
    int32_t diff = dc_q - *dc_pred;
    *dc_pred = dc_q;
    int dc_cat = fj_bit_category(diff);
    if (dc_cat > 0) {
        uint32_t val_bits = (uint32_t)(diff < 0 ? (diff - 1) : diff) & ((1u << dc_cat) - 1u);
        uint32_t combined = ((uint32_t)dc_huff->code[dc_cat] << dc_cat) | val_bits;
        fj_bw_put_bits_unchecked(bw, combined, dc_huff->len[dc_cat] + dc_cat);
    } else {
        fj_bw_put_bits_unchecked(bw, dc_huff->code[0], dc_huff->len[0]);
    }
    fj_bw_put_bits_unchecked(bw, ac_huff->code[0x00], ac_huff->len[0x00]);
}

static void fj_encode_block_row_const(
    const uint8_t* row0,
    const float inv_quant[64],
    int32_t* dc_pred,
    const fj_huff_lut_t* dc_huff,
    const fj_huff_lut_t* ac_huff,
    fj_bitwriter_t* bw
) {
    if (bw->len + 256u > bw->cap && !fj_bw_reserve(bw, 4096u)) return;

    uint64_t w0;
    memcpy(&w0, row0, 8u);
    if (w0 == (uint64_t)row0[0] * 0x0101010101010101ULL) {
        float v = ((float)row0[0] - 128.0f) * 64.0f * inv_quant[0];
        int32_t dc_q = (int32_t)(v >= 0.0f ? (v + 0.5f) : (v - 0.5f));
        fj_emit_dc_only(dc_q, dc_pred, dc_huff, ac_huff, bw);
        return;
    }

    /* All 8 rows are identical: only row 0 of the 2D DCT (zigzag 0,1,5,6,14,15,27,28) is non-zero! */
    float d0 = (float)row0[0] - 128.0f, d1 = (float)row0[1] - 128.0f;
    float d2 = (float)row0[2] - 128.0f, d3 = (float)row0[3] - 128.0f;
    float d4 = (float)row0[4] - 128.0f, d5 = (float)row0[5] - 128.0f;
    float d6 = (float)row0[6] - 128.0f, d7 = (float)row0[7] - 128.0f;

    float t0 = d0 + d7, t7 = d0 - d7;
    float t1 = d1 + d6, t6 = d1 - d6;
    float t2 = d2 + d5, t5 = d2 - d5;
    float t3 = d3 + d4, t4 = d3 - d4;

    float t10 = t0 + t3, t13 = t0 - t3;
    float t11 = t1 + t2, t12 = t1 - t2;
    float z1 = (t12 + t13) * 0.707106781f;

    float r[8];
    r[0] = (t10 + t11) * 8.0f;
    r[4] = (t10 - t11) * 8.0f;
    r[2] = (t13 + z1)  * 8.0f;
    r[6] = (t13 - z1)  * 8.0f;

    t10 = t4 + t5;
    t11 = t5 + t6;
    t12 = t6 + t7;
    float z5 = (t10 - t12) * 0.382683433f;
    float z2 = 0.541196100f * t10 + z5;
    float z4 = 1.306562965f * t12 + z5;
    float z3 = t11 * 0.707106781f;
    float z11 = t7 + z3;
    float z13 = t7 - z3;
    r[5] = (z13 + z2) * 8.0f;
    r[3] = (z13 - z2) * 8.0f;
    r[1] = (z11 + z4) * 8.0f;
    r[7] = (z11 - z4) * 8.0f;

    static const uint8_t kRow0Zz[8] = { 0, 1, 5, 6, 14, 15, 27, 28 };
    int32_t qv[8];
    for (int i = 0; i < 8; ++i) {
        float v = r[i] * inv_quant[kRow0Zz[i]];
        qv[i] = (int32_t)(v >= 0.0f ? (v + 0.5f) : (v - 0.5f));
    }

    int32_t diff = qv[0] - *dc_pred;
    *dc_pred = qv[0];
    int dc_cat = fj_bit_category(diff);
    if (dc_cat > 0) {
        uint32_t val_bits = (uint32_t)(diff < 0 ? (diff - 1) : diff) & ((1u << dc_cat) - 1u);
        fj_bw_put_bits_unchecked(bw, ((uint32_t)dc_huff->code[dc_cat] << dc_cat) | val_bits, dc_huff->len[dc_cat] + dc_cat);
    } else {
        fj_bw_put_bits_unchecked(bw, dc_huff->code[0], dc_huff->len[0]);
    }

    int prev_zz = 0;
    for (int i = 1; i < 8; ++i) {
        int32_t ac = qv[i];
        if (ac == 0) continue;
        int zz = kRow0Zz[i];
        int zero_run = zz - prev_zz - 1;
        while (zero_run >= 16) {
            fj_bw_put_bits_unchecked(bw, ac_huff->code[0xF0], ac_huff->len[0xF0]);
            zero_run -= 16;
        }
        int ac_cat = fj_bit_category(ac);
        uint8_t sym = (uint8_t)((zero_run << 4) | ac_cat);
        uint32_t val_bits = (uint32_t)(ac < 0 ? (ac - 1) : ac) & ((1u << ac_cat) - 1u);
        fj_bw_put_bits_unchecked(bw, ((uint32_t)ac_huff->code[sym] << ac_cat) | val_bits, ac_huff->len[sym] + ac_cat);
        prev_zz = zz;
    }
    fj_bw_put_bits_unchecked(bw, ac_huff->code[0x00], ac_huff->len[0x00]);
}

static void fj_encode_block_aan(
    const uint8_t* src_plane,
    int32_t plane_stride,
    unsigned repeated_rows,
    const float inv_quant[64],
    int32_t* dc_pred,
    const fj_huff_lut_t* dc_huff,
    const fj_huff_lut_t* ac_huff,
    fj_bitwriter_t* bw
) {
    /* Nearest-neighbor scaling can map a strip to only two source rows. If their
     * samples also match across this block, use the existing exact row-only DCT. */
    if (repeated_rows != 0) {
        int r;
        for (r = 1; r < 8; ++r) {
            if (!(repeated_rows & (1u << r)) &&
                memcmp(src_plane, src_plane + (size_t)r * plane_stride, 8) != 0) break;
        }
        if (r == 8) {
            fj_encode_block_row_const(src_plane, inv_quant, dc_pred, dc_huff, ac_huff, bw);
            return;
        }
    }
    if (bw->len + 512u > bw->cap && !fj_bw_reserve(bw, 4096u)) return;

    float ws[64] __attribute__((aligned(16)));
    for (int r = 0; r < 8; ++r) {
        if (repeated_rows & (1u << r)) {
            memcpy(ws + r * 8, ws + (r - 1) * 8, 8 * sizeof(float));
            continue;
        }
        const uint8_t* p = src_plane + (size_t)r * (size_t)plane_stride;
        float d0 = (float)p[0] - 128.0f, d1 = (float)p[1] - 128.0f;
        float d2 = (float)p[2] - 128.0f, d3 = (float)p[3] - 128.0f;
        float d4 = (float)p[4] - 128.0f, d5 = (float)p[5] - 128.0f;
        float d6 = (float)p[6] - 128.0f, d7 = (float)p[7] - 128.0f;

        float t0 = d0 + d7, t7 = d0 - d7;
        float t1 = d1 + d6, t6 = d1 - d6;
        float t2 = d2 + d5, t5 = d2 - d5;
        float t3 = d3 + d4, t4 = d3 - d4;

        float t10 = t0 + t3, t13 = t0 - t3;
        float t11 = t1 + t2, t12 = t1 - t2;
        float z1 = (t12 + t13) * 0.707106781f;

        float* row = ws + r * 8;
        row[0] = t10 + t11;
        row[4] = t10 - t11;
        row[2] = t13 + z1;
        row[6] = t13 - z1;

        t10 = t4 + t5;
        t11 = t5 + t6;
        t12 = t6 + t7;
        float z5 = (t10 - t12) * 0.382683433f;
        float z2 = 0.541196100f * t10 + z5;
        float z4 = 1.306562965f * t12 + z5;
        float z3 = t11 * 0.707106781f;
        float z11 = t7 + z3;
        float z13 = t7 - z3;
        row[5] = z13 + z2;
        row[3] = z13 - z2;
        row[1] = z11 + z4;
        row[7] = z11 - z4;
    }

#if defined(__SSE2__) || defined(_M_X64)
    const __m128 vc4 = _mm_set1_ps(0.707106781f);
    const __m128 vc6 = _mm_set1_ps(0.382683433f);
    const __m128 vc2m6 = _mm_set1_ps(0.541196100f);
    const __m128 vc2p6 = _mm_set1_ps(1.306562965f);
    for (int c = 0; c < 8; c += 4) {
        __m128 d0 = _mm_load_ps(ws + 0 * 8 + c);
        __m128 d1 = _mm_load_ps(ws + 1 * 8 + c);
        __m128 d2 = _mm_load_ps(ws + 2 * 8 + c);
        __m128 d3 = _mm_load_ps(ws + 3 * 8 + c);
        __m128 d4 = _mm_load_ps(ws + 4 * 8 + c);
        __m128 d5 = _mm_load_ps(ws + 5 * 8 + c);
        __m128 d6 = _mm_load_ps(ws + 6 * 8 + c);
        __m128 d7 = _mm_load_ps(ws + 7 * 8 + c);

        __m128 t0 = _mm_add_ps(d0, d7), t7 = _mm_sub_ps(d0, d7);
        __m128 t1 = _mm_add_ps(d1, d6), t6 = _mm_sub_ps(d1, d6);
        __m128 t2 = _mm_add_ps(d2, d5), t5 = _mm_sub_ps(d2, d5);
        __m128 t3 = _mm_add_ps(d3, d4), t4 = _mm_sub_ps(d3, d4);

        __m128 t10 = _mm_add_ps(t0, t3), t13 = _mm_sub_ps(t0, t3);
        __m128 t11 = _mm_add_ps(t1, t2), t12 = _mm_sub_ps(t1, t2);
        __m128 z1 = _mm_mul_ps(_mm_add_ps(t12, t13), vc4);

        _mm_store_ps(ws + 0 * 8 + c, _mm_add_ps(t10, t11));
        _mm_store_ps(ws + 4 * 8 + c, _mm_sub_ps(t10, t11));
        _mm_store_ps(ws + 2 * 8 + c, _mm_add_ps(t13, z1));
        _mm_store_ps(ws + 6 * 8 + c, _mm_sub_ps(t13, z1));

        t10 = _mm_add_ps(t4, t5);
        t11 = _mm_add_ps(t5, t6);
        t12 = _mm_add_ps(t6, t7);
        __m128 z5 = _mm_mul_ps(_mm_sub_ps(t10, t12), vc6);
        __m128 z2 = _mm_add_ps(_mm_mul_ps(vc2m6, t10), z5);
        __m128 z4 = _mm_add_ps(_mm_mul_ps(vc2p6, t12), z5);
        __m128 z3 = _mm_mul_ps(t11, vc4);
        __m128 z11 = _mm_add_ps(t7, z3);
        __m128 z13 = _mm_sub_ps(t7, z3);
        _mm_store_ps(ws + 5 * 8 + c, _mm_add_ps(z13, z2));
        _mm_store_ps(ws + 3 * 8 + c, _mm_sub_ps(z13, z2));
        _mm_store_ps(ws + 1 * 8 + c, _mm_add_ps(z11, z4));
        _mm_store_ps(ws + 7 * 8 + c, _mm_sub_ps(z11, z4));
    }
#else
    for (int c = 0; c < 8; ++c) {
        float d0 = ws[0 * 8 + c], d1 = ws[1 * 8 + c], d2 = ws[2 * 8 + c], d3 = ws[3 * 8 + c];
        float d4 = ws[4 * 8 + c], d5 = ws[5 * 8 + c], d6 = ws[6 * 8 + c], d7 = ws[7 * 8 + c];

        float t0 = d0 + d7, t7 = d0 - d7;
        float t1 = d1 + d6, t6 = d1 - d6;
        float t2 = d2 + d5, t5 = d2 - d5;
        float t3 = d3 + d4, t4 = d3 - d4;

        float t10 = t0 + t3, t13 = t0 - t3;
        float t11 = t1 + t2, t12 = t1 - t2;
        float z1 = (t12 + t13) * 0.707106781f;

        ws[0 * 8 + c] = t10 + t11;
        ws[4 * 8 + c] = t10 - t11;
        ws[2 * 8 + c] = t13 + z1;
        ws[6 * 8 + c] = t13 - z1;

        t10 = t4 + t5;
        t11 = t5 + t6;
        t12 = t6 + t7;
        float z5 = (t10 - t12) * 0.382683433f;
        float z2 = 0.541196100f * t10 + z5;
        float z4 = 1.306562965f * t12 + z5;
        float z3 = t11 * 0.707106781f;
        float z11 = t7 + z3;
        float z13 = t7 - z3;
        ws[5 * 8 + c] = z13 + z2;
        ws[3 * 8 + c] = z13 - z2;
        ws[1 * 8 + c] = z11 + z4;
        ws[7 * 8 + c] = z11 - z4;
    }
#endif

    int32_t qcoeff[64];
    int last_nz = 0;
    for (int i = 0; i < 64; ++i) {
        float v = ws[kJpegZigZag[i]] * inv_quant[i];
        int32_t q = (int32_t)(v >= 0.0f ? (v + 0.5f) : (v - 0.5f));
        qcoeff[i] = q;
        if (q != 0) last_nz = i;
    }

    /* DC coefficient */
    int32_t diff = qcoeff[0] - *dc_pred;
    *dc_pred = qcoeff[0];
    int dc_cat = fj_bit_category(diff);
    if (dc_cat > 0) {
        uint32_t val_bits = (uint32_t)(diff < 0 ? (diff - 1) : diff) & ((1u << dc_cat) - 1u);
        fj_bw_put_bits_unchecked(bw, ((uint32_t)dc_huff->code[dc_cat] << dc_cat) | val_bits, dc_huff->len[dc_cat] + dc_cat);
    } else {
        fj_bw_put_bits_unchecked(bw, dc_huff->code[0], dc_huff->len[0]);
    }

    /* AC coefficients up to last_nz */
    int zero_run = 0;
    for (int i = 1; i <= last_nz; ++i) {
        int32_t ac = qcoeff[i];
        if (ac == 0) {
            zero_run++;
            continue;
        }
        while (zero_run >= 16) {
            fj_bw_put_bits_unchecked(bw, ac_huff->code[0xF0], ac_huff->len[0xF0]);
            zero_run -= 16;
        }
        int ac_cat = fj_bit_category(ac);
        uint8_t sym = (uint8_t)((zero_run << 4) | ac_cat);
        uint32_t val_bits = (uint32_t)(ac < 0 ? (ac - 1) : ac) & ((1u << ac_cat) - 1u);
        fj_bw_put_bits_unchecked(bw, ((uint32_t)ac_huff->code[sym] << ac_cat) | val_bits, ac_huff->len[sym] + ac_cat);
        zero_run = 0;
    }
    if (last_nz < 63) {
        fj_bw_put_bits_unchecked(bw, ac_huff->code[0x00], ac_huff->len[0x00]);
    }
}

typedef enum {
    FJ_BUILTIN_SRC_BGR24 = 0,
    FJ_BUILTIN_SRC_UYVY  = 1,
    FJ_BUILTIN_SRC_SCALED_BGR24 = 2,
    FJ_BUILTIN_SRC_YYUV  = 3
} fj_builtin_src_mode_t;

typedef struct {
    fj_builtin_src_mode_t mode;
    const uint8_t* src;
    int32_t src_w;
    int32_t src_h;
    int32_t src_stride;
    int32_t crop_x;
    int32_t crop_y;
    int32_t crop_w;
    int32_t crop_h;
    int32_t dst_w;
    int32_t dst_h;
    int32_t aligned_w;
    int32_t mcu_cols;
    int32_t mcu_rows;
    int32_t rows_per_iv;
    int32_t num_iv;
    int32_t n_threads;
    const int32_t* x_map;
    float inv_q_y[64];
    float inv_q_c[64];
    fj_huff_lut_t dc_luma_huff;
    fj_huff_lut_t dc_chroma_huff;
    fj_huff_lut_t ac_luma_huff;
    fj_huff_lut_t ac_chroma_huff;
    int32_t rgb2y_r[256], rgb2y_g[256], rgb2y_b[256];
    int32_t rgb2cb_r[256], rgb2cb_g[256], rgb2cb_b[256];
    int32_t rgb2cr_r[256], rgb2cr_g[256], rgb2cr_b[256];
    fj_bitwriter_t thread_bw[LSRAW_MAX_THREADS];
} fj_builtin_job_t;

typedef struct {
    fj_builtin_job_t* job;
    int32_t thread_idx;
} fj_builtin_worker_arg_t;

static void fj_builtin_encode_worker(fj_builtin_job_t* job, int32_t tid) {
    int32_t iv_start = (tid * job->num_iv) / job->n_threads;
    int32_t iv_end   = ((tid + 1) * job->num_iv) / job->n_threads;
    if (iv_start >= iv_end) return;

    int32_t aw = job->aligned_w;
    int32_t cw = aw >> 1;
    size_t y_strip_sz = (size_t)9 * (size_t)aw;
    size_t c_strip_sz = (size_t)9 * (size_t)cw;
    uint8_t* strip = (uint8_t*)malloc(y_strip_sz + 2u * c_strip_sz);
    if (!strip) {
        job->thread_bw[tid].oom = 1;
        return;
    }
    uint8_t* y_strip  = strip;
    uint8_t* cb_strip = y_strip + y_strip_sz;
    uint8_t* cr_strip = cb_strip + c_strip_sz;
    /* Row slot 8 stores the last converted scaled source row so adjacent MCU rows sharing sy skip BGR->YCbCr */
    uint8_t* cached_y  = y_strip  + (size_t)8 * (size_t)aw;
    uint8_t* cached_cb = cb_strip + (size_t)8 * (size_t)cw;
    uint8_t* cached_cr = cr_strip + (size_t)8 * (size_t)cw;
    int32_t cached_sy = -1;

    fj_bitwriter_t* bw = &job->thread_bw[tid];
    size_t est_bytes = (size_t)(iv_end - iv_start) * (size_t)job->rows_per_iv * (size_t)job->dst_w + 4096u;
    if (est_bytes < 65536u) est_bytes = 65536u;
    fj_bw_reserve(bw, est_bytes);

    int32_t dst_w = job->dst_w;
    int32_t dst_h = job->dst_h;
    int32_t last_dx = dst_w - 1;
    int32_t last_dy = dst_h - 1;

    for (int32_t iv = iv_start; iv < iv_end; ++iv) {
        int32_t mr_start = iv * job->rows_per_iv;
        int32_t mr_end   = mr_start + job->rows_per_iv;
        if (mr_end > job->mcu_rows) mr_end = job->mcu_rows;

        int32_t dc_y = 0, dc_cb = 0, dc_cr = 0;

        for (int32_t mr = mr_start; mr < mr_end; ++mr) {
            int32_t base_y = mr * 8;
            int all_rows_identical = 0;
            unsigned repeated_rows = 0;

            if (job->mode == FJ_BUILTIN_SRC_SCALED_BGR24) {
                int32_t dy0 = base_y > last_dy ? last_dy : base_y;
                int32_t dy7 = (base_y + 7) > last_dy ? last_dy : (base_y + 7);
                int32_t sy0 = clamp_i32(job->crop_y + (int32_t)(((int64_t)dy0 * (int64_t)job->crop_h) / dst_h), 0, job->src_h - 1);
                int32_t sy7 = clamp_i32(job->crop_y + (int32_t)(((int64_t)dy7 * (int64_t)job->crop_h) / dst_h), 0, job->src_h - 1);
                if (sy0 == sy7) {
                    all_rows_identical = 1;
                    if (sy0 != cached_sy) {
                        cached_sy = sy0;
                        const uint8_t* srow = job->src + (size_t)sy0 * (size_t)job->src_stride;
                        const int32_t* xmap = job->x_map;
                        for (int32_t x = 0; x + 1 < dst_w; x += 2) {
                            const uint8_t* p0 = srow + xmap[x];
                            const uint8_t* p1 = srow + xmap[x + 1];
                            uint8_t b0 = p0[0], g0 = p0[1], r0 = p0[2];
                            uint8_t b1 = p1[0], g1 = p1[1], r1 = p1[2];
                            cached_y[x]     = (uint8_t)((job->rgb2y_r[r0] + job->rgb2y_g[g0] + job->rgb2y_b[b0]) >> 16);
                            cached_y[x + 1] = (uint8_t)((job->rgb2y_r[r1] + job->rgb2y_g[g1] + job->rgb2y_b[b1]) >> 16);
                            int32_t cb0 = (job->rgb2cb_r[r0] + job->rgb2cb_g[g0] + job->rgb2cb_b[b0]) >> 16;
                            int32_t cb1 = (job->rgb2cb_r[r1] + job->rgb2cb_g[g1] + job->rgb2cb_b[b1]) >> 16;
                            int32_t cr0 = (job->rgb2cr_r[r0] + job->rgb2cr_g[g0] + job->rgb2cr_b[b0]) >> 16;
                            int32_t cr1 = (job->rgb2cr_r[r1] + job->rgb2cr_g[g1] + job->rgb2cr_b[b1]) >> 16;
                            cached_cb[x >> 1] = (uint8_t)((cb0 + cb1 + 1) >> 1);
                            cached_cr[x >> 1] = (uint8_t)((cr0 + cr1 + 1) >> 1);
                        }
                        if (dst_w & 1) {
                            int32_t x = dst_w - 1;
                            const uint8_t* p0 = srow + xmap[x];
                            uint8_t b0 = p0[0], g0 = p0[1], r0 = p0[2];
                            cached_y[x] = (uint8_t)((job->rgb2y_r[r0] + job->rgb2y_g[g0] + job->rgb2y_b[b0]) >> 16);
                            cached_cb[x >> 1] = (uint8_t)((job->rgb2cb_r[r0] + job->rgb2cb_g[g0] + job->rgb2cb_b[b0]) >> 16);
                            cached_cr[x >> 1] = (uint8_t)((job->rgb2cr_r[r0] + job->rgb2cr_g[g0] + job->rgb2cr_b[b0]) >> 16);
                        }
                        uint8_t pad_y  = cached_y[last_dx];
                        uint8_t pad_cb = cached_cb[last_dx >> 1];
                        uint8_t pad_cr = cached_cr[last_dx >> 1];
                        for (int32_t x = dst_w; x < aw; ++x) cached_y[x] = pad_y;
                        for (int32_t cx = (dst_w + 1) >> 1; cx < cw; ++cx) {
                            cached_cb[cx] = pad_cb;
                            cached_cr[cx] = pad_cr;
                        }
                    }
                }
            }

            if (all_rows_identical) {
                for (int32_t mc = 0; mc < job->mcu_cols; ++mc) {
                    int32_t x0 = mc * 16;
                    int32_t cx0 = mc * 8;
                    fj_encode_block_row_const(cached_y + x0,     job->inv_q_y, &dc_y,  &job->dc_luma_huff,   &job->ac_luma_huff,   bw);
                    fj_encode_block_row_const(cached_y + x0 + 8, job->inv_q_y, &dc_y,  &job->dc_luma_huff,   &job->ac_luma_huff,   bw);
                    fj_encode_block_row_const(cached_cb + cx0,   job->inv_q_c, &dc_cb, &job->dc_chroma_huff, &job->ac_chroma_huff, bw);
                    fj_encode_block_row_const(cached_cr + cx0,   job->inv_q_c, &dc_cr, &job->dc_chroma_huff, &job->ac_chroma_huff, bw);
                }
                continue;
            }

            for (int r = 0; r < 8; ++r) {
                int32_t dy = base_y + r;
                if (dy > last_dy) dy = last_dy;

                uint8_t* y_dst  = y_strip  + (size_t)r * (size_t)aw;
                uint8_t* cb_dst = cb_strip + (size_t)r * (size_t)cw;
                uint8_t* cr_dst = cr_strip + (size_t)r * (size_t)cw;

                if (job->mode == FJ_BUILTIN_SRC_UYVY) {
                    const uint8_t* srow = job->src + (size_t)dy * (size_t)job->src_stride;
                    for (int32_t x = 0; x + 1 < dst_w; x += 2) {
                        uint8_t u  = srow[x * 2 + 0];
                        uint8_t y0 = srow[x * 2 + 1];
                        uint8_t v  = srow[x * 2 + 2];
                        uint8_t y1 = srow[x * 2 + 3];
                        if ((u | y0 | v | y1) == 0u ||
                            (u == 0u && y0 == 0u && v == 128u && y1 == 128u) ||
                            (u == 128u && y0 == 128u && v == 0u && y1 == 0u)) {
                            y0 = 0u; y1 = 0u; u = 128u; v = 128u;
                        }
                        y_dst[x]     = y0;
                        y_dst[x + 1] = y1;
                        cb_dst[x >> 1] = u;
                        cr_dst[x >> 1] = v;
                    }
                    if (dst_w & 1) {
                        int32_t x = dst_w - 1;
                        uint8_t u  = srow[x * 2 + 0];
                        uint8_t y0 = srow[x * 2 + 1];
                        uint8_t v  = srow[x * 2 + 2];
                        if ((y0 | u | v) == 0u) { u = 128u; v = 128u; }
                        y_dst[x] = y0;
                        cb_dst[x >> 1] = u;
                        cr_dst[x >> 1] = v;
                    }
                } else if (job->mode == FJ_BUILTIN_SRC_YYUV) {
                    const uint8_t* srow = job->src + (size_t)dy * (size_t)job->src_stride;
                    for (int32_t x = 0; x + 1 < dst_w; x += 2) {
                        uint8_t y0 = srow[x * 2 + 0];
                        uint8_t y1 = srow[x * 2 + 1];
                        uint8_t u  = srow[x * 2 + 2];
                        uint8_t v  = srow[x * 2 + 3];
                        if ((y0 | y1 | u | v) == 0u ||
                            (y0 == 128u && y1 == 128u && u == 0u && v == 0u)) {
                            y0 = 0u; y1 = 0u; u = 128u; v = 128u;
                        }
                        y_dst[x]     = y0;
                        y_dst[x + 1] = y1;
                        cb_dst[x >> 1] = u;
                        cr_dst[x >> 1] = v;
                    }
                    if (dst_w & 1) {
                        int32_t x = dst_w - 1;
                        uint8_t y0 = srow[x * 2 + 0];
                        uint8_t u  = srow[x * 2 + 2];
                        uint8_t v  = srow[x * 2 + 3];
                        if ((y0 | u | v) == 0u) { u = 128u; v = 128u; }
                        y_dst[x] = y0;
                        cb_dst[x >> 1] = u;
                        cr_dst[x >> 1] = v;
                    }
                } else if (job->mode == FJ_BUILTIN_SRC_BGR24) {
                    const uint8_t* srow = job->src + (size_t)dy * (size_t)job->src_stride;
                    for (int32_t x = 0; x + 1 < dst_w; x += 2) {
                        const uint8_t* p0 = srow + (size_t)x * 3u;
                        const uint8_t* p1 = p0 + 3;
                        uint8_t b0 = p0[0], g0 = p0[1], r0 = p0[2];
                        uint8_t b1 = p1[0], g1 = p1[1], r1 = p1[2];
                        y_dst[x]     = (uint8_t)((job->rgb2y_r[r0] + job->rgb2y_g[g0] + job->rgb2y_b[b0]) >> 16);
                        y_dst[x + 1] = (uint8_t)((job->rgb2y_r[r1] + job->rgb2y_g[g1] + job->rgb2y_b[b1]) >> 16);
                        int32_t cb0 = (job->rgb2cb_r[r0] + job->rgb2cb_g[g0] + job->rgb2cb_b[b0]) >> 16;
                        int32_t cb1 = (job->rgb2cb_r[r1] + job->rgb2cb_g[g1] + job->rgb2cb_b[b1]) >> 16;
                        int32_t cr0 = (job->rgb2cr_r[r0] + job->rgb2cr_g[g0] + job->rgb2cr_b[b0]) >> 16;
                        int32_t cr1 = (job->rgb2cr_r[r1] + job->rgb2cr_g[g1] + job->rgb2cr_b[b1]) >> 16;
                        cb_dst[x >> 1] = (uint8_t)((cb0 + cb1 + 1) >> 1);
                        cr_dst[x >> 1] = (uint8_t)((cr0 + cr1 + 1) >> 1);
                    }
                    if (dst_w & 1) {
                        int32_t x = dst_w - 1;
                        const uint8_t* p0 = srow + (size_t)x * 3u;
                        uint8_t b0 = p0[0], g0 = p0[1], r0 = p0[2];
                        y_dst[x] = (uint8_t)((job->rgb2y_r[r0] + job->rgb2y_g[g0] + job->rgb2y_b[b0]) >> 16);
                        cb_dst[x >> 1] = (uint8_t)((job->rgb2cb_r[r0] + job->rgb2cb_g[g0] + job->rgb2cb_b[b0]) >> 16);
                        cr_dst[x >> 1] = (uint8_t)((job->rgb2cr_r[r0] + job->rgb2cr_g[g0] + job->rgb2cr_b[b0]) >> 16);
                    }
                } else {
                    int32_t sy = job->crop_y + (int32_t)(((int64_t)dy * (int64_t)job->crop_h) / dst_h);
                    sy = clamp_i32(sy, 0, job->src_h - 1);
                    if (sy == cached_sy) {
                        if (r != 0) repeated_rows |= 1u << r;
                        memcpy(y_dst,  cached_y,  (size_t)aw);
                        memcpy(cb_dst, cached_cb, (size_t)cw);
                        memcpy(cr_dst, cached_cr, (size_t)cw);
                        continue;
                    }
                    cached_sy = sy;
                    const uint8_t* srow = job->src + (size_t)sy * (size_t)job->src_stride;
                    const int32_t* xmap = job->x_map;
                    for (int32_t x = 0; x + 1 < dst_w; x += 2) {
                        const uint8_t* p0 = srow + xmap[x];
                        const uint8_t* p1 = srow + xmap[x + 1];
                        uint8_t b0 = p0[0], g0 = p0[1], r0 = p0[2];
                        uint8_t b1 = p1[0], g1 = p1[1], r1 = p1[2];
                        y_dst[x]     = (uint8_t)((job->rgb2y_r[r0] + job->rgb2y_g[g0] + job->rgb2y_b[b0]) >> 16);
                        y_dst[x + 1] = (uint8_t)((job->rgb2y_r[r1] + job->rgb2y_g[g1] + job->rgb2y_b[b1]) >> 16);
                        int32_t cb0 = (job->rgb2cb_r[r0] + job->rgb2cb_g[g0] + job->rgb2cb_b[b0]) >> 16;
                        int32_t cb1 = (job->rgb2cb_r[r1] + job->rgb2cb_g[g1] + job->rgb2cb_b[b1]) >> 16;
                        int32_t cr0 = (job->rgb2cr_r[r0] + job->rgb2cr_g[g0] + job->rgb2cr_b[b0]) >> 16;
                        int32_t cr1 = (job->rgb2cr_r[r1] + job->rgb2cr_g[g1] + job->rgb2cr_b[b1]) >> 16;
                        cb_dst[x >> 1] = (uint8_t)((cb0 + cb1 + 1) >> 1);
                        cr_dst[x >> 1] = (uint8_t)((cr0 + cr1 + 1) >> 1);
                    }
                    if (dst_w & 1) {
                        int32_t x = dst_w - 1;
                        const uint8_t* p0 = srow + xmap[x];
                        uint8_t b0 = p0[0], g0 = p0[1], r0 = p0[2];
                        y_dst[x] = (uint8_t)((job->rgb2y_r[r0] + job->rgb2y_g[g0] + job->rgb2y_b[b0]) >> 16);
                        cb_dst[x >> 1] = (uint8_t)((job->rgb2cb_r[r0] + job->rgb2cb_g[g0] + job->rgb2cb_b[b0]) >> 16);
                        cr_dst[x >> 1] = (uint8_t)((job->rgb2cr_r[r0] + job->rgb2cr_g[g0] + job->rgb2cr_b[b0]) >> 16);
                    }
                    uint8_t pad_y  = y_dst[last_dx];
                    uint8_t pad_cb = cb_dst[last_dx >> 1];
                    uint8_t pad_cr = cr_dst[last_dx >> 1];
                    for (int32_t x = dst_w; x < aw; ++x) y_dst[x] = pad_y;
                    for (int32_t cx = (dst_w + 1) >> 1; cx < cw; ++cx) {
                        cb_dst[cx] = pad_cb;
                        cr_dst[cx] = pad_cr;
                    }
                    memcpy(cached_y,  y_dst,  (size_t)aw);
                    memcpy(cached_cb, cb_dst, (size_t)cw);
                    memcpy(cached_cr, cr_dst, (size_t)cw);
                    continue;
                }

                /* Edge-pad right margin to 16-pixel MCU width */
                uint8_t pad_y  = y_dst[last_dx];
                uint8_t pad_cb = cb_dst[last_dx >> 1];
                uint8_t pad_cr = cr_dst[last_dx >> 1];
                for (int32_t x = dst_w; x < aw; ++x) y_dst[x] = pad_y;
                for (int32_t cx = (dst_w + 1) >> 1; cx < cw; ++cx) {
                    cb_dst[cx] = pad_cb;
                    cr_dst[cx] = pad_cr;
                }
            }

            /* Encode MCU columns for this 8-row strip */
            for (int32_t mc = 0; mc < job->mcu_cols; ++mc) {
                int32_t x0 = mc * 16;
                int32_t cx0 = mc * 8;
                fj_encode_block_aan(y_strip + x0,      aw, repeated_rows, job->inv_q_y, &dc_y,  &job->dc_luma_huff,   &job->ac_luma_huff,   bw);
                fj_encode_block_aan(y_strip + x0 + 8,  aw, repeated_rows, job->inv_q_y, &dc_y,  &job->dc_luma_huff,   &job->ac_luma_huff,   bw);
                fj_encode_block_aan(cb_strip + cx0,    cw, repeated_rows, job->inv_q_c, &dc_cb, &job->dc_chroma_huff, &job->ac_chroma_huff, bw);
                fj_encode_block_aan(cr_strip + cx0,    cw, repeated_rows, job->inv_q_c, &dc_cr, &job->dc_chroma_huff, &job->ac_chroma_huff, bw);
            }
        }

        fj_bw_flush_rst(bw, (iv < job->num_iv - 1) ? 1 : 0, iv);
    }

    free(strip);
}

#if defined(_WIN32)
static unsigned __stdcall fj_builtin_worker_fn(void* arg) {
    fj_builtin_worker_arg_t* a = (fj_builtin_worker_arg_t*)arg;
    fj_builtin_encode_worker(a->job, a->thread_idx);
    return 0;
}
#else
static void* fj_builtin_worker_fn(void* arg) {
    fj_builtin_worker_arg_t* a = (fj_builtin_worker_arg_t*)arg;
    fj_builtin_encode_worker(a->job, a->thread_idx);
    return NULL;
}
#endif

static int32_t fj_write_dht_marker(uint8_t* dst, uint8_t tc_th, const uint8_t bits[16], const uint8_t* vals, int32_t nvals) {
    int32_t pos = 0;
    uint16_t mlen = (uint16_t)(2 + 1 + 16 + nvals);
    dst[pos++] = 0xFF; dst[pos++] = 0xC4;
    dst[pos++] = (uint8_t)(mlen >> 8); dst[pos++] = (uint8_t)(mlen & 0xFF);
    dst[pos++] = tc_th;
    memcpy(dst + pos, bits, 16); pos += 16;
    memcpy(dst + pos, vals, (size_t)nvals); pos += nvals;
    return pos;
}

static int32_t fj_builtin_jpeg_encode_core(
    fj_builtin_src_mode_t mode,
    const uint8_t* src,
    int32_t src_w,
    int32_t src_h,
    int32_t src_stride,
    int32_t crop_x,
    int32_t crop_y,
    int32_t crop_w,
    int32_t crop_h,
    int32_t dst_w,
    int32_t dst_h,
    int32_t quality,
    int32_t max_bytes,
    uint8_t* dst_fixed,
    int32_t dst_fixed_cap,
    uint8_t** out_alloc,
    int32_t* out_len
) {
    fj_builtin_job_t* job = (fj_builtin_job_t*)calloc(1, sizeof(fj_builtin_job_t));
    if (!job) return FUJI_CODEC_ERR_CODEC;

    job->mode = mode;
    job->src = src;
    job->src_w = src_w;
    job->src_h = src_h;
    job->src_stride = src_stride;
    job->crop_x = crop_x;
    job->crop_y = crop_y;
    job->crop_w = crop_w;
    job->crop_h = crop_h;
    job->dst_w = dst_w;
    job->dst_h = dst_h;
    job->aligned_w = (dst_w + 15) & ~15;
    job->mcu_cols = job->aligned_w >> 4;
    job->mcu_rows = (dst_h + 7) >> 3;

    int32_t* x_map_buf = NULL;
    if (mode == FJ_BUILTIN_SRC_SCALED_BGR24) {
        x_map_buf = (int32_t*)malloc((size_t)dst_w * sizeof(int32_t));
        if (!x_map_buf) {
            free(job);
            return FUJI_CODEC_ERR_CODEC;
        }
        for (int32_t dx = 0; dx < dst_w; ++dx) {
            int32_t sx = crop_x + (int32_t)(((int64_t)dx * (int64_t)crop_w) / dst_w);
            sx = clamp_i32(sx, 0, src_w - 1);
            x_map_buf[dx] = sx * 3;
        }
        job->x_map = x_map_buf;
    }

    int32_t q = clamp_i32(quality, 1, 100);
    int32_t q_scale = (q < 50) ? (5000 / q) : (200 - q * 2);
    uint8_t qtbl_y_nat[64], qtbl_c_nat[64];
    for (int i = 0; i < 64; ++i) {
        qtbl_y_nat[i] = (uint8_t)clamp_i32((kStdLumaQuant[i] * q_scale + 50) / 100, 1, 255);
        qtbl_c_nat[i] = (uint8_t)clamp_i32((kStdChromaQuant[i] * q_scale + 50) / 100, 1, 255);
    }
    for (int i = 0; i < 64; ++i) {
        int nat = kJpegZigZag[i];
        int r = nat >> 3;
        int c = nat & 7;
        float aan = 8.0f * kAanScale[r] * kAanScale[c];
        job->inv_q_y[i] = 1.0f / (aan * (float)qtbl_y_nat[nat]);
        job->inv_q_c[i] = 1.0f / (aan * (float)qtbl_c_nat[nat]);
    }

    fj_huff_build(&job->dc_luma_huff,   kDcLumaBits,   kDcLumaVals);
    fj_huff_build(&job->dc_chroma_huff, kDcChromaBits, kDcChromaVals);
    fj_huff_build(&job->ac_luma_huff,   kAcLumaBits,   kAcLumaVals);
    fj_huff_build(&job->ac_chroma_huff, kAcChromaBits, kAcChromaVals);

    for (int i = 0; i < 256; ++i) {
        job->rgb2y_r[i]  =  19595 * i;
        job->rgb2y_g[i]  =  38470 * i;
        job->rgb2y_b[i]  =   7471 * i + 32768;
        job->rgb2cb_r[i] = -11056 * i;
        job->rgb2cb_g[i] = -21712 * i;
        job->rgb2cb_b[i] =  32768 * i + (128 << 16) + 32768;
        job->rgb2cr_r[i] =  32768 * i;
        job->rgb2cr_g[i] = -27440 * i;
        job->rgb2cr_b[i] =  -5328 * i + (128 << 16) + 32768;
    }

    int32_t n_threads = fuji_codec_default_threads();
    if (n_threads > LSRAW_MAX_THREADS) n_threads = LSRAW_MAX_THREADS;
    if ((int64_t)dst_w * (int64_t)dst_h < 262144LL || job->mcu_cols > 65535 || job->mcu_rows < 8) {
        n_threads = 1;
    }

    int32_t dri = 0;
    if (n_threads > 1) {
        int32_t target_iv = n_threads * 2;
        int32_t rpi = job->mcu_rows / target_iv;
        if (rpi < 1) rpi = 1;
        while (rpi > 1 && (int64_t)rpi * (int64_t)job->mcu_cols > 65535LL) {
            rpi--;
        }
        int32_t niv = (job->mcu_rows + rpi - 1) / rpi;
        if (niv > 1 && (int64_t)rpi * (int64_t)job->mcu_cols <= 65535LL) {
            job->rows_per_iv = rpi;
            job->num_iv = niv;
            dri = rpi * job->mcu_cols;
            if (n_threads > niv) n_threads = niv;
        } else {
            n_threads = 1;
        }
    }
    if (n_threads <= 1) {
        n_threads = 1;
        job->rows_per_iv = job->mcu_rows;
        job->num_iv = 1;
        dri = 0;
    }
    job->n_threads = n_threads;

    if (n_threads == 1) {
        fj_builtin_encode_worker(job, 0);
    } else {
        fj_builtin_worker_arg_t args[LSRAW_MAX_THREADS];
#if defined(_WIN32)
        HANDLE threads[LSRAW_MAX_THREADS];
        int spawned = 0;
        for (int32_t t = 1; t < n_threads; ++t) {
            args[t].job = job;
            args[t].thread_idx = t;
            uintptr_t h = _beginthreadex(NULL, 0, fj_builtin_worker_fn, &args[t], 0, NULL);
            if (h != 0) threads[spawned++] = (HANDLE)h;
            else fj_builtin_encode_worker(job, t);
        }
        fj_builtin_encode_worker(job, 0);
        if (spawned > 0) {
            WaitForMultipleObjects((DWORD)spawned, threads, TRUE, INFINITE);
            for (int i = 0; i < spawned; ++i) CloseHandle(threads[i]);
        }
#else
        pthread_t threads[LSRAW_MAX_THREADS];
        int spawned[LSRAW_MAX_THREADS];
        memset(spawned, 0, sizeof(spawned));
        for (int32_t t = 1; t < n_threads; ++t) {
            args[t].job = job;
            args[t].thread_idx = t;
            if (pthread_create(&threads[t], NULL, fj_builtin_worker_fn, &args[t]) == 0) {
                spawned[t] = 1;
            } else {
                fj_builtin_encode_worker(job, t);
            }
        }
        fj_builtin_encode_worker(job, 0);
        for (int32_t t = 1; t < n_threads; ++t) {
            if (spawned[t]) pthread_join(threads[t], NULL);
        }
#endif
    }

    int oom = 0;
    size_t scan_bytes = 0;
    for (int32_t t = 0; t < n_threads; ++t) {
        if (job->thread_bw[t].oom) oom = 1;
        scan_bytes += job->thread_bw[t].len;
    }

    uint8_t hdr[768];
    int32_t hpos = 0;
    /* SOI */
    hdr[hpos++] = 0xFF; hdr[hpos++] = 0xD8;
    /* APP0 JFIF 1.01 */
    static const uint8_t kApp0[18] = {
        0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00,
        0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
    };
    memcpy(hdr + hpos, kApp0, sizeof(kApp0)); hpos += (int32_t)sizeof(kApp0);

    /* DQT luma + chroma in zigzag order */
    hdr[hpos++] = 0xFF; hdr[hpos++] = 0xDB; hdr[hpos++] = 0x00; hdr[hpos++] = 0x43; hdr[hpos++] = 0x00;
    for (int i = 0; i < 64; ++i) hdr[hpos++] = qtbl_y_nat[kJpegZigZag[i]];
    hdr[hpos++] = 0xFF; hdr[hpos++] = 0xDB; hdr[hpos++] = 0x00; hdr[hpos++] = 0x43; hdr[hpos++] = 0x01;
    for (int i = 0; i < 64; ++i) hdr[hpos++] = qtbl_c_nat[kJpegZigZag[i]];

    /* SOF0 (Baseline DCT, 8-bit, YCbCr 4:2:2: Y=2x1 q0, Cb=1x1 q1, Cr=1x1 q1) */
    hdr[hpos++] = 0xFF; hdr[hpos++] = 0xC0; hdr[hpos++] = 0x00; hdr[hpos++] = 0x11;
    hdr[hpos++] = 0x08;
    hdr[hpos++] = (uint8_t)(dst_h >> 8); hdr[hpos++] = (uint8_t)(dst_h & 0xFF);
    hdr[hpos++] = (uint8_t)(dst_w >> 8); hdr[hpos++] = (uint8_t)(dst_w & 0xFF);
    hdr[hpos++] = 0x03;
    hdr[hpos++] = 0x01; hdr[hpos++] = 0x21; hdr[hpos++] = 0x00;
    hdr[hpos++] = 0x02; hdr[hpos++] = 0x11; hdr[hpos++] = 0x01;
    hdr[hpos++] = 0x03; hdr[hpos++] = 0x11; hdr[hpos++] = 0x01;

    /* DHT */
    hpos += fj_write_dht_marker(hdr + hpos, 0x00, kDcLumaBits,   kDcLumaVals,   12);
    hpos += fj_write_dht_marker(hdr + hpos, 0x10, kAcLumaBits,   kAcLumaVals,   162);
    hpos += fj_write_dht_marker(hdr + hpos, 0x01, kDcChromaBits, kDcChromaVals, 12);
    hpos += fj_write_dht_marker(hdr + hpos, 0x11, kAcChromaBits, kAcChromaVals, 162);

    if (dri > 0) {
        hdr[hpos++] = 0xFF; hdr[hpos++] = 0xDD; hdr[hpos++] = 0x00; hdr[hpos++] = 0x04;
        hdr[hpos++] = (uint8_t)(dri >> 8); hdr[hpos++] = (uint8_t)(dri & 0xFF);
    }

    /* SOS */
    static const uint8_t kSos[14] = {
        0xFF, 0xDA, 0x00, 0x0C, 0x03,
        0x01, 0x00, 0x02, 0x11, 0x03, 0x11,
        0x00, 0x3F, 0x00
    };
    memcpy(hdr + hpos, kSos, sizeof(kSos)); hpos += (int32_t)sizeof(kSos);

    int32_t rc = FUJI_CODEC_ERR_CODEC;
    size_t total_sz = (size_t)hpos + scan_bytes + 2u;
    if (!oom && total_sz <= (size_t)INT32_MAX) {
        int32_t total = (int32_t)total_sz;
        if (out_len) *out_len = total;
        if (out_alloc) {
            if (max_bytes > 0 && total > max_bytes) {
                rc = FUJI_CODEC_ERR_OVERFLOW;
            } else {
                uint8_t* out = (uint8_t*)malloc(total_sz);
                if (out) {
                    size_t pos = 0;
                    memcpy(out + pos, hdr, (size_t)hpos); pos += (size_t)hpos;
                    for (int32_t t = 0; t < n_threads; ++t) {
                        if (job->thread_bw[t].len > 0) {
                            memcpy(out + pos, job->thread_bw[t].data, job->thread_bw[t].len);
                            pos += job->thread_bw[t].len;
                        }
                    }
                    out[pos++] = 0xFF; out[pos++] = 0xD9;
                    *out_alloc = out;
                    rc = total;
                }
            }
        } else {
            if (!dst_fixed || total > dst_fixed_cap) {
                rc = FUJI_CODEC_ERR_OVERFLOW;
            } else {
                size_t pos = 0;
                memcpy(dst_fixed + pos, hdr, (size_t)hpos); pos += (size_t)hpos;
                for (int32_t t = 0; t < n_threads; ++t) {
                    if (job->thread_bw[t].len > 0) {
                        memcpy(dst_fixed + pos, job->thread_bw[t].data, job->thread_bw[t].len);
                        pos += job->thread_bw[t].len;
                    }
                }
                dst_fixed[pos++] = 0xFF; dst_fixed[pos++] = 0xD9;
                rc = total;
            }
        }
    }

    for (int32_t t = 0; t < n_threads; ++t) free(job->thread_bw[t].data);
    free(x_map_buf);
    free(job);
    return rc;
}

static int32_t fj_builtin_jpeg_encode_422_alloc(
    const uint8_t* src_422,
    int32_t width,
    int32_t height,
    int32_t src_stride_bytes,
    int32_t quality,
    int32_t max_bytes,
    uint8_t** out_buf,
    int32_t* out_actual_size,
    int is_yyuv
) {
    return fj_builtin_jpeg_encode_core(
        is_yyuv ? FJ_BUILTIN_SRC_YYUV : FJ_BUILTIN_SRC_UYVY,
        src_422, width, height, src_stride_bytes,
        0, 0, width, height, width, height, quality, max_bytes,
        NULL, 0, out_buf, out_actual_size
    );
}

static int32_t fj_builtin_jpeg_encode_bgr24_alloc(
    const uint8_t* src_bgr24,
    int32_t width,
    int32_t height,
    int32_t src_stride_bytes,
    int32_t quality,
    int32_t max_bytes,
    uint8_t** out_buf,
    int32_t* out_actual_size
) {
    return fj_builtin_jpeg_encode_core(
        FJ_BUILTIN_SRC_BGR24, src_bgr24, width, height, src_stride_bytes,
        0, 0, width, height, width, height, quality, max_bytes,
        NULL, 0, out_buf, out_actual_size
    );
}

static int32_t jpeg_encode_422_alloc(
    const uint8_t* src_422,
    int32_t width,
    int32_t height,
    int32_t src_stride_bytes,
    int32_t quality,
    int32_t max_bytes,
    uint8_t** out_buf,
    int32_t* out_actual_size,
    int is_yyuv
) {
    if (!src_422 || width <= 0 || height <= 0 || src_stride_bytes < width * 2 || !out_buf) {
        return FUJI_CODEC_ERR_INVALID_ARGS;
    }
    *out_buf = NULL;
    if (out_actual_size) *out_actual_size = 0;

    int32_t q = clamp_i32(quality, 1, 100);
    ensure_runtime_init();

    if (g_rt.has_libjpeg8) {
        /* Streaming 8-line planar YUV422 raw FDCT: ZERO RGB round-trip & only ~190 KB scratch for 102MP! */
        struct fj_jpeg_compress_struct cinfo;
        struct fj_err_ctx jerr;
        unsigned char* volatile mem_buf = NULL;
        unsigned long mem_size = 0;
        uint8_t* volatile strip_buf = NULL;

        memset(&cinfo, 0, sizeof(cinfo));
        cinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;

        if (setjmp(jerr.env) != 0) {
            free(strip_buf);
            if (mem_buf) free(mem_buf);
            g_rt.destroy_compress(&cinfo);
            return FUJI_CODEC_ERR_CODEC;
        }

        g_rt.create_compress(&cinfo, FJ_JPEG_LIB_VERSION, sizeof(cinfo));
        g_rt.mem_dest(&cinfo, (unsigned char**)&mem_buf, &mem_size);

        cinfo.image_width      = (fj_JDIMENSION)width;
        cinfo.image_height     = (fj_JDIMENSION)height;
        cinfo.input_components = 3;
        cinfo.in_color_space   = FJ_JCS_YCbCr;

        g_rt.set_defaults(&cinfo);
        g_rt.set_quality(&cinfo, q, 1);
        cinfo.dct_method = FJ_JDCT_ISLOW;
        /* Explicit 4:2:2 horizontal subsampling matching MemYc UYVY422 (1x8 iMCU row = 8 scanlines) */
        cinfo.comp_info[0].h_samp_factor = 2;
        cinfo.comp_info[0].v_samp_factor = 1;
        cinfo.comp_info[1].h_samp_factor = 1;
        cinfo.comp_info[1].v_samp_factor = 1;
        cinfo.comp_info[2].h_samp_factor = 1;
        cinfo.comp_info[2].v_samp_factor = 1;
        cinfo.raw_data_in = 1;

        g_rt.start_compress(&cinfo, 1);

        int32_t y_stride = (width + 15) & ~15;
        int32_t c_stride = y_stride >> 1;
        size_t y_plane_sz = (size_t)FJ_DCTSIZE * (size_t)y_stride;
        size_t c_plane_sz = (size_t)FJ_DCTSIZE * (size_t)c_stride;
        strip_buf = (uint8_t*)malloc(y_plane_sz + 2u * c_plane_sz);
        if (!strip_buf) {
            g_rt.destroy_compress(&cinfo);
            if (mem_buf) free(mem_buf);
            return FUJI_CODEC_ERR_CODEC;
        }

        uint8_t* y_base  = strip_buf;
        uint8_t* cb_base = y_base + y_plane_sz;
        uint8_t* cr_base = cb_base + c_plane_sz;

        fj_JSAMPROW y_rows[FJ_DCTSIZE];
        fj_JSAMPROW cb_rows[FJ_DCTSIZE];
        fj_JSAMPROW cr_rows[FJ_DCTSIZE];
        for (int r = 0; r < FJ_DCTSIZE; ++r) {
            y_rows[r]  = y_base  + (size_t)r * (size_t)y_stride;
            cb_rows[r] = cb_base + (size_t)r * (size_t)c_stride;
            cr_rows[r] = cr_base + (size_t)r * (size_t)c_stride;
        }
        fj_JSAMPARRAY planes[3] = { y_rows, cb_rows, cr_rows };

        for (int32_t y = 0; y < height; y += FJ_DCTSIZE) {
            for (int32_t r = 0; r < FJ_DCTSIZE; ++r) {
                int32_t sy = y + r;
                if (sy >= height) sy = height - 1;
                const uint8_t* src_row = src_422 + (size_t)sy * (size_t)src_stride_bytes;
                unpack_422_row_to_planar422(
                    src_row, width, is_yyuv, y_rows[r], cb_rows[r], cr_rows[r], y_stride, c_stride
                );
            }
            g_rt.write_raw_data(&cinfo, planes, FJ_DCTSIZE);
        }

        g_rt.finish_compress(&cinfo);
        g_rt.destroy_compress(&cinfo);
        free(strip_buf);

        if (!mem_buf || mem_size == 0 || mem_size > (unsigned long)INT32_MAX) {
            if (mem_buf) free(mem_buf);
            return FUJI_CODEC_ERR_CODEC;
        }
        int32_t actual = (int32_t)mem_size;
        if (out_actual_size) *out_actual_size = actual;
        if (max_bytes > 0 && actual > max_bytes) {
            free(mem_buf);
            return FUJI_CODEC_ERR_OVERFLOW;
        }
        *out_buf = mem_buf;
        return actual;
    }

    if (g_rt.has_libjpeg62) {
        struct fj62_jpeg_compress_struct cinfo;
        struct fj_err_ctx jerr;
        unsigned char* volatile mem_buf = NULL;
        unsigned long mem_size = 0;
        uint8_t* volatile strip_buf = NULL;

        memset(&cinfo, 0, sizeof(cinfo));
        cinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;

        if (setjmp(jerr.env) != 0) {
            free(strip_buf);
            if (mem_buf) free(mem_buf);
            g_rt.destroy_compress(&cinfo);
            return FUJI_CODEC_ERR_CODEC;
        }

        g_rt.create_compress(&cinfo, FJ_JPEG_LIB_VERSION_62, sizeof(cinfo));
        g_rt.mem_dest(&cinfo, (unsigned char**)&mem_buf, &mem_size);

        cinfo.image_width      = (fj_JDIMENSION)width;
        cinfo.image_height     = (fj_JDIMENSION)height;
        cinfo.input_components = 3;
        cinfo.in_color_space   = FJ_JCS_YCbCr;

        g_rt.set_defaults(&cinfo);
        g_rt.set_quality(&cinfo, q, 1);
        cinfo.dct_method = FJ_JDCT_ISLOW;
        cinfo.comp_info[0].h_samp_factor = 2;
        cinfo.comp_info[0].v_samp_factor = 1;
        cinfo.comp_info[1].h_samp_factor = 1;
        cinfo.comp_info[1].v_samp_factor = 1;
        cinfo.comp_info[2].h_samp_factor = 1;
        cinfo.comp_info[2].v_samp_factor = 1;
        cinfo.raw_data_in = 1;

        g_rt.start_compress(&cinfo, 1);

        int32_t y_stride = (width + 15) & ~15;
        int32_t c_stride = y_stride >> 1;
        size_t y_plane_sz = (size_t)FJ_DCTSIZE * (size_t)y_stride;
        size_t c_plane_sz = (size_t)FJ_DCTSIZE * (size_t)c_stride;
        strip_buf = (uint8_t*)malloc(y_plane_sz + 2u * c_plane_sz);
        if (!strip_buf) {
            g_rt.destroy_compress(&cinfo);
            if (mem_buf) free(mem_buf);
            return FUJI_CODEC_ERR_CODEC;
        }

        uint8_t* y_base  = strip_buf;
        uint8_t* cb_base = y_base + y_plane_sz;
        uint8_t* cr_base = cb_base + c_plane_sz;

        fj_JSAMPROW y_rows[FJ_DCTSIZE];
        fj_JSAMPROW cb_rows[FJ_DCTSIZE];
        fj_JSAMPROW cr_rows[FJ_DCTSIZE];
        for (int r = 0; r < FJ_DCTSIZE; ++r) {
            y_rows[r]  = y_base  + (size_t)r * (size_t)y_stride;
            cb_rows[r] = cb_base + (size_t)r * (size_t)c_stride;
            cr_rows[r] = cr_base + (size_t)r * (size_t)c_stride;
        }
        fj_JSAMPARRAY planes[3] = { y_rows, cb_rows, cr_rows };

        for (int32_t y = 0; y < height; y += FJ_DCTSIZE) {
            for (int32_t r = 0; r < FJ_DCTSIZE; ++r) {
                int32_t sy = y + r;
                if (sy >= height) sy = height - 1;
                const uint8_t* src_row = src_422 + (size_t)sy * (size_t)src_stride_bytes;
                unpack_422_row_to_planar422(
                    src_row, width, is_yyuv, y_rows[r], cb_rows[r], cr_rows[r], y_stride, c_stride
                );
            }
            g_rt.write_raw_data(&cinfo, planes, FJ_DCTSIZE);
        }

        g_rt.finish_compress(&cinfo);
        g_rt.destroy_compress(&cinfo);
        free(strip_buf);

        if (!mem_buf || mem_size == 0 || mem_size > (unsigned long)INT32_MAX) {
            if (mem_buf) free(mem_buf);
            return FUJI_CODEC_ERR_CODEC;
        }
        int32_t actual = (int32_t)mem_size;
        if (out_actual_size) *out_actual_size = actual;
        if (max_bytes > 0 && actual > max_bytes) {
            free(mem_buf);
            return FUJI_CODEC_ERR_OVERFLOW;
        }
        *out_buf = mem_buf;
        return actual;
    }

    if (g_rt.has_turbojpeg) {
        int32_t y_stride = (width + 15) & ~15;
        int32_t c_stride = y_stride >> 1;
        size_t y_sz = (size_t)height * (size_t)y_stride;
        size_t c_sz = (size_t)height * (size_t)c_stride;
        uint8_t* yuv = (uint8_t*)malloc(y_sz + 2u * c_sz);
        if (!yuv) return FUJI_CODEC_ERR_CODEC;

        uint8_t* y_plane  = yuv;
        uint8_t* cb_plane = y_plane + y_sz;
        uint8_t* cr_plane = cb_plane + c_sz;

        for (int32_t y = 0; y < height; ++y) {
            const uint8_t* src_row = src_422 + (size_t)y * (size_t)src_stride_bytes;
            unpack_422_row_to_planar422(
                src_row, width, is_yyuv,
                y_plane + (size_t)y * (size_t)y_stride,
                cb_plane + (size_t)y * (size_t)c_stride,
                cr_plane + (size_t)y * (size_t)c_stride,
                y_stride, c_stride
            );
        }

        tjhandle tj = g_rt.tj_init_compress();
        if (!tj) {
            free(yuv);
            return FUJI_CODEC_ERR_CODEC;
        }
        const unsigned char* planes[3] = { y_plane, cb_plane, cr_plane };
        int strides[3] = { y_stride, c_stride, c_stride };
        unsigned char* tj_buf = NULL;
        unsigned long tj_size = 0;
        int rc = g_rt.tj_compress_from_yuv_planes(
            tj, planes, width, strides, height, FJ_TJSAMP_422, &tj_buf, &tj_size, q, 0
        );
        g_rt.tj_destroy(tj);
        free(yuv);

        if (rc != 0 || !tj_buf || tj_size == 0 || tj_size > (unsigned long)INT32_MAX) {
            if (tj_buf) g_rt.tj_free(tj_buf);
            return FUJI_CODEC_ERR_CODEC;
        }
        int32_t actual = (int32_t)tj_size;
        if (out_actual_size) *out_actual_size = actual;
        if (max_bytes > 0 && actual > max_bytes) {
            g_rt.tj_free(tj_buf);
            return FUJI_CODEC_ERR_OVERFLOW;
        }
        /* Copy to CRT malloc buffer so fuji_codec_free() works uniformly across Windows CRTs */
        uint8_t* out = (uint8_t*)malloc((size_t)actual);
        if (!out) {
            g_rt.tj_free(tj_buf);
            return FUJI_CODEC_ERR_CODEC;
        }
        memcpy(out, tj_buf, (size_t)actual);
        g_rt.tj_free(tj_buf);
        *out_buf = out;
        return actual;
    }

    return fj_builtin_jpeg_encode_422_alloc(
        src_422, width, height, src_stride_bytes, q, max_bytes, out_buf, out_actual_size, is_yyuv
    );
}

FUJI_CODEC_EXPORT int32_t fuji_jpeg_encode_uyvy_alloc(
    const uint8_t* src_uyvy,
    int32_t width,
    int32_t height,
    int32_t src_stride_bytes,
    int32_t quality,
    int32_t max_bytes,
    uint8_t** out_buf,
    int32_t* out_actual_size
) {
    return jpeg_encode_422_alloc(
        src_uyvy, width, height, src_stride_bytes, quality, max_bytes, out_buf, out_actual_size, 0
    );
}

FUJI_CODEC_EXPORT int32_t fuji_jpeg_encode_yyuv_alloc(
    const uint8_t* src_yyuv,
    int32_t width,
    int32_t height,
    int32_t src_stride_bytes,
    int32_t quality,
    int32_t max_bytes,
    uint8_t** out_buf,
    int32_t* out_actual_size
) {
    return jpeg_encode_422_alloc(
        src_yyuv, width, height, src_stride_bytes, quality, max_bytes, out_buf, out_actual_size, 1
    );
}

FUJI_CODEC_EXPORT int32_t fuji_jpeg_encode_bgr24_alloc(
    const uint8_t* src_bgr24,
    int32_t width,
    int32_t height,
    int32_t src_stride_bytes,
    int32_t quality,
    int32_t max_bytes,
    uint8_t** out_buf,
    int32_t* out_actual_size
) {
    if (!src_bgr24 || width <= 0 || height <= 0 || src_stride_bytes < width * 3 || !out_buf) {
        return FUJI_CODEC_ERR_INVALID_ARGS;
    }
    *out_buf = NULL;
    if (out_actual_size) *out_actual_size = 0;

    int32_t q = clamp_i32(quality, 1, 100);
    ensure_runtime_init();

    if (g_rt.has_libjpeg8) {
        struct fj_jpeg_compress_struct cinfo;
        struct fj_err_ctx jerr;
        unsigned char* volatile mem_buf = NULL;
        unsigned long mem_size = 0;

        memset(&cinfo, 0, sizeof(cinfo));
        cinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;

        if (setjmp(jerr.env) != 0) {
            if (mem_buf) free(mem_buf);
            g_rt.destroy_compress(&cinfo);
            return FUJI_CODEC_ERR_CODEC;
        }

        g_rt.create_compress(&cinfo, FJ_JPEG_LIB_VERSION, sizeof(cinfo));
        g_rt.mem_dest(&cinfo, (unsigned char**)&mem_buf, &mem_size);

        cinfo.image_width      = (fj_JDIMENSION)width;
        cinfo.image_height     = (fj_JDIMENSION)height;
        cinfo.input_components = 3;
        cinfo.in_color_space   = FJ_JCS_EXT_BGR;

        g_rt.set_defaults(&cinfo);
        g_rt.set_quality(&cinfo, q, 1);
        cinfo.dct_method = FJ_JDCT_ISLOW;
        /* 4:2:2 subsampling for photographic fidelity */
        cinfo.comp_info[0].h_samp_factor = 2;
        cinfo.comp_info[0].v_samp_factor = 1;
        cinfo.comp_info[1].h_samp_factor = 1;
        cinfo.comp_info[1].v_samp_factor = 1;
        cinfo.comp_info[2].h_samp_factor = 1;
        cinfo.comp_info[2].v_samp_factor = 1;

        g_rt.start_compress(&cinfo, 1);

        fj_JSAMPROW row_ptrs[16];
        int32_t y = 0;
        while (y < height) {
            int32_t batch = height - y;
            if (batch > 16) batch = 16;
            for (int32_t i = 0; i < batch; ++i) {
                row_ptrs[i] = (fj_JSAMPROW)(uintptr_t)(src_bgr24 + (size_t)(y + i) * (size_t)src_stride_bytes);
            }
            g_rt.write_scanlines(&cinfo, row_ptrs, (fj_JDIMENSION)batch);
            y += batch;
        }

        g_rt.finish_compress(&cinfo);
        g_rt.destroy_compress(&cinfo);

        if (!mem_buf || mem_size == 0 || mem_size > (unsigned long)INT32_MAX) {
            if (mem_buf) free(mem_buf);
            return FUJI_CODEC_ERR_CODEC;
        }
        int32_t actual = (int32_t)mem_size;
        if (out_actual_size) *out_actual_size = actual;
        if (max_bytes > 0 && actual > max_bytes) {
            free(mem_buf);
            return FUJI_CODEC_ERR_OVERFLOW;
        }
        *out_buf = mem_buf;
        return actual;
    }

    if (g_rt.has_libjpeg62) {
        /* Use standard JCS_RGB with 16-row BGR->RGB swap so both libjpeg-turbo v62 and IJG 6b work */
        struct fj62_jpeg_compress_struct cinfo;
        struct fj_err_ctx jerr;
        unsigned char* volatile mem_buf = NULL;
        unsigned long mem_size = 0;
        uint8_t* volatile rgb_batch = NULL;

        memset(&cinfo, 0, sizeof(cinfo));
        cinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;

        if (setjmp(jerr.env) != 0) {
            free(rgb_batch);
            if (mem_buf) free(mem_buf);
            g_rt.destroy_compress(&cinfo);
            return FUJI_CODEC_ERR_CODEC;
        }

        g_rt.create_compress(&cinfo, FJ_JPEG_LIB_VERSION_62, sizeof(cinfo));
        g_rt.mem_dest(&cinfo, (unsigned char**)&mem_buf, &mem_size);

        cinfo.image_width      = (fj_JDIMENSION)width;
        cinfo.image_height     = (fj_JDIMENSION)height;
        cinfo.input_components = 3;
        cinfo.in_color_space   = FJ_JCS_RGB;

        g_rt.set_defaults(&cinfo);
        g_rt.set_quality(&cinfo, q, 1);
        cinfo.dct_method = FJ_JDCT_ISLOW;
        cinfo.comp_info[0].h_samp_factor = 2;
        cinfo.comp_info[0].v_samp_factor = 1;
        cinfo.comp_info[1].h_samp_factor = 1;
        cinfo.comp_info[1].v_samp_factor = 1;
        cinfo.comp_info[2].h_samp_factor = 1;
        cinfo.comp_info[2].v_samp_factor = 1;

        g_rt.start_compress(&cinfo, 1);

        size_t rgb_row_bytes = (size_t)width * 3u;
        rgb_batch = (uint8_t*)malloc(rgb_row_bytes * 16u);
        if (!rgb_batch) {
            g_rt.destroy_compress(&cinfo);
            if (mem_buf) free(mem_buf);
            return FUJI_CODEC_ERR_CODEC;
        }

        fj_JSAMPROW row_ptrs[16];
        int32_t y = 0;
        while (y < height) {
            int32_t batch = height - y;
            if (batch > 16) batch = 16;
            for (int32_t i = 0; i < batch; ++i) {
                const uint8_t* src_row = src_bgr24 + (size_t)(y + i) * (size_t)src_stride_bytes;
                uint8_t* dst_row = rgb_batch + (size_t)i * rgb_row_bytes;
                for (int32_t x = 0; x < width; ++x) {
                    dst_row[x * 3 + 0] = src_row[x * 3 + 2];
                    dst_row[x * 3 + 1] = src_row[x * 3 + 1];
                    dst_row[x * 3 + 2] = src_row[x * 3 + 0];
                }
                row_ptrs[i] = dst_row;
            }
            g_rt.write_scanlines(&cinfo, row_ptrs, (fj_JDIMENSION)batch);
            y += batch;
        }

        g_rt.finish_compress(&cinfo);
        g_rt.destroy_compress(&cinfo);
        free(rgb_batch);

        if (!mem_buf || mem_size == 0 || mem_size > (unsigned long)INT32_MAX) {
            if (mem_buf) free(mem_buf);
            return FUJI_CODEC_ERR_CODEC;
        }
        int32_t actual = (int32_t)mem_size;
        if (out_actual_size) *out_actual_size = actual;
        if (max_bytes > 0 && actual > max_bytes) {
            free(mem_buf);
            return FUJI_CODEC_ERR_OVERFLOW;
        }
        *out_buf = mem_buf;
        return actual;
    }

    if (g_rt.has_turbojpeg) {
        tjhandle tj = g_rt.tj_init_compress();
        if (!tj) return FUJI_CODEC_ERR_CODEC;
        unsigned char* tj_buf = NULL;
        unsigned long tj_size = 0;
        int rc = g_rt.tj_compress2(
            tj, src_bgr24, width, src_stride_bytes, height, FJ_TJPF_BGR,
            &tj_buf, &tj_size, FJ_TJSAMP_422, q, 0
        );
        g_rt.tj_destroy(tj);
        if (rc != 0 || !tj_buf || tj_size == 0 || tj_size > (unsigned long)INT32_MAX) {
            if (tj_buf) g_rt.tj_free(tj_buf);
            return FUJI_CODEC_ERR_CODEC;
        }
        int32_t actual = (int32_t)tj_size;
        if (out_actual_size) *out_actual_size = actual;
        if (max_bytes > 0 && actual > max_bytes) {
            g_rt.tj_free(tj_buf);
            return FUJI_CODEC_ERR_OVERFLOW;
        }
        uint8_t* out = (uint8_t*)malloc((size_t)actual);
        if (!out) {
            g_rt.tj_free(tj_buf);
            return FUJI_CODEC_ERR_CODEC;
        }
        memcpy(out, tj_buf, (size_t)actual);
        g_rt.tj_free(tj_buf);
        *out_buf = out;
        return actual;
    }

    return fj_builtin_jpeg_encode_bgr24_alloc(
        src_bgr24, width, height, src_stride_bytes, q, max_bytes, out_buf, out_actual_size
    );
}

static void fj_scale_bgr24_strip_row(
    const uint8_t* src_bgr,
    int32_t src_stride,
    int32_t src_h,
    int32_t crop_y,
    int32_t crop_h,
    int32_t dst_w,
    int32_t dst_h,
    int32_t dy,
    const int32_t* x_map,
    uint8_t* dst_row,
    int32_t swap_bgr_to_rgb,
    int32_t* prev_sy,
    const uint8_t* prev_dst_row
) {
    int32_t sy = crop_y + (int32_t)(((int64_t)dy * (int64_t)crop_h) / dst_h);
    sy = clamp_i32(sy, 0, src_h - 1);
    if (prev_dst_row && sy == *prev_sy) {
        memcpy(dst_row, prev_dst_row, (size_t)dst_w * 3u);
        return;
    }
    *prev_sy = sy;
    const uint8_t* srow = src_bgr + (size_t)sy * (size_t)src_stride;
    if (swap_bgr_to_rgb) {
        for (int32_t dx = 0; dx < dst_w; ++dx) {
            const uint8_t* sp = srow + x_map[dx];
            dst_row[dx * 3 + 0] = sp[2];
            dst_row[dx * 3 + 1] = sp[1];
            dst_row[dx * 3 + 2] = sp[0];
        }
    } else {
        for (int32_t dx = 0; dx < dst_w; ++dx) {
            const uint8_t* sp = srow + x_map[dx];
            dst_row[dx * 3 + 0] = sp[0];
            dst_row[dx * 3 + 1] = sp[1];
            dst_row[dx * 3 + 2] = sp[2];
        }
    }
}

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
) {
    if (!src_bgr || src_w <= 0 || src_h <= 0 ||
        crop_x < 0 || crop_y < 0 || crop_w <= 0 || crop_h <= 0 ||
        crop_x + crop_w > src_w || crop_y + crop_h > src_h ||
        dst_w <= 0 || dst_h <= 0 || dst_cap < 0) {
        return FUJI_CODEC_ERR_INVALID_ARGS;
    }
    if (out_len) *out_len = 0;

    int32_t q = clamp_i32(quality, 1, 100);
    ensure_runtime_init();

    /* Use the multi-threaded SSE2 built-in pure-C encoder whenever external libjpeg is absent
     * OR for large scaled frames (>= 4 MP, e.g. 102MP capture) where multi-threaded DRI + exact
     * row-constant DCT beats single-threaded libjpeg by ~4-8x on both Linux and Windows! */
    if ((!g_rt.has_libjpeg8 && !g_rt.has_libjpeg62) ||
        ((int64_t)dst_w * (int64_t)dst_h >= 4000000LL)) {
        return fj_builtin_jpeg_encode_core(
            FJ_BUILTIN_SRC_SCALED_BGR24,
            src_bgr, src_w, src_h, src_w * 3,
            crop_x, crop_y, crop_w, crop_h,
            dst_w, dst_h, q, 0,
            dst_jpeg, dst_cap, NULL, out_len
        );
    }

    int32_t* volatile x_map = (int32_t*)malloc((size_t)dst_w * sizeof(int32_t));
    uint8_t* volatile strip_buf = (uint8_t*)malloc((size_t)dst_w * 16u * 3u);
    if (!x_map || !strip_buf) {
        free(x_map);
        free(strip_buf);
        return FUJI_CODEC_ERR_CODEC;
    }
    for (int32_t dx = 0; dx < dst_w; ++dx) {
        int32_t sx = crop_x + (int32_t)(((int64_t)dx * (int64_t)crop_w) / dst_w);
        sx = clamp_i32(sx, 0, src_w - 1);
        x_map[dx] = sx * 3;
    }

    if (g_rt.has_libjpeg8) {
        struct fj_jpeg_compress_struct cinfo;
        struct fj_err_ctx jerr;
        unsigned char* volatile mem_buf = NULL;
        unsigned long mem_size = 0;

        memset(&cinfo, 0, sizeof(cinfo));
        cinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;

        if (setjmp(jerr.env) != 0) {
            if (mem_buf) free(mem_buf);
            free(x_map);
            free(strip_buf);
            g_rt.destroy_compress(&cinfo);
            return FUJI_CODEC_ERR_CODEC;
        }

        g_rt.create_compress(&cinfo, FJ_JPEG_LIB_VERSION, sizeof(cinfo));
        g_rt.mem_dest(&cinfo, (unsigned char**)&mem_buf, &mem_size);

        cinfo.image_width      = (fj_JDIMENSION)dst_w;
        cinfo.image_height     = (fj_JDIMENSION)dst_h;
        cinfo.input_components = 3;
        cinfo.in_color_space   = FJ_JCS_EXT_BGR;

        g_rt.set_defaults(&cinfo);
        g_rt.set_quality(&cinfo, q, 1);
        cinfo.dct_method = FJ_JDCT_ISLOW;
        cinfo.comp_info[0].h_samp_factor = 2;
        cinfo.comp_info[0].v_samp_factor = 1;
        cinfo.comp_info[1].h_samp_factor = 1;
        cinfo.comp_info[1].v_samp_factor = 1;
        cinfo.comp_info[2].h_samp_factor = 1;
        cinfo.comp_info[2].v_samp_factor = 1;

        g_rt.start_compress(&cinfo, 1);

        fj_JSAMPROW row_ptrs[16];
        size_t row_bytes = (size_t)dst_w * 3u;
        int32_t prev_sy = -1;
        const uint8_t* prev_row = NULL;
        int32_t y = 0;
        while (y < dst_h) {
            int32_t batch = dst_h - y;
            if (batch > 16) batch = 16;
            for (int32_t i = 0; i < batch; ++i) {
                uint8_t* dst_row = strip_buf + (size_t)i * row_bytes;
                fj_scale_bgr24_strip_row(
                    src_bgr, src_w * 3, src_h, crop_y, crop_h, dst_w, dst_h, y + i,
                    x_map, dst_row, 0, &prev_sy, prev_row
                );
                prev_row = dst_row;
                row_ptrs[i] = dst_row;
            }
            g_rt.write_scanlines(&cinfo, row_ptrs, (fj_JDIMENSION)batch);
            y += batch;
        }

        g_rt.finish_compress(&cinfo);
        g_rt.destroy_compress(&cinfo);
        free(x_map);
        free(strip_buf);

        if (!mem_buf || mem_size == 0 || mem_size > (unsigned long)INT32_MAX) {
            if (mem_buf) free(mem_buf);
            return FUJI_CODEC_ERR_CODEC;
        }
        int32_t actual = (int32_t)mem_size;
        if (out_len) *out_len = actual;
        if (!dst_jpeg || actual > dst_cap) {
            free(mem_buf);
            return FUJI_CODEC_ERR_OVERFLOW;
        }
        memcpy(dst_jpeg, mem_buf, (size_t)actual);
        free(mem_buf);
        return actual;
    }

    /* has_libjpeg62 */
    {
        struct fj62_jpeg_compress_struct cinfo;
        struct fj_err_ctx jerr;
        unsigned char* volatile mem_buf = NULL;
        unsigned long mem_size = 0;

        memset(&cinfo, 0, sizeof(cinfo));
        cinfo.err = g_rt.std_error(&jerr.pub);
        jerr.pub.error_exit = fj_jpeg_error_exit;
        jerr.pub.emit_message = fj_jpeg_emit_message;

        if (setjmp(jerr.env) != 0) {
            if (mem_buf) free(mem_buf);
            free(x_map);
            free(strip_buf);
            g_rt.destroy_compress(&cinfo);
            return FUJI_CODEC_ERR_CODEC;
        }

        g_rt.create_compress(&cinfo, FJ_JPEG_LIB_VERSION_62, sizeof(cinfo));
        g_rt.mem_dest(&cinfo, (unsigned char**)&mem_buf, &mem_size);

        cinfo.image_width      = (fj_JDIMENSION)dst_w;
        cinfo.image_height     = (fj_JDIMENSION)dst_h;
        cinfo.input_components = 3;
        cinfo.in_color_space   = FJ_JCS_RGB;

        g_rt.set_defaults(&cinfo);
        g_rt.set_quality(&cinfo, q, 1);
        cinfo.dct_method = FJ_JDCT_ISLOW;
        cinfo.comp_info[0].h_samp_factor = 2;
        cinfo.comp_info[0].v_samp_factor = 1;
        cinfo.comp_info[1].h_samp_factor = 1;
        cinfo.comp_info[1].v_samp_factor = 1;
        cinfo.comp_info[2].h_samp_factor = 1;
        cinfo.comp_info[2].v_samp_factor = 1;

        g_rt.start_compress(&cinfo, 1);

        fj_JSAMPROW row_ptrs[16];
        size_t row_bytes = (size_t)dst_w * 3u;
        int32_t prev_sy = -1;
        const uint8_t* prev_row = NULL;
        int32_t y = 0;
        while (y < dst_h) {
            int32_t batch = dst_h - y;
            if (batch > 16) batch = 16;
            for (int32_t i = 0; i < batch; ++i) {
                uint8_t* dst_row = strip_buf + (size_t)i * row_bytes;
                fj_scale_bgr24_strip_row(
                    src_bgr, src_w * 3, src_h, crop_y, crop_h, dst_w, dst_h, y + i,
                    x_map, dst_row, 1, &prev_sy, prev_row
                );
                prev_row = dst_row;
                row_ptrs[i] = dst_row;
            }
            g_rt.write_scanlines(&cinfo, row_ptrs, (fj_JDIMENSION)batch);
            y += batch;
        }

        g_rt.finish_compress(&cinfo);
        g_rt.destroy_compress(&cinfo);
        free(x_map);
        free(strip_buf);

        if (!mem_buf || mem_size == 0 || mem_size > (unsigned long)INT32_MAX) {
            if (mem_buf) free(mem_buf);
            return FUJI_CODEC_ERR_CODEC;
        }
        int32_t actual = (int32_t)mem_size;
        if (out_len) *out_len = actual;
        if (!dst_jpeg || actual > dst_cap) {
            free(mem_buf);
            return FUJI_CODEC_ERR_OVERFLOW;
        }
        memcpy(dst_jpeg, mem_buf, (size_t)actual);
        free(mem_buf);
        return actual;
    }
}
