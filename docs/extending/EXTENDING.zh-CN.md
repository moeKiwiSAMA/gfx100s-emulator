# 扩展与模组开发指南 (Extending & Modding)

[English](EXTENDING.md) | **简体中文** | [日本語](EXTENDING.ja.md)

*光学、玻璃与机械质感 —— FUJIFILM GFX100S 模拟器开放扩展架构指南*

本模拟器将底层相机芯片模拟与三层开放、支持热重载的扩展体系彻底解耦：

1. **模块化镜头与转接环 JAR（`<模拟器根目录>/lenses/*.jar`）** —— 基于 Java `ServiceLoader` SPI v2，完整建模真实光学像差、光圈叶片几何、对焦马达运动学以及 `LENSCOM` 卡口通信协议。
2. **光学滤镜、入瞳散景遮罩与自定义 SPIR-V 着色器（`<模拟器根目录>/filters/*.jar`）** —— 运行于镜头前端线性辐射度空间的物理滤镜、几何光学散景孔径遮罩，以及自定义 Vulkan 1.1 `.spv` 计算着色器。
3. **机身与工作台 UI 主题（`<模拟器根目录>/themes/body/` 与 `themes/ui/`）** —— 矢量优先的 SVG + 混合材质相机外壳皮肤，以及基于 `-fuji-*` 语义令牌的暗房/明亮工作台配色包。

> [!TIP]
> **使用 AI 编程助手（Claude Code / Gemini / Codex / Cursor）开发扩展？** 仓库根目录下的 `skills/` 提供了开箱即用的 AI 技能规范（含完整字段表、参数校验边界与验证命令），直接让 AI 读取即可一次生成可用扩展：
> - 镜头与转接环开发技能：[`skills/create-lens/SKILL.md`](../../skills/create-lens/SKILL.md)
> - 光学滤镜与 SPIR-V 着色器技能：[`skills/create-optical-filter/SKILL.md`](../../skills/create-optical-filter/SKILL.md)
> - 机身与 UI 主题开发技能：[`skills/create-theme/SKILL.md`](../../skills/create-theme/SKILL.md)
> - 实时控制与热重载 HTTP 接口参考：[`docs/api/HTTP_API.zh-CN.md`](../api/HTTP_API.zh-CN.md)

---

## 1. 模块化镜头与卡口转接环 JAR（`lenses/*.jar`）

内置的每一支镜头（如 `lenses/lens-gf63/`、`lenses/lens-gf110/`、`lenses/lens-takumar-50-f14/`）与卡口转接环（`lenses/adapter-fringer-ef-gfx/`、`lenses/adapter-m42-gfx/`、`lenses/adapter-hasselblad-v-gfx/`）均为独立打包的 JAR 模块。启动或执行热扫描时，`LensModuleLoader` 会通过隔离的 `URLClassLoader` 与 `java.util.ServiceLoader` 自动扫描 `<模拟器根目录>/lenses/`。

### 1.1 ServiceLoader SPI 与严格互斥通信契约

实现 `com.github.nyabanban.fuji.emulator.api.LensModule`（或 `MountAdapterModule`），并在 JAR 资源中注册完整类名：

- `META-INF/services/com.github.nyabanban.fuji.emulator.api.LensModule`
- `META-INF/services/com.github.nyabanban.fuji.emulator.api.MountAdapterModule`

每支镜头（或“转接环 + 镜头”组合）必须通过 `sealed interface LensControlContract` 声明**严格三选一**的通信模式（加载时由 `validateContract()` 强制校验）：

| 模式 (`LensCommunicationMode`) | 契约类型 | 电子触点 | 卡口 `LENSCOM` (`SPI0`) 与固件行为 |
| --- | --- | --- | --- |
| `GENERIC_SIMULATED` | `LensControlContract.GenericSimulated(LenscomIdentitySpec)` | `true` | 根据光学与机械参数动态合成标准 `LENSCOM` 协议响应：128 字节 `cmd 0x014` 身份头、128 KiB `cmd 0x0C0` 光学 EEPROM、`A`/`C`/`MANUAL` 1/3 档光圈同步，以及有符号 15 位对焦编码器（`0..0x7FFF`）。 |
| `MANUAL` | `LensControlContract.Manual` | `false` | 纯机械手动镜头或无触点转接环（强制要求 `defaultFocusMode = "MF"`、`focusMotorType = "MANUAL"`、`focusSweepSeconds = 0.0`）；机身显示 `F0` 并启用无镜头释放/转接环设置。 |
| `REAL_FIRMWARE` | `LensControlContract.RealFirmware(...)` | `true` | 从 `<模拟器根目录>/firmware-data/lenses/` 加载用户自备的真实镜头/转接环固件镜像（`fuji-lens-type4-dat` 或 `fringer-adapter-bin`）。文件缺失或 SHA-256 不匹配时**立即报错失败**，绝不静默降级。 |

