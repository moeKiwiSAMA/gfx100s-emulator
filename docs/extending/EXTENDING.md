# Extending & Modding Guide

**English** | [简体中文](EXTENDING.zh-CN.md) | [日本語](EXTENDING.ja.md)

*Optics, Glass, and Brass — Open Extension Architecture for the FUJIFILM GFX100S Emulator*

The emulator separates camera silicon from three open, hot-reloadable extension layers:

1. **Modular Lenses & Mount Adapters (`<emulator-home>/lenses/*.jar`)** — Java `ServiceLoader` SPI v2 modules modeling physical glass aberrations, iris geometry, focus motor kinematics, and `LENSCOM` mount protocols.
2. **Optical Filters, Pupil Masks & Custom SPIR-V Shaders (`<emulator-home>/filters/*.jar`)** — Front-of-lens linear-radiance filter elements, entrance-pupil bokeh cutouts, and custom Vulkan 1.1 `.spv` compute passes.
3. **Body & Studio UI Themes (`<emulator-home>/themes/body/` & `themes/ui/`)** — Vector-first SVG + hybrid material camera chassis skins and semantic JavaFX CSS darkroom/daylight palettes.

> [!TIP]
> **Building with an AI Coding Agent?** Ready-to-run agent skills with complete schemas, validation bounds, and verification commands live in the repository's `skills/` directory:
> - Custom Lenses & Adapters: [`skills/create-lens/SKILL.md`](../../skills/create-lens/SKILL.md)
> - Optical Filters & SPIR-V Shaders: [`skills/create-optical-filter/SKILL.md`](../../skills/create-optical-filter/SKILL.md)
> - Camera Body & UI Themes: [`skills/create-theme/SKILL.md`](../../skills/create-theme/SKILL.md)
> - Live Automation & Hot-Reload Endpoints: [`docs/api/HTTP_API.md`](../api/HTTP_API.md)

---

## 1. Modular Lens & Mount Adapter JARs

Every interchangeable lens (`lenses/lens-gf63/`, `lenses/lens-gf110/`, `lenses/lens-takumar-50-f14/`, etc.) and mount adapter (`lenses/adapter-fringer-ef-gfx/`, `lenses/adapter-m42-gfx/`, `lenses/adapter-hasselblad-v-gfx/`) is packaged as an independent JAR discovered from `<emulator-home>/lenses/` by `LensModuleLoader` via `URLClassLoader` + `java.util.ServiceLoader`.

### 1.1 ServiceLoader SPI & Communication Contracts

Implement `com.github.nyabanban.fuji.emulator.api.LensModule` (or `MountAdapterModule`) and register its binary class name in:

- `META-INF/services/com.github.nyabanban.fuji.emulator.api.LensModule`
- `META-INF/services/com.github.nyabanban.fuji.emulator.api.MountAdapterModule`

Each lens or bridged adapter+lens pair declares **strictly one** mutually exclusive `LensControlContract` (`LensCommunicationMode`), enforced at load time by `validateContract()`:

| Mode | Contract | Electronic Contacts | Mount & Firmware Behavior |
| --- | --- | --- | --- |
| `GENERIC_SIMULATED` | `LensControlContract.GenericSimulated(LenscomIdentitySpec)` | `true` | Synthesizes a compliant `SPI0` `LENSCOM` peer: 128-byte `cmd 0x014` Identity Header, 128 KiB `cmd 0x0C0` Optical EEPROM, 1/3-stop iris sync (`A` / `C` / `MANUAL`), and signed 15-bit focus encoder (`0..0x7FFF`). |
| `MANUAL` | `LensControlContract.Manual` | `false` | Pure mechanical glass or passive adapter (`defaultFocusMode = "MF"`, `focusMotorType = "MANUAL"`, `focusSweepSeconds = 0.0`). Body `LENSCOM` stays silent (`F0` / `MOUNT ADAPTER SETTING`). |
| `REAL_FIRMWARE` | `LensControlContract.RealFirmware(...)` | `true` | Loads a user-owned lens/adapter firmware binary from `<emulator-home>/firmware-data/lenses/` (`fuji-lens-type4-dat` or `fringer-adapter-bin`). Missing or SHA-256-mismatched files fail fast without silent downgrade. |

