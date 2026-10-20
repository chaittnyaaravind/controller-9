#!/bin/sh
# Live check of macOS keyboard & mouse mode: real host (keyboard backend) + simulated phone sweep,
# recorded by tap.swift, which SWALLOWS every tagged event — nothing is typed or clicked into apps.
# Needs the terminal to have Accessibility permission. Must run outside any sandbox (window server access).
# Writes tests/results/mac-keyboard-mode.txt. Uses ports 47921/47922 (a paired host is untouched).
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; T="$(mktemp -d)"; trap 'kill $HP $TP 2>/dev/null || true; rm -rf "$T"' EXIT
HP=""; TP=""
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
PATH="/opt/homebrew/opt/rustup/bin:$PATH" cargo build -q --release -p ninectl --manifest-path "$ROOT/Cargo.toml"
swiftc -O "$ROOT/tools/mac-keyboard-check/tap.swift" -o "$T/tap"
B="$ROOT/target/release/controller9"
"$B" status > "$T/status.json"; grep -q '"macos-keyboard"' "$T/status.json" && grep -q '"available": true' "$T/status.json" || { cat "$T/status.json"; echo "keyboard backend not available" >&2; exit 1; }
"$T/tap" 22 > "$T/tap.jsonl" 2>&1 & TP=$!
i=0; until grep -q ready "$T/tap.jsonl"; do i=$((i+1)); [ $i -gt 50 ] && { cat "$T/tap.jsonl"; echo "tap not ready — aborting before any input" >&2; exit 1; }; sleep 0.1; done
"$B" host --ephemeral --pair --auto-approve --no-advertise --no-ble --tcp-port 47921 --udp-port 47922 --stream-port 0 > "$T/host.log" 2>&1 & HP=$!
sleep 2
"$B" sim --host 127.0.0.1:47921 --code --pattern sweep --duration 10.2 --json > "$T/sim.json" 2> "$T/sim.err"
sleep 1; kill $HP; wait $HP 2>/dev/null || true; HP=""
sleep 1; kill $TP 2>/dev/null || true; wait $TP 2>/dev/null || true; TP=""
python3 -I "$ROOT/tools/mac-keyboard-check/verify.py" "$T/tap.jsonl" | tee "$ROOT/tests/results/mac-keyboard-mode.txt"
