/*
 * fuji_isp_simd.c - Multi-threaded AVX-512 / AVX2+FMA / SSE2 / Scalar CPU ISP
 * Demosaic (+ WB Gain + 3x3 CCM + 16x16x16 3D FilmSim LUT + Tone LUT +
 * UYVY422/YYUV422/NV16/NV12 + 5-tap HPF Focus Peaking + 256-bin YRGB Histogram
 * + Single-Pass AF Contrast) and Heavy Spatial Optical Filter Kernels
 * (Black Mist, UV Filter, Star Cross-Screen).
 */

#if !defined(_WIN32)
#define _POSIX_C_SOURCE 200809L
#include <pthread.h>
#include <unistd.h>
#else
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif

#include "fuji_isp_simd.h"

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#if defined(__x86_64__) || defined(_M_X64)
#define FUJI_X86_64 1
#include <immintrin.h>
#if defined(__GNUC__) || defined(__clang__)
#include <cpuid.h>
#endif
#if (defined(__GNUC__) && !defined(__clang__) && (__GNUC__ >= 7)) || \
    (defined(__clang__) && (__clang_major__ >= 7))
#define FUJI_HAS_AVX512_TARGET 1
#define FUJI_AVX512_TARGET __attribute__((target("avx512f,avx512bw,avx512dq,avx512vl,fma")))
#else
#define FUJI_HAS_AVX512_TARGET 0
#endif
#define FUJI_AVX2_TARGET __attribute__((target("avx2,fma")))
#else
#define FUJI_X86_64 0
#define FUJI_HAS_AVX512_TARGET 0
#endif

#define FUJI_PI 3.14159265358979323846
#define FUJI_ISP_MAX_THREADS 8

/* ========================================================================= */
/* Runtime CPUID / OSXSAVE SIMD Level Detection                              */
/* ========================================================================= */

static int ascii_eq_ci(const char* a, const char* b) {
    if (a == NULL || b == NULL) return 0;
    while (*a && *b) {
        int ca = (unsigned char)*a++;
        int cb = (unsigned char)*b++;
        if (ca >= 'A' && ca <= 'Z') ca += 'a' - 'A';
        if (cb >= 'A' && cb <= 'Z') cb += 'a' - 'A';
        if (ca != cb) return 0;
    }
    return *a == '\0' && *b == '\0';
}

static int parse_simd_cap_env(void) {
    const char* env = getenv("FUJI_ISP_SIMD_MAX");
    if (env == NULL || env[0] == '\0') return FUJI_ISP_SIMD_AVX512;
    if (ascii_eq_ci(env, "scalar") || ascii_eq_ci(env, "0")) return FUJI_ISP_SIMD_SCALAR;
    if (ascii_eq_ci(env, "sse2")   || ascii_eq_ci(env, "1")) return FUJI_ISP_SIMD_SSE2;
    if (ascii_eq_ci(env, "avx2")   || ascii_eq_ci(env, "2")) return FUJI_ISP_SIMD_AVX2;
    if (ascii_eq_ci(env, "avx512") || ascii_eq_ci(env, "3")) return FUJI_ISP_SIMD_AVX512;
    return FUJI_ISP_SIMD_AVX512;
}

#if FUJI_X86_64
static uint64_t read_xcr0(void) {
    uint32_t eax = 0u, edx = 0u;
    __asm__ __volatile__("xgetbv" : "=a"(eax), "=d"(edx) : "c"(0u));
    return ((uint64_t)edx << 32) | (uint64_t)eax;
}

static int detect_hw_simd_level(void) {
    unsigned int eax = 0u, ebx = 0u, ecx = 0u, edx = 0u;
    if (!__get_cpuid(0u, &eax, &ebx, &ecx, &edx)) {
        return FUJI_ISP_SIMD_SCALAR;
    }
    unsigned int max_leaf = eax;
    if (max_leaf < 1u) return FUJI_ISP_SIMD_SCALAR;

    __get_cpuid(1u, &eax, &ebx, &ecx, &edx);
    int has_sse2    = (edx & (1u << 26)) != 0u;
    int has_osxsave = (ecx & (1u << 27)) != 0u;
    int has_avx     = (ecx & (1u << 28)) != 0u;
    int has_fma     = (ecx & (1u << 12)) != 0u;
    if (!has_sse2) return FUJI_ISP_SIMD_SCALAR;

    int level = FUJI_ISP_SIMD_SSE2;
    if (has_osxsave && has_avx && max_leaf >= 7u) {
        uint64_t xcr0 = read_xcr0();
        if ((xcr0 & 0x6ull) == 0x6ull) {
            __get_cpuid_count(7u, 0u, &eax, &ebx, &ecx, &edx);
            int has_avx2 = (ebx & (1u << 5)) != 0u;
            if (has_avx2 && has_fma) {
                level = FUJI_ISP_SIMD_AVX2;
#if FUJI_HAS_AVX512_TARGET
                if ((xcr0 & 0xE6ull) == 0xE6ull) {
                    int has_512f  = (ebx & (1u << 16)) != 0u;
                    int has_512dq = (ebx & (1u << 17)) != 0u;
                    int has_512bw = (ebx & (1u << 30)) != 0u;
                    int has_512vl = (ebx & (1u << 31)) != 0u;
                    if (has_512f && has_512dq && has_512bw && has_512vl) {
                        level = FUJI_ISP_SIMD_AVX512;
                    }
                }
#endif
            }
        }
    }
    return level;
}
#else
static int detect_hw_simd_level(void) {
    return FUJI_ISP_SIMD_SCALAR;
}
#endif

static int g_hw_simd_level = -1;

static int get_simd_level(void) {
    if (g_hw_simd_level < 0) {
        g_hw_simd_level = detect_hw_simd_level();
    }
    int cap = parse_simd_cap_env();
    return g_hw_simd_level < cap ? g_hw_simd_level : cap;
}

static int detect_default_threads(void) {
    const char* env = getenv("FUJI_ISP_SIMD_THREADS");
    if (env != NULL && env[0] != '\0') {
        int v = atoi(env);
        if (v >= 1) {
            return v > FUJI_ISP_MAX_THREADS ? FUJI_ISP_MAX_THREADS : v;
        }
    }
    int cores = 4;
#if defined(_WIN32)
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    if (si.dwNumberOfProcessors >= 1) {
        cores = (int)si.dwNumberOfProcessors;
    }
#else
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    if (n >= 1) cores = (int)n;
#endif
    if (cores < 1) cores = 1;
    if (cores > FUJI_ISP_MAX_THREADS) cores = FUJI_ISP_MAX_THREADS;
    return cores;
}

FUJI_ISP_SIMD_EXPORT int32_t fuji_isp_simd_abi_version(void) {
    return FUJI_ISP_SIMD_ABI_VERSION;
}

FUJI_ISP_SIMD_EXPORT int32_t fuji_isp_simd_level(void) {
    return get_simd_level();
}

FUJI_ISP_SIMD_EXPORT const char* fuji_isp_simd_backend_name(void) {
    switch (get_simd_level()) {
        case FUJI_ISP_SIMD_AVX512: return "avx512-mt";
        case FUJI_ISP_SIMD_AVX2:   return "avx2-mt";
        case FUJI_ISP_SIMD_SSE2:   return "sse2-mt";
        default:                   return "scalar-mt";
    }
}

FUJI_ISP_SIMD_EXPORT int32_t fuji_isp_simd_default_threads(void) {
    return detect_default_threads();
}

/* ========================================================================= */
/* Lightweight Persistent Worker Thread Pool (POSIX pthread / Win32 native)  */
/* Caller executes task 0 inline; up to FUJI_ISP_MAX_THREADS-1 workers run   */
/* tasks 1..num_tasks-1 concurrently and join before returning to caller.    */
/* ========================================================================= */

typedef void (*fuji_task_fn)(int32_t task_idx, int32_t num_tasks, void* ctx);

typedef struct {
#if defined(_WIN32)
    CRITICAL_SECTION submit_cs;
    CRITICAL_SECTION cs;
    CONDITION_VARIABLE start_cv;
    CONDITION_VARIABLE done_cv;
    HANDLE workers[FUJI_ISP_MAX_THREADS - 1];
#else
    pthread_mutex_t submit_mu;
    pthread_mutex_t mu;
    pthread_cond_t start_cv;
    pthread_cond_t done_cv;
    pthread_t workers[FUJI_ISP_MAX_THREADS - 1];
#endif
    int initialized;
    int shutdown;
    int num_workers;
    uint64_t generation;
    fuji_task_fn fn;
    void* ctx;
    int32_t active_tasks;
    int32_t remaining;
} fuji_thread_pool;

static fuji_thread_pool g_pool;

#if defined(_WIN32)
static INIT_ONCE g_pool_once = INIT_ONCE_STATIC_INIT;

static DWORD WINAPI pool_worker_main(LPVOID arg) {
    int32_t worker_slot = (int32_t)(intptr_t)arg; /* 1..num_workers */
    uint64_t seen_gen = 0;
    for (;;) {
        EnterCriticalSection(&g_pool.cs);
        while (!g_pool.shutdown && g_pool.generation == seen_gen) {
            SleepConditionVariableCS(&g_pool.start_cv, &g_pool.cs, INFINITE);
        }
        if (g_pool.shutdown) {
            LeaveCriticalSection(&g_pool.cs);
            return 0;
        }
        seen_gen = g_pool.generation;
        fuji_task_fn fn = g_pool.fn;
        void* ctx = g_pool.ctx;
        int32_t ntasks = g_pool.active_tasks;
        LeaveCriticalSection(&g_pool.cs);

        if (worker_slot < ntasks && fn != NULL) {
            fn(worker_slot, ntasks, ctx);
        }

        EnterCriticalSection(&g_pool.cs);
        g_pool.remaining--;
        if (g_pool.remaining == 0) {
            WakeConditionVariable(&g_pool.done_cv);
        }
        LeaveCriticalSection(&g_pool.cs);
    }
}

static BOOL CALLBACK pool_init_once_cb(PINIT_ONCE once, PVOID param, PVOID* context) {
    (void)once; (void)param; (void)context;
    InitializeCriticalSection(&g_pool.submit_cs);
    InitializeCriticalSection(&g_pool.cs);
    InitializeConditionVariable(&g_pool.start_cv);
    InitializeConditionVariable(&g_pool.done_cv);
    g_pool.shutdown = 0;
    g_pool.generation = 0;
    g_pool.num_workers = FUJI_ISP_MAX_THREADS - 1;
    for (int i = 0; i < g_pool.num_workers; ++i) {
        g_pool.workers[i] = CreateThread(NULL, 0, pool_worker_main, (LPVOID)(intptr_t)(i + 1), 0, NULL);
        if (g_pool.workers[i] == NULL) {
            g_pool.num_workers = i;
            break;
        }
    }
    g_pool.initialized = 1;
    return TRUE;
}

static void pool_ensure_init(void) {
    InitOnceExecuteOnce(&g_pool_once, pool_init_once_cb, NULL, NULL);
}

static void pool_parallel_run(int32_t num_tasks, fuji_task_fn fn, void* ctx) {
    if (num_tasks <= 1) {
        fn(0, 1, ctx);
        return;
    }
    pool_ensure_init();
    if (g_pool.num_workers <= 0) {
        for (int32_t i = 0; i < num_tasks; ++i) fn(i, num_tasks, ctx);
        return;
    }
    if (!TryEnterCriticalSection(&g_pool.submit_cs)) {
        /* Concurrent JVM caller: run inline without blocking or deadlocking. */
        for (int32_t i = 0; i < num_tasks; ++i) fn(i, num_tasks, ctx);
        return;
    }
    int32_t clamped = num_tasks;
    if (clamped > g_pool.num_workers + 1) clamped = g_pool.num_workers + 1;

    EnterCriticalSection(&g_pool.cs);
    g_pool.fn = fn;
    g_pool.ctx = ctx;
    g_pool.active_tasks = clamped;
    g_pool.remaining = g_pool.num_workers;
    g_pool.generation++;
    WakeAllConditionVariable(&g_pool.start_cv);
    LeaveCriticalSection(&g_pool.cs);

    /* Calling thread executes task 0 concurrently */
    fn(0, clamped, ctx);

    EnterCriticalSection(&g_pool.cs);
    while (g_pool.remaining > 0) {
        SleepConditionVariableCS(&g_pool.done_cv, &g_pool.cs, INFINITE);
    }
    LeaveCriticalSection(&g_pool.cs);
    LeaveCriticalSection(&g_pool.submit_cs);
}
#else
static pthread_once_t g_pool_once = PTHREAD_ONCE_INIT;

static void* pool_worker_main(void* arg) {
    int32_t worker_slot = (int32_t)(intptr_t)arg; /* 1..num_workers */
    uint64_t seen_gen = 0;
    for (;;) {
        pthread_mutex_lock(&g_pool.mu);
        while (!g_pool.shutdown && g_pool.generation == seen_gen) {
            pthread_cond_wait(&g_pool.start_cv, &g_pool.mu);
        }
        if (g_pool.shutdown) {
            pthread_mutex_unlock(&g_pool.mu);
            return NULL;
        }
        seen_gen = g_pool.generation;
        fuji_task_fn fn = g_pool.fn;
        void* ctx = g_pool.ctx;
        int32_t ntasks = g_pool.active_tasks;
        pthread_mutex_unlock(&g_pool.mu);

        if (worker_slot < ntasks && fn != NULL) {
            fn(worker_slot, ntasks, ctx);
        }

        pthread_mutex_lock(&g_pool.mu);
        g_pool.remaining--;
        if (g_pool.remaining == 0) {
            pthread_cond_signal(&g_pool.done_cv);
        }
        pthread_mutex_unlock(&g_pool.mu);
    }
}

static void pool_init_once_cb(void) {
    pthread_mutex_init(&g_pool.submit_mu, NULL);
    pthread_mutex_init(&g_pool.mu, NULL);
    pthread_cond_init(&g_pool.start_cv, NULL);
    pthread_cond_init(&g_pool.done_cv, NULL);
    g_pool.shutdown = 0;
    g_pool.generation = 0;
    g_pool.num_workers = FUJI_ISP_MAX_THREADS - 1;
    for (int i = 0; i < g_pool.num_workers; ++i) {
        if (pthread_create(&g_pool.workers[i], NULL, pool_worker_main, (void*)(intptr_t)(i + 1)) != 0) {
            g_pool.num_workers = i;
            break;
        }
    }
    g_pool.initialized = 1;
}

__attribute__((destructor))
static void pool_destroy_on_unload(void) {
    if (!g_pool.initialized) return;
    pthread_mutex_lock(&g_pool.mu);
    g_pool.shutdown = 1;
    pthread_cond_broadcast(&g_pool.start_cv);
    pthread_mutex_unlock(&g_pool.mu);
    for (int i = 0; i < g_pool.num_workers; ++i) {
        pthread_join(g_pool.workers[i], NULL);
    }
    pthread_cond_destroy(&g_pool.done_cv);
    pthread_cond_destroy(&g_pool.start_cv);
    pthread_mutex_destroy(&g_pool.mu);
    pthread_mutex_destroy(&g_pool.submit_mu);
    g_pool.initialized = 0;
}

static void pool_ensure_init(void) {
    pthread_once(&g_pool_once, pool_init_once_cb);
}

static void pool_parallel_run(int32_t num_tasks, fuji_task_fn fn, void* ctx) {
    if (num_tasks <= 1) {
        fn(0, 1, ctx);
        return;
    }
    pool_ensure_init();
    if (g_pool.num_workers <= 0) {
        for (int32_t i = 0; i < num_tasks; ++i) fn(i, num_tasks, ctx);
        return;
    }
    if (pthread_mutex_trylock(&g_pool.submit_mu) != 0) {
        for (int32_t i = 0; i < num_tasks; ++i) fn(i, num_tasks, ctx);
        return;
    }
    int32_t clamped = num_tasks;
    if (clamped > g_pool.num_workers + 1) clamped = g_pool.num_workers + 1;

    pthread_mutex_lock(&g_pool.mu);
    g_pool.fn = fn;
    g_pool.ctx = ctx;
    g_pool.active_tasks = clamped;
    g_pool.remaining = g_pool.num_workers;
    g_pool.generation++;
    pthread_cond_broadcast(&g_pool.start_cv);
    pthread_mutex_unlock(&g_pool.mu);

    /* Calling thread executes task 0 concurrently */
    fn(0, clamped, ctx);

    pthread_mutex_lock(&g_pool.mu);
    while (g_pool.remaining > 0) {
        pthread_cond_wait(&g_pool.done_cv, &g_pool.mu);
    }
    pthread_mutex_unlock(&g_pool.mu);
    pthread_mutex_unlock(&g_pool.submit_mu);
}
#endif

static int32_t resolve_threads(int32_t req_threads, int32_t work_items, int32_t min_items_per_thread) {
    int32_t t = (req_threads <= 0) ? detect_default_threads() : req_threads;
    if (t > FUJI_ISP_MAX_THREADS) t = FUJI_ISP_MAX_THREADS;
    if (t < 1) t = 1;
    if (min_items_per_thread > 0 && work_items < min_items_per_thread * 2) return 1;
    if (min_items_per_thread > 0) {
        int32_t max_t = work_items / min_items_per_thread;
        if (max_t < 1) max_t = 1;
        if (t > max_t) t = max_t;
    }
    return t;
}

/* ========================================================================= */
/* Common Scalar Helpers                                                     */
/* ========================================================================= */

static inline int32_t clamp_i32(int32_t v, int32_t lo, int32_t hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static inline uint8_t clamp_u8_i32(int32_t v) {
    return (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v));
}

static inline void store_u32_le(uint8_t* p, uint32_t v) {
    memcpy(p, &v, sizeof(v));
}

static void fill_u32_pattern(uint8_t* dst, int32_t byte_len, uint32_t pattern) {
    int32_t n = byte_len >> 2;
    for (int32_t i = 0; i < n; ++i) {
        store_u32_le(dst + (i << 2), pattern);
    }
}

static int32_t g_sample8_to_12[256];
static volatile int g_sample8_inited = 0;

static void init_sample8_to_12(void) {
    if (g_sample8_inited) return;
    for (int i = 0; i < 256; ++i) {
        g_sample8_to_12[i] = (i * 4095) / 255;
    }
    g_sample8_inited = 1;
}

/* ========================================================================= */
/* PART 1: Multi-Threaded SIMD CPU ISP Pipeline                              */
/* ========================================================================= */

#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static void convert_bayer_row_u16_avx512(const uint8_t* src, int32_t src_w, int32_t* dst_inner) {
    int32_t x = 0;
    for (; x + 16 <= src_w; x += 16) {
        __m256i v16 = _mm256_loadu_si256((const __m256i*)(src + (x << 1)));
        __m512i v32 = _mm512_cvtepu16_epi32(v16);
        _mm512_storeu_si512((void*)(dst_inner + x), _mm512_srli_epi32(v32, 4));
    }
    for (; x < src_w; ++x) {
        int32_t off = x << 1;
        dst_inner[x] = (((int32_t)src[off]) | (((int32_t)src[off + 1]) << 8)) >> 4;
    }
}
#endif

FUJI_AVX2_TARGET
static void convert_bayer_row_u16_avx2(const uint8_t* src, int32_t src_w, int32_t* dst_inner) {
    int32_t x = 0;
    for (; x + 8 <= src_w; x += 8) {
        __m128i v16 = _mm_loadu_si128((const __m128i*)(src + (x << 1)));
        __m256i v32 = _mm256_cvtepu16_epi32(v16);
        _mm256_storeu_si256((__m256i*)(dst_inner + x), _mm256_srli_epi32(v32, 4));
    }
    for (; x < src_w; ++x) {
        int32_t off = x << 1;
        dst_inner[x] = (((int32_t)src[off]) | (((int32_t)src[off + 1]) << 8)) >> 4;
    }
}

static void convert_bayer_row_u16_sse2(const uint8_t* src, int32_t src_w, int32_t* dst_inner) {
    const __m128i zero = _mm_setzero_si128();
    int32_t x = 0;
    for (; x + 8 <= src_w; x += 8) {
        __m128i v16 = _mm_loadu_si128((const __m128i*)(src + (x << 1)));
        __m128i lo = _mm_srli_epi32(_mm_unpacklo_epi16(v16, zero), 4);
        __m128i hi = _mm_srli_epi32(_mm_unpackhi_epi16(v16, zero), 4);
        _mm_storeu_si128((__m128i*)(dst_inner + x), lo);
        _mm_storeu_si128((__m128i*)(dst_inner + x + 4), hi);
    }
    for (; x < src_w; ++x) {
        int32_t off = x << 1;
        dst_inner[x] = (((int32_t)src[off]) | (((int32_t)src[off + 1]) << 8)) >> 4;
    }
}
#endif

