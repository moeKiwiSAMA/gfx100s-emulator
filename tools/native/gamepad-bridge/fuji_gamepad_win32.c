/* SPDX-License-Identifier: MIT */
/*
 * fuji_gamepad_win32.c — Windows backend of the fuji_gamepad ABI v1 (fuji_gamepad.dll).
 *
 * XInput is loaded at runtime with LoadLibraryW (xinput1_4 -> xinput1_3 -> xinput9_1_0); the DLL is
 * never linked against xinput, so it imports only KERNEL32.dll and msvcrt.dll.
 *
 * XInputGetState is required. When the DLL also exports the undocumented ordinal 100
 * (XInputGetStateEx, xinput1_3 / xinput1_4) it is preferred because it additionally reports the
 * Guide button as 0x0400 == FUJI_GP_GUIDE; its state layout is a superset of XINPUT_STATE.
 *
 * Empty slots are expensive to query (XInput enumerates the bus), so a disconnected slot is probed
 * at most once per FGP_PROBE_MS.
 */
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

#include <string.h>

#include "fuji_gamepad.h"

#define FGP_PROBE_MS 1000ull
#define FGP_ERROR_DEVICE_NOT_CONNECTED 1167ul
#define FGP_XINPUT_BUTTON_MASK 0xF7FFu /* 0x0800 is unused by XInput */

typedef struct FgpXGamepad {
    WORD  wButtons;
    BYTE  bLeftTrigger;
    BYTE  bRightTrigger;
    SHORT sThumbLX;
    SHORT sThumbLY;
    SHORT sThumbRX;
    SHORT sThumbRY;
} FgpXGamepad;

typedef struct FgpXState {
    DWORD       dwPacketNumber;
    FgpXGamepad Gamepad;
    BYTE        reserved[32]; /* slack for XInputGetStateEx's larger struct */
} FgpXState;

typedef DWORD(WINAPI* FgpXInputGetStateFn)(DWORD, FgpXState*);

typedef struct FgpDll {
    const wchar_t* file;
    const char*    backend;
} FgpDll;

static const FgpDll k_dlls[] = {
    {L"xinput1_4.dll", "xinput1_4"},
    {L"xinput1_3.dll", "xinput1_3"},
    {L"xinput9_1_0.dll", "xinput9_1_0"},
};

static HMODULE             g_module;
static FgpXInputGetStateFn g_get_state;
static const char*         g_backend = "none";
static int                 g_initialized;
static uint64_t            g_sequence;
static ULONGLONG           g_next_probe[FUJI_GAMEPAD_MAX_SLOTS];
static FujiGamepadState    g_slots[FUJI_GAMEPAD_MAX_SLOTS];

static FgpXInputGetStateFn fgp_resolve(HMODULE m, LPCSTR name) {
    FARPROC p = GetProcAddress(m, name);
    if (p == NULL) return NULL;
    return (FgpXInputGetStateFn)(void (*)(void))p;
}

static float fgp_thumb(SHORT v) {
    float f = (float)v / 32767.0f;
    return f < -1.0f ? -1.0f : (f > 1.0f ? 1.0f : f);
}

static void fgp_set_name(FujiGamepadState* s, int index) {
    static const char prefix[] = "XInput Controller (Slot ";
    size_t n = sizeof(prefix) - 1;
    memset(s->name, 0, sizeof(s->name));
    memcpy(s->name, prefix, n);
    s->name[n] = (char)('0' + index);
    s->name[n + 1] = ')';
}

FUJI_GAMEPAD_EXPORT int32_t fuji_gamepad_abi_version(void) {
    return FUJI_GAMEPAD_ABI_VERSION;
}

FUJI_GAMEPAD_EXPORT const char* fuji_gamepad_backend_name(void) {
    return g_initialized ? g_backend : "none";
}

