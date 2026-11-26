#!/usr/bin/env python3
"""
FUJIFILM GFX100S 目录模式 SD 卡（FF-DSC01 / DCF / Sidecar）管理工具。

零第三方依赖（仅使用 Python 3 标准库），跨平台支持 Linux / macOS / Windows。
与模拟器 `DirectoryFat32BlockStore.kt` 及 `SdSidecarJson.kt` 完全兼容，用于：
  1. init     — 初始化标准 FF-DSC01 目录模式 SD 卡（含 FFDB/FFXFER.DAT、DCIM/100_FUJI/、同级 .fuji-sd.json）
  2. inspect  — 检查目录模式 SD 卡容量、FAT32/FF-DSC01 几何参数、sidecar 簇分配、FFDB 与 DCF/EXIF 照片详情
  3. validate — 严格校验宿主目录卫生、FFDB、DCF/EXIF 合规性与 .fuji-sd.json 一致性
  4. --selftest — 运行内置单元测试套件（使用隔离临时目录，不修改工作区）
"""

from __future__ import annotations

import argparse
import base64
import contextlib
from dataclasses import dataclass, field
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import struct
import sys
import tempfile
import unittest
from typing import Any

# =============================================================================
# 常量定义（对齐 DirectoryFat32BlockStore.kt / SdSidecarJson.kt / M5LiveInCameraFormatE2eTest.kt）
# =============================================================================

SIDECAR_SCHEMA = 1
SECTOR_BYTES = 512
MAX_DIR_DEPTH = 32
MAX_FILE_SIZE = 0xFFFF_FFFF
MIN_FAT32_CLUSTERS = 65_525
MIN_CAPACITY_BYTES = 34 * 1024 * 1024  # 34 MiB，确保 FAT32 簇数 >= 65525

DEFAULT_CAPACITY_BYTES = 4 * 1024 * 1024 * 1024  # 4 GiB 标准 FF-DSC01 几何
DEFAULT_VOLUME_ID = 0x46554A49  # ASCII "FUJI" = 1179994697
DEFAULT_VOLUME_LABEL = "GFX100S    "
FF_DSC01_OEM_ID = "FF-DSC01"
MSWIN_OEM_ID = "MSWIN4.1"

ATTR_DIRECTORY = 0x10
ATTR_ARCHIVE = 0x20

FSINFO_LEAD_SIG = 0x41615252
FSINFO_STRUCT_SIG = 0x61417272
FSINFO_TRAIL_SIG = 0xAA550000

VALID_SPC = frozenset({1, 2, 4, 8, 16, 32, 64, 128})
ILLEGAL_FAT_CHARS = frozenset('/\\:*?"<>|\x00')

DCF_DIR_RE = re.compile(r"^([1-9][0-9]{2})([A-Z0-9_]{5})$")
DCF_FILE_RE = re.compile(
    r"^([A-Z0-9_]{4})(000[1-9]|00[1-9][0-9]|0[1-9][0-9]{2}|[1-9][0-9]{3})\.(JPG|RAF|MOV|HIF|WAV|THM)$"
)
DCF_FILE_CI_RE = re.compile(
    r"^([A-Za-z0-9_]{4})(000[1-9]|00[1-9][0-9]|0[1-9][0-9]{2}|[1-9][0-9]{3})\.(jpg|raf|mov|hif|wav|thm)$",
    re.IGNORECASE,
)

RAF_MAGIC = b"FUJIFILMCCD-RAW "
FFXFER_HEADER_MAGIC = b"FFDB\x00\x01\x00\x00"


# =============================================================================
# 数据结构
# =============================================================================


@dataclass
class VolumeGeometry:
    partition_start_lba: int
    partition_sector_count: int
    sectors_per_cluster: int
    reserved_sectors: int
    num_fats: int
    sectors_per_fat: int
    root_cluster: int = 2
    fs_info_sector: int = 1
    backup_boot_sector: int = 6
    volume_id: int = DEFAULT_VOLUME_ID
    volume_label: str = DEFAULT_VOLUME_LABEL

    @property
    def cluster_bytes(self) -> int:
        return self.sectors_per_cluster * SECTOR_BYTES

    @property
    def fat_start_lba(self) -> int:
        return self.partition_start_lba + self.reserved_sectors

    @property
    def data_start_lba(self) -> int:
        return self.fat_start_lba + self.num_fats * self.sectors_per_fat

    @property
    def data_sectors(self) -> int:
        return self.partition_sector_count - self.reserved_sectors - self.num_fats * self.sectors_per_fat

    @property
    def cluster_count(self) -> int:
        return self.data_sectors // self.sectors_per_cluster

    def to_sidecar_dict(self) -> dict[str, Any]:
        label = normalize_volume_label(self.volume_label)
        return {
            "partitionStartLba": int(self.partition_start_lba),
            "partitionSectorCount": int(self.partition_sector_count),
            "sectorsPerCluster": int(self.sectors_per_cluster),
            "reservedSectors": int(self.reserved_sectors),
            "numFats": int(self.num_fats),
            "sectorsPerFat": int(self.sectors_per_fat),
            "rootCluster": int(self.root_cluster),
            "fsInfoSector": int(self.fs_info_sector),
            "backupBootSector": int(self.backup_boot_sector),
            "volumeId": to_signed_i32(int(self.volume_id)),
            "volumeLabel": label,
        }

    @classmethod
    def from_sidecar_dict(cls, data: dict[str, Any]) -> VolumeGeometry:
        required = (
            "partitionStartLba",
            "partitionSectorCount",
            "sectorsPerCluster",
            "reservedSectors",
            "numFats",
            "sectorsPerFat",
            "rootCluster",
            "fsInfoSector",
            "backupBootSector",
            "volumeId",
        )
        for key in required:
            if key not in data or not is_exact_int(data[key]):
                raise ValueError(f"sidecar volume.{key} 缺失或不是整数")
        raw_label = data.get("volumeLabel", DEFAULT_VOLUME_LABEL)
        if not isinstance(raw_label, str):
            raise ValueError("sidecar volume.volumeLabel 必须是字符串")
        return cls(
            partition_start_lba=int(data["partitionStartLba"]),
            partition_sector_count=int(data["partitionSectorCount"]),
            sectors_per_cluster=int(data["sectorsPerCluster"]),
            reserved_sectors=int(data["reservedSectors"]),
            num_fats=int(data["numFats"]),
            sectors_per_fat=int(data["sectorsPerFat"]),
            root_cluster=int(data["rootCluster"]),
            fs_info_sector=int(data["fsInfoSector"]),
            backup_boot_sector=int(data["backupBootSector"]),
            volume_id=to_signed_i32(int(data["volumeId"])),
            volume_label=normalize_volume_label(raw_label),
        )


@dataclass
class TrackedEntry:
    relative_path: str
    is_directory: bool
    short_name: str
    attributes: int
    start_cluster: int
    clusters: list[int]
    size_bytes: int
    mtime_epoch_millis: int
    host_mtime_nanos: int
    host_size: int

    def to_sidecar_dict(self) -> dict[str, Any]:
        return {
            "relativePath": self.relative_path,
            "isDirectory": self.is_directory,
            "shortName": self.short_name[:11].ljust(11, " "),
            "attributes": int(self.attributes),
            "startCluster": int(self.start_cluster),
            "clusters": [int(c) for c in self.clusters],
            "sizeBytes": int(self.size_bytes),
            "mtimeEpochMillis": int(self.mtime_epoch_millis),
            "hostMtimeNanos": int(self.host_mtime_nanos),
            "hostSize": int(self.host_size),
        }


@dataclass
class ExifInfo:
    valid_jpeg: bool = False
    has_eoi: bool = False
    sof_marker: str = ""
    width: int = 0
    height: int = 0
    precision: int = 0
    components: int = 0
    has_exif_app1: bool = False
    byte_order: str = ""
    make: str = ""
    model: str = ""
    orientation: int | None = None
    software: str = ""
    datetime_str: str = ""
    datetime_original: str = ""
    exif_version: str = ""
    color_space: int | None = None
    pixel_x: int | None = None
    pixel_y: int | None = None
    interop_index: str = ""
    interop_version: str = ""
    has_thumbnail: bool = False
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "validJpeg": self.valid_jpeg,
            "hasEoi": self.has_eoi,
            "sofMarker": self.sof_marker,
            "width": self.width,
            "height": self.height,
            "precision": self.precision,
            "components": self.components,
            "hasExifApp1": self.has_exif_app1,
            "byteOrder": self.byte_order,
            "make": self.make,
            "model": self.model,
            "orientation": self.orientation,
            "software": self.software,
            "dateTime": self.datetime_str,
            "dateTimeOriginal": self.datetime_original,
            "exifVersion": self.exif_version,
            "colorSpace": self.color_space,
            "pixelXDimension": self.pixel_x,
            "pixelYDimension": self.pixel_y,
            "interopIndex": self.interop_index,
            "interopVersion": self.interop_version,
            "hasThumbnail": self.has_thumbnail,
            "errors": list(self.errors),
            "warnings": list(self.warnings),
        }


@dataclass
class ValidationReport:
    directory: str
    sidecar_path: str
    ok: bool = True
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    summary: dict[str, Any] = field(default_factory=dict)

    def add_error(self, msg: str) -> None:
        self.errors.append(msg)
        self.ok = False

    def add_warning(self, msg: str) -> None:
        self.warnings.append(msg)

    def to_dict(self) -> dict[str, Any]:
        return {
            "ok": self.ok,
            "directory": self.directory,
            "sidecarPath": self.sidecar_path,
            "errorCount": len(self.errors),
            "warningCount": len(self.warnings),
            "errors": list(self.errors),
            "warnings": list(self.warnings),
            "summary": self.summary,
        }


# =============================================================================
# 基础辅助函数
# =============================================================================


def is_exact_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def to_signed_i32(value: int) -> int:
    v = value & 0xFFFF_FFFF
    return v - 0x1_0000_0000 if v >= 0x8000_0000 else v


def to_unsigned_u32(value: int) -> int:
    return value & 0xFFFF_FFFF


def normalize_volume_label(label: str) -> str:
    cleaned = "".join(ch if 0x20 <= ord(ch) <= 0x7E else " " for ch in label.upper())
    return cleaned[:11].ljust(11, " ")


def sidecar_path_for(card_dir: Path) -> Path:
    resolved = card_dir.resolve()
    return resolved.with_name(resolved.name + ".fuji-sd.json")


def lock_path_for(card_dir: Path) -> Path:
    resolved = card_dir.resolve()
    return resolved.with_name(resolved.name + ".lock")


def is_safe_fat32_name(name: str) -> bool:
    """严格对齐 DirectoryFat32BlockStore.isSafeFileName。"""
    if not name or len(name) > 255:
        return False
    if name.startswith("."):
        return False
    for ch in name:
        if ord(ch) < 0x20 or ch in ILLEGAL_FAT_CHARS:
            return False
    return True


def make_short_name(name: str, used_short_names: set[str]) -> str:
    """生成 11 字符 FAT32 8.3 短文件名（与 DirectoryFat32BlockStore 一致）。"""
    dot = name.rfind(".")
    if dot > 0:
        raw_base = name[:dot]
        raw_ext = name[dot + 1 :]
    else:
        raw_base = name
        raw_ext = ""

    def clean_part(text: str) -> str:
        out: list[str] = []
        for ch in text.upper():
            if ch in " .":
                continue
            code = ord(ch)
            if (
                ord("A") <= code <= ord("Z")
                or ord("0") <= code <= ord("9")
                or ch in "_-$~!#%&{}()@^'`"
            ):
                out.append(ch)
            else:
                out.append("_")
        return "".join(out)

    base = clean_part(raw_base) or "FILE"
    ext = clean_part(raw_ext)[:3]

    needs_tilde = (
        len(raw_base) > 8
        or len(raw_ext) > 3
        or raw_base.upper() != base
        or raw_ext.upper() != ext
        or "." in raw_base
        or " " in name
    )

    if not needs_tilde:
        candidate = base[:8].ljust(8, " ") + ext.ljust(3, " ")
        if candidate not in used_short_names:
            used_short_names.add(candidate)
            return candidate

    for idx in range(1, 10000):
        suffix = f"~{idx}"
        prefix = base[: max(1, 8 - len(suffix))]
        candidate = (prefix + suffix).ljust(8, " ") + ext.ljust(3, " ")
        if candidate not in used_short_names:
            used_short_names.add(candidate)
            return candidate
    raise RuntimeError(f"无法为文件名生成唯一 8.3 短名: {name}")


