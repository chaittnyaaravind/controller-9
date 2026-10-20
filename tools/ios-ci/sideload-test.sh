#!/bin/sh
# Install test for the public sideload IPA: re-sign THIS exact file the way Sideloadly/AltStore do
# (Apple Development certificate + team provisioning profile), install it on a connected iPhone, launch it.
# Usage: tools/ios-ci/sideload-test.sh <unsigned.ipa> [device-udid]
set -eu
IPA=$1; DEV=${2:-}
export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
unzip -q "$IPA" -d "$W"
APP=$(ls -d "$W"/Payload/*.app); BID=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist")
PROF=$(grep -l "$BID" "$HOME"/Library/Developer/Xcode/UserData/Provisioning\ Profiles/*.mobileprovision "$HOME"/Library/MobileDevice/Provisioning\ Profiles/*.mobileprovision 2>/dev/null | head -1)
[ -n "$PROF" ] || { echo "no provisioning profile for $BID (build once with Xcode signing)"; exit 1; }
security cms -D -i "$PROF" > "$W/prof.plist"
/usr/libexec/PlistBuddy -x -c 'Print :Entitlements' "$W/prof.plist" > "$W/ent.plist"
IDENT=$(security find-identity -v -p codesigning | grep 'Apple Development' | head -1 | sed -E 's/.*"(.*)".*/\1/')
cp "$PROF" "$APP/embedded.mobileprovision"
codesign -f -s "$IDENT" --entitlements "$W/ent.plist" --timestamp=none "$APP" 2>&1 | tail -1
codesign -v --strict "$APP" && echo "re-signed OK for $(/usr/libexec/PlistBuddy -c 'Print :TeamName' "$W/prof.plist")"
if [ -z "$DEV" ]; then echo "no device given: signing verified only"; exit 0; fi
xcrun devicectl device install app --device "$DEV" "$APP" | grep -E 'App installed|bundleID'
xcrun devicectl device process launch --device "$DEV" --terminate-existing "$BID" | tail -1
