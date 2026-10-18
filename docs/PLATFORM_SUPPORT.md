# Platform support (2026-10-18)

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
| Installed on a device | **no** (no phone connected) | **no** |

## Desktop hosts
| | Transport connected | Virtual device created | Visible to the OS | In game |
|---|---|---|---|---|
| **macOS** | U (CLI host on this Mac, loopback, 3 client languages, IP + stream) | **Blocked** — Apple `com.apple.developer.hid.virtual.device` entitlement (verified NULL without it) | blocked | blocked |
| **Windows 11 x64** | C (Rust type-checks for Windows incl. BLE/WinRT) | C for the client side; **KMDF VHF driver source not built** (needs WDK; attestation signing for release; test-signing only with your approval) | not tested | not tested — generic HID gamepad, XInput-only titles need Steam Input |
| **Linux x86_64** | C (type-checks without BLE; BLE needs a native build with libdbus-1-dev) | C (uinput) — **not run** | not tested | not tested |

Windows ARM64, Linux aarch64 hosts: aarch64 Linux type-checks; neither is tested or offered.

## What users see
Each host shows its backend status with the exact blocker and fix (e.g. "macOS refused to create a
virtual HID device: this build lacks the … entitlement"), per-player link labels, every reachable
address, and Bluetooth scanning state. The phone shows the active link and why other links are
unavailable. No status indicator turns green for a capability that has not been proven.
