//! 9-controller protocol v1 — reference implementation. See `protocol/SPEC.md`.
pub mod control;
pub mod frame;
pub mod input;
pub mod motion;
pub mod noise;
pub mod pairing;
pub mod profile;
pub mod stats;

pub const PROTO_VERSION: u8 = 1;
pub const DEFAULT_TCP_PORT: u16 = 47901;
pub const DEFAULT_UDP_PORT: u16 = 47900;
pub const SERVICE_TYPE: &str = "_ninectl._tcp.local.";
pub const MAX_SLOTS: usize = 4;

/// Fill `buf` from the OS CSPRNG. Panics only if the OS has no entropy source.
pub fn random_bytes<const N: usize>() -> [u8; N] {
    let mut b = [0u8; N];
    getrandom::fill(&mut b).expect("OS random source unavailable");
    b
}

pub fn host_id(static_pub: &[u8; 32]) -> String {
    use sha2::{Digest, Sha256};
    Sha256::digest(static_pub)[..8].iter().map(|b| format!("{b:02x}")).collect()
}