static void convert_bayer_row_to_i32(
    const uint8_t* raw,
    int32_t sy,
    int32_t src_w,
    int32_t is_16bit,
    int simd_level,
    int32_t* out_padded /* length src_w + 2; pixels live at [1..src_w] */
) {
    int32_t* inner = out_padded + 1;
    if (is_16bit) {
        const uint8_t* src = raw + (size_t)sy * (size_t)src_w * 2u;
#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
        if (simd_level >= FUJI_ISP_SIMD_AVX512) {
            convert_bayer_row_u16_avx512(src, src_w, inner);
        } else
#endif
        if (simd_level >= FUJI_ISP_SIMD_AVX2) {
            convert_bayer_row_u16_avx2(src, src_w, inner);
        } else if (simd_level >= FUJI_ISP_SIMD_SSE2) {
            convert_bayer_row_u16_sse2(src, src_w, inner);
        } else
#endif
        {
            (void)simd_level;
            for (int32_t x = 0; x < src_w; ++x) {
                int32_t off = x << 1;
                inner[x] = (((int32_t)src[off]) | (((int32_t)src[off + 1]) << 8)) >> 4;
            }
        }
    } else {
        const uint8_t* src = raw + (size_t)sy * (size_t)src_w;
        for (int32_t x = 0; x < src_w; ++x) {
            inner[x] = g_sample8_to_12[src[x]];
        }
    }
    /* Mirror edge columns by 1 to preserve 2x2 RGGB Bayer CFA parity (BUG-V3-7-6). */
    out_padded[0]         = (src_w > 1) ? inner[1] : inner[0];
    out_padded[src_w + 1] = (src_w > 1) ? inner[src_w - 2] : inner[0];
}

/* 16x16x16 3D LUT trilinear interpolation (scalar reference) */
static inline void apply_lut3d_scalar(
    const float* lut3d,
    int32_t r8, int32_t g8, int32_t b8,
    int32_t* out_r, int32_t* out_g, int32_t* out_b
) {
    const float scale = 15.0f / 255.0f;
    float fr = (float)r8 * scale;
    float fg = (float)g8 * scale;
    float fb = (float)b8 * scale;
    int32_t r0 = (int32_t)fr; if (r0 > 14) r0 = 14;
    int32_t g0 = (int32_t)fg; if (g0 > 14) g0 = 14;
    int32_t b0 = (int32_t)fb; if (b0 > 14) b0 = 14;
    float dr = fr - (float)r0;
    float dg = fg - (float)g0;
    float db = fb - (float)b0;

    int32_t base = ((b0 * 16 + g0) * 16 + r0) * 3;
    const float* p000 = lut3d + base;
    const float* p100 = p000 + 3;
    const float* p010 = p000 + 48;
    const float* p110 = p010 + 3;
    const float* p001 = p000 + 768;
    const float* p101 = p001 + 3;
    const float* p011 = p001 + 48;
    const float* p111 = p011 + 3;

    int32_t res[3];
    for (int c = 0; c < 3; ++c) {
        float c00 = p000[c] + (p100[c] - p000[c]) * dr;
        float c10 = p010[c] + (p110[c] - p010[c]) * dr;
        float c01 = p001[c] + (p101[c] - p001[c]) * dr;
        float c11 = p011[c] + (p111[c] - p011[c]) * dr;
        float c0  = c00 + (c10 - c00) * dg;
        float c1  = c01 + (c11 - c01) * dg;
        float val = c0 + (c1 - c0) * db;
        if (val < 0.0f) val = 0.0f;
        if (val > 1.0f) val = 1.0f;
        res[c] = clamp_i32((int32_t)(val * 255.0f + 0.5f), 0, 255);
    }
    *out_r = res[0];
    *out_g = res[1];
    *out_b = res[2];
}

static void isp_process_row_scalar(
    const int32_t* sp, const int32_t* sc, const int32_t* sn, int32_t sy_odd,
    const int32_t* src_x_map, int32_t off_x, int32_t end_x,
    int32_t gain_r, int32_t gain_g, int32_t gain_b, int32_t unity_gain,
    int32_t has_ccm, const int32_t* ccm,
    const int32_t* lut_r, const int32_t* lut_g, const int32_t* lut_b,
    const float* lut3d,
    int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    uint8_t* row_r, uint8_t* row_g, uint8_t* row_b, uint8_t* row_y
) {
    for (int32_t x = off_x; x < end_x; ++x) {
        int32_t sx = src_x_map[x];
        int32_t sx_odd = sx & 1;
        int32_t c  = sc[sx];
        int32_t l  = sc[sx - 1];
        int32_t r  = sc[sx + 1];
        int32_t u  = sp[sx];
        int32_t d  = sn[sx];
        int32_t ul = sp[sx - 1];
        int32_t ur = sp[sx + 1];
        int32_t dl = sn[sx - 1];
        int32_t dr = sn[sx + 1];

        int32_t r_lin, g_lin, b_lin;
        if (!sy_odd && !sx_odd) {
            r_lin = c;
            g_lin = (l + r + u + d + 2) >> 2;
            b_lin = (ul + ur + dl + dr + 2) >> 2;
        } else if (!sy_odd && sx_odd) {
            r_lin = (l + r + 1) >> 1;
            g_lin = c;
            b_lin = (u + d + 1) >> 1;
        } else if (sy_odd && !sx_odd) {
            r_lin = (u + d + 1) >> 1;
            g_lin = c;
            b_lin = (l + r + 1) >> 1;
        } else {
            r_lin = (ul + ur + dl + dr + 2) >> 2;
            g_lin = (l + r + u + d + 2) >> 2;
            b_lin = c;
        }

        if (!unity_gain) {
            r_lin = clamp_i32((int32_t)(((int64_t)r_lin * (int64_t)gain_r) >> 10), 0, 4095);
            g_lin = clamp_i32((int32_t)(((int64_t)g_lin * (int64_t)gain_g) >> 10), 0, 4095);
            b_lin = clamp_i32((int32_t)(((int64_t)b_lin * (int64_t)gain_b) >> 10), 0, 4095);
        }

        int32_t ri, gi, bi;
        if (has_ccm) {
            ri = clamp_i32((ccm[0] * r_lin + ccm[1] * g_lin + ccm[2] * b_lin + 512) >> 10, 0, 4095);
            gi = clamp_i32((ccm[3] * r_lin + ccm[4] * g_lin + ccm[5] * b_lin + 512) >> 10, 0, 4095);
            bi = clamp_i32((ccm[6] * r_lin + ccm[7] * g_lin + ccm[8] * b_lin + 512) >> 10, 0, 4095);
        } else {
            ri = r_lin; gi = g_lin; bi = b_lin;
        }

        int32_t r8 = lut_r[ri];
        int32_t g8 = lut_g[gi];
        int32_t b8 = lut_b[bi];

        if (lut3d != NULL) {
            apply_lut3d_scalar(lut3d, r8, g8, b8, &r8, &g8, &b8);
        }

        int32_t y8;
        if (has_yuv_mat) {
            y8 = clamp_i32((yuv_mat[0] * r8 + yuv_mat[1] * g8 + yuv_mat[2] * b8 + yuv_half) >> yuv_shift, 0, 255);
        } else {
            y8 = clamp_i32((77 * r8 + 150 * g8 + 29 * b8 + 128) >> 8, 0, 255);
        }
        row_r[x] = (uint8_t)r8;
        row_g[x] = (uint8_t)g8;
        row_b[x] = (uint8_t)b8;
        row_y[x] = (uint8_t)y8;
    }
}

#if FUJI_X86_64
FUJI_AVX2_TARGET
static inline void apply_lut3d_8_avx2(
    const float* lut3d,
    __m256i* r8_io, __m256i* g8_io, __m256i* b8_io
) {
    const __m256 v_scale = _mm256_set1_ps(15.0f / 255.0f);
    const __m256 v_zero_f = _mm256_setzero_ps();
    const __m256 v_one_f  = _mm256_set1_ps(1.0f);
    const __m256 v_255_f  = _mm256_set1_ps(255.0f);
    const __m256 v_half_f = _mm256_set1_ps(0.5f);
    const __m256i v_14    = _mm256_set1_epi32(14);
    const __m256i v_zero  = _mm256_setzero_si256();
    const __m256i v_255   = _mm256_set1_epi32(255);

    __m256 fr = _mm256_mul_ps(_mm256_cvtepi32_ps(*r8_io), v_scale);
    __m256 fg = _mm256_mul_ps(_mm256_cvtepi32_ps(*g8_io), v_scale);
    __m256 fb = _mm256_mul_ps(_mm256_cvtepi32_ps(*b8_io), v_scale);

    __m256i r0 = _mm256_min_epi32(_mm256_cvttps_epi32(fr), v_14);
    __m256i g0 = _mm256_min_epi32(_mm256_cvttps_epi32(fg), v_14);
    __m256i b0 = _mm256_min_epi32(_mm256_cvttps_epi32(fb), v_14);

    __m256 dr = _mm256_sub_ps(fr, _mm256_cvtepi32_ps(r0));
    __m256 dg = _mm256_sub_ps(fg, _mm256_cvtepi32_ps(g0));
    __m256 db = _mm256_sub_ps(fb, _mm256_cvtepi32_ps(b0));

    /* idx000 = ((b0 * 16 + g0) * 16 + r0) * 3 */
    __m256i lin = _mm256_add_epi32(_mm256_slli_epi32(b0, 8), _mm256_add_epi32(_mm256_slli_epi32(g0, 4), r0));
    __m256i i000 = _mm256_add_epi32(lin, _mm256_slli_epi32(lin, 1));
    __m256i i100 = _mm256_add_epi32(i000, _mm256_set1_epi32(3));
    __m256i i010 = _mm256_add_epi32(i000, _mm256_set1_epi32(48));
    __m256i i110 = _mm256_add_epi32(i010, _mm256_set1_epi32(3));
    __m256i i001 = _mm256_add_epi32(i000, _mm256_set1_epi32(768));
    __m256i i101 = _mm256_add_epi32(i001, _mm256_set1_epi32(3));
    __m256i i011 = _mm256_add_epi32(i001, _mm256_set1_epi32(48));
    __m256i i111 = _mm256_add_epi32(i011, _mm256_set1_epi32(3));

    __m256i* outs[3] = { r8_io, g8_io, b8_io };
    for (int c = 0; c < 3; ++c) {
        const float* base = lut3d + c;
        __m256 c000 = _mm256_i32gather_ps(base, i000, 4);
        __m256 c100 = _mm256_i32gather_ps(base, i100, 4);
        __m256 c010 = _mm256_i32gather_ps(base, i010, 4);
        __m256 c110 = _mm256_i32gather_ps(base, i110, 4);
        __m256 c001 = _mm256_i32gather_ps(base, i001, 4);
        __m256 c101 = _mm256_i32gather_ps(base, i101, 4);
        __m256 c011 = _mm256_i32gather_ps(base, i011, 4);
        __m256 c111 = _mm256_i32gather_ps(base, i111, 4);

        __m256 c00 = _mm256_fmadd_ps(_mm256_sub_ps(c100, c000), dr, c000);
        __m256 c10 = _mm256_fmadd_ps(_mm256_sub_ps(c110, c010), dr, c010);
        __m256 c01 = _mm256_fmadd_ps(_mm256_sub_ps(c101, c001), dr, c001);
        __m256 c11 = _mm256_fmadd_ps(_mm256_sub_ps(c111, c011), dr, c011);
        __m256 c0  = _mm256_fmadd_ps(_mm256_sub_ps(c10, c00), dg, c00);
        __m256 c1  = _mm256_fmadd_ps(_mm256_sub_ps(c11, c01), dg, c01);
        __m256 val = _mm256_fmadd_ps(_mm256_sub_ps(c1, c0), db, c0);
        val = _mm256_min_ps(v_one_f, _mm256_max_ps(v_zero_f, val));
        __m256i vi = _mm256_cvttps_epi32(_mm256_fmadd_ps(val, v_255_f, v_half_f));
        *outs[c] = _mm256_max_epi32(v_zero, _mm256_min_epi32(v_255, vi));
    }
}

FUJI_AVX2_TARGET
static void isp_process_row_avx2(
    const int32_t* sp, const int32_t* sc, const int32_t* sn, int32_t sy_odd,
    const int32_t* src_x_map, int32_t off_x, int32_t end_x, int32_t same_dims,
    int32_t gain_r, int32_t gain_g, int32_t gain_b, int32_t unity_gain,
    int32_t has_ccm, const int32_t* ccm,
    const int32_t* lut_r, const int32_t* lut_g, const int32_t* lut_b,
    const float* lut3d,
    int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    uint8_t* row_r, uint8_t* row_g, uint8_t* row_b, uint8_t* row_y
) {
    const int32_t fast_gain_ok = unity_gain ||
        (gain_r >= 0 && gain_r <= 524287 && gain_g >= 0 && gain_g <= 524287 && gain_b >= 0 && gain_b <= 524287);
    if (!fast_gain_ok) {
        isp_process_row_scalar(sp, sc, sn, sy_odd, src_x_map, off_x, end_x, gain_r, gain_g, gain_b, unity_gain, has_ccm, ccm, lut_r, lut_g, lut_b, lut3d, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, row_r, row_g, row_b, row_y);
        return;
    }

    const __m256i v_zero  = _mm256_setzero_si256();
    const __m256i v_one   = _mm256_set1_epi32(1);
    const __m256i v_two   = _mm256_set1_epi32(2);
    const __m256i v_4095  = _mm256_set1_epi32(4095);
    const __m256i v_255   = _mm256_set1_epi32(255);
    const __m256i v_512   = _mm256_set1_epi32(512);
    const __m256i v_gr    = _mm256_set1_epi32(gain_r);
    const __m256i v_gg    = _mm256_set1_epi32(gain_g);
    const __m256i v_gb    = _mm256_set1_epi32(gain_b);
    const __m256i v_ym0   = _mm256_set1_epi32(has_yuv_mat ? yuv_mat[0] : 77);
    const __m256i v_ym1   = _mm256_set1_epi32(has_yuv_mat ? yuv_mat[1] : 150);
    const __m256i v_ym2   = _mm256_set1_epi32(has_yuv_mat ? yuv_mat[2] : 29);
    const __m256i v_yhalf = _mm256_set1_epi32(has_yuv_mat ? yuv_half : 128);
    const int32_t y_sh    = has_yuv_mat ? yuv_shift : 8;
    const __m256i pack_shuf = _mm256_setr_epi8(
        0, 4, 8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        0, 4, 8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1
    );
    const __m256i odd_even0 = _mm256_setr_epi32(0, -1, 0, -1, 0, -1, 0, -1);
    const __m256i odd_even1 = _mm256_setr_epi32(-1, 0, -1, 0, -1, 0, -1, 0);

    int32_t x = off_x;
    for (; x + 8 <= end_x; x += 8) {
        __m256i c, l, r, u, d, ul, ur, dl, dr, odd_mask;
        if (same_dims) {
            c  = _mm256_loadu_si256((const __m256i*)(sc + x));
            l  = _mm256_loadu_si256((const __m256i*)(sc + x - 1));
            r  = _mm256_loadu_si256((const __m256i*)(sc + x + 1));
            u  = _mm256_loadu_si256((const __m256i*)(sp + x));
            d  = _mm256_loadu_si256((const __m256i*)(sn + x));
            ul = _mm256_loadu_si256((const __m256i*)(sp + x - 1));
            ur = _mm256_loadu_si256((const __m256i*)(sp + x + 1));
            dl = _mm256_loadu_si256((const __m256i*)(sn + x - 1));
            dr = _mm256_loadu_si256((const __m256i*)(sn + x + 1));
            odd_mask = ((x & 1) == 0) ? odd_even0 : odd_even1;
        } else {
            __m256i sx = _mm256_loadu_si256((const __m256i*)(src_x_map + x));
            odd_mask = _mm256_cmpeq_epi32(_mm256_and_si256(sx, v_one), v_one);
            c  = _mm256_i32gather_epi32(sc,     sx, 4);
            l  = _mm256_i32gather_epi32(sc - 1, sx, 4);
            r  = _mm256_i32gather_epi32(sc + 1, sx, 4);
            u  = _mm256_i32gather_epi32(sp,     sx, 4);
            d  = _mm256_i32gather_epi32(sn,     sx, 4);
            ul = _mm256_i32gather_epi32(sp - 1, sx, 4);
            ur = _mm256_i32gather_epi32(sp + 1, sx, 4);
            dl = _mm256_i32gather_epi32(sn - 1, sx, 4);
            dr = _mm256_i32gather_epi32(sn + 1, sx, 4);
        }

        __m256i h_avg = _mm256_srli_epi32(_mm256_add_epi32(_mm256_add_epi32(l, r), v_one), 1);
        __m256i v_avg = _mm256_srli_epi32(_mm256_add_epi32(_mm256_add_epi32(u, d), v_one), 1);
        __m256i x_avg = _mm256_srli_epi32(_mm256_add_epi32(_mm256_add_epi32(_mm256_add_epi32(l, r), _mm256_add_epi32(u, d)), v_two), 2);
        __m256i q_avg = _mm256_srli_epi32(_mm256_add_epi32(_mm256_add_epi32(_mm256_add_epi32(ul, ur), _mm256_add_epi32(dl, dr)), v_two), 2);

        __m256i r_lin, g_lin, b_lin;
        if (!sy_odd) {
            r_lin = _mm256_blendv_epi8(c,     h_avg, odd_mask);
            g_lin = _mm256_blendv_epi8(x_avg, c,     odd_mask);
            b_lin = _mm256_blendv_epi8(q_avg, v_avg, odd_mask);
        } else {
            r_lin = _mm256_blendv_epi8(v_avg, q_avg, odd_mask);
            g_lin = _mm256_blendv_epi8(c,     x_avg, odd_mask);
            b_lin = _mm256_blendv_epi8(h_avg, c,     odd_mask);
        }

        if (!unity_gain) {
            r_lin = _mm256_min_epi32(v_4095, _mm256_srli_epi32(_mm256_mullo_epi32(r_lin, v_gr), 10));
            g_lin = _mm256_min_epi32(v_4095, _mm256_srli_epi32(_mm256_mullo_epi32(g_lin, v_gg), 10));
            b_lin = _mm256_min_epi32(v_4095, _mm256_srli_epi32(_mm256_mullo_epi32(b_lin, v_gb), 10));
        }

        __m256i ri, gi, bi;
        if (has_ccm) {
            ri = _mm256_srai_epi32(_mm256_add_epi32(_mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(ccm[0]), r_lin), _mm256_mullo_epi32(_mm256_set1_epi32(ccm[1]), g_lin)), _mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(ccm[2]), b_lin), v_512)), 10);
            gi = _mm256_srai_epi32(_mm256_add_epi32(_mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(ccm[3]), r_lin), _mm256_mullo_epi32(_mm256_set1_epi32(ccm[4]), g_lin)), _mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(ccm[5]), b_lin), v_512)), 10);
            bi = _mm256_srai_epi32(_mm256_add_epi32(_mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(ccm[6]), r_lin), _mm256_mullo_epi32(_mm256_set1_epi32(ccm[7]), g_lin)), _mm256_add_epi32(_mm256_mullo_epi32(_mm256_set1_epi32(ccm[8]), b_lin), v_512)), 10);
            ri = _mm256_max_epi32(v_zero, _mm256_min_epi32(v_4095, ri));
            gi = _mm256_max_epi32(v_zero, _mm256_min_epi32(v_4095, gi));
            bi = _mm256_max_epi32(v_zero, _mm256_min_epi32(v_4095, bi));
        } else {
            ri = r_lin; gi = g_lin; bi = b_lin;
        }

        __m256i r8 = _mm256_i32gather_epi32(lut_r, ri, 4);
        __m256i g8 = _mm256_i32gather_epi32(lut_g, gi, 4);
        __m256i b8 = _mm256_i32gather_epi32(lut_b, bi, 4);

        if (lut3d != NULL) {
            apply_lut3d_8_avx2(lut3d, &r8, &g8, &b8);
        }

        __m256i y8 = _mm256_add_epi32(
            _mm256_add_epi32(_mm256_mullo_epi32(v_ym0, r8), _mm256_mullo_epi32(v_ym1, g8)),
            _mm256_add_epi32(_mm256_mullo_epi32(v_ym2, b8), v_yhalf)
        );
        y8 = _mm256_srai_epi32(y8, y_sh);
        y8 = _mm256_max_epi32(v_zero, _mm256_min_epi32(v_255, y8));

        __m256i rp = _mm256_shuffle_epi8(r8, pack_shuf);
        __m256i gp = _mm256_shuffle_epi8(g8, pack_shuf);
        __m256i bp = _mm256_shuffle_epi8(b8, pack_shuf);
        __m256i yp = _mm256_shuffle_epi8(y8, pack_shuf);
        store_u32_le(row_r + x,     (uint32_t)_mm256_extract_epi32(rp, 0));
        store_u32_le(row_r + x + 4, (uint32_t)_mm256_extract_epi32(rp, 4));
        store_u32_le(row_g + x,     (uint32_t)_mm256_extract_epi32(gp, 0));
        store_u32_le(row_g + x + 4, (uint32_t)_mm256_extract_epi32(gp, 4));
        store_u32_le(row_b + x,     (uint32_t)_mm256_extract_epi32(bp, 0));
        store_u32_le(row_b + x + 4, (uint32_t)_mm256_extract_epi32(bp, 4));
        store_u32_le(row_y + x,     (uint32_t)_mm256_extract_epi32(yp, 0));
        store_u32_le(row_y + x + 4, (uint32_t)_mm256_extract_epi32(yp, 4));
    }
    if (x < end_x) {
        isp_process_row_scalar(sp, sc, sn, sy_odd, src_x_map, x, end_x, gain_r, gain_g, gain_b, unity_gain, has_ccm, ccm, lut_r, lut_g, lut_b, lut3d, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, row_r, row_g, row_b, row_y);
    }
}

