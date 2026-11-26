# HTTP Control & Telemetry API Reference

**English** | [简体中文](HTTP_API.zh-CN.md) | [日本語](HTTP_API.ja.md)

The FUJIFILM GFX100S Emulator embeds a zero-dependency loopback HTTP server (`ControlServer` / `ControlApiSpec`, OpenAPI `3.1.0`) for inspecting live hardware telemetry, automating physical controls/touch/dials, hot-swapping lenses/filters/themes, and driving headless regression scripts.

- **Related Guide**: [Extending & Modding Guide](../extending/EXTENDING.md)
- **Live Self-Hosted Explorer**: Open `http://127.0.0.1:8787/api/docs` while the emulator is running (offline single-file HTML UI with live Try-It execution) or fetch `GET /api/openapi.json` (also generated statically at `docs/api/http-openapi.json` and `docs/api/http-control-api.md`).

---

## 1. Server Enablement, Configuration & Security

### 1.1 Port Configuration (`configs/emulator.yaml` & CLI)

`ControlServer` starts automatically in both GUI and `--headless` interactive sessions using `httpPort` from `configs/emulator.yaml` (or `--config <path>`):

```yaml
# configs/emulator.yaml (standard release & development default: 8787)
httpPort: 8787
```

- **Default Base URL**: `http://127.0.0.1:8787` (`httpPort: 0` binds an OS-assigned ephemeral loopback port; check `logs/latest.log` for `Control API listening on /127.0.0.1:<port>`).
- *(Note: Static OpenAPI examples in `ControlApiSpec.DEFAULT_BASE_URL` use `http://127.0.0.1:18080` as a placeholder server URL; point scripts to your configured `httpPort`, `8787` by default.)*

### 1.2 Headless, Profiler & Telemetry Flags / Environment Variables

| Option / Key | Source | Effect |
| --- | --- | --- |
| `--home <dir>` / `--config <yaml>` | CLI | Selects emulator root directory and YAML launch config (`configs/emulator.yaml`). |
| `--headless --auto-boot --no-console` | CLI | Boots firmware without JavaFX windows or interactive stdin (ideal for CI/Python automation). |
| `--profile` / `--profile-interval-ms <ms>` | CLI / `-Dfuji.profiler.enabled=true` | Auto-starts `AsyncPerfProfiler` (also configurable via `profiler: { enabled: true, intervalMs: 5 }` in `emulator.yaml`; `-Dfuji.profiler.exportFile=true` writes reports to `logs/`). |
| `--no-telemetry` | CLI / `-Dfuji.telemetry.disabled=true` | Highest-priority kill switch disabling all outbound telemetry uploads. |
| `--telemetry-upload` | CLI / `-Dfuji.telemetry.upload=true` | Enables log upload on exit when a telemetry endpoint + token are configured (`relaxed` mode only; `discovery` and `strict` modes never perform network I/O). |
| `FUJI_TELEMETRY_URL` / `FUJI_INGEST_TOKEN` | Env Vars | Override `telemetry.endpoint` and `telemetry.ingestToken` in `emulator.yaml` (both disabled/blank by default in public builds). |

### 1.3 Loopback Security, Headers & Optimistic Concurrency (`revision`)

- **Loopback Only**: Socket binds strictly to `InetAddress.getLoopbackAddress()` (`127.0.0.1`).
- **Same-Origin Browser Guard**: Requests carrying an `Origin` header are permitted **only** when `Origin` equals `http://127.0.0.1:<port>` or `http://localhost:<port>`; cross-origin browser requests fail with `400 {"error": "cross-origin control denied"}` (no CORS headers are issued).
- **Request Limits**: JSON write routes require `Content-Type: application/json`; request bodies are capped at **`64 KiB` (`65,536` bytes)**; query strings allow at most **`16` unique parameters**. Responses set `Cache-Control: no-store`.
- **Optimistic Revision Control (`PATCH /api/v1/components/{id}`)**:
  Instead of HTTP `ETag` headers, low-level component writes require the integer `"revision"` reported in `GET /api/v1/state`:
  ```json
  { "revision": 4, "changes": { "shutterHalf": true } }
  ```
  Each successful patch increments `"revision"` (`200 OK`). If a stale revision is sent, the server returns **`409 Conflict`** (`{"error": "revision conflict"}`) without mutating state — re-read `GET /api/v1/state` and retry. High-level `POST /api/*` routes (`/api/lens`, `/api/filters`, `/api/adc`, `/api/shooting-mode`, etc.) resolve the latest revision atomically on the owner loop for you.

### 1.4 HTTP Status Codes & Error Envelope

