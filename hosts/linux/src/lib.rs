//! Linux virtual gamepads through `/dev/uinput` (kernel evdev). See `hosts/linux/README.md`.
#[cfg(target_os = "linux")]
mod uinput;
#[cfg(target_os = "linux")]
pub use uinput::{Identity, UinputBackend};
