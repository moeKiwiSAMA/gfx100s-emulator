# HTTP 控制与遥测 API 参考手册

[English](HTTP_API.md) | **简体中文** | [日本語](HTTP_API.ja.md)

FUJIFILM GFX100S 模拟器内置了零外部依赖的本地环回 HTTP 服务（`ControlServer` / `ControlApiSpec`，遵循 OpenAPI `3.1.0`），用于读取实时硬件状态与遥测、自动化操控物理按键/触控/拨盘、热切换镜头/滤镜/主题，以及驱动无界面（Headless）自动化测试脚本。

- **关联指南**：[扩展与模组开发指南](../extending/EXTENDING.zh-CN.md)
- **内置离线交互式文档**：模拟器运行期间在浏览器打开 `http://127.0.0.1:8787/api/docs` 即可使用零 CDN 离线交互式 API 调试台，或访问 `GET /api/openapi.json` 获取实时 OpenAPI 3.1.0 规范（仓库亦同步提供静态生成的 `docs/api/http-openapi.json` 与 `docs/api/http-control-api.md`）。

---

## 1. 服务启用、配置项与安全策略

### 1.1 端口配置（`configs/emulator.yaml` 与命令行）

在桌面 GUI 与 `--headless` 交互模式下，`ControlServer` 均会自动读取 `configs/emulator.yaml`（或通过 `--config <路径>` 指定的配置文件）中的 `httpPort` 启动监听：

```yaml
# configs/emulator.yaml（发行版与开发配置默认均为 8787）
httpPort: 8787
```

- **默认基础地址**：`http://127.0.0.1:8787`（若设为 `httpPort: 0`，系统将自动分配空闲本地端口，可在 `logs/latest.log` 中查看 `Control API listening on /127.0.0.1:<端口>`）。
- *（注：`ControlApiSpec.DEFAULT_BASE_URL` 静态 OpenAPI 示例中使用 `18080` 作为示例端口占位符；实际自动化脚本请连接 `emulator.yaml` 中配置的 `httpPort`，默认即 `8787`。）*

### 1.2 无头运行、性能剖析器与遥测启动参数 / 环境变量

| 参数 / 配置键 | 来源 | 作用说明 |
| --- | --- | --- |
| `--home <目录>` / `--config <yaml>` | CLI | 指定模拟器运行根目录与主配置文件路径（默认 `configs/emulator.yaml`）。 |
| `--headless --auto-boot --no-console` | CLI | 无窗口自动开机并关闭标准输入控制台，适合 Python/CI 批量脚本控制。 |
| `--profile` / `--profile-interval-ms <ms>` | CLI / `-Dfuji.profiler.enabled=true` | 启动时自动开启 `AsyncPerfProfiler` 采样（也可在 `emulator.yaml` 中配置 `profiler: { enabled: true, intervalMs: 5 }`；`-Dfuji.profiler.exportFile=true` 会在退出时导出报告至 `logs/`）。 |
| `--no-telemetry` | CLI / `-Dfuji.telemetry.disabled=true` | 最高优先级总开关，强制禁用一切外部遥测与日志上报。 |
| `--telemetry-upload` | CLI / `-Dfuji.telemetry.upload=true` | 在已配置遥测服务端与令牌的前提下启用退出日志上报（仅 `relaxed` 模式支持；`discovery` 与 `strict` 模式永远零网络请求）。 |
| `FUJI_TELEMETRY_URL` / `FUJI_INGEST_TOKEN` | 环境变量 | 覆盖 `emulator.yaml` 中的 `telemetry.endpoint` 与 `telemetry.ingestToken`（公开构建默认关闭且不附带外部上报地址）。 |

### 1.3 本地环回安全、请求限制与乐观并发控制（`revision`）