When authoring an electronic `LenscomIdentitySpec`, ensure `shortId` is `1..7` uppercase ASCII alphanumerics (`[A-Z0-9]`), set `capabilityFlags1 = 0x01` on native GF lenses (LMO/LEQ support), and give every lens a **unique 10-character `serialAscii`** (e.g., `"GF08001701"`) so the camera body's NVRAM Optical EEPROM cache never reuses stale focal/aperture tables across hot-swaps.

### 1.2 Physical Optics, Focus Kinematics & Specialty Hooks

`LensOpticalSpec` feeds `OpticalLensProfile` on both the Vulkan 1.1 compute backend and deterministic `CpuOpticalPipeline`:

- **Focal, Iris & Image Circle**: `minFocalLengthMm..maxFocalLengthMm`, `minAperture..maxAperture` (enumerated along `ApertureScale.THIRD_STOPS`), scalar T-stop `transmission` (`0.0..1.0`), and `imageCircleMm` (`56.0..57.0` mm for native GF covering the `54.78` mm GFX diagonal; `43.3` mm for adapted 35mm lenses with authentic corner cutoff; `79.0` mm for 6×6 medium format).
- **Focus Motor Slew & Breathing**: `focusMotorType` (`LM_FAST`, `LM`, `STM`, `USM`, `DC_CORELESS`, `MANUAL` in `FocusMotorTypes`), full infinity-to-MOD travel time `focusSweepSeconds` (`0.0..5.0` s in diopter space), wide/tele MOD (`minFocusDistanceM`, `teleMinFocusDistanceM`), and close-focus `focusBreathing` (`-0.5..0.5`).
- **Aberrations & Flare**: Brown-Conrady radial distortion (`distortionK1`, `distortionK2`), lateral CA (`caRedScale`, `caBlueScale`), axial CA / LoCA (`longitudinalCaDefocusMm`, scaled by `minAperture / fNumber`), wide-open spherical contrast veil (`wideOpenContrastDrop` in `0.0..0.6`), `vignettingCos4` + `vignettingMechanical`, and `flareStrength` (`0.0..1.0`) with coating tint `flareTintR/G/B`.
- **Diaphragm & Bokeh Character**: `apertureBladeCount` (`5..11`), `apertureBladeRoundness` (`0.0..1.0`, ramping from circular wide open to polygonal stopped down), off-axis mechanical clipping `bokehCatsEye` (`0.0..1.0`), aspherical `bokehOnionRing` (`0.0..0.5`), and soap-bubble `bokehRimHighlight` (`0.0..0.6`).
- **Dynamic Zooms & Specialty Glass (`SPI v2`)**:
  - Override `opticalSpecAt(focalLengthMm, focusDistanceM, aperture)` for variable-aperture zooms and focal-dependent distortion/CA/vignetting curves.
  - Override `opticalHook(): LensOpticalHook?` for Scheimpflug tilt-shift (`effectiveFocusDistanceAt`), anamorphic/fisheye UV projection (`warpUv`), custom pupil apodization/swirl (`pupilWeight`), or a post-bokeh GPU shader (`vulkanSpirvPass` at `CustomSpirvStage.POST_OPTICS_RGB`).
  - Override `eepromBytes(): ByteArray?` (`131,072` bytes) when supplying a custom 128 KiB GF Optical EEPROM table.

### 1.3 Recommended Optical Data Sources

To calibrate realistic profiles rather than arbitrary coefficients, consult the 8 reference sources documented with conversion formulas in [`skills/create-lens/SKILL.md`](../../skills/create-lens/SKILL.md):

