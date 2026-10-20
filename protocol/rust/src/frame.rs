//! Encrypted UDP datagrams (SPEC §6).
use crate::input::{InputState, VALID_BUTTONS, VALID_FLAGS};
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::ChaCha20Poly1305;

pub const MAGIC: [u8; 2] = *b"9C";
pub const HEADER_LEN: usize = 16;
pub const TAG_LEN: usize = 16;
pub const MIN_DATAGRAM: usize = HEADER_LEN + TAG_LEN;
pub const MAX_DATAGRAM: usize = 256;

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum PacketType { Input = 1, Ping = 2, Pong = 3, Feedback = 4, Bye = 5 }

impl PacketType {
    pub fn from_u8(v: u8) -> Option<Self> {
        Some(match v { 1 => Self::Input, 2 => Self::Ping, 3 => Self::Pong, 4 => Self::Feedback, 5 => Self::Bye, _ => return None })
    }
}

#[derive(Debug, thiserror::Error, PartialEq, Eq, Clone, Copy)]
pub enum FrameError {
    #[error("datagram too short")] TooShort,
    #[error("datagram too long")] TooLong,
    #[error("bad magic")] BadMagic,
    #[error("unsupported version")] BadVersion,
    #[error("unknown packet type")] UnknownType,
    #[error("authentication failed")] Auth,
    #[error("payload length mismatch")] BadLength,
    #[error("field out of range")] OutOfRange,
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct Header { pub ptype: PacketType, pub session_id: u32, pub counter: u64 }

impl Header {
    pub fn encode(&self) -> [u8; HEADER_LEN] {
        let mut h = [0u8; HEADER_LEN];
        h[0..2].copy_from_slice(&MAGIC);
        h[2] = crate::PROTO_VERSION;
        h[3] = self.ptype as u8;
        h[4..8].copy_from_slice(&self.session_id.to_le_bytes());
        h[8..16].copy_from_slice(&self.counter.to_le_bytes());
        h
    }
    /// Parse the plaintext header without authenticating it (used only to find the session key).
    pub fn peek(d: &[u8]) -> Result<Header, FrameError> {
        if d.len() < MIN_DATAGRAM { return Err(FrameError::TooShort); }
        if d.len() > MAX_DATAGRAM { return Err(FrameError::TooLong); }
        if d[0..2] != MAGIC { return Err(FrameError::BadMagic); }
        if d[2] != crate::PROTO_VERSION { return Err(FrameError::BadVersion); }
        let ptype = PacketType::from_u8(d[3]).ok_or(FrameError::UnknownType)?;
        Ok(Header {
            ptype,
            session_id: u32::from_le_bytes(d[4..8].try_into().unwrap()),
            counter: u64::from_le_bytes(d[8..16].try_into().unwrap()),
        })
    }
}

fn nonce(counter: u64) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[4..].copy_from_slice(&counter.to_le_bytes());
    n
}

pub fn seal(key: &[u8; 32], header: Header, payload: &[u8]) -> Vec<u8> {
    let h = header.encode();
    let ct = ChaCha20Poly1305::new(key.into())
        .encrypt((&nonce(header.counter)).into(), Payload { msg: payload, aad: &h })
        .expect("chacha20poly1305 encrypt cannot fail for small inputs");
    let mut out = Vec::with_capacity(HEADER_LEN + ct.len());
    out.extend_from_slice(&h);
    out.extend_from_slice(&ct);
    out
}

/// Authenticate and decrypt. Returns the header and plaintext payload.
pub fn open(key: &[u8; 32], d: &[u8]) -> Result<(Header, Vec<u8>), FrameError> {
    let header = Header::peek(d)?;
    let pt = ChaCha20Poly1305::new(key.into())
        .decrypt((&nonce(header.counter)).into(), Payload { msg: &d[HEADER_LEN..], aad: &d[..HEADER_LEN] })
        .map_err(|_| FrameError::Auth)?;
    Ok((header, pt))
}

#[derive(Copy, Clone, Debug, PartialEq, Eq, Default)]
pub struct InputFrame { pub t_us: u64, pub state: InputState, pub flags: u16 }

