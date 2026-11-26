# 拡張・MOD開発ガイド (Extending & Modding)

[English](EXTENDING.md) | [简体中文](EXTENDING.zh-CN.md) | **日本語**

*光学・硝材・金属の質感 —— FUJIFILM GFX100S エミュレーター拡張アーキテクチャ*

本エミュレーターはカメラ本体のシリコンエミュレーションと、ホットリロードに対応した3つのオープンな拡張レイヤーを明確に分離しています。

1. **モジュラーレンズ＆マウントアダプター JAR（`<emulator-home>/lenses/*.jar`）** —— Java `ServiceLoader` SPI v2 により、光学収差・絞り羽根形状・AFモーター駆動速度・`LENSCOM` マウント通信プロトコルを物理ベースで再現します。
2. **光学フィルター・入射瞳ボケマスク・カスタム SPIR-V シェーダー（`<emulator-home>/filters/*.jar`）** —— センサー露光前のリニアHDR放射輝度空間で動作する光学フィルター、幾何光学ボケマスク、および Vulkan 1.1 `.spv` コンピュートシェーダーを提供します。
3. **カメラボディ＆スタジオ UI テーマ（`<emulator-home>/themes/body/`・`themes/ui/`）** —— ベクターSVGとハイブリッドマテリアルによる外装スキン、および `-fuji-*` セマンティックCSSトークンによるUIカラーテーマです。

> [!TIP]
> **AIコーディングエージェント（Claude Code / Gemini / Codex / Cursor）で開発する場合** — リポジトリの `skills/` ディレクトリに、完全なスキーマ・バリデーション境界・検証コマンドを網羅したエージェント用スキル定義を同梱しています。
> - カスタムレンズ＆アダプター作成：[`skills/create-lens/SKILL.md`](../../skills/create-lens/SKILL.md)
> - 光学フィルター＆SPIR-Vシェーダー作成：[`skills/create-optical-filter/SKILL.md`](../../skills/create-optical-filter/SKILL.md)
> - ボディ＆UIテーマ作成：[`skills/create-theme/SKILL.md`](../../skills/create-theme/SKILL.md)
> - HTTP制御・ホットリロードAPIリファレンス：[`docs/api/HTTP_API.ja.md`](../api/HTTP_API.ja.md)

---

## 1. モジュラーレンズ＆マウントアダプター JAR（`lenses/*.jar`）

交換レンズ（`lenses/lens-gf63/`、`lenses/lens-gf110/`、`lenses/lens-takumar-50-f14/` など）とマウントアダプター（`lenses/adapter-fringer-ef-gfx/`、`lenses/adapter-m42-gfx/`、`lenses/adapter-hasselblad-v-gfx/`）は独立した JAR としてパッケージ化され、起動時および再スキャン時に `LensModuleLoader` が `<emulator-home>/lenses/` から `URLClassLoader` + `java.util.ServiceLoader` 経由で自動検出します。

### 1.1 ServiceLoader SPI と排他的通信コントラクト

`com.github.nyabanban.fuji.emulator.api.LensModule`（または `MountAdapterModule`）を実装し、以下のサービスファイルに完全修飾クラス名を記載します。

- `META-INF/services/com.github.nyabanban.fuji.emulator.api.LensModule`
- `META-INF/services/com.github.nyabanban.fuji.emulator.api.MountAdapterModule`

すべてのレンズ（およびアダプター＋レンズ構成）は、`sealed interface LensControlContract` によって**排他的な3モードのいずれか1つ**を宣言します（ロード時に `validateContract()` で厳密に検証されます）。