All errors return `application/json; charset=utf-8` with `{"error": "<reason>"}`:

| Status | Meaning |
| --- | --- |
| `200 OK` / `201 Created` | Request succeeded (`201` is returned by `POST /api/v1/dump`). |
| `400 Bad Request` | Malformed JSON, missing/unknown fields, out-of-range number, missing `Content-Type: application/json`, body `> 64 KiB`, `> 16` query params, or cross-origin `Origin`. |
| `404 Not Found` | Unknown route/HTTP method, or target component/GUI controller unavailable in headless/minimal configs. |
| `409 Conflict` | Stale `"revision"` passed to `PATCH /api/v1/components/{id}`. |
| `500` / `503` | Internal failure, executor rejected (`503`), or telemetry upload disabled/unreachable (`503` on `/api/telemetry/upload-log`). |

---

## 2. Complete Endpoint Reference (39 Endpoints)

### 2.1 Core & Catalog (4)

| Method & Path | Parameters / Request Body | Response & Notes |
| --- | --- | --- |
| `GET /api/v1/catalog` | Query (`<= 16` unique keys): `address` (e.g. `0xfff70000`), `subsystem` (`isp`, `dma`, `gpio`), `query` (free text), `id`, `selector`, `space`, `width`, `direction`, `coverage=true`, or `unknown=true` (live discovery state). | `200` `{"count": N, "entries": [...]}` from the compiled GFX100S 2.13 address dictionary. |
| `GET /api/v1/state` | *(none)* | `200` `{"components": [{"id", "revision", "config", "state"}, ...], "machine": {"status", "tick", "attempts", "screenId", ...}}`. Primary telemetry & revision snapshot. |
| `POST /api/v1/dump` | Empty body or `{}` (`Content-Type: application/json`). | `201` `{"dump": "dump-..."}` written under `<home>/dumps/`. |
| `PATCH /api/v1/components/{id}` | Path `id` matching `^[a-z][a-z0-9-]{0,63}$`. Body: `{"revision": <int>=0, "changes": {<key>: <val>}}`. | `200` updated component snapshot (`revision + 1`), or `409` on revision conflict. |

**Common `PATCH /api/v1/components/{id}` Targets**:
- `buttons`: boolean keys `power`, `shutterHalf`, `shutterFull`, `menu`, `back`, `up`, `down`, `left`, `right`, `play`, `delete`, `movieMode`, `drive`, `afOn`, `focusMode`, `rearDialPress`, `frontDialPress`, `fn4`, `focusStickPress`.
- `cmos`: `sourceKind` (`JPEG`, `PNG`, `SCENE_3D`, `VIDEO_FILE`, `WEBCAM`, `SCREEN_CAPTURE`, etc.), `sourceFile` (e.g. `scene://qr-ball`, `scene://star-bokeh`, `scene://mona-lisa`, or file path), `loop` (`boolean`).
- `lens`: all `LensFactory` optical/motor settings + `"rescanPlugins": <monotonic-int>` to hot-rescan `lenses/*.jar`.
- `optical-filter`: all filter settings + `"rescanPlugins": <monotonic-int>` to hot-rescan `filters/*.jar`.

---

### 2.2 Asynchronous Performance Profiler (3)

| Method & Path | Parameters / Request Body | Response |
| --- | --- | --- |
| `POST /api/profiler/start` | Optional query `?intervalMs=5` (`>= 1`) or JSON `{"intervalMs": 5}` (body overrides query). | `200` `{"running": true, "intervalMs": 5}`. |
| `POST /api/profiler/stop` | Optional query `?format=json\|text\|txt` (or header `Accept: text/plain`). | `200` JSON summary or `text/plain` hot-spot report. |
| `GET /api/profiler/report` | Optional query `?format=json\|text\|txt` (or header `Accept: text/plain`). | `200` latest/live report without stopping an active sampling run. |

---

### 2.3 Touch, Buttons, Dials & Viewfinder Controls (11)

