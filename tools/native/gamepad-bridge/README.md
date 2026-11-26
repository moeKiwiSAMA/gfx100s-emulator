# `fuji_gamepad` — Zero-Dependency Native Gamepad Polling Bridge (ABI v1)

Small C11 library polled from the JVM `GamepadPoller` daemon via FFM. One polling thread, no
callbacks, no allocations, and the library never keeps a caller pointer. The ABI is in
[`fuji_gamepad.h`](fuji_gamepad.h) and is frozen: `FujiGamepadState` is 176 bytes, with XInput-style
`FUJI_GP_*` button bits.

| Platform | Source | Artifact | Backend(s) |
|---|---|---|---|
| Linux x86_64 | `fuji_gamepad_linux.c` | `libfuji_gamepad.so` | kernel **evdev** (`/dev/input/event*`); **joydev** (`/dev/input/js*`) fallback |
| Windows x86_64 | `fuji_gamepad_win32.c` | `fuji_gamepad.dll` | **XInput**, loaded at runtime (`xinput1_4` → `xinput1_3` → `xinput9_1_0`) |

## ABI summary

- `fuji_gamepad_init()` is idempotent and returns `OK`. On Windows it returns `ERR_BACKEND` when no XInput DLL loads. Having zero controllers is not an error.
- `fuji_gamepad_poll(out, capacity)` fills `out[0..min(capacity,4)-1]`, where `out[i].slot == i`. It returns the number of connected controllers. It returns `ERR_ARGUMENT` for a NULL buffer or `capacity <= 0`, and `ERR_STATE` before init or after shutdown.
- Axes: `lx/rx` lie in [-1,1] with + meaning right. `ly/ry` lie in [-1,1] with + meaning **up**. `lt/rt` lie in [0,1]. **No deadzone** is applied in C.
- Slots: there are at most 4, and each device gets the lowest free index and keeps it while connected. A disconnected slot is fully zeroed with `name == ""`.
- `sequence` comes from one library-wide counter. A slot's value is updated on connect and on every input change, so it keeps increasing across reconnects.
- Hot-plug: Linux rescans `/dev/input` at most once per second, and treats `ENODEV` or EOF as a disconnect. Windows probes empty XInput slots at most once per second.
- `fuji_gamepad_backend_name()` returns `"none"` when not initialized. On Linux it returns `"joydev"` while joydev devices are in use and `"evdev"` otherwise, including when zero devices are present. On Windows it returns the name of the loaded XInput DLL (`"xinput1_4"`, …).

## Mapping

| `FUJI_GP_*` | evdev | joydev (xpad layout) | XInput |
|---|---|---|---|
| A / B / X / Y | `BTN_SOUTH` / `BTN_EAST` / `BTN_X` / `BTN_Y` | button 0 / 1 / 2 / 3 | native |
| LB / RB | `BTN_TL` / `BTN_TR` | 4 / 5 | native |
| BACK / START / GUIDE | `BTN_SELECT` / `BTN_START` / `BTN_MODE` | 6 / 7 / 8 | native; GUIDE only via `XInputGetStateEx` (ordinal 100) |
| L3 / R3 | `BTN_THUMBL` / `BTN_THUMBR` | 9 / 10 | native |
| DPAD_* | `BTN_DPAD_*`, `ABS_HAT0X/Y`, xpad `BTN_TRIGGER_HAPPY1..4` | hat axes 6 / 7 | native |
| lx, ly | `ABS_X`, `-ABS_Y` | axis 0, −axis 1 | `sThumbL* / 32767` |
| rx, ry | `ABS_RX`, `-ABS_RY` (`ABS_Z`, `-ABS_RZ` when the pad has no RX/RY) | axis 3, −axis 4 | `sThumbR* / 32767` |
| lt, rt | `ABS_Z` / `ABS_RZ`, else `ABS_BRAKE` / `ABS_GAS`, else `BTN_TL2` / `BTN_TR2` as 0/1 | (axis 2 / 5 + 1) / 2 | `b*Trigger / 255` |

