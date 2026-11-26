# Generated GFX100S 2.13 Flash layout and field dictionary

Generated from `catalog/flash/gfx100s-213.yaml` by `:address-catalog:compileCatalog`. Do not edit.

Dictionary `gfx100s-213-flash-layout-v1` / Catalog `gfx100s-213-discovery-v1` / SHA256 `fa5c4234f2c5d27803833ca8247ee6a4aa8b9cceb7751883d1c6adc1f1349506`. Firmware `gfx100s-213` / `ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12`.

- **Total NOR Flash size**: `0x4000000` (64 MiB)
- **Erase block size**: `0x20000` (128 KiB)
- **Program page size**: 256 bytes
- **Privacy boundary**: Contains only public `GXUP0004.DAT` firmware constants and synthetic `HYPOTHESIS` factory defaults — zero private personal camera data.

## NOR Flash partition table (32 entries, `section4 @ 0x017476E8`)

| Index | Partition ID | Blocks | Logical interval | Flags | Role | Confidence | Evidence |
| ---: | --- | --- | --- | ---: | --- | --- | --- |
| 0 | `part-00-bootloader` | 6 (0..5) | `[0x0, 0xc0000)` | 0 | bootloader-and-ipl | `DAT` | GXUP0004.DAT section4 partition table @ 0x017476E8[0] |
| 1 | `part-01-main-firmware-and-factory-seed` | 361 (6..366) | `[0xc0000, 0x2de0000)` | 1 | main-firmware-and-bank14-default-seed | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[1]; 0x000C0000..0x00132000 holds official 0x72000-byte bank-14 factory seed read by 0x0129E738 |
| 2 | `part-02-firmware-aux` | 9 (367..375) | `[0x2de0000, 0x2f00000)` | 1 | firmware-auxiliary-image | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[2] |
| 3 | `part-03-firmware-meta` | 1 (376..376) | `[0x2f00000, 0x2f20000)` | 1 | firmware-metadata-block | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[3] |
| 4 | `part-04-writable-slot0` | 1 (377..377) | `[0x2f20000, 0x2f40000)` | 3 | firmware-writable-slot-0 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[4] |
| 5 | `part-05-writable-slot1` | 1 (378..378) | `[0x2f40000, 0x2f60000)` | 3 | firmware-writable-slot-1 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[5] |
| 6 | `part-06-writable-slot2` | 1 (379..379) | `[0x2f60000, 0x2f80000)` | 3 | firmware-writable-slot-2 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[6] |
| 7 | `part-07-writable-slot3` | 1 (380..380) | `[0x2f80000, 0x2fa0000)` | 3 | firmware-writable-slot-3 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[7] |
| 8 | `part-08-writable-pair` | 2 (381..382) | `[0x2fa0000, 0x2fe0000)` | 3 | firmware-writable-pair | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[8] |
| 9 | `part-09-empty-slot` | 0 (@383) | `[0x2fe0000, 0x2fe0000)` | 5 | unassigned-zero-length-slot | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[9] |
| 10 | `part-10-empty-slot` | 0 (@383) | `[0x2fe0000, 0x2fe0000)` | 6 | unassigned-zero-length-slot | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[10] |
| 11 | `part-11-empty-slot` | 0 (@383) | `[0x2fe0000, 0x2fe0000)` | 5 | unassigned-zero-length-slot | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[11] |
| 12 | `part-12-factory-aux-data` | 40 (383..422) | `[0x2fe0000, 0x34e0000)` | 2 | factory-auxiliary-calibration-area | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[12] |
| 13 | `part-13-bank13-boot-config` | 1 (423..423) | `[0x34e0000, 0x3500000)` | 2 | bank13-boot-config-prefix | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[13]; first 0x508 bytes read during cold boot |
| 14 | `part-14-bank14-factory-config` | 66 (424..489) | `[0x3500000, 0x3d40000)` | 2 | bank14-factory-calibration-and-config | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[14]; loaded to RAM 0x04E08000 by 0x0129D8C4 |
| 15 | `part-15-empty-slot` | 0 (@490) | `[0x3d40000, 0x3d40000)` | 2 | unassigned-zero-length-slot | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[15] |
| 16 | `part-16-domain490-log0` | 1 (490..490) | `[0x3d40000, 0x3d60000)` | 4 | runtime-status-block-490 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[16] |
| 17 | `part-17-domain491-log1` | 1 (491..491) | `[0x3d60000, 0x3d80000)` | 4 | runtime-status-block-491 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[17] |
| 18 | `part-18-domain492-shutdown` | 1 (492..492) | `[0x3d80000, 0x3da0000)` | 4 | runtime-status-block-492 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[18] |
| 19 | `part-19-domain493-log3` | 1 (493..493) | `[0x3da0000, 0x3dc0000)` | 4 | runtime-status-block-493 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[19] |
| 20 | `part-20-bkup-blocka-ping` | 1 (494..494) | `[0x3dc0000, 0x3de0000)` | 3 | bkup-primary-user-settings-ping | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[20]; 8192B BlockA written by 0x012A125C |
| 21 | `part-21-bkup-blocka-pong` | 1 (495..495) | `[0x3de0000, 0x3e00000)` | 3 | bkup-primary-user-settings-pong | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[21]; ping-pong mirror of BlockA |
| 22 | `part-22-bkup-blockb-ping` | 1 (496..496) | `[0x3e00000, 0x3e20000)` | 3 | bkup-custom-c1-c6-settings-ping | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[22]; 8192B BlockB C1-C6 custom banks |
| 23 | `part-23-bkup-blockb-pong` | 1 (497..497) | `[0x3e20000, 0x3e40000)` | 3 | bkup-custom-c1-c6-settings-pong | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[23]; ping-pong mirror of BlockB |
| 24 | `part-24-reserved-498-503` | 6 (498..503) | `[0x3e40000, 0x3f00000)` | 4 | reserved-log-area-498-503 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[24] |
| 25 | `part-25-empty-slot` | 0 (@504) | `[0x3f00000, 0x3f00000)` | 3 | unassigned-zero-length-slot | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[25] |
| 26 | `part-26-secondary-writable-504-505` | 2 (504..505) | `[0x3f00000, 0x3f40000)` | 3 | writable-secondary-bank-504-505 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[26] |
| 27 | `part-27-secondary-506` | 1 (506..506) | `[0x3f40000, 0x3f60000)` | 3 | secondary-readonly-block-506 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[27] |
| 28 | `part-28-secondary-507` | 1 (507..507) | `[0x3f60000, 0x3f80000)` | 3 | secondary-readonly-block-507 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[28] |
| 29 | `part-29-empty-slot` | 0 (@508) | `[0x3f80000, 0x3f80000)` | 3 | unassigned-zero-length-slot | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[29] |
| 30 | `part-30-tail-config-508` | 1 (508..508) | `[0x3f80000, 0x3fa0000)` | 2 | tail-factory-config-block-508 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[30] |
| 31 | `part-31-tail-end-509-511` | 3 (509..511) | `[0x3fa0000, 0x4000000)` | 3 | tail-end-blocks-509-511 | `DAT` | GXUP0004.DAT section4 @ 0x017476E8[31]; 0x03FE0000..0x04000000 backed by dat-end-block-ff-experimental-v1 |

