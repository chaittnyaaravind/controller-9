# Acceptance status (2026-10-26)

Every "done" points at evidence. Anything not run on the named hardware says so.

| Item | Status | Evidence |
|---|---|---|
| Rename to Controller-9 without breaking IDs/protocol | **done** | `docs/NAME_MIGRATION.md`; vectors unchanged |
| Protocol byte-compatible Rust/Kotlin/Swift incl. links + policy | **done (automated)** | vector files reproduced by all three; live IP + stream interop Rust↔Kotlin and Rust↔Swift in `tests/run_all.sh` |
| Failover, authentication, sequence protection, safe neutral | **done** in integration tests; neutral on disconnect measured on a device over BLE | `hosts/core/tests/`; `tests/results/2026-10-18-android-realme-bluetooth.md` |
| ≥1 local transport works without Internet | **done on devices** — Android Wi-Fi and BLE, iPhone Wi-Fi | `2026-10-18-android-realme-wifi.md`, `2026-10-18-android-realme-bluetooth.md`, `2026-10-19-iphone15-wifi.md` |
| Verified alternative with Wi-Fi radio off | **done for Android** — BLE link to the host, and the phone as a Bluetooth Xbox controller (no network) | `2026-10-18-android-realme-bluetooth.md`, `2026-10-25-direct-bluetooth-mac.txt` |
| Android signed APK, install-tested | **done** — 0.2.2 signed (same certificate since 0.1.0), installed over 0.2.1 on a realme 12 5G, A/B/X/Y/D-pad reached macOS GameController | `2026-10-26-android-0.2.0-release.md`, site `releases.json` |
| iPhone sideload IPA, install-tested | **done** — 0.2.1 re-signed like Sideloadly, installed and running on an iPhone 15; 0.2.2 tested end to end in the iOS Simulator (20/20 controls reach a host) | `2026-10-19-iphone15-wifi.md`, `2026-10-26-ios-simulator/` |
| iOS App Store / TestFlight | **blocked** — needs the $99 Apple Developer Program | — |
| Mac: controller visible to games | **done via the Android phone** (Bluetooth Xbox controller, no Mac app); Asphalt Legends shows Xbox prompts; Safari and Chrome see a standard gamepad. The Mac **companion** still can't create a gamepad (Apple entitlement) and offers keyboard-and-mouse mode | `2026-10-25-direct-bluetooth-mac.txt`, `2026-10-25-asphalt/`, `2026-10-26-chrome-gamepad-api.png`, `mac-keyboard-mode.txt` |
| Windows: OS-visible controller | **done in CI** (Windows Server runners): XInput sees every button and axis in order, rumble back to the phone, SDL sees "Xbox 360 Controller". **Not yet run on a desktop Windows 10/11 PC** | `.github/workflows/windows-probe.yml`, `installers.yml` |
| Linux: OS-visible controller and a game | **done** — uinput Xbox 360 pad in SDL; SuperTuxKart detects it with force feedback; Bluetooth Xbox mode seen by SDL and evdev in a host simulation | `2026-10-19-linux-uinput-vm.md`, `2026-10-20-linux-supertuxkart.txt`, `.github/workflows/bt-host-sim.yml` |
| Game compatibility entries | **2 games**: Asphalt Legends (Mac, Android Bluetooth mode), SuperTuxKart (Linux) | as above |
| Touch accuracy | **done** — every control sends only itself (iPhone 15 and Simulator, 20/20); chords tested on both platforms | `2026-10-19-iphone15-wifi.md`, `2026-10-26-ios-simulator/accuracy.txt` |
| Connection problems explained to the user | **done** — connection check on Android and iPhone, checked against a real host up and down | `2026-10-26-connection-doctor/` |
| Download page links only real, tested artifacts | **done** — sizes and SHA-256 computed from the files; the build stops on a missing file | `site/tools/build.py` |
| Android TV / smart TV | **not tested** — needs a TV | — |
| iPhone Bluetooth link to the computer app | **not tested** — code compiles; needs the iPhone and a host side by side | — |

Broad public launch criteria: Android and the website are ready; the remaining blockers are a desktop
Windows run, TV hardware, and the paid store programs (Google Play $25, Apple $99) — owner decisions.