| Method & Path | Request Schema | Response Schema |
| --- | --- | --- |
| `POST /api/touch` | **Exact keys**: `{"x": 0..65535, "y": 0..65535, "pressed": true\|false}` (rear LCD native coordinates: `x` in `0..1023`, `y` in `0..767`). | `200` `{"ok": true, "component": "rear-touch-ic", "x", "y", "pressed", "revision"}`. |
| `POST /api/touch/swipe` | Provide **either** `"direction": "up"\|"down"\|"left"\|"right"` **or** `"x1", "y1", "x2", "y2"` (`0..65535` — never mix both!), plus optional `"steps"` (`1..8`, default `4`) and `"stepMs"` (`5..100`, default `20`). | `200` `{"ok": true, "component", "x1", "y1", "x2", "y2", "steps", "stepMs", "revision"}` (calibrated for LiveView T-Fn flicks and Playback slide-commit). |
| `GET /api/eye-sensor` | *(none)* | `200` `{"ok": true, "component": "tmd2772", "near": bool, "activeViewfinder": "LCD"\|"EVF"\|"BOTH"\|"WAITING", "lcdSequence", "evfSequence", "lcdOutputActive", "evfOutputActive"}`. |
| `POST /api/eye-sensor` | **Exact key**: `{"near": true\|false}`. | `200` updated eye-sensor & viewfinder routing state. |
| `GET /api/adc` | *(none)* | `200` `{"ok": true, "component": "soc-adc", "revision", "controls": {...}, "samples", "eventCount", "sampleReadCount"}`. |
| `POST /api/adc` | Non-empty subset of: booleans `qButton`, `aelButton`, `fn1Button`, `fn2Button`, `fn3Button`, `lightButton`; string `modeDial` (`default`, `P`, `S`, `A`, `M`, `C1`..`C6`); integers `rearDialPosition` (`-1..3`), `rearDialDetents` (`-10000..10000`), relative `rearDialStep` (`-1000..1000`), raw 12-bit overrides `ch3Override`, `ch5Override`, `ch6Override`, `ch7Override`, `ch11Override` (`-1..4095`, `-1` clears). | `200` `{"ok": true, "component": "soc-adc", "revision", "changes": {...}}`. |
| `GET /api/focus-mode` | *(none)* | `200` `{"ok": true, "component": "soc-gpio", "revision", "focusMode", "effectiveFocusMode"}`. |
| `POST /api/focus-mode` | **Exact key** `focusMode` (or alias `mode`): `"AF-S"` (`"AFS"`/`"S"`), `"AF-C"` (`"AFC"`/`"C"`), `"MF"` (`"M"`), or `"default"`. | `200` `{"ok": true, "component": "soc-gpio", "revision", "focusMode", "effectiveFocusMode"}`. |
| `GET /api/shooting-mode` | *(none)* | `200` `{"ok": true, "mode": "STILL"\|"MOVIE", "movieMode": bool, "modeDial", "focusMode"}`. |
| `POST /api/shooting-mode` | Non-empty subset of: `mode` (`STILL`, `PHOTO`, `MOVIE`, `VIDEO`), `movieMode` (`bool`), `modeDial` (`P`/`S`/`A`/`M`/`C1`..`C6`), `focusMode` (`AF-S`/`AF-C`/`MF`/`default`). | `200` combined shooting-mode summary + `"changes"`. |
| `POST /api/view-mode` | **Exact key**: `{"pressed": true\|false}` (drives GPIO Bank 7 Pin 6). | `200` `{"ok": true, "gpio": "bank7.pin6", "pressed": bool}`. |

---

### 2.4 Optics & Filter Stack (6)

