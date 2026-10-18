#!/usr/bin/env python3
"""Generate the Controller-9 app icon (original geometric mark) as PNG, ICO and ICNS.
No third-party dependencies. Usage: python3 tools/icons/make_icons.py <out_dir>"""
import math, struct, sys, zlib, os

BG = (11, 12, 13); RING = (238, 240, 241); ACCENT = (198, 244, 50)

def render(n):
    px = bytearray()
    r_out, r_in = 0.34, 0.27
    corner = 0.22
    for y in range(n):
        px.append(0)
        for x in range(n):
            u, v = (x + 0.5) / n, (y + 0.5) / n
            # rounded-square mask with 2px anti-aliasing
            dx, dy = max(abs(u - 0.5) - (0.5 - corner), 0), max(abs(v - 0.5) - (0.5 - corner), 0)
            d = math.hypot(dx, dy) - corner
            a = max(0.0, min(1.0, 0.5 - d * n))
            col = BG
            cx, cy = u - 0.5, v - 0.52
            rr = math.hypot(cx, cy)
            ring = max(0.0, min(1.0, 0.5 - (abs(rr - (r_out + r_in) / 2) - (r_out - r_in) / 2) * n))
            spoke = max(0.0, min(1.0, 0.5 - (abs(cy) - 0.028) * n)) * (1.0 if abs(cx) < r_in else 0.0)
            hub = max(0.0, min(1.0, 0.5 - (rr - 0.07) * n))
            dot = max(0.0, min(1.0, 0.5 - (math.hypot(cx, cy + 0.305) - 0.055) * n))
            mix = max(ring, spoke, hub)
            col = tuple(int(BG[i] * (1 - mix) + RING[i] * mix) for i in range(3))
            col = tuple(int(col[i] * (1 - dot) + ACCENT[i] * dot) for i in range(3))
            px += bytes(col) + bytes([int(a * 255)])
    return bytes(px)

def png(n):
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(render(n), 9)) + chunk(b"IEND", b"")

def ico(images):
    hdr = struct.pack("<HHH", 0, 1, len(images)); off = 6 + 16 * len(images); dirs = b""; data = b""
    for n, p in images:
        dirs += struct.pack("<BBBBHHII", n % 256, n % 256, 0, 0, 1, 32, len(p), off + len(data)); data += p
    return hdr + dirs + data

def icns(images):
    types = {128: b"ic07", 256: b"ic08", 512: b"ic09", 1024: b"ic10"}
    body = b"".join(types[n] + struct.pack(">I", len(p) + 8) + p for n, p in images if n in types)
    return b"icns" + struct.pack(">I", len(body) + 8) + body

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
sizes = {n: png(n) for n in (32, 128, 256, 512)}
for name, n in (("32x32.png", 32), ("128x128.png", 128), ("128x128@2x.png", 256), ("icon.png", 512)):
    open(os.path.join(out, name), "wb").write(sizes[n])
open(os.path.join(out, "icon.ico"), "wb").write(ico([(32, sizes[32]), (256, sizes[256])]))
open(os.path.join(out, "icon.icns"), "wb").write(icns([(128, sizes[128]), (256, sizes[256]), (512, sizes[512])]))
print("icons written to", out)
