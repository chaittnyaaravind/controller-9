# ADR 0009 — macOS: IOHIDUserDevice behind Apple's entitlement; report Blocked until granted

**Status:** accepted (2026-10-17); **externally blocked**

**Evidence.** `hosts/macos/poc/vhid_probe.c` and the backend's own test: on macOS 27.0.1 an
unentitled `IOHIDUserDeviceCreateWithProperties` returns NULL. The backend therefore reports
`Blocked` with the remedy and never claims to inject input; pairing, diagnostics and the input
pipeline still work. Even when entitled, GameController.framework titles may ignore virtual HID
devices; SDL/IOKit-HID titles are the target. See `docs/platforms/macos.md` for the route.
