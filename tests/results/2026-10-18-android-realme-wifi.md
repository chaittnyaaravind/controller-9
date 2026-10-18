# 2026-10-18 — first real-device campaign: Android phone ↔ macOS host over Wi-Fi

Phone: realme 12 5G (RMX3999), Android 16 (API 36), arm64. App: Controller-9 0.1.0 debug.
Host: MacBook Air (Apple Silicon, macOS 27.0.1), `controller9 host` CLI, macOS backend (reports
Blocked — no OS-visible controller; input state observed via host diagnostics).
Network: campus guest Wi-Fi "SJSU_guest" (open, 802.11ac, RSSI −65). Phone 10.251.46.25/16,
Mac 10.250.244.195 — **different subnets, routed** via 10.251.255.254 (not the same L2 segment);
no Internet needed by the app. DNS-SD discovery does not cross the router → manual address used.

| Test | Result |
|---|---|
| Install + launch (adb install) | OK, no crash after fixes below |
| Numeric-comparison pairing | OK ×4 (codes matched on both screens: 144 347, 016 486, 722 099, 912 819) |
| Session over Wi-Fi/LAN | OK, link labelled correctly; backend warning shown on phone |
| Protocol RTT (phone ping/pong) | p50 10.4–13.0 ms, p95 55–124 ms (noisy routed guest network); ICMP to phone 13–60 ms |
| Motion sensor rate | **199.8–199.9 Hz** (game rotation vector) |
| Gas pedal (touch at 84 % depth) | host received rt = 55306 (= 0.84 × 65535) |
| E-brake button | host received buttons = 1 (A) |
| Send rate | 5 Hz idle heartbeat; 17–20 Hz while holding a control (50 ms heartbeat) |
| Wi-Fi cut while holding full throttle (×2) | host neutralized ≈0.22–0.5 s after the last received frame; no stuck input; Android took ~2–3 s to stop transmitting after `svc wifi disable` |
| Reconnect after Wi-Fi returns | **0.13–0.27 s** from "network available" to session; 4.6 s from radio-on command (Android Wi-Fi association dominates); same slot kept |
| TCP-only (stream) link over real Wi-Fi | OK (used once by the policy, before the fix below) |
| Host identity changed | phone refuses, keeps pairing, explains (after fix) |
| Loss / stale / invalid frames | 0 / 0 / 0 throughout |

## Defects found on the device and fixed
1. Text with no explicit colour rendered black on black (no Material Surface) → default content colour.
2. **Crash**: UDP receive after the link was closed during a link swap (EBADF) → closed-link exception handled; every link thread catches and reports.
3. Controller screen never auto-connected (read settings before DataStore loaded) → read stored settings.
4. **Security**: `open` was sent before the pinned host-key check; an impostor's `not_paired` made the phone delete its pairing → key pinned right after the handshake on Android and iOS; pairing kept.
5. Policy: unmeasured TCP-only link beat measured noisy Wi-Fi (planned switch, and after an outage) → planned switches only to measured links; unmeasured stream inherits the IP RTT (Rust/Kotlin/Swift + new vectors).
6. Host kept showing "Live" while a player was silent → `silent_ms` + "No input" label.

## Not yet tested on this device
Bluetooth LE (phone Bluetooth off; permission needs the user), USB tethering, hotspot, Wi-Fi Direct
network, battery drain, 60-minute session, true end-to-end latency, motion steering with tilt.
