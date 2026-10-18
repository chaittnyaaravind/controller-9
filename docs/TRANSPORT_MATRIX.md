# Transport matrix — phone ↔ host links (2026-10-18)

Evidence levels (cumulative): **I** implemented · **C** compiled · **U** unit/integration-tested on
this Mac (loopback or simulated radio) · **D** device-tested · **O** OS-input-tested · **G** game-tested
· **R** release-ready. Nothing below is past **U**: no phone, Windows PC, Linux machine or second Mac
has been available. Every untested combination is **UNVERIFIED**.

All links carry the same protocol (SPEC §8): Noise XX + AEAD end to end, identical sessions, slots,
freshness and safe-neutral rules. None needs the Internet.

## Links

| Link | How it works | Phone side | Host side | Evidence | Notes / limits |
|---|---|---|---|---|---|
| **Wi-Fi / LAN** | IP binding: TCP 47901 control + UDP 47900 input; QR lists every host address; phone races them | Android, iOS | macOS, Windows, Linux | I C U (loopback, 3 languages live) | Client isolation on guest Wi-Fi blocks it → use hotspot/BLE |
| **TCP-only (stream)** | one TCP stream (47902), records, latest-input coalescing | Android, iOS | all | I C U (live Rust↔Kotlin and Rust↔Swift on loopback) | For networks that drop UDP; adds head-of-line blocking under loss |
| **Phone hotspot** | IP binding over the phone's own hotspot; computer joins it | Android, iOS | all | I C | Android 192.168.43.x / varies by OEM; iPhone 172.20.10.x. App reaching the joined computer: **UNVERIFIED** |
| **Computer hotspot** | phone joins a network the computer creates | Android, iOS | Linux (`nmcli dev wifi hotspot`), Windows Mobile Hotspot | I (no code needed) | macOS can only share an existing connection (Internet Sharing) — not a standalone AP. Windows may require an upstream adapter: **UNVERIFIED** |
| **Wi-Fi Direct (Android-hosted local network)** | Android creates an autonomous Wi-Fi Direct group (Android 10+); the computer joins it as an ordinary Wi-Fi network | Android | any OS with Wi-Fi | I C | iPhone has no Wi-Fi Direct API. Apple's AWDL peer-to-peer is Apple-only and not used |
| **USB tethering** | IP binding over the phone's USB network (no ADB, no developer mode) | Android (Settings → Hotspot & tethering → USB tethering); iPhone (Personal Hotspot over USB) | Windows (RNDIS/NCM built in), Linux (rndis_host/cdc_ncm), macOS (iPhone yes; Android only if the phone uses NCM) | I C | Classified as "USB network" by interface name/subnet; **UNVERIFIED** on every pair |
| **Ethernet** | IP binding; host or phone (USB-C Ethernet adapter on Android) wired to the LAN | Android | all | I C | **UNVERIFIED** |
| **Bluetooth LE** | GATT binding: phone = peripheral, host = central (btleplug: CoreBluetooth / WinRT / BlueZ); segmentation; hint in scan-response service data (Android) or local name (iOS) | Android 8+ with LE peripheral support; iOS (foreground only) | macOS, Windows, Linux (needs libdbus-1-dev build) | I C U (simulated characteristics at MTU 23/185 against the real host session code) | Fallback: expected 15–30 ms intervals, coalesced input. **Real radio UNVERIFIED** — measure before advertising rates |
| Bluetooth Classic RFCOMM | — | Android only (iOS forbids) | Windows/Linux | not implemented | Would reuse the stream binding unchanged |
| Android USB accessory (AOA) | — | Android | needs a libusb host component | not implemented | Feasible; not started |
| iPhone custom USB channel | — | — | — | not possible | Third-party apps get no generic USB data channel (MFi/External Accessory only) |
| Cloud relay | — | — | — | intentionally absent | Local only by design |

## Pairs × "works with Wi-Fi radios off?"

| Phone → host | Wired path (Wi-Fi + BT off) | Bluetooth path (Wi-Fi off) | Status |
|---|---|---|---|
| Android → Windows | USB tethering | BLE | both implemented; **UNVERIFIED** |
| Android → Linux | USB tethering | BLE | both implemented; **UNVERIFIED** |
| Android → macOS | USB tethering only if the phone exposes NCM | BLE | **UNVERIFIED** (macOS has no RNDIS driver) |
| iPhone → macOS | Personal Hotspot over USB | BLE (app in foreground) | **UNVERIFIED** |
| iPhone → Windows | Personal Hotspot over USB (needs Apple's device driver) | BLE (app in foreground) | **UNVERIFIED** |
| iPhone → Linux | Personal Hotspot over USB (ipheth) | BLE (app in foreground) | **UNVERIFIED** |

No combination may be advertised as "works without Wi-Fi" until the matching acceptance rows
(docs/TEST_PLAN.md T4, T5) pass on real hardware.
