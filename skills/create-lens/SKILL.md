---
name: create-lens
description: Design, calibrate, compile, and verify custom modular Lens (LensModule) and Mount Adapter (MountAdapterModule) JAR plugins for the FUJIFILM GFX100S Emulator using real optical bench data, ServiceLoader SPI v2, LENSCOM protocol contracts, and 6-language metadata.
---

# Create a Modular Lens or Mount Adapter Plugin (`LensModule` / `MountAdapterModule`)

Use this skill whenever asked to create, port, tune, or package a custom interchangeable lens or mount adapter `.jar` for the FUJIFILM GFX100S Emulator. Every interface, parameter name, validation bound, and command below matches `component-api` and `:components:lens` in the codebase.

---

## 1. Architecture & Discovery Overview

At startup (and on live rescan), `LensModuleLoader` scans `<emulator-home>/lenses/*.jar` using isolated `URLClassLoader` instances and `java.util.ServiceLoader`:

- **Lens service file**: `META-INF/services/com.github.nyabanban.fuji.emulator.api.LensModule`
- **Adapter service file**: `META-INF/services/com.github.nyabanban.fuji.emulator.api.MountAdapterModule`

Each discovered module is validated via `validateContract()` before registration:
- Native GF lenses (`mountType = "GF"`) attach directly to the camera body.
- Non-GF lenses (e.g., `mountType = "EF"`, `"M42"`, `"Hasselblad-V"`) automatically pair with a matching `MountAdapterModule` (`adapter.lensMount == lens.mountType`) or an explicit `adapterId`.
- Symlinked JARs, unreadable ZIPs, or contract-violating modules are skipped with `WARN [FALLBACK][lens-plugin]`.

---

## 2. Strictly Mutually Exclusive Communication Contracts

Every lens or bridged adapter+lens assembly operates in **exactly one** of three `LensCommunicationMode` modes via `sealed interface LensControlContract` (`com.github.nyabanban.fuji.emulator.api.LensControlContract`):

| Mode | Contract Class | `electronicContacts` | Body `LENSCOM` (`SPI0`) | When to Use |
| --- | --- | --- | --- | --- |
| `GENERIC_SIMULATED` | `LensControlContract.GenericSimulated(identitySpec)` | `true` (required) | Synthesizes a protocol-compliant 128-byte `cmd 0x014` Identity Header, 128 KiB `cmd 0x0C0` Optical EEPROM, iris drive, and 15-bit focus encoder | Electronic AF/AE native GF lenses and smart electronic adapters (default for custom electronic lenses) |
| `MANUAL` | `LensControlContract.Manual` | `false` (required) | Silent (`0x00000000`, body shows `F0` / `MOUNT ADAPTER SETTING`) | Vintage mechanical lenses and passive adapters (`defaultFocusMode = "MF"`, `focusMotorType = "MANUAL"`, `focusSweepSeconds = 0.0`) |
| `REAL_FIRMWARE` | `LensControlContract.RealFirmware(firmwareFormat, expectedFirmwareFile, expectedSha256, identitySpec)` | `true` (required) | Driven by a user-supplied real lens/adapter firmware image under `<emulator-home>/firmware-data/lenses/` (`fuji-lens-type4-dat` or `fringer-adapter-bin`) | Hardware firmware research; fails fast if the file is absent or fails SHA-256 verification (never silently downgrades) |

### `LenscomIdentitySpec` Schema & Validation Rules

For `LensControlContract.GenericSimulated`, construct `LenscomIdentitySpec`:

