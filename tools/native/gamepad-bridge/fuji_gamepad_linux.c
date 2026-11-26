/* SPDX-License-Identifier: MIT */
/*
 * fuji_gamepad_linux.c — Linux backend of the fuji_gamepad ABI v1 (libfuji_gamepad.so).
 *
 * Backend 1: kernel evdev (/dev/input/event*), the primary path.
 * Backend 2: joydev (/dev/input/js*), used ONLY while no evdev gamepad is accessible. As soon as an
 *            evdev gamepad becomes accessible every joydev slot is closed, so one physical device is
 *            never reported twice (joydev and evdev are never mixed).
 *
 * Zero dependencies beyond libc and the kernel UAPI headers. No threads, no allocations: all state
 * lives in a fixed table of FUJI_GAMEPAD_MAX_SLOTS slots, driven entirely from fuji_gamepad_poll().
 * Hot-plug is handled by rescanning /dev/input at most once per FGP_RESCAN_MS and by treating
 * ENODEV / EOF / any hard read error on an open node as a disconnect.
 */
#define _POSIX_C_SOURCE 200809L

#include "fuji_gamepad.h"

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <linux/input.h>
#include <linux/joystick.h>

#ifndef FGP_INPUT_DIR
#define FGP_INPUT_DIR "/dev/input"
#endif
#define FGP_RESCAN_MS      1000
#define FGP_MAX_NODES      256
#define FGP_REJECT_CACHE   64
#define FGP_XPAD_BUTTONS   11

#define FGP_LONG_BITS (8u * (unsigned)sizeof(unsigned long))
#define FGP_NLONGS(n) (((unsigned)(n) + FGP_LONG_BITS - 1u) / FGP_LONG_BITS)

#define FGP_DPAD_X_MASK (FUJI_GP_DPAD_LEFT | FUJI_GP_DPAD_RIGHT)
#define FGP_DPAD_Y_MASK (FUJI_GP_DPAD_UP | FUJI_GP_DPAD_DOWN)

enum { FGP_KIND_NONE = 0, FGP_KIND_EVDEV = 1, FGP_KIND_JOYDEV = 2 };

enum {
    FGP_AX_LX = 0,
    FGP_AX_LY,
    FGP_AX_RX,
    FGP_AX_RY,
    FGP_AX_LT,
    FGP_AX_RT,
    FGP_AX_HATX,
    FGP_AX_HATY,
    FGP_AX_COUNT
};

/* xpad / XInput button order, shared by joydev button numbers and generic BTN_JOYSTICK devices. */
static const uint32_t k_xpad_buttons[FGP_XPAD_BUTTONS] = {
    FUJI_GP_A,    FUJI_GP_B,     FUJI_GP_X,     FUJI_GP_Y,  FUJI_GP_LB, FUJI_GP_RB,
    FUJI_GP_BACK, FUJI_GP_START, FUJI_GP_GUIDE, FUJI_GP_L3, FUJI_GP_R3,
};

typedef struct FgpAxisCal {
    int     code; /* evdev ABS_* code feeding this logical axis, or -1 when absent */
    int32_t min;
    int32_t max;
} FgpAxisCal;

typedef struct FgpSlot {
    int        kind;   /* FGP_KIND_* */
    int        fd;     /* -1 when free */
    char       path[64];
    dev_t      rdev;
    ino_t      ino;
    FgpAxisCal cal[FGP_AX_COUNT]; /* evdev only */
    int        lt_digital;        /* evdev: lt driven by BTN_TL2 (no analog trigger axis) */
    int        rt_digital;        /* evdev: rt driven by BTN_TR2 */
    int        generic_joystick;  /* evdev: BTN_JOYSTICK class, buttons mapped by index */
    int        happy_dpad;        /* evdev: xpad dpad_to_buttons (BTN_TRIGGER_HAPPY1..4) */
    int        dropped;           /* evdev: SYN_DROPPED seen, waiting for SYN_REPORT */
    uint32_t   key_buttons;
    uint32_t   hat_buttons;
    FujiGamepadState pub;
} FgpSlot;