#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static inline void apply_lut3d_16_avx512(
    const float* lut3d,
    __m512i* r8_io, __m512i* g8_io, __m512i* b8_io
) {
    const __m512 v_scale  = _mm512_set1_ps(15.0f / 255.0f);
    const __m512 v_zero_f = _mm512_setzero_ps();
    const __m512 v_one_f  = _mm512_set1_ps(1.0f);
    const __m512 v_255_f  = _mm512_set1_ps(255.0f);
    const __m512 v_half_f = _mm512_set1_ps(0.5f);
    const __m512i v_14    = _mm512_set1_epi32(14);
    const __m512i v_zero  = _mm512_setzero_si512();
    const __m512i v_255   = _mm512_set1_epi32(255);

    __m512 fr = _mm512_mul_ps(_mm512_cvtepi32_ps(*r8_io), v_scale);
    __m512 fg = _mm512_mul_ps(_mm512_cvtepi32_ps(*g8_io), v_scale);
    __m512 fb = _mm512_mul_ps(_mm512_cvtepi32_ps(*b8_io), v_scale);

    __m512i r0 = _mm512_min_epi32(_mm512_cvttps_epi32(fr), v_14);
    __m512i g0 = _mm512_min_epi32(_mm512_cvttps_epi32(fg), v_14);
    __m512i b0 = _mm512_min_epi32(_mm512_cvttps_epi32(fb), v_14);

    __m512 dr = _mm512_sub_ps(fr, _mm512_cvtepi32_ps(r0));
    __m512 dg = _mm512_sub_ps(fg, _mm512_cvtepi32_ps(g0));
    __m512 db = _mm512_sub_ps(fb, _mm512_cvtepi32_ps(b0));

    __m512i lin = _mm512_add_epi32(_mm512_slli_epi32(b0, 8), _mm512_add_epi32(_mm512_slli_epi32(g0, 4), r0));
    __m512i i000 = _mm512_add_epi32(lin, _mm512_slli_epi32(lin, 1));
    __m512i i100 = _mm512_add_epi32(i000, _mm512_set1_epi32(3));
    __m512i i010 = _mm512_add_epi32(i000, _mm512_set1_epi32(48));
    __m512i i110 = _mm512_add_epi32(i010, _mm512_set1_epi32(3));
    __m512i i001 = _mm512_add_epi32(i000, _mm512_set1_epi32(768));
    __m512i i101 = _mm512_add_epi32(i001, _mm512_set1_epi32(3));
    __m512i i011 = _mm512_add_epi32(i001, _mm512_set1_epi32(48));
    __m512i i111 = _mm512_add_epi32(i011, _mm512_set1_epi32(3));

    __m512i* outs[3] = { r8_io, g8_io, b8_io };
    for (int c = 0; c < 3; ++c) {
        const float* base = lut3d + c;
        __m512 c000 = _mm512_i32gather_ps(i000, base, 4);
        __m512 c100 = _mm512_i32gather_ps(i100, base, 4);
        __m512 c010 = _mm512_i32gather_ps(i010, base, 4);
        __m512 c110 = _mm512_i32gather_ps(i110, base, 4);
        __m512 c001 = _mm512_i32gather_ps(i001, base, 4);
        __m512 c101 = _mm512_i32gather_ps(i101, base, 4);
        __m512 c011 = _mm512_i32gather_ps(i011, base, 4);
        __m512 c111 = _mm512_i32gather_ps(i111, base, 4);

        __m512 c00 = _mm512_fmadd_ps(_mm512_sub_ps(c100, c000), dr, c000);
        __m512 c10 = _mm512_fmadd_ps(_mm512_sub_ps(c110, c010), dr, c010);
        __m512 c01 = _mm512_fmadd_ps(_mm512_sub_ps(c101, c001), dr, c001);
        __m512 c11 = _mm512_fmadd_ps(_mm512_sub_ps(c111, c011), dr, c011);
        __m512 c0  = _mm512_fmadd_ps(_mm512_sub_ps(c10, c00), dg, c00);
        __m512 c1  = _mm512_fmadd_ps(_mm512_sub_ps(c11, c01), dg, c01);
        __m512 val = _mm512_fmadd_ps(_mm512_sub_ps(c1, c0), db, c0);
        val = _mm512_min_ps(v_one_f, _mm512_max_ps(v_zero_f, val));
        __m512i vi = _mm512_cvttps_epi32(_mm512_fmadd_ps(val, v_255_f, v_half_f));
        *outs[c] = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_255, vi));
    }
}

FUJI_AVX512_TARGET
static void isp_process_row_avx512(
    const int32_t* sp, const int32_t* sc, const int32_t* sn, int32_t sy_odd,
    const int32_t* src_x_map, int32_t off_x, int32_t end_x, int32_t same_dims,
    int32_t gain_r, int32_t gain_g, int32_t gain_b, int32_t unity_gain,
    int32_t has_ccm, const int32_t* ccm,
    const int32_t* lut_r, const int32_t* lut_g, const int32_t* lut_b,
    const float* lut3d,
    int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    uint8_t* row_r, uint8_t* row_g, uint8_t* row_b, uint8_t* row_y
) {
    const int32_t fast_gain_ok = unity_gain ||
        (gain_r >= 0 && gain_r <= 524287 && gain_g >= 0 && gain_g <= 524287 && gain_b >= 0 && gain_b <= 524287);
    if (!fast_gain_ok) {
        isp_process_row_scalar(sp, sc, sn, sy_odd, src_x_map, off_x, end_x, gain_r, gain_g, gain_b, unity_gain, has_ccm, ccm, lut_r, lut_g, lut_b, lut3d, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, row_r, row_g, row_b, row_y);
        return;
    }

    const __m512i v_zero  = _mm512_setzero_si512();
    const __m512i v_one   = _mm512_set1_epi32(1);
    const __m512i v_two   = _mm512_set1_epi32(2);
    const __m512i v_4095  = _mm512_set1_epi32(4095);
    const __m512i v_255   = _mm512_set1_epi32(255);
    const __m512i v_512   = _mm512_set1_epi32(512);
    const __m512i v_gr    = _mm512_set1_epi32(gain_r);
    const __m512i v_gg    = _mm512_set1_epi32(gain_g);
    const __m512i v_gb    = _mm512_set1_epi32(gain_b);
    const __m512i v_ym0   = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[0] : 77);
    const __m512i v_ym1   = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[1] : 150);
    const __m512i v_ym2   = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[2] : 29);
    const __m512i v_yhalf = _mm512_set1_epi32(has_yuv_mat ? yuv_half : 128);
    const unsigned int y_sh = (unsigned int)(has_yuv_mat ? yuv_shift : 8);

    int32_t x = off_x;
    for (; x + 16 <= end_x; x += 16) {
        __m512i c, l, r, u, d, ul, ur, dl, dr;
        __mmask16 odd_k;
        if (same_dims) {
            c  = _mm512_loadu_si512((const void*)(sc + x));
            l  = _mm512_loadu_si512((const void*)(sc + x - 1));
            r  = _mm512_loadu_si512((const void*)(sc + x + 1));
            u  = _mm512_loadu_si512((const void*)(sp + x));
            d  = _mm512_loadu_si512((const void*)(sn + x));
            ul = _mm512_loadu_si512((const void*)(sp + x - 1));
            ur = _mm512_loadu_si512((const void*)(sp + x + 1));
            dl = _mm512_loadu_si512((const void*)(sn + x - 1));
            dr = _mm512_loadu_si512((const void*)(sn + x + 1));
            odd_k = ((x & 1) == 0) ? 0xAAAAu : 0x5555u;
        } else {
            __m512i sx = _mm512_loadu_si512((const void*)(src_x_map + x));
            odd_k = _mm512_test_epi32_mask(sx, v_one);
            c  = _mm512_i32gather_epi32(sx, (const void*)sc, 4);
            l  = _mm512_i32gather_epi32(sx, (const void*)(sc - 1), 4);
            r  = _mm512_i32gather_epi32(sx, (const void*)(sc + 1), 4);
            u  = _mm512_i32gather_epi32(sx, (const void*)sp, 4);
            d  = _mm512_i32gather_epi32(sx, (const void*)sn, 4);
            ul = _mm512_i32gather_epi32(sx, (const void*)(sp - 1), 4);
            ur = _mm512_i32gather_epi32(sx, (const void*)(sp + 1), 4);
            dl = _mm512_i32gather_epi32(sx, (const void*)(sn - 1), 4);
            dr = _mm512_i32gather_epi32(sx, (const void*)(sn + 1), 4);
        }

        __m512i h_avg = _mm512_srli_epi32(_mm512_add_epi32(_mm512_add_epi32(l, r), v_one), 1);
        __m512i v_avg = _mm512_srli_epi32(_mm512_add_epi32(_mm512_add_epi32(u, d), v_one), 1);
        __m512i x_avg = _mm512_srli_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_add_epi32(l, r), _mm512_add_epi32(u, d)), v_two), 2);
        __m512i q_avg = _mm512_srli_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_add_epi32(ul, ur), _mm512_add_epi32(dl, dr)), v_two), 2);

        __m512i r_lin, g_lin, b_lin;
        if (!sy_odd) {
            r_lin = _mm512_mask_blend_epi32(odd_k, c,     h_avg);
            g_lin = _mm512_mask_blend_epi32(odd_k, x_avg, c);
            b_lin = _mm512_mask_blend_epi32(odd_k, q_avg, v_avg);
        } else {
            r_lin = _mm512_mask_blend_epi32(odd_k, v_avg, q_avg);
            g_lin = _mm512_mask_blend_epi32(odd_k, c,     x_avg);
            b_lin = _mm512_mask_blend_epi32(odd_k, h_avg, c);
        }

        if (!unity_gain) {
            r_lin = _mm512_min_epi32(v_4095, _mm512_srli_epi32(_mm512_mullo_epi32(r_lin, v_gr), 10));
            g_lin = _mm512_min_epi32(v_4095, _mm512_srli_epi32(_mm512_mullo_epi32(g_lin, v_gg), 10));
            b_lin = _mm512_min_epi32(v_4095, _mm512_srli_epi32(_mm512_mullo_epi32(b_lin, v_gb), 10));
        }

        __m512i ri, gi, bi;
        if (has_ccm) {
            ri = _mm512_srai_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(ccm[0]), r_lin), _mm512_mullo_epi32(_mm512_set1_epi32(ccm[1]), g_lin)), _mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(ccm[2]), b_lin), v_512)), 10);
            gi = _mm512_srai_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(ccm[3]), r_lin), _mm512_mullo_epi32(_mm512_set1_epi32(ccm[4]), g_lin)), _mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(ccm[5]), b_lin), v_512)), 10);
            bi = _mm512_srai_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(ccm[6]), r_lin), _mm512_mullo_epi32(_mm512_set1_epi32(ccm[7]), g_lin)), _mm512_add_epi32(_mm512_mullo_epi32(_mm512_set1_epi32(ccm[8]), b_lin), v_512)), 10);
            ri = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_4095, ri));
            gi = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_4095, gi));
            bi = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_4095, bi));
        } else {
            ri = r_lin; gi = g_lin; bi = b_lin;
        }

        __m512i r8 = _mm512_i32gather_epi32(ri, (const void*)lut_r, 4);
        __m512i g8 = _mm512_i32gather_epi32(gi, (const void*)lut_g, 4);
        __m512i b8 = _mm512_i32gather_epi32(bi, (const void*)lut_b, 4);

        if (lut3d != NULL) {
            apply_lut3d_16_avx512(lut3d, &r8, &g8, &b8);
        }

        __m512i y8 = _mm512_add_epi32(
            _mm512_add_epi32(_mm512_mullo_epi32(v_ym0, r8), _mm512_mullo_epi32(v_ym1, g8)),
            _mm512_add_epi32(_mm512_mullo_epi32(v_ym2, b8), v_yhalf)
        );
        y8 = _mm512_srai_epi32(y8, y_sh);
        y8 = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_255, y8));

        _mm_storeu_si128((__m128i*)(row_r + x), _mm512_cvtepi32_epi8(r8));
        _mm_storeu_si128((__m128i*)(row_g + x), _mm512_cvtepi32_epi8(g8));
        _mm_storeu_si128((__m128i*)(row_b + x), _mm512_cvtepi32_epi8(b8));
        _mm_storeu_si128((__m128i*)(row_y + x), _mm512_cvtepi32_epi8(y8));
    }
    if (x < end_x) {
        isp_process_row_scalar(sp, sc, sn, sy_odd, src_x_map, x, end_x, gain_r, gain_g, gain_b, unity_gain, has_ccm, ccm, lut_r, lut_g, lut_b, lut3d, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, row_r, row_g, row_b, row_y);
    }
}
#endif
#endif

static inline void pack_uv_pair_scalar(
    int32_t avg_r, int32_t avg_g, int32_t avg_b,
    int32_t mono_mode, int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    uint8_t* u_out, uint8_t* v_out
) {
    if (mono_mode) {
        *u_out = 128u;
        *v_out = 128u;
    } else if (has_yuv_mat) {
        *u_out = clamp_u8_i32(((yuv_mat[3] * avg_r + yuv_mat[4] * avg_g + yuv_mat[5] * avg_b + yuv_half) >> yuv_shift) + 128);
        *v_out = clamp_u8_i32(((yuv_mat[6] * avg_r + yuv_mat[7] * avg_g + yuv_mat[8] * avg_b + yuv_half) >> yuv_shift) + 128);
    } else {
        *u_out = clamp_u8_i32(((-43 * avg_r - 85 * avg_g + 128 * avg_b + 128) >> 8) + 128);
        *v_out = clamp_u8_i32(((128 * avg_r - 107 * avg_g - 21 * avg_b + 128) >> 8) + 128);
    }
}

static void pack_interior_yuv_scalar(
    const uint8_t* row_r, const uint8_t* row_g, const uint8_t* row_b, const uint8_t* row_y,
    int32_t even_start, int32_t even_end,
    int32_t mono_mode, int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    int32_t is_semi_planar, int32_t is_yyuv422, uint8_t* dst_row, uint8_t* uv_row
) {
    for (int32_t x = even_start; x < even_end; x += 2) {
        int32_t ar = ((int32_t)row_r[x] + (int32_t)row_r[x + 1]) >> 1;
        int32_t ag = ((int32_t)row_g[x] + (int32_t)row_g[x + 1]) >> 1;
        int32_t ab = ((int32_t)row_b[x] + (int32_t)row_b[x + 1]) >> 1;
        uint8_t u8, v8;
        pack_uv_pair_scalar(ar, ag, ab, mono_mode, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, &u8, &v8);
        if (is_semi_planar) {
            uv_row[x]     = u8;
            uv_row[x + 1] = v8;
        } else if (is_yyuv422) {
            uint8_t* p = dst_row + (x << 1);
            p[0] = row_y[x];
            p[1] = row_y[x + 1];
            p[2] = u8;
            p[3] = v8;
        } else {
            uint8_t* p = dst_row + (x << 1);
            p[0] = u8;
            p[1] = row_y[x];
            p[2] = v8;
            p[3] = row_y[x + 1];
        }
    }
}

#if FUJI_X86_64
FUJI_AVX2_TARGET
static void pack_interior_yuv_avx2(
    const uint8_t* row_r, const uint8_t* row_g, const uint8_t* row_b, const uint8_t* row_y,
    int32_t even_start, int32_t even_end,
    int32_t mono_mode, int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    int32_t is_semi_planar, int32_t is_yyuv422, uint8_t* dst_row, uint8_t* uv_row
) {
    const __m128i ones_8   = _mm_set1_epi8(1);
    const __m256i v_zero   = _mm256_setzero_si256();
    const __m256i v_255    = _mm256_set1_epi32(255);
    const __m256i v_128    = _mm256_set1_epi32(128);
    const __m256i v_u0     = _mm256_set1_epi32(has_yuv_mat ? yuv_mat[3] : -43);
    const __m256i v_u1     = _mm256_set1_epi32(has_yuv_mat ? yuv_mat[4] : -85);
    const __m256i v_u2     = _mm256_set1_epi32(has_yuv_mat ? yuv_mat[5] : 128);
    const __m256i v_v0     = _mm256_set1_epi32(has_yuv_mat ? yuv_mat[6] : 128);
    const __m256i v_v1     = _mm256_set1_epi32(has_yuv_mat ? yuv_mat[7] : -107);
    const __m256i v_v2     = _mm256_set1_epi32(has_yuv_mat ? yuv_mat[8] : -21);
    const __m256i v_half   = _mm256_set1_epi32(has_yuv_mat ? yuv_half : 128);
    const int32_t sh       = has_yuv_mat ? yuv_shift : 8;
    const __m128i uv_mono  = _mm_set1_epi8((char)0x80);
    const __m256i pack_shuf = _mm256_setr_epi8(
        0, 4, 8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        0, 4, 8, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1
    );

    int32_t x = even_start;
    for (; x + 16 <= even_end; x += 16) {
        __m128i uv16;
        if (mono_mode) {
            uv16 = uv_mono;
        } else {
            /* Exact truncating pair average (a + b) >> 1 matching Kotlin ushr 1 */
            __m128i r16 = _mm_srli_epi16(_mm_maddubs_epi16(_mm_loadu_si128((const __m128i*)(row_r + x)), ones_8), 1);
            __m128i g16 = _mm_srli_epi16(_mm_maddubs_epi16(_mm_loadu_si128((const __m128i*)(row_g + x)), ones_8), 1);
            __m128i b16 = _mm_srli_epi16(_mm_maddubs_epi16(_mm_loadu_si128((const __m128i*)(row_b + x)), ones_8), 1);
            __m256i ar = _mm256_cvtepu16_epi32(r16);
            __m256i ag = _mm256_cvtepu16_epi32(g16);
            __m256i ab = _mm256_cvtepu16_epi32(b16);

            __m256i u32 = _mm256_add_epi32(_mm256_srai_epi32(_mm256_add_epi32(_mm256_add_epi32(_mm256_mullo_epi32(v_u0, ar), _mm256_mullo_epi32(v_u1, ag)), _mm256_add_epi32(_mm256_mullo_epi32(v_u2, ab), v_half)), sh), v_128);
            __m256i v32 = _mm256_add_epi32(_mm256_srai_epi32(_mm256_add_epi32(_mm256_add_epi32(_mm256_mullo_epi32(v_v0, ar), _mm256_mullo_epi32(v_v1, ag)), _mm256_add_epi32(_mm256_mullo_epi32(v_v2, ab), v_half)), sh), v_128);
            u32 = _mm256_max_epi32(v_zero, _mm256_min_epi32(v_255, u32));
            v32 = _mm256_max_epi32(v_zero, _mm256_min_epi32(v_255, v32));

            __m256i up = _mm256_shuffle_epi8(u32, pack_shuf);
            __m256i vp = _mm256_shuffle_epi8(v32, pack_shuf);
            uint64_t u64 = ((uint64_t)(uint32_t)_mm256_extract_epi32(up, 0)) | (((uint64_t)(uint32_t)_mm256_extract_epi32(up, 4)) << 32);
            uint64_t v64 = ((uint64_t)(uint32_t)_mm256_extract_epi32(vp, 0)) | (((uint64_t)(uint32_t)_mm256_extract_epi32(vp, 4)) << 32);
            uv16 = _mm_unpacklo_epi8(_mm_cvtsi64_si128((long long)u64), _mm_cvtsi64_si128((long long)v64));
        }

        if (is_semi_planar) {
            _mm_storeu_si128((__m128i*)(uv_row + x), uv16);
        } else {
            __m128i y16 = _mm_loadu_si128((const __m128i*)(row_y + x));
            if (is_yyuv422) {
                _mm_storeu_si128((__m128i*)(dst_row + (x << 1)),      _mm_unpacklo_epi16(y16, uv16));
                _mm_storeu_si128((__m128i*)(dst_row + (x << 1) + 16), _mm_unpackhi_epi16(y16, uv16));
            } else {
                _mm_storeu_si128((__m128i*)(dst_row + (x << 1)),      _mm_unpacklo_epi8(uv16, y16));
                _mm_storeu_si128((__m128i*)(dst_row + (x << 1) + 16), _mm_unpackhi_epi8(uv16, y16));
            }
        }
    }
    if (x < even_end) {
        pack_interior_yuv_scalar(row_r, row_g, row_b, row_y, x, even_end, mono_mode, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, is_semi_planar, is_yyuv422, dst_row, uv_row);
    }
}

