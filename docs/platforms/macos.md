# macOS host

## What works without Apple approval
The whole host: Noise pairing (QR/code), sessions, four slots, timeouts, diagnostics, DNS-SD, and
the companion app. The backend reports **Blocked** and the UI says so plainly.

## What is blocked, verified
`IOHIDUserDeviceCreateWithProperties` returns NULL for an unentitled process on macOS 27.0.1
(`hosts/macos/poc/vhid_probe.c`; `cargo test -p nine-pad-macos` prints the live status).

Unified log for one attempt (2026-10-19): `[com.apple.iohid:userdevice] IOServiceOpen:0xe00002c2`
(`kIOReturnBadArgument`, returned when the IOHIDResource user client refuses to open), then
`Destroy: <IOHIDUserDeviceRef … id:0x0>`.

### Answered 2026-10-20: root does not help
Run as administrator (macOS admin prompt), the device creation still returns NULL and
GameController.framework sees nothing (`tests/results/mac-root-probe.txt`). Without Apple's
entitlement there is no virtual gamepad on macOS 27 at any privilege level; keyboard & mouse mode is
the only $0 path.

### Open question and its probe (development only)
Two things are unknown until someone runs it: whether macOS 27 still lets an administrator (root)
create the device without the entitlement, and whether **GameController.framework** (what most Mac
games use) lists such a virtual pad. `sh tools/mac-root-probe.sh` answers both in one run: it asks
for your password, starts a throwaway root host on separate ports (an already paired host is
untouched), runs the simulated sweep, lists HID devices and listens with `tools/mac-gc-probe`.
Report: `tests/results/mac-root-probe.txt`. Root is a test rig, **never a shipping path** — users
must not run a game controller as root.

### Answered 2026-10-20: macOS has a built-in Xbox match for Bluetooth LE controllers
`/System/Library/Extensions/AppleGameControllerPersonality.kext` (macOS 27.0.1) routes any HID
interface with **VendorID 1118 (0x045E) / ProductID 2835 (0x0B13)** — "Xbox Series X Wireless
Controller" — to `AppleGCHIDUserEventDriver`, category `xbox`, with no transport restriction. That is
the identity the Android **direct Bluetooth controller** mode presents over HID-over-GATT
(`docs/DIRECT_BLUETOOTH.md`), so macOS's own driver, not our app, creates the gamepad.
Device proof (gates G2–G6) is still pending.

### Answered 2026-10-20: AppleSyntheticGameController is closed to apps
macOS ships `AppleSyntheticGameController.kext` (service `AppleGCResource`), which creates software
controllers. An unentitled process gets `IOServiceOpen` → `0xe00002c1` (privilege violation) for user
client types 0–2, so it is reserved for Apple's own software. Not a route.

## Route to resolve
1. Enrol in the Apple Developer Program (organisation account recommended).
2. Request the **`com.apple.developer.hid.virtual.device`** entitlement (ready-to-paste text: `macos-entitlement-request.md`) (CoreHID / IOHIDUserDevice
   virtual devices) through Apple's entitlement request form, describing the use: a user-installed
   companion that exposes a phone as a standard HID gamepad.
3. When granted, add it to a provisioning profile, uncomment it in
   `hosts/app/macos/entitlements.plist`, sign with Developer ID, notarize.
4. Acceptance A7: SDL `testcontroller` (IOKit HID path) must see the device. Titles that only use
   GameController.framework may still ignore virtual devices — document per title, do not promise.

Alternatives (not adopted): a DriverKit HID dext (separate entitlements and system-extension
approval, more user friction) or the licensed libvirtualhid macOS broker.

## Keyboard & mouse mode (the $0 path, default without the entitlement)
`nine_pad_macos::native_backend()` uses the virtual gamepad when the entitlement exists, otherwise
**keyboard & mouse mode** (`hosts/macos/src/keyboard.rs`, layout in `hosts/core/src/keymap.rs`):
left stick = WASD, D-pad = arrows, A Space · B C · X R · Y F · LB Q · RB E · View Tab · Menu Esc ·
LS Shift · RS V, right stick = mouse (relative deltas, so mouse-look works), RT = left click,
LT = right click. One phone at a time. Every key and button is released on disconnect/timeout.
- **New permission: Accessibility** (System Settings → Privacy & Security → Accessibility), needed
  for `CGEventPost`. The app asks through macOS's own prompt (`CGRequestPostEventAccess`) from the
  "Allow keyboard control" button; until granted the backend reports Blocked with that remedy. No
  usage-description string exists for it in Info.plist.
- Games that read only controllers (GameController.framework) won't react; games with keyboard
  controls do. Events carry user-data tag 0x4339.
- Live proof: `sh tools/mac-keyboard-check/run.sh` (outside any sandbox; the test tap swallows
  every tagged event so nothing reaches your apps) → `tests/results/mac-keyboard-mode.txt`.

## Permissions the desktop app declares
`hosts/app/src-tauri/Info.plist` (merged into the app bundle): **Bluetooth** (`NSBluetoothAlwaysUsageDescription`,
to reach phones over BLE when Wi-Fi is unavailable) and **Local Network** (`NSLocalNetworkUsageDescription`,
`NSBonjourServices: _ninectl._tcp`). Without the Bluetooth reason, macOS terminates the app the moment it
scans (TCC privacy violation) — found by the install test of the first DMG on 2026-10-19.
