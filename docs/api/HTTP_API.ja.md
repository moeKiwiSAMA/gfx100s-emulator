# HTTP 制御＆テレメトリ API リファレンス

[English](HTTP_API.md) | [简体中文](HTTP_API.zh-CN.md) | **日本語**

FUJIFILM GFX100S エミュレーターには、外部ライブラリ依存ゼロのローカルループバック HTTP サーバー（`ControlServer` / `ControlApiSpec`、OpenAPI `3.1.0` 準拠）が組み込まれています。リアルタイムなハードウェア状態の取得、物理ボタン・タッチパネル・ダイヤルの自動操作、レンズ・光学フィルター・テーマのホットスワップ、およびヘッドレス自動テストの制御をすべて HTTP 経由で実行できます。

- **関連ガイド**：[拡張・MOD開発ガイド](../extending/EXTENDING.ja.md)
- **内蔵インタラクティブ API エクスプローラー**：エミュレーター起動中にブラウザで `http://127.0.0.1:8787/api/docs` を開くと、オフライン動作のインタラクティブ HTML ドキュメント（検索・Try-It 実行機能付き）を利用できます。OpenAPI 3.1.0 スキーマは `GET /api/openapi.json` から取得可能です（リポジトリ内にも `docs/api/http-openapi.json` と `docs/api/http-control-api.md` が同梱されています）。

---

## 1. サーバー有効化・設定・セキュリティ仕様

### 1.1 ポート設定（`configs/emulator.yaml` ＆ CLI）

GUI モードおよび `--headless` 対話モードのいずれでも、`configs/emulator.yaml`（または `--config <path>` で指定した YAML）の `httpPort` に従って `ControlServer` が自動起動します。

```yaml
# configs/emulator.yaml（リリース版・開発版の標準ポート：8787）
httpPort: 8787
```

- **標準ベース URL**：`http://127.0.0.1:8787`（`httpPort: 0` を指定すると OS が空きループバックポートを自動割り当てし、`logs/latest.log` に `Control API listening on /127.0.0.1:<port>` と記録されます）。
- *（※ `ControlApiSpec.DEFAULT_BASE_URL` の静的 OpenAPI サンプルでは例示用ポートとして `18080` が使われていますが、実際のスクリプトからは設定ファイルの `httpPort`［既定値 `8787`］へ接続してください。）*

### 1.2 ヘッドレス実行・プロファイラー・テレメトリ関連フラグ／環境変数

| フラグ / 設定キー | 指定箇所 | 動作内容 |
| --- | --- | --- |
| `--home <dir>` / `--config <yaml>` | CLI | エミュレーターのホームディレクトリおよび設定 YAML（既定 `configs/emulator.yaml`）を指定。 |
| `--headless --auto-boot --no-console` | CLI | JavaFX ウィンドウおよび標準入力コンソールなしでファームウェアを自動起動（CI や Python 自動化に最適）。 |
| `--profile` / `--profile-interval-ms <ms>` | CLI / `-Dfuji.profiler.enabled=true` | 起動時に `AsyncPerfProfiler` を自動開始（`emulator.yaml` の `profiler: { enabled: true, intervalMs: 5 }` でも設定可。`-Dfuji.profiler.exportFile=true` で終了時に `logs/` へ出力）。 |
| `--no-telemetry` | CLI / `-Dfuji.telemetry.disabled=true` | 最優先キルスイッチ。外部へのテレメトリ・ログ送信を完全に無効化します。 |
| `--telemetry-upload` | CLI / `-Dfuji.telemetry.upload=true` | テレメトリ送信先とトークンが設定されている場合に終了時のログ送信を有効化（`relaxed` モード限定。`discovery` / `strict` モードは常にネットワーク通信ゼロ）。 |
| `FUJI_TELEMETRY_URL` / `FUJI_INGEST_TOKEN` | 環境変数 | `emulator.yaml` の `telemetry.endpoint` / `telemetry.ingestToken` を上書き（公開ビルドでは既定で無効）。 |

### 1.3 ループバック保護・リクエスト制限・楽観的排他制御（`revision`）

