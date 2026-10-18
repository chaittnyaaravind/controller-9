# 2026-10-18 — redesign acceptance on the real phone

Phone: realme 12 5G (RMX3999, serial X8JZHQ49GMTWXWRG), Android 16, with a Cloudflare 1.1.1.1 VPN
running. Host: MacBook Air, macOS 27.0.1, `controller9 host --config-dir <hostcfg>` (host id
`b3290803d963ffe4`, unchanged throughout). Network: campus guest Wi-Fi (routed subnets).
The macOS backend is **Blocked** (missing `com.apple.developer.hid.virtual.device`): no game on
the Mac can see Controller-9. Every input result below is the state decoded by the host core
(CLI `--stats-every 1` JSON, sampled once per second, timestamped on the Mac).
Screens: `2026-10-18-redesign-screens/` (downscaled captures from the phone).

## Results

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | Phone detected and authorized | PASS | `adb devices -l`: `X8JZHQ49GMTWXWRG device … model:RMX3999`; keyguard not showing |
| 2 | USB troubleshooting (phone dropped off twice) | PASS — diagnosed | adb restart + `system_profiler SPUSBDataType` + `ioreg -p IOUSB` + USB log all showed no phone at all → physical disconnect, not adb auth. It came back once reconnected. |
| 3a | Install + launch current APK | PASS | `adb install -r` → Success (data kept with `-r`) |
| 3b | All five layouts render on the phone | PASS | on-device Compose test (2/2); live screens for Xbox-style and DualSense-style; Layouts list with all five cards (`p-04-layouts.jpg`) |
| 3c | Layout selection persists after restart | PASS | on-device test: selection → activity recreate → `active_profile=pad:dualsense`; also after each `install -r` the app reopened on the last chosen layout |
| 4a | Real buttons — Xbox-style over Wi-Fi | PASS | host saw A→`btn=1`, Guide→`16384`, LB→`16`, D-pad up→`1024`, RT→`rt=65535`, left stick right→`lx=32767`; all back to 0 |
| 4b | Racing-profile throttle over Wi-Fi | PASS | 50 % pedal depth→`rt=32792`, full→`65535`; brake→`lt=65535`; E-brake→`btn=1`; released to 0 |
| 4c | Steering calibration | PARTIAL | Live readout and the refuse-to-guess path PASS: lying flat, angle/output/direction show "—" and "Measure neutral" answers "Couldn’t read the motion sensor. Hold the phone upright, not flat."; nothing saved. Neutral / full-left / full-right with real tilting: **BLOCKED** — needs a person holding the phone |
| 4d | Safe release when the connection drops (BLE, Wi-Fi off) | PASS | RT held at `rt=65535` → `svc bluetooth disable` at …708.754 → host `neutral_reset` at …709.435, ≈257 ms after the last frame; no stuck input |
| 5a | Two presets over Wi-Fi | PASS | Xbox-style (4a) and DualSense-style: Cross→`1`, Circle→`2`, R2→`65535`, PS→`16384`, Create→`64`, Options→`128`, L3→`256`. Transport: phone `connected link=LAN`, pill `P1 · WI-FI`; host `"link":"Wi-Fi / LAN"` |
| 5b | Two presets over Bluetooth LE, Wi-Fi off | PASS | DualSense-style: Cross `1`, Circle `2`, Triangle `8`, Square `4`, R2 `65535`, L1 `16`, PS `16384`, D-pad right `8192`. Xbox-style: A `1`, X `4`, LT `65535`, View `64`, Menu `128`, right stick up `ry` 4256→18665→32008, left stick up `ly`→29129 (the first right-stick attempt did not register at the 1 Hz samples; repeated). Transport: phone `connected link=BLE … MTU 517`, pill `P1 · BLE`; host `"link":"Bluetooth LE"`, peer `phone 06a3b2 (Bluetooth LE)`; `wifi_on=0`, `ip route get` → "Network is unreachable", no adb forward/reverse |
| 6a | Portrait + landscape screenshots | PASS | portrait: Home (unpaired, connected), Layouts, Switch Pro detail + mapping table; landscape: Xbox-style, DualSense-style (Wi-Fi and BLE), layout switcher, Home, Calibration, Diagnostics |
| 6b | Real disconnect → Stale on the host | PASS | Wi-Fi cut under a LAN session: host `"health":"stale"`, `silent_ms` 2599 while the TCP control socket was still ESTABLISHED. BLE cut: live → **stale** at +1.7 s → session closed after 2 s silence → **holding** (30 s grace) → on return `waiting` → `live` in the same slot |
| 6c | Test suite | PASS | `tests/run_all.sh` exit 0 (15/15) and `cargo clippy --workspace --all-features -D warnings` exit 0, after all fixes below; Android unit tests 31/31 |
| 7 | Host identity preserved / restarted | PASS | restarted three times with the same `--config-dir`; always `host_id=b3290803d963ffe4` |
| — | Re-pairing (after the data loss below) | PASS | numeric comparison: phone and host both showed **140 524** for "realme 12 5G"; approved only after comparing; no auto-approve |
| — | Diagnostics on device | PASS | connected 174 s; last host reply 165 ms ago; last frame sent 29 ms ago; last disconnect reason "no response for 5021 ms on Bluetooth LE" (matches the phone log) |