1. **LensBench** (`https://optic.chat`) — sequential optical bench & prescription conventions
2. **Photons to Photos · Optical Bench** (`https://www.photonstophotos.net/GeneralTopics/Lenses/OpticalBench/OpticalBench.htm`, Bill Claff) — patent prescriptions, pupil geometry, breathing & cat's-eye clipping
3. **Jim Kasson · The Last Word** (`https://blog.kasson.com`) — uncorrected RAW GFX100/100S GF lens MTF, LoCA, breathing & spherical aberration bench tests
4. **LensTip** (`https://www.lenstip.com`) — lateral/axial CA, coma, flare/ghosting, vignetting & AF sweep lab data
5. **OpticalLimits** (`https://www.opticallimits.com`) — raw distortion, lateral CA, vignetting, bokeh & per-aperture MTF
6. **Lensfun** (`https://lensfun.github.io`) — Brown-Conrady distortion, TCA scaling & vignetting math conventions
7. **DxOMark** (`https://www.dxomark.com`) — measured T-stop transmission & illumination falloff
8. **Fujifilm Official GF Lens Specifications** — focal range, aperture stops, blade count, MOD, image circle & AF motor architecture

### 1.4 Building, Dropping In & Hot-Testing a Lens JAR

1. Compile against `compileOnly(project(":component-api"))` (see `lenses/lens-gf63/` for a clean prime template and `lenses/lens-gf32-64/` for a zoom):
   ```bash
   ./gradlew :lenses:lens-gf63:jar --no-daemon
   ```
2. Copy the compiled `.jar` into `<emulator-home>/lenses/`.
3. Rescan and mount live without restarting (via console `/lens rescan` or HTTP Control API on default port `8787`):
   ```bash
   REV=$(curl -s http://127.0.0.1:8787/api/v1/state | jq '.components[] | select(.id=="lens") | .revision')
   curl -s -X PATCH http://127.0.0.1:8787/api/v1/components/lens \
     -H "Content-Type: application/json" -d "{\"revision\": $REV, \"changes\": {\"rescanPlugins\": 1}}"
   curl -s -X POST http://127.0.0.1:8787/api/lens \
     -H "Content-Type: application/json" -d '{"template": "my-lens-id", "opticsEnabled": true}'
   ```

---

## 2. Optical Filters, Bokeh Pupil Masks & Custom SPIR-V Shaders

The standalone `:components:optical-filter` component (`OpticalFilterStackPort` at `"optical-filter.stack"`) models a multi-slot optical matte box in front of the lens. Every filter executes in **linear HDR radiance space** prior to CMOS photon shot noise, read noise, and 14/16-bit Bayer readout, so scalar filter density drives the camera's real AE/histogram metering naturally.

### 2.1 Built-In Filter Elements & Multi-Slot Stacking

Up to **12 ordered filter slots** (`OpticalFilterSlotCodec.MAX_SLOTS = 12`) can be combined in the desktop **Optical Filter** sidebar, `configs/components/optical-filter/optical-filter.yaml`, or `POST /api/filters`:

- **`nd` (Neutral Density)**: `ND2`..`ND1000` or `VARIABLE` (`0.0..10.0` EV) pure `2^(-stops)` scalar attenuation with zero spatial copy overhead.
- **`gnd` (Graduated ND)**: `0.0..5.0` EV soft-edge rotatable sky attenuator (`horizon`, `feather`, `angleDeg`).
- **`black-mist` (Cinema Diffusion)**: `1/8`, `1/4`, `1/2`, `1`, or `CUSTOM` separable highlight halation preserving deep shadows.
- **`star` (Cross-Screen Grating)**: `4`, `6`, or `8` diffraction spikes radiating from specular highlights.
- **`uv` (UV Protective Glass)**: Models coating physics from `PRO_MRC` (`99.6%` transmittance) to `CHEAP_POOR` (`89.5%` Fresnel loss, veiling glare, point-symmetric `(-x,-y)` internal ghosting, MTF micro-softness, and green-yellow soda-lime tint).
- **`bokeh-mask` (Entrance-Pupil Cutout)**: `STAR_5` (`★5`), `HEART` (`♥`), `HEXAGON` (`⬡`), `RING` (`◎`), or plugin `CUSTOM`. Feeds `OpticalPupilMask.pupilWeight(ux, uy)` directly into the 2D depth-of-field bokeh kernel — keeping focused subjects sharp while shaping out-of-focus point lights (best seen wide open at `f/1.4`–`f/2.8` on `scene://star-bokeh`).

