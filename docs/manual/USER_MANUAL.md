# FUJIFILM GFX100S Emulator — User Manual

**English** | [简体中文](USER_MANUAL.zh-CN.md) | [日本語](USER_MANUAL.ja.md)

← [Back to README](../../README.md) · [Firmware Setup](../FIRMWARE.md) · [Extending & Modding](../extending/EXTENDING.md) · [HTTP Control API](../api/HTTP_API.md)

---

> [!IMPORTANT]
> **Scope of This Manual**
> This document covers **only the host features, workspace controls, virtual optics, storage, audio, GPU compute, and diagnostic tools of this emulator**. It does **not** explain how to use Fujifilm's in-camera menus, Film Simulations, autofocus settings, or exposure modes — because the emulator runs unmodified GFX100S firmware, please refer to Fujifilm's official *GFX100S Owner's Manual* for operating the camera OS itself.

---

## Table of Contents

1. [Launchers, CLI Modes & Six-Face Workspace](#1-launchers-cli-modes--six-face-workspace)
2. [Body Themes, UI Palette Themes & Localization](#2-body-themes-ui-palette-themes--localization)
3. [Physical Controls, Dials, Shutter, Covers & Input Bindings](#3-physical-controls-dials-shutter-covers--input-bindings)
4. [Modular Lenses, Adapters, Focus Motor & Aperture Ring](#4-modular-lenses-adapters-focus-motor--aperture-ring)
5. [Universal Matte-Box Optical Filter Stack](#5-universal-matte-box-optical-filter-stack)
6. [Virtual 3D Depth Scenes & External CMOS Inputs](#6-virtual-3d-depth-scenes--external-cmos-inputs)
7. [5-Axis IBIS & Camera Orientation](#7-5-axis-ibis--camera-orientation)
8. [Virtual Dual SD Cards](#8-virtual-dual-sd-cards)
9. [NOR Flash Persistence, Snapshots & Diagnostics](#9-nor-flash-persistence-snapshots--diagnostics)
10. [Full-Duplex Host Audio I/O](#10-full-duplex-host-audio-io)
11. [Performance & Vulkan 1.1 GPU Compute Settings](#11-performance--vulkan-11-gpu-compute-settings)
12. [Troubleshooting & Exact Log Paths](#12-troubleshooting--exact-log-paths)

---

## 1. Launchers, CLI Modes & Six-Face Workspace

### Launching the Emulator

- **Windows (`x86_64` Portable Release)**:
  - Double-click **`fuji-emulator-gfx100s.exe`** (zero-dependency native launcher that automatically uses the bundled `jdk\bin\java.exe` runtime and requests your high-performance NVIDIA/AMD GPU on hybrid laptops), or run **`start-gfx100s.bat`**.
- **Linux (`x86_64` Portable Release)**:
  - Run **`./start-gfx100s.sh`**, or in the `app-image` distribution run **`./bin/fuji-emulator-gfx100s`**.
- **Source Workspace (`JDK 25`)**:
  - Run `sh jvm.sh launch` (or `./gradlew :desktop:run --args="--home local/runtime --config local/runtime/config/emulator.yaml --auto-boot"`).

Useful launch flags (append to any launcher script or executable):

| Flag | Purpose |
|---|---|
| `--auto-boot` | Starts booting the camera firmware immediately on window open (enabled by default in release launchers) |
| `--flash-dat <path>` | Unpacks an official `.DAT` firmware package into `flash/nor-flash.bin` and exits |
| `--boot-rom` | Opts into experimental cold boot via `bootrom/boot-rom.bin` (`PC = 0xFFF00000`) instead of default Flash-Direct |
| `--realtime-pace` | Paces virtual clock (`54 MHz`) to 1:1 wall-clock time during idle loops to reduce host CPU usage |
| `--nogui` / `--headless` | Runs the emulator purely in the terminal + HTTP API without opening JavaFX windows |
| `--no-console` | Disables the interactive `fuji> ` stdin console thread |
| `--debug` / `--log-level=<LVL>` | Sets console log verbosity (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`) |

### Control Center & Six-Face Camera Studio

Opening the desktop app brings up the **Control Center** (`MAIN ENTRY`) alongside movable camera body windows:

- **Top Header Bar**:
  - **Load Firmware / Pause / Resume**: Freezes and resumes guest ARM execution on the spot without losing state.
  - **Flash**: Graphical menu to flash a `.DAT` firmware package, export/import raw 64 MiB `.bin` NOR images, or restore stock factory flash.
  - **Lens Profile**: Pops out a dedicated floating **Lens Optics Profile** window so you can pull focus or stop down while watching the viewfinder.
  - **Controls**: Opens the **Key & Gamepad Bindings** editor.
  - **Body / UI / Reload / Lang**: Live switchers for Camera Body Theme, UI Palette Theme, external theme hot-reload, and interface language.
- **Six Camera Faces (`GFX100S Original` Layout)**:
  - **Rear**: Main `1024×768` touchscreen LCD (`LCDC1`), EVF eyepiece & eye sensor indicator, focus lever/joystick, rear command dial, `MENU/OK`, `DISP/BACK`, `PLAY`, `Q`, `AF-ON`, `AE-L`, and Delete buttons.
  - **Top**: Rotating `P/S/A/M/C1..C6` Mode Dial, `STILL / MOVIE` collar switch, Power lever + Two-Stage Shutter, `Fn1`/Exposure button, `DRIVE` button, and the live **Shoulder Monochrome Sub-LCD** (Sharp MIP readout).
  - **Front**: G-mount bayonet & exposed medium-format CMOS / mounted lens front, Front Command Dial, and front `Fn2` button.
  - **Left Side**: Weather-sealed hinged port covers over the 3.5mm Microphone jack, 3.5mm Headphone jack, USB-C port, and Micro-HDMI port.
  - **Right Side**: Dual UHS SD card bay door and card slot indicators.
  - **Bottom**: NP-W235 battery compartment door.
  - Toggle individual faces on or off inside the `Controls` tab and click **Arrange Windows** anytime to tile open faces cleanly onto your monitor.

### Floating Electronic Viewfinder (`EVF Window`) & Shoulder Sub-LCD

- **Open Floating EVF**: Double-click the rear EVF eyepiece on the camera body, click **EVF Window (F8)** in the `Controls` tab, or press **`F8`**.
- **Borderless Magnified View**: Renders the `1280×960` EVF scanout in its own movable window with a **Pin** toggle to keep it always on top of other desktop windows (for example, beside a lighting reference or game window).
- **Eye Proximity Sensor (`F6`) & `VIEW MODE` (`F7`)**:
  - Press **`F6`** (or click the Eye Sensor button in the EVF window) to toggle the virtual `TMD2772` infrared eye sensor (`I2C1 @ 0x39`) between **Eye Near EVF** and **Eye Away**.
  - Press **`F7`** to press the camera's physical **`VIEW MODE`** button (`GPIO 7.6`), cycling through `EYE SENSOR`, `EVF ONLY`, `LCD ONLY`, etc.
- **Shoulder Sub-LCD**: Located on the **Top** face of the GFX100S body, displaying live firmware-rendered shooting parameters, shutter/aperture/ISO graphics, histograms, and remaining frame counts even in standby.

---

## 2. Body Themes, UI Palette Themes & Localization

Hardware emulation is completely decoupled from visual skinning. All preferences save automatically to **`<emulator-dir>/profile.yaml`**.

### Built-In Camera Body Themes (`Body`)

Switch at any time from the header **`Body`** dropdown (or console `/theme body <id>`) with **zero black-screen interruption** — live LCD/EVF scanout frames continue uninterrupted across window transitions:

| Theme ID | Display Name | Panels & Aesthetic |
|---|---|---|
| `origin` | **GFX100S Original (6-Face)** | Complete 6-face magnesium-alloy & leatherette GFX100S studio with shoulder Sub-LCD, hinged port covers, and G-mount bayonet |
| `hasselblad-x2d2` | **Hasselblad X2D II 100C** | 3-panel dark-anodized Nordic chassis with sculpted grip, orange shutter release, and XCD mount |
| `hasselblad-907x` | **Hasselblad 907X & CFV 100C** | 3-panel modular digital back with satin chrome trim, grained black leatherette, and waist-level styling |
| `compact-rangefinder` | **Compact Rangefinder** | Streamlined 2-panel street rangefinder body with corner viewfinder and machined knurled dials |

### Built-In UI Palette Themes (`UI`)

Mix and match any body theme with 5 orthogonal window stylesheets from the **`UI`** dropdown (or `/theme ui <id>`):
- `origin` (*Original Dark Studio* — matte `#161515` darkroom neutral)
- `nordic-minimal` (*Nordic Minimal* — deep slate with `#E85D1A` orange accents)
- `daylight-studio` (*Daylight Studio* — crisp `#F3F4F6` light mode)
- `vintage-brass` (*Vintage Brass* — warm charcoal accented with `#D4A259` antique brass)
- `olive-field` (*Olive Field* — matte olive instrument finish with `#C9B37E` sand-gold readouts)

### Hot-Reloading Custom Themes & 6-Language UI

- **External Themes**: Place custom body theme packs under `<emulator-dir>/themes/body/<id>/` and custom CSS palettes under `<emulator-dir>/themes/ui/<id>.css`, then click **`Reload`** in the header bar (or call `POST /api/theme/reload`) to apply edits live without restarting. See [`EXTENDING.md`](../extending/EXTENDING.md) for authoring details.
- **6 Instant Languages (`Lang`)**: Switch live between **English (`en`)**, **Simplified Chinese (`zh_CN`)**, **Japanese (`ja_JP`)**, **German (`de_DE`)**, **French (`fr_FR`)**, and **Spanish (`es_ES`)** from the top-right `Lang` selector or `/lang <code>`. *(Note: This changes the **emulator host UI** language, independent of the camera firmware's internal menu language.)*

---

## 3. Physical Controls, Dials, Shutter, Covers & Input Bindings

### Operating Body Controls with a Mouse

- **Rear Touchscreen LCD**: Left-click to tap (touch-to-focus, touch-shutter, Q-Menu cells); click-and-drag to swipe across photos in playback or drag the AF point.
- **Mode Dial (`P / S / A / M / C1..C6`)**: Scroll the mouse wheel over the top Mode Dial, drag it rotationally (`-36°` per detent), right-click it to jump directly to a slot, or select a mode in the `Controls` tab.
- **`S / C / M` Focus Lever & `STILL / MOVIE` Collar**: Click, drag, or scroll over the physical selector on the body (or toggle in `Controls`).
- **Front & Rear Command Dials**: Hover over either command dial on the body (or the `COMMAND DIALS` strip in `Controls`) and **scroll the mouse wheel** or **drag horizontally** to step detents; **click** the dial center to press `frontDialPress` / `rearDialPress` (used by firmware for focus zoom, ISO/SS toggle, etc.).
- **Two-Stage Shutter (`S1` & `S2`)**:
  - Clicking the shutter button on the camera body delivers a calibrated **`650 ms` shutter pulse** so single-shot autofocus and 102MP JPEG/RAW capture complete cleanly on a casual tap.
  - For deliberate half-press metering/AF lock (`S1`) followed by full release (`S2`), use dedicated `S1`/`S2` controls in the button matrix, keyboard shortcuts, or a gamepad's analog trigger.
- **Key Combo-Lock (Multi-Button Chords)**:
  - Need to hold one button while pressing another (such as `Delete` + `Rear Dial`, or holding `DISP/BACK` while toggling Power)? **Right-click**, **`Shift + Click`**, or click the **`🔓`** icon next to any momentary button — or turn on **Combo-Lock Mode** — to latch it held down. Click **Unlock All** or **Release All Keys** in the header to release every held momentary switch at once.
- **Mechanical Port Covers & Compartment Doors**:
  - Left-click hinged covers on the Left, Right, and Bottom camera faces to swing open/close the Mic/Headphone/USB/HDMI flaps, SD card door, and battery door.

### Keyboard Shortcuts & Multi-Gamepad Bindings (`Controls` Dialog)

Click **`Controls`** in the top header bar to open **Controls — Key & Gamepad Bindings** (saved in `<emulator-dir>/input-bindings.yaml`):

- **Built-in Viewfinder Function Keys**:
  - `F6`: Toggle EVF Eye Proximity Sensor (`Near` ↔ `Far`)
  - `F7`: Press camera `VIEW MODE` button
  - `F8`: Show / hide standalone Floating EVF window
- **Custom Keyboard Shortcuts (Empty by Default)**:
  - Keyboard bindings are intentionally unbound out of the box so typing in text fields or the console never triggers stray camera presses.
  - Click any row in the **Keyboard** column and press any key or exact modifier chord (e.g., `Space`, `S`, `Ctrl+Shift+K`, arrow keys, `[` / `]`) to bind any of the 35 actions (`Shutter half-press S1`, `Shutter full-press S2`, `AF-ON`, `AE-L`, `Focus Nearer/Farther`, `Zoom In/Out`, `Front/Rear/Mode Dial`, `MENU/OK`, `DISP/BACK`, `PLAY`, `Q`, `Fn1/Fn2/Fn4`, etc.). Press `Esc` to cancel or **Clear All Keyboard** to reset.
  - `HOLD` actions stay pressed while you hold the key down (letting the camera firmware perform native key repeat); `STEP` dial/focus/zoom actions step cleanly and auto-repeat when held.
- **Native XInput / evdev Gamepad Support**:
  - Plug in up to 4 controllers on Windows (`XInput`) or Linux (`evdev`/`joydev`). Select `Auto (first connected)`, a fixed slot (`Slot 0..3`), or pin a specific controller by name, and tune the **Stick deadzone** (`0.10..0.50`, default `0.28`).
  - **Ergonomic Default Gamepad Profile**:
    - **Right Trigger (`RT`) — Two-Stage Shutter**: Pull past `25%` (`RT ½`) to half-press **`S1`** (autofocus & AE lock); pull past `80%` (`RT`) to fire **`S2`**!
    - **`RB` / `LB` / `LT ½`**: `AF-ON` / `Q Menu` / `DRIVE`
    - **Right Stick (`RS`)**: Left/Right = **Rear Command Dial**; Up/Down = **Front Command Dial**; Click (`R3`) = **Rear Dial Press**
    - **D-Pad & Left Stick (`LS`)**: 4-way navigation + Click (`L3`) = **Focus Lever Press**
    - **`A` / `B` / `X` / `Y`**: `MENU/OK` / `DISP/BACK` / `Playback` / `Delete`
    - **`Back` / `Start`**: `VIEW MODE` / `Fn1`

---

## 4. Modular Lenses, Adapters, Focus Motor & Aperture Ring

Open the **`Lens & Optics`** tab (or pop out the floating **`Lens Profile`** window from the header bar) to configure the optical assembly in front of the virtual 43.8 × 32.9 mm IMX461 sensor.

### Bundled Native GF & Adapted Lenses (`lenses/*.jar`)

| Profile ID | Lens & Mount Configuration | Optical Highlights |
|---|---|---|
| `gf20-35` | **FUJINON GF20-35mmF4 R WR** (Native GF) | Ultra-wide zoom, low flare, `0.35 m` close focus, gentle wide vignetting |
| `gf32-64` | **FUJINON GF32-64mmF4 R LM WR** (Native GF) | Standard workhorse zoom, Linear Motor AF, subtle aspherical bokeh texture |
| `gf45-100` | **FUJINON GF45-100mmF4 R LM OIS WR** (Native GF) | 5-stop optical OIS, Linear Motor, 9 rounded aperture blades |
| `gf50` | **FUJINON GF50mmF3.5 R LM WR** (Native GF) | Compact pancake prime, ultra-fast `0.22 s` AF sweep, minimal CA |
| `gf63` | **FUJINON GF63mmF2.8 R WR** (Native GF) | Unit-focusing DC motor, mild focus breathing, soft wide-open LoCA |
| `gf110` | **FUJINON GF110mmF2 R LM WR** (Native GF) | Medium-format portrait flagship: razor-thin `f/2` DoF, creamy bokeh & corner cat's-eye |
| `adapter-fringer-ef-gfx` + `canon-ef85-f14l` | **Fringer EF-GFX Pro** + **Canon EF 85mm f/1.4L IS USM** | Electronic cross-mount AF/aperture/IS translation, `f/1.4` LoCA & `43.3 mm` 35mm-full-frame image circle falloff |
| `adapter-m42-gfx` + `takumar-50-f14` | **M42 → GFX Mechanical** + **SMC Takumar 50mm f/1.4** | Pure manual no-contact vintage prime: wide-open spherical glow, warm amber flare & bubble-rim bokeh |
| `adapter-hasselblad-v-gfx` + `zeiss-planar-80-f28` | **Hasselblad V → GFX Mechanical** + **Zeiss Planar 80mm f/2.8** | 6×6 medium-format `79 mm` image circle, classic 5-blade pentagonal bokeh |

- Drop third-party lens or adapter JARs into `<emulator-dir>/lenses/` and click **Rescan Plugins** to load them at runtime without restarting.
- Toggle **Mount Attached** and **Electronic Contacts** (`KEY_LENS_DET`) to simulate hot-unmounting a lens or shooting with dumb mechanical adapters (*SHOOT WITHOUT LENS*).

### Three-Way Aperture Ring (`A Auto` / `C Command` / `Manual F`)

Electronic GF and Fringer-adapted lenses synchronize bidirectionally with the camera body over the `SPI0 LENSCOM` bus:

- **`A Auto`**: Locks the lens ring to `A` — the camera firmware drives the iris automatically in `P` (Program) and `S` (Shutter Priority) modes.
- **`C Command`**: Sets the lens ring to `C` — rotate the camera's **Front or Rear Command Dial** to step f-stops directly from the body.
- **`Manual F`**: Simulates turning the physical numbered aperture ring on the lens barrel — drag the **Aperture** slider and watch the f-number update live on the camera's rear LCD, EVF, Shoulder Sub-LCD, and AE exposure meter!

### Physical Focus Motor & Expert Optical Tuning

- **Focus Travel & AF**: Enable **Realistic Focus Motor Travel** to model physical DC vs. Linear Motor (`LM`) slew speeds during `AF-S` / `AF-C` sweeps, or click **AF Focus** / drag **Focus Distance** manually in `MF` mode (live telemetry shows 35mm-equivalent focal length, horizontal FOV, hyperfocal distance, Circle of Confusion in `µm`/`px`, and near–far Depth of Field).
- **Advanced Optics (`Expert Tuning`)**: Expand the tuning drawer to customize Brown-Conrady distortion (`k1`, `k2`), lateral R/B chromatic aberration, longitudinal CA (`mm`), wide-open spherical glow, `cos⁴` & mechanical vignetting, focus breathing, T-stop light transmission, `imageCircleMm` sensor coverage, backlight flare tint, aperture blade count (`5..11`), blade roundness, cat's-eye clipping, onion-ring texture, and bubble-rim bokeh.

---

## 5. Universal Matte-Box Optical Filter Stack

Available in both the quick **MATTE BOX · FILTERS** sidebar card and the full **MATTE BOX · OPTICAL FILTER RACK** inside `Lens & Optics` (or console `/filter` and `/nd`):

- **Universal Thread-Free Design**: By deliberate design, the emulator **never enforces physical filter thread diameters (`ø62mm` / `ø77mm` / `ø82mm`)**. Every filter fits every native or adapted lens simultaneously.
- **Up to 12 Ordered Slots + Swing-Away Bypass**: Stack up to 12 filters ordered from Front of Lens (`#1`) to Rear of Rack (`#N`), reorder or mute individual slots, apply 1-click **Quick Recipes** (e.g., `ND8 + ND64 (9 EV)`, `★5 Bokeh + Star + Mist`), or flip the master **IN PATH / SWING AWAY** switch to bypass the entire matte box with zero rendering overhead.
- **Built-in Physical Filter Elements**:
  - **ND Filter (Neutral Density)**: Continuous `0.0..10.0 EV` light reduction (`ND2` through `ND1000`, stackable). Directly attenuates scene radiance reaching the CMOS so the camera's live AE meter and shutter/ISO respond physically (e.g. `ND8` = exact `-3.00 EV`).
  - **Graduated ND (`GND`)**: Soft-edge sky density (`0..5 EV`) with adjustable horizon position, feather width, and tilt angle.
  - **Black Mist**: Cinema highlight halation & shadow lift across standard `1/8`, `1/4`, `1/2`, `1` diffusion grades.
  - **Star Cross-Screen**: `4`-, `6`-, or `8`-point diffraction spikes on specular highlights with adjustable spike intensity and rotation angle.
  - **UV Filter (Coating Comparison)**: Compare **Pro MRC** (`T = 99.6%`), **Standard** (`T = 96.5%`), and uncoated **Cheap Glass** (`T = 89.5%`, `-0.16 EV` loss with strong veiling glare, point-symmetric ghosting, MTF blur, and green cast).
  - **Bokeh Pupil Masks**: Inserts shaped cutouts into the lens entrance pupil — **`★5 Star`**, **`♥ Heart`**, **`⬡ Hexagon`**, **`◎ Ring`**, or **`✦ Custom`** masks — transforming out-of-focus point lights into crisp optical shapes (pair with `gf110` wide open at `f/2` on `scene://star-bokeh`!).
  - Click **Rescan Plugins** to hot-load external filter plugins (`filters/*.jar` and custom `.spv` Vulkan compute shaders).

---

## 6. Virtual 3D Depth Scenes & External CMOS Inputs

Switch what the virtual camera sees in real time from the **`INPUT SCENE`** panel (or `/source set <kind> <ref>` in the console):

### Built-In True 3D Depth Scenes (`3D` Badge)

3D scenes render a per-pixel metric depth buffer alongside linear RGB, driving realistic foreground/background defocus, focus breathing, PDAF phase-difference defocus signals, and Face/Eye-AF without manual distance sliders:

- **`scene://qr-ball` (*QR Sphere 3D* — Default)**: A rotating textured calibration sphere at `0.83..1.05 m` in front of a crisp `5.5 m` QR-code wall — ideal for testing near-to-far AF transitions and shallow medium-format DoF.
- **`scene://mona-lisa` (*Mona Lisa Eye-AF 3D*)**: Smooth 60 fps 3D portrait gallery scene engineered for testing Fujifilm Face Detection and Left/Right Eye-AF tracking boxes.
- **`scene://milk-frog` (*Milk Frog 3D + Audio*)**: Animated 3D character scene with Eye-AF geometry, studio depth targets, and synchronized spatial audio (pair with `3D Scene Spatial Audio` in the Audio panel!).
- **`scene://depth-studio` (*3D Optical Bokeh Studio*)**: Three-plane (`0.8 m / 2.5 m / 12 m`) optical test bench combining resolution/color charts with distant night highlights.
- **`scene://star-bokeh` (*Point-Light Bokeh*) & `scene://bokeh-city` (*Night Bokeh City*)**: High-contrast night point lights — focus on the `1.35 m` foreground target with a fast prime (`gf110` at `f/2`) to inspect shaped bokeh pupil masks, mechanical cat's-eye, and star cross filters.

### 2D Planar & Live External Inputs (`2D` Badge)

For 2D sources, adjust the **2D Planar Subject Distance** slider in `Lens & Optics` to simulate focusing into and out of the plane:

- **Static Image (`JPEG` / `PNG`)**: Browse to any high-resolution photo or test chart on disk (e.g., `test-qr.jpg`).
- **Local Video (`FFmpeg`)**: Stream `.mp4`, `.mkv`, or `.mov` video files onto the CMOS sensor (with optional **Loop Playback**).
- **Screen / Game Capture**: Enter `screen://primary` for full-desktop capture or build a cropped region URI (`screen://region/x,y,w,h?fps=24`) to photograph desktop apps or games.
- **Webcam**: Feed your host computer's physical webcam (`webcam:default`) through GFX100S optics and Film Simulations.
- **Network Stream (`RTSP / TCP / HTTP`)**: Connect live `rtsp://` cameras or push custom RGB (+ optional depth) frames over `tcp://127.0.0.1:9555`.

---

## 7. 5-Axis IBIS & Camera Orientation

Found under **`GYRO & 5-AXIS IBIS`** in the workspace panels:

- **Body & Tilting-LCD Accelerometers (`MMA8452Q`)**: Simulates gravity vectors on `I2C1 @ 0x1C` (body) and `I2C2 @ 0x1D` (LCD tilt mechanism).
- **1-Click Orientation Presets**: Switch instantly between **Landscape `0°`**, **Portrait `+90°` (CW)**, **Portrait `-90°` (CCW)**, and **Inverted `180°`** — watch the viewfinder HUD icons rotate into vertical shooting layout and captured JPEGs/RAWs record accurate portrait EXIF orientation!
- **Roll & Pitch Electronic Level**: Drag **Roll Angle** and **Pitch Angle** continuously to test the camera's on-screen 2D/3D electronic horizon level.
- **Handheld Micro-Shake & 5-Axis IBIS Telemetry**: Increase **Handheld Micro-Shake** to inject realistic hand tremor and watch the `biscom` (`SPI2`) 5-axis In-Body Image Stabilization controller counter-shift the sensor in real time (`Shift (X, Y) µm` live readout).

---

## 8. Virtual Dual SD Cards

The emulator models both UHS SD card slots (`Slot 1` & `Slot 2`), configured in `<emulator-dir>/configs/components/sdcard-reader/sdcard-reader.yaml` (ready-to-use templates live in `configs/examples/`):

| Backend Mode | How It Works | Best For |
|---|---|---|
| **`directory`** *(Default)* | Live two-way sync between a virtual 4 GiB FAT32 card and host folders `<emulator-dir>/runtime/compoments/sdcard-reader/sd-card-slot-1/` & `sd-card-slot-2/` | Everyday shooting! Captured 102MP `.JPG`, `.RAF`, and `.MOV` files appear immediately in your OS file manager, and photos dropped into `DCIM/100_FUJI/` appear immediately in camera `PLAY` mode |
| **`qcow2`** | Sparse 4 GiB Copy-on-Write virtual disk (`sd-card-slot-1.qcow2`) consuming only a few KiB initially, with optional read-only `slot1BackingFile` | Fast disposable test sessions where all writes or in-camera formats stay isolated from a clean master card |
| **`raw`** | Sector-exact raw `.img` FAT32 disk image under `runtime/compoments/sdcard-reader/` | Low-level FAT32 inspection, full card backups, or SD card boot research |

- **Live Hot-Swap & Write-Protect Switch**: Open the **`Components`** tab → expand **`sdcard-reader`** (or `PATCH /api/v1/components/sdcard-reader`) to toggle **`slot1Inserted`**, **`slot2Inserted`**, **`slot1ReadOnly`**, and **`slot2ReadOnly`** while the camera is running — triggering real GPIO card-detect interrupts (`GPIO 10.4` / `10.8`) and SDHCI insertion/removal events.

---

## 9. NOR Flash Persistence, Snapshots & Diagnostics

- **Automatic Non-Volatile Camera Persistence**:
  - In normal play, you do **not** need manual save-states to keep your camera setup: the unified 64 MiB `<emulator-dir>/flash/nor-flash.bin` image and coin-cell-backed Sub-MCU NVRAM (`runtime/compoments/power-peer/submcu-nvram.json`) persist your language/timezone setup, exposure settings, Film Simulation recipes, `C1..C6` custom banks, and lens calibration across restarts and `AUTO POWER OFF` sleep/wake cycles.
  - Back up or swap complete camera setups anytime via **Flash → Export NOR Flash (.bin)...** and **Flash → Import NOR Flash (.bin)...** (or `--export-flash` / `--import-flash`).
- **Full Machine Save-States (`EmulatorSnapshot` v1)**:
  - For deterministic debugging or instant mid-session resume, the `fuji-emulator-snapshot-v1` format (`manifest.json`, `components.json`, 4-core CPU state, guest DRAM, and component snapshots) can be resumed directly at launch with:
    ```bash
    ./start-gfx100s.sh --resume-snapshot /path/to/snapshot-dir
    ```
    *(When `--auto-boot` is off, the main button changes to **Resume Snapshot**.) Always duplicate a snapshot directory before running destructive experiments.*
- **Safe-Point Diagnostics Bundle**:
  - Click **Save Diagnostics** in the top header bar (or call `POST http://127.0.0.1:8787/api/v1/dump`) to write a bounded safe-point inspection archive under `<emulator-dir>/dumps/`.

---

## 10. Full-Duplex Host Audio I/O

Open the **`Audio I/O`** tab (or use the quick **AUDIO I/O** sidebar strip / `/audio` console command) to configure low-latency bidirectional 48 kHz PCM audio (`fuji_audio` over Windows `WASAPI` or Linux `PipeWire` / `PulseAudio` / `ALSA`):

- **Output (Camera → PC)**:
  - Reproduces shutter mechanical/electronic sound effects, AF confirmation beeps, menu clicks, self-timer tones, and H.264/HEVC `.MOV` playback audio.
  - Hot-swap host output devices without restarting, adjust **Master Volume** / **Mute**, and toggle the virtual **3.5mm Headphone Jack (`GPIO 8.4`)** to test camera headphone monitoring behavior.
- **Input & Loopback (PC → Camera)**:
  - Feeds real-time stereo audio into camera `.MOV` video recordings and on-screen L/R microphone level meters!
  - Choose from 5 input sources:
    1. **Input Off**
    2. **Host Microphone** (any physical USB/analog mic on your PC)
    3. **Capture System Output (Loopback)** — records your PC's desktop/music/game audio directly into the camera via `WASAPI Loopback` or `PipeWire`/`PulseAudio` monitor sources!
    4. **3D Scene Spatial Audio** — routes synchronized scene audio (such as `scene://milk-frog`) straight into the camera mic bus with zero hardware setup
    5. **Built-in 440 Hz Test Tone**
  - Includes **Input Gain**, **Mute Input**, **Monitor Pass-Through**, **Target Latency (`10..500 ms`)**, **3.5mm External Mic Jack (`GPIO 6.4`)** plug state, and live stereo VU meters.

---

## 11. Performance & Vulkan 1.1 GPU Compute Settings

Located at the bottom of the **`Lens & Optics`** tab under **`GRAPHICS / VULKAN COMPUTE`** (and persisted in `profile.yaml`):

- **Compute Backend (`Auto` / `Vulkan GPU` / `CPU SIMD`)**:
  - **`Auto (Vulkan -> CPU)`** *(Recommended)*: Dynamically loads `vulkan-1.dll` / `libvulkan.so.1` to run 3D scene rasterization, Pass 0 optical filters, 2×2 energy-conserving bokeh pyramids, CMOS readout, and LiveView ISP demosaicing/focus-peaking on your GPU — falling back cleanly to the multi-threaded `AVX2`/`AVX-512` CPU SIMD pipeline if no Vulkan 1.1+ GPU is available.
  - Switch between **`Vulkan GPU`** and **`CPU SIMD`** live at any time to compare performance or isolate a GPU driver issue.
- **Selecting a Physical GPU & Dual-GPU Laptop Tip**:
  - On multi-GPU desktops or hybrid laptops (iGPU + discrete NVIDIA/AMD dGPU), pick the exact physical GPU from the **Vulkan GPU** dropdown (`Discrete` / `Integrated` badges + `ExtMem` capability indicator).
  - **Game Zero-Copy Interop**: When connecting an external game via GPU External Memory (`ExtMem`), select the **same physical GPU** that renders the game; mismatched GPUs automatically fall back to host shared memory.
- **Host CPU & Clock Pacing**:
  - Type `tps` in the `fuji> ` console to inspect live Real-Time Ratio (RTR), effective ARM MIPS, WFI sleep share, and JVM heap usage, or `profiler start` → `profiler stop` for a detailed sampling breakdown.
  - Launch with **`--realtime-pace`** (or set `realTimePacer: true` in `configs/emulator.yaml`) if you want idle LiveView/menu loops paced to 1:1 real wall-clock speed for cooler, quieter laptop operation.

---

## 12. Troubleshooting & Exact Log Paths

### Common Solutions

| Symptom | Cause & Fix |
|---|---|
| **Firmware Setup prompt on launch** | `flash/nor-flash.bin` has not been created yet. Select your official GFX100S v2.13 `GXUP0004.DAT` (SHA-256 `ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12`) — see [`FIRMWARE.md`](../FIRMWARE.md). |
| **`Active: CPU Fallback` in Vulkan status** | Host OS lacks a Vulkan 1.1+ ICD (`vulkan-1.dll` / `libvulkan.so.1`). Install up-to-date NVIDIA / AMD / Intel graphics drivers (or `mesa-vulkan-drivers` on Linux). |
| **Buttons appear unresponsive** | Check if a momentary key was left latched in **Combo-Lock** (`🔒 Locked` banner) — click **Release All Keys** in the top header bar. Also ensure the camera's Power switch is `ON` and execution is not **Paused**. |
| **No sound in `.MOV` playback** | Check the **Audio I/O** tab (`Enable Output` ON, unmuted, valid host output device) and verify `runtime/media/ffmpeg` (`ffmpeg.exe` on Windows) is present. |
| **Resetting camera NVRAM / corrupted settings** | Use in-camera `MENU → SET UP → USER SETTING → RESET → INITIALIZE`, or re-flash cleanly from host CLI with `--flash-dat GXUP0004.DAT --no-preserve-calibration` and delete `runtime/compoments/power-peer/submcu-nvram.json`. |

### Exact Log File Locations (Attach When Reporting Bugs!)

The emulator uses a zero-ANSI, plain UTF-8 dual-channel logger (`CONSOLE` at `INFO` to keep your terminal clean; `FILE` at full forensic **`DEBUG`** level, including explicit `WARN [FALLBACK][vulkan|audio|bootrom|OS-SCHED]` notices):

- **Windows (`x86_64` Portable Release)**:
  ```text
  <emulator-folder>\logs\latest.log
  ```
  *(Right next to `fuji-emulator-gfx100s.exe`. Previous sessions are automatically compressed to `<emulator-folder>\logs\yyyy-MM-dd-N.log.gz`.)*
- **Linux (`x86_64` Portable Release)**:
  ```text
  <emulator-folder>/logs/latest.log
  ```
  *(Right next to `start-gfx100s.sh` / `bin/fuji-emulator-gfx100s`. Previous sessions are compressed to `<emulator-folder>/logs/yyyy-MM-dd-N.log.gz`.)*
- **Source Builds (`sh jvm.sh launch` / `./gradlew :desktop:run`)**:
  ```text
  local/runtime/logs/latest.log    (or ./logs/latest.log in your --home directory)
  ```
  *(Override the log directory anytime with `-Dfuji.log.dir=/custom/path`, or adjust live verbosity in the `fuji> ` console via `/log status`, `/log level DEBUG`, or `/log file TRACE`.)*