## Supplemental and writable COW Flash domains

| Domain ID | Logical interval | File | Fill | DAT seed | Writable COW subrange | Confidence |
| --- | --- | --- | --- | --- | --- | --- |
| `dat-part12-lens-cache-ff` | `[0x2fe0000, 0x34e0000)` | `factory-part12-lens-cache.img` | `0xff` | none | `[0x2fe0000, 0x34e0000)` -> `cow-part12-lens-cache.img` | `DAT` |
| `dat-bank13-read508` | `[0x34e0000, 0x3500000)` | `factory-bank13-read508.img` | `0xff` | none | `[0x34e0000, 0x3500000)` -> `cow-bank13.img` | `DAT` |
| `blank-ff-experiment` | `[0x3500000, 0x3d40000)` | `factory-config.img` | `0x00` | `GXUP0004.DAT+0xc0274` (0x72000B, inv=true, `DAT`) | read-only | `DAT` |
| `dat-domain490-497-ff` | `[0x3d40000, 0x3e40000)` | `factory-domain490-497.img` | `0xff` | none | `[0x3d40000, 0x3e40000)` -> `cow-domain490-497.img` | `DAT` |
| `dat-range504-ff` | `[0x3f00000, 0x3f40000)` | `factory-domain504-505.img` | `0xff` | none | `[0x3f00000, 0x3f40000)` -> `cow-domain504-505.img` | `HYPOTHESIS` |
| `dat-secondary-read506-507-ff` | `[0x3f40000, 0x3f80000)` | `factory-domain506-507.img` | `0xff` | none | `[0x3f40000, 0x3f80000)` -> `cow-domain506-507.img` | `DAT` |
| `dat-tail-config508-ff` | `[0x3f80000, 0x3fa0000)` | `factory-domain508.img` | `0xff` | none | `[0x3f80000, 0x3fa0000)` -> `cow-domain508.img` | `DAT` |

