# 固件获取与可选真机 Dump 指南

[English](FIRMWARE.md) | **简体中文** | [日本語](FIRMWARE.ja.md)

← [返回 README](../README.zh-CN.md) · [模拟器用户手册 →](manual/USER_MANUAL.zh-CN.md)

---

本仓库及所有预编译便携发行包均 **不包含任何富士胶片（FUJIFILM）专有固件、掩膜 BootROM 或相机出厂校准二进制文件**。运行模拟器前，您需要自行从富士官方网站下载未经修改的 **FUJIFILM GFX100S 官方固件 Ver. 2.13（`GXUP0004.DAT`）**。

> [!NOTE]
> **日常使用只需 `GXUP0004.DAT`（v2.13）这一个文件。** 本文[第二部分](#第二部分--可选真机硬件-dumpboot-rombin-与-nor-flashbin)介绍的真机 `bootrom` 与 `flash` 镜像属于**完全可选项**——不提取任何真机数据，同样可以完整体验拍照、录像、菜单操作、模块化镜头、光学滤镜与主题扩展。

---

## 第一部分 — 获取富士官方 GFX100S Ver. 2.13 固件（`GXUP0004.DAT`）

### 1. 从富士官网下载

1. 访问富士胶片全球官方网站 GFX100S 固件支持页面：
   - <https://www.fujifilm-x.com/global/support/download/firmware/cameras/gfx100s/>
2. 阅读并同意官方固件许可协议，下载 **Ver. 2.13** 固件包（文件名为 **`GXUP0004.DAT`**）。
   - *版本提示*：请确认下载的固件版本为 **Ver. 2.13**（约 `61.5 MB`）。本模拟器的硬件寄存器映射与地址字典均针对 **2.13** 版本精确验证。

### 2. 校验 SHA-256 哈希值

导入固件前，请先校验本地文件的 SHA-256 是否与官方原版 Ver. 2.13 完全一致：

| 项目 | 期望值 |
|---|---|
| **文件名** | `GXUP0004.DAT` |
| **适用机型与版本** | FUJIFILM GFX100S — Ver. 2.13 |
| **精确 SHA-256** | `ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12` |

**Windows（PowerShell）：**

```powershell
Get-FileHash .\GXUP0004.DAT -Algorithm SHA256
```

**Linux（终端）：**

```bash
sha256sum GXUP0004.DAT
```

确认输出的哈希值与上方 `ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12` 完全匹配（PowerShell 输出大写字母属正常现象）。

---

### 3. 放置与刷入固件

#### A. 预编译便携发行包（`Windows .zip` / `Linux .tar.gz` / `app-image`）

便携发行版采用统一的 **64 MiB QSPI NOR Flash** 持久化镜像（位于 `<模拟器根目录>/flash/nor-flash.bin`，精确大小 `67,108,864` 字节）。首次解压公开版时，`flash/` 目录下仅有 `PUT_HERE` 说明文件，您可通过以下任一方式用 `GXUP0004.DAT` 初始化生成 `nor-flash.bin`：

1. **图形界面一键导入（最简单，推荐新手使用）**
   - Windows 双击运行 **`fuji-emulator-gfx100s.exe`**（或 `start-gfx100s.bat`）；Linux 运行 **`./start-gfx100s.sh`**（或 `app-image` 包中的 `./bin/fuji-emulator-gfx100s`）。
   - 在首次启动弹出的固件引导提示框中（或点击窗口顶部菜单 **烧录 Flash / Flash → 烧录固件 (.DAT)... / Flash Firmware (.DAT)...**），选择刚才校验通过的 **`GXUP0004.DAT`**。
   - 模拟器会自动将固件解包写入 `<模拟器根目录>/flash/nor-flash.bin`（64 MiB）并冷启动进入相机首次开机的语言与日期设置界面。
   - *（小技巧：您也可以在首次启动前直接把 `GXUP0004.DAT` 复制到 `<模拟器根目录>/flash/GXUP0004.DAT`。）*

2. **命令行离线烧录**
   - **Windows（PowerShell / CMD）**：
     ```powershell
     .\fuji-emulator-gfx100s.exe --flash-dat C:\Downloads\GXUP0004.DAT
     ```
   - **Linux**：
     ```bash
     ./start-gfx100s.sh --flash-dat ~/Downloads/GXUP0004.DAT
     ```
   - 默认情况下，若 `flash/nor-flash.bin` 已存在，`--flash-dat` 仅更新 `[0x00000000..0x02FE0000)` 固件区并保留 `[0x02FE0000..0x04000000)` 用户设置与校准区；如需连同校准/设置区一并重置为空白基线，可追加 `--no-preserve-calibration`。

3. **交互式终端控制台烧录**
   - 在终端 `fuji> ` 提示符下执行：
     ```text
     /flash write /path/to/GXUP0004.DAT
     ```

完成初始化后，所有机内菜单设置、`C1–C6` 自定义档位及已注册 GF 镜头缓存均实时保存在 `flash/nor-flash.bin` 中，后续启动无需保留原始 `.DAT` 文件。

#### B. 从源码编译运行（`./gradlew` / `sh jvm.sh`）

- **若从源码打包便携发行版**（运行 `./gradlew :packageReleaseLinux -PfujiBundleStockBins=false` 或 `:packageReleaseWindows -PfujiBundleStockBins=false`）：解压生成的压缩包后，目录结构与上方 **3.A** 完全一致（使用 `<解压目录>/flash/nor-flash.bin`）。
- **若直接运行源码开发目录（`local/runtime/`）**：
  - 先执行 `sh jvm.sh prepare` 生成 `local/runtime/`。
  - 使用统一 NOR Flash 配置（`norFlashFile: "flash/nor-flash.bin"`）时，可运行 `--home local/runtime --flash-dat /path/to/GXUP0004.DAT` 生成 `local/runtime/flash/nor-flash.bin`。
  - 使用开发版直读 `.DAT` 配置（`local/runtime/config/emulator.yaml`）时，可将固件放入 **`local/runtime/runtime/firmware/GXUP0004.DAT`**，并在 `local/runtime/config/emulator.yaml` 中配置：
    ```yaml
    firmwareFile: "runtime/firmware/GXUP0004.DAT"
    cpuEngine: "dynarmic"
    ```
    或在启动时通过 `--firmware /path/to/GXUP0004.DAT` 指定路径。

---

## 第二部分 — 可选真机硬件 Dump（`boot-rom.bin` 与 `nor-flash.bin`）

> [!CAUTION]
> **实机变砖风险与免责声明**
> 从真实相机提取内部 BootROM 或 QSPI NOR Flash 需要在实机上运行非官方社区研究工具。任何操作失误、意外断电或固件版本不匹配，都可能导致**实机永久变砖（无法开机）、丢失出厂 CMOS/防抖校准数据或丧失官方保修资格**。
>
> **本项目不提供任何实机破解或提取载荷，作者与贡献者对任何实机损坏、变砖、数据丢失或经济损失概不承担任何责任。** 请仅在完全清楚硬件风险的前提下自行操作——**或者更坦率地说，直接向身边已经提取并脱敏处理好的朋友索取一份干净镜像即可。**

### 为什么真机 Dump 是可选项？

- **默认启动模式（`Flash-Direct`）**：默认情况下，模拟器只需官方 `GXUP0004.DAT` 即可通过经严格验证的 **Flash-Direct** 路径（从 `flash/nor-flash.bin[0x20000..0x3FFFF]` 加载 `boot.bin`）冷启动，完整支持相机全部拍摄、菜单、镜头与滤镜功能。
- **真机 Dump 带来的额外保真度**：
  1. **脱敏后的 64 MiB `nor-flash.bin`**：使用真实 GFX100S 机身的出厂配置与标定表（`[0x02FE0000..0x04000000)`）替代模拟器内置的净室合成默认常量。
  2. **32 KiB SPX4 掩膜 `boot-rom.bin`**：允许显式开启实验性的全链路真机冷启动（从芯片复位向量 `PC = 0xFFF00000` 执行掩膜 BootROM → Stage-1 IPL → Stage-2 XIP → 4 核 SMP `SGI 0` 唤醒 → `flash/nor-flash.bin`）。

### 第一步 — 提取前必做的机内出厂重置与隐私脱敏！

> [!WARNING]
> **切勿公开上传、发帖分享或向公共仓库提交您个人相机的原始 `nor-flash.bin`！**
> 64 MiB NOR Flash 中的 `bank-14` 分区（`[0x03500000..0x03D40000)`）及尾部备份扇区（`[0x03D40000..0x04000000)`）存有相机的**唯一机身序列号、Wi-Fi / 蓝牙 MAC 地址、无线配对密钥、EXIF 版权署名与个人拍摄设置**。

如果您拥有运行 2.13 固件的 GFX100S 实机并打算自行提取：

1. **提取前务必先在相机内恢复出厂设置**：
   - 在实机菜单中依次进入 **`MENU → 设置 (SET UP) → 用户设置 (USER SETTING) → 重置 (RESET) → 初始化 (INITIALIZE)`**，先彻底清除个人无线连接信息、EXIF 版权/作者名、自定义菜单及 `BKUP` 用户设置扇区。
2. **参考社区 SD 卡无损研究方案**：
   - 关于富士相机 SD 卡引导与内存只读导出工具链，可参考开源项目 [**fujimod**](https://github.com/justjake/fujimod)（[fujimod.com](https://fujimod.com)）与 [**FujiHack**](https://github.com/fujihack/fujihack) 的安全说明（切勿尝试盲刷相机内部闪存！）。
3. **严格保管个人 `bank-14` 数据**：
   - 即使执行了机内“初始化”，`bank-14`（`0x03500000..0x03D40000`）仍保留出厂机身序列号与硬件 MAC 地址。请仅在本地自用，切勿将未抹除序列号/MAC 的原始镜像发到公开网络。

### 第二步 — 文件规格、校验和与放置目录

| 镜像文件 | 精确字节大小 | 期望 SHA-256 | 便携发行包放置路径 | 源码开发目录路径 |
|---|---|---|---|---|
| **`nor-flash.bin`** *(64 MiB QSPI NOR)* | `67,108,864` 字节（`64 MiB`） | *因机身与设置而异* | `<模拟器根目录>/flash/nor-flash.bin` | `<运行目录>/flash/nor-flash.bin` |
| **`boot-rom.bin`** *(32 KiB 掩膜 ROM)* | `32,768` 字节（`32 KiB`） | `2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4` | `<模拟器根目录>/bootrom/boot-rom.bin` | `<运行目录>/bootrom/boot-rom.bin` *（或 `runtime/firmware/boot-rom.bin`）* |

- **导入 `nor-flash.bin`**：
  - 关闭模拟器后直接复制到 `<模拟器根目录>/flash/nor-flash.bin`，**或**通过顶部菜单 **Flash → 导入 NOR Flash (.bin)...**、命令行 `--import-flash /path/to/nor-flash.bin`、控制台 `/flash import /path/to/nor-flash.bin` 导入。
  - 可随时使用 `--inspect-flash` 检查当前 NOR 镜像完整性与分区状态。
- **启用可选 `boot-rom.bin` 真机链路冷启动（`--boot-rom`）**：
  - 仅放置 `bootrom/boot-rom.bin` 时，模拟器默认仍走最稳定的 **Flash-Direct** 启动。
  - 如需体验从 `PC = 0xFFF00000` 完整执行 32 KiB 掩膜 BootROM 的冷启动链路，可在启动时附加 **`--boot-rom`** 参数：
    ```bash
    # Linux
    ./start-gfx100s.sh --boot-rom

    # Windows
    .\fuji-emulator-gfx100s.exe --boot-rom
    ```
    或在 `<模拟器根目录>/configs/emulator.yaml` 中设置 `bootRomBoot: true`。
  - *回退机制与已知限制*：开启 `--boot-rom` 但未找到合法的 32,768 字节 `boot-rom.bin` 时，模拟器不会报错退出，而是在 `logs/latest.log` 记录 `WARN [FALLBACK][bootrom]` 并自动回退至 Flash-Direct。此外，实验性 BootROM 模式在首次开机引导（OOBE）尚未完成时中途关机重启可能停滞，建议先完成首次语言/时区设置或使用默认的 Flash-Direct 模式。
