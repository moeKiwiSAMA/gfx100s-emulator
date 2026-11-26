---
name: create-optical-filter
description: Configure multi-slot optical filter stacks, create custom OpticalFilterElementProvider JAR plugins, build geometrical-optics bokeh pupil masks, and compile Vulkan 1.1 SPIR-V (.spv) compute filters for the FUJIFILM GFX100S Emulator.
---

# Create & Configure Optical Filters, Bokeh Pupil Masks, and Custom SPIR-V Shaders

Use this skill whenever asked to configure an optical filter stack, build a custom `OpticalFilterElementProvider` `.jar` plugin, author a custom entrance-pupil bokeh cutout mask, or write a custom Vulkan 1.1 SPIR-V (`.spv`) compute shader for the FUJIFILM GFX100S Emulator.

---

## 1. Explicit Non-Goals & Scope Boundaries (CRITICAL!)

Before designing any filter configuration, UI control, or plugin schema, strictly observe these architectural non-goals:

1. **NEVER implement or enforce physical filter thread diameters (`ø62mm` / `ø77mm` / `ø82mm`) or step-up rings**: The emulator's `optical-filter` component models a **universal thread-free matte box** (`OpticalFilterStackPort` at `"optical-filter.stack"`). Any filter element works freely with any lens (`GF`, adapted `EF`/`M42`/`Hasselblad-V`, or bare sensor) without mechanical caliber matching or vignetting rings. Do **not** add `threadDiameterMm` fields or reject a filter based on mounted lens dimensions.
2. **Operate in linear HDR radiance space**: All optical filters execute **before** CMOS photon shot noise, read noise, ADC quantization, and Film Simulation LUTs — never as post-ISP sRGB screen overlays. Scalar transmittances (`transmittanceMultiplier()`) attenuate sensor irradiance directly so the camera's real AE/histogram metering responds naturally.

---

## 2. Filter Stack Configuration Schema (JSON & YAML)

The `:components:optical-filter` component (`id = "optical-filter"`) supports both flat single-stage settings and an ordered multi-slot matte-box rack (`slots`, encoded via `OpticalFilterSlotCodec`, up to **`12` slots**, max **`4096` JSON chars**).

### Multi-Slot JSON Schema (`POST /api/filters` or `slots` string)

```json
{
  "enabled": true,
  "slots": [
    { "type": "uv", "enabled": true, "preset": "PRO_MRC" },
    { "type": "nd", "enabled": true, "preset": "ND8", "stops": 3.0 },
    { "type": "gnd", "enabled": true, "stops": 2.0, "horizon": 0.48, "feather": 0.25, "angleDeg": 0.0 },
    { "type": "black-mist", "enabled": true, "preset": "1/4", "strength": 0.25, "threshold": 0.35 },
    { "type": "star", "enabled": true, "points": 6, "strength": 0.75, "threshold": 0.55, "angleDeg": 15.0, "length": 0.12 },
    { "type": "bokeh-mask", "enabled": true, "shape": "STAR_5", "angleDeg": 0.0, "scale": 0.96 }
  ]
}
```

*(Accepted `type` aliases in `OpticalFilterSlotCodec`: `"bokeh"`/`"pupil"`/`"mask"` -> `"bokeh-mask"`, `"mist"`/`"blackmist"` -> `"black-mist"`, `"grad"` -> `"gnd"`, `"cross"` -> `"star"`.)*

### Component YAML Schema (`configs/components/optical-filter/optical-filter.yaml`)

```yaml
enabled: true
ndPreset: ND8
ndStops: 3.0
uvPreset: PRO_MRC
bokehMask: STAR_5
bokehMaskAngleDeg: 0.0
bokehMaskScale: 0.96
mistEnabled: true
mistPreset: "1/4"
mistStrength: 0.25
mistThreshold: 0.35
# Optional ordered multi-slot override (leave "" to use flat keys above):
slots: '[{"type":"uv","enabled":true,"preset":"PRO_MRC"},{"type":"nd","enabled":true,"preset":"ND8","stops":3.0}]'
```

### Built-In Optical Filter Reference

