#!/usr/bin/env python3
"""Build the original milk frog sculpt used by scene://milk-frog.

The shape follows the proportions in 文火_slowfire's turnarounds (BV1enXoBSEfU).
No source art or third-party mesh is needed. --glb writes an editable copy of
the same geometry. Only --audio needs the original decoded naiwa-main.raw.
"""

import argparse
import bisect
import gzip
import json
import math
import pathlib
import struct

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT_DIR = REPO_ROOT / "components/cmos/src/main/resources/scenes"

# Cross sections from the hips to the crown: height, half-width, front/back
# radius and centre depth in metres. The front faces the camera at negative Z.
PROFILE = [
    (-0.290, 0.003, 0.003, 0.016),
    (-0.275, 0.077, 0.069, 0.014),
    (-0.240, 0.141, 0.114, 0.011),
    (-0.185, 0.181, 0.146, 0.004),
    (-0.107, 0.193, 0.166, -0.006),
    (-0.035, 0.186, 0.162, -0.004),
    (0.032, 0.163, 0.139, 0.003),
    (0.083, 0.130, 0.109, 0.008),
    (0.122, 0.109, 0.093, 0.004),
    (0.160, 0.105, 0.091, -0.005),
    (0.201, 0.100, 0.093, -0.006),
    (0.241, 0.094, 0.087, -0.004),
    (0.273, 0.076, 0.072, -0.001),
    (0.293, 0.049, 0.048, 0.002),
    (0.300, 0.027, 0.026, 0.003),
    (0.303, 0.001, 0.001, 0.003),
]


def mix(a, b, t):
    return tuple(x + (y - x) * t for x, y in zip(a, b))


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def unit(v):
    length = math.sqrt(sum(n * n for n in v))
    return tuple(n / length for n in v) if length > 1e-12 else (0.0, 1.0, 0.0)


def spline(points, t):
    """Cubic Hermite interpolation through artist-specified cross sections."""
    ts = [p[0] for p in points]
    i = max(0, min(len(ts) - 2, bisect.bisect_right(ts, t) - 1))
    a, b = points[i], points[i + 1]
    before, after = points[max(0, i - 1)], points[min(len(ts) - 1, i + 2)]
    dt = b[0] - a[0]
    f = max(0.0, min(1.0, (t - a[0]) / dt))
    h = smooth(f)
    return tuple(
        (1 - h) * a[k]
        + h * b[k]
        + dt
        * f
        * (1 - f)
        * ((1 - f) * (b[k] - before[k]) / (b[0] - before[0]) - f * (after[k] - a[k]) / (after[0] - a[0]))
        for k in range(1, len(a))
    )