#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static void pack_interior_yuv_avx512(
    const uint8_t* row_r, const uint8_t* row_g, const uint8_t* row_b, const uint8_t* row_y,
    int32_t even_start, int32_t even_end,
    int32_t mono_mode, int32_t has_yuv_mat, const int32_t* yuv_mat, int32_t yuv_shift, int32_t yuv_half,
    int32_t is_semi_planar, int32_t is_yyuv422, uint8_t* dst_row, uint8_t* uv_row
) {
    const __m256i ones_8   = _mm256_set1_epi8(1);
    const __m512i v_zero   = _mm512_setzero_si512();
    const __m512i v_255    = _mm512_set1_epi32(255);
    const __m512i v_128    = _mm512_set1_epi32(128);
    const __m512i v_u0     = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[3] : -43);
    const __m512i v_u1     = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[4] : -85);
    const __m512i v_u2     = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[5] : 128);
    const __m512i v_v0     = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[6] : 128);
    const __m512i v_v1     = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[7] : -107);
    const __m512i v_v2     = _mm512_set1_epi32(has_yuv_mat ? yuv_mat[8] : -21);
    const __m512i v_half   = _mm512_set1_epi32(has_yuv_mat ? yuv_half : 128);
    const unsigned int sh  = (unsigned int)(has_yuv_mat ? yuv_shift : 8);
    const __m128i uv_mono  = _mm_set1_epi8((char)0x80);

    int32_t x = even_start;
    for (; x + 32 <= even_end; x += 32) {
        __m128i uv_lo, uv_hi;
        if (mono_mode) {
            uv_lo = uv_mono;
            uv_hi = uv_mono;
        } else {
            __m256i r16 = _mm256_srli_epi16(_mm256_maddubs_epi16(_mm256_loadu_si256((const __m256i*)(row_r + x)), ones_8), 1);
            __m256i g16 = _mm256_srli_epi16(_mm256_maddubs_epi16(_mm256_loadu_si256((const __m256i*)(row_g + x)), ones_8), 1);
            __m256i b16 = _mm256_srli_epi16(_mm256_maddubs_epi16(_mm256_loadu_si256((const __m256i*)(row_b + x)), ones_8), 1);
            __m512i ar = _mm512_cvtepu16_epi32(r16);
            __m512i ag = _mm512_cvtepu16_epi32(g16);
            __m512i ab = _mm512_cvtepu16_epi32(b16);

            __m512i u32 = _mm512_add_epi32(_mm512_srai_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_mullo_epi32(v_u0, ar), _mm512_mullo_epi32(v_u1, ag)), _mm512_add_epi32(_mm512_mullo_epi32(v_u2, ab), v_half)), sh), v_128);
            __m512i v32 = _mm512_add_epi32(_mm512_srai_epi32(_mm512_add_epi32(_mm512_add_epi32(_mm512_mullo_epi32(v_v0, ar), _mm512_mullo_epi32(v_v1, ag)), _mm512_add_epi32(_mm512_mullo_epi32(v_v2, ab), v_half)), sh), v_128);
            u32 = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_255, u32));
            v32 = _mm512_max_epi32(v_zero, _mm512_min_epi32(v_255, v32));

            __m128i u16 = _mm512_cvtepi32_epi8(u32);
            __m128i v16 = _mm512_cvtepi32_epi8(v32);
            uv_lo = _mm_unpacklo_epi8(u16, v16);
            uv_hi = _mm_unpackhi_epi8(u16, v16);
        }

        if (is_semi_planar) {
            _mm_storeu_si128((__m128i*)(uv_row + x),      uv_lo);
            _mm_storeu_si128((__m128i*)(uv_row + x + 16), uv_hi);
        } else {
            __m128i y_lo = _mm_loadu_si128((const __m128i*)(row_y + x));
            __m128i y_hi = _mm_loadu_si128((const __m128i*)(row_y + x + 16));
            uint8_t* p = dst_row + (x << 1);
            if (is_yyuv422) {
                _mm_storeu_si128((__m128i*)(p),      _mm_unpacklo_epi16(y_lo, uv_lo));
                _mm_storeu_si128((__m128i*)(p + 16), _mm_unpackhi_epi16(y_lo, uv_lo));
                _mm_storeu_si128((__m128i*)(p + 32), _mm_unpacklo_epi16(y_hi, uv_hi));
                _mm_storeu_si128((__m128i*)(p + 48), _mm_unpackhi_epi16(y_hi, uv_hi));
            } else {
                _mm_storeu_si128((__m128i*)(p),      _mm_unpacklo_epi8(uv_lo, y_lo));
                _mm_storeu_si128((__m128i*)(p + 16), _mm_unpackhi_epi8(uv_lo, y_lo));
                _mm_storeu_si128((__m128i*)(p + 32), _mm_unpacklo_epi8(uv_hi, y_hi));
                _mm_storeu_si128((__m128i*)(p + 48), _mm_unpackhi_epi8(uv_hi, y_hi));
            }
        }
    }
    if (x < even_end) {
        pack_interior_yuv_avx2(row_r, row_g, row_b, row_y, x, even_end, mono_mode, has_yuv_mat, yuv_mat, yuv_shift, yuv_half, is_semi_planar, is_yyuv422, dst_row, uv_row);
    }
}
#endif
#endif

/* 5-tap HPF Focus Peaking Row Stamp (supports UYVY422, YYUV422, NV16/NV12) */
static inline void stamp_peaking_pixel(
    int32_t px, uint8_t hi_y, uint8_t hi_cb, uint8_t hi_cr,
    int32_t is_semi_planar, int32_t is_yyuv422,
    uint8_t* y_or_uyvy_row, uint8_t* uv_row
) {
    int32_t pair_x = px & ~1;
    if (is_semi_planar) {
        y_or_uyvy_row[px] = hi_y;
        if (uv_row != NULL) {
            uv_row[pair_x]     = hi_cb;
            uv_row[pair_x + 1] = hi_cr;
        }
    } else if (is_yyuv422) {
        uint8_t* p = y_or_uyvy_row + (pair_x << 1);
        p[(px & 1) ? 1 : 0] = hi_y;
        p[2] = hi_cb;
        p[3] = hi_cr;
    } else {
        uint8_t* p = y_or_uyvy_row + (pair_x << 1);
        p[0] = hi_cb;
        p[2] = hi_cr;
        p[(px & 1) ? 3 : 1] = hi_y;
    }
}

static int32_t isp_peaking_row_scalar(
    const uint8_t* yp2, const uint8_t* yp1, const uint8_t* yc, const uint8_t* yn1, const uint8_t* yn2,
    int32_t width, int32_t thr, uint8_t hi_y, uint8_t hi_cb, uint8_t hi_cr,
    int32_t is_semi_planar, int32_t is_yyuv422, uint8_t* y_or_uyvy_row, uint8_t* uv_row
) {
    int32_t stamped = 0;
    const int32_t x_end = width - 2;
    for (int32_t x = 2; x < x_end; ++x) {
        int32_t gx1 = abs((int)yc[x + 1] - (int)yc[x - 1]);
        int32_t gx2 = abs((int)yc[x + 2] - (int)yc[x - 2]);
        int32_t gy1 = abs((int)yn1[x] - (int)yp1[x]);
        int32_t gy2 = abs((int)yn2[x] - (int)yp2[x]);
        int32_t mag = (gx1 > gx2 ? gx1 : gx2) + (gy1 > gy2 ? gy1 : gy2);
        if (mag > thr) {
            stamp_peaking_pixel(x, hi_y, hi_cb, hi_cr, is_semi_planar, is_yyuv422, y_or_uyvy_row, uv_row);
            stamped++;
        }
    }
    return stamped;
}

#if FUJI_X86_64
FUJI_AVX2_TARGET
static int32_t isp_peaking_row_avx2(
    const uint8_t* yp2, const uint8_t* yp1, const uint8_t* yc, const uint8_t* yn1, const uint8_t* yn2,
    int32_t width, int32_t thr, uint8_t hi_y, uint8_t hi_cb, uint8_t hi_cr,
    int32_t is_semi_planar, int32_t is_yyuv422, uint8_t* y_or_uyvy_row, uint8_t* uv_row
) {
    int32_t stamped = 0;
    const __m256i v_thr = _mm256_set1_epi16((short)thr);
    const int32_t x_end = width - 2;
    int32_t x = 2;
    for (; x + 16 <= x_end; x += 16) {
        __m128i l1 = _mm_loadu_si128((const __m128i*)(yc + x - 1));
        __m128i r1 = _mm_loadu_si128((const __m128i*)(yc + x + 1));
        __m128i l2 = _mm_loadu_si128((const __m128i*)(yc + x - 2));
        __m128i r2 = _mm_loadu_si128((const __m128i*)(yc + x + 2));
        __m128i u1 = _mm_loadu_si128((const __m128i*)(yp1 + x));
        __m128i d1 = _mm_loadu_si128((const __m128i*)(yn1 + x));
        __m128i u2 = _mm_loadu_si128((const __m128i*)(yp2 + x));
        __m128i d2 = _mm_loadu_si128((const __m128i*)(yn2 + x));

        __m128i gx1 = _mm_or_si128(_mm_subs_epu8(r1, l1), _mm_subs_epu8(l1, r1));
        __m128i gx2 = _mm_or_si128(_mm_subs_epu8(r2, l2), _mm_subs_epu8(l2, r2));
        __m128i gy1 = _mm_or_si128(_mm_subs_epu8(d1, u1), _mm_subs_epu8(u1, d1));
        __m128i gy2 = _mm_or_si128(_mm_subs_epu8(d2, u2), _mm_subs_epu8(u2, d2));

        __m128i mx = _mm_max_epu8(gx1, gx2);
        __m128i my = _mm_max_epu8(gy1, gy2);
        __m256i sum16 = _mm256_add_epi16(_mm256_cvtepu8_epi16(mx), _mm256_cvtepu8_epi16(my));
        uint32_t mask = (uint32_t)_mm256_movemask_epi8(_mm256_cmpgt_epi16(sum16, v_thr));
        if (mask == 0u) continue;
        mask &= 0x55555555u;
        while (mask != 0u) {
            int bit = __builtin_ctz(mask) >> 1;
            mask &= (mask - 1u);
            stamp_peaking_pixel(x + bit, hi_y, hi_cb, hi_cr, is_semi_planar, is_yyuv422, y_or_uyvy_row, uv_row);
            stamped++;
        }
    }
    for (; x < x_end; ++x) {
        int32_t gx1 = abs((int)yc[x + 1] - (int)yc[x - 1]);
        int32_t gx2 = abs((int)yc[x + 2] - (int)yc[x - 2]);
        int32_t gy1 = abs((int)yn1[x] - (int)yp1[x]);
        int32_t gy2 = abs((int)yn2[x] - (int)yp2[x]);
        int32_t mag = (gx1 > gx2 ? gx1 : gx2) + (gy1 > gy2 ? gy1 : gy2);
        if (mag > thr) {
            stamp_peaking_pixel(x, hi_y, hi_cb, hi_cr, is_semi_planar, is_yyuv422, y_or_uyvy_row, uv_row);
            stamped++;
        }
    }
    return stamped;
}

#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static int32_t isp_peaking_row_avx512(
    const uint8_t* yp2, const uint8_t* yp1, const uint8_t* yc, const uint8_t* yn1, const uint8_t* yn2,
    int32_t width, int32_t thr, uint8_t hi_y, uint8_t hi_cb, uint8_t hi_cr,
    int32_t is_semi_planar, int32_t is_yyuv422, uint8_t* y_or_uyvy_row, uint8_t* uv_row
) {
    int32_t stamped = 0;
    const __m512i v_thr = _mm512_set1_epi16((short)thr);
    const int32_t x_end = width - 2;
    int32_t x = 2;
    for (; x + 32 <= x_end; x += 32) {
        __m256i l1 = _mm256_loadu_si256((const __m256i*)(yc + x - 1));
        __m256i r1 = _mm256_loadu_si256((const __m256i*)(yc + x + 1));
        __m256i l2 = _mm256_loadu_si256((const __m256i*)(yc + x - 2));
        __m256i r2 = _mm256_loadu_si256((const __m256i*)(yc + x + 2));
        __m256i u1 = _mm256_loadu_si256((const __m256i*)(yp1 + x));
        __m256i d1 = _mm256_loadu_si256((const __m256i*)(yn1 + x));
        __m256i u2 = _mm256_loadu_si256((const __m256i*)(yp2 + x));
        __m256i d2 = _mm256_loadu_si256((const __m256i*)(yn2 + x));

        __m256i gx1 = _mm256_or_si256(_mm256_subs_epu8(r1, l1), _mm256_subs_epu8(l1, r1));
        __m256i gx2 = _mm256_or_si256(_mm256_subs_epu8(r2, l2), _mm256_subs_epu8(l2, r2));
        __m256i gy1 = _mm256_or_si256(_mm256_subs_epu8(d1, u1), _mm256_subs_epu8(u1, d1));
        __m256i gy2 = _mm256_or_si256(_mm256_subs_epu8(d2, u2), _mm256_subs_epu8(u2, d2));

        __m256i mx = _mm256_max_epu8(gx1, gx2);
        __m256i my = _mm256_max_epu8(gy1, gy2);
        __m512i sum16 = _mm512_add_epi16(_mm512_cvtepu8_epi16(mx), _mm512_cvtepu8_epi16(my));
        uint32_t mask = (uint32_t)_mm512_cmpgt_epi16_mask(sum16, v_thr);
        if (mask == 0u) continue;
        while (mask != 0u) {
            int bit = __builtin_ctz(mask);
            mask &= (mask - 1u);
            stamp_peaking_pixel(x + bit, hi_y, hi_cb, hi_cr, is_semi_planar, is_yyuv422, y_or_uyvy_row, uv_row);
            stamped++;
        }
    }
    for (; x < x_end; ++x) {
        int32_t gx1 = abs((int)yc[x + 1] - (int)yc[x - 1]);
        int32_t gx2 = abs((int)yc[x + 2] - (int)yc[x - 2]);
        int32_t gy1 = abs((int)yn1[x] - (int)yp1[x]);
        int32_t gy2 = abs((int)yn2[x] - (int)yp2[x]);
        int32_t mag = (gx1 > gx2 ? gx1 : gx2) + (gy1 > gy2 ? gy1 : gy2);
        if (mag > thr) {
            stamp_peaking_pixel(x, hi_y, hi_cb, hi_cr, is_semi_planar, is_yyuv422, y_or_uyvy_row, uv_row);
            stamped++;
        }
    }
    return stamped;
}
#endif

/* Single-pass SIMD AF contrast row energy: sum(|Y(x+1)-Y(x-1)| + |Y(y+1)-Y(y-1)|) */
FUJI_AVX2_TARGET
static uint64_t isp_af_contrast_row_avx2(const uint8_t* yp1, const uint8_t* yc, const uint8_t* yn1, int32_t x_start, int32_t x_end) {
    __m256i acc = _mm256_setzero_si256();
    int32_t x = x_start;
    for (; x + 32 <= x_end; x += 32) {
        __m256i l = _mm256_loadu_si256((const __m256i*)(yc + x - 1));
        __m256i r = _mm256_loadu_si256((const __m256i*)(yc + x + 1));
        __m256i u = _mm256_loadu_si256((const __m256i*)(yp1 + x));
        __m256i d = _mm256_loadu_si256((const __m256i*)(yn1 + x));
        acc = _mm256_add_epi64(acc, _mm256_sad_epu8(l, r));
        acc = _mm256_add_epi64(acc, _mm256_sad_epu8(u, d));
    }
    uint64_t lanes[4];
    _mm256_storeu_si256((__m256i*)lanes, acc);
    uint64_t sum = lanes[0] + lanes[1] + lanes[2] + lanes[3];
    for (; x < x_end; ++x) {
        sum += (uint64_t)abs((int)yc[x + 1] - (int)yc[x - 1]) + (uint64_t)abs((int)yn1[x] - (int)yp1[x]);
    }
    return sum;
}
#endif

static uint64_t isp_af_contrast_row_scalar(const uint8_t* yp1, const uint8_t* yc, const uint8_t* yn1, int32_t x_start, int32_t x_end) {
    uint64_t sum = 0;
    for (int32_t x = x_start; x < x_end; ++x) {
        sum += (uint64_t)abs((int)yc[x + 1] - (int)yc[x - 1]) + (uint64_t)abs((int)yn1[x] - (int)yp1[x]);
    }
    return sum;
}

typedef struct {
    const uint8_t* raw;
    uint8_t* y_or_uyvy_dst;
    uint8_t* uv_dst;
    const int32_t* src_x_map;
    const int32_t* ccm;
    const int32_t* lut_r;
    const int32_t* lut_g;
    const int32_t* lut_b;
    const float* lut3d;
    const int32_t* yuv_mat;
    int32_t src_w, src_h, is_16bit;
    int32_t dst_w, dst_h, stride;
    int32_t is_semi_planar, is_nv12, is_yyuv422;
    int32_t off_x, off_y, fit_w, fit_h, end_x, end_y;
    int32_t gain_r, gain_g, gain_b, unity_gain;
    int32_t has_ccm, has_yuv_mat, mono_mode;
    int32_t yuv_shift, yuv_half, same_dims;
    int32_t peaking_active, peaking_thr;
    uint8_t hi_y, hi_cb, hi_cr;
    int32_t compute_hist;
    int32_t compute_af;
    int simd_level;
    int32_t stamped_per_thread[FUJI_ISP_MAX_THREADS];
    uint64_t af_per_thread[FUJI_ISP_MAX_THREADS];
    int32_t hist_per_thread[FUJI_ISP_MAX_THREADS][1024];
} isp_mt_ctx;

