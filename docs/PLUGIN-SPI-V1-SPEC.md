# FUJIFILM GFX100S Emulator — Plugin SPI v1.0 & Custom SPIR-V Compute Shader Spec

External **Lens (`LensModule`)** and **Optical Filter (`OpticalFilterElementProvider`)** plugins can ship precompiled **Khronos SPIR-V (`.spv`)** compute shaders inside their JARs and run them directly on the emulator's **Vulkan 1.1 Compute** optical pipeline in-place on `DEVICE_LOCAL` VRAM (zero extra PCIe round-trips), with automatic deterministic CPU fallback.

---

## 1. Fixed `set = 0` Plugin Compute Shader ABI

Every custom plugin compute shader MUST bind its resources in **`set = 0`** using the following layout:

| Binding | Type | Layout / Semantics |
| --- | --- | --- |
| `set = 0, binding = 0` | `SSBO` (`readonly`) | `vec4 px[]` — Linear HDR RGBA input (`width * height` pixels; `.rgb` = linear radiance). |
| `set = 0, binding = 1` | `SSBO` (`writeonly`) | `vec4 px[]` — Linear HDR RGBA output (`width * height` pixels). |
| `set = 0, binding = 2` | `SSBO` (`readonly`) | `float depth[]` — Per-pixel metric depth in meters (`width * height` floats; `0.0` if unavailable). |
| `set = 0, binding = 3` | `UBO` (`std140`) | `PluginUbo`: 32-byte standard header + up to **4096 B** of plugin `uniformData`. |
| `push_constant` (optional) | Push Constants | Up to **128 B** of fast per-frame parameters (`pushConstants`). |

### Standard `PluginUbo` Header (Bytes `0..31`)

```glsl
#version 450
layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

layout(std430, set = 0, binding = 0) readonly  buffer SrcRgba     { vec4  px[];    } srcBuf;
layout(std430, set = 0, binding = 1) writeonly buffer DstRgba     { vec4  px[];    } dstBuf;
layout(std430, set = 0, binding = 2) readonly  buffer DepthMeters { float depth[]; } depthBuf;

layout(std140, set = 0, binding = 3) uniform PluginUbo {
    uint  width;        // offset  0
    uint  height;       // offset  4
    float focusM;       // offset  8  (active lens focus distance in meters)
    float fNumber;      // offset 12  (active aperture f-number)
    float focalMm;      // offset 16  (active focal length in mm)
    uint  frameIndex;   // offset 20  (monotonic frame counter)
    uint  flags;        // offset 24  (bit 0 = depth buffer valid)
    uint  userBytes;    // offset 28  (byte length of plugin uniformData)
    // --- Plugin-defined uniformData begins at byte offset 32 (<= 4096 B) ---
    vec4  tintGain;     // offset 32..47
} ubo;

layout(push_constant) uniform Push {
    float strength;     // optional push constants (<= 128 B)
} pc;

void main() {
    uint x = gl_GlobalInvocationID.x;
    uint y = gl_GlobalInvocationID.y;
    if (x >= ubo.width || y >= ubo.height) return;
    uint idx = y * ubo.width + x;
    vec4 c = srcBuf.px[idx];
    float d = depthBuf.depth[idx];
    vec3 outRgb = mix(c.rgb, c.rgb * ubo.tintGain.rgb, clamp(pc.strength, 0.0, 1.0));
    dstBuf.px[idx] = vec4(outRgb, d);
}
```

### Pipeline Stages (`CustomSpirvStage`)

- `CustomSpirvStage.PRE_OPTICS_FILTER`: Executes in linear HDR radiance space **before** lens geometric warp and depth-of-field bokeh (recommended for `OpticalFilterElement` plugins). Original metric depth `.w` is automatically preserved for downstream warp/bokeh even if the plugin writes `1.0` to `.a`.
- `CustomSpirvStage.POST_OPTICS_RGB`: Executes in linear HDR radiance space **after** lens warp & bokeh and **before** CMOS shot/read noise + Bayer mosaic readout (recommended for `LensOpticalHook` plugins such as anamorphic streak flare or custom halation).

### Hard Validation & Caching Limits

