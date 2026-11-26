# Third-Party Licenses & Notices

This project (**FUJIFILM GFX100S Emulator**) is licensed under the **MIT License** (`Copyright (c) 2026 moeKiwiSAMA` — see [`LICENSE`](LICENSE)).

Portable release distributions (`Windows x86_64` and `Linux x86_64`) and source builds use third-party open-source libraries, toolchains, and runtime components under their respective licenses summarized below.

- **Pure-MIT First-Party Subsystems**: All first-party Kotlin modules and C/C++ native bridges (`fuji_dynarmic` JNI/FFM bridge, `fuji_render`, `fuji_vulkan_optics`, `fuji_isp_simd`, `fuji_codec`, `fuji_audio`, `fuji_gamepad`, and the SLF4J 2.0 `FujiLogging` provider) are licensed under the **MIT License**. Neither `ch.qos.logback` nor `Unicorn Engine` is used or distributed in release builds.
- **Zero Proprietary Camera Firmware**: Public source trees and portable release bundles contain **zero** proprietary FUJIFILM firmware, BootROM, or camera calibration binaries.

---

## 1. Native CPU JIT, Graphics, Codec & C++ Libraries

| Component | License | Role in Distribution | Source / Upstream |
|---|---|---|---|
| **Dynarmic** (ARMv7-A / AArch32 JIT, SoC fork & upstream) | **0BSD** (Zero-Clause BSD, MIT-compatible) & **MIT** (`fuji_dynarmic` bridge) | Primary 4-core ARMv7-A + NEON/VFPv4 x86_64 JIT engine (`libfuji_dynarmic.so` / `fuji_dynarmic.dll`) | <https://github.com/moeKiwiSAMA/dynarmic> · Upstream: <https://github.com/merryhime/dynarmic> |
| **mcl** (Merry's Common Library) | **0BSD** | C++ utility library used by Dynarmic | <https://github.com/merryhime/mcl> |
| **xbyak** | **BSD-3-Clause** | x86-64 JIT code generator used by Dynarmic | <https://github.com/herumi/xbyak> |
| **fmt** | **MIT** | C++ formatting library used by Dynarmic | <https://github.com/fmtlib/fmt> |
| **tsl::robin-map** | **MIT** | Hash table library used by Dynarmic | <https://github.com/Tessil/robin-map> |
| **zycore** & **zydis** | **MIT** | x86-64 disassembler library used by Dynarmic | <https://github.com/zyantronic/zydis> |
| **Boost** (header-only libraries) | **BSL-1.0** (Boost Software License 1.0) | C++ headers used during Dynarmic compilation | <https://www.boost.org/> |
| **Khronos Vulkan-Headers** (`fuji_vk_abi.h`) | **Apache-2.0** | Vulkan 1.1 C ABI declarations for dynamic runtime loading of `libvulkan.so.1` / `vulkan-1.dll` in `fuji_vulkan_optics` | <https://github.com/KhronosGroup/Vulkan-Headers> |
| **miniaudio** | **MIT-0** / **Public Domain** (Unlicense) | Cross-platform WASAPI / PulseAudio / PipeWire / ALSA audio backend in `fuji_audio` | <https://github.com/mackron/miniaudio> |
| **libjpeg-turbo** (optional dynamic host codec) | **IJG** + **Modified BSD-3-Clause** + **zlib** | Optional host JPEG codec loaded dynamically via `dlopen` / `LoadLibraryA` when present alongside the built-in MIT C JPEG encoder | <https://github.com/libjpeg-turbo/libjpeg-turbo> |

---

## 2. Standalone FFmpeg Video Playback Executable (`LGPL-2.1-or-later`)

For in-camera H.264 / HEVC `.MOV` playback, portable Linux and Windows distributions include **FFmpeg 8.1.3** (`runtime/media/ffmpeg` on Linux, `runtime/media/ffmpeg.exe` on Windows), built from unmodified upstream FFmpeg 8.1.3 release sources as a **separate external executable** invoked over standard process pipes under the **GNU Lesser General Public License v2.1 or later (`LGPL-2.1-or-later`)** (configured without `--enable-gpl` or `--enable-nonfree`).

- Each portable distribution includes the full LGPL-2.1 license text (`COPYING.LGPLv2.1`), exact build configuration script (`build.sh`), and provenance notice (`SOURCE.txt`) in **`runtime/media/licenses/`**.
- The corresponding unmodified upstream source archive (**`ffmpeg-8.1.3.tar.xz`**) is published alongside every binary release and CI build artifact, and is also available directly from upstream.
- Upstream project & legal notices: <https://ffmpeg.org/releases/> · <https://ffmpeg.org/legal.html>
- Users may substitute any compatible host FFmpeg binary at runtime via `-Dfuji.ffmpeg.bin=/path/to/ffmpeg` or `FUJI_FFMPEG_BIN=/path/to/ffmpeg`.

---

## 3. JVM & Build Ecosystem Libraries

| Component | Version(s) | License | Upstream Source |
|---|---|---|---|
| **Kotlin Standard Library & Coroutines** (`org.jetbrains.kotlin`, `org.jetbrains.kotlinx`) | `2.3.20` / `1.8.0` | **Apache-2.0** | <https://github.com/JetBrains/kotlin> |
| **Gradle & Gradle Wrapper** | `9.x` / Wrapper | **Apache-2.0** | <https://github.com/gradle/gradle> |
| **Jackson** (`jackson-core`, `jackson-databind`, `jackson-annotations`, `jackson-dataformat-yaml`) | `2.20.1` / `2.20` | **Apache-2.0** | <https://github.com/FasterXML/jackson> |
| **SnakeYAML** (`org.yaml:snakeyaml`) | `2.4` | **Apache-2.0** | <https://bitbucket.org/snakeyaml/snakeyaml> |
| **Google Guice** (`com.google.inject:guice`) | `7.0.0` | **Apache-2.0** | <https://github.com/google/guice> |
| **Google Guava** (`com.google.guava:guava` & companion annotations) | `31.0.1-jre` | **Apache-2.0** | <https://github.com/google/guava> |
| **Jakarta Inject API** (`jakarta.inject:jakarta.inject-api`) | `2.0.1` | **Apache-2.0** | <https://github.com/eclipse-ee4j/injection-api> |
| **JetBrains Annotations** (`org.jetbrains:annotations`) | `13.0` | **Apache-2.0** | <https://github.com/JetBrains/java-annotations> |
| **SLF4J API** (`org.slf4j:slf4j-api`) | `2.0.17` | **MIT** | <https://www.slf4j.org/> |
| **Checker Framework Qualifiers** (`org.checkerframework:checker-qual`) | `3.12.0` | **MIT** | <https://checkerframework.org/> |
| **AOP Alliance** (`aopalliance:aopalliance`) | `1.0` | **Public Domain** | <https://sourceforge.net/projects/aopalliance/> |

*(Test-only dependencies used strictly during development/CI — **JUnit 5** under `EPL-2.0`, plus **OpenTest4J**, **API Guardian**, and **java-diff-utils** under `Apache-2.0` — are never shipped in portable release bundles.)*

---

## 4. Bundled Platform Runtimes & Toolchain Libraries

### 4.1 OpenJDK 25 (`jlink` / `jpackage` Embedded Runtime)
- **License**: **GNU General Public License, version 2, with the Classpath Exception** (`GPL-2.0-only WITH Classpath-exception-2.0`)
- **Distribution**: Eclipse Adoptium Temurin OpenJDK 25 (`jdk-25.0.5+8`)
- **Upstream & Legal Notices**: <https://github.com/openjdk/jdk25u> · <https://adoptium.net/temurin/releases/?version=25>
- Full per-module legal notices are included inside portable distributions under `jdk/legal/` (or `lib/runtime/legal/` in Linux `jpackage` app-images).

### 4.2 OpenJFX / JavaFX 25 (`25.0.1`)
- **License**: **GNU General Public License, version 2, with the Classpath Exception** (`GPL-2.0-only WITH Classpath-exception-2.0`)
- **Modules**: `javafx-base`, `javafx-graphics`, `javafx-controls`, `javafx-swing`
- **Upstream**: <https://github.com/openjdk/jfx> · <https://openjfx.io/>

### 4.3 MinGW-w64 / GCC Runtime Libraries (Windows `x86_64` Native Binaries)
- **Components**: `libgcc`, `libstdc++`, `libwinpthread` (statically linked into Windows `x86_64` `.dll` / `.exe` binaries for zero external DLL dependency)
- **License**: **GNU General Public License, version 3 (or later), with the GCC Runtime Library Exception, version 3.1** (`GPL-3.0-or-later WITH GCC-exception-3.1`); `winpthreads` under **MIT / BSD-3-Clause**
- **Upstream**: <https://gcc.gnu.org/> · <https://www.mingw-w64.org/>

---

## 5. Original Bundled 3D & 2D Optical Scene Assets

- **Synthetic & AI-Generated Scene Artwork**: Bundled calibration and portrait/depth test scene assets (`components/cmos/src/main/resources/scenes/*`, including `mona-lisa-figure.png`, `mona-lisa-valley.jpg`, and `milk-frog-mesh.bin.gz`) are original AI-generated and procedurally synthesized assets created specifically for this project without third-party copyrighted image textures (`milk-frog-mesh.bin.gz` is a procedural 3D mesh inspired by the character turnaround by [文火_slowfire](https://www.bilibili.com/video/BV1enXoBSEfU/)).
- **Milk Frog Audio Sample** (`components/cmos/src/main/resources/scenes/milk-frog-laugh.pcm.gz`): Adapted from [`gezi-wen/dsh-naiwa-laugh`](https://github.com/gezi-wen/dsh-naiwa-laugh) under the **MIT License**.

---

## 6. Permissive License Summaries

- **0BSD (Dynarmic, mcl)**: Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted. THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE.
- **BSD-3-Clause (xbyak, winpthreads)**: Redistribution and use in source and binary forms, with or without modification, are permitted provided that copyright notices and disclaimers are retained and contributor names are not used for endorsement without prior written permission.
- **BSL-1.0 (Boost)**: Permission is hereby granted, free of charge, to any person or organization obtaining a copy of the software and accompanying documentation to use, reproduce, display, distribute, execute, and transmit the Software, and to prepare derivative works.
- **Apache-2.0**: Licensed under the Apache License, Version 2.0 (<https://www.apache.org/licenses/LICENSE-2.0>).
- **MIT**: Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files to deal in the Software without restriction, subject to retaining the copyright and permission notice.

---

## 7. Trademarks & Non-Affiliation Notice

**FUJIFILM**, **GFX**, **GFX100S**, **GF**, **PROVIA**, **Velvia**, **ASTIA**, **Classic Chrome**, **PRO Neg.**, **ACROS**, **ETERNA**, and related model or Film Simulation names are trademarks or registered trademarks of **FUJIFILM Corporation**. **Hasselblad**, **X2D**, **907X**, **CFV**, **XCD**, **Canon**, **EF**, **Zeiss**, **Planar**, **Asahi Pentax**, **Takumar**, and **Fringer** are trademarks or registered trademarks of their respective owners, referenced solely to describe optical/body simulation profiles and mount protocols.

This project is an independent, clean-room community software project created for educational, photographic, and interoperability research purposes. It is **not affiliated with, endorsed by, sponsored by, or supported by FUJIFILM Corporation** or any other camera or lens manufacturer.
