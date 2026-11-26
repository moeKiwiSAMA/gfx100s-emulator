# ファームウェア導入および実機ダンプ（任意）ガイド

[English](FIRMWARE.md) | [简体中文](FIRMWARE.zh-CN.md) | **日本語**

← [README に戻る](../README.ja.md) · [ユーザーマニュアル →](manual/USER_MANUAL.ja.md)

---

本リポジトリおよび配布されているポータブル版リリースには、**富士フイルム（FUJIFILM）の純正ファームウェア、マスク BootROM、個体別キャリブレーションデータなどのプロプライエタリなバイナリは一切含まれていません**。エミュレータを起動するには、富士フイルム公式サイトから未改変の **FUJIFILM GFX100S ファームウェア Ver. 2.13（`GXUP0004.DAT`）** をご自身でダウンロードしていただく必要があります。

> [!NOTE]
> **通常利用に必要なのは `GXUP0004.DAT`（Ver. 2.13）の 1 ファイルのみです。** 本ガイドの[第 2 部](#第-2-部--実機ハードウェアダンプの導入完全任意boot-rombin--nor-flashbin)で解説する実機からの `bootrom` / `flash` ダンプは **完全に任意（オプション）** であり、静止画・動画撮影、メニュー操作、交換レンズや光学フィルター、テーマ拡張を楽しむ上で必須ではありません。

---

## 第 1 部 — 公式 GFX100S ファームウェア Ver. 2.13（`GXUP0004.DAT`）の準備

### 1. 富士フイルム公式サイトからダウンロード

1. 富士フイルムの GFX100S 公式ファームウェアダウンロードページへアクセスします：
   - <https://www.fujifilm-x.com/global/support/download/firmware/cameras/gfx100s/>
2. 使用許諾契約をご確認の上、**Ver. 2.13** のファームウェアファイル（**`GXUP0004.DAT`**、約 `61.5 MB`）をダウンロードしてください。
   - *バージョンに関する注意*：本エミュレータのレジスタ定義およびアドレスカタログは **Ver. 2.13** 専用に検証されています。ダウンロード時にバージョンが **2.13** であることを必ずご確認ください。

### 2. SHA-256 ハッシュ値の検証

導入前に、ダウンロードした `GXUP0004.DAT` の SHA-256 チェックサムが公式 Ver. 2.13 と完全一致しているか確認します：

| 項目 | 期待値 |
|---|---|
| **ファイル名** | `GXUP0004.DAT` |
| **対象機種・バージョン** | FUJIFILM GFX100S — Ver. 2.13 |
| **SHA-256** | `ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12` |

**Windows（PowerShell）：**

```powershell
Get-FileHash .\GXUP0004.DAT -Algorithm SHA256
```

**Linux（ターミナル）：**

```bash
sha256sum GXUP0004.DAT
```

出力されたハッシュが `ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12` と一致することを確認してください（PowerShell では大文字で表示されます）。

---

### 3. エミュレータへの導入手順

#### A. ポータブル版リリース（`Windows .zip` / `Linux .tar.gz` / `app-image`）

ポータブル版はカメラ内蔵の **64 MiB QSPI NOR Flash**（`<エミュレータフォルダ>/flash/nor-flash.bin`、厳密に `67,108,864` バイト）をエミュレートします。初回展開直後の `flash/` には案内ファイル（`PUT_HERE`）のみが入っているため、以下のいずれかの方法で `GXUP0004.DAT` から `nor-flash.bin` を初期化します：

1. **GUI からの導入（最も簡単・初心者向け推奨）**
   - Windows は **`fuji-emulator-gfx100s.exe`**（または `start-gfx100s.bat`）、Linux は **`./start-gfx100s.sh`**（`app-image` 版では `./bin/fuji-emulator-gfx100s`）を起動します。
   - 初回起動時のファームウェアセットアップ案内ダイアログ（または上部メニューバーの **Flash → Flash Firmware (.DAT)...**）から、検証済みの **`GXUP0004.DAT`** を選択します。
   - 自動的に `<エミュレータフォルダ>/flash/nor-flash.bin`（64 MiB）が生成され、GFX100S の初回起動ウィザード（言語・日時設定）が立ち上がります。
   - *（ヒント：初回起動前に `<エミュレータフォルダ>/flash/GXUP0004.DAT` へファイルを直接配置しておくことも可能です。）*

2. **コマンドライン（CLI）からの一発書き込み**
   - **Windows（PowerShell / CMD）**：
     ```powershell
     .\fuji-emulator-gfx100s.exe --flash-dat C:\Downloads\GXUP0004.DAT
     ```
   - **Linux**：
     ```bash
     ./start-gfx100s.sh --flash-dat ~/Downloads/GXUP0004.DAT
     ```
   - 既存の `flash/nor-flash.bin` がある場合、デフォルトでは設定・キャリブレーション領域（`[0x02FE0000..0x04000000)`）を保持したままファームウェア領域（`[0x00000000..0x02FE0000)`）のみを更新します。工場出荷状態として完全初期化したい場合は `--no-preserve-calibration` を追加してください。

3. **対話型コンソールからの書き込み**
   - ターミナルの `fuji> ` プロンプトから以下を実行します：
     ```text
     /flash write /path/to/GXUP0004.DAT
     ```

一度 `flash/nor-flash.bin` が生成されれば、カメラ内のメニュー設定や `C1–C6` カスタム登録、装着した GF レンズのキャッシュはすべて `nor-flash.bin` に自動保存されるため、元の `.DAT` ファイルを残しておく必要はありません。

#### B. ソースコードからビルドして実行する場合（`./gradlew` / `sh jvm.sh`）

- **ソースからポータブル配布パッケージを生成する場合**（`./gradlew :packageReleaseLinux -PfujiBundleStockBins=false` または `:packageReleaseWindows -PfujiBundleStockBins=false`）：展開後のディレクトリ構成は上記 **3.A** と同一です（`<展開先>/flash/nor-flash.bin` を使用）。
- **開発用ランタイム（`local/runtime/`）を直接起動する場合**：
  - `sh jvm.sh prepare` で `local/runtime/` を生成します。
  - 統合 NOR Flash 設定（`norFlashFile: "flash/nor-flash.bin"`）では `--home local/runtime --flash-dat /path/to/GXUP0004.DAT` で初期化します。
  - 開発用の `.DAT` 直接読み込み設定（`local/runtime/config/emulator.yaml`）では、**`local/runtime/runtime/firmware/GXUP0004.DAT`** に配置し、`local/runtime/config/emulator.yaml` に以下を指定します：
    ```yaml
    firmwareFile: "runtime/firmware/GXUP0004.DAT"
    cpuEngine: "dynarmic"
    ```
    または起動引数 `--firmware /path/to/GXUP0004.DAT` を渡します。

---

## 第 2 部 — 実機ハードウェアダンプの導入（完全任意：`boot-rom.bin` & `nor-flash.bin`）

> [!CAUTION]
> **実機の文鎮化リスクおよび免責事項（必読）**
> 実機カメラから内蔵 BootROM や QSPI NOR Flash を吸い出す作業には、非公式のコミュニティ製ツールを実機上で動作させるリスクが伴います。手順の誤りや電源断、ファームウェアの不整合により、**カメラ本体の起動不能（文鎮化 / ブリック）、センサー・IBIS 工場出荷時キャリブレーションの破損、メーカー保証の喪失**を招く恐れがあります。
>
> **本プロジェクトは実機向けの改造・吸い出しペイロードを一切配布しておらず、万一実機の故障・破損・データ消失・金銭的損害が発生しても、開発者およびコントリビューターは一切の責任を負いません。** リスクを完全に理解した自己責任のもとで行うか、**率直なところ、すでにクリーンな初期化済みダンプを抽出している信頼できる友人に頼むことをおすすめします。**

### なぜ実機ダンプは任意なのか？

- **標準起動モード（`Flash-Direct`）**：デフォルトでは公式 `GXUP0004.DAT` のみから生成した `flash/nor-flash.bin[0x20000..0x3FFFF]` の `boot.bin` を直接実行する **Flash-Direct** 方式で起動し、すべての撮影機能・メニュー・レンズ・フィルターが問題なく動作します。
- **実機ダンプを導入する意味**：
  1. **初期化済み 64 MiB `nor-flash.bin`**：エミュレータのクリーンルーム合成デフォルト値の代わりに、実機固有の工場出荷テーブル領域（`[0x02FE0000..0x04000000)`）を再現できます。
  2. **32 KiB SPX4 マスク `boot-rom.bin`**：SoC のリセットベクタ（`PC = 0xFFF00000`）からマスク BootROM → Stage-1 IPL → Stage-2 XIP → 4 コア SMP `SGI 0` 起動 → `flash/nor-flash.bin` へと至るフルシリコン起動経路（実験的機能）を有効化できます。

### ステップ 1 — ダンプ前の必須「設定初期化」と個人情報保護

> [!WARNING]
> **ご自身のカメラから吸い出した生の `nor-flash.bin` をインターネット上へ絶対に公開・共有しないでください！**
> `bank-14` 領域（`[0x03500000..0x03D40000)`）および末尾バックアップ領域（`[0x03D40000..0x04000000)`）には、カメラ個体の**シリアル番号、Wi-Fi / Bluetooth MAC アドレス、無線ペアリング情報、EXIF 著作権者名、各種ユーザー設定**が記録されています。

お手元の GFX100S（Ver. 2.13）からご自身で抽出する場合：

1. **必ず先にカメラ本体で「初期化（工場出荷状態へリセット）」を実行する**：
   - カメラのメニューから **`MENU → セットアップ (SET UP) → 初期設定 (USER SETTING) → リセット (RESET) → 設定初期化 (INITIALIZE)`** を実行し、Wi-Fi/Bluetooth 情報、著作権情報、カスタム設定（`BKUP` ブロック）を完全に消去してからダンプを行ってください。
2. **SD カードベースの非破壊リサーチツールを参照する**：
   - 富士フイルム機の SD カードブートおよびメモリ読み出し手法については、オープンソースの [**fujimod**](https://github.com/justjake/fujimod)（[fujimod.com](https://fujimod.com)）および [**FujiHack**](https://github.com/fujihack/fujihack) のドキュメントを参照してください（内蔵フラッシュへの無謀な書き込みは絶対に行わないでください）。
3. **`bank-14` の個体識別情報を厳重に管理する**：
   - カメラ内の「設定初期化」を行っても、`bank-14`（`0x03500000..0x03D40000`）には工場出荷時のシリアル番号や MAC アドレスが残ります。必ずご自身のローカル環境内に留めてください。

### ステップ 2 — ファイルサイズ・ハッシュ値・配置パス一覧

| ダンプファイル | 厳密なサイズ | 期待される SHA-256 | ポータブル版の配置先 | 開発用ランタイムの配置先 |
|---|---|---|---|---|
| **`nor-flash.bin`** *(64 MiB QSPI NOR)* | `67,108,864` バイト（`64 MiB`） | *個体・設定により異なる* | `<エミュレータフォルダ>/flash/nor-flash.bin` | `<ランタイムフォルダ>/flash/nor-flash.bin` |
| **`boot-rom.bin`** *(32 KiB マスク ROM)* | `32,768` バイト（`32 KiB`） | `2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4` | `<エミュレータフォルダ>/bootrom/boot-rom.bin` | `<ランタイムフォルダ>/bootrom/boot-rom.bin` *（または `runtime/firmware/boot-rom.bin`）* |

- **`nor-flash.bin` の読み込み方法**：
  - エミュレータ終了中に `<エミュレータフォルダ>/flash/nor-flash.bin` へ直接配置するか、上部メニュー **Flash → Import NOR Flash (.bin)...**、CLI 引数 `--import-flash /path/to/nor-flash.bin`、またはコンソール `/flash import /path/to/nor-flash.bin` を使用します。
  - `--inspect-flash` で配置済みイメージのサイズやチェックサムをいつでも確認できます。
- **`boot-rom.bin` によるフル BootROM コールドブートの有効化（`--boot-rom`）**：
  - `bootrom/boot-rom.bin` を置いただけでは、安定性の高い標準の **Flash-Direct** 起動が使われます。
  - `PC = 0xFFF00000` からの実機マスク BootROM 起動を有効にするには、起動時に **`--boot-rom`** フラグを付与するか：
    ```bash
    # Linux
    ./start-gfx100s.sh --boot-rom

    # Windows
    .\fuji-emulator-gfx100s.exe --boot-rom
    ```
    `<エミュレータフォルダ>/configs/emulator.yaml` に `bootRomBoot: true` を設定してください。
  - *フォールバックと既知の制限*：`--boot-rom` 指定時に有効な 32,768 バイトの `boot-rom.bin` が見つからない場合、エミュレータは停止せず `logs/latest.log` に `WARN [FALLBACK][bootrom]` を出力して自動的に Flash-Direct で起動します。なお実験的な BootROM モードでは、初回起動セットアップ（OOBE）の途中で電源を切って再起動すると停止する場合があるため、先に初回セットアップを完了させるか標準の Flash-Direct をご利用ください。
