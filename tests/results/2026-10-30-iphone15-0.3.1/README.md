# iPhone 15 (iOS 27.0.1), Controller-9 0.3.1 build 5 — real-device run (2026-10-30)

Built from this commit and signed with the owner's free Apple ID (team `92RT7Q9JD6`, profiles valid until
2026-11-03). Xcode installed it as part of the UI-test runs, over 0.3.0. Host: `controller9 host --mock --trace-input`
on the MacBook Air. The iPhone connected over Wi-Fi: the campus network let phone and Mac reach each other this
evening.

| Check | Result |
|---|---|
| Installed version | `dev.ninecontroller.ios` **0.3.1 (5)** |
| Touch accuracy (`ControllerAccuracyUITests`), phone flat on the table, hands off | **20/20** (`accuracy-hands-off.txt`) |
| First run, phone in the owner's hand | 16/20 (`accuracy-first-run-handheld.txt`). Per-slot split: every extra input came from the iPhone itself. It sent Y/LB presses, left-stick movement, and right-stick values from gyro aim, which was still on from 0.3.0 testing. That is fingers on the screen and a moving phone, not the app; the hands-off rerun is 20/20 |
| Gyro aim, hand-held (on via `GyroSetupUITests`, controller opened with `-layout pad:xbox -screen controller`) | 50 s, 2,015 frames: right stick reached **−32767…+32767 on both axes**; **0 frames** with any button, left stick or trigger. Free-hand turns mix axes, so which gesture gave which direction is not proven by this run (unit tests cover the mapping) |
| Gyro aim turned off again afterwards | `testTurnOffGyroAim` passed |

Not tested on the iPhone: the Bluetooth pairing fallback (Wi-Fi worked, so nothing needed it), and the glass custom-layout
look on the device (only the stock layout was played). 
## Sideload IPA (released)

`site/public/downloads/Controller-9-0.3.1-iphone-sideload.ipa`: an unsigned Release build made like CI
(`.github/workflows/ios.yml`), 2,054,205 bytes, SHA-256 `ce7d6a7f4ccc050043c7fe37f9f15bdf0cd959276e6d6d0741f4290ea3046c51`.
`tools/ios-ci/sideload-test.sh` re-signed this exact file with the free Apple ID, installed it on the iPhone 15 and launched it.
It was still running 8 s later, and the phone reports `dev.ninecontroller.ios 0.3.1 (5)`. The AltStore source (`altstore.json`)
lists 0.3.1 build 5, dated 2026-10-30.