- **ループバック専用バインド**：ソケットは常に `InetAddress.getLoopbackAddress()`（`127.0.0.1`）のみにバインドされ、外部ネットワークへは公開されません。
- **同一オリジン保護**：ブラウザ等から `Origin` ヘッダーが付与されたリクエストは、`http://127.0.0.1:<port>` または `http://localhost:<port>` と一致する場合のみ許可され、クロスオリジン要求は `400 {"error": "cross-origin control denied"}` で拒否されます（CORS ヘッダーは発行しません）。
- **ペイロード＆クエリ上限**：ボディを持つ書き込みリクエストには `Content-Type: application/json` が必須です。最大ボディサイズは **`64 KiB`（`65,536` バイト）**、クエリパラメータは最大 **`16` 個（重複不可）**です。すべてのレスポンスに `Cache-Control: no-store` が付与されます。
- **楽観的リビジョン制御（`PATCH /api/v1/components/{id}`）**：
  低レベルコンポーネント更新では HTTP `ETag` ヘッダーではなく、`GET /api/v1/state` が返す整数の `"revision"` フィールドを用います。
  ```json
  { "revision": 4, "changes": { "shutterHalf": true } }
  ```
  更新成功ごとに `"revision"` がインクリメントされます（`200 OK`）。古い `revision` を送信した場合は状態を破壊せず **`409 Conflict`**（`{"error": "revision conflict"}`）を返すため、`GET /api/v1/state` を再取得して安全にリトライできます。なお、`POST /api/lens`・`/api/filters`・`/api/adc`・`/api/shooting-mode` などの高レベル API はサーバー内部で自動的に最新 `revision` を解決します。

### 1.4 HTTP ステータスコードとエラー形式

すべてのエラーは `application/json; charset=utf-8` 形式で `{"error": "<メッセージ>"}` を返します。

| ステータス | 意味 |
| --- | --- |
| `200 OK` / `201 Created` | リクエスト成功（`POST /api/v1/dump` のみ `201`）。 |
| `400 Bad Request` | 不正な JSON、必須キー欠落・未知のキー、数値範囲外、`Content-Type: application/json` 欠落、`64 KiB` 超過、クエリ `> 16` 個、またはクロスオリジン拒否。 |
| `404 Not Found` | 未知のパス／メソッド、または現在の構成で対象コンポーネント／GUI コントローラーが無効（ヘッドレス時の `/api/theme` 等）。 |
| `409 Conflict` | `PATCH /api/v1/components/{id}` における楽観的ロック `"revision"` の不一致。 |
| `500` / `503` | 内部エラー、実行キュー飽和（`503`）、またはテレメトリ無効／送信失敗（`/api/telemetry/upload-log` の `503`）。 |

---

## 2. 全エンドポイント一覧（全39エンドポイント）

### 2.1 コア状態＆アドレスカタログ（Core & Catalog：4）

| メソッド＆パス | クエリ / リクエストボディ | レスポンス＆備考 |
| --- | --- | --- |
| `GET /api/v1/catalog` | クエリ（最大 `16` 個）：`address`（例 `0xfff70000`）、`subsystem`（`isp`、`dma`、`gpio`）、`query`（フリーワード）、`id`、`selector`、`space`、`width`、`direction`、`coverage=true`、`unknown=true`。 | `200` `{"count": N, "entries": [...]}`。GFX100S 2.13 ハードウェアアドレス辞書を検索。 |
| `GET /api/v1/state` | *（なし）* | `200` `{"components": [{"id", "revision", "config", "state"}, ...], "machine": {"status", "tick", "attempts", "screenId", ...}}`。全コンポーネント状態と実行テレメトリの一括スナップショット。 |
| `POST /api/v1/dump` | 空ボディまたは `{}`（`Content-Type: application/json`）。 | `201` `{"dump": "dump-..."}`（`<home>/dumps/` にメモリ・状態ダンプを出力）。 |
| `PATCH /api/v1/components/{id}` | パス `id`（`^[a-z][a-z0-9-]{0,63}$`）、ボディ `{"revision": <0以上の整数>, "changes": {<key>: <val>}}`。 | `200` 更新後のコンポーネント情報（`revision + 1`）。競合時は `409`。 |

