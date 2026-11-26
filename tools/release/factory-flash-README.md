# 模拟出厂配置 Flash 模板说明

本目录包含面向普通用户 Release 的 **GFX100S 2.13 模拟出厂 Flash 模板**（由 `tools/release/factory-flash-template.yaml` 与权威 Flash 字典 `catalog/flash/gfx100s-213.yaml` 联合生成，自动生成的人类可读总表见 `docs/generated/flash-map.md`）：

- **证据与隐私边界**：仅使用公开固件包 `GXUP0004.DAT` 内置的官方出厂常量（`DAT`/`SOURCE`）与干净的合成默认阈值（`HYPOTHESIS`），**不包含任何实机个人序列号、用户偏好或私有校准数据**。
- **已覆盖分区**（与 `components/flash-image` 的 `SupplementalRegions` / `WritableRegions` 保持一致）：
  - `factory-part12-lens-cache.img`（`0x02FE0000`, `5 MiB`）：`part[12]` 已注册 GF 镜头 EEPROM 缓存可写 COW 区（`0x02FE0000..0x034E0000`，10 个 512 KiB 槽位，出厂基线全 `0xFF`；通过 `LENSCOM` 下载镜头 128 KiB EEPROM 后，关机/自动关机时写入 `cow-part12-lens-cache.img`）。
  - `factory-bank13-read508.img`（`0x034E0000`, `128 KiB`）：`part[13]` 已注册镜头目录与冷启动前置配置可写 COW 区（`0x034E0000..0x03500000`，2 个 64 KiB 擦除扇区，默认填充 `0xFF`；冷启动读取前 `0x508` 字节，关机/自动关机时擦写 `cow-bank13.img` 以索引 `part[12]` 镜头槽位）。
  - `factory-config.img`（`0x03500000`, `8.25 MiB`）：`part[14]` (`bank-14`) 持久出厂校准与配置区。前 `0x72000` 字节由公开 `GXUP0004.DAT` 偏移 `0x000C0274`（按位取反，对应固件 `section4 @ 0x0129E738` 的官方默认表，含 `0xAA55` 头魔数与占位序列号 `593536333431...`）播种，其余清零；并叠加 `HYPOTHESIS` 合成电池阈值补丁 `+0x03E7..+0x03E8 = 5E 01`（`350 < 502`），使虚拟供电下正常上报满电 `BATT_STS_FULL (0x08)` 并完整冷启动至 `START 言語/LANG. 1/5` 首次设置界面。
  - `factory-domain490-497.img`（`0x03D40000`, `1 MiB`）：主配置与运行时状态可写 COW 区（`0x03D40000..0x03E40000`，覆盖 `part[16..23]`）。出厂基线保持全 `0xFF`，首次冷启动与关机/自动关机时由原厂固件自动向 `cow-domain490-497.img` 写入 Slot 1 日志区（`0x03D40000`/`0x03D60000`）、Slot 0 关机状态区（`0x03D80000`/`0x03DA0000`，256 B）、`8192 B` `BlockA`（`0x03DC0000`，主设置）与 `8192 B` `BlockB`（`0x03E00000`，`C1..C6` 自定义组），其中 `BlockA`/`BlockB` 均受 `section4 @ 0x012A2D34` 2D 比特奇偶校验 ECC 保护（`+0x1EFC..+0x1FFB` 行校验、`+0x1FFC..+0x1FFF` 列校验），重启自动从 COW 恢复。
  - `factory-domain504-505.img`（`0x03F00000`, `256 KiB`）：次可写 COW 区（`0x03F00000..0x03F40000`）。
  - `factory-domain506-507.img`（`0x03F40000`, `256 KiB`）：次可写 COW 区（`0x03F40000..0x03F80000`，`part[27..28]`，关机时由 `section4 @ 0x012A0960` 写入）。
  - `factory-domain508.img`（`0x03F80000`, `128 KiB`）：尾部配置可写 COW 区（`0x03F80000..0x03FA0000`，`part[30]`，关机时持久化）。
- **恢复出厂设置方法**：关闭模拟器后，删除 `runtime/compoments/flash-image/` 下的 `cow-*.img` 与 `runtime/compoments/power-peer/submcu-nvram.json`（或将 `runtime/templates/factory-flash/` 全部文件复制覆盖回 `runtime/compoments/flash-image/` 并删除 `submcu-nvram.json`）即可回到干净出厂态（重新进入语言/时区向导）。
- **单一事实来源校验**：`catalog/flash/gfx100s-213.yaml` 是 Flash 分区、校验和与字段的唯一权威定义；运行 `:address-catalog:verifyCatalog` 会自动校验 `docs/generated/flash-map.md` 与 `tools/release/factory-flash-template.yaml` 与字典 100% 一致。
