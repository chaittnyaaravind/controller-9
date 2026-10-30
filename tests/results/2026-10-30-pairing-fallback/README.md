# Pairing when the Wi-Fi keeps devices apart (2026-10-30)

Found while release-testing Android 0.3.0: on SJSU_guest the realme 12 5G and the MacBook Air could not reach
each other (ping failed both ways), and "Pair with code" by address only said "Can’t reach the computer on this
network". Fixed on Android and iPhone; host fix in `hosts/core/src/link.rs`.

## What changed

| Where | Change |
|---|---|
| Android + iPhone pairing | A network pairing attempt that gets **no answer** (timeout, no route — not a refusal: `ConnectionDoctor.noAnswer`) switches to Bluetooth pairing by itself when Bluetooth is usable, showing "No answer over Wi-Fi — this network may keep devices apart. Trying Bluetooth instead…". The computer's pairing window is already open, so the code comparison simply appears. Without Bluetooth, the connection check runs on that address and its cause and every fix are shown (plus "turn Bluetooth on" when it is only off) |
| Android connection check | A VPN counts as the cause only if it carries traffic to the computer: its most specific route to that address is not a `throw` route (Android 10+). The phone's 1.1.1.1 VPN excludes 10/8 and was wrongly blamed before |
| Host BLE link | The 2 s silence watchdog applies only once input flows; before that (handshake, a person comparing pairing codes — the phone waits up to 120 s) the limit is 130 s. Before the fix the host closed the Bluetooth link 2 s after showing the code, so **pairing over Bluetooth failed whenever confirming took more than 2 s** |

## Evidence

| Check | Result |
|---|---|
| Host regression test `ble_link_survives_quiet_before_the_session_opens` (simulated GATT, 2.5 s quiet before pairing) | failed before the fix, passes after |
| Unit tests | Android `ConnectionDoctorTest` 7/7 (2 new), iOS NineAppCore 37/37 (2 new); `tests/run_all.sh` all pass; iPhone app builds for the simulator |
| realme, Bluetooth off, pair by `10.251.54.244` | after 4 s: "10.251.54.244 doesn't answer … a Wi-Fi that keeps devices apart …" with all fixes and "Or turn Bluetooth on" (`no-bluetooth-explained.png`); before the VPN fix it said "A VPN is on" |
| realme, Bluetooth on, same address | note after 3 s, "Connecting to computer over Bluetooth…", code over Bluetooth LE after ~9 s; confirmed after 5–6 s; host `paired`, `pairing_resolved ok` (`bluetooth-fallback.png`, `fallback-test.sh`) |
| Same, before the host fix | host log "BLE session silent for 2s; closing it" → `pairing_resolved ok:false` |

## Not done

- iPhone: the fallback is unit-tested and compiles, but was not run on the iPhone 15 (not reachable today; the
  simulator has no Bluetooth peripheral).
- iPhone connection check still counts any VPN tunnel as a VPN (iOS has no public route table); it can blame a
  VPN that excludes the local network.