## Header magic and checksum / ECC laws

| Checksum ID | Domain | Algorithm | Payload range | Output range | Confidence | Description |
| --- | --- | --- | --- | --- | --- | --- |
| `bank14-header-magic` | `blank-ff-experiment` | `magic-u16le` | `[+0x200, +0x202)` | `[+0x200, +0x202)` | `SOURCE` | Validates that bank-14 holds initialized factory calibration data; otherwise 0x0129D8C4 falls back to the 0x72000-byte DAT seed @ 0x000C0000. |
| `bkup-blocka-parity2d` | `dat-domain490-497-ff` | `parity2d-row-col-v1` | `[+0x80000, +0x81efc)` | `[+0x81efc, +0x82000)` | `SOURCE` | 2D bit-parity ECC over 1983 x 32-bit rows [0x0000..0x1EFC): 256B row parity at +0x1EFC..+0x1FFB (bytes 0..247 active) and 4B 32-bit column XOR parity at +0x1FFC..+0x1FFF. |
| `bkup-blockb-parity2d` | `dat-domain490-497-ff` | `parity2d-row-col-v1` | `[+0xc0000, +0xc1efc)` | `[+0xc1efc, +0xc2000)` | `SOURCE` | 2D bit-parity ECC over 8192B BlockB custom settings bank (C1..C6 slots) using the same 0x012A2D34 law as BlockA. |

## Researched persistent configuration fields

