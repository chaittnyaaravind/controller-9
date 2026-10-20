# Platform support (2026-10-20)

Four separate statuses per host — never merged into one green light:
**Transport connected** → **Virtual device created** → **Input visible to the OS** → **Verified in game**.
Evidence levels as in TRANSPORT_MATRIX.md (I implemented · C compiled · U tested on the dev Mac ·
D device · O OS-input · G game · R release-ready).

## Phone controllers
| | Android | iPhone |
|---|---|---|
| App | Compose app — D | SwiftUI app — D |
| Links | Wi-Fi/LAN, TCP-only, hotspot, USB tethering, Ethernet, Wi-Fi Direct local network, BLE peripheral | Wi-Fi/LAN, TCP-only, Personal Hotspot (Wi-Fi/USB), BLE peripheral (foreground) |
| Motion | game rotation vector, 100/200 Hz | Core Motion device motion, 100/200 Hz |
| Distribution | signed release APK on the website | free sideload (AltStore source / Sideloadly), 7-day re-sign; App Store needs the $99 program |
| Installed on a device | **yes** — realme 12 5G, Android 16 (`tests/results/2026-10-18-*`) | **yes** — iPhone 15, iOS 27.0.1: 20/20 touch-accuracy UI test, ~100 updates/s, RTT p50 7.5 ms (`tests/results/2026-10-19-iphone15-wifi.md`) |

## Desktop hosts
| | Transport | Virtual device | Visible to the OS | In game |
|---|---|---|---|---|
| **Windows x64** | O | **ViGEmBus Xbox 360 pad** (driver installed by the app's one-click button / `controller9 install-driver`, checksum-pinned, UAC) | **O — XInput**: every button in order, all axes both ways, game rumble back to the phone; **SDL** sees "Xbox 360 Controller". Fallback without Microsoft's XUSB driver: DualShock 4, proven through SDL ("PS4 Controller") | via XInput/SDL (the APIs games use); no commercial title run |
| **macOS** | U | gamepad blocked (Apple entitlement); **keyboard & mouse mode** instead (Accessibility permission) | **O — live test**: 18 keys in order, pointer both ways on both axes, both clicks (`tests/results/mac-keyboard-mode.txt`) | games with keyboard controls; controller-only games can't see it |
| **Linux aarch64** | O | uinput 045e:028e | **O — SDL 2.30 "X360 Controller"**, all 15 buttons, sticks, triggers, rumble, safe release (`tests/results/2026-10-19-linux-uinput-vm.md`) | **G — SuperTuxKart 1.4** detects "Xbox 360 Controller" (11 buttons, 6 axes, hat) with force feedback (`tests/results/2026-10-20-linux-supertuxkart.txt`) |
| **Linux x86_64** | O (CI) | uinput | **O — SDL "X360 Controller"**, every button and axis in order (installers CI) | same code as aarch64 |

Windows evidence comes from GitHub's Windows Server 2025 runners (`.github/workflows/installers.yml`,
`windows-probe.yml`). ViGEmBus is unsupported on Server and Server lacks Microsoft's Xbox 360 driver,
so CI adds Microsoft's own `xusb21` (Update Catalog, SHA-pinned) — desktop Windows 10/11 ship it.
Not yet run on a desktop Windows 10/11 PC. Some anti-cheat titles refuse virtual pads.

## What users see
Each host shows its backend status with the exact blocker and a one-click fix where one exists
("Install driver" on Windows, "Allow keyboard control" on Mac), per-player link labels, every
reachable address, and Bluetooth scanning state. If the usual port is taken, the app runs on free
ports and says so instead of failing. No status turns green for a capability that has not been proven.
