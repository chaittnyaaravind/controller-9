//! Noise_XX_25519_ChaChaPoly_SHA256 (Noise Protocol Framework rev. 34).
//!
//! Small, dependency-light implementation so the Kotlin and Swift ports can mirror it line for
//! line. Correctness is checked in tests by interoperating with the `snow` crate in both roles,
//! and the ports reproduce `protocol/vectors/noise_xx.json` byte for byte.
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::ChaCha20Poly1305;
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

pub const PROTOCOL_NAME: &[u8] = b"Noise_XX_25519_ChaChaPoly_SHA256";
pub const PROLOGUE: &[u8] = b"9ctl/1";
pub const DH_LEN: usize = 32;
pub const TAG_LEN: usize = 16;
pub const MAX_MESSAGE: usize = 65535;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum NoiseError {
    #[error("handshake message has the wrong length")]
    BadLength,
    #[error("decryption failed")]
    Decrypt,
    #[error("handshake used out of order")]
    State,
    #[error("nonce exhausted")]
    Nonce,
    #[error("invalid (low-order) public key")]
    BadKey,
}

pub struct KeyPair {
    secret: StaticSecret,
    pub public: [u8; 32],
}

impl KeyPair {
    pub fn generate() -> Self { Self::from_secret(crate::random_bytes::<32>()) }
    pub fn from_secret(bytes: [u8; 32]) -> Self {
        let secret = StaticSecret::from(bytes);
        let public = PublicKey::from(&secret).to_bytes();
        Self { secret, public }
    }
    pub fn secret_bytes(&self) -> [u8; 32] { self.secret.to_bytes() }
    fn dh(&self, remote: &[u8; 32]) -> Result<[u8; 32], NoiseError> {
        let out = self.secret.diffie_hellman(&PublicKey::from(*remote)).to_bytes();
        if out == [0u8; 32] { return Err(NoiseError::BadKey); }
        Ok(out)
    }
}

fn hmac(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut m = <Hmac<Sha256> as hmac::KeyInit>::new_from_slice(key).expect("hmac accepts any key length");
    for p in parts { m.update(p); }
    m.finalize().into_bytes().into()
}

/// Noise HKDF with two outputs.
pub fn hkdf2(ck: &[u8; 32], ikm: &[u8]) -> ([u8; 32], [u8; 32]) {
    let mut temp = hmac(ck, &[ikm]);
    let o1 = hmac(&temp, &[&[1u8]]);
    let o2 = hmac(&temp, &[&o1, &[2u8]]);
    temp.zeroize();
    (o1, o2)
}

fn nonce(n: u64) -> [u8; 12] {
    let mut b = [0u8; 12];
    b[4..].copy_from_slice(&n.to_le_bytes());
    b
}

pub struct CipherState {
    k: Option<[u8; 32]>,
    n: u64,
}

impl Drop for CipherState {
    fn drop(&mut self) { if let Some(k) = self.k.as_mut() { k.zeroize(); } }
}

impl CipherState {
    fn empty() -> Self { Self { k: None, n: 0 } }
    pub fn new(k: [u8; 32]) -> Self { Self { k: Some(k), n: 0 } }
    pub fn nonce(&self) -> u64 { self.n }

    pub fn encrypt_with_ad(&mut self, ad: &[u8], pt: &[u8]) -> Result<Vec<u8>, NoiseError> {
        let Some(k) = self.k else { return Ok(pt.to_vec()) };
        if self.n == u64::MAX { return Err(NoiseError::Nonce); }
        let c = ChaCha20Poly1305::new((&k).into());
        let out = c.encrypt((&nonce(self.n)).into(), Payload { msg: pt, aad: ad }).map_err(|_| NoiseError::Decrypt)?;
        self.n += 1;
        Ok(out)
    }

    pub fn decrypt_with_ad(&mut self, ad: &[u8], ct: &[u8]) -> Result<Vec<u8>, NoiseError> {
        let Some(k) = self.k else { return Ok(ct.to_vec()) };
        if self.n == u64::MAX { return Err(NoiseError::Nonce); }
        let c = ChaCha20Poly1305::new((&k).into());
        let out = c.decrypt((&nonce(self.n)).into(), Payload { msg: ct, aad: ad }).map_err(|_| NoiseError::Decrypt)?;
        self.n += 1;
        Ok(out)
    }
}

