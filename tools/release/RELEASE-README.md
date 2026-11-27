# FUJIFILM GFX100S 2.13 模拟器发行版

本发行包已内置 **完整 64 MiB 统一 QSPI NOR Flash 2.13 出厂模板（`runtime/templates/factory-flash/nor-flash-2.13.bin`，首次启动自动无缝初始化最外层 `flash/nor-flash.bin`，同时提供双语放置说明 `flash/PUT_HERE`，无需额外携带冗余 `GXUP0004.DAT` 或在压缩包内重复打包两份 64 MiB 镜像；公开脱敏构建可通过 `-PfujiBundleStockBins=false` 仅保留 `PUT_HERE` 指引供用户自行放置或刷入）**、**32 KiB SPX4 掩膜 BootROM（最外层 `bootrom/boot-rom.bin` + 双语说明 `bootrom/PUT_HERE`）**、对应平台的 **强制必选高性能原生组件（非可选项，经 `:verifyReleaseLinux` / `:verifyReleaseWindows` 门禁校验）**——包括 **Dynarmic ARMv7-A AVX2 原生 JIT 主引擎**（`libfuji_dynarmic.so` / 零依赖 `fuji_dynarmic.dll`，Dynarmic-only 发行版不含 Unicorn）、**M10 全双工原生音频桥**（`libfuji_audio.so` / 零依赖 `fuji_audio.dll`）、**PERF-2 原生 SIMD 渲染管线桥**（`libfuji_render.so` / 零依赖 `fuji_render.dll`，SSE2 基线 + 运行期 AVX2 动态派发）、**VULKAN-W1 动态 Vulkan 1.1 计算光学管线桥**（`libfuji_vulkan_optics.so` / 零依赖 `fuji_vulkan_optics.dll`，动态加载系统 `libvulkan.so.1` / `vulkan-1.dll` 并支持 2×2 散景金字塔、Stage-1 显存缓存与 GPU 3D 场景光栅化，无可用硬件 GPU 时显式回退至 CPU 光学管线）、**多线程 CPU ISP/滤镜 SIMD 桥**（`libfuji_isp_simd.so` / 零依赖 `fuji_isp_simd.dll`）、**原生 LSRAW / 102MP 内置多线程纯 C JPEG 编解码桥**（`libfuji_codec.so` / 零依赖 `fuji_codec.dll`，内置零外部依赖多线程基线 `YUV422` 102MP 流式 JPEG 编码器——Windows 无需系统 `libjpeg` 即可 ~291 ms 直出 102MP JPEG，并自动兼容宿主机 `libjpeg-turbo` / `libjpeg.so.62`）与 **原生多手柄输入桥**（`libfuji_gamepad.so` / 零依赖 `fuji_gamepad.dll`）、**原生一键启动器与 `jlink`/`jpackage` 自包含 JDK 25 运行时**（Windows `fuji-emulator-gfx100s.exe` / Linux `jpackage` app-image）——以及全部独立 SoC/硬件组件 JAR、**出厂 2.13 NOR Flash 模板（`runtime/templates/factory-flash/nor-flash-2.13.bin`）** 与 **双卡槽目录直通 SD 卡环境**，默认启用 **`relaxed` 无限指令游玩模式**（强制使用 Dynarmic JIT 主引擎 + `instructionBudget: unlimited`，持续运行至窗口关闭；遇到尚未建模的外设寄存器时自动保持读写值而不中断模拟器；研究级精确门控仍保留在 `discovery` / `strict` 模式）。

## 一键启动（开箱即用，无需预装 JDK）

固件内回放 H.264 / HEVC 录像时，Linux 和 Windows 发行包会调用随包附带的 FFmpeg。可通过 JVM 属性 `fuji.ffmpeg.bin` 或环境变量 `FUJI_FFMPEG_BIN` 指定其他可执行文件。

