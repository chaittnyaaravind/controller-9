# Platform support (2026-10-19)

Four separate statuses per host — never merged into one green light:
**Transport connected** → **Virtual device created** → **Input visible to the OS** → **Verified in game**.
Evidence levels as in TRANSPORT_MATRIX.md (I implemented · C compiled · U tested on the dev Mac ·
D device · O OS-input · G game · R release-ready).

## Phone controllers
| | Android | iPhone |
|---|---|---|
| App | Compose app — I C U(JVM protocol + engine tests) | SwiftUI app — I; core packages C U; UI parses, **not type-checked (needs full Xcode)** |
| Links | Wi-Fi/LAN, TCP-only, hotspot, USB tethering, Ethernet, Wi-Fi Direct local network, BLE peripheral | Wi-Fi/LAN, TCP-only, Personal Hotspot (Wi-Fi/USB), BLE peripheral (foreground) |
| Motion | game rotation vector, 100/200 Hz | Core Motion device motion, 100/200 Hz |
| Build artifact | debug APK + unsigned release APK (local); signed APK/AAB via CI secrets | needs Apple team + Xcode; TestFlight/App Store only |
| Installed on a device | **yes** — realme 12 5G, Android 16 (D: pairing, Wi-Fi, BLE, failover; `tests/results/2026-10-18-*`). Relative steering, redundant links and new haptics built but **not yet installed** | **no** |

## Desktop hosts
| | Transport connected | Virtual device created | Visible to the OS | In game |
|---|---|---|---|---|
| **macOS** | U (CLI host on this Mac, loopback, 3 client languages, IP + stream) | **Blocked** — Apple `com.apple.developer.hid.virtual.device` entitlement (verified NULL without it) | blocked | blocked |
| **Windows 11 x64** | C (Rust type-checks for Windows incl. BLE/WinRT) | C for the client side; **KMDF VHF driver source not built** (needs WDK; attestation signing for release; test-signing only with your approval) | not tested | not tested — generic HID gamepad, XInput-only titles need Steam Input |
| **Linux aarch64** | U (native build incl. BLE; simulated controller over the real protocol, loopback in a VM) | **O — created** (uinput, 045e:028e) | **O — SDL 2.30 sees "X360 Controller"** with the built-in mapping; all 15 buttons, sticks, triggers, rumble and both safe-release paths verified ([results](../tests/results/2026-10-19-linux-uinput-vm.md)) | not tested (no game run yet) |
| **Linux x86_64** | C (type-checks; same code as aarch64) | C (uinput, same code) | not tested on x86_64 | not tested |

Windows ARM64: not tested or offered.

## What users see
Each host shows its backend status with the exact blocker and fix (e.g. "macOS refused to create a
virtual HID device: this build lacks the … entitlement"), per-player link labels, every reachable
address, and Bluetooth scanning state. The phone shows the active link and why other links are
unavailable. No status indicator turns green for a capability that has not been proven.