### 2.2 Custom Filter JARs, Pupil Masks & Vulkan SPIR-V Compute Passes

Drop custom `.jar` plugins implementing `com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider` (registered in `META-INF/services/com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider`) into `<emulator-home>/filters/`:

1. **Auto-Generated Studio UI (`FilterParamSpec`)**: Declare `NUMBER`, `ENUM`, `BOOLEAN`, or `TEXT` parameters in `parameterSchema()` and `OpticalFilterPane` builds live sliders, dropdowns, and toggles automatically.
2. **Custom Bokeh Pupil Masks (`OpticalPupilMask`)**: Return a custom mask (`shape = BokehMaskShape.CUSTOM`) from `OpticalFilterElement.pupilMask()` evaluating analytical SDFs, rasterized SVG geometry, or a bundled grayscale bitmap over the unit disk `(ux, uy)`. Multiple pupil masks in the rack compose multiplicatively via `CompositeOpticalPupilMask`.
3. **Custom Vulkan 1.1 SPIR-V Shaders (`CustomSpirvPassSpec` / `fuji_vulkan_custom_spv.h`)**:
   - Embed a precompiled `.spv` (`glslangValidator -V --target-env vulkan1.0 -S comp ...`, `20 B .. 256 KiB`, magic `0x07230203`, workgroup `1..64`) and load it via `CustomSpirvPassSpec.fromResource(...)` at `CustomSpirvStage.PRE_OPTICS_FILTER`.
   - Bind `set = 0`: `binding = 0` (`readonly SrcRgba`), `binding = 1` (`writeonly DstRgba`), `binding = 2` (`readonly DepthMeters`), `binding = 3` (`std140 PluginUbo`: 32-byte standard header `[width, height, focusM, fNumber, focalMm, frameIndex, flags, userBytes]` + up to `4096 B` plugin `uniformData`), plus optional `push_constant` (`<= 128 B`).
   - Compiled pipelines live in a 32-entry SHA-256 LRU `VkPipeline` cache with zero per-frame recompilation.
   - **Mandatory CPU Fallback**: Always implement `applySpatialLinearRgb(...)` (using zero-allocation `OpticalFilterScratch` and optional `OpticalFilterContext`). If `opticsBackend: cpu` is active or a `.spv` fails validation, the runtime logs `WARN [FALLBACK][vulkan]` and routes cleanly to your CPU implementation without poisoning `VkDevice`.

See [`skills/create-optical-filter/SKILL.md`](../../skills/create-optical-filter/SKILL.md) for full GLSL + Kotlin templates.

---

## 3. Explicit Non-Goals (Scope Boundaries)

To keep creative experimentation frictionless, maintainable, and legally clean, this emulator **intentionally will NOT implement** the following:

1. **NEVER Enforcing Physical Filter Thread Diameters (`ø62mm` / `ø77mm` / `ø82mm`)**:
   Optical filters will **never** check, restrict, or match physical screw-in thread diameters against a mounted lens's front filter caliber. In real life, juggling `ø62mm`, `ø77mm`, `ø82mm`, and bulb-front ultra-wide adapters is a chore; in this emulator, the filter rack is permanently a **universal thread-free matte box** so any filter (or stacked combination) works immediately on every lens without step-up ring micromanagement.
