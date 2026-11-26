# Modular Lens & Mount Adapter JARs (`lenses/`)

This directory contains independently packaged, runtime-discoverable **Lens (`LensModule`)** and **Mount Adapter (`MountAdapterModule`)** JAR modules for the Fujifilm GFX100S Emulator.

At startup, `LensModuleLoader` scans `<home>/lenses/` (via `URLClassLoader` + `java.util.ServiceLoader`), validates every module's strictly mutually exclusive communication contract, and pairs optical profiles with `components/lens` (`CpuOpticalPipeline`) and `components/mount` (`LENSCOM` SPI0 responder).

---

## 1. Strictly Mutually Exclusive Communication Modes

Every lens or adapter+lens assembly operates in **exactly one** of three mutually exclusive modes (`LensCommunicationMode` / `sealed interface LensControlContract` in [LensModule.kt](../component-api/src/main/kotlin/com/github/nyabanban/fuji/emulator/api/LensModule.kt)):

| Mode | Electronic Contacts | Body `LENSCOM` (SPI0) | Firmware Image | Failure Policy |
|---|---|---|---|---|
| `MANUAL` | `false` (`MF` only) | Silent (`0x00000000`, body shows `F 0` / `MOUNT ADAPTER SETTING`) | Forbidden (`""`) | Rejects `electronicContacts=true`, `AF-S`/`AF-C`, or non-empty `realFirmwareFile`. |
| `REAL_FIRMWARE` | `true` | Backed by verified real lens/adapter firmware (`GFUP00##.DAT` Type-4 `~byte` or Fringer `.bin`) | Mandatory (`firmware-data/lenses/`) | **Fail-fast**: missing, symlinked, corrupt, or SHA-256-mismatched firmware throws immediately. **Zero silent downgrade** to `GENERIC_SIMULATED` or `MANUAL`. |
| `GENERIC_SIMULATED` | `true` | Protocol-level DAT-compliant `LENSCOM` responder synthesized from physical optical/mechanical specs | Forbidden (`""`) | Rejects non-empty `realFirmwareFile` or `electronicContacts=false`. |

### DAT-Verified `LENSCOM` Protocol Alignment

All electronic lens modules follow the GFX100S 2.13 `GXUP0004.DAT` `LENSCOM` specification:
- Each electronic lens JAR declares a **unique 10-byte `hdr[80..89]` serial number** so the body's `part-12`/`part-13` Optical EEPROM cache (`0x01225764`) never returns a stale 128 KiB EEPROM when swapping lenses.
- `cmd 0x014` returns a compliant 128-byte Identity Header (`hdr[8..9]="GL"`, `hdr[10]='Z'|'S'`, `hdr[11]='S'|'N'`, `hdr[12]='A'|'M'`, `hdr[98..99]=0x01,0x30`, `hdr[100]=0x30`).
- `cmd 0x0C0` serves a dynamically synthesized 128 KiB Optical EEPROM (`0xAA55` magic at `+0x000BE`, focal/aperture tables).
- `cmd 0x054` / `cmd 0x061` (focus drive), `cmd 0x0A0` (iris drive), and `cmd 0x023` (live signed 15-bit focus encoder) drive the physical focus-motor slew and `CpuOpticalPipeline` defocus loop.

---

## 2. Optical Profile Reference Sources & Provenance

