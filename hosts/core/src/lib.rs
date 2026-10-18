//! Shared desktop host core. OS-specific code lives only in backend crates
//! (`hosts/linux`, `hosts/macos`, `hosts/windows`) that implement [`backend::Backend`].
pub mod backend;
pub mod client;
pub mod discovery;
pub mod hid;
pub mod host;
pub mod pairing;
pub mod store;
pub mod time;

pub use backend::{Backend, BackendKind, BackendStatus, VirtualPad};
pub use host::{Host, HostEvent, HostSnapshot};
pub use store::{HostConfig, Store};