| モード (`LensCommunicationMode`) | コントラクト型 | 電子接点 | `LENSCOM` (`SPI0`) 通信とファームウェア挙動 |
| --- | --- | --- | --- |
| `GENERIC_SIMULATED` | `LensControlContract.GenericSimulated(LenscomIdentitySpec)` | `true` | 光学・機構仕様からプロトコル準拠の `LENSCOM` 応答を合成：128バイト識別ヘッダー（`cmd 0x014`）、128 KiB 光学EEPROM（`cmd 0x0C0`）、`A`/`C`/`MANUAL` 1/3段絞り連動、符号付き15bitフォーカスエンコーダー（`0..0x7FFF`）。 |
| `MANUAL` | `LensControlContract.Manual` | `false` | 機械式MFレンズおよび非電子マウントアダプター（`defaultFocusMode = "MF"`、`focusMotorType = "MANUAL"`、`focusSweepSeconds = 0.0` 必須）。ボディ側は `F0`（マウントアダプター設定）として動作します。 |
| `REAL_FIRMWARE` | `LensControlContract.RealFirmware(...)` | `true` | `<emulator-home>/firmware-data/lenses/` 内のユーザー所有レンズ／アダプター実ファームウェア（`fuji-lens-type4-dat` または `fringer-adapter-bin`）を使用。ファイル不在やSHA-256不一致時は即座にエラー停止し、別モードへ暗黙フォールバックしません。 |

電子レンズの `LenscomIdentitySpec` では、`shortId` を `1..7` 文字の英大文字・数字（`[A-Z0-9]`）とし、純正GF相当レンズでは `capabilityFlags1 = 0x01`（点像復元処理 LMO 対応）を設定してください。また、ボディNVRAM内の128 KiB EEPROMキャッシュ衝突を防ぐため、**レンズごとに固有の10文字シリアル `serialAscii`**（例：`"GF08001701"`）を必ず割り当ててください。

### 1.2 光学モデル・AFモーター駆動・特殊光学フック

`LensOpticalSpec` は Vulkan 1.1 GPU コンピュートバックエンドと決定論的 `CpuOpticalPipeline`（`OpticalLensProfile`）の双方を駆動します。

- **焦点距離・絞り・イメージサークル**：`minFocalLengthMm..maxFocalLengthMm`、`minAperture..maxAperture`（`ApertureScale.THIRD_STOPS` による1/3段ステップ）、実効透過率 `transmission`（`0.0..1.0`）、イメージサークル直径 `imageCircleMm`（GFX 43.8×32.9 mm センサー対角線 `54.78` mm に対し、ネイティブGFは `56.0..57.0` mm、35mm判レンズは周辺ケラレを伴う `43.3` mm、6×6中判は余裕のある `79.0` mm）。
- **フォーカスモーター速度とブリージング**：`FocusMotorTypes`（`LM_FAST`、`LM`、`STM`、`USM`、`DC_CORELESS`、`MANUAL`）、無限遠から最短撮影距離（`minFocusDistanceM` / `teleMinFocusDistanceM`）までの駆動時間 `focusSweepSeconds`（`0.0..5.0` 秒、ジオプター空間で補間）、近接時のフォーカスブリージング `focusBreathing`（`-0.5..0.5`）。
- **収差とフレア**：Brown-Conrady 放射歪曲収差（`distortionK1`、`distortionK2`）、倍率色収差（`caRedScale`、`caBlueScale`）、軸上色収差 LoCA（`longitudinalCaDefocusMm`、`minAperture / fNumber` に比例して減衰）、開放球面収差によるコントラスト低下 `wideOpenContrastDrop`（`0.0..0.6`）、コサイン乗則減光 `vignettingCos4` と機械的周辺減光 `vignettingMechanical`、およびゴースト・フレア強度 `flareStrength` と反射色 `flareTintR/G/B`。
- **絞り羽根とボケ味**：`apertureBladeCount`（`5..11` 枚）、羽根の丸み `apertureBladeRoundness`（`0.0..1.0`、開放時は真円、絞り込むと多角形へ遷移）、口径食（レモン型ボケ）`bokehCatsEye`（`0.0..1.0`）、非球面レンズの年輪ボケ `bokehOnionRing`（`0.0..0.5`）、バブルボケ輪郭強調 `bokehRimHighlight`（`0.0..0.6`）。
- **可変絞りズームと特殊光学フック（SPI v2）**：
  - `opticalSpecAt(focalLengthMm, focusDistanceM, aperture)` をオーバーライドすることで、F値変動ズームや焦点距離に応じた歪曲・色収差・周辺減光カーブを表現可能。
  - `opticalHook(): LensOpticalHook?` により、シャインプルーフの原理に基づくティルト・シフト（`effectiveFocusDistanceAt`）、アナモルフィック／魚眼投影（`warpUv`）、ぐるぐるボケ・ミラーレンズ副鏡遮蔽（`pupilWeight`）、またはボケ合成後のカスタム `.spv` パス（`vulkanSpirvPass`、`CustomSpirvStage.POST_OPTICS_RGB`）を追加可能。
  - `eepromBytes(): ByteArray?`（`131,072` バイト固定）で独自の128 KiB 光学EEPROMバイナリを提供可能。

