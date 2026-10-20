# Controller-9 launch board — v0.1.0

Owner decisions are marked **[you]**; everything else is engineering work that can proceed without
them. Status words mean exactly this: **Shipped** = file on the website, installed and tested on the
platform; **Preview** = shipped with a stated limitation; **Built** = installer exists and passed its
install test, not yet on the website; **Blocked** = waiting on the named item.

## Platforms

| Platform | Package | Status | Install test evidence | What it needs next |
|---|---|---|---|---|
| Android | `.apk` (sideload), `.aab` (Play Store) | **Shipped** (APK) | Android 16 emulator: installs, launches in 0.9 s, no crash. Same code tested on a realme 12 over Wi-Fi/BLE (`tests/results/2026-10-18-*`). | Google Play listing **[you]**: developer account, store text, screenshots, privacy form. |
| Mac | `.dmg` universal (Apple silicon + Intel) | **Preview** | Launches from the DMG, stays up, host listening; TCC crash found and fixed. | Apple Developer Program **[you]** → notarize, request `hid.virtual.device` so games see the controller. |
| Windows | NSIS setup `.exe` (x64) | **Built** (CI) | Silent install + launch + listening on a Windows Server 2025 runner (`installers.yml`). | EV code-signing certificate **[you]** + Microsoft attestation signing of the driver — until then games can't see the controller. |
| Linux | `.deb` x86_64 + arm64, AppImage x86_64 | **Built** (CI + VM) | `.deb` installs with its uinput rule; app launches and listens; SDL sees "X360 Controller", every button and axis in order (`installers.yml`, `tests/results/2026-10-19-linux-uinput-vm.md`). | Real game on a Linux desktop with a phone (one session). |
| iPhone | `.ipa` (unsigned, for sideloading) | **Built** | Signed build installed on an iPhone 15; on-device accuracy test 20/20 (`tests/results/2026-10-19-iphone15-wifi.md`). | Apple Developer Program **[you]** → TestFlight, then App Store review. |

## Decisions and costs for the owner

| Decision | Why it matters | Cost (approx.) |
|---|---|---|
| Apple Developer Program | iPhone public install (TestFlight/App Store), Mac notarization, Mac controller entitlement request | $99 / year |
| Google Play developer account | Android in the Play Store (auto-updates, trust) | $25 once |
| Windows EV code-signing certificate | Signed installer + driver attestation so Windows games see a controller | ~$250–600 / year (vendor-dependent) |
| Custom domain (e.g. controller9.app) | Brand, trust, shorter link than controller-9.vercel.app | ~$10–60 / year |
| Back up `~/.controller9-release/` | The Android release key: losing it means updates can't install over the current app | free — do it now |

## Release v0.1.0 notes

- Phone controllers on Android and iPhone with five familiar controller layouts (Xbox, DualSense,
  DualShock 4, Switch Pro, 8BitDo Pro 2) plus racing, arcade and custom layouts.
- Grip-relative tilt steering: centre is however you hold the phone; recenter any time.
- Touch accuracy: round controls hit-test as circles, cardinal-dominant D-pad with hysteresis, the
  control under the finger always wins. Verified 20/20 on an iPhone 15.
- Redundant links: Wi-Fi and Bluetooth in one session; losing one link keeps you playing.
- Two-motor rumble rendered on the phone's haptics.
- Encrypted pairing (Noise XX), local network only, no account, no telemetry.
- Measured: ~100 updates/s during play, 7.5 ms median round trip over Wi-Fi, 0 lost frames.

## Known limitations (stated on the website)

- Mac: games can't see the controller until Apple grants the entitlement.
- Windows: games can't see the controller until the driver is signed.
- iPhone: no public install path without the Apple Developer Program.