static void isp_strip_worker(int32_t task_idx, int32_t num_tasks, void* vctx) {
    isp_mt_ctx* ctx = (isp_mt_ctx*)vctx;
    const int32_t dst_h = ctx->dst_h;
    const int32_t dst_w = ctx->dst_w;
    const int32_t src_w = ctx->src_w;
    const int32_t src_h = ctx->src_h;
    const int32_t max_y = src_h - 1;
    const int32_t stride = ctx->stride;
    const int32_t uv_height = ctx->is_nv12 ? (dst_h / 2) : dst_h;
    const int32_t uv_max_row = uv_height - 1;

    /* Align strip boundaries to even rows so NV12 UV rows are owned exclusively by one strip */
    int32_t y_start = (int32_t)(((int64_t)task_idx * (int64_t)dst_h) / (int64_t)num_tasks);
    int32_t y_end   = (int32_t)(((int64_t)(task_idx + 1) * (int64_t)dst_h) / (int64_t)num_tasks);
    if (task_idx > 0) y_start &= ~1;
    if (task_idx + 1 < num_tasks) y_end &= ~1;
    if (y_start >= y_end) {
        ctx->stamped_per_thread[task_idx] = 0;
        ctx->af_per_thread[task_idx] = 0;
        return;
    }

    int32_t* local_hist = ctx->compute_hist ? ctx->hist_per_thread[task_idx] : NULL;
    if (local_hist != NULL) {
        memset(local_hist, 0, 1024u * sizeof(int32_t));
    }

    /* Halo rows needed for 5-tap peaking (2) or 3-tap AF contrast (1) */
    const int32_t halo = ctx->peaking_active ? 2 : (ctx->compute_af ? 1 : 0);
    const int32_t loop_start = (y_start - halo > 0) ? (y_start - halo) : 0;
    const int32_t loop_end   = (y_end + halo < dst_h) ? (y_end + halo) : dst_h;

    int32_t stack_src_rows[3 * (2048 + 2)];
    uint8_t stack_rgb[3 * 2048];
    uint8_t stack_y_ring[5 * 2048];

    int32_t* src_rows_buf = stack_src_rows;
    uint8_t* rgb_buf      = stack_rgb;
    uint8_t* y_ring_buf   = stack_y_ring;
    const size_t src_row_stride = (size_t)src_w + 2u;
    if (src_w > 2048) src_rows_buf = (int32_t*)malloc(3u * src_row_stride * sizeof(int32_t));
    if (dst_w > 2048) {
        rgb_buf    = (uint8_t*)malloc(3u * (size_t)dst_w);
        y_ring_buf = (uint8_t*)malloc(5u * (size_t)dst_w);
    }

    int32_t* sp_buf = src_rows_buf;
    int32_t* sc_buf = src_rows_buf + src_row_stride;
    int32_t* sn_buf = src_rows_buf + 2u * src_row_stride;
    uint8_t* row_r  = rgb_buf;
    uint8_t* row_g  = rgb_buf + dst_w;
    uint8_t* row_b  = rgb_buf + 2 * dst_w;
    int32_t cached_sy_p = -1, cached_sy_c = -1, cached_sy_n = -1;

    int32_t stamped_pixels = 0;
    uint64_t af_sum = 0;
    const int simd_level = ctx->simd_level;
    const int32_t af_x0 = ctx->off_x + 1;
    const int32_t af_x1 = ctx->end_x - 1;
    const int32_t af_y_min = ctx->off_y + 1;
    const int32_t af_y_max = ctx->end_y - 2;

    for (int32_t y = loop_start; y < loop_end; ++y) {
        uint8_t* row_y = y_ring_buf + (size_t)(y % 5) * (size_t)dst_w;
        const int in_owned_strip = (y >= y_start && y < y_end);

        if (y < ctx->off_y || y >= ctx->end_y) {
            if (halo > 0) memset(row_y, 0, (size_t)dst_w);
            if (in_owned_strip) {
                const size_t y_row_off = (size_t)y * (size_t)stride;
                const int write_uv_row = !ctx->is_nv12 || ((y & 1) == 0);
                const int32_t uv_row_idx = ctx->is_nv12 ? (y >> 1) : y;
                const size_t uv_row_off = (size_t)uv_row_idx * (size_t)stride;
                if (ctx->is_semi_planar) {
                    memset(ctx->y_or_uyvy_dst + y_row_off, 0, (size_t)dst_w);
                    if (write_uv_row) {
                        memset(ctx->uv_dst + uv_row_off, 0x80, (size_t)dst_w);
                    }
                } else {
                    uint32_t black_pat = ctx->is_yyuv422 ? 0x80800000u : 0x00800080u;
                    fill_u32_pattern(ctx->y_or_uyvy_dst + y_row_off, dst_w * 2, black_pat);
                }
            }
        } else {
            const int32_t sy = ctx->same_dims ? y : (int32_t)(((int64_t)(y - ctx->off_y) * (int64_t)src_h) / (int64_t)ctx->fit_h);
            const int32_t sy_clamped = (sy < 0) ? 0 : (sy > max_y ? max_y : sy);
            const int32_t y_prev = (sy_clamped > 0) ? (sy_clamped - 1) : ((max_y > 0) ? 1 : 0);
            const int32_t y_next = (sy_clamped < max_y) ? (sy_clamped + 1) : ((max_y > 0) ? (max_y - 1) : 0);

            if (y_prev != cached_sy_p) {
                convert_bayer_row_to_i32(ctx->raw, y_prev, src_w, ctx->is_16bit, simd_level, sp_buf);
                cached_sy_p = y_prev;
            }
            if (sy_clamped != cached_sy_c) {
                convert_bayer_row_to_i32(ctx->raw, sy_clamped, src_w, ctx->is_16bit, simd_level, sc_buf);
                cached_sy_c = sy_clamped;
            }
            if (y_next != cached_sy_n) {
                convert_bayer_row_to_i32(ctx->raw, y_next, src_w, ctx->is_16bit, simd_level, sn_buf);
                cached_sy_n = y_next;
            }

            if (ctx->off_x > 0) memset(row_y, 0, (size_t)ctx->off_x);
            if (ctx->end_x < dst_w) memset(row_y + ctx->end_x, 0, (size_t)(dst_w - ctx->end_x));

#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
            if (simd_level >= FUJI_ISP_SIMD_AVX512) {
                isp_process_row_avx512(
                    sp_buf + 1, sc_buf + 1, sn_buf + 1, sy_clamped & 1,
                    ctx->src_x_map, ctx->off_x, ctx->end_x, ctx->same_dims,
                    ctx->gain_r, ctx->gain_g, ctx->gain_b, ctx->unity_gain,
                    ctx->has_ccm, ctx->ccm, ctx->lut_r, ctx->lut_g, ctx->lut_b,
                    ctx->lut3d,
                    ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half,
                    row_r, row_g, row_b, row_y
                );
            } else
#endif
            if (simd_level >= FUJI_ISP_SIMD_AVX2) {
                isp_process_row_avx2(
                    sp_buf + 1, sc_buf + 1, sn_buf + 1, sy_clamped & 1,
                    ctx->src_x_map, ctx->off_x, ctx->end_x, ctx->same_dims,
                    ctx->gain_r, ctx->gain_g, ctx->gain_b, ctx->unity_gain,
                    ctx->has_ccm, ctx->ccm, ctx->lut_r, ctx->lut_g, ctx->lut_b,
                    ctx->lut3d,
                    ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half,
                    row_r, row_g, row_b, row_y
                );
            } else
#endif
            {
                isp_process_row_scalar(
                    sp_buf + 1, sc_buf + 1, sn_buf + 1, sy_clamped & 1,
                    ctx->src_x_map, ctx->off_x, ctx->end_x,
                    ctx->gain_r, ctx->gain_g, ctx->gain_b, ctx->unity_gain,
                    ctx->has_ccm, ctx->ccm, ctx->lut_r, ctx->lut_g, ctx->lut_b,
                    ctx->lut3d,
                    ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half,
                    row_r, row_g, row_b, row_y
                );
            }

            if (in_owned_strip) {
                if (local_hist != NULL) {
                    int32_t* hy = local_hist;
                    int32_t* hr = local_hist + 256;
                    int32_t* hg = local_hist + 512;
                    int32_t* hb = local_hist + 768;
                    for (int32_t x = ctx->off_x; x < ctx->end_x; ++x) {
                        hy[row_y[x]]++;
                        hr[row_r[x]]++;
                        hg[row_g[x]]++;
                        hb[row_b[x]]++;
                    }
                }

                const size_t y_row_off = (size_t)y * (size_t)stride;
                const int write_uv_row = !ctx->is_nv12 || ((y & 1) == 0);
                const int32_t uv_row_idx = ctx->is_nv12 ? (y >> 1) : y;
                const size_t uv_row_off = (size_t)uv_row_idx * (size_t)stride;
                const int32_t even_start = (ctx->off_x + 1) & ~1;
                const int32_t even_end   = ctx->end_x & ~1;

                if (ctx->is_semi_planar) {
                    memcpy(ctx->y_or_uyvy_dst + y_row_off, row_y, (size_t)dst_w);
                    if (write_uv_row) {
                        uint8_t* uv_row = ctx->uv_dst + uv_row_off;
                        for (int32_t x = 0; x < even_start && x < dst_w; x += 2) {
                            const int in0 = (x >= ctx->off_x && x < ctx->end_x);
                            const int in1 = (x + 1 >= ctx->off_x && x + 1 < ctx->end_x);
                            if (!in0 && !in1) { uv_row[x] = 0x80u; uv_row[x + 1] = 0x80u; }
                            else {
                                int32_t ar = in0 ? row_r[x] : row_r[x + 1];
                                int32_t ag = in0 ? row_g[x] : row_g[x + 1];
                                int32_t ab = in0 ? row_b[x] : row_b[x + 1];
                                pack_uv_pair_scalar(ar, ag, ab, ctx->mono_mode, ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half, &uv_row[x], &uv_row[x + 1]);
                            }
                        }
                        if (even_start < even_end) {
#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
                            if (simd_level >= FUJI_ISP_SIMD_AVX512) {
                                pack_interior_yuv_avx512(row_r, row_g, row_b, row_y, even_start, even_end, ctx->mono_mode, ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half, 1, 0, NULL, uv_row);
                            } else
#endif
                            if (simd_level >= FUJI_ISP_SIMD_AVX2) {
                                pack_interior_yuv_avx2(row_r, row_g, row_b, row_y, even_start, even_end, ctx->mono_mode, ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half, 1, 0, NULL, uv_row);
                            } else
#endif
                            {
                                pack_interior_yuv_scalar(row_r, row_g, row_b, row_y, even_start, even_end, ctx->mono_mode, ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half, 1, 0, NULL, uv_row);
                            }
                        }
                        for (int32_t x = (even_end > even_start ? even_end : even_start); x < dst_w; x += 2) {
                            const int in0 = (x >= ctx->off_x && x < ctx->end_x);
                            const int in1 = (x + 1 >= ctx->off_x && x + 1 < ctx->end_x);
                            if (!in0 && !in1) { uv_row[x] = 0x80u; uv_row[x + 1] = 0x80u; }
                            else {
                                int32_t ar = in0 ? row_r[x] : row_r[x + 1];
                                int32_t ag = in0 ? row_g[x] : row_g[x + 1];
                                int32_t ab = in0 ? row_b[x] : row_b[x + 1];
                                pack_uv_pair_scalar(ar, ag, ab, ctx->mono_mode, ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half, &uv_row[x], &uv_row[x + 1]);
                            }
                        }
                    }
                } else {
                    uint8_t* dst_row = ctx->y_or_uyvy_dst + y_row_off;
                    for (int32_t x = 0; x < even_start && x < dst_w; x += 2) {
                        const int in0 = (x >= ctx->off_x && x < ctx->end_x);
                        const int in1 = (x + 1 >= ctx->off_x && x + 1 < ctx->end_x);
                        uint8_t* p = dst_row + (x << 1);
                        uint8_t u8 = 0x80u, v8 = 0x80u;
                        uint8_t y0 = in0 ? row_y[x] : 0u;
                        uint8_t y1 = in1 ? row_y[x + 1] : 0u;
                        if (in0 || in1) {
                            int32_t ar = in0 ? row_r[x] : row_r[x + 1];
                            int32_t ag = in0 ? row_g[x] : row_g[x + 1];
                            int32_t ab = in0 ? row_b[x] : row_b[x + 1];
                            pack_uv_pair_scalar(ar, ag, ab, ctx->mono_mode, ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half, &u8, &v8);
                        }
                        if (ctx->is_yyuv422) { p[0] = y0; p[1] = y1; p[2] = u8; p[3] = v8; }
                        else                { p[0] = u8; p[1] = y0; p[2] = v8; p[3] = y1; }
                    }
                    if (even_start < even_end) {
#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
                        if (simd_level >= FUJI_ISP_SIMD_AVX512) {
                            pack_interior_yuv_avx512(row_r, row_g, row_b, row_y, even_start, even_end, ctx->mono_mode, ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half, 0, ctx->is_yyuv422, dst_row, NULL);
                        } else
#endif
                        if (simd_level >= FUJI_ISP_SIMD_AVX2) {
                            pack_interior_yuv_avx2(row_r, row_g, row_b, row_y, even_start, even_end, ctx->mono_mode, ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half, 0, ctx->is_yyuv422, dst_row, NULL);
                        } else
#endif
                        {
                            pack_interior_yuv_scalar(row_r, row_g, row_b, row_y, even_start, even_end, ctx->mono_mode, ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half, 0, ctx->is_yyuv422, dst_row, NULL);
                        }
                    }
                    for (int32_t x = (even_end > even_start ? even_end : even_start); x < dst_w; x += 2) {
                        const int in0 = (x >= ctx->off_x && x < ctx->end_x);
                        const int in1 = (x + 1 >= ctx->off_x && x + 1 < ctx->end_x);
                        uint8_t* p = dst_row + (x << 1);
                        uint8_t u8 = 0x80u, v8 = 0x80u;
                        uint8_t y0 = in0 ? row_y[x] : 0u;
                        uint8_t y1 = in1 ? row_y[x + 1] : 0u;
                        if (in0 || in1) {
                            int32_t ar = in0 ? row_r[x] : row_r[x + 1];
                            int32_t ag = in0 ? row_g[x] : row_g[x + 1];
                            int32_t ab = in0 ? row_b[x] : row_b[x + 1];
                            pack_uv_pair_scalar(ar, ag, ab, ctx->mono_mode, ctx->has_yuv_mat, ctx->yuv_mat, ctx->yuv_shift, ctx->yuv_half, &u8, &v8);
                        }
                        if (ctx->is_yyuv422) { p[0] = y0; p[1] = y1; p[2] = u8; p[3] = v8; }
                        else                { p[0] = u8; p[1] = y0; p[2] = v8; p[3] = y1; }
                    }
                }
            }
        }

        if (in_owned_strip) {
            const size_t y_row_off = (size_t)y * (size_t)stride;
            if (ctx->is_semi_planar) {
                const int write_uv_row = !ctx->is_nv12 || ((y & 1) == 0);
                const int32_t uv_row_idx = ctx->is_nv12 ? (y >> 1) : y;
                const size_t uv_row_off = (size_t)uv_row_idx * (size_t)stride;
                if (y < dst_h - 1 && stride > dst_w) {
                    memset(ctx->y_or_uyvy_dst + y_row_off + dst_w, 0, (size_t)(stride - dst_w));
                }
                if (write_uv_row && uv_row_idx < uv_max_row && stride > dst_w) {
                    memset(ctx->uv_dst + uv_row_off + dst_w, 0, (size_t)(stride - dst_w));
                }
            } else {
                if (y < dst_h - 1 && stride > dst_w * 2) {
                    memset(ctx->y_or_uyvy_dst + y_row_off + dst_w * 2, 0, (size_t)(stride - dst_w * 2));
                }
            }
        }

        /* Single-pass AF contrast on row ay = y - 1 (once ay-1, ay, ay+1 are in y_ring_buf) */
        if (ctx->compute_af && y >= loop_start + 2 && af_x0 < af_x1) {
            const int32_t ay = y - 1;
            if (ay >= y_start && ay < y_end && ay >= af_y_min && ay <= af_y_max) {
                const uint8_t* yp1 = y_ring_buf + (size_t)((ay - 1) % 5) * (size_t)dst_w;
                const uint8_t* yc  = y_ring_buf + (size_t)(ay % 5) * (size_t)dst_w;
                const uint8_t* yn1 = y_ring_buf + (size_t)((ay + 1) % 5) * (size_t)dst_w;
#if FUJI_X86_64
                if (simd_level >= FUJI_ISP_SIMD_AVX2) {
                    af_sum += isp_af_contrast_row_avx2(yp1, yc, yn1, af_x0, af_x1);
                } else
#endif
                {
                    af_sum += isp_af_contrast_row_scalar(yp1, yc, yn1, af_x0, af_x1);
                }
            }
        }

        /* Fused L1-hot 5-tap HPF Focus Peaking on row py = y - 2 */
        if (ctx->peaking_active && y >= loop_start + 4) {
            const int32_t py = y - 2;
            if (py >= y_start && py < y_end && py >= 2 && py < dst_h - 2) {
                const uint8_t* yp2 = y_ring_buf + (size_t)((py - 2) % 5) * (size_t)dst_w;
                const uint8_t* yp1 = y_ring_buf + (size_t)((py - 1) % 5) * (size_t)dst_w;
                const uint8_t* yc  = y_ring_buf + (size_t)(py % 5) * (size_t)dst_w;
                const uint8_t* yn1 = y_ring_buf + (size_t)((py + 1) % 5) * (size_t)dst_w;
                const uint8_t* yn2 = y_ring_buf + (size_t)((py + 2) % 5) * (size_t)dst_w;
                uint8_t* dst_py = ctx->y_or_uyvy_dst + (size_t)py * (size_t)stride;
                uint8_t* uv_py  = NULL;
                if (ctx->is_semi_planar) {
                    const int32_t p_uv_row = ctx->is_nv12 ? (py >> 1) : py;
                    if (p_uv_row < uv_height) uv_py = ctx->uv_dst + (size_t)p_uv_row * (size_t)stride;
                }
#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
                if (simd_level >= FUJI_ISP_SIMD_AVX512) {
                    stamped_pixels += isp_peaking_row_avx512(yp2, yp1, yc, yn1, yn2, dst_w, ctx->peaking_thr, ctx->hi_y, ctx->hi_cb, ctx->hi_cr, ctx->is_semi_planar, ctx->is_yyuv422, dst_py, uv_py);
                } else
#endif
                if (simd_level >= FUJI_ISP_SIMD_AVX2) {
                    stamped_pixels += isp_peaking_row_avx2(yp2, yp1, yc, yn1, yn2, dst_w, ctx->peaking_thr, ctx->hi_y, ctx->hi_cb, ctx->hi_cr, ctx->is_semi_planar, ctx->is_yyuv422, dst_py, uv_py);
                } else
#endif
                {
                    stamped_pixels += isp_peaking_row_scalar(yp2, yp1, yc, yn1, yn2, dst_w, ctx->peaking_thr, ctx->hi_y, ctx->hi_cb, ctx->hi_cr, ctx->is_semi_planar, ctx->is_yyuv422, dst_py, uv_py);
                }
            }
        }
    }

    ctx->stamped_per_thread[task_idx] = stamped_pixels;
    ctx->af_per_thread[task_idx] = af_sum;

    if (src_rows_buf != stack_src_rows) free(src_rows_buf);
    if (rgb_buf != stack_rgb) free(rgb_buf);
    if (y_ring_buf != stack_y_ring) free(y_ring_buf);
}

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
) {
    if (raw == NULL || y_or_uyvy_dst == NULL || src_x_map == NULL ||
        lut_r == NULL || lut_g == NULL || lut_b == NULL || cfg == NULL) {
        return -1;
    }

    const int32_t src_w          = cfg[0];
    const int32_t src_h          = cfg[1];
    const int32_t is_16bit       = cfg[2];
    const int32_t dst_w          = cfg[3];
    const int32_t dst_h          = cfg[4];
    const int32_t stride         = cfg[5];
    const int32_t is_semi_planar = cfg[6];
    const int32_t is_nv12        = cfg[7];
    const int32_t off_x          = cfg[8];
    const int32_t off_y          = cfg[9];
    const int32_t fit_w          = cfg[10];
    const int32_t fit_h          = cfg[11];
    const int32_t gain_r         = cfg[12];
    const int32_t gain_g         = cfg[13];
    const int32_t gain_b         = cfg[14];
    const int32_t flags          = cfg[15];
    const int32_t yuv_shift      = cfg[16];
    const int32_t peaking_thr    = cfg[17];
    const uint32_t peaking_pack  = (uint32_t)cfg[18];
    const int32_t req_threads    = cfg[19];

    if (src_w < 2 || src_h < 2 || dst_w < 2 || (dst_w & 1) != 0 || dst_h < 1) return -2;
    if (is_semi_planar && uv_dst == NULL) return -3;
    if (fit_w <= 0 || fit_h <= 0 || off_x < 0 || off_y < 0 || off_x + fit_w > dst_w || off_y + fit_h > dst_h) return -4;

    if (!is_16bit) init_sample8_to_12();

    isp_mt_ctx ctx;
    ctx.raw            = raw;
    ctx.y_or_uyvy_dst  = y_or_uyvy_dst;
    ctx.uv_dst         = uv_dst;
    ctx.src_x_map      = src_x_map;
    ctx.ccm            = ccm;
    ctx.lut_r          = lut_r;
    ctx.lut_g          = lut_g;
    ctx.lut_b          = lut_b;
    ctx.lut3d          = ((flags & FUJI_ISP_FLAG_HAS_LUT3D) != 0) ? lut3d_16 : NULL;
    ctx.yuv_mat        = yuv_mat;
    ctx.src_w          = src_w;
    ctx.src_h          = src_h;
    ctx.is_16bit       = is_16bit;
    ctx.dst_w          = dst_w;
    ctx.dst_h          = dst_h;
    ctx.stride         = stride;
    ctx.is_semi_planar = is_semi_planar;
    ctx.is_nv12        = is_nv12;
    ctx.is_yyuv422     = (!is_semi_planar && ((flags & FUJI_ISP_FLAG_YYUV422) != 0)) ? 1 : 0;
    ctx.off_x          = off_x;
    ctx.off_y          = off_y;
    ctx.fit_w          = fit_w;
    ctx.fit_h          = fit_h;
    ctx.end_x          = off_x + fit_w;
    ctx.end_y          = off_y + fit_h;
    ctx.gain_r         = gain_r;
    ctx.gain_g         = gain_g;
    ctx.gain_b         = gain_b;
    ctx.unity_gain     = (gain_r == 1024 && gain_g == 1024 && gain_b == 1024) ? 1 : 0;
    ctx.has_ccm        = (((flags & FUJI_ISP_FLAG_HAS_CCM) != 0) && ccm != NULL) ? 1 : 0;
    ctx.has_yuv_mat    = (((flags & FUJI_ISP_FLAG_HAS_YUV_MAT) != 0) && yuv_mat != NULL) ? 1 : 0;
    ctx.mono_mode      = ((flags & FUJI_ISP_FLAG_MONO_MODE) != 0) ? 1 : 0;
    ctx.yuv_shift      = (yuv_shift > 0 && yuv_shift <= 24) ? yuv_shift : 10;
    ctx.yuv_half       = 1 << (ctx.yuv_shift - 1);
    ctx.same_dims      = (src_w == dst_w && src_h == dst_h && off_x == 0 && off_y == 0) ? 1 : 0;
    ctx.peaking_active = (((flags & FUJI_ISP_FLAG_PEAKING) != 0) &&
                          (is_semi_planar ? (dst_w >= 6 && dst_h >= 5) : (dst_w >= 4 && dst_h >= 3))) ? 1 : 0;
    ctx.peaking_thr    = peaking_thr;
    ctx.hi_y           = (uint8_t)(peaking_pack & 0xffu);
    ctx.hi_cb          = (uint8_t)((peaking_pack >> 8) & 0xffu);
    ctx.hi_cr          = (uint8_t)((peaking_pack >> 16) & 0xffu);
    ctx.compute_hist   = (hist_yrgb != NULL) ? 1 : 0;
    ctx.compute_af     = (out_af_contrast != NULL && ((flags & FUJI_ISP_FLAG_AF_CONTRAST) != 0)) ? 1 : 0;
    ctx.simd_level     = get_simd_level();

    /* Only spin up MT when frame has enough work (>= 64 rows and >= 64k pixels) */
    int32_t num_tasks = 1;
    if ((int64_t)dst_w * (int64_t)dst_h >= 65536 && dst_h >= 64) {
        num_tasks = resolve_threads(req_threads, dst_h, 32);
    }

    pool_parallel_run(num_tasks, isp_strip_worker, &ctx);

    int32_t total_stamped = 0;
    uint64_t total_af = 0;
    for (int32_t t = 0; t < num_tasks; ++t) {
        total_stamped += ctx.stamped_per_thread[t];
        total_af      += ctx.af_per_thread[t];
    }
    if (hist_yrgb != NULL) {
        memcpy(hist_yrgb, ctx.hist_per_thread[0], 1024u * sizeof(int32_t));
        for (int32_t t = 1; t < num_tasks; ++t) {
            const int32_t* h = ctx.hist_per_thread[t];
            for (int32_t i = 0; i < 1024; ++i) {
                hist_yrgb[i] += h[i];
            }
        }
    }
    if (out_af_contrast != NULL) {
        *out_af_contrast = (int64_t)total_af;
    }
    return total_stamped;
}