def parse_volume_id(raw: str) -> int:
    text = raw.strip()
    if text.lower().startswith("0x"):
        return to_signed_i32(int(text, 16))
    return to_signed_i32(int(text, 10))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        while True:
            chunk = fh.read(65536)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def format_bytes(num_bytes: int) -> str:
    if num_bytes >= 1024**3 and num_bytes % (1024**3) == 0:
        return f"{num_bytes // (1024**3)} GiB ({num_bytes:,} B)"
    if num_bytes >= 1024**2:
        return f"{num_bytes / (1024**2):.2f} MiB ({num_bytes:,} B)"
    if num_bytes >= 1024:
        return f"{num_bytes / 1024:.2f} KiB ({num_bytes:,} B)"
    return f"{num_bytes} B"


# =============================================================================
# FAT32 / FF-DSC01 几何计算与引导扇区构造
# =============================================================================


def compute_ff_dsc01_volume(
    capacity_bytes: int,
    volume_label: str = DEFAULT_VOLUME_LABEL,
    volume_id: int = DEFAULT_VOLUME_ID,
) -> VolumeGeometry:
    """
    计算 FAT32 卷几何参数：
    - 4 GiB（4,294,967,296 B）采用实机 Fujifilm FF-DSC01 精确布局（M5LiveInCameraFormatE2eTest 实测值）：
      partitionStartLba=8192, partitionSectorCount=8380416, spc=64, reserved=6146, fats=2, spf=1023 -> dataStartLba=16384
    - 其余合法容量（>= 34 MiB 且 512 字节对齐）使用对齐集群边界的合法 FAT32 几何（clusterCount >= 65525）。
    """
    if capacity_bytes < MIN_CAPACITY_BYTES:
        raise ValueError(
            f"FAT32 容量过小（{capacity_bytes} B），至少需要 {MIN_CAPACITY_BYTES} B (34 MiB) 以满足 >= {MIN_FAT32_CLUSTERS} 簇"
        )
    if capacity_bytes % SECTOR_BYTES != 0:
        raise ValueError(f"容量必须是 {SECTOR_BYTES} 字节的整数倍: {capacity_bytes}")

    label = normalize_volume_label(volume_label)
    vol_id = to_signed_i32(volume_id)

    if capacity_bytes == DEFAULT_CAPACITY_BYTES:
        return VolumeGeometry(
            partition_start_lba=8192,
            partition_sector_count=8_380_416,
            sectors_per_cluster=64,
            reserved_sectors=6146,
            num_fats=2,
            sectors_per_fat=1023,
            root_cluster=2,
            fs_info_sector=1,
            backup_boot_sector=6,
            volume_id=vol_id,
            volume_label=label,
        )

    total_sectors = capacity_bytes // SECTOR_BYTES
    part_start = 8192 if capacity_bytes >= 64 * 1024 * 1024 else 2048
    if total_sectors <= part_start + 66_000:
        part_start = 2048
    part_sectors = total_sectors - part_start
    num_fats = 2

    # 匹配 DirectoryFat32BlockStore.computeDefaultVolume 的簇大小策略
    if capacity_bytes <= 128 * 1024 * 1024:
        spc = 1
    elif capacity_bytes <= 512 * 1024 * 1024:
        spc = 8
    elif capacity_bytes <= 2 * 1024 * 1024 * 1024:
        spc = 16
    elif capacity_bytes <= 8 * 1024 * 1024 * 1024:
        spc = 64
    else:
        spc = 64

    # 如所选 spc 导致簇数不足 FAT32 下限，自动回退到更小 spc
    for candidate_spc in [spc, 32, 16, 8, 4, 2, 1]:
        if candidate_spc > spc:
            continue
        reserved = 32
        spf = 1
        for _ in range(16):
            data_sec = part_sectors - reserved - num_fats * spf
            clusters = data_sec // candidate_spc
            new_spf = ((clusters + 2) * 4 + SECTOR_BYTES - 1) // SECTOR_BYTES
            if new_spf == spf:
                break
            spf = new_spf
        # 将 data_start_lba 对齐到 candidate_spc 边界（保持 >= 32 保留扇区）
        rem = (part_start + reserved + num_fats * spf) % candidate_spc
        if rem != 0:
            reserved += candidate_spc - rem
            # 调整保留扇区后重新收敛 spf
            for _ in range(8):
                data_sec = part_sectors - reserved - num_fats * spf
                clusters = data_sec // candidate_spc
                new_spf = ((clusters + 2) * 4 + SECTOR_BYTES - 1) // SECTOR_BYTES
                if new_spf == spf:
                    break
                spf = new_spf
        data_sec = part_sectors - reserved - num_fats * spf
        clusters = data_sec // candidate_spc
        if clusters >= MIN_FAT32_CLUSTERS:
            return VolumeGeometry(
                partition_start_lba=part_start,
                partition_sector_count=part_sectors,
                sectors_per_cluster=candidate_spc,
                reserved_sectors=reserved,
                num_fats=num_fats,
                sectors_per_fat=spf,
                root_cluster=2,
                fs_info_sector=1,
                backup_boot_sector=6,
                volume_id=vol_id,
                volume_label=label,
            )

    raise ValueError(f"无法为容量 {capacity_bytes} B 计算合法 FAT32 卷几何（簇数 < {MIN_FAT32_CLUSTERS}）")


def build_boot_sectors(
    vol: VolumeGeometry,
    free_count: int,
    next_free: int,
    oem_id: str = FF_DSC01_OEM_ID,
) -> dict[int, bytes]:
    """构造 MBR、VBR（OEM=FF-DSC01）、Backup VBR、FSInfo 及 Backup FSInfo 原始扇区。"""
    raw: dict[int, bytes] = {}
    oem_bytes = oem_id.encode("ascii")[:8].ljust(8, b" ")
    label_bytes = normalize_volume_label(vol.volume_label).encode("ascii")

    # 1. MBR (LBA 0)
    if vol.partition_start_lba > 0:
        mbr = bytearray(SECTOR_BYTES)
        pe = 0x1BE
        mbr[pe] = 0x00
        mbr[pe + 1 : pe + 4] = b"\xFE\xFF\xFF"
        mbr[pe + 4] = 0x0C  # FAT32 LBA
        mbr[pe + 5 : pe + 8] = b"\xFE\xFF\xFF"
        struct.pack_into("<II", mbr, pe + 8, vol.partition_start_lba, vol.partition_sector_count)
        mbr[0x1FE] = 0x55
        mbr[0x1FF] = 0xAA
        raw[0] = bytes(mbr)

    # 2. VBR / BPB (LBA partition_start_lba)
    vbr = bytearray(SECTOR_BYTES)
    vbr[0:3] = b"\xEB\x58\x90"
    vbr[3:11] = oem_bytes
    struct.pack_into("<H", vbr, 0x0B, SECTOR_BYTES)
    vbr[0x0D] = vol.sectors_per_cluster
    struct.pack_into("<H", vbr, 0x0E, vol.reserved_sectors)
    vbr[0x10] = vol.num_fats
    struct.pack_into("<HH", vbr, 0x11, 0, 0)
    vbr[0x15] = 0xF8
    struct.pack_into("<HHH", vbr, 0x16, 0, 63, 255)
    struct.pack_into("<III", vbr, 0x1C, vol.partition_start_lba, vol.partition_sector_count, vol.sectors_per_fat)
    struct.pack_into("<HH", vbr, 0x28, 0, 0)
    struct.pack_into("<IHH", vbr, 0x2C, vol.root_cluster, vol.fs_info_sector, vol.backup_boot_sector)
    vbr[0x40] = 0x80
    vbr[0x42] = 0x29
    struct.pack_into("<I", vbr, 0x43, to_unsigned_u32(vol.volume_id))
    vbr[0x47:0x52] = label_bytes
    vbr[0x52:0x5A] = b"FAT32   "
    vbr[0x1FE] = 0x55
    vbr[0x1FF] = 0xAA
    vbr_bytes = bytes(vbr)
    raw[vol.partition_start_lba] = vbr_bytes
    if 0 < vol.backup_boot_sector < vol.reserved_sectors:
        raw[vol.partition_start_lba + vol.backup_boot_sector] = vbr_bytes

    # 3. FSInfo & Backup FSInfo
    if 0 < vol.fs_info_sector < vol.reserved_sectors:
        fsi = bytearray(SECTOR_BYTES)
        struct.pack_into("<I", fsi, 0x000, FSINFO_LEAD_SIG)
        struct.pack_into(
            "<III",
            fsi,
            0x1E4,
            FSINFO_STRUCT_SIG,
            to_unsigned_u32(free_count),
            to_unsigned_u32(next_free),
        )
        struct.pack_into("<I", fsi, 0x1FC, FSINFO_TRAIL_SIG)
        fsi_bytes = bytes(fsi)
        raw[vol.partition_start_lba + vol.fs_info_sector] = fsi_bytes
        if 0 < vol.backup_boot_sector and vol.backup_boot_sector + vol.fs_info_sector < vol.reserved_sectors:
            raw[vol.partition_start_lba + vol.backup_boot_sector + vol.fs_info_sector] = fsi_bytes

    return raw


# =============================================================================
# 零依赖 JPEG / EXIF / DCF 2.0 解析器与最小合法样例构造器
# =============================================================================


def _read_u16(buf: bytes, offset: int, big_endian: bool) -> int:
    return struct.unpack_from(">H" if big_endian else "<H", buf, offset)[0]


def _read_u32(buf: bytes, offset: int, big_endian: bool) -> int:
    return struct.unpack_from(">I" if big_endian else "<I", buf, offset)[0]


def _read_tiff_value(tiff: bytes, tiff_type: int, count: int, value_field_off: int, big_endian: bool) -> Any:
    type_sizes = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8}
    unit = type_sizes.get(tiff_type)
    if unit is None or count < 0:
        return None
    total = unit * count
    if total <= 4:
        data_off = value_field_off
    else:
        data_off = _read_u32(tiff, value_field_off, big_endian)
    if data_off < 0 or data_off + total > len(tiff):
        return None
    raw = tiff[data_off : data_off + total]
    if tiff_type == 2:  # ASCII
        return raw.split(b"\x00", 1)[0].decode("ascii", errors="replace").strip()
    if tiff_type == 7:  # UNDEFINED
        return raw
    if tiff_type == 3:  # SHORT
        vals = [struct.unpack_from(">H" if big_endian else "<H", raw, i * 2)[0] for i in range(count)]
        return vals[0] if count == 1 else vals
    if tiff_type == 4:  # LONG
        vals = [struct.unpack_from(">I" if big_endian else "<I", raw, i * 4)[0] for i in range(count)]
        return vals[0] if count == 1 else vals
    return raw


def _parse_ifd(tiff: bytes, offset: int, big_endian: bool) -> tuple[dict[int, Any], int]:
    if offset <= 0 or offset + 2 > len(tiff):
        return {}, 0
    num_entries = _read_u16(tiff, offset, big_endian)
    pos = offset + 2
    tags: dict[int, Any] = {}
    for _ in range(num_entries):
        if pos + 12 > len(tiff):
            break
        tag = _read_u16(tiff, pos, big_endian)
        tiff_type = _read_u16(tiff, pos + 2, big_endian)
        count = _read_u32(tiff, pos + 4, big_endian)
        tags[tag] = _read_tiff_value(tiff, tiff_type, count, pos + 8, big_endian)
        pos += 12
    next_ifd = _read_u32(tiff, pos, big_endian) if pos + 4 <= len(tiff) else 0
    return tags, next_ifd


