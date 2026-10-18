//! Pairing primitives (SPEC §5): QR proof, nonce commitment, 6-digit SAS, pairing URI.
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64URL;
use base64::Engine;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

pub fn qr_proof(token: &[u8; 16], handshake_hash: &[u8; 32]) -> [u8; 32] {
    let mut m = <Hmac<Sha256> as hmac::KeyInit>::new_from_slice(token).unwrap();
    m.update(b"9ctl-qr");
    m.update(handshake_hash);
    m.finalize().into_bytes().into()
}

pub fn verify_qr_proof(token: &[u8; 16], handshake_hash: &[u8; 32], proof: &[u8]) -> bool {
    proof.len() == 32 && bool::from(qr_proof(token, handshake_hash).ct_eq(proof))
}

pub fn commitment(handshake_hash: &[u8; 32], nb: &[u8; 16]) -> [u8; 32] {
    Sha256::new().chain_update(b"9ctl-commit").chain_update(handshake_hash).chain_update(nb).finalize().into()
}

pub fn verify_commitment(handshake_hash: &[u8; 32], nb: &[u8; 16], c: &[u8]) -> bool {
    c.len() == 32 && bool::from(commitment(handshake_hash, nb).ct_eq(c))
}

/// Six-digit short authentication string, 0..=999_999.
pub fn sas_code(handshake_hash: &[u8; 32], na: &[u8; 16], nb: &[u8; 16]) -> u32 {
    let d = Sha256::new().chain_update(b"9ctl-sas").chain_update(handshake_hash).chain_update(na).chain_update(nb).finalize();
    u32::from_be_bytes([d[0], d[1], d[2], d[3]]) % 1_000_000
}

pub fn format_code(code: u32) -> String {
    let s = format!("{code:06}");
    format!("{} {}", &s[..3], &s[3..])
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairUri {
    pub addr: String,
    pub port: u16,
    pub host_static: [u8; 32],
    pub token: [u8; 16],
    pub name: String,
    /// Extra addresses of the host (other interfaces: hotspot, USB tethering, Ethernet). SPEC §8.1.
    pub extra: Vec<String>,
}

fn pct_encode(s: &str) -> String {
    s.bytes().map(|b| match b {
        b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
        _ => format!("%{b:02X}"),
    }).collect()
}

fn pct_decode(s: &str) -> Option<String> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            let hex = std::str::from_utf8(b.get(i + 1..i + 3)?).ok()?;
            out.push(u8::from_str_radix(hex, 16).ok()?);
            i += 3;
        } else { out.push(b[i]); i += 1; }
    }
    String::from_utf8(out).ok()
}

impl PairUri {
    pub fn to_uri(&self) -> String {
        let mut u = format!("ninectl://pair?v=1&a={}&p={}&k={}&t={}&n={}",
            pct_encode(&self.addr), self.port, B64URL.encode(self.host_static), B64URL.encode(self.token), pct_encode(&self.name));
        if !self.extra.is_empty() {
            u.push_str("&x=");
            u.push_str(&self.extra.iter().map(|a| pct_encode(a)).collect::<Vec<_>>().join(","));
        }
        u
    }

    pub fn parse(uri: &str) -> Option<PairUri> {
        let q = uri.strip_prefix("ninectl://pair?")?;
        let (mut v, mut a, mut p, mut k, mut t, mut n) = (None, None, None, None, None, None);
        let mut extra = Vec::new();
        for kv in q.split('&') {
            let (key, val) = kv.split_once('=')?;
            match key {
                "v" => v = Some(val),
                "a" => a = pct_decode(val),
                "p" => p = val.parse::<u16>().ok(),
                "k" => k = B64URL.decode(val).ok(),
                "t" => t = B64URL.decode(val).ok(),
                "n" => n = pct_decode(val),
                "x" => extra = val.split(',').filter(|s| !s.is_empty()).filter_map(pct_decode).take(8).collect(),
                _ => {} // unknown keys ignored for forward compatibility
            }
        }
        if v? != "1" { return None; }
        Some(PairUri {
            addr: a?, port: p?, host_static: k?.try_into().ok()?, token: t?.try_into().ok()?,
            name: n.unwrap_or_default(), extra,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uri_roundtrip_and_rejects() {
        let u = PairUri { addr: "192.168.1.20".into(), port: 47901, host_static: [3; 32], token: [9; 16], name: "Studio PC & co".into(), extra: vec![] };
        let multi = PairUri { extra: vec!["172.20.10.2".into(), "192.168.42.10".into()], ..u.clone() };
        assert_eq!(PairUri::parse(&multi.to_uri()), Some(multi.clone()));
        assert!(multi.to_uri().ends_with("&x=172.20.10.2,192.168.42.10"));
        assert_eq!(PairUri::parse(&u.to_uri()), Some(u.clone()));
        assert!(PairUri::parse(&u.to_uri().replace("v=1", "v=2")).is_none());
        assert!(PairUri::parse("https://evil/pair?v=1").is_none());
        assert!(PairUri::parse(&u.to_uri().replace("&t=", "&x=")).is_none());
    }

    #[test]
    fn sas_and_commitment() {
        let h = [1u8; 32];
        let (na, nb) = ([2u8; 16], [3u8; 16]);
        let c = commitment(&h, &nb);
        assert!(verify_commitment(&h, &nb, &c));
        assert!(!verify_commitment(&h, &[4u8; 16], &c));
        let code = sas_code(&h, &na, &nb);
        assert!(code < 1_000_000);
        assert_ne!(code, sas_code(&[0u8; 32], &na, &nb));
        assert_eq!(format_code(42), "000 042");
    }

    #[test]
    fn qr_proof_binds_session() {
        let t = [5u8; 16];
        let p = qr_proof(&t, &[1u8; 32]);
        assert!(verify_qr_proof(&t, &[1u8; 32], &p));
        assert!(!verify_qr_proof(&t, &[2u8; 32], &p));
        assert!(!verify_qr_proof(&[6u8; 16], &[1u8; 32], &p));
        assert!(!verify_qr_proof(&t, &[1u8; 32], &p[..31]));
    }
}