**主な `PATCH /api/v1/components/{id}` 対象コンポーネント**：
- `buttons`（真偽値）：`power`、`shutterHalf`（半押し S1）、`shutterFull`（全押し S2）、`menu`、`back`、`up`、`down`、`left`、`right`、`play`、`delete`、`movieMode`、`drive`、`afOn`、`focusMode`、`rearDialPress`、`frontDialPress`、`fn4`、`focusStickPress`。
- `cmos`：`sourceKind`（`JPEG`、`PNG`、`SCENE_3D`、`VIDEO_FILE`、`WEBCAM`、`SCREEN_CAPTURE` 等）、`sourceFile`（`scene://qr-ball`、`scene://star-bokeh`、`scene://mona-lisa` またはファイルパス）、`loop`（真偽値）。
- `lens` / `optical-filter`：各光学パラメータに加え、`"rescanPlugins"` をインクリメントすることで `lenses/*.jar` / `filters/*.jar` を無停止再スキャン可能。

---

### 2.2 非同期パフォーマンスプロファイラー（Profiler：3）

| メソッド＆パス | クエリ / リクエストボディ | レスポンス |
| --- | --- | --- |
| `POST /api/profiler/start` | 任意クエリ `?intervalMs=5`（`>= 1`）または JSON `{"intervalMs": 5}`（ボディ優先）。 | `200` `{"running": true, "intervalMs": 5}`。 |
| `POST /api/profiler/stop` | 任意 `?format=json\|text\|txt`（または `Accept: text/plain`）。 | `200` プロファイリングを停止し、JSON またはプレーンテキストのホットスポットレポートを返却。 |
| `GET /api/profiler/report` | 任意 `?format=json\|text\|txt`（または `Accept: text/plain`）。 | `200` 計測を継続したまま最新レポートを取得。 |

---

### 2.3 タッチ・ダイヤル・ボタン・ファインダー制御（Touch & Controls：11）

| メソッド＆パス | リクエスト JSON スキーマ | レスポンス概要 |
| --- | --- | --- |
| `POST /api/touch` | **必須3キー固定**：`{"x": 0..65535, "y": 0..65535, "pressed": true\|false}`（背面液晶のネイティブ座標範囲は `x: 0..1023`、`y: 0..767`）。 | `200` `{"ok": true, "component": "rear-touch-ic", "x", "y", "pressed", "revision"}`。 |
| `POST /api/touch/swipe` | 方向プリセット `"direction": "up"\|"down"\|"left"\|"right"` **または**明示座標 `"x1", "y1", "x2", "y2"` の**どちらか一方**（混在不可）、任意で `"steps"`（`1..8`、既定 `4`）・`"stepMs"`（`5..100`、既定 `20`）。 | `200` LiveView の T-Fn フリックおよび再生画面のコマ送りスワイプに最適化されたマルチステップ操作を実行。 |
| `GET /api/eye-sensor` | *（なし）* | `200` `tmd2772` アイセンサー状態 `near`、現在の有効表示先 `activeViewfinder`（`LCD`\|`EVF`\|`BOTH`\|`WAITING`）、フレームシーケンスを返却。 |
| `POST /api/eye-sensor` | **必須単一キー**：`{"near": true\|false}`。 | `200` アイセンサー接近／離脱を切り替え、EVF／背面LCDの自動切り替えを駆動。 |
| `GET /api/adc` | *（なし）* | `200` `soc-adc` のボタン・モードダイヤル・リアダイヤル・ADCサンプル値を取得。 |
| `POST /api/adc` | 非空サブセット：真偽値 `qButton`、`aelButton`、`fn1Button`..`fn3Button`、`lightButton`；文字列 `modeDial`（`default`、`P`、`S`、`A`、`M`、`C1`..`C6`）；整数 `rearDialPosition`（`-1..3`）、`rearDialDetents`（`-10000..10000`）、相対ステップ **`rearDialStep`**（`-1000..1000`）、生12bitオーバーライド `ch3Override`/`ch5Override`/`ch6Override`/`ch7Override`/`ch11Override`（`-1..4095`、`-1` で解除）。 | `200` `{"ok": true, "component": "soc-adc", "revision", "changes": {...}}`。 |
| `GET /api/focus-mode` | *（なし）* | `200` `soc-gpio` の `focusMode` と `effectiveFocusMode` を取得。 |
| `POST /api/focus-mode` | **単一キー** `focusMode`（別名 `mode`）：`"AF-S"`（`"AFS"`/`"S"`）、`"AF-C"`（`"AFC"`/`"C"`）、`"MF"`（`"M"`）、`"default"`。 | `200` ボディの `S/C/M` フォーカスモードレバーを変更。 |
| `GET /api/shooting-mode` | *（なし）* | `200` `mode`（`STILL`\|`MOVIE`）、`movieMode`、`modeDial`、`focusMode` を一括取得。 |
| `POST /api/shooting-mode` | 非空サブセット：`mode`（`STILL`、`PHOTO`、`MOVIE`、`VIDEO`）、`movieMode`（真偽値）、`modeDial`、`focusMode`。 | `200` STILL/MOVIE スイッチ・モードダイヤル・フォーカスモードを一括更新。 |
| `POST /api/view-mode` | **必須単一キー**：`{"pressed": true\|false}`（GPIO Bank 7 Pin 6）。 | `200` `VIEW MODE` ボタンの押下／解放を実行。 |