typedef struct FgpReject {
    dev_t rdev;
    ino_t ino;
} FgpReject;

static FgpSlot   g_slots[FUJI_GAMEPAD_MAX_SLOTS];
static int       g_initialized;
static uint64_t  g_sequence;
static int64_t   g_last_scan_ms;
static FgpReject g_rejects[FGP_REJECT_CACHE];
static int       g_reject_count;

/* ------------------------------------------------------------------------------------------------ */
/* Helpers                                                                                          */
/* ------------------------------------------------------------------------------------------------ */

static int64_t fgp_now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return (int64_t)ts.tv_sec * 1000 + (int64_t)(ts.tv_nsec / 1000000);
}

static int fgp_test_bit(const unsigned long* bits, unsigned bit) {
    return (int)((bits[bit / FGP_LONG_BITS] >> (bit % FGP_LONG_BITS)) & 1ul);
}

static float fgp_clampf(float v, float lo, float hi) {
    if (v != v) return 0.0f; /* NaN guard */
    return v < lo ? lo : (v > hi ? hi : v);
}

/* Stick: [min,max] -> [-1,1]. Ranges straddling 0 are scaled per side so a raw 0 is exactly 0. */
static float fgp_norm_stick(int32_t v, int32_t mn, int32_t mx) {
    float f;
    if (mx <= mn) return 0.0f;
    if (mn < 0 && mx > 0) {
        f = v >= 0 ? (float)v / (float)mx : (float)v / (float)(-(int64_t)mn);
    } else {
        f = 2.0f * ((float)((int64_t)v - mn) / (float)((int64_t)mx - mn)) - 1.0f;
    }
    return fgp_clampf(f, -1.0f, 1.0f);
}

/* Trigger: [min,max] -> [0,1]. */
static float fgp_norm_trigger(int32_t v, int32_t mn, int32_t mx) {
    if (mx <= mn) return 0.0f;
    return fgp_clampf((float)((int64_t)v - mn) / (float)((int64_t)mx - mn), 0.0f, 1.0f);
}

/* Hat: -1 below centre, +1 above centre, 0 at centre. */
static int fgp_hat_dir(int32_t v, int32_t mn, int32_t mx) {
    int64_t twice = 2 * (int64_t)v;
    int64_t sum = (int64_t)mn + (int64_t)mx;
    if (mx <= mn) return v < 0 ? -1 : (v > 0 ? 1 : 0);
    return twice < sum ? -1 : (twice > sum ? 1 : 0);
}

static void fgp_set_hat(FgpSlot* s, int axis_y, int dir) {
    if (axis_y) {
        s->hat_buttons &= ~FGP_DPAD_Y_MASK;
        if (dir < 0) s->hat_buttons |= FUJI_GP_DPAD_UP;
        if (dir > 0) s->hat_buttons |= FUJI_GP_DPAD_DOWN;
    } else {
        s->hat_buttons &= ~FGP_DPAD_X_MASK;
        if (dir < 0) s->hat_buttons |= FUJI_GP_DPAD_LEFT;
        if (dir > 0) s->hat_buttons |= FUJI_GP_DPAD_RIGHT;
    }
    s->pub.buttons = s->key_buttons | s->hat_buttons;
}

static void fgp_slot_reset(FgpSlot* s) {
    int r;
    memset(s, 0, sizeof(*s));
    s->fd = -1;
    for (r = 0; r < FGP_AX_COUNT; ++r) {
        s->cal[r].code = -1;
    }
}

static int fgp_count_kind(int kind) {
    int i, n = 0;
    for (i = 0; i < FUJI_GAMEPAD_MAX_SLOTS; ++i) {
        if (g_slots[i].kind == kind) ++n;
    }
    return n;
}

static int fgp_lowest_free(void) {
    int i;
    for (i = 0; i < FUJI_GAMEPAD_MAX_SLOTS; ++i) {
        if (g_slots[i].kind == FGP_KIND_NONE) return i;
    }
    return -1;
}