## Defects found on the device and fixed in this pass

1. **Top bar overflow** (DualSense-style): the longer layout name squeezed the bar so "EXIT" wrapped
   vertically. Now: compact link names in the pill (Wi-Fi, BLE, USB, TCP), the pill truncates first,
   bar buttons are single-line, "Inputs" is a toggle instead of the wider "Hide inputs", and the bar
   uses the full gap between the shoulder controls. Verified on the phone (`l-05-dualsense-bar-fixed.jpg`).
2. **VPN tunnel counted as Wi-Fi/LAN**: with Wi-Fi off, the VPN's `tun0` (172.16.0.2) was classified
   as LAN, so the policy kept trying a LAN switch every second while on BLE. VPN, tunnel and cellular
   interfaces are now never local links (`LocalLinks.classify`, unit-tested). Verified: cold start with
   Wi-Fi off → BLE on attempt 0, 0 LAN attempts in 25 s.
3. Disconnect reason no longer repeats the link name.
4. Host diagnostics: input gaps longer than the timeout are logged with the arrival gap and the
   phone-side send gap; the CLI stats line now includes `health` and `device_id`.

## Procedure error (mine)

Running the on-device UI test with Gradle's `connectedDebugAndroidTest` **uninstalled the app
afterwards, erasing its data** (controller identity, pairing, settings). The app was reinstalled,
re-paired by code comparison, and the Bluetooth permission (Nearby devices) was granted again in
Android's own dialog. Future runs must install with `adb install -r` and use `am instrument`.

## Open observations (not fixed)

- **Transient releases on Wi-Fi during holds**: 6 `neutral_reset`s during roughly 25 Wi-Fi holds,
  each 0.4–2 s after a press began; 0 during 14+ BLE holds. Seen on both the new preset surface and the
  original Racing renderer, with and without the inspector, with and without screenshots. They fail
  safe (released, never stuck) and recover on the next frame. The new host `input gap` log line
  (arrival gap vs phone send gap) is in place to separate phone scheduling from network delay on the
  next run.
- **BLE reconnect after Bluetooth returns: 16.6 s** (earlier campaign 6.6 s): an attempt started just
  before the adapter was back waited its full 8 s timeout. Restarting the attempt on the adapter-on
  broadcast would remove this.
- After Wi-Fi returns the phone stays on BLE until LAN is measured (no background probe), as before.
- Native desktop window not screenshotted (terminal lacks Screen Recording permission; not changed).