---

### 2.4 レンズ＆光学フィルター制御（Optics & Filters：6）

| メソッド＆パス | リクエスト JSON スキーマ | レスポンス概要 |
| --- | --- | --- |
| `GET /api/lens` | *（なし）* | `200` 装着中レンズのテンプレート・通信モード・絞りリングモード（`A`/`C`/`MANUAL`）・実効F値・焦点距離・ピント距離・OIS・光学計算フラグ・マウント状態を返却。 |
| `POST /api/lens` | 非空サブセット：`template`（`gf63`、`gf110`、`gf50`、`gf32-64`、`gf45-100`、`gf20-35`、`canon-ef85-f14l`、`takumar-50-f14`、`zeiss-planar-80-f28`、`custom` 等）、`adapterId`（`adapter-fringer-ef-gfx`、`adapter-m42-gfx`、`adapter-hasselblad-v-gfx`）、`communicationMode`（`GENERIC_SIMULATED`、`MANUAL`、`REAL_FIRMWARE`）、`realFirmwareFile`、`apertureRingMode`（`A`/`C`/`MANUAL`）、`aperture`、`focalLengthMm`、`focusDistanceM`、`subjectDistanceM`、`focusMode`、`oisEnabled`、`opticsEnabled`、`electronicContacts`、`afTrigger`。 | `200` レンズ／アダプターのホットスワップや絞り・焦点距離・ピント位置のライブ変更。 |
| `GET /api/filters` | *（なし）* | `200` `optical-filter` の設定・スロット一覧・総合透過率・検出済みプラグイン一覧を返却。 |
| `POST /api/filters` | `clear: true`（別名 `off: true`）；`enabled: bool`；順序付き `slots` 配列（最大 `12` スロットの `{"type": "...", "enabled": true, ...}` または JSON 文字列）；フラット設定キー（`ndPreset`、`ndStops`、`uvPreset`、`uvTransmittance`、`uvVeilingGlare`、`uvGhostIntensity`、`uvSoftness`、`uvTintR/G/B`、`bokehMask`［`OFF`/`STAR_5`/`HEART`/`HEXAGON`/`RING`/`CUSTOM`］、`bokehMaskAngleDeg`、`bokehMaskScale`、`star*`、`mist*`、`gnd*`）；または短縮オブジェクト `uv`・`nd`・`bokeh`・`star`・`mist`・`gnd`。 | `200` 更新後の光学フィルタースタック状態。 |
| `GET /api/nd` | *（なし）* | `200` NDフィルター専用メトリクス（段数・減光倍数・光学濃度・透過率％・要約ラベル）。 |
| `POST /api/nd` | `off: true`、`enabled`、`preset`/`ndPreset`（`OFF`、`ND2`..`ND1000`、`VARIABLE`）、`stops`/`ndStops`（`0.0..10.0`）。 | `200` 更新後のNDメトリクス。 |

---

### 2.5 オーディオ・無線・テレメトリ・UI言語／テーマ・NOR Flash（13）