- **必选内置精简运行时（`jlink` + `jpackage`）**：发行包通过 JDK 25 `jlink` 裁剪并强制内置最小运行时（Linux `buildJlinkRuntimeLinux` 约 `64M`、`packageReleaseLinuxAppImageTar` app-image 约 `89M`，已实测零系统 JDK 冷启直达 `STAGE_6_INTERACTIVE_UI`；Windows 发行包强制打包 `43K` 零依赖原生启动器 `fuji-emulator-gfx100s.exe` 与交叉 `buildJlinkRuntimeWindows` 精简运行时，支持 Windows 原生 `packageReleaseWindowsJpackage`），**无需本机预装 JDK 25**（如自行删除 `jdk/` 目录，则会回退至系统 `JAVA_HOME` 或 `PATH` 中的 JDK 25）。
- **Windows**：
  - 直接双击原生启动器 **`fuji-emulator-gfx100s.exe`**（`43K` 零外部依赖原生程序，或双击 **`start-gfx100s.bat`**）。
  - 两者均自动优先使用包内 `jdk\bin\java.exe`，并默认注入 `--enable-native-access=ALL-UNNAMED -Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=15 -Dprism.order=d3d,sw`。
- **Linux**：
  - 标准 `.tar.gz` 发行包：运行 **`./start-gfx100s.sh`**（自动使用包内 `jdk/bin/java`）。
  - `jpackage` 原生 app-image 包（`*-linux-x86_64-appimage.tar.gz`，约 `89M`）：直接运行 **`./bin/fuji-emulator-gfx100s`**（原生可执行启动器，自动定位当前目录配置并冷启动）或 **`./start-gfx100s.sh`**。
- **可选 JDK 25 AOT 加速缓存（JEP 483）**：如通过 `:buildAotCacheLinux` 生成 `runtime/fuji-emulator.aot`（约 `29M`），启动器会自动附加 `-XX:AOTCache=...` 加速冷启动。

启动后将自动打开六面机身界面与配置中心，并以 **STILL 静态拍照模式** 自动冷启动加载 2.13 固件至原生 `1024×768` LCD 显示（完整渲染 OOBE `0x0423` 地区选择世界地图、支持 **暂停/继续**、触摸屏点击、EVF 眼传感器切换、独立 **`镜头光学`** 页签/浮窗 `LensProfileWindow`、独立 **`音频 I/O`** 页签 `AudioIoPanel`、右侧栏 **`输入源 · INPUT SOURCE`** 与 **`音频快捷 · AUDIO I/O`** 面板、快门拍照、STILL 实时取景 **`MENU/OK`** 菜单与 **`DISP/BACK`** HUD 切换及 `PLAY` 回放，M8 全模式零 MMIO 回退；关闭窗口自动安全退出；同时在 `http://127.0.0.1:8787` 提供本地控制 API）。

- **全双工音频与系统环回（M10 必选原生音频子系统）**：
  - 发行包强制内置跨平台原生音频桥（Linux `libfuji_audio.so` / Windows 零依赖 `fuji_audio.dll`，由 `:verifyReleaseLinux` / `:verifyReleaseWindows` 门禁强校验，零可选降级；仅当系统声卡服务不可用时才在运行时回退 `JavaSound` 并记录显式 `WARN [FALLBACK][audio]`），在 **`Audio I/O` / `音频 I/O`** 页签可直接选择播放输出设备、麦克风或**捕获电脑系统音频输出（WASAPI / PipeWire / PulseAudio Loopback）**、调节目标缓冲延迟（`10..500 ms`）、音量/增益/静音/监听、测试音与 3.5mm 耳机（`GPIO 8.4`）/外接麦克风（`GPIO 6.4`）插孔状态，并查看实时 VU 电平表。
- **动态 Vulkan 1.1 计算加速、可选物理 GPU 与显式回退诊断（`WARN [FALLBACK][...]`）**：
  - 发行包强制内置 **`libfuji_vulkan_optics.so` / `fuji_vulkan_optics.dll`**（零静态 `libvulkan` 链接，运行时动态探测系统 `libvulkan.so.1` / `vulkan-1.dll`），内嵌 **6 枚 SPIR-V 1.0 计算着色器**（Pass 0 滤镜栈、光学畸变、五形态 2D 入瞳散景、CMOS Bayer 读出与 LiveView ISP 去马赛克/峰值对焦）。可在 **`镜头光学`（Lens & Optics）-> `图形 / Vulkan 计算`** 面板、控制台 **`/gpu`**（或 **`/lens gpu`**）以及 `profile.yaml`（`graphics.opticsBackend` / `graphics.vulkanDevice`）中热切换计算后端（`auto` / `vulkan` / `cpu`）与具体物理显卡。
  - **游戏零拷贝同 GPU 提示**：接入 Minecraft 等游戏使用 GPU 显存零拷贝（External Memory）传输时，**务必选择与游戏运行在同一张显卡（同 GPU）上的 Vulkan 设备**；双显卡笔记本若选错显卡，系统会自动降级为共享内存传输。在无可用硬件 GPU 或驱动的机器上自动回退至高速多核 CPU 光学管线，并在日志打印清晰的 **`WARN [FALLBACK][vulkan]`** 诊断条目（语言、主题、音频、GPU 全子系统均遵循显式 `[FALLBACK][...]` 告警策略，无静默降级）。
