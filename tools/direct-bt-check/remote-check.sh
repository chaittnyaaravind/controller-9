#!/bin/sh
# Remote keys check (docs/DIRECT_BLUETOOTH.md): with the phone paired as a Bluetooth controller and
# "Remote keys" on, the debug app moves the Mac's pointer 400 px right and presses Volume Up.
# Measures both on the Mac. Needs a debug build on the phone (USB debugging). Usage: sh remote-check.sh
set -eu
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"
x() { osascript -l JavaScript -e 'ObjC.import("AppKit"); $.NSEvent.mouseLocation.x' | cut -d. -f1; }
v() { osascript -e 'output volume of (get volume settings)'; }
x0=$(x); v0=$(v)
adb shell am start -f 0x20000000 -n dev.ninecontroller.android/.MainActivity --ez c9_remote true >/dev/null
sleep 3
x1=$(x); v1=$(v)
echo "pointer x: $x0 -> $x1"; echo "volume: $v0 -> $v1"
[ "$x1" -gt "$x0" ] && echo "mouse: PASS" || echo "mouse: FAIL"
[ "$v1" -gt "$v0" ] || [ "$v0" -ge 100 ] && echo "media keys: PASS" || echo "media keys: FAIL"
