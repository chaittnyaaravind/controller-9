//! Diagnostics: RFC 3550 interarrival jitter and rolling percentiles.

/// RFC 3550 §6.4.1 jitter estimator. Sender/receiver clock offset cancels out.
#[derive(Debug, Default, Clone)]
pub struct Jitter { j: f64, prev_transit: Option<i64> }
impl Jitter {
    pub fn update(&mut self, send_us: u64, recv_us: u64) {
        let transit = recv_us as i64 - send_us as i64;
        if let Some(p) = self.prev_transit {
            self.j += ((transit - p).abs() as f64 - self.j) / 16.0;
        }
        self.prev_transit = Some(transit);
    }
    pub fn ms(&self) -> f64 { self.j / 1000.0 }
}

/// Fixed-size ring of samples with nearest-rank percentiles.
#[derive(Debug, Clone)]
pub struct Window { buf: Vec<f64>, cap: usize, next: usize, total: u64 }
impl Window {
    pub fn new(cap: usize) -> Self { Self { buf: Vec::with_capacity(cap), cap, next: 0, total: 0 } }
    pub fn push(&mut self, v: f64) {
        if self.buf.len() < self.cap { self.buf.push(v) } else { self.buf[self.next] = v }
        self.next = (self.next + 1) % self.cap;
        self.total += 1;
    }
    pub fn len(&self) -> usize { self.buf.len() }
    pub fn is_empty(&self) -> bool { self.buf.is_empty() }
    pub fn total(&self) -> u64 { self.total }
    pub fn percentile(&self, p: f64) -> Option<f64> {
        if self.buf.is_empty() { return None; }
        let mut s = self.buf.clone();
        s.sort_by(|a, b| a.total_cmp(b));
        let rank = ((p / 100.0) * s.len() as f64).ceil().max(1.0) as usize;
        Some(s[rank.min(s.len()) - 1])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn percentiles() {
        let mut w = Window::new(100);
        for i in 1..=100 { w.push(i as f64); }
        assert_eq!(w.percentile(50.0), Some(50.0));
        assert_eq!(w.percentile(95.0), Some(95.0));
        assert_eq!(w.percentile(100.0), Some(100.0));
        w.push(1000.0); // evicts 1
        assert_eq!(w.percentile(100.0), Some(1000.0));
        assert_eq!(Window::new(4).percentile(50.0), None);
    }
    #[test]
    fn jitter_zero_for_constant_delay() {
        let mut j = Jitter::default();
        for i in 0..100u64 { j.update(i * 8333, i * 8333 + 5000); }
        assert_eq!(j.ms(), 0.0);
    }
}