class Sculpt:
    def __init__(self):
        self.vertices = []
        self.faces = []

    def vertex(self, pos, color, part):
        self.vertices.append((pos, tuple(round(v) for v in color), part))
        return len(self.vertices) - 1

    def bridge(self, a, b):
        for i in range(len(a)):
            j = (i + 1) % len(a)
            if a[i] != b[i]:
                self.faces.append((a[i], b[i], a[j]))
            if a[j] != b[j]:
                self.faces.append((a[j], b[i], b[j]))

    def tube(self, path, color, part, steps=16, sides=16):
        # Tangent frame keeps each articulated digit and tapered wrist round.
        points = [(i / (len(path) - 1), *p) for i, p in enumerate(path)]
        last = None
        for row in range(steps + 1):
            t = row / steps
            x, y, z, radius, aspect = spline(points, t)
            p0 = spline(points, max(0.0, t - 0.001))
            p1 = spline(points, min(1.0, t + 0.001))
            tangent = unit(tuple(b - a for a, b in zip(p0[:3], p1[:3])))
            side = unit(cross((0.0, 0.0, 1.0), tangent))
            out = cross(tangent, side)
            ring = []
            for i in range(sides):
                a = math.tau * i / sides
                ring.append(
                    self.vertex(
                        tuple(
                            p + radius * (s * math.cos(a) + aspect * n * math.sin(a))
                            for p, s, n in zip((x, y, z), side, out)
                        ),
                        color(t),
                        part,
                    )
                )
            if last:
                self.bridge(ring, last)
            else:
                self.cap(ring, (x, y, z), color(t), part, True)
            last = ring
        self.cap(last, (x, y, z), color(1.0), part, False)

    def cap(self, ring, center, color, part, reverse=False):
        c = self.vertex(center, color, part)
        for i, a in enumerate(ring):
            b = ring[(i + 1) % len(ring)]
            self.faces.append((c, b, a) if reverse else (c, a, b))

    def orb(self, center, radii, color, part, yaw=0.0, roll=0.0, sides=20, rows=9):
        # A closed lathed solid with shared poles.
        last = None
        for j in range(1, rows):
            lat = math.pi * (j / rows - 0.5)
            ring = []
            for i in range(sides):
                a = math.tau * i / sides
                x = radii[0] * math.cos(lat) * math.sin(a)
                y = radii[1] * math.sin(lat)
                z = -radii[2] * math.cos(lat) * math.cos(a)
                rx = x * math.cos(roll) - y * math.sin(roll)
                ry = x * math.sin(roll) + y * math.cos(roll)
                ring.append(
                    self.vertex(
                        (
                            center[0] + rx * math.cos(yaw) + z * math.sin(yaw),
                            center[1] + ry,
                            center[2] - rx * math.sin(yaw) + z * math.cos(yaw),
                        ),
                        color,
                        part,
                    )
                )
            if last:
                self.bridge(last, ring)
            else:
                self.cap(
                    ring,
                    (
                        center[0] + radii[1] * math.sin(roll) * math.cos(yaw),
                        center[1] - radii[1] * math.cos(roll),
                        center[2] - radii[1] * math.sin(roll) * math.sin(yaw),
                    ),
                    color,
                    part,
                )
            last = ring
        self.cap(
            last,
            (
                center[0] - radii[1] * math.sin(roll) * math.cos(yaw),
                center[1] + radii[1] * math.cos(roll),
                center[2] + radii[1] * math.sin(roll) * math.sin(yaw),
            ),
            color,
            part,
            True,
        )


