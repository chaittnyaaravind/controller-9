#!/bin/sh
# Development-only probe: does macOS let an administrator (root) create the virtual gamepad without
# the com.apple.developer.hid.virtual.device entitlement, and if so, do games (GameController.framework)
# see it? This is NOT a shipping path — it only answers the question before Apple grants the entitlement.
#
# Run in Terminal (it asks for your password once):   sh tools/mac-root-probe.sh
# Uses throwaway identity (--ephemeral) and separate ports, so an already running/paired host is untouched.
# Output: tests/results/mac-root-probe.txt
set -u
cd "$(dirname "$0")/.." || exit 1
OUT=tests/results/mac-root-probe.txt
BIN=target/release/controller9
PROBE=$(mktemp -d)/gc-probe
[ -x "$BIN" ] || { echo "build first: cargo build --release -p ninectl"; exit 1; }
swiftc -O -o "$PROBE" tools/mac-gc-probe/main.swift || exit 1
{
  echo "# mac root probe $(date -u +%Y-%m-%dT%H:%M:%SZ) · macOS $(sw_vers -productVersion) ($(sw_vers -buildVersion))"
  echo "## status as user";  "$BIN" status
  echo "## status as root";  sudo "$BIN" status
} > "$OUT" 2>&1
sudo "$BIN" host --ephemeral --pair --auto-approve --no-advertise --no-ble --tcp-port 47911 --udp-port 47910 --stream-port 0 > "$OUT.host" 2>&1 &
HOST=$!
sleep 1.5
"$PROBE" 16 > "$OUT.gc" 2>&1 &
GC=$!
sleep 0.5
"$BIN" sim --host 127.0.0.1:47911 --code --pattern sweep --duration 10.2 --json > "$OUT.sim" 2>&1 &
SIM=$!
sleep 2
{ echo "## HID devices while the session is open"; hidutil list 2>&1 | grep -i -E "controller-9|0x45e" | head -5; } >> "$OUT"
wait "$SIM"; wait "$GC"
sudo kill "$HOST" 2>/dev/null
{
  echo "## host";            grep -v -E '^ +[▀▄█ ]+$' "$OUT.host" | grep -v '^[[:space:]]*$' | head -20
  echo "## simulator";       cat "$OUT.sim"
  echo "## GameController.framework"; cat "$OUT.gc"
} >> "$OUT"
rm -f "$OUT.host" "$OUT.sim" "$OUT.gc"
echo "wrote $OUT"; grep -A3 'status as root' "$OUT" | head -5; grep -c '"button"' "$OUT" | sed 's/^/GameController button events: /'