/* ========================================================================= */
/* PART 2: Heavy Spatial Optical Filter Kernels (Black Mist, UV, Star)       */
/* ========================================================================= */

/* Horizontal sliding-window box blur (4-row unrolled for full FPU latency hiding) */
static void box_blur_h_rows_f32(
    const float* src,
    float* dst,
    int32_t width,
    int32_t y_start,
    int32_t y_end,
    int32_t radius
) {
    const int32_t max_x = width - 1;
    const float inv_k = 1.0f / (float)(radius * 2 + 1);
    int32_t y = y_start;

    for (; y + 4 <= y_end; y += 4) {
        const float* s0 = src + (size_t)y * (size_t)width;
        const float* s1 = s0 + width;
        const float* s2 = s1 + width;
        const float* s3 = s2 + width;
        float* d0 = dst + (size_t)y * (size_t)width;
        float* d1 = d0 + width;
        float* d2 = d1 + width;
        float* d3 = d2 + width;

        float sum0 = s0[0] * (float)(radius + 1);
        float sum1 = s1[0] * (float)(radius + 1);
        float sum2 = s2[0] * (float)(radius + 1);
        float sum3 = s3[0] * (float)(radius + 1);
        for (int32_t k = 1; k <= radius; ++k) {
            int32_t idx = k < max_x ? k : max_x;
            sum0 += s0[idx];
            sum1 += s1[idx];
            sum2 += s2[idx];
            sum3 += s3[idx];
        }

        /* Left border x in [0, radius] */
        int32_t x = 0;
        int32_t left_end = (radius + 1 < width) ? (radius + 1) : width;
        for (; x < left_end; ++x) {
            d0[x] = sum0 * inv_k;
            d1[x] = sum1 * inv_k;
            d2[x] = sum2 * inv_k;
            d3[x] = sum3 * inv_k;
            int32_t add_x = (x + radius + 1 < max_x) ? (x + radius + 1) : max_x;
            int32_t rem_x = (x - radius > 0) ? (x - radius) : 0;
            sum0 += s0[add_x] - s0[rem_x];
            sum1 += s1[add_x] - s1[rem_x];
            sum2 += s2[add_x] - s2[rem_x];
            sum3 += s3[add_x] - s3[rem_x];
        }
        /* Interior x: branch-free sliding window */
        int32_t mid_end = max_x - radius;
        for (; x < mid_end; ++x) {
            d0[x] = sum0 * inv_k;
            d1[x] = sum1 * inv_k;
            d2[x] = sum2 * inv_k;
            d3[x] = sum3 * inv_k;
            int32_t ax = x + radius + 1;
            int32_t rx = x - radius;
            sum0 += s0[ax] - s0[rx];
            sum1 += s1[ax] - s1[rx];
            sum2 += s2[ax] - s2[rx];
            sum3 += s3[ax] - s3[rx];
        }
        /* Right border */
        for (; x < width; ++x) {
            d0[x] = sum0 * inv_k;
            d1[x] = sum1 * inv_k;
            d2[x] = sum2 * inv_k;
            d3[x] = sum3 * inv_k;
            int32_t add_x = (x + radius + 1 < max_x) ? (x + radius + 1) : max_x;
            int32_t rem_x = (x - radius > 0) ? (x - radius) : 0;
            sum0 += s0[add_x] - s0[rem_x];
            sum1 += s1[add_x] - s1[rem_x];
            sum2 += s2[add_x] - s2[rem_x];
            sum3 += s3[add_x] - s3[rem_x];
        }
    }

    for (; y < y_end; ++y) {
        const float* s = src + (size_t)y * (size_t)width;
        float* d = dst + (size_t)y * (size_t)width;
        float sum = s[0] * (float)(radius + 1);
        for (int32_t k = 1; k <= radius; ++k) {
            sum += s[k < max_x ? k : max_x];
        }
        for (int32_t x = 0; x < width; ++x) {
            d[x] = sum * inv_k;
            int32_t add_x = (x + radius + 1 < max_x) ? (x + radius + 1) : max_x;
            int32_t rem_x = (x - radius > 0) ? (x - radius) : 0;
            sum += s[add_x] - s[rem_x];
        }
    }
}

/* Row-major vertical sliding-window box blur on column slice [x0, x1) (100% L1/L2 cache-resident SIMD!) */
static void box_blur_v_slice_scalar_f32(
    const float* src, float* dst, int32_t width, int32_t height, int32_t radius, int32_t x0, int32_t x1
) {
    const int32_t span = x1 - x0;
    float stack_col[1024];
    float* col_sum = (span <= 1024) ? stack_col : (float*)malloc((size_t)span * sizeof(float));
    const int32_t max_y = height - 1;
    const float r1 = (float)(radius + 1);
    const float inv_k = 1.0f / (float)(radius * 2 + 1);

    for (int32_t i = 0; i < span; ++i) col_sum[i] = src[x0 + i] * r1;
    for (int32_t k = 1; k <= radius; ++k) {
        const float* rk = src + (size_t)(k < max_y ? k : max_y) * (size_t)width + x0;
        for (int32_t i = 0; i < span; ++i) col_sum[i] += rk[i];
    }
    for (int32_t y = 0; y < height; ++y) {
        float* drow = dst + (size_t)y * (size_t)width + x0;
        const float* add_row = src + (size_t)(y + radius + 1 < max_y ? y + radius + 1 : max_y) * (size_t)width + x0;
        const float* rem_row = src + (size_t)(y - radius > 0 ? y - radius : 0) * (size_t)width + x0;
        for (int32_t i = 0; i < span; ++i) {
            float s = col_sum[i];
            drow[i] = s * inv_k;
            col_sum[i] = s + (add_row[i] - rem_row[i]);
        }
    }
    if (col_sum != stack_col) free(col_sum);
}

#if FUJI_X86_64
static void box_blur_v_slice_sse2_f32(
    const float* src, float* dst, int32_t width, int32_t height, int32_t radius, int32_t x0, int32_t x1
) {
    const int32_t span = x1 - x0;
    float stack_col[1024];
    float* col_sum = (span <= 1024) ? stack_col : (float*)malloc((size_t)span * sizeof(float));
    const int32_t max_y = height - 1;
    const __m128 v_r1 = _mm_set1_ps((float)(radius + 1));
    const __m128 v_inv = _mm_set1_ps(1.0f / (float)(radius * 2 + 1));
    const float inv_k = 1.0f / (float)(radius * 2 + 1);
    const int32_t s4 = span & ~3;

    const float* r0 = src + x0;
    for (int32_t i = 0; i < s4; i += 4) _mm_storeu_ps(col_sum + i, _mm_mul_ps(_mm_loadu_ps(r0 + i), v_r1));
    for (int32_t i = s4; i < span; ++i) col_sum[i] = r0[i] * (float)(radius + 1);

    for (int32_t k = 1; k <= radius; ++k) {
        const float* rk = src + (size_t)(k < max_y ? k : max_y) * (size_t)width + x0;
        for (int32_t i = 0; i < s4; i += 4) _mm_storeu_ps(col_sum + i, _mm_add_ps(_mm_loadu_ps(col_sum + i), _mm_loadu_ps(rk + i)));
        for (int32_t i = s4; i < span; ++i) col_sum[i] += rk[i];
    }

    for (int32_t y = 0; y < height; ++y) {
        float* drow = dst + (size_t)y * (size_t)width + x0;
        const float* add_row = src + (size_t)(y + radius + 1 < max_y ? y + radius + 1 : max_y) * (size_t)width + x0;
        const float* rem_row = src + (size_t)(y - radius > 0 ? y - radius : 0) * (size_t)width + x0;
        for (int32_t i = 0; i < s4; i += 4) {
            __m128 s = _mm_loadu_ps(col_sum + i);
            _mm_storeu_ps(drow + i, _mm_mul_ps(s, v_inv));
            _mm_storeu_ps(col_sum + i, _mm_add_ps(s, _mm_sub_ps(_mm_loadu_ps(add_row + i), _mm_loadu_ps(rem_row + i))));
        }
        for (int32_t i = s4; i < span; ++i) {
            float s = col_sum[i];
            drow[i] = s * inv_k;
            col_sum[i] = s + (add_row[i] - rem_row[i]);
        }
    }
    if (col_sum != stack_col) free(col_sum);
}

FUJI_AVX2_TARGET
static void box_blur_v_slice_avx2_f32(
    const float* src, float* dst, int32_t width, int32_t height, int32_t radius, int32_t x0, int32_t x1
) {
    const int32_t span = x1 - x0;
    float stack_col[1024];
    float* col_sum = (span <= 1024) ? stack_col : (float*)malloc((size_t)span * sizeof(float));
    const int32_t max_y = height - 1;
    const __m256 v_r1 = _mm256_set1_ps((float)(radius + 1));
    const __m256 v_inv = _mm256_set1_ps(1.0f / (float)(radius * 2 + 1));
    const float inv_k = 1.0f / (float)(radius * 2 + 1);
    const int32_t s8 = span & ~7;

    const float* r0 = src + x0;
    for (int32_t i = 0; i < s8; i += 8) _mm256_storeu_ps(col_sum + i, _mm256_mul_ps(_mm256_loadu_ps(r0 + i), v_r1));
    for (int32_t i = s8; i < span; ++i) col_sum[i] = r0[i] * (float)(radius + 1);

    for (int32_t k = 1; k <= radius; ++k) {
        const float* rk = src + (size_t)(k < max_y ? k : max_y) * (size_t)width + x0;
        for (int32_t i = 0; i < s8; i += 8) _mm256_storeu_ps(col_sum + i, _mm256_add_ps(_mm256_loadu_ps(col_sum + i), _mm256_loadu_ps(rk + i)));
        for (int32_t i = s8; i < span; ++i) col_sum[i] += rk[i];
    }

    for (int32_t y = 0; y < height; ++y) {
        float* drow = dst + (size_t)y * (size_t)width + x0;
        const float* add_row = src + (size_t)(y + radius + 1 < max_y ? y + radius + 1 : max_y) * (size_t)width + x0;
        const float* rem_row = src + (size_t)(y - radius > 0 ? y - radius : 0) * (size_t)width + x0;
        for (int32_t i = 0; i < s8; i += 8) {
            __m256 s = _mm256_loadu_ps(col_sum + i);
            _mm256_storeu_ps(drow + i, _mm256_mul_ps(s, v_inv));
            _mm256_storeu_ps(col_sum + i, _mm256_add_ps(s, _mm256_sub_ps(_mm256_loadu_ps(add_row + i), _mm256_loadu_ps(rem_row + i))));
        }
        for (int32_t i = s8; i < span; ++i) {
            float s = col_sum[i];
            drow[i] = s * inv_k;
            col_sum[i] = s + (add_row[i] - rem_row[i]);
        }
    }
    if (col_sum != stack_col) free(col_sum);
}

#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static void box_blur_v_slice_avx512_f32(
    const float* src, float* dst, int32_t width, int32_t height, int32_t radius, int32_t x0, int32_t x1
) {
    const int32_t span = x1 - x0;
    float stack_col[1024];
    float* col_sum = (span <= 1024) ? stack_col : (float*)malloc((size_t)span * sizeof(float));
    const int32_t max_y = height - 1;
    const __m512 v_r1 = _mm512_set1_ps((float)(radius + 1));
    const __m512 v_inv = _mm512_set1_ps(1.0f / (float)(radius * 2 + 1));
    const float inv_k = 1.0f / (float)(radius * 2 + 1);
    const int32_t s16 = span & ~15;

    const float* r0 = src + x0;
    for (int32_t i = 0; i < s16; i += 16) _mm512_storeu_ps(col_sum + i, _mm512_mul_ps(_mm512_loadu_ps(r0 + i), v_r1));
    for (int32_t i = s16; i < span; ++i) col_sum[i] = r0[i] * (float)(radius + 1);

    for (int32_t k = 1; k <= radius; ++k) {
        const float* rk = src + (size_t)(k < max_y ? k : max_y) * (size_t)width + x0;
        for (int32_t i = 0; i < s16; i += 16) _mm512_storeu_ps(col_sum + i, _mm512_add_ps(_mm512_loadu_ps(col_sum + i), _mm512_loadu_ps(rk + i)));
        for (int32_t i = s16; i < span; ++i) col_sum[i] += rk[i];
    }

    for (int32_t y = 0; y < height; ++y) {
        float* drow = dst + (size_t)y * (size_t)width + x0;
        const float* add_row = src + (size_t)(y + radius + 1 < max_y ? y + radius + 1 : max_y) * (size_t)width + x0;
        const float* rem_row = src + (size_t)(y - radius > 0 ? y - radius : 0) * (size_t)width + x0;
        for (int32_t i = 0; i < s16; i += 16) {
            __m512 s = _mm512_loadu_ps(col_sum + i);
            _mm512_storeu_ps(drow + i, _mm512_mul_ps(s, v_inv));
            _mm512_storeu_ps(col_sum + i, _mm512_add_ps(s, _mm512_sub_ps(_mm512_loadu_ps(add_row + i), _mm512_loadu_ps(rem_row + i))));
        }
        for (int32_t i = s16; i < span; ++i) {
            float s = col_sum[i];
            drow[i] = s * inv_k;
            col_sum[i] = s + (add_row[i] - rem_row[i]);
        }
    }
    if (col_sum != stack_col) free(col_sum);
}
#endif
#endif

static void box_blur_v_slice_dispatch_f32(
    const float* src, float* dst, int32_t width, int32_t height, int32_t radius, int32_t x0, int32_t x1, int simd_level
) {
#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
    if (simd_level >= FUJI_ISP_SIMD_AVX512) {
        box_blur_v_slice_avx512_f32(src, dst, width, height, radius, x0, x1);
        return;
    }
#endif
    if (simd_level >= FUJI_ISP_SIMD_AVX2) {
        box_blur_v_slice_avx2_f32(src, dst, width, height, radius, x0, x1);
        return;
    }
    if (simd_level >= FUJI_ISP_SIMD_SSE2) {
        box_blur_v_slice_sse2_f32(src, dst, width, height, radius, x0, x1);
        return;
    }
#endif
    (void)simd_level;
    box_blur_v_slice_scalar_f32(src, dst, width, height, radius, x0, x1);
}

/* Full-parallel separable RGB box blur: scales cleanly across all worker threads via
 * (channel, row-strip) for H-pass and cache-resident (channel, col-tile) for V-pass! */
typedef struct {
    const float* in[3];
    float* tmp[3];
    float* out[3];
    int32_t width, height, radius, pass_idx;
    int simd_level;
    int phase; /* 0 = horizontal, 1 = vertical */
} blur3_ctx;

static void blur3_worker(int32_t task_idx, int32_t num_tasks, void* vctx) {
    blur3_ctx* ctx = (blur3_ctx*)vctx;
    if (ctx->phase == 0) {
        /* Split 3 * height rows evenly across all num_tasks workers */
        int32_t total_rows = 3 * ctx->height;
        int32_t r0 = (int32_t)(((int64_t)task_idx * (int64_t)total_rows) / (int64_t)num_tasks);
        int32_t r1 = (int32_t)(((int64_t)(task_idx + 1) * (int64_t)total_rows) / (int64_t)num_tasks);
        while (r0 < r1) {
            int32_t ch = r0 / ctx->height;
            int32_t y0 = r0 - ch * ctx->height;
            int32_t ch_end = (ch + 1) * ctx->height;
            int32_t seg_end = (r1 < ch_end) ? r1 : ch_end;
            int32_t y1 = seg_end - ch * ctx->height;
            const float* src = (ctx->pass_idx == 0) ? ctx->in[ch] : ctx->out[ch];
            box_blur_h_rows_f32(src, ctx->tmp[ch], ctx->width, y0, y1, ctx->radius);
            r0 = seg_end;
        }
    } else {
        /* Split 3 * width columns into SIMD-aligned cache-resident tiles across all workers */
        int32_t tile_w = 256;
        int32_t tiles_per_ch = (ctx->width + tile_w - 1) / tile_w;
        int32_t total_tiles = 3 * tiles_per_ch;
        for (int32_t ti = task_idx; ti < total_tiles; ti += num_tasks) {
            int32_t ch = ti / tiles_per_ch;
            int32_t tx = ti - ch * tiles_per_ch;
            int32_t x0 = tx * tile_w;
            int32_t x1 = x0 + tile_w;
            if (x1 > ctx->width) x1 = ctx->width;
            box_blur_v_slice_dispatch_f32(ctx->tmp[ch], ctx->out[ch], ctx->width, ctx->height, ctx->radius, x0, x1, ctx->simd_level);
        }
    }
}

static void blur_rgb_separable(
    const float* in_r, const float* in_g, const float* in_b,
    float* tmp_r, float* tmp_g, float* tmp_b,
    float* out_r, float* out_g, float* out_b,
    int32_t width, int32_t height, int32_t radius, int32_t passes,
    int simd_level, int32_t req_threads
) {
    blur3_ctx ctx;
    ctx.in[0]  = in_r;  ctx.in[1]  = in_g;  ctx.in[2]  = in_b;
    ctx.tmp[0] = tmp_r; ctx.tmp[1] = tmp_g; ctx.tmp[2] = tmp_b;
    ctx.out[0] = out_r; ctx.out[1] = out_g; ctx.out[2] = out_b;
    ctx.width  = width;
    ctx.height = height;
    ctx.radius = radius;
    ctx.simd_level = simd_level;
    int32_t ntasks = ((int64_t)width * (int64_t)height >= 32768 && req_threads != 1)
        ? resolve_threads(req_threads, 3 * height, 48)
        : 1;
    for (int32_t p = 0; p < passes; ++p) {
        ctx.pass_idx = p;
        ctx.phase = 0;
        pool_parallel_run(ntasks, blur3_worker, &ctx);
        ctx.phase = 1;
        pool_parallel_run(ntasks, blur3_worker, &ctx);
    }
}

/* ---------- Black Mist Highlight Extract + Recombine (AVX-512/AVX2/SSE2/Scalar) ---------- */

static void bm_extract_scalar(
    const float* r, const float* g, const float* b,
    float* hr, float* hg, float* hb,
    int32_t start, int32_t end, float thr
) {
    const float knee = 0.25f;
    for (int32_t i = start; i < end; ++i) {
        float cr = r[i], cg = g[i], cb = b[i];
        float lum = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb;
        float excess = lum - thr;
        float w = (excess > 0.0f) ? (excess / (excess + knee)) : 0.0f;
        hr[i] = cr * w;
        hg[i] = cg * w;
        hb[i] = cb * w;
    }
}

static void bm_recombine_scalar(
    float* r, float* g, float* b,
    const float* hr, const float* hg, const float* hb,
    int32_t start, int32_t end, float gain, float lift
) {
    for (int32_t i = start; i < end; ++i) {
        r[i] = r[i] + hr[i] * gain + lift;
        g[i] = g[i] + hg[i] * gain + lift;
        b[i] = b[i] + hb[i] * gain + lift;
    }
}

#if FUJI_X86_64
static void bm_extract_sse2(
    const float* r, const float* g, const float* b,
    float* hr, float* hg, float* hb,
    int32_t start, int32_t end, float thr
) {
    const __m128 kr = _mm_set1_ps(0.2126f), kg = _mm_set1_ps(0.7152f), kb = _mm_set1_ps(0.0722f);
    const __m128 v_thr = _mm_set1_ps(thr), v_zero = _mm_setzero_ps(), v_knee = _mm_set1_ps(0.25f);
    int32_t i = start;
    for (; i + 4 <= end; i += 4) {
        __m128 cr = _mm_loadu_ps(r + i), cg = _mm_loadu_ps(g + i), cb = _mm_loadu_ps(b + i);
        __m128 lum = _mm_add_ps(_mm_add_ps(_mm_mul_ps(kr, cr), _mm_mul_ps(kg, cg)), _mm_mul_ps(kb, cb));
        __m128 ex = _mm_max_ps(v_zero, _mm_sub_ps(lum, v_thr));
        __m128 w = _mm_div_ps(ex, _mm_add_ps(ex, v_knee));
        _mm_storeu_ps(hr + i, _mm_mul_ps(cr, w));
        _mm_storeu_ps(hg + i, _mm_mul_ps(cg, w));
        _mm_storeu_ps(hb + i, _mm_mul_ps(cb, w));
    }
    bm_extract_scalar(r, g, b, hr, hg, hb, i, end, thr);
}

