# GFX100S 六面窗口与配置中心规范

本文件是唯一现行 UI 规范。历史外观推导保留在 历史 UI 研究记录 与下表不可变来源记录中；固件实际渲染与菜单进度以 `README.md` 为准。

## 1. 窗口布局与交互契约

配置中心是最右侧纵长主窗口，六个独立实体面窗口默认按三列同时展示：

| 左侧窄列 | 中间宽列 | 右侧 |
| --- | --- | --- |
| 机身左侧／接口 | 机身顶部（含 `display.shoulder`） | 配置主窗口（含独立 `镜头光学` 与 `音频 I/O` 页签，以及右侧栏 `输入源 · INPUT SOURCE` 与 `音频快捷 · AUDIO I/O` 面板） |
| 镜头正面（顺时针旋转 90°） | 主屏背面（含 `display.main` 与 `rear-evf` `display.evf`） | 贯穿主要高度；支持拆出独立浮窗 `LensProfileWindow` 与 `EvfViewfinderWindow` |
| 机身右侧／SD 仓 | 底板／电池仓 | 窗口开关、光学/镜头预设、全双工音频 I/O、多源 CMOS 切换与眼传感器控制 |

- **比例与变换**：同列统一宽度，主屏保留主要面积；各面按独立 crop/slot 等比缩放，禁止压扁机身；正面 SVG、镜头动态层与热点共享同一旋转变换，顶部拖条不旋转；配置窗口缩小时不得遮挡 Tab 或控件。
- 背面窗口按显示器的实际像素数选择主屏采样。缩到原始宽高的一半或更小时，先对相邻的 2×2 个预乘透明度像素取平均，再由 JavaFX 显示。窗口大小、主题或显示器 DPI 改变时会重新选择采样级别。导出的固件帧保留原始分辨率。
- **极简 EVF 浮窗与置顶**：`EvfViewfinderWindow` 为无常驻工具栏的全幅取景视口（默认 `560×420`，最小 `280×210`），状态、眼传感器（`F6`）、`VIEW MODE`（`F7`）与置顶按钮（`#evf-window-pin-toggle`）收纳在右上角半透明悬浮条中，仅在鼠标悬停或键盘焦点进入时淡入；置顶状态持久化为 `profile.yaml` 的 `windows.evfAlwaysOnTop`。该窗口不隶属主窗口，以保证主窗口最小化时仍可置顶，并随 `CameraStudio.close()` 一并关闭。
- **窗口生命周期与瞬时按键防抖**：六个面窗口采用无系统装饰细边线，仅顶部窄条可拖动，硬件热点不触发拖窗。关闭面窗口仅隐藏并同步开关，重开复用原 `Stage`/节点并恢复位置，不卸载硬件组件；隐藏、关闭或失焦时自动调用 `cancelPendingReleases()` 并释放临时按键；关闭主窗口释放全部子窗、按键与运行时。瞬时按键经 `SingleStepButtonHold` 与 `OwnerLoop` 应用 tick 锚定（确保 STILL 实时取景下 `MENU/OK` 与 `DISP/BACK` 稳定越过固件防抖门限）。
- **owner 线程、光学/音频/输入源面板与三屏隔离**：
  - 全部硬件与宿主控制项（如 `battery` 电量/电压、`lens`/`mount` 10 套镜头/转接环预设与 AF/MF/光圈/焦距/电子触点、`audio` 全双工音频输出/麦克风/环回/插孔、`cmos` 多源输入切换、`i2c-bus` `eyeSensorNear`）通过 `owner` 线程或线程安全运行适配器提交。
  - **独立 `镜头光学` 页签与浮窗 `LensProfileWindow`（`LensProfilePanel`）**：提供 10 套预设（`gf45-100`、`gf20-35`、`gf32-64`、`gf63`、`gf110`、`gf50`、`adapter-ef-gfx`、`adapter-m42-dumb`、`adapter-hasselblad-v`、`custom`）、2D 平面散焦/深度虚化、`imageCircleMm` 暗角、AF/MF 对焦、电子触点状态控制，以及 **图形 / Vulkan 计算（Graphics / Vulkan Compute）** 卡片（支持无重启热切换 `Auto (Vulkan -> CPU)` / `Vulkan GPU` / `CPU SIMD` 与枚举到的物理 Vulkan 显卡，同步持久化至 `profile.yaml` 并醒目提示：**接入 Minecraft 等游戏使用 GPU 显存零拷贝时，必须选择与游戏运行在同一张显卡（同 GPU）上的 Vulkan 设备**，否则自动降级为共享内存传输）。
  - **独立 `音频 I/O` 页签（`AudioIoPanel`）与右侧栏 `音频快捷 · AUDIO I/O` 面板**：管理音频引擎后端（`auto` / `native` / `javasound`）与目标缓冲延迟（`10..500 ms`）、播放输出（开关、音量、静音、设备选择、3.5mm 耳机插孔 `GPIO 8.4`）、音频输入与系统环回（`OFF` / `MICROPHONE` / `OUTPUT_LOOPBACK` / `TEST_TONE`、设备选择、增益、静音、监听开关、测试音频率、3.5mm 外接麦克风插孔 `GPIO 6.4`），并实时渲染输出/输入 VU 电平表与 TX/RX 环形缓冲统计。
  - **右侧栏 `输入源 · INPUT SOURCE` 面板（`SourceSelectorPane`）**：支持实时切换多源 CMOS 输入（`JPEG`、`PNG`、`VIDEO_FILE`/`ffmpeg-pipe`、`RTMP_RTSP`、`WEBCAM`、`stream-ingest` TCP `FSCM`+depth / HTTP / UDP、`screen-capture` 桌面/区域/游戏窗口、`optical-scene` 三平面 `0.8m/2.5m/12m` 深度影棚）。
  - 当前 `12` 个逻辑 `ButtonPort` 与 `68` 个物理热区由单一真源 [catalog.json](../../../desktop/src/main/resources/panels/gfx100s/catalog.json) 定义，不手工维护第二份映射表。
  - **开关与拨杆交互契约**：所有物理锁存开关与档位选择器（如 `power`、`STILL/MOVIE`、`S/C/M`、`P/S/A/M/C1–C6`、`eyeSensorNear`）必须由 `catalog.json` 的 `interaction` 驱动为锁存切换/档位选择器并显示 `开启`/`关闭`（或档位状态），严禁实现为松手即弹回的瞬时按压按钮；`按下`/`释放` 仅用于瞬时按键。
  - `display.main`（`1024×768` 1:1 直出）、`display.evf`（`640×480`）与 `display.shoulder`（`304×230` 正立 `upright`）完全隔离，互不别名；无固件帧时明确显示等待态，禁止宿主假菜单。