```kotlin
LenscomIdentitySpec(
    shortId = "GF80F17",            // REQUIRED: 1..7 uppercase ASCII [A-Z0-9]
    modelAscii = "GF80mmF1.7 R WR", // REQUIRED: 1..63 ASCII chars
    mountCode = "GL",               // 2 uppercase ASCII [A-Z0-9]; "GL" = Fujifilm G-mount
    serialAscii = "GF08001701",     // Unique 10-char serial! Prevents body 128 KiB EEPROM cache collisions on lens swap
    firmwareMajorBcd = 0x01,
    firmwareMinorBcd = 0x00,
    hardwareMajorBcd = 0x01,
    hardwareMinorBcd = 0x00,
    protocolMajorBcd = 0x01,
    protocolMinorBcd = 0x30,
    capabilityFlags0 = 0x30,
    capabilityFlags1 = 0x01,        // 0x01 enables LMO/LEQ on native GF lenses; 0x00 on adapted lenses
    isAdapter = false,              // true when bridged through an electronic MountAdapterModule
    isZoom = false,                 // true when maxFocalMm > minFocalMm
    hasOis = false,                 // true if lens has optical stabilization
    hasApertureRing = true,
    minFocalMm = 80.0,
    maxFocalMm = 80.0,
    minAperture = 1.7,
    maxAperture = 16.0,
    focusStepMin = 0,               // 0..focusStepMax
    focusStepMax = 0x7FFF,          // <= 0x7FFF (signed 15-bit encoder)
    focusMotorType = "DC_CORELESS", // One of FocusMotorTypes
    focusSweepSeconds = 0.62,       // Finite >= 0.0
)
```

> [!IMPORTANT]
> Always assign a **unique `serialAscii`** (10 characters, e.g., `"GF08001701"`) per electronic lens. The GFX100S firmware caches the 128 KiB Optical EEPROM in NVRAM keyed by the 10-byte lens serial (`hdr[80..89]`); duplicate serials cause stale focal/aperture tables after hot-swapping lenses.

---

## 3. Complete `LensOpticalSpec` Schema & Bounds

`com.github.nyabanban.fuji.emulator.api.LensOpticalSpec` feeds both the Vulkan 1.1 GPU optical compute pipeline and the deterministic `CpuOpticalPipeline` (`OpticalLensProfile`). Respect every hard `require(...)` bound:

