//! Control-channel messages and framing (SPEC §2, §4).
use serde::{Deserialize, Serialize};

pub const MAX_PREHANDSHAKE_FRAME: usize = 4096;

/// Base64 secret whose `Debug` never prints the value (secrets must not reach logs).
#[derive(Serialize, Deserialize, Clone, PartialEq, Eq)]
#[serde(transparent)]
pub struct SecretB64(pub String);
impl std::fmt::Debug for SecretB64 {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { f.write_str("<redacted>") }
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(tag = "t", rename_all = "snake_case", rename_all_fields = "camelCase")]
pub enum Control {
    // controller -> host
    Open {
        #[serde(default, skip_serializing_if = "Option::is_none")] resume_slot: Option<u8>,
        #[serde(default, skip_serializing_if = "Option::is_none")] profile: Option<String>,
    },
    PairQr { proof: String },
    PairCode,
    Nonce { n: String },
    CodeOk,
    CodeReject,
    /// Controller-measured diagnostics, sent about every 2 s while a session is open.
    Stats {
        #[serde(default, skip_serializing_if = "Option::is_none")] rtt_p50_ms: Option<f32>,
        #[serde(default, skip_serializing_if = "Option::is_none")] rtt_p95_ms: Option<f32>,
        #[serde(default, skip_serializing_if = "Option::is_none")] sensor_hz: Option<f32>,
        #[serde(default, skip_serializing_if = "Option::is_none")] send_hz: Option<f32>,
        #[serde(default, skip_serializing_if = "Option::is_none")] battery_pct: Option<u8>,
    },
    // host -> controller
    Session {
        slot: u8, session_id: u32, udp_port: u16, k_in: SecretB64, k_out: SecretB64,
        heartbeat_ms: u32, idle_heartbeat_ms: u32, timeout_ms: u32,
    },
    PairRequired { modes: Vec<String> },
    Commit { c: String },
    Reveal { n: String },
    Paired,
    PairFailed { reason: String },
    Rumble { low: u16, high: u16, ms: u16 },
    Error { code: String, #[serde(default, skip_serializing_if = "Option::is_none")] message: Option<String> },
    // both
    Bye { #[serde(default, skip_serializing_if = "Option::is_none")] reason: Option<String> },
}

impl Control {
    pub fn to_json(&self) -> Vec<u8> { serde_json::to_vec(self).expect("control messages always serialize") }
    pub fn from_json(b: &[u8]) -> Result<Self, serde_json::Error> { serde_json::from_slice(b) }
    pub fn error(code: &str) -> Self { Control::Error { code: code.into(), message: None } }
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct InitHello { pub proto: Vec<u8> }

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct HostHello {
    pub proto: u8, pub name: String, pub id: String, pub os: String,
    /// Supported bindings (SPEC §8): "ip", "stream", "ble". Additive; absent on older hosts.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub links: Vec<String>,
    /// The host's local IPv4 addresses (all interfaces), so controllers can fail over between
    /// Wi-Fi, hotspot, USB tethering and Wi-Fi Direct. Additive.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub addrs: Vec<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct ControllerHello { pub proto: u8, pub name: String, pub platform: String, pub app: String }

/// `len:u16be || bytes`.
pub fn frame(bytes: &[u8]) -> Vec<u8> {
    assert!(!bytes.is_empty() && bytes.len() <= u16::MAX as usize);
    let mut out = Vec::with_capacity(2 + bytes.len());
    out.extend_from_slice(&(bytes.len() as u16).to_be_bytes());
    out.extend_from_slice(bytes);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_shapes_match_spec() {
        assert_eq!(String::from_utf8(Control::Open { resume_slot: Some(2), profile: None }.to_json()).unwrap(), r#"{"t":"open","resumeSlot":2}"#);
        assert_eq!(String::from_utf8(Control::PairCode.to_json()).unwrap(), r#"{"t":"pair_code"}"#);
        let s = Control::Session { slot: 1, session_id: 7, udp_port: 47900, k_in: SecretB64("a".into()), k_out: SecretB64("b".into()), heartbeat_ms: 50, idle_heartbeat_ms: 200, timeout_ms: 250 };
        let j = String::from_utf8(s.to_json()).unwrap();
        assert!(j.contains(r#""sessionId":7"#) && j.contains(r#""kIn":"a""#) && j.contains(r#""t":"session""#));
        assert!(!format!("{s:?}").contains("\"a\""), "secrets must be redacted in Debug");
        assert_eq!(Control::from_json(br#"{"t":"open","extra":1}"#).unwrap(), Control::Open { resume_slot: None, profile: None });
        assert!(Control::from_json(br#"{"t":"launch_missiles"}"#).is_err());
        assert!(Control::from_json(b"not json").is_err());
    }
}