- Binary size: `20 B .. 256 KiB`, 4-byte aligned, little-endian SPIR-V magic `0x07230203`, `OpCapability Shader`, matching `GLCompute` `OpEntryPoint`.
- Workgroup size: `workgroupSizeX` / `workgroupSizeY` in `1..64`.
- Uniform payload: `uniformData.size <= 4096 B`; push constants: `pushConstants.size <= 128 B`.
- Compiled `VkShaderModule` + `VkPipeline` + `VkPipelineLayout` are cached in a **32-entry LRU cache** backed by the emulator's persistent `VkPipelineCache`, keyed by `SHA-256(spirvBytes) + entryPoint + pushConstantSize` (0 ms compilation on repeated frames).

---

## 2. Compiling GLSL to SPIR-V (`.spv`)

Target Vulkan 1.0 / SPIR-V 1.0 for maximum compatibility across discrete GPUs, iGPUs, and Mesa Lavapipe:

```sh
glslangValidator -V --target-env vulkan1.0 -S comp \
  src/main/glsl/warm_diffusion.comp \
  -o src/main/resources/shaders/warm_diffusion.comp.spv
```

*(Optional verification with Khronos `spirv-val`: `spirv-val --target-env vulkan1.0 src/main/resources/shaders/warm_diffusion.comp.spv`.)*

---

## 3. Packaging an External Plugin JAR (`META-INF/services`)

Drop compiled plugin JARs into `<runtime-home>/filters/` (optical filters) or `<runtime-home>/lenses/` (lenses) and run `/filter rescan` or `/lens rescan` (no restart required).

### Optical Filter Plugin Example (`OpticalFilterElementProvider`)

1. Register provider class in `META-INF/services/com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider`.
2. Load the embedded `.spv` in one line via `CustomSpirvPassSpec.fromResource(...)`:

```kotlin
class WarmDiffusionFilter(private val strength: Float) : OpticalFilterElement {
    override val id = "warm-diffusion"
    override val displayName = "Warm Diffusion"
    override val enabled = true
    override val hasSpatialEffect = true
    override fun transmittanceMultiplier() = 1.0

    override fun vulkanSpirvPass(width: Int, height: Int): CustomSpirvPassSpec =
        CustomSpirvPassSpec.fromResource(
            classLoader = javaClass.classLoader,
            resourcePath = "shaders/warm_diffusion.comp.spv",
            workgroupSizeX = 16,
            workgroupSizeY = 16,
            uniformData = floatArrayOf(1.08f, 1.00f, 0.92f, 1.0f).toLittleEndianBytes(),
            pushConstants = floatArrayOf(strength).toLittleEndianBytes(),
            stage = CustomSpirvStage.PRE_OPTICS_FILTER,
        )

    // Mandatory deterministic CPU fallback (headless / opticsBackend=cpu / shader fallback)
    override fun applySpatialLinearRgb(
        red: FloatArray, green: FloatArray, blue: FloatArray,
        width: Int, height: Int, scratch: OpticalFilterScratch,
    ) {
        val s = strength.coerceIn(0f, 1f)
        for (i in 0 until width * height) {
            red[i] *= 1f + 0.08f * s
            blue[i] *= 1f - 0.08f * s
        }
    }

    override fun summaryBadge() = "WarmDiff ${(strength * 100).toInt()}%"
    override fun stateMap() = mapOf("id" to id, "strength" to strength)
}
```

### Specialty Lens Plugin Example (`LensModule` + `LensOpticalHook`)

Register your `LensModule` in `META-INF/services/com.github.nyabanban.fuji.emulator.api.LensModule` and override `LensOpticalHook.vulkanSpirvPass(width, height, focusDistanceM, fNumber, focalLengthMm)` returning a `CustomSpirvPassSpec(..., stage = CustomSpirvStage.POST_OPTICS_RGB)` alongside CPU `warpUv` / `effectiveFocusDistanceAt` / `pupilWeight` fallback methods.

---

## 4. Mandatory CPU Fallback & Fault Isolation Contract

- **Always implement CPU methods**: `OpticalFilterElement.applySpatialLinearRgb(...)` and `LensOpticalHook` methods remain the canonical fallback whenever `opticsBackend: cpu` is selected, no Vulkan GPU is available, or a plugin's `.spv` fails validation/compilation.
- **Zero-crash fault isolation**: If a third-party JAR supplies malformed or incompatible `.spv` bytecode, the runtime logs `WARN [FALLBACK][vulkan] ...`, rejects the pass cleanly without poisoning the active `VkDevice` context, and routes that frame to the plugin's CPU implementation automatically.