编写电子镜头的 `LenscomIdentitySpec` 时，请确保 `shortId` 为 `1..7` 位大写英数字（`[A-Z0-9]`），原生 GF 镜头开启 `capabilityFlags1 = 0x01`（支持机身镜头调制优化器 LMO），并为每支镜头分配**唯一的 10 字符 `serialAscii` 序列号**（如 `"GF08001701"`），防止机身 NVRAM 中的 128 KiB 光学 EEPROM 缓存在热插拔换镜时发生串台。

### 1.2 物理光学模型、对焦马达运动学与特殊光学钩子

`LensOpticalSpec` 同时驱动 Vulkan 1.1 GPU 光学计算管线与确定性 `CpuOpticalPipeline`（`OpticalLensProfile`）：

- **焦段、光圈与像场直径**：`minFocalLengthMm..maxFocalLengthMm`、`minAperture..maxAperture`（按 `ApertureScale.THIRD_STOPS` 生成 1/3 档步进）、T 值透光率 `transmission`（`0.0..1.0`）以及物理像场直径 `imageCircleMm`（GFX 43.8×32.9 mm 传感器对角线为 `54.78` mm：原生 GF 镜头通常取 `56.0..57.0` mm；转接 135 全画幅取 `43.3` mm 以真实还原硬暗角裁切；6×6 中画幅取 `79.0` mm）。
- **对焦马达行程与呼吸效应**：`FocusMotorTypes`（`LM_FAST`、`LM`、`STM`、`USM`、`DC_CORELESS`、`MANUAL`）、无穷远至最近对焦距离（`minFocusDistanceM` / `teleMinFocusDistanceM`）的全行程对焦耗时 `focusSweepSeconds`（`0.0..5.0` 秒，按屈光度空间平滑推进），以及近摄呼吸效应系数 `focusBreathing`（`-0.5..0.5`）。
- **几何畸变、色散与炫光**：Brown-Conrady 径向畸变（`distortionK1`、`distortionK2`）、倍率色差（`caRedScale`、`caBlueScale`）、轴向色散/紫绿边 `longitudinalCaDefocusMm`（随收小光圈按 `minAperture / fNumber` 衰减）、全开球差反差下降 `wideOpenContrastDrop`（`0.0..0.6`）、`vignettingCos4` 与机械暗角 `vignettingMechanical`，以及镀膜炫光强度 `flareStrength` 与偏色 `flareTintR/G/B`。
- **光圈叶片与散景（Bokeh）特征**：`apertureBladeCount`（严格限定 `5..11` 片）、圆润度 `apertureBladeRoundness`（`0.0..1.0`，全开圆形、收小光圈渐变为多边形）、口径蚀柠檬猫眼 `bokehCatsEye`（`0.0..1.0`）、非球面洋葱圈 `bokehOnionRing`（`0.0..0.5`）及过矫正肥皂泡亮边 `bokehRimHighlight`（`0.0..0.6`）。
- **浮动光圈变焦与特殊光学扩展（SPI v2）**：
  - 重写 `opticalSpecAt(focalLengthMm, focusDistanceM, aperture)` 可实现浮动最大光圈变焦以及随焦距连续变化的畸变、色散和暗角曲线；
  - 重写 `opticalHook(): LensOpticalHook?` 可实现移轴沙姆定律倾斜焦平面（`effectiveFocusDistanceAt`）、变形宽银幕/鱼眼几何映射（`warpUv`）、旋焦/折返入瞳透过率（`pupilWeight`）或焦外后置自定义 `.spv` 着色器（`vulkanSpirvPass`，阶段 `CustomSpirvStage.POST_OPTICS_RGB`）；
  - 重写 `eepromBytes(): ByteArray?` 可提供精确 `131,072` 字节的自定义 128 KiB 光学 EEPROM 镜像。

### 1.3 推荐的 8 大光学参数权威参考源

制作镜头参数时请参考以下 **8 个实测与光学工程数据源**（具体换算公式详见 [`skills/create-lens/SKILL.md`](../../skills/create-lens/SKILL.md)）：