- **仅限本机环回（Loopback）**：套接字强制绑定于 `InetAddress.getLoopbackAddress()`（`127.0.0.1`），绝不监听公网网卡。
- **浏览器同源校验**：若请求携带 `Origin` 头，仅允许 `http://127.0.0.1:<端口>` 或 `http://localhost:<端口>`；任何跨源浏览器请求均返回 `400 {"error": "cross-origin control denied"}`（不下发 CORS 头）。
- **请求体与查询参数限制**：非空 JSON 写请求必须携带 `Content-Type: application/json`；单次请求体上限为 **`64 KiB`（`65,536` 字节）**；URL 查询参数最多允许 **`16` 个不重复键**。所有响应默认携带 `Cache-Control: no-store`。
- **乐观修订号并发控制（`PATCH /api/v1/components/{id}`）**：
  底层组件写接口不使用 HTTP `ETag` 头，而是通过 `GET /api/v1/state` 返回的整数 `"revision"` 字段执行乐观锁校验：
  ```json
  { "revision": 4, "changes": { "shutterHalf": true } }
  ```
  每次修改成功后 `"revision"` 单调递增（`200 OK`）。若提交了过期的 `revision`（例如 UI 面板同时修改了该组件），服务端会拒绝覆盖并返回 **`409 Conflict`**（`{"error": "revision conflict"}`），客户端只需重新读取 `GET /api/v1/state` 获取最新 `revision` 重试即可。高层便捷写接口（`POST /api/lens`、`/api/filters`、`/api/adc`、`/api/shooting-mode` 等）已在主线程内部自动完成最新 `revision` 同步。

### 1.4 HTTP 状态码与错误响应格式

所有错误均返回 `application/json; charset=utf-8`，格式统一为 `{"error": "<原因>"}`：

| 状态码 | 含义说明 |
| --- | --- |
| `200 OK` / `201 Created` | 请求成功（`POST /api/v1/dump` 返回 `201`）。 |
| `400 Bad Request` | JSON 语法错误、缺少必填键或包含未知键、数值越界、缺少 `Content-Type: application/json`、请求体 `> 64 KiB`、查询参数 `> 16` 个或跨源 `Origin` 被拒。 |
| `404 Not Found` | 未知路由/HTTP 方法，或当前运行模式下未挂载对应组件/GUI 控制器（如无头模式下的 `/api/theme`）。 |
| `409 Conflict` | `PATCH /api/v1/components/{id}` 的 `"revision"` 乐观锁版本冲突。 |
| `500` / `503` | 内部异常、执行队列繁忙拒绝（`503`），或 `/api/telemetry/upload-log` 未启用/上传失败（`503`）。 |

---

## 2. 全量接口完整参考（共 39 个端点）

### 2.1 核心状态与地址字典（Core & Catalog，4 个）

| 方法与路径 | 查询参数 / 请求体 | 响应格式与说明 |
| --- | --- | --- |
| `GET /api/v1/catalog` | 查询参数（`<= 16` 个不重复键）：`address`（如 `0xfff70000`）、`subsystem`（`isp`、`dma`、`gpio`）、`query`（全文检索）、`id`、`selector`、`space`、`width`、`direction`、`coverage=true`，或 `unknown=true`（查询未建模访问记录）。 | `200` `{"count": N, "entries": [...]}`，查询内置编译版硬件地址字典。 |
| `GET /api/v1/state` | *（无）* | `200` `{"components": [{"id", "revision", "config", "state"}, ...], "machine": {"status", "tick", "attempts", "screenId", ...}}`。最核心的全量遥测与 `revision` 快照接口。 |
| `POST /api/v1/dump` | 空请求体或 `{}`（`Content-Type: application/json`）。 | `201` `{"dump": "dump-..."}`，在 `<home>/dumps/` 下生成状态与寄存器转储快照。 |
| `PATCH /api/v1/components/{id}` | 路径 `id` 匹配 `^[a-z][a-z0-9-]{0,63}$`；请求体 `{"revision": <非负整数>, "changes": {<键>: <值>}}`。 | `200` 更新后的组件状态（`revision + 1`），版本过期时返回 `409`。 |

**常用 `PATCH /api/v1/components/{id}` 组件速查**：
- `buttons`（全部布尔值）：`power`、`shutterHalf`（半按 S1）、`shutterFull`（全按 S2）、`menu`、`back`、`up`、`down`、`left`、`right`、`play`、`delete`、`movieMode`、`drive`、`afOn`、`focusMode`、`rearDialPress`、`frontDialPress`、`fn4`、`focusStickPress`。
- `cmos`：`sourceKind`（`JPEG`、`PNG`、`SCENE_3D`、`VIDEO_FILE`、`WEBCAM`、`SCREEN_CAPTURE` 等）、`sourceFile`（如 `scene://qr-ball`、`scene://star-bokeh`、`scene://mona-lisa` 或媒体路径）、`loop`（布尔值）。
- `lens`：支持全部 `LensFactory` 光学与马达参数，递增 `"rescanPlugins"` 可在线重新扫描 `lenses/*.jar`。
- `optical-filter`：支持全部滤镜参数，递增 `"rescanPlugins"` 可在线重新扫描 `filters/*.jar`。

