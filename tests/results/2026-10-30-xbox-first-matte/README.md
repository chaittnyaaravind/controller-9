# Android: opens as an Xbox controller, matte-black hardware, more responsive buttons (2026-10-30)

These changes follow the owner's feedback after trying the phone with POOLS on the Mac: "buttons are not sensitive
as they look", "remove that cheap light", "it should directly turn into an Xbox controller", "remove unnecessary sections".

| Change | Where |
|---|---|
| The app opens on the controller and turns on Bluetooth controller mode itself (it asks for permissions and Bluetooth when needed). The computer pairs with "Xbox Wireless Controller", with nothing to install. A computer-app session the player started from Settings is left alone | `MainActivity.kt` (start = play), `ui/controller/XboxStart.kt`, `ControllerScreen.kt` |
| In-game bar: layout pill, Lock, Settings (plus Recenter when steering is on). Gyro, Inputs and Exit moved to Settings. A short "Pair once" card shows under the bar until a computer connects, never over a button | `ControllerScreen.kt` |
| Look: matte-black hardware. A matte shell with no coloured light, no blur and no animation; moulded caps with a soft contact shadow and a fine top highlight; pressed caps sit lower and darker. A/B/X/Y letters in Xbox colours (only on Xbox-position letters) | `ui/pad/Glass.kt`, `ui/pad/PadVisuals.kt` |
| Edges: a thumb up to 20 dp off a cap presses it, unless it is about equally close to two caps (then nothing, as before) | `PadGeometry.hit(slop)`, `TouchMathTest.thumbJustOffACapStillPressesIt` |
| Fast taps: over Bluetooth HID, a button or trigger that goes down is reported at least once even if it is released before the radio sends; its release follows immediately | `input/TapLatch.kt`, `HidGamepad.submit`/`pumpLoop`, `HidTapTest` (4) |
| Click feel: a crisp click on press (`PRIMITIVE_CLICK`, was the soft `EFFECT_TICK`) and a lighter one on release | `input/Haptics.kt` |

Evidence: Android unit tests all pass (14 suites); `tests/run_all.sh` all PASS; emulator screenshots
`controller-matte-A-held.png` (A held, pairing card in the empty band) and `first-version-card-over-buttons.png`
(the first placement, which covered the centre buttons and was moved).

Not yet done: a run on the realme with POOLS (the phone was unplugged); no latency measurement for "feels laggy" beyond
the fast-tap fix. The Bluetooth HID radio's pace is set by the computer.
