# Android phone as a Bluetooth controller for Google TV — emulator simulation (2026-10-26)

Two Android emulators on the emulator's virtual Bluetooth (netsim, emulator 37.2.12): a Pixel 8 phone
(Android 16) running the **released Controller-9 0.2.3 APK** in Bluetooth controller mode, and a
**Google TV** (Android 16, `system-images;android-36;google-tv;arm64-v8a`) as the host.

| Step | Result |
|---|---|
| TV accessory screen finds the controller | **No — emulator limitation**: the virtual controller rejects the TV's filtered LE scan (`btm_ble_scan_filt_param_cfg_evt: BTM_ERR_PROCESSING`), so it lists no BLE devices at all |
| Unfiltered scan + bond from the TV (debug-only `c9_bond` hook) | found "Xbox Wireless Controller"; numeric-comparison pairing confirmed on both; bonded (`bonded.txt`) |
| Google TV's own HID host (HOGP) connects | yes — `HID_HOST connected`, input device **Xbox Wireless Controller 045e:0b13**, classes **GAMEPAD, JOYSTICK, VIBRATOR** (`input-device.txt`) |
| Presses on the phone reach the TV | A → `BTN_GAMEPAD` (South), B → `BTN_EAST`, X → `BTN_NORTH`, Y → `BTN_WEST` (Android BUTTON_A/B/X/Y), D-pad up → `ABS_HAT0Y −1`, right → `ABS_HAT0X +1`, each released (`getevent.txt`) |

What this does not prove: pairing from a real TV's accessory screen (its filtered scan works on real
radios), a real TV's Bluetooth chip, or a game on the TV. Repeat with `tools/tv-sim/run.sh`.
