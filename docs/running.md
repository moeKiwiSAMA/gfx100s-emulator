# 运行、跨平台构建与验证手册

本手册覆盖在 **Linux / Windows / macOS** 上构建、配置、加速、启动、快照续跑与测试模拟器的全部常用命令。最小上手路径见 [README Quick Start](../README.md#quick-start在任意新电脑上跑起来)。


## 工具链与跨平台启动入口

构建、运行与 JavaFX 统一使用 **JDK 25** 与 **Gradle**（唯一构建工具），首次引导仅需本机已装 **Java 17+**。**原生 CPU 开发与发行版强制以 Dynarmic JIT（`libfuji_dynarmic.so` / `fuji_dynarmic.dll`）为必选主引擎（非可选项）**，Unicorn 仅保留作差分测试参考与兼容回退。

| 平台 | 推荐 Shell / 入口 | 必选 Dynarmic JIT 主引擎 | 差分参考库（`setup-native`） | 当前验证状态 |
| --- | --- | --- | --- | --- |
| **Linux x86_64** | POSIX `sh`（`sh jvm.sh ...` / `./gradlew`） | `.tools/dynarmic-v1/build-linux/libfuji_dynarmic.so` | `.tools/native/libunicorn.so.2` | **已持续实测**（x86_64） |
| **Windows x86_64** | Git Bash / Cygwin（`sh jvm.sh ...`）或 CMD/PowerShell（`gradlew.bat` + `java tools/EmulatorTools.java`） | `.tools/dynarmic-v1/build-win64/fuji_dynarmic.dll`（零外部依赖） | `.tools/native/unicorn.dll` | **跨平台就绪，待 Windows 实机统一验证** |
| **macOS x86_64 / aarch64** | POSIX `sh`（`sh jvm.sh ...` / `./gradlew`） | 由 `:cpu-unicorn:buildDynarmicNative` 本地编译 | `.tools/native/libunicorn.2.dylib` | **工具链支持，未实测** |

### 首次初始化（任一平台）

```sh
# 1) 自动安装并校验 JDK 25 到 .tools/jdk-25*
sh jvm.sh setup-jvm

# 2) 【必选】编译 Dynarmic ARMv7-A AVX2 JIT 主引擎（Linux / Win64 交叉）
./gradlew --no-daemon :cpu-unicorn:buildDynarmicNative
./gradlew --no-daemon :cpu-unicorn:buildDynarmicNativeWindowsCross   # 仅需打包 Windows 发行版时

# 3) （差分测试参考 / 兼容回退）纯 Java 下载/校验官方 Unicorn 2.1.4 wheel 到 .tools/native/
sh jvm.sh setup-native

# 4) 构建并初始化可移植运行目录 local/runtime/
sh jvm.sh prepare
```

- **Windows（无 Git Bash 时）**：可直接用 CMD / PowerShell 执行：
  ```bat
  java tools/EmulatorTools.java setup-jvm
  gradlew.bat --no-daemon :cpu-unicorn:buildDynarmicNative
  java tools/EmulatorTools.java setup-native
  gradlew.bat prepareRuntime
  java tools/EmulatorTools.java launch
  ```
- **Windows 路径建议**：将 `TEMP` / `TMP` / `java.io.tmpdir` 指向较短的可写目录（或通过 `--java-temp-directory` 指定），含空格路径加双引号；Git Bash/MSYS 已由 `jvm.sh` 关闭自动 argv 路径猜测（`MSYS2_ARG_CONV_EXCL='*'`）。
- **离线或高速单步构建**：环境已有依赖缓存时可加 `--no-daemon --offline`（例如 `./gradlew --no-daemon --offline :stageRuntime`）；禁止在同一 checkout 并发启动两个 Gradle 构建。

## 原生 CPU 引擎（Dynarmic JIT 必选）与原生音频桥

**Dynarmic JIT 是原生 CPU 开发、CI 与 Release 发行版的必选主引擎（非可选项）**；Unicorn 严格定位为差分一致性对照（`DynarmicVsUnicornDifferentialTest` / `strict`）与历史兼容回退：

| 定位 | 引擎 / 原生库 | 默认路径 | 平台与特点 |
| --- | --- | --- | --- |
| **必选主引擎** | **Dynarmic JIT + C++ 原生 MMIO 快速路径** | `.tools/dynarmic-v1/build-{linux,win64}/{libfuji_dynarmic.so,fuji_dynarmic.dll}` | JDK 25 FFM `critical(false)` 零 JNI、**62 项 C ABI 导出**（Win64 零外部 DLL 依赖）、C++ `soc-timer` 读影子（`-100%` timer upcall）、512 槽 O(1) RO-MMIO 缓存与 64-bit `LDRD`/`STRD` 原生拆分（[说明](../tools/native/dynarmic-bridge/README.md)）：实测 **Phase 1 `40.32 → 46.09 MIPS (+14.3%)` / MMIO `-45.4%`**、**Phase 2 `53.93 → 67.63 MIPS (+25.4%)` / `RTR 8.69×`**、**Phase 3 MMIO `-52.9%`**。 |
| **全双工音频桥（M10）** | **`fuji_audio`（miniaudio + JDK 25 FFM）** | `build/native-audio/{linux,win64}/{libfuji_audio.so,fuji_audio.dll}` | 低延迟双向音频播放、麦克风采集与**系统输出环回（WASAPI / PipeWire / PulseAudio Loopback）**；缺失原生库时透明回退 `JavaSound`。 |
| **原生 SIMD 渲染桥（PERF-2）** | **`fuji_render`（C11 SSE2/AVX2 + JDK 25 FFM）** | `.tools/render-bridge/build-{linux,win64}/{libfuji_render.so,fuji_render.dll}` | 零外部依赖 C11 渲染桥（`-msse2` 基线 + 运行时 CPUID AVX2 动态分发，[说明](../tools/native/render-bridge/README.md)）：加速 `PIC1+OSD0` 合成、Morton 瓦片与 VGLIB 栅格化，缺失时透明回退纯 Kotlin（`FUJI_RENDER_NATIVE=0` 可强制回退）。 |
| **差分测试参考（加速）** | **Unicorn Fast-Step v2（ABI v3）** | `.tools/unicorn-arm-fast-v2/build/{libunicorn.so.2,unicorn.dll}` | 自研补丁（[说明](../tools/native/README.md)）：**50M 真实冷开机 `40.203 MIPS`**，与官方 wheel 100% 比特等价且 `.ucctx` 快照互通，用于差分验证参考。 |
| **差分测试参考（基准）** | **官方 Unicorn 2.1.4 Wheel** | `.tools/native/{libunicorn.so.2,unicorn.dll,libunicorn.2.dylib}` | `sh jvm.sh setup-native` 纯 Java 提取校验，用于差分对照与零编译器兼容回退。 |

### 编译必选 Dynarmic JIT、原生音频/渲染桥与差分对照库

```sh
# 1) 【必选】编译 Dynarmic ARMv7-A AVX2 JIT 主引擎（标准 Gradle 入口）
./gradlew --no-daemon :cpu-unicorn:buildDynarmicNative              # Linux 本机 -> libfuji_dynarmic.so
./gradlew --no-daemon :cpu-unicorn:buildDynarmicNativeWindowsCross  # Linux MinGW-w64 交叉 -> 零依赖 fuji_dynarmic.dll
# 等效底层脚本：./tools/native/dynarmic-bridge/build_dynarmic.sh linux|win64|all

# 2) 编译 M10 全双工原生音频桥（miniaudio + FFM）
./gradlew --no-daemon :buildAudioNative                             # Linux -> libfuji_audio.so (~26 KB)
./gradlew --no-daemon :buildAudioNativeWindowsCross                 # Linux MinGW-w64 交叉 -> fuji_audio.dll (~161 KB)

# 3) 编译原生 SIMD 渲染桥（C11 SSE2/AVX2 + FFM）
./gradlew --no-daemon :buildRenderNative                            # Linux -> libfuji_render.so (~53 KB)
./gradlew --no-daemon :buildRenderNativeWindowsCross                # Linux MinGW-w64 交叉 -> 零依赖 fuji_render.dll (~135 KB)

# 4) （差分测试参考）编译 Unicorn Fast-Step v2（自动使用宿主机一半 CPU 核心数 max(1, nproc / 2)）
./gradlew --no-daemon :cpu-unicorn:buildArmFastNative               # Linux / macOS
gradlew.bat --no-daemon :cpu-unicorn:buildArmFastNative             # Windows
./gradlew --no-daemon :cpu-unicorn:buildArmFastNativeWindowsCross   # Linux MinGW-w64 交叉构建 Win64 unicorn.dll
```

- 仅需官方 wheel 离线安装/校验时，使用 `sh tools/setup-native.sh --catalog` 或 `--verify-only`（支持 `--platform windows-x86_64|linux-x86_64|macos-aarch64` 与本地 `--wheel` / `--release-metadata`）。

### Windows 性能优化、实时时钟节流与异步性能分析

- **Windows 原生三库加速**：Windows 一键发行版（`:packageReleaseWindows` / `:packageReleaseWindowsJpackage`）强制打包零外部依赖的 **`fuji_dynarmic.dll`**（`:cpu-unicorn:buildDynarmicNativeWindowsCross`）、**`fuji_audio.dll`**（`:buildAudioNativeWindowsCross`）与 **`fuji_render.dll`**（`:buildRenderNativeWindowsCross`），并附差分对照库 `unicorn.dll`。
- **Windows 1 ms 高精度定时器（`WindowsTimerResolution`）**：Windows 默认系统时钟粒度为 `15.625 ms`，会放大节流休眠抖动。启用 `RealTimePacer` 后，模拟器通过 Java 25 FFM 自动调用 `winmm.dll` `timeBeginPeriod(1)` 并在退出时配对 `timeEndPeriod(1)`（Linux/macOS 自动零开销跳过，无额外 DLL 依赖）。
- **实时时钟节流（`RealTimePacer`，仅 `relaxed` 模式）**：以固件 `54,000,000 ticks/s` 标定虚拟时钟，避免 WFI 空闲期满载狂奔并让交互与 RTC 走时贴近 1:1 真实墙钟（按键或 HTTP 控制命令到达时立即 `unpark` 唤醒；`discovery`/`strict` 模式严格禁止启用以保证 100% 确定性）：
  ```sh
  # 通过 CLI 开关或 JVM 属性在 relaxed 交互模式启用 1:1 实时节流
  sh jvm.sh launch --home "PATH_TO_RUNTIME" -- --realtime-pace
  # 或在 emulator.yaml 中配置：
  # realTimePacer: true   # 或 { enabled: true, targetRatio: 1.0, ticksPerRealSecond: 54000000 }
  ```
- **内置异步采样分析器（`AsyncPerfProfiler`）、彩色控制台（`ConsoleAnsi`）、分级日志（`INFO`/`DEBUG`）与指令注册表（`EmulatorConsole` + `ConsoleCommandRegistry`）**：
  - 终端日志统一采用 `[HH:mm:ss INFO]: ...`（基于纯 MIT 异步非阻塞 `CONSOLE` 通道与 `FujiLogFormatter`）：默认分层为 **终端 `CONSOLE`=`INFO`**（仅输出关键生命周期/阶段里程碑/汇总，保持终端清爽）+ **文件 `logs/latest.log`=`DEBUG`**（完整保留 `[USER/ACTION]`、`[WATCHDOG/HEARTBEAT]` 与 `[RELAXED/SUMMARY/DETAILS]` 逐项明细，便于排障）；支持启动参数 `--debug` / `--log-level=<TRACE|DEBUG|INFO|WARN|ERROR|OFF>` 与运行期 `/log [status|level <LEVEL>|file <LEVEL>]` 实时调级。在交互 TTY 自动启用 ANSI 分级着色与彩色 `fuji> ` 提示符（Windows 自动通过 JDK 25 FFM `kernel32.dll` 开启 ConHost VT100），而在 `logs/latest.log` 与非 TTY 管道/CI 中严格输出纯文本（零 ANSI 转义码）；支持环境变量 `NO_COLOR`、`FORCE_COLOR` 与 JVM 属性 `-Dfuji.console.color=auto|on|off`。
  - GUI 模式与 `--headless` / `--nogui` / `nogui` 模式下默认启动守护 stdin 控制台（EOF 时静默退出，不阻塞 CI/测试；可用 `--no-console` 或 `-Dfuji.console.disabled=true` 禁用）。
  - 内置命名空间指令注册表（支持省略或带 `/` 前缀、`<组件>:<指令>` 命名空间分发、别名、拼写错误建议、`Usage:` 提示与分页 `help [page|<cmd>|<component>]`）：
    - 核心指令（`fuji`）：`help`、`tps`（`status`）、`log [status|level <LEVEL>|file <LEVEL>]`（别名 `loglevel`、`logger`）、`color [auto|on|off|status]`（别名 `ansi`、`colours`）、`audio`（别名 `sound`，控制输出/输入/环回/设备/静音/增益/测试音/插孔）、`profiler <start|stop|info|report|health>`（别名 `prof`、`timings`）、`health`（`gc`）、`components`（`plugins`）、`boot`、`pause`、`resume`、`stop`。
    - 动态注册的硬件组件指令：`buttons`（`button`/`press`、`power`、`mode`）、`i2c-bus`（`touch`、`evf`）、`display`（`screenshot`、`display`）、`battery`（`battery`）、`mount`（`mount`）、`lens`（`lens [status|templates|template <id>|focus <m|inf>|af|aperture <f>|focal <mm>|optics <on|off>]`）、`cmos`（`cmos`、`source [status|set <kind> <ref> [loop]]`）、`sdcard-reader`（`sd`）、`flash-image`（`flash`）。
  - 也支持 CLI 开关 `--profile`（可选 `--profile-interval-ms 20`）开机自动采样，或经本地 HTTP 接口 `POST /api/profiler/start`、`POST /api/profiler/stop`、`GET /api/profiler/report?format=text` 获取同源性能报告（默认关闭，零热路径侵入）。

## 自包含 `jlink` + `jpackage` + AOT 缓存一键 Release 打包与 GitLab CI

面向最终用户的发行版默认采用 **`executionMode: relaxed`**（必选 **Dynarmic JIT** 主引擎 + `instructionBudget: unlimited`，支持 GUI 暂停/继续、标定 `SingleStepButtonHold` 使 `MENU/OK` 与 `DISP/BACK` 在 STILL 实时取景稳定响应、`0x0423` 世界地图完整渲染、独立`镜头光学`页签/浮窗、独立`音频 I/O`页签与右侧栏输入源/音频快捷切换，且 M8 全模式实测 `0` MMIO 回退；启动前自动复用已有 `cow-*.img` 与双槽目录直通 SD 卡；研究级精确门控仍保留在 `discovery` / `strict`），详见 [发行版使用说明](../tools/release/RELEASE-README.md) 与 [模拟出厂 Flash 说明](../tools/release/factory-flash-README.md)：

```sh
# 1) 生成自包含 JDK 25 jlink 精简运行时（无需宿主机安装 JDK）
./gradlew --no-daemon :buildJlinkRuntimeLinux              # 产物约 64M
./gradlew --no-daemon :buildJlinkRuntimeWindows            # 原生/交叉 jlink 运行时

# 2) 打包 Linux 原生 jpackage app-image (.tar.gz，约 89M，零系统 JDK 实测冷启至 STAGE_6_INTERACTIVE_UI)
./gradlew --no-daemon :packageReleaseLinuxJpackage :packageReleaseLinuxAppImageTar

# 3) （可选）生成 JDK 25 AOT 冷启动加速缓存（JEP 483，约 29M）
./gradlew --no-daemon :buildAotCacheLinux

# 4) 构建 Windows 43K 原生启动器（fuji-emulator-gfx100s.exe）与 Windows jpackage / 标准包
./gradlew --no-daemon :buildWindowsNativeLauncher :packageReleaseWindowsJpackage
./gradlew --no-daemon :packageReleaseLinux :packageReleaseWindows

# 5) 单独生成模拟出厂 Flash 模板（源码定义在 tools/release/factory-flash-template.yaml）
./gradlew --no-daemon :generateFactoryFlashTemplate
```

- **扩展出厂 Flash 字段**：后续研究确定出厂配置字段后，只需向 `tools/release/factory-flash-template.yaml`（及其对应的权威 Flash 字典）追加字段定义，打包任务即自动打补丁并重算 `regions.json` 与 `mutable.json` 的 SHA-256。

## 运行目录、独立组件与配置

```text
local/runtime/
  config/
    emulator.yaml             # executionMode(默认 discovery)、firmwareFile、nativeLibrary、预算与 discovery 策略
    catalog.compiled.json     # 由 :address-catalog:compileCatalog 自动生成，禁止手改
    firmware/gfx100s-213.yaml # 机身/固件装配 profile
  compoments/
    battery-0.3.0.jar
    battery/battery.yaml
    display-0.3.0.jar
    cmos-0.3.0.jar
    soc-jpeg-0.3.0.jar
    sdcard-reader-0.3.0.jar
    sdcard-reader/
      sdcard-reader.yaml
      sdcard-reader-slot-1.img
      sdcard-reader-slot-2.img
```

- **同步运行目录**：`sh jvm.sh prepare`（或 `./gradlew prepareRuntime`）将最新 JAR 与 `config/catalog.compiled.json` 同步到 `local/runtime`，保留用户已改过的 `emulator.yaml` 与组件 YAML。
- **窄契约装配**：`ComponentLoader` / `ComponentHost` 仅按窄 ID、兼容 ABI 与导出端口校验组件，不要求因全局字典摘要变动重打无关 JAR。
- **常用显式外设/按键 Profile（默认按需开启，除标明 `live` 外均为 `restart-only`）**：
  - `soc-gpio`：`profile: gfx100s-213-gpio-buttons-v1`（DISP/BACK、快门半按/全按）或 `v2`（增加 `PLAY` GPIO 9.6）
  - `power-peer`：`powerButtonProfile: gfx100s-213-power-switch-v1`（电源拨杆）
  - `soc-adc`：`menuButtonProfile: gfx100s-213-menu-adc-v1`（MENU/OK 按键），以及 14 项 `live=true` 在线控制（CH3 `qButton`/`aelButton`、CH5 `modeDial` `M/C1..C6/P/S/A`、CH6 `fn1/2/3Button`/`lightButton`、CH7+11 `rearDialPosition`/`rearDialDetents` 与 `ch{3,5,6,7,11}Override`；也可通过 `GET`/`POST /api/adc` 与 `rearDialStep` 热操控）
  - `buttons`（`relaxed` 开机按键保持）：支持在 `emulator.yaml` 中配置 `bootHoldButtons: [back]` 或在 `buttons/boot-hold.yaml` 中配置 `holdButtons`，冷启动进入固件升级等组合键模式时保留指定按键按下状态
  - `soc-gpio` + `soc-adc`：`directionButtonProfile: gfx100s-213-direction-input-v1`（八向方向键）
  - `i2c-bus`：`eyeSensorNear`（`live=true`，在线切换 `I2C1 @ 0x39` 眼传感器远近）
  - `sdcard-reader`：`slot1Inserted`、`slot2Inserted`、`slot1ReadOnly`、`slot2ReadOnly`（`live=true`，运行时经 `PATCH /api/v1/components/sdcard-reader` 在线热插拔与切换写保护，自动联动 `GPIO 10.4/10.8` 与 SDHCI `PRESENT_STATE`/`CARD_INSERTION`/`CARD_REMOVAL`；与 `soc-sd-host/sd-card-v9.json` 存在差异时以 `sdcard-reader.yaml` 为权威来源自动对齐）
  - `soc-vglib-init`：`evfScanout`（经 `LCDC1 0xFFFA9000` `+0x014=0x0500` 输出 `1280×960` EVF 到 `display.evf`，与 `+0x014=0x0400` `1024×768` 后屏共享 `LCDC1`；`LCDC0 0xFFF7A000` 为 HDMI 通道）与 `lcdc1-pic-yuv-osd0-nearest-v1`（`PIC1 YUV + OSD0` 复合扫描输出，支持 `4:3`/`3:2`/`16:9`/`1:1`/`65:24`/`5:4`/`7:6` 非 4:3 画幅）

## 六面 GUI、HTTP 控制与地址字典查询

### 启动桌面六面 GUI

```sh
# 使用默认运行目录 local/runtime
sh jvm.sh launch

# 指定任意运行目录（含空格路径用双引号包裹）
sh jvm.sh launch --home "PATH_TO_RUNTIME"
```

- 在无物理显示器的 Linux 服务器上，可配合 `DISPLAY=:1`（如 `Xvnc :1`）运行图形模式；Windows / macOS 桌面直接启动即可显示六面机壳窗口。布局与视觉约束见 [界面规范](ui/gfx100s/README.md)。

### 本地 HTTP 控制接口（仅绑定 loopback，拒绝含 `Origin` 头）

| 接口 | 方法 | 说明 |
| --- | --- | --- |
| `/api/v1/state` | `GET` | 返回 owner 快照、schema、配置 `revision`、组件状态与 CPU frontier |
| `/api/v1/components/<id>` | `PATCH` | 提交 `{"revision":0,"changes":{...}}`（`Content-Type: application/json`，陈旧 revision 返回 `409`，非法键或 `restart-only` 返回 `400`） |
| `/api/v1/dump` | `POST` | 在 owner 安全点导出有界状态/寄存器 ZIP dump（接受空 body 或 `{}`，返回 `201`；内存最多 16 块 / 合计 16 MiB） |
| `/api/adc` | `GET` / `POST` | 查询或实时操控 `soc-adc` 按键/拨盘（CH3 `qButton`/`aelButton`、CH5 `modeDial`、CH6 `fn1..3Button`/`lightButton`、CH7+11 后拨盘 `rearDialStep`/`rearDialPosition`/`rearDialDetents` 与原始通道覆盖） |
| `/api/v1/catalog` | `GET` | 只读查询编译字典（支持 `address`、`width`、`direction`、`id`、`property`、`space`、`selector`、`wordBits`、`coverage=true`、`unknown=true`） |
| `/api/audio` | `GET` / `POST` | 查询全双工音频实时状态（含 VU 电平、TX/RX 环形缓冲与插孔状态），或通过 14 键 JSON 子集热切输出/输入/环回/设备/延迟/后端/增益/静音/测试音/3.5mm 插孔 |
| `/api/audio/devices` | `GET` | 枚举宿主机可用音频输出、麦克风与系统环回（Loopback）设备清单 |
| `/api/profiler/start` / `stop` / `report` | `POST` / `GET` | 启停异步采样分析器并获取 `json`/`text` 性能报告（`?format=text`） |
| `/api/telemetry/upload-log` | `POST` | 按需将本次运行日志与诊断摘要上传至 `fuji-telemetry-hub`（默认不自动上传） |

### 字典编译与 CLI 静态查询

```sh
# 修改 catalog/**/*.yaml 后重新编译并校验
./gradlew --no-daemon :address-catalog:compileCatalog :address-catalog:verifyCatalog

# CLI 静态字典查询（不启动 CPU 或读取动态设备）
sh jvm.sh launch --home "PATH_TO_RUNTIME" -- --query-address 0xFFFC41E0 --width 4 --space cpu-physical --direction read
sh jvm.sh launch --home "PATH_TO_RUNTIME" -- --query-id "STABLE_DICTIONARY_ID" --property provenance
sh jvm.sh launch --home "PATH_TO_RUNTIME" -- --query-coverage
sh jvm.sh launch --home "PATH_TO_RUNTIME" -- --query-selector 29 --space rtc-power-register --word-bits 16 --direction read
```

## `EmulatorSnapshot` v1 快照保存与续跑

项目以跨平台版本化模拟器快照 **`fuji-emulator-snapshot-v1`**（`DisplayBringupSnapshot`）替代旧版 Linux 进程级 DMTCP 映像：

- **覆盖完整状态**：4 核 ARM/FPU `.ucctx`（与 Fast-Step v2 / v1 / 官方 wheel 100% 互通）、共享 RAM（8 线程并行校验）、全部 `StatefulComponent` 外设状态、虚拟时钟、GIC/IRQ、`WordExclusive`、输入游标与稀疏 SD 块镜像。
- **跨平台稀疏盘摘要**：Linux 下对 `> 64 MiB` 大盘启用 `SEEK_DATA`/`SEEK_HOLE` + OpenSSL `libcrypto.so.3` FFM 加速；**Windows NTFS 下启用 `kernel32.dll` `DeviceIoControl(FSCTL_QUERY_ALLOCATED_RANGES)` 跳洞 + HotSpot SHA-NI/AVX2 硬件 `MessageDigest`**（并支持路径键缓存命中）；非稀疏卷或 macOS 自动回退纯 JVM，摘要结果逐字节一致。
- **安全复用**：严禁原地改写共享快照或冻结 HOME；分支实验先复制到独立工作目录再续跑。

## 测试、原厂采证与研究

### 轻量单门控：日常定向测试

日常修改只跑受影响模块单测（无需重跑全量长程任务）；多测试类并行可加 `-PfujiTestForks=4`（1..8）：

```sh
# 原生 CPU 单元测试（未设 FUJI_UNICORN_LIBRARY 时按平台自动探测）
./gradlew --no-daemon :cpu-unicorn:test

# 指定多个硬件组件测试
./gradlew --no-daemon :components:soc-sd-host:test :components:soc-jpeg:test :components:cmos:test
```

### 4 层 120 项 E2E 套件（`discovery` 模式）

常规与里程碑 E2E 统一使用 `discovery` 模式（**不设置 `-Dfuji.e2e.strict=true`**）；`E2eHarness.resolveNativeLibrary()` 按平台自动解析可用原生库（Linux 优先 Fast-Step v2，其余回退 `.tools/native/` 官方 wheel）：

```sh
FUJI_FIRMWARE_FILE="$PWD/firmware-data/gfx100s/2.13/GXUP0004.DAT" \
./gradlew --no-daemon -PfujiTestForks=1 \
  :desktop:test --tests "com.github.nyabanban.fuji.emulator.desktop.e2e.*"
```

*(Linux 无头服务器需在前面加 `DISPLAY=:1 XAUTHORITY="$HOME/.Xauthority"` 以覆盖 3 项 JavaFX `--smoke-ui` 图形用例；Windows / macOS 桌面直接运行即可。)*

### Fast-Step v2 与 M5P 性能及比特等价基准

```sh
FUJI_FIRMWARE_FILE="$PWD/firmware-data/gfx100s/2.13/GXUP0004.DAT" \
./gradlew --no-daemon :desktop:test \
  --tests "*FastStepV2BootBenchmarkTest*" \
  --tests "*M5pPerformanceBenchmarkTest*"
```

### 里程碑全量采证与离线实机 Dump 核验

仅在里程碑交汇或核对实机 dump 时运行（输出必须指向全新目录，不覆盖既有证据）：

```sh
sh jvm.sh collect --firmware "PATH_TO_DAT" --native-library "PATH_TO_NATIVE" \
  --output "evidence/runs/NEW-run" --jobs 2
sh jvm.sh verify --run "evidence/runs/NEW-run"
sh jvm.sh compare-runs --before "evidence/runs/OLD-run" --after "evidence/runs/NEW-run" \
  --output "local/NEW-comparison.json"

# 实机 dump 只读离线核验（不启动 guest）
  -PhardwareDumpArchive=firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip \
  -PhardwareDumpReport=local/NEW-hardware-dump.generated.json \
  -PgitExecutable="$(which git)" :firmware:analyzeHardwareDump
```

## 文本、文档与推送前 7 门控卫生检查

```sh
# 推送 / 合并前一键自动化审计（7 门控红线：保护文件、路径泄露、编码、文档对齐与原生同步检查）

# 维护文档本地链接检查（任意断链即失败）
./gradlew --no-daemon -I tools/check-docs.init.gradle.kts verifyDocumentation

# UTF-8 / 无 BOM / LF / 末尾单 LF 增量文本检查
./gradlew --no-daemon -I tools/check-text.init.gradle.kts verifyProjectText

# 行尾空白与工作区状态核对
git diff --check
git status -sb
```