### 1.3 推奨される8つの光学データ参照元

感覚的な当て推量ではなく、実測光学データに基づいたプロファイル作成のため、以下の**8つの推奨リファレンス**を参照してください（パラメータ変換式は [`skills/create-lens/SKILL.md`](../../skills/create-lens/SKILL.md) に詳述）。

1. **LensBench**（`https://optic.chat`）—— 順次光線追跡ベンチおよびレンズ処方評価の規約
2. **Photons to Photos · Optical Bench**（`https://www.photonstophotos.net/GeneralTopics/Lenses/OpticalBench/OpticalBench.htm`、Bill Claff 氏）—— 光学特許処方、入射瞳・射出瞳ジオメトリ、ブリージング、口径食（Cat's-eye）解析
3. **Jim Kasson · The Last Word**（`https://blog.kasson.com`）—— GFX100/100S 純正GFレンズの未補正RAW光学ベンチ実測（MTF、軸上色収差 LoCA、フォーカスブリージング、開放球面収差）
4. **LensTip**（`https://www.lenstip.com`）—— 倍率・軸上色収差、コマ収差、フレア／ゴースト耐性、周辺減光、AF駆動速度のラボ測定データ
5. **OpticalLimits（旧 Photozone）**（`https://www.opticallimits.com`）—— 未補正RAW対カメラ内補正の歪曲収差、周辺倍率色収差、周辺減光、ボケ質、絞り値別周辺MTF
6. **Lensfun オープンレンズデータベース**（`https://lensfun.github.io`）—— Brown-Conrady 歪曲係数、TCAスケーリング、周辺減光モデルの数理規約
7. **DxOMark**（`https://www.dxomark.com`）—— 実測Tストップ透過率および周辺光量落ちデータ
8. **富士フイルム公式 GFレンズ仕様表** —— 焦点距離、開放／最小絞り、絞り羽根枚数、最短撮影距離（MOD）、イメージサークル、AFモーター方式

### 1.4 ビルド・配置・ホットリロード手順

1. `compileOnly(project(":component-api"))` を参照して JAR をビルドします（単焦点は `lenses/lens-gf63/`、ズームは `lenses/lens-gf32-64/` を参照）：
   ```bash
   ./gradlew :lenses:lens-gf63:jar --no-daemon
   ```
2. 生成された `.jar` を `<emulator-home>/lenses/` に配置します。
3. エミュレーターを再起動せず、コンソールの `/lens rescan` または HTTP API（標準ポート `8787`）から即時スキャン・装着できます：
   ```bash
   REV=$(curl -s http://127.0.0.1:8787/api/v1/state | jq '.components[] | select(.id=="lens") | .revision')
   curl -s -X PATCH http://127.0.0.1:8787/api/v1/components/lens \
     -H "Content-Type: application/json" -d "{\"revision\": $REV, \"changes\": {\"rescanPlugins\": 1}}"
   curl -s -X POST http://127.0.0.1:8787/api/lens \
     -H "Content-Type: application/json" -d '{"template": "my-lens-id", "opticsEnabled": true}'
   ```