static void bm_recombine_sse2(
    float* r, float* g, float* b,
    const float* hr, const float* hg, const float* hb,
    int32_t start, int32_t end, float gain, float lift
) {
    const __m128 v_gain = _mm_set1_ps(gain), v_lift = _mm_set1_ps(lift);
    int32_t i = start;
    for (; i + 4 <= end; i += 4) {
        _mm_storeu_ps(r + i, _mm_add_ps(_mm_add_ps(_mm_loadu_ps(r + i), _mm_mul_ps(_mm_loadu_ps(hr + i), v_gain)), v_lift));
        _mm_storeu_ps(g + i, _mm_add_ps(_mm_add_ps(_mm_loadu_ps(g + i), _mm_mul_ps(_mm_loadu_ps(hg + i), v_gain)), v_lift));
        _mm_storeu_ps(b + i, _mm_add_ps(_mm_add_ps(_mm_loadu_ps(b + i), _mm_mul_ps(_mm_loadu_ps(hb + i), v_gain)), v_lift));
    }
    bm_recombine_scalar(r, g, b, hr, hg, hb, i, end, gain, lift);
}

FUJI_AVX2_TARGET
static void bm_extract_avx2(
    const float* r, const float* g, const float* b,
    float* hr, float* hg, float* hb,
    int32_t start, int32_t end, float thr
) {
    const __m256 kr = _mm256_set1_ps(0.2126f), kg = _mm256_set1_ps(0.7152f), kb = _mm256_set1_ps(0.0722f);
    const __m256 v_thr = _mm256_set1_ps(thr), v_zero = _mm256_setzero_ps(), v_knee = _mm256_set1_ps(0.25f);
    int32_t i = start;
    for (; i + 8 <= end; i += 8) {
        __m256 cr = _mm256_loadu_ps(r + i), cg = _mm256_loadu_ps(g + i), cb = _mm256_loadu_ps(b + i);
        __m256 lum = _mm256_fmadd_ps(kr, cr, _mm256_fmadd_ps(kg, cg, _mm256_mul_ps(kb, cb)));
        __m256 ex = _mm256_max_ps(v_zero, _mm256_sub_ps(lum, v_thr));
        __m256 w = _mm256_div_ps(ex, _mm256_add_ps(ex, v_knee));
        _mm256_storeu_ps(hr + i, _mm256_mul_ps(cr, w));
        _mm256_storeu_ps(hg + i, _mm256_mul_ps(cg, w));
        _mm256_storeu_ps(hb + i, _mm256_mul_ps(cb, w));
    }
    bm_extract_scalar(r, g, b, hr, hg, hb, i, end, thr);
}

FUJI_AVX2_TARGET
static void bm_recombine_avx2(
    float* r, float* g, float* b,
    const float* hr, const float* hg, const float* hb,
    int32_t start, int32_t end, float gain, float lift
) {
    const __m256 v_gain = _mm256_set1_ps(gain), v_lift = _mm256_set1_ps(lift);
    int32_t i = start;
    for (; i + 8 <= end; i += 8) {
        _mm256_storeu_ps(r + i, _mm256_add_ps(_mm256_fmadd_ps(_mm256_loadu_ps(hr + i), v_gain, _mm256_loadu_ps(r + i)), v_lift));
        _mm256_storeu_ps(g + i, _mm256_add_ps(_mm256_fmadd_ps(_mm256_loadu_ps(hg + i), v_gain, _mm256_loadu_ps(g + i)), v_lift));
        _mm256_storeu_ps(b + i, _mm256_add_ps(_mm256_fmadd_ps(_mm256_loadu_ps(hb + i), v_gain, _mm256_loadu_ps(b + i)), v_lift));
    }
    bm_recombine_scalar(r, g, b, hr, hg, hb, i, end, gain, lift);
}

#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static void bm_extract_avx512(
    const float* r, const float* g, const float* b,
    float* hr, float* hg, float* hb,
    int32_t start, int32_t end, float thr
) {
    const __m512 kr = _mm512_set1_ps(0.2126f), kg = _mm512_set1_ps(0.7152f), kb = _mm512_set1_ps(0.0722f);
    const __m512 v_thr = _mm512_set1_ps(thr), v_zero = _mm512_setzero_ps(), v_knee = _mm512_set1_ps(0.25f);
    int32_t i = start;
    for (; i + 16 <= end; i += 16) {
        __m512 cr = _mm512_loadu_ps(r + i), cg = _mm512_loadu_ps(g + i), cb = _mm512_loadu_ps(b + i);
        __m512 lum = _mm512_fmadd_ps(kr, cr, _mm512_fmadd_ps(kg, cg, _mm512_mul_ps(kb, cb)));
        __m512 ex = _mm512_max_ps(v_zero, _mm512_sub_ps(lum, v_thr));
        __m512 w = _mm512_div_ps(ex, _mm512_add_ps(ex, v_knee));
        _mm512_storeu_ps(hr + i, _mm512_mul_ps(cr, w));
        _mm512_storeu_ps(hg + i, _mm512_mul_ps(cg, w));
        _mm512_storeu_ps(hb + i, _mm512_mul_ps(cb, w));
    }
    bm_extract_scalar(r, g, b, hr, hg, hb, i, end, thr);
}

FUJI_AVX512_TARGET
static void bm_recombine_avx512(
    float* r, float* g, float* b,
    const float* hr, const float* hg, const float* hb,
    int32_t start, int32_t end, float gain, float lift
) {
    const __m512 v_gain = _mm512_set1_ps(gain), v_lift = _mm512_set1_ps(lift);
    int32_t i = start;
    for (; i + 16 <= end; i += 16) {
        _mm512_storeu_ps(r + i, _mm512_add_ps(_mm512_fmadd_ps(_mm512_loadu_ps(hr + i), v_gain, _mm512_loadu_ps(r + i)), v_lift));
        _mm512_storeu_ps(g + i, _mm512_add_ps(_mm512_fmadd_ps(_mm512_loadu_ps(hg + i), v_gain, _mm512_loadu_ps(g + i)), v_lift));
        _mm512_storeu_ps(b + i, _mm512_add_ps(_mm512_fmadd_ps(_mm512_loadu_ps(hb + i), v_gain, _mm512_loadu_ps(b + i)), v_lift));
    }
    bm_recombine_scalar(r, g, b, hr, hg, hb, i, end, gain, lift);
}
#endif
#endif

typedef struct {
    float *r, *g, *b;
    float *hr, *hg, *hb;
    int32_t count;
    float p0, p1;
    int simd_level;
    int mode; /* 0 = extract, 1 = recombine */
} bm_vec_ctx;

static void bm_vec_worker(int32_t task_idx, int32_t num_tasks, void* vctx) {
    bm_vec_ctx* c = (bm_vec_ctx*)vctx;
    int32_t s = (int32_t)(((int64_t)task_idx * (int64_t)c->count) / (int64_t)num_tasks);
    int32_t e = (int32_t)(((int64_t)(task_idx + 1) * (int64_t)c->count) / (int64_t)num_tasks);
    if (c->mode == 0) {
#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
        if (c->simd_level >= FUJI_ISP_SIMD_AVX512) { bm_extract_avx512(c->r, c->g, c->b, c->hr, c->hg, c->hb, s, e, c->p0); return; }
#endif
        if (c->simd_level >= FUJI_ISP_SIMD_AVX2)   { bm_extract_avx2(c->r, c->g, c->b, c->hr, c->hg, c->hb, s, e, c->p0); return; }
        if (c->simd_level >= FUJI_ISP_SIMD_SSE2)   { bm_extract_sse2(c->r, c->g, c->b, c->hr, c->hg, c->hb, s, e, c->p0); return; }
#endif
        bm_extract_scalar(c->r, c->g, c->b, c->hr, c->hg, c->hb, s, e, c->p0);
    } else {
#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
        if (c->simd_level >= FUJI_ISP_SIMD_AVX512) { bm_recombine_avx512(c->r, c->g, c->b, c->hr, c->hg, c->hb, s, e, c->p0, c->p1); return; }
#endif
        if (c->simd_level >= FUJI_ISP_SIMD_AVX2)   { bm_recombine_avx2(c->r, c->g, c->b, c->hr, c->hg, c->hb, s, e, c->p0, c->p1); return; }
        if (c->simd_level >= FUJI_ISP_SIMD_SSE2)   { bm_recombine_sse2(c->r, c->g, c->b, c->hr, c->hg, c->hb, s, e, c->p0, c->p1); return; }
#endif
        bm_recombine_scalar(c->r, c->g, c->b, c->hr, c->hg, c->hb, s, e, c->p0, c->p1);
    }
}

FUJI_ISP_SIMD_EXPORT int32_t fuji_optics_black_mist_f32(
    float* red, float* green, float* blue,
    float* hi_r, float* hi_g, float* hi_b,
    float* tmp_r, float* tmp_g, float* tmp_b,
    int32_t width, int32_t height,
    float threshold, float strength, int32_t radius, int32_t threads
) {
    if (!red || !green || !blue || !hi_r || !hi_g || !hi_b || !tmp_r || !tmp_g || !tmp_b) return -1;
    if (width < 4 || height < 4 || radius < 1) return -2;

    const int32_t count = width * height;
    const int simd_level = get_simd_level();
    const int32_t vec_tasks = resolve_threads(threads, count, 32768);

    bm_vec_ctx vc;
    vc.r = red; vc.g = green; vc.b = blue;
    vc.hr = hi_r; vc.hg = hi_g; vc.hb = hi_b;
    vc.count = count;
    vc.p0 = threshold; vc.p1 = 0.0f;
    vc.simd_level = simd_level;
    vc.mode = 0;
    pool_parallel_run(vec_tasks, bm_vec_worker, &vc);

    blur_rgb_separable(hi_r, hi_g, hi_b, tmp_r, tmp_g, tmp_b, hi_r, hi_g, hi_b, width, height, radius, 2, simd_level, threads);

    vc.p0 = strength * 0.65f;
    vc.p1 = strength * 0.008f;
    vc.mode = 1;
    pool_parallel_run(vec_tasks, bm_vec_worker, &vc);
    return 0;
}

/* ---------- UV Filter: Softness + Point-Symmetric Ghost + Veiling Glare + Tint ---------- */

static void uv_blend_scalar(
    float* r, float* g, float* b,
    const float* br, const float* bg, const float* bb,
    int32_t s, int32_t e, float inv_s, float soft
) {
    for (int32_t i = s; i < e; ++i) {
        r[i] = r[i] * inv_s + br[i] * soft;
        g[i] = g[i] * inv_s + bg[i] * soft;
        b[i] = b[i] * inv_s + bb[i] * soft;
    }
}

static void uv_ghost_add_scalar(
    float* r, float* g, float* b,
    const float* gr, const float* gg, const float* gb,
    int32_t s, int32_t e, float kR, float kG, float kB
) {
    for (int32_t i = s; i < e; ++i) {
        r[i] += gr[i] * kR;
        g[i] += gg[i] * kG;
        b[i] += gb[i] * kB;
    }
}

static void uv_glare_tint_scalar(
    float* r, float* g, float* b,
    int32_t s, int32_t e,
    float keep, float veil_r, float veil_g, float veil_b,
    float tR, float tG, float tB
) {
    for (int32_t i = s; i < e; ++i) {
        r[i] = (r[i] * keep + veil_r) * tR;
        g[i] = (g[i] * keep + veil_g) * tG;
        b[i] = (b[i] * keep + veil_b) * tB;
    }
}

#if FUJI_X86_64
FUJI_AVX2_TARGET
static void uv_blend_avx2(
    float* r, float* g, float* b,
    const float* br, const float* bg, const float* bb,
    int32_t s, int32_t e, float inv_s, float soft
) {
    const __m256 v_inv = _mm256_set1_ps(inv_s), v_s = _mm256_set1_ps(soft);
    int32_t i = s;
    for (; i + 8 <= e; i += 8) {
        _mm256_storeu_ps(r + i, _mm256_fmadd_ps(_mm256_loadu_ps(br + i), v_s, _mm256_mul_ps(_mm256_loadu_ps(r + i), v_inv)));
        _mm256_storeu_ps(g + i, _mm256_fmadd_ps(_mm256_loadu_ps(bg + i), v_s, _mm256_mul_ps(_mm256_loadu_ps(g + i), v_inv)));
        _mm256_storeu_ps(b + i, _mm256_fmadd_ps(_mm256_loadu_ps(bb + i), v_s, _mm256_mul_ps(_mm256_loadu_ps(b + i), v_inv)));
    }
    uv_blend_scalar(r, g, b, br, bg, bb, i, e, inv_s, soft);
}

FUJI_AVX2_TARGET
static void uv_ghost_add_avx2(
    float* r, float* g, float* b,
    const float* gr, const float* gg, const float* gb,
    int32_t s, int32_t e, float kR, float kG, float kB
) {
    const __m256 v_kr = _mm256_set1_ps(kR), v_kg = _mm256_set1_ps(kG), v_kb = _mm256_set1_ps(kB);
    int32_t i = s;
    for (; i + 8 <= e; i += 8) {
        _mm256_storeu_ps(r + i, _mm256_fmadd_ps(_mm256_loadu_ps(gr + i), v_kr, _mm256_loadu_ps(r + i)));
        _mm256_storeu_ps(g + i, _mm256_fmadd_ps(_mm256_loadu_ps(gg + i), v_kg, _mm256_loadu_ps(g + i)));
        _mm256_storeu_ps(b + i, _mm256_fmadd_ps(_mm256_loadu_ps(gb + i), v_kb, _mm256_loadu_ps(b + i)));
    }
    uv_ghost_add_scalar(r, g, b, gr, gg, gb, i, e, kR, kG, kB);
}

FUJI_AVX2_TARGET
static void uv_glare_tint_avx2(
    float* r, float* g, float* b,
    int32_t s, int32_t e,
    float keep, float veil_r, float veil_g, float veil_b,
    float tR, float tG, float tB
) {
    const __m256 vk = _mm256_set1_ps(keep);
    const __m256 vvr = _mm256_set1_ps(veil_r), vvg = _mm256_set1_ps(veil_g), vvb = _mm256_set1_ps(veil_b);
    const __m256 vtr = _mm256_set1_ps(tR), vtg = _mm256_set1_ps(tG), vtb = _mm256_set1_ps(tB);
    int32_t i = s;
    for (; i + 8 <= e; i += 8) {
        _mm256_storeu_ps(r + i, _mm256_mul_ps(_mm256_fmadd_ps(_mm256_loadu_ps(r + i), vk, vvr), vtr));
        _mm256_storeu_ps(g + i, _mm256_mul_ps(_mm256_fmadd_ps(_mm256_loadu_ps(g + i), vk, vvg), vtg));
        _mm256_storeu_ps(b + i, _mm256_mul_ps(_mm256_fmadd_ps(_mm256_loadu_ps(b + i), vk, vvb), vtb));
    }
    uv_glare_tint_scalar(r, g, b, i, e, keep, veil_r, veil_g, veil_b, tR, tG, tB);
}

#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static void uv_blend_avx512(
    float* r, float* g, float* b,
    const float* br, const float* bg, const float* bb,
    int32_t s, int32_t e, float inv_s, float soft
) {
    const __m512 v_inv = _mm512_set1_ps(inv_s), v_s = _mm512_set1_ps(soft);
    int32_t i = s;
    for (; i + 16 <= e; i += 16) {
        _mm512_storeu_ps(r + i, _mm512_fmadd_ps(_mm512_loadu_ps(br + i), v_s, _mm512_mul_ps(_mm512_loadu_ps(r + i), v_inv)));
        _mm512_storeu_ps(g + i, _mm512_fmadd_ps(_mm512_loadu_ps(bg + i), v_s, _mm512_mul_ps(_mm512_loadu_ps(g + i), v_inv)));
        _mm512_storeu_ps(b + i, _mm512_fmadd_ps(_mm512_loadu_ps(bb + i), v_s, _mm512_mul_ps(_mm512_loadu_ps(b + i), v_inv)));
    }
    uv_blend_scalar(r, g, b, br, bg, bb, i, e, inv_s, soft);
}

FUJI_AVX512_TARGET
static void uv_ghost_add_avx512(
    float* r, float* g, float* b,
    const float* gr, const float* gg, const float* gb,
    int32_t s, int32_t e, float kR, float kG, float kB
) {
    const __m512 v_kr = _mm512_set1_ps(kR), v_kg = _mm512_set1_ps(kG), v_kb = _mm512_set1_ps(kB);
    int32_t i = s;
    for (; i + 16 <= e; i += 16) {
        _mm512_storeu_ps(r + i, _mm512_fmadd_ps(_mm512_loadu_ps(gr + i), v_kr, _mm512_loadu_ps(r + i)));
        _mm512_storeu_ps(g + i, _mm512_fmadd_ps(_mm512_loadu_ps(gg + i), v_kg, _mm512_loadu_ps(g + i)));
        _mm512_storeu_ps(b + i, _mm512_fmadd_ps(_mm512_loadu_ps(gb + i), v_kb, _mm512_loadu_ps(b + i)));
    }
    uv_ghost_add_scalar(r, g, b, gr, gg, gb, i, e, kR, kG, kB);
}

FUJI_AVX512_TARGET
static void uv_glare_tint_avx512(
    float* r, float* g, float* b,
    int32_t s, int32_t e,
    float keep, float veil_r, float veil_g, float veil_b,
    float tR, float tG, float tB
) {
    const __m512 vk = _mm512_set1_ps(keep);
    const __m512 vvr = _mm512_set1_ps(veil_r), vvg = _mm512_set1_ps(veil_g), vvb = _mm512_set1_ps(veil_b);
    const __m512 vtr = _mm512_set1_ps(tR), vtg = _mm512_set1_ps(tG), vtb = _mm512_set1_ps(tB);
    int32_t i = s;
    for (; i + 16 <= e; i += 16) {
        _mm512_storeu_ps(r + i, _mm512_mul_ps(_mm512_fmadd_ps(_mm512_loadu_ps(r + i), vk, vvr), vtr));
        _mm512_storeu_ps(g + i, _mm512_mul_ps(_mm512_fmadd_ps(_mm512_loadu_ps(g + i), vk, vvg), vtg));
        _mm512_storeu_ps(b + i, _mm512_mul_ps(_mm512_fmadd_ps(_mm512_loadu_ps(b + i), vk, vvb), vtb));
    }
    uv_glare_tint_scalar(r, g, b, i, e, keep, veil_r, veil_g, veil_b, tR, tG, tB);
}
#endif
#endif

typedef struct {
    float *r, *g, *b;
    float *sr, *sg, *sb;
    int32_t width, height, count;
    float f0, f1, f2, f3, f4, f5, f6;
    int simd_level;
    int mode; /* 0=blend, 1=ghost_extract, 2=ghost_add, 3=sum_rgb, 4=glare_tint */
    int any_spec[FUJI_ISP_MAX_THREADS];
    double sum_r[FUJI_ISP_MAX_THREADS];
    double sum_g[FUJI_ISP_MAX_THREADS];
    double sum_b[FUJI_ISP_MAX_THREADS];
} uv_vec_ctx;

#if FUJI_X86_64
FUJI_AVX2_TARGET
static int uv_ghost_extract_avx2(
    const float* r, const float* g, const float* b,
    float* sr, float* sg, float* sb,
    int32_t s, int32_t e, int32_t last
) {
    const float thr = 0.45f;
    const __m256 kr = _mm256_set1_ps(0.2126f), kg = _mm256_set1_ps(0.7152f), kb = _mm256_set1_ps(0.0722f);
    const __m256 v_thr = _mm256_set1_ps(thr), v_zero = _mm256_setzero_ps();
    int found = 0;
    int32_t i = s;
    for (; i + 8 <= e; i += 8) {
        __m256 cr = _mm256_loadu_ps(r + i), cg = _mm256_loadu_ps(g + i), cb = _mm256_loadu_ps(b + i);
        __m256 lum = _mm256_fmadd_ps(kr, cr, _mm256_fmadd_ps(kg, cg, _mm256_mul_ps(kb, cb)));
        int mask = _mm256_movemask_ps(_mm256_cmp_ps(lum, v_thr, _CMP_GT_OQ));
        if (mask == 0) {
            int32_t d0 = last - (i + 7);
            _mm256_storeu_ps(sr + d0, v_zero);
            _mm256_storeu_ps(sg + d0, v_zero);
            _mm256_storeu_ps(sb + d0, v_zero);
            continue;
        }
        for (int32_t k = 0; k < 8; ++k) {
            int32_t idx = i + k;
            int32_t d = last - idx;
            float r0 = r[idx], g0 = g[idx], b0 = b[idx];
            float l0 = 0.2126f * r0 + 0.7152f * g0 + 0.0722f * b0;
            if (l0 > thr) {
                float denom = (l0 > 1e-4f) ? l0 : 1e-4f;
                float ex = (l0 - thr) / denom;
                sr[d] = r0 * ex; sg[d] = g0 * ex; sb[d] = b0 * ex;
                found = 1;
            } else {
                sr[d] = 0.0f; sg[d] = 0.0f; sb[d] = 0.0f;
            }
        }
    }
    for (; i < e; ++i) {
        float cr = r[i], cg = g[i], cb = b[i];
        float lum = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb;
        int32_t d = last - i;
        if (lum > thr) {
            float denom = (lum > 1e-4f) ? lum : 1e-4f;
            float ex = (lum - thr) / denom;
            sr[d] = cr * ex; sg[d] = cg * ex; sb[d] = cb * ex;
            found = 1;
        } else {
            sr[d] = 0.0f; sg[d] = 0.0f; sb[d] = 0.0f;
        }
    }
    return found;
}
#endif