| Field | Type | Default / Allowed Range | Optical / Mechanical Meaning |
| --- | --- | --- | --- |
| `id` | `String` | Non-blank slug (e.g. `"gf80-f17"`) | Must match `LensModule.id` |
| `displayName` | `String` | Human-readable title | Shown in Lens Panel & HTTP API |
| `mountType` | `String` | `"GF"`, `"EF"`, `"M42"`, `"Hasselblad-V"`, etc. | Physical bayonet mount |
| `modelName` | `String` | EXIF / LENSCOM model string | e.g. `"GF80mmF1.7 R WR"` |
| `defaultFocalLengthMm` | `Double` | `> 0.0` (`1.0..2000.0`) | Initial focal length on mount |
| `referenceFocalLengthMm` | `Double` | `> 0.0` (usually `minFocalLengthMm`) | `1.0x` FOV reference focal length |
| `minFocalLengthMm` | `Double` | `> 0.0` | Wide-end focal length (equal to max for primes) |
| `maxFocalLengthMm` | `Double` | `>= minFocalLengthMm` | Tele-end focal length |
| `defaultAperture` | `Double` | `minAperture..maxAperture` | Initial f-number (usually wide open) |
| `minAperture` | `Double` | `> 0.0` (`0.5..64.0`) | Fastest (wide-open) f-number (e.g. `1.7`, `2.0`, `4.0`) |
| `maxAperture` | `Double` | `>= minAperture` | Smallest stopped-down f-number (e.g. `16.0`, `22.0`, `32.0`). `ApertureScale.THIRD_STOPS` enumerates up to 31 `1/3`-stop steps |
| `minFocusDistanceM` | `Double` | `> 0.0` | Wide-end Minimum Object Distance (MOD / MFD) in meters |
| `teleMinFocusDistanceM` | `Double` | `> 0.0` (default `minFocusDistanceM`) | Tele-end MOD in meters (linearly interpolated across zoom) |
| `defaultFocusDistanceM` | `Double` | `> 0.0` (default `2.5`) | Initial focus distance in meters |
| `transmission` | `Double` | `0.0..1.0` (default `0.92`) | Scalar T-stop light transmission (`(fNumber / tStop)^2`) |
| `focusBreathing` | `Double` | `-0.5..0.5` (default `0.0`) | Fractional focal shift at close focus (`effF = f * (1 + breathing * f / (distM * 1000))`; negative narrows/widens per group design) |
| `distortionK1` | `Double` | Finite (`-1.0..1.0`, typical `-0.08..+0.04`) | Primary Brown-Conrady radial distortion (`< 0` barrel, `> 0` pincushion) |
| `distortionK2` | `Double` | Finite (`-1.0..1.0`, typical `-0.01..+0.02`) | Secondary 4th-order radial distortion (mustache compensation) |
| `caRedScale` | `Double` | `> 0.0` (`0.8..1.2`, typical `1.0004..1.0045`) | Lateral CA radial magnification of Red relative to Green |
| `caBlueScale` | `Double` | `> 0.0` (`0.8..1.2`, typical `0.9955..0.9996`) | Lateral CA radial magnification of Blue relative to Green |
| `longitudinalCaDefocusMm` | `Double` | Finite (`-25.0..25.0`, typical `0.02..0.40`) | Axial CA (LoCA) focal shift in mm (`+delta` Red, `0` Green, `-delta` Blue), attenuated by `minAperture / fNumber` |
| `wideOpenContrastDrop` | `Double` | `0.0..0.6` (default `0.0`) | Spherical aberration / wide-open veil (`0.02..0.08` modern GF, `0.25..0.35` vintage f/1.4); scales with `(minAperture / fNumber)^2` |
| `vignettingCos4` | `Double` | `>= 0.0` (`0.0..8.0`, typical `1.1..2.8`) | Natural `cos^p(theta)` peripheral illumination falloff exponent |
| `vignettingMechanical` | `Double` | `0.0..1.0` (typical `0.06..0.30`) | Corner barrel/hood mechanical shading factor |
| `imageCircleMm` | `Double` | `> 0.0` (`20.0..120.0`) | Physical image circle diameter in mm. GFX 43.8×32.9 mm sensor diagonal is **54.78 mm**: native GF = `56.0..57.0`, adapted 35mm full-frame = `43.3..44.5` (authentic corner cutoff), 6×6 medium format = `79.0` |
| `electronicContacts` | `Boolean` | `true` or `false` | Must be `false` for `MANUAL` and `true` for `GENERIC_SIMULATED` / `REAL_FIRMWARE` |
| `oisEnabled` | `Boolean` | `true` or `false` | Optical Image Stabilization support |
| `defaultFocusMode` | `String` | `"AF-S"`, `"AF-C"`, or `"MF"` | Must be `"MF"` when `LensControlContract.Manual` is used |
| `focusMotorType` | `String` | `"LM_FAST"`, `"LM"`, `"STM"`, `"USM"`, `"DC_CORELESS"`, `"MANUAL"` | Defined in `FocusMotorTypes`; must be `"MANUAL"` for manual lenses |
| `focusSweepSeconds` | `Double` | `0.0..5.0` | Full infinity-to-MOD focus motor slew duration in seconds (`0.0` for `MANUAL`; `0.22..0.40` `LM`/`LM_FAST`; `0.60..0.85` `DC_CORELESS`) |
| `flareStrength` | `Double` | `0.0..1.0` | Specular ghosting / veiling flare gain (`0.05..0.14` modern multi-coated, `0.30..0.55` vintage single-coated) |
| `flareTintR`, `G`, `B` | `Double` | `0.0..2.0` | Coating reflection tint (cool cyan-green Nano GI vs warm amber vintage) |
| `apertureBladeCount` | `Int` | `5..11` (hard `require` bound!) | Diaphragm blade count (e.g. `9` rounded GF, `5` Zeiss Planar, `6` or `8` vintage M42) |
| `apertureBladeRoundness` | `Double` | `0.0..1.0` | `1.0` = perfectly circular at all stops; `0.80..0.92` = modern rounded blades; `0.10..0.25` = crisp straight-edge polygons stopped down |
| `bokehCatsEye` | `Double` | `0.0..1.0` | Wide-open off-axis mechanical pupil clipping (lemon-shaped corner bokeh), scales with `minAperture / fNumber` |
| `bokehOnionRing` | `Double` | `0.0..0.5` | Concentric aspherical molding ring texture inside bokeh disks |
| `bokehRimHighlight` | `Double` | `0.0..0.6` | Over-corrected spherical "soap-bubble" bright bokeh rim (`0.0..0.06` creamy portrait lens, `0.30..0.45` vintage triplet/Takumar) |
| `opticalCharacterSummary` | `String` | Concise English summary | Fallback summary string |

