# Changelog — v0.3.1 (2026-11-27)

`v0.3.1` is a focused performance, hardware-compatibility, and desktop ergonomics release building on `v0.3.0`: restoring full **64-bit Fastmem + Dynarmic JIT** throughput (`~43 FPS` STILL LiveView and menu navigation), hardening ARMv7 / MMU / unprivileged memory emulation for bare-metal bootloaders and Linux guests (`U-Boot 2024.10` & Alpine Linux), and refining the desktop UI across all 5 themes, 6 languages, GPD handheld gamepad layouts, and cross-platform webcam selection.

---

## 1. Performance & Imaging Regression Fixes

- **10× Tick-Loop Regression Fix (`RetainedMmioBank`)**: Removed unmodeled retained MMIO banks from `MmioRouter.alwaysTickDevices`, restoring cold-boot and interactive frame-pacing speed.
- **Dynarmic A32 JIT & 64-Bit Fastmem Restoration**:
  - Restored `A32GetSetElimination` and `DeadCodeElimination` safely under `check_halt_on_memory_access` (`preserve_state_across_memory_access`).
  - Re-enabled 64-bit Normal RAM Fastmem with strict `test vaddr, 3; jnz abort` alignment guards and Device / Strongly-Ordered memory exclusion, recovering STILL LiveView from `4.6 FPS` to **`43.1 FPS`** and menu navigation to **`42.6 FPS`**.
- **CMOS, UniResz Vulkan & 102MP Pipeline Polish**:
  - Added `sourceGeneration` caching to `ExposureBayerScaler` to eliminate redundant per-frame Bayer rescaling.
  - Accelerated stock UniResz face-preview DMA on the active Vulkan device with the stock 2560-byte UYVY stride and tracked the UniResz shader in native release cache keys.
  - Fixed 102MP still capture, audio, and white-balance regressions.

---

## 2. ARMv7 / Dynarmic Compatibility (U-Boot & Linux Guests)

- Added CP14 ThumbEE handling, ARMv7 unprivileged load/store (`LDRT`/`STRT`) support, Data Abort base-register restoration, and Linux MMU / Prefetch Abort JIT cache invalidation in the native Dynarmic bridge.
- Verified end-to-end SD1 chainloading into `U-Boot 2024.10` (`1024×768` LCDC1 `vidconsole` + interactive `gfx100s=>` prompt) and Alpine Linux guests without firmware regressions.

---

## 3. Desktop UI, Gamepad & Webcam Ergonomics

- **Unified Flash Dropdown Styling Across All 5 UI Themes**: Styled `.menu-button.studio-header-btn*` and `.context-menu` / `.menu-item` / `.separator-menu-item` consistently with `.combo-box-popup .list-view` across `Origin`, `Daylight Studio`, `Nordic Minimal`, `Olive Field`, and `Vintage Brass`.
- **Reorganized Physical Controls & Raw Readback (Both Expanded by Default)**:
  - Split the crowded single card into two clean, default-expanded cards: **Physical Buttons** (grouped into *Shutter & Power*, a tactile 3×3 *D-Pad & Navigation* cluster, and a 2-column *Function & ADC* grid) and **Raw Hardware Readback** (compact raw switch overrides + high-density 20-signal live telemetry table).
  - Removed duplicate Mode Dial dropdown and verbose labels from the daily button matrix while preserving all debug/test node IDs and 380px × 6-language × 5-theme layout integrity.
- **GPD-Friendly Default Gamepad Profile & Single-Stage Trigger**:
  - Exposed `GamepadInput.GUIDE` (`Menu`, `0x0400` / `FUJI_GP_GUIDE`) in Kotlin alongside existing Windows `XInputGetStateEx` and Linux `BTN_MODE` native bridge support.
  - Updated default bindings: `MENU_OK → Menu (GUIDE)`, `PLAY → Start`, `AE_L → Y`, `AF_ON → B`, `DELETE → X`, `FN1 → RB` (`DISP_BACK → A`, `VIEW_MODE → Back`).
  - Defaulted right-trigger shutter to single-stage threshold firing (`twoStageTriggerEnabled = false`, `triggerFullThreshold = 0.50`) with configurable two-stage option.
- **Cross-Platform Webcam Device Picker & Cleaner 2D Input Panel**:
  - Added `WebcamDeviceDiscovery` enumerating cameras on **Windows** (FFmpeg DirectShow modern/legacy + `Alternative name` deduplication + PowerShell CIM fallback), **Linux** (`/sys/class/video4linux` filtering UVC metadata nodes), and **macOS** (FFmpeg AVFoundation), plus dynamic `webcam:default` resolution.
  - Replaced manual `webcam:...` text entry in `SourceSelectorPane` with an async enumerated dropdown + hot-plug refresh button (`↻`), and removed the 4 redundant 2D quick-preset chips below the 2D category selector.
