# Android 0.3.0 release APK — real-device test (2026-10-30)

- File: `site/public/downloads/Controller-9-0.3.0.apk` (= `apps/android/app/build/outputs/apk/release/Controller-9-0.3.0-release.apk`,
  built after `4f9e615`; no Android or protocol change since), SHA-256 `316eb47f7ca7fc4553858fd3c1c7dc7ede0d00b95098fb59eccc915f255426c1`,
  identical to the `base.apk` pulled back from the phone after install.
- Signed: V2, `CN=Controller-9, O=Controller-9`, certificate SHA-256 `7a797fc9…684ba1be` — same certificate as every release since 0.1.0.
- Device: realme 12 5G (RMX3999), Android 16. Host: `controller9 host --mock --trace-input` on the MacBook Air (macOS 27.0.1).

| Check | Result |
|---|---|
| Install over 0.2.3 (`adb install -r`) | versionName 0.3.0, versionCode 6; games (Any game, Asphalt Legends, Go Kart Racing) and the selected game kept |
| Pairing with the computer app | six-digit code by manual address; codes matched on both screens (389 986), paired |
| Touch accuracy, gyro aim off | **20/20** — every control sends only itself (`accuracy-gyro-off.txt`) |
| Touch accuracy, gyro aim on (phone lying still) | **20/20** (`accuracy-gyro-on.txt`); gyro was live: 149 of 260 frames carried right-stick values, peak \|ry\| 624 of 32767 (≈2 %) from tap vibration — below the checker's 3000 threshold |
| Customise (review fix) | Layouts → Xbox-style → Customise → Save, then Play: the controller opened **Xbox-style · custom**, not the stock layout (`customise-play-keeps-custom.png`). "Any game" set back to Xbox-style afterwards |
| Bluetooth "Turn on" with Bluetooth off (review fix) | showed Android's "Controller-9 wants to turn on Bluetooth" prompt; Deny → mode stays off, Wi-Fi session unaffected |

## How this was run

The campus Wi-Fi (SJSU_guest) isolates clients today — phone and Mac could not ping each other — so the phone
reached the host through `adb reverse tcp:47901 tcp:47901` and `tcp:47902` and paired with `127.0.0.1:47901`.
The session ran on the TCP stream link (the datagram link cannot cross `adb reverse`, which is TCP-only, so it
reconnected every 3 s; that is the tunnel, not the app). The app, its touch handling and its input frames are
the same as over Wi-Fi.

`accuracy.sh` is the Android port of `ControllerAccuracyUITests`: it presses each control with `adb shell input`
on the landscape Xbox-style controller and prints `STEP <name> <unix-seconds>` lines; the oracle is
`tools/ios-ci/check-trace.py <steps> <host.log>`, unchanged from the iPhone runs.

## Gyro aim and the iPhone's 13/20 (2026-10-27)

Gyro aim adds the phone's turn rate to the right stick by design. With the phone still, Android's identical maths
(dead zone 0.035 rad/s, full stick at 3 rad/s) stays far below the accuracy checker's threshold, so it scores
20/20. A hand-held phone that rocks while being tapped exceeds it, and the checker then counts right-stick
movement as a wrong extra input. That is the likely cause of the iPhone's 13/20 with gyro on; its log was not
kept and the iPhone was not reachable today, so it is **not confirmed on the iPhone**.

## Seen, not fixed

In a customised layout the controller's top bar (layout name, GYRO … EXIT; one chip wider than stock because of
EDIT) overlaps the top inner corners of LT and RT. Stock layouts are clear. Not checked whether 0.2.3 did the same.