- **解耦前置滤镜栈（`optical-filter`）、真实光学星芒散景与多源取景**：
  - **独立前置滤镜栈（`configs/components/optical-filter.yaml` / `Optical Filters` 面板）**：完全独立于机身与镜头的光学滤镜系统，支持均匀/可变 **ND**（`OFF` / `ND2..ND1000` / `0..10 EV` 连续衰减，实测 `ND8` 产生精准 `-3.00 EV` 机内曝光尺偏移）、真实入瞳卷积 **星芒/造型散景光圈遮罩**（`bokehMask`: `STAR_5` / `HEART` / `HEXAGON` / `RING`）、星芒镜（`4/6/8pt`）、黑柔（`Black Mist 1/8..1`）与渐变灰（`GND 0..5 EV`）。
  - **12 枚可发现模块化镜头 / 转接环 JAR（`lenses/*.jar`，`Lens & Optics` 页签或独立浮窗）**：`gf45-100`、`gf20-35`、`gf32-64`、`gf63`、`gf110`、`gf50`（支持 `LENSCOM` 双向光圈环/变焦/OIS 同步与 `LMO/LEQ` 镜头调制优化器）及 `adapter-fringer-ef-gfx + canon-ef85-f14l`、`adapter-m42-gfx + takumar-50-f14`、`adapter-hasselblad-v-gfx + zeiss-planar-80-f28`，严格互斥三模式（`MANUAL`/`REAL_FIRMWARE`/`GENERIC_SIMULATED`），支持 2D 平面散焦 + 深度虚化、`imageCircleMm` 像场圆暗角、轴向色散、多叶片与造型入瞳散景、AF/MF 对焦与 `KEY_LENS_DET` 电子触点门控。
  - **右侧栏 `INPUT SOURCE` 多源切换**：默认启用 3D 解析多景深对焦场景 **`scene://qr-ball`**（`0.83..1.05 m` 校准球体 + `5.5 m` 可扫二维码墙），另内置 **`scene://star-bokeh`**（专用星芒散景实测场景：前景对焦呈现锐利彩色五角星光斑、背景对焦收敛为星点）、**`scene://mona-lisa`**（真 3D 景深人脸/人眼 AF 与竖拍检测）、`scene://depth-studio`、`scene://bokeh-city`、`scene://resolution-chart`、`JPEG`、`PNG`、`VIDEO_FILE`/`ffmpeg-pipe`、`RTMP_RTSP`、`WEBCAM`、`stream-ingest`（TCP `FSCM`+depth / HTTP / UDP）与 `screen-capture`（跨平台桌面/区域/游戏窗口）。