Every parameter in the 9 built-in lens profiles is tagged by provenance:
- **`SPEC`**: Official manufacturer mechanical/optical specification (focal range, aperture range & 1/3-stop scale, blade count, MFD wide/tele, OIS, image circle, AF motor architecture).
- **`BENCH-REFERENCED`**: Calibrated against independent optical-bench and laboratory measurements (raw uncorrected distortion, lateral & longitudinal chromatic aberration, vignetting falloff, wide-open spherical contrast/MTF drop, flare resistance/tint, bokeh shape/cat's-eye/onion-ring, and AF sweep timing).
- **`SIM`**: Real-time analytical approximation parameters mapped onto `CpuOpticalPipeline` (e.g., 2-term Brown-Conrady `k1`/`k2`, linear-RGB spherical glow mix, opposite-axis specular ghost gain, regular N-blade polygon roundness).

### Primary Reference Sources

1. **LensBench & Sequential Optical Design Frameworks**
   - **LensBench** (`https://optic.chat`) — sequential optical workbench and lens-prescription evaluation conventions.
   - **Photons to Photos — Optical Bench** (`https://www.photonstophotos.net/GeneralTopics/Lenses/OpticalBench/OpticalBench.htm`, Bill Claff) — optical prescriptions, exit-pupil geometry, entrance/exit pupil vignetting, and cat's-eye optical analysis.
2. **Independent Lab & Optical Bench Test Databases**
   - **Jim Kasson — The Last Word (GFX Optical Bench Series)** (`https://blog.kasson.com`) — raw uncorrected GFX 100/100S bench measurements for native Fujifilm GF primes and zooms (MTF, LoCA, focus breathing, wide-open spherical aberration, and uncorrected distortion/falloff).
   - **LensTip** (`https://www.lenstip.com`) — laboratory measurements of lateral & longitudinal chromatic aberration, coma, flare/ghosting behavior, vignetting, and autofocus sweep speed.
   - **OpticalLimits (formerly Photozone)** (`https://www.opticallimits.com`) — laboratory tests of raw vs corrected geometric distortion, lateral CA, vignetting, bokeh character, and corner MTF across apertures.
   - **Lensfun Open-Source Lens Profile Database** (`https://lensfun.github.io`) — open Brown-Conrady distortion, TCA scaling, and vignetting modeling conventions.
   - **DxOMark** (`https://www.dxomark.com`) — measured T-stop transmission and light-falloff reference data.
3. **Official Manufacturer Technical Datasheets**
   - **Fujifilm GF Lens Technical Specifications** (`GF 45-100mm F4 R LM OIS WR`, `GF 20-35mm F4 R WR`, `GF 32-64mm F4 R LM WR`, `GF 63mm F2.8 R WR`, `GF 110mm F2 R LM WR`, `GF 50mm F3.5 R LM WR`).
   - **Canon EF Lens Specifications** (`EF 85mm f/1.4L IS USM`).
   - **Asahi Optical Co. / Pentax Historical Specifications** (`Super-Takumar 50mm f/1.4`, 7-element / 8-blade vintage M42).
   - **Carl Zeiss / Hasselblad Historical Specifications** (`Planar 80mm f/2.8 C/CF`, 6x6 medium format, 5-blade Compur/Prontor leaf shutter).
   - **Fringer Adapter Technical Documentation** (`https://www.fringeradapter.com`, `EF-GFX Pro` electronic mount translation behavior).

---

## 3. Modular Lens JAR Matrix (9 Lenses)

| Module JAR | Lens ID | Mode (Default) | `LENSCOM` Serial | Focal / Aperture (`SPEC`) | MFD W/T (`SPEC`) | Motor / Sweep (`SPEC`/`BENCH-REFERENCED`) | Blades / Bokeh (`SPEC`/`BENCH-REFERENCED`) | Key Optical Traits (`BENCH-REFERENCED`/`SIM`) |
|---|---|---|---|---|---|---|---|---|
| `lens-gf45-100.jar` | `gf45-100` | `GENERIC_SIMULATED` | `GF04510001` | 45-100mm, f/4-32, OIS | 0.65m / 0.82m | `LM` / 0.35s | 9 rounded (0.90), mild cat's-eye 0.25 | Well-corrected modern zoom (`k1=-0.024`), low CA, clean flare (0.08), 5-stop OIS |
| `lens-gf20-35.jar` | `gf20-35` | `GENERIC_SIMULATED` | `GF02003501` | 20-35mm, f/4-22 | 0.35m / 0.35m | `STM` / 0.30s | 9 rounded (0.85), cat's-eye 0.15 | Wide-end barrel (`k1=-0.072`), natural cos4 falloff (2.1), Nano GI ultra-low flare (0.05) |
| `lens-gf32-64.jar` | `gf32-64` | `GENERIC_SIMULATED` | `GF03206401` | 32-64mm, f/4-32 | 0.50m / 0.60m | `LM` / 0.32s | 9 rounded (0.88), onion-ring 0.18 | Standard zoom (`k1=-0.036`), 3 aspherical elements (subtle onion-ring), very low CA |
| `lens-gf63.jar` | `gf63` | `GENERIC_SIMULATED` | `GF06302801` | 63mm, f/2.8-32 | 0.50m | `DC_CORELESS` / 0.78s | 9 rounded (0.82), cat's-eye 0.28 | Unit-focusing slower DC motor, noticeable focus breathing (+0.075), mild LoCA (`2.2mm`), high micro-contrast |
| `lens-gf110.jar` | `gf110` | `GENERIC_SIMULATED` | `GF11002001` | 110mm, f/2-22 | 0.90m | `LM_FAST` / 0.28s | 9 rounded (0.92), cat's-eye 0.50 | Near-zero distortion (`k1=+0.004`), ultra-creamy portrait bokeh, classic corner lemon cat's-eye wide open |
| `lens-gf50.jar` | `gf50` | `GENERIC_SIMULATED` | `GF05003501` | 50mm, f/3.5-32 | 0.55m | `LM_FAST` / 0.22s | 9 rounded (0.85), cat's-eye 0.18 | Fastest/lightest GF linear motor (0.22s), crisp wide open (`contrastDrop=0.02`), near-zero CA |
| `lens-canon-ef85-f14l.jar` | `canon-ef85-f14l` | `GENERIC_SIMULATED` (via Fringer) | `EF08501401` | 85mm, f/1.4-22, IS | 0.85m | `USM` / 0.42s | 9 rounded (0.85), cat's-eye 0.55, onion 0.12 | Adapted 35mm `43.3mm` image circle (hard GFX corner cutoff), strong f/1.4 LoCA (`5.2mm`) & spherical glow (0.14) |
| `lens-takumar-50-f14.jar` | `takumar-50-f14` | `MANUAL` | *(none)* | 50mm, f/1.4-16 | 0.45m | `MANUAL` / 0.00s | 8 semi-straight (0.45), rim 0.36, cat's-eye 0.58 | Vintage single-coated M42: `44.5mm` circle, pronounced f/1.4 glow (0.28), warm amber flare (0.52), soap-bubble bokeh rim |
| `lens-zeiss-planar-80-f28.jar` | `zeiss-planar-80-f28` | `MANUAL` | *(none)* | 80mm, f/2.8-22 | 0.90m | `MANUAL` / 0.00s | **5 straight (0.20)**, rim 0.22 | 6x6 medium format `79.0mm` image circle (zero GFX corner cutoff), signature 5-blade pentagonal stopped-down bokeh, warm vintage flare (0.34) |

---

## 4. Modular Mount Adapter JAR Matrix (3 Adapters)

| Module JAR | Adapter ID | Legacy Template ID | Mount Bridge | Mode | Electronic Contacts | Default Mounted Lens |
|---|---|---|---|---|---|---|
| `adapter-fringer-ef-gfx.jar` | `adapter-fringer-ef-gfx` | `adapter-ef-gfx` | `EF -> GF` | `GENERIC_SIMULATED` | `true` | `canon-ef85-f14l` |
| `adapter-m42-gfx.jar` | `adapter-m42-gfx` | `adapter-m42-dumb` | `M42 -> GF` | `MANUAL` | `false` | `takumar-50-f14` |
| `adapter-hasselblad-v-gfx.jar` | `adapter-hasselblad-v-gfx` | `adapter-hasselblad-v` | `Hasselblad V -> GF` | `MANUAL` | `false` | `zeiss-planar-80-f28` |

---

## 5. Adding Custom Lens / Adapter JARs or Real Firmware

1. **Custom Modular JAR**: Implement `com.github.nyabanban.fuji.emulator.api.LensModule` (or `MountAdapterModule`), register it under `META-INF/services/com.github.nyabanban.fuji.emulator.api.LensModule` (or `MountAdapterModule`), build the JAR, and copy it into `<emulator-home>/lenses/`.
2. **Real Lens Firmware (`REAL_FIRMWARE` Mode)**: Place a user-owned Fujifilm Type-4 lens update file (`GFUP00##.DAT`) or Fringer adapter binary (`.bin`) under `<emulator-home>/firmware-data/lenses/`, then set `communicationMode: "REAL_FIRMWARE"` and `realFirmwareFile: "<filename>"` (with optional `realFirmwareSha256`). Missing or invalid files fail fast without silent fallback.