static int fgp_path_in_use(const char* path) {
    int i;
    for (i = 0; i < FUJI_GAMEPAD_MAX_SLOTS; ++i) {
        if (g_slots[i].kind != FGP_KIND_NONE && strcmp(g_slots[i].path, path) == 0) return 1;
    }
    return 0;
}

static void fgp_slot_disconnect(int index) {
    FgpSlot* s = &g_slots[index];
    if (s->fd >= 0) {
        close(s->fd);
    }
    fgp_slot_reset(s);
}

static void fgp_close_all_joydev(void) {
    int i;
    for (i = 0; i < FUJI_GAMEPAD_MAX_SLOTS; ++i) {
        if (g_slots[i].kind == FGP_KIND_JOYDEV) fgp_slot_disconnect(i);
    }
}

static int fgp_is_rejected(const struct stat* st) {
    int i;
    for (i = 0; i < g_reject_count; ++i) {
        if (g_rejects[i].rdev == st->st_rdev && g_rejects[i].ino == st->st_ino) return 1;
    }
    return 0;
}

static void fgp_remember_reject(const struct stat* st) {
    if (g_reject_count >= FGP_REJECT_CACHE) {
        g_reject_count = 0; /* simple reset; nodes are re-probed at most once more */
    }
    g_rejects[g_reject_count].rdev = st->st_rdev;
    g_rejects[g_reject_count].ino = st->st_ino;
    ++g_reject_count;
}

/* ------------------------------------------------------------------------------------------------ */
/* evdev                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

static uint32_t fgp_evdev_key_bit(const FgpSlot* s, int code) {
    switch (code) {
        case BTN_SOUTH:      return FUJI_GP_A;     /* == BTN_A, == BTN_GAMEPAD */
        case BTN_EAST:       return FUJI_GP_B;     /* == BTN_B */
        case BTN_X:          return FUJI_GP_X;
        case BTN_Y:          return FUJI_GP_Y;
        case BTN_TL:         return FUJI_GP_LB;
        case BTN_TR:         return FUJI_GP_RB;
        case BTN_SELECT:     return FUJI_GP_BACK;
        case BTN_START:      return FUJI_GP_START;
        case BTN_MODE:       return FUJI_GP_GUIDE;
        case BTN_THUMBL:     return FUJI_GP_L3;
        case BTN_THUMBR:     return FUJI_GP_R3;
        case BTN_DPAD_UP:    return FUJI_GP_DPAD_UP;
        case BTN_DPAD_DOWN:  return FUJI_GP_DPAD_DOWN;
        case BTN_DPAD_LEFT:  return FUJI_GP_DPAD_LEFT;
        case BTN_DPAD_RIGHT: return FUJI_GP_DPAD_RIGHT;
        default: break;
    }
    if (s->happy_dpad) {
        switch (code) {
            case BTN_TRIGGER_HAPPY1: return FUJI_GP_DPAD_LEFT;
            case BTN_TRIGGER_HAPPY2: return FUJI_GP_DPAD_RIGHT;
            case BTN_TRIGGER_HAPPY3: return FUJI_GP_DPAD_UP;
            case BTN_TRIGGER_HAPPY4: return FUJI_GP_DPAD_DOWN;
            default: break;
        }
    }
    if (s->generic_joystick && code >= BTN_TRIGGER && code < BTN_TRIGGER + FGP_XPAD_BUTTONS) {
        return k_xpad_buttons[code - BTN_TRIGGER];
    }
    return 0;
}

static void fgp_evdev_set_key(FgpSlot* s, int code, int down) {
    uint32_t bit;
    if (code == BTN_TL2 && s->lt_digital) {
        s->pub.lt = down ? 1.0f : 0.0f;
        return;
    }
    if (code == BTN_TR2 && s->rt_digital) {
        s->pub.rt = down ? 1.0f : 0.0f;
        return;
    }
    bit = fgp_evdev_key_bit(s, code);
    if (bit == 0) return;
    if (down) {
        s->key_buttons |= bit;
    } else {
        s->key_buttons &= ~bit;
    }
    s->pub.buttons = s->key_buttons | s->hat_buttons;
}