- **全局偏好（`profile.yaml`）、机身/UI 双解耦主题与英文默认多语言（i18n）**：
  - 运行目录下自动读写 **`<run-dir>/profile.yaml`**，保存界面语言、机身主题、UI 主题、缩放比例与 EVF 窗口状态（硬件配置与界面偏好完全分离）。
  - **默认英文（`en`）实时切换 `zh_CN`**：点击窗口右上角 `EN | 中文`、在控制台执行 **`/lang en`** / **`/lang zh_CN`**，或调用 **`GET/POST /api/lang`** 均可即时无重启切换并自动保存到 `profile.yaml`。
  - **机身外形主题（Body Theme）**：内置 **`origin`**（写实 GFX100S 六面机身，含 G-mount CMOS 与镁合金/荔枝纹混合 PNG+SVG 贴图）、**`hasselblad-x2d2`**（Hasselblad X2D II 100C，含 XCD-mount 与阳极钛金材质）、**`hasselblad-907x`**（Hasselblad 907X & CFV 100C，含镀铬/皮革材质）、**`compact-rangefinder`**；全部内置 PNG+SVG 资源均已直接打入 `lib/desktop-0.3.1.jar`（`2.53 MB`，`.tar.gz`/`.zip` 与 `jpackage` app-image 均自包含开箱即用），支持真实旋转顶板 **模式拨盘（`P/S/A/M/C1..C6`，`-36°/档`）**、**`S/C/M` 对焦拨杆指针**与 **`650 ms` 轻点快门 `S2` 脉冲**；同时支持在运行目录创建 **`./themes/body/<id>/`** 放入自定义 `body-theme.json` + PNG/SVG 面板。
  - **界面配色主题（UI Theme）**：内置 **`origin`**（经典曜石黑）、**`nordic-minimal`**、**`daylight-studio`**、**`vintage-brass`**、**`olive-field`**；支持在运行目录创建 **`./themes/ui/<id>.css`** 覆盖界面配色，可与任意机身主题自由混搭。

- **统一 64 MiB QSPI NOR Flash 持久化与宿主机烧录器（CLI + 控制台 + 桌面 UI + HTTP API）**：
  - `relaxed` 模式默认直接从发行包最外层单文件 **`flash/nor-flash.bin`**（64 MiB，Micron MT25QU512ABB JEDEC `0x20BB20`，全区支持 4 KiB/64 KiB `0xFF` 擦除与 256 B 页编程 NOR 1→0 法则）冷启动与落盘持久化——同时支持**机内设置/校准持久化**（`[0x02FE0000..0x04000000)`）、**机内原厂初始化**（`SET UP → USER SETTING → RESET → INITIALIZE`）以及**机内 `DISP/BACK` 真固件刷写（FWUP）**与**自定义固件烧录**，配合 Sub-MCU 纽扣电池 NVRAM（`runtime/compoments/power-peer/submcu-nvram.json`）在重启与 `AUTO POWER OFF` 休眠唤醒后完整保留相机状态。
  - **双启动路径（默认 Flash-Direct + 实验性 BootROM 显式开启）**：
    - **默认（推荐）— Flash-Direct**：直接通过 **Flash-Direct**（`PC=0x0` 从 `flash/nor-flash.bin[0x20000..0x3FFFF]` 加载 `boot.bin`）启动；公开版可使用 `--flash-dat GXUP0004.DAT`（或 UI **Flash → Flash Firmware (.DAT)...** / 控制台 `/flash write`）将固件写入 `flash/nor-flash.bin`。
    - **实验性完整真机 BootROM 冷启（需显式开启）**：放置最外层 `bootrom/boot-rom.bin`（32 KiB，完整发行版默认内置）并在 `configs/emulator.yaml` 中设置 `bootRomBoot: true`（或使用 `--boot-rom` 启动参数），即从 **`PC=0xFFF00000`** 执行 BootROM → Stage-1 IPL → Stage-2 XIP → 4 核 `SGI 0` 唤醒 → `flash/nor-flash.bin` 冷启动。已知问题：OOBE 中途关机后以该模式重启可能卡死。已开启但缺失 `bootrom/boot-rom.bin` 时，打印显式 **`WARN [FALLBACK][bootrom]`** 并回退至 Flash-Direct。
    - **隐私安全提示**：**切勿公开分享或上传个人实机导出的原始 NOR Flash 镜像**——`[0x03500000..0x03D40000)`（`bank-14`）包含唯一机身序列号、Wi-Fi/蓝牙 MAC 地址及出厂传感器校准数据。
  - **四入口宿主机烧录器**：
    - **桌面 UI 顶部栏 `Flash` / `烧录 Flash` 菜单**：`烧录固件 (.DAT)...`（默认保留 `[0x02FE0000..0x04000000)` 校准与设置并自动冷重启）、`导出 NOR Flash (.bin)...`、`导入 NOR Flash (.bin)...`、`恢复 2.13 出厂 NOR Flash`。
    - **交互式控制台 `/flash` 指令**：`/flash [status | write <file.DAT> [--no-preserve-cal] | export <out.bin> | import <in.bin> | restore-factory]`。
    - **命令行离线烧录参数**：`--inspect-flash`、`--flash-dat <file.DAT> [--no-preserve-cal]`、`--export-flash <out.bin>`、`--import-flash <in.bin>`、`--restore-factory-flash`。
    - **本地 HTTP API**：`GET /api/flash` 与 `POST /api/flash`（`action`: `write-dat` / `export` / `import` / `restore-factory`）。
