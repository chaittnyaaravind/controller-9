# Test plan

Two layers: **development checks** (run anytime, `tests/run_all.sh`) and the **Stage 4 acceptance
campaign** on real devices and operating systems. Mocks never count as acceptance evidence.

## Metric definitions (precise; report these names)
| Metric | Definition | How measured | Target |
|---|---|---|---|
| **Protocol RTT** | phone send time of an encrypted `ping` → phone receive time of the matching `pong` (phone monotonic clock) | `InputChannel.rttMs` p50/p95 over ≥ 1000 pings | — (diagnostic) |
| **One-way network estimate** | Protocol RTT / 2 | derived | — |
| **Wi-Fi input latency** | finger touch-down (or tilt crossing) → OS-visible gamepad state change on the host | high-speed camera (≥ 240 fps) filming phone + host input-viewer; or photodiode rig; n ≥ 200 events | < 20 ms typical (p50), < 35 ms p95 |
| **USB latency** | as above over Android USB tethering / ADB-reverse | same | < 10 ms typical |
| **Host processing** | UDP receive → backend `submit` return | host `hostProcessingP95Us` | — |
| **Motion sampling rate** | sensor events per second delivered to the app while steering | app `Sensor Hz`, 60 s window | ≥ 100 Hz |
| **Reconnect time** | Wi-Fi restored (or control loss detected) → `session` received | app `lastReconnectMs`; host log timestamps | < 3 s |
| **Battery** | % battery used per hour of continuous play, screen at 50% brightness, motion on | OS battery stats, 60 min, start ≥ 80% | < 8 %/h |
| **First-time setup** | install complete → first input visible in a gamepad tester | stopwatch, 5 first-time users | < 2 min |
| **Loss / jitter** | counter gaps / RFC 3550 jitter of input frames | host diagnostics | report |

Loopback results (this Mac) measure protocol + crypto overhead only and are labelled as such.

## Development checks (automated, no devices) — `tests/run_all.sh`
Rust unit + loopback e2e (pairing, replay/stale/tamper, timeouts, grace/resume, 4 slots, rumble),
cross-target checks (Linux x86_64/aarch64, Windows x86_64), vector stability, Kotlin vector + engine
tests, Android debug APK, Swift vector + app-core tests, iOS UI parse, three-language live interop.

## Acceptance matrix (Stage 4)
| # | Area | Platform(s) | Procedure | Pass criteria | Needs |
|---|---|---|---|---|---|
| A1 | Install + first run | Android | `adb install`, launch, permissions | no crash; only documented permission prompts | Android phone |
| A2 | Touch controls | Android, iOS | every control type in all presets; multi-touch 5 fingers | correct bits/axes in host Diagnostics and OS tester | phones + host |
| A3 | Motion + calibration | Android, iOS | calibrate, sweep ±lock, curves, invert, lock | monotonic, centred, Sensor Hz ≥ 100 | phones |
| A4 | QR / code pairing | all | pair each phone with each host; wrong code; expired QR | succeed / fail as specified | phones + hosts |
| A5 | OS-visible pad | Windows | `joy.cpl`, Windows.Gaming.Input tester, SDL `testcontroller`, Steam Input | all axes/buttons/hat correct | Windows 11 + signed/test-signed driver |
| A6 | OS-visible pad | Linux | `evtest`, SDL `testcontroller`, Steam | correct mapping, FF rumble returns to phone | Linux box |
| A7 | OS-visible pad | macOS | SDL `testcontroller`, GameController test app | **blocked** until entitlement | Apple entitlement |
| A8 | Games | Win/Linux | ≥ 3 titles (racing, platformer, local co-op) | playable; note anti-cheat refusals | games |
| A9 | Disconnect neutral | all hosts | hold throttle, kill Wi-Fi / app / host | host shows neutral ≤ 250 ms; no stuck input | — |
| A10 | Packet loss | all | `tc netem` / Network Link Conditioner 1/5/10 % | no stuck input; loss counted | — |
| A11 | Four players | Win/Linux | 4 phones | 4 independent pads in tester | 4 phones |
| A12 | Reconnect | phones | toggle Wi-Fi, walk out of range | back < 3 s after Wi-Fi returns; same slot | — |
| A13 | Latency | phone↔host | camera/photodiode rig | report p50/p95 vs targets | rig |
| A14 | Battery | phones | 60 min session | report %/h | — |
| A15 | Security | all | malformed fuzz (`ninectl` + `tc`), replay capture, rogue host | all rejected; no crash | — |
| A16 | Lifecycle / orientation / a11y | phones | background, rotate, TalkBack/VoiceOver, reduced motion | input released on background; controls labelled | — |
| A17 | Repeated sessions | all | 50 connect/disconnect cycles | no leaks/crashes; slots released | — |

## Transport campaign (Controller-9 master prompt §I) — all real-device
| # | Scenario | Pairs | Pass criteria |
|---|---|---|---|
| T1 | Same LAN, Internet disabled at the router | each phone × host | connects, plays 10 min, metrics recorded |
| T2 | Phone hotspot, Internet/mobile data off | each phone × host | as T1; record subnet + whether client isolation interferes |
| T3 | Android Wi-Fi Direct local network | Android × each host | computer joins; QR lists 192.168.49.x; as T1 |
| T4 | Bluetooth only — every Wi-Fi radio off on both devices | each phone × host | connects over BLE ≤ 15 s; report RTT/jitter/loss/input Hz; button + motion usable |
| T5 | USB only — Wi-Fi and Bluetooth off | each phone × host where supported | USB tethering link; lowest RTT expected |
| T6 | Kill the active link mid-input (holding throttle) | every link pair | host shows neutral ≤ 250 ms; next link up < 3 s; same slot; no stuck input; no flapping in 10 min |
| T7 | Sleep/wake, background, route change, cable unplug, BT toggle, host restart, rotation | all | recovers; nothing stuck; diagnostics explain |
| T8 | Bad code, unpaired peer, replayed capture, malformed packets, flood, revoked peer | all bindings | all rejected, no crash, limits hold |
| T9 | 1–4 controllers, sustained 60 min, host load | all hosts | per-slot metrics; battery %/h |
| T10 | OS controller detection + named games | each advertised host | gamepad tester + ≥3 titles; record refusals |

Report separately: network RTT, one-way estimate, host processing, OS injection delay, and true
end-to-end (camera/photodiode) latency. Anything not run is **UNVERIFIED**.

## Recording results
Each run records: date, device model + OS version, host OS + version, backend, connection type
(band, RSSI), duration, sample count, p50/p95/p99, jitter, loss, sensor Hz, battery, reconnect
times — into `tests/results/<date>-<topic>.md`. Never overwrite earlier results.