How Linux picks devices:
- An evdev node counts as a gamepad when it has `BTN_GAMEPAD` or `BTN_JOYSTICK` plus `ABS_X`.
- `BTN_JOYSTICK`-class devices map `BTN_TRIGGER+n` using the xpad button order.
- joydev is scanned **only** when no evdev gamepad is open. When an evdev gamepad becomes accessible, every joydev slot is closed, so a device is never reported twice.

## Build (artifacts go to the gitignored `.tools/gamepad-bridge/`)

```sh
./tools/native/gamepad-bridge/build_gamepad.sh linux   # .so + test_gamepad_bridge + test_gamepad_mapping (both run)
./tools/native/gamepad-bridge/build_gamepad.sh win64   # .dll (+ test exe); fails if the DLL imports anything but KERNEL32/msvcrt
./tools/native/gamepad-bridge/build_gamepad.sh all
```

Overrides: `CC`, `FUJI_MINGW64_GCC`, `FUJI_MINGW64_OBJDUMP`, `FUJI_GAMEPAD_BUILD_HOME`. Each output
directory gets a `source.properties` file (`abiVersion=1`, size, SHA-256).

- `test_gamepad_bridge.c` is a black-box ABI self-test against the built library. It passes with no controller attached.
- `test_gamepad_mapping.c` is a white-box Linux test. It `#include`s the backend and feeds synthetic `input_event` / `js_event` records through pipes, so the mapping is checked without hardware.

## Linux permissions

Most distributions make `/dev/input/event*` readable only by `root` and the `input` group. On a
desktop, systemd-logind usually grants the active seat user access to joysticks through a
**uaccess** ACL (udev `70-uaccess.rules`). If no controller shows up:

```sh
ls -l /dev/input/event* && getfacl /dev/input/eventN   # check access
sudo usermod -aG input "$USER"                          # or: add to the input group, then log in again
```

Nodes that cannot be opened (`EACCES`) are skipped silently, and they are retried on the next
once-per-second rescan.

## Desktop integration (`desktop/.../input/`)

- **Controls / 按键绑定** header button opens the non-blocking `InputBindingsDialog`: per-action keyboard + gamepad
  cells, live capture (`Esc` cancels), conflict moves with a flagged row, `×` clear, *Clear All Keyboard*,
  *Reset Gamepad Default*, deadzone (0.10–0.50), two-stage RT toggle and a **Controller** selector.
- Config lives in `<home>/input-bindings.yaml` (`schema: 1`). **The keyboard map is empty by default**; the gamepad
  ships an ergonomic profile (RT ½ = S1, RT full = S2, RB = AF-ON, LB = Q, LT ½ = DRIVE, D-pad/LS = directions,
  L3 = focus lever press, RS = rear/front dials, R3 = rear dial press, A/B/X/Y = MENU-OK/DISP-BACK/Play/Delete,
  Back = VIEW MODE, Start = Fn1). A corrupt file logs `WARN [FALLBACK][input-bindings]` and uses the defaults.
- `selectedGamepad`: `auto` (first connected), `slot:<0..3>`, or `<slot>:<name>` (that device only, followed when it
  re-plugs into another slot). Other controllers are ignored while one is pinned; unplugging it releases every held
  action.
- The library is optional: it is looked up via `-Dfuji.gamepad.library`, `FUJI_GAMEPAD_LIBRARY`, `runtime/native/`
  and `.tools/gamepad-bridge/build-*/`; release staging copies it only when built (`./gradlew :buildGamepadNative`,
  `:buildGamepadNativeWindowsCross`). Without it the dialog shows "bridge unavailable" and keyboard shortcuts still
  work (`WARN [FALLBACK][gamepad]`).
- Held directions are sent as held body buttons (the firmware repeats them like real keys); only dial / lens STEP
  actions auto-repeat host-side (400 ms delay, 125 ms interval).