impl InputFrame {
    pub const LEN: usize = 26;
    pub fn encode(&self) -> [u8; Self::LEN] {
        let s = &self.state;
        let mut b = [0u8; Self::LEN];
        b[0..8].copy_from_slice(&self.t_us.to_le_bytes());
        b[8..12].copy_from_slice(&s.buttons.to_le_bytes());
        b[12..14].copy_from_slice(&s.lx.to_le_bytes());
        b[14..16].copy_from_slice(&s.ly.to_le_bytes());
        b[16..18].copy_from_slice(&s.rx.to_le_bytes());
        b[18..20].copy_from_slice(&s.ry.to_le_bytes());
        b[20..22].copy_from_slice(&s.lt.to_le_bytes());
        b[22..24].copy_from_slice(&s.rt.to_le_bytes());
        b[24..26].copy_from_slice(&self.flags.to_le_bytes());
        b
    }
    pub fn decode(b: &[u8]) -> Result<Self, FrameError> {
        if b.len() != Self::LEN { return Err(FrameError::BadLength); }
        let u16_at = |i: usize| u16::from_le_bytes([b[i], b[i + 1]]);
        let i16_at = |i: usize| i16::from_le_bytes([b[i], b[i + 1]]);
        let f = InputFrame {
            t_us: u64::from_le_bytes(b[0..8].try_into().unwrap()),
            state: InputState {
                buttons: u32::from_le_bytes(b[8..12].try_into().unwrap()),
                lx: i16_at(12), ly: i16_at(14), rx: i16_at(16), ry: i16_at(18),
                lt: u16_at(20), rt: u16_at(22),
            },
            flags: u16_at(24),
        };
        let s = &f.state;
        if s.buttons & !VALID_BUTTONS != 0 || f.flags & !VALID_FLAGS != 0
            || [s.lx, s.ly, s.rx, s.ry].contains(&i16::MIN) {
            return Err(FrameError::OutOfRange);
        }
        Ok(f)
    }
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct Ping { pub t_us: u64, pub id: u32 }
impl Ping {
    pub const LEN: usize = 12;
    pub fn encode(&self) -> [u8; 12] {
        let mut b = [0u8; 12];
        b[..8].copy_from_slice(&self.t_us.to_le_bytes());
        b[8..].copy_from_slice(&self.id.to_le_bytes());
        b
    }
    pub fn decode(b: &[u8]) -> Result<Self, FrameError> {
        if b.len() != Self::LEN { return Err(FrameError::BadLength); }
        Ok(Ping { t_us: u64::from_le_bytes(b[..8].try_into().unwrap()), id: u32::from_le_bytes(b[8..].try_into().unwrap()) })
    }
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct Pong { pub t_us: u64, pub id: u32, pub host_rx_us: u64, pub host_tx_us: u64 }
impl Pong {
    pub const LEN: usize = 28;
    pub fn encode(&self) -> [u8; 28] {
        let mut b = [0u8; 28];
        b[..8].copy_from_slice(&self.t_us.to_le_bytes());
        b[8..12].copy_from_slice(&self.id.to_le_bytes());
        b[12..20].copy_from_slice(&self.host_rx_us.to_le_bytes());
        b[20..].copy_from_slice(&self.host_tx_us.to_le_bytes());
        b
    }
    pub fn decode(b: &[u8]) -> Result<Self, FrameError> {
        if b.len() != Self::LEN { return Err(FrameError::BadLength); }
        Ok(Pong {
            t_us: u64::from_le_bytes(b[..8].try_into().unwrap()),
            id: u32::from_le_bytes(b[8..12].try_into().unwrap()),
            host_rx_us: u64::from_le_bytes(b[12..20].try_into().unwrap()),
            host_tx_us: u64::from_le_bytes(b[20..].try_into().unwrap()),
        })
    }
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct Feedback { pub low: u16, pub high: u16, pub ms: u16 }
impl Feedback {
    pub const LEN: usize = 6;
    pub fn encode(&self) -> [u8; 6] {
        let mut b = [0u8; 6];
        b[..2].copy_from_slice(&self.low.to_le_bytes());
        b[2..4].copy_from_slice(&self.high.to_le_bytes());
        b[4..].copy_from_slice(&self.ms.to_le_bytes());
        b
    }
    pub fn decode(b: &[u8]) -> Result<Self, FrameError> {
        if b.len() != Self::LEN { return Err(FrameError::BadLength); }
        let f = Feedback { low: u16::from_le_bytes([b[0], b[1]]), high: u16::from_le_bytes([b[2], b[3]]), ms: u16::from_le_bytes([b[4], b[5]]) };
        if f.ms > 5000 { return Err(FrameError::OutOfRange); }
        Ok(f)
    }
}

/// Sender-side counter (starts at 1, strictly increasing).
#[derive(Debug, Default)]
pub struct Counter(u64);
impl Counter {
    pub fn bump(&mut self) -> u64 { self.0 += 1; self.0 }
}

/// What a [`ReplayWindow`] decided about one authenticated datagram.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Seen {
    /// First time this counter arrived: process it.
    New,
    /// Already received (the same datagram sent over another link, or a replay): drop.
    Duplicate,
    /// Older than the window: drop.
    TooOld,
}

/// Receiver-side replay protection for a session that may receive the same datagrams over several
/// links at once (SPEC §6.4, §8.5). A 64-counter sliding window (as in RFC 4303 §3.4.3): every
/// counter is processed at most once, and a counter more than 64 behind the newest is refused.
///
/// The window alone does not order *input*: the receiver applies an input frame only if its counter
/// is also greater than the last applied input's, so a late copy can never restore an older button,
/// trigger or stick state.
#[derive(Debug, Default, Clone)]
pub struct ReplayWindow { highest: u64, bitmap: u64, accepted: u64, duplicate: u64, too_old: u64, lost: u64 }

impl ReplayWindow {
    pub const SIZE: u64 = 64;