## 2. 几何、配色与 GFX ETERNA 55 视觉风格

- **机身几何**：严格依据 GFX100S / FF200003 官方手册与实拍照片绘制轮廓、窄倒角、材质接缝与明暗关系（如 `S/C/M` 拨杆按手册印刷 77 页/PDF 101 页顺序标注在左侧、屈光度轮紧贴 EVF 左壳、俯视 EVF 高于机顶、左侧双接口盖接缝、右侧细长 SD 仓门），不借用其他机型布局。
- **GFX ETERNA 55 Simulator 视觉标准**：画布与面板采用冷静中性的单色暗调（基准画布 `#161515`、层级清晰的暗灰控件、高可读中性白字与细边线、克制聚焦态），硬光集中在窄倒角与相邻暗边，不使用廉价渐变或大面积泛光。
- **确定性材质**：机身喷涂与蒙皮橡胶采用原创、确定性、整面非周期程序纹理（明确缓存上限与内存 scope，无 32/64px 周期接缝、不逐帧重算）；视觉近似不冒充实测 BRDF。

## 3. 来源记录与精简验证

| 资料 | 用途 |
| --- | --- |
| [18 个官方来源](sources.generated.json) | GFX100S 手册与外观来源身份 |
| [几何核对](geometry-reference.generated.json) | `S/C/M`、EVF 与照片分层依据 |
| [网页代码记录](source-code-reference.generated.json) | ETERNA simulator 官方 CSS/JS/资源配色参考 |
| [浏览器观察](visual-reference.generated.json) | 参考页面与截图观察 |
| [字体依据](font-reference.generated.json) | 字体栈与平台回退规则 |
| [材质研究](texture-reference.generated.json) | 材质对比参数与限制 |
| [历史设计验证](design-validation.generated.json) | 历史构建与截图记录（不可变原件） |

- **UI 修改验收（单门控）**：运行相关无头/图形 JUnit 与 `--smoke-ui` E2E（覆盖六面开关、窗口关闭/重开、拖条无误触、紧凑排布、越界配置拒绝、三屏绑定与 `eyeSensorNear`），并**实际目视检查导出的 PNG 截图**（记录分辨率、DPI、捕获环境与 `git commit`，不以文件存在或 PNG 哈希替代视觉核对）。
- **边界声明**：宿主窗口、按键高亮与镜头插图仅证明宿主 UI 能力，不代表固件已消费按键、扫描输出或完成回放。
