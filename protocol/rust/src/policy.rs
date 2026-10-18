//! Link-selection policy (docs/CONNECTION_MANAGER.md). Pure and deterministic so the Kotlin and
//! Swift clients reproduce the same decisions from `protocol/vectors/policy.json`.
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Copy, PartialEq, Eq, Hash, Debug)]
#[serde(rename_all = "snake_case")]
pub enum LinkKind { Usb, Ethernet, Lan, Hotspot, WifiDirect, Stream, Ble }

impl LinkKind {
    pub const ALL: [LinkKind; 7] = [LinkKind::Usb, LinkKind::Ethernet, LinkKind::Lan, LinkKind::Hotspot, LinkKind::WifiDirect, LinkKind::Stream, LinkKind::Ble];
    /// Prior preference before any measurement (wired > local Wi-Fi > TCP-only stream > BLE).
    pub fn base(self) -> f64 {
        match self { LinkKind::Usb => 100.0, LinkKind::Ethernet => 95.0, LinkKind::Lan | LinkKind::Hotspot | LinkKind::WifiDirect => 85.0, LinkKind::Stream => 60.0, LinkKind::Ble => 40.0 }
    }
    pub fn label(self) -> &'static str {
        match self { LinkKind::Usb => "USB network", LinkKind::Ethernet => "Ethernet", LinkKind::Lan => "Wi-Fi / LAN", LinkKind::Hotspot => "Hotspot",
            LinkKind::WifiDirect => "Wi-Fi Direct", LinkKind::Stream => "TCP-only (stream)", LinkKind::Ble => "Bluetooth LE" }
    }
}

/// Recent measurements for one candidate link.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct LinkStats {
    pub kind: LinkKind,
    pub available: bool,
    pub rtt_p95_ms: Option<f64>,
    pub loss_pct: f64,
    pub disconnects_per_min: f64,
    pub last_failure_age_s: Option<f64>,
    pub attempts: u32,
    pub successes: u32,
}

impl Default for LinkStats {
    fn default() -> Self { LinkStats { kind: LinkKind::Lan, available: false, rtt_p95_ms: None, loss_pct: 0.0, disconnects_per_min: 0.0, last_failure_age_s: None, attempts: 0, successes: 0 } }
}

pub const SWITCH_MARGIN: f64 = 25.0;
pub const BETTER_FOR_S: f64 = 3.0;
pub const MIN_DWELL_S: f64 = 10.0;
pub const RECENT_FAILURE_S: f64 = 10.0;

/// Score of an available link; `None` when unavailable.
pub fn score(s: &LinkStats) -> Option<f64> {
    if !s.available { return None; }
    let mut v = s.kind.base();
    if let Some(r) = s.rtt_p95_ms { v -= (r - 20.0).max(0.0); }
    v -= s.loss_pct * 4.0;
    v -= s.disconnects_per_min * 15.0;
    if s.last_failure_age_s.is_some_and(|a| a < RECENT_FAILURE_S) { v -= 50.0; }
    if s.attempts >= 3 && (s.successes as f64) < 0.5 * s.attempts as f64 { v -= 30.0; }
    Some(v)
}