---

### 2.2 异步性能剖析器（Profiler，3 个）

| 方法与路径 | 查询参数 / 请求体 | 响应说明 |
| --- | --- | --- |
| `POST /api/profiler/start` | 可选查询参数 `?intervalMs=5`（`>= 1`）或 JSON `{"intervalMs": 5}`（请求体优先于查询参数）。 | `200` `{"running": true, "intervalMs": 5}`。 |
| `POST /api/profiler/stop` | 可选 `?format=json\|text\|txt`（或请求头 `Accept: text/plain`）。 | `200` 停止采样并返回 JSON 统计对象或 `text/plain` 热点栈文本报告。 |
| `GET /api/profiler/report` | 可选 `?format=json\|text\|txt`（或请求头 `Accept: text/plain`）。 | `200` 不中断采样的前提下读取当前最新剖析报告。 |

---

### 2.3 触控、拨盘、按键与取景器控制（Touch & Controls，11 个）

| 方法与路径 | 请求 JSON 结构 | 响应说明 |
| --- | --- | --- |
| `POST /api/touch` | **严格包含三个键**：`{"x": 0..65535, "y": 0..65535, "pressed": true\|false}`（后置液晶屏原生触控分辨率为 `x: 0..1023`，`y: 0..767`）。 | `200` `{"ok": true, "component": "rear-touch-ic", "x", "y", "pressed", "revision"}`。 |
| `POST /api/touch/swipe` | 二选一提供预设方向 `"direction": "up"\|"down"\|"left"\|"right"` **或**显式坐标 `"x1", "y1", "x2", "y2"`（严禁混用！），可选 `"steps"`（`1..8`，默认 `4`）与 `"stepMs"`（`5..100`，默认 `20`）。 | `200` 执行经过虚拟时钟校准的多步滑动手势（同时满足取景 T-Fn 快划与回放翻页阈值）。 |
| `GET /api/eye-sensor` | *（无）* | `200` 返回 `tmd2772` 眼传感器状态 `near`、当前活动取景器 `activeViewfinder`（`LCD`\|`EVF`\|`BOTH`\|`WAITING`）及双屏帧序号。 |
| `POST /api/eye-sensor` | **严格单键**：`{"near": true\|false}`。 | `200` 模拟眼睛靠近/离开 EVF 取景器，触发自动取景切换。 |
| `GET /api/adc` | *（无）* | `200` 读取 `soc-adc` 按键阶梯、曝光转盘、后拨轮计数与通道采样状态。 |
| `POST /api/adc` | 非空子集：布尔键 `qButton`、`aelButton`、`fn1Button`、`fn2Button`、`fn3Button`、`lightButton`；字符串 `modeDial`（`default`、`P`、`S`、`A`、`M`、`C1`..`C6`）；整数 `rearDialPosition`（`-1..3`）、`rearDialDetents`（`-10000..10000`）、相对步进 **`rearDialStep`**（`-1000..1000`），以及 12 位原始通道覆盖 `ch3Override`/`ch5Override`/`ch6Override`/`ch7Override`/`ch11Override`（`-1..4095`，`-1` 清除）。 | `200` `{"ok": true, "component": "soc-adc", "revision", "changes": {...}}`。 |
| `GET /api/focus-mode` | *（无）* | `200` 返回 `soc-gpio` 对焦拨杆状态 `focusMode` 与 `effectiveFocusMode`。 |
| `POST /api/focus-mode` | **严格单键** `focusMode`（或别名 `mode`）：`"AF-S"`（兼容 `"AFS"`/`"S"`）、`"AF-C"`（`"AFC"`/`"C"`）、`"MF"`（`"M"`）或 `"default"`。 | `200` 更新机身 `S/C/M` 对焦模式拨杆。 |
| `GET /api/shooting-mode` | *（无）* | `200` 一次请求汇总 `mode`（`STILL`\|`MOVIE`）、`movieMode`、`modeDial` 与 `focusMode`。 |
| `POST /api/shooting-mode` | 非空子集：`mode`（`STILL`、`PHOTO`、`MOVIE`、`VIDEO`）、`movieMode`（布尔值）、`modeDial`、`focusMode`。 | `200` 组合更新静态/视频切换杆、曝光模式转盘与对焦模式。 |
| `POST /api/view-mode` | **严格单键**：`{"pressed": true\|false}`（驱动 GPIO Bank 7 Pin 6）。 | `200` 按下或松开 `VIEW MODE` 取景模式切换键。 |

