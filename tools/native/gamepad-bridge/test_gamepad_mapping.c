/* SPDX-License-Identifier: MIT */
/*
 * test_gamepad_mapping.c — white-box test of the Linux evdev/joydev mapping logic WITHOUT hardware.
 *
 * It #includes fuji_gamepad_linux.c so the static helpers are reachable, feeds hand-built capability
 * bitmaps to the classifier, and pushes synthetic `struct input_event` / `struct js_event` records
 * through non-blocking pipes that stand in for /dev/input nodes (EOF on the pipe exercises the
 * disconnect path; ioctl() on a pipe fails with ENOTTY, which the resync path tolerates).
 */
#include "fuji_gamepad_linux.c"

#include <math.h>
#include <stdint.h>

#define CHECK(cond, msg)                                                      \
    do {                                                                      \
        if (!(cond)) {                                                        \
            fprintf(stderr, "[FAIL] %s:%d: %s\n", __FILE__, __LINE__, (msg)); \
            exit(1);                                                          \
        }                                                                     \
    } while (0)

#define NEAR(a, b) (fabsf((a) - (b)) < 1e-4f)

typedef struct Caps {
    unsigned long keys[FGP_NLONGS(KEY_CNT)];
    unsigned long abs[FGP_NLONGS(ABS_CNT)];
    struct input_absinfo ai[ABS_CNT];
} Caps;

static void caps_key(Caps* c, unsigned code) {
    c->keys[code / FGP_LONG_BITS] |= 1ul << (code % FGP_LONG_BITS);
}

static void caps_abs(Caps* c, unsigned code, int32_t mn, int32_t mx) {
    c->abs[code / FGP_LONG_BITS] |= 1ul << (code % FGP_LONG_BITS);
    c->ai[code].minimum = mn;
    c->ai[code].maximum = mx;
}

static void caps_xpad(Caps* c) {
    static const unsigned keys[] = {BTN_SOUTH, BTN_EAST, BTN_X, BTN_Y, BTN_TL, BTN_TR,
                                    BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR};
    size_t i;
    memset(c, 0, sizeof(*c));
    for (i = 0; i < sizeof(keys) / sizeof(keys[0]); ++i) caps_key(c, keys[i]);
    caps_abs(c, ABS_X, -32768, 32767);
    caps_abs(c, ABS_Y, -32768, 32767);
    caps_abs(c, ABS_RX, -32768, 32767);
    caps_abs(c, ABS_RY, -32768, 32767);
    caps_abs(c, ABS_Z, 0, 1023);
    caps_abs(c, ABS_RZ, 0, 1023);
    caps_abs(c, ABS_HAT0X, -1, 1);
    caps_abs(c, ABS_HAT0Y, -1, 1);
}

/* Builds a configured evdev candidate whose fd is the read end of a fresh non-blocking pipe. */
static int make_evdev(FgpSlot* cand, const Caps* c, const char* path, int* wfd) {
    int fds[2];
    fgp_slot_reset(cand);
    CHECK(pipe(fds) == 0, "pipe");
    CHECK(fcntl(fds[0], F_SETFL, O_NONBLOCK) == 0, "fcntl");
    cand->kind = FGP_KIND_EVDEV;
    cand->fd = fds[0];
    snprintf(cand->path, sizeof(cand->path), "%s", path);
    snprintf(cand->pub.name, sizeof(cand->pub.name), "Fake %s", path);
    *wfd = fds[1];
    return fgp_evdev_setup(cand, c->keys, c->abs, c->ai);
}

static void ev(int wfd, int type, int code, int value) {
    struct input_event e;
    memset(&e, 0, sizeof(e));
    e.type = (unsigned short)type;
    e.code = (unsigned short)code;
    e.value = value;
    CHECK(write(wfd, &e, sizeof(e)) == (ssize_t)sizeof(e), "write input_event");
}

static void js(int wfd, int type, int number, int value) {
    struct js_event e;
    memset(&e, 0, sizeof(e));
    e.type = (unsigned char)type;
    e.number = (unsigned char)number;
    e.value = (short)value;
    CHECK(write(wfd, &e, sizeof(e)) == (ssize_t)sizeof(e), "write js_event");
}

static int poll4(FujiGamepadState* st) {
    int rc = fuji_gamepad_poll(st, FUJI_GAMEPAD_MAX_SLOTS);
    CHECK(rc >= 0, "poll failed");
    return rc;
}