static void fgp_evdev_apply_axis(FgpSlot* s, int role, int32_t v) {
    const FgpAxisCal* c = &s->cal[role];
    switch (role) {
        case FGP_AX_LX:   s->pub.lx = fgp_norm_stick(v, c->min, c->max); break;
        case FGP_AX_LY:   s->pub.ly = -fgp_norm_stick(v, c->min, c->max); break;
        case FGP_AX_RX:   s->pub.rx = fgp_norm_stick(v, c->min, c->max); break;
        case FGP_AX_RY:   s->pub.ry = -fgp_norm_stick(v, c->min, c->max); break;
        case FGP_AX_LT:   s->pub.lt = fgp_norm_trigger(v, c->min, c->max); break;
        case FGP_AX_RT:   s->pub.rt = fgp_norm_trigger(v, c->min, c->max); break;
        case FGP_AX_HATX: fgp_set_hat(s, 0, fgp_hat_dir(v, c->min, c->max)); break;
        case FGP_AX_HATY: fgp_set_hat(s, 1, fgp_hat_dir(v, c->min, c->max)); break;
        default: break;
    }
}

static void fgp_evdev_set_abs(FgpSlot* s, int code, int32_t v) {
    int r;
    for (r = 0; r < FGP_AX_COUNT; ++r) {
        if (s->cal[r].code == code) fgp_evdev_apply_axis(s, r, v);
    }
}

static void fgp_evdev_apply_event(FgpSlot* s, const struct input_event* ev) {
    if (ev->type == EV_SYN) {
        if (ev->code == SYN_DROPPED) {
            s->dropped = 1;
        } else if (ev->code == SYN_REPORT && s->dropped) {
            s->dropped = 2; /* resync requested; performed by the drain loop */
        }
        return;
    }
    if (s->dropped) return; /* discard until the SYN_REPORT that follows SYN_DROPPED */
    if (ev->type == EV_KEY) {
        if (ev->value == 2) return; /* autorepeat */
        fgp_evdev_set_key(s, (int)ev->code, ev->value != 0);
    } else if (ev->type == EV_ABS) {
        fgp_evdev_set_abs(s, (int)ev->code, (int32_t)ev->value);
    }
}

/* Re-reads the full key and absolute-axis state from the kernel (open, and after SYN_DROPPED). */
static void fgp_evdev_resync(FgpSlot* s) {
    unsigned long keys[FGP_NLONGS(KEY_CNT)];
    int code, r;
    memset(keys, 0, sizeof(keys));
    if (ioctl(s->fd, EVIOCGKEY(sizeof(keys)), keys) >= 0) {
        s->key_buttons = 0;
        for (code = BTN_MISC; code < KEY_CNT; ++code) {
            fgp_evdev_set_key(s, code, fgp_test_bit(keys, (unsigned)code));
        }
        s->pub.buttons = s->key_buttons | s->hat_buttons;
    }
    for (r = 0; r < FGP_AX_COUNT; ++r) {
        struct input_absinfo ai;
        if (s->cal[r].code < 0) continue;
        memset(&ai, 0, sizeof(ai));
        if (ioctl(s->fd, EVIOCGABS(s->cal[r].code), &ai) >= 0) {
            fgp_evdev_apply_axis(s, r, ai.value);
        }
    }
}

static void fgp_evdev_bind(FgpSlot* s, int role, int code, const unsigned long* absbits,
                           const struct input_absinfo* ai) {
    if (!fgp_test_bit(absbits, (unsigned)code)) return;
    if (ai[code].maximum <= ai[code].minimum) return;
    s->cal[role].code = code;
    s->cal[role].min = ai[code].minimum;
    s->cal[role].max = ai[code].maximum;
}