---

## 4. Eight Recommended Optical Parameter Sources & Mapping Guide

Ground every custom lens in real optical measurements rather than guessed numbers. Consult these **8 recommended optical parameter sources**:

1. **LensBench** (`https://optic.chat`) — sequential optical bench & lens prescription evaluation conventions (element count, aspherical/ED glass placement, distortion & field curvature behavior).
2. **Photons to Photos · Optical Bench** (`https://www.photonstophotos.net/GeneralTopics/Lenses/OpticalBench/OpticalBench.htm`, Bill Claff) — patent optical prescriptions, entrance/exit pupil geometry, effective focal length vs focus distance (`focusBreathing`), mechanical vignetting (`vignettingMechanical`), and off-axis pupil clipping (`bokehCatsEye`).
3. **Jim Kasson · The Last Word** (`https://blog.kasson.com`) — GFX100/100S native GF lens uncorrected RAW optical bench measurements: MTF contrast wide open vs stopped down (`wideOpenContrastDrop`), axial CA / LoCA (`longitudinalCaDefocusMm`), focus breathing, and field curvature.
4. **LensTip** (`https://www.lenstip.com`) — lab measurements of lateral/axial chromatic aberration (`caRedScale`, `caBlueScale`, `longitudinalCaDefocusMm`), coma, flare/ghosting against bright sources (`flareStrength`, `flareTintR/G/B`), vignetting, and full-sweep AF speed (`focusSweepSeconds`).
5. **OpticalLimits (formerly Photozone)** (`https://www.opticallimits.com`) — raw uncorrected vs in-camera corrected geometric distortion (`distortionK1`, `distortionK2`), lateral CA pixel width at corners, peripheral EV light falloff, bokeh rendering (`bokehOnionRing`, `bokehRimHighlight`), and corner MTF across apertures.
6. **Lensfun Open Lens Database** (`https://lensfun.github.io`) — open-source Brown-Conrady / poly3/ptlens radial distortion coefficients, transverse chromatic aberration (TCA) red/blue radial scaling (`caRedScale`, `caBlueScale`), and vignetting math conventions.
7. **DxOMark** (`https://www.dxomark.com`) — measured T-stop light transmission (`transmission = (fStop / tStop)^2`, clamped to `0.0..1.0`) and peripheral illumination falloff reference curves (`vignettingCos4`).
8. **Fujifilm Official GF Lens Specifications** — exact focal length, max/min aperture, aperture blade count (`apertureBladeCount`), minimum object distance (`minFocusDistanceM` / `teleMinFocusDistanceM`), image circle (`imageCircleMm`), OIS support (`oisEnabled`), and focus motor architecture (`LM`, `STM`, `DC_CORELESS`).

### Quick Conversion Formulas from Lab Data

- **Percent Distortion (`D%`, negative = barrel) to `distortionK1`**: `distortionK1 ≈ (D% / 100.0) * 1.15` (add a small opposite-sign `distortionK2 ≈ -0.15 * distortionK1` if wavy/mustache distortion is noted).
- **Corner Lateral CA (`px` on 11648×8736 GFX sensor, half-diagonal `≈ 7280 px`)**: `caRedScale = 1.0 + (redShiftPx / 7280.0)`, `caBlueScale = 1.0 - (blueShiftPx / 7280.0)`.
- **T-Stop to `transmission`**: `transmission = (minAperture / measuredTStop)^2` (typically `0.86..0.95`).
- **Corner Falloff (`EV` wide open)**: combine natural `vignettingCos4` (`1.2..2.6` from tele to ultra-wide) with `vignettingMechanical` (`0.08..0.28`) so corner gain `cos^p(theta) * (1 - vignettingMechanical)` matches `2^(-EV_falloff)`.

