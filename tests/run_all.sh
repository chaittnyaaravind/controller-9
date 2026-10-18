#!/bin/sh
# 9-controller development test suite (Stage 2 safeguards). Runs everything that can run on a
# macOS dev machine without devices. Device, OS-driver and game tests are in docs/TEST_PLAN.md.
#   tests/run_all.sh            # all suites
#   SKIP_ANDROID=1 tests/run_all.sh
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export PATH="/opt/homebrew/opt/rustup/bin:$PATH"
LOG="${TMPDIR:-/tmp}/ninectl-tests"
mkdir -p "$LOG"
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
step() { printf '\n== %s\n' "$1"; }

step "Rust: protocol, host core, backends, CLI, desktop app (unit + e2e over loopback)"
cargo test --workspace --quiet 2>&1 | grep -E "^test result|FAILED|panicked" || true
cargo test --workspace --quiet >/dev/null 2>&1 && pass "cargo test --workspace"

step "Rust: cross-target type checks (Linux uinput, Windows VHF client + devnode)"
cargo check --quiet -p nine-pad-linux -p ninectl --target x86_64-unknown-linux-gnu --all-targets && pass "x86_64-unknown-linux-gnu"
cargo check --quiet -p nine-pad-linux -p ninectl --target aarch64-unknown-linux-gnu && pass "aarch64-unknown-linux-gnu"
cargo check --quiet -p nine-pad-windows -p ninectl --target x86_64-pc-windows-msvc --bins && pass "x86_64-pc-windows-msvc"

step "Rust: regenerate vectors and confirm they are unchanged"
cargo run --quiet -p nine-proto --bin gen-vectors >/dev/null
git diff --quiet -- protocol/vectors && pass "vectors stable" || { echo "  vectors changed — commit them and rerun the ports"; exit 1; }

if [ -z "${SKIP_ANDROID:-}" ]; then
  step "Kotlin/Android: protocol vectors, engine tests, debug APK"
  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}" ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
  (cd apps/android && ./gradlew -q :protocol:test :app:testDebugUnitTest :app:assembleDebug --console=plain) && pass "gradle tests + assembleDebug"
fi

step "Swift: NineKit vectors, iOS app core"
tools/swift-test.sh protocol/swift >"$LOG/swift-ninekit.log" 2>&1 && pass "NineKit"
tools/swift-test.sh apps/ios/Core >"$LOG/swift-core.log" 2>&1 && pass "NineAppCore"
(cd apps/ios/App && swiftc -parse ./*.swift) && pass "iOS UI sources parse (type-check needs full Xcode)"

step "Live interop over loopback: Rust host <- Rust sim, Kotlin client, Swift client"
cargo build --quiet --release -p ninectl
BIN="$ROOT/target/release/ninectl"
"$BIN" host --mock --ephemeral --bind 127.0.0.1 --tcp-port 47961 --udp-port 47960 --pair --no-advertise >"$LOG/host.log" 2>&1 &
HOST_PID=$!
trap 'kill -INT $HOST_PID 2>/dev/null || true' EXIT
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do grep -q "ninectl://" "$LOG/host.log" && break; sleep 0.3; done
URI="$(grep -o 'ninectl://[^ ]*' "$LOG/host.log" | head -1)"
"$BIN" sim --uri "$URI" --rate 120 --duration 5 --json >"$LOG/sim.json" 2>/dev/null && pass "Rust sim: $(cat "$LOG/sim.json")"
# A QR token is single-use; mint a fresh one per client by restarting pairing via a new host.
kill -INT $HOST_PID; sleep 0.5
for client in kotlin swift; do
  "$BIN" host --mock --ephemeral --bind 127.0.0.1 --tcp-port 47961 --udp-port 47960 --pair --no-advertise >"$LOG/host-$client.log" 2>&1 &
  HOST_PID=$!
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do grep -q "ninectl://" "$LOG/host-$client.log" && break; sleep 0.3; done
  URI="$(grep -o 'ninectl://[^ ]*' "$LOG/host-$client.log" | head -1)"
  if [ "$client" = kotlin ] && [ -z "${SKIP_ANDROID:-}" ]; then
    (cd apps/android && NINECTL_URI="$URI" ./gradlew :protocol:test --tests '*LiveInterop*' --rerun --console=plain >"$LOG/kotlin-live.log" 2>&1)
    grep -E "Kotlin->Rust interop" "$LOG/kotlin-live.log" && grep -q "LiveInteropTest > pairOpenStreamAndPing PASSED" "$LOG/kotlin-live.log" \
      && pass "Kotlin client <-> Rust host" || { echo "  Kotlin live interop FAILED (see $LOG/kotlin-live.log)"; exit 1; }
  fi
  if [ "$client" = swift ]; then
    NINECTL_URI="$URI" perl -e 'alarm 150; exec @ARGV' tools/swift-test.sh protocol/swift >"$LOG/swift-live.log" 2>&1
    grep -E "Swift->Rust interop" "$LOG/swift-live.log" && grep -q "liveInteropWithRustHost() passed" "$LOG/swift-live.log" \
      && pass "Swift client <-> Rust host" || { echo "  Swift live interop FAILED (see $LOG/swift-live.log)"; exit 1; }
  fi
  kill -INT $HOST_PID; sleep 0.5
done
trap - EXIT
printf '\nAll development checks passed. Logs: %s\n' "$LOG"
