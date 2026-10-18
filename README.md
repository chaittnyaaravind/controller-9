# Controller-9

Use your phone as a low-latency game controller for your computer — touch controls, motion
steering, up to four players — over your local Wi-Fi, end-to-end encrypted, no account, no cloud.

| Part | Path | Tech |
|---|---|---|
| Android controller | `apps/android` | Kotlin, Jetpack Compose |
| iPhone controller | `apps/ios` | Swift, SwiftUI, Core Motion, Core Haptics |
| Desktop companion (macOS / Windows / Linux) | `hosts/app` | Tauri v2 + Rust host core |
| Headless host + controller simulator | `hosts/cli` (`ninectl`) | Rust |
| Host core (sessions, pairing, slots, diagnostics) | `hosts/core` | Rust |
| Virtual controllers | `hosts/linux` (uinput), `hosts/windows` (KMDF VHF driver), `hosts/macos` (IOHIDUserDevice) | Rust, C |
| Protocol v1 + vectors | `protocol/` (spec, Rust reference, Kotlin, Swift) | Noise XX, ChaCha20-Poly1305 |

**Status:** see [`docs/SUPPORT_MATRIX.md`](docs/SUPPORT_MATRIX.md) and the requirement map
[`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md). Nothing is marked working until it ran.

```sh
tests/run_all.sh                                   # every check that runs without devices
cargo run --release -p ninectl -- host --pair      # headless host; prints a pairing QR
cargo run --release -p ninectl -- sim --uri '…'    # simulated controller + RTT report
```
Build details: [`docs/BUILD.md`](docs/BUILD.md) · security: [`docs/SECURITY.md`](docs/SECURITY.md) ·
tests and metric definitions: [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md).
