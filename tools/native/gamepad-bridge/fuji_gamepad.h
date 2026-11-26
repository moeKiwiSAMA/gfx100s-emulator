/* SPDX-License-Identifier: MIT */
/*
 * fuji_gamepad — zero-dependency cross-platform gamepad polling bridge (ABI v1).
 *
 * Linux  : libfuji_gamepad.so, kernel evdev (/dev/input/event*) with joydev (/dev/input/js*) fallback.
 * Windows: fuji_gamepad.dll, XInput loaded dynamically (xinput1_4 -> xinput1_3 -> xinput9_1_0).
 *          The DLL must import ONLY KERNEL32.dll and msvcrt.dll.
 *
 * Threading: all functions must be called from ONE polling thread (the JVM GamepadPoller daemon).
 * Ownership: the caller owns the output buffer; the library never retains caller pointers.
 */
#ifndef FUJI_GAMEPAD_H
#define FUJI_GAMEPAD_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32) || defined(__CYGWIN__)
  #if defined(FUJI_GAMEPAD_BUILD_DLL)
    #define FUJI_GAMEPAD_EXPORT __declspec(dllexport)
  #else
    #define FUJI_GAMEPAD_EXPORT
  #endif
#else
  #define FUJI_GAMEPAD_EXPORT __attribute__((visibility("default")))
#endif

#define FUJI_GAMEPAD_ABI_VERSION 1
#define FUJI_GAMEPAD_MAX_SLOTS   4

/* Status codes. */
#define FUJI_GAMEPAD_OK            0
#define FUJI_GAMEPAD_ERR_ARGUMENT -1  /* NULL buffer or capacity <= 0 */
#define FUJI_GAMEPAD_ERR_STATE    -2  /* poll before init, or after shutdown */
#define FUJI_GAMEPAD_ERR_BACKEND  -3  /* no usable backend could be loaded (init only) */

/* Button bit masks: XInput wButtons-compatible layout on every platform. */
#define FUJI_GP_DPAD_UP    0x0001u
#define FUJI_GP_DPAD_DOWN  0x0002u
#define FUJI_GP_DPAD_LEFT  0x0004u
#define FUJI_GP_DPAD_RIGHT 0x0008u
#define FUJI_GP_START      0x0010u
#define FUJI_GP_BACK       0x0020u
#define FUJI_GP_L3         0x0040u
#define FUJI_GP_R3         0x0080u
#define FUJI_GP_LB         0x0100u
#define FUJI_GP_RB         0x0200u
#define FUJI_GP_GUIDE      0x0400u
#define FUJI_GP_A          0x1000u
#define FUJI_GP_B          0x2000u
#define FUJI_GP_X          0x4000u
#define FUJI_GP_Y          0x8000u

/*
 * One controller slot. Axis conventions are identical on every platform:
 *   lx, rx : [-1.0, +1.0], +1.0 = stick pushed RIGHT
 *   ly, ry : [-1.0, +1.0], +1.0 = stick pushed UP     (evdev ABS_Y/ABS_RY must be inverted)
 *   lt, rt : [ 0.0, +1.0], +1.0 = trigger fully pulled
 * Values are raw-normalized: NO deadzone is applied by the library (the JVM side owns deadzones).
 * A hat/D-pad reported as axes (evdev ABS_HAT0X/ABS_HAT0Y, joydev hat axes) is folded into the
 * FUJI_GP_DPAD_* bits. A disconnected slot has connected == 0 and every other numeric field zeroed.
 */
typedef struct FujiGamepadState {
    int32_t  connected;   /* 0x00: 1 if a controller occupies this slot, else 0          */
    int32_t  slot;        /* 0x04: slot index 0..FUJI_GAMEPAD_MAX_SLOTS-1 (== array index) */
    uint32_t buttons;     /* 0x08: FUJI_GP_* bit mask                                     */
    uint32_t reserved0;   /* 0x0C: always 0                                               */
    float    lx;          /* 0x10 */
    float    ly;          /* 0x14 */
    float    rx;          /* 0x18 */
    float    ry;          /* 0x1C */
    float    lt;          /* 0x20 */
    float    rt;          /* 0x24 */
    uint64_t sequence;    /* 0x28: monotonically increases whenever this slot's input changes */
    char     name[128];   /* 0x30: UTF-8, NUL-terminated device name ("" when disconnected)  */
} FujiGamepadState;       /* total: 176 bytes (0xB0) */

#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(FujiGamepadState) == 176, "FujiGamepadState must be 176 bytes");
#endif

/* Returns FUJI_GAMEPAD_ABI_VERSION. Safe to call at any time. */
FUJI_GAMEPAD_EXPORT int32_t     fuji_gamepad_abi_version(void);

/* Static ASCII backend identifier: "xinput1_4", "xinput1_3", "xinput9_1_0", "evdev", "joydev" or "none". */
FUJI_GAMEPAD_EXPORT const char* fuji_gamepad_backend_name(void);

/* Idempotent. Returns FUJI_GAMEPAD_OK, or FUJI_GAMEPAD_ERR_BACKEND when no backend is usable
 * (e.g. no XInput DLL present). Having zero controllers attached is NOT an error. */
FUJI_GAMEPAD_EXPORT int32_t     fuji_gamepad_init(void);

/* Non-blocking. Fills out[0 .. min(capacity, FUJI_GAMEPAD_MAX_SLOTS)-1] (out[i].slot == i) and
 * returns the number of connected controllers (>= 0), or a negative FUJI_GAMEPAD_ERR_* code.
 * Hot-plug: newly attached devices appear and removed devices disconnect within ~1 second. */
FUJI_GAMEPAD_EXPORT int32_t     fuji_gamepad_poll(FujiGamepadState* out, int32_t capacity);

/* Releases every OS handle. Idempotent; fuji_gamepad_init() may be called again afterwards. */
FUJI_GAMEPAD_EXPORT void        fuji_gamepad_shutdown(void);

#ifdef __cplusplus
}
#endif

#endif /* FUJI_GAMEPAD_H */