/*
 * Pure capability classification + axis-role assignment (no I/O; unit-tested directly).
 * keybits: KEY_CNT bits, absbits: ABS_CNT bits, ai: ABS_CNT entries (only set bits are read).
 * Returns 1 when the device is a gamepad and `s` has been configured, 0 otherwise.
 */
static int fgp_evdev_setup(FgpSlot* s, const unsigned long* keybits, const unsigned long* absbits,
                           const struct input_absinfo* ai) {
    int has_gamepad = fgp_test_bit(keybits, BTN_GAMEPAD);
    int has_joystick = fgp_test_bit(keybits, BTN_JOYSTICK);
    int r;
    if (!(has_gamepad || has_joystick) || !fgp_test_bit(absbits, ABS_X)) return 0;

    for (r = 0; r < FGP_AX_COUNT; ++r) s->cal[r].code = -1;
    fgp_evdev_bind(s, FGP_AX_LX, ABS_X, absbits, ai);
    fgp_evdev_bind(s, FGP_AX_LY, ABS_Y, absbits, ai);
    if (fgp_test_bit(absbits, ABS_RX) || fgp_test_bit(absbits, ABS_RY)) {
        fgp_evdev_bind(s, FGP_AX_RX, ABS_RX, absbits, ai);
        fgp_evdev_bind(s, FGP_AX_RY, ABS_RY, absbits, ai);
        fgp_evdev_bind(s, FGP_AX_LT, ABS_Z, absbits, ai);
        fgp_evdev_bind(s, FGP_AX_RT, ABS_RZ, absbits, ai);
    } else if (fgp_test_bit(absbits, ABS_Z) && fgp_test_bit(absbits, ABS_RZ)) {
        /* No ABS_RX/RY: generic HID pads (hid-generic, many USB/BT clones) put the right stick on Z/RZ. */
        fgp_evdev_bind(s, FGP_AX_RX, ABS_Z, absbits, ai);
        fgp_evdev_bind(s, FGP_AX_RY, ABS_RZ, absbits, ai);
    } else {
        fgp_evdev_bind(s, FGP_AX_LT, ABS_Z, absbits, ai);
        fgp_evdev_bind(s, FGP_AX_RT, ABS_RZ, absbits, ai);
    }
    if (s->cal[FGP_AX_LT].code < 0) fgp_evdev_bind(s, FGP_AX_LT, ABS_BRAKE, absbits, ai);
    if (s->cal[FGP_AX_RT].code < 0) fgp_evdev_bind(s, FGP_AX_RT, ABS_GAS, absbits, ai);
    fgp_evdev_bind(s, FGP_AX_HATX, ABS_HAT0X, absbits, ai);
    fgp_evdev_bind(s, FGP_AX_HATY, ABS_HAT0Y, absbits, ai);

    s->lt_digital = s->cal[FGP_AX_LT].code < 0 && fgp_test_bit(keybits, BTN_TL2);
    s->rt_digital = s->cal[FGP_AX_RT].code < 0 && fgp_test_bit(keybits, BTN_TR2);
    s->generic_joystick = !has_gamepad;
    s->happy_dpad = !fgp_test_bit(keybits, BTN_DPAD_UP) && s->cal[FGP_AX_HATX].code < 0 &&
                    fgp_test_bit(keybits, BTN_TRIGGER_HAPPY1) && fgp_test_bit(keybits, BTN_TRIGGER_HAPPY4);
    return 1;
}