static void uv_vec_worker(int32_t task_idx, int32_t num_tasks, void* vctx) {
    uv_vec_ctx* c = (uv_vec_ctx*)vctx;
    int32_t s = (int32_t)(((int64_t)task_idx * (int64_t)c->count) / (int64_t)num_tasks);
    int32_t e = (int32_t)(((int64_t)(task_idx + 1) * (int64_t)c->count) / (int64_t)num_tasks);
    if (c->mode == 0) {
#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
        if (c->simd_level >= FUJI_ISP_SIMD_AVX512) { uv_blend_avx512(c->r, c->g, c->b, c->sr, c->sg, c->sb, s, e, c->f0, c->f1); return; }
#endif
        if (c->simd_level >= FUJI_ISP_SIMD_AVX2)   { uv_blend_avx2(c->r, c->g, c->b, c->sr, c->sg, c->sb, s, e, c->f0, c->f1); return; }
#endif
        uv_blend_scalar(c->r, c->g, c->b, c->sr, c->sg, c->sb, s, e, c->f0, c->f1);
    } else if (c->mode == 1) {
        /* Point-symmetric ghost extract: dst_idx = count - 1 - src_idx (exact for (h-1-y)*w + (w-1-x)!) */
        const int32_t last = c->count - 1;
#if FUJI_X86_64
        if (c->simd_level >= FUJI_ISP_SIMD_AVX2) {
            c->any_spec[task_idx] = uv_ghost_extract_avx2(c->r, c->g, c->b, c->sr, c->sg, c->sb, s, e, last);
            return;
        }
#endif
        const float thr = 0.45f;
        int found = 0;
        for (int32_t i = s; i < e; ++i) {
            float cr = c->r[i], cg = c->g[i], cb = c->b[i];
            float lum = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb;
            int32_t d = last - i;
            if (lum > thr) {
                float denom = (lum > 1e-4f) ? lum : 1e-4f;
                float ex = (lum - thr) / denom;
                c->sr[d] = cr * ex;
                c->sg[d] = cg * ex;
                c->sb[d] = cb * ex;
                found = 1;
            } else {
                c->sr[d] = 0.0f;
                c->sg[d] = 0.0f;
                c->sb[d] = 0.0f;
            }
        }
        c->any_spec[task_idx] = found;
    } else if (c->mode == 2) {
#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
        if (c->simd_level >= FUJI_ISP_SIMD_AVX512) { uv_ghost_add_avx512(c->r, c->g, c->b, c->sr, c->sg, c->sb, s, e, c->f0, c->f1, c->f2); return; }
#endif
        if (c->simd_level >= FUJI_ISP_SIMD_AVX2)   { uv_ghost_add_avx2(c->r, c->g, c->b, c->sr, c->sg, c->sb, s, e, c->f0, c->f1, c->f2); return; }
#endif
        uv_ghost_add_scalar(c->r, c->g, c->b, c->sr, c->sg, c->sb, s, e, c->f0, c->f1, c->f2);
    } else if (c->mode == 3) {
        double sr = 0.0, sg = 0.0, sb = 0.0;
        for (int32_t i = s; i < e; ++i) {
            sr += (double)c->r[i];
            sg += (double)c->g[i];
            sb += (double)c->b[i];
        }
        c->sum_r[task_idx] = sr;
        c->sum_g[task_idx] = sg;
        c->sum_b[task_idx] = sb;
    } else if (c->mode == 4) {
#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
        if (c->simd_level >= FUJI_ISP_SIMD_AVX512) { uv_glare_tint_avx512(c->r, c->g, c->b, s, e, c->f0, c->f1, c->f2, c->f3, c->f4, c->f5, c->f6); return; }
#endif
        if (c->simd_level >= FUJI_ISP_SIMD_AVX2)   { uv_glare_tint_avx2(c->r, c->g, c->b, s, e, c->f0, c->f1, c->f2, c->f3, c->f4, c->f5, c->f6); return; }
#endif
        uv_glare_tint_scalar(c->r, c->g, c->b, s, e, c->f0, c->f1, c->f2, c->f3, c->f4, c->f5, c->f6);
    }
}

FUJI_ISP_SIMD_EXPORT int32_t fuji_optics_uv_filter_f32(
    float* red, float* green, float* blue,
    float* scratch_r, float* scratch_g, float* scratch_b,
    float* aux_r, float* aux_g, float* aux_b,
    int32_t width, int32_t height,
    float softness, float ghost_intensity, float veiling_glare,
    float tint_r, float tint_g, float tint_b,
    int32_t has_tint, int32_t threads
) {
    if (!red || !green || !blue || !scratch_r || !scratch_g || !scratch_b || !aux_r || !aux_g || !aux_b) return -1;
    if (width < 4 || height < 4) return -2;

    const int32_t count = width * height;
    const int simd_level = get_simd_level();
    const int32_t vec_tasks = resolve_threads(threads, count, 32768);

    uv_vec_ctx vc;
    memset(&vc, 0, sizeof(vc));
    vc.r = red; vc.g = green; vc.b = blue;
    vc.sr = scratch_r; vc.sg = scratch_g; vc.sb = scratch_b;
    vc.width = width; vc.height = height; vc.count = count;
    vc.simd_level = simd_level;

    /* 1. Micro-softness MTF degradation */
    if ((double)softness > 1e-4) {
        int32_t blur_radius = ((double)softness >= 0.25) ? 2 : 1;
        blur_rgb_separable(red, green, blue, aux_r, aux_g, aux_b, scratch_r, scratch_g, scratch_b, width, height, blur_radius, 1, simd_level, threads);
        vc.f0 = 1.0f - softness;
        vc.f1 = softness;
        vc.mode = 0;
        pool_parallel_run(vec_tasks, uv_vec_worker, &vc);
    }

    /* 2. Point-symmetric internal double-reflection chromatic ghosting */
    if ((double)ghost_intensity > 1e-4) {
        vc.mode = 1;
        pool_parallel_run(vec_tasks, uv_vec_worker, &vc);
        int any_spec = 0;
        for (int32_t t = 0; t < vec_tasks; ++t) any_spec |= vc.any_spec[t];
        if (any_spec) {
            int32_t min_dim = (width < height) ? width : height;
            int32_t ghost_radius = clamp_i32(min_dim / 32, 2, 16);
            blur_rgb_separable(scratch_r, scratch_g, scratch_b, aux_r, aux_g, aux_b, scratch_r, scratch_g, scratch_b, width, height, ghost_radius, 1, simd_level, threads);
            float gain = (float)((double)ghost_intensity * 2.4);
            vc.f0 = gain * 0.90f;
            vc.f1 = gain * 1.24f;
            vc.f2 = gain * 1.06f;
            vc.mode = 2;
            pool_parallel_run(vec_tasks, uv_vec_worker, &vc);
        }
    }

    /* 3. Veiling glare / stray-light contrast washout + glass chromatic cast */
    if ((double)veiling_glare > 1e-4 || has_tint) {
        double sum_r = 0.0, sum_g = 0.0, sum_b = 0.0;
        if ((double)veiling_glare > 1e-4) {
            vc.mode = 3;
            pool_parallel_run(vec_tasks, uv_vec_worker, &vc);
            for (int32_t t = 0; t < vec_tasks; ++t) {
                sum_r += vc.sum_r[t];
                sum_g += vc.sum_g[t];
                sum_b += vc.sum_b[t];
            }
        }
        double inv_n = 1.0 / (double)count;
        float keep = 1.0f - 0.28f * veiling_glare;
        if (keep < 0.55f) keep = 0.55f;
        float veil_r = (float)(sum_r * inv_n * (double)veiling_glare * 0.50);
        float veil_g = (float)(sum_g * inv_n * (double)veiling_glare * 0.50);
        float veil_b = (float)(sum_b * inv_n * (double)veiling_glare * 0.50);

        double luma_norm = 0.2126 * (double)tint_r + 0.7152 * (double)tint_g + 0.0722 * (double)tint_b;
        if (luma_norm < 0.1) luma_norm = 0.1;
        float tR = has_tint ? (float)((double)tint_r / luma_norm) : 1.0f;
        float tG = has_tint ? (float)((double)tint_g / luma_norm) : 1.0f;
        float tB = has_tint ? (float)((double)tint_b / luma_norm) : 1.0f;

        vc.f0 = keep;
        vc.f1 = veil_r; vc.f2 = veil_g; vc.f3 = veil_b;
        vc.f4 = tR;     vc.f5 = tG;     vc.f6 = tB;
        vc.mode = 4;
        pool_parallel_run(vec_tasks, uv_vec_worker, &vc);
    }
    return 0;
}

/* ---------- Star Cross-Screen (4/6/8-pt Diffraction Streaks) ---------- */

#define FUJI_STAR_MAX_HOT 2400
#define FUJI_STAR_MAX_RAYS 4
#define FUJI_STAR_MAX_LEN 180

typedef struct {
    int32_t x, y;
    float sr, sg, sb;
} star_hot_px;

typedef struct {
    double dx_step[FUJI_STAR_MAX_RAYS][FUJI_STAR_MAX_LEN + 1];
    double dy_step[FUJI_STAR_MAX_RAYS][FUJI_STAR_MAX_LEN + 1];
    float decay[FUJI_STAR_MAX_LEN + 1];
    star_hot_px hots[FUJI_STAR_MAX_HOT];
    int32_t hot_count;
    int32_t ray_count;
    int32_t max_len;
    int32_t width, height;
    float *r, *g, *b;
    float *acc_r, *acc_g, *acc_b;
    int simd_level;
} star_ctx;

static int32_t star_scan_hot_pixels(
    const float* red, const float* green, const float* blue,
    int32_t width, int32_t height,
    float thr, float gain, int simd_level,
    star_hot_px* out_hots
) {
    int32_t hot_count = 0;
    for (int32_t y = 0; y < height && hot_count < FUJI_STAR_MAX_HOT; ++y) {
        const size_t row = (size_t)y * (size_t)width;
        const float* rr = red + row;
        const float* gg = green + row;
        const float* bb = blue + row;
        int32_t x = 0;

#if FUJI_X86_64
        if (simd_level >= FUJI_ISP_SIMD_SSE2) {
            const __m128 kr = _mm_set1_ps(0.2126f), kg = _mm_set1_ps(0.7152f), kb = _mm_set1_ps(0.0722f);
            const __m128 v_thr = _mm_set1_ps(thr);
            for (; x + 4 <= width && hot_count < FUJI_STAR_MAX_HOT; x += 4) {
                __m128 lum = _mm_add_ps(_mm_add_ps(_mm_mul_ps(kr, _mm_loadu_ps(rr + x)), _mm_mul_ps(kg, _mm_loadu_ps(gg + x))), _mm_mul_ps(kb, _mm_loadu_ps(bb + x)));
                int mask = _mm_movemask_ps(_mm_cmpgt_ps(lum, v_thr));
                if (mask == 0) continue;
                for (int k = 0; k < 4 && hot_count < FUJI_STAR_MAX_HOT; ++k) {
                    if ((mask & (1 << k)) == 0) continue;
                    int32_t px = x + k;
                    if (hot_count > 400 && (((px ^ y) & 1) != 0)) continue;
                    float cr = rr[px], cg = gg[px], cb = bb[px];
                    float l = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb;
                    if (l <= thr) continue;
                    float denom = (l > 1e-4f) ? l : 1e-4f;
                    float coeff = ((l - thr) / denom) * gain;
                    out_hots[hot_count].x = px;
                    out_hots[hot_count].y = y;
                    out_hots[hot_count].sr = cr * coeff;
                    out_hots[hot_count].sg = cg * coeff;
                    out_hots[hot_count].sb = cb * coeff;
                    hot_count++;
                }
            }
        }
#else
        (void)simd_level;
#endif
        for (; x < width && hot_count < FUJI_STAR_MAX_HOT; ++x) {
            float cr = rr[x], cg = gg[x], cb = bb[x];
            float l = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb;
            if (l <= thr) continue;
            if (hot_count > 400 && (((x ^ y) & 1) != 0)) continue;
            float denom = (l > 1e-4f) ? l : 1e-4f;
            float coeff = ((l - thr) / denom) * gain;
            out_hots[hot_count].x = x;
            out_hots[hot_count].y = y;
            out_hots[hot_count].sr = cr * coeff;
            out_hots[hot_count].sg = cg * coeff;
            out_hots[hot_count].sb = cb * coeff;
            hot_count++;
        }
    }
    return hot_count;
}

static void star_add_scalar(float* r, float* g, float* b, const float* ar, const float* ag, const float* ab, int32_t s, int32_t e) {
    for (int32_t i = s; i < e; ++i) {
        r[i] += ar[i];
        g[i] += ag[i];
        b[i] += ab[i];
    }
}

#if FUJI_X86_64
FUJI_AVX2_TARGET
static void star_add_avx2(float* r, float* g, float* b, const float* ar, const float* ag, const float* ab, int32_t s, int32_t e) {
    int32_t i = s;
    for (; i + 8 <= e; i += 8) {
        _mm256_storeu_ps(r + i, _mm256_add_ps(_mm256_loadu_ps(r + i), _mm256_loadu_ps(ar + i)));
        _mm256_storeu_ps(g + i, _mm256_add_ps(_mm256_loadu_ps(g + i), _mm256_loadu_ps(ag + i)));
        _mm256_storeu_ps(b + i, _mm256_add_ps(_mm256_loadu_ps(b + i), _mm256_loadu_ps(ab + i)));
    }
    star_add_scalar(r, g, b, ar, ag, ab, i, e);
}

#if FUJI_HAS_AVX512_TARGET
FUJI_AVX512_TARGET
static void star_add_avx512(float* r, float* g, float* b, const float* ar, const float* ag, const float* ab, int32_t s, int32_t e) {
    int32_t i = s;
    for (; i + 16 <= e; i += 16) {
        _mm512_storeu_ps(r + i, _mm512_add_ps(_mm512_loadu_ps(r + i), _mm512_loadu_ps(ar + i)));
        _mm512_storeu_ps(g + i, _mm512_add_ps(_mm512_loadu_ps(g + i), _mm512_loadu_ps(ag + i)));
        _mm512_storeu_ps(b + i, _mm512_add_ps(_mm512_loadu_ps(b + i), _mm512_loadu_ps(ab + i)));
    }
    star_add_scalar(r, g, b, ar, ag, ab, i, e);
}
#endif
#endif

static void star_band_worker(int32_t task_idx, int32_t num_tasks, void* vctx) {
    star_ctx* c = (star_ctx*)vctx;
    const int32_t w = c->width;
    const int32_t h = c->height;
    const int32_t y0 = (int32_t)(((int64_t)task_idx * (int64_t)h) / (int64_t)num_tasks);
    const int32_t y1 = (int32_t)(((int64_t)(task_idx + 1) * (int64_t)h) / (int64_t)num_tasks);
    if (y0 >= y1) return;

    const int32_t start_px = y0 * w;
    const int32_t end_px   = y1 * w;
    const size_t band_bytes = (size_t)(end_px - start_px) * sizeof(float);
    memset(c->acc_r + start_px, 0, band_bytes);
    memset(c->acc_g + start_px, 0, band_bytes);
    memset(c->acc_b + start_px, 0, band_bytes);

    const int32_t max_len = c->max_len;
    const int32_t ray_count = c->ray_count;
    float* ar = c->acc_r;
    float* ag = c->acc_g;
    float* ab = c->acc_b;

    for (int32_t i = 0; i < c->hot_count; ++i) {
        const star_hot_px* hp = &c->hots[i];
        const int32_t hy = hp->y;
        if (hy + max_len + 1 < y0 || hy - max_len - 1 >= y1) continue;
        const int32_t hx = hp->x;
        const float sr = hp->sr, sg = hp->sg, sb = hp->sb;

        const double dhx = (double)hx;
        const double dhy = (double)hy;
        for (int32_t k = 0; k < ray_count; ++k) {
            const double* dxs = c->dx_step[k];
            const double* dys = c->dy_step[k];
            for (int32_t step = 1; step <= max_len; ++step) {
                const float wt = c->decay[step];
                const float wr = sr * wt;
                const float wg = sg * wt;
                const float wb = sb * wt;
                const double dxv = dxs[step];
                const double dyv = dys[step];

                int32_t px1 = (int32_t)floor((dhx + dxv) + 0.5);
                int32_t py1 = (int32_t)floor((dhy + dyv) + 0.5);
                if (px1 >= 0 && px1 < w && py1 >= y0 && py1 < y1) {
                    int32_t d1 = py1 * w + px1;
                    ar[d1] += wr;
                    ag[d1] += wg;
                    ab[d1] += wb;
                }
                int32_t px2 = (int32_t)floor((dhx - dxv) + 0.5);
                int32_t py2 = (int32_t)floor((dhy - dyv) + 0.5);
                if (px2 >= 0 && px2 < w && py2 >= y0 && py2 < y1) {
                    int32_t d2 = py2 * w + px2;
                    ar[d2] += wr;
                    ag[d2] += wg;
                    ab[d2] += wb;
                }
            }
        }
    }

#if FUJI_X86_64
#if FUJI_HAS_AVX512_TARGET
    if (c->simd_level >= FUJI_ISP_SIMD_AVX512) {
        star_add_avx512(c->r, c->g, c->b, ar, ag, ab, start_px, end_px);
        return;
    }
#endif
    if (c->simd_level >= FUJI_ISP_SIMD_AVX2) {
        star_add_avx2(c->r, c->g, c->b, ar, ag, ab, start_px, end_px);
        return;
    }
#endif
    star_add_scalar(c->r, c->g, c->b, ar, ag, ab, start_px, end_px);
}

FUJI_ISP_SIMD_EXPORT int32_t fuji_optics_star_filter_f32(
    float* red, float* green, float* blue,
    float* acc_r, float* acc_g, float* acc_b,
    int32_t width, int32_t height,
    int32_t points, float strength, float threshold, float length_frac,
    double angle_deg, int32_t threads
) {
    if (!red || !green || !blue || !acc_r || !acc_g || !acc_b) return -1;
    if (width < 4 || height < 4) return -2;

    int32_t clamped_pts = (points == 4 || points == 6 || points == 8) ? points : 4;
    int32_t ray_count = clamped_pts / 2;
    int32_t min_dim = (width < height) ? width : height;
    int32_t max_len = clamp_i32((int32_t)floor((double)min_dim * (double)length_frac + 0.5), 4, FUJI_STAR_MAX_LEN);
    float gain = (float)((double)strength * 0.28 / (double)ray_count);

    star_ctx* c = (star_ctx*)malloc(sizeof(star_ctx));
    if (c == NULL) return -3;
    c->simd_level = get_simd_level();
    c->hot_count = star_scan_hot_pixels(red, green, blue, width, height, threshold, gain, c->simd_level, c->hots);
    if (c->hot_count <= 0) {
        free(c);
        return 0;
    }

    const double base_angle = angle_deg * (FUJI_PI / 180.0);
    const double angle_step = FUJI_PI / (double)ray_count;
    for (int32_t k = 0; k < ray_count; ++k) {
        double dx = cos(base_angle + (double)k * angle_step);
        double dy = sin(base_angle + (double)k * angle_step);
        for (int32_t step = 1; step <= max_len; ++step) {
            c->dx_step[k][step] = dx * (double)step;
            c->dy_step[k][step] = dy * (double)step;
        }
    }
    for (int32_t step = 0; step <= max_len; ++step) {
        double t = (double)step / (double)max_len;
        c->decay[step] = (float)((1.0 - t) * exp(-2.6 * t));
    }

    c->ray_count = ray_count;
    c->max_len   = max_len;
    c->width     = width;
    c->height    = height;
    c->r = red; c->g = green; c->b = blue;
    c->acc_r = acc_r; c->acc_g = acc_g; c->acc_b = acc_b;

    int32_t num_tasks = (c->hot_count >= 32 && height >= 64)
        ? resolve_threads(threads, height, 64)
        : 1;
    pool_parallel_run(num_tasks, star_band_worker, c);

    int32_t hot_count = c->hot_count;
    free(c);
    return hot_count;
}