static void expect_zero(const FujiGamepadState* s, int index) {
    FujiGamepadState z;
    memset(&z, 0, sizeof(z));
    z.slot = index;
    CHECK(memcmp(&z, s, sizeof(z)) == 0, "disconnected slot not fully zeroed");
}

int main(void) {
    Caps c;
    FgpSlot cand;
    FujiGamepadState st[FUJI_GAMEPAD_MAX_SLOTS];
    int w0, w1, w2, w3, i;
    uint64_t seq;

    printf("[GAMEPAD-MAPPING] Running hardware-free evdev/joydev mapping test...\n");

    /* 1. normalization */
    CHECK(fgp_norm_stick(0, -32768, 32767) == 0.0f, "stick centre");
    CHECK(fgp_norm_stick(32767, -32768, 32767) == 1.0f, "stick max");
    CHECK(fgp_norm_stick(-32768, -32768, 32767) == -1.0f, "stick min");
    CHECK(fgp_norm_stick(0, 0, 255) == -1.0f && fgp_norm_stick(255, 0, 255) == 1.0f, "unsigned stick");
    CHECK(NEAR(fgp_norm_stick(128, 0, 256), 0.0f), "unsigned stick centre");
    CHECK(fgp_norm_stick(99999, -100, 100) == 1.0f, "stick clamp");
    CHECK(fgp_norm_trigger(0, 0, 1023) == 0.0f && fgp_norm_trigger(1023, 0, 1023) == 1.0f, "trigger ends");
    CHECK(NEAR(fgp_norm_trigger(0, -255, 255), 0.5f), "signed trigger");
    CHECK(fgp_hat_dir(-1, -1, 1) == -1 && fgp_hat_dir(0, -1, 1) == 0 && fgp_hat_dir(1, -1, 1) == 1, "hat");
    printf("  [PASS] 1. stick/trigger/hat normalization (exact 0 at centre, clamped, no deadzone)\n");

    /* 2. classification */
    caps_xpad(&c);
    fgp_slot_reset(&cand);
    CHECK(fgp_evdev_setup(&cand, c.keys, c.abs, c.ai) == 1, "xpad not a gamepad");
    CHECK(cand.cal[FGP_AX_RX].code == ABS_RX && cand.cal[FGP_AX_RY].code == ABS_RY, "xpad right stick");
    CHECK(cand.cal[FGP_AX_LT].code == ABS_Z && cand.cal[FGP_AX_RT].code == ABS_RZ, "xpad triggers");
    CHECK(!cand.lt_digital && !cand.rt_digital && !cand.generic_joystick, "xpad flags");

    memset(&c, 0, sizeof(c)); /* keyboard */
    caps_key(&c, KEY_A);
    caps_key(&c, KEY_ENTER);
    CHECK(fgp_evdev_setup(&cand, c.keys, c.abs, c.ai) == 0, "keyboard accepted");
    memset(&c, 0, sizeof(c)); /* accelerometer / motion sensor */
    caps_abs(&c, ABS_X, -32768, 32767);
    caps_abs(&c, ABS_Y, -32768, 32767);
    caps_abs(&c, ABS_Z, -32768, 32767);
    CHECK(fgp_evdev_setup(&cand, c.keys, c.abs, c.ai) == 0, "accelerometer accepted");
    memset(&c, 0, sizeof(c)); /* touchpad */
    caps_key(&c, BTN_TOUCH);
    caps_key(&c, BTN_LEFT);
    caps_abs(&c, ABS_X, 0, 1000);
    CHECK(fgp_evdev_setup(&cand, c.keys, c.abs, c.ai) == 0, "touchpad accepted");
    memset(&c, 0, sizeof(c)); /* gamepad buttons but no ABS_X */
    caps_key(&c, BTN_SOUTH);
    CHECK(fgp_evdev_setup(&cand, c.keys, c.abs, c.ai) == 0, "pad without ABS_X accepted");

    memset(&c, 0, sizeof(c)); /* generic HID joystick: right stick on Z/RZ */
    caps_key(&c, BTN_TRIGGER);
    caps_key(&c, BTN_THUMB);
    caps_abs(&c, ABS_X, 0, 255);
    caps_abs(&c, ABS_Y, 0, 255);
    caps_abs(&c, ABS_Z, 0, 255);
    caps_abs(&c, ABS_RZ, 0, 255);
    fgp_slot_reset(&cand);
    CHECK(fgp_evdev_setup(&cand, c.keys, c.abs, c.ai) == 1, "generic joystick rejected");
    CHECK(cand.generic_joystick, "generic flag");
    CHECK(cand.cal[FGP_AX_RX].code == ABS_Z && cand.cal[FGP_AX_RY].code == ABS_RZ, "Z/RZ right stick");
    CHECK(cand.cal[FGP_AX_LT].code < 0 && cand.cal[FGP_AX_RT].code < 0, "generic no triggers");

    memset(&c, 0, sizeof(c)); /* digital triggers, BRAKE/GAS */
    caps_key(&c, BTN_SOUTH);
    caps_key(&c, BTN_TL2);
    caps_key(&c, BTN_TR2);
    caps_abs(&c, ABS_X, -128, 127);
    caps_abs(&c, ABS_RX, -128, 127);
    caps_abs(&c, ABS_GAS, 0, 255);
    fgp_slot_reset(&cand);
    CHECK(fgp_evdev_setup(&cand, c.keys, c.abs, c.ai) == 1, "digital pad rejected");
    CHECK(cand.lt_digital && !cand.rt_digital, "lt digital, rt via ABS_GAS");
    CHECK(cand.cal[FGP_AX_RT].code == ABS_GAS, "ABS_GAS -> rt");
    printf("  [PASS] 2. classification: xpad/generic/digital-trigger pads accepted; keyboard, accelerometer,\n"
           "              touchpad, pad-without-ABS_X rejected; Z/RZ->right stick only when RX/RY absent\n");

    /* 3. evdev event flow through a pipe */
    g_initialized = 1;
    g_last_scan_ms = INT64_MAX / 2; /* suppress /dev/input rescans during the white-box test */
    caps_xpad(&c);
    CHECK(make_evdev(&cand, &c, "fake/event0", &w0) == 1, "setup");
    fgp_slot_attach(0, &cand);
    CHECK(poll4(st) == 1, "one connected");
    CHECK(st[0].connected == 1 && st[0].slot == 0 && st[0].sequence > 0, "attached");
    CHECK(strcmp(st[0].name, "Fake fake/event0") == 0, "name");
    CHECK(strcmp(fuji_gamepad_backend_name(), "evdev") == 0, "backend evdev");
    expect_zero(&st[1], 1);
    seq = st[0].sequence;

    ev(w0, EV_KEY, BTN_SOUTH, 1);
    ev(w0, EV_KEY, BTN_MODE, 1);
    ev(w0, EV_KEY, BTN_SELECT, 1);
    ev(w0, EV_KEY, BTN_THUMBR, 1);
    ev(w0, EV_ABS, ABS_X, 32767);
    ev(w0, EV_ABS, ABS_Y, -32768);  /* physical up */
    ev(w0, EV_ABS, ABS_RX, -32768);
    ev(w0, EV_ABS, ABS_RY, 32767);  /* physical down */
    ev(w0, EV_ABS, ABS_Z, 1023);
    ev(w0, EV_ABS, ABS_RZ, 0);
    ev(w0, EV_ABS, ABS_HAT0X, -1);
    ev(w0, EV_ABS, ABS_HAT0Y, 1);
    ev(w0, EV_SYN, SYN_REPORT, 0);
    poll4(st);
    CHECK(st[0].buttons == (FUJI_GP_A | FUJI_GP_GUIDE | FUJI_GP_BACK | FUJI_GP_R3 | FUJI_GP_DPAD_LEFT |
                            FUJI_GP_DPAD_DOWN), "evdev buttons + hat");
    CHECK(st[0].lx == 1.0f && st[0].ly == 1.0f && st[0].rx == -1.0f && st[0].ry == -1.0f, "evdev sticks (+Y up)");
    CHECK(st[0].lt == 1.0f && st[0].rt == 0.0f, "evdev triggers");
    CHECK(st[0].sequence > seq, "sequence bumped on change");
    seq = st[0].sequence;
    poll4(st);
    CHECK(st[0].sequence == seq, "sequence stable without input");

    ev(w0, EV_KEY, BTN_SOUTH, 2); /* autorepeat ignored */
    ev(w0, EV_KEY, BTN_DPAD_UP, 1);
    ev(w0, EV_ABS, ABS_HAT0Y, 0);
    ev(w0, EV_ABS, ABS_HAT0X, 0);
    ev(w0, EV_KEY, BTN_MODE, 0);
    ev(w0, EV_SYN, SYN_REPORT, 0);
    poll4(st);
    CHECK(st[0].buttons == (FUJI_GP_A | FUJI_GP_BACK | FUJI_GP_R3 | FUJI_GP_DPAD_UP), "dpad buttons/hat release");
    CHECK(st[0].sequence > seq, "sequence bumped again");

    ev(w0, EV_SYN, SYN_DROPPED, 0);
    ev(w0, EV_KEY, BTN_EAST, 1); /* must be discarded until SYN_REPORT */
    ev(w0, EV_SYN, SYN_REPORT, 0);
    ev(w0, EV_KEY, BTN_Y, 1);
    ev(w0, EV_SYN, SYN_REPORT, 0);
    poll4(st);
    CHECK((st[0].buttons & FUJI_GP_B) == 0, "events after SYN_DROPPED discarded");
    CHECK((st[0].buttons & FUJI_GP_Y) != 0, "events after resync applied");

    close(w0); /* EOF == device gone */
    CHECK(poll4(st) == 0, "disconnect on EOF");
    expect_zero(&st[0], 0);
    printf("  [PASS] 3. evdev: buttons, hat->dpad, BTN_DPAD_*, Y inversion, triggers, autorepeat ignored,\n"
           "              SYN_DROPPED discard+resync, sequence semantics, EOF -> zeroed slot\n");

    /* 4. digital triggers + xpad dpad_to_buttons */
    memset(&c, 0, sizeof(c));
    caps_key(&c, BTN_SOUTH);
    caps_key(&c, BTN_TL2);
    caps_key(&c, BTN_TR2);
    caps_key(&c, BTN_TRIGGER_HAPPY1);
    caps_key(&c, BTN_TRIGGER_HAPPY2);
    caps_key(&c, BTN_TRIGGER_HAPPY3);
    caps_key(&c, BTN_TRIGGER_HAPPY4);
    caps_abs(&c, ABS_X, -32768, 32767);
    caps_abs(&c, ABS_Y, -32768, 32767);
    CHECK(make_evdev(&cand, &c, "fake/event1", &w0) == 1, "setup digital");
    CHECK(cand.lt_digital && cand.rt_digital && cand.happy_dpad, "digital flags");
    fgp_slot_attach(0, &cand);
    ev(w0, EV_KEY, BTN_TL2, 1);
    ev(w0, EV_KEY, BTN_TRIGGER_HAPPY3, 1);
    ev(w0, EV_KEY, BTN_TRIGGER_HAPPY2, 1);
    poll4(st);
    CHECK(st[0].lt == 1.0f && st[0].rt == 0.0f, "digital lt");
    CHECK(st[0].buttons == (FUJI_GP_DPAD_UP | FUJI_GP_DPAD_RIGHT), "happy dpad");
    close(w0);
    poll4(st);
    printf("  [PASS] 4. BTN_TL2/TR2 -> lt/rt 0|1 when no analog axis; xpad BTN_TRIGGER_HAPPY1..4 -> dpad\n");

    /* 5. joydev through a pipe */
    {
        int fds[2];
        CHECK(pipe(fds) == 0, "pipe");
        CHECK(fcntl(fds[0], F_SETFL, O_NONBLOCK) == 0, "fcntl");
        fgp_slot_reset(&cand);
        cand.kind = FGP_KIND_JOYDEV;
        cand.fd = fds[0];
        snprintf(cand.path, sizeof(cand.path), "fake/js0");
        snprintf(cand.pub.name, sizeof(cand.pub.name), "Fake js0");
        w1 = fds[1];
        js(w1, JS_EVENT_AXIS | JS_EVENT_INIT, 2, -32767);
        js(w1, JS_EVENT_AXIS | JS_EVENT_INIT, 5, -32767);
        js(w1, JS_EVENT_BUTTON | JS_EVENT_INIT, 0, 1);
        fgp_slot_attach(0, &cand);
        poll4(st);
        CHECK(strcmp(fuji_gamepad_backend_name(), "joydev") == 0, "backend joydev");
        CHECK(st[0].connected && st[0].lt == 0.0f && st[0].rt == 0.0f && st[0].buttons == FUJI_GP_A, "js init");
        js(w1, JS_EVENT_AXIS, 0, 32767);
        js(w1, JS_EVENT_AXIS, 1, -32767);
        js(w1, JS_EVENT_AXIS, 3, -32767);
        js(w1, JS_EVENT_AXIS, 4, 32767);
        js(w1, JS_EVENT_AXIS, 5, 32767);
        js(w1, JS_EVENT_AXIS, 6, 32767);
        js(w1, JS_EVENT_AXIS, 7, -32767);
        js(w1, JS_EVENT_BUTTON, 0, 0);
        js(w1, JS_EVENT_BUTTON, 8, 1);
        js(w1, JS_EVENT_BUTTON, 6, 1);
        js(w1, JS_EVENT_BUTTON, 11, 1); /* beyond xpad layout: ignored */
        poll4(st);
        CHECK(st[0].lx == 1.0f && st[0].ly == 1.0f && st[0].rx == -1.0f && st[0].ry == -1.0f, "js sticks");
        CHECK(st[0].rt == 1.0f && st[0].lt == 0.0f, "js triggers [-1,1]->[0,1]");
        CHECK(st[0].buttons == (FUJI_GP_GUIDE | FUJI_GP_BACK | FUJI_GP_DPAD_RIGHT | FUJI_GP_DPAD_UP), "js buttons");
        /* An evdev pad becoming accessible closes every joydev slot (never report twice). */
        fgp_close_all_joydev();
        CHECK(poll4(st) == 0 && strcmp(fuji_gamepad_backend_name(), "evdev") == 0, "joydev closed");
        expect_zero(&st[0], 0);
        close(w1);
    }
    printf("  [PASS] 5. joydev: xpad axis/button layout, INIT events, hat axes 6/7 -> dpad, lt/rt remap,\n"
           "              joydev slots dropped when evdev takes over\n");

    /* 6. stable lowest-free slot assignment */
    caps_xpad(&c);
    make_evdev(&cand, &c, "fake/event10", &w0);
    fgp_slot_attach(fgp_lowest_free(), &cand);
    make_evdev(&cand, &c, "fake/event11", &w1);
    fgp_slot_attach(fgp_lowest_free(), &cand);
    make_evdev(&cand, &c, "fake/event12", &w2);
    fgp_slot_attach(fgp_lowest_free(), &cand);
    CHECK(poll4(st) == 3, "three connected");
    CHECK(strcmp(st[2].name, "Fake fake/event12") == 0, "third in slot 2");
    seq = st[2].sequence;
    close(w1);
    CHECK(poll4(st) == 2, "middle disconnected");
    expect_zero(&st[1], 1);
    CHECK(strcmp(st[0].name, "Fake fake/event10") == 0 && strcmp(st[2].name, "Fake fake/event12") == 0,
          "survivors keep their slots");
    CHECK(fgp_path_in_use("fake/event12") && !fgp_path_in_use("fake/event11"), "path tracking");
    make_evdev(&cand, &c, "fake/event13", &w3);
    CHECK(fgp_lowest_free() == 1, "lowest free is 1");
    fgp_slot_attach(1, &cand);
    make_evdev(&cand, &c, "fake/event14", &w1);
    fgp_slot_attach(fgp_lowest_free(), &cand);
    CHECK(poll4(st) == 4 && fgp_lowest_free() == -1, "four connected, none free");
    CHECK(strcmp(st[1].name, "Fake fake/event13") == 0 && strcmp(st[3].name, "Fake fake/event14") == 0, "reuse");
    CHECK(st[1].sequence > seq && st[3].sequence > st[1].sequence, "global sequence monotonic across reconnects");
    for (i = 0; i < 4; ++i) CHECK(st[i].slot == i, "slot index");
    fuji_gamepad_shutdown();
    CHECK(g_slots[0].fd == -1 && g_slots[3].fd == -1, "shutdown closed fds");
    close(w0);
    close(w1);
    close(w2);
    close(w3);
    printf("  [PASS] 6. lowest-free stable slots, survivors keep slots, reuse of freed slot, 4-slot cap\n");

    printf("[GAMEPAD-MAPPING] ALL CHECKS PASSED\n");
    return 0;
}