---

### 2.4 镜头光学与滤镜架控制（Optics & Filters，6 个）

| 方法与路径 | 请求 JSON 结构 | 响应说明 |
| --- | --- | --- |
| `GET /api/lens` | *（无）* | `200` 返回当前挂载镜头模板、通信模式、光圈环档位（`A`/`C`/`MANUAL`）、实时光圈与步进索引、焦距、对焦距离、OIS、光学开关及卡口连接状态。 |
| `POST /api/lens` | 非空子集：`template`（如 `gf63`、`gf110`、`gf50`、`gf32-64`、`gf45-100`、`gf20-35`、`canon-ef85-f14l`、`takumar-50-f14`、`zeiss-planar-80-f28`、`custom`）、`adapterId`（`adapter-fringer-ef-gfx`、`adapter-m42-gfx`、`adapter-hasselblad-v-gfx`）、`communicationMode`（`GENERIC_SIMULATED`、`MANUAL`、`REAL_FIRMWARE`）、`realFirmwareFile`、`apertureRingMode`（`A`/`C`/`MANUAL`）、`aperture`、`focalLengthMm`、`focusDistanceM`、`subjectDistanceM`、`focusMode`、`oisEnabled`、`opticsEnabled`、`electronicContacts`、`afTrigger`。 | `200` 热更换镜头/转接环或实时调节光圈、焦距与对焦距离。 |
| `GET /api/filters` | *（无）* | `200` 返回 `optical-filter` 完整配置、多槽位栈、总透光率与已发现的插件列表。 |
| `POST /api/filters` | 支持：`clear: true`（或 `off: true` 一键清空）；`enabled: bool`；有序多槽位数组 `slots`（最多 `12` 个 `{"type": "...", "enabled": true, ...}` 对象或 JSON 字符串）；扁平字段（`ndPreset`、`ndStops`、`uvPreset`、`uvTransmittance`、`uvVeilingGlare`、`uvGhostIntensity`、`uvSoftness`、`uvTintR/G/B`、`bokehMask` [`OFF`/`STAR_5`/`HEART`/`HEXAGON`/`RING`/`CUSTOM`]、`bokehMaskAngleDeg`、`bokehMaskScale`、`star*`、`mist*`、`gnd*`）；或嵌套简写对象 `uv`、`nd`、`bokeh`、`star`、`mist`、`gnd`。 | `200` 更新后的光学滤镜栈状态。 |
| `GET /api/nd` | *（无）* | `200` 专用 ND 遥测（`preset`、`stops`、`effectiveStops`、`factor`、`opticalDensity`、`transmittancePercent`、`summary`）。 |
| `POST /api/nd` | ND 快捷接口，支持 `off: true`、`enabled`、`preset`/`ndPreset`（`OFF`、`ND2`..`ND1000`、`VARIABLE`）或 `stops`/`ndStops`（`0.0..10.0`）。 | `200` 更新后的 ND 减光指标。 |

---

### 2.5 音频、无线、遥测、语言/主题与 NOR Flash（13 个）

