#!/bin/sh
# Write SHA256SUMS for every distributable file under a directory (APK, AAB, DMG, MSI/EXE, deb, AppImage, IPA, ZIP).
# Usage: tools/release/checksums.sh <dir>
set -eu
dir="${1:?usage: checksums.sh <dir>}"
cd "$dir"
find . -type f \( -name '*.apk' -o -name '*.aab' -o -name '*.dmg' -o -name '*.msi' -o -name '*.exe' -o -name '*.deb' -o -name '*.rpm' -o -name '*.AppImage' -o -name '*.ipa' -o -name '*.zip' \) \
  -print | sort | while read -r f; do shasum -a 256 "$f" 2>/dev/null || sha256sum "$f"; done > SHA256SUMS
cat SHA256SUMS