2. **No Artificial Mechanical Flange / Barrel Collision Lockouts**:
   Adapted 35mm, M42, and 6×6 lenses (as well as specialty tilt-shift or custom optical hooks) are never blocked by simulated rear-element baffle collisions. Physical optical consequences like a 35mm lens's `43.3 mm` image circle shading on the `54.78 mm` medium-format sensor ARE modeled optically, without artificial mount lockouts.
3. **No Post-ISP sRGB Screen Stickers Masquerading as Optics**:
   We do not implement cheap post-JPEG 2D overlays for bokeh shapes, ND darkening, or lens flare. Optical extensions belong in linear radiance / pupil space upstream of the sensor and ISP.
4. **No Firmware Cracking, DRM/Signature Circumvention, or Hardware Flashing Utilities**:
   The emulator does not bundle proprietary Fujifilm firmware binaries and will never develop tools that modify, patch, or flash physical cameras in the field.

---

## 4. Body & Studio UI Themes (Theme System v2)

Theme System v2 (`BodyTheme.kt`, `UiTheme.kt`, `PanelCatalog.kt`, `SvgArtwork.kt`) splits visual customization into two orthogonal layers under `<emulator-home>/themes/` that can be mixed freely and hot-reloaded live:

- **Camera Body Themes (`themes/body/<id>/body-theme.json`)**: Physical camera faces (`1..12` panels), artwork, interactive control hitboxes, screen viewports, hinged doors, and dynamic SVG bindings. Built-ins: `origin` (6-face GFX100S), `hasselblad-x2d2`, `hasselblad-907x`, `compact-rangefinder`.
- **Studio UI Themes (`themes/ui/<id>/ui-theme.json` or `themes/ui/<id>.css`)**: Window backdrop and semantic `-fuji-*` JavaFX CSS tokens overlaid onto `/panel.css`. Built-ins: `origin` (Origin Obsidian), `nordic-minimal`, `daylight-studio`, `vintage-brass`, `olive-field`.

### 4.1 Vector-First Hybrid Artwork & Strict Hitboxes

- **Artwork Modes**: Vector SVG (`"svg": "rear.svg"`, `<= 256 KiB`, `<= 4096` elements, exact `viewBox="0 0 <width> <height>"`) is strongly recommended. Inside SVG, you can combine crisp vector bevels with:
  - Embedded pack-local raster sub-assemblies (`<image href="artwork/mode-dial.png" .../>`, `<= 4 MiB`, `<= 4096×4096`),
  - Tiled material bitmaps (`<pattern id="..." patternUnits="userSpaceOnUse" width="..." height="..." data-texture="textures/leatherette.png"/>`, `<= 2 MiB`, `<= 1024×1024`; built-in themes also share `/themes/body/shared/{leatherette,brushed-chrome,xcd-glass}.png`),
  - Zero-weight procedural microrelief (`data-generator="continuous-grain-v1"`, `1200×800`, `data-kind` in `paint`, `rubber`, `brushed-metal`, `anodized`), and
  - Hardware-accelerated `data-effect="shadow"` / `"inner-shadow"`, `<clipPath>`, and cycle-checked local `<use href="#id"/>`.
