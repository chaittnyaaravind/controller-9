# macOS host

## What works without Apple approval
The whole host: Noise pairing (QR/code), sessions, four slots, timeouts, diagnostics, DNS-SD, and
the companion app. The backend reports **Blocked** and the UI says so plainly.

## What is blocked, verified
`IOHIDUserDeviceCreateWithProperties` returns NULL for an unentitled process on macOS 27.0.1
(`hosts/macos/poc/vhid_probe.c`; `cargo test -p nine-pad-macos` prints the live status).

## Route to resolve
1. Enrol in the Apple Developer Program (organisation account recommended).
2. Request the **`com.apple.developer.hid.virtual.device`** entitlement (CoreHID / IOHIDUserDevice
   virtual devices) through Apple's entitlement request form, describing the use: a user-installed
   companion that exposes a phone as a standard HID gamepad.
3. When granted, add it to a provisioning profile, uncomment it in
   `hosts/app/macos/entitlements.plist`, sign with Developer ID, notarize.
4. Acceptance A7: SDL `testcontroller` (IOKit HID path) must see the device. Titles that only use
   GameController.framework may still ignore virtual devices — document per title, do not promise.

Alternatives (not adopted): a DriverKit HID dext (separate entitlements and system-extension
approval, more user friction) or the licensed libvirtualhid macOS broker.
