# FUJIFILM GFX100S Emulator HTTP Control API

> **Auto-generated** from `ControlApiSpec.kt` (`OpenAPI 3.1.0`, `43` endpoints). Do not hand-edit; regenerate with `./gradlew :generateHttpApiDocs`.

## Overview

- **Base URL**: `http://127.0.0.1:18080` (loopback `127.0.0.1` only; port set via `--control-port`).
- **Live Interactive Explorer**: `GET /api/docs` (offline single-file HTML) and `GET /api/openapi.json` (OpenAPI `3.1.0`).
- **Generated Artifacts**: [http-openapi.json](http-openapi.json) | [http-control-api.html](http-control-api.html)
- **Security & Limits**: Cross-origin `Origin` headers are rejected (`400`); JSON write routes require `Content-Type: application/json`; maximum request body size is `64 KiB` (`65536` bytes).

### Standard HTTP Status Codes

| Status | Meaning |
| --- | --- |
| `200` / `201` | Request succeeded (`201` for `POST /api/v1/dump`). |
| `400` | Invalid JSON, missing/extra keys, out-of-range value, wrong `Content-Type`, or cross-origin `Origin`. |
| `404` | Unknown route/method or optional component/handler not enabled in this session. |
| `409` | Optimistic-concurrency revision conflict on `PATCH /api/v1/components/{id}`. |
| `500` / `503` | Internal error or executor rejected / external upload unavailable. |

## Endpoint Index

| Category | Method | Path | Summary |
| --- | --- | --- | --- |
| Core & Catalog | `GET` | `/api/v1/catalog` | Query hardware address catalog |
| Core & Catalog | `GET` | `/api/v1/state` | Get live emulator and component state snapshot |
| Core & Catalog | `POST` | `/api/v1/dump` | Trigger a memory and state dump |
| Core & Catalog | `PATCH` | `/api/v1/components/{id}` | Patch live settings of a hardware component |
| Profiler | `POST` | `/api/profiler/start` | Start asynchronous performance profiler |
| Profiler | `POST` | `/api/profiler/stop` | Stop profiler and fetch report |
| Profiler | `GET` | `/api/profiler/report` | Read latest profiler report |
| Touch & Controls | `POST` | `/api/touch` | Send a rear-LCD touch event |
| Touch & Controls | `POST` | `/api/touch/swipe` | Send a multi-step rear-LCD touch swipe/flick |
| Touch & Controls | `GET` | `/api/eye-sensor` | Get EVF eye proximity sensor state |
| Touch & Controls | `POST` | `/api/eye-sensor` | Set EVF eye proximity sensor |
| Touch & Controls | `GET` | `/api/adc` | Inspect ADC buttons, dials, and channel samples |
| Touch & Controls | `POST` | `/api/adc` | Update ADC buttons, dials, or raw channel overrides |
| Touch & Controls | `GET` | `/api/focus-mode` | Get S/C/M focus mode lever state |
| Touch & Controls | `POST` | `/api/focus-mode` | Set S/C/M focus mode lever |
| Touch & Controls | `GET` | `/api/shooting-mode` | Get combined STILL/MOVIE, mode dial, and focus mode state |
| Touch & Controls | `POST` | `/api/shooting-mode` | Update STILL/MOVIE switch, mode dial, and/or focus mode |
| Touch & Controls | `POST` | `/api/view-mode` | Press or release the VIEW MODE button |
| Optics & Filters | `GET` | `/api/lens` | Get mounted lens optical and electronic state |
| Optics & Filters | `POST` | `/api/lens` | Update mounted lens profile or optical parameters |
| Optics & Filters | `GET` | `/api/filters` | Get optical filter stack state |
| Optics & Filters | `POST` | `/api/filters` | Configure optical filter stack |
| Optics & Filters | `GET` | `/api/nd` | Get ND filter transmittance and stop metrics |
| Optics & Filters | `POST` | `/api/nd` | Configure ND filter stops or preset |
| Audio | `GET` | `/api/audio` | Get audio output and input mixer state |
| Audio | `GET` | `/api/audio/devices` | List host audio output and input devices |
| Audio | `POST` | `/api/audio` | Update audio output, input, and jack settings |
| Wireless | `GET` | `/api/wireless` | Get BCM43438 Wi-Fi / BLE wireless state |
| Wireless | `POST` | `/api/wireless` | Update BCM43438 virtual wireless peer state |
| Power & Battery | `GET` | `/api/battery` | Get NP-W235 battery pack and USB-C charging state |
| Power & Battery | `POST` | `/api/battery` | Update NP-W235 battery level, charging, or USB-C external power |
| Power & Battery | `GET` | `/api/power` | Get power and battery state (alias of GET /api/battery) |
| Power & Battery | `POST` | `/api/power` | Update power and battery state (alias of POST /api/battery) |
| Telemetry | `POST` | `/api/telemetry/upload-log` | Upload current diagnostic session log |
| UI & Personalization | `GET` | `/api/lang` | Get active UI language and supported locales |
| UI & Personalization | `POST` | `/api/lang` | Switch desktop UI language live |
| UI & Personalization | `GET` | `/api/theme` | Get active camera body and UI studio theme |
| UI & Personalization | `POST` | `/api/theme` | Switch camera body and/or studio UI theme |
| UI & Personalization | `POST` | `/api/theme/reload` | Hot-reload external camera body and UI themes |
| NOR Flash | `GET` | `/api/flash` | Get unified 64 MiB QSPI NOR Flash status |
| NOR Flash | `POST` | `/api/flash` | Flash .DAT firmware, export/import raw NOR image, or restore factory Flash |
| Documentation | `GET` | `/api/openapi.json` | Get OpenAPI 3.1.0 specification |
| Documentation | `GET` | `/api/docs` | Open offline interactive HTTP API Explorer |

## Categories

