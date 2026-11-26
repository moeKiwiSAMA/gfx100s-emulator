<div align="center">

# FUJIFILM GFX100S Emulator

*MEDIUM FORMAT SYSTEM EMULATOR · 富士フイルム GFX100S エミュレータ*

**English** | [简体中文](README.zh-CN.md) | [日本語](README.ja.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform: Windows x86_64 | Linux x86_64](https://img.shields.io/badge/Platform-Windows%20x86__64%20%7C%20Linux%20x86__64-informational)
![Firmware: GFX100S v2.13](https://img.shields.io/badge/Firmware-GFX100S%20v2.13-black)
![Zero Proprietary Binaries](https://img.shields.io/badge/Binaries-Zero%20Proprietary%20Included-success)

*AArch32 4-Core SMP Dynarmic JIT · Vulkan 1.1 Computational Optics · Six-Face Tactile Camera Studio*

</div>

---

> [!CAUTION]
> **Independent Clean-Room & AI-Assisted Engineering Disclaimer**
>
> This is an independent, non-affiliated clean-room software project built with heavy AI-assisted engineering ("AI-slop" included, warts and all). It ships **zero** proprietary Fujifilm firmware, BootROM, or factory calibration binaries.
>
> Emulated SoC peripherals, interrupt timing, and optical/ISP models approximate the behavior needed to boot and operate the camera OS on a PC — **they can and do diverge from real GFX100S silicon**.
>
> Do **not** treat this codebase as an authoritative hardware specification. If you use this emulator or observations from it as a basis for reverse-engineering, custom firmware modification, or hardware experiments on a physical camera, and your real camera **bricks, loses factory calibration, voids its warranty, or suffers any other damage or financial/data loss**, **the author(s) and contributors bear ZERO responsibility — you act 100% at your own risk.**

---

## *The Joy of Medium Format — 写真機としての歓び*

There is a quiet audacity in what Fujifilm built with the GFX system. For decades, medium format belonged almost exclusively to tethered studio stands and deliberate, unhurried tripods. Fujifilm took a 43.8 × 32.9 mm sensor — over 1.7× the area of full-frame — and put it inside a weather-sealed body you can carry into rain, mountain wind, and crowded streets. More than the numbers, they kept faith with how a camera *feels* in the hand: physical dials that click with mechanical certainty, aperture rings with real detents, a two-stage shutter release that asks you to pause and breathe, and nearly ninety years of color memory distilled into PROVIA, Velvia, ASTIA, Classic Chrome, ACROS, and ETERNA — where light feels remembered rather than merely recorded.

This project exists because we love that craft:

- **We wholeheartedly wish everyone could own a real Fujifilm camera.** Nothing rendered on a monitor can ever replace cold magnesium alloy against your palm, the damped resistance of a GF lens ring, or real glass gathering real photons.
- **For students, dreamers, and photographers whose budget isn't quite there yet**, we hope this emulator gives you a window into Fujifilm's medium-format ergonomics, menu philosophy, and optical character on your own desk until the day you pick up the real thing.
- **And for existing GFX100S / GFX owners**, we hope it serves as a playful, risk-free sandbox to explore how your camera thinks, test lighting and focal lengths, and discover new creative possibilities in the instrument you already hold.

---

## Platform & Hardware Requirements

This emulator relies on hand-tuned `x86_64` JIT code generation and SIMD imaging bridges. Please check your host machine before downloading:

| Component | Requirement | Notes |
|---|---|---|
| **OS & Architecture** | **64-bit `x86_64` (`amd64`) Windows 10/11 or Linux ONLY** | **Apple Silicon (`macOS arm64`) and Linux `aarch64` are NOT supported.** `fuji_dynarmic` and the native SIMD imaging bridges target `x86_64` execution paths directly. |
| **CPU Instruction Set** | **`AVX2` or `AVX-512` strongly recommended** | Used intensively by the 4-core ARMv7-A Dynarmic JIT, SIMD renderer, CPU ISP, and spatial optical filter bridges (partial SSE2 fallback exists, but modern `AVX2`/`AVX-512` CPUs are the baseline). |
| **GPU (Recommended)** | **Vulkan 1.1+ capable GPU** (`vulkan-1.dll` / `libvulkan.so.1`) | Enables real-time GPU 3D scene rasterization, 2×2 pyramid bokeh convolution, and optical filter compute shaders. Systems without Vulkan automatically fall back to the multi-threaded SIMD CPU optical pipeline (logging `WARN [FALLBACK][vulkan]`). |
| **System Memory** | **8 GB+ RAM recommended** | Models the GFX100S 40-bit LPDDR4 address space off-heap alongside a `-Xmx2g` JVM desktop heap. |

---

## What's Inside

- **Unmodified GFX100S v2.13 Firmware Execution**: Boots stock Fujifilm GFX100S v2.13 firmware (`0` firmware binary patches) on a 4-core SMP ARMv7-A + NEON/VFPv4 **Dynarmic JIT** (`cpuEngine: dynarmic`), modeling 64 MiB QSPI NOR Flash (`MT25QU512ABB` sector erase & page-program semantics), Sub-MCU coin-cell NVRAM, 102MP still capture (zero-dependency multi-threaded C JPEG encoder, `LSRAW` lossless/compressed RAW, 400MP Pixel Shift, and in-camera RAW conversion), plus 1080p H.264/HEVC `.MOV` recording and playback.
- **Six-Face Tactile Camera Studio & Theme System v2**: Interactive Rear, Top, Front, Left/Right Side port covers, and Dual SD / Battery doors; shoulder Sub-LCD; borderless always-on-top **Floating EVF**; rotating **Mode Dial (`P/S/A/M/C1..C6`)**, **`S/C/M` Focus Lever**, and two-stage **`S1`/`S2` Shutter**; 4 built-in Body Themes (**GFX100S**, **Hasselblad X2D II 100C**, **Hasselblad 907X & CFV 100C**, **Compact Rangefinder**), 5 UI CSS themes, and instant 6-language switching (`en`, `zh_CN`, `ja_JP`, `de_DE`, `fr_FR`, `es_ES`).
- **Modular Lenses, Universal Optical Filter Stack & Vulkan 1.1 Optics**: 12 hot-swappable native GF & adapted classic lenses (`lenses/*.jar` with bidirectional `LENSCOM` aperture/focus/OIS sync, focus breathing, LoCA, and cat's-eye vignetting), a 12-slot universal matte-box filter stack (ND, GND, Black Mist, Starburst, physical UV, shaped/custom Bokeh Pupil Masks, custom `.spv` shaders), 5-axis IBIS & portrait orientation sensor, 5 built-in 3D depth scenes (`qr-ball`, `star-bokeh`, `mona-lisa`, `milk-frog` with synchronized audio, `depth-studio`), webcam, video/stream ingest, screen/game capture, and full-duplex host audio (`WASAPI` / `PipeWire` / `PulseAudio` loopback).

---

## Quick Start

### Option A — 1-Click Portable Download (Recommended — No Java Required)

Every portable bundle ships with a trimmed **JDK 25 (`jlink`) runtime** and all prebuilt `x86_64` native libraries. You do **not** need to install Java, CMake, or any compiler.

1. **Download the portable bundle** for your OS:
   - **Stable Releases**: [**GitHub Releases**](https://github.com/moeKiwiSAMA/gfx100s-emulator/releases)
   - **Latest CI Builds**: [**GitHub Actions**](https://github.com/moeKiwiSAMA/gfx100s-emulator/actions) → open the latest green **Build & Package** workflow run → download `gfx100s-emulator-windows-x86_64` or `gfx100s-emulator-linux-x86_64` under **Artifacts** *(you must be signed into GitHub to download Actions artifacts)*.
2. **Download Official Fujifilm GFX100S Firmware v2.13 (`GXUP0004.DAT`)** — see [Firmware & Optional Hardware Dumps](#firmware--optional-hardware-dumps) below.
3. **Extract & Launch**:
   - **Windows (`x86_64`)**: Extract the `.zip` into any folder and double-click **`fuji-emulator-gfx100s.exe`** (or **`start-gfx100s.bat`**).
   - **Linux (`x86_64`)**: Extract the archive and run **`./start-gfx100s.sh`** (or **`./bin/fuji-emulator-gfx100s`** inside the `app-image` bundle).
4. **Install Firmware on First Launch**:
   - If `flash/nor-flash.bin` has not been initialized yet, the emulator opens a **Firmware Setup** dialog where you can point it to your downloaded `GXUP0004.DAT` (or use **Flash → Flash Firmware (.DAT)...**, or pass `--flash-dat <path/to/GXUP0004.DAT>`). It writes the 64 MiB `flash/nor-flash.bin` image and boots straight into the GFX100S first-time language and clock setup.

### Option B — Building from Source (Developers & Modders)

Prerequisites: **Git**, **Java 17+** (for bootstrap; `sh jvm.sh setup-jvm` fetches **JDK 25** automatically, or point `JAVA_HOME` at an existing JDK 25), and a **C/C++20 + CMake + Ninja** toolchain (`gcc`/`g++` on Linux; MSYS2 MinGW-w64 on Windows or `x86_64-w64-mingw32-g++` for cross-builds).

```bash
# 1. Bootstrap JDK 25 into .tools/ (or set JAVA_HOME to an installed JDK 25)
sh jvm.sh setup-jvm

# 2. Build the mandatory x86_64 native bridges (Dynarmic JIT, Audio, SIMD Render/ISP, Vulkan Optics, Codec, Gamepad)
./gradlew --no-daemon \
  :cpu-dynarmic:buildDynarmicNative \
  :buildAudioNative \
  :buildRenderNative \
  :buildVulkanOpticsNative \
  :buildIspSimdNative \
  :buildCodecNative \
  :buildGamepadNative

# 3a. Package a self-contained portable Linux release (clean, zero stock binaries):
./gradlew --no-daemon :packageReleaseLinux -PfujiBundleStockBins=false
# -> Output in build/distributions/

# 3b. Or stage a local developer runtime in local/runtime/ and launch:
sh jvm.sh prepare
sh jvm.sh launch
```

*(On Windows PowerShell/CMD without Git Bash, run `java tools/EmulatorTools.java setup-jvm` and `.\gradlew.bat ...`.)*

---

## Firmware & Optional Hardware Dumps

### Required — Official Fujifilm GFX100S Firmware Ver. 2.13 (`GXUP0004.DAT`)

1. Download **`GXUP0004.DAT`** directly from Fujifilm's official support page:
   <https://www.fujifilm-x.com/global/support/download/firmware/cameras/gfx100s/>
2. Verify its **SHA-256** checksum before flashing:
   ```text
   ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12
   ```
   - **Windows PowerShell**: `Get-FileHash .\GXUP0004.DAT -Algorithm SHA256`
   - **Linux**: `sha256sum GXUP0004.DAT`
3. Install into `<emulator-dir>/flash/nor-flash.bin` via the first-launch prompt, **Flash → Flash Firmware (.DAT)...**, or CLI:
   - **Windows**: `.\fuji-emulator-gfx100s.exe --flash-dat path\to\GXUP0004.DAT`
   - **Linux**: `./start-gfx100s.sh --flash-dat /path/to/GXUP0004.DAT`

### Optional — Real-Hardware `bootrom` & `flash` Dumps (Full Silicon Cold-Boot)

By default, the emulator boots cleanly via **Flash-Direct** using only official `GXUP0004.DAT` — **hardware dumps are completely optional**.

Owners of a physical GFX100S can optionally extract the 32 KiB mask BootROM (`bootrom/boot-rom.bin`, SHA-256 `2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4`, enabled via `--boot-rom` or `bootRomBoot: true` in `configs/emulator.yaml`) and 64 MiB QSPI NOR Flash (`flash/nor-flash.bin`) using community SD-card research tooling such as [**fujimod**](https://github.com/justjake/fujimod) ([fujimod.com](https://fujimod.com)) or [**FujiHack**](https://github.com/fujihack/fujihack).

> [!WARNING]
> Always run an in-camera **Factory Reset (`MENU → SET UP → USER SETTING → RESET → INITIALIZE`)** before dumping, and **NEVER share raw personal NOR Flash dumps publicly** (`[0x03500000..0x03D40000)` / `bank-14` holds your camera's unique serial number, Wi-Fi/BLE MAC addresses, and factory sensor calibration). Running custom dump payloads on real hardware carries bricking and warranty risks that are **100% your own responsibility** — or candidly, **ask a trusted friend who has already extracted sanitized dumps**.

Full step-by-step instructions: **[`docs/FIRMWARE.md`](docs/FIRMWARE.md)**

---

## Documentation, Modding & AI Skills

| Guide | English | 简体中文 | 日本語 | What It Covers |
|---|---|---|---|---|
| **Firmware & Hardware Dump Guide** | [`docs/FIRMWARE.md`](docs/FIRMWARE.md) | [`docs/FIRMWARE.zh-CN.md`](docs/FIRMWARE.zh-CN.md) | [`docs/FIRMWARE.ja.md`](docs/FIRMWARE.ja.md) | Downloading & SHA-256 verifying `GXUP0004.DAT` v2.13, NOR Flash setup, and optional sanitized `boot-rom.bin` / `nor-flash.bin` dumps |
| **Emulator User Manual** | [`docs/manual/USER_MANUAL.md`](docs/manual/USER_MANUAL.md) | [`docs/manual/USER_MANUAL.zh-CN.md`](docs/manual/USER_MANUAL.zh-CN.md) | [`docs/manual/USER_MANUAL.ja.md`](docs/manual/USER_MANUAL.ja.md) | Six-face workspace, body/UI themes, dials & shortcuts, modular lenses, optical filters, 3D scenes, dual SD cards, audio I/O, Vulkan GPU & logs |
| **Extending & Modding Guide** | [`docs/extending/EXTENDING.md`](docs/extending/EXTENDING.md) | [`docs/extending/EXTENDING.zh-CN.md`](docs/extending/EXTENDING.zh-CN.md) | [`docs/extending/EXTENDING.ja.md`](docs/extending/EXTENDING.ja.md) | Authoring custom Lens JARs, Optical Filters / Bokeh Masks / `.spv` shaders, Theme System v2 packs, explicit non-goals & 6-language i18n |
| **HTTP Control & Telemetry API** | [`docs/api/HTTP_API.md`](docs/api/HTTP_API.md) | [`docs/api/HTTP_API.zh-CN.md`](docs/api/HTTP_API.zh-CN.md) | [`docs/api/HTTP_API.ja.md`](docs/api/HTTP_API.ja.md) | Local `127.0.0.1:8787` REST automation for shutter, dials, lenses, filters, themes, live state & Python/`curl` scripting |

### AI-Readable Public Skills (`skills/`)

Ready-to-use `SKILL.md` specifications for AI coding agents (Claude Code, Gemini CLI / Antigravity, Codex, Cursor):

- [`skills/create-lens/SKILL.md`](skills/create-lens/SKILL.md) — Build & calibrate custom native or adapted Lens JARs (with curated optical bench data sources)
- [`skills/create-optical-filter/SKILL.md`](skills/create-optical-filter/SKILL.md) — Create custom Optical Filter elements, shaped Bokeh Pupil Masks, and Vulkan `.spv` compute passes
- [`skills/create-theme/SKILL.md`](skills/create-theme/SKILL.md) — Design custom Camera Body Themes (hybrid PNG+SVG, rotating dials, LEDs) & UI CSS themes

---

## Reporting Issues & Attaching Logs

Bug reports and hardware behavior observations are welcome on [**GitHub Issues**](https://github.com/moeKiwiSAMA/gfx100s-emulator/issues). Please attach the plain UTF-8 debug log (`DEBUG` threshold, rotated automatically per session) when opening an issue:

- **Windows (Portable Release)**: `<emulator-folder>\logs\latest.log` *(next to `fuji-emulator-gfx100s.exe`; prior runs are archived as `logs\yyyy-MM-dd-N.log.gz`)*
- **Linux (Portable Release)**: `<emulator-folder>/logs/latest.log` *(next to `start-gfx100s.sh` / `bin/fuji-emulator-gfx100s`; prior runs are archived as `logs/yyyy-MM-dd-N.log.gz`)*
- **Source Builds (`sh jvm.sh launch` / `./gradlew :desktop:run`)**: `<runtime-home>/logs/latest.log` *(e.g. `local/runtime/logs/latest.log` or `./logs/latest.log`, or custom `-Dfuji.log.dir=<path>`)*

Never attach proprietary firmware binaries or personal camera serial/MAC dumps to public issues.

---

## License & A Sincere Non-Commercial Plea

Released under the [**MIT License**](LICENSE) (`Copyright (c) 2026 moeKiwiSAMA`). Third-party open-source notices — including `0BSD` Dynarmic, `LGPL-2.1+` standalone FFmpeg playback binary and companion source archive, and `GPLv2+CE` OpenJDK/OpenJFX 25 — are documented in [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md).

Even though the MIT License legally permits broad reuse, **commercial use, paid repackaging, or commercial exploitation of this emulator is strongly discouraged**.

> **If you have the budget to commercialize or monetize this project, please spend that money buying a real Fujifilm camera instead.**

---

## Acknowledgments

1. **FUJIFILM Corporation** — for creating the extraordinary **GFX100S**, the GF optical system, and generations of photographic craft and color science that inspired every line of this tribute.
2. [**fujimod**](https://github.com/justjake/fujimod) ([fujimod.com](https://fujimod.com)) & the [**FujiHack**](https://github.com/fujihack/fujihack) community — for pioneering open Fujifilm camera research and sharing invaluable architectural references.
3. [**Dynarmic**](https://github.com/merryhime/dynarmic) (and our AArch32 SoC JIT fork [`moeKiwiSAMA/dynarmic`](https://github.com/moeKiwiSAMA/dynarmic)) — for the rock-solid, high-performance ARMv7-A JIT foundation at the heart of the emulator.

---

*FUJIFILM, GFX, GFX100S, GF, and Film Simulation names are trademarks of FUJIFILM Corporation. This project is independent and not affiliated with or endorsed by FUJIFILM Corporation.*