---

## 5. Variable-Aperture Zooms & Specialty Optics (`SPI v2`)

### Dynamic Zoom Curves (`opticalSpecAt`)

Override `LensModule.opticalSpecAt(focalLengthMm, focusDistanceM, aperture)` to model variable-aperture zooms (e.g., f/3.5–5.6) and focal-length-dependent barrel-to-pincushion distortion, CA, and vignetting:

```kotlin
override fun opticalSpecAt(
    focalLengthMm: Double,
    focusDistanceM: Double,
    aperture: Double,
): LensOpticalSpec {
    val t = ((focalLengthMm - 35.0) / (70.0 - 35.0)).coerceIn(0.0, 1.0)
    return opticalSpec.copy(
        minAperture = 4.5 + 1.1 * t,         // f/4.5 wide -> f/5.6 tele
        distortionK1 = -0.042 + 0.058 * t,   // -4.2% barrel @ 35mm -> +1.6% pincushion @ 70mm
        vignettingCos4 = 2.2 - 0.8 * t,
    )
}
```

### Specialty Optics (`LensOpticalHook` + Optional Vulkan SPIR-V)

Override `opticalHook(): LensOpticalHook?` for Tilt-Shift (Scheimpflug tilted focal plane), Anamorphic squeeze + oval bokeh, Petzval field curvature / swirly bokeh, Fisheye projection, or Reflex mirror central obstruction:

- `warpUv(nx, ny, focalLengthMm, focusDistanceM, aperture, outUv): Boolean` — custom normalized sensor UV warp (`true` overrides Brown-Conrady).
- `effectiveFocusDistanceAt(nx, ny, baseFocusDistanceM): Double` — spatially varying focal surface (Scheimpflug tilt or Petzval curvature).
- `pupilWeight(ux, uy, nx, ny, aperture): Double` — entrance-pupil weight in `[0.0, 1.0]` (e.g., anamorphic `2x` oval pupil, off-axis swirl, or mirror obstruction).
- `vulkanSpirvPass(width, height, focusDistanceM, fNumber, focalLengthMm): CustomSpirvPassSpec?` — optional precompiled `.spv` compute shader running on GPU at `CustomSpirvStage.POST_OPTICS_RGB` (always keep the CPU hook methods implemented as automatic fallback!).
- `cacheKey(): Long` — return a non-zero deterministic hash of any mutable hook state.

Optional custom 128 KiB EEPROM: override `eepromBytes(): ByteArray?` returning **exactly `131,072` bytes** (`LensModule.LENS_EEPROM_BYTES`).

---

## 6. Localization Best Practices (6 Default Languages)

Always populate `PluginMetadata` with all **6 built-in desktop locales** (`zh_CN`, `ja_JP`, `en`, `de_DE`, `fr_FR`, `es_ES`) plus top-level English `description` so the Lens Panel localizes seamlessly and falls back to English for any other locale:

```kotlin
override val metadata: PluginMetadata = PluginMetadata(
    apiVersion = PluginMetadata.CURRENT_API_VERSION, // 2
    version = "1.0.0",
    author = "Your Name",
    description = "Fast portrait prime with creamy bokeh and gentle wide-open axial CA.",
    descriptionZh = "大光圈人像定焦镜头，柔美虚化与轻微全开轴向色散。",
    names = mapOf(
        "en" to "GF 80mm F1.7 R WR",
        "zh_CN" to "GF 80mm F1.7 R WR",
        "ja_JP" to "GF 80mm F1.7 R WR",
        "de_DE" to "GF 80mm F1.7 R WR",
        "fr_FR" to "GF 80mm F1.7 R WR",
        "es_ES" to "GF 80mm F1.7 R WR",
    ),
    descriptions = mapOf(
        "en" to "Fast portrait prime with creamy bokeh and gentle wide-open axial CA.",
        "zh_CN" to "大光圈人像定焦镜头，柔美虚化与轻微全开轴向色散。",
        "ja_JP" to "とろけるようなボケ味と穏やかな開放軸上色収差を備えた大口径中望遠単焦点。",
        "de_DE" to "Lichtstarke Porträt-Festbrennweite mit weichem Bokeh und sanfter Längs-CA bei Offenblende.",
        "fr_FR" to "Focale fixe portrait ultra-lumineuse au bokeh crémeux et douce AC longitudinale à pleine ouverture.",
        "es_ES" to "Objetivo fijo luminoso para retrato con bokeh cremoso y suave AC axial a máxima apertura.",
    ),
)
```

