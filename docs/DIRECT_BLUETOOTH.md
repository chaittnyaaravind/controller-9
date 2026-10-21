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