| Method & Path | Request Schema | Response Schema |
| --- | --- | --- |
| `GET /api/lens` | *(none)* | `200` `{"ok": true, "component": "lens", "revision", "template", "model", "communicationMode", "electronicContacts", "apertureRingMode", "aperture", "irisStepIndex", "apertureRingStepIndex", "bodyIrisSynced", "bodyCommandedAperture", "minAperture", "maxAperture", "apertureStops", "focalLengthMm", "minFocalLengthMm", "maxFocalLengthMm", "focusDistanceM", "minFocusDistanceM", "focusMode", "oisEnabled", "opticsEnabled", "mountAttached", "mountProtocol"}`. |
| `POST /api/lens` | Non-empty subset of: `template` (e.g. `gf63`, `gf110`, `gf50`, `gf32-64`, `gf45-100`, `gf20-35`, `canon-ef85-f14l`, `takumar-50-f14`, `zeiss-planar-80-f28`, `custom`), `adapterId` (`adapter-fringer-ef-gfx`, `adapter-m42-gfx`, `adapter-hasselblad-v-gfx`), `communicationMode` (`GENERIC_SIMULATED`, `MANUAL`, `REAL_FIRMWARE`), `realFirmwareFile`, `apertureRingMode` (`A`, `C`, `MANUAL`), `aperture`, `focalLengthMm`, `focusDistanceM`, `subjectDistanceM`, `focusMode`, `oisEnabled`, `opticsEnabled`, `electronicContacts`, `afTrigger`. | `200` updated `GET /api/lens` state. |
| `GET /api/filters` | *(none)* | `200` merged `optical-filter` config + live state (`enabled`, `slots`, `totalTransmittance`, `availableFilterProviders`, etc.). |
| `POST /api/filters` | Supports: `clear: true` (or `off: true`); `enabled: bool`; `slots` (JSON array or encoded string of `<= 12` `{ "type": "...", "enabled": true, ... }` items); flat keys (`ndPreset`, `ndStops`, `uvPreset`, `uvTransmittance`, `uvVeilingGlare`, `uvGhostIntensity`, `uvSoftness`, `uvTintR/G/B`, `bokehMask` [`OFF`/`STAR_5`/`HEART`/`HEXAGON`/`RING`/`CUSTOM`], `bokehMaskAngleDeg`, `bokehMaskScale`, `starEnabled`/`starPoints`/`starStrength`/`starThreshold`/`starAngleDeg`/`starLength`, `mistEnabled`/`mistPreset`/`mistStrength`/`mistThreshold`, `gndEnabled`/`gndStops`/`gndHorizon`/`gndFeather`/`gndAngleDeg`); or nested shorthand objects `uv`, `nd`, `bokeh`, `star`, `mist`, `gnd`. | `200` updated `optical-filter` state. |
| `GET /api/nd` | *(none)* | `200` `{"ok": true, "enabled", "preset", "stops", "effectiveStops", "factor", "opticalDensity", "transmittance", "transmittancePercent", "summary"}`. |
| `POST /api/nd` | Shorthand accepting `off: true`, `enabled: bool`, `preset`/`ndPreset` (`OFF`, `ND2`..`ND1000`, `VARIABLE`), and/or `stops`/`ndStops` (`0.0..10.0`). | `200` updated ND metrics. |

---

### 2.5 Audio, Wireless, Telemetry, UI/Theme & NOR Flash (13)

| Category | Method & Path | Request / Parameters | Response & Behavior |
| --- | --- | --- | --- |
| **Audio** | `GET /api/audio` | *(none)* | `200` WM8962 + host audio mixer, latency, jack, and mic state (`404` if disabled). |
| **Audio** | `GET /api/audio/devices` | *(none)* | `200` `{"outputs": [...], "inputs": [...]}` host audio device IDs. |
| **Audio** | `POST /api/audio` | Non-empty subset of: `enabled`, `volume` (`0.0..1.0`), `muted`, `outputDeviceId`, `bufferMs` (`>= 4`), `backendPreference` (`auto`, `native`, `javax`, `silent`), `headphonePlugged`, `inputMode` (`SILENCE`, `TEST_TONE`, `HOST_MIC`), `inputDeviceId`, `inputGainDb`, `inputMuted`, `inputMonitor`, `testToneHz`, `extMicPlugged`. | `200` updated audio status. |
| **Wireless** | `GET /api/wireless` | *(none)* | `200` BCM43438 Wi-Fi/BLE state (`deviceName`, `blePeerConnected`, `wifiPeerConnected`, `ptpIpBridgeEnabled`). |
| **Wireless** | `POST /api/wireless` | Non-empty subset of `deviceName`, `blePeerConnected`, `wifiPeerConnected`, `ptpIpBridgeEnabled` (`relaxed` mode). | `200` updated BCM43438 state. |
| **Telemetry** | `POST /api/telemetry/upload-log` | Optional query `?notes=...` or JSON `{"notes": "..."}`. | `200` `{"uploaded": true, "sessionId": "..."}` when telemetry is configured; `503` when disabled/offline. |
| **UI Locale** | `GET /api/lang` | *(none)* | `200` `{"language": "en", "supported": ["en","zh_CN","ja_JP","de_DE","fr_FR","es_ES"]}`. |
| **UI Locale** | `POST /api/lang` | **Exact key** `language` (or `lang`): `"en"`, `"zh_CN"`, `"ja_JP"`, `"de_DE"`, `"fr_FR"`, or `"es_ES"`. | `200` live UI locale switch + profile persistence. |
| **Themes** | `GET /api/theme` | *(none)* | `200` active `body` & `ui` themes + discovered packs (`404` in headless mode). |
| **Themes** | `POST /api/theme` | Non-empty subset of `{"body": "<id>", "ui": "<id>"}` (e.g. `origin`, `hasselblad-x2d2`, `hasselblad-907x`, `compact-rangefinder`; UI: `origin`, `nordic-minimal`, `daylight-studio`, `vintage-brass`, `olive-field`). | `200` hot-swaps body faces and/or JavaFX CSS across all open windows. |
| **Themes** | `POST /api/theme/reload` | Empty body or `{}`. | `200` `{"reloaded": true, "reloadGeneration": N, ...}` rescans `themes/body/` and `themes/ui/` from disk. |
| **NOR Flash** | `GET /api/flash` | *(none)* | `200` 64 MiB QSPI NOR Flash status (`norFlashFile`, `bootPath`, `bootRomPresent`, `sha256`, `eraseCommitted`, `programCommitted`). |
| **NOR Flash** | `POST /api/flash` | `{"action": "write-dat"\|"export"\|"import"\|"restore-factory", "path": "...", "preserveCalibration": true}`. | `200` executes flash action; mutating actions reload components and cold-reboot the camera. |