- **默认双卡目录直通模式（`directory`）**：
  - 槽位 1 目录：**`runtime/compoments/sdcard-reader/sd-card-slot-1/`**（已预初始化富士 `FF-DSC01` 目录结构 `FFDB/FFXFER.DAT` + `DCIM/100_FUJI/DSCF0001.JPG` 示例照片，开机完成向导后按 **`PLAY`** 即可直接体验照片回放！）。
  - 槽位 2 目录：**`runtime/compoments/sdcard-reader/sd-card-slot-2/`**（已预初始化干净的 `FFDB/FFXFER.DAT` + `DCIM/100_FUJI/` 目录）。
  - 两个目录均支持**宿主机与虚拟相机双向实时同步**（直接向目录增删照片、或相机内拍照/删除/格式化均会实时生效），且 **`slot1Inserted` / `slot2Inserted` / `slot1ReadOnly` / `slot2ReadOnly`** 支持运行时热插拔与在线写保护切换（修改 `sdcard-reader.yaml` 时自动与 `soc-sd-host` 对齐，无需手工同步 `sd-card-v9.json`）。
- **常用按键提示**：
  - **`MENU/OK`**：打开主菜单（`MENU`）或确认选择；
  - **`DISP/BACK`**：返回上级菜单或退出回放/菜单回到实时取景；
  - **`PLAY`**：进入/退出照片回放模式（默认读取卡槽 1 中的 `DSCF0001.JPG` 或您新拍摄/拖入的照片）；
  - **快门 `S1`/`S2`**：半按对焦、轻点或全按触发 **`650 ms` 快门脉冲**与原生 102MP 硬件流式 JPEG 拍照（内置零依赖纯 C 编码器，含 `160×120` EXIF 缩略图）并写入 SD 卡。

## 交互式彩色命令行控制台与性能分析（Profiler）

启动后终端采用统一日志格式（`[HH:mm:ss INFO]: ...`），在交互终端自动启用 ANSI 分级着色与彩色 `fuji> ` 提示符（Windows 自动启用 ConHost VT100，而 `logs/latest.log` 与重定向管道始终保持零转义码纯文本；支持 `NO_COLOR`、`FORCE_COLOR` 与 `/color [auto|on|off|status]` 实时切换），并提供基于命令注册表的交互控制台（GUI 模式与 `--nogui` / `--headless` 无界面模式均支持，命令可省略或带 `/` 前缀，亦支持 `<组件名>:<指令>` 命名空间调用；如需关闭控制台可加 `--no-console`）：

- **帮助与终端控制**：
  - `help [页码]`：分页列出所有已注册的核心与硬件组件指令。
  - `help <指令>`：查看指令说明、用法（`Usage:`）、别名与所属组件。
  - `help <组件名>`：列出指定组件注册的全部指令（如 `help buttons`、`help i2c-bus`、`help fuji`）；输入错拼指令时自动给出相似指令建议。
  - `color [auto|on|off|status]`（别名 `ansi`、`colours`）：实时切换或查看控制台 ANSI 彩色输出模式。
  - `lang [status|list|en|zh_CN]`（别名 `language`、`locale`）：实时切换或查询界面语言（默认 `en`，支持 HTTP `GET/POST /api/lang`）。
  - `theme [status|list|body <id>|ui <id>]`：查看或实时切换机身主题与 UI 配色主题（支持 HTTP `GET/POST /api/theme`）。
  - `audio`（别名 `sound`）：查询/控制全双工音频输出、麦克风/系统环回输入、设备列表、音量/增益、静音、测试音与 3.5mm 插孔状态。
- **性能分析与系统状态**：
  - `tps`（别名 `status`）：查看实时 RTR、平均 MIPS、有效虚拟 MIPS、WFI 占比、启动阶段、帧计数与堆内存。
  - `profiler start [--interval 20]`：开启异步采样（`5..1000 ms`）；`profiler info` / `profiler report` 查看实时进度与 RTR/MIPS/MMIO/热栈报告；`profiler stop [--save path.json]` 停止采样、输出完整报告并可选导出 JSON；`profiler health`（或 `health` / `gc`）查看 JVM 堆内存、GC、线程与 CPU 负载。
  - `components`（别名 `plugins`）：列出已加载硬件组件及其注册的控制台指令数。