/* Queries capabilities through ioctl and configures the slot. Returns 1 for a gamepad. */
static int fgp_evdev_probe(FgpSlot* s) {
    unsigned long evbits[FGP_NLONGS(EV_CNT)];
    unsigned long keybits[FGP_NLONGS(KEY_CNT)];
    unsigned long absbits[FGP_NLONGS(ABS_CNT)];
    struct input_absinfo ai[ABS_CNT];
    int code;

    memset(evbits, 0, sizeof(evbits));
    memset(keybits, 0, sizeof(keybits));
    memset(absbits, 0, sizeof(absbits));
    memset(ai, 0, sizeof(ai));
    if (ioctl(s->fd, EVIOCGBIT(0, sizeof(evbits)), evbits) < 0) return 0;
    if (!fgp_test_bit(evbits, EV_KEY) || !fgp_test_bit(evbits, EV_ABS)) return 0;
    if (ioctl(s->fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) < 0) return 0;
    if (ioctl(s->fd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits) < 0) return 0;
    for (code = 0; code < ABS_CNT; ++code) {
        if (!fgp_test_bit(absbits, (unsigned)code)) continue;
        if (ioctl(s->fd, EVIOCGABS(code), &ai[code]) < 0) {
            absbits[code / FGP_LONG_BITS] &= ~(1ul << (code % FGP_LONG_BITS));
        }
    }
    if (!fgp_evdev_setup(s, keybits, absbits, ai)) return 0;
    if (ioctl(s->fd, EVIOCGNAME(sizeof(s->pub.name)), s->pub.name) < 0 || s->pub.name[0] == '\0') {
        snprintf(s->pub.name, sizeof(s->pub.name), "Gamepad");
    }
    s->pub.name[sizeof(s->pub.name) - 1] = '\0';
    return 1;
}

/* Returns 0 when the queue is drained, -1 when the device is gone. */
static int fgp_evdev_drain(FgpSlot* s) {
    struct input_event ev[64];
    for (;;) {
        ssize_t n = read(s->fd, ev, sizeof(ev));
        size_t i, count;
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            return -1; /* ENODEV (unplugged) or any other hard error */
        }
        if (n == 0) return -1;
        count = (size_t)n / sizeof(ev[0]);
        for (i = 0; i < count; ++i) {
            fgp_evdev_apply_event(s, &ev[i]);
            if (s->dropped == 2) {
                s->dropped = 0;
                fgp_evdev_resync(s);
            }
        }
    }
    return 0;
}

/* ------------------------------------------------------------------------------------------------ */
/* joydev                                                                                           */
/* ------------------------------------------------------------------------------------------------ */

static void fgp_joydev_apply_event(FgpSlot* s, const struct js_event* e) {
    unsigned type = (unsigned)e->type & ~(unsigned)JS_EVENT_INIT;
    if (type == JS_EVENT_BUTTON) {
        uint32_t bit;
        if (e->number >= FGP_XPAD_BUTTONS) return;
        bit = k_xpad_buttons[e->number];
        if (e->value) {
            s->key_buttons |= bit;
        } else {
            s->key_buttons &= ~bit;
        }
        s->pub.buttons = s->key_buttons | s->hat_buttons;
    } else if (type == JS_EVENT_AXIS) {
        float f = fgp_clampf((float)e->value / 32767.0f, -1.0f, 1.0f);
        switch (e->number) {
            case 0: s->pub.lx = f; break;
            case 1: s->pub.ly = -f; break;
            case 2: s->pub.lt = (f + 1.0f) * 0.5f; break;
            case 3: s->pub.rx = f; break;
            case 4: s->pub.ry = -f; break;
            case 5: s->pub.rt = (f + 1.0f) * 0.5f; break;
            case 6: fgp_set_hat(s, 0, e->value < 0 ? -1 : (e->value > 0 ? 1 : 0)); break;
            case 7: fgp_set_hat(s, 1, e->value < 0 ? -1 : (e->value > 0 ? 1 : 0)); break;
            default: break;
        }
    }
}

static int fgp_joydev_probe(FgpSlot* s) {
    unsigned char axes = 0, buttons = 0;
    if (ioctl(s->fd, JSIOCGAXES, &axes) < 0) return 0;
    if (ioctl(s->fd, JSIOCGBUTTONS, &buttons) < 0) return 0;
    if (axes < 2 || buttons < 4) return 0; /* skip accelerometers & other non-pads */
    if (ioctl(s->fd, JSIOCGNAME(sizeof(s->pub.name)), s->pub.name) < 0 || s->pub.name[0] == '\0') {
        snprintf(s->pub.name, sizeof(s->pub.name), "Joystick");
    }
    s->pub.name[sizeof(s->pub.name) - 1] = '\0';
    return 1;
}

