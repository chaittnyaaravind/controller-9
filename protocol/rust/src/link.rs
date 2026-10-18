//! Transport bindings (SPEC §8): stream records and BLE GATT segmentation.

pub const REC_CONTROL: u8 = 0x01;
pub const REC_DATAGRAM: u8 = 0x02;
pub const STREAM_TCP_PORT: u16 = 47902;

pub const BLE_SERVICE: &str = "c9c90001-1c3a-4e9d-8f2b-6c7a0b5d4e10";
pub const BLE_CONTROL_UP: &str = "c9c90002-1c3a-4e9d-8f2b-6c7a0b5d4e10";
pub const BLE_CONTROL_DOWN: &str = "c9c90003-1c3a-4e9d-8f2b-6c7a0b5d4e10";
pub const BLE_INPUT_UP: &str = "c9c90004-1c3a-4e9d-8f2b-6c7a0b5d4e10";
pub const BLE_INPUT_DOWN: &str = "c9c90005-1c3a-4e9d-8f2b-6c7a0b5d4e10";
pub const BLE_PREFERRED_MTU: usize = 185;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Record { Control(Vec<u8>), Datagram(Vec<u8>) }

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum LinkError {
    #[error("record length out of range")] Length,
    #[error("unknown record kind {0}")] Kind(u8),
}

impl Record {
    pub fn encode(&self) -> Vec<u8> {
        let (k, b) = match self { Record::Control(b) => (REC_CONTROL, b), Record::Datagram(b) => (REC_DATAGRAM, b) };
        assert!(!b.is_empty() && b.len() <= u16::MAX as usize);
        let mut out = Vec::with_capacity(3 + b.len());
        out.push(k);
        out.extend_from_slice(&(b.len() as u16).to_be_bytes());
        out.extend_from_slice(b);
        out
    }
}

/// Incremental record parser for a byte stream.
#[derive(Default)]
pub struct RecordReader { buf: Vec<u8> }

impl RecordReader {
    pub fn push(&mut self, bytes: &[u8]) { self.buf.extend_from_slice(bytes); }
    /// Next complete record, `Ok(None)` if more bytes are needed.
    pub fn next_record(&mut self) -> Result<Option<Record>, LinkError> {
        if self.buf.len() < 3 { return Ok(None); }
        let kind = self.buf[0];
        let len = u16::from_be_bytes([self.buf[1], self.buf[2]]) as usize;
        if len == 0 { return Err(LinkError::Length); }
        if kind != REC_CONTROL && kind != REC_DATAGRAM { return Err(LinkError::Kind(kind)); }
        if self.buf.len() < 3 + len { return Ok(None); }
        let body = self.buf[3..3 + len].to_vec();
        self.buf.drain(..3 + len);
        Ok(Some(if kind == REC_CONTROL { Record::Control(body) } else { Record::Datagram(body) }))
    }
}

pub const SEG_FIRST: u8 = 0x80;
pub const SEG_LAST: u8 = 0x40;

/// Splits messages into characteristic values for one GATT characteristic.
#[derive(Default)]
pub struct Segmenter { counter: u8 }

impl Segmenter {
    /// `att_mtu` is the negotiated ATT MTU; each value is at most `att_mtu - 3` bytes.
    pub fn segment(&mut self, msg: &[u8], att_mtu: usize) -> Vec<Vec<u8>> {
        let chunk = att_mtu.saturating_sub(4).max(1);
        let pieces: Vec<&[u8]> = if msg.is_empty() { vec![&[]] } else { msg.chunks(chunk).collect() };
        let n = pieces.len();
        pieces.into_iter().enumerate().map(|(i, c)| {
            let mut hdr = self.counter & 0x3F;
            self.counter = self.counter.wrapping_add(1) & 0x3F;
            if i == 0 { hdr |= SEG_FIRST; }
            if i == n - 1 { hdr |= SEG_LAST; }
            let mut v = Vec::with_capacity(1 + c.len());
            v.push(hdr);
            v.extend_from_slice(c);
            v
        }).collect()
    }
}