def parse_jpeg_exif(data: bytes) -> ExifInfo:
    """零依赖解析 JPEG 帧头与 APP1 EXIF/DCF 2.0 标签。"""
    info = ExifInfo()
    if len(data) < 4 or data[0:2] != b"\xFF\xD8":
        info.errors.append("缺少 JPEG SOI 起始标记 (0xFFD8)")
        return info

    pos = 2
    n = len(data)
    exif_payload: bytes | None = None
    found_sof = False

    while pos < n:
        if data[pos] != 0xFF:
            info.errors.append(f"偏移 0x{pos:X} 处缺少 JPEG 标记前缀 0xFF")
            break
        while pos < n and data[pos] == 0xFF:
            pos += 1
        if pos >= n:
            break
        marker = data[pos]
        pos += 1

        if marker == 0xD9:  # EOI
            info.has_eoi = True
            break
        if marker == 0x01 or (0xD0 <= marker <= 0xD7):  # TEM / RSTn
            continue
        if marker == 0xDA:  # SOS：其后为熵编码扫描流，定位尾部 EOI
            if pos + 2 > n:
                info.errors.append("SOS 段被截断")
                break
            sos_len = struct.unpack_from(">H", data, pos)[0]
            if sos_len < 2 or pos + sos_len > n:
                info.errors.append("SOS 段长度越界")
                break
            # 在扫描流中检测 EOI（忽略字节填充 0xFF 0x00 及 RSTn）
            scan_pos = pos + sos_len
            while scan_pos < n - 1:
                idx = data.find(b"\xFF", scan_pos)
                if idx < 0 or idx + 1 >= n:
                    break
                nxt = data[idx + 1]
                if nxt == 0x00 or nxt == 0xFF or (0xD0 <= nxt <= 0xD7):
                    scan_pos = idx + 2
                    continue
                if nxt == 0xD9:
                    info.has_eoi = True
                    break
                # 兼容多扫描渐进 JPEG，跳出继续常规段解析
                pos = idx
                break
            else:
                break
            if info.has_eoi:
                break
            if pos < scan_pos:
                break
            continue

        if pos + 2 > n:
            info.errors.append(f"标记 0xFF{marker:02X} 长度字段被截断")
            break
        seg_len = struct.unpack_from(">H", data, pos)[0]
        if seg_len < 2 or pos + seg_len > n:
            info.errors.append(f"标记 0xFF{marker:02X} 段长度越界 ({seg_len})")
            break
        seg_body = data[pos + 2 : pos + seg_len]
        pos += seg_len

        if marker == 0xE1 and exif_payload is None and seg_body.startswith(b"Exif\x00\x00"):
            exif_payload = seg_body[6:]
        elif marker in (0xC0, 0xC1, 0xC2, 0xC3) and not found_sof:
            if len(seg_body) >= 6:
                info.sof_marker = f"SOF{marker - 0xC0}"
                info.precision = seg_body[0]
                info.height, info.width = struct.unpack_from(">HH", seg_body, 1)
                info.components = seg_body[5]
                found_sof = True

    if not found_sof or info.width <= 0 or info.height <= 0:
        info.errors.append("缺少有效的 JPEG SOF 图像尺寸段")
    if not info.has_eoi:
        info.errors.append("缺少 JPEG EOI 结束标记 (0xFFD9)，文件可能截断")

    info.valid_jpeg = found_sof and info.has_eoi and not info.errors

    # 解析 APP1 EXIF TIFF
    if exif_payload is None:
        info.warnings.append("缺少 APP1 EXIF 元数据段（不符合 DCF 2.0 规范）")
        return info

    info.has_exif_app1 = True
    if len(exif_payload) < 8:
        info.errors.append("APP1 EXIF TIFF 头长度不足 8 字节")
        return info

    endian_tag = exif_payload[0:2]
    if endian_tag == b"II":
        big_endian = False
        info.byte_order = "LE (II)"
    elif endian_tag == b"MM":
        big_endian = True
        info.byte_order = "BE (MM)"
    else:
        info.errors.append(f"无效的 EXIF TIFF 字节序标记: {endian_tag!r}")
        return info

    magic = _read_u16(exif_payload, 2, big_endian)
    if magic != 42:
        info.errors.append(f"无效的 EXIF TIFF 魔数: {magic} (应为 42)")
        return info

    ifd0_off = _read_u32(exif_payload, 4, big_endian)
    ifd0, ifd1_off = _parse_ifd(exif_payload, ifd0_off, big_endian)
    if not ifd0:
        info.errors.append("EXIF IFD0 为空或偏移越界")
        return info

    info.has_thumbnail = ifd1_off > 0 and ifd1_off < len(exif_payload)
    info.make = str(ifd0.get(0x010F, "") or "")
    info.model = str(ifd0.get(0x0110, "") or "")
    if isinstance(ifd0.get(0x0112), int):
        info.orientation = int(ifd0[0x0112])
    info.software = str(ifd0.get(0x0131, "") or "")
    info.datetime_str = str(ifd0.get(0x0132, "") or "")

    exif_ifd_off = ifd0.get(0x8769)
    if isinstance(exif_ifd_off, int) and 0 < exif_ifd_off < len(exif_payload):
        exif_ifd, _ = _parse_ifd(exif_payload, exif_ifd_off, big_endian)
        ver = exif_ifd.get(0x9000)
        if isinstance(ver, (bytes, bytearray)):
            info.exif_version = ver.decode("ascii", errors="replace")
        elif isinstance(ver, str):
            info.exif_version = ver
        info.datetime_original = str(exif_ifd.get(0x9003, "") or "")
        if isinstance(exif_ifd.get(0xA001), int):
            info.color_space = int(exif_ifd[0xA001])
        if isinstance(exif_ifd.get(0xA002), int):
            info.pixel_x = int(exif_ifd[0xA002])
        if isinstance(exif_ifd.get(0xA003), int):
            info.pixel_y = int(exif_ifd[0xA003])

        interop_off = exif_ifd.get(0xA005)
        if isinstance(interop_off, int) and 0 < interop_off < len(exif_payload):
            interop_ifd, _ = _parse_ifd(exif_payload, interop_off, big_endian)
            info.interop_index = str(interop_ifd.get(0x0001, "") or "")
            iver = interop_ifd.get(0x0002)
            if isinstance(iver, (bytes, bytearray)):
                info.interop_version = iver.decode("ascii", errors="replace")
    else:
        info.warnings.append("EXIF 缺少 ExifIFD (0x8769) 子目录")

    # DCF 2.0 合规提示
    if not info.make or not info.model:
        info.warnings.append("EXIF 缺少相机厂商(Make)或型号(Model)标签")
    if info.components not in (0, 3):
        info.warnings.append(f"JPEG 色彩分量数为 {info.components}（DCF 标准要求 3 分量 YCbCr）")
    if info.pixel_x is not None and info.width > 0 and info.pixel_x != info.width:
        info.warnings.append(f"EXIF PixelXDimension ({info.pixel_x}) 与 JPEG 实际宽度 ({info.width}) 不一致")
    if info.pixel_y is not None and info.height > 0 and info.pixel_y != info.height:
        info.warnings.append(f"EXIF PixelYDimension ({info.pixel_y}) 与 JPEG 实际高度 ({info.height}) 不一致")
    if info.interop_index and info.interop_index not in ("R98", "R03", "THM"):
        info.warnings.append(f"EXIF InteroperabilityIndex={info.interop_index!r} 不是常见 DCF 标识 (R98/R03)")
    if not info.interop_index:
        info.warnings.append("EXIF 缺少 DCF InteroperabilityIFD (R98/R03)")

    return info


def _build_tiff_ifd_block(entries: list[tuple[int, int, int, bytes]]) -> tuple[bytes, list[tuple[int, bytes]]]:
    """按标签升序序列化单个 Little-Endian TIFF IFD（返回 IFD 本体与外部长值列表）。"""
    ordered = sorted(entries, key=lambda item: item[0])
    out = bytearray()
    out += struct.pack("<H", len(ordered))
    externals: list[tuple[int, bytes]] = []
    for tag, tiff_type, count, raw_val in ordered:
        entry_off = len(out)
        if len(raw_val) <= 4:
            padded = raw_val.ljust(4, b"\x00")
            out += struct.pack("<HHI", tag, tiff_type, count) + padded
        else:
            out += struct.pack("<HHII", tag, tiff_type, count, 0)
            externals.append((entry_off + 8, raw_val))
    out += struct.pack("<I", 0)  # next IFD = 0
    return bytes(out), externals


def build_sample_dcf_jpeg(
    make: str = "FUJIFILM",
    model: str = "GFX100S",
    dt_str: str | None = None,
) -> bytes:
    """
    零依赖构造标准 16x16 基线 YCbCr DCF 2.0 / EXIF 2.32 JPEG 样本照片。
    包含合法 SOI、APP1(Exif/IFD0/ExifIFD/InteropIFD R98)、DQT、SOF0(16x16 3-comp)、DHT、SOS 与 EOI。
    """
    if dt_str is None:
        dt_str = datetime.now(timezone.utc).strftime("%Y:%m:%d %H:%M:%S")
    width, height = 16, 16

    # 构造 Little-Endian TIFF EXIF 结构
    tiff = bytearray(b"II" + struct.pack("<HI", 42, 8))

    def append_ifd(
        entries: list[tuple[int, int, int, bytes]],
    ) -> tuple[int, dict[int, int]]:
        base_off = len(tiff)
        ordered = sorted(entries, key=lambda item: item[0])
        block, externals = _build_tiff_ifd_block(ordered)
        tag_val_offsets: dict[int, int] = {}
        for idx, (tag, _tt, _cnt, _rv) in enumerate(ordered):
            tag_val_offsets[tag] = base_off + 2 + idx * 12 + 8
        tiff.extend(block)
        for rel_off, payload in externals:
            abs_val_off = len(tiff)
            struct.pack_into("<I", tiff, base_off + rel_off, abs_val_off)
            tiff.extend(payload)
            if len(tiff) % 2 == 1:
                tiff.append(0)
        return base_off, tag_val_offsets

    make_b = (make + "\x00").encode("ascii")
    model_b = (model + "\x00").encode("ascii")
    sw_b = b"Digital Camera GFX100S Ver2.13\x00"
    dt_b = (dt_str + "\x00").encode("ascii")

    # 1. IFD0（占位 ExifIFD 指针）
    _ifd0_off, ifd0_val_pos = append_ifd(
        [
            (0x010F, 2, len(make_b), make_b),
            (0x0110, 2, len(model_b), model_b),
            (0x0112, 3, 1, struct.pack("<H", 1)),
            (0x0131, 2, len(sw_b), sw_b),
            (0x0132, 2, len(dt_b), dt_b),
            (0x8769, 4, 1, struct.pack("<I", 0)),
        ]
    )

    # 2. ExifIFD（占位 InteropIFD 指针）
    exif_ifd_off, exif_val_pos = append_ifd(
        [
            (0x9000, 7, 4, b"0232"),
            (0x9003, 2, len(dt_b), dt_b),
            (0x9004, 2, len(dt_b), dt_b),
            (0xA001, 3, 1, struct.pack("<H", 1)),  # sRGB
            (0xA002, 4, 1, struct.pack("<I", width)),
            (0xA003, 4, 1, struct.pack("<I", height)),
            (0xA005, 4, 1, struct.pack("<I", 0)),
        ]
    )
    struct.pack_into("<I", tiff, ifd0_val_pos[0x8769], exif_ifd_off)

    # 3. InteroperabilityIFD (DCF 2.0 R98)
    interop_off, _ = append_ifd(
        [
            (0x0001, 2, 4, b"R98\x00"),
            (0x0002, 7, 4, b"0100"),
        ]
    )
    struct.pack_into("<I", tiff, exif_val_pos[0xA005], interop_off)

    app1_body = b"Exif\x00\x00" + bytes(tiff)

    # DQT（单一 8-bit 量化表 ID=0，全 16）
    dqt_body = bytes([0x00]) + bytes([16] * 64)

    # SOF0（基线 DCT，8-bit，16x16，3 分量 4:4:4 YCbCr，全引用 QTable 0）
    sof0_body = struct.pack(
        ">BHHB" + "BBB" * 3,
        8,
        height,
        width,
        3,
        1,
        0x11,
        0,
        2,
        0x11,
        0,
        3,
        0x11,
        0,
    )

    # 最小合法 Huffman 表：DC 表 0（符号 0 -> 码字 '0'），AC 表 0（符号 0x00 EOB -> 码字 '0'）
    # 16 个码长计数 [1, 0, ..., 0] + 符号 [0x00]
    dht_dc = bytes([0x00, 1] + [0] * 15 + [0x00])
    dht_ac = bytes([0x10, 1] + [0] * 15 + [0x00])
    dht_body = dht_dc + dht_ac

    # SOS：3 分量均使用 DC0/AC0；16x16 @ 4:4:4 = 4 个 MCU = 12 个 8x8 块，每块 DC=0 ('0') + AC=EOB ('0') = 24 个 0 位 = 3 字节 0x00
    sos_body = struct.pack(">BBBBBBBBBB", 3, 1, 0x00, 2, 0x00, 3, 0x00, 0, 63, 0)
    scan_data = b"\x00\x00\x00"

    def seg(marker: int, body: bytes) -> bytes:
        return struct.pack(">BBH", 0xFF, marker, len(body) + 2) + body

    return (
        b"\xFF\xD8"
        + seg(0xE1, app1_body)
        + seg(0xDB, dqt_body)
        + seg(0xC0, sof0_body)
        + seg(0xC4, dht_body)
        + seg(0xDA, sos_body)
        + scan_data
        + b"\xFF\xD9"
    )


