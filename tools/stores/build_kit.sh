#!/bin/sh
# Build the store-listing kit in build/stores/: icon, itch.io cover, screenshots, and the upload files.
# Needs Google Chrome (headless) and sips. Screenshots come from build/stores/src/android (adb screencap).
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
OUT="$ROOT/build/stores"; DL="$ROOT/site/public/downloads"; V=0.1.0
mkdir -p "$OUT/icon" "$OUT/itch" "$OUT/android-screens" "$OUT/files"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

cp "$ROOT/hosts/app/src-tauri/icons/icon.png" "$OUT/icon/icon-512.png"

# itch.io cover, 630x500: mark + wordmark on the site background.
cat >"$TMP/cover.html" <<HTML
<!doctype html><style>html,body{margin:0;width:630px;height:500px;background:#0B0C0D;overflow:hidden}
.c{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:34px;
background:radial-gradient(60% 55% at 50% 42%,rgb(198 244 50/.12),transparent 70%)}
.m{width:190px;height:190px}.w{width:220px}</style>
<div class="c"><img class="m" src="file://$ROOT/brand/svg/controller9-app-icon.svg"><img class="w" src="file://$ROOT/brand/svg/controller9-logotype.svg"></div>
HTML
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
  --window-size=630,500 --screenshot="$OUT/itch/cover-630x500.png" "file://$TMP/cover.html" 2>/dev/null

for f in "$ROOT"/build/stores/src/android/*.png; do cp "$f" "$OUT/android-screens/"; done

# Upload files: the same tested builds the website serves (never rebuilt here).
for f in Controller-9-$V.apk Controller-9-$V-linux-amd64.deb Controller-9-$V-linux-arm64.deb \
         Controller-9-$V-linux-x86_64.AppImage Controller-9-$V-mac-universal.dmg Controller-9-$V-windows-x64-setup.exe; do
  cp "$DL/$f" "$OUT/files/"
done
(cd "$OUT/files" && shasum -a 256 * > SHA256SUMS)
echo "kit: $OUT"