---

## 2. 光学フィルター・ボケマスク・カスタム SPIR-V シェーダー

独立コンポーネント `:components:optical-filter`（ポート `"optical-filter.stack"`）は、レンズ前方に装着するマルチスロット・マットボックスを再現します。すべてのフィルターは CMOS のショットノイズ・読み出しノイズ・ベイヤー配列量子化より上流の**リニアHDR放射輝度空間**で計算されるため、ND減光などはカメラ本来のAE測光・ヒストグラムへ自然に反映されます。

### 2.1 内蔵フィルター要素とマルチスロット積層

デスクトップの **Optical Filter** ペイン、`configs/components/optical-filter/optical-filter.yaml`、または `POST /api/filters` から、順序付きで最大 **12スロット**（`OpticalFilterSlotCodec.MAX_SLOTS = 12`）を重ね掛けできます。

- **`nd`（ND減光フィルター）**：`ND2`..`ND1000` または無段階 `VARIABLE`（`0.0..10.0` EV）。空間コピー負荷ゼロの純スカラー `2^(-stops)` 減衰。
- **`gnd`（ハーフND / グラデーションND）**：`0.0..5.0` EV、境界位置 `horizon`・ぼかし幅 `feather`・回転角 `angleDeg` を自在に調整可能。
- **`black-mist`（ブラックミスト拡散）**：`1/8`・`1/4`・`1/2`・`1`・`CUSTOM`。暗部の締まりを保ちつつハイライトに映画的なハレーションを付与。
- **`star`（クロススクリーン／スター）**：高輝度点光源から `4`・`6`・`8` 条の回折光条を生成。
- **`uv`（UV保護フィルター）**：最高級マルチコート `PRO_MRC`（透過率 `99.6%`）から廉価ノンコートガラス `CHEAP_POOR`（`89.5%` フレネル損失、ベールグレア、光軸対称 `(-x,-y)` の内面反射ゴースト、MTF微細低下、ソーダ石灰ガラスの色かぶり）まで物理再現。
- **`bokeh-mask`（入射瞳ボケ形状マスク）**：五芒星 `STAR_5`（`★5`）、ハート `HEART`（`♥`）、六角形 `HEXAGON`（`⬡`）、反射望遠リング `RING`（`◎`）、およびプラグイン `CUSTOM`。後処理スタンプではなくレンズの2D被写界深度ボケ畳み込みカーネル内で入射瞳透過率 `pupilWeight(ux, uy)` を評価するため、ピント面はシャープなままアウトフォーカスの点光源だけが美しいマスク形状に結像します（`opticsEnabled: true`・開放付近 `f/1.4`–`f/2.8`・`scene://star-bokeh` での確認が最適です）。

### 2.2 カスタムフィルター JAR・独自ボケマスク・Vulkan SPIR-V コンピュートシェーダー

`com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider` を実装し、`META-INF/services/com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider` を含む `.jar` を `<emulator-home>/filters/` に配置するだけで拡張できます。

