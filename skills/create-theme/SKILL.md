---
name: create-theme
description: Author, validate, localize, and hot-reload Camera Body Themes (body-theme.json + hybrid SVG/PNG artwork) and Studio UI Themes (ui-theme.json + JavaFX CSS tokens) for Theme System v2 in the FUJIFILM GFX100S Emulator.
---

# Create Camera Body & Studio UI Themes (`Theme System v2`)

Use this skill whenever asked to create, customize, debug, or hot-reload a **Camera Body Theme** or **Studio UI Theme** for the FUJIFILM GFX100S Emulator. Every manifest filename, SVG attribute, CSS token, and security rule below matches `BodyTheme.kt`, `UiTheme.kt`, `PanelCatalog.kt`, `SvgArtwork.kt`, and `CameraStudio.kt`.

---

## 1. Pack Directory Layout & Exact Manifest Filenames

Theme System v2 decouples physical camera chassis skins (**Body Themes**) from desktop chrome palettes (**UI Themes**). External packs live under `<emulator-home>/themes/` and hot-reload without restarting:

```text
<emulator-home>/themes/
├── body/
│   └── <theme-id>/                 # Must match [a-z0-9][a-z0-9_-]{0,63}
│       ├── body-theme.json         # REQUIRED exact manifest filename (<= 256 KiB)
│       ├── rear.svg                # Vector panel SVG(s): [a-z][a-z0-9-]*\.svg (<= 256 KiB, <= 4096 nodes)
│       ├── top.svg
│       ├── front.svg
│       ├── artwork/                # Optional pack-local <image href="artwork/..."> bitmaps (<= 4 MiB, <= 4096x4096)
│       │   └── mode-dial.png
│       └── textures/               # Optional pack-local <pattern data-texture="textures/..."> tiles (<= 2 MiB, <= 1024x1024)
│           └── leatherette.png
└── ui/
    ├── <ui-theme-id>/
    │   ├── ui-theme.json           # REQUIRED exact manifest filename for v2 UI pack (<= 64 KiB)
    │   └── theme.css               # Overlay JavaFX stylesheet (<= 128 KiB, zero @import)
    └── <simple-ui-id>.css          # Optional single-file CSS UI theme shorthand
```

> [!IMPORTANT]
> - Always name the body manifest **`body-theme.json`** and the UI manifest **`ui-theme.json`** (never `theme.json` or `manifest.json`), and ensure `"id"` in JSON exactly equals the parent folder name `<theme-id>`.
> - Built-in packs may also reference `shared/leatherette.png`, `shared/brushed-chrome.png`, and `shared/xcd-glass.png`. External packs under `<emulator-home>/themes/body/<id>/` enforce strict `toRealPath()` containment inside `<packDir>`, so place textures inside the pack's own `textures/` or `artwork/` folder!

---

## 2. Body Theme Manifest Schema (`body-theme.json`, `schema: 2`)

A Body Theme defines `1..12` camera views (`views`). If `"layout"` is omitted, `BodyThemeRegistry.autoGenerateLayout` automatically builds a clean non-overlapping studio layout; include `"layout"` when you want exact multi-face window slots (`width` in `400..4096`, `height` in `300..4096`, `entryMinWidth` in `280..800`, `rotationDegrees` `0` or `90`).

