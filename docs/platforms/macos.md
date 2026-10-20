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

### Open question and its probe (development only)
Two things are unknown until someone runs it: whether macOS 27 still lets an administrator (root)
create the device without the entitlement, and whether **GameController.framework** (what most Mac
games use) lists such a virtual pad. `sh tools/mac-root-probe.sh` answers both in one run: it asks
for your password, starts a throwaway root host on separate ports (an already paired host is
untouched), runs the simulated sweep, lists HID devices and listens with `tools/mac-gc-probe`.
Report: `tests/results/mac-root-probe.txt`. Root is a test rig, **never a shipping path** — users
must not run a game controller as root.

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
