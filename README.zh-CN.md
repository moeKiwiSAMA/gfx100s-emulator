<div align="center">

# FUJIFILM GFX100S Emulator

*MEDIUM FORMAT SYSTEM EMULATOR · 富士フイルム GFX100S エミュレータ*

[English](README.md) | **简体中文** | [日本語](README.ja.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform: Windows x86_64 | Linux x86_64](https://img.shields.io/badge/Platform-Windows%20x86__64%20%7C%20Linux%20x86__64-informational)
![Firmware: GFX100S v2.13](https://img.shields.io/badge/Firmware-GFX100S%20v2.13-black)
![Zero Proprietary Binaries](https://img.shields.io/badge/Binaries-Zero%20Proprietary%20Included-success)

*AArch32 4 核 SMP Dynarmic JIT · Vulkan 1.1 计算光学 · 六面实体相机工作台*

</div>

---

> [!CAUTION]
> **独立洁净室与 AI 辅助工程免责声明**
>
> 本项目是一个独立的、非官方关联的洁净室软件项目，开发过程中大量使用了 AI 辅助工程（包含各种 "AI-slop" 的粗糙之处与瑕疵）。仓库内**不包含**任何富士胶片专有固件、BootROM 或原厂校准二进制文件。
>
> 模拟器中的 SoC 外设、中断时序及光学/ISP 模型仅为在 PC 上启动并体验相机操作系统而近似实现——**它们可能且确实会与真实的 GFX100S 硬件存在差异**。
>
> 请**勿**将本代码库视作权威硬件规格书。如果您基于本模拟器或其观察结果对真机进行逆向分析、修改自定义固件或开展硬件实验，并导致您的真机**变砖、丢失出厂校准数据、失去保修或遭受任何其他损坏、数据与财产损失**，**作者及所有贡献者不承担任何责任——一切风险完全由您自行承担。**

---

## *致富士胶片 — 写真機としての歓び*

富士胶片在 GFX 系统上所做的事情，本身带着一种安静的勇气。很多年里，中画幅几乎只属于影棚里的沉重支架与谨小慎微的三脚架。而富士将一块 43.8 × 32.9 mm 的传感器——面积超过全画幅 1.7 倍——放进了一台可以带进风雨、山岚与街头巷尾的防滴防尘机身里。比参数更打动人的，是它始终守住了「相机握在手里应当是什么感觉」：刻度分明的机械拨盘、阻尼恰到好处的镜头光圈环、需要屏息按下的两段式快门，以及近九十年色彩记忆凝练而成的 PROVIA、Velvia、ASTIA、Classic Chrome、ACROS 与 ETERNA——光线在其中不是被冰冷记录，而是像被温柔记住。

我们写下这个模拟器，正是出于对这份造物精神的喜爱：

- **我们由衷希望每个人都能拥有一台真正的富士相机。** 屏幕上渲染出的一切，都永远无法替代掌心冰凉的镁合金质感、GF 镜头光圈环细腻的段落阻尼，以及真实玻璃汇聚真实光线的那一刻。
- **对于预算尚未到位、仍在路上的学生、梦想家与摄影爱好者**，我们希望这个项目能在您的书桌上打开一扇窗，让您先行触摸富士中画幅的操作逻辑、菜单哲学与光学性格，直到您亲手捧起真机的那一天。
- **对于已经拥有 GFX100S 或 GFX 系统的朋友**，也希望它能成为您桌面上一块安全、有趣的试验田，陪您探索手中相机内部的思考方式，发掘这台器材更多意想不到的可能。

---

## 平台与硬件要求

本模拟器高度依赖针对 `x86_64` 架构手调的 JIT 动态编译与 SIMD 图像桥接库，下载前请确认您的主机环境：

| 组件 | 要求 | 说明 |
|---|---|---|
| **操作系统与架构** | **仅支持 64 位 `x86_64`（`amd64`）Windows 10/11 与 Linux** | **暂不支持 Apple Silicon（`macOS arm64`）及 Linux `aarch64`。** `fuji_dynarmic` 与原生 SIMD 图像桥接库深度绑定 `x86_64` 执行路径。 |
| **CPU 指令集** | **强烈建议具备 `AVX2` 或 `AVX-512`** | 4 核 ARMv7-A Dynarmic JIT、SIMD 渲染器、CPU ISP 与空间光学滤镜桥接库均重度使用向量指令（部分链路保留 SSE2 回退，但现代 `AVX2`/`AVX-512` 桌面与笔记本处理器是设计基线）。 |
| **GPU（推荐）** | **支持 Vulkan 1.1+ 的显卡**（Windows `vulkan-1.dll` / Linux `libvulkan.so.1`） | 开启实时 GPU 3D 场景光栅化、2×2 金字塔散景卷积与光学滤镜计算着色器。无 Vulkan 环境将自动回退至多线程 SIMD CPU 光学管线（日志中会明确记录 `WARN [FALLBACK][vulkan]`）。 |
| **系统内存** | **建议 8 GB 及以上 RAM** | 模拟器会在堆外建模 GFX100S 的 40 位 LPDDR4 内存空间，同时桌面端 JVM 默认分配 `-Xmx2g` 堆内存。 |

---

## 核心特性一览

- **无修改运行 GFX100S v2.13 原厂固件**：在 4 核 SMP ARMv7-A + NEON/VFPv4 **Dynarmic JIT**（`cpuEngine: dynarmic`）上直接启动富士官方 GFX100S v2.13 固件（`0` 固件二进制补丁）；完整建模 64 MiB QSPI NOR Flash（`MT25QU512ABB` 扇区擦除与页编程语义）、Sub-MCU 纽扣电池 NVRAM、1.02 亿像素静态拍摄（内置零外部依赖的多线程 C 语言 JPEG 编码器、`LSRAW` 无损/压缩 RAW、4 亿像素位移多重拍摄及机内 RAW 冲印），以及 1080p H.264/HEVC `.MOV` 录制与回放。
- **六面实体相机工作台与 Theme System v2**：可交互的机背、机顶、正面、左右侧面接口盖板与双 SD 卡/电池仓门；机顶肩屏 Sub-LCD；无边框置顶**悬浮 EVF 取景器**；可旋转**模式拨盘（`P/S/A/M/C1..C6`）**、**`S/C/M` 对焦拨杆**与**两段式 `S1`/`S2` 快门**；内置 4 套机身主题（**GFX100S**、**Hasselblad X2D II 100C**、**Hasselblad 907X & CFV 100C**、**紧凑旁轴 Compact Rangefinder**）、5 套 UI 界面主题，以及即时切换的 6 国语言界面（`en`、`zh_CN`、`ja_JP`、`de_DE`、`fr_FR`、`es_ES`）。
- **模块化镜头、通用光学滤镜栈与 Vulkan 1.1 光学引擎**：内置 12 支可热插拔的原生 GF 与转接经典镜头（`lenses/*.jar`，支持双向 `LENSCOM` 光圈/对焦/OIS 同步、对焦呼吸效应、轴向色差 LoCA 与猫眼口径蚀）、12 槽位通用遮光斗光学滤镜栈（ND、GND、黑柔 Black Mist、星芒 Starburst、物理 UV、异形/自定义散景光瞳遮罩及自定义 `.spv` 着色器）、五轴防抖与竖拍姿态传感器、5 个内置 3D 景深测试场景（`qr-ball`、`star-bokeh`、`mona-lisa`、带同步音频的 `milk-frog`、`depth-studio`），以及摄像头、视频流、屏幕/游戏画面采集和全双工主机音频（`WASAPI` / `PipeWire` / `PulseAudio` 回采）。

---

## 快速上手

### 方式一 — 一键下载便携版（推荐所有人使用，无需安装 Java）

每个便携发行包均已内置精简版 **JDK 25（`jlink`）运行时**及全套 `x86_64` 原生动态库。您**无需**手动安装 Java、CMake 或任何编译器。

1. **下载对应系统的便携包**：
   - **稳定发布版**：[**GitHub Releases**](https://github.com/moeKiwiSAMA/gfx100s-emulator/releases)
   - **最新 CI 构建**：[**GitHub Actions**](https://github.com/moeKiwiSAMA/gfx100s-emulator/actions) → 点开最新的绿色 **Build & Package** 工作流 → 在页面底部的 **Artifacts** 中下载 `gfx100s-emulator-windows-x86_64` 或 `gfx100s-emulator-linux-x86_64` *（需登录 GitHub 账号方可下载 Actions 构建产物）*。
2. **下载富士官方 GFX100S v2.13 固件（`GXUP0004.DAT`）** — 见下方[固件准备与可选真机转储](#固件准备与可选真机转储)。
3. **解压并启动**：
   - **Windows（`x86_64`）**：将 `.zip` 解压到任意文件夹，双击 **`fuji-emulator-gfx100s.exe`**（或 **`start-gfx100s.bat`**）。
   - **Linux（`x86_64`）**：解压压缩包后运行 **`./start-gfx100s.sh`**（在 `app-image` 目录中也可直接运行 **`./bin/fuji-emulator-gfx100s`**）。
4. **首次启动导入固件**：
   - 若 `flash/nor-flash.bin` 尚未初始化，模拟器会弹出友好的**固件设置向导**，直接选择下载好的 `GXUP0004.DAT` 即可（也可通过顶部菜单 **Flash → Flash Firmware (.DAT)...**，或命令行传入 `--flash-dat <GXUP0004.DAT路径>`）。程序会自动生成 64 MiB 的 `flash/nor-flash.bin` 并直接引导进入 GFX100S 首次开机语言与时钟设置界面。

### 方式二 — 从源码构建（面向开发者与 Mod 作者）

前置环境：**Git**、**Java 17+**（仅用于引导；`sh jvm.sh setup-jvm` 会自动拉取 **JDK 25**，也可自行配置 `JAVA_HOME` 指向 JDK 25），以及 **C/C++20 + CMake + Ninja** 工具链（Linux 使用 `gcc`/`g++`；Windows 使用 MSYS2 MinGW-w64，或使用 `x86_64-w64-mingw32-g++` 交叉编译）。

```bash
# 1. 自动拉取 JDK 25 到 .tools/（若已配置系统 JDK 25 可跳过）
sh jvm.sh setup-jvm

# 2. 编译全部必需的 x86_64 原生桥接库（Dynarmic JIT、音频、SIMD 渲染/ISP、Vulkan 光学、编解码、手柄）
./gradlew --no-daemon \
  :cpu-dynarmic:buildDynarmicNative \
  :buildAudioNative \
  :buildRenderNative \
  :buildVulkanOpticsNative \
  :buildIspSimdNative \
  :buildCodecNative \
  :buildGamepadNative

# 3a. 打包独立便携的 Linux 发行版（纯净构建，不打包任何专有文件）：
./gradlew --no-daemon :packageReleaseLinux -PfujiBundleStockBins=false
# -> 产物位于 build/distributions/

# 3b. 或在 local/runtime/ 搭建本地开发运行时并直接启动：
sh jvm.sh prepare
sh jvm.sh launch
```

*（在未使用 Git Bash 的 Windows PowerShell / CMD 中，请改用 `java tools/EmulatorTools.java setup-jvm` 与 `.\gradlew.bat ...`。）*

---

## 固件准备与可选真机转储

### 必需 — 富士官方 GFX100S Ver. 2.13 固件（`GXUP0004.DAT`）

1. 前往富士胶片官方支持页面下载 **`GXUP0004.DAT`**：
   <https://www.fujifilm-x.com/global/support/download/firmware/cameras/gfx100s/>
2. 刷入前请核对 **SHA-256** 校验值：
   ```text
   ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12
   ```
   - **Windows PowerShell**：`Get-FileHash .\GXUP0004.DAT -Algorithm SHA256`
   - **Linux**：`sha256sum GXUP0004.DAT`
3. 通过首次启动向导、菜单 **Flash → Flash Firmware (.DAT)...** 或命令行写入 `<模拟器目录>/flash/nor-flash.bin`：
   - **Windows**：`.\fuji-emulator-gfx100s.exe --flash-dat path\to\GXUP0004.DAT`
   - **Linux**：`./start-gfx100s.sh --flash-dat /path/to/GXUP0004.DAT`

### 可选 — 真机 `bootrom` 与 `flash` 转储（还原完整芯片冷启动）

默认情况下，模拟器仅需官方 `GXUP0004.DAT` 即可通过 **Flash-Direct** 模式正常启动——**真机转储完全不是必需项**。

拥有 GFX100S 实机的进阶用户，也可借助社区 SD 卡研究工具如 [**fujimod**](https://github.com/justjake/fujimod)（[fujimod.com](https://fujimod.com)）或 [**FujiHack**](https://github.com/fujihack/fujihack)，自行提取 32 KiB 掩膜 BootROM（`bootrom/boot-rom.bin`，SHA-256 `2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4`，通过 `--boot-rom` 或在 `configs/emulator.yaml` 中设置 `bootRomBoot: true` 启用）以及 64 MiB QSPI NOR Flash（`flash/nor-flash.bin`）。

> [!WARNING]
> 转储前请务必在真机上执行一次**恢复出厂设置（`MENU → SET UP → USER SETTING → RESET → INITIALIZE`）**，并且**切勿公开发布个人未脱敏的原始 NOR Flash 转储文件**（`[0x03500000..0x03D40000)` / `bank-14` 区域存有相机唯一序列号、Wi-Fi/BLE MAC 地址与出厂传感器校准参数）。在真机上运行自定义转储载荷存在变砖与失保风险，**一切后果均由您自行承担**——或者坦率地说，**找一位已经提取过脱敏转储的可靠朋友帮忙**，往往是最省心的选择。

完整分步指南请阅读：**[`docs/FIRMWARE.zh-CN.md`](docs/FIRMWARE.zh-CN.md)**

---

## 文档、扩展开发与 AI Skills 中心

| 文档 | 语言 | 内容概要 |
|---|---|---|
| **固件与真机转储指南** | [EN](docs/FIRMWARE.md) · [中文](docs/FIRMWARE.zh-CN.md) · [日本語](docs/FIRMWARE.ja.md) | `GXUP0004.DAT` v2.13 校验、NOR Flash 初始化与可选真机转储 |
| **模拟器用户手册** | [EN](docs/manual/USER_MANUAL.md) · [中文](docs/manual/USER_MANUAL.zh-CN.md) · [日本語](docs/manual/USER_MANUAL.ja.md) | 六面操作台、主题、拨盘、镜头、滤镜、3D 场景、双 SD 卡与音频 |
| **扩展与 Mod 开发指南** | [EN](docs/extending/EXTENDING.md) · [中文](docs/extending/EXTENDING.zh-CN.md) · [日本語](docs/extending/EXTENDING.ja.md) | 自定义镜头 JAR、滤镜 / `.spv` 着色器、Theme v2、非目标与 6 语 i18n |
| **HTTP 控制与遥测 API** | [EN](docs/api/HTTP_API.md) · [中文](docs/api/HTTP_API.zh-CN.md) · [日本語](docs/api/HTTP_API.ja.md) | 本地 `127.0.0.1:8787` REST 自动化与 Python/`curl` 脚本示例 |

### 面向 AI 编程助手的公开 Skills（`skills/`）

专为 Claude Code、Gemini CLI / Antigravity、Codex、Cursor 等 AI 编程代理准备的 `SKILL.md` 规范文件：

- [`skills/create-lens/SKILL.md`](skills/create-lens/SKILL.md) — 构建与标定自定义原生或转接镜头 JAR（附精选光学测试数据来源）
- [`skills/create-optical-filter/SKILL.md`](skills/create-optical-filter/SKILL.md) — 创建自定义光学滤镜单元、异形散景光瞳遮罩与 Vulkan `.spv` 计算着色器
- [`skills/create-theme/SKILL.md`](skills/create-theme/SKILL.md) — 设计自定义相机机身主题（PNG+SVG 混合渲染、旋转拨盘、LED 指示灯）与 UI CSS 主题

---

## 提交 Issue 与日志位置

欢迎在 [**GitHub Issues**](https://github.com/moeKiwiSAMA/gfx100s-emulator/issues) 提交缺陷报告或硬件行为对比反馈。提单时请务必附上纯 UTF-8 调试日志（`DEBUG` 级别，按每次运行自动轮转归档）：

- **Windows（便携发行版）**：`<模拟器目录>\logs\latest.log` *（就在 `fuji-emulator-gfx100s.exe` 同级目录下；历史日志归档为 `logs\yyyy-MM-dd-N.log.gz`）*
- **Linux（便携发行版）**：`<模拟器目录>/logs/latest.log` *（就在 `start-gfx100s.sh` / `bin/fuji-emulator-gfx100s` 同级目录下；历史日志归档为 `logs/yyyy-MM-dd-N.log.gz`）*
- **源码运行（`sh jvm.sh launch` / `./gradlew :desktop:run`）**：`<runtime-home>/logs/latest.log` *（例如 `local/runtime/logs/latest.log` 或 `./logs/latest.log`，亦可通过 `-Dfuji.log.dir=<path>` 自定义）*

请勿在公开 Issue 中上传专有固件二进制文件或个人相机序列号/MAC 地址转储。

---

## 开源协议与诚挚的非商业请求

本项目采用 [**MIT 协议**](LICENSE) 开源（`Copyright (c) 2026 moeKiwiSAMA`）。第三方开源组件声明（含 `0BSD` 协议的 Dynarmic、`LGPL-2.1+` 协议的独立 FFmpeg 回放二进制及配套源码包、`GPLv2+CE` 协议的 OpenJDK/OpenJFX 25 等）详见 [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)。

虽然 MIT 协议在法律上允许广泛的再分发与使用，但**我们强烈不建议将本模拟器用于任何商业用途、付费打包或商业化变现**。

> **如果你有拿它去做商业化的预算，请把这笔钱用来买一台真正的富士相机吧！**

---

## 致谢

1. **FUJIFILM Corporation（富士胶片）** — 感谢你们造出了如此非凡的 **GFX100S**、GF 光学系统，以及启发了本项目每一行代码的摄影工艺与色彩科学。
2. [**fujimod**](https://github.com/justjake/fujimod)（[fujimod.com](https://fujimod.com)）与 [**FujiHack**](https://github.com/fujihack/fujihack) 社区 — 感谢你们在富士相机开放研究领域的开拓性工作与宝贵的架构参考资料。
3. [**Dynarmic**](https://github.com/merryhime/dynarmic)（及我们的 AArch32 SoC JIT 分支 [`moeKiwiSAMA/dynarmic`](https://github.com/moeKiwiSAMA/dynarmic)） — 感谢其为本模拟器核心提供了稳固、高性能的 ARMv7-A JIT 编译底座。

---

*FUJIFILM、GFX、GFX100S、GF 及各胶片模拟名称均为 FUJIFILM Corporation 的注册商标。本项目为独立开源项目，与 FUJIFILM Corporation 无任何隶属或背书关系。*