def build_default_ffxfer_payload() -> bytes:
    """构造 1024 字节确定性 FFDB/FFXFER.DAT 初始内容。"""
    buf = bytearray(1024)
    buf[0 : len(FFXFER_HEADER_MAGIC)] = FFXFER_HEADER_MAGIC
    tag = b"FUJIFILM GFX100S FF-DSC01"
    buf[16 : 16 + len(tag)] = tag
    return bytes(buf)


# =============================================================================
# 宿主目录扫描与 Sidecar JSON 读写（严格兼容 SdSidecarJson.kt）
# =============================================================================


def assert_integer_only_json(obj: Any, path: str = "$") -> None:
    """SdSidecarJson.kt 仅支持整数，拒绝任何浮点数。"""
    if isinstance(obj, float):
        raise ValueError(f"sidecar JSON 字段 {path} 包含浮点数 {obj!r}（SdSidecarJson 仅支持整数）")
    if isinstance(obj, dict):
        for k, v in obj.items():
            assert_integer_only_json(v, f"{path}.{k}")
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            assert_integer_only_json(v, f"{path}[{i}]")


def load_sidecar_json(path: Path) -> dict[str, Any]:
    raw_text = path.read_text(encoding="utf-8")
    parsed = json.loads(raw_text)
    if not isinstance(parsed, dict):
        raise ValueError("sidecar 根节点必须是 JSON 对象")
    assert_integer_only_json(parsed)
    return parsed