```json
{
  "schema": 2,
  "id": "titanium-rf",
  "name": "Titanium Rangefinder",
  "nameZh": "钛合金旁轴机身",
  "description": "Machined titanium 2-face rangefinder body with rotary dials and hinged SD door.",
  "descriptionZh": "CNC 钛合金双面板旁轴机身，配机械旋转拨盘与可开合存储卡舱盖。",
  "brandLabel": "CUSTOM",
  "modelLabel": "RF-100",
  "recommendedUiTheme": "nordic-minimal",
  "i18n": {
    "names": {
      "en": "Titanium Rangefinder",
      "zh_CN": "钛合金旁轴机身",
      "ja_JP": "チタン・レンジファインダー",
      "de_DE": "Titan-Messsucherkamera",
      "fr_FR": "Télémétrique Titane",
      "es_ES": "Telemétrica de Titanio"
    },
    "descriptions": {
      "en": "Machined titanium 2-face rangefinder body with rotary dials and hinged SD door.",
      "zh_CN": "CNC 钛合金双面板旁轴机身，配机械旋转拨盘与可开合存储卡舱盖。",
      "ja_JP": "削り出しチタン外装・回転ダイヤル・開閉式カードドアを備えた2パネルボディ。",
      "de_DE": "Gefrästes Titan-Messsuchergehäuse mit Drehrädern und aufklappbarer Kartenfachklappe.",
      "fr_FR": "Boîtier télémétrique en titane usiné avec molettes rotatives et trappe SD articulée.",
      "es_ES": "Cuerpo telemétrico de titanio mecanizado con diales giratorios y tapa SD abatible."
    }
  },
  "layout": {
    "width": 1280,
    "height": 820,
    "entryMinWidth": 340,
    "configuration": { "x": 770, "y": 10, "width": 500, "height": 800 },
    "faces": {
      "top":  { "slot": { "x": 10, "y": 10,  "width": 750, "height": 290 }, "crop": { "x": 0, "y": 0, "width": 1000, "height": 340 }, "rotationDegrees": 0 },
      "rear": { "slot": { "x": 10, "y": 310, "width": 750, "height": 500 }, "crop": { "x": 0, "y": 0, "width": 1000, "height": 660 }, "rotationDegrees": 0 }
    }
  },
  "views": [
    {
      "id": "top",
      "title": "TOP",
      "titleZh": "顶面",
      "titles": { "en": "TOP", "zh_CN": "顶面", "ja_JP": "上面", "de_DE": "OBERSEITE", "fr_FR": "DESSUS", "es_ES": "SUPERIOR" },
      "svg": "top.svg",
      "width": 1000,
      "height": 340,
      "dynamicStates": [
        {
          "id": "mode-dial-rotor",
          "mode": "rotate-map",
          "componentId": "soc-adc",
          "settingKey": "modeDial",
          "pivotX": 180,
          "pivotY": 170,
          "angleMap": { "P": 0, "S": -36, "A": -72, "M": -108, "C1": -144, "C2": 180 }
        },
        { "id": "movie-switch-still", "mode": "highlight-button", "buttonKey": "movieMode", "activeOpacity": 0.0, "inactiveOpacity": 1.0 },
        { "id": "movie-switch-movie", "mode": "highlight-button", "buttonKey": "movieMode", "activeOpacity": 1.0, "inactiveOpacity": 0.0 },
        { "id": "tally-led", "mode": "led-binding", "componentId": "mount", "settingKey": "attached", "activeOpacity": 1.0, "inactiveOpacity": 0.15 }
      ],
      "controls": [
        { "id": "top-mode-dial", "label": "Mode Dial", "labelZh": "曝光模式转盘", "x": 120, "y": 110, "width": 120, "height": 120, "kind": "dial", "componentId": "soc-adc", "settingKey": "modeDial", "interaction": "inspect" },
        { "id": "top-focus-bar", "role": "focus-mode-segments", "label": "Focus Mode", "labelZh": "对焦模式", "x": 270, "y": 140, "width": 126, "height": 42, "kind": "switch", "buttonKey": "focusMode", "interaction": "latching" },
        { "id": "top-movie-mode", "label": "Still / Movie", "labelZh": "静态 / 视频", "x": 420, "y": 148, "width": 80, "height": 28, "kind": "switch", "buttonKey": "movieMode", "interaction": "latching" },
        { "id": "top-shoulder", "label": "Sub LCD", "labelZh": "肩屏", "x": 540, "y": 80, "width": 170, "height": 128, "kind": "display", "displayPort": "display.shoulder", "cornerRadius": 6 },
        { "id": "top-shutter-full", "label": "Shutter S2", "labelZh": "快门全按", "x": 800, "y": 88, "width": 64, "height": 64, "kind": "button", "buttonKey": "shutterFull", "interaction": "momentary" },
        { "id": "top-shutter-half", "label": "Shutter S1", "labelZh": "快门半按", "x": 770, "y": 170, "width": 124, "height": 42, "kind": "button", "buttonKey": "shutterHalf", "interaction": "momentary" },
        { "id": "top-power", "label": "Power", "labelZh": "电源开关", "x": 872, "y": 104, "width": 44, "height": 44, "kind": "switch", "buttonKey": "power", "interaction": "latching" }
      ]
    }
  ]
}
```