def body(s):
    # Split one latitude at the lip line and join its two edges *inside*
    # the head. The smile is a recessed cavity visible from off-axis.
    lip_y = 0.15
    n = 72
    corner = 11
    heights = sorted(
        set(
            [a[0] for a in PROFILE]
            + [lip_y, -0.235, -0.234, 0.029, 0.031]
            + [PROFILE[0][0] + k * (PROFILE[-1][0] - PROFILE[0][0]) / 34 for k in range(1, 34)]
        )
    )
    upper = lower = last = None
    for h in heights:
        for is_upper in [False, True] if h == lip_y else [h > lip_y]:
            ring = []
            for i in range(n):
                angle = math.tau * (i if i <= n // 2 else i - n) / n
                # Trace the patch with the same two meridians over its full
                # height. A regular angular grid makes the cream edge scalloped.
                belly_y = (h + 0.102) / 0.133
                edge = 1.14 * math.sqrt(max(0.0, 1.0 - belly_y**2))
                if abs(belly_y) < 1.40:
                    ix = min(i, n - i)
                    edge = max(0.018, edge)
                    fringe = min(0.018, edge * 0.35)
                    if ix <= 11:
                        phi = (edge - fringe) * ix / 11
                    elif ix <= 13:
                        phi = edge + fringe * (ix - 12)
                    else:
                        phi = edge + fringe + (math.pi - edge - fringe) * (ix - 13) / (n / 2 - 13)
                    blend = smooth((1.40 - abs(belly_y)) / 0.40)
                    angle += (math.copysign(phi, angle) - angle) * blend
                # Rounded U-shaped smile with lifted corners and raised cheeks.
                inside = max(0.0, 1.0 - (abs(angle) / (math.tau * corner / n)) ** 2)
                opening = inside**0.70
                w = math.exp(-(((h - lip_y) / (0.052 if is_upper else 0.064)) ** 2))
                y = h + w * (
                    0.014 * (1 - inside) * math.exp(-((angle / 1.35) ** 4))
                    + (0.009 if is_upper else -0.039) * opening
                )
                if is_upper and h == lip_y and not opening:
                    ring.append(lower[i])
                    continue
                rx, rz, z0 = spline(PROFILE, y)
                x = rx * math.sin(angle)
                z = z0 - rz * math.cos(angle)
                bulge = math.exp(-(((abs(angle) - 0.82) / 0.34) ** 2) - ((y - 0.180) / 0.035) ** 2)
                z -= 0.011 * bulge
                color = mix((243, 179, 64), (248, 204, 146), smooth((y + 0.26) / 0.55))
                belly = (angle / 1.14) ** 2 + ((y + 0.102) / 0.133) ** 2
                color = mix(color, (245, 223, 183), smooth((1.04 - belly) / 0.08))
                color = mix(color, (248, 176, 130), 0.54 * bulge)
                jaw = h <= lip_y and opening > 0 and abs(h - lip_y) < 0.039
                ring.append(s.vertex((x, y, z), color, 5 if jaw else 0))
            if h == lip_y and is_upper:
                upper = ring
            else:
                if last:
                    s.bridge(last, ring)
                else:
                    s.cap(ring, (0, h, 0.016), (243, 179, 64), 0)
                if h == lip_y:
                    lower = ring
            last = ring
    s.cap(last, (0, PROFILE[-1][0], 0.003), (248, 204, 146), 0, True)
    section = list(range(n - corner, n)) + list(range(corner + 1))
    edge = [lower[k] for k in section] + [upper[k] for k in reversed(section[1:-1])]
    last = edge
    for step in range(1, 8):
        t = step / 8
        ring = []
        for vi in edge:
            (x, y, z), _, part = s.vertices[vi]
            curve = math.sin(t * math.pi * 0.5)
            ring.append(
                s.vertex(
                    (x * (1 - t), 0.145 + (y - 0.145) * (1 - t), z * (1 - curve) + (-0.035) * curve),
                    mix((122, 61, 45), (64, 30, 32), curve),
                    part,
                )
            )
        s.bridge(last, ring)
        last = ring
    s.cap(last, (0, 0.145, -0.035), (65, 29, 32), 0, True)
    # Tongue sits behind the lip, in the open volume, with a darker central crease.
    s.orb((0, 0.122, -0.077), (0.044, 0.009, 0.017), (217, 112, 113), 5, rows=10)
    s.tube(
        [(0, 0.124, -0.092, 0.0006, 1), (0, 0.129, -0.09, 0.0011, 1), (0, 0.130, -0.086, 0.0005, 1)],
        lambda t: (184, 84, 86),
        5,
        steps=6,
        sides=6,
    )


def limbs_and_eyes(s):
    for side in (-1, 1):

        def mirror(path, side=side):
            return [(side * x, y, z, r, a) for x, y, z, r, a in path]

        honey = lambda t: mix((250, 205, 116), (226, 160, 62), smooth(t))
        cocoa = lambda t: mix((128, 99, 67), (79, 65, 51), smooth(t))
        s.tube(
            mirror(
                [
                    (0.080, -0.211, 0.014, 0.055, 0.96),
                    (0.105, -0.266, 0.010, 0.049, 0.94),
                    (0.113, -0.323, 0.002, 0.035, 1),
                    (0.119, -0.383, -0.010, 0.024, 1),
                    (0.132, -0.424, -0.024, 0.017, 0.95),
                ]
            ),
            honey,
            0,
            steps=17,
            sides=16,
        )
        s.orb(
            (side * 0.14, -0.437, -0.046),
            (0.034, 0.017, 0.055),
            (173, 131, 73),
            2,
            yaw=-side * 0.42,
            rows=10,
        )
        # Shoulders down to elbows, then the forearms turn in front of the abdomen.
        s.tube(
            mirror(
                [
                    (0.109, 0.055, 0.010, 0.035, 1),
                    (0.150, 0.029, 0.008, 0.048, 1),
                    (0.189, -0.021, -0.002, 0.044, 1),
                    (0.205, -0.071, -0.035, 0.040, 1),
                    (0.203, -0.088, -0.068, 0.036, 1),
                    (0.181, -0.082, -0.095, 0.032, 1),
                    (0.153, -0.066, -0.125, 0.024, 1),
                    (0.128, -0.063, -0.143, 0.021, 0.90),
                ]
            ),
            lambda t, honey=honey: mix(honey(t), (151, 111, 67), smooth((t - 0.69) / 0.31)),
            2,
            steps=24,
            sides=16,
        )
        s.orb(
            (side * 0.122, -0.062, -0.144),
            (0.037, 0.024, 0.019),
            (139, 103, 65),
            2,
            yaw=-side * 0.48,
            roll=side * 0.05,
            rows=12,
        )
        for k in range(3):
            y = -0.045 - 0.014 * k
            s.tube(
                mirror(
                    [
                        (0.117, y, -0.149, 0.009, 0.91),
                        (0.099, y + 0.002, -0.158, 0.009, 0.92),
                        (0.081 + 0.004 * k, y - 0.001, -0.163, 0.0075, 0.92),
                        (0.067 + 0.005 * k, y - 0.005, -0.165, 0.004, 1),
                        (0.064 + 0.005 * k, y - 0.005, -0.164, 0.001, 1),
                    ]
                ),
                cocoa,
                2,
                steps=9,
                sides=10,
            )
        s.tube(
            mirror(
                [
                    (0.135, -0.052, -0.142, 0.010, 1),
                    (0.134, -0.041, -0.144, 0.009, 1),
                    (0.125, -0.032, -0.148, 0.008, 1),
                    (0.115, -0.031, -0.151, 0.0025, 1),
                ]
            ),
            cocoa,
            2,
            steps=9,
            sides=10,
        )
        eye(s, side)


def eye(s, side):
    # One continuous corneal dome: concentric pigment bands follow the
    # surface so the iris cannot detach when seen from a three-quarter view.
    yaw = -side * 0.28

    def point(x, y, z):
        return (
            side * 0.041 + x * math.cos(yaw) + z * math.sin(yaw),
            0.226 + y,
            -0.087 - x * math.sin(yaw) + z * math.cos(yaw),
        )

    rings = [
        (1.0, 0.003, (242, 202, 120), 0),
        (0.91, -0.0045, (242, 199, 116), 0),
        (0.84, -0.0070, (109, 135, 68), 3),
        (0.78, -0.0090, (119, 159, 75), 3),
        (0.65, -0.0115, (151, 185, 90), 3),
        (0.55, -0.0130, (146, 182, 91), 3),
        (0.49, -0.0138, (39, 58, 44), 4),
        (0.40, -0.0146, (26, 38, 32), 4),
        (0.16, -0.0150, (25, 34, 29), 4),
    ]
    last = None
    count = 36
    for radius, depth, color, part in rings:
        ring = []
        for i in range(count):
            a = math.tau * i / count
            x = 0.034 * radius * math.cos(a)
            # The lower lid lifts into a smile towards the cheek.
            y = 0.026 * radius * math.sin(a) + side * x * 0.095
            z = depth + 0.0047 * radius**2
            ring.append(s.vertex(point(x, y, z), color, part))
        if last:
            s.bridge(last, ring)
        last = ring
    s.cap(last, point(0, 0, -0.0152), (25, 34, 29), 4, True)
    s.orb(
        point(-0.004, 0.007, -0.0153),
        (0.0035, 0.004, 0.0016),
        (250, 240, 205),
        3,
        yaw=yaw,
        sides=14,
        rows=7,
    )


def normals(s):
    sums = [[0.0, 0.0, 0.0] for v in s.vertices]
    for a, b, c in s.faces:
        p, q, r = (s.vertices[i][0] for i in (a, b, c))
        normal = cross(tuple(y - x for x, y in zip(p, q)), tuple(y - x for x, y in zip(p, r)))
        for i in (a, b, c):
            for k in range(3):
                sums[i][k] += normal[k]
    return [unit(v) for v in sums]


def pack_mesh(s, ns):
    count = len(s.vertices)
    positions, colors, parts = zip(*s.vertices)
    lo = tuple(map(min, zip(*positions)))
    hi = tuple(map(max, zip(*positions)))
    buf = bytearray(struct.pack("<4sHHII6f", b"MF3D", 1, 0, count, len(s.faces), *lo, *hi))
    for k in range(3):
        buf.extend(
            struct.pack(
                f"<{count}h",
                *(round(32767 * (2 * (p[k] - lo[k]) / (hi[k] - lo[k]) - 1)) for p in positions),
            )
        )
    for k in range(3):
        buf.extend(struct.pack(f"<{count}b", *(round(127 * n[k]) for n in ns)))
    for k in range(3):
        buf.extend(bytes(v[k] for v in colors))
    buf.extend(bytes(parts))
    buf.extend(b"".join(struct.pack("<3H", *face) for face in s.faces))
    OUT_DIR.mkdir(exist_ok=True, parents=True)
    (OUT_DIR / "milk-frog-mesh.bin.gz").write_bytes(gzip.compress(buf, compresslevel=9, mtime=0))


def export_glb(path, s, ns):
    """glTF uses linear vertex colours. Part IDs remain available to DCC tools."""
    positions, colors, parts = zip(*s.vertices)
    buf = bytearray()
    views, accessors = [], []

    def accessor(data, kind, component, count, **extra):
        buf.extend(bytes((-len(buf)) % 4))
        views.append({"buffer": 0, "byteOffset": len(buf), "byteLength": len(data)})
        buf.extend(data)
        accessors.append(
            dict(bufferView=len(views) - 1, componentType=component, count=count, type=kind, **extra)
        )
        return len(accessors) - 1

    def vec3(vs):
        return b"".join(struct.pack("<3f", *v) for v in vs)

    n = len(parts)
    pos = accessor(
        vec3(positions),
        "VEC3",
        5126,
        n,
        min=list(map(min, zip(*positions))),
        max=list(map(max, zip(*positions))),
    )
    norm = accessor(vec3(ns), "VEC3", 5126, n)

    def linear(v):
        s = v / 255
        return s / 12.92 if s <= 0.04045 else ((s + 0.055) / 1.055) ** 2.4

    col = accessor(vec3(tuple(linear(k) for k in v) for v in colors), "VEC3", 5126, n)
    pid = accessor(b"".join(struct.pack("<f", p) for p in parts), "SCALAR", 5126, n)
    idx = accessor(b"".join(struct.pack("<3H", *f) for f in s.faces), "SCALAR", 5123, 3 * len(s.faces))
    doc = {
        "asset": {"version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0}],
        "meshes": [
            {
                "name": "Belly laugh",
                "primitives": [
                    {
                        "attributes": {"POSITION": pos, "NORMAL": norm, "COLOR_0": col, "_PART": pid},
                        "indices": idx,
                        "material": 0,
                    }
                ],
            }
        ],
        "materials": [
            {"doubleSided": True, "pbrMetallicRoughness": {"metallicFactor": 0, "roughnessFactor": 0.87}}
        ],
        "buffers": [{"byteLength": len(buf)}],
        "bufferViews": views,
        "accessors": accessors,
    }
    data = json.dumps(doc, separators=(",", ":")).encode()
    data += b" " * ((-len(data)) % 4)
    buf.extend(bytes((-len(buf)) % 4))
    path.write_bytes(
        struct.pack("<4sII", b"glTF", 2, 28 + len(data) + len(buf))
        + struct.pack("<I4s", len(data), b"JSON")
        + data
        + struct.pack("<I4s", len(buf), b"BIN\0")
        + buf
    )


def bake_mesh(glb):
    sculpt = Sculpt()
    body(sculpt)
    limbs_and_eyes(sculpt)
    ns = normals(sculpt)
    pack_mesh(sculpt, ns)
    if glb:
        export_glb(glb, sculpt, ns)
    print(f"Baked {len(sculpt.vertices)} vertices, {len(sculpt.faces)} triangles.")


def bake_audio(raw_path):
    # Use naiwa-main.raw (iconic 4.07s viral Milk Frog laugh loop from gezi-wen/dsh-naiwa-laugh)
    raw = raw_path.read_bytes()
    src_sr = struct.unpack_from("<I", raw, 0)[0]
    n_src = (len(raw) - 4) // 2
    samps = struct.unpack_from(f"<{n_src}h", raw, 4)

    # Trim leading/trailing near-silence cleanly (threshold 350, keeping 25 ms natural breath padding)
    nz = [i for i, s in enumerate(samps) if abs(s) > 350]
    pad = int(src_sr * 0.025)
    start = max(0, nz[0] - pad)
    end = min(n_src, nz[-1] + pad)
    trimmed = samps[start:end]

    # Resample cleanly to 24,000 Hz mono with 5-tap windowed box/lanczos low-pass prefilter
    dst_sr = 24000
    ratio = src_sr / dst_sr
    n_dst = round(len(trimmed) / ratio)
    resampled = []
    n_trim = len(trimmed)
    for i in range(n_dst):
        center = i * ratio
        c0 = math.floor(center)
        # Soft 4-tap Catmull/linear anti-aliased sample
        acc = 0.0
        wsum = 0.0
        for k in (-1, 0, 1, 2):
            idx = max(0, min(n_trim - 1, c0 + k))
            d = abs((c0 + k) - center)
            w = max(0.0, 1.0 - d / 1.85)
            acc += trimmed[idx] * w
            wsum += w
        resampled.append(acc / max(1e-6, wsum))

    # Remove DC offset
    dc = sum(resampled) / len(resampled)
    resampled = [v - dc for v in resampled]

    # Normalize to -3 dBFS (0.707 * 32767 ≈ 23170) so laugh is punchy without clipping
    peak = max(abs(v) for v in resampled)
    gain = 23170.0 / max(1.0, peak)
    normed = [v * gain for v in resampled]

    # Smooth 35 ms cosine fade-in and fade-out for zero-click seamless looping
    fade_n = min(int(dst_sr * 0.035), len(normed) // 10)
    for i in range(fade_n):
        w = 0.5 * (1.0 - math.cos(math.pi * i / fade_n))
        normed[i] *= w
        normed[len(normed) - 1 - i] *= w

    pcm16 = [max(-32767, min(32767, round(v))) for v in normed]

    # Compute 128-bin normalized RMS laugh envelope (0..255) to drive 60 fps belly-laugh animation!
    env_bins = 128
    env = []
    max_rms = 1e-6
    raw_rms = []
    for b in range(env_bins):
        i0 = (b * len(pcm16)) // env_bins
        i1 = max(i0 + 1, ((b + 1) * len(pcm16)) // env_bins)
        seg = pcm16[i0:i1]
        r = math.sqrt(sum(v * v for v in seg) / len(seg))
        raw_rms.append(r)
        max_rms = max(max_rms, r)
    for r in raw_rms:
        env.append(max(0, min(255, round((r / max_rms) ** 0.75 * 255.0))))

    # Delta-encode int16 PCM (lossless, ~2x smaller after gzip!)
    deltas = []
    prev = 0
    for s in pcm16:
        d = ((s - prev + 32768) & 0xFFFF) - 32768
        deltas.append(d)
        prev = s

    buf = bytearray()
    buf += struct.pack("<4sHHII", b"MFAD", 1, dst_sr, len(pcm16), env_bins)
    buf += struct.pack(f"<{env_bins}B", *env)
    buf += struct.pack(f"<{len(deltas)}h", *deltas)

    out_path = OUT_DIR / "milk-frog-laugh.pcm.gz"
    compressed = gzip.compress(bytes(buf), compresslevel=9, mtime=0)
    out_path.write_bytes(compressed)
    print(
        f"BAKED AUDIO: {len(pcm16)} frames ({len(pcm16) / dst_sr:.2f}s @ {dst_sr} Hz mono), envBins={env_bins}"
    )
    print(f"  -> {out_path} ({len(compressed) / 1024:.1f} KiB gzipped, {len(buf) / 1024:.1f} KiB raw)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--glb", type=pathlib.Path, help="export editable mesh")
    parser.add_argument("--audio", type=pathlib.Path, help="also rebake audio from decoded PCM")
    args = parser.parse_args()
    bake_mesh(args.glb)
    if args.audio:
        bake_audio(args.audio)
