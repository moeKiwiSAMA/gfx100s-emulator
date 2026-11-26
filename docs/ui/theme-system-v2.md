# Decoupled Theme System v2 Guide (Body Themes & UI Themes)

This document specifies the Theme System v2 architecture used by the Fuji GFX100S Emulator desktop studio.

## 1. Two Orthogonal, Decoupled Layers

- **Camera Body Themes (`./themes/body/<id>/`)**: Define the physical camera views (1–12 panels), artwork, interactive control hitboxes, display viewport geometry, and dynamic SVG state bindings.
- **UI Window Themes (`./themes/ui/<id>/ui-theme.json` or `./themes/ui/<id>.css`)**: Define the studio chrome palette, semantic `-fuji-*` design tokens, typography, and window backdrop colors.

Any Body Theme can be combined freely with any UI Theme at runtime (via GUI header combos, `/theme`, or `POST /api/theme`), and external packs can be hot-reloaded without restarting via the header `↻` Reload button, `/theme reload`, or `POST /api/theme/reload`.

## 2. Artwork Policy: Vector-First SVG + Material Bitmap Textures + Optional Raster Mode

| Mode | Recommended? | Use Case | Security & Size Limits |
| --- | --- | --- | --- |
| **Vector SVG** (`"svg": "rear.svg"`) | **Strongly Recommended** | Crisp resolution-independent chassis, bevels, dials, engraved legends, and dynamic SVG bindings | `<= 256 KiB` per SVG, `<= 4096` elements; zero `<script>`, `<foreignObject>`, `on*`, external URLs, or arbitrary CSS `style` |
| **SVG + Material Bitmap Textures** (`<pattern data-texture="textures/leather.png">`) | **Recommended for Rich Materials** | Realistic leatherette, brushed anodized metal, carbon fiber, or wood grip textures tiled cleanly inside vector shapes | Pack-local relative path only (`.png/.jpg/.jpeg/.webp`), `<= 2 MiB`, `<= 1024×1024 px` |
| **Procedural Microrelief** (`data-generator="continuous-grain-v1"`) | **Recommended (Zero Asset Weight)** | Deterministic non-periodic micro-grain (`paint`, `rubber`, `brushed-metal`, `anodized`) | Bounded in-memory cache |
| **Full-View Raster Artwork** (`"artwork": "rear.png"`) | **Supported, Not Recommended** | Rapid prototyping or photographic skins when vector artwork is unavailable | Pack-local relative path only, `<= 4 MiB`, `<= 4096×4096 px`; hitboxes declared in `body-theme.json` |

> **Recommendation**: Prefer vector SVG with procedural microrelief or bounded `<pattern data-texture="...">` material tiles over full-view raster images so camera faces remain tack-sharp at every window scale and HiDPI factor.

## 3. Body Theme Manifest v2 (`body-theme.json`)

```json
{
  "schema": 2,
  "id": "my-custom-body",
  "name": "My Custom Body",
  "nameZh": "我的自定义机身",
  "description": "Custom medium-format studio skin.",
  "descriptionZh": "自定义中画幅工作台机身皮肤。",
  "recommendedUiTheme": "nordic-minimal",
  "i18n": {
    "names": { "en": "My Custom Body", "zh_CN": "我的自定义机身", "ja_JP": "カスタムボディ" },
    "descriptions": { "en": "Custom medium-format studio skin.", "zh_CN": "自定义中画幅工作台机身皮肤。" }
  },
  "views": [
    {
      "id": "rear",
      "title": "REAR",
      "titleZh": "背面",
      "titles": { "en": "REAR", "zh_CN": "背面" },
      "svg": "rear.svg",
      "width": 1000,
      "height": 720,
      "dynamicStates": [
        {
          "id": "mode-dial-rotor",
          "mode": "rotate-map",
          "componentId": "soc-adc",
          "settingKey": "modeDial",
          "pivotX": 200,
          "pivotY": 160,
          "angleMap": { "P": -60, "S": -30, "A": 0, "M": 30 }
        }
      ],
      "controls": [
        {
          "id": "custom-main-screen",
          "kind": "display",
          "displayPort": "display.main",
          "cornerRadius": 8,
          "x": 100, "y": 160, "width": 560, "height": 420,
          "label": "Main Display", "labelZh": "主屏幕"
        }
      ]
    }
  ]
}
```

### Supported SVG v2 Vector Features

- `<clipPath id="...">` with local `clip-path="url(#id)"`
- `<use href="#id" x="..." y="..."/>` (local `#id` only, cycle-checked, max depth 4)
- `stroke-dasharray` (knurled dials, ticks, dashed reticles)
- `data-effect="shadow"` / `data-effect="inner-shadow"` (with optional `data-shadow-radius`, `data-shadow-offset-x`, `data-shadow-offset-y`, `data-shadow-opacity`, `data-shadow-color`)
- `<pattern id="..." width="..." height="..." patternUnits="userSpaceOnUse" data-texture="textures/leather.png"/>`

### Semantic Control Dispatch (Zero Hardcoded ID Lock-In)

Controls use `displayPort` for screens, `buttonKey` for hardware buttons and `componentId` with `settingKey` for component settings. Supported roles include `view-mode`, `eye-sensor`, `rear-command-dial`, `front-command-dial`, `focus-selector`, `focus-mode-segments` and `exposure-mode-button`. The three focus segments select AF-S, AF-C and MF directly. An exposure-mode pushbutton opens a chooser and applies the selected mode. A panel can draw both positions of its STILL/MOVIE control with complementary `highlight-button` bindings for `movieMode`. Display viewports support `cornerRadius` and `clipShape: "circle"`.

## 4. UI Theme Manifest v2 (`ui-theme.json`) & Semantic CSS Tokens

External UI themes may be either:
1. A directory pack `./themes/ui/<id>/ui-theme.json` referencing a local CSS file (e.g., `"css": "theme.css"`), or
2. A legacy single CSS file `./themes/ui/<id>.css`.

All built-in panels (`CameraStudio`, `SourceSelectorPane`, `LensProfilePanel`, `OpticalFilterPane`, `AudioIoPanel`, `EvfViewfinderWindow`) consume semantic looked-up tokens defined in `/panel.css` under `.root`:

- Surfaces: `-fuji-backdrop`, `-fuji-bg-base`, `-fuji-bg-surface`, `-fuji-bg-card`, `-fuji-bg-elevated`, `-fuji-bg-input`, `-fuji-bg-header-top`, `-fuji-bg-header-bot`
- Borders & Typography: `-fuji-border`, `-fuji-border-strong`, `-fuji-text-primary`, `-fuji-text-secondary`, `-fuji-text-muted`
- Accents & Status: `-fuji-accent`, `-fuji-accent-hover`, `-fuji-accent-fg`, `-fuji-ok`, `-fuji-warn`, `-fuji-danger`, `-fuji-badge-bg`, `-fuji-selection-bg`
- Semantic Component Accents: `-fuji-source-3d-*`, `-fuji-source-2d-*`, `-fuji-ring-active-*`

External CSS files containing `@import` or exceeding `128 KiB` are rejected with an explicit `WARN [FALLBACK][theme]` log entry.

## 5. Multi-Language Metadata & English Fallback

All built-in Body Themes (4) and UI Themes (5) ship localized names and descriptions in 6 languages (`en`, `zh_CN`, `ja_JP`, `de_DE`, `fr_FR`, `es_ES`). External packs may supply any subset of locales in `i18n.names` / `i18n.descriptions` (or `nameZh` / `descriptionZh`). Any missing or unsupported locale strictly falls back to English.