| 分类 | 方法与路径 | 请求参数 / JSON 字段 | 响应与行为 |
| --- | --- | --- | --- |
| **音频** | `GET /api/audio` | *（无）* | `200` WM8962 与宿主机混音器音量、静音、缓冲延迟、耳机/麦克风插孔及输入模式状态。 |
| **音频** | `GET /api/audio/devices` | *（无）* | `200` 枚举宿主机可用播放与录音设备 `{"outputs": [...], "inputs": [...]}`。 |
| **音频** | `POST /api/audio` | 非空子集：`enabled`、`volume`（`0.0..1.0`）、`muted`、`outputDeviceId`、`bufferMs`（`>= 4`）、`backendPreference`（`auto`/`native`/`javax`/`silent`）、`headphonePlugged`、`inputMode`（`SILENCE`/`TEST_TONE`/`HOST_MIC`）、`inputDeviceId`、`inputGainDb`、`inputMuted`、`inputMonitor`、`testToneHz`、`extMicPlugged`。 | `200` 实时更新音频输出、麦克风测试音与 3.5mm 插孔插入状态。 |
| **无线** | `GET /api/wireless` | *（无）* | `200` BCM43438 无线状态（`deviceName`、`blePeerConnected`、`wifiPeerConnected`、`ptpIpBridgeEnabled`）。 |
| **无线** | `POST /api/wireless` | 非空子集：`deviceName`、`blePeerConnected`、`wifiPeerConnected`、`ptpIpBridgeEnabled`（仅 `relaxed` 模式允许开启 PTP-IP 桥接）。 | `200` 更新后的无线连接状态。 |
| **遥测** | `POST /api/telemetry/upload-log` | 可选查询参数 `?notes=...` 或 JSON `{"notes": "..."}`。 | 已启用遥测时返回 `200`（`uploaded: true`），禁用或离线时返回 `503`。 |
| **界面语言** | `GET /api/lang` | *（无）* | `200` `{"language": "en", "supported": ["en","zh_CN","ja_JP","de_DE","fr_FR","es_ES"]}`。 |
| **界面语言** | `POST /api/lang` | **严格单键** `language`（或 `lang`）：`en`、`zh_CN`、`ja_JP`、`de_DE`、`fr_FR`、`es_ES`。 | `200` 实时热切换桌面 UI 语言并自动持久化至用户配置。 |
| **外观主题** | `GET /api/theme` | *（无）* | `200` 当前激活的 `body` 与 `ui` 主题及可用主题列表（无头模式返回 `404`）。 |
| **外观主题** | `POST /api/theme` | 非空子集：`{"body": "<id>", "ui": "<id>"}`（机身内置：`origin`、`hasselblad-x2d2`、`hasselblad-907x`、`compact-rangefinder`；UI 内置：`origin`、`nordic-minimal`、`daylight-studio`、`vintage-brass`、`olive-field`）。 | `200` 实时切换相机机身面板与工作台样式表。 |
| **外观主题** | `POST /api/theme/reload` | 空请求体或 `{}`。 | `200` 重新扫描磁盘 `themes/body/` 与 `themes/ui/` 并实时刷新所有打开窗口。 |
| **NOR Flash** | `GET /api/flash` | *（无）* | `200` 64 MiB QSPI NOR Flash 状态（路径、`bootPath`、`bootRomPresent`、`sha256`、擦写计数）。 |
| **NOR Flash** | `POST /api/flash` | `{"action": "write-dat"\|"export"\|"import"\|"restore-factory", "path": "...", "preserveCalibration": true}`。 | `200` 执行在线刷写 `.DAT`、导出/导入镜像或恢复出厂 Flash（写操作会自动重载硬件并冷重启相机）。 |

*（另含内置文档端点 `GET /api/openapi.json` 与 `GET /api/docs`。）*

---

## 3. 实用 `curl` 与 Python 自动化脚本示例

以下示例默认连接 `http://127.0.0.1:8787`（`export FUJI_API=http://127.0.0.1:8787`）。

### 3.1 常用 `curl` 命令速查