1. **LensBench**（`https://optic.chat`）—— 序列光学实验台与镜头处方评估规范
2. **Photons to Photos · Optical Bench**（`https://www.photonstophotos.net/GeneralTopics/Lenses/OpticalBench/OpticalBench.htm`，Bill Claff）—— 光学专利结构、出入瞳几何、呼吸效应、机械暗角与口径蚀（Cat's-eye）建模
3. **Jim Kasson · The Last Word**（`https://blog.kasson.com`）—— GFX100/100S 原生富士 GF 镜头未机内矫正 RAW 光学台实测（MTF、轴向色差 LoCA、呼吸效应、全开球差）
4. **LensTip**（`https://www.lenstip.com`）—— 倍率/轴向色差、慧差、逆光炫光/鬼影、暗角与自动对焦行程耗时实验室数据
5. **OpticalLimits（原 Photozone）**（`https://www.opticallimits.com`）—— RAW 原始畸变与机内矫正对比、边缘色散、暗角、焦外表现与各档光圈边角 MTF
6. **Lensfun 开源镜头数据库**（`https://lensfun.github.io`）—— Brown-Conrady 畸变模型、TCA 横向色差缩放系数与暗角数学拟合约定
7. **DxOMark**（`https://www.dxomark.com`）—— 实测 T-stop 透光率与边缘照度衰减参考数据
8. **富士胶片（Fujifilm）官方 GF 镜头技术规格表** —— 焦距、最大/最小光圈、光圈叶片数、最近对焦距离（MOD）、像场与对焦马达架构规格

### 1.4 编译、放入目录与热加载测试

1. 仅以 `compileOnly(project(":component-api"))` 编译打包（定焦可参考 `lenses/lens-gf63/`，变焦可参考 `lenses/lens-gf32-64/`）：
   ```bash
   ./gradlew :lenses:lens-gf63:jar --no-daemon
   ```
2. 将生成的 `.jar` 放入 `<模拟器根目录>/lenses/`。
3. 无需重启模拟器，通过控制台 `/lens rescan` 或 HTTP 控制接口（发行版默认端口 `8787`）热扫描并挂载新镜头：
   ```bash
   REV=$(curl -s http://127.0.0.1:8787/api/v1/state | jq '.components[] | select(.id=="lens") | .revision')
   curl -s -X PATCH http://127.0.0.1:8787/api/v1/components/lens \
     -H "Content-Type: application/json" -d "{\"revision\": $REV, \"changes\": {\"rescanPlugins\": 1}}"
   curl -s -X POST http://127.0.0.1:8787/api/lens \
     -H "Content-Type: application/json" -d '{"template": "my-lens-id", "opticsEnabled": true}'
   ```

---

## 2. 光学滤镜、入瞳散景遮罩与自定义 SPIR-V 着色器

独立的 `:components:optical-filter` 组件（端口 `"optical-filter.stack"`）在镜头前端构建了多槽位遮光斗滤镜架。所有滤镜均严格工作在**传感器曝光前的线性 HDR 辐射度空间**（位于 CMOS 光子散粒噪声、读出噪声与 Bayer 采样之前），因此减光镜的透光率衰减会真实联动相机的 AE 自动测光与直方图。

### 2.1 内置光学滤镜元件与多槽位叠加

在桌面端**光学滤镜架（Optical Filter）**面板、`configs/components/optical-filter/optical-filter.yaml` 或 `POST /api/filters` 中，最多可按顺序自由叠加 **12 个滤镜槽位**（`OpticalFilterSlotCodec.MAX_SLOTS = 12`）：

