# Native SIMD Rendering Pipeline Bridge (`libfuji_render.so` / `fuji_render.dll`)

Zero-dependency portable C11 SIMD accelerator for the FUJIFILM GFX100S LCDC1/VGLIB scanout, Morton tiling, command rasterization, and JavaFX `PixelBuffer` presentation hot paths.

## Cross-Platform & CPU Safety Guarantees
- **Baseline ISA**: `-msse2` (strict x86-64 baseline; guaranteed available on every x86-64 CPU).
- **Runtime AVX2 Dispatch**: AVX2 kernels are compiled with `__attribute__((target("avx2")))` and gated at runtime behind `CPUID` + `OSXSAVE` + `XGETBV` (`XCR0[2:1] == 0b11`) + Leaf 7 EBX bit 5 (`AVX2`). Never executes AVX2 instructions on SSE2-only CPUs (zero `SIGILL` risk).
- **Windows MinGW-w64 Parity**: Cross-compiled with `-static-libgcc -Wl,--exclude-libs,ALL`; imports ONLY system DLLs (`KERNEL32.dll`, `msvcrt.dll`).
- **Bit-Exact Silicon Fidelity**: Every kernel matches the pure-Kotlin reference implementation bit-for-bit (`SHA-256` verified), including BT.601 `UYVY` -> RGB with the all-zero macro-pixel neutral-black rule, exact Blinn `div255Rounded`, and 8×8 Morton Z-order tiling.
- **Pure-Kotlin Fallback**: If `libfuji_render.so` / `fuji_render.dll` is unavailable or disabled (`-Dfuji.render.native=false` / `FUJI_RENDER_NATIVE=0`), the JVM transparently runs the pure-Kotlin implementation.

## Build

```bash
bash tools/native/render-bridge/build_render.sh all
```

Outputs:
- `.tools/render-bridge/build-linux/libfuji_render.so`
- `.tools/render-bridge/build-win64/fuji_render.dll`