| カテゴリ | メソッド＆パス | リクエスト / パラメータ | レスポンス＆動作 |
| --- | --- | --- | --- |
| **Audio** | `GET /api/audio` | *（なし）* | `200` WM8962＋ホストオーディオの音量・ミュート・バッファ・ヘッドホン／マイク端子状態。 |
| **Audio** | `GET /api/audio/devices` | *（なし）* | `200` ホストの入出力オーディオデバイス一覧 `{"outputs": [...], "inputs": [...]}`。 |
| **Audio** | `POST /api/audio` | 非空サブセット：`enabled`、`volume`（`0.0..1.0`）、`muted`、`outputDeviceId`、`bufferMs`（`>= 4`）、`backendPreference`（`auto`/`native`/`javax`/`silent`）、`headphonePlugged`、`inputMode`（`SILENCE`/`TEST_TONE`/`HOST_MIC`）、`inputDeviceId`、`inputGainDb`、`inputMuted`、`inputMonitor`、`testToneHz`、`extMicPlugged`。 | `200` オーディオミキサー・テストトーン・3.5mmプラグ挿入状態をライブ更新。 |
| **Wireless** | `GET /api/wireless` | *（なし）* | `200` BCM43438 Wi-Fi/BLE 状態（`deviceName`、`blePeerConnected`、`wifiPeerConnected`、`ptpIpBridgeEnabled`）。 |
| **Wireless** | `POST /api/wireless` | 非空サブセット：`deviceName`、`blePeerConnected`、`wifiPeerConnected`、`ptpIpBridgeEnabled`（`relaxed` モード）。 | `200` 仮想モバイルピア接続や PTP-IP ブリッジを更新。 |
| **Telemetry** | `POST /api/telemetry/upload-log` | 任意 `?notes=...` または JSON `{"notes": "..."}`。 | テレメトリ有効時は `200`（`uploaded: true`）、無効／未接続時は `503`。 |
| **UI 言語** | `GET /api/lang` | *（なし）* | `200` `{"language": "en", "supported": ["en","zh_CN","ja_JP","de_DE","fr_FR","es_ES"]}`。 |
| **UI 言語** | `POST /api/lang` | **単一キー** `language`（別名 `lang`）：`en`、`zh_CN`、`ja_JP`、`de_DE`、`fr_FR`、`es_ES`。 | `200` 再起動なしでデスクトップUI言語を即時切り替え＆保存。 |
| **テーマ** | `GET /api/theme` | *（なし）* | `200` 現在の `body`・`ui` テーマと利用可能なパック一覧（ヘッドレス時は `404`）。 |
| **テーマ** | `POST /api/theme` | 非空サブセット：`{"body": "<id>", "ui": "<id>"}`（内蔵ボディ：`origin`、`hasselblad-x2d2`、`hasselblad-907x`、`compact-rangefinder`／内蔵UI：`origin`、`nordic-minimal`、`daylight-studio`、`vintage-brass`、`olive-field`）。 | `200` ボディ外装とスタジオCSSテーマを全ウィンドウへ即時反映。 |
| **テーマ** | `POST /api/theme/reload` | 空ボディまたは `{}`。 | `200` ディスク上の `themes/body/` と `themes/ui/` を再スキャンしてホットリロード。 |
| **NOR Flash** | `GET /api/flash` | *（なし）* | `200` 64 MiB QSPI NOR Flash のパス・起動経路（`bootPath`）・SHA-256・消去／書込回数を返却。 |
| **NOR Flash** | `POST /api/flash` | `{"action": "write-dat"\|"export"\|"import"\|"restore-factory", "path": "...", "preserveCalibration": true}`。 | `200` `.DAT` 書き込み・RAWエクスポート／インポート・工場出荷状態復元を実行（変更操作後は自動でコールドリブート）。 |

*（加えて自己ドキュメント配信用の `GET /api/openapi.json` と `GET /api/docs` を備えています。）*

---

## 3. 実践的な `curl` ＆ Python 自動化サンプル

以下の例では標準ポート `http://127.0.0.1:8787` を使用します（`export FUJI_API=http://127.0.0.1:8787`）。

### 3.1 よく使う `curl` レシピ