struct SymmetricState {
    cs: CipherState,
    ck: [u8; 32],
    h: [u8; 32],
}

impl SymmetricState {
    fn new() -> Self {
        let mut h = [0u8; 32];
        h[..PROTOCOL_NAME.len()].copy_from_slice(PROTOCOL_NAME); // exactly 32 bytes: no hashing
        Self { cs: CipherState::empty(), ck: h, h }
    }
    fn mix_hash(&mut self, data: &[u8]) {
        let mut d = Sha256::new();
        d.update(self.h);
        d.update(data);
        self.h = d.finalize().into();
    }
    fn mix_key(&mut self, ikm: &[u8]) {
        let (ck, k) = hkdf2(&self.ck, ikm);
        self.ck = ck;
        self.cs = CipherState::new(k);
    }
    fn encrypt_and_hash(&mut self, pt: &[u8]) -> Result<Vec<u8>, NoiseError> {
        let ct = self.cs.encrypt_with_ad(&self.h, pt)?;
        self.mix_hash(&ct);
        Ok(ct)
    }
    fn decrypt_and_hash(&mut self, ct: &[u8]) -> Result<Vec<u8>, NoiseError> {
        let pt = self.cs.decrypt_with_ad(&self.h, ct)?;
        self.mix_hash(ct);
        Ok(pt)
    }
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Role { Initiator, Responder }

/// Completed handshake: transport ciphers plus channel-binding data.
pub struct Transport {
    pub send: CipherState,
    pub recv: CipherState,
    /// Final handshake hash `h` — binds pairing proofs and the SAS to this session.
    pub handshake_hash: [u8; 32],
    pub remote_static: [u8; 32],
}

impl Transport {
    pub fn encrypt(&mut self, pt: &[u8]) -> Result<Vec<u8>, NoiseError> { self.send.encrypt_with_ad(&[], pt) }
    pub fn decrypt(&mut self, ct: &[u8]) -> Result<Vec<u8>, NoiseError> { self.recv.decrypt_with_ad(&[], ct) }
}

/// XX handshake state machine. Message order: I write 1, R write 2, I write 3.
pub struct Handshake {
    role: Role,
    ss: SymmetricState,
    s: KeyPair,
    e: Option<KeyPair>,
    fixed_e: Option<[u8; 32]>,
    rs: Option<[u8; 32]>,
    re: Option<[u8; 32]>,
    step: u8,
}

impl Handshake {
    pub fn new(role: Role, s: KeyPair) -> Self {
        let mut ss = SymmetricState::new();
        ss.mix_hash(PROLOGUE);
        Self { role, ss, s, e: None, fixed_e: None, rs: None, re: None, step: 0 }
    }

    /// Test vectors only: use a fixed ephemeral private key.
    pub fn with_fixed_ephemeral(mut self, e: [u8; 32]) -> Self { self.fixed_e = Some(e); self }

    pub fn is_finished(&self) -> bool { self.step == 3 }
    pub fn remote_static(&self) -> Option<[u8; 32]> { self.rs }

    fn gen_e(&mut self) -> [u8; 32] {
        let kp = match self.fixed_e.take() { Some(b) => KeyPair::from_secret(b), None => KeyPair::generate() };
        let p = kp.public;
        self.e = Some(kp);
        p
    }

    fn my_turn(&self) -> bool {
        matches!((self.role, self.step), (Role::Initiator, 0) | (Role::Responder, 1) | (Role::Initiator, 2))
    }

    pub fn write_message(&mut self, payload: &[u8]) -> Result<Vec<u8>, NoiseError> {
        if !self.my_turn() { return Err(NoiseError::State); }
        let mut out = Vec::with_capacity(96 + payload.len() + TAG_LEN);
        match self.step {
            0 => { // -> e
                let e = self.gen_e();
                self.ss.mix_hash(&e);
                out.extend_from_slice(&e);
            }
            1 => { // <- e, ee, s, es
                let e = self.gen_e();
                self.ss.mix_hash(&e);
                out.extend_from_slice(&e);
                let re = self.re.ok_or(NoiseError::State)?;
                let ee = self.e.as_ref().unwrap().dh(&re)?;
                self.ss.mix_key(&ee);
                let s = self.ss.encrypt_and_hash(&self.s.public)?;
                out.extend_from_slice(&s);
                let es = self.s.dh(&re)?;
                self.ss.mix_key(&es);
            }
            2 => { // -> s, se
                let s = self.ss.encrypt_and_hash(&self.s.public)?;
                out.extend_from_slice(&s);
                let se = self.s.dh(&self.re.ok_or(NoiseError::State)?)?;
                self.ss.mix_key(&se);
            }
            _ => return Err(NoiseError::State),
        }
        out.extend_from_slice(&self.ss.encrypt_and_hash(payload)?);
        if out.len() > MAX_MESSAGE { return Err(NoiseError::BadLength); }
        self.step += 1;
        Ok(out)
    }

