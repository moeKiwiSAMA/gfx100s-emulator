/* SPDX-License-Identifier: MIT */
/*
 * test_gamepad_bridge.c — black-box self-test of the fuji_gamepad ABI v1 shared library.
 * Must pass with NO controller attached (connected controllers are tolerated and printed).
 */
#if !defined(_WIN32) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif
#include "fuji_gamepad.h"

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <time.h>
#endif

#define CHECK(cond, msg)                                                      \
    do {                                                                      \
        if (!(cond)) {                                                        \
            fprintf(stderr, "[FAIL] %s:%d: %s\n", __FILE__, __LINE__, (msg)); \
            exit(1);                                                          \
        }                                                                     \
    } while (0)

#define CANARY 0xA5

static double now_seconds(void) {
#ifdef _WIN32
    LARGE_INTEGER f, c;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&c);
    return (double)c.QuadPart / (double)f.QuadPart;
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
#endif
}

static int all_bytes(const void* p, size_t n, unsigned char v) {
    const unsigned char* b = (const unsigned char*)p;
    size_t i;
    for (i = 0; i < n; ++i) {
        if (b[i] != v) return 0;
    }
    return 1;
}

/* Validates one returned slot; returns 1 when connected. */
static int check_slot(const FujiGamepadState* s, int index) {
    CHECK(s->slot == index, "out[i].slot != i");
    CHECK(s->connected == 0 || s->connected == 1, "connected must be 0 or 1");
    CHECK(s->reserved0 == 0, "reserved0 must be 0");
    CHECK(memchr(s->name, '\0', sizeof(s->name)) != NULL, "name not NUL-terminated");
    if (!s->connected) {
        FujiGamepadState z;
        memset(&z, 0, sizeof(z));
        z.slot = index;
        CHECK(memcmp(&z, s, sizeof(z)) == 0, "disconnected slot is not fully zeroed");
        return 0;
    }
    CHECK(s->lx >= -1.0f && s->lx <= 1.0f && s->ly >= -1.0f && s->ly <= 1.0f, "left stick out of range");
    CHECK(s->rx >= -1.0f && s->rx <= 1.0f && s->ry >= -1.0f && s->ry <= 1.0f, "right stick out of range");
    CHECK(s->lt >= 0.0f && s->lt <= 1.0f && s->rt >= 0.0f && s->rt <= 1.0f, "trigger out of range");
    CHECK((s->buttons & ~0xF7FFu) == 0, "unknown button bits set");
    CHECK(s->sequence > 0, "connected slot must have sequence > 0");
    CHECK(s->name[0] != '\0', "connected slot must have a name");
    return 1;
}