```bash
export FUJI_API="http://127.0.0.1:8787"

# 1. マシン実行テレメトリ（仮想クロック tick・画面IDなど）を取得
curl -s "$FUJI_API/api/v1/state" | jq '.machine'

# 2. 静止画モード（STILL）・マニュアル露出（M）・マニュアルフォーカス（MF）へ切り替え
curl -s -X POST "$FUJI_API/api/shooting-mode" \
  -H "Content-Type: application/json" \
  -d '{"mode": "STILL", "modeDial": "M", "focusMode": "MF"}'

# 3. GF 110mm F2 R LM WR を装着し、光学シミュレーション有効・開放 F2.0・ピント 1.2m に設定
curl -s -X POST "$FUJI_API/api/lens" \
  -H "Content-Type: application/json" \
  -d '{"template": "gf110", "opticsEnabled": true, "apertureRingMode": "MANUAL", "aperture": 2.0, "focusDistanceM": 1.2}'

# 4. マルチスロット・フィルター装着：Pro MRC UV + ND8 + ブラックミスト 1/4 + 五芒星ボケマスク
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

# 5. リアコマンドダイヤルを右へ3クリック回転
curl -s -X POST "$FUJI_API/api/adc" \
  -H "Content-Type: application/json" \
  -d '{"rearDialStep": 3}'

# 6. 外部テーマを再読み込みし、Hasselblad X2D II ボディ＋Nordic Minimal UI＋日本語表示に切り替え
curl -s -X POST "$FUJI_API/api/theme/reload" -H "Content-Type: application/json" -d '{}'
curl -s -X POST "$FUJI_API/api/theme" -H "Content-Type: application/json" -d '{"body": "hasselblad-x2d2", "ui": "nordic-minimal"}'
curl -s -X POST "$FUJI_API/api/lang" -H "Content-Type: application/json" -d '{"language": "ja_JP"}'
```

### 3.2 Python 自動化スクリプト：`409` リビジョン再試行付き2段シャッター＆フォーカスブラケット撮影

標準ライブラリのみで動作する以下の Python 3 スクリプトは、`PATCH /api/v1/components/buttons` の `409 Conflict` 楽観的ロック再試行を適切に処理しながら、実機同様の2段シャッター（`S1` 半押し → `S2` 全押し 650ms 保持 → 解放）で3点フォーカスブラケット撮影を自動実行します。

```python
#!/usr/bin/env python3
"""GFX100S ループバック HTTP API を用いた自動フォーカスブラケット撮影スクリプト（外部依存なし）。"""

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
    """HTTP 409 リビジョン競合時に自動で最新 revision を再取得してリトライするヘルパー。"""
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
    raise RuntimeError(f"{component_id} のリビジョン再試行上限 ({max_retries}回) を超過しました")


def trigger_shutter(s1_lead_sec: float = 0.12, s2_hold_sec: float = 0.65) -> None:
    """2段シャッターパルス：S1 半押し -> S1+S2 全押し保持 -> リリース。"""
    patch_component("buttons", {"shutterHalf": True, "shutterFull": False})
    time.sleep(s1_lead_sec)
    patch_component("buttons", {"shutterHalf": True, "shutterFull": True})
    time.sleep(s2_hold_sec)
    patch_component("buttons", {"shutterHalf": False, "shutterFull": False})


if __name__ == "__main__":
    # 静止画・絞り優先オート(A)・MFに設定し、GF 63mm F2.8 を開放 F2.8 で装着
    api_request("POST", "/api/shooting-mode", {"mode": "STILL", "modeDial": "A", "focusMode": "MF"})
    api_request("POST", "/api/lens", {"template": "gf63", "opticsEnabled": True, "aperture": 2.8})

    for distance_m in (0.8, 1.4, 2.5):
        lens_info = api_request("POST", "/api/lens", {"focusDistanceM": distance_m})
        print(f"[Focus] {lens_info['model']} -> {lens_info['focusDistanceM']} m")
        time.sleep(0.4)
        trigger_shutter()
        print(f"[Shot] {distance_m} m でレリーズ完了。SDカードへの書き込み待機中...")
        time.sleep(1.5)
```
