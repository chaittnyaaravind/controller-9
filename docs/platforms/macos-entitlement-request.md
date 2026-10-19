# macOS virtual-HID entitlement — request text

Ready to paste into Apple's entitlement request (Apple Developer account → Contact / entitlement
request for `com.apple.developer.hid.virtual.device`). Fill the bracketed fields. Keep it factual —
Apple reviews the use case, not marketing.

---

**Entitlement:** `com.apple.developer.hid.virtual.device`

**App:** Controller-9 for macOS — [bundle ID, e.g. `dev.ninecontroller.host`], distributed by
[team name] outside the Mac App Store with Developer ID signing and notarization.

**What the app does:** Controller-9 turns a user's own phone into a game controller for their Mac.
The phone app sends button, stick and trigger state over the local network or Bluetooth LE to a
companion app the user installs on the Mac. The companion presents that input to games as one
standard USB-HID gamepad per connected phone (up to four players).

**Why a virtual HID device is required:** Games read controllers through IOKit HID / GameController.
There is no other supported way for a user-space app to present a gamepad. Without the entitlement,
`IOHIDUserDeviceCreateWithProperties` returns NULL (verified on macOS 27.0.1).

**What the virtual device is:** a generic HID gamepad (Generic Desktop / Game Pad usage): 15 buttons,
two 2-axis sticks, two triggers. It is not a keyboard or mouse. It never injects keystrokes or
pointer events and does not impersonate any other vendor's hardware.

**When it exists:** only while the companion app is running and a phone the user explicitly paired
is connected. It is removed when the phone disconnects (after a short reconnect grace) or the app quits.

**Security and privacy:**
- Pairing uses Noise XX with a six-digit numeric comparison or a one-time QR token, confirmed on
  both devices. Unpaired devices cannot send input.
- All input is end-to-end encrypted (ChaCha20-Poly1305). Replayed, stale or out-of-order input is
  rejected.
- If input stops for 250 ms, every control is released, so nothing stays held.
- No network service outside the local network, no accounts, no analytics.

**User control:** the user installs the app, pairs each phone, and can disconnect or forget a phone
at any time. A menu-bar/status indicator shows when a virtual controller is active.

**Technical contact:** [name, email]

---

After approval: add the entitlement to the provisioning profile, enable it in
`hosts/app/macos/entitlements.plist`, sign with Developer ID, notarize, then run acceptance A7
(`docs/TEST_PLAN.md`). Some titles that only use GameController.framework may still ignore virtual
devices — test per title and document; do not promise universal support.
