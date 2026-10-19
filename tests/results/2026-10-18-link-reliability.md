# Link reliability fixes — real-device results (2026-10-18)

Hardware: realme 12 5G (Android 16) ↔ MacBook Air (macOS 27.0.1), host `controller9 host` built from
`5f4d235`, paired host identity `b3290803d963ffe4` preserved. All results below are real radio
(Wi-Fi / Bluetooth LE), not loopback. Evidence: host stats/event log and phone logcat (tags `C9Conn`,
`C9Ble`) with epoch timestamps.

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | Brief neutral releases while holding input over Wi-Fi | **PASS (fixed)** | Cause: frames sent 50 ms apart arrived 330–440 ms apart (Wi-Fi power-save batching) → 250 ms release fired. With the low-latency Wi-Fi lock: 0/30 holds released (was 2/20, 6/25), 0 input gaps; RTT p50/p95 8.8/55.9 ms (was ~20/~125). Lock held → released on Home → held on return (verified via `dumpsys wifi`). |
| 2 | Bluetooth LE reconnect after Bluetooth off → on | **PASS (fixed)** | Release after Bluetooth off: 0.76 / 0.66 / 0.66 s. Reconnect after on: 5.3 / 7.5 / 5.3 s (was 16.6 s). Log: "Bluetooth turned off", "Bluetooth turned on; retrying now". |
| 3a | Return to Wi-Fi when Wi-Fi comes back while on BLE | **PASS, with radio stall** | Back on Wi-Fi in ~5.7 s, no user action (2 runs). Trigger was the phone's BLE stalling ≥2 s while Wi-Fi associates (shared radio) → host "BLE session silent for 2s" → phone failed over to LAN in ~0.2 s. Input released safely during the stall. Not fixable in the app. |
| 3b | Planned BLE → Wi-Fi switch via the unmeasured-LAN probe (Wi-Fi already connected) | **PASS** | Link preference Bluetooth LE → Automatic tapped at 1792380589.46. Phone: "probing unmeasured LAN while on BLE" +3.24 s, "connected link=LAN" +3.41 s (make-before-break, 170 ms). Host: link `Bluetooth LE` → `Wi-Fi / LAN` at 03:29:54Z, health `live` throughout, `neutral_resets` 0. The old link's close was logged as "ended during switch", not a failure (race fix). |
| 3c | No duplicate connects during a switch | **PASS** | One `connected link=LAN` per switch (was two before the `linkFailed` guard). |

Not covered here: steering calibration with a real hand-held wheel turn (needs the user) and any game
seeing the controller (macOS entitlement still missing; see `docs/FIRST_GAME_TEST.md`).

The link preference used for test 3b was restored to **Automatic** (its original value).
