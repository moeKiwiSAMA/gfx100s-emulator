# FUJIFILM GFX100S 模拟器 — 用户手册

[English](USER_MANUAL.md) | **简体中文** | [日本語](USER_MANUAL.ja.md)

← [返回 README](../../README.zh-CN.md) · [固件获取指南](../FIRMWARE.zh-CN.md) · [扩展与 Mod 开发](../extending/EXTENDING.zh-CN.md) · [HTTP 控制 API](../api/HTTP_API.zh-CN.md)

---

> [!IMPORTANT]
> **本手册适用范围**
> 本手册**仅介绍本模拟器自身的宿主功能、多面机身窗口、模块化镜头与光学滤镜、3D 场景输入、虚拟双 SD 卡、全双工音频、Vulkan GPU 加速及故障排查**。由于模拟器直接运行未经修改的 GFX100S 原厂固件，**不包含相机内部菜单、胶片模拟、曝光与对焦操作教学**——关于相机系统本身的使用方法，请参阅富士胶片官方《GFX100S 使用说明书》。

---

## 目录

1. [启动方式、命令行模式与六面工作台](#1-启动方式命令行模式与六面工作台)
2. [机身主题、UI 配色主题与多语言切换](#2-机身主题ui-配色主题与多语言切换)
3. [实体按键、双指令拨盘、两段式快门、机械舱盖与键位绑定](#3-实体按键双指令拨盘两段式快门机械舱盖与键位绑定)
4. [模块化镜头、转接环、物理对焦马达与三态光圈环](#4-模块化镜头转接环物理对焦马达与三态光圈环)
5. [通用遮光斗前置光学滤镜栈](#5-通用遮光斗前置光学滤镜栈)
6. [虚拟 3D 景深场景与外部 CMOS 输入源](#6-虚拟-3d-景深场景与外部-cmos-输入源)
7. [五轴防抖（IBIS）与机身姿态传感器](#7-五轴防抖ibis与机身姿态传感器)
8. [虚拟双槽 SD 卡（目录直通 / 稀疏镜像 / 原始镜像）](#8-虚拟双槽-sd-卡目录直通--稀疏镜像--原始镜像)
9. [NOR Flash 持久化、状态快照与诊断导出](#9-nor-flash-持久化状态快照与诊断导出)
10. [全双工宿主机音频 I/O](#10-全双工宿主机音频-io)
11. [性能调优与 Vulkan 1.1 GPU 计算设置](#11-性能调优与-vulkan-11-gpu-计算设置)
12. [常见问题排查与精确日志路径](#12-常见问题排查与精确日志路径)

---

## 1. 启动方式、命令行模式与六面工作台

### 启动模拟器

- **Windows（`x86_64` 便携发行包）**：
  - 直接双击 **`fuji-emulator-gfx100s.exe`**（零外部依赖原生启动器，自动调用包内自带的 `jdk\bin\java.exe`，并在双显卡笔记本上自动请求 NVIDIA/AMD 独立显卡），或双击运行 **`start-gfx100s.bat`**。
- **Linux（`x86_64` 便携发行包）**：
  - 在终端运行 **`./start-gfx100s.sh`**，或在 `app-image` 发行目录中运行 **`./bin/fuji-emulator-gfx100s`**。
- **源码工作区（`JDK 25`）**：
  - 执行 `sh jvm.sh launch`（或 `./gradlew :desktop:run --args="--home local/runtime --config local/runtime/config/emulator.yaml --auto-boot"`）。

常用启动参数（可直接追加在启动器或脚本末尾）：

| 参数 | 作用说明 |
|---|---|
| `--auto-boot` | 打开窗口后立即自动冷启动相机固件（便携发行版启动器已默认附加） |
| `--flash-dat <路径>` | 将官方 `.DAT` 固件包解包写入 `flash/nor-flash.bin` 后退出 |
| `--boot-rom` | 显式启用实验性 `bootrom/boot-rom.bin`（`PC = 0xFFF00000`）真机冷启动链路 |
| `--realtime-pace` | 将 `54 MHz` 虚拟时钟在空闲循环中节流至 1:1 真实墙钟速度，显著降低宿主机待机 CPU 占用 |
| `--nogui` / `--headless` | 纯终端无界面模式运行（保留交互控制台与本地 HTTP API） |
| `--no-console` | 关闭终端交互式 `fuji> ` 命令行输入线程 |
| `--debug` / `--log-level=<级别>` | 指定终端控制台日志级别（`TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR`、`OFF`） |

### 控制中心与六面实体相机工作台

启动桌面端后，会打开**控制中心主窗口**（`MAIN ENTRY`）以及可自由拖动的相机机身面板窗口：

- **顶部工具栏**：
  - **加载固件 / 暂停 / 继续（Load / Pause / Resume）**：随时冻结或恢复 4 核 ARM 固件执行，不丢失当前相机状态。
  - **烧录 Flash（Flash）**：图形化烧录 `.DAT` 固件、导出/导入完整 64 MiB `.bin` NOR 镜像，或一键恢复出厂模板。
  - **镜头配置（Lens Profile）**：弹出独立的**镜头光学配置浮窗**，方便在盯着取景画面的同时拧光圈、拉对焦或调光学参数。
  - **按键绑定（Controls）**：打开**键盘快捷键与游戏手柄绑定**设置窗口。
  - **机身 / 界面 / 重载 / 语言（Body / UI / Reload / Lang）**：实时热切换机身外形、界面配色、热重载外部自定义主题以及切换界面语言。
- **六面相机视图（`GFX100S Original` 默认主题）**：
  - **背面（Rear）**：`1024×768` 主触控液晶屏（`LCDC1`）、EVF 目镜与眼传感器状态、对焦摇杆、后指令拨盘及 `MENU/OK`、`DISP/BACK`、`PLAY`、`Q`、`AF-ON`、`AE-L`、删除等机身按键。
  - **顶面（Top）**：真实旋转 `P/S/A/M/C1..C6` 模式拨盘、`STILL / MOVIE` 拍照/录像切换环、电源拨杆与两段式快门、`Fn1`/曝光补偿键、`DRIVE` 键，以及实时刷新的**单色肩屏 Sub-LCD**（Sharp MIP 副屏）。
  - **正面（Front）**：G 卡口与中画幅 CMOS / 当前挂载镜头前组、前指令拨盘、正面 `Fn2` 按键。
  - **左侧面（Left Side）**：带可开合防尘防滴舱盖的 3.5mm 麦克风口、3.5mm 耳机监听口、USB-C 口与 Micro-HDMI 口。
  - **右侧面（Right Side）**：双 UHS SD 卡槽舱门与卡槽状态。
  - **底面（Bottom）**：NP-W235 电池仓锁扣与舱盖。
  - 可在 `Controls` 页签按需勾选需要显示的机身面，点击 **排列窗口（Arrange Windows）** 即可根据当前屏幕分辨率自动整齐排布。

### 独立悬浮电子取景器（`EVF Window`）与机顶肩屏

- **打开独立 EVF 浮窗**：直接双击机身背面的 EVF 目镜区域、点击面板中的 **EVF Window (F8)** 按钮，或按下键盘 **`F8`**。
- **无边框置顶放大监看**：以独立窗口呈现 `1280×960` 电子取景器画面，点击窗口上的 **置顶（Pin）** 即可始终浮在其他桌面软件或游戏窗口上方。
- **眼传感器（`F6`）与 `VIEW MODE`（`F7`）**：
  - 按 **`F6`**（或点击 EVF 窗口上的眼传感器按钮）可切换虚拟 `TMD2772` 红外眼传感器（`I2C1 @ 0x39`）的 **贴近目镜（Near）** 与 **离开目镜（Far）** 状态。
  - 按 **`F7`** 模拟按下机身侧面的 **`VIEW MODE`** 物理按键（`GPIO 7.6`），切换取景器/后屏显示模式。
- **机顶肩屏（Shoulder Sub-LCD）**：位于机身 **Top** 顶面右侧，由原厂固件实时驱动显示快门、光圈、ISO、曝光标尺、胶片模拟图标、直方图及剩余张数。

---

## 2. 机身主题、UI 配色主题与多语言切换

硬件模拟与外观皮肤完全解耦，所有界面偏好自动持久化至 **`<模拟器目录>/profile.yaml`**。

### 内置 4 套机身外形主题（`Body`）

点击顶部栏 **`Body`** 下拉框（或控制台执行 `/theme body <id>`）即可**零黑屏无缝热切换**——切换机身窗口布局时，实时取景画面保持连续不中断：

| 主题 ID | 名称 | 面板结构与设计风格 |
|---|---|---|
| `origin` | **GFX100S Original (6-Face)** | 原版 GFX100S 六面写实镁合金与荔枝纹蒙皮机身，含机顶肩屏、可开合接口盖与 G 卡口 |
| `hasselblad-x2d2` | **Hasselblad X2D II 100C** | 北欧深色阳极氧化铝 3 面板中画幅机身，雕塑感手柄、橙色快门与 XCD 卡口视觉 |
| `compact-rangefinder` | **Compact Rangefinder** | 精简双面板街头旁轴机身，左上角取景窗与滚花机械拨盘 |
| `hasselblad-907x` | **Hasselblad 907X & CFV 100C** | 经典缎面镀铬边框 + 黑色皮革纹模块化中画幅数码后背 3 面板布局 |

### 内置 5 套 UI 界面配色主题（`UI`）

可与任意机身主题自由混搭（顶部栏 **`UI`** 下拉框或 `/theme ui <id>`）：
- `origin`（*Original Dark Studio* — 经典 `#161515` 暗房曜石黑中性配色）
- `nordic-minimal`（*Nordic Minimal* — 深石板灰搭配 `#E85D1A` 金属橙高亮）
- `daylight-studio`（*Daylight Studio* — 清爽明亮的 `#F3F4F6` 白昼工作台配色）
- `vintage-brass`（*Vintage Brass* — 暖炭黑皮革搭配 `#D4A259` 复古拉丝黄铜点缀）
- `olive-field`（*Olive Field* — 哑光军绿户外仪器风搭配 `#C9B37E` 暖沙金读数）

### 自定义主题热重载与 6 语言界面

- **外部自定义主题热重载**：在运行目录下创建 `<模拟器目录>/themes/body/<id>/` 或 `<模拟器目录>/themes/ui/<id>.css` 放入自定义主题包后，无需重启模拟器，直接点击顶部栏的 **`Reload`** 按钮（或调用 `POST /api/theme/reload`）即可即时生效。主题制作规范详见 [`EXTENDING.zh-CN.md`](../extending/EXTENDING.zh-CN.md)。
- **6 语言零重启切换（`Lang`）**：右上角 `Lang` 菜单（或控制台 `/lang <代码>`）支持 **English（`en`）**、**简体中文（`zh_CN`）**、**日本語（`ja_JP`）**、**Deutsch（`de_DE`）**、**Français（`fr_FR`）** 与 **Español（`es_ES`）** 实时切换。*（注：此选项控制模拟器宿主桌面界面语言，与相机固件内部的系统菜单语言互不干扰。）*

---

## 3. 实体按键、双指令拨盘、两段式快门、机械舱盖与键位绑定

### 鼠标交互方式

- **背面触控屏（Rear LCD）**：在后屏画面上直接左键点击即可触发触控对焦、触控快门或点选 `Q` 菜单；在回放模式下按住拖动即可左右滑动翻看照片。
- **模式拨盘（`P / S / A / M / C1..C6`）**：鼠标悬停在机顶模式拨盘上滚动滚轮、按住拖动旋转（每档 `-36°`）、右键直接跳选档位，或在 `Controls` 页签点击切换。
- **`S / C / M` 对焦模式拨杆与 `STILL / MOVIE` 切换拨杆**：点击、拖动或在拨杆上滚动滚轮即可切换单次对焦（`AF-S`）、连续对焦（`AF-C`）、手动对焦（`MF`）以及静态拍照 / 视频录制模式。
- **前后双指令拨盘（Front & Rear Command Dials）**：在机身前后拨盘（或控制面板拨盘条）上**滚动鼠标滚轮**或**水平拖动**即可逐格旋转；**左键单击拨盘中心**模拟按下拨盘中键（`frontDialPress` / `rearDialPress`，用于对焦放大、光圈/感光度功能切换等）。
- **两段式快门（`S1` 半按 / `S2` 全按）**：
  - 用鼠标轻点机身快门键会自动触发经标定的 **`650 ms` 快门脉冲**，确保轻点一下即可顺畅完成对焦合焦与 102MP 照片拍摄。
  - 如需独立控制半按测光/锁焦（`S1`）与全按释放（`S2`），可使用按键矩阵中的 `S1`/`S2` 键、自定义键盘快捷键，或使用游戏手柄的两段式线性扳机。
- **按键锁定与组合键（Combo-Lock）**：
  - 需要同时按住多个按键（例如按住 `DISP/BACK` 开机进入固件升级界面，或组合键操作）时，在任意瞬时按键上 **右键点击**、**`Shift + 左键`**、点击按键旁的 **`🔓`** 图标，或开启全局 **组合键锁定模式（Combo-Lock Mode）**，即可将该键保持按下状态。点击顶栏 **释放全部按键（Release All Keys）** 或面板上的 **Unlock All** 可立即松开所有锁定按键。
- **可开合机械接口盖与电池/卡槽舱门**：
  - 左键点击机身左侧接口盖、右侧 SD 卡舱门及底部电池仓盖，可交互展开或合上橡胶盖与仓门。

### 键盘快捷键与多手柄输入绑定（顶部 `Controls` 按钮）

点击顶部工具栏的 **`Controls`** 打开 **键盘与手柄输入绑定** 窗口（配置自动保存在 `<模拟器目录>/input-bindings.yaml`）：

- **内置取景器功能热键**：
  - `F6`：切换 EVF 眼传感器贴近 / 离开
  - `F7`：按下机身 `VIEW MODE` 键
  - `F8`：显示 / 隐藏独立悬浮 EVF 窗口
- **自定义键盘快捷键（默认留空）**：
  - 为防止在终端输入指令或编辑参数时误触快门，默认不预置普通字母键盘映射。
  - 点击表格 **Keyboard** 列的任意单元格，按下任意按键或组合键（支持 `Ctrl`、`Alt`、`Shift`、`Meta` 精确匹配）即可绑定全部 35 项相机动作（半按快门 `S1`、全按快门 `S2`、`AF-ON`、`AE-L`、远近对焦、变焦、前后拨盘、模式拨盘、`MENU/OK`、`DISP/BACK`、`PLAY`、`Q`、方向键、`Fn1/Fn2/Fn4` 等）。按住快捷键时由相机固件原生响应长按连发。
- **原生多手柄即插即用（XInput / evdev）**：
  - 内置原生手柄桥（Windows `XInput` / Linux `evdev`，最多同时识别 4 支手柄，可调节摇杆死区 `0.10..0.50`）。
  - **人体工学默认手柄映射**：
    - **右扳机（`RT`）两段式快门**：轻扣过 `25%`（`RT ½`）触发 **`S1` 半按对焦/测光**；深按过 **`65%`**（`RT`，可在界面中以 `40%–95%` 自由调节并实时查看 `LT/RT` 读数与峰值，完美适配掌机霍尔短行程扳机或扳机锁）触发 **`S2` 全按拍照**！取消勾选「两段式右扳机」则轻按过 `25%` 直接单段触发对焦并拍照。
    - **`RB` / `LB` / `LT ½`**：`AF-ON` / `Q 菜单` / `DRIVE 驱动模式`
    - **右摇杆（`RS`）**：左右拨动 = **后指令拨盘**；上下拨动 = **前指令拨盘**；按下 `R3` = **后拨盘中键**
    - **十字键与左摇杆（`LS`）**：四向导航 + 按下 `L3` = **对焦摇杆按下**
    - **`B` / `A` / `X` / `Y`**：`MENU/OK` / `DISP/BACK` / `PLAY 回放` / `Delete 删除`
    - **`Back` / `Start`**：`VIEW MODE` / `Fn1`

---

## 4. 模块化镜头、转接环、物理对焦马达与三态光圈环

在 **`Lens & Optics`（镜头光学）** 页签或独立 **`Lens Profile`** 浮窗（以及控制台 `/lens`、`/mount` 指令）中，可随时热插拔和调校挂载于 43.8 × 32.9 mm 中画幅传感器前的光学镜头组。

### 内置 12 枚原生 GF 镜头与经典转接镜头模块（`lenses/*.jar`）

| 模板 ID | 镜头与转接配置 | 光学性格与硬件特性 |
|---|---|---|
| `gf20-35` | **FUJINON GF20-35mmF4 R WR**（原生 GF） | 超广角恒定变焦，极低眩光，`0.35 m` 近摄，自然边角失光 |
| `gf32-64` | **FUJINON GF32-64mmF4 R LM WR**（原生 GF） | 标准变焦主力，线性马达 AF，轻微非球面洋葱圈散景 |
| `gf45-100` | **FUJINON GF45-100mmF4 R LM OIS WR**（原生 GF） | 5 档光学防抖 OIS + 线性马达，9 片圆形光圈叶片 |
| `gf50` | **FUJINON GF50mmF3.5 R LM WR**（原生 GF） | 轻巧饼干定焦，`0.22 s` 极速线性对焦行程，近乎零色散 |
| `gf63` | **FUJINON GF63mmF2.8 R WR**（原生 GF） | 整组对焦 DC 马达，轻微呼吸效应，全开 `f/2.8` 柔和轴向色散 |
| `gf110` | **FUJINON GF110mmF2 R LM WR**（原生 GF） | 中画幅人像旗舰：`f/2` 极浅景深、奶油虚化与经典边缘猫眼口径蚀 |
| `adapter-fringer-ef-gfx` + `canon-ef85-f14l` | **Fringer EF-GFX Pro 电子环** + **Canon EF 85mm f/1.4L IS USM** | 跨卡口电子触点光圈/AF/IS 协议转译，`f/1.4` 轴向色差与 `43.3 mm` 全画幅像场边缘暗角 |
| `adapter-m42-gfx` + `takumar-50-f14` | **M42 → GFX 纯机械环** + **SMC Takumar 50mm f/1.4** | 无电子触点复古手动标头：全开球差柔光、暖琥珀逆光眩光与泡泡焦外亮边 |
| `adapter-hasselblad-v-gfx` + `zeiss-planar-80-f28` | **Hasselblad V → GFX 机械环** + **Zeiss Planar 80mm f/2.8** | 6×6 中画幅 `79 mm` 超大像场覆盖，经典 5 叶片五边形散景 |

- 将第三方镜头/转接环插件 `.jar` 放入 `<模拟器目录>/lenses/` 后，直接点击面板上的 **Rescan Plugins（重新扫描插件）** 即可在线加载，无需重启。
- 切换 **Mount Attached（卡口挂载）** 与 **Electronic Contacts（电子触点）** 可模拟在线拆装镜头或无触点纯机械镜头拍摄（配合机内“无镜头拍摄”选项）。

### 三态光圈环双向同步（`A Auto` / `C Command` / `Manual F`）

带电子触点的镜头通过 `SPI0 LENSCOM` 总线与机身固件实时双向通信：

- **`A Auto`（A 档自动）**：模拟将镜头光圈环拧到 `A` 档，在 `P` / `S` 曝光模式下由机身自动曝光系统实时控制光圈收缩。
- **`C Command`（C 档机身拨轮）**：模拟将镜头光圈环拧到 `C` 档，直接用机身的**前/后指令拨盘**调节光圈值。
- **`Manual F`（手动光圈环）**：模拟直接转动镜头镜筒上的实体刻度光圈环——拖动面板上的 **Aperture** 滑块，相机的后屏、EVF、机顶肩屏及 AE 测光表会立刻同步跳变至对应 F 值！

### 物理对焦马达与专家光学微调

- **真实对焦马达行程**：勾选 **Realistic Focus Motor Travel** 可还原 DC 马达与线性马达（`LM`）在自动对焦扫焦时的真实物理速度曲线；实时光学遥测区会动态计算等效 135 焦距、水平视场角、超焦距、弥散圆（CoC `µm`/`px`）以及景深远近界限。
- **高级光学调校（`Advanced Optics`）**：展开专家折叠栏，可逐项调整径向畸变（`k1`/`k2`）、横向红/蓝色差、轴向色差（LoCA `mm`）、全开球差柔化、`cos⁴` 与机械渐晕、对焦呼吸效应、T 值透光率、`imageCircleMm` 像场圆直径、逆光眩光染色、光圈叶片数（`5..11`）、叶片圆度、边角猫眼、非球面洋葱圈与泡泡亮边散景。

---

## 5. 通用遮光斗前置光学滤镜栈

在右侧快捷 **MATTE BOX · FILTERS** 卡片或 `Lens & Optics` 页签内的完整 **MATTE BOX · OPTICAL FILTER RACK**（及控制台 `/filter`、`/nd`）中操控：

- **无口径限制通用设计**：本模拟器**明确不强制镜头前组滤镜螺纹口径匹配（如 `ø62mm` / `ø77mm` / `ø82mm`）**，任何滤镜均可直接自由挂载在任何镜头前，省去繁琐的转接环折腾。
- **最高 12 槽位自由叠片 + 一键摇开旁路（Swing-Away）**：支持从镜头最前端（`#1`）到遮光斗后端（`#N`）自由叠加最多 12 片滤镜（如 `ND8 + ND64 = 9 EV`），支持单槽独立开关、上下调序、一键预设配方（Quick Recipes），以及零算力损耗的总开关 **IN PATH / SWING AWAY** 摇开对比。
- **内置物理光学滤镜类型**：
  - **中灰减光镜（`ND Filter`）**：`0.0..10.0 EV` 连续或档位减光（`ND2..ND1000`），真实衰减入射至 CMOS 的光子能量，使机内实时曝光尺与自动快门/ISO 产生精确物理偏移（如 `ND8` 对应精准 `-3.00 EV`）。
  - **渐变中灰镜（`Graduated ND`）**：可调天空压暗档数（`0..5 EV`）、地平线位置、羽化过渡宽度与旋转倾角。
  - **黑柔滤镜（`Black Mist`）**：电影感高光晕染与暗部柔化（`1/8`、`1/4`、`1/2`、`1` 档位与扩散强度调节）。
  - **星芒镜（`Star Cross-Screen`）**：`4` / `6` / `8` 线衍射星芒，可调锋芒强度与旋转角度。
  - **物理 UV 镜对比（`UV Filter`）**：直观对比顶级多层镀膜 **Pro MRC**（透过率 `99.6%`）、普通单层镀膜 **Standard**（`96.5%`）与无镀膜廉价玻璃 **Cheap Glass**（`89.5%`，伴随强烈杂散光雾翳、中心对称鬼影、MTF 锐度劣化与偏绿染色）。
  - **异形散景入瞳遮罩（`Bokeh Mask`）**：在镜头入瞳处插入 **`★5 五角星`**、**`♥ 爱心`**、**`⬡ 六边形`**、**`◎ 折返圆环`** 或 **`✦ 自定义`** 光瞳图样，将失焦高光点真实卷积成锐利的几何造型光斑（推荐搭配 `gf110` 全开 `f/2` + `scene://star-bokeh` 场景体验！）。
  - 点击 **Rescan Plugins** 可免重启热加载 `filters/*.jar` 外部滤镜插件与自定义 Vulkan `.spv` 着色器。

---

## 6. 虚拟 3D 景深场景与外部 CMOS 输入源

通过右侧 **`INPUT SCENE`（输入源）** 面板（或控制台 `/source set <类型> <地址>`）实时切换虚拟传感器捕获的画面：

### 内置真 3D 深度光学场景（带 `3D` 标识）

3D 场景同时输出逐像素真实物理深度缓冲（米）与高动态范围线性光信号，无需手动设对焦平面距离，即可直接驱动远近景深虚化、相位差 PDAF 对焦与人脸/人眼追踪框：

- **`scene://qr-ball`（*QR Sphere 3D* — 默认场景）**：前景 `0.83..1.05 m` 旋转校准球体 + 背景 `5.5 m` 高清二维码墙，适合测试远近切换 AF 与中画幅浅景深。
- **`scene://mona-lisa`（*Mona Lisa Eye-AF 3D*）**：60 fps 真 3D 纵深人像画廊场景，专为测试富士人脸识别与左/右眼部追焦框（Eye-AF）设计。
- **`scene://milk-frog`（*Milk Frog 3D + Audio*）**：带动画、眼部追焦几何体、影棚景深道具与**同步空间笑声音频**的 3D 角色场景（可在音频面板选择 `3D Scene Spatial Audio` 直接内录声音）。
- **`scene://depth-studio`（*3D Optical Bokeh Studio*）**：三平面（`0.8 m / 2.5 m / 12 m`）综合光学测试棚，集成色卡、分辨率标板与远景夜景光源。
- **`scene://star-bokeh`（*Point-Light Bokeh*）与 `scene://bokeh-city`（*Night Bokeh City*）**：高反差夜景点光源场景——使用大光圈镜头对焦在 `1.35 m` 前景标靶上，背景点光源即化作饱满的星形/心形散景光斑或星芒。

### 2D 平面与外部实时视频源（带 `2D` 标识）

使用 2D 输入源时，可配合 `Lens & Optics` 中的 **2D 平面被摄体距离（2D Planar Subject Distance）** 滑块控制对焦合焦与虚化程度：

- **静态图片（`JPEG` / `PNG`）**：浏览加载电脑上的任意照片或测试标板（如自带的 `test-qr.jpg`）。
- **本地视频文件（`FFmpeg`）**：导入 `.mp4`、`.mkv`、`.mov` 视频作为取景画面（支持勾选循环播放）。
- **桌面 / 游戏窗口采集（`Screen / Game Capture`）**：输入 `screen://primary` 采集主显示器，或设置 `screen://region/x,y,w,h?fps=24` 截取指定游戏窗口进入中画幅光学与胶片模拟管线。
- **电脑摄像头（`Webcam`）**：输入 `webcam:default` 调用宿主机物理摄像头实时取景拍照。
- **网络推流（`RTSP / TCP / HTTP`）**：接入 `rtsp://` 视频流，或通过 `tcp://127.0.0.1:9555` 推送外部渲染器画面（支持附带深度通道）。

---

## 7. 五轴防抖（IBIS）与机身姿态传感器

位于工作台 **`GYRO & 5-AXIS IBIS`** 面板：

- **机身与翻折屏加速度计（`MMA8452Q`）**：完整模拟 `I2C1 @ 0x1C` 机身姿态传感器与 `I2C2 @ 0x1D` 翻折屏重力传感器。
- **一键横竖拍预设**：点击 **横拍 `0°`**、**顺时针竖拍 `+90°`**、**逆时针竖拍 `-90°`** 或 **倒置 `180°`**，取景器 HUD 图标会自动旋转为竖拍布局，拍出的照片也会自动写入正确的竖拍 EXIF 方向标记。
- **滚转（Roll）与俯仰（Pitch）电子水准仪**：连续拖动角度滑块可实时测试相机屏幕内的二维/三维电子水平仪。
- **手持微抖动与五轴 IBIS 遥测**：调高 **Handheld Micro-Shake（手持微抖动）** 可注入模拟手震，实时观察 `biscom`（`SPI2`）五轴机身防抖机构反向补偿传感器的微米级位移遥测（`Shift (X, Y) µm`）。

---

## 8. 虚拟双槽 SD 卡（目录直通 / 稀疏镜像 / 原始镜像）

双 SD 卡槽配置文件位于 `<模拟器目录>/configs/components/sdcard-reader/sdcard-reader.yaml`（`configs/examples/` 目录下附带三种模式的配置模板）：

| 后端模式 | 工作机制 | 推荐用途 |
|---|---|---|
| **`directory`** *（默认推荐）* | 将 `<模拟器目录>/runtime/compoments/sdcard-reader/sd-card-slot-1/` 与 `sd-card-slot-2/` 两个本地文件夹实时双向映射为 4 GiB FAT32 存储卡 | 日常拍照与录像！机内拍下的 102MP `.JPG`、`LSRAW` `.RAF` 和 `.MOV` 视频会直接出现在电脑文件夹里；往 `DCIM/100_FUJI/` 拖入照片也能在相机 `PLAY` 模式直接回放 |
| **`qcow2`** | 稀疏写时复制 4 GiB 虚拟磁盘（`sd-card-slot-1.qcow2`，初始仅占数 KiB，支持指定只读母盘 `slot1BackingFile`） | 适合隔离测试或频繁机内格式化实验，不污染原始母盘 |
| **`raw`** | 直接挂载 `runtime/compoments/sdcard-reader/` 下的原始扇区级 `.img` 镜像 | 底层文件系统分析、真实 SD 卡完整备份挂载或引导研究 |

- **在线热插拔与写保护锁切换**：无需重启模拟器，在 **`Components`** 页签展开 **`sdcard-reader`**（或调用 `PATCH /api/v1/components/sdcard-reader`），即可实时切换 **`slot1Inserted`**、**`slot2Inserted`**（拔插卡触发真实 `GPIO 10.4/10.8` 与 SDHCI 中断）及 **`slot1ReadOnly`**、**`slot2ReadOnly`**（物理写保护锁扣）。

---

## 9. NOR Flash 持久化、状态快照与诊断导出

- **日常设置自动持久化（无需手动存盘）**：
  - 相机的语言/时区首次引导状态、曝光参数、胶片模拟配方、`C1..C6` 自定义设置以及镜头缓存会自动写入 `<模拟器目录>/flash/nor-flash.bin`（64 MiB）与 Sub-MCU 纽扣电池 NVRAM（`runtime/compoments/power-peer/submcu-nvram.json`），关闭重开或 `AUTO POWER OFF` 休眠唤醒后均完整保留。
  - 可随时通过顶部菜单 **Flash → 导出 / 导入 NOR Flash (.bin)...**（或 `--export-flash` / `--import-flash`）备份和切换整套机身配置。
- **全机状态快照（`EmulatorSnapshot` v1）**：
  - 如需从某个精确时刻瞬时恢复（包含 4 核 ARM 寄存器、堆外客机 DRAM、中断控制器与全部外设状态），可在启动时传入 `fuji-emulator-snapshot-v1` 快照目录：
    ```bash
    ./start-gfx100s.sh --resume-snapshot /path/to/snapshot-dir
    ```
    *（建议在副本目录上进行分支实验，避免原地覆写共享快照。）*
- **安全点诊断包导出**：
  - 点击顶部栏 **Save Diagnostics**（或请求 `POST http://127.0.0.1:8787/api/v1/dump`），可在 `<模拟器目录>/dumps/` 下导出一份有界安全点状态快照以供排障分析。

---

## 10. 全双工宿主机音频 I/O

打开 **`Audio I/O`（音频 I/O）** 页签（或右侧快捷音频栏 / 控制台 `/audio` 指令），即可操控由 `fuji_audio` 原生桥驱动的低延迟 48 kHz 双向音频链路（Windows `WASAPI` / Linux `PipeWire` · `PulseAudio` · `ALSA`）：

- **音频输出（相机 → 电脑）**：
  - 实时播放相机快门声、对焦合焦提示音、按键音、自拍倒计时音以及机内 `.MOV` 视频回放伴音。
  - 支持在线热切换播放设备、调节主音量/静音，并可拨动虚拟 **3.5mm 耳机插孔开关（`GPIO 8.4`）** 测试耳机监听逻辑。
- **音频输入与系统内录环回（电脑 → 相机）**：
  - 将实时立体声输入送入相机的 `.MOV` 录像音轨与屏幕 L/R 麦克风电平表！
  - 提供 5 种输入源模式：
    1. **关闭输入（Input Off）**
    2. **宿主机麦克风（Host Microphone）**
    3. **捕获电脑系统输出（Loopback 系统内录）** —— 通过 `WASAPI Loopback` 或 `PipeWire`/`PulseAudio` 直接把电脑正在播放的音乐或游戏声音录进相机视频里！
    4. **3D 场景空间音频（3D Scene Spatial Audio）** —— 零外设直接内录 `scene://milk-frog` 等 3D 场景的同步声源
    5. **内置 440 Hz 标准测试音**
  - 支持调节输入增益、输入静音、耳返直通监听、目标缓冲延迟（`10..500 ms`）、**3.5mm 外接麦克风插孔开关（`GPIO 6.4`）**，并提供实时立体声 VU 电平表。

---

## 11. 性能调优与 Vulkan 1.1 GPU 计算设置

位于 **`Lens & Optics`** 页签底部的 **`GRAPHICS / VULKAN COMPUTE`** 区域（自动保存至 `profile.yaml`，亦支持控制台 `/gpu`）：

- **光学计算后端热切换（`Auto` / `Vulkan GPU` / `CPU SIMD`）**：
  - **`Auto (Vulkan -> CPU)`** *（默认推荐）*：运行时动态加载系统 `vulkan-1.dll` / `libvulkan.so.1`，在 GPU 上并行执行 3D 场景光栅化、前置滤镜栈、2×2 能量守恒散景金字塔卷积、CMOS Bayer 读出与 LiveView ISP 去马赛克/峰值对焦；若本机无可用 Vulkan 1.1+ 驱动，则自动无损回退至多线程 `AVX2`/`AVX-512` 原生 CPU 光学管线。
- **指定物理显卡与双显卡笔记本游戏零拷贝提示**：
  - 多显卡台式机或核显+独显笔记本可在 **Vulkan GPU** 下拉框中手动指定物理显卡（带 `Discrete` / `Integrated` 与 `ExtMem` 标记）。
  - **同卡零拷贝提示**：通过 GPU 外部显存（`ExtMem`）接入外部游戏画面时，**请务必选择与该游戏运行在同一张物理显卡上的 Vulkan 设备**（跨显卡时会自动安全降级为共享内存传输）。
- **性能监控与实时时钟节流**：
  - 在 `fuji> ` 终端输入 `tps` 查看实时倍率（RTR）、有效 ARM MIPS 与 WFI 空闲占比；输入 `profiler start` / `profiler stop` 生成热点采样报告。
  - 笔记本插电/电池游玩时若希望降低风扇噪音，可在启动时附加 **`--realtime-pace`**（或在 `configs/emulator.yaml` 中设置 `realTimePacer: true`）。

---

## 12. 常见问题排查与精确日志路径

### 常见问题速查

| 现象 | 原因与解决方法 |
|---|---|
| **启动弹出固件缺失引导框** | 尚未初始化 `flash/nor-flash.bin`。请按 [`FIRMWARE.zh-CN.md`](../FIRMWARE.zh-CN.md) 选择官方 GFX100S v2.13 `GXUP0004.DAT`（SHA-256：`ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12`）。 |
| **Vulkan 状态显示 `Active: CPU Fallback`** | 系统未检测到可用 Vulkan 1.1+ 驱动（`vulkan-1.dll` / `libvulkan.so.1`）。请更新 NVIDIA / AMD / Intel 显卡驱动（Linux 可安装 `mesa-vulkan-drivers`）。 |
| **机身按键点击无反应** | 检查是否有瞬时按键处于**组合键锁定（`🔒 Locked`）**状态——点击顶栏 **Release All Keys** 一键松开；同时确认机身电源拨杆处于 `ON` 且未处于 **Pause（暂停）** 状态。 |
| **视频回放无声音** | 检查 **Audio I/O** 页签是否已开启输出且未静音，并确认发行包内 `runtime/media/ffmpeg`（Windows 为 `ffmpeg.exe`）未被安全软件误删。 |
| **重置损坏的机内设置** | 可在相机菜单内执行 `RESET → INITIALIZE`，或在宿主机用 `--flash-dat GXUP0004.DAT --no-preserve-calibration` 重新刷入干净基线并删除 `runtime/compoments/power-peer/submcu-nvram.json`。 |

### 精确日志文件路径（提交 Issue 时请务必附上！）

模拟器采用零 ANSI 转义码、纯 UTF-8 编码的双通道异步日志系统（终端默认 `INFO` 保持清爽，文件端完整记录 **`DEBUG`** 级事件及所有 `WARN [FALLBACK][vulkan|audio|bootrom|OS-SCHED]` 回退诊断）：

- **Windows（`x86_64` 便携发行包）**：
  ```text
  <模拟器解压目录>\logs\latest.log
  ```
  *（位于 `fuji-emulator-gfx100s.exe` 同级目录的 `logs` 文件夹内；历史会话自动压缩归档为 `<模拟器解压目录>\logs\yyyy-MM-dd-N.log.gz`。）*
- **Linux（`x86_64` 便携发行包）**：
  ```text
  <模拟器解压目录>/logs/latest.log
  ```
  *（位于 `start-gfx100s.sh` / `bin/fuji-emulator-gfx100s` 同级目录的 `logs/` 下；历史会话自动归档为 `<模拟器解压目录>/logs/yyyy-MM-dd-N.log.gz`。）*
- **源码构建运行（`sh jvm.sh launch` / `./gradlew :desktop:run`）**：
  ```text
  local/runtime/logs/latest.log    （或您指定的 --home 目录下的 logs/latest.log）
  ```
  *（可通过 JVM 参数 `-Dfuji.log.dir=/自定义路径` 更改日志目录，或在 `fuji> ` 控制台通过 `/log status`、`/log level DEBUG` 实时调整级别。）*
