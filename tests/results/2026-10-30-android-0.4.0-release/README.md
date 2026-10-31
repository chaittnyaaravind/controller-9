# Android 0.4.0 — "your phone is an Xbox controller" — real-device test (2026-10-30)

- File: `site/public/downloads/Controller-9-0.4.0.apk`, SHA-256 `67a2152be30e62dd26fa74716117eb840e6e6bc69e712f3de13210492e13091a`,
  identical to the `base.apk` pulled back from the phone. Signed with the same certificate as every release (`7a797fc9…684ba1be`).
- Device: realme 12 5G (Android 16), installed over 0.3.1. Computer: MacBook Air, macOS 27.0.1, paired once as "Xbox Wireless Controller".
- What changed: see `2026-10-30-xbox-first-matte/`. The app opens as the controller with Bluetooth controller mode on, has the
  matte-black look, an edge margin, the fast-tap latch and crisp clicks.

## Oracle: Apple's GameController framework (what Mac games read)

`gcrec.swift` records every change of the first extended gamepad (A…RS, D-pad, both sticks). `accuracy-hid.sh` presses each control on
the phone with `adb shell input` (Guide last, because macOS answers Guide by opening its Games overlay and holds controller input for
a few seconds, like with a real pad).

| Check | Result |
|---|---|
| Every control sends only itself (`xbox-bluetooth-every-control.txt`, `gamecontroller-trace.log`) | **20/20**: A, B, X, Y, LB, RB, LT, RT, View, Menu, LS, RS, D-pad left/up/right/down, a touch between Y and B sends nothing, left stick right, right stick right; Guide opened the Mac's Games overlay (`guide-opens-games-overlay.png`) and isn't passed to apps, same as a real Xbox pad |
| Rumble from the Mac (`tools/mac-rumble`, GameController haptics) | the phone vibrated: the vibrator log shows the rumble streaming in ~100 ms segments for the pattern's length. Clearly seen at 19:50:20 on the same code before the version number changed; on 0.4.0 the tool reported "rumble sent" and the vibrator log has entries in that minute |
| After installing the update | the Mac kept the Bluetooth link but dropped the gamepad until its own Bluetooth was turned off and on (4 s to come back), as documented (`docs/DIRECT_BLUETOOTH.md`, lesson 11). Any app update does this; the site tells people |

Not tested on real hardware here: Windows, Linux, consoles, TVs (the Linux host simulation from 2026-10-26 covers SDL/Steam's driver).