    /// Call only after AEAD authentication succeeded.
    pub fn check(&mut self, counter: u64) -> Seen {
        if counter == 0 { self.too_old += 1; return Seen::TooOld; }
        if counter > self.highest {
            let shift = counter - self.highest;
            // Counters that leave the window without ever arriving are lost.
            for off in Self::SIZE.saturating_sub(shift)..Self::SIZE {
                if self.highest > off && (self.bitmap >> off) & 1 == 0 { self.lost += 1; }
            }
            if shift > Self::SIZE && self.highest != 0 { self.lost += shift - Self::SIZE; }
            self.bitmap = if shift >= Self::SIZE { 0 } else { self.bitmap << shift };
            self.bitmap |= 1;
            self.highest = counter;
            self.accepted += 1;
            return Seen::New;
        }
        let off = self.highest - counter;
        if off >= Self::SIZE { self.too_old += 1; return Seen::TooOld; }
        if (self.bitmap >> off) & 1 == 1 { self.duplicate += 1; return Seen::Duplicate; }
        self.bitmap |= 1 << off;
        self.accepted += 1;
        Seen::New
    }
    pub fn highest(&self) -> u64 { self.highest }
    pub fn accepted(&self) -> u64 { self.accepted }
    pub fn duplicate(&self) -> u64 { self.duplicate }
    pub fn too_old(&self) -> u64 { self.too_old }
    /// Counters that slid out of the window without arriving on any link.
    pub fn lost(&self) -> u64 { self.lost }
}

/// Receiver-side freshness: accept only counters greater than the highest accepted one.
#[derive(Debug, Default, Clone)]
pub struct Freshness { highest: u64, accepted: u64, stale: u64, lost: u64 }

impl Freshness {
    /// Returns true when the frame is fresh. Call only after AEAD authentication succeeded.
    pub fn accept(&mut self, counter: u64) -> bool {
        if counter <= self.highest { self.stale += 1; return false; }
        if self.highest != 0 { self.lost += counter - self.highest - 1; }
        self.highest = counter;
        self.accepted += 1;
        true
    }
    pub fn highest(&self) -> u64 { self.highest }
    pub fn accepted(&self) -> u64 { self.accepted }
    pub fn stale(&self) -> u64 { self.stale }
    /// Counter gaps. Note: frames that arrive late after a newer one count as both a gap and stale.
    pub fn lost(&self) -> u64 { self.lost }
}

#[cfg(test)]
mod tests {
    use super::*;
    const K: [u8; 32] = [7u8; 32];

    fn frame() -> InputFrame {
        InputFrame { t_us: 123_456, state: InputState { buttons: 0b101, lx: -32767, ly: 32767, rx: 5, ry: -5, lt: 0, rt: 65535 }, flags: 1 }
    }