int main(void) {
    FujiGamepadState st[FUJI_GAMEPAD_MAX_SLOTS + 2];
    int32_t rc;
    int i, cap, connected = 0;
    const char* backend;

    printf("[GAMEPAD-BRIDGE] Running standalone C ABI v1 self-test...\n");

    /* 1. ABI version + layout */
    CHECK(fuji_gamepad_abi_version() == FUJI_GAMEPAD_ABI_VERSION, "ABI version mismatch");
    CHECK(FUJI_GAMEPAD_ABI_VERSION == 1, "header ABI version != 1");
    CHECK(sizeof(FujiGamepadState) == 176, "sizeof(FujiGamepadState) != 176");
    CHECK(offsetof(FujiGamepadState, connected) == 0x00, "offsetof(connected)");
    CHECK(offsetof(FujiGamepadState, slot) == 0x04, "offsetof(slot)");
    CHECK(offsetof(FujiGamepadState, buttons) == 0x08, "offsetof(buttons)");
    CHECK(offsetof(FujiGamepadState, reserved0) == 0x0C, "offsetof(reserved0)");
    CHECK(offsetof(FujiGamepadState, lx) == 0x10, "offsetof(lx)");
    CHECK(offsetof(FujiGamepadState, ly) == 0x14, "offsetof(ly)");
    CHECK(offsetof(FujiGamepadState, rx) == 0x18, "offsetof(rx)");
    CHECK(offsetof(FujiGamepadState, ry) == 0x1C, "offsetof(ry)");
    CHECK(offsetof(FujiGamepadState, lt) == 0x20, "offsetof(lt)");
    CHECK(offsetof(FujiGamepadState, rt) == 0x24, "offsetof(rt)");
    CHECK(offsetof(FujiGamepadState, sequence) == 0x28, "offsetof(sequence)");
    CHECK(offsetof(FujiGamepadState, name) == 0x30, "offsetof(name)");
    CHECK(sizeof(((FujiGamepadState*)0)->name) == 128, "sizeof(name) != 128");
    CHECK(FUJI_GAMEPAD_MAX_SLOTS == 4, "MAX_SLOTS != 4");
    printf("  [PASS] 1. ABI v%d, sizeof(FujiGamepadState)=%u, all 12 field offsets match header\n",
           (int)fuji_gamepad_abi_version(), (unsigned)sizeof(FujiGamepadState));

    /* 2. poll before init */
    backend = fuji_gamepad_backend_name();
    CHECK(backend != NULL && strcmp(backend, "none") == 0, "backend must be 'none' before init");
    CHECK(fuji_gamepad_poll(st, FUJI_GAMEPAD_MAX_SLOTS) == FUJI_GAMEPAD_ERR_STATE, "poll before init != ERR_STATE");
    printf("  [PASS] 2. pre-init: backend='none', poll -> ERR_STATE\n");

    /* 3. init */
    rc = fuji_gamepad_init();
#ifdef _WIN32
    if (rc == FUJI_GAMEPAD_ERR_BACKEND) {
        printf("  [SKIP] 3+. no XInput DLL available: init -> ERR_BACKEND (expected without XInput)\n");
        CHECK(fuji_gamepad_poll(st, 4) == FUJI_GAMEPAD_ERR_STATE, "poll after failed init != ERR_STATE");
        fuji_gamepad_shutdown();
        printf("[GAMEPAD-BRIDGE] ALL CHECKS PASSED (backend unavailable)\n");
        return 0;
    }
#endif
    CHECK(rc == FUJI_GAMEPAD_OK, "init != OK");
    CHECK(fuji_gamepad_init() == FUJI_GAMEPAD_OK, "second init (idempotent) != OK");
    backend = fuji_gamepad_backend_name();
    CHECK(backend != NULL && backend[0] != '\0' && strcmp(backend, "none") != 0, "backend name after init");
    printf("  [PASS] 3. init OK (idempotent), backend='%s'\n", backend);

    /* 4. full poll */
    memset(st, CANARY, sizeof(st));
    rc = fuji_gamepad_poll(st, FUJI_GAMEPAD_MAX_SLOTS + 2);
    CHECK(rc >= 0 && rc <= FUJI_GAMEPAD_MAX_SLOTS, "poll returned out-of-range count");
    for (i = 0; i < FUJI_GAMEPAD_MAX_SLOTS; ++i) {
        connected += check_slot(&st[i], i);
    }
    CHECK(connected == rc, "return value != number of connected slots");
    CHECK(all_bytes(&st[FUJI_GAMEPAD_MAX_SLOTS], 2 * sizeof(st[0]), CANARY),
          "poll wrote past FUJI_GAMEPAD_MAX_SLOTS");
    printf("  [PASS] 4. poll -> %d connected; slots 0..3 valid, disconnected slots all-zero, no write past slot 3\n",
           (int)rc);
    for (i = 0; i < FUJI_GAMEPAD_MAX_SLOTS; ++i) {
        if (st[i].connected) {
            printf("         slot %d: '%s' buttons=0x%04X lx=%.3f ly=%.3f rx=%.3f ry=%.3f lt=%.3f rt=%.3f seq=%llu\n",
                   i, st[i].name, (unsigned)st[i].buttons, st[i].lx, st[i].ly, st[i].rx, st[i].ry, st[i].lt,
                   st[i].rt, (unsigned long long)st[i].sequence);
        }
    }

    /* 5. capacity 1..3 + canary */
    for (cap = 1; cap <= 3; ++cap) {
        memset(st, CANARY, sizeof(st));
        rc = fuji_gamepad_poll(st, cap);
        CHECK(rc >= 0, "poll with small capacity failed");
        for (i = 0; i < cap; ++i) {
            check_slot(&st[i], i);
        }
        CHECK(all_bytes(&st[cap], (size_t)(FUJI_GAMEPAD_MAX_SLOTS + 2 - cap) * sizeof(st[0]), CANARY),
              "poll wrote beyond capacity");
    }
    printf("  [PASS] 5. capacity 1..3 writes exactly that many entries (canary intact)\n");

    /* 6. argument errors */
    memset(st, CANARY, sizeof(st));
    CHECK(fuji_gamepad_poll(NULL, 4) == FUJI_GAMEPAD_ERR_ARGUMENT, "NULL buffer != ERR_ARGUMENT");
    CHECK(fuji_gamepad_poll(st, 0) == FUJI_GAMEPAD_ERR_ARGUMENT, "capacity 0 != ERR_ARGUMENT");
    CHECK(fuji_gamepad_poll(st, -1) == FUJI_GAMEPAD_ERR_ARGUMENT, "capacity -1 != ERR_ARGUMENT");
    CHECK(all_bytes(st, sizeof(st), CANARY), "argument-error poll wrote to buffer");
    printf("  [PASS] 6. NULL buffer / capacity 0 / capacity -1 -> ERR_ARGUMENT, buffer untouched\n");

    /* 7. 1000 rapid polls */
    {
        double t0 = now_seconds(), dt;
        for (i = 0; i < 1000; ++i) {
            rc = fuji_gamepad_poll(st, FUJI_GAMEPAD_MAX_SLOTS);
            CHECK(rc >= 0, "rapid poll failed");
        }
        dt = now_seconds() - t0;
#ifdef _WIN32
        printf("  [INFO] 7. 1000 polls took %.3f ms (timing bound enforced on Linux only)\n", dt * 1000.0);
#else
        CHECK(dt < 1.0, "1000 polls took >= 1 s");
        printf("  [PASS] 7. 1000 polls took %.3f ms (< 1000 ms)\n", dt * 1000.0);
#endif
    }

    /* 8. shutdown / re-init */
    fuji_gamepad_shutdown();
    fuji_gamepad_shutdown();
    CHECK(fuji_gamepad_poll(st, FUJI_GAMEPAD_MAX_SLOTS) == FUJI_GAMEPAD_ERR_STATE, "poll after shutdown != ERR_STATE");
    CHECK(strcmp(fuji_gamepad_backend_name(), "none") == 0, "backend must be 'none' after shutdown");
    CHECK(fuji_gamepad_init() == FUJI_GAMEPAD_OK, "re-init != OK");
    rc = fuji_gamepad_poll(st, FUJI_GAMEPAD_MAX_SLOTS);
    CHECK(rc >= 0, "poll after re-init failed");
    for (i = 0; i < FUJI_GAMEPAD_MAX_SLOTS; ++i) {
        check_slot(&st[i], i);
    }
    fuji_gamepad_shutdown();
    CHECK(fuji_gamepad_poll(st, FUJI_GAMEPAD_MAX_SLOTS) == FUJI_GAMEPAD_ERR_STATE, "poll after 2nd shutdown != ERR_STATE");
    printf("  [PASS] 8. shutdown idempotent, poll after shutdown -> ERR_STATE, re-init + poll OK\n");

    printf("[GAMEPAD-BRIDGE] ALL CHECKS PASSED\n");
    return 0;
}