1. **UIコントロール自動生成（`FilterParamSpec`）**：`parameterSchema()` で `NUMBER`・`ENUM`・`BOOLEAN`・`TEXT` パラメータを定義すると、スタジオのフィルターラックUIにスライダーやコンボボックスが自動生成されます。
2. **カスタム入射瞳ボケマスク（`OpticalPupilMask`）**：`OpticalFilterElement.pupilMask()` から `shape = BokehMaskShape.CUSTOM` のマスクを返すことで、単位円 `(ux, uy)` 上の解析的SDF、ラスタライズ済みSVGパス、または同梱グレースケールPNG画像のバイリニアサンプリングによる自由なボケ形状を実装できます（複数マスク有効時は `CompositeOpticalPupilMask` が透過率を乗算合成）。
3. **カスタム Vulkan 1.1 SPIR-V シェーダー（`CustomSpirvPassSpec` / `fuji_vulkan_custom_spv.h`）**：
   - `glslangValidator -V --target-env vulkan1.0 -S comp` でプリコンパイルした `.spv`（`20 B .. 256 KiB`、4バイト境界、マジック `0x07230203`、ワークグループ `1..64`）を `CustomSpirvPassSpec.fromResource(...)` でロードし、`CustomSpirvStage.PRE_OPTICS_FILTER` ステージにて `DEVICE_LOCAL` VRAM 上でゼロコピー実行。
   - `set = 0` 固定バインディング規約：`binding = 0`（`readonly SrcRgba`）、`binding = 1`（`writeonly DstRgba`）、`binding = 2`（`readonly DepthMeters`）、`binding = 3`（`std140 PluginUbo`：先頭32バイト標準ヘッダー `[width, height, focusM, fNumber, focalMm, frameIndex, flags, userBytes]` ＋ 最大 `4096 B` の `uniformData`）、および任意の `push_constant`（`<= 128 B`）。
   - 32エントリ SHA-256 LRU `VkPipeline` キャッシュにより、2フレーム目以降のコンパイル負荷はゼロ。
   - **必須CPUフォールバック契約**：`opticsBackend: cpu` 環境やシェーダー検証エラー時に備え、`applySpatialLinearRgb(...)`（ゼロアロケーション `OpticalFilterScratch` と `OpticalFilterContext` を利用）を**必ず併せて実装**してください。万一 `.spv` が不正でも `VkDevice` を破壊せず、`WARN [FALLBACK][vulkan]` を記録して安全にCPU実装へフォールバックします。

シェーダーとKotlinの完全な実装例は [`skills/create-optical-filter/SKILL.md`](../../skills/create-optical-filter/SKILL.md) を参照してください。

---

## 3. 明確な非目標（実装しない仕様境界 / Non-Goals）

自由な光学実験とクリーンな設計を守るため、本エミュレーターでは以下の仕様を**意図的に実装しない（Non-Goals）**と定めています。

1. **レンズ前枠の物理フィルター径（`ø62mm` / `ø77mm` / `ø82mm` 等）の一致制限は一切行いません**：
   光学フィルターが装着レンズのフィルターネジ径を検査・制限することは**永久にありません**。現実の撮影機材では口径ごとのステップアップリングや出目金広角レンズ専用ホルダーの管理が煩雑ですが、本エミュレーターのフィルターラックは**口径フリーのユニバーサル・マットボックス**として設計されており、どのレンズに対しても任意のフィルターを即座に組み合わせられます。
2. **フランジバック干渉や鏡筒の物理衝突によるマウント装着ロックは行いません**：
   35mm判レンズの `43.3 mm` イメージサークルによる中判センサー四隅のケラレなど、光学的・物理的な結像特性は忠実に再現しますが、創造的な異種マウント実験を妨げる人為的な装着禁止ロックは設けません。
3. **ISP処理後の2D画面オーバーレイによる「擬似光学エフェクト」は行いません**：
   ボケ形状やND減光、フレアをJPEG/sRGB画面上のスタンプ合成でごまかす実装は採用せず、必ずセンサー露光前のリニア放射輝度・瞳空間で計算します。
4. **プロプライエタリなファームウェアの改変・署名回避・実機書き換えツールは一切提供しません**：
   著作権で保護されたメーカー純正ファームウェアの同梱や、実機カメラの改造・フラッシュ書き換えを目的とした機能は一切含みません。

---

## 4. ボディ＆スタジオ UI テーマ（Theme System v2）

Theme System v2（`BodyTheme.kt`、`UiTheme.kt`、`PanelCatalog.kt`、`SvgArtwork.kt`）は、`<emulator-home>/themes/` 配下で外観を2つの独立レイヤーに分離し、自由な組み合わせとライブホットリロードを実現しています。

