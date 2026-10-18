# Controller-9

Use your phone as a low-latency game controller for your computer — touch controls, motion
steering, up to four players — over local Wi-Fi, a hotspot, a USB cable or Bluetooth LE, with
automatic failover. End-to-end encrypted, no Internet, no account, no cloud.

| Part | Path | Tech |
|---|---|---|
| Android controller | `apps/android` | Kotlin, Jetpack Compose |
| iPhone controller | `apps/ios` | Swift, SwiftUI, Core Motion, Core Haptics |
| Desktop companion (macOS / Windows / Linux) | `hosts/app` | Tauri v2 + Rust host core |
| Headless host + controller simulator | `hosts/cli` (`ninectl`) | Rust |
| Host core (sessions, pairing, slots, diagnostics) | `hosts/core` | Rust |
| Virtual controllers | `hosts/linux` (uinput), `hosts/windows` (KMDF VHF driver), `hosts/macos` (IOHIDUserDevice) | Rust, C |
| Protocol v1 + vectors | `protocol/` (spec, Rust reference, Kotlin, Swift) | Noise XX, ChaCha20-Poly1305 |

**Status:** see [`docs/PLATFORM_SUPPORT.md`](docs/PLATFORM_SUPPORT.md), [`docs/TRANSPORT_MATRIX.md`](docs/TRANSPORT_MATRIX.md),
[`docs/ACCEPTANCE_STATUS.md`](docs/ACCEPTANCE_STATUS.md) and the requirement map
[`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md). Nothing is marked working until it ran.

```sh
tests/run_all.sh                                   # every check that runs without devices
cargo run --release -p ninectl --bin controller9 -- host --pair      # headless host; prints a pairing QR
cargo run --release -p ninectl --bin controller9 -- sim --uri '…' [--link stream]   # simulated controller
```
Build details: [`docs/BUILD.md`](docs/BUILD.md) · security: [`docs/SECURITY.md`](docs/SECURITY.md) ·
tests and metric definitions: [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md).