- **已注册的硬件组件指令**（按实际加载组件动态注册）：
  - `buttons` / `soc-gpio`：`button <按键> [tap|hold|release] [ms]`（别名 `press`）、`power [status|on|off]`、`mode [status|still|movie]`、`focus [status|af-s|af-c|mf]`、`dial [status|P|A|S|M|C1..C6]`
  - `i2c-bus`：`touch <x> <y> [ms]`、`evf [status|near|far]`（切换 EVF 眼传感器）
  - `display`：`screenshot [main|evf|sub] [path.png]`、`display [status|brightness <0.0-1.0>]`
  - `battery`：`battery [status|level <0-100>|mv <0-12000>|present <on|off>]`
  - `mount` / `lens` / `optical-filter`：`mount [status|attach|detach]`（在线装卸 GF 虚拟镜头）、`lens [status|templates|template <id>|focus <m|inf>|af|aperture <f>|focal <mm>|optics <on|off>]`、`filter [status|clear|nd ...|bokeh-mask ...|star ...|mist ...|gnd ...]` & `nd [off|nd2..nd1000|<stops>]`（实时控制前置光学滤镜栈，支持 HTTP `GET/POST /api/filters` & `/api/nd`）
  - `cmos` / `sdcard-reader` / `flash-image`：`cmos [status]`、`source [status|set <kind> <ref> [loop]]`（实时切换多源取景输入，含 `scene://star-bokeh` 与 `scene://mona-lisa`）、`sd [status]`、`flash [status|write <file.DAT> [--no-preserve-cal]|export <out.bin>|import <in.bin>|restore-factory]`
- **生命周期与无界面运行**：`boot`、`pause` / `resume`、`stop`（安全刷盘退出）；在启动命令末尾追加 `--nogui`（或 `nogui`）即可纯终端无界面运行模拟器。

## 目录结构

- `fuji-emulator-gfx100s.exe` / `bin/fuji-emulator-gfx100s`：原生一键启动器（Windows MinGW-w64 零依赖可执行程序 / Linux `jpackage` app-image 原生启动器）。
- `start-gfx100s.bat` / `start-gfx100s.sh`：一键启动脚本（自动优先使用内置 `jdk/` 或 `jpackage` 启动器，默认 `--home <ROOT> --config <ROOT>/configs/emulator.yaml --auto-boot`）。
- `profile.yaml`：运行期自动生成的全局界面偏好文件（`language`、`theme.body`、`theme.ui`、`windows.fitScale`、`windows.evfWindowOpen`、`windows.evfAlwaysOnTop`）。
- `themes/`（可选用户扩展目录）：放置自定义机身主题包 `./themes/body/<id>/` 与 UI 样式 `./themes/ui/<id>.css`。
- `jdk/`（或 `jpackage` app-image 中的 `lib/runtime/`）：通过 JDK 25 `jlink` 裁剪生成的自包含精简运行时（仅保留模拟器与 JavaFX 25 所需模块）。
- `logs/`：最外层日志目录（`latest.log` 与按会话归档的 `.log.gz` 均位于此处）。
- `configs/`：最外层配置目录（经 `YAML-CLEANUP-W9` 精简为摄影师友好的英文注释配置，内部 SoC 寄存/连线已收归引擎内建默认值）：
  - `configs/emulator.yaml`：主运行配置（默认 `executionMode: relaxed` + `instructionBudget: unlimited` + 音频/触控/EVF/STILL↔MOVIE/MENU/PLAY 全量能力）。
  - `configs/firmware/gfx100s-213-release.yaml`：与全部 SoC 硬件配置匹配的固件元数据。
  - `configs/components/`：精简后的用户级硬件组件 `.yaml` 配置（含 `lens.yaml`、`optical-filter.yaml`、`cmos.yaml`、`battery.yaml`、`sdcard-reader.yaml`、`buttons.yaml`、`display.yaml`、`i2c-bus.yaml`、`mount.yaml`、`tmd2772.yaml`、`mma8452q.yaml`、`wm8962.yaml` 等；内部 SoC 芯片配置保留引擎安全默认值）。
  - `configs/examples/`：**各运行模式与硬件外设完整配置样例**（均附中文注释，可直接复制覆盖对应配置文件）：
    - `emulator-relaxed-default.yaml` / `dynarmic-turbo.yaml` / `emulator-discovery-research.yaml` / `emulator-strict-gate.yaml`（默认游玩 / 强制 Dynarmic 极速 JIT / 探索 / 严格门控模式）
    - `sdcard-reader-directory-dual.yaml` / `sdcard-reader-qcow2-cow.yaml` / `sdcard-reader-raw-boot.yaml`（双卡目录直通 / QCOW2 稀疏写时复制 / RAW 原始镜像与 `banban-injector` 启动卡）
    - `cmos-static-image.yaml` / `cmos-ffmpeg-camera-video.yaml` / `cmos-stream-ingest.yaml` / `cmos-screen-capture.yaml` / `cmos-optical-scene.yaml`（静态图 / FFmpeg 摄像头与视频流 / TCP·HTTP·UDP 推流 / 跨平台桌面与游戏窗口采集 / 三平面 `0.8m/2.5m/12m` 深度光学影棚）
    - `buttons-movie-mode.yaml`（开机默认视频录像档 vs 拍照档）