- [Core & Catalog](#core-catalog) (4) — Live emulator state snapshots, component patching, memory dumps, and hardware address dictionary queries.
- [Profiler](#profiler) (3) — Asynchronous CPU/instruction profiler start, stop, and JSON or plain-text reports.
- [Touch & Controls](#touch-controls) (11) — Rear LCD touch taps/swipes, EVF eye sensor, ADC buttons/dials, focus mode, shooting mode, and VIEW MODE.
- [Optics & Filters](#optics-filters) (6) — Mounted lens control, multi-slot optical filter rack (UV, ND, Bokeh Mask, Star, Mist, GND), and ND shorthand.
- [Audio](#audio) (3) — Host audio output/input mixer, device enumeration, headphone/mic jack simulation, and loopback monitoring.
- [Wireless](#wireless) (2) — BCM43438 Wi-Fi / BLE combo radio status and virtual mobile peer / PTP-IP bridge controls.
- [Power & Battery](#power-battery) (4) — NP-W235 battery pack state-of-charge, pack voltage, USB-C VBUS external power, and charging controls.
- [Telemetry](#telemetry) (1) — Manual diagnostic session log upload.
- [UI & Personalization](#ui-personalization) (5) — Runtime UI locale switching and camera body / studio theme customization.
- [NOR Flash](#nor-flash) (2) — Unified 64 MiB QSPI NOR Flash status inspection, .DAT firmware flashing, raw export/import, and factory restore.
- [Documentation](#documentation) (2) — Self-hosted OpenAPI 3.1.0 specification and offline interactive HTML API explorer.

## Core & Catalog

Live emulator state snapshots, component patching, memory dumps, and hardware address dictionary queries.

### `GET /api/v1/catalog`

**Query hardware address catalog** — Queries the bundled GFX100S 2.13 MMIO/memory address dictionary using up to 16 distinct query parameters.

**Parameters**

| Name | In | Type | Required | Description |
| --- | --- | --- | --- | --- |
| `address` | `query` | `string` | no | Hex or decimal MMIO/memory address to look up (e.g. `0xfff40000`). |
| `subsystem` | `query` | `string` | no | Optional subsystem filter (e.g. `isp`, `dma`, `gpio`). |
| `query` | `query` | `string` | no | Optional free-text search term across device/region names and notes. |

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Matching address catalog entries. |
| `400` | `application/json` | Duplicate, malformed, or excessive (>16) query parameters. |
| `500` | `application/json` | Address dictionary unavailable. |

**Success Response Example (`200`)**

```json
{
  "count" : 1,
  "entries" : [ {
    "name" : "soc-isp.core",
    "start" : "0xfff70000",
    "end" : "0xfff702ec",
    "basis" : "DAT"
  } ]
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/v1/catalog?address=0xfff40000"
```

### `GET /api/v1/state`

**Get live emulator and component state snapshot** — Returns the complete component roster (id, revision, config, state) merged with the live `machine` execution telemetry map.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current component snapshot and machine telemetry. |

**Success Response Example (`200`)**

```json
{
  "components" : [ {
    "id" : "buttons",
    "revision" : 1,
    "config" : {
      "power" : true,
      "movieMode" : false
    },
    "state" : {
      "power" : true,
      "movieMode" : false
    }
  } ],
  "machine" : {
    "status" : "running",
    "tick" : 125000000,
    "screenId" : "0x0100"
  }
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/v1/state"
```

### `POST /api/v1/dump`

**Trigger a memory and state dump** — Captures an emulator state/memory dump archive via the runtime dump handler. Body must be empty or `{}`.

**Request Example**

```json
{ }
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `201` | `application/json` | Dump created successfully. |
| `400` | `application/json` | Non-empty JSON object or missing `application/json` Content-Type. |
| `500` | `application/json` | Dump handler unavailable. |

**Success Response Example (`201`)**

```json
{
  "dump" : "dumps/dump-20261125-120000"
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/v1/dump" \
  -H "Content-Type: application/json" \
  -d '{}'
```

### `PATCH /api/v1/components/{id}`

**Patch live settings of a hardware component** — Applies a live settings patch to component `{id}` with optimistic concurrency control using its current `revision` from `GET /api/v1/state`.

**Parameters**

| Name | In | Type | Required | Description |
| --- | --- | --- | --- | --- |
| `id` | `path` | `string` | yes | Component identifier matching `[a-z][a-z0-9-]{0,63}` (e.g. `buttons`, `cmos`, `soc-adc`). |

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `revision` | `integer` | yes | Expected non-negative component revision. (range: `0..`) |
| `changes` | `object` | yes | Key-value map of live component settings to update. |

**Request Example**

```json
{
  "revision" : 1,
  "changes" : {
    "menu" : true
  }
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Updated component descriptor with incremented revision. |
| `400` | `application/json` | Malformed payload, invalid setting key/value, or wrong Content-Type. |
| `409` | `application/json` | Revision mismatch (stale revision). |

**Success Response Example (`200`)**

```json
{
  "id" : "buttons",
  "revision" : 2,
  "state" : {
    "menu" : true
  }
}
```

**curl Example**

```bash
curl -s -X PATCH "http://127.0.0.1:18080/api/v1/components/buttons" \
  -H "Content-Type: application/json" \
  -d '{"revision":1,"changes":{"menu":true}}'
```

## Profiler

Asynchronous CPU/instruction profiler start, stop, and JSON or plain-text reports.

### `POST /api/profiler/start`

**Start asynchronous performance profiler** — Starts sampling CPU/PC hot spots and subsystem timings. `intervalMs` may be provided as a query parameter or JSON body field (body overrides query).

**Parameters**

| Name | In | Type | Required | Description |
| --- | --- | --- | --- | --- |
| `intervalMs` | `query` | `integer` | no | Sampling interval in milliseconds (defaults to profiler default). (range: `1..`) |

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `intervalMs` | `integer` | no | Optional sampling interval in milliseconds. (range: `1..`) |

**Request Example**

```json
{
  "intervalMs" : 5
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Profiler started. |
| `400` | `application/json` | Invalid `intervalMs` or non-JSON body. |
| `404` | `application/json` | Profiler not attached to this session. |

**Success Response Example (`200`)**

```json
{
  "running" : true,
  "intervalMs" : 5
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/profiler/start" \
  -H "Content-Type: application/json" \
  -d '{"intervalMs":5}'
```

### `POST /api/profiler/stop`

**Stop profiler and fetch report** — Stops active profiling and returns the collected report as JSON, or as `text/plain` when `?format=text|txt` or `Accept: text/plain` is sent.

**Parameters**

| Name | In | Type | Required | Description |
| --- | --- | --- | --- | --- |
| `format` | `query` | `string` | no | Response format override (`json`, `text`, or `txt`). (values: `json`, `text`, `txt`) |

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Profiler stopped with summary report (JSON or plain text). |
| `404` | `application/json` | Profiler not attached to this session. |

**Success Response Example (`200`)**

```json
{
  "running" : false,
  "samples" : 1240,
  "elapsedMs" : 6200
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/profiler/stop"
```

### `GET /api/profiler/report`

**Read latest profiler report** — Returns the current or last completed profiler report without stopping a running session. Supports `text/plain` via `?format=text|txt` or `Accept: text/plain`.

**Parameters**

| Name | In | Type | Required | Description |
| --- | --- | --- | --- | --- |
| `format` | `query` | `string` | no | Response format override (`json`, `text`, or `txt`). (values: `json`, `text`, `txt`) |

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Latest profiler report. |
| `404` | `application/json` | Profiler not attached to this session. |

**Success Response Example (`200`)**

```json
{
  "running" : true,
  "samples" : 610,
  "elapsedMs" : 3050
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/profiler/report?format=json"
```

## Touch & Controls

Rear LCD touch taps/swipes, EVF eye sensor, ADC buttons/dials, focus mode, shooting mode, and VIEW MODE.

### `POST /api/touch`

**Send a rear-LCD touch event** — Injects a single capacitive touch down/move/up event onto the rear LCD (`rear-touch-ic` preferred, falling back to `i2c-bus`). Payload must contain exact keys `x`, `y`, `pressed`.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `x` | `integer` | yes | Horizontal touch coordinate (`0..65535`; rear LCD native range `0..1023`). (range: `0..65535`) |
| `y` | `integer` | yes | Vertical touch coordinate (`0..65535`; rear LCD native range `0..767`). (range: `0..65535`) |
| `pressed` | `boolean` | yes | `true` for finger down/move, `false` for finger release. |

**Request Example**

```json
{
  "x" : 512,
  "y" : 384,
  "pressed" : true
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Touch event applied. |
| `400` | `application/json` | Missing/extra keys or out-of-range coordinates. |
| `404` | `application/json` | No touch controller component available. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "rear-touch-ic",
  "x" : 512,
  "y" : 384,
  "pressed" : true,
  "revision" : 4
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/touch" \
  -H "Content-Type: application/json" \
  -d '{"x":512,"y":384,"pressed":true}'
```

### `POST /api/touch/swipe`

**Send a multi-step rear-LCD touch swipe/flick** — Executes a calibrated multi-step capacitive swipe gesture. Provide either a cardinal `direction` (`up`/`down`/`left`/`right`) OR explicit `x1`/`y1`/`x2`/`y2` endpoints (never both). Default `4` steps x `20 ms` satisfies both LiveView T-Fn flicks (<260 ms) and Playback slide-commit (>341 px).

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `direction` | `string` | no | Preset swipe direction (mutually exclusive with `x1`/`y1`/`x2`/`y2`). (values: `up`, `down`, `left`, `right`) |
| `x1` | `integer` | no | Start X coordinate (`0..65535`). Required when `direction` is omitted. (range: `0..65535`) |
| `y1` | `integer` | no | Start Y coordinate (`0..65535`). Required when `direction` is omitted. (range: `0..65535`) |
| `x2` | `integer` | no | End X coordinate (`0..65535`). Required when `direction` is omitted. (range: `0..65535`) |
| `y2` | `integer` | no | End Y coordinate (`0..65535`). Required when `direction` is omitted. (range: `0..65535`) |
| `steps` | `integer` | no | Number of intermediate move steps (`1..8`, default `4`). (range: `1..8`) |
| `stepMs` | `integer` | no | Per-step dwell in milliseconds (`5..100`, default `20`). (range: `5..100`) |

**Request Example**

```json
{
  "direction" : "left",
  "steps" : 4,
  "stepMs" : 20
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Swipe gesture completed. |
| `400` | `application/json` | Invalid direction, mixed direction+coordinates, or out-of-range steps/stepMs. |
| `404` | `application/json` | No touch controller component available. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "rear-touch-ic",
  "direction" : "left",
  "x1" : 760,
  "y1" : 384,
  "x2" : 240,
  "y2" : 384,
  "steps" : 4,
  "stepMs" : 20,
  "revision" : 10
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/touch/swipe" \
  -H "Content-Type: application/json" \
  -d '{"direction":"left","steps":4,"stepMs":20}'
```

### `GET /api/eye-sensor`

**Get EVF eye proximity sensor state** — Inspects the TMD2772 (or `i2c-bus` fallback) eye proximity state along with active viewfinder detection (`LCD`, `EVF`, `BOTH`, or `WAITING`) and display frame sequences.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current eye sensor and viewfinder routing state. |
| `404` | `application/json` | Eye sensor component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "tmd2772",
  "near" : false,
  "activeViewfinder" : "LCD",
  "lcdSequence" : 42,
  "evfSequence" : 0,
  "lcdOutputActive" : true,
  "evfOutputActive" : false
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/eye-sensor"
```

### `POST /api/eye-sensor`

**Set EVF eye proximity sensor** — Sets whether an eye is detected near the EVF (`near: true` switches automatic viewfinder routing to EVF; `false` returns to rear LCD).

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `near` | `boolean` | yes | `true` for eye close to EVF, `false` for eye away. |

**Request Example**

```json
{
  "near" : true
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Eye sensor updated. |
| `400` | `application/json` | Payload missing exact `near` boolean key. |
| `404` | `application/json` | Eye sensor component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "tmd2772",
  "near" : true,
  "revision" : 2
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/eye-sensor" \
  -H "Content-Type: application/json" \
  -d '{"near":true}'
```

### `GET /api/adc`

**Inspect ADC buttons, dials, and channel samples** — Returns live `soc-adc` control states (`qButton`, `aelButton`, `modeDial`, `fn1Button`..`fn3Button`, `lightButton`, rear command dial, and channel overrides) plus sampled ADC register counts.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current `soc-adc` control values and sample counters. |
| `404` | `application/json` | `soc-adc` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "soc-adc",
  "revision" : 3,
  "controls" : {
    "qButton" : false,
    "aelButton" : false,
    "modeDial" : "default",
    "fn1Button" : false,
    "fn2Button" : false,
    "fn3Button" : false,
    "lightButton" : false,
    "rearDialPosition" : -1,
    "rearDialDetents" : 0
  },
  "eventCount" : 12,
  "sampleReadCount" : 420
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/adc"
```

### `POST /api/adc`

**Update ADC buttons, dials, or raw channel overrides** — Updates a non-empty subset of `soc-adc` inputs, including momentary buttons, mode dial, relative `rearDialStep` detent increments, or raw 12-bit channel overrides (`-1` clears override).

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `qButton` | `boolean` | no | Press/release the Q (Quick Menu) button. |
| `aelButton` | `boolean` | no | Press/release the AE-L button. |
| `fn1Button` | `boolean` | no | Press/release the Fn1 / Exposure Comp (`+/-`) button. |
| `fn2Button` | `boolean` | no | Press/release the Fn2 button. |
| `fn3Button` | `boolean` | no | Press/release the Fn3 button. |
| `lightButton` | `boolean` | no | Press/release the top LCD backlight button. |
| `modeDial` | `string` | no | Mode dial position (e.g. `default`, `P`, `S`, `A`, `M`, `C1`..`C6`). |
| `rearDialPosition` | `integer` | no | Explicit rear dial quadrature phase (`-1..3`, `-1` uses detents). (range: `-1..3`) |
| `rearDialDetents` | `integer` | no | Absolute rear command dial detent counter (`-10000..10000`). (range: `-10000..10000`) |
| `rearDialStep` | `integer` | no | Relative rear command dial detent delta (`-1000..1000`). (range: `-1000..1000`) |
| `ch3Override` | `integer` | no | Raw 12-bit ADC CH3 override (`-1..4095`). (range: `-1..4095`) |
| `ch5Override` | `integer` | no | Raw 12-bit ADC CH5 override (`-1..4095`). (range: `-1..4095`) |
| `ch6Override` | `integer` | no | Raw 12-bit ADC CH6 override (`-1..4095`). (range: `-1..4095`) |
| `ch7Override` | `integer` | no | Raw 12-bit ADC CH7 override (`-1..4095`). (range: `-1..4095`) |
| `ch11Override` | `integer` | no | Raw 12-bit ADC CH11 override (`-1..4095`). (range: `-1..4095`) |

**Request Example**

```json
{
  "qButton" : true,
  "modeDial" : "M"
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | ADC settings patched. |
| `400` | `application/json` | Empty payload, unknown key, wrong type, or out-of-range integer. |
| `404` | `application/json` | `soc-adc` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "soc-adc",
  "revision" : 4,
  "changes" : {
    "qButton" : true,
    "modeDial" : "M"
  }
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/adc" \
  -H "Content-Type: application/json" \
  -d '{"qButton":true,"modeDial":"M"}'
```

### `GET /api/focus-mode`

**Get S/C/M focus mode lever state** — Inspects the `soc-gpio` focus mode selector (`focusMode` configuration and resolved `effectiveFocusMode`).

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current focus mode lever state. |
| `404` | `application/json` | `soc-gpio` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "soc-gpio",
  "revision" : 2,
  "focusMode" : "default",
  "effectiveFocusMode" : "AF-S"
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/focus-mode"
```

### `POST /api/focus-mode`

**Set S/C/M focus mode lever** — Sets the physical S/C/M focus mode lever via exact key `focusMode` or `mode`. Accepts `AF-S` (`AFS`/`S`), `AF-C` (`AFC`/`C`), `MF` (`M`), or `default`.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `focusMode` | `string` | no | Target focus mode (provide either `focusMode` or `mode`). (values: `AF-S`, `AF-C`, `MF`, `default`) |
| `mode` | `string` | no | Alias for `focusMode`. (values: `AF-S`, `AF-C`, `MF`, `default`) |

**Request Example**

```json
{
  "focusMode" : "MF"
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Focus mode updated. |
| `400` | `application/json` | Invalid key or unsupported focus mode value. |
| `404` | `application/json` | `soc-gpio` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "soc-gpio",
  "revision" : 3,
  "focusMode" : "MF",
  "effectiveFocusMode" : "MF"
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/focus-mode" \
  -H "Content-Type: application/json" \
  -d '{"focusMode":"MF"}'
```

### `GET /api/shooting-mode`

**Get combined STILL/MOVIE, mode dial, and focus mode state** — Reads the `buttons` STILL/MOVIE lever (`movieMode`), `soc-adc` `modeDial`, and `soc-gpio` `effectiveFocusMode` in a single request.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Combined shooting mode summary. |
| `404` | `application/json` | `buttons` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "mode" : "STILL",
  "movieMode" : false,
  "modeDial" : "default",
  "focusMode" : "AF-S"
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/shooting-mode"
```

### `POST /api/shooting-mode`

**Update STILL/MOVIE switch, mode dial, and/or focus mode** — Updates one or more of `mode` (`STILL`/`MOVIE`/`PHOTO`/`VIDEO`), `movieMode` (boolean), `modeDial`, and `focusMode`.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `mode` | `string` | no | Shooting mode switch (`STILL`, `PHOTO`, `MOVIE`, or `VIDEO`). (values: `STILL`, `MOVIE`, `PHOTO`, `VIDEO`) |
| `movieMode` | `boolean` | no | Boolean equivalent of `mode` (`true` = MOVIE, `false` = STILL). |
| `modeDial` | `string` | no | Exposure mode dial position (e.g. `P`, `S`, `A`, `M`, `C1`..`C6`). |
| `focusMode` | `string` | no | Focus lever position (`AF-S`, `AF-C`, `MF`, or `default`). (values: `AF-S`, `AF-C`, `MF`, `default`) |

**Request Example**

```json
{
  "mode" : "MOVIE",
  "modeDial" : "M",
  "focusMode" : "AF-C"
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Updated shooting mode summary with applied `changes`. |
| `400` | `application/json` | Empty payload or invalid field value. |
| `404` | `application/json` | `buttons` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "mode" : "MOVIE",
  "movieMode" : true,
  "modeDial" : "M",
  "focusMode" : "AF-C",
  "changes" : {
    "movieMode" : true,
    "mode" : "MOVIE",
    "modeDial" : "M",
    "focusMode" : "AF-C"
  }
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/shooting-mode" \
  -H "Content-Type: application/json" \
  -d '{"mode":"MOVIE","modeDial":"M","focusMode":"AF-C"}'
```

### `POST /api/view-mode`

**Press or release the VIEW MODE button** — Asserts or releases the hardware VIEW MODE GPIO button (`pressed: true` / `false`) to cycle LCD/EVF viewfinder modes.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `pressed` | `boolean` | yes | `true` to press VIEW MODE, `false` to release. |

**Request Example**

```json
{
  "pressed" : true
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | VIEW MODE button state updated. |
| `400` | `application/json` | Payload missing exact `pressed` boolean key. |
| `404` | `application/json` | VIEW MODE GPIO target unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "pressed" : true
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/view-mode" \
  -H "Content-Type: application/json" \
  -d '{"pressed":true}'
```

## Optics & Filters

Mounted lens control, multi-slot optical filter rack (UV, ND, Bokeh Mask, Star, Mist, GND), and ND shorthand.

### `GET /api/lens`

**Get mounted lens optical and electronic state** — Inspects the currently mounted lens template, focal length, aperture/iris sync, focus distance, OIS/optics flags, and mount contact protocol.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current mounted lens state. |
| `404` | `application/json` | `lens` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "lens",
  "revision" : 2,
  "template" : "gf50-f35",
  "model" : "GF50mmF3.5 R LM WR",
  "communicationMode" : "SIMULATED",
  "electronicContacts" : true,
  "apertureRingMode" : "A",
  "aperture" : 3.5,
  "focalLengthMm" : 50.0,
  "focusDistanceM" : 2.0,
  "oisEnabled" : false,
  "opticsEnabled" : true,
  "mountAttached" : true
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/lens"
```

### `POST /api/lens`

**Update mounted lens profile or optical parameters** — Swaps the mounted lens template/adapter or adjusts live optical controls such as focal length, aperture ring mode (`A`/`C`/`MANUAL`), aperture, focus distance, OIS, and optics simulation.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `template` | `string` | no | Lens module ID (e.g. `gf50-f35`, `gf32-64-f4`, `gf110-f2`, `gf45-100-f4`). |
| `adapterId` | `string` | no | Mount adapter ID for adapted lenses (e.g. `fringer-ef-gfx`, `m42-gfx`). |
| `communicationMode` | `string` | no | Lens communication mode (`SIMULATED`, `MANUAL`, or `REAL_FIRMWARE`). |
| `realFirmwareFile` | `string` | no | Path to real lens firmware file when using `REAL_FIRMWARE` mode. |
| `apertureRingMode` | `string` | no | Aperture ring position (`A`, `C`, or `MANUAL`). (values: `A`, `C`, `MANUAL`) |
| `aperture` | `number` | no | Manual aperture f-number (e.g. `2.0`, `3.5`, `8.0`). |
| `focalLengthMm` | `number` | no | Zoom focal length in millimeters. |
| `focusDistanceM` | `number` | no | Focus distance in meters. |
| `subjectDistanceM` | `number` | no | Optical scene subject distance in meters. |
| `focusMode` | `string` | no | Lens focus mode setting. |
| `oisEnabled` | `boolean` | no | Enable/disable optical image stabilization (OIS). |
| `opticsEnabled` | `boolean` | no | Enable/disable physically based lens optics pipeline. |
| `electronicContacts` | `boolean` | no | Enable/disable electronic lens mount contacts. |
| `afTrigger` | `boolean` | no | Trigger an autofocus step. |

**Request Example**

```json
{
  "apertureRingMode" : "MANUAL",
  "aperture" : 4.0,
  "focusDistanceM" : 1.5
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Updated lens state. |
| `400` | `application/json` | Unknown lens key, null value, or invalid `apertureRingMode`. |
| `404` | `application/json` | `lens` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "lens",
  "revision" : 3,
  "apertureRingMode" : "MANUAL",
  "aperture" : 4.0,
  "focusDistanceM" : 1.5
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/lens" \
  -H "Content-Type: application/json" \
  -d '{"apertureRingMode":"MANUAL","aperture":4.0,"focusDistanceM":1.5}'
```

### `GET /api/filters`

**Get optical filter stack state** — Inspects the `optical-filter` component configuration and computed state across the ordered multi-slot rack (`slots`) as well as UV, ND, Bokeh Mask, Star, Black Mist, and Graduated ND stages.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current optical filter stack state. |
| `404` | `application/json` | `optical-filter` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "optical-filter",
  "revision" : 1,
  "enabled" : true,
  "slots" : "",
  "ndPreset" : "OFF",
  "ndStops" : 0.0,
  "uvPreset" : "OFF",
  "bokehMask" : "OFF",
  "starEnabled" : false,
  "mistEnabled" : false,
  "gndEnabled" : false
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/filters"
```

### `POST /api/filters`

**Configure optical filter stack** — Configures the `optical-filter` stack using `clear`/`off`, an ordered `slots` array (up to 4 stacked filters), flat settings (`ndPreset`, `ndStops`, `uvPreset`, `bokehMask`, `star*`, `mist*`, `gnd*`), or nested shorthand objects (`uv`, `nd`, `bokeh`, `star`, `mist`, `gnd`).

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `clear` | `boolean` | no | Reset all filter slots and stages to OFF. |
| `off` | `boolean` | no | Alias for `clear`. |
| `enabled` | `boolean` | no | Master optical filter bypass switch. |
| `slots` | `array` | no | Ordered multi-slot rack array (max 4 filter objects with `type`) or encoded string. |
| `ndPreset` | `string` | no | ND preset ID (`OFF`, `ND2`, `ND4`, `ND8`, `ND16`, `ND32`, `ND64`, `ND1000`, `VND`). |
| `ndStops` | `number` | no | ND attenuation in EV stops (`0.0..10.0`). (range: `0.0..10.0`) |
| `uvPreset` | `string` | no | UV protector preset (`OFF`, `MULTI_COATED`, `UNCOATED`, `WARMING`, `HAZE`, `CUSTOM`). |
| `bokehMask` | `string` | no | Custom aperture bokeh mask shape (`OFF`, `HEART`, `STAR`, `HEXAGON`, `CAT_EYE`, `ANAMORPHIC`). |
| `starEnabled` | `boolean` | no | Enable diffraction star filter. |
| `mistEnabled` | `boolean` | no | Enable black mist / diffusion glow filter. |
| `gndEnabled` | `boolean` | no | Enable graduated neutral-density filter. |
| `uv` | `object` | no | Nested UV shorthand object (`preset`, `transmittance`, `veilingGlare`, `ghostIntensity`, `softness`, `tintR/G/B`) or preset string. |
| `nd` | `object` | no | Nested ND shorthand object (`preset`, `stops`). |
| `bokeh` | `object` | no | Nested bokeh shorthand object (`shape`/`mask`, `angleDeg`, `scale`). |
| `star` | `object` | no | Nested star shorthand object (`enabled`, `points`, `strength`, `threshold`, `angleDeg`, `length`). |
| `mist` | `object` | no | Nested mist shorthand object (`enabled`, `preset`, `strength`, `threshold`). |
| `gnd` | `object` | no | Nested GND shorthand object (`enabled`, `stops`, `horizon`, `feather`, `angleDeg`). |

**Request Example**

```json
{
  "enabled" : true,
  "nd" : {
    "preset" : "ND8"
  },
  "mist" : {
    "enabled" : true,
    "strength" : 0.35
  }
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Updated optical filter stack state. |
| `400` | `application/json` | No valid filter setting or unknown preset/shape. |
| `404` | `application/json` | `optical-filter` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "optical-filter",
  "revision" : 2,
  "enabled" : true,
  "ndPreset" : "ND8",
  "ndStops" : 3.0,
  "mistEnabled" : true,
  "mistStrength" : 0.35
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/filters" \
  -H "Content-Type: application/json" \
  -d '{"enabled":true,"nd":{"preset":"ND8"},"mist":{"enabled":true,"strength":0.35}}'
```

### `GET /api/nd`

**Get ND filter transmittance and stop metrics** — Returns dedicated neutral-density (ND) filter telemetry including stops, effective stops, filter factor, optical density, transmittance percentage, and human-readable summary.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current ND filter optical metrics. |
| `404` | `application/json` | `optical-filter` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "optical-filter",
  "revision" : 2,
  "enabled" : true,
  "preset" : "ND8",
  "stops" : 3.0,
  "effectiveStops" : 3.0,
  "factor" : 8.0,
  "opticalDensity" : 0.903,
  "transmittance" : 0.125,
  "transmittancePercent" : 12.5,
  "summary" : "ND8 (-3.0 EV)"
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/nd"
```

### `POST /api/nd`

**Configure ND filter stops or preset** — Shorthand endpoint for setting the neutral-density filter by preset (`preset`/`ndPreset`), explicit EV stops (`stops`/`ndStops` in `0.0..10.0`), `enabled`, or `off: true`.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `off` | `boolean` | no | Set to `true` to clear ND filter to `OFF` (0 stops). |
| `preset` | `string` | no | ND preset name (e.g. `ND2`, `ND4`, `ND8`, `ND64`, `ND1000`, `VND`). |
| `ndPreset` | `string` | no | Alias for `preset`. |
| `stops` | `number` | no | ND density in EV stops (`0.0..10.0`). (range: `0.0..10.0`) |
| `ndStops` | `number` | no | Alias for `stops`. (range: `0.0..10.0`) |
| `enabled` | `boolean` | no | Enable or bypass optical filter processing. |

**Request Example**

```json
{
  "preset" : "ND8"
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Updated ND filter metrics. |
| `400` | `application/json` | Missing parameters, unknown preset, or out-of-range stops. |
| `404` | `application/json` | `optical-filter` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "optical-filter",
  "revision" : 3,
  "enabled" : true,
  "preset" : "ND8",
  "stops" : 3.0,
  "effectiveStops" : 3.0
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/nd" \
  -H "Content-Type: application/json" \
  -d '{"preset":"ND8"}'
```

## Audio

Host audio output/input mixer, device enumeration, headphone/mic jack simulation, and loopback monitoring.

### `GET /api/audio`

**Get audio output and input mixer state** — Returns live WM8962 + host audio engine state, volume, mute, backend, buffer size, headphone/mic jack state, and input capture settings.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current audio subsystem status. |
| `404` | `application/json` | Audio engine unavailable. |

**Success Response Example (`200`)**

```json
{
  "enabled" : true,
  "volume" : 0.8,
  "muted" : false,
  "bufferMs" : 24,
  "backendPreference" : "auto",
  "headphonePlugged" : false,
  "inputMode" : "SILENCE",
  "extMicPlugged" : false
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/audio"
```

### `GET /api/audio/devices`

**List host audio output and input devices** — Enumerates host playback and capture devices available to the emulator audio bridge.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Available host audio devices. |
| `404` | `application/json` | Audio device enumeration unavailable. |

**Success Response Example (`200`)**

```json
{
  "outputs" : [ {
    "id" : "default",
    "name" : "System Default"
  } ],
  "inputs" : [ {
    "id" : "default",
    "name" : "System Default"
  } ]
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/audio/devices"
```

### `POST /api/audio`

**Update audio output, input, and jack settings** — Applies a non-empty subset of live host audio mixer and jack-detection settings.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `enabled` | `boolean` | no | Enable or disable host audio playback. |
| `volume` | `number` | no | Master output volume scalar (`0.0..1.0`). (range: `0.0..1.0`) |
| `muted` | `boolean` | no | Mute output playback. |
| `outputDeviceId` | `string` | no | Host playback device ID from `GET /api/audio/devices`. |
| `bufferMs` | `integer` | no | Audio ring buffer latency target in milliseconds. (range: `4..`) |
| `backendPreference` | `string` | no | Preferred audio backend (`auto`, `native`, `javax`, `silent`). |
| `headphonePlugged` | `boolean` | no | Simulate 3.5 mm headphone jack insertion. |
| `inputMode` | `string` | no | Microphone input source mode (e.g. `SILENCE`, `TEST_TONE`, `HOST_MIC`). |
| `inputDeviceId` | `string` | no | Host capture device ID from `GET /api/audio/devices`. |
| `inputGainDb` | `number` | no | Input microphone gain in dB. |
| `inputMuted` | `boolean` | no | Mute microphone capture. |
| `inputMonitor` | `boolean` | no | Enable live input-to-output monitoring. |
| `testToneHz` | `number` | no | Frequency in Hz when `inputMode` is `TEST_TONE`. |
| `extMicPlugged` | `boolean` | no | Simulate 3.5 mm external microphone jack insertion. |

**Request Example**

```json
{
  "volume" : 0.75,
  "muted" : false,
  "headphonePlugged" : true
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Updated audio subsystem state. |
| `400` | `application/json` | Empty body or unknown audio setting key. |
| `404` | `application/json` | Audio engine unavailable. |

**Success Response Example (`200`)**

```json
{
  "enabled" : true,
  "volume" : 0.75,
  "muted" : false,
  "headphonePlugged" : true
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/audio" \
  -H "Content-Type: application/json" \
  -d '{"volume":0.75,"muted":false,"headphonePlugged":true}'
```

## Wireless

BCM43438 Wi-Fi / BLE combo radio status and virtual mobile peer / PTP-IP bridge controls.

### `GET /api/wireless`

**Get BCM43438 Wi-Fi / BLE wireless state** — Returns the current BCM43438 wireless combo state (firmware download progress, BLE peer connection, Wi-Fi STA/AP state, and PTP-IP bridge status).

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | BCM43438 wireless status. |
| `404` | `application/json` | `bcm43438` wireless component unavailable. |

**Success Response Example (`200`)**

```json
{
  "deviceName" : "GFX100S-EMU",
  "blePeerConnected" : false,
  "wifiPeerConnected" : false,
  "ptpIpBridgeEnabled" : false
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/wireless"
```

### `POST /api/wireless`

**Update BCM43438 virtual wireless peer state** — Controls virtual mobile BLE/Wi-Fi peer attachment, advertised camera device name, or host PTP-IP bridge enablement.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `deviceName` | `string` | no | Advertised camera wireless name. |
| `blePeerConnected` | `boolean` | no | Connect or disconnect the virtual BLE mobile peer. |
| `wifiPeerConnected` | `boolean` | no | Connect or disconnect the virtual Wi-Fi peer. |
| `ptpIpBridgeEnabled` | `boolean` | no | Enable or disable the host PTP-IP socket bridge. |

**Request Example**

```json
{
  "blePeerConnected" : true,
  "ptpIpBridgeEnabled" : true
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Updated BCM43438 wireless state. |
| `400` | `application/json` | No recognized wireless keys provided. |
| `404` | `application/json` | `bcm43438` wireless component unavailable. |

**Success Response Example (`200`)**

```json
{
  "deviceName" : "GFX100S-EMU",
  "blePeerConnected" : true,
  "wifiPeerConnected" : false,
  "ptpIpBridgeEnabled" : true
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/wireless" \
  -H "Content-Type: application/json" \
  -d '{"blePeerConnected":true,"ptpIpBridgeEnabled":true}'
```

## Power & Battery

NP-W235 battery pack state-of-charge, pack voltage, USB-C VBUS external power, and charging controls.

### `GET /api/battery`

**Get NP-W235 battery pack and USB-C charging state** — Returns live NP-W235 battery installation state, charge percentage, terminal voltage in millivolts, charging flag, USB-C external power status, and MAX17049 `CRATE` drift rate.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current battery and USB-C power state. |
| `404` | `application/json` | `battery` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "battery",
  "revision" : 1,
  "present" : true,
  "chargePercent" : 78.0,
  "voltageMillivolts" : 8061,
  "charging" : true,
  "externalPowerConnected" : true,
  "usbVbusConnected" : true,
  "cratePercentPerHour" : 36.0,
  "autoChargeDrift" : false
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/battery"
```

### `POST /api/battery`

**Update NP-W235 battery level, charging, or USB-C external power** — Updates a non-empty subset of live NP-W235 battery and USB-C external power settings, automatically synchronizing `max17048`, `power-peer`, and `soc-system-control` USB VBUS.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `present` | `boolean` | no | `true` when NP-W235 battery pack is installed, `false` when removed. |
| `chargePercent` | `number` | no | Battery state of charge (`0.0..100.0`). (range: `0.0..100.0`) |
| `batteryPercent` | `number` | no | Alias for `chargePercent` (`0.0..100.0`). (range: `0.0..100.0`) |
| `voltageMillivolts` | `integer` | no | Explicit 2S pack voltage in mV (`0..12000`); derived automatically from NP-W235 curve when omitted on charge changes. (range: `0..12000`) |
| `charging` | `boolean` | no | Enable or disable active battery charging (enabling automatically connects USB-C external power). |
| `externalPowerConnected` | `boolean` | no | Connect or disconnect external USB-C VBUS power. |
| `usbVbusConnected` | `boolean` | no | Alias for `externalPowerConnected`. |
| `autoChargeDrift` | `boolean` | no | Enable gentle virtual-time SOC drift while charging (`+36%/hr`). |

**Request Example**

```json
{
  "chargePercent" : 78.0,
  "charging" : true
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Updated battery and USB-C power state. |
| `400` | `application/json` | Unknown key, wrong type, or out-of-range percentage/voltage. |
| `404` | `application/json` | `battery` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "battery",
  "revision" : 2,
  "present" : true,
  "chargePercent" : 78.0,
  "voltageMillivolts" : 8061,
  "charging" : true,
  "externalPowerConnected" : true,
  "usbVbusConnected" : true,
  "cratePercentPerHour" : 36.0,
  "autoChargeDrift" : false
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/battery" \
  -H "Content-Type: application/json" \
  -d '{"chargePercent":78.0,"charging":true}'
```

### `GET /api/power`

**Get power and battery state (alias of GET /api/battery)** — Alias of `GET /api/battery` returning live NP-W235 battery pack and USB-C external power telemetry.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current battery and USB-C power state. |
| `404` | `application/json` | `battery` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "battery",
  "revision" : 1,
  "present" : true,
  "chargePercent" : 78.0,
  "voltageMillivolts" : 8061,
  "charging" : true,
  "externalPowerConnected" : true,
  "usbVbusConnected" : true,
  "cratePercentPerHour" : 36.0,
  "autoChargeDrift" : false
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/power"
```

### `POST /api/power`

**Update power and battery state (alias of POST /api/battery)** — Alias of `POST /api/battery` updating NP-W235 battery level, charging, or USB-C external power.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `present` | `boolean` | no | `true` when NP-W235 battery pack is installed, `false` when removed. |
| `chargePercent` | `number` | no | Battery state of charge (`0.0..100.0`). (range: `0.0..100.0`) |
| `batteryPercent` | `number` | no | Alias for `chargePercent` (`0.0..100.0`). (range: `0.0..100.0`) |
| `voltageMillivolts` | `integer` | no | Explicit 2S pack voltage in mV (`0..12000`). (range: `0..12000`) |
| `charging` | `boolean` | no | Enable or disable active battery charging. |
| `externalPowerConnected` | `boolean` | no | Connect or disconnect external USB-C VBUS power. |
| `usbVbusConnected` | `boolean` | no | Alias for `externalPowerConnected`. |
| `autoChargeDrift` | `boolean` | no | Enable gentle virtual-time SOC drift while charging (`+36%/hr`). |

**Request Example**

```json
{
  "externalPowerConnected" : true,
  "charging" : true
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Updated battery and USB-C power state. |
| `400` | `application/json` | Unknown key, wrong type, or out-of-range percentage/voltage. |
| `404` | `application/json` | `battery` component unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "component" : "battery",
  "revision" : 2,
  "present" : true,
  "chargePercent" : 78.0,
  "voltageMillivolts" : 8061,
  "charging" : true,
  "externalPowerConnected" : true,
  "usbVbusConnected" : true,
  "cratePercentPerHour" : 36.0,
  "autoChargeDrift" : false
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/power" \
  -H "Content-Type: application/json" \
  -d '{"externalPowerConnected":true,"charging":true}'
```

## Telemetry

Manual diagnostic session log upload.

### `POST /api/telemetry/upload-log`

**Upload current diagnostic session log** — Triggers a manual upload of the current emulator diagnostic log when telemetry is explicitly enabled. Optional `notes` may be passed in the query string or JSON body.

**Parameters**

| Name | In | Type | Required | Description |
| --- | --- | --- | --- | --- |
| `notes` | `query` | `string` | no | Optional operator notes attached to the uploaded diagnostic bundle. |

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `notes` | `string` | no | Optional operator notes (overrides query parameter if provided). |

**Request Example**

```json
{
  "notes" : "repro-raw-conv"
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Log uploaded (`uploaded == true`). |
| `400` | `application/json` | Non-string `notes` or invalid JSON body. |
| `404` | `application/json` | Telemetry handler unavailable. |
| `503` | `application/json` | Upload attempted but failed or telemetry disabled (`uploaded == false`). |

**Success Response Example (`200`)**

```json
{
  "uploaded" : true,
  "sessionId" : "sess-001"
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/telemetry/upload-log" \
  -H "Content-Type: application/json" \
  -d '{"notes":"repro-raw-conv"}'
```

## UI & Personalization

Runtime UI locale switching and camera body / studio theme customization.

### `GET /api/lang`

**Get active UI language and supported locales** — Returns the current desktop studio UI locale and list of supported language codes.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current UI locale state. |

**Success Response Example (`200`)**

```json
{
  "language" : "en",
  "supported" : [ "en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES" ]
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/lang"
```

### `POST /api/lang`

**Switch desktop UI language live** — Hot-switches the emulator desktop UI language without restarting. Provide exact key `language` or `lang`.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `language` | `string` | no | Target locale code (`en`, `zh_CN`, `ja_JP`, `de_DE`, `fr_FR`, `es_ES`). Provide either `language` or `lang`. |
| `lang` | `string` | no | Alias for `language`. |

**Request Example**

```json
{
  "language" : "zh_CN"
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Language updated. |
| `400` | `application/json` | Missing exact key or unsupported locale. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "language" : "zh_CN",
  "supported" : [ "en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES" ]
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/lang" \
  -H "Content-Type: application/json" \
  -d '{"language":"zh_CN"}'
```

### `GET /api/theme`

**Get active camera body and UI studio theme** — Returns the currently active camera body theme (`body`) and desktop studio UI theme (`ui`), plus available presets.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current theme configuration. |
| `404` | `application/json` | Theme controller unavailable (headless mode). |

**Success Response Example (`200`)**

```json
{
  "body" : "graphite",
  "ui" : "studio-dark"
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/theme"
```

### `POST /api/theme`

**Switch camera body and/or studio UI theme** — Hot-switches the camera body finish (`body`) and/or desktop studio UI theme (`ui`). Accepts a non-empty subset of `body` and `ui` with non-blank string values.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `body` | `string` | no | Camera body theme identifier. |
| `ui` | `string` | no | Desktop studio UI theme identifier. |

**Request Example**

```json
{
  "body" : "graphite",
  "ui" : "studio-dark"
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Theme updated. |
| `400` | `application/json` | Empty body, unknown key, or blank theme string. |
| `404` | `application/json` | Theme controller unavailable (headless mode). |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "body" : "graphite",
  "ui" : "studio-dark"
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/theme" \
  -H "Content-Type: application/json" \
  -d '{"body":"graphite","ui":"studio-dark"}'
```

### `POST /api/theme/reload`

**Hot-reload external camera body and UI themes** — Rescans `./themes/body/` and `./themes/ui/` packs from disk, re-resolves the active camera body and UI themes, and notifies all live studio windows without restarting.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Themes reloaded. |
| `400` | `application/json` | Non-empty request body. |
| `404` | `application/json` | Theme reload unavailable (headless mode). |

**Success Response Example (`200`)**

```json
{
  "reloaded" : true,
  "reloadGeneration" : 1,
  "body" : "origin",
  "ui" : "origin"
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/theme/reload"
```

## NOR Flash

Unified 64 MiB QSPI NOR Flash status inspection, .DAT firmware flashing, raw export/import, and factory restore.

### `GET /api/flash`

**Get unified 64 MiB QSPI NOR Flash status** — Returns the active 64 MiB QSPI NOR Flash image path, boot path (`flash-direct` or `bootrom-nor`), BootROM presence, SHA-256 digest, writability, and committed erase/program counters.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Current unified NOR Flash status. |
| `404` | `application/json` | Flash controller unavailable. |

**Success Response Example (`200`)**

```json
{
  "unifiedNorMode" : true,
  "norFlashFile" : "flash/nor-flash.bin",
  "factoryAvailable" : true,
  "bootPath" : "flash-direct",
  "bootRomPresent" : true,
  "loaded" : true,
  "length" : 67108864,
  "writable" : true,
  "eraseCommitted" : 0,
  "programCommitted" : 0
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/flash"
```

### `POST /api/flash`

**Flash .DAT firmware, export/import raw NOR image, or restore factory Flash** — Performs a live host flasher action against the unified 64 MiB QSPI NOR Flash image (`write-dat`, `export`, `import`, or `restore-factory`). Mutating actions automatically reload the component host and cold-reboot the camera.

**Request Body (`application/json`)**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `action` | `string` | yes | Flash operation to perform. (values: `write-dat`, `export`, `import`, `restore-factory`) |
| `path` | `string` | no | Source or destination file path (required for `write-dat`, `export`, and `import`; resolved relative to emulator home if not absolute). |
| `preserveCalibration` | `boolean` | no | For `write-dat` only: preserve calibration and user settings `[0x02FE0000..0x04000000)` (default `true`). |

**Request Example**

```json
{
  "action" : "write-dat",
  "path" : "GXUP0004.DAT",
  "preserveCalibration" : true
}
```

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | Flash action succeeded. |
| `400` | `application/json` | Invalid action, missing path, or unknown field. |
| `404` | `application/json` | Flash controller unavailable. |

**Success Response Example (`200`)**

```json
{
  "ok" : true,
  "sizeBytes" : 67108864,
  "preservedCalibration" : true
}
```

**curl Example**

```bash
curl -s -X POST "http://127.0.0.1:18080/api/flash" \
  -H "Content-Type: application/json" \
  -d '{"action":"write-dat","path":"GXUP0004.DAT","preserveCalibration":true}'
```

## Documentation

Self-hosted OpenAPI 3.1.0 specification and offline interactive HTML API explorer.

### `GET /api/openapi.json`

**Get OpenAPI 3.1.0 specification** — Serves the deterministically generated OpenAPI 3.1.0 JSON schema describing every route on this ControlServer, ready for Swagger UI, Postman, Apifox, Bruno, Insomnia, or Redoc.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `application/json` | OpenAPI 3.1.0 JSON document. |

**Success Response Example (`200`)**

```json
{
  "openapi" : "3.1.0",
  "info" : {
    "title" : "FUJIFILM GFX100S Emulator HTTP Control API",
    "version" : "1.0.0"
  }
}
```

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/openapi.json"
```

### `GET /api/docs`

**Open offline interactive HTTP API Explorer** — Serves a zero-CDN, self-contained HTML interactive API explorer with instant search, tag filtering, copy-as-curl snippets, and live same-origin Try-It execution against this ControlServer.

**Responses**

| Status | Content-Type | Description |
| --- | --- | --- |
| `200` | `text/html; charset=utf-8` | Self-contained interactive HTML API Explorer. |

**curl Example**

```bash
curl -s "http://127.0.0.1:18080/api/docs"
```
