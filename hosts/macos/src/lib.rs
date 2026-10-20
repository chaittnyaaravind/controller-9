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
#[cfg(target_os = "macos")]
mod keyboard;
#[cfg(target_os = "macos")]
pub use keyboard::{KeyboardBackend, MARK as KEYBOARD_EVENT_MARK};

/// The backend the macOS hosts use: a real virtual gamepad when Apple's entitlement is present,
/// otherwise keyboard & mouse mode (Accessibility permission only).
#[cfg(target_os = "macos")]
pub fn native_backend() -> std::sync::Arc<dyn nine_host_core::backend::Backend> {
    use nine_host_core::backend::Backend;
    if IoHidBackend.status().available { std::sync::Arc::new(IoHidBackend) } else { std::sync::Arc::new(KeyboardBackend) }
}