- **`nd`（中灰减光镜）**：`ND2`..`ND1000` 或无级可调 `VARIABLE`（`0.0..10.0` EV），纯标量 `2^(-stops)` 辐射度衰减，零逐像素拷贝开销。
- **`gnd`（软边渐变灰滤镜）**：`0.0..5.0` EV 天空压暗，支持水平线位置 `horizon`、羽化宽度 `feather` 与旋转角 `angleDeg`。
- **`black-mist`（电影黑柔焦滤镜）**：`1/8`、`1/4`、`1/2`、`1` 或 `CUSTOM`，在保留暗部扎实反差的同时对高光产生柔和晕染（Halation）。
- **`star`（星芒衍射滤镜）**：围绕高光点光源生成 `4`、`6` 或 `8` 线线性衍射星芒。
- **`uv`（物理 UV 保护镜）**：真实模拟玻璃与镀膜品质差异——从顶级多层镀膜 `PRO_MRC`（透光率 `99.6%`）到廉価无镀膜玻璃 `CHEAP_POOR`（`89.5%` 透光损耗、灰雾眩光、跨光轴中心 `(-x,-y)` 点对称双反射绿紫鬼影、MTF 微柔劣化与钠钙玻璃偏色）。
- **`bokeh-mask`（入瞳几何散景遮罩）**：内置五角星 `STAR_5`（`★5`）、爱心 `HEART`（`♥`）、六边形 `HEXAGON`（`⬡`）、折返圆环 `RING`（`◎`）及插件 `CUSTOM`。通过 `OpticalPupilMask.pupilWeight(ux, uy)` 直接参与景深散景卷积核计算——焦点内物体依旧锐利，焦外点光源则物理成相为对应孔径形状（建议开启镜头光学计算、使用 `f/1.4`–`f/2.8` 大光圈近摄并搭配 `scene://star-bokeh` 观察）。

### 2.2 第三方滤镜 JAR、自定义散景遮罩与 Vulkan SPIR-V 计算着色器

实现 `com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider` 并在 `META-INF/services/com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider` 注册后，将 `.jar` 放入 `<模拟器根目录>/filters/` 即可：

1. **自描述 UI 控件生成（`FilterParamSpec`）**：在 `parameterSchema()` 中声明 `NUMBER`、`ENUM`、`BOOLEAN` 或 `TEXT` 参数，桌面滤镜面板会自动为其生成滑动条、下拉框与开关。
2. **自定义入瞳散景遮罩（`OpticalPupilMask`）**：在 `OpticalFilterElement.pupilMask()` 中返回 `shape = BokehMaskShape.CUSTOM` 的遮罩实例，可在单位圆盘 `(ux, uy)` 内计算解析有向距离场（SDF）、预光栅化的 SVG 路径，或双线性采样内嵌的灰度 PNG 位图；多个入瞳遮罩叠加时由 `CompositeOpticalPupilMask` 自动相乘合成。
3. **自定义 Vulkan 1.1 SPIR-V 着色器（`CustomSpirvPassSpec` / `fuji_vulkan_custom_spv.h`）**：
   - 使用 `glslangValidator -V --target-env vulkan1.0 -S comp` 将 GLSL 编译为 `.spv`（`20 B .. 256 KiB`、4 字节对齐、魔数 `0x07230203`、工作组 `1..64`），并通过 `CustomSpirvPassSpec.fromResource(...)` 在 `CustomSpirvStage.PRE_OPTICS_FILTER` 阶段直接就地运行于 GPU `DEVICE_LOCAL` 显存。
   - 固定 `set = 0` 绑定约定：`binding = 0`（只读输入 `SrcRgba`）、`binding = 1`（只写输出 `DstRgba`）、`binding = 2`（只读米制深度图 `DepthMeters`）、`binding = 3`（`std140 PluginUbo`：前 32 字节标准头 `[width, height, focusM, fNumber, focalMm, frameIndex, flags, userBytes]` + 最多 `4096 B` 插件自定义 `uniformData`），以及可选 `<= 128 B` `push_constant`。
   - 原生层内置 32 槽位 SHA-256 LRU `VkPipeline` 缓存，连续帧零重编译开销。
   - **强制 CPU 回退契约**：插件**必须**同时实现 `applySpatialLinearRgb(...)`（复用零分配 `OpticalFilterScratch` 缓冲与可选 `OpticalFilterContext`）。当处于 `opticsBackend: cpu` 无 GPU 环境或第三方 `.spv` 校验失败时，运行时会记录 `WARN [FALLBACK][vulkan]` 并自动无损回退至 CPU 实现，绝不污染 `VkDevice` 上下文或导致崩溃。

完整 GLSL 与 Kotlin 模板请参阅 [`skills/create-optical-filter/SKILL.md`](../../skills/create-optical-filter/SKILL.md)。

---

## 3. 明确不会实现的功能边界（Non-Goals）

为了让光学创作自由顺畅、架构边界清晰纯粹，本模拟器**明确声明永远不会实现**以下机制：

1. **绝不强制校验滤镜物理螺纹口径（`ø62mm` / `ø77mm` / `ø82mm`）**：
   光学滤镜系统**永远不会**去匹配或限制镜头的物理前组滤镜口径。现实中为不同镜头折腾 `ø62mm`、`ø77mm`、`ø82mm` 转接环与灯泡头支架既繁琐又昂贵；在模拟器中，滤镜架被明确设计为**通用无口径限制的遮光斗（Universal Matte Box）**，任何滤镜都可以直接与任意原生 GF、135 或 6×6 镜头自由组合，绝不引入无意义的转接环口径管理负担。
