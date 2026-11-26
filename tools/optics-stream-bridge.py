#!/usr/bin/env python3
"""
M-Optics P1 Lightweight Stream-Ingest Bridge (Linux & Windows, Python 3 stdlib only).

Feeds live RGB24 frames (and optional Float32 depth maps) into the FUJIFILM GFX100S
virtual CMOS `stream-ingest` backend (`sourceKind: stream-ingest`, `sourceFile: tcp://127.0.0.1:9555`).

Examples:
  # 1. Animated 30fps test card + synthetic depth plane (verifies live CMOS + CoC bokeh):
  python3 tools/optics-stream-bridge.py --mode testcard --port 9555 --fps 30 --depth

  # 2. Pipe OBS / RTSP / RTMP / local video / webcam via FFmpeg into TCP FSCM:
  python3 tools/optics-stream-bridge.py --mode ffmpeg --input rtsp://127.0.0.1:8554/obs --port 9555

  # 3. Push a single JPEG/PNG frame over HTTP POST /frame:
  python3 tools/optics-stream-bridge.py --mode http-image --image path/to/scene.jpg --port 9555

OBS / Minecraft / VRChat / Spout Integration:
  - OBS Studio: output RTSP/RTMP (e.g. MediaMTX) or Virtual Camera -> `--mode ffmpeg --input ...`
  - VRChat / Minecraft (Windows Spout): capture Spout texture via FFmpeg/OBS or Python SpoutGL ->
    pack 16-byte little-endian `FSCM` header + RGB24/RGBA32/BGRA32 pixels (+ optional Float32 depth)
    and write directly to `127.0.0.1:9555` over a persistent TCP socket.
"""

from __future__ import annotations

import argparse
import math
import socket
import struct
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

FSCM_MAGIC = b"FSCM"
FLAG_HAS_DEPTH_F32 = 1


def encode_fscm_header(width: int, height: int, fmt_code: int, payload_bytes: int, has_depth: bool) -> bytes:
    flags = FLAG_HAS_DEPTH_F32 if has_depth else 0
    return struct.pack("<4sHHBBHI", FSCM_MAGIC, width, height, fmt_code, flags, 0, payload_bytes)


def build_testcard_frame(width: int, height: int, frame_idx: int, include_depth: bool) -> tuple[bytes, bytes]:
    rgb = bytearray(width * height * 3)
    depth = bytearray(width * height * 4) if include_depth else bytearray()
    shift = (frame_idx * 4) % width
    pos = 0
    dpos = 0
    for y in range(height):
        for x in range(width):
            sx = (x + shift) % width
            if (sx // 32 + y // 32) % 2 == 0:
                r, g, b = 220, 220, 220
            else:
                r, g, b = 30, 30, 30
            if abs(x - width // 2) < width // 8 and abs(y - height // 2) < height // 6:
                r, g, b = 230, 50, 40
            rgb[pos] = r
            rgb[pos + 1] = g
            rgb[pos + 2] = b
            pos += 3
            if include_depth:
                # Left half in-focus (2.0 m), right half far background (15.0 m)
                d_val = 2.0 if x < width // 2 else 15.0
                struct.pack_into("<f", depth, dpos, d_val)
                dpos += 4
    return bytes(rgb), bytes(depth)


def run_testcard(host: str, port: int, width: int, height: int, fps: float, count: int, depth: bool) -> None:
    interval = 1.0 / max(fps, 1.0)
    with socket.create_connection((host, port), timeout=5.0) as sock:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        idx = 0
        while count <= 0 or idx < count:
            t0 = time.monotonic()
            rgb, depth_bytes = build_testcard_frame(width, height, idx, depth)
            hdr = encode_fscm_header(width, height, 0, len(rgb) + len(depth_bytes), depth)
            sock.sendall(hdr)
            sock.sendall(rgb)
            if depth_bytes:
                sock.sendall(depth_bytes)
            idx += 1
            sleep_s = interval - (time.monotonic() - t0)
            if sleep_s > 0:
                time.sleep(sleep_s)


def run_ffmpeg_bridge(host: str, port: int, width: int, height: int, src: str, ffmpeg_bin: str) -> None:
    vf = f"scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=black"
    cmd = [ffmpeg_bin, "-nostdin", "-loglevel", "error"]
    if src.lower().startswith(("rtsp://", "rtsps://")):
        cmd += ["-rtsp_transport", "tcp"]
    cmd += ["-i", src, "-an", "-sn", "-dn", "-vf", vf, "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"]
    frame_bytes = width * height * 3
    hdr = encode_fscm_header(width, height, 0, frame_bytes, False)

    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    try:
        assert proc.stdout is not None
        with socket.create_connection((host, port), timeout=5.0) as sock:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            while True:
                buf = proc.stdout.read(frame_bytes)
                if len(buf) < frame_bytes:
                    break
                sock.sendall(hdr)
                sock.sendall(buf)
    finally:
        proc.terminate()
        proc.wait(timeout=3)


def run_http_image(host: str, port: int, image_path: Path) -> None:
    data = image_path.read_bytes()
    ctype = "image/png" if image_path.suffix.lower() == ".png" else "image/jpeg"
    req = urllib.request.Request(
        f"http://{host}:{port}/frame",
        data=data,
        headers={"Content-Type": ctype},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=5.0) as resp:
        print(resp.read().decode("utf-8"))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Bridge external video/3D streams into GFX100S CMOS stream-ingest.")
    parser.add_argument("--mode", choices=["testcard", "ffmpeg", "http-image"], default="testcard")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9555)
    parser.add_argument("--width", type=int, default=512)
    parser.add_argument("--height", type=int, default=288)
    parser.add_argument("--fps", type=float, default=30.0)
    parser.add_argument("--count", type=int, default=0, help="Frames to send in testcard mode (0 = infinite)")
    parser.add_argument("--depth", action="store_true", help="Attach synthetic Float32 depth plane in testcard mode")
    parser.add_argument("--input", default="", help="Input file or RTSP/RTMP URL for --mode ffmpeg")
    parser.add_argument("--ffmpeg", default="ffmpeg", help="Path to ffmpeg executable")
    parser.add_argument("--image", default="", help="JPEG/PNG path for --mode http-image")
    args = parser.parse_args(argv)

    if args.mode == "testcard":
        run_testcard(args.host, args.port, args.width, args.height, args.fps, args.count, args.depth)
    elif args.mode == "ffmpeg":
        if not args.input:
            parser.error("--input is required for --mode ffmpeg")
        run_ffmpeg_bridge(args.host, args.port, args.width, args.height, args.input, args.ffmpeg)
    elif args.mode == "http-image":
        if not args.image:
            parser.error("--image is required for --mode http-image")
        run_http_image(args.host, args.port, Path(args.image))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