| Field ID | Domain | Relative offset | Length | Type | Confidence | Factory template patch | Default hex | Description |
| --- | --- | --- | ---: | --- | --- | --- | --- | --- |
| `bank13.lens_dir_header` | `dat-bank13-read508` | `+0x0` | 3 | `bytes` | `SOURCE` | no | runtime/DAT | Registered-lens directory slot cursors and count (0..10 slots backed by part-12). |
| `bank13.lens_dir_magic` | `dat-bank13-read508` | `+0x4` | 4 | `u32le` | `SOURCE` | no | `f923c1ab` | Little-endian 0xABC123F9 magic validating part-13 registered-lens directory. |
| `bank14.loader_flags_d8` | `blank-ff-experiment` | `+0xd8` | 1 | `u8` | `SOURCE` | no | `80` | Bank-14 loader status/flag byte set to 0x80 during bank-14 initialization. |
| `bank14.hardware_id_prefix` | `blank-ff-experiment` | `+0x100` | 6 | `bytes` | `DAT` | no | `cb0480ff0203` | Official DAT body/board identifier header bytes for GFX100S. |
| `bank14.factory_serial_placeholder` | `blank-ff-experiment` | `+0x11b` | 30 | `ascii` | `DAT` | no | `353933353336333333343331303030303030303030303030303030303030` | Public DAT factory placeholder serial string shipped in GXUP0004.DAT (contains zero personal camera data). |
| `bank14.dcf_prefix_table` | `blank-ff-experiment` | `+0x146` | 17 | `ascii` | `DAT` | no | `5f46554a4944534346424c4f475f445346` | Default DCF folder/filename prefix constants (_FUJI / DSCF / BLOG / _DSF). |
| `bank14.exif_model` | `blank-ff-experiment` | `+0x15c` | 7 | `ascii` | `DAT` | no | `47465831303053` | EXIF camera model string ('GFX100S'). |
| `bank14.exif_make` | `blank-ff-experiment` | `+0x16f` | 8 | `ascii` | `DAT` | no | `46554a4946494c4d` | EXIF camera manufacturer string ('FUJIFILM'). |
| `bank14.platform_tag_spx4` | `blank-ff-experiment` | `+0x1c7` | 4 | `ascii` | `DAT` | no | `53505834` | SoC platform identifier tag ('SPX4'). |
| `bank14.header_magic` | `blank-ff-experiment` | `+0x200` | 2 | `u16le` | `SOURCE` | no | `55aa` | Little-endian 0xAA55 magic validating bank-14 calibration image. |
| `bank14.loader_version_tag` | `blank-ff-experiment` | `+0x204` | 2 | `bytes` | `SOURCE` | no | `0194` | Bank-14 calibration schema/version bytes written at boot (0x01, 0x94). |
| `bank14.default_language` | `blank-ff-experiment` | `+0x20b` | 1 | `u8` | `SOURCE` | no | `01` | Factory default UI language index (0..34; DAT seed 0x01 = English). |
| `bank14.factory_region_defaults` | `blank-ff-experiment` | `+0x215` | 5 | `bytes` | `SOURCE` | no | `0000110100` | Factory regional BKUP initialization defaults (date format, video/region, and image quality). |
| `bank14.seed_reload_flag` | `blank-ff-experiment` | `+0x244` | 1 | `u8` | `SOURCE` | no | `00` | Factory calibration seed reload control flag (0 = keep Flash/RAM contents, 1 = reload 0x6F000B DAT seed). |
| `bank14.temp_power_timing_defaults` | `blank-ff-experiment` | `+0x380` | 34 | `bytes` | `DAT` | no | `848c99a40a0a848c060a0a0a0a0ab6fe0a1e1e1f00050550011770005601180000d6` | Official DAT thermal/power timing defaults (includes battery debounce count 0x06 at +0x0388). |
| `bank14.battery_end_adc_threshold` | `blank-ff-experiment` | `+0x3e7` | 2 | `u16le` | `HYPOTHESIS` | yes | `f501` | Battery END ADC cutoff threshold (u16le, 501 = 0x01F5, <= 5920 mV). Keeps nominal 7400 mV (626) and legacy static power-peer fallback (502) safe while enabling authentic BATT_STS_END (0x06) auto-shutdown at exhausted 5800 mV (490 <= 501). |
| `bank14.battery_delta_low_half_mk3` | `blank-ff-experiment` | `+0x1183` | 3 | `bytes` | `SOURCE` | no | runtime/DAT | Signed 8-bit battery threshold deltas for LOW (+0x1183), HALF (+0x1184), and MK3 (+0x1185) in multi-level mode. |
| `bank14.defect_pixel_counts` | `blank-ff-experiment` | `+0x2502` | 780 | `bytes` | `SOURCE` | no | runtime/DAT | Sensor/defect-pixel/shading calibration sub-block length headers (all zero in DAT seed; safe no-op when zero). |
| `bank14.temp_compare_thresholds` | `blank-ff-experiment` | `+0x4069` | 6 | `bytes` | `SOURCE` | no | `012001300130` | Three 16-bit big-endian temperature comparison thresholds (0x0120, 0x0130, 0x0130) initialized by bank-14 loader. |
| `bank14.battery_curve_scale` | `blank-ff-experiment` | `+0x40c2` | 1 | `u8` | `SOURCE` | no | runtime/DAT | Unsigned 8-bit temperature-slope divisor for multi-level battery threshold curve (0 safely bypasses division). |
| `bank14.battery_temp_ref_c` | `blank-ff-experiment` | `+0x416c` | 2 | `bytes` | `SOURCE` | no | runtime/DAT | Primary (+0x416C) and secondary (+0x416D) signed 8-bit temperature breakpoints (deg C) for multi-level battery curve. |
| `bank14.battery_threshold_mode` | `blank-ff-experiment` | `+0x4176` | 1 | `u8` | `SOURCE` | no | `01` | Battery threshold evaluation mode: 2 enables multi-level temp-compensated curve, 1 uses fallback formula 0x0120C858. |
| `bank14.battery_adc_offset_163c4` | `blank-ff-experiment` | `+0x163c4` | 1 | `u8` | `SOURCE` | no | `00` | Unsigned 8-bit additive ADC calibration trim applied to battery_end_adc_threshold. |
| `bank14.ibis_cal_block` | `blank-ff-experiment` | `+0x2b000` | 16384 | `bytes` | `SOURCE` | no | runtime/DAT | 16 KiB IBIS (Body Image Stabilization) factory calibration block mirrored with part-30. |
| `bank14.bt_bd_addr` | `blank-ff-experiment` | `+0x798a` | 6 | `bytes` | `HYPOTHESIS` | yes | `02464a100001` | Synthetic deterministic locally-administered (LAA) Bluetooth BD_ADDR (02:46:4A:10:00:01; zero personal camera data). |
| `bank14.wireless_camera_name` | `blank-ff-experiment` | `+0x8a42` | 32 | `ascii` | `HYPOTHESIS` | yes | `46554a4946494c4d2d474658313030532d454d55312020202020202020202020` | Synthetic space-padded wireless/Bluetooth camera name ('FUJIFILM-GFX100S-EMU1           '; preserves 9-byte 'FUJIFILM-' prefix required by S4 0x012ABD30 BLE ScanRsp AD 0x09). |
| `bank14.wireless_camera_name_len` | `blank-ff-experiment` | `+0x8a63` | 1 | `u8` | `DAT` | yes | `15` | Active length in bytes of bank14.wireless_camera_name (21 = 0x15 for 'FUJIFILM-GFX100S-EMU1'). |
| `bank14.wifi_mac_address` | `blank-ff-experiment` | `+0x8ab9` | 6 | `bytes` | `HYPOTHESIS` | yes | `02464a100000` | Synthetic deterministic locally-administered (LAA) Wi-Fi MAC address (02:46:4A:10:00:00; zero personal camera data). |
| `bkup.blocka_generation_header` | `dat-domain490-497-ff` | `+0x80000` | 4 | `bytes` | `SIM` | no | runtime/DAT | BlockA (0x03DC0000) ping-pong record header written by firmware on first cold boot. |
| `bkup.blocka_header_slot` | `dat-domain490-497-ff` | `+0x80004` | 256 | `bytes` | `SOURCE` | no | runtime/DAT | BlockA 256-byte metadata slot preceding main BKUP settings payload. |
| `bkup.blocka_settings_prefix` | `dat-domain490-497-ff` | `+0x80104` | 1293 | `bytes` | `DAT` | no | runtime/DAT | Main BKUP user settings payload prefix (language, date/time selection state, display/power preferences; initialized automatically by firmware on first boot). |
| `bkup.blocka_dcf_exif_prefix` | `dat-domain490-497-ff` | `+0x80611` | 40 | `ascii` | `SIM` | no | runtime/DAT | Persistent DCF filename prefix ('_DSF' / 'DSCF') and EXIF copyright/artist pad written by firmware on first boot. |
| `bkup.blocka_settings_tail` | `dat-domain490-497-ff` | `+0x80639` | 6303 | `bytes` | `DAT` | no | runtime/DAT | Remainder of BlockA persistent shooting/UI/custom preferences payload. |
| `bkup.blocka_firmware_version` | `dat-domain490-497-ff` | `+0x81ef8` | 2 | `bytes` | `SOURCE` | no | runtime/DAT | BCD firmware version stamp (0x02 0x13 = v2.13) written into BlockA trailer. |
| `bkup.blocka_parity2d_row` | `dat-domain490-497-ff` | `+0x81efc` | 256 | `parity2d` | `SOURCE` | no | runtime/DAT | 256-byte row parity vector (1983 active row bits across bytes 0..247) covering BlockA [0x0000..0x1EFC). |
| `bkup.blocka_parity2d_col` | `dat-domain490-497-ff` | `+0x81ffc` | 4 | `parity2d` | `SOURCE` | no | runtime/DAT | 4-byte 32-bit column XOR parity covering BlockA [0x0000..0x1EFC). |
| `bkup.blockb_generation_header` | `dat-domain490-497-ff` | `+0xc0000` | 4 | `bytes` | `SOURCE` | no | runtime/DAT | BlockB (0x03E00000) ping-pong record header (15-bit sequence number at +0x0000..+0x0001). |
| `bkup.blockb_aux_metadata` | `dat-domain490-497-ff` | `+0xc0004` | 50 | `bytes` | `SOURCE` | no | runtime/DAT | BlockB 50-byte auxiliary metadata slot preceding the six 1024B C1..C6 custom banks. |
| `bkup.blockb_custom_c1_c6` | `dat-domain490-497-ff` | `+0xc0100` | 6144 | `bytes` | `DAT` | no | runtime/DAT | Six 1024-byte custom shooting banks (C1..C6) initialized automatically by firmware on first cold boot. |
| `bkup.blockb_parity2d_row` | `dat-domain490-497-ff` | `+0xc1efc` | 256 | `parity2d` | `SOURCE` | no | runtime/DAT | 256-byte row parity vector covering BlockB [0x0000..0x1EFC). |
| `bkup.blockb_parity2d_col` | `dat-domain490-497-ff` | `+0xc1ffc` | 4 | `parity2d` | `SOURCE` | no | runtime/DAT | 4-byte 32-bit column XOR parity covering BlockB [0x0000..0x1EFC). |
