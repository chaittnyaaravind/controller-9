# Android 0.5.0 — Android-only, real-device test (2026-10-30)

The project became Android-only. The iPhone app, the desktop companion, the Rust host and protocol and their CI moved to
the `archive/multiplatform` branch. The Android app lost the computer-app connection (Wi-Fi/QR pairing, connection check,
diagnostics, players 2–4) and with it the internet, network, Wi-Fi Direct and location permissions. It is a Bluetooth
Xbox controller. The home screen is now a Settings hub: controller status, games, layouts, controls & feel, steering, Remote.

- File: `site/public/downloads/Controller-9-0.5.0.apk`, 2,884,380 bytes, SHA-256 `e18d97228b30526d9a1c3d4cd036e189dc6875208186493e417dc462eed450a2`,
  identical to the `base.apk` pulled back from the phone. Same signing certificate as every release.
- Device: realme 12 5G (Android 16), installed over 0.4.0. Computer: MacBook Air, macOS 27.0.1, paired as "Xbox Wireless Controller".

| Check | Result |
|---|---|
| Installed permissions | no `INTERNET`, network, Wi-Fi or location permission in the installed package |
| Opens as the controller; Mac reconnects (after turning the Mac's Bluetooth off and on once, needed after any update) | yes, 5 s |
| Every control through macOS GameController (`xbox-bluetooth-every-control.txt`, `gamecontroller-trace.log`) | **20/20**; the gap between Y and B sends nothing; Guide opens the Mac's Games overlay as with a real pad |
| Left stick, slow 1.5 s drag | full travel (0.997) |
| Rumble from the Mac (`tools/mac-rumble`) | the phone vibrated from 20:13:30.9, in ~100 ms segments for the pattern |
| Settings hub | `settings-connected.png`, `settings-games-controller.png`; controller mid-drag: `controller-left-stick-mid-drag.png` |

Test-tool fix found during this run: the first version of `gcrec.swift` logged a change only if it exceeded 0.02 between two
consecutive updates, so a smooth drag of many small steps logged nothing (a false "left stick failed"). It now logs against the
last logged value. Screenshots mid-drag showed the stick moving the whole time.