```bash
export FUJI_API="http://127.0.0.1:8787"

# 1. 读取相机实时运行遥测（虚拟时钟节拍、当前屏幕 ID 等）
curl -s "$FUJI_API/api/v1/state" | jq '.machine'

# 2. 切换为静态照片模式、M 档手动曝光与 MF 手动对焦
curl -s -X POST "$FUJI_API/api/shooting-mode" \
  -H "Content-Type: application/json" \
  -d '{"mode": "STILL", "modeDial": "M", "focusMode": "MF"}'

# 3. 热挂载 GF 110mm F2 R LM WR 镜头，开启物理光学管线，设为 f/2.0、对焦 1.2m
curl -s -X POST "$FUJI_API/api/lens" \
  -H "Content-Type: application/json" \
  -d '{"template": "gf110", "opticsEnabled": true, "apertureRingMode": "MANUAL", "aperture": 2.0, "focusDistanceM": 1.2}'

# 4. 在滤镜架叠加：专业 MRC UV 镜 + ND8 + 1/4 黑柔 + 五角星散景遮罩
curl -s -X POST "$FUJI_API/api/filters" \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "slots": [
      {"type": "uv", "enabled": true, "preset": "PRO_MRC"},
      {"type": "nd", "enabled": true, "preset": "ND8", "stops": 3.0},
      {"type": "black-mist", "enabled": true, "preset": "1/4", "strength": 0.25},
      {"type": "bokeh-mask", "enabled": true, "shape": "STAR_5", "scale": 0.96}
    ]
  }'

# 5. 顺时针拨动后指令拨轮 3 个刻度
curl -s -X POST "$FUJI_API/api/adc" \
  -H "Content-Type: application/json" \
  -d '{"rearDialStep": 3}'

# 6. 热重载外部主题包，切换为哈苏 X2D II 机身 + 北欧极简 UI，并将界面切为简体中文
curl -s -X POST "$FUJI_API/api/theme/reload" -H "Content-Type: application/json" -d '{}'
curl -s -X POST "$FUJI_API/api/theme" -H "Content-Type: application/json" -d '{"body": "hasselblad-x2d2", "ui": "nordic-minimal"}'
curl -s -X POST "$FUJI_API/api/lang" -H "Content-Type: application/json" -d '{"language": "zh_CN"}'
```

### 3.2 Python 自动化脚本：带 `409` 乐观锁重试的两段式快门与自动对焦包围曝光

以下零第三方依赖的 Python 3 脚本演示了如何通过 `PATCH /api/v1/components/buttons` 处理 `409 Conflict` 修订号重试，模拟真实两段式快门（半按 `S1` -> 全按 `S2` 保持 650ms -> 释放）并自动完成 3 张不同对焦距离的景深合成包围拍摄：

```python
#!/usr/bin/env python3
"""通过 GFX100S 本地 HTTP 控制接口执行自动对焦距离包围拍摄（零外部依赖）。"""

import json
import time
import urllib.error
import urllib.request

BASE_URL = "http://127.0.0.1:8787"


def api_request(method: str, path: str, payload: dict | None = None) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"{BASE_URL}{path}", data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=5.0) as resp:
        return json.loads(resp.read().decode("utf-8"))


def patch_component(component_id: str, changes: dict, max_retries: int = 5) -> dict:
    """带 HTTP 409 乐观修订号冲突自动重试的组件更新辅助函数。"""
    for _ in range(max_retries):
        state = api_request("GET", "/api/v1/state")
        comp = next(c for c in state["components"] if c["id"] == component_id)
        try:
            return api_request(
                "PATCH",
                f"/api/v1/components/{component_id}",
                {"revision": comp["revision"], "changes": changes},
            )
        except urllib.error.HTTPError as err:
            if err.code == 409:
                continue
            raise
    raise RuntimeError(f"组件 {component_id} 乐观锁重试超过 {max_retries} 次")


def trigger_shutter(s1_lead_sec: float = 0.12, s2_hold_sec: float = 0.65) -> None:
    """标准两段式快门脉冲：S1 半按 -> S1+S2 全按保持 -> 完整松开。"""
    patch_component("buttons", {"shutterHalf": True, "shutterFull": False})
    time.sleep(s1_lead_sec)
    patch_component("buttons", {"shutterHalf": True, "shutterFull": True})
    time.sleep(s2_hold_sec)
    patch_component("buttons", {"shutterHalf": False, "shutterFull": False})


if __name__ == "__main__":
    # 设为静态拍摄 + A 档光圈优先 + MF 手动对焦，挂载 GF 63mm F2.8 全开光圈
    api_request("POST", "/api/shooting-mode", {"mode": "STILL", "modeDial": "A", "focusMode": "MF"})
    api_request("POST", "/api/lens", {"template": "gf63", "opticsEnabled": True, "aperture": 2.8})

    for distance_m in (0.8, 1.4, 2.5):
        lens_info = api_request("POST", "/api/lens", {"focusDistanceM": distance_m})
        print(f"[对焦] {lens_info['model']} -> {lens_info['focusDistanceM']} m")
        time.sleep(0.4)
        trigger_shutter()
        print(f"[快门] 已完成 {distance_m} m 拍摄，等待静态图像写入 SD 卡...")
        time.sleep(1.5)
```