FUJI_GAMEPAD_EXPORT int32_t fuji_gamepad_init(void) {
    size_t i;
    if (g_initialized) return FUJI_GAMEPAD_OK;
    for (i = 0; i < sizeof(k_dlls) / sizeof(k_dlls[0]); ++i) {
        HMODULE m = LoadLibraryW(k_dlls[i].file);
        FgpXInputGetStateFn plain, ex;
        if (m == NULL) continue;
        plain = fgp_resolve(m, "XInputGetState");
        if (plain == NULL) {
            FreeLibrary(m);
            continue;
        }
        ex = fgp_resolve(m, MAKEINTRESOURCEA(100));
        g_module = m;
        g_get_state = ex != NULL ? ex : plain;
        g_backend = k_dlls[i].backend;
        break;
    }
    if (g_module == NULL) {
        g_backend = "none";
        return FUJI_GAMEPAD_ERR_BACKEND;
    }
    memset(g_slots, 0, sizeof(g_slots));
    memset(g_next_probe, 0, sizeof(g_next_probe));
    g_initialized = 1;
    return FUJI_GAMEPAD_OK;
}

FUJI_GAMEPAD_EXPORT int32_t fuji_gamepad_poll(FujiGamepadState* out, int32_t capacity) {
    ULONGLONG now;
    int i, n, connected = 0;
    if (out == NULL || capacity <= 0) return FUJI_GAMEPAD_ERR_ARGUMENT;
    if (!g_initialized) return FUJI_GAMEPAD_ERR_STATE;

    now = GetTickCount64();
    for (i = 0; i < FUJI_GAMEPAD_MAX_SLOTS; ++i) {
        FujiGamepadState* s = &g_slots[i];
        FgpXState xs;
        DWORD rc;
        if (!s->connected && now < g_next_probe[i]) continue;

        memset(&xs, 0, sizeof(xs));
        rc = g_get_state((DWORD)i, &xs);
        if (rc != ERROR_SUCCESS) {
            /* ERROR_DEVICE_NOT_CONNECTED or any other failure: treat as empty slot. */
            (void)FGP_ERROR_DEVICE_NOT_CONNECTED;
            memset(s, 0, sizeof(*s));
            g_next_probe[i] = now + FGP_PROBE_MS;
            continue;
        }
        {
            uint32_t buttons = (uint32_t)xs.Gamepad.wButtons & FGP_XINPUT_BUTTON_MASK;
            float lx = fgp_thumb(xs.Gamepad.sThumbLX);
            float ly = fgp_thumb(xs.Gamepad.sThumbLY);
            float rx = fgp_thumb(xs.Gamepad.sThumbRX);
            float ry = fgp_thumb(xs.Gamepad.sThumbRY);
            float lt = (float)xs.Gamepad.bLeftTrigger / 255.0f;
            float rt = (float)xs.Gamepad.bRightTrigger / 255.0f;
            int fresh = !s->connected;
            if (fresh) {
                memset(s, 0, sizeof(*s));
                s->connected = 1;
                fgp_set_name(s, i);
            }
            if (fresh || s->buttons != buttons || s->lx != lx || s->ly != ly || s->rx != rx ||
                s->ry != ry || s->lt != lt || s->rt != rt) {
                s->buttons = buttons;
                s->lx = lx;
                s->ly = ly;
                s->rx = rx;
                s->ry = ry;
                s->lt = lt;
                s->rt = rt;
                s->sequence = ++g_sequence;
            }
            s->slot = i;
            ++connected;
        }
    }

    n = capacity < FUJI_GAMEPAD_MAX_SLOTS ? capacity : FUJI_GAMEPAD_MAX_SLOTS;
    for (i = 0; i < n; ++i) {
        out[i] = g_slots[i];
        out[i].slot = i;
    }
    return connected;
}

FUJI_GAMEPAD_EXPORT void fuji_gamepad_shutdown(void) {
    if (!g_initialized) return;
    if (g_module != NULL) {
        FreeLibrary(g_module);
    }
    g_module = NULL;
    g_get_state = NULL;
    g_backend = "none";
    memset(g_slots, 0, sizeof(g_slots));
    memset(g_next_probe, 0, sizeof(g_next_probe));
    g_initialized = 0;
}
