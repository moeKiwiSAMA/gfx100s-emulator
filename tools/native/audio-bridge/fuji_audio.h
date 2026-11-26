#ifndef FUJI_AUDIO_H
#define FUJI_AUDIO_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32) || defined(__CYGWIN__)
  #if defined(FUJI_AUDIO_BUILD_DLL)
    #define FUJI_AUDIO_EXPORT __declspec(dllexport)
  #else
    #define FUJI_AUDIO_EXPORT
  #endif
#else
  #define FUJI_AUDIO_EXPORT __attribute__((visibility("default")))
#endif

#define FUJI_AUDIO_ABI_VERSION 1

#define FUJI_AUDIO_DIR_OUTPUT   0
#define FUJI_AUDIO_DIR_INPUT    1
#define FUJI_AUDIO_DIR_LOOPBACK 2

typedef struct FujiAudioDeviceInfo {
    char    id[256];        /* offset 0x000: UTF-8 device identifier */
    char    name[256];      /* offset 0x100: UTF-8 human-readable name */
    int32_t is_default;     /* offset 0x200: 1 if system default for this direction, else 0 */
    int32_t sample_rate_hz; /* offset 0x204: preferred/native sample rate in Hz */
    int32_t channels;       /* offset 0x208: preferred/native channel count */
} FujiAudioDeviceInfo;      /* total: 524 bytes (0x20C) */

#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(FujiAudioDeviceInfo) == 524, "FujiAudioDeviceInfo must be 524 bytes");
#endif

FUJI_AUDIO_EXPORT int32_t     fuji_audio_abi_version(void);
FUJI_AUDIO_EXPORT const char* fuji_audio_backend_name(void);
FUJI_AUDIO_EXPORT int32_t     fuji_audio_enumerate(int32_t direction, FujiAudioDeviceInfo* out, int32_t max_count);
FUJI_AUDIO_EXPORT void*       fuji_audio_open_output(const char* device_id, int32_t sample_rate_hz, int32_t channels, int32_t target_latency_ms);
FUJI_AUDIO_EXPORT void*       fuji_audio_open_input(const char* device_id, int32_t loopback, int32_t sample_rate_hz, int32_t channels, int32_t target_latency_ms);
FUJI_AUDIO_EXPORT int32_t     fuji_audio_write(void* handle, const int16_t* interleaved_pcm, int32_t frames);
FUJI_AUDIO_EXPORT int32_t     fuji_audio_read(void* handle, int16_t* interleaved_pcm, int32_t max_frames, int32_t timeout_ms);
FUJI_AUDIO_EXPORT int32_t     fuji_audio_get_latency_ms(void* handle);
FUJI_AUDIO_EXPORT void        fuji_audio_close(void* handle);

/* --------------------------------------------------------------------------
 * OS-Native High-Resolution Timer, Scheduler & Memory Tuning C ABI (v1)
 * Shipped inside libfuji_audio.so / fuji_audio.dll with zero extra deps.
 * -------------------------------------------------------------------------- */
#define FUJI_OS_ABI_VERSION 1

#define FUJI_OS_CAP_HIGHRES_TIMER    (1 << 0) /* Win: WaitableTimer HIGH_RES / Linux: clock_nanosleep */
#define FUJI_OS_CAP_TIMER_PERIOD_1MS (1 << 1) /* Win: timeBeginPeriod(1) / Linux: PR_SET_TIMERSLACK=1 */
#define FUJI_OS_CAP_ANTI_ECOQOS      (1 << 2) /* Win: ProcessPowerThrottling / ThreadPowerThrottling */
#define FUJI_OS_CAP_THREAD_PRIORITY  (1 << 3) /* Win: SetThreadPriority / Linux: timerslack=1 */
#define FUJI_OS_CAP_MADV_HUGEPAGE    (1 << 4) /* Linux: madvise(MADV_HUGEPAGE) */

#define FUJI_OS_ROLE_OWNER_LOOP 0
#define FUJI_OS_ROLE_AUDIO_IO   1

FUJI_AUDIO_EXPORT int32_t fuji_os_abi_version(void);
FUJI_AUDIO_EXPORT int32_t fuji_os_capabilities(void);
FUJI_AUDIO_EXPORT int64_t fuji_os_monotonic_nanos(void);
FUJI_AUDIO_EXPORT int32_t fuji_os_tune_process(int32_t enable_high_perf);
FUJI_AUDIO_EXPORT int32_t fuji_os_tune_current_thread(int32_t role);
FUJI_AUDIO_EXPORT int32_t fuji_os_acquire_timer_period(int32_t period_ms);
FUJI_AUDIO_EXPORT int32_t fuji_os_release_timer_period(int32_t period_ms);
FUJI_AUDIO_EXPORT int32_t fuji_os_sleep_nanos(int64_t duration_ns);
FUJI_AUDIO_EXPORT void*   fuji_os_waiter_create(void);
FUJI_AUDIO_EXPORT void    fuji_os_waiter_reset(void* handle);
FUJI_AUDIO_EXPORT int32_t fuji_os_waiter_wait_nanos(void* handle, int64_t duration_ns);
FUJI_AUDIO_EXPORT void    fuji_os_waiter_wake(void* handle);
FUJI_AUDIO_EXPORT void    fuji_os_waiter_destroy(void* handle);
FUJI_AUDIO_EXPORT int32_t fuji_os_advise_hugepage(void* addr, uint64_t length);

#ifdef __cplusplus
}
#endif

#endif /* FUJI_AUDIO_H */