2. **不设置人为机械卡口干涉或后组碰撞锁死**：
   我们通过物理像场直径（如 135 镜头的 `43.3 mm` 像场在 `54.78 mm` 中画幅传感器上的真实暗角裁切）呈现跨画幅光学特性，但绝不人为禁止任何创意跨卡口转接或实验性光学组合。
3. **不用 ISP 之后的 2D 屏幕贴纸伪装光学效果**：
   散景光斑形状、ND 减光与眩光绝不采用 JPEG/sRGB 屏幕层贴图伪造，所有光学扩展均严格保持在传感器前的线性辐射度与入瞳空间。
4. **绝不提供商业固件破解、签名绕过或真机刷写工具**：
   本模拟器不包含任何受版权保护的原厂固件二进制文件，也绝不开发任何用于修改、破解或刷写实体相机的工具。

---

## 4. 机身与工作台 UI 主题（Theme System v2）

Theme System v2（`BodyTheme.kt`、`UiTheme.kt`、`PanelCatalog.kt`、`SvgArtwork.kt`）将视觉定制拆分为两个正交解耦的层级，存放于 `<模拟器根目录>/themes/` 下，支持任意自由混搭与不重启热重载：

- **相机机身主题（`themes/body/<id>/body-theme.json`）**：定义 `1..12` 个相机机身面板、外观美术、交互热区、屏幕视口、可开合舱门以及动态 SVG 状态绑定。内置 4 款：`origin`（6 面板 GFX100S）、`hasselblad-x2d2`、`hasselblad-907x`、`compact-rangefinder`。
- **工作台 UI 主题（`themes/ui/<id>/ui-theme.json` 或 `themes/ui/<id>.css`）**：在基础样式表 `/panel.css` 之上覆盖窗口背景色与 `-fuji-*` 语义令牌。内置 5 款：`origin`（曜石暗房）、`nordic-minimal`、`daylight-studio`、`vintage-brass`、`olive-field`。

### 4.1 矢量优先混合材质与严格热区校验

- **美术资源规范**：强烈推荐使用矢量 SVG（`"svg": "rear.svg"`，单文件 `<= 256 KiB`、节点数 `<= 4096`，且 `viewBox` 必须精确等于 `0 0 <width> <height>`）。在 SVG 内可自由组合：
  - 包内局部高清位图部件（`<image href="artwork/mode-dial.png" .../>`，`<= 4 MiB`、`<= 4096×4096`）；
  - 材质贴图平铺图案（`<pattern id="..." patternUnits="userSpaceOnUse" width="..." height="..." data-texture="textures/leatherette.png"/>`，`<= 2 MiB`、`<= 1024×1024`；内置主题亦共享 `/themes/body/shared/{leatherette,brushed-chrome,xcd-glass}.png`）；
  - 零体积程序化连续微浮雕纹理（`data-generator="continuous-grain-v1"`，固定 `1200×800`，`data-kind` 支持 `paint`、`rubber`、`brushed-metal`、`anodized`）；
  - 硬件加速投影/内阴影 `data-effect="shadow"` / `"inner-shadow"`、`<clipPath>` 与带环路检测的本地 `<use href="#id"/>`。