- **カメラボディテーマ（`themes/body/<id>/body-theme.json`）**：`1..12` 面のカメラパネル、ベクター／ハイブリッド外装アートワーク、操作ヒットボックス、ディスプレイビューポート、開閉式カバー、動的SVGバインディングを定義。標準搭載：`origin`（6面 GFX100S）、`hasselblad-x2d2`、`hasselblad-907x`、`compact-rangefinder`。
- **スタジオUIテーマ（`themes/ui/<id>/ui-theme.json` または `themes/ui/<id>.css`）**：ベース `/panel.css` 上に重ねるウィンドウ背景色と `-fuji-*` セマンティックCSSトークンを定義。標準搭載：`origin`（Origin Obsidian）、`nordic-minimal`、`daylight-studio`、`vintage-brass`、`olive-field`。

### 4.1 ベクター優先ハイブリッド外装と厳格なヒットボックス規約

- **アートワーク構成**：解像度に依存せずシャープに描画されるベクターSVG（`"svg": "rear.svg"`、`<= 256 KiB`、要素数 `<= 4096`、`viewBox="0 0 <width> <height>"` 厳密一致）を強く推奨します。SVG内では以下を組み合わせ可能です：
  - パック内ビットマップ部品の埋め込み（`<image href="artwork/mode-dial.png" .../>`、`<= 4 MiB`、`<= 4096×4096`）
  - リアルな革シボやヘアライン金属のタイルテクスチャ（`<pattern ... patternUnits="userSpaceOnUse" data-texture="textures/leatherette.png"/>`、`<= 2 MiB`、`<= 1024×1024`。内蔵テーマは `/themes/body/shared/{leatherette,brushed-chrome,xcd-glass}.png` も共有）
  - ファイル容量ゼロのプロシージャル微細凹凸テクスチャ（`data-generator="continuous-grain-v1"`、`1200×800` 固定、`data-kind` は `paint`・`rubber`・`brushed-metal`・`anodized`）
  - ドロップ／インナーシャドウ `data-effect="shadow"` / `"inner-shadow"`、`<clipPath>`、循環参照チェック付きローカル `<use href="#id"/>`
- **透明ヒットボックス規約**：`views[].controls` の各コントロールは、SVG内の `<g id="<id>" data-control-id="<id>" data-view-id="<viewId>">` と1対1で対応し、その直下に座標が完全一致する透明な `<rect data-hitbox="true" x="..." y="..." width="..." height="..." fill="none" stroke="none"/>` を**必ず1つだけ**持つ必要があります。
- **動的バインディング・ロール・開閉ギミック**：
  - `dynamicStates`：`rotate-map`（`soc-adc.modeDial` や `soc-gpio.focusMode` に応じて `pivotX`/`pivotY` を軸にダイヤル・レバーを回転）、`highlight-button`（`movieMode` や `power` の切り替えグラフィック制御）、`led-binding`（ステータスLED点灯表現）。
  - `studio-display-hotspot`：`kind: "display"`（`display.main` 背面タッチ／スワイプ、`display.evf` ダブルクリックで独立EVFウィンドウ起動、`display.shoulder` 反射型サブ液晶、`cornerRadius` や `clipShape: "circle"` 対応）に自動付与される透明インタラクティブ層。
  - `focus-mode-segments` ＆ `exposure-mode-button`：3ポジション `AF-S/AF-C/MF` セグメントスイッチ、および物理モードダイヤルを持たないモダンボディ向けの露出モード選択ボタン。
  - `data-control-proxy`：`<svg>` 直下に `transform` なしの透明 `<rect id="..." data-control-proxy="<対象ID>" ... fill="none" stroke="none"/>` を置くことで、`buttonKey` の重複制約を保ったまま別面のダイヤル等（例：上面から操作する `front-command-dial`）をプロキシ配置可能。
  - 開閉式カバー `covers`：`#<id>-closed` と `#<id>-open` のSVGグループに `kind: "cover"`・任意ラッチ `kind: "latch"`・`interaction: "cover-toggle"`・`inside`・`openHitbox` を組み合わせ、デュアルSDスロット扉やバッテリー蓋の開閉を再現。