*(Plus self-documentation endpoints `GET /api/openapi.json` and `GET /api/docs`.)*

---

## 3. Practical `curl` & Python Automation Examples

All examples below use the standard `http://127.0.0.1:8787` base URL (`export FUJI_API=http://127.0.0.1:8787`).

### 3.1 Quick `curl` Recipes

```bash
export FUJI_API="http://127.0.0.1:8787"

# 1. Read live machine telemetry & active screen ID
curl -s "$FUJI_API/api/v1/state" | jq '.machine'

# 2. Switch to Manual exposure (M), Still photo mode, and MF focus lever
curl -s -X POST "$FUJI_API/api/shooting-mode" \
  -H "Content-Type: application/json" \
  -d '{"mode": "STILL", "modeDial": "M", "focusMode": "MF"}'

# 3. Mount GF 110mm F2 R LM WR at f/2.0, focus at 1.2 m, with physical optics enabled
curl -s -X POST "$FUJI_API/api/lens" \
  -H "Content-Type: application/json" \
  -d '{"template": "gf110", "opticsEnabled": true, "apertureRingMode": "MANUAL", "aperture": 2.0, "focusDistanceM": 1.2}'

# 4. Stack Pro MRC UV + ND8 + 1/4 Black Mist + 5-Point Star Bokeh Mask
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

# 5. Rotate rear command dial +3 detents clockwise
curl -s -X POST "$FUJI_API/api/adc" \
  -H "Content-Type: application/json" \
  -d '{"rearDialStep": 3}'

# 6. Hot-reload external themes & switch to Hasselblad X2D II + Nordic Minimal in Japanese
curl -s -X POST "$FUJI_API/api/theme/reload" -H "Content-Type: application/json" -d '{}'
curl -s -X POST "$FUJI_API/api/theme" -H "Content-Type: application/json" -d '{"body": "hasselblad-x2d2", "ui": "nordic-minimal"}'
curl -s -X POST "$FUJI_API/api/lang" -H "Content-Type: application/json" -d '{"language": "ja_JP"}'
```

### 3.2 Python Automation: Optimistic `PATCH` Shutter Release & Focus Bracketing

This zero-dependency Python 3 script demonstrates proper `409 Conflict` revision retry on `PATCH /api/v1/components/buttons` to fire a realistic two-stage shutter (`S1` -> `S2` 650 ms hold -> release) across an automated 3-step focus bracket:

```python
#!/usr/bin/env python3
"""Automated focus-bracketing capture via the GFX100S loopback HTTP Control API."""

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
    """Patches a component with automatic retry on HTTP 409 optimistic revision conflict."""
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
    raise RuntimeError(f"Exceeded {max_retries} revision retries for {component_id}")


def trigger_shutter(s1_lead_sec: float = 0.12, s2_hold_sec: float = 0.65) -> None:
    """Two-stage shutter pulse: S1 half-press -> S1+S2 full-press -> clean release."""
    patch_component("buttons", {"shutterHalf": True, "shutterFull": False})
    time.sleep(s1_lead_sec)
    patch_component("buttons", {"shutterHalf": True, "shutterFull": True})
    time.sleep(s2_hold_sec)
    patch_component("buttons", {"shutterHalf": False, "shutterFull": False})


if __name__ == "__main__":
    # Configure MF + GF 63mm F2.8 wide open
    api_request("POST", "/api/shooting-mode", {"mode": "STILL", "modeDial": "A", "focusMode": "MF"})
    api_request("POST", "/api/lens", {"template": "gf63", "opticsEnabled": True, "aperture": 2.8})

    for distance_m in (0.8, 1.4, 2.5):
        lens_info = api_request("POST", "/api/lens", {"focusDistanceM": distance_m})
        print(f"[Focus] {lens_info['model']} -> {lens_info['focusDistanceM']} m")
        time.sleep(0.4)
        trigger_shutter()
        print(f"[Shot] Captured frame at {distance_m} m; waiting for still pipeline...")
        time.sleep(1.5)
```
