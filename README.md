# Controller-9

Your Android phone is an Xbox controller. Open the app and the phone pairs with your computer from the
computer's own Bluetooth settings as **"Xbox Wireless Controller"**. Nothing is installed on the
computer, and games read it the way they read the real thing: buttons, sticks, analog-reported triggers,
D-pad, Guide, and rumble back to the phone. No account, no internet permission, no ads.

| Part | Path | Tech |
|---|---|---|
| Android app | `apps/android` | Kotlin, Jetpack Compose |
| Input and profile model (+ frozen vectors) | `protocol/kotlin`, `protocol/vectors` | Kotlin |
| Xbox Bluetooth HID report and its vectors | `apps/android/.../input/XboxHidReport.kt`, `tests/vectors/hid-xbox-ble.json` | Kotlin |
| Website | `site/` | static HTML, built by `site/tools/build.py` |

**Status and evidence:** [`docs/ACCEPTANCE_STATUS.md`](docs/ACCEPTANCE_STATUS.md), [`docs/DIRECT_BLUETOOTH.md`](docs/DIRECT_BLUETOOTH.md),
[`docs/SUPPORT_MATRIX.md`](docs/SUPPORT_MATRIX.md). Nothing is marked working until it ran on a real phone.

```sh
tests/run_all.sh                                        # unit tests + debug APK
cd apps/android && ./gradlew :app:assembleRelease      # release APK (signing: docs/BUILD.md)
```

The earlier multiplatform project (iPhone app, Mac/Windows/Linux companion, Rust host and protocol) is
kept on the `archive/multiplatform` branch.
