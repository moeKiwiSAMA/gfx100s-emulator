# Native Vulkan 1.1 Compute Optical Pipeline (`libfuji_vulkan_optics.so` / `fuji_vulkan_optics.dll`)

Cross-platform, zero-bloat Vulkan 1.1 Compute runtime for lens optical simulation and CMOS Bayer readout (`VULKAN-W1`).

## Key Architecture
- **Dynamic Vulkan Loader Only**: Loads `libvulkan.so.1` (Linux) or `vulkan-1.dll` (Windows) at runtime via `dlopen` / `LoadLibraryA`. **Zero static Vulkan linkage** and **zero extra runtime DLLs** in release bundles.
- **User-Selectable Physical GPU & Hardware-GPU-First Default**: Exports `fuji_vk_enumerate_devices(...)` and `fuji_vk_create_for_device(device_index, ...)` so users can choose any compute-capable physical GPU in the **Lens & Optics -> Graphics / Vulkan Compute** panel, via CLI `/gpu` (`/lens gpu`), via `profile.yaml` (`graphics.opticsBackend` / `graphics.vulkanDevice`), or via `FUJI_VK_DEVICE` / `-Dfuji.vulkan.device=...`. When set to `auto`, prefers discrete GPUs (`VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU`), falls back to integrated GPUs, and skips software/CPU ICDs by default (software ICDs such as Mesa Lavapipe can be explicitly selected by index or opted into via `FUJI_VK_SELECT_ALLOW_SOFTWARE`).
- **Same-GPU Requirement for External Game Zero-Copy Interop**: When connecting Minecraft or other external games via GPU External Memory (`VK_KHR_external_memory_win32` / `VK_KHR_external_memory_fd` / `VK_EXT_external_memory_dma_buf`), you **MUST select the SAME physical GPU that the game runs on**. On hybrid/multi-GPU systems (e.g. Intel iGPU + NVIDIA/AMD dGPU), selecting a different GPU prevents zero-copy handle import and automatically falls back to CPU Shared Memory transport.
- **3 Embedded SPIR-V 1.0 Compute Shaders** (`shaders/*.comp` -> `fuji_vulkan_shaders_spirv.h`):
  1. `optics_warp`: 5-axis IBIS shift/roll, Brown-Conrady `k1/k2/p1/p2` distortion, zoom, lateral R/B chromatic aberration with bilinear resampling, `cos^p` + mechanical vignetting.
  2. `pupil_bokeh`: Thin-lens depth-buffer Circle-of-Confusion with true 2D entrance-pupil masks (`CIRCLE`, `POLYGON` 5..11 blades + roundness, `STAR_5` five-pointed star, `HEART`, `RING`) + rim highlight & onion-ring modulation.
  3. `cmos_bayer`: Portable 32-bit-emulated 64-bit `SplitMix64` stateless PRNG (zero `shaderInt64` requirement) + Box-Muller photon shot & ISO-scaled read noise + Bayer CFA mosaic (`RGGB`/`GRBG`/`GBRG`/`BGGR`, 8..16-bit).
- **Phase 2 Game Zero-Copy RGB+Depth Interop ABI**: Pre-wired `FujiVkExternalFrameDesc` + `fuji_vk_import_external_frame` / `fuji_vk_release_external_frame` supporting `VK_KHR_external_memory_win32` (Win32 HANDLE / D3D11 shared texture) and `VK_KHR_external_memory_fd` / `VK_EXT_external_memory_dma_buf` (Linux DMA-BUF fd).
- **Plugin SPI v1.0 Custom SPIR-V Compute Runtime (`fuji_vulkan_custom_spv.{h,cpp}`)**: Executes validated third-party `.spv` compute shaders in-place on `DEVICE_LOCAL` VRAM at `PRE_OPTICS_FILTER` and `POST_OPTICS_RGB` stages with a fixed `set = 0` ABI (`binding 0=SrcRgba`, `1=DstRgba`, `2=DepthMeters`, `3=PluginUbo` [32 B std header + `<= 4096 B` user data], optional `<= 128 B` push constants), backed by a bounded 32-entry SHA-256 LRU pipeline cache (`ctx->pipelineCache`) and zero-poison error isolation. Full developer guide: [docs/PLUGIN-SPI-V1-SPEC.md](../../../docs/PLUGIN-SPI-V1-SPEC.md).

## Build Commands

```bash
# Regenerate embedded SPIR-V 1.0 shader header (zero external tools needed):
python3 tools/native/vulkan-optics/shaders/generate_spirv_header.py

# Build Linux .so + run native Vulkan compute self-test, and cross-compile Win64 .dll:
bash tools/native/vulkan-optics/build_vulkan_optics.sh all
```

## Optional CI Image Enhancement (`fuji-emulator-build-env`)
The bridge compiles today with zero extra system packages (`fuji_vk_abi.h` + embedded SPIR-V). For optional upstream GLSL compilation (`glslangValidator`), SPIR-V validation (`spirv-val`), and headless Lavapipe GPU testing in `fuji-emulator-build-env/Dockerfile`, the minimal apt package set is:
`libvulkan-dev glslang-tools spirv-tools mesa-vulkan-drivers`