- **セキュリティ境界とホットリロード**：外部パックは `toRealPath()` によりパックディレクトリ外へのパストラバーサル（`..`・シンボリックリンク脱出）を厳格に遮断し、`<script>`・インライン `style`・`on*`・外部URL・CSS `@import` を拒否します。ヘッダーの `↻` 更新ボタン、コンソール `/theme reload`、または `POST /api/theme/reload` で再起動なしに即時反映できます。詳細は [`skills/create-theme/SKILL.md`](../../skills/create-theme/SKILL.md) を参照してください。

---

## 5. 多言語ローカライズ（i18n）のベストプラクティス

デスクトップUI（`I18n.kt` および `desktop/src/main/resources/i18n/messages_*.properties`）、プラグインメタデータ（`PluginMetadata`）、テーマシステム（`ThemeLocalization`）は共通の**6言語ライブ切り替え基盤**（ヘッダー言語メニュー / `/lang` / `POST /api/lang`）で統一されています。

### 5.1 デフォルトで6言語すべてを提供することを推奨

カスタムレンズ JAR、光学フィルター JAR、ボディテーマ、UIテーマを作成・公開する際は、以下の**標準6言語**を揃えることを強く推奨します。

| 正規ロケールコード | 言語 | 役割 |
| --- | --- | --- |
| `zh_CN` | 中国語（簡体字 / 简体中文） | 推奨デフォルト言語 |
| `ja_JP` | 日本語 | 推奨デフォルト言語 |
| `en` | 英語（English） | **必須ベース＆フォールバック言語** |
| `de_DE` | ドイツ語（Deutsch） | 推奨デフォルト言語 |
| `fr_FR` | フランス語（Français） | 推奨デフォルト言語 |
| `es_ES` | スペイン語（Español） | 推奨デフォルト言語 |

### 5.2 フォールバック順序と UTF-8 エンコーディング規約

- **厳密な英語フォールバック**：`PluginMetadata` と `ThemeLocalization` はハイフンとアンダースコアを正規化（`ja-JP` と `ja_JP` を同一視）し、以下の順序で解決します。
  1. 完全一致ロケール（例：`ja_JP`）
  2. 2文字の基本言語プレフィックス（例：`ja`）
  3. 英語（`en`）またはトップレベルの英語 `name` / `description`
  未翻訳の言語や未対応ロケール環境でも、クラッシュや生キー露出を起こさず確実に英語表記へフォールバックします。
- **文字コード・マニフェスト記述規約**：
  - すべての `.json` マニフェスト、`META-INF/services/*`、Kotlin ソース、`messages_*.properties` は **BOMなし UTF-8** および **LF (`\n`) 改行**（末尾改行あり）で保存してください。`I18n.kt` は `InputStreamReader(..., StandardCharsets.UTF_8)` で直接 `.properties` を読み込むため、日本語・中国語・欧州アクセント文字を `\uXXXX` エスケープせずそのまま記述できます。
  - **Kotlin プラグイン**（`LensModule`、`MountAdapterModule`、`OpticalFilterElementProvider`）では、`PluginMetadata(names = ..., descriptions = ...)` と `FilterParamSpec(labels = ..., descriptions = ...)` に6言語マップを設定してください。
  - **テーママニフェスト**（`body-theme.json`、`ui-theme.json`）では、トップレベルの英語 `"name"`・`"description"` に加え、`"i18n": { "names": { ... }, "descriptions": { ... } }`、各パネルの `"titles"`、各コントロールの `"labels"` に6言語を記載してください。
