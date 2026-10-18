# 2026-10-18 — Bluetooth LE on real radios: Android phone ↔ macOS host

Phone: realme 12 5G (RMX3999), Android 16 (API 36). App: Controller-9 0.1.0 debug (GATT peripheral).
Host: MacBook Air (Apple Silicon, macOS 27.0.1), `controller9 host --config-dir … --no-advertise
--stats-every 1` (btleplug / CoreBluetooth central). Backend: macOS IOHID — **Blocked** (missing
`com.apple.developer.hid.virtual.device`). **No game or OS input API on the Mac can see a controller
from this run.** Everything below is the input state the host core received and decoded.

USB cable stayed connected for adb only. `adb forward --list` and `adb reverse --list` were empty;
with phone Wi-Fi off the phone had no route to the Mac (`Network is unreachable` on LAN/stream
attempts) and the Mac's `lsof` showed only its own listeners, no phone sockets. The only path left
was the Bluetooth radio.

## Setup checks
| Check | Result |
|---|---|
| Phone permissions | BLUETOOTH_ADVERTISE + BLUETOOTH_CONNECT granted by the user (realme blocks `pm grant`); `bluetooth_on=1` |
| Host permission | `NSBluetoothAlwaysUsageDescription` embedded in the CLI binary; macOS prompt accepted |
| Pairing | Reused the existing Controller-9 pairing (Noise XX, pinned host key). No OS Bluetooth bonding is used; the GATT link carries the same end-to-end AEAD frames. Host ran **without** any auto-approve option |
| Active link, phone | `P1 · CHAITANYAS-MACBOOK-AIR · BLUETOOTH LE`; log `connected link=BLE slot=0 detail=MTU 517` |
| Active link, host | slot stats `"link":"Bluetooth LE"`, peer `phone (Bluetooth LE)` |

## Measurements (real BLE radio, phone Wi-Fi off)
| Test | Result |
|---|---|
| Cold start with Wi-Fi off | BLE connected on attempt 0 |
| Gas pedal | host rt = 55306 (84 % depth) and 65535 (full), released to 0 |
| E-brake | host buttons = 1 while held, 0 after |
| Steering | lx reached 32767 from real motion data (saturated). Full tilt range **not yet measured** |
| Input frame rate at host | 5–7 Hz idle heartbeat, 20–23 Hz holding a control, 74–111 Hz during sweeps |
| Protocol RTT (encrypted ping) | p50 55–65 ms, p95 87–110 ms; one 262 ms p95 sample right after a reconnect |
| Inter-arrival | p50 ≈ 60 ms, p95 ≈ 210 ms (BLE connection interval + coalescing) |
| Lost / stale / invalid | 0–3 lost (superseded inputs coalesced), 0 stale, 0 invalid |
| 60 s soak after fixes | 0 phone restarts, 0 host neutral resets, 1 frame lost |
| **Bluetooth turned off while holding full throttle** | `svc bluetooth disable` at t=0; frames kept arriving until ≈ t+0.49 s (stack shutdown); host `neutral_reset` **≈ 243 ms after the last frame** (t+0.74 s); host closed the BLE session at t+2.5 s (2 s silence); slot kept for the 30 s grace |
| Reconnect after Bluetooth on | host `slot_connected` **6.6 s** after `svc bluetooth enable` (BT stack start + advertise + scan + Noise handshake); same slot; phone shows "Last reconnect took 18749 ms" = whole outage |
| Wi-Fi turned back on, Automatic link | stays on BLE: the policy only switches to *measured* links, and LAN failed while Wi-Fi was off. See open issue 3 |

## Defects found on the radio and fixed
1. Phone UI stuck on "Reconnecting" while BLE was alive: a stale reconnect coroutine kept running.
   Fixed with a connection generation guard and deadlines on stream/BLE handshakes and `open`.
2. False watchdog trips ("no response for 1506 ms on Bluetooth LE"): a 1 s dead-link timeout is
   too tight for BLE intervals. Now BLE uses a 5 s timeout, 500 ms pings and reacts to the GATT
   disconnect callback at once.
3. The old BLE session lingered ≈5 s on the Mac and delayed the reconnect: the host now closes a
   BLE session after 2 s of silence. Input neutralization is unaffected (still 250 ms).
4. Host `ble.connected` list: two sessions both called "phone" were removed together. Now only the
   ended session's entry is removed.

## Open issues
1. Tilt-range steering over BLE not measured (needs a person to turn the phone).
2. btleplug logs "Shouldn't get anything but Ok!" on macOS; harmless so far.
3. With Automatic, a phone on BLE does not return to Wi-Fi when Wi-Fi comes back, because LAN is
   unmeasured. Needs a background probe of better links.
4. The policy counts failed attempts while no network exists at all, which lowers link scores.
5. Not run: BLE to Windows/Linux hosts, iOS as peripheral, more than one phone over BLE.

## Simulated vs radio
Earlier BLE results (`hosts/core/tests/links.rs`, MTU 23/185) use **simulated characteristics** on
loopback. They prove segmentation and session code, not radio behaviour. Only the table above
comes from real radios.
