#!/bin/sh
# Upload the tested builds to itch.io with butler. Usage: tools/stores/itch-push.sh <itch-user>/<project-slug>
# One-time: `butler login` (opens the browser; the API key stays in butler's own config, never in this repo).
# Channel names carry the platform word so itch tags each file (android / windows / osx / linux).
set -eu
TARGET="${1:?usage: itch-push.sh user/project}"
BUTLER="${BUTLER:-$HOME/tools-dl/butler/butler}"
F="$(cd "$(dirname "$0")/../.." && pwd)/build/stores/files"; V=0.1.0
[ -f "$F/SHA256SUMS" ] || { echo "run tools/stores/build_kit.sh first" >&2; exit 1; }
(cd "$F" && shasum -a 256 -c SHA256SUMS >/dev/null) || { echo "kit files changed since SHA256SUMS" >&2; exit 1; }
push() { "$BUTLER" push "$F/$1" "$TARGET:$2" --userversion "$V"; }
push Controller-9-$V.apk                      android
push Controller-9-$V-windows-x64-setup.exe    windows-x64
push Controller-9-$V-mac-universal.dmg        osx-universal
push Controller-9-$V-linux-amd64.deb          linux-amd64-deb
push Controller-9-$V-linux-arm64.deb          linux-arm64-deb
push Controller-9-$V-linux-x86_64.AppImage    linux-x86_64-appimage
"$BUTLER" status "$TARGET"
