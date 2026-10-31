# Support matrix (Android only, since 2026-10-30)

The phone pairs as a Bluetooth LE "Xbox Wireless Controller" (045E:0B13). Statuses are what ran, not what should work.

| Computer / reader | Status | Evidence |
|---|---|---|
| macOS 27 — GameController (Mac games) | **works** — 20/20 controls, Guide opens the Games overlay, rumble reaches the phone | `tests/results/2026-10-30-android-0.4.0-release/` |
| macOS — Safari / Chrome Gamepad API (browser cloud gaming) | **works** — standard mapping, buttons 0–15, all axes | `docs/DIRECT_BLUETOOTH.md` (2026-10-25/26) |
| macOS — Asphalt Legends | **works** — Xbox prompts, played from the phone | `tests/results/2026-10-25-asphalt/` |
| Linux — SDL/Steam HIDAPI Xbox driver, evdev | **simulated** (uhid, no radio) — buttons, axes, rumble | `docs/DIRECT_BLUETOOTH.md` (bt-host-sim) |
| Google TV (emulator, virtual Bluetooth) | see `tools/tv-sim` | `docs/DIRECT_BLUETOOTH.md` |
| Windows 10/11 | **not tested** on hardware | — |

Phones: realme 12 5G (Android 16) is the test device. Android 8+ is supported by the app; Bluetooth
controller mode needs a phone that can act as a Bluetooth LE peripheral (the app says so if it can't).