static int fgp_joydev_drain(FgpSlot* s) {
    struct js_event ev[64];
    for (;;) {
        ssize_t n = read(s->fd, ev, sizeof(ev));
        size_t i, count;
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            return -1;
        }
        if (n == 0) return -1;
        count = (size_t)n / sizeof(ev[0]);
        for (i = 0; i < count; ++i) {
            fgp_joydev_apply_event(s, &ev[i]);
        }
    }
    return 0;
}

/* ------------------------------------------------------------------------------------------------ */
/* Slot management + scanning                                                                       */
/* ------------------------------------------------------------------------------------------------ */

static int fgp_drain(FgpSlot* s) {
    return s->kind == FGP_KIND_EVDEV ? fgp_evdev_drain(s) : fgp_joydev_drain(s);
}

/* Moves a configured candidate into slot `index`, syncs its initial state and marks it connected. */
static void fgp_slot_attach(int index, const FgpSlot* candidate) {
    FgpSlot* s = &g_slots[index];
    *s = *candidate;
    s->pub.connected = 1;
    s->pub.slot = index;
    s->pub.reserved0 = 0;
    if (s->kind == FGP_KIND_EVDEV) {
        fgp_evdev_resync(s);
    }
    if (fgp_drain(s) != 0) {
        fgp_slot_disconnect(index);
        return;
    }
    s->pub.sequence = ++g_sequence;
}

static int fgp_cmp_int(const void* a, const void* b) {
    int x = *(const int*)a, y = *(const int*)b;
    return (x > y) - (x < y);
}

static int fgp_list_nodes(const char* prefix, int* nums, int max) {
    DIR* d = opendir(FGP_INPUT_DIR);
    struct dirent* de;
    size_t plen = strlen(prefix);
    int n = 0;
    if (d == NULL) return 0;
    while (n < max && (de = readdir(d)) != NULL) {
        const char* p;
        char* end = NULL;
        long v;
        if (strncmp(de->d_name, prefix, plen) != 0) continue;
        p = de->d_name + plen;
        if (!isdigit((unsigned char)p[0])) continue;
        v = strtol(p, &end, 10);
        if (end == NULL || *end != '\0' || v < 0 || v > 1000000) continue;
        nums[n++] = (int)v;
    }
    closedir(d);
    qsort(nums, (size_t)n, sizeof(nums[0]), fgp_cmp_int);
    return n;
}

static void fgp_scan_kind(int kind) {
    int nums[FGP_MAX_NODES];
    const char* prefix = kind == FGP_KIND_EVDEV ? "event" : "js";
    int n = fgp_list_nodes(prefix, nums, FGP_MAX_NODES);
    int i;
    for (i = 0; i < n; ++i) {
        char path[64];
        struct stat st;
        FgpSlot cand;
        int fd, ok, index;

        if (fgp_lowest_free() < 0 && (kind == FGP_KIND_JOYDEV || fgp_count_kind(FGP_KIND_JOYDEV) == 0)) {
            return; /* every slot already holds a device of the preferred backend */
        }
        snprintf(path, sizeof(path), "%s/%s%d", FGP_INPUT_DIR, prefix, nums[i]);
        if (fgp_path_in_use(path)) continue;
        if (stat(path, &st) != 0 || !S_ISCHR(st.st_mode)) continue;
        if (fgp_is_rejected(&st)) continue;

        fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue; /* EACCES (not in `input` group / no uaccess), EBUSY, ENODEV ... */

        fgp_slot_reset(&cand);
        cand.kind = kind;
        cand.fd = fd;
        snprintf(cand.path, sizeof(cand.path), "%s", path);
        cand.rdev = st.st_rdev;
        cand.ino = st.st_ino;
        ok = kind == FGP_KIND_EVDEV ? fgp_evdev_probe(&cand) : fgp_joydev_probe(&cand);
        if (!ok) {
            close(fd);
            fgp_remember_reject(&st);
            continue;
        }
        if (kind == FGP_KIND_EVDEV && fgp_count_kind(FGP_KIND_JOYDEV) > 0) {
            fgp_close_all_joydev(); /* evdev now accessible: never report a device twice */
        }
        index = fgp_lowest_free();
        if (index < 0) {
            close(fd);
            return;
        }
        fgp_slot_attach(index, &cand);
    }
}