| `type` | Built-In GPU | Slot / Flat Parameters & Allowed Bounds | Behavior |
| --- | --- | --- | --- |
| `nd` | Yes | `preset` (`OFF`, `ND2`, `ND4`, `ND8`, `ND16`, `ND32`, `ND64`, `ND128`, `ND256`, `ND512`, `ND1000`, `VARIABLE`), `stops` (`0.0..10.0` EV) | Pure scalar `2^(-stops)` irradiance attenuation (zero spatial copy overhead) |
| `gnd` | Yes | `stops` (`0.0..5.0` EV), `horizon` (`0.0..1.0`), `feather` (`0.02..1.0`), `angleDeg` (`-180.0..180.0`) | Rotatable soft-edge sky attenuator |
| `black-mist` | Yes | `preset` (`OFF`, `1/8`, `1/4`, `1/2`, `1`, `CUSTOM`), `strength` (`0.0..1.0`), `threshold` (`0.0..1.0`) | Separable highlight halation bloom preserving dark shadows |
| `star` | Yes | `points` (`4`, `6`, `8`), `strength` (`0.0..2.0`), `threshold` (`0.10..2.0`), `angleDeg` (`0.0..180.0`), `length` (`0.02..0.35`) | Linear highlight diffraction spikes |
| `uv` | Yes | `preset` (`OFF`, `PRO_MRC` `99.6%`, `STANDARD` `96.5%`, `CHEAP_POOR` `89.5%`, `CUSTOM`), `transmittance` (`0.50..1.0`), `veilingGlare` (`0.0..0.50`), `ghostIntensity` (`0.0..0.50`), `softness` (`0.0..1.0`), `tintR`/`G`/`B` (`0.75..1.25`) | Fresnel loss, MTF micro-softness, point-symmetric `(-x,-y)` double-reflection ghosting, and glass tint |
| `bokeh-mask` | Yes (`!= CUSTOM`) | `shape` (`OFF`, `STAR_5`, `HEART`, `HEXAGON`, `RING`, `CUSTOM`), `angleDeg` (`-180.0..180.0`), `scale` (`0.40..1.0`) | Entrance-pupil cutout convolved inside the depth-of-field bokeh kernel |

> [!TIP]
> **Optical Physics Tip for `bokeh-mask`**: Because pupil masks shape the entrance pupil rather than stamping fake overlays, in-focus subjects remain tack-sharp. To see crisp `STAR_5` or `HEART` bokeh, enable lens optics (`opticsEnabled: true`), open the lens wide (e.g. `f/1.4`–`f/2.8`), focus close (`0.5`–`1.2 m`), and aim at distant pinpoint highlights (`scene://star-bokeh` or `scene://bokeh-city`).

---

## 3. Custom Filter Plugin JARs (`OpticalFilterElementProvider` SPI v2)

External filter JARs placed in `<emulator-home>/filters/*.jar` are discovered via `ServiceLoader` using:
- **Service file**: `META-INF/services/com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider`

### Self-Describing UI Controls (`FilterParamSpec`) & 6-Locale `PluginMetadata`

The desktop **Optical Filter Rack** auto-generates interactive sliders (`NUMBER`), dropdowns (`ENUM`), checkboxes (`BOOLEAN`), and text boxes (`TEXT`) directly from `parameterSchema()`. Always provide **6 default locales** (`en`, `zh_CN`, `ja_JP`, `de_DE`, `fr_FR`, `es_ES`) with English fallback:

