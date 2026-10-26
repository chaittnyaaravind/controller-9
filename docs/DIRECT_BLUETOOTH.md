# Direct Bluetooth gamepad — the phone *is* the controller (plan, 2026-10-20)

## Why
Every other route needs something on the computer: a driver (Windows), uinput (Linux), or an
entitlement Apple has not granted (macOS — `docs/platforms/macos.md`). A real Bluetooth controller
needs none of that: the OS's own Bluetooth HID stack creates the gamepad. If the phone speaks the
same protocol, it is a real controller to the OS — on macOS too, with no Mac app at all.

## How
Android phone = Bluetooth LE peripheral running **HID over GATT (HOGP)**, the profile real
Bluetooth pads (Xbox Wireless Controller, model 1914) use:
- HID service 0x1812: Report Map (Xbox Wireless Controller BLE descriptor), input report 1
  (sticks, triggers, hat, 15 buttons), output report 3 (rumble → phone vibration), HID Information,
  Protocol Mode, Control Point.
- Device Information 0x180A with PnP ID **045E:0B13** so macOS GameController.framework, SDL,
  Steam and Windows XInput (xinputhid) apply their Xbox Wireless Controller mapping.
- Battery service 0x180F (phone battery).
- Encrypted characteristics → the computer bonds once from its Bluetooth settings; it reconnects
  like any paired pad.

iPhone cannot do this (CoreBluetooth refuses the HID service). iPhone keeps the host-app route.

## Gates — nothing is claimed before its gate passes on real hardware
| # | Gate | Proof |
|---|---|---|
| G1 | Android accepts the HID GATT service and advertises it | app log + `adb` on realme 12 5G |
| G2 | Mac bonds with the phone from System Settings → Bluetooth | pairing completes, device listed as connected |
| G3 | macOS creates a HID gamepad | `hidutil list` / `ioreg` shows 045E:0B13 |
| G4 | **GameController.framework sees an extended gamepad** | `tools/mac-gc-probe` lists it; every button and axis moves |
| G5 | SDL sees "Xbox Wireless Controller" | SDL `testcontroller` / pygame |
| G6 | A real game is played | a Mac game with controller support |
| G7 | Rumble from the game vibrates the phone | output report 3 received |
| G8 | Latency / rate | report rate and button-to-host latency measured |
| G9 | Windows and Linux pair the same way | to be run when hardware is available |

## Product work after G4
Home-screen mode "Bluetooth controller (no computer app)" with a pairing guide, connection
status, battery, layouts/motion reused unchanged (same `ControllerEngine`), reconnect to the last
computer, unit tests for the report encoder, site + docs updated with evidence only.

## Risks (honest)
- Some Android builds block or interfere with third-party HID GATT services → G1 tells us.
- macOS may expect Xbox-specific behaviour beyond the descriptor → G4 tells us; fallback is a
  generic HID gamepad (SDL/Steam see it, GameController.framework may not).
- iPhone cannot be a Bluetooth HID device; it stays on the host-app route.

## Status — device-tested 2026-10-20 (realme 12 5G, Android 16 → MacBook Air, macOS 27.0.1)
| Gate | Result |
|---|---|
| G1–G3 | PASS — pairs from System Settings → Bluetooth; macOS creates HID 045E:0B13 over Bluetooth LE |
| G4 | PASS — GameController.framework: "Xbox One" extended gamepad with haptics; all 12 buttons, D-pad 4 ways, both sticks both axes both ways (`tests/results/2026-10-20-direct-bluetooth-mac.txt`) |
| Stability | 60 s steady state: 0 disconnects; app swiped from Recents and reopened: controller stays, all inputs arrive |
| Computer's Bluetooth off/on (sleep, range) | recovers by itself in ~6 s |
| **Phone's** Bluetooth off/on or phone reboot | does **not** recover by itself; turning the computer's Bluetooth off and on fixes it (tip shown in the app) |
| G6 (2026-10-25) | **PASS in Asphalt Legends** (Mac App Store, GameController.framework): with the debug sweep and Asphalt in front, Asphalt switches to Xbox button prompts, the pad closes a pop-up and opens its settings (`tests/results/2026-10-25-asphalt/`). A Unity game (Go Kart Racing) not yet tested |
| G5, G7–G9 | not run yet (SDL, rumble from a game, latency, Windows/Linux) |

### What the hardware taught us (each fix is in `HidGamepad.kt`)
1. A bonded host's notification subscription must persist across reconnects (macOS does not subscribe again).
2. macOS caches attribute handles; Android assigns the next free handle, which moves (234–351 measured).
   The controller is pinned at handle 640 with filler services, verified after setup and retried if a
   system service registered mid-way. Filler services need unique UUIDs (this stack never answers for duplicates).
3. Android's Service Changed characteristic has no client configuration descriptor, so macOS cannot
   subscribe and never refreshes its cache after the phone's layout changes. Hence the controller lives in
   a foreground service and is never torn down with the UI; only a phone Bluetooth restart rebuilds it.
4. After Bluetooth restarts this stack reports new links only via MTU/read/write callbacks; an app must
   claim the link (`connect`) or Android drops it within seconds (the controller flapped 14×/min).
5. Advertising needs a fresh callback per session and a retry while the LE advertiser comes up.
6. Pair from System Settings: a pairing made through CoreBluetooth or `blueutil` does not attach the HID driver.
7. Games match on the pad's **name**, not only 045E:0B13. macOS takes the HID product string from the
   phone's Bluetooth name ("realme 12 5G"), and Unity's Input System applies its Xbox layout only to
   `Xbox.*Wireless Controller` (checked in Go Kart Racing's Unity 6000.0.67 Input System). So the phone
   takes the name "Xbox Wireless Controller" while the mode is on and restores the user's name when it
   is turned off. A computer paired under the old name must pair again. Device-tested 2026-10-25: macOS
   lists the HID product and the GameController vendor as "Xbox Wireless Controller"
   (`tests/results/2026-10-25-direct-bluetooth-mac.txt`).
8. After the computer's Bluetooth restarts, a link this stack reports only through MTU/read/write
   callbacks (lesson 4) must also resume the bonded host's subscription. Before 2026-10-25 only the
   connection callback did, so the phone could show "connected" and send nothing (seen once: the sweep
   waited for a subscription that never came). In the re-test the connection callback fired, so the
   adopted-link path is fixed in code but not yet seen on hardware.
9. The Xbox (Guide) button belongs to macOS: it opens the game overlay, and controller input to apps
   stops for a few seconds afterwards. The debug sweep skips it; a player pressing it mid-game sees
   the same pause, as with a real Xbox pad.
10. Never `am force-stop` the app while a computer is paired: the computer keeps the Bluetooth link
   but drops the gamepad until its own Bluetooth is turned off and on. Debug hooks are sent to the
   running app with `am start -f 0x20000000` (single top), not by restarting it.