- `flash/`：最外层用户可见的 64 MiB QSPI NOR Flash 目录：
  - `flash/nor-flash.bin`：64 MiB 统一可读写 QSPI NOR Flash 镜像（完整发行版首次启动时自动从 `runtime/templates/factory-flash/nor-flash-2.13.bin` 生成，压缩包内不重复打包两份 64 MiB 镜像；所有机内设置与 FWUP 刷机均直接持久化于此；公开脱敏构建 `-PfujiBundleStockBins=false` 时不含模板 `.bin`，用户可自行放置或通过宿主机工具烧录）。
  - `flash/PUT_HERE`：中英双语放置与烧录指引。
- `bootrom/`：最外层用户可见的 32 KiB SPX4 掩膜 BootROM 目录：
  - `bootrom/boot-rom.bin`：32 KiB SPX4 掩膜 BootROM（完整发行版默认内置；公开脱敏构建 `-PfujiBundleStockBins=false` 时省略）。
  - `bootrom/PUT_HERE`：中英双语 BootROM 放置与 SHA-256 校验指引。
- `runtime/`：内部二进制运行资源：
  - `runtime/native/`：对应平台 **必选高性能原生组件**（经 `:verifyReleaseLinux` / `:verifyReleaseWindows` 强门禁校验，非可选项）：**Dynarmic ARMv7-A AVX2 JIT 主引擎**（`libfuji_dynarmic.so` / `fuji_dynarmic.dll`）、**M10 全双工原生音频桥**（`libfuji_audio.so` / `fuji_audio.dll`）、**PERF-2 原生 SIMD 渲染管线桥**（`libfuji_render.so` / `fuji_render.dll`）与 **VULKAN-W1 动态 Vulkan 1.1 计算光学管线桥**（`libfuji_vulkan_optics.so` / `fuji_vulkan_optics.dll`）、**CPU ISP/滤镜 SIMD 桥**（`libfuji_isp_simd.so` / `fuji_isp_simd.dll`）、**多线程 LSRAW/JPEG 编解码桥**（`libfuji_codec.so` / `fuji_codec.dll`）与 **原生多手柄输入桥**（`libfuji_gamepad.so` / `fuji_gamepad.dll`）；发行版仅保留 Dynarmic 主引擎，不附带冗余 Unicorn 库。
  - `runtime/compoments/`：独立硬件组件 JAR 与二进制资产/虚拟介质（含 `cmos/test-qr.jpg`、`sdcard-reader/sd-card-slot-{1,2}/`）。
  - `runtime/templates/factory-flash/nor-flash-2.13.bin`：纯净的 64 MiB 2.13 出厂 NOR Flash 模板备份（可通过菜单 **Flash → Restore Stock 2.13 Factory Flash**、`/flash restore-factory` 或 `--restore-factory-flash` 一键恢复；`-PfujiBundleStockBins=false` 时省略）。
