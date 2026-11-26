#include "fuji_audio.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <pthread.h>
#include <sys/mman.h>
#endif

#define CHECK(cond, msg)                                                      \
    do {                                                                      \
        if (!(cond)) {                                                        \
            fprintf(stderr, "[FAIL] %s:%d: %s\n", __FILE__, __LINE__, (msg)); \
            exit(1);                                                          \
        }                                                                     \
    } while (0)

typedef struct WakeArgs {
    void* waiter;
    int64_t delay_ns;
} WakeArgs;

#ifdef _WIN32
static DWORD WINAPI wake_thread_fn(LPVOID arg) {
    WakeArgs* a = (WakeArgs*)arg;
    fuji_os_sleep_nanos(a->delay_ns);
    fuji_os_waiter_wake(a->waiter);
    return 0;
}
#else
static void* wake_thread_fn(void* arg) {
    WakeArgs* a = (WakeArgs*)arg;
    fuji_os_sleep_nanos(a->delay_ns);
    fuji_os_waiter_wake(a->waiter);
    return NULL;
}
#endif

int main(void) {
    printf("[AUDIO-BRIDGE] Running standalone C ABI v1 + OS-Native v1 self-test...\n");

    CHECK(fuji_audio_abi_version() == FUJI_AUDIO_ABI_VERSION, "ABI version mismatch");
    CHECK(sizeof(FujiAudioDeviceInfo) == 524, "FujiAudioDeviceInfo size != 524");

    const char* backend = fuji_audio_backend_name();
    CHECK(backend != NULL && strlen(backend) > 0, "backend name empty");
#ifdef _WIN32
    CHECK(strcmp(backend, "wasapi") == 0, "expected wasapi backend on Win32");
#else
    CHECK(strcmp(backend, "pulse-pipewire") == 0, "expected pulse-pipewire backend on Linux");
#endif
    printf("  [PASS] 1. ABI v%d + backend='%s' + struct size=%zu B\n",
           fuji_audio_abi_version(), backend, sizeof(FujiAudioDeviceInfo));

    /* Enumeration must succeed gracefully (>=0) even on headless CI without audio daemon */
    FujiAudioDeviceInfo devs[16];
    int32_t n_out = fuji_audio_enumerate(FUJI_AUDIO_DIR_OUTPUT, devs, 16);
    int32_t n_in = fuji_audio_enumerate(FUJI_AUDIO_DIR_INPUT, devs, 16);
    int32_t n_lb = fuji_audio_enumerate(FUJI_AUDIO_DIR_LOOPBACK, devs, 16);
    CHECK(n_out >= 0 && n_in >= 0 && n_lb >= 0, "enumeration returned error");
    CHECK(fuji_audio_enumerate(-1, devs, 16) < 0, "invalid direction -1 must fail");
    CHECK(fuji_audio_enumerate(99, devs, 16) < 0, "invalid direction 99 must fail");
    CHECK(fuji_audio_enumerate(FUJI_AUDIO_DIR_OUTPUT, NULL, 16) == 0, "null buffer must return 0");
    printf("  [PASS] 2. Enumeration graceful (outputs=%d, inputs=%d, loopbacks=%d)\n",
           n_out, n_in, n_lb);

    /* Null & invalid-argument safety */
    int16_t pcm[64];
    memset(pcm, 0, sizeof(pcm));
    CHECK(fuji_audio_open_output(NULL, 0, 2, 10) == NULL, "invalid sample rate 0 must fail");
    CHECK(fuji_audio_open_output(NULL, 48000, 0, 10) == NULL, "invalid channels 0 must fail");
    CHECK(fuji_audio_open_input(NULL, 1, 48000, 9, 10) == NULL, "invalid channels 9 must fail");
    CHECK(fuji_audio_write(NULL, pcm, 32) < 0, "write(NULL) must fail");
    CHECK(fuji_audio_read(NULL, pcm, 32, 5) < 0, "read(NULL) must fail");
    CHECK(fuji_audio_get_latency_ms(NULL) < 0, "get_latency_ms(NULL) must fail");
    fuji_audio_close(NULL); /* must be safe no-op */
    printf("  [PASS] 3. Null handle & invalid-argument guard checks\n");

    /* 4. OS-Native timer, scheduler & process/thread tuning */
    CHECK(fuji_os_abi_version() == FUJI_OS_ABI_VERSION, "OS ABI version mismatch");
    int32_t caps = fuji_os_capabilities();
    CHECK(caps > 0, "OS capabilities must be non-zero");
    int32_t proc_flags = fuji_os_tune_process(1);
    CHECK(proc_flags >= 0, "fuji_os_tune_process failed");
    int32_t thr_owner = fuji_os_tune_current_thread(FUJI_OS_ROLE_OWNER_LOOP);
    int32_t thr_audio = fuji_os_tune_current_thread(FUJI_OS_ROLE_AUDIO_IO);
    CHECK(thr_owner >= 0 && thr_audio >= 0, "fuji_os_tune_current_thread failed");
    CHECK(fuji_os_acquire_timer_period(0) < 0, "period 0 must fail");
    CHECK(fuji_os_acquire_timer_period(16) < 0, "period 16 must fail");
    CHECK(fuji_os_acquire_timer_period(1) == 0, "acquire_timer_period(1) failed");
    CHECK(fuji_os_release_timer_period(1) == 0, "release_timer_period(1) failed");
    printf("  [PASS] 4. OS ABI v%d caps=0x%x proc=0x%x thr_owner=0x%x thr_audio=0x%x\n",
           fuji_os_abi_version(), caps, proc_flags, thr_owner, thr_audio);

    /* 5. High-resolution monotonic clock & sleep_nanos accuracy (15 x 2 ms) */
    const int64_t target_ns = 2000000LL;
    int64_t overshoots[15];
    int64_t sum_overshoot_ns = 0;
    const int iters = 15;
    for (int i = 0; i < iters; ++i) {
        int64_t t0 = fuji_os_monotonic_nanos();
        CHECK(fuji_os_sleep_nanos(target_ns) == 0, "fuji_os_sleep_nanos failed");
        int64_t t1 = fuji_os_monotonic_nanos();
        CHECK(t1 >= t0, "monotonic clock went backwards");
        int64_t elapsed = t1 - t0;
        int64_t overshoot = elapsed - target_ns;
        if (overshoot < 0) overshoot = 0;
        overshoots[i] = overshoot;
        sum_overshoot_ns += overshoot;
    }
    for (int i = 0; i < iters; ++i) {
        for (int j = i + 1; j < iters; ++j) {
            if (overshoots[j] < overshoots[i]) {
                int64_t tmp = overshoots[i];
                overshoots[i] = overshoots[j];
                overshoots[j] = tmp;
            }
        }
    }
    double p50_overshoot_us = (double)overshoots[iters / 2] / 1000.0;
    double avg_overshoot_us = ((double)sum_overshoot_ns / (double)iters) / 1000.0;
    double max_overshoot_us = (double)overshoots[iters - 1] / 1000.0;
    printf("  [PASS] 5. sleep_nanos(2ms x %d): p50=%.1f us, avg=%.1f us, max=%.1f us\n",
           iters, p50_overshoot_us, avg_overshoot_us, max_overshoot_us);
#ifndef _WIN32
    CHECK(p50_overshoot_us < 1000.0, "Linux clock_nanosleep median overshoot unexpectedly high (>1 ms)");
#endif

    /* 6. Interruptible high-res waiter: timed expiration + instant cross-thread wake */
    void* waiter = fuji_os_waiter_create();
    CHECK(waiter != NULL, "fuji_os_waiter_create failed");
    fuji_os_waiter_reset(waiter);
    CHECK(fuji_os_waiter_wait_nanos(waiter, 2000000LL) == 0, "timed wait should expire with 0");

    /* Pre-signaled wake must return immediately with 1 */
    fuji_os_waiter_wake(waiter);
    CHECK(fuji_os_waiter_wait_nanos(waiter, 500000000LL) == 1, "pre-signaled wait must return 1");

    /* Cross-thread wake interrupts a 500 ms wait in ~10 ms */
    WakeArgs wargs;
    wargs.waiter = waiter;
    wargs.delay_ns = 10000000LL;
    int64_t wt0 = fuji_os_monotonic_nanos();
#ifdef _WIN32
    HANDLE th = CreateThread(NULL, 0, wake_thread_fn, &wargs, 0, NULL);
    CHECK(th != NULL, "CreateThread failed");
    int32_t wrc = fuji_os_waiter_wait_nanos(waiter, 500000000LL);
    WaitForSingleObject(th, INFINITE);
    CloseHandle(th);
#else
    pthread_t th;
    CHECK(pthread_create(&th, NULL, wake_thread_fn, &wargs) == 0, "pthread_create failed");
    int32_t wrc = fuji_os_waiter_wait_nanos(waiter, 500000000LL);
    pthread_join(th, NULL);
#endif
    int64_t wt_elapsed_ms = (fuji_os_monotonic_nanos() - wt0) / 1000000LL;
    CHECK(wrc == 1, "cross-thread wake must return 1");
    CHECK(wt_elapsed_ms < 250, "cross-thread wake took too long");
    fuji_os_waiter_destroy(waiter);
    fuji_os_waiter_destroy(NULL);
    CHECK(fuji_os_waiter_wait_nanos(NULL, 1000LL) < 0, "wait(NULL) must fail");
    printf("  [PASS] 6. Interruptible high-res waiter (timed + pre-wake + cross-thread wake in %lld ms)\n",
           (long long)wt_elapsed_ms);

    /* 7. Transparent Huge Pages (MADV_HUGEPAGE) helper + null/zero guards */
    CHECK(fuji_os_advise_hugepage(NULL, 4096) < 0, "advise_hugepage(NULL) must fail");
    CHECK(fuji_os_advise_hugepage(pcm, 0) < 0, "advise_hugepage(len=0) must fail");
#ifdef _WIN32
    void* buf = malloc(2u * 1024u * 1024u);
    CHECK(buf != NULL, "malloc 2MiB failed");
    CHECK(fuji_os_advise_hugepage(buf, 2u * 1024u * 1024u) == 0, "Win32 advise_hugepage must be safe no-op");
    free(buf);
#else
    size_t sz = 2u * 1024u * 1024u;
    void* buf = mmap(NULL, sz, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    CHECK(buf != MAP_FAILED, "mmap 2MiB failed");
    int32_t thp_rc = fuji_os_advise_hugepage(buf, (uint64_t)sz);
    /* 0 on THP-enabled kernels; -EINVAL allowed if container disables THP */
    CHECK(thp_rc == 0 || thp_rc == -22, "unexpected madvise(MADV_HUGEPAGE) error");
    munmap(buf, sz);
#endif
    printf("  [PASS] 7. THP advise_hugepage + guard checks\n");

    fuji_os_tune_process(0);
    printf("[AUDIO-BRIDGE] ALL 7/7 SELF-TESTS PASSED!\n");
    return 0;
}