/// Available links, best first (ties broken by the fixed order of `LinkKind::ALL`).
pub fn rank(cands: &[LinkStats]) -> Vec<LinkKind> {
    let mut v: Vec<(f64, usize, LinkKind)> = cands.iter()
        .filter_map(|s| score(s).map(|sc| (sc, LinkKind::ALL.iter().position(|k| *k == s.kind).unwrap(), s.kind)))
        .collect();
    v.sort_by(|a, b| b.0.total_cmp(&a.0).then(a.1.cmp(&b.1)));
    v.into_iter().map(|x| x.2).collect()
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct PolicyState {
    pub current: Option<LinkKind>,
    pub current_healthy: bool,
    pub since_switch_s: f64,
    /// How long the best alternative has continuously out-scored the current link by the margin.
    pub better_for_s: f64,
    pub override_kind: Option<LinkKind>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(tag = "action", rename_all = "snake_case")]
pub enum Decision {
    /// Keep the current link.
    Stay,
    /// Planned move to a clearly better link (current is healthy).
    Switch { to: LinkKind },
    /// Current link failed or none is active: try these in order, with bounded timeouts/backoff.
    Failover { order: Vec<LinkKind> },
    /// Nothing usable — tell the user why; never claim connectivity.
    Unavailable,
}

pub fn decide(cands: &[LinkStats], st: &PolicyState) -> Decision {
    let available = |k: LinkKind| cands.iter().any(|c| c.kind == k && c.available);
    if let Some(o) = st.override_kind.filter(|o| available(*o)) {
        return if st.current == Some(o) && st.current_healthy { Decision::Stay } else if st.current_healthy && st.current.is_some() { Decision::Switch { to: o } } else { Decision::Failover { order: vec![o] } };
    }
    let order = rank(cands);
    match st.current {
        Some(cur) if st.current_healthy => {
            let Some(&best) = order.first() else { return Decision::Stay };
            if best == cur { return Decision::Stay; }
            let (Some(sb), Some(sc)) = (cands.iter().find(|c| c.kind == best).and_then(score), cands.iter().find(|c| c.kind == cur).and_then(score)) else {
                return Decision::Stay;
            };
            if sb - sc >= SWITCH_MARGIN && st.better_for_s >= BETTER_FOR_S && st.since_switch_s >= MIN_DWELL_S { Decision::Switch { to: best } } else { Decision::Stay }
        }
        cur => {
            let mut order = order;
            // A failed current link goes last, not first.
            if let Some(c) = cur { if let Some(i) = order.iter().position(|k| *k == c) { let k = order.remove(i); order.push(k); } }
            if order.is_empty() { Decision::Unavailable } else { Decision::Failover { order } }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn l(kind: LinkKind, rtt: f64) -> LinkStats { LinkStats { kind, available: true, rtt_p95_ms: Some(rtt), ..Default::default() } }

    #[test]
    fn prefers_wired_then_wifi_then_ble() {
        assert_eq!(rank(&[l(LinkKind::Ble, 30.0), l(LinkKind::Lan, 8.0), l(LinkKind::Usb, 2.0)]), vec![LinkKind::Usb, LinkKind::Lan, LinkKind::Ble]);
        // a terrible LAN loses to BLE
        let mut bad = l(LinkKind::Lan, 120.0); bad.loss_pct = 10.0;
        assert_eq!(rank(&[bad, l(LinkKind::Ble, 25.0)])[0], LinkKind::Ble);
    }

    #[test]
    fn hysteresis_prevents_flapping() {
        let c = [l(LinkKind::Lan, 10.0), l(LinkKind::Usb, 2.0)];
        let mut st = PolicyState { current: Some(LinkKind::Lan), current_healthy: true, since_switch_s: 2.0, better_for_s: 5.0, override_kind: None };
        assert_eq!(decide(&c, &st), Decision::Stay, "dwell time");
        st.since_switch_s = 30.0; st.better_for_s = 1.0;
        assert_eq!(decide(&c, &st), Decision::Stay, "not better for long enough");
        st.better_for_s = 4.0;
        assert_eq!(decide(&c, &st), Decision::Stay, "margin 15 < 25: USB 100 vs LAN 85");
        let c2 = [l(LinkKind::Ble, 30.0), l(LinkKind::Usb, 2.0)];
        st.current = Some(LinkKind::Ble);
        assert_eq!(decide(&c2, &st), Decision::Switch { to: LinkKind::Usb });
    }

    #[test]
    fn failover_and_unavailable() {
        let st = PolicyState { current: Some(LinkKind::Lan), current_healthy: false, ..Default::default() };
        assert_eq!(decide(&[l(LinkKind::Lan, 5.0), l(LinkKind::Ble, 30.0)], &st), Decision::Failover { order: vec![LinkKind::Ble, LinkKind::Lan] });
        let none = [LinkStats { kind: LinkKind::Lan, available: false, ..Default::default() }];
        assert_eq!(decide(&none, &st), Decision::Unavailable);
    }

    #[test]
    fn user_override_wins_when_available() {
        let c = [l(LinkKind::Lan, 5.0), l(LinkKind::Ble, 30.0)];
        let st = PolicyState { current: Some(LinkKind::Lan), current_healthy: true, since_switch_s: 0.0, better_for_s: 0.0, override_kind: Some(LinkKind::Ble) };
        assert_eq!(decide(&c, &st), Decision::Switch { to: LinkKind::Ble });
        let st2 = PolicyState { override_kind: Some(LinkKind::Usb), ..st };
        assert_eq!(decide(&c, &st2), Decision::Stay, "override unavailable → normal policy");
    }
}