```kotlin
package com.example.filters

import com.github.nyabanban.fuji.emulator.api.*

class GoldStreakFilterProvider : OpticalFilterElementProvider {
    override val filterType: String = "gold-streak"
    override val displayName: String = "Anamorphic Gold Streak"

    override val metadata: PluginMetadata = PluginMetadata(
        apiVersion = PluginMetadata.CURRENT_API_VERSION,
        version = "1.0.0",
        author = "Optics Workshop",
        description = "Horizontal warm gold anamorphic highlight streak filter.",
        descriptionZh = "水平暖金变形宽银幕高光拉丝滤镜。",
        names = mapOf(
            "en" to "Anamorphic Gold Streak",
            "zh_CN" to "暖金拉丝滤镜",
            "ja_JP" to "アナモルフィック・ゴールドストリーク",
            "de_DE" to "Anamorphotischer Goldstreifenfilter",
            "fr_FR" to "Filtre traînée dorée anamorphique",
            "es_ES" to "Filtro de destello dorado anamórfico",
        ),
        descriptions = mapOf(
            "en" to "Horizontal warm gold anamorphic highlight streak filter.",
            "zh_CN" to "水平暖金变形宽银幕高光拉丝滤镜。",
            "ja_JP" to "高輝度光源から水平方向に暖色ゴールドの光条を描くフィルター。",
            "de_DE" to "Erzeugt horizontale warmgoldene Lichtstreifen an Spitzlichtern.",
            "fr_FR" to "Génère des traînées lumineuses horizontales dorées sur les hautes lumières.",
            "es_ES" to "Genera destellos horizontales dorados cálidos en las altas luces.",
        ),
    )

    override fun parameterSchema(): List<FilterParamSpec> = listOf(
        FilterParamSpec(
            key = "intensity",
            type = FilterParamType.NUMBER,
            labelEn = "Streak Intensity",
            labelZh = "拉丝强度",
            defaultValue = 0.6,
            min = 0.0,
            max = 1.5,
            step = 0.05,
            labels = mapOf(
                "en" to "Streak Intensity", "zh_CN" to "拉丝强度", "ja_JP" to "ストリーク強度",
                "de_DE" to "Streifenstärke", "fr_FR" to "Intensité", "es_ES" to "Intensidad",
            ),
        ),
    )

    override fun create(config: Map<String, Any?>): OpticalFilterElement {
        val enabled = (config["enabled"] as? Boolean) ?: true
        val intensity = ((config["intensity"] as? Number)?.toDouble() ?: 0.6).coerceIn(0.0, 1.5)
        return GoldStreakFilterElement(enabled, intensity)
    }
}
```

Inside `OpticalFilterElement`:
- Reuse `scratch.ensurePrimary(width * height)` / `scratch.ensureAux(width * height)` (`OpticalFilterScratch`) — **zero heap allocations per frame**.
- Override the 7-argument `applySpatialLinearRgb(red, green, blue, width, height, scratch, context: OpticalFilterContext)` when your filter needs per-pixel `context.depthMeters`, `fNumber`, `focalLengthMm`, `focusDistanceM`, `rollDeg`, `pitchDeg`, or `frameIndex`.

---

## 4. Custom Bokeh Pupil Masks (Analytical SDF, SVG Path, or Grayscale Bitmap)

To create a custom aperture bokeh shape (e.g., clover, cat paw, crescent, or an embedded grayscale PNG/SVG cutout):

1. Implement `OpticalPupilMask` with `override val shape = BokehMaskShape.CUSTOM` (reporting `CUSTOM` ensures the optical backend evaluates your exact `pupilWeight(ux, uy)` LUT rather than substituting a built-in shape).
2. Return this `OpticalPupilMask` from `OpticalFilterElement.pupilMask()`, with `hasSpatialEffect = false` and `transmittanceMultiplier() = 1.0`.
3. `(ux, uy)` are normalized entrance-pupil unit-disk coordinates (`ux^2 + uy^2 <= 1.0`, `+uy` points down in image space):
   - **Analytical SDF**: evaluate a signed distance function in `(ux, uy)` with a smooth `~0.04` anti-aliasing feather into `[0.0, 1.0]`.
   - **Grayscale Bitmap / Pre-rasterized SVG**: decode a bundled `128×128` or `256×256` grayscale PNG (or rasterize an SVG path once at plugin load time) into a `FloatArray` lookup table, map `(ux, uy)` from `[-scale, +scale]` to `[0, dim - 1]`, and bilinearly sample the `[0.0, 1.0]` transmittance weight.
   - **Stacking**: multiple active pupil masks in `slots` automatically multiply together via `CompositeOpticalPupilMask`.

---

## 5. Custom Vulkan 1.1 SPIR-V (`.spv`) Compute Shaders (`Plugin SPI v1.0`)

Third-party filters can run directly on `DEVICE_LOCAL` VRAM in the Vulkan 1.1 optical compute pipeline by overriding `OpticalFilterElement.vulkanSpirvPass(width: Int, height: Int): CustomSpirvPassSpec?` while **always keeping `applySpatialLinearRgb(...)` implemented as the mandatory deterministic CPU fallback**.

### Fixed `set = 0` Descriptor ABI