def write_sidecar_atomic(path: Path, doc: dict[str, Any]) -> None:
    assert_integer_only_json(doc)
    text = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_name(path.name + f".tmp.{os.getpid()}")
    try:
        with tmp_path.open("w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        os.replace(tmp_path, path)
    finally:
        if tmp_path.exists():
            try:
                tmp_path.unlink()
            except OSError:
                pass


@dataclass
class HostScanItem:
    relative_path: str
    full_path: Path
    is_directory: bool
    size_bytes: int
    mtime_nanos: int
    depth: int


def scan_host_directory(root_dir: Path, report: ValidationReport | None = None) -> list[HostScanItem]:
    """按 DirectoryFat32BlockStore.scanHostDirectory() 规则递归扫描宿主目录。"""
    items: list[HostScanItem] = []

    def fail_or_raise(msg: str) -> None:
        if report is not None:
            report.add_error(msg)
        else:
            raise ValueError(msg)

    def walk(current: Path, prefix: str, depth: int) -> None:
        if depth > MAX_DIR_DEPTH:
            fail_or_raise(f"宿主目录嵌套深度超过上限 {MAX_DIR_DEPTH}: {prefix}")
            return
        try:
            children = sorted(current.iterdir(), key=lambda p: p.name)
        except OSError as exc:
            fail_or_raise(f"无法读取目录 {current}: {exc}")
            return

        seen_lower: set[str] = set()
        for child in children:
            name = child.name
            rel = name if not prefix else f"{prefix}/{name}"
            if child.is_symlink():
                fail_or_raise(f"宿主 SD 目录禁止包含符号链接: {rel}")
                continue
            if not is_safe_fat32_name(name):
                fail_or_raise(f"存在非法 FAT32 或隐藏文件名（禁止以 '.' 开头或含非法字符）: {rel}")
                continue
            lower = name.lower()
            if lower in seen_lower:
                fail_or_raise(f"目录内存在大小写不敏感文件名冲突: {rel}")
                continue
            seen_lower.add(lower)

            st = os.lstat(child)
            is_dir = child.is_dir()
            size_bytes = 0 if is_dir else int(st.st_size)
            if not is_dir and size_bytes > MAX_FILE_SIZE:
                fail_or_raise(f"文件大小超过 FAT32 4GiB-1 上限: {rel} ({size_bytes} B)")
            items.append(
                HostScanItem(
                    relative_path=rel,
                    full_path=child,
                    is_directory=is_dir,
                    size_bytes=size_bytes,
                    mtime_nanos=int(st.st_mtime_ns),
                    depth=depth,
                )
            )
            if is_dir:
                walk(child, rel, depth + 1)

    walk(root_dir, "", 1)
    return items


def build_sidecar_document(
    card_dir: Path,
    vol: VolumeGeometry,
    capacity_bytes: int,
    oem_id: str = FF_DSC01_OEM_ID,
) -> dict[str, Any]:
    """基于宿主目录内容生成完整的、可直接供模拟器挂载的 `.fuji-sd.json` sidecar 文档。"""
    scanned = scan_host_directory(card_dir)
    cluster_bytes = vol.cluster_bytes
    max_cluster = vol.root_cluster + vol.cluster_count - 1
    next_cluster = vol.root_cluster + 1
    used_clusters = 1  # rootCluster

    used_short_by_parent: dict[str, set[str]] = {}
    entries: list[TrackedEntry] = []

    for item in scanned:
        parent_key = item.relative_path.rsplit("/", 1)[0] if "/" in item.relative_path else ""
        base_name = item.relative_path.rsplit("/", 1)[-1]
        short_pool = used_short_by_parent.setdefault(parent_key, set())
        short_name = make_short_name(base_name, short_pool)

        if item.is_directory:
            needed = 1
        elif item.size_bytes == 0:
            needed = 0
        else:
            needed = (item.size_bytes + cluster_bytes - 1) // cluster_bytes

        if next_cluster + needed - 1 > max_cluster:
            raise ValueError(f"SD 卡空间不足，无法为 {item.relative_path} 分配 {needed} 个簇")

        clusters = list(range(next_cluster, next_cluster + needed))
        next_cluster += needed
        used_clusters += needed
        start_cluster = clusters[0] if clusters else 0

        entries.append(
            TrackedEntry(
                relative_path=item.relative_path,
                is_directory=item.is_directory,
                short_name=short_name,
                attributes=ATTR_DIRECTORY if item.is_directory else ATTR_ARCHIVE,
                start_cluster=start_cluster,
                clusters=clusters,
                size_bytes=0 if item.is_directory else item.size_bytes,
                mtime_epoch_millis=item.mtime_nanos // 1_000_000,
                host_mtime_nanos=item.mtime_nanos,
                host_size=0 if item.is_directory else item.size_bytes,
            )
        )

    free_count = max(0, vol.cluster_count - used_clusters)
    raw_sectors = build_boot_sectors(vol, free_count=free_count, next_free=next_cluster, oem_id=oem_id)
    raw_encoded = {
        str(lba): base64.b64encode(sec).decode("ascii")
        for lba, sec in sorted(raw_sectors.items())
        if any(b != 0 for b in sec)
    }

    return {
        "schema": SIDECAR_SCHEMA,
        "capacityBytes": int(capacity_bytes),
        "sectorSize": SECTOR_BYTES,
        "formatted": True,
        "volume": vol.to_sidecar_dict(),
        "rawSectors": raw_encoded,
        "entries": [e.to_sidecar_dict() for e in entries],
    }


# =============================================================================
# 核心业务：init / inspect / validate
# =============================================================================


def init_sdcard(
    card_dir: Path,
    capacity_bytes: int = DEFAULT_CAPACITY_BYTES,
    volume_label: str = DEFAULT_VOLUME_LABEL,
    volume_id: int = DEFAULT_VOLUME_ID,
    with_sample_photo: bool = False,
    force: bool = False,
) -> dict[str, Any]:
    """初始化标准 FF-DSC01 目录模式 SD 卡。"""
    resolved_dir = card_dir.resolve()
    sidecar = sidecar_path_for(resolved_dir)

    if resolved_dir.is_symlink():
        raise ValueError(f"目标路径不能是符号链接: {resolved_dir}")
    if resolved_dir.exists():
        if not resolved_dir.is_dir():
            raise ValueError(f"目标路径已存在且不是目录: {resolved_dir}")
        has_contents = any(resolved_dir.iterdir())
        if (has_contents or sidecar.exists()) and not force:
            raise FileExistsError(
                f"目标 SD 目录非空或已存在 sidecar（如需覆盖重建请加 --force）: {resolved_dir}"
            )
        if force:
            for child in list(resolved_dir.iterdir()):
                if child.is_dir() and not child.is_symlink():
                    shutil.rmtree(child)
                else:
                    child.unlink()
            if sidecar.exists() or sidecar.is_symlink():
                sidecar.unlink()

    vol = compute_ff_dsc01_volume(
        capacity_bytes=capacity_bytes,
        volume_label=volume_label,
        volume_id=volume_id,
    )

    resolved_dir.mkdir(parents=True, exist_ok=True)
    ffdb_dir = resolved_dir / "FFDB"
    dcim_dir = resolved_dir / "DCIM" / "100_FUJI"
    ffdb_dir.mkdir(parents=True, exist_ok=True)
    dcim_dir.mkdir(parents=True, exist_ok=True)

    ffxfer_path = ffdb_dir / "FFXFER.DAT"
    ffxfer_bytes = build_default_ffxfer_payload()
    ffxfer_path.write_bytes(ffxfer_bytes)

    sample_photo_rel: str | None = None
    if with_sample_photo:
        sample_path = dcim_dir / "DSCF0001.JPG"
        sample_path.write_bytes(build_sample_dcf_jpeg())
        sample_photo_rel = "DCIM/100_FUJI/DSCF0001.JPG"

    doc = build_sidecar_document(resolved_dir, vol, capacity_bytes, oem_id=FF_DSC01_OEM_ID)
    write_sidecar_atomic(sidecar, doc)

    return {
        "directory": str(resolved_dir),
        "sidecarPath": str(sidecar),
        "oemId": FF_DSC01_OEM_ID,
        "capacityBytes": capacity_bytes,
        "volume": doc["volume"],
        "ffxferPath": "FFDB/FFXFER.DAT",
        "ffxferBytes": len(ffxfer_bytes),
        "ffxferSha256": hashlib.sha256(ffxfer_bytes).hexdigest(),
        "samplePhoto": sample_photo_rel,
        "trackedEntries": len(doc["entries"]),
    }


def _parse_boot_sector_summary(raw_sectors: dict[int, bytes], vol: VolumeGeometry) -> dict[str, Any]:
    info: dict[str, Any] = {
        "hasMbr": 0 in raw_sectors,
        "hasVbr": vol.partition_start_lba in raw_sectors,
        "hasBackupVbr": (vol.partition_start_lba + vol.backup_boot_sector) in raw_sectors,
        "hasFsInfo": (vol.partition_start_lba + vol.fs_info_sector) in raw_sectors,
        "oemId": "",
        "fsType": "",
        "mbrPartitionType": None,
        "fsInfoFreeClusters": None,
        "fsInfoNextFree": None,
    }
    mbr = raw_sectors.get(0)
    if mbr and len(mbr) == SECTOR_BYTES:
        info["mbrPartitionType"] = f"0x{mbr[0x1BE + 4]:02X}"
    vbr = raw_sectors.get(vol.partition_start_lba)
    if vbr and len(vbr) == SECTOR_BYTES:
        info["oemId"] = vbr[3:11].decode("ascii", errors="replace").rstrip(" \x00")
        info["fsType"] = vbr[0x52:0x5A].decode("ascii", errors="replace").rstrip(" \x00")
    fsi = raw_sectors.get(vol.partition_start_lba + vol.fs_info_sector)
    if fsi and len(fsi) == SECTOR_BYTES:
        free_cnt, next_free = struct.unpack_from("<II", fsi, 0x1E8)
        info["fsInfoFreeClusters"] = free_cnt
        info["fsInfoNextFree"] = next_free
    return info


def inspect_sdcard(card_dir: Path) -> dict[str, Any]:
    """检查目录模式 SD 卡的完整结构信息。"""
    resolved_dir = card_dir.resolve()
    sidecar = sidecar_path_for(resolved_dir)
    lock_file = lock_path_for(resolved_dir)

    if not resolved_dir.exists() or not resolved_dir.is_dir() or resolved_dir.is_symlink():
        raise FileNotFoundError(f"SD 卡目录不存在或不是有效目录: {resolved_dir}")

    result: dict[str, Any] = {
        "directory": str(resolved_dir),
        "sidecarPath": str(sidecar),
        "sidecarExists": sidecar.exists() and not sidecar.is_symlink(),
        "lockExists": lock_file.exists(),
        "sidecar": None,
        "ffdb": {
            "exists": False,
            "ffxferExists": False,
            "ffxferSize": 0,
            "ffxferSha256": None,
            "headerHex": None,
        },
        "dcim": {
            "exists": False,
            "folders": [],
            "photoCount": 0,
            "rafCount": 0,
            "otherMediaCount": 0,
            "photos": [],
        },
        "hostSummary": {
            "directoryCount": 0,
            "fileCount": 0,
            "totalFileBytes": 0,
            "hygieneIssues": [],
        },
    }

    # 1. 宿主目录扫描
    hygiene_probe = ValidationReport(directory=str(resolved_dir), sidecar_path=str(sidecar))
    scanned = scan_host_directory(resolved_dir, report=hygiene_probe)
    result["hostSummary"]["directoryCount"] = sum(1 for x in scanned if x.is_directory)
    result["hostSummary"]["fileCount"] = sum(1 for x in scanned if not x.is_directory)
    result["hostSummary"]["totalFileBytes"] = sum(x.size_bytes for x in scanned if not x.is_directory)
    result["hostSummary"]["hygieneIssues"] = list(hygiene_probe.errors)

    # 2. Sidecar 摘要
    if result["sidecarExists"]:
        try:
            doc = load_sidecar_json(sidecar)
            vol = VolumeGeometry.from_sidecar_dict(doc["volume"])
            raw_sectors: dict[int, bytes] = {}
            for k, v in (doc.get("rawSectors") or {}).items():
                if isinstance(k, str) and isinstance(v, str):
                    raw_sectors[int(k)] = base64.b64decode(v)
            entries = doc.get("entries") or []
            used_clusters = 1 + sum(len(e.get("clusters") or []) for e in entries if isinstance(e, dict))
            boot_info = _parse_boot_sector_summary(raw_sectors, vol)
            result["sidecar"] = {
                "schema": doc.get("schema"),
                "formatted": doc.get("formatted"),
                "capacityBytes": doc.get("capacityBytes"),
                "sectorSize": doc.get("sectorSize"),
                "volume": vol.to_sidecar_dict(),
                "clusterBytes": vol.cluster_bytes,
                "clusterCount": vol.cluster_count,
                "dataStartLba": vol.data_start_lba,
                "usedClusters": used_clusters,
                "freeClusters": max(0, vol.cluster_count - used_clusters),
                "trackedEntries": len(entries),
                "rawSectorLbas": sorted(raw_sectors.keys()),
                "boot": boot_info,
            }
        except Exception as exc:
            result["sidecar"] = {"parseError": str(exc)}

    # 3. FFDB / FFXFER.DAT
    ffdb_dir = resolved_dir / "FFDB"
    ffxfer = ffdb_dir / "FFXFER.DAT"
    result["ffdb"]["exists"] = ffdb_dir.is_dir() and not ffdb_dir.is_symlink()
    if ffxfer.is_file() and not ffxfer.is_symlink():
        data = ffxfer.read_bytes()
        result["ffdb"]["ffxferExists"] = True
        result["ffdb"]["ffxferSize"] = len(data)
        result["ffdb"]["ffxferSha256"] = hashlib.sha256(data).hexdigest()
        result["ffdb"]["headerHex"] = data[:16].hex()

    # 4. DCIM / DCF / EXIF
    dcim_dir = resolved_dir / "DCIM"
    if dcim_dir.is_dir() and not dcim_dir.is_symlink():
        result["dcim"]["exists"] = True
        for sub in sorted(dcim_dir.iterdir(), key=lambda p: p.name):
            if not sub.is_dir() or sub.is_symlink():
                continue
            dcf_match = bool(DCF_DIR_RE.match(sub.name))
            folder_files = sorted(
                (p for p in sub.iterdir() if p.is_file() and not p.is_symlink()),
                key=lambda p: p.name,
            )
            result["dcim"]["folders"].append(
                {
                    "name": sub.name,
                    "dcfCompliantName": dcf_match,
                    "fileCount": len(folder_files),
                }
            )
            for media in folder_files:
                rel = f"DCIM/{sub.name}/{media.name}"
                ext = media.suffix.upper()
                item_summary: dict[str, Any] = {
                    "relativePath": rel,
                    "sizeBytes": media.stat().st_size,
                    "dcfCompliantName": bool(DCF_FILE_RE.match(media.name)),
                    "kind": ext.lstrip(".") or "UNKNOWN",
                }
                if ext == ".JPG":
                    result["dcim"]["photoCount"] += 1
                    exif = parse_jpeg_exif(media.read_bytes())
                    item_summary["exif"] = exif.to_dict()
                elif ext == ".RAF":
                    result["dcim"]["rafCount"] += 1
                    head = media.read_bytes()[:32]
                    item_summary["validRafMagic"] = head.startswith(RAF_MAGIC)
                else:
                    result["dcim"]["otherMediaCount"] += 1
                result["dcim"]["photos"].append(item_summary)

    return result


def validate_sdcard(
    card_dir: Path,
    strict: bool = False,
    require_sidecar: bool = False,
    require_photo: bool = False,
) -> ValidationReport:
    """全面校验 FF-DSC01 目录模式 SD 卡。"""
    resolved_dir = card_dir.resolve()
    sidecar = sidecar_path_for(resolved_dir)
    lock_file = lock_path_for(resolved_dir)
    report = ValidationReport(directory=str(resolved_dir), sidecar_path=str(sidecar))

    # 1. 目标目录存在性与符号链接检查
    if resolved_dir.is_symlink():
        report.add_error(f"目标 SD 目录不能是符号链接: {resolved_dir}")
        return report
    if not resolved_dir.exists():
        report.add_error(f"目标 SD 目录不存在: {resolved_dir}")
        return report
    if not resolved_dir.is_dir():
        report.add_error(f"目标路径不是目录: {resolved_dir}")
        return report

    # 2. 同级 .lock 文件检查
    if lock_file.is_symlink():
        report.add_error(f"同级锁文件不能是符号链接: {lock_file.name}")
    elif lock_file.exists():
        if not lock_file.is_file() or lock_file.stat().st_size != 0:
            report.add_error(f"同级锁文件 {lock_file.name} 必须为空常规文件")

    # 3. 宿主目录卫生扫描（隐藏文件、符号链接、非法字符、大小写冲突、嵌套深度、文件大小）
    scanned = scan_host_directory(resolved_dir, report=report)
    host_by_rel = {item.relative_path: item for item in scanned}

    # 4. FFDB/FFXFER.DAT 校验
    ffdb_dir = resolved_dir / "FFDB"
    ffxfer_file = ffdb_dir / "FFXFER.DAT"
    ffxfer_ok = False
    if not ffdb_dir.exists() or not ffdb_dir.is_dir() or ffdb_dir.is_symlink():
        report.add_error("缺少富士数据库目录 FFDB/")
    elif not ffxfer_file.exists() or not ffxfer_file.is_file() or ffxfer_file.is_symlink():
        report.add_error("缺少富士传输数据库文件 FFDB/FFXFER.DAT")
    else:
        size = ffxfer_file.stat().st_size
        if size == 0:
            report.add_error("FFDB/FFXFER.DAT 为空文件 (0 字节)")
        else:
            ffxfer_ok = True

    # 5. DCIM / DCF 2.0 / EXIF 校验
    dcim_dir = resolved_dir / "DCIM"
    dcf_folder_count = 0
    jpg_count = 0
    raf_count = 0
    valid_exif_jpg_count = 0

    if not dcim_dir.exists() or not dcim_dir.is_dir() or dcim_dir.is_symlink():
        if require_photo or strict:
            report.add_error("缺少 DCF 照片根目录 DCIM/")
        else:
            report.add_warning("未找到 DCIM/ 目录（尚未拍摄照片的空白卡允许无 DCIM）")
    else:
        for child in sorted(dcim_dir.iterdir(), key=lambda p: p.name):
            if child.is_symlink():
                continue  # 已在 scan_host_directory 报错
            if not child.is_dir():
                report.add_warning(f"DCIM/ 根目录下存在非目录文件: DCIM/{child.name}")
                continue
            m_dir = DCF_DIR_RE.match(child.name)
            if not m_dir:
                report.add_error(
                    f"DCIM 子目录命名不符合 DCF 规范 (^[1-9][0-9]{{2}}[A-Z0-9_]{{5}}$，如 100_FUJI): DCIM/{child.name}"
                )
            else:
                dcf_folder_count += 1
                if not child.name.endswith("_FUJI"):
                    report.add_warning(f"DCIM 子目录未使用富士默认后缀 _FUJI: DCIM/{child.name}")

            for media in sorted(child.iterdir(), key=lambda p: p.name):
                if media.is_symlink():
                    continue
                rel = f"DCIM/{child.name}/{media.name}"
                if media.is_dir():
                    report.add_error(f"DCF 目录内不应嵌套子目录: {rel}")
                    continue

                if not DCF_FILE_RE.match(media.name):
                    if DCF_FILE_CI_RE.match(media.name):
                        msg = f"DCF 文件名包含小写字母（标准 DCF 要求全大写）: {rel}"
                        if strict:
                            report.add_error(msg)
                        else:
                            report.add_warning(msg)
                    else:
                        report.add_error(
                            f"媒体文件名不符合 DCF 8.3 命名规范（如 DSCF0001.JPG）: {rel}"
                        )

                ext = media.suffix.upper()
                if ext == ".JPG":
                    jpg_count += 1
                    exif = parse_jpeg_exif(media.read_bytes())
                    for err in exif.errors:
                        report.add_error(f"{rel}: {err}")
                    for warn in exif.warnings:
                        if strict:
                            report.add_error(f"{rel}: {warn}")
                        else:
                            report.add_warning(f"{rel}: {warn}")
                    if exif.valid_jpeg and exif.has_exif_app1 and not exif.errors:
                        valid_exif_jpg_count += 1
                elif ext == ".RAF":
                    raf_count += 1
                    head = media.read_bytes()[:32]
                    if not head.startswith(RAF_MAGIC):
                        report.add_error(f"{rel}: 缺少 Fujifilm RAF 魔数头 'FUJIFILMCCD-RAW '")

    if require_photo and (jpg_count + raf_count) == 0:
        report.add_error("指定了 --require-photo，但 DCIM/ 中未找到任何 JPG/RAF 照片")

    # 6. 同级 <dir>.fuji-sd.json sidecar 校验
    sidecar_ok = False
    oem_id = ""
    if sidecar.is_symlink():
        report.add_error(f"sidecar 文件不能是符号链接: {sidecar.name}")
    elif not sidecar.exists():
        msg = f"缺少同级 sidecar 文件 {sidecar.name}（首次挂载时模拟器会自动生成）"
        if require_sidecar or strict:
            report.add_error(msg)
        else:
            report.add_warning(msg)
    else:
        sidecar_ok, oem_id = _validate_sidecar_file(sidecar, host_by_rel, report, strict=strict)

    report.summary = {
        "hostDirectories": sum(1 for x in scanned if x.is_directory),
        "hostFiles": sum(1 for x in scanned if not x.is_directory),
        "ffxferOk": ffxfer_ok,
        "dcfFolders": dcf_folder_count,
        "jpgCount": jpg_count,
        "validExifJpgCount": valid_exif_jpg_count,
        "rafCount": raf_count,
        "sidecarPresent": sidecar.exists() and not sidecar.is_symlink(),
        "sidecarValid": sidecar_ok,
        "oemId": oem_id,
    }
    return report


def _validate_sidecar_file(
    sidecar_path: Path,
    host_by_rel: dict[str, HostScanItem],
    report: ValidationReport,
    strict: bool,
) -> tuple[bool, str]:
    err_before = len(report.errors)
    oem_id = ""
    try:
        doc = load_sidecar_json(sidecar_path)
    except Exception as exc:
        report.add_error(f"sidecar JSON 解析失败: {exc}")
        return False, ""

    # 基本字段
    schema = doc.get("schema")
    if not is_exact_int(schema) or schema != SIDECAR_SCHEMA:
        report.add_error(f"不支持的 sidecar schema: {schema!r} (应为 {SIDECAR_SCHEMA})")
    sector_size = doc.get("sectorSize")
    if not is_exact_int(sector_size) or sector_size != SECTOR_BYTES:
        report.add_error(f"sidecar sectorSize 非法: {sector_size!r} (应为 {SECTOR_BYTES})")
    cap = doc.get("capacityBytes")
    if not is_exact_int(cap) or cap <= 0 or cap % SECTOR_BYTES != 0:
        report.add_error(f"sidecar capacityBytes 非法: {cap!r} (须为 512 字节正整数倍)")
        return False, ""
    if cap < MIN_CAPACITY_BYTES:
        report.add_warning(f"sidecar capacityBytes={cap} 小于标准 34 MiB FAT32 建议下限")

    formatted = doc.get("formatted")
    if formatted is not True:
        report.add_error(f"sidecar formatted 必须为 true，当前为 {formatted!r}")

    # volume 几何校验
    vol_raw = doc.get("volume")
    if not isinstance(vol_raw, dict):
        report.add_error("sidecar 缺少 volume 对象")
        return False, ""
    try:
        vol = VolumeGeometry.from_sidecar_dict(vol_raw)
    except ValueError as exc:
        report.add_error(str(exc))
        return False, ""

    total_sectors = cap // SECTOR_BYTES
    if vol.sectors_per_cluster not in VALID_SPC:
        report.add_error(f"volume.sectorsPerCluster 非法: {vol.sectors_per_cluster}")
    if vol.num_fats not in (1, 2):
        report.add_error(f"volume.numFats 非法: {vol.num_fats} (应为 1 或 2)")
    if vol.reserved_sectors < 1:
        report.add_error(f"volume.reservedSectors 必须 >= 1: {vol.reserved_sectors}")
    if vol.sectors_per_fat < 1:
        report.add_error(f"volume.sectorsPerFat 必须 >= 1: {vol.sectors_per_fat}")
    if vol.root_cluster < 2:
        report.add_error(f"volume.rootCluster 必须 >= 2: {vol.root_cluster}")
    if vol.partition_start_lba < 0 or vol.partition_sector_count <= 0:
        report.add_error("volume 分区起始 LBA 或扇区数非法")
    if vol.partition_start_lba + vol.partition_sector_count > total_sectors:
        report.add_error(
            f"volume 分区越界: {vol.partition_start_lba} + {vol.partition_sector_count} > {total_sectors}"
        )
    if vol.sectors_per_cluster in VALID_SPC and vol.cluster_count < MIN_FAT32_CLUSTERS:
        report.add_error(f"FAT32 数据簇数不足 {MIN_FAT32_CLUSTERS}: {vol.cluster_count}")

    # rawSectors 校验
    raw_obj = doc.get("rawSectors")
    decoded_sectors: dict[int, bytes] = {}
    if not isinstance(raw_obj, dict):
        report.add_error("sidecar 缺少 rawSectors 对象")
    else:
        for lba_key, b64_val in raw_obj.items():
            if not isinstance(lba_key, str) or not lba_key.isdigit():
                report.add_error(f"rawSectors 键不是非负整数 LBA: {lba_key!r}")
                continue
            lba = int(lba_key)
            if lba >= total_sectors:
                report.add_error(f"rawSectors LBA 越界: {lba} >= {total_sectors}")
                continue
            if not isinstance(b64_val, str):
                report.add_error(f"rawSectors[{lba}] 负载不是字符串")
                continue
            try:
                sec = base64.b64decode(b64_val, validate=True)
            except Exception as exc:
                report.add_error(f"rawSectors[{lba}] Base64 解码失败: {exc}")
                continue
            if len(sec) != SECTOR_BYTES:
                report.add_error(f"rawSectors[{lba}] 解码长度 {len(sec)} != {SECTOR_BYTES}")
                continue
            decoded_sectors[lba] = sec

    # MBR 校验
    if vol.partition_start_lba > 0:
        mbr = decoded_sectors.get(0)
        if mbr is None:
            report.add_error("rawSectors 缺少 LBA 0 MBR 扇区")
        else:
            if mbr[0x1FE:0x200] != b"\x55\xAA":
                report.add_error("MBR 缺少 0x55 0xAA 引导签名")
            pe = 0x1BE
            part_type = mbr[pe + 4]
            mbr_start, mbr_count = struct.unpack_from("<II", mbr, pe + 8)
            if part_type not in (0x0B, 0x0C):
                report.add_error(f"MBR 分区类型 0x{part_type:02X} 不是 FAT32 (0x0B/0x0C)")
            if mbr_start != vol.partition_start_lba or mbr_count != vol.partition_sector_count:
                report.add_error(
                    f"MBR 分区表 ({mbr_start}, {mbr_count}) 与 volume ({vol.partition_start_lba}, {vol.partition_sector_count}) 不一致"
                )

    # VBR / Backup VBR 校验
    vbr = decoded_sectors.get(vol.partition_start_lba)
    if vbr is None:
        report.add_error(f"rawSectors 缺少 VBR 扇区 (LBA {vol.partition_start_lba})")
    else:
        if vbr[0x1FE:0x200] != b"\x55\xAA":
            report.add_error("VBR 缺少 0x55 0xAA 引导签名")
        oem_id = vbr[3:11].decode("ascii", errors="replace").rstrip(" \x00")
        fs_type = vbr[0x52:0x5A].decode("ascii", errors="replace")
        if fs_type != "FAT32   ":
            report.add_error(f"VBR 文件系统标识非法: {fs_type!r} (应为 'FAT32   ')")
        if oem_id != FF_DSC01_OEM_ID:
            msg = f"VBR OEM ID 为 {oem_id!r}（非富士实机标准 '{FF_DSC01_OEM_ID}'）"
            if strict:
                report.add_error(msg)
            else:
                report.add_warning(msg)

        bps = struct.unpack_from("<H", vbr, 0x0B)[0]
        v_spc = vbr[0x0D]
        v_res = struct.unpack_from("<H", vbr, 0x0E)[0]
        v_fats = vbr[0x10]
        v_hidden, v_tot, v_spf = struct.unpack_from("<III", vbr, 0x1C)
        v_root, v_fsi, v_bk = struct.unpack_from("<IHH", vbr, 0x2C)
        checks = [
            ("bytesPerSector", bps, SECTOR_BYTES),
            ("sectorsPerCluster", v_spc, vol.sectors_per_cluster),
            ("reservedSectors", v_res, vol.reserved_sectors),
            ("numFats", v_fats, vol.num_fats),
            ("hiddenSectors", v_hidden, vol.partition_start_lba),
            ("totalSectors32", v_tot, vol.partition_sector_count),
            ("sectorsPerFat", v_spf, vol.sectors_per_fat),
            ("rootCluster", v_root, vol.root_cluster),
            ("fsInfoSector", v_fsi, vol.fs_info_sector),
            ("backupBootSector", v_bk, vol.backup_boot_sector),
        ]
        for field_name, actual, expected in checks:
            if actual != expected:
                report.add_error(f"VBR BPB.{field_name}={actual} 与 volume 配置 ({expected}) 不一致")

        if 0 < vol.backup_boot_sector < vol.reserved_sectors:
            bk_vbr = decoded_sectors.get(vol.partition_start_lba + vol.backup_boot_sector)
            if bk_vbr is None:
                report.add_warning(f"rawSectors 缺少备份 VBR (LBA {vol.partition_start_lba + vol.backup_boot_sector})")
            elif bk_vbr != vbr:
                report.add_error("备份 VBR 与主 VBR 内容不一致")

    # FSInfo 校验
    if 0 < vol.fs_info_sector < vol.reserved_sectors:
        fsi = decoded_sectors.get(vol.partition_start_lba + vol.fs_info_sector)
        if fsi is None:
            report.add_error(f"rawSectors 缺少 FSInfo 扇区 (LBA {vol.partition_start_lba + vol.fs_info_sector})")
        else:
            lead = struct.unpack_from("<I", fsi, 0x000)[0]
            sig2 = struct.unpack_from("<I", fsi, 0x1E4)[0]
            trail = struct.unpack_from("<I", fsi, 0x1FC)[0]
            if lead != FSINFO_LEAD_SIG or sig2 != FSINFO_STRUCT_SIG or trail != FSINFO_TRAIL_SIG:
                report.add_error("FSInfo 签名校验失败 (应为 0x41615252 / 0x61417272 / 0xAA550000)")

    # entries 簇分配与宿主目录同步检查
    entries_raw = doc.get("entries")
    if not isinstance(entries_raw, list):
        report.add_error("sidecar 缺少 entries 数组")
        return False, oem_id

    claimed_clusters: dict[int, str] = {vol.root_cluster: "<ROOT>"}
    max_cluster = vol.root_cluster + vol.cluster_count - 1
    seen_rels: set[str] = set()
    cluster_bytes = vol.cluster_bytes if vol.sectors_per_cluster in VALID_SPC else SECTOR_BYTES

    for idx, row in enumerate(entries_raw):
        if not isinstance(row, dict):
            report.add_error(f"entries[{idx}] 不是对象")
            continue
        rel = row.get("relativePath")
        is_dir = row.get("isDirectory")
        short_name = row.get("shortName")
        start_cluster = row.get("startCluster")
        clusters = row.get("clusters")
        size_bytes = row.get("sizeBytes")

        if not isinstance(rel, str) or not rel or rel.startswith("/") or ".." in rel.split("/"):
            report.add_error(f"entries[{idx}].relativePath 非法: {rel!r}")
            continue
        if rel in seen_rels:
            report.add_error(f"entries 存在重复 relativePath: {rel}")
            continue
        seen_rels.add(rel)

        if not isinstance(is_dir, bool):
            report.add_error(f"entries[{rel}].isDirectory 必须是布尔值")
            continue
        if not isinstance(short_name, str) or len(short_name) != 11:
            report.add_error(f"entries[{rel}].shortName 必须是 11 字符 ASCII: {short_name!r}")
        if not is_exact_int(start_cluster) or not is_exact_int(size_bytes) or size_bytes < 0:
            report.add_error(f"entries[{rel}] startCluster/sizeBytes 类型或范围非法")
            continue
        if not isinstance(clusters, list) or not all(is_exact_int(c) for c in clusters):
            report.add_error(f"entries[{rel}].clusters 必须是整数数组")
            continue

        if clusters:
            if start_cluster != clusters[0]:
                report.add_error(f"entries[{rel}] startCluster({start_cluster}) != clusters[0]({clusters[0]})")
        elif start_cluster != 0:
            report.add_error(f"entries[{rel}] clusters 为空但 startCluster={start_cluster} != 0")

        if is_dir:
            if len(clusters) < 1:
                report.add_error(f"目录条目 {rel} 至少应分配 1 个簇")
            if size_bytes != 0:
                report.add_error(f"目录条目 {rel} sizeBytes 应为 0，当前为 {size_bytes}")
        else:
            expected_clusters = 0 if size_bytes == 0 else (size_bytes + cluster_bytes - 1) // cluster_bytes
            if len(clusters) < expected_clusters:
                report.add_error(
                    f"文件条目 {rel} 簇数量 ({len(clusters)}) 不足以容纳 {size_bytes} B (至少 {expected_clusters} 簇)"
                )

        for c in clusters:
            if c < 2 or c > max_cluster:
                report.add_error(f"entries[{rel}] 包含越界簇号 {c} (合法范围 2..{max_cluster})")
            elif c in claimed_clusters:
                report.add_error(f"簇号 {c} 发生冲突：{claimed_clusters[c]} 与 {rel} 重复占用")
            else:
                claimed_clusters[c] = rel

        # 对比宿主文件系统
        host_item = host_by_rel.get(rel)
        if host_item is None:
            msg = f"sidecar 记录了宿主目录已不存在的条目: {rel}"
            if strict:
                report.add_error(msg)
            else:
                report.add_warning(msg)
        elif host_item.is_directory != is_dir:
            report.add_error(f"sidecar 与宿主目录条目类型冲突 (目录/文件): {rel}")
        elif not is_dir and host_item.size_bytes != size_bytes:
            msg = f"文件 {rel} 宿主大小 ({host_item.size_bytes} B) 与 sidecar ({size_bytes} B) 存在漂移"
            if strict:
                report.add_error(msg)
            else:
                report.add_warning(msg)

    for rel in sorted(host_by_rel.keys()):
        if rel not in seen_rels:
            msg = f"宿主目录存在 sidecar 尚未跟踪的新条目: {rel}（模拟器挂载时将自动增量分配）"
            if strict:
                report.add_error(msg)
            else:
                report.add_warning(msg)

    return len(report.errors) == err_before, oem_id


# =============================================================================
# 终端可读中文渲染
# =============================================================================


def print_init_summary(res: dict[str, Any]) -> None:
    vol = res["volume"]
    print("✓ 已成功初始化 FF-DSC01 目录模式 SD 卡")
    print(f"  SD 目录       : {res['directory']}")
    print(f"  Sidecar 文件  : {res['sidecarPath']}")
    print(f"  容量 / OEM    : {format_bytes(res['capacityBytes'])} | OEM={res['oemId']} | 卷标={vol['volumeLabel'].strip()!r}")
    print(
        f"  FAT32 几何    : LBA={vol['partitionStartLba']}..{vol['partitionStartLba'] + vol['partitionSectorCount'] - 1}, "
        f"SPC={vol['sectorsPerCluster']} ({vol['sectorsPerCluster'] * SECTOR_BYTES // 1024} KiB/簇), "
        f"Reserved={vol['reservedSectors']}, FATs={vol['numFats']}x{vol['sectorsPerFat']}"
    )
    print(f"  富士数据库    : {res['ffxferPath']} ({res['ffxferBytes']} B, SHA256={res['ffxferSha256'][:16]}...)")
    if res["samplePhoto"]:
        print(f"  示例 DCF 照片 : {res['samplePhoto']}")
    print(f"  已跟踪条目数  : {res['trackedEntries']}")


def print_inspect_summary(info: dict[str, Any]) -> None:
    print(f"=== FUJIFILM 目录模式 SD 卡检查报告 ===")
    print(f"SD 目录       : {info['directory']}")
    print(f"Sidecar 路径  : {info['sidecarPath']} ({'存在' if info['sidecarExists'] else '缺失'})")
    print(f"锁文件 (.lock): {'存在' if info['lockExists'] else '无'}")
    hs = info["hostSummary"]
    print(
        f"宿主统计      : {hs['directoryCount']} 个目录, {hs['fileCount']} 个文件, "
        f"总计 {format_bytes(hs['totalFileBytes'])}"
    )
    if hs["hygieneIssues"]:
        print(f"宿主卫生异常  : {len(hs['hygieneIssues'])} 项")
        for issue in hs["hygieneIssues"]:
            print(f"  [!] {issue}")

    print("\n[1] Sidecar (.fuji-sd.json)")
    sc = info["sidecar"]
    if sc is None:
        print("  (未找到 sidecar 文件)")
    elif "parseError" in sc:
        print(f"  [错误] 解析失败: {sc['parseError']}")
    else:
        vol = sc["volume"]
        boot = sc["boot"]
        print(f"  Schema / 状态 : v{sc['schema']} | formatted={sc['formatted']} | 容量={format_bytes(sc['capacityBytes'])}")
        print(
            f"  OEM / 卷标    : OEM={boot['oemId'] or '(空)'} | FS={boot['fsType'] or '(空)'} | "
            f"Label={vol['volumeLabel'].strip()!r} | VolID=0x{to_unsigned_u32(vol['volumeId']):08X}"
        )
        print(
            f"  FAT32 布局    : PartLBA={vol['partitionStartLba']}, Sectors={vol['partitionSectorCount']}, "
            f"SPC={vol['sectorsPerCluster']} ({sc['clusterBytes'] // 1024} KiB/簇), "
            f"Reserved={vol['reservedSectors']}, FAT={vol['numFats']}x{vol['sectorsPerFat']}, DataLBA={sc['dataStartLba']}"
        )
        print(
            f"  簇使用情况    : 已用 {sc['usedClusters']:,} / 总计 {sc['clusterCount']:,} 簇 "
            f"(空闲 {sc['freeClusters']:,} 簇, 跟踪 {sc['trackedEntries']} 条目)"
        )
        print(f"  原始引导扇区  : LBAs={sc['rawSectorLbas']} | MBR类型={boot['mbrPartitionType'] or '无'}")

    print("\n[2] 富士数据库 (FFDB/FFXFER.DAT)")
    ffdb = info["ffdb"]
    if ffdb["ffxferExists"]:
        print(
            f"  FFDB/FFXFER.DAT: 存在 ({format_bytes(ffdb['ffxferSize'])}, "
            f"SHA256={ffdb['ffxferSha256'][:16]}..., 头16B={ffdb['headerHex']})"
        )
    else:
        print(f"  FFDB 目录: {'存在' if ffdb['exists'] else '缺失'} | FFXFER.DAT: 缺失")

    print("\n[3] DCF 照片与 EXIF (DCIM/)")
    dcim = info["dcim"]
    if not dcim["exists"]:
        print("  (未找到 DCIM/ 目录)")
    else:
        print(
            f"  DCF 子目录数  : {len(dcim['folders'])} | "
            f"JPG={dcim['photoCount']}, RAF={dcim['rafCount']}, 其它={dcim['otherMediaCount']}"
        )
        for folder in dcim["folders"]:
            mark = "合规" if folder["dcfCompliantName"] else "非标准"
            print(f"  - DCIM/{folder['name']}/ ({mark}, {folder['fileCount']} 文件)")
        for photo in dcim["photos"]:
            name_flag = "DCF" if photo["dcfCompliantName"] else "非DCF名"
            base_line = f"    * {photo['relativePath']} [{name_flag}, {format_bytes(photo['sizeBytes'])}]"
            if "exif" in photo:
                ex = photo["exif"]
                if ex["validJpeg"]:
                    cam = f"{ex['make']} {ex['model']}".strip() or "未知机型"
                    dcf_tag = ex["interopIndex"] or "无Interop"
                    print(
                        f"{base_line} -> {ex['width']}x{ex['height']} {ex['sofMarker']} | "
                        f"EXIF {ex['exifVersion'] or '无'} | {cam} | {dcf_tag}"
                    )
                else:
                    print(f"{base_line} -> [JPEG损坏: {'; '.join(ex['errors'])}]")
            elif "validRafMagic" in photo:
                raf_ok = "有效RAF头" if photo["validRafMagic"] else "RAF头异常"
                print(f"{base_line} -> {raf_ok}")
            else:
                print(base_line)


def print_validate_report(report: ValidationReport) -> None:
    status = "通过 (PASS)" if report.ok else "失败 (FAIL)"
    print(f"=== FUJIFILM 目录模式 SD 卡校验结果: {status} ===")
    print(f"SD 目录       : {report.directory}")
    print(f"Sidecar 文件  : {report.sidecar_path}")
    s = report.summary
    if s:
        print(
            f"摘要          : 目录={s.get('hostDirectories', 0)}, 文件={s.get('hostFiles', 0)}, "
            f"FFXFER={'OK' if s.get('ffxferOk') else '异常'}, "
            f"DCF目录={s.get('dcfFolders', 0)}, JPG={s.get('jpgCount', 0)} (EXIF有效={s.get('validExifJpgCount', 0)}), "
            f"RAF={s.get('rafCount', 0)}, Sidecar={'OK' if s.get('sidecarValid') else '未通过/缺失'}"
            + (f" (OEM={s['oemId']})" if s.get("oemId") else "")
        )
    print(f"错误数: {len(report.errors)} | 警告数: {len(report.warnings)}")
    for idx, err in enumerate(report.errors, 1):
        print(f"  [错误 {idx}] {err}")
    for idx, warn in enumerate(report.warnings, 1):
        print(f"  [警告 {idx}] {warn}")


# =============================================================================
# 内置单元测试套件 (--selftest)
# =============================================================================


class FujiSdcardToolSelfTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="fuji-sd-selftest-")
        self.root = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_01_init_inspect_validate_default_4gb_with_sample_photo(self) -> None:
        card = self.root / "slot1"
        init_res = init_sdcard(card, with_sample_photo=True)
        self.assertEqual(init_res["capacityBytes"], DEFAULT_CAPACITY_BYTES)
        self.assertEqual(init_res["oemId"], FF_DSC01_OEM_ID)
        self.assertEqual(init_res["volume"]["partitionStartLba"], 8192)
        self.assertEqual(init_res["volume"]["reservedSectors"], 6146)
        self.assertEqual(init_res["volume"]["sectorsPerFat"], 1023)
        self.assertEqual(init_res["volume"]["sectorsPerCluster"], 64)
        self.assertTrue((card / "FFDB" / "FFXFER.DAT").is_file())
        self.assertTrue((card / "DCIM" / "100_FUJI" / "DSCF0001.JPG").is_file())
        self.assertTrue(sidecar_path_for(card).is_file())

        # 验证 sidecar 为纯整数 JSON（兼容 SdSidecarJson）
        doc = load_sidecar_json(sidecar_path_for(card))
        self.assertEqual(doc["schema"], 1)
        self.assertEqual(doc["sectorSize"], 512)
        self.assertTrue(doc["formatted"])

        info = inspect_sdcard(card)
        self.assertTrue(info["ffdb"]["ffxferExists"])
        self.assertEqual(info["dcim"]["photoCount"], 1)
        photo = info["dcim"]["photos"][0]
        self.assertTrue(photo["exif"]["validJpeg"])
        self.assertEqual(photo["exif"]["make"], "FUJIFILM")
        self.assertEqual(photo["exif"]["model"], "GFX100S")
        self.assertEqual(photo["exif"]["interopIndex"], "R98")
        self.assertEqual(photo["exif"]["width"], 16)
        self.assertEqual(photo["exif"]["height"], 16)

        report = validate_sdcard(card, strict=True, require_sidecar=True, require_photo=True)
        self.assertTrue(report.ok, f"期望通过严格校验，实际错误: {report.errors}")
        self.assertEqual(len(report.warnings), 0, f"期望零警告，实际警告: {report.warnings}")

    def test_02_custom_capacity_40mb_geometry(self) -> None:
        card = self.root / "slot40m"
        cap = 40 * 1024 * 1024
        init_sdcard(card, capacity_bytes=cap, volume_label="TESTCARD", volume_id=0x12345678)
        report = validate_sdcard(card, strict=True, require_sidecar=True)
        self.assertTrue(report.ok, f"40 MiB 严格校验失败: {report.errors}")
        info = inspect_sdcard(card)
        self.assertGreaterEqual(info["sidecar"]["clusterCount"], MIN_FAT32_CLUSTERS)
        self.assertEqual(info["sidecar"]["boot"]["oemId"], FF_DSC01_OEM_ID)

    def test_03_reject_hidden_dotfile_and_symlink(self) -> None:
        card = self.root / "bad_hygiene"
        init_sdcard(card)
        (card / ".DS_Store").write_bytes(b"junk")
        report = validate_sdcard(card)
        self.assertFalse(report.ok)
        self.assertTrue(any("隐藏" in e or "非法 FAT32" in e for e in report.errors))

        (card / ".DS_Store").unlink()
        target = self.root / "outside.txt"
        target.write_text("x", encoding="utf-8")
        try:
            os.symlink(target, card / "LINK.TXT")
        except (OSError, NotImplementedError):
            return  # 某些无权限 Windows 环境可能禁用符号链接
        report2 = validate_sdcard(card)
        self.assertFalse(report2.ok)
        self.assertTrue(any("符号链接" in e for e in report2.errors))

    def test_04_reject_corrupt_jpeg_missing_ffxfer_and_bad_dcf_name(self) -> None:
        card = self.root / "bad_media"
        init_sdcard(card)
        (card / "FFDB" / "FFXFER.DAT").unlink()
        (card / "DCIM" / "100_FUJI" / "badname.jpg").write_bytes(b"\xFF\xD8\x00\x00")
        (card / "DCIM" / "100_FUJI" / "DSCF0002.RAF").write_bytes(b"NOT_A_RAF")
        report = validate_sdcard(card)
        self.assertFalse(report.ok)
        joined = "\n".join(report.errors)
        self.assertIn("FFDB/FFXFER.DAT", joined)
        self.assertIn("DCF 8.3", joined)
        self.assertIn("SOF", joined)
        self.assertIn("RAF 魔数头", joined)

    def test_05_detect_sidecar_corruption_and_cluster_overlap(self) -> None:
        card = self.root / "bad_sidecar"
        init_sdcard(card, with_sample_photo=True)
        sc_path = sidecar_path_for(card)
        doc = load_sidecar_json(sc_path)
        # 人为制造簇重叠 + schema 错误
        doc["schema"] = 99
        if len(doc["entries"]) >= 2:
            c0 = doc["entries"][0]["clusters"][0]
            doc["entries"][1]["startCluster"] = c0
            doc["entries"][1]["clusters"] = [c0]
        write_sidecar_atomic(sc_path, doc)

        report = validate_sdcard(card, require_sidecar=True)
        self.assertFalse(report.ok)
        joined = "\n".join(report.errors)
        self.assertIn("schema", joined)
        self.assertIn("冲突", joined)

    def test_06_reject_float_in_sidecar_json(self) -> None:
        card = self.root / "float_sidecar"
        init_sdcard(card)
        sc_path = sidecar_path_for(card)
        raw = sc_path.read_text(encoding="utf-8").replace('"schema": 1', '"schema": 1.0', 1)
        sc_path.write_text(raw, encoding="utf-8")
        report = validate_sdcard(card, require_sidecar=True)
        self.assertFalse(report.ok)
        self.assertTrue(any("浮点数" in e for e in report.errors))

    def test_07_cli_json_roundtrip_and_force_guard(self) -> None:
        card = self.root / "cli_card"
        buf_init = io.StringIO()
        with contextlib.redirect_stdout(buf_init):
            rc_init = main(["init", str(card), "--with-sample-photo", "--json"])
        self.assertEqual(rc_init, 0)
        init_payload = json.loads(buf_init.getvalue())
        self.assertEqual(init_payload["oemId"], FF_DSC01_OEM_ID)

        # 未指定 --force 时拒绝覆盖非空目录
        buf_err = io.StringIO()
        with contextlib.redirect_stderr(buf_err):
            rc_refuse = main(["init", str(card)])
        self.assertNotEqual(rc_refuse, 0)

        buf_inspect = io.StringIO()
        with contextlib.redirect_stdout(buf_inspect):
            rc_inspect = main(["inspect", str(card), "--json"])
        self.assertEqual(rc_inspect, 0)
        inspect_payload = json.loads(buf_inspect.getvalue())
        self.assertEqual(inspect_payload["dcim"]["photoCount"], 1)

        buf_val = io.StringIO()
        with contextlib.redirect_stdout(buf_val):
            rc_val = main(["validate", str(card), "--strict", "--require-sidecar", "--require-photo", "--json"])
        self.assertEqual(rc_val, 0)
        val_payload = json.loads(buf_val.getvalue())
        self.assertTrue(val_payload["ok"])

        # 新增未跟踪照片：普通模式警告但通过，--strict 模式报错
        (card / "DCIM" / "100_FUJI" / "DSCF0002.JPG").write_bytes(build_sample_dcf_jpeg())
        rep_norm = validate_sdcard(card, strict=False, require_sidecar=True)
        self.assertTrue(rep_norm.ok)
        self.assertTrue(any("尚未跟踪" in w for w in rep_norm.warnings))
        rep_strict = validate_sdcard(card, strict=True, require_sidecar=True)
        self.assertFalse(rep_strict.ok)


