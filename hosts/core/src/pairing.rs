//! Pairing window, QR tokens, attempt limits and lockout (SPEC §5.3).
use nine_proto::pairing::verify_qr_proof;
use std::collections::VecDeque;
use std::time::{Duration, Instant};

pub const WINDOW: Duration = Duration::from_secs(120);
pub const TOKEN_TTL: Duration = Duration::from_secs(300);
pub const MAX_ATTEMPTS_PER_MIN: usize = 5;
pub const MAX_REJECTS: u32 = 3;
pub const LOCKOUT: Duration = Duration::from_secs(60);

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Refusal { Closed, RateLimited, Locked }

impl Refusal {
    pub fn code(self) -> &'static str {
        match self { Refusal::Closed => "pairing_closed", Refusal::RateLimited | Refusal::Locked => "rate_limited" }
    }
}

#[derive(Default)]
pub struct Pairing {
    open_until: Option<Instant>,
    token: Option<([u8; 16], Instant)>,
    attempts: VecDeque<Instant>,
    rejects: u32,
    locked_until: Option<Instant>,
}

impl Pairing {
    /// Open the window and mint a fresh one-time QR token (replacing any previous one).
    pub fn open(&mut self, now: Instant) -> [u8; 16] {
        self.open_until = Some(now + WINDOW);
        let t = nine_proto::random_bytes::<16>();
        self.token = Some((t, now + TOKEN_TTL));
        t
    }
    pub fn close(&mut self) { self.open_until = None; self.token = None; }
    pub fn is_open(&self, now: Instant) -> bool { self.open_until.is_some_and(|u| now < u) }
    pub fn seconds_left(&self, now: Instant) -> u32 {
        self.open_until.map(|u| u.saturating_duration_since(now).as_secs() as u32).unwrap_or(0)
    }

    /// Count an attempt; refuse when closed, over the rate limit, or locked out.
    pub fn begin_attempt(&mut self, now: Instant) -> Result<(), Refusal> {
        if self.locked_until.is_some_and(|u| now < u) { return Err(Refusal::Locked); }
        if !self.is_open(now) { return Err(Refusal::Closed); }
        while self.attempts.front().is_some_and(|t| now.duration_since(*t) > Duration::from_secs(60)) { self.attempts.pop_front(); }
        if self.attempts.len() >= MAX_ATTEMPTS_PER_MIN { return Err(Refusal::RateLimited); }
        self.attempts.push_back(now);
        Ok(())
    }

    pub fn record_reject(&mut self, now: Instant) {
        self.rejects += 1;
        if self.rejects >= MAX_REJECTS { self.locked_until = Some(now + LOCKOUT); self.rejects = 0; }
    }

    /// Verify and consume the QR token.
    pub fn check_qr(&mut self, now: Instant, h: &[u8; 32], proof: &[u8]) -> bool {
        let Some((tok, exp)) = self.token else { return false };
        if now >= exp || !verify_qr_proof(&tok, h, proof) { return false; }
        self.token = None;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use nine_proto::pairing::qr_proof;

    #[test]
    fn window_rate_limit_lockout() {
        let t0 = Instant::now();
        let mut p = Pairing::default();
        assert_eq!(p.begin_attempt(t0), Err(Refusal::Closed));
        p.open(t0);
        for _ in 0..MAX_ATTEMPTS_PER_MIN { p.begin_attempt(t0).unwrap(); }
        assert_eq!(p.begin_attempt(t0), Err(Refusal::RateLimited));
        assert!(p.begin_attempt(t0 + Duration::from_secs(61)).is_ok());
        for _ in 0..MAX_REJECTS { p.record_reject(t0 + Duration::from_secs(61)); }
        assert_eq!(p.begin_attempt(t0 + Duration::from_secs(62)), Err(Refusal::Locked));
        assert!(!p.is_open(t0 + WINDOW + Duration::from_secs(1)));
    }

    #[test]
    fn qr_token_is_one_time_and_expires() {
        let t0 = Instant::now();
        let mut p = Pairing::default();
        let tok = p.open(t0);
        let h = [9u8; 32];
        assert!(!p.check_qr(t0, &h, &qr_proof(&tok, &[8u8; 32])), "proof for another session");
        assert!(p.check_qr(t0, &h, &qr_proof(&tok, &h)));
        assert!(!p.check_qr(t0, &h, &qr_proof(&tok, &h)), "token reused");
        let tok = p.open(t0);
        assert!(!p.check_qr(t0 + TOKEN_TTL, &h, &qr_proof(&tok, &h)), "expired");
    }
}
