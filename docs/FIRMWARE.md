# Firmware & Optional Hardware Dump Guide

**English** | [简体中文](FIRMWARE.zh-CN.md) | [日本語](FIRMWARE.ja.md)

← [Back to README](../README.md) · [User Manual →](manual/USER_MANUAL.md)

---

This repository and its prebuilt portable releases distribute **zero** proprietary FUJIFILM firmware, BootROM, or camera calibration binaries. To boot the emulator, you supply the official, unmodified **FUJIFILM GFX100S Firmware Ver. 2.13 (`GXUP0004.DAT`)** downloaded directly from Fujifilm.

> [!NOTE]
> **Only `GXUP0004.DAT` (v2.13) is required.** Real-hardware `bootrom` and `flash` dumps described in [Part 2](#part-2--optional-real-hardware-dumps-boot-rombin--nor-flashbin) are **100% optional** and not needed for normal photography, menu navigation, lens/filter simulation, or plugin development.

---

## Part 1 — Official GFX100S Firmware Ver. 2.13 (`GXUP0004.DAT`)

### 1. Download from Fujifilm

1. Visit the official Fujifilm GFX100S firmware support page:
   - <https://www.fujifilm-x.com/global/support/download/firmware/cameras/gfx100s/>
2. Read and accept Fujifilm's firmware license agreement, then download **Firmware Ver. 2.13** (`GXUP0004.DAT`).
   - *Important*: Make sure the package version is **2.13** (`GXUP0004.DAT`, approximately `61.5 MB`). Hardware register maps and address catalogs in this emulator are verified specifically against Ver. 2.13.

### 2. Verify the SHA-256 Checksum

Before loading the file, verify that your download matches the exact SHA-256 digest of official GFX100S Ver. 2.13:

| Field | Expected Value |
|---|---|
| **Filename** | `GXUP0004.DAT` |
| **Camera / Version** | FUJIFILM GFX100S — Ver. 2.13 |
| **SHA-256** | `ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12` |

**Windows (PowerShell):**

```powershell
Get-FileHash .\GXUP0004.DAT -Algorithm SHA256
```

**Linux (Terminal):**

```bash
sha256sum GXUP0004.DAT
```

Confirm the printed hash equals `ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12` (case-insensitive on PowerShell).

---

### 3. Install Firmware into the Emulator

#### A. Portable Release Bundles (`Windows .zip` / `Linux .tar.gz` / `app-image`)

Portable releases emulate the camera's unified **64 MiB QSPI NOR Flash** (`<emulator-dir>/flash/nor-flash.bin`, `67,108,864` bytes). On a clean public bundle, `flash/` initially contains only the `PUT_HERE` notice until you initialize `nor-flash.bin` from `GXUP0004.DAT` using any of the methods below:

1. **Graphical Setup (Easiest — Recommended for Beginners)**
   - Launch **`fuji-emulator-gfx100s.exe`** (Windows) or **`./start-gfx100s.sh`** / **`./bin/fuji-emulator-gfx100s`** (Linux).
   - When prompted that firmware is not yet installed (or via the top bar menu **Flash → Flash Firmware (.DAT)...**), select your verified **`GXUP0004.DAT`**.
   - The emulator unpacks the firmware into `<emulator-dir>/flash/nor-flash.bin` (`64 MiB`) and boots into the camera's first-time language and clock setup screen.
   - *(Tip: You can also drop `GXUP0004.DAT` directly into `<emulator-dir>/flash/GXUP0004.DAT` before first launch.)*

2. **One-Shot Command Line Flash**
   - **Windows (PowerShell / CMD)**:
     ```powershell
     .\fuji-emulator-gfx100s.exe --flash-dat C:\Downloads\GXUP0004.DAT
     ```
   - **Linux**:
     ```bash
     ./start-gfx100s.sh --flash-dat ~/Downloads/GXUP0004.DAT
     ```
   - By default, `--flash-dat` writes the firmware partition (`[0x00000000..0x02FE0000)`) while preserving existing settings/calibration (`[0x02FE0000..0x04000000)`) if `flash/nor-flash.bin` already exists. Append `--no-preserve-calibration` to initialize a fresh blank calibration/settings region.

3. **Interactive Console**
   - At the `fuji> ` terminal prompt, run:
     ```text
     /flash write /path/to/GXUP0004.DAT
     ```

Once `<emulator-dir>/flash/nor-flash.bin` is created, all in-camera settings, custom `C1–C6` banks, and registered GF lens caches persist automatically across restarts. You do not need to keep `GXUP0004.DAT` in the folder afterward.

#### B. Source Builds (`./gradlew` / `sh jvm.sh`)

- **If you build a portable release archive from source** (`./gradlew :packageReleaseLinux -PfujiBundleStockBins=false` or `:packageReleaseWindows -PfujiBundleStockBins=false`), extract the archive and follow **Section 3.A** above (`<bundle-root>/flash/nor-flash.bin`).
- **If you run the developer workspace runtime (`local/runtime/`)**:
  - Stage the workspace with `sh jvm.sh prepare`.
  - For unified NOR Flash configs (`norFlashFile: "flash/nor-flash.bin"`): initialize `<runtime-home>/flash/nor-flash.bin` via `--home local/runtime --flash-dat /path/to/GXUP0004.DAT`.
  - For direct `.DAT` developer/discovery configs (`local/runtime/config/emulator.yaml`): place the firmware at **`local/runtime/runtime/firmware/GXUP0004.DAT`** (or any path of your choice) and set in `local/runtime/config/emulator.yaml`:
    ```yaml
    firmwareFile: "runtime/firmware/GXUP0004.DAT"
    cpuEngine: "dynarmic"
    ```
    Or pass `--firmware /path/to/GXUP0004.DAT` on launch (`sh jvm.sh launch --firmware /path/to/GXUP0004.DAT`).

---

## Part 2 — Optional Real-Hardware Dumps (`boot-rom.bin` & `nor-flash.bin`)

> [!CAUTION]
> **Hardware Risk & Zero-Liability Warning**
> Dumping internal ROM or QSPI NOR memory from a physical camera requires running unofficial community research code on your own hardware. Any mistake, power loss, or firmware mismatch can **permanently brick your camera, corrupt factory sensor/IBIS calibration, or void your manufacturer warranty**.
>
> **This project provides NO camera-side exploit or dumping payload, and its authors accept ZERO responsibility for any hardware damage, bricked bodies, or data/financial loss.** Proceed only if you fully understand the risks — **or candidly, ask a trusted friend who has already extracted clean, sanitized dumps.**

### Why Hardware Dumps Are Optional

- **Default Boot Mode (`Flash-Direct`)**: Out of the box, the emulator boots via **Flash-Direct** (`PC = 0x00000000`, loading `boot.bin` from `flash/nor-flash.bin[0x20000..0x3FFFF]`) seeded solely from official `GXUP0004.DAT`. Every camera menu, still/video mode, lens, filter, and UI feature works without hardware dumps.
- **What Real Dumps Add**:
  1. **Sanitized 64 MiB `nor-flash.bin`**: Supplies factory calibration/configuration tables from real GFX100S NOR flash (`[0x02FE0000..0x04000000)`) instead of clean-room synthetic defaults.
  2. **32 KiB SPX4 Mask `boot-rom.bin`**: Allows opting into experimental full-silicon cold boot starting at the SoC mask ROM reset vector (`PC = 0xFFF00000` → Stage-1 IPL → Stage-2 XIP → 4-core SMP `SGI 0` wake → `flash/nor-flash.bin`).

### Step 1 — Mandatory Privacy Sanitization Before Dumping!

> [!WARNING]
> **NEVER share, publish, or upload raw NOR Flash dumps from your personal camera!**
> Partition `bank-14` (`[0x03500000..0x03D40000)`) and user backup sectors (`[0x03D40000..0x04000000)`) store your camera's **unique body serial number, Wi-Fi & Bluetooth MAC addresses, wireless pairing keys, custom settings, and unit-specific CMOS calibration**.

If you extract dumps from your own GFX100S running Ver. 2.13:

1. **Remove personal pairing & user settings on the camera first**:
   - On your physical GFX100S, run **`MENU → SET UP → USER SETTING → RESET → INITIALIZE`** (full Factory Reset) so wireless credentials, custom menus, copyright/author EXIF strings, and user `BKUP` blocks are wiped cleanly before dumping.
2. **Use SD-card research tooling (never blind internal flash writes)**:
   - Refer to the open-source [**fujimod**](https://github.com/justjake/fujimod) ([fujimod.com](https://fujimod.com)) and [**FujiHack**](https://github.com/fujihack/fujihack) projects for documentation on non-destructive SD-card dual-boot / memory-read workflows on Fujifilm cameras.
3. **Keep `bank-14` strictly private**:
   - Even after an in-camera Factory Reset, factory `bank-14` (`0x03500000..0x03D40000`) retains factory serial/MAC identifiers. Keep personal dumps strictly on your own local machine (or scrub serial/MAC fields before sharing with anyone).

### Step 2 — Expected File Sizes, Hashes & Placement Paths

| Dump File | Exact Size | Expected SHA-256 | Portable Release Path | Developer Runtime Path |
|---|---|---|---|---|
| **`nor-flash.bin`** *(64 MiB QSPI NOR)* | `67,108,864` bytes (`64 MiB`) | *Varies per unit / settings* | `<emulator-dir>/flash/nor-flash.bin` | `<runtime-home>/flash/nor-flash.bin` |
| **`boot-rom.bin`** *(32 KiB SPX4 Mask ROM)* | `32,768` bytes (`32 KiB`) | `2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4` | `<emulator-dir>/bootrom/boot-rom.bin` | `<runtime-home>/bootrom/boot-rom.bin` *(or `runtime/firmware/boot-rom.bin`)* |

- **Importing `nor-flash.bin`**:
  - Copy directly to `<emulator-dir>/flash/nor-flash.bin` while the emulator is closed, **or** use **Flash → Import NOR Flash (.bin)...** in the top menu, `--import-flash /path/to/nor-flash.bin` on the CLI, or `/flash import /path/to/nor-flash.bin` in the console.
  - Inspect an installed NOR image anytime with:
    ```bash
    ./start-gfx100s.sh --inspect-flash
    ```
- **Enabling Optional `boot-rom.bin` Cold Boot (`--boot-rom`)**:
  - Simply placing `bootrom/boot-rom.bin` keeps the emulator on the fast, battle-tested **Flash-Direct** path by default.
  - To boot from `PC = 0xFFF00000` through the real 32 KiB mask BootROM, either pass **`--boot-rom`** at launch:
    ```bash
    # Linux
    ./start-gfx100s.sh --boot-rom

    # Windows
    .\fuji-emulator-gfx100s.exe --boot-rom
    ```
    or set `bootRomBoot: true` in `<emulator-dir>/configs/emulator.yaml`.
  - *Fallback & Known Caveat*: If `--boot-rom` / `bootRomBoot: true` is enabled without a valid 32,768-byte `boot-rom.bin`, the emulator logs `WARN [FALLBACK][bootrom]` in `logs/latest.log` and falls back cleanly to Flash-Direct. Under experimental BootROM mode, powering off mid-way through first-boot OOBE and rebooting can stall; complete the initial language/date wizard first or boot via default Flash-Direct.