    #[test]
    fn roundtrip() {
        let h = Header { ptype: PacketType::Input, session_id: 0xdeadbeef, counter: 9 };
        let d = seal(&K, h, &frame().encode());
        assert_eq!(d.len(), HEADER_LEN + InputFrame::LEN + TAG_LEN);
        let (h2, pt) = open(&K, &d).unwrap();
        assert_eq!(h2, h);
        assert_eq!(InputFrame::decode(&pt).unwrap(), frame());
    }

    #[test]
    fn header_and_body_are_authenticated() {
        let h = Header { ptype: PacketType::Input, session_id: 1, counter: 1 };
        let d = seal(&K, h, &frame().encode());
        for i in 0..d.len() {
            let mut t = d.clone();
            t[i] ^= 0x01;
            assert!(open(&K, &t).is_err(), "bit flip at byte {i} accepted");
        }
        assert_eq!(open(&[8u8; 32], &d), Err(FrameError::Auth));
    }

    #[test]
    fn malformed() {
        assert_eq!(Header::peek(&[0u8; 10]), Err(FrameError::TooShort));
        assert_eq!(Header::peek(&[0u8; 300]), Err(FrameError::TooLong));
        let mut d = seal(&K, Header { ptype: PacketType::Ping, session_id: 1, counter: 1 }, &[0; 12]);
        d[3] = 99;
        assert_eq!(Header::peek(&d), Err(FrameError::UnknownType));
        d[2] = 0;
        assert_eq!(Header::peek(&d), Err(FrameError::BadVersion));
        d[0] = b'X';
        assert_eq!(Header::peek(&d), Err(FrameError::BadMagic));
    }

    #[test]
    fn bounds() {
        let mut f = frame();
        f.state.lx = i16::MIN;
        assert_eq!(InputFrame::decode(&f.encode()), Err(FrameError::OutOfRange));
        let mut f = frame();
        f.state.buttons = 1 << 20;
        assert_eq!(InputFrame::decode(&f.encode()), Err(FrameError::OutOfRange));
        let mut f = frame();
        f.flags = 0x80;
        assert_eq!(InputFrame::decode(&f.encode()), Err(FrameError::OutOfRange));
        assert_eq!(InputFrame::decode(&[0u8; 25]), Err(FrameError::BadLength));
    }

    #[test]
    fn freshness_rejects_stale_duplicate_and_counts_gaps() {
        let mut f = Freshness::default();
        assert!(f.accept(1));
        assert!(f.accept(2));
        assert!(!f.accept(2)); // duplicate / replay
        assert!(f.accept(5)); // 3, 4 lost
        assert!(!f.accept(4)); // late
        assert_eq!((f.accepted(), f.stale(), f.lost(), f.highest()), (3, 2, 2, 5));
    }

    #[test]
    fn replay_window_dedups_copies_from_two_links() {
        let mut w = ReplayWindow::default();
        // Link A delivers 1..=4; link B delivers the same datagrams later: all duplicates.
        for c in 1..=4 { assert_eq!(w.check(c), Seen::New); }
        for c in 1..=4 { assert_eq!(w.check(c), Seen::Duplicate); }
        // A per-link ping (counter 6) overtaken by input 7 on the other link is still processed once.
        assert_eq!(w.check(7), Seen::New);
        assert_eq!(w.check(6), Seen::New);
        assert_eq!(w.check(6), Seen::Duplicate);
        assert_eq!(w.check(0), Seen::TooOld);
        assert_eq!((w.accepted(), w.duplicate(), w.highest()), (6, 5, 7));
    }

    #[test]
    fn replay_window_refuses_old_and_counts_loss_once_out_of_window() {
        let mut w = ReplayWindow::default();
        assert_eq!(w.check(1), Seen::New);
        assert_eq!(w.check(3), Seen::New); // 2 missing but still inside the window
        assert_eq!(w.lost(), 0);
        // 2 slides out unseen (1) and 4..=36 never fit the new window (33).
        assert_eq!(w.check(100), Seen::New);
        assert_eq!(w.lost(), 34);
        assert_eq!(w.check(36), Seen::TooOld); // 100 - 36 = 64: outside
        assert_eq!(w.check(37), Seen::New); // late but inside: processed once
        assert_eq!(w.check(37), Seen::Duplicate);
        assert_eq!(w.too_old(), 1);
    }
}
