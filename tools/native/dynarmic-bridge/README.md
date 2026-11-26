# `fuji_dynarmic` — Mandatory Primary ARMv7-A + VFPv4 + NEON Fastmem JIT Engine

**Sole native ARMv7-A CPU engine** for `fuji-emulator-gfx100s` development, CI, and Release bundles (`cpuEngine: dynarmic`, pure MIT license). Native C++20 Cortex-A7 4-core JIT engine built on pinned [`moeKiwiSAMA/dynarmic`](https://github.com/moeKiwiSAMA/dynarmic) branch `master` (`c019a7001773458116d21be3245acb4d1783d650`) linking its reusable `dynarmic::a32_sys` runtime (with primary-RAM fast-slot and 1-entry MRU micro-TLB) for Linux x86_64 (`libfuji_dynarmic.so`) and Windows x86_64 (`fuji_dynarmic.dll`).

## Architecture

- **`fuji_dynarmic.h`**: Stable C ABI v1 (`FUJI_DYN_ABI_VERSION = 1`, **62 exported C symbols** on both Linux `libfuji_dynarmic.so` and zero-external-dependency Win64 `fuji_dynarmic.dll` importing only `KERNEL32.dll` + `msvcrt.dll`) designed for Java 25 FFM `Linker.Option.critical(false)` leaf downcalls (zero JNI) and zero-copy host RAM backing pointers.
- **Upstream `dynarmic::a32_sys` (`moeKiwiSAMA/dynarmic` `a32-soc-jit`, `include/dynarmic/a32_sys/`)**:
  - **`<dynarmic/a32_sys/cp15.hpp>`**: Cortex-A7 CP15 coprocessor (`MIDR=0x410FC075`, `MPIDR=0x80000000|core`, `SCTLR`, `ACTLR`, `CPACR`, `TTBR0/1`, `TTBCR`, `DACR`, `DFSR/IFSR/DFAR/IFAR`, `PAR` + `ATS1*`, `PRRR/NMRR`, `VBAR`, `CBAR`, `CONTEXTIDR`, `TPIDR*`, `CNTFRQ`/`CNTPCT`/`CNTVCT`, PMU, TLB & I-cache maintenance) plus CP10 VFP system register hooks (`FPEXC`, `FPSID`, `MVFR0/1`).
  - **`<dynarmic/a32_sys/mmu_tlb.hpp>`**: ARMv7-A Short-Descriptor L1/L2 page-table walker (`Section`, `Supersection`, `Large Page`, `Small Page`, `DACR`, `AP/AP2/AFE`, `XN/PXN`, `TEX/C/B` + `PRRR/NMRR`) paired with a 4096-entry direct-mapped software TLB and Dynarmic's 1M-entry direct host-RAM `page_table` fastpath (zero callbacks for mapped RW RAM pages; FFM upcalls only on MMIO or faults).
  - **`<dynarmic/a32_sys/exceptions.hpp>`**: Full ARMv7-A banked registers (`USR/SYS/FIQ/IRQ/SVC/ABT/UND`), architectural exception delivery (`UND`/`SVC`/`PABT`/`DABT`/`IRQ`/`FIQ`), and translation-time `PreCodeReadHook` + `InterpreterFallback` for privileged instructions (`CPS`, `MSR CPSR_c*`, `MRS/MSR SPSR`, `SUBS/MOVS pc, ...`, `LDM^`/`STM^`, `SRS`/`RFE`, `VMRS/VMSR FPEXC`).
  - **`<dynarmic/a32_sys/mmio_fastpath.hpp>`**: Reusable `TimerShadowBank<8>`, `RoMmioShadowTable<512>`, `MmioFastpathStats`, and `MmioWidthSplitter` (native 64-bit `LDRD`/`STRD` MMIO splitting).
  - **Built-in cooperative block-linking halt**: `moeKiwiSAMA/dynarmic` (`a32-soc-jit`) natively checks `halt_reason == 0` at `LinkBlock`/`LinkBlockFast`/`PopRSBHint`/`FastDispatchHint` exits in `a32_emit_x64.cpp`, so full `OptimizationFlag::BlockLinking` (`1,000+ MIPS`) coexists with instant MMIO-halt, IRQ-pending, and `virtual_tick` flush preemption without any local patch files.
- **`fuji_dynarmic.cpp`**: Thin C ABI v1 bridge wiring `dynarmic::a32_sys` into 4-core `Dynarmic::A32::Jit` orchestration with shared `Dynarmic::ExclusiveMonitor` (`LDREX`/`STREX`/`CLREX`), `WFI`/`WFE`/`SEV`, tracepoints, native C++ `soc-timer` (`0xFFF3C000..0xFFF3C04F`) read shadow (`-100%` timer FFM upcalls), 512-slot O(1) RO/retained MMIO shadow cache, and native 64-bit `LDRD`/`STRD` MMIO splitting.

## Verified GFX100S 2.13 A/B Benchmarks (Native MMIO Fastpath)

| Workload Phase | Before (Baseline Dynarmic) | After (C++ Timer + 512-Slot RO-MMIO + `LDRD`/`STRD` Split) | Delta |
| --- | --- | --- | --- |
| **Phase 1 (Cold Boot)** | `40.32 MIPS` | **`46.09 MIPS`** | **`+14.3%` MIPS**, **MMIO upcalls `-45.4%`** |
| **Phase 2 (Interactive OOBE)** | `53.93 MIPS` | **`67.63 MIPS`** (`RTR 8.69x`) | **`+25.4%` MIPS** |
| **Phase 3 (Live View / Steady)** | Baseline MMIO upcalls | Native shadow active | **MMIO upcalls `-52.9%`** (`-100%` timer upcalls) |

C++ self-test (`test_dynarmic_bridge`): `9/9 PASS` (`1,075–1,206 MIPS` synthetic throughput).

## Building (Mandatory Primary Engine; Artifacts Stay in Gitignored `.tools/dynarmic-v1/`)

Gradle is the canonical build entrypoint (required by CI and Release packaging `:stageReleaseLinux` / `:stageReleaseWindows`):

```sh
# Recommended via Gradle (canonical CI & developer workflow):
./gradlew --no-daemon --offline -PfujiTestForks=1 :cpu-dynarmic:buildDynarmicNative
./gradlew --no-daemon --offline -PfujiTestForks=1 :cpu-dynarmic:buildDynarmicNativeWindowsCross

# Or invoke the underlying CMake/Ninja helper script directly:
# Build Linux x86_64 libfuji_dynarmic.so + run C++ self-test suite
./tools/native/dynarmic-bridge/build_dynarmic.sh linux

# Cross-compile Windows x86_64 fuji_dynarmic.dll (static libstdc++/libgcc/libwinpthread)
./tools/native/dynarmic-bridge/build_dynarmic.sh win64

# Build both
./tools/native/dynarmic-bridge/build_dynarmic.sh all
```

Outputs (outside Git):
- `.tools/dynarmic-v1/build-linux/libfuji_dynarmic.so`
- `.tools/dynarmic-v1/build-linux/test_dynarmic_bridge`
- `.tools/dynarmic-v1/build-win64/fuji_dynarmic.dll`
