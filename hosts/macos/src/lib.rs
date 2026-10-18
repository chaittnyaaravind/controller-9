//! macOS virtual gamepads via `IOHIDUserDevice`.
//!
//! Creating a user-space HID device requires the Apple-granted entitlement
//! `com.apple.developer.hid.virtual.device` (verified: without it the call returns NULL on
//! macOS 27.0.1, see `poc/vhid_probe.c`). Without the entitlement this backend reports
//! `Blocked` — it never pretends to inject input. Even with the entitlement, games built on
//! GameController.framework may ignore virtual HID devices; SDL/IOKit-HID games are the target.
#[cfg(target_os = "macos")]
mod iohid;
#[cfg(target_os = "macos")]
pub use iohid::IoHidBackend;
