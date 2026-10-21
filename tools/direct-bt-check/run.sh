#!/bin/sh
# Hardware gates G1–G5 of docs/DIRECT_BLUETOOTH.md: Android phone (USB debugging) as a Bluetooth
# controller, macOS as the computer, no Controller-9 app on the Mac.
#   1. installs the debug APK and starts the mode with the debug-only sweep (MainActivity c9_hid)
#   2. you pair the phone once in System Settings → Bluetooth (opened for you)
#   3. checks macOS created the HID gamepad (hidutil) and records what GameController.framework sees
# Writes tests/results/<date>-direct-bluetooth-mac.txt. Usage: sh tools/direct-bt-check/run.sh
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; T="$(mktemp -d)"; LP=""; trap 'kill $LP 2>/dev/null || true; rm -rf "$T"' EXIT
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"
OUT="$ROOT/tests/results/$(date +%Y-%m-%d)-direct-bluetooth-mac.txt"
PKG=dev.ninecontroller.android

[ "$(adb devices | grep -c 'device$')" = 1 ] || { adb devices; echo "connect exactly one phone with USB debugging" >&2; exit 1; }
APK="$(ls "$ROOT"/apps/android/app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || { echo "build the debug APK first (apps/android: ./gradlew :app:assembleDebug)" >&2; exit 1; }
swiftc -O -o "$T/gc-probe" "$ROOT/tools/mac-gc-probe/main.swift"

echo "== G1: install, start Bluetooth controller mode"
adb install -r "$APK" >/dev/null
for p in BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT; do adb shell pm grant $PKG android.permission.$p 2>/dev/null || true; done
adb logcat -c
adb logcat -v time -s C9Hid:V C9HidSweep:V > "$T/phone.log" 2>&1 & LP=$!
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity --ez c9_hid true --ez c9_hid_sweep true >/dev/null
i=0; until grep -q -E "advertising as HID|failed|refused" "$T/phone.log"; do i=$((i+1)); [ $i -gt 100 ] && break; sleep 0.1; done
grep -E "advertising|failed|refused" "$T/phone.log" | tail -2
grep -q "advertising as HID" "$T/phone.log" || { echo "G1 FAIL: phone did not advertise (Bluetooth permission? tap Allow on the phone, then rerun)" >&2; cat "$T/phone.log"; exit 1; }

echo "== G2: pair. In the Bluetooth window that opens, click Connect next to your phone and accept on both."
hidutil list 2>/dev/null | grep -i -q "0x45e *0xb13" && echo "(already paired)" || open "x-apple.systempreferences:com.apple.Bluetooth"
i=0; until grep -q "subscribed to input" "$T/phone.log"; do i=$((i+1)); [ $i -gt 1800 ] && { echo "G2 FAIL: no computer subscribed within 3 min" >&2; cat "$T/phone.log"; exit 1; }; sleep 0.1; done
echo "paired and subscribed"

echo "== G3/G4: HID device + GameController.framework (sweep runs ~30 s)"
"$T/gc-probe" 45 > "$T/gc.jsonl" 2>&1 &
GP=$!
sleep 3
hidutil list 2>/dev/null | grep -i "0x45e" > "$T/hid.txt" || true
wait $GP || true
python3 -I "$ROOT/tools/direct-bt-check/verify.py" "$T/hid.txt" "$T/gc.jsonl" "$T/phone.log" | tee "$OUT"