- **Hitbox Contract**: Every entry in `views[].controls` must match a `<g id="<id>" data-control-id="<id>" data-view-id="<viewId>">` containing **one** transparent `<rect data-hitbox="true" x="..." y="..." width="..." height="..." fill="none" stroke="none"/>` with identical coordinates.
- **Interactive Bindings & Roles**:
  - `dynamicStates`: `rotate-map` (rotates Mode Dial `soc-adc.modeDial` or Focus Lever `soc-gpio.focusMode` around `pivotX`/`pivotY` via `angleMap`), `highlight-button` (swaps `movieMode` or `power` switch graphics), and `led-binding` (component indicator LEDs).
  - `studio-display-hotspot`: Automatically applied to `kind: "display"` viewports (`display.main` touch/swipe, `display.evf` double-click floating EVF, `display.shoulder` reflective sub-LCD, with `cornerRadius` or `clipShape: "circle"`).
  - `focus-mode-segments` & `exposure-mode-button`: Semantic `role` values providing a 3-segment `AF-S/AF-C/MF` bar or dial-less exposure mode chooser.
  - `data-control-proxy`: Top-level `<rect id="..." data-control-proxy="<targetControlId>" ... fill="none" stroke="none"/>` (no `transform`) exposing a control from another face (e.g., `front-command-dial` on `top.svg`) without duplicating `buttonKey`.
  - Mechanical `covers`: Hinged SD/battery/port doors pairing `#<id>-closed` and `#<id>-open` SVG groups with `kind: "cover"`, optional `kind: "latch"`, `interaction: "cover-toggle"`, `inside`, and `openHitbox`.
- **Security Containment & Live Reload**: External theme loaders enforce `toRealPath()` containment (rejecting `..`, absolute paths, symlink escapes, `<script>`, inline `style`, `on*`, external URLs, and CSS `@import`). Reload packs live via the header `↻` button, `/theme reload`, or `POST /api/theme/reload`. Full details in [`skills/create-theme/SKILL.md`](../../skills/create-theme/SKILL.md).

---

## 5. Localization (i18n) Best Practices

The desktop studio (`I18n.kt` + UTF-8 bundles in `desktop/src/main/resources/i18n/messages_*.properties`), plugin registry (`PluginMetadata`), and Theme System v2 (`ThemeLocalization`) share a unified **6-language localization model** with live hot-switching (`POST /api/lang` / header language selector).

### 5.1 Provide All 6 Default Languages

Whenever you publish a Lens JAR, Optical Filter JAR, Body Theme, or UI Theme, we strongly recommend providing translations for **all 6 default locales**:

| Canonical Code | Language | Role |
| --- | --- | --- |
| `zh_CN` | Chinese (Simplified / 简体中文) | Recommended default locale |
| `ja_JP` | Japanese (日本語) | Recommended default locale |
| `en` | English | **Mandatory base & fallback locale** |
| `de_DE` | German (Deutsch) | Recommended default locale |
| `fr_FR` | French (Français) | Recommended default locale |
| `es_ES` | Spanish (Español) | Recommended default locale |

### 5.2 Resolution Order & UTF-8 Encoding Rules

- **Strict English Fallback**: Both `PluginMetadata` and `ThemeLocalization` normalize `-` and `_` (so `zh-CN` and `zh_CN` match identically) and resolve in deterministic order:
  1. Exact locale match (`ja_JP`),
  2. Base 2-letter language match (`ja`),
  3. English (`en`) / top-level `name` & `description`.
  Missing keys or unsupported locales never crash or show blank labels — they fall back cleanly to English.
- **File & Manifest Conventions**:
  - Encode all `.json` manifests, `META-INF/services/*` files, Kotlin sources, and `messages_*.properties` bundles in **UTF-8 without BOM** and **LF (`\n`) line endings** (with a final trailing `\n`). Note that `I18n.kt` reads `.properties` directly via `InputStreamReader(..., StandardCharsets.UTF_8)`, so native UTF-8 characters (Chinese, Japanese, German umlauts, French/Spanish accents) are written directly without `\uXXXX` escaping.
  - In **Kotlin plugins** (`LensModule`, `MountAdapterModule`, `OpticalFilterElementProvider`), populate `PluginMetadata(names = mapOf(...), descriptions = mapOf(...))` and `FilterParamSpec(labels = mapOf(...), descriptions = mapOf(...))` across all 6 codes.
  - In **Theme JSON manifests** (`body-theme.json`, `ui-theme.json`), populate `"i18n": { "names": { ... }, "descriptions": { ... } }`, panel `"titles"`, and control `"labels"` across all 6 codes alongside top-level English `"name"` and `"description"`.
