# Android 0.3.1 release APK — real-device test (2026-10-30)

- File: `site/public/downloads/Controller-9-0.3.1.apk` (= `apps/android/app/build/outputs/apk/release/Controller-9-0.3.1-release.apk`),
  SHA-256 `774f526e40e3484a94a06b568ff61bce5086c721795260fbce4d18f9f0f69346`, identical to the `base.apk` pulled back from the phone.
- Signed: `CN=Controller-9, O=Controller-9`, certificate SHA-256 `7a797fc9…684ba1be` — same as every release since 0.1.0.
- Device: realme 12 5G (RMX3999), Android 16. Host: `controller9 host --mock --trace-input`, rebuilt from this commit, on the
  MacBook Air (macOS 27.0.1). It reached the phone through `adb reverse` because the campus Wi-Fi isolates clients
  (same method as 0.3.0).

What's new since 0.3.0: the Bluetooth pairing fallback (`2026-10-30-pairing-fallback/`), custom layouts drawn as glass
with the bar between the triggers (`2026-10-30-custom-layout-look/`), and the motion and spacing pass (`2026-10-30-motion-spacing/`).

| Check | Result |
|---|---|
| Install the published 0.3.0, then this 0.3.1 over it (`adb install -r`) | 0.3.0 (code 6) → 0.3.1 (code 7); games, the custom layout and the computer pairing kept (connected without pairing again) |
| Touch accuracy, gyro aim off, **this exact file** | **20/20** (`accuracy-gyro-off.txt`) |
| Touch accuracy, gyro aim on (chip lit; phone lying still) | **20/20** (`accuracy-gyro-on-prefix-build.txt`). This ran on the build just before the final one, which differs only in the layout pill's caret (below). The gyro sent no right-stick values during this run (phone still), so it shows gyro aim adds no stray input at rest, not that aim works |
| Custom layout ("Xbox-style · custom") on the phone | glass parts, glyphs, D-pad arrows; A lights when pressed; bar clear of LT/RT (`custom-layout.png`) |
| Found and fixed during this test | on the custom layout, the layout pill's "▾" wrapped to a second line, making the pill taller than the chips. The final build keeps it on one line (`custom-layout.png` is from the final build) |
| Stock layout | `stock-layout.png` |

Not tested: the Bluetooth fallback on this exact file (it was tested on the same code under version 0.3.0, see
`2026-10-30-pairing-fallback/`); the iPhone (unavailable).