static void fgp_rescan(void) {
    fgp_scan_kind(FGP_KIND_EVDEV);
    if (fgp_count_kind(FGP_KIND_EVDEV) == 0) {
        fgp_scan_kind(FGP_KIND_JOYDEV);
    }
    g_last_scan_ms = fgp_now_ms();
}

/* ------------------------------------------------------------------------------------------------ */
/* Public ABI                                                                                       */
/* ------------------------------------------------------------------------------------------------ */

FUJI_GAMEPAD_EXPORT int32_t fuji_gamepad_abi_version(void) {
    return FUJI_GAMEPAD_ABI_VERSION;
}

FUJI_GAMEPAD_EXPORT const char* fuji_gamepad_backend_name(void) {
    if (!g_initialized) return "none";
    if (fgp_count_kind(FGP_KIND_JOYDEV) > 0) return "joydev";
    return "evdev";
}

FUJI_GAMEPAD_EXPORT int32_t fuji_gamepad_init(void) {
    int i;
    if (g_initialized) return FUJI_GAMEPAD_OK;
    for (i = 0; i < FUJI_GAMEPAD_MAX_SLOTS; ++i) {
        fgp_slot_reset(&g_slots[i]);
    }
    g_reject_count = 0;
    g_initialized = 1;
    fgp_rescan();
    return FUJI_GAMEPAD_OK;
}

FUJI_GAMEPAD_EXPORT int32_t fuji_gamepad_poll(FujiGamepadState* out, int32_t capacity) {
    int i, n, connected = 0;
    if (out == NULL || capacity <= 0) return FUJI_GAMEPAD_ERR_ARGUMENT;
    if (!g_initialized) return FUJI_GAMEPAD_ERR_STATE;

    if (fgp_now_ms() - g_last_scan_ms >= FGP_RESCAN_MS) {
        fgp_rescan();
    }
    for (i = 0; i < FUJI_GAMEPAD_MAX_SLOTS; ++i) {
        FgpSlot* s = &g_slots[i];
        FujiGamepadState before;
        if (s->kind == FGP_KIND_NONE) continue;
        before = s->pub;
        if (fgp_drain(s) != 0) {
            fgp_slot_disconnect(i);
            continue;
        }
        if (before.buttons != s->pub.buttons || before.lx != s->pub.lx || before.ly != s->pub.ly ||
            before.rx != s->pub.rx || before.ry != s->pub.ry || before.lt != s->pub.lt ||
            before.rt != s->pub.rt) {
            s->pub.sequence = ++g_sequence;
        }
        ++connected;
    }

    n = capacity < FUJI_GAMEPAD_MAX_SLOTS ? capacity : FUJI_GAMEPAD_MAX_SLOTS;
    for (i = 0; i < n; ++i) {
        if (g_slots[i].kind != FGP_KIND_NONE) {
            out[i] = g_slots[i].pub;
        } else {
            memset(&out[i], 0, sizeof(out[i]));
        }
        out[i].slot = i;
    }
    return connected;
}

FUJI_GAMEPAD_EXPORT void fuji_gamepad_shutdown(void) {
    int i;
    if (!g_initialized) return;
    for (i = 0; i < FUJI_GAMEPAD_MAX_SLOTS; ++i) {
        fgp_slot_disconnect(i);
    }
    g_reject_count = 0;
    g_initialized = 0;
}