- **透明热区契约**：`views[].controls` 中的每个控件必须在 SVG 中拥有一一对应的 `<g id="<id>" data-control-id="<id>" data-view-id="<viewId>">`，且内部包含**唯一一个**坐标完全一致的透明 `<rect data-hitbox="true" x="..." y="..." width="..." height="..." fill="none" stroke="none"/>`。
- **动态状态、语义角色与机械舱盖**：
  - `dynamicStates`：`rotate-map`（根据 `soc-adc.modeDial` 或 `soc-gpio.focusMode` 围绕 `pivotX`/`pivotY` 旋转曝光转盘与对焦拨杆）、`highlight-button`（联动切换 `movieMode` 静态/视频拨杆或 `power` 电源开关双态图形）、`led-binding`（状态指示灯透明度绑定）。
  - `studio-display-hotspot`：自动挂载于 `kind: "display"` 屏幕视口（`display.main` 电容触控与滑动手势、`display.evf` 双击弹出独立取景器窗口、`display.shoulder` 反射式单色肩屏，支持 `cornerRadius` 圆角与 `clipShape: "circle"` 圆形裁切）。
  - `focus-mode-segments` 与 `exposure-mode-button`：分别为三段式 `AF-S/AF-C/MF` 直选条与无机械转盘机身的曝光模式选择键赋予原生交互。
  - `data-control-proxy`：在 `<svg>` 顶层放置无 `transform` 的透明 `<rect id="..." data-control-proxy="<目标控件ID>" ... fill="none" stroke="none"/>`，即可在不重复占用 `buttonKey` 的前提下跨面板映射物理拨轮/按键（例如在顶面操作前指令拨轮 `front-command-dial`）。
  - 机械舱盖 `covers`：通过 `#<id>-closed` 与 `#<id>-open` 两组 SVG 图形配合 `kind: "cover"`、可选卡扣 `kind: "latch"`、`interaction: "cover-toggle"`、`inside` 与 `openHitbox`，真实还原双 SD 卡舱门、电池仓盖与接口盖的独立开合交互。
- **安全沙箱与实时热重载**：外部主题加载器通过 `toRealPath()` 严格校验目录边界，拒绝任何 `..` 路径穿越、符号链接越界、`<script>`、内联 `style`、`on*` 事件、外部网络 URL 与 CSS `@import`。修改文件后点击顶部栏 `↻` 刷新按钮、执行 `/theme reload` 或调用 `POST /api/theme/reload` 即可实时生效。完整规范见 [`skills/create-theme/SKILL.md`](../../skills/create-theme/SKILL.md)。

---

## 5. 多语言国际化（i18n）最佳实践

桌面工作台（`I18n.kt` 与 `desktop/src/main/resources/i18n/messages_*.properties`）、插件元数据（`PluginMetadata`）及 Theme System v2（`ThemeLocalization`）采用统一的**六语种实时切换架构**（顶部语言菜单 / `/lang` / `POST /api/lang`）。

### 5.1 推荐默认提供全部 6 种语言

在发布自定义镜头 JAR、光学滤镜 JAR、机身主题或 UI 主题时，强烈建议默认完整提供以下 **6 种内置语言**：

| 标准语言代码 | 语言名称 | 角色定位 |
| --- | --- | --- |
| `zh_CN` | 简体中文 | 推荐默认内置语种 |
| `ja_JP` | 日语（日本語） | 推荐默认内置语种 |
| `en` | 英语（English） | **强制基准与兜底回退语种** |
| `de_DE` | 德语（Deutsch） | 推荐默认内置语种 |
| `fr_FR` | 法语（Français） | 推荐默认内置语种 |
| `es_ES` | 西班牙语（Español） | 推荐默认内置语种 |

### 5.2 回退优先级与 UTF-8 编码规范

- **严格的英语兜底回退**：`PluginMetadata` 与 `ThemeLocalization` 会自动兼容连字符与下划线（`zh-CN` 与 `zh_CN` 等价），并按以下确定性顺序解析：
  1. 精确匹配区域代码（如 `ja_JP`）；
  2. 匹配双字母基础语种前缀（如 `ja`）；
  3. 回退至英语（`en`）或顶层英文 `name` / `description`。
  即使部分语种缺省或遇到未支持的区域设置，界面也绝不会报错或显示空白键名，而是平滑回退至英文文案。
- **文件编码与清单规范**：
  - 所有 `.json` 主题清单、`META-INF/services/*`、Kotlin 源码以及 `messages_*.properties` 资源文件均须使用**无 BOM 的 UTF-8 编码**与 **LF (`\n`) 换行符**（文件末尾保留换行）。`I18n.kt` 直接通过 `InputStreamReader(..., StandardCharsets.UTF_8)` 读取 `.properties`，因此可直接书写原生中文、日文与欧洲重音字符，无需转义为 `\uXXXX`。
  - 在 **Kotlin 插件**（`LensModule`、`MountAdapterModule`、`OpticalFilterElementProvider`）中，为 `PluginMetadata(names = ..., descriptions = ...)` 与 `FilterParamSpec(labels = ..., descriptions = ...)` 填入全部 6 种语言映射。
  - 在**主题清单**（`body-theme.json`、`ui-theme.json`）中，除保留顶层英文 `"name"` 与 `"description"` 外，在 `"i18n": { "names": { ... }, "descriptions": { ... } }`、面板 `"titles"` 与控件 `"labels"` 中同步提供 6 语种译文。
