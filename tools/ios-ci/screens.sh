#!/bin/sh
# Launch every screen of a Debug simulator build (`-screen <name>`, see ScreenshotRoute in HomeViews.swift)
# in the iOS Simulator and save a PNG of each, plus the app's console output.
# Usage: tools/ios-ci/screens.sh <path/to/Controller9.app> <out-dir>
set -eu
APP=$1; OUT=$2; mkdir -p "$OUT"
BID=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist")
# Newest iOS runtime, then a Pro iPhone on it (fallback: any iPhone).
RT=$(xcrun simctl list runtimes -j | python3 -c 'import json,sys; r=[x for x in json.load(sys.stdin)["runtimes"] if x["platform"]=="iOS" and x["isAvailable"]]; print(sorted(r,key=lambda x:[int(p) for p in x["version"].split(".")])[-1]["identifier"])')
DEV=$(xcrun simctl list devices -j | python3 -c "import json,sys; d=[x for x in json.load(sys.stdin)['devices']['$RT'] if x['isAvailable'] and 'iPhone' in x['name']]; p=[x for x in d if 'Pro' in x['name'] and 'Max' not in x['name']] or d; print(p[-1]['udid']); print(p[-1]['name'], file=sys.stderr)")
echo "simulator: $DEV on $RT"
xcrun simctl boot "$DEV" 2>/dev/null || true
xcrun simctl bootstatus "$DEV" -b >/dev/null
xcrun simctl ui "$DEV" appearance dark
xcrun simctl status_bar "$DEV" override --time 9:41 --batteryState charged --batteryLevel 100 --wifiBars 3 --cellularBars 4 || true
xcrun simctl install "$DEV" "$APP"
# Pre-grant what the simulator can; local network and Bluetooth prompts cannot be pre-granted.
xcrun simctl privacy "$DEV" grant camera "$BID" 2>/dev/null || true
for s in home add profiles editor motion connection diagnostics settings controller; do
  xcrun simctl terminate "$DEV" "$BID" 2>/dev/null || true
  xcrun simctl launch --terminate-running-process --console-pty "$DEV" "$BID" -screen "$s" > "$OUT/$s.log" 2>&1 &
  L=$!
  sleep 5
  xcrun simctl io "$DEV" screenshot "$OUT/$s.png" >/dev/null 2>&1
  kill "$L" 2>/dev/null || true
  echo "captured $s"
done
xcrun simctl spawn "$DEV" log show --last 3m --style compact --predicate "process == \"Controller9\" AND messageType >= 16" > "$OUT/errors.log" 2>&1 || true
