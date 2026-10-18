# 2026-10-18 — redesign acceptance on the real phone (partial)

Phone: realme 12 5G (RMX3999, serial X8JZHQ49GMTWXWRG), Android 16. Host: MacBook Air, macOS 27.0.1,
`controller9 host --config-dir <hostcfg>` (host id `b3290803d963ffe4`, unchanged). Build: commits
`517fb9c`..`3fb33cb`. The macOS backend is **Blocked** (missing `com.apple.developer.hid.virtual.device`):
no game on the Mac can see Controller-9; everything here is measured at the host core.

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | Phone detected and authorized | PASS (first window) | `adb devices -l`: `X8JZHQ49GMTWXWRG device … model:RMX3999`; keyguard not showing; Wi-Fi and Bluetooth on |
| 2 | USB troubleshooting after the phone disappeared (twice) | BLOCKED — physical | `adb kill-server`/`start-server`: no devices; `system_profiler SPUSBDataType`, `ioreg -p IOUSB` and the last 5 min of the USB log show **no** phone at all, so it is not an adb authorization problem. Needs the cable reseated / phone reconnected. |
| 3a | Install + launch current APK | PASS | `adb install -r` → Success |
| 3b | All five layouts render on the phone | PASS | `UiFlowTest.everyPresetDiagramRendersWithUnavailableControlsDescribed` (connectedDebugAndroidTest, 2 tests, 0 failures): each preset diagram displayed; every unavailable control described |
| 3c | Layout selection persists after app restart | PASS | `UiFlowTest.selectingALayoutUpdatesTheAppAndPersistsAcrossRestart`: Home → Layouts → DualSense-style → "Use this layout"; activity recreated; stored `active_profile` = `pad:dualsense` and Home shows it |
| — | **Side effect** | **Regression in procedure** | Gradle's `connectedDebugAndroidTest` uninstalls the app after the run, which erased the phone's app data (controller identity, pairing, settings) and the review screenshots. App reinstalled. The phone must pair again. Future runs: install both APKs with adb and run `am instrument` so the app is kept. The host identity was not touched. |
| 4 | Button input, Racing throttle, calibration, release on connection drop | BLOCKED | Needs re-pairing (code confirmation on both screens), then the phone left USB |
| 5 | Two presets over Wi-Fi and over Bluetooth LE with Wi-Fi off, transport on both ends | BLOCKED | Same |
| 6a | Portrait / landscape screenshots of the redesign | BLOCKED | Same (the UI-test screenshots were deleted with the app data) |
| 6b | Real disconnect → host "stale" state | BLOCKED | Same. Logic covered by the `slot_health` unit test only |
| 6c | Test suite | PASS | `tests/run_all.sh` exit 0 (15 PASS lines: Rust tests, cross-target checks, vectors stable, Gradle tests + APK, Swift, live Rust/Kotlin/Swift interop); `cargo clippy --workspace --all-features -D warnings` exit 0 |
| 7 | Paired host identity preserved, host restarted | PASS | Restarted with the same `--config-dir`; log shows `host_id=b3290803d963ffe4`. Running with pairing open; every request still needs an explicit `y` after comparing codes (no auto-approve). |

Earlier verified transport results (Wi-Fi, Bluetooth LE) are unchanged:
`2026-10-18-android-realme-wifi.md`, `2026-10-18-android-realme-bluetooth.md`.