```glsl
#version 450
layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

layout(std430, set = 0, binding = 0) readonly  buffer SrcRgba     { vec4  px[];    } srcBuf;
layout(std430, set = 0, binding = 1) writeonly buffer DstRgba     { vec4  px[];    } dstBuf;
layout(std430, set = 0, binding = 2) readonly  buffer DepthMeters { float depth[]; } depthBuf;

layout(std140, set = 0, binding = 3) uniform PluginUbo {
    uint  width;        // offset  0
    uint  height;       // offset  4
    float focusM;       // offset  8
    float fNumber;      // offset 12
    float focalMm;      // offset 16
    uint  frameIndex;   // offset 20
    uint  flags;        // offset 24 (bit 0 = depth buffer valid)
    uint  userBytes;    // offset 28 (length of uniformData)
    // --- Plugin uniformData starts at byte offset 32 (max 4096 B, std140 aligned) ---
    vec4  params;       // offset 32..47
} ubo;

layout(push_constant) uniform Push {
    float intensity;    // optional pushConstants (max 128 B)
} pc;

void main() {
    uint x = gl_GlobalInvocationID.x;
    uint y = gl_GlobalInvocationID.y;
    if (x >= ubo.width || y >= ubo.height) return;
    uint idx = y * ubo.width + x;
    vec4 src = srcBuf.px[idx];
    float d = depthBuf.depth[idx];
    vec3 outRgb = src.rgb * mix(vec3(1.0), ubo.params.rgb, clamp(pc.intensity, 0.0, 1.0));
    dstBuf.px[idx] = vec4(outRgb, d); // Preserve depth in .w!
}
```

### Hard SPIR-V Limits & Compilation Command

- **Bytecode bounds**: `20..262,144` bytes (`256 KiB`), multiple of 4 bytes, little-endian magic `0x07230203`, `OpCapability Shader`, matching `GLCompute` `OpEntryPoint`.
- **Workgroup**: `workgroupSizeX` and `workgroupSizeY` in `1..64` (default `16×16`).
- **Payloads**: `uniformData.size <= 4096` bytes; `pushConstants.size <= 128` bytes; up to `8` custom SPIR-V stages cached in a 32-entry SHA-256 LRU `VkPipeline` cache.
- **Stage**: use `CustomSpirvStage.PRE_OPTICS_FILTER` for front-of-lens filters (`POST_OPTICS_RGB` is for post-bokeh hooks).
- **Compile & validate**:
  ```bash
  glslangValidator -V --target-env vulkan1.0 -S comp \
    src/main/glsl/gold_streak.comp \
    -o src/main/resources/shaders/gold_streak.comp.spv
  spirv-val --target-env vulkan1.0 src/main/resources/shaders/gold_streak.comp.spv
  ```
- **Load from JAR classpath**:
  ```kotlin
  override fun vulkanSpirvPass(width: Int, height: Int): CustomSpirvPassSpec =
      CustomSpirvPassSpec.fromResource(
          classLoader = javaClass.classLoader,
          resourcePath = "shaders/gold_streak.comp.spv",
          workgroupSizeX = 16,
          workgroupSizeY = 16,
          uniformData = uboBytes,
          pushConstants = pushBytes,
          stage = CustomSpirvStage.PRE_OPTICS_FILTER,
      )
  ```

---

## 6. Drop-In & Testing Workflow

1. Package your plugin JAR with `META-INF/services/com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider` and drop it into `<emulator-home>/filters/`.
2. Run unit & SPIR-V tests in source builds:
   ```bash
   ./gradlew :component-api:test :components:optical-filter:test :components:lens:test --tests "*CustomSpirv*" --no-daemon
   ```
3. Live-rescan external filter JARs and activate your filter slot via HTTP API (default port `8787`):
   ```bash
   # 1. Rescan <emulator-home>/filters/*.jar
   REV=$(curl -s http://127.0.0.1:8787/api/v1/state | jq '.components[] | select(.id=="optical-filter") | .revision')
   curl -s -X PATCH http://127.0.0.1:8787/api/v1/components/optical-filter \
     -H "Content-Type: application/json" \
     -d "{\"revision\": $REV, \"changes\": {\"rescanPlugins\": 1}}"

   # 2. Mount your custom filter in the multi-slot matte-box rack
   curl -s -X POST http://127.0.0.1:8787/api/filters \
     -H "Content-Type: application/json" \
     -d '{"enabled": true, "slots": [{"type": "gold-streak", "enabled": true, "intensity": 0.8}]}'
   ```