    pub fn read_message(&mut self, msg: &[u8]) -> Result<Vec<u8>, NoiseError> {
        if self.my_turn() || self.step > 2 { return Err(NoiseError::State); }
        let mut rest = msg;
        let take = |rest: &mut &[u8], n: usize| -> Result<Vec<u8>, NoiseError> {
            if rest.len() < n { return Err(NoiseError::BadLength); }
            let (a, b) = rest.split_at(n);
            *rest = b;
            Ok(a.to_vec())
        };
        match self.step {
            0 => { // responder reads -> e
                let re: [u8; 32] = take(&mut rest, DH_LEN)?.try_into().unwrap();
                self.ss.mix_hash(&re);
                self.re = Some(re);
            }
            1 => { // initiator reads <- e, ee, s, es
                let re: [u8; 32] = take(&mut rest, DH_LEN)?.try_into().unwrap();
                self.ss.mix_hash(&re);
                self.re = Some(re);
                let ee = self.e.as_ref().ok_or(NoiseError::State)?.dh(&re)?;
                self.ss.mix_key(&ee);
                let enc_s = take(&mut rest, DH_LEN + TAG_LEN)?;
                let rs: [u8; 32] = self.ss.decrypt_and_hash(&enc_s)?.try_into().map_err(|_| NoiseError::BadLength)?;
                self.rs = Some(rs);
                let es = self.e.as_ref().unwrap().dh(&rs)?;
                self.ss.mix_key(&es);
            }
            2 => { // responder reads -> s, se
                let enc_s = take(&mut rest, DH_LEN + TAG_LEN)?;
                let rs: [u8; 32] = self.ss.decrypt_and_hash(&enc_s)?.try_into().map_err(|_| NoiseError::BadLength)?;
                self.rs = Some(rs);
                let se = self.e.as_ref().ok_or(NoiseError::State)?.dh(&rs)?;
                self.ss.mix_key(&se);
            }
            _ => return Err(NoiseError::State),
        }
        let payload = self.ss.decrypt_and_hash(rest)?;
        self.step += 1;
        Ok(payload)
    }