---

## 3. SVG Artwork Grammar, Hitboxes & Interactive Bindings

### Admitted SVG Elements & Attributes (`PanelResources.document`)

- **Root**: `<svg xmlns="http://www.w3.org/2000/svg" width="W" height="H" viewBox="0 0 W H">` — `viewBox` **must** equal `0 0 <panel.width> <panel.height>` exactly.
- **Allowed elements** (`<= 4096` total nodes, `<= 262,144` bytes): `svg`, `g`, `rect`, `circle`, `ellipse`, `path`, `line`, `polyline`, `polygon`, `text`, `tspan`, `title`, `desc`, `defs`, `linearGradient`, `radialGradient`, `stop`, `pattern`, `clipPath`, `use`, `image`.
- **Forbidden attributes/elements** (immediately rejected): `<script>`, `<foreignObject>`, `<style>`, `style="..."`, `filter`, `mask`, `gradientTransform`, `patternTransform`, `on*` event attributes, external URLs (`http://`, `https://`, `data:`), or XML `DOCTYPE` / entities.
- **Supported materials & effects**:
  - **Embedded raster `<image>`**: `<image href="artwork/mode-dial.png" x="120" y="110" width="120" height="120"/>` (`.png/.jpg/.jpeg/.webp`, `<= 4 MiB`, `<= 4096×4096`).
  - **Tiled material `<pattern data-texture>`**: `<pattern id="leather" patternUnits="userSpaceOnUse" width="160" height="160" data-texture="textures/leatherette.png"/>` (`<= 2 MiB`, `<= 1024×1024`, empty children).
  - **Procedural zero-weight microrelief**: `<pattern id="body-grain" patternUnits="userSpaceOnUse" width="1200" height="800" data-generator="continuous-grain-v1" data-kind="anodized" data-seed="42"/>` (`width="1200" height="800"` required; `data-kind` in `paint`, `rubber`, `brushed-metal`, `anodized`).
  - **Drop / Inner Shadows**: `data-effect="shadow"` or `data-effect="inner-shadow"` with optional `data-shadow-radius` (`0.5..32`), `data-shadow-offset-x`/`y` (`-20..20`), `data-shadow-opacity` (`0..1`), `data-shadow-color`.
  - **Local `<clipPath>` & `<use href="#id">`**: local `#id` only (max `<use>` depth `4`, cycle-checked).

### Strict Hitbox Invariant (`validateArtwork`)

Every control declared in `views[].controls` **must** have a 1:1 matching `<g>` in the panel's SVG:

```xml
<g id="top-shutter-full" data-control-id="top-shutter-full" data-view-id="top">
  <circle cx="832" cy="120" r="30" fill="#e85d1a" stroke="#1a1a1a" stroke-width="2"/>
  <rect data-hitbox="true" x="800" y="88" width="64" height="64" fill="none" stroke="none"/>
</g>
```