/// Reassembles messages from characteristic values; drops partial messages on any gap.
#[derive(Default)]
pub struct Reassembler { expect: Option<u8>, partial: Option<Vec<u8>>, discarding: bool, max: usize, pub dropped: u64 }

impl Reassembler {
    pub fn new(max_message: usize) -> Self { Self { max: max_message, ..Default::default() } }
    pub fn push(&mut self, value: &[u8]) -> Option<Vec<u8>> {
        let (&hdr, chunk) = value.split_first()?;
        let ctr = hdr & 0x3F;
        let gap = self.expect.is_some_and(|e| e != ctr);
        self.expect = Some((ctr + 1) & 0x3F);
        if gap && self.partial.take().is_some() { self.dropped += 1; }
        if hdr & SEG_FIRST != 0 {
            if self.partial.take().is_some() { self.dropped += 1; }
            self.partial = Some(Vec::new());
            self.discarding = false;
        }
        let Some(p) = self.partial.as_mut() else {
            // Rest of an already-rejected message: ignore quietly; an orphan segment counts once.
            if !self.discarding { self.dropped += 1; }
            self.discarding = hdr & SEG_LAST == 0;
            return None;
        };
        p.extend_from_slice(chunk);
        if p.len() > self.max {
            self.partial = None;
            self.dropped += 1;
            self.discarding = hdr & SEG_LAST == 0;
            return None;
        }
        if hdr & SEG_LAST != 0 { return self.partial.take(); }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn records_roundtrip_split_anywhere() {
        let recs = [Record::Control(vec![1, 2, 3]), Record::Datagram(vec![9; 58]), Record::Control(vec![7; 300])];
        let bytes: Vec<u8> = recs.iter().flat_map(Record::encode).collect();
        for split in [1, 2, 3, 5, 64, bytes.len()] {
            let mut r = RecordReader::default();
            let mut got = Vec::new();
            for c in bytes.chunks(split) { r.push(c); while let Some(x) = r.next_record().unwrap() { got.push(x); } }
            assert_eq!(got, recs);
        }
        let mut r = RecordReader::default();
        r.push(&[0x07, 0, 1, 0]);
        assert_eq!(r.next_record(), Err(LinkError::Kind(7)));
        let mut r = RecordReader::default();
        r.push(&[0x01, 0, 0]);
        assert_eq!(r.next_record(), Err(LinkError::Length));
    }

    #[test]
    fn segmentation_mtu_and_gaps() {
        let msg: Vec<u8> = (0..200u8).collect();
        for mtu in [23, 62, 185, 517] {
            let mut s = Segmenter::default();
            let mut r = Reassembler::new(65535);
            let vals = s.segment(&msg, mtu);
            assert!(vals.iter().all(|v| v.len() <= mtu - 3));
            let mut out = None;
            for v in &vals { if let Some(m) = r.push(v) { out = Some(m); } }
            assert_eq!(out.unwrap(), msg);
        }
        // input datagram (58 bytes) fits one value at MTU 62
        assert_eq!(Segmenter::default().segment(&[0; 58], 62).len(), 1);
        // a lost middle segment drops the message, the next one still arrives
        let mut s = Segmenter::default();
        let mut r = Reassembler::new(65535);
        let a = s.segment(&msg, 23);
        let b = s.segment(&[5; 10], 23);
        for (i, v) in a.iter().enumerate() { if i != 3 { assert!(r.push(v).is_none()); } }
        assert_eq!(r.push(&b[0]), Some(vec![5; 10]));
        assert!(r.dropped >= 1);
        // counter wraps mod 64
        let mut s = Segmenter::default();
        let mut r = Reassembler::new(100);
        for i in 0..200u8 { for v in s.segment(&[i], 23) { assert_eq!(r.push(&v), Some(vec![i])); } }
        assert_eq!(r.dropped, 0);
    }

    #[test]
    fn oversized_message_is_dropped() {
        let mut s = Segmenter::default();
        let mut r = Reassembler::new(50);
        for v in s.segment(&[1; 100], 23) { assert!(r.push(&v).is_none()); }
        assert_eq!(r.dropped, 1);
    }
}
