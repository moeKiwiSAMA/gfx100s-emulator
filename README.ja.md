<div align="center">

# FUJIFILM GFX100S Emulator

*MEDIUM FORMAT SYSTEM EMULATOR · 富士フイルム GFX100S エミュレータ*

[English](README.md) | [简体中文](README.zh-CN.md) | **日本語**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform: Windows x86_64 | Linux x86_64](https://img.shields.io/badge/Platform-Windows%20x86__64%20%7C%20Linux%20x86__64-informational)
![Firmware: GFX100S v2.13](https://img.shields.io/badge/Firmware-GFX100S%20v2.13-black)
![Zero Proprietary Binaries](https://img.shields.io/badge/Binaries-Zero%20Proprietary%20Included-success)

*AArch32 4 コア SMP Dynarmic JIT · Vulkan 1.1 コンピュテーショナル光学 · 6 面タクタイルカメラスタジオ*

</div>

---

> [!CAUTION]
> **独立クリーンルーム実装および AI 支援開発に関する免責事項**
>
> 本プロジェクトは、特定の企業・団体と無関係な独立クリーンルーム実装であり、開発には AI 支援エンジニアリング（いわゆる "AI-slop" 的な粗さや癖も含みます）を多用しています。リポジトリ内に富士フイルム製のプロプライエタリなファームウェア、BootROM、工場出荷時キャリブレーションバイナリは**一切含まれていません**。
>
> エミュレートされた SoC ペリフェラル、割り込みタイミング、および光学・ISP モデルは、PC 上でカメラ OS を起動し操作感を再現するための近似実装であり、**実機 GFX100S のシリコン挙動とは異なる場合があります**。
>
> 本コードベースを公式ハードウェア仕様書として扱わないでください。本エミュレータやそこから得た知見をもとに実機のリバースエンジニアリング、カスタムファームウェア改変、ハードウェア実験などを行った結果、**実機の文鎮化（ブリック）、工場キャリブレーションデータの消失、保証の失効、その他いかなる損傷・データ損失・経済的損失が生じたとしても、作者およびコントリビューターは一切の責任を負いません。すべて自己責任にてご使用ください。**

---

> **初回公開バージョンについて：** 本リリースは**最初の公開バージョン**です。まだ大小さまざまなバグや粗削りな部分がたくさん残っていますが、ダイヤルを回し、レンズを付け替え、シャッターを切るだけでもすでに十分すぎるほど楽しいので、思い切って先にお披露目することにしました。おかしな挙動や不具合を見かけたら、ぜひログを添えて Issue で教えてください！

## *ラージフォーマットへのラブレター — 写真機としての歓び*

いつの頃からだろうか、富士フイルムという名前を耳にすると、冷たいスペック表より先に、どこか粒子を帯びた懐かしい時間が浮かんでくる。午後のブラインド越しに漂う埃や、雨上がりの路地に滲むひんやりとした反射、あるいは引き出しの奥から出てきた、縁の少し黄ばんだプリントのような気配。GFX の魅力もまさにそこにある。片側には、暗部と明部のあいだの微かな息づかいまで受け止めてしまう 1 億画素ラージフォーマットの底知れない懐の深さがあり、もう片側には、連写を急かすことなく、あなたが顔を上げてちょうどいい光が落ちるのを静かに待ってくれるような、昔ながらの佇まいと節度が残されている。そして何より心を掴むのは、「写真機を手に持つ歓び」を手放さなかったことだ。確かなクリック感で回る物理ダイヤル、指先に応える絞りリングの節度、一呼吸おいて押し込む二段式シャッター、そして 90 年近くに及ぶ色づくりの記憶が結晶した PROVIA、Velvia、ASTIA、CLASSIC Chrome、ACROS、ETERNA —— そこでは光がただ記録されるのではなく、記憶としてそっと定着する。

このエミュレータは、そんな静かな偏愛をコードの中にそっと閉じ込めたものです：

- **光への憧れは、いつか本物の富士フイルムカメラの手触りへと辿り着いてほしい。** 画面の上にどれほど精巧な像を描いても、掌に伝わる冷たいマグネシウム合金の質感や、GF レンズの絞りリングが刻むしっとりとした抵抗感、本物のガラスが本物の光を集める瞬間の代わりにはならない。
- **実機を手に迎えるその日まで、机の上の小さな窓がひと足先に開いてくれる。** まだ予算が途上にあっても、富士フイルムのラージフォーマットが持つ操作の呼吸やメニューの思想、光学の味わいは、手元でそっと馴染ませておくことができる。
- **すでに GFX を手元に持つ人にとっては、静かで安全な実験室になる。** 実機を傷める心配もなく、ダイヤルとシャッターの奥を覗き込んでみれば、いま使っているカメラの思いがけない一面に出会えるかもしれない。

---

## 対応プラットフォームと動作要件

本エミュレータは `x86_64` アーキテクチャ向けに最適化した JIT コンパイラと SIMD 画像処理ブリッジに依存しています。ダウンロード前に環境をご確認ください：

| 項目 | 要件 | 備考 |
|---|---|---|
| **OS・アーキテクチャ** | **64 ビット `x86_64`（`amd64`）Windows 10/11 および Linux のみ** | **Apple Silicon（`macOS arm64`）および Linux `aarch64` は非対応です。** `fuji_dynarmic` とネイティブ SIMD 画像ブリッジが `x86_64` の実行パスに直接最適化されています。 |
| **CPU 命令セット** | **`AVX2` または `AVX-512` を強く推奨** | 4 コア ARMv7-A Dynarmic JIT、SIMD レンダラ、CPU ISP、空間光学フィルタブリッジが広範に使用します（一部 SSE2 フォールバックがありますが、現代の `AVX2`/`AVX-512` 対応 CPU を基準に設計しています）。 |
| **GPU（推奨）** | **Vulkan 1.1+ 対応 GPU**（Windows `vulkan-1.dll` / Linux `libvulkan.so.1`） | リアルタイム GPU 3D シーンラスタライズ、2×2 ピラミッドボケ畳み込み、光学フィルタ用コンピュートシェーダが有効になります。非対応環境では自動的にマルチスレッド SIMD CPU 光学パイプラインへフォールバックします（ログに `WARN [FALLBACK][vulkan]` を出力）。 |
| **メモリ** | **8 GB 以上のシステム RAM を推奨** | GFX100S の 40 ビット LPDDR4 空間をオフヒープで確保し、あわせて JVM デスクトップヒープ `-Xmx2g` を使用します。 |

---

## 主な特徴

- **無改変 GFX100S v2.13 ファームウェア実行**：4 コア SMP ARMv7-A + NEON/VFPv4 **Dynarmic JIT**（`cpuEngine: dynarmic`）上で富士フイルム公式 GFX100S v2.13 ファームウェア（バイナリパッチ `0` 箇所）をそのまま起動します。64 MiB QSPI NOR Flash（`MT25QU512ABB` セクタ消去・ページ書き込みセマンティクス）、Sub-MCU コイン電池 NVRAM、102MP 静止画撮影（外部依存ゼロのマルチスレッド C 製 JPEG エンコーダ、`LSRAW` ロスレス/圧縮 RAW、400MP ピクセルシフトマルチショット、カメラ内 RAW 現像）、1080p H.264/HEVC `.MOV` 記録・再生を実装しています。
- **6 面タクタイルカメラスタジオ & Theme System v2**：背面・上面・前面・左右側面端子カバー・デュアル SD / バッテリー蓋まで操作可能な 6 面ビュー、肩部サブ液晶、枠なし最前面表示の**フローティング EVF**、回転する**モードダイヤル（`P/S/A/M/C1..C6`）**、**`S/C/M` フォーカスレバー**、二段式 **`S1`/`S2` シャッター**を搭載。4 種類の内蔵ボディテーマ（**GFX100S**、**Hasselblad X2D II 100C**、**Hasselblad 907X & CFV 100C**、**Compact Rangefinder**）、5 種類の UI CSS テーマ、6 言語の即時切り替え（`en`、`zh_CN`、`ja_JP`、`de_DE`、`fr_FR`、`es_ES`）に対応しています。
- **モジュラーレンズ、ユニバーサル光学フィルタスタック & Vulkan 1.1 光学系**：ホットスワップ可能な 12 本の内蔵 GF / マウントアダプタークラシックレンズ（`lenses/*.jar` による双方向 `LENSCOM` 絞り・フォーカス・OIS 同期、フォーカスブリージング、軸上色収差 LoCA、口径食）、12 スロットの汎用マットボックス光学フィルタスタック（ND、GND、ブラックミスト、クロス、物理 UV、各種・カスタムボケ瞳マスク、カスタム `.spv` シェーダ）、5 軸手ブレ補正と縦位置センサー、5 種類の内蔵 3D 深度シーン（`qr-ball`、`star-bokeh`、`mona-lisa`、音声同期付き `milk-frog`、`depth-studio`）、さらに Web カメラ・動画/ストリーム入力・画面/ゲームキャプチャ・全二重ホストオーディオ（`WASAPI` / `PipeWire` / `PulseAudio` ループバック）を備えています。

---

## クイックスタート

### 方法 A — ワンクリック・ポータブル版（推奨・Java インストール不要）

ポータブル配布パッケージには軽量化した **JDK 25（`jlink`）ランタイム**と全 `x86_64` ネイティブライブラリが同梱されています。Java や CMake、コンパイラを別途インストールする必要は**ありません**。

1. **お使いの OS 用ポータブル版をダウンロード**：
   - **安定リリース**：[**GitHub Releases**](https://github.com/moeKiwiSAMA/gfx100s-emulator/releases)
   - **最新 CI ビルド**：[**GitHub Actions**](https://github.com/moeKiwiSAMA/gfx100s-emulator/actions) → 最新の緑色 **Build & Package** ワークフローを開き、**Artifacts** から `gfx100s-emulator-windows-x86_64` または `gfx100s-emulator-linux-x86_64` をダウンロード *（Actions アーティファクトのダウンロードには GitHub へのサインインが必要です）*。
2. **富士フイルム公式 GFX100S v2.13 ファームウェア（`GXUP0004.DAT`）を入手** — 下の[ファームウェアと任意の実機ダンプ](#ファームウェアと任意の実機ダンプ)を参照してください。
3. **展開して起動**：
   - **Windows（`x86_64`）**：`.zip` を任意のフォルダに展開し、**`fuji-emulator-gfx100s.exe`**（または **`start-gfx100s.bat`**）をダブルクリックします。
   - **Linux（`x86_64`）**：アーカイブを展開して **`./start-gfx100s.sh`**（`app-image` の場合は **`./bin/fuji-emulator-gfx100s`**）を実行します。
4. **初回起動時のファームウェア導入**：
   - `flash/nor-flash.bin` が未作成の場合、**ファームウェア設定ダイアログ**が開きますので、ダウンロードした `GXUP0004.DAT` を選択してください（上部メニュー **Flash → Flash Firmware (.DAT)...** や `--flash-dat <GXUP0004.DATのパス>` でも指定できます）。64 MiB の `flash/nor-flash.bin` が生成され、そのまま GFX100S 初回起動時の言語・時計設定画面へ進みます。

### 方法 B — ソースからのビルド（開発者・Mod 制作者向け）

前提環境：**Git**、**Java 17+**（ブートストラップ用；`sh jvm.sh setup-jvm` が **JDK 25** を自動取得するか、既存の JDK 25 に `JAVA_HOME` を向けてください）、および **C/C++20 + CMake + Ninja** ツールチェーン（Linux は `gcc`/`g++`、Windows は MSYS2 MinGW-w64 またはクロスビルド用の `x86_64-w64-mingw32-g++`）。

```bash
# 1. .tools/ に JDK 25 を自動セットアップ（システムに JDK 25 がある場合は JAVA_HOME を設定）
sh jvm.sh setup-jvm

# 2. 必須の x86_64 ネイティブブリッジをすべてビルド（Dynarmic JIT、オーディオ、SIMD 描画/ISP、Vulkan 光学、コーデック、ゲームパッド）
./gradlew --no-daemon \
  :cpu-dynarmic:buildDynarmicNative \
  :buildAudioNative \
  :buildRenderNative \
  :buildVulkanOpticsNative \
  :buildIspSimdNative \
  :buildCodecNative \
  :buildGamepadNative

# 3a. 自己完結型の Linux ポータブル版をパッケージ（クリーン構成、プロプライエタリバイナリなし）：
./gradlew --no-daemon :packageReleaseLinux -PfujiBundleStockBins=false
# -> build/distributions/ に出力されます

# 3b. または local/runtime/ に開発用ランタイムを配置して起動：
sh jvm.sh prepare
sh jvm.sh launch
```

*（Git Bash を使わない Windows PowerShell / CMD では `java tools/EmulatorTools.java setup-jvm` と `.\gradlew.bat ...` を使用してください。）*

---

## ファームウェアと任意の実機ダンプ

### 必須 — 富士フイルム公式 GFX100S ファームウェア Ver. 2.13（`GXUP0004.DAT`）

1. 富士フイルム公式サポートページから **`GXUP0004.DAT`** をダウンロードします：
   <https://www.fujifilm-x.com/global/support/download/firmware/cameras/gfx100s/>
2. 書き込み前に **SHA-256** ハッシュ値を確認してください：
   ```text
   ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12
   ```
   - **Windows PowerShell**：`Get-FileHash .\GXUP0004.DAT -Algorithm SHA256`
   - **Linux**：`sha256sum GXUP0004.DAT`
3. 初回起動ダイアログ、メニュー **Flash → Flash Firmware (.DAT)...**、またはコマンドラインから `<エミュレータフォルダ>/flash/nor-flash.bin` へ導入します：
   - **Windows**：`.\fuji-emulator-gfx100s.exe --flash-dat path\to\GXUP0004.DAT`
   - **Linux**：`./start-gfx100s.sh --flash-dat /path/to/GXUP0004.DAT`

### 任意 — 実機 `bootrom` & `flash` ダンプ（シリコン完全コールドブート向け）

標準設定では、公式 `GXUP0004.DAT` のみを用いた **Flash-Direct** 方式で問題なく起動します。**実機ダンプは一切必須ではありません。**

GFX100S 実機をお持ちの上級ユーザーは、[**fujimod**](https://github.com/justjake/fujimod)（[fujimod.com](https://fujimod.com)）や [**FujiHack**](https://github.com/fujihack/fujihack) などのコミュニティ製 SD カード研究ツールを使い、32 KiB マスク BootROM（`bootrom/boot-rom.bin`、SHA-256 `2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4`、`--boot-rom` または `configs/emulator.yaml` の `bootRomBoot: true` で有効化）および 64 MiB QSPI NOR Flash（`flash/nor-flash.bin`）を任意で抽出することもできます。

> [!WARNING]
> ダンプ前には必ずカメラ本体で**初期化（`MENU → セットアップ → ユーザー設定 → リセット → 初期化`）**を実行し、**個人固有の生の NOR Flash ダンプは絶対に公開しないでください**（`[0x03500000..0x03D40000)` / `bank-14` 領域にはカメラ固有のシリアル番号、Wi-Fi/BLE MAC アドレス、工場出荷時センサーキャリブレーション情報が含まれています）。実機上でカスタムダンプ用ペイロードを実行することには文鎮化や保証対象外となるリスクが伴い、**すべてご自身の責任となります**。率直に申し上げれば、**すでにサニタイズ済みのダンプを取得している信頼できる友人にお願いする**のが最も確実です。

詳細な手順はこちら：**[`docs/FIRMWARE.ja.md`](docs/FIRMWARE.ja.md)**

---

## ドキュメント・拡張開発・AI Skills ハブ

| ガイド | 言語 | 内容 |
|---|---|---|
| **ファームウェア & ダンプ** | [EN](docs/FIRMWARE.md) · [中文](docs/FIRMWARE.zh-CN.md) · [日本語](docs/FIRMWARE.ja.md) | `GXUP0004.DAT` v2.13 検証、NOR Flash 構築と任意の実機ダンプ |
| **ユーザーマニュアル** | [EN](docs/manual/USER_MANUAL.md) · [中文](docs/manual/USER_MANUAL.zh-CN.md) · [日本語](docs/manual/USER_MANUAL.ja.md) | 6 面スタジオ、テーマ、ダイヤル、レンズ、フィルタ、3D、SD、音声 |
| **拡張 & Mod 開発ガイド** | [EN](docs/extending/EXTENDING.md) · [中文](docs/extending/EXTENDING.zh-CN.md) · [日本語](docs/extending/EXTENDING.ja.md) | レンズ JAR、フィルタ / `.spv` シェーダ、Theme v2、非目標と 6 言語 i18n |
| **HTTP 制御 & テレメトリ API** | [EN](docs/api/HTTP_API.md) · [中文](docs/api/HTTP_API.zh-CN.md) · [日本語](docs/api/HTTP_API.ja.md) | ローカル `127.0.0.1:8787` REST 自動化と Python/`curl` 例 |

### AI コーディングエージェント向け公開 Skills（`skills/`）

Claude Code、Gemini CLI / Antigravity、Codex、Cursor などの AI エージェントがそのまま参照できる `SKILL.md` 仕様書です：

- [`skills/create-lens/SKILL.md`](skills/create-lens/SKILL.md) — カスタム純正・アダプターレンズ JAR の作成とキャリブレーション（厳選した光学ベンチ情報源一覧付き）
- [`skills/create-optical-filter/SKILL.md`](skills/create-optical-filter/SKILL.md) — カスタム光学フィルタ要素、異形ボケ瞳マスク、Vulkan `.spv` コンピュートパスの作成
- [`skills/create-theme/SKILL.md`](skills/create-theme/SKILL.md) — カスタムカメラボディテーマ（PNG+SVG ハイブリッド、回転ダイヤル、LED）および UI CSS テーマの設計

---

## バグ報告とログの添付について

不具合の報告や実機挙動との比較フィードバックは [**GitHub Issues**](https://github.com/moeKiwiSAMA/gfx100s-emulator/issues) にて歓迎します。Issue を立てる際は、起動ごとに自動ローテーションされるプレーン UTF-8 デバッグログ（`DEBUG` しきい値）を添付してください：

- **Windows（ポータブル版）**：`<エミュレータフォルダ>\logs\latest.log` *（`fuji-emulator-gfx100s.exe` と同じフォルダ内；過去のログは `logs\yyyy-MM-dd-N.log.gz` に保存されます）*
- **Linux（ポータブル版）**：`<エミュレータフォルダ>/logs/latest.log` *（`start-gfx100s.sh` / `bin/fuji-emulator-gfx100s` と同じフォルダ内；過去のログは `logs/yyyy-MM-dd-N.log.gz` に保存されます）*
- **ソースビルド（`sh jvm.sh launch` / `./gradlew :desktop:run`）**：`<runtime-home>/logs/latest.log` *（例：`local/runtime/logs/latest.log` または `./logs/latest.log`、あるいは `-Dfuji.log.dir=<path>` で指定した先）*

公開 Issue にはプロプライエタリなファームウェアバイナリや個人カメラのシリアル番号・MAC アドレスダンプを絶対に添付しないでください。

---

## ライセンスと商用利用に関する心からのお願い

本プロジェクトは [**MIT License**](LICENSE) のもとで公開されています（`Copyright (c) 2026 moeKiwiSAMA`）。サードパーティ製オープンソースコンポーネント（`0BSD` の Dynarmic、`LGPL-2.1+` の単体 FFmpeg 再生バイナリおよび対応ソースアーカイブ、`GPLv2+CE` の OpenJDK/OpenJFX 25 など）の表記は [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) をご覧ください。

MIT ライセンスは法的には幅広い再利用を認めていますが、**本エミュレータの商用利用、有償再パッケージ、商用展開は強く控えていただきたいと願っています**。

> **もし商用化に回す予算があるなら、どうかそのお金で本物の富士フイルムのカメラを一台買ってください。**

---

## 謝辞

1. **富士フイルム株式会社（FUJIFILM Corporation）** — このオマージュのすべての行にインスピレーションを与えてくれた、素晴らしい **GFX100S**、GF 光学システム、そして何世代にもわたる写真づくりのクラフトマンシップとカラーサイエンスに深い感謝を込めて。
2. [**fujimod**](https://github.com/justjake/fujimod)（[fujimod.com](https://fujimod.com)）および [**FujiHack**](https://github.com/fujihack/fujihack) コミュニティ — 富士フイルムカメラのオープンな研究を切り拓き、貴重なアーキテクチャ資料を共有してくださったことに感謝いたします。
3. [**Dynarmic**](https://github.com/merryhime/dynarmic)（および本プロジェクトの AArch32 SoC JIT フォーク [`moeKiwiSAMA/dynarmic`](https://github.com/moeKiwiSAMA/dynarmic)） — エミュレータの中核を支える堅牢かつ高速な ARMv7-A JIT コンパイル基盤に感謝いたします。

---

*FUJIFILM、GFX、GFX100S、GF、およびフィルムシミュレーション名称は富士フイルム株式会社の商標です。本プロジェクトは独立したオープンソースプロジェクトであり、富士フイルム株式会社との提携・承認・推奨を受けるものではありません。*