1. `id` and `data-control-id` must both equal `control.id`, and `data-view-id` must equal `view.id`.
2. The `<g>` must have **exact one** direct child `<rect data-hitbox="true" ... fill="none" stroke="none"/>` whose `x`, `y`, `width`, `height` match the JSON control bounds identically.
3. Every `control.id` and every bound `buttonKey` must be **unique across all views** in `body-theme.json`. (Allowed `buttonKey` values: `power`, `menu`, `back`, `up`, `down`, `left`, `right`, `play`, `shutterHalf`, `shutterFull`, `delete`, `rearDialPress`, `movieMode`, `drive`, `afOn`, `focusMode`, `frontDialPress`, `fn4`, `focusStickPress`.) A control cannot set both `buttonKey` and `componentId`.

### Semantic Roles, Proxies, Display Viewports & Mechanical Covers

- **`studio-display-hotspot` & Display Viewports (`kind: "display"`)**: Set `"displayPort"` to `"display.main"`, `"display.evf"`, or `"display.shoulder"`, with optional `"cornerRadius": 6` or `"clipShape": "circle"`. `CameraStudio` automatically attaches `.studio-display-hotspot` so the screen border never flashes a hover ring while `display.main` dispatches capacitive touch/swipes and double-clicking `display.evf` pops out the floating EVF window.
- **`rotate-map` (`dynamicStates`)**: Rotates `<g id="<ds.id>">` around `(pivotX, pivotY)` by `angleMap[value]` degrees for `"componentId": "soc-adc", "settingKey": "modeDial"` (`P`, `S`, `A`, `M`, `C1`..`C6`) or `"componentId": "soc-gpio", "settingKey": "focusMode"` (`AF-S`/`S`, `AF-C`/`C`, `MF`/`M`).
- **`highlight-button` & `led-binding` (`dynamicStates`)**: Toggles SVG node opacity between `activeOpacity` and `inactiveOpacity` from a physical `buttonKey` (e.g., paired `movieMode` or `power` lever positions) or a component state key (`led-binding`).
- **`focus-mode-segments` (`role`)**: Set `"role": "focus-mode-segments"` and `"buttonKey": "focusMode"` to mount a 3-detent `AF-S | AF-C | MF` segmented switch directly over your printed artwork labels.
- **`exposure-mode-button` (`role`)**: Set `"role": "exposure-mode-button"`, `"componentId": "soc-adc"`, `"settingKey": "modeDial"` on dial-less modern bodies (like X2D II) to open an exposure-mode chooser on click.
- **`data-control-proxy` (Cross-View Proxy Hitbox)**: Since each `buttonKey` can appear in `controls` only once across the theme, expose a button/dial from another face (e.g., `front-command-dial` visible along the top deck) by placing a top-level `<rect>` directly under `<svg>` with **no `transform`**:
  ```xml
  <rect id="top-front-dial-proxy" data-control-proxy="front-command-dial" x="245" y="253" width="174" height="43" fill="none" stroke="none"/>
  ```
- **Mechanical `covers` (Hinged Doors & Latches)**: Declare `"covers": [{ "id": "sd-door", "latch": "sd-latch", "inside": ["slot-1", "slot-2"], "openHitbox": { "x": 688, "y": 353, "width": 99, "height": 298 } }]` in the view JSON, define controls `"sd-door"` (`"kind": "cover"`, `"interaction": "cover-toggle"`) and optional `"sd-latch"` (`"kind": "latch"`, `"interaction": "cover-toggle"`), and include both `<g id="sd-door-closed">` and `<g id="sd-door-open">` in the SVG.
- **Conditional `layers`**: `"layers": [{ "id": "lens-mounted-art", "visibleWhen": { "componentId": "mount", "settingKey": "attached", "equals": true } }]` shows/hides `<g id="lens-mounted-art">`.

---

## 4. Studio UI Theme Manifest (`ui-theme.json`) & CSS Tokens

Create `<emulator-home>/themes/ui/<ui-id>/ui-theme.json` + `theme.css` (`<= 128 KiB`, **never use `@import`**):

