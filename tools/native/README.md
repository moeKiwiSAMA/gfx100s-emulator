# Native CPU Engine (`fuji_dynarmic`)

All native ARMv7-A CPU execution in this project runs on **`fuji_dynarmic` (Dynarmic JIT)** via JDK 25 Foreign Function & Memory (FFM, zero JNI, pure MIT license):

- **C++20 Cortex-A7 4-core ARMv7-A + VFPv4 + NEON Fastmem JIT** built on pinned `moeKiwiSAMA/dynarmic` (`a32-soc-jit`, linking `dynarmic::a32_sys`, **62+ C ABI exports**, `Linker.Option.critical(false)`, Linux `libfuji_dynarmic.so` + zero-external-dep Win64 `fuji_dynarmic.dll`).
- Features native C++ `soc-timer` read shadow (`-100%` timer upcalls), 512-slot O(1) RO-MMIO cache, generic 72-window / 414-slot MMIO read-mirror catalog, WFI host-idle sleep, true 4-core host-thread SMP (`smpHostThreads`), and native 64-bit `LDRD`/`STRD` MMIO splitting.
- Build via Gradle:
  - Linux / native host: `./gradlew :cpu-dynarmic:buildDynarmicNative`
  - Win64 cross-compile (using [mingw64-toolchain.cmake](mingw64-toolchain.cmake)): `./gradlew :cpu-dynarmic:buildDynarmicNativeWindowsCross`

See [dynarmic-bridge/README.md](dynarmic-bridge/README.md) for architecture, benchmarks, and build details.