def run_selftest(verbosity: int = 2) -> int:
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(FujiSdcardToolSelfTest)
    runner = unittest.TextTestRunner(stream=sys.stderr, verbosity=verbosity)
    result = runner.run(suite)
    return 0 if result.wasSuccessful() else 1


# =============================================================================
# CLI 解析与入口（全中文帮助）
# =============================================================================


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="fuji-sdcard-tool.py",
        description=(
            "FUJIFILM GFX100S 目录模式 SD 卡管理工具（零外部依赖，兼容 DirectoryFat32BlockStore / SdSidecarJson）。\n"
            "支持初始化标准 FF-DSC01 目录卡、检查 FFDB/DCF/EXIF 与 .fuji-sd.json sidecar，以及严格合规校验。"
        ),
        epilog=(
            "使用示例:\n"
            "  初始化 4 GiB 标准 FF-DSC01 SD 卡并附带示例照片:\n"
            "    python3 tools/fuji-sdcard-tool.py init ./sdcard_slot1 --with-sample-photo\n"
            "  检查目录卡内容与 sidecar 摘要:\n"
            "    python3 tools/fuji-sdcard-tool.py inspect ./sdcard_slot1\n"
            "  严格校验目录卡（含 EXIF/DCF 与 sidecar 一致性）:\n"
            "    python3 tools/fuji-sdcard-tool.py validate ./sdcard_slot1 --strict\n"
            "  运行内置单元测试:\n"
            "    python3 tools/fuji-sdcard-tool.py --selftest"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--selftest",
        action="store_true",
        help="运行内置单元测试套件（使用隔离临时目录，验证 init/inspect/validate 与异常处理）",
    )

    sub = parser.add_subparsers(dest="command", title="可用子命令", metavar="<子命令>")

    # init
    p_init = sub.add_parser(
        "init",
        help="初始化标准 FF-DSC01 目录模式 SD 卡及同级 .fuji-sd.json sidecar",
        description=(
            "创建标准 FF-DSC01 目录模式 SD 卡：生成 FFDB/FFXFER.DAT、DCIM/100_FUJI/、"
            "可选示例 EXIF/DCF 照片 DSCF0001.JPG，以及同级 <目录名>.fuji-sd.json sidecar 文件。"
        ),
    )
    p_init.add_argument("directory", type=Path, help="目标 SD 卡宿主目录路径（同级将生成 <目录名>.fuji-sd.json）")
    cap_group = p_init.add_mutually_exclusive_group()
    cap_group.add_argument(
        "--capacity-gb",
        type=int,
        default=None,
        help="虚拟 SD 卡容量，单位 GiB（默认: 4，即 4 GiB 标准 FF-DSC01 几何）",
    )
    cap_group.add_argument(
        "--capacity-mb",
        type=int,
        default=None,
        help="虚拟 SD 卡容量，单位 MiB（最小值: 34，用于自定义测试卡容量）",
    )
    p_init.add_argument(
        "--label",
        default=DEFAULT_VOLUME_LABEL.strip(),
        help="FAT32 卷标（最多 11 个 ASCII 字符，默认: GFX100S）",
    )
    p_init.add_argument(
        "--volume-id",
        default=f"0x{DEFAULT_VOLUME_ID:08X}",
        help="FAT32 32 位卷序列号，支持十六进制 0x 前缀（默认: 0x46554A49 / 'FUJI'）",
    )
    p_init.add_argument(
        "--with-sample-photo",
        action="store_true",
        help="额外在 DCIM/100_FUJI/DSCF0001.JPG 写入一张合规的 16x16 EXIF 2.32 / DCF 2.0 样本 JPEG",
    )
    p_init.add_argument(
        "--force",
        action="store_true",
        help="若目标目录非空或已存在 sidecar，清空并强制重新初始化",
    )
    p_init.add_argument("--json", action="store_true", help="以 JSON 格式输出初始化结果")

    # inspect
    p_inspect = sub.add_parser(
        "inspect",
        help="检查目录模式 SD 卡的 sidecar 几何、FFDB 数据库与 DCIM/EXIF 照片详情",
        description="只读检查目录模式 SD 卡及其同级 .fuji-sd.json，展示 FAT32 几何参数、簇分配、FFDB 状态与照片 EXIF。",
    )
    p_inspect.add_argument("directory", type=Path, help="要检查的 SD 卡宿主目录路径")
    p_inspect.add_argument("--json", action="store_true", help="以 JSON 格式输出检查报告")

    # validate
    p_val = sub.add_parser(
        "validate",
        help="校验目录模式 SD 卡的宿主卫生、FFDB、DCF/EXIF 与 sidecar 合规性",
        description=(
            "严格检查目录模式 SD 卡：无隐藏点文件/符号链接/大小写冲突、FFDB/FFXFER.DAT 有效、"
            "DCIM/100_FUJI 与 DSCFxxxx.JPG 符合 DCF 2.0 与 EXIF 规范，且同级 .fuji-sd.json 完整一致。"
            "校验失败时返回非零退出码。"
        ),
    )
    p_val.add_argument("directory", type=Path, help="要校验的 SD 卡宿主目录路径")
    p_val.add_argument(
        "--strict",
        action="store_true",
        help="严格模式：将所有警告（如非 FF-DSC01 OEM、缺少 DCF Interop/EXIF 字段、sidecar 漂移）提升为错误",
    )
    p_val.add_argument(
        "--require-sidecar",
        action="store_true",
        help="强制要求同级 <目录名>.fuji-sd.json sidecar 必须存在且合法",
    )
    p_val.add_argument(
        "--require-photo",
        action="store_true",
        help="强制要求 DCIM/ 下至少存在一张有效 JPG 或 RAF 照片",
    )
    p_val.add_argument("--json", action="store_true", help="以 JSON 格式输出校验报告")

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.selftest:
        return run_selftest()

    if not args.command:
        parser.print_help()
        return 2

    try:
        if args.command == "init":
            if args.capacity_mb is not None:
                capacity_bytes = args.capacity_mb * 1024 * 1024
            elif args.capacity_gb is not None:
                capacity_bytes = args.capacity_gb * 1024 * 1024 * 1024
            else:
                capacity_bytes = DEFAULT_CAPACITY_BYTES
            res = init_sdcard(
                card_dir=args.directory,
                capacity_bytes=capacity_bytes,
                volume_label=args.label,
                volume_id=parse_volume_id(args.volume_id),
                with_sample_photo=args.with_sample_photo,
                force=args.force,
            )
            if args.json:
                print(json.dumps(res, ensure_ascii=False, indent=2))
            else:
                print_init_summary(res)
            return 0

        if args.command == "inspect":
            info = inspect_sdcard(args.directory)
            if args.json:
                print(json.dumps(info, ensure_ascii=False, indent=2))
            else:
                print_inspect_summary(info)
            return 0

        if args.command == "validate":
            report = validate_sdcard(
                card_dir=args.directory,
                strict=args.strict,
                require_sidecar=args.require_sidecar,
                require_photo=args.require_photo,
            )
            if args.json:
                print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))
            else:
                print_validate_report(report)
            return 0 if report.ok else 1

    except (ValueError, FileExistsError, FileNotFoundError, OSError) as exc:
        print(f"错误: {exc}", file=sys.stderr)
        return 1

    parser.print_help()
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
