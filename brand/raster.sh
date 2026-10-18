#!/bin/sh
# Rasterize the Controller-9 app icon for the desktop app (Tauri): PNG sizes, .icns and .ico.
# Needs Google Chrome (headless render), sips and iconutil (macOS). Run after brand/build.py.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
OUT="$ROOT/hosts/app/src-tauri/icons"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 "$ROOT/brand/build.py" >/dev/null
printf '<!doctype html><style>html,body{margin:0;background:transparent}img{display:block;width:1024px;height:1024px}</style><img src="file://%s">' \
  "$ROOT/brand/svg/controller9-app-icon.svg" >"$TMP/icon.html"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --default-background-color=00000000 --allow-file-access-from-files \
  --window-size=1024,1024 --screenshot="$TMP/1024.png" "file://$TMP/icon.html" 2>/dev/null

px() { sips -z "$1" "$1" "$TMP/1024.png" --out "$2" >/dev/null; }
px 32 "$OUT/32x32.png"; px 128 "$OUT/128x128.png"; px 256 "$OUT/128x128@2x.png"; px 512 "$OUT/icon.png"

SET="$TMP/icon.iconset"; mkdir "$SET"
for s in 16 32 128 256 512; do px "$s" "$SET/icon_${s}x${s}.png"; px "$((s * 2))" "$SET/icon_${s}x${s}@2x.png"; done
iconutil -c icns "$SET" -o "$OUT/icon.icns"

# .ico with PNG entries (Windows Vista+ reads PNG-compressed icon images).
for s in 16 24 32 48 64 256; do px "$s" "$TMP/ico-$s.png"; done
python3 - "$TMP" "$OUT/icon.ico" <<'EOF'
import struct, sys
tmp, out = sys.argv[1], sys.argv[2]
sizes = [16, 24, 32, 48, 64, 256]
blobs = [open(f"{tmp}/ico-{s}.png", "rb").read() for s in sizes]
head = struct.pack("<HHH", 0, 1, len(sizes))
off = 6 + 16 * len(sizes)
dirs = b""
for s, b in zip(sizes, blobs):
    dirs += struct.pack("<BBBBHHII", s % 256, s % 256, 0, 0, 1, 32, len(b), off)
    off += len(b)
open(out, "wb").write(head + dirs + b"".join(blobs))
EOF
echo "icons written to $OUT"