---

## 7. Complete Project Layout & Build Workflow

### In-Tree Subproject (`lenses/lens-<slug>/`)

1. Create `lenses/lens-<slug>/build.gradle.kts` (root `build.gradle.kts` automatically configures `compileOnly(project(":component-api"))` and sets `archiveFileName = "lens-<slug>.jar"` for all `:lenses:*` subprojects):
   ```kotlin
   plugins {
       kotlin("jvm")
   }
   ```
2. Add `include("lenses:lens-<slug>")` to `settings.gradle.kts` (if building inside a fork).
3. Create `lenses/lens-<slug>/src/main/kotlin/com/example/lenses/MyCustomLensModule.kt` implementing `LensModule`.
4. Create `lenses/lens-<slug>/src/main/resources/META-INF/services/com.github.nyabanban.fuji.emulator.api.LensModule` (UTF-8, LF, no BOM, trailing newline) containing the exact binary class name:
   ```text
   com.example.lenses.MyCustomLensModule
   ```

*(For a standalone external Gradle project outside the repo, compile against `component-api-<version>.jar` as `compileOnly` so `LensModule` is not shaded into your plugin JAR.)*

### Drop-In Installation & Live Verification

1. Build the JAR:
   ```bash
   ./gradlew :lenses:lens-<slug>:jar --no-daemon
   ```
2. Copy `lenses/lens-<slug>/build/libs/lens-<slug>.jar` into `<emulator-home>/lenses/`.
3. **Verify via unit tests**:
   ```bash
   ./gradlew :components:lens:test --tests "*LensPluginSpiV2Test*" --tests "*LensTemplatesAndFocusTest*" --no-daemon
   ```
4. **Hot-rescan and mount at runtime via HTTP API** (default port `8787` in `configs/emulator.yaml`):
   ```bash
   # 1. Read current lens component revision
   REV=$(curl -s http://127.0.0.1:8787/api/v1/state | jq '.components[] | select(.id=="lens") | .revision')

   # 2. Rescan <emulator-home>/lenses/*.jar without restarting
   curl -s -X PATCH http://127.0.0.1:8787/api/v1/components/lens \
     -H "Content-Type: application/json" \
     -d "{\"revision\": $REV, \"changes\": {\"rescanPlugins\": 1}}"

   # 3. Mount the new lens and enable physical optics
   curl -s -X POST http://127.0.0.1:8787/api/lens \
     -H "Content-Type: application/json" \
     -d '{"template": "gf80-f17", "opticsEnabled": true, "apertureRingMode": "MANUAL", "aperture": 1.7, "focusDistanceM": 1.2}'
   ```

---

## 8. Pre-Flight Checklist for AI Agents

- [ ] `apertureBladeCount` is strictly in `5..11` (e.g., never `12` or `0`).
- [ ] `LenscomIdentitySpec.shortId` is `1..7` uppercase ASCII `[A-Z0-9]` (no hyphens or lowercase!) and `serialAscii` is a unique 10-character ID.
- [ ] `MANUAL` lenses set `electronicContacts = false`, `defaultFocusMode = "MF"`, `focusMotorType = "MANUAL"`, and `focusSweepSeconds = 0.0`.
- [ ] `META-INF/services/com.github.nyabanban.fuji.emulator.api.LensModule` matches the exact package + class name and ends with `\n`.
- [ ] `PluginMetadata` includes `en`, `zh_CN`, `ja_JP`, `de_DE`, `fr_FR`, and `es_ES`.