```json
{
  "schema": 2,
  "id": "cobalt-darkroom",
  "name": "Cobalt Darkroom",
  "nameZh": "钴蓝暗房",
  "author": "Studio Designer",
  "version": "1.0.0",
  "isDark": true,
  "backdropHex": "#11161F",
  "recommendedBodyTheme": "origin",
  "css": "theme.css",
  "i18n": {
    "names": {
      "en": "Cobalt Darkroom", "zh_CN": "钴蓝暗房", "ja_JP": "コバルト・ダークルーム",
      "de_DE": "Kobalt-Dunkelkammer", "fr_FR": "Chambre noire cobalt", "es_ES": "Cuarto oscuro cobalto"
    },
    "descriptions": {
      "en": "Deep slate-cobalt darkroom palette with crisp ice-blue accents.",
      "zh_CN": "深邃钴蓝暗房配色与冰蓝高对比度控件强调色。",
      "ja_JP": "深いコバルトスレート基調にアイスブルーのアクセントを配した暗室テーマ。",
      "de_DE": "Tiefes Kobalt-Dunkelkammer-Farbschema mit eisblauen Akzenten.",
      "fr_FR": "Palette chambre noire ardoise-cobalt aux accents bleu glacé.",
      "es_ES": "Paleta cuarto oscuro azul cobalto profundo con acentos azul hielo."
    }
  }
}
```

Override semantic `-fuji-*` design tokens on `.root` in `theme.css` (built on top of `/panel.css`):

```css
.root {
    -fuji-backdrop: #11161F;
    -fuji-bg-base: #11161F;
    -fuji-bg-surface: #171E29;
    -fuji-bg-card: #1C2533;
    -fuji-bg-elevated: #243042;
    -fuji-bg-input: #0D1219;
    -fuji-bg-header-top: #1B2330;
    -fuji-bg-header-bot: #121721;
    -fuji-border: #2C3A4F;
    -fuji-border-strong: #3E526E;
    -fuji-text-primary: #EEF2F6;
    -fuji-text-secondary: #CBD5E1;
    -fuji-text-muted: #8B9BB4;
    -fuji-accent: #38BDF8;
    -fuji-accent-hover: #7DD3FC;
    -fuji-accent-fg: #0B111A;
    -fuji-ok: #4ADE80;
    -fuji-warn: #FBBF24;
    -fuji-danger: #F87171;
    -fuji-badge-bg: #1E293B;
    -fuji-selection-bg: #1E3A5F;
    -fuji-source-3d-bg: #132A22;
    -fuji-source-3d-border: #22C55E;
    -fuji-source-3d-fg: #86EFAC;
    -fuji-source-2d-bg: #15253B;
    -fuji-source-2d-border: #38BDF8;
    -fuji-source-2d-fg: #BAE6FD;
    -fuji-ring-active-border: #F59E0B;
    -fuji-ring-active-fg: #FDE68A;
}
```

---

## 5. Live Hot-Reload & Verification Commands

1. **Hot-reload & activate via HTTP Control API** (no restart required; default port `8787`):
   ```bash
   # Rescan <emulator-home>/themes/body/ and <emulator-home>/themes/ui/
   curl -s -X POST http://127.0.0.1:8787/api/theme/reload -H "Content-Type: application/json" -d '{}'

   # Activate your Body Theme and UI Theme
   curl -s -X POST http://127.0.0.1:8787/api/theme \
     -H "Content-Type: application/json" \
     -d '{"body": "titanium-rf", "ui": "cobalt-darkroom"}'
   ```
   *(Or click the header `↻` Reload button / run `/theme reload` in the built-in console.)*
2. **Run automated theme & SVG artwork validation tests** (in source builds):
   ```bash
   ./gradlew :desktop:test --tests "*BodyThemeTest*" --tests "*UiThemeTest*" --tests "*SvgArtworkAndThemeV2Test*" --no-daemon
   ```
3. **Check `logs/latest.log` for `[FALLBACK][theme]`**: Any schema mismatch, out-of-bounds hitbox, disallowed SVG attribute, or symlink/path-traversal violation logs the exact reason under `[FALLBACK][theme]` and falls back cleanly to `origin`.