    pub fn into_transport(self) -> Result<Transport, NoiseError> {
        if self.step != 3 { return Err(NoiseError::State); }
        let (k1, k2) = hkdf2(&self.ss.ck, &[]);
        let (send, recv) = match self.role {
            Role::Initiator => (CipherState::new(k1), CipherState::new(k2)),
            Role::Responder => (CipherState::new(k2), CipherState::new(k1)),
        };
        Ok(Transport { send, recv, handshake_hash: self.ss.h, remote_static: self.rs.ok_or(NoiseError::State)? })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run_pair(i: &mut Handshake, r: &mut Handshake) {
        let m1 = i.write_message(b"{\"proto\":[1]}").unwrap();
        assert_eq!(r.read_message(&m1).unwrap(), b"{\"proto\":[1]}");
        let m2 = r.write_message(b"host").unwrap();
        assert_eq!(i.read_message(&m2).unwrap(), b"host");
        let m3 = i.write_message(b"phone").unwrap();
        assert_eq!(r.read_message(&m3).unwrap(), b"phone");
    }

    #[test]
    fn self_roundtrip_and_transport() {
        let (is, rs) = (KeyPair::generate(), KeyPair::generate());
        let (ip, rp) = (is.public, rs.public);
        let mut i = Handshake::new(Role::Initiator, is);
        let mut r = Handshake::new(Role::Responder, rs);
        run_pair(&mut i, &mut r);
        let (mut ti, mut tr) = (i.into_transport().unwrap(), r.into_transport().unwrap());
        assert_eq!(ti.remote_static, rp);
        assert_eq!(tr.remote_static, ip);
        assert_eq!(ti.handshake_hash, tr.handshake_hash);
        let ct = ti.encrypt(b"hello").unwrap();
        assert_eq!(tr.decrypt(&ct).unwrap(), b"hello");
        let ct = tr.encrypt(b"back").unwrap();
        assert_eq!(ti.decrypt(&ct).unwrap(), b"back");
        // replayed transport message must fail (nonce advanced)
        assert!(ti.decrypt(&ct).is_err());
    }

    #[test]
    fn tampered_message_rejected() {
        let mut i = Handshake::new(Role::Initiator, KeyPair::generate());
        let mut r = Handshake::new(Role::Responder, KeyPair::generate());
        let m1 = i.write_message(b"").unwrap();
        r.read_message(&m1).unwrap();
        let mut m2 = r.write_message(b"x").unwrap();
        m2[40] ^= 1;
        assert_eq!(i.read_message(&m2), Err(NoiseError::Decrypt));
    }

    #[test]
    fn out_of_order_rejected() {
        let mut r = Handshake::new(Role::Responder, KeyPair::generate());
        assert_eq!(r.write_message(b""), Err(NoiseError::State));
    }

    const NAME: &str = "Noise_XX_25519_ChaChaPoly_SHA256";

    #[test]
    fn interop_we_initiate_snow_responds() {
        let rk = KeyPair::generate();
        let mut snow_r = snow::Builder::new(NAME.parse().unwrap())
            .local_private_key(&rk.secret_bytes()).unwrap()
            .prologue(PROLOGUE).unwrap()
            .build_responder().unwrap();
        let mut i = Handshake::new(Role::Initiator, KeyPair::generate());
        let mut buf = [0u8; 1024];
        let mut pl = [0u8; 1024];
        let m1 = i.write_message(b"one").unwrap();
        let n = snow_r.read_message(&m1, &mut pl).unwrap();
        assert_eq!(&pl[..n], b"one");
        let n = snow_r.write_message(b"two", &mut buf).unwrap();
        assert_eq!(i.read_message(&buf[..n]).unwrap(), b"two");
        let m3 = i.write_message(b"three").unwrap();
        let n = snow_r.read_message(&m3, &mut pl).unwrap();
        assert_eq!(&pl[..n], b"three");
        let hh = snow_r.get_handshake_hash().to_vec();
        let mut snow_t = snow_r.into_transport_mode().unwrap();
        let mut t = i.into_transport().unwrap();
        assert_eq!(t.handshake_hash.to_vec(), hh);
        assert_eq!(t.remote_static, rk.public);
        let ct = t.encrypt(b"frame").unwrap();
        let n = snow_t.read_message(&ct, &mut pl).unwrap();
        assert_eq!(&pl[..n], b"frame");
        let n = snow_t.write_message(b"reply", &mut buf).unwrap();
        assert_eq!(t.decrypt(&buf[..n]).unwrap(), b"reply");
    }

    #[test]
    fn interop_snow_initiates_we_respond() {
        let ik = KeyPair::generate();
        let mut snow_i = snow::Builder::new(NAME.parse().unwrap())
            .local_private_key(&ik.secret_bytes()).unwrap()
            .prologue(PROLOGUE).unwrap()
            .build_initiator().unwrap();
        let mut r = Handshake::new(Role::Responder, KeyPair::generate());
        let mut buf = [0u8; 1024];
        let mut pl = [0u8; 1024];
        let n = snow_i.write_message(b"a", &mut buf).unwrap();
        assert_eq!(r.read_message(&buf[..n]).unwrap(), b"a");
        let m2 = r.write_message(b"b").unwrap();
        let n = snow_i.read_message(&m2, &mut pl).unwrap();
        assert_eq!(&pl[..n], b"b");
        let n = snow_i.write_message(b"c", &mut buf).unwrap();
        assert_eq!(r.read_message(&buf[..n]).unwrap(), b"c");
        let hh = snow_i.get_handshake_hash().to_vec();
        let t = r.into_transport().unwrap();
        assert_eq!(t.handshake_hash.to_vec(), hh);
        assert_eq!(t.remote_static, ik.public);
    }
}
