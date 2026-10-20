//! Host runtime: TCP control server, UDP input receiver, slots, timers, pairing flows.
use crate::backend::{Backend, BackendStatus, VirtualPad};
use crate::pairing::Pairing;
use crate::store::{HostConfig, PairedDevice, Store};
use crate::time::mono_us;
use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use nine_proto::control::{self, Control, ControllerHello, HostHello, InitHello, SecretB64};
use nine_proto::frame::{self, Counter, Feedback, Header, InputFrame, PacketType, Ping, Pong, ReplayWindow, Seen};
use nine_proto::input::{InputState, FLAG_PAUSED};
use nine_proto::noise::{Handshake, KeyPair, Role, Transport};
use nine_proto::pairing::{commitment, format_code, sas_code, PairUri};
use nine_proto::stats::{Jitter, Window};
use parking_lot::Mutex;
use serde::Serialize;
use std::collections::{HashMap, VecDeque};
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::io::{AsyncRead, AsyncWrite};
use nine_proto::policy::LinkKind;
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::{broadcast, mpsc, oneshot, watch};

const MAX_CONNECTIONS: usize = 8;
const HANDSHAKES_PER_10S: usize = 20;
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const UNPAIRED_TIMEOUT: Duration = Duration::from_secs(30);
const TICK: Duration = Duration::from_millis(5);

#[derive(Serialize, Clone, Debug)]
#[serde(tag = "type", rename_all = "snake_case", rename_all_fields = "camelCase")]
pub enum HostEvent {
    PairingRequest { id: u64, code: String, name: String, platform: String },
    PairingResolved { id: u64, ok: bool },
    Paired { name: String },
    SlotConnected { slot: u8, name: String },
    SlotDisconnected { slot: u8, name: String, grace_s: u32 },
    SlotReleased { slot: u8 },
    NeutralReset { slot: u8 },
    /// Another link joined a live session (SPEC §8.5): same player, same slot.
    PathUp { slot: u8, link: String },
    /// One link of a session ended; the session continues while another link is up.
    PathDown { slot: u8, link: String, reason: String },
    Notice { level: String, message: String },
}

#[derive(Serialize, Clone, Debug, Default)]
#[serde(rename_all = "camelCase")]
pub struct PhoneStats { pub rtt_p50_ms: Option<f32>, pub rtt_p95_ms: Option<f32>, pub sensor_hz: Option<f32>, pub send_hz: Option<f32>, pub battery_pct: Option<u8> }

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct StateDto { pub buttons: u32, pub lx: i16, pub ly: i16, pub rx: i16, pub ry: i16, pub lt: u16, pub rt: u16 }

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct SlotSnapshot {
    pub slot: u8,
    pub link: LinkKind,
    pub link_label: String,
    pub connected: bool,
    pub grace_s_left: Option<u32>,
    pub name: String,
    pub platform: String,
    pub profile: Option<String>,
    pub connected_s: u64,
    /// Milliseconds since the last valid datagram (None before the first one).
    pub silent_ms: Option<u64>,
    pub frames: u64,
    pub rate_hz: Option<f64>,
    pub lost: u64,
    pub stale: u64,
    pub invalid: u64,
    pub jitter_ms: f64,
    pub interarrival_p50_ms: Option<f64>,
    pub interarrival_p95_ms: Option<f64>,
    pub host_processing_p95_us: Option<f64>,
    pub neutral_resets: u64,
    pub motion: bool,
    pub phone: PhoneStats,
    pub state: StateDto,
    pub pad_ok: bool,
    pub pad_error: Option<String>,
    /// Stable per-phone identity: hex of the first 8 bytes of SHA-256 of its public static key
    /// (same scheme as the host id; derived from a public key, not a secret).
    pub device_id: String,
    /// "live" | "stale" | "holding" | "waiting" — see [`slot_health`].
    pub health: &'static str,
    /// Every link currently attached to this player's session (SPEC §8.5).
    pub paths: Vec<PathSnapshot>,
    /// More than one link delivered fresh datagrams in the last second.
    pub redundant: bool,
    /// Copies of a datagram that another link had already delivered (dropped, by design).
    pub duplicates: u64,
    /// Input frames that arrived after a newer one (late copy or reorder); never applied.
    pub superseded: u64,
    /// Recent link changes, newest last: "Bluetooth LE up", "Wi-Fi / LAN down: …".
    pub path_events: Vec<String>,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct PathSnapshot {
    pub link: LinkKind,
    pub link_label: String,
    pub peer: String,
    pub connected_s: u64,
    pub silent_ms: Option<u64>,
    /// Same rule as the slot, applied to this link alone.
    pub health: &'static str,
    /// Datagrams this link delivered first.
    pub first: u64,
    /// Datagrams this link delivered after another link already had.
    pub late_copies: u64,
    /// Share of the last 64 datagrams this link delivered first (0..1).
    pub first_share: f64,
    /// How far behind the first copy this link's copies arrived (median / p95), ms.
    pub behind_p50_ms: Option<f64>,
    pub behind_p95_ms: Option<f64>,
    pub gap_p95_ms: Option<f64>,
    /// Round trip measured by the phone on this link.
    pub rtt_p50_ms: Option<f32>,
    pub rtt_p95_ms: Option<f32>,
    /// The link currently delivering most input first.
    pub preferred: bool,
}

/// Input older than this is reported as stale in the UI. The controller is neutralized much
/// earlier (config `timeout_ms`, 250 ms); this only labels the session for humans.
pub const STALE_AFTER_MS: u64 = 1000;

/// Classify a slot for display: a session only counts as live while frames keep arriving.
pub fn slot_health(connected: bool, silent_ms: Option<u64>) -> &'static str {
    match (connected, silent_ms) {
        (false, _) => "holding",
        (true, None) => "waiting",
        (true, Some(ms)) if ms > STALE_AFTER_MS => "stale",
        (true, Some(_)) => "live",
    }
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct HostSnapshot {
    pub name: String,
    /// Every local IPv4 address with its classified link (Wi-Fi, Ethernet, USB network, hotspot…).
    pub addresses: Vec<crate::netif::LocalAddr>,
    /// Bindings this host is serving right now (SPEC §8).
    pub links: Vec<String>,
    pub stream_port: Option<u16>,
    pub ble: crate::link::BleStatus,
    pub id: String,
    pub os: String,
    pub address: Option<String>,
    pub tcp_port: u16,
    pub udp_port: u16,
    pub advertising: bool,
    pub backend: BackendStatus,
    pub max_slots: u8,
    pub slots: Vec<SlotSnapshot>,
    pub pairing_open: bool,
    pub pairing_seconds_left: u32,
    pub paired: Vec<PairedDevice>,
    pub uptime_s: u64,
    pub malformed_datagrams: u64,
    pub unknown_session_datagrams: u64,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct PairingInfo { pub uri: String, pub qr_svg: String, pub seconds_left: u32 }

/// Where replies (pong, feedback) for a session go: the UDP source address, or a link-owned channel
/// (stream / BLE bindings) that frames them for its medium.
#[derive(Clone, Debug)]
pub enum Reply { Udp(SocketAddr), Link(u64, mpsc::UnboundedSender<Vec<u8>>) }

impl Reply {
    fn send(&self, sh: &Shared, bytes: Vec<u8>) {
        match self {
            Reply::Udp(a) => { let _ = sh.udp.try_send_to(&bytes, *a); }
            Reply::Link(_, tx) => { let _ = tx.send(bytes); }
        }
    }
}

/// One link (control channel + its datagrams) attached to a session. A session has 1..=MAX_PATHS.
struct Path {
    conn_id: u64,
    link: LinkKind,
    peer: String,
    /// Source IP of the control connection; UDP datagrams from it belong to this path.
    peer_ip: Option<IpAddr>,
    ctl_tx: mpsc::UnboundedSender<Control>,
    reply: Option<Reply>,
    connected_at: Instant,
    last_rx: Option<Instant>,
    last_rx_us: Option<u64>,
    first: u64,
    late: u64,
    gaps: Window,
    behind: Window,
    rtt_p50_ms: Option<f32>,
    rtt_p95_ms: Option<f32>,
}

impl Path {
    fn new(conn_id: u64, link: LinkKind, peer: &str, peer_ip: Option<IpAddr>, ctl_tx: &mpsc::UnboundedSender<Control>) -> Path {
        Path { conn_id, link, peer: peer.into(), peer_ip, ctl_tx: ctl_tx.clone(), reply: None, connected_at: Instant::now(),
            last_rx: None, last_rx_us: None, first: 0, late: 0, gaps: Window::new(256), behind: Window::new(256), rtt_p50_ms: None, rtt_p95_ms: None }
    }
    fn is_ip(&self) -> bool { !matches!(self.link, LinkKind::Ble | LinkKind::Stream) }
}

pub const MAX_PATHS: usize = 4;

struct Slot {
    /// Link of the most recent path (shown while the slot is held in grace with no path).
    link: LinkKind,
    key: [u8; 32],
    name: String,
    platform: String,
    profile: Option<String>,
    paths: Vec<Path>,
    connected: bool,
    connected_at: Instant,
    grace_until: Option<Instant>,
    session_id: u32,
    k_in: [u8; 32],
    k_out: [u8; 32],
    out_counter: Counter,
    window: ReplayWindow,
    /// Counter of the last applied input frame: input is applied only in increasing counter order.
    last_input: u64,
    superseded: u64,
    /// Arrival time of recent first copies (counter, time), to measure how far behind other links are.
    first_seen: VecDeque<(u64, Instant)>,
    /// conn_id of the path that delivered each of the last 64 first copies.
    wins: VecDeque<u64>,
    path_events: VecDeque<String>,
    last_rx: Option<Instant>,
    last_rx_us: Option<u64>,
    /// Sender timestamp of the previous input frame (phone clock), for gap diagnosis.
    last_tx_us: Option<u64>,
    state: InputState,
    flags: u16,
    submitted_once: bool,
    neutral_resets: u64,
    invalid: u64,
    jitter: Jitter,
    gaps: Window,
    host_proc: Window,
    pad: Option<Box<dyn VirtualPad>>,
    pad_error: Option<String>,
    /// When a missing pad is tried again (the user may fix the cause — grant a permission, install a
    /// driver — while the phone stays connected).
    pad_retry_at: Option<Instant>,
    phone: PhoneStats,
}

impl Slot {
    /// Which path a datagram came over: link bindings name their connection; UDP matches the
    /// control connection's source IP (the phone sends both from the same interface).
    fn path_of(&self, from: &Reply) -> Option<usize> {
        match from {
            Reply::Link(id, _) => self.paths.iter().position(|p| p.conn_id == *id),
            Reply::Udp(a) => self.paths.iter().position(|p| p.is_ip() && p.peer_ip == Some(a.ip()))
                .or_else(|| self.paths.iter().position(Path::is_ip)),
        }
    }
    fn send_all(&self, m: &Control) { for p in &self.paths { let _ = p.ctl_tx.send(m.clone()); } }
    fn note(&mut self, e: String) { if self.path_events.len() >= 8 { self.path_events.pop_front(); } self.path_events.push_back(e); }
    /// The path delivering most datagrams first lately (ties: the earliest attached).
    fn preferred(&self) -> Option<usize> {
        if self.paths.is_empty() { return None; }
        let share = |p: &Path| self.wins.iter().filter(|w| **w == p.conn_id).count();
        let mut best = 0;
        for i in 1..self.paths.len() { if share(&self.paths[i]) > share(&self.paths[best]) { best = i; } }
        Some(best)
    }
    fn apply(&mut self, s: InputState) -> bool {
        if self.submitted_once && s == self.state { return false; }
        self.state = s;
        self.submitted_once = true;
        if let Some(p) = self.pad.as_mut()
            && let Err(e) = p.submit(&s) { self.pad_error = Some(e.to_string()); }
        true
    }
}

#[derive(Default)]
struct State {
    slots: Vec<Option<Slot>>,
    sessions: HashMap<u32, usize>,
    malformed: u64,
    unknown_session: u64,
}

struct PendingCode { conn_id: u64, tx: Option<oneshot::Sender<bool>> }

pub(crate) struct Shared {
    secret: [u8; 32],
    public: [u8; 32],
    host_id: String,
    cfg: HostConfig,
    tcp_port: u16,
    udp_port: u16,
    pub(crate) stream_port: Option<u16>,
    pub(crate) ble: Mutex<crate::link::BleStatus>,
    store: Mutex<Store>,
    state: Mutex<State>,
    pairing: Mutex<Pairing>,
    pending: Mutex<HashMap<u64, PendingCode>>,
    hs_rate: Mutex<HashMap<IpAddr, VecDeque<Instant>>>,
    backend: Arc<dyn Backend>,
    events: broadcast::Sender<HostEvent>,
    udp: Arc<UdpSocket>,
    started: Instant,
    next_id: AtomicU64,
    conns: AtomicUsize,
    shutdown: watch::Sender<bool>,
    advertising: Mutex<Option<crate::discovery::Advertiser>>,
}

impl Shared {
    fn emit(&self, e: HostEvent) { let _ = self.events.send(e); }
    pub(crate) fn id(&self) -> u64 { self.next_id.fetch_add(1, Ordering::Relaxed) }
    /// BLE advert filter (SPEC §8.3): hint = first 4 bytes of our host id, or zeros while pairing is open.
    pub(crate) fn ble_hint_ok(&self, hint: &[u8]) -> bool {
        let ours: Vec<u8> = (0..4).filter_map(|i| u8::from_str_radix(self.host_id.get(2 * i..2 * i + 2)?, 16).ok()).collect();
        hint.len() >= 4 && (hint[..4] == ours[..] || (hint[..4] == [0u8; 4] && self.pairing.lock().is_open(Instant::now())))
    }
    fn links(&self) -> Vec<String> {
        let mut v = vec!["ip".to_string()];
        if self.stream_port.is_some() { v.push("stream".into()); }
        if self.ble.lock().active { v.push("ble".into()); }
        v
    }
}

#[derive(Clone)]
pub struct Host { pub(crate) sh: Arc<Shared> }

#[derive(Debug, thiserror::Error)]
pub enum HostError {
    #[error("cannot bind {0}: {1}")]
    Bind(String, std::io::Error),
}

impl Host {
    pub async fn start(store: Store, backend: Arc<dyn Backend>) -> Result<Host, HostError> {
        let mut cfg = store.config.clone();
        if let Some(o) = store.port_override { (cfg.tcp_port, cfg.udp_port, cfg.stream_port) = (o.tcp, o.udp, o.stream); }
        let tcp = TcpListener::bind((cfg.bind.as_str(), cfg.tcp_port)).await
            .map_err(|e| HostError::Bind(format!("tcp/{}", cfg.tcp_port), e))?;
        let udp = UdpSocket::bind((cfg.bind.as_str(), cfg.udp_port)).await
            .map_err(|e| HostError::Bind(format!("udp/{}", cfg.udp_port), e))?;
        // DSCP 46 (Expedited Forwarding) → Wi-Fi WMM voice queue: pongs and rumble skip bulk traffic. Best effort.
        if let Err(e) = socket2::SockRef::from(&udp).set_tos_v4(0xB8) { tracing::debug!("could not mark UDP as EF: {e}"); }
        let (tcp_port, udp_port) = (tcp.local_addr().unwrap().port(), udp.local_addr().unwrap().port());
        let stream_listener = match cfg.stream_port {
            Some(p) => Some(TcpListener::bind((cfg.bind.as_str(), p)).await.map_err(|e| HostError::Bind(format!("tcp/{p} (stream binding)"), e))?),
            None => None,
        };
        let stream_port = stream_listener.as_ref().map(|l| l.local_addr().unwrap().port());
        let max = (cfg.max_slots as usize).min(nine_proto::MAX_SLOTS).min(backend.status().max_pads.max(1));
        let state = State { slots: (0..max).map(|_| None).collect(), ..Default::default() };
        let (shutdown, _) = watch::channel(false);
        let sh = Arc::new(Shared {
            secret: store.identity.secret_bytes(),
            public: store.identity.public,
            host_id: nine_proto::host_id(&store.identity.public),
            cfg: cfg.clone(), tcp_port, udp_port, stream_port, ble: Mutex::new(crate::link::BleStatus::default()),
            store: Mutex::new(store),
            state: Mutex::new(state),
            pairing: Mutex::new(Pairing::default()),
            pending: Mutex::new(HashMap::new()),
            hs_rate: Mutex::new(HashMap::new()),
            backend,
            events: broadcast::channel(256).0,
            udp: Arc::new(udp),
            started: Instant::now(),
            next_id: AtomicU64::new(1),
            conns: AtomicUsize::new(0),
            shutdown,
            advertising: Mutex::new(None),
        });
        if cfg.advertise {
            match crate::discovery::Advertiser::start(&cfg.name, &sh.host_id, tcp_port, udp_port, max) {
                Ok(a) => *sh.advertising.lock() = Some(a),
                Err(e) => sh.emit(HostEvent::Notice { level: "warn".into(), message: format!("Network discovery unavailable ({e}); connect by QR or address") }),
            }
        }
        tokio::spawn(accept_loop(sh.clone(), tcp, false));
        if let Some(l) = stream_listener { tokio::spawn(accept_loop(sh.clone(), l, true)); }
        #[cfg(feature = "ble")]
        if cfg.ble { crate::ble::spawn(sh.clone()); }
        tokio::spawn(udp_loop(sh.clone()));
        tokio::spawn(tick_loop(sh.clone()));
        tracing::info!(host_id = %sh.host_id, tcp_port, udp_port, backend = %sh.backend.status().name, "host started");
        Ok(Host { sh })
    }

    pub fn config(&self) -> HostConfig { self.sh.store.lock().config.clone() }

    /// Persist a new configuration. Ports, bind address and slot count apply on next start.
    pub fn save_config(&self, mut cfg: HostConfig) -> Result<(), String> {
        cfg.name = cfg.name.trim().chars().filter(|c| !c.is_control()).take(40).collect();
        if cfg.name.is_empty() { return Err("Name cannot be empty".into()); }
        if !(1..=4).contains(&cfg.max_slots) { return Err("Slots must be 1-4".into()); }
        if !(100..=2000).contains(&cfg.timeout_ms) { return Err("Input timeout must be 100-2000 ms".into()); }
        let mut s = self.sh.store.lock();
        s.config = cfg;
        s.save().map_err(|e| e.to_string())
    }

    pub fn subscribe(&self) -> broadcast::Receiver<HostEvent> { self.sh.events.subscribe() }
    pub fn tcp_port(&self) -> u16 { self.sh.tcp_port }
    pub fn udp_port(&self) -> u16 { self.sh.udp_port }
    pub fn public_key(&self) -> [u8; 32] { self.sh.public }

    pub fn open_pairing(&self, addr_override: Option<String>) -> PairingInfo {
        let now = Instant::now();
        let token = self.sh.pairing.lock().open(now);
        let addr = addr_override
            .or_else(|| (self.sh.cfg.bind != "0.0.0.0").then(|| self.sh.cfg.bind.clone()))
            .or_else(|| crate::discovery::primary_ipv4().map(|a| a.to_string()))
            .unwrap_or_else(|| "127.0.0.1".into());
        let extra: Vec<String> = crate::netif::local_ipv4().into_iter().map(|a| a.ip.to_string()).filter(|a| *a != addr).take(6).collect();
        let uri = PairUri { addr, port: self.sh.tcp_port, host_static: self.sh.public, token, name: self.sh.cfg.name.clone(), extra }.to_uri();
        let qr_svg = qrcode::QrCode::with_error_correction_level(uri.as_bytes(), qrcode::EcLevel::M)
            .map(|c| c.render::<qrcode::render::svg::Color>().min_dimensions(232, 232).quiet_zone(true)
                .dark_color(qrcode::render::svg::Color("#0C0B09")).light_color(qrcode::render::svg::Color("#F2EDE4")).build())
            .unwrap_or_default();
        PairingInfo { uri, qr_svg, seconds_left: self.sh.pairing.lock().seconds_left(now) }
    }

    pub fn close_pairing(&self) {
        self.sh.pairing.lock().close();
        let ids: Vec<u64> = self.sh.pending.lock().keys().copied().collect();
        for id in ids { self.answer(id, false); }
    }

    /// Host user's verdict on a numeric-comparison request.
    pub fn answer(&self, id: u64, allow: bool) -> bool {
        let tx = self.sh.pending.lock().get_mut(&id).and_then(|p| p.tx.take());
        tx.map(|t| t.send(allow).is_ok()).unwrap_or(false)
    }

    pub fn unpair(&self, key_b64: &str) -> bool {
        let removed = { let mut s = self.sh.store.lock(); let r = s.remove(key_b64); let _ = s.save(); r };
        if removed {
            let key = B64.decode(key_b64).ok();
            let st = self.sh.state.lock();
            for s in st.slots.iter().flatten() {
                if Some(s.key.to_vec()) == key { s.send_all(&Control::Bye { reason: Some("unpaired".into()) }); }
            }
        }
        removed
    }

    pub fn disconnect_slot(&self, slot: u8) {
        let st = self.sh.state.lock();
        if let Some(Some(s)) = st.slots.get(slot as usize) { s.send_all(&Control::Bye { reason: Some("disconnected by host".into()) }); }
    }

    /// Ask the game's controller to rumble the phone (manual test from the host UI).
    pub fn test_rumble(&self, slot: u8) { self.rumble(slot, 30000, 30000, 250) }

    /// Rumble one phone: heavy (`low`) and light (`high`) motor magnitudes, like a console pad. Sent on
    /// the session's preferred link only (a control message, so one copy is enough).
    pub fn rumble(&self, slot: u8, low: u16, high: u16, ms: u16) {
        let st = self.sh.state.lock();
        if let Some(Some(s)) = st.slots.get(slot as usize)
            && let Some(p) = s.preferred() { let _ = s.paths[p].ctl_tx.send(Control::Rumble { low, high, ms: ms.min(5000) }); }
    }

    pub fn snapshot(&self) -> HostSnapshot {
        let sh = &self.sh;
        let now = Instant::now();
        let (pairing_open, pairing_seconds_left) = { let p = sh.pairing.lock(); (p.is_open(now), p.seconds_left(now)) };
        let paired = sh.store.lock().paired.clone();
        let st = sh.state.lock();
        let slots = st.slots.iter().enumerate().filter_map(|(i, s)| s.as_ref().map(|s| {
            let p50 = s.gaps.percentile(50.0);
            let silent_ms = s.last_rx.map(|t| now.duration_since(t).as_millis() as u64);
            let preferred = s.preferred();
            let link = preferred.map(|p| s.paths[p].link).unwrap_or(s.link);
            let paths: Vec<PathSnapshot> = s.paths.iter().enumerate().map(|(pi, p)| {
                let silent = p.last_rx.map(|t| now.duration_since(t).as_millis() as u64);
                let wins = s.wins.iter().filter(|w| **w == p.conn_id).count();
                PathSnapshot {
                    link: p.link, link_label: p.link.label().into(), peer: p.peer.clone(),
                    connected_s: now.duration_since(p.connected_at).as_secs(), silent_ms: silent, health: slot_health(true, silent),
                    first: p.first, late_copies: p.late,
                    first_share: if s.wins.is_empty() { 0.0 } else { wins as f64 / s.wins.len() as f64 },
                    behind_p50_ms: p.behind.percentile(50.0), behind_p95_ms: p.behind.percentile(95.0), gap_p95_ms: p.gaps.percentile(95.0),
                    rtt_p50_ms: p.rtt_p50_ms, rtt_p95_ms: p.rtt_p95_ms, preferred: preferred == Some(pi),
                }
            }).collect();
            let live = paths.iter().filter(|p| p.silent_ms.is_some_and(|ms| ms <= 1000)).count();
            let mut phone = s.phone.clone();
            if let Some(p) = preferred.map(|p| &s.paths[p]) { phone.rtt_p50_ms = p.rtt_p50_ms; phone.rtt_p95_ms = p.rtt_p95_ms; }
            SlotSnapshot {
                device_id: nine_proto::host_id(&s.key), health: slot_health(s.connected, silent_ms),
                redundant: live >= 2, duplicates: s.window.duplicate(), superseded: s.superseded,
                path_events: s.path_events.iter().cloned().collect(), paths,
                slot: i as u8, link, link_label: link.label().into(), connected: s.connected,
                grace_s_left: s.grace_until.map(|g| g.saturating_duration_since(now).as_secs() as u32),
                name: s.name.clone(), platform: s.platform.clone(), profile: s.profile.clone(),
                connected_s: now.duration_since(s.connected_at).as_secs(),
                silent_ms,
                frames: s.window.accepted(), rate_hz: p50.filter(|v| *v > 0.0).map(|v| 1000.0 / v),
                lost: s.window.lost(), stale: s.window.too_old() + s.superseded, invalid: s.invalid, jitter_ms: s.jitter.ms(),
                interarrival_p50_ms: p50, interarrival_p95_ms: s.gaps.percentile(95.0),
                host_processing_p95_us: s.host_proc.percentile(95.0), neutral_resets: s.neutral_resets,
                motion: s.flags & nine_proto::input::FLAG_MOTION != 0, phone,
                state: StateDto { buttons: s.state.buttons, lx: s.state.lx, ly: s.state.ly, rx: s.state.rx, ry: s.state.ry, lt: s.state.lt, rt: s.state.rt },
                pad_ok: s.pad.is_some() && s.pad_error.is_none(), pad_error: s.pad_error.clone(),
            }
        })).collect();
        HostSnapshot {
            name: sh.cfg.name.clone(), id: sh.host_id.clone(), os: std::env::consts::OS.into(),
            addresses: crate::netif::local_ipv4(), links: sh.links(), stream_port: sh.stream_port, ble: sh.ble.lock().clone(),
            address: crate::discovery::primary_ipv4().map(|a| a.to_string()),
            tcp_port: sh.tcp_port, udp_port: sh.udp_port, advertising: sh.advertising.lock().is_some(),
            backend: sh.backend.status(), max_slots: st.slots.len() as u8, slots,
            pairing_open, pairing_seconds_left, paired, uptime_s: sh.started.elapsed().as_secs(),
            malformed_datagrams: st.malformed, unknown_session_datagrams: st.unknown_session,
        }
    }

    /// Neutralize every slot, tell controllers goodbye, stop advertising and all tasks.
    pub async fn shutdown(&self) {
        {
            let mut st = self.sh.state.lock();
            for s in st.slots.iter_mut().flatten() {
                s.apply(InputState::NEUTRAL);
                s.send_all(&Control::Bye { reason: Some("host shutting down".into()) });
            }
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
        let _ = self.sh.shutdown.send(true);
        self.sh.advertising.lock().take();
    }
}

// ---------------------------------------------------------------- TCP control

async fn accept_loop(sh: Arc<Shared>, listener: TcpListener, stream_binding: bool) {
    let mut stop = sh.shutdown.subscribe();
    loop {
        tokio::select! {
            r = listener.accept() => {
                let Ok((stream, peer)) = r else { continue };
                if sh.conns.load(Ordering::Relaxed) >= MAX_CONNECTIONS || !allow_handshake(&sh, peer.ip()) {
                    tracing::warn!(%peer, "connection refused: limit");
                    continue; // dropping the stream closes it
                }
                sh.conns.fetch_add(1, Ordering::Relaxed);
                let sh2 = sh.clone();
                tokio::spawn(async move {
                    let r = if stream_binding {
                        let link = crate::netif::kind_for_local(stream.local_addr().ok().map(|a| a.ip()));
                        let _ = stream.set_nodelay(true);
                        crate::link::serve_stream(&sh2, stream, format!("{peer} (stream)"), if link == LinkKind::Lan { LinkKind::Stream } else { link }).await
                    } else { handle_conn(&sh2, stream, peer).await };
                    if let Err(e) = r { tracing::info!(%peer, "control connection ended: {e}"); }
                    sh2.conns.fetch_sub(1, Ordering::Relaxed);
                });
            }
            _ = stop.changed() => break,
        }
    }
}

fn allow_handshake(sh: &Shared, ip: IpAddr) -> bool {
    let now = Instant::now();
    let mut m = sh.hs_rate.lock();
    m.retain(|_, q| q.back().is_some_and(|t| now.duration_since(*t) < Duration::from_secs(10)));
    let q = m.entry(ip).or_default();
    while q.front().is_some_and(|t| now.duration_since(*t) >= Duration::from_secs(10)) { q.pop_front(); }
    if q.len() >= HANDSHAKES_PER_10S { return false; }
    q.push_back(now);
    true
}

pub(crate) async fn read_frame<R: AsyncRead + Unpin>(rd: &mut R, max: usize) -> std::io::Result<Vec<u8>> {
    let len = rd.read_u16().await? as usize;
    if len == 0 || len > max {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "bad frame length"));
    }
    let mut b = vec![0u8; len];
    rd.read_exact(&mut b).await?;
    Ok(b)
}

async fn send<W: AsyncWrite + Unpin>(wr: &mut W, t: &mut Transport, m: &Control) -> std::io::Result<()> {
    let ct = t.encrypt(&m.to_json()).map_err(|e| std::io::Error::other(e.to_string()))?;
    wr.write_all(&control::frame(&ct)).await
}

fn io_err(e: impl std::fmt::Display) -> std::io::Error { std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string()) }

enum PairFlow {
    Idle,
    AwaitNonce { nb: [u8; 16] },
    AwaitVerdict { id: u64, phone: Option<bool>, host: Option<bool>, rx: Option<oneshot::Receiver<bool>> },
}

async fn recv_verdict(rx: &mut Option<oneshot::Receiver<bool>>) -> bool {
    match rx.as_mut() {
        Some(r) => { let v = r.await.unwrap_or(false); *rx = None; v }
        None => std::future::pending().await,
    }
}

async fn handle_conn(sh: &Arc<Shared>, stream: TcpStream, peer: SocketAddr) -> std::io::Result<()> {
    stream.set_nodelay(true)?;
    let link = crate::netif::kind_for_local(stream.local_addr().ok().map(|a| a.ip()));
    let (rd, wr) = stream.into_split();
    serve_control(sh, rd, wr, peer.to_string(), link, sh.id(), Some(peer.ip())).await
}

/// Runs one controller's control channel over any reliable framed byte stream (TCP, stream
/// binding, BLE GATT pipes). Input datagrams arrive separately via [`handle_datagram`].
/// `conn_id` names this link (link bindings tag their datagrams with it); `peer_ip` is set for the IP
/// binding, whose UDP datagrams are matched to the link by source address.
#[allow(clippy::too_many_arguments)]
pub(crate) async fn serve_control<R, W>(sh: &Arc<Shared>, mut rd: R, mut wr: W, peer: String, link: LinkKind, conn_id: u64, peer_ip: Option<IpAddr>) -> std::io::Result<()>
where R: AsyncRead + Unpin + Send, W: AsyncWrite + Unpin + Send {
    let mut hs = Handshake::new(Role::Responder, KeyPair::from_secret(sh.secret));
    let (mut t, hello, supported) = tokio::time::timeout(HANDSHAKE_TIMEOUT, async {
        let m1 = read_frame(&mut rd, control::MAX_PREHANDSHAKE_FRAME).await?;
        let p1 = hs.read_message(&m1).map_err(io_err)?;
        let supported = serde_json::from_slice::<InitHello>(&p1).map(|h| h.proto.contains(&1)).unwrap_or(false);
        let hh = HostHello { proto: 1, name: sh.cfg.name.clone(), id: sh.host_id.clone(), os: std::env::consts::OS.into(), links: sh.links(),
            addrs: if sh.cfg.bind == "0.0.0.0" { crate::netif::local_ipv4().into_iter().map(|a| a.ip.to_string()).take(8).collect() } else { vec![sh.cfg.bind.clone()] },
            multipath: true };
        let m2 = hs.write_message(&serde_json::to_vec(&hh).unwrap()).map_err(io_err)?;
        wr.write_all(&control::frame(&m2)).await?;
        let m3 = read_frame(&mut rd, control::MAX_PREHANDSHAKE_FRAME).await?;
        let p3 = hs.read_message(&m3).map_err(io_err)?;
        let hello: ControllerHello = serde_json::from_slice(&p3).unwrap_or(ControllerHello {
            proto: 1, name: "Controller".into(), platform: "unknown".into(), app: "?".into() });
        Ok::<_, std::io::Error>((hs.into_transport().map_err(io_err)?, hello, supported))
    }).await.map_err(|_| io_err("handshake timeout"))??;

    if !supported {
        send(&mut wr, &mut t, &Control::error("unsupported_version")).await?;
        return Ok(());
    }
    let name: String = hello.name.chars().filter(|c| !c.is_control()).take(40).collect();
    let platform: String = hello.platform.chars().filter(|c| c.is_ascii_alphanumeric()).take(16).collect();
    let remote = t.remote_static;
    let h = t.handshake_hash;
    tracing::info!(%peer, %name, %platform, "controller handshake complete");

    let (ctl_tx, mut ctl_rx) = mpsc::unbounded_channel::<Control>();
    let mut slot_idx: Option<usize> = None;
    let mut flow = PairFlow::Idle;
    let unpaired_deadline = tokio::time::Instant::now() + UNPAIRED_TIMEOUT;
    let mut stop = sh.shutdown.subscribe();

    let result: std::io::Result<()> = async {
        loop {
            let verdict_rx = match &mut flow { PairFlow::AwaitVerdict { rx, .. } => Some(rx), _ => None };
            let mut none = None;
            let rxref = match verdict_rx { Some(r) => r, None => &mut none };
            tokio::select! {
                f = read_frame(&mut rd, 65535) => {
                    let ct = match f { Ok(c) => c, Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(()), Err(e) => return Err(e) };
                    let pt = t.decrypt(&ct).map_err(io_err)?;
                    let msg = match Control::from_json(&pt) {
                        Ok(m) => m,
                        Err(_) => { send(&mut wr, &mut t, &Control::error("bad_message")).await?; continue; }
                    };
                    match msg {
                        Control::Bye { .. } => return Ok(()),
                        Control::Open { resume_slot, profile, join } => {
                            if !sh.store.lock().is_paired(&remote) {
                                send(&mut wr, &mut t, &Control::PairRequired { modes: vec!["qr".into(), "code".into()] }).await?;
                                continue;
                            }
                            if slot_idx.is_some() { send(&mut wr, &mut t, &Control::error("bad_message")).await?; continue; }
                            if let Some(sid) = join {
                                match join_session(sh, &remote, sid, Path::new(conn_id, link, &peer, peer_ip, &ctl_tx)) {
                                    Ok((idx, reply)) => { slot_idx = Some(idx); send(&mut wr, &mut t, &reply).await?; }
                                    Err(code) => send(&mut wr, &mut t, &Control::Error { code: "join_refused".into(), message: Some(code.into()) }).await?,
                                }
                                continue;
                            }
                            match open_session(sh, &remote, &name, &platform, profile, resume_slot, Path::new(conn_id, link, &peer, peer_ip, &ctl_tx)) {
                                Some((idx, reply, pad_err)) => {
                                    slot_idx = Some(idx);
                                    send(&mut wr, &mut t, &reply).await?;
                                    if let Some(e) = pad_err {
                                        send(&mut wr, &mut t, &Control::Error { code: "backend_unavailable".into(), message: Some(e) }).await?;
                                    }
                                }
                                None => send(&mut wr, &mut t, &Control::error("slots_full")).await?,
                            }
                        }
                        Control::PairQr { proof } => {
                            let now = Instant::now();
                            let r = sh.pairing.lock().begin_attempt(now);
                            if let Err(refusal) = r { send(&mut wr, &mut t, &Control::error(refusal.code())).await?; continue; }
                            let proof = B64.decode(proof).unwrap_or_default();
                            let ok = sh.pairing.lock().check_qr(now, &h, &proof);
                            if ok {
                                finish_pairing(sh, &remote, &name, &platform);
                                send(&mut wr, &mut t, &Control::Paired).await?;
                            } else {
                                sh.pairing.lock().record_reject(now);
                                send(&mut wr, &mut t, &Control::PairFailed { reason: "bad_proof".into() }).await?;
                            }
                        }
                        Control::PairCode => {
                            // A new code request supersedes an unanswered one: drop its pending entry and prompt.
                            if let PairFlow::AwaitVerdict { id, .. } = flow {
                                sh.pending.lock().remove(&id);
                                sh.emit(HostEvent::PairingResolved { id, ok: false });
                                flow = PairFlow::Idle;
                            }
                            let r = sh.pairing.lock().begin_attempt(Instant::now());
                            if let Err(refusal) = r { send(&mut wr, &mut t, &Control::error(refusal.code())).await?; continue; }
                            let nb = nine_proto::random_bytes::<16>();
                            send(&mut wr, &mut t, &Control::Commit { c: B64.encode(commitment(&h, &nb)) }).await?;
                            flow = PairFlow::AwaitNonce { nb };
                        }
                        Control::Nonce { n } => {
                            let PairFlow::AwaitNonce { nb } = flow else { send(&mut wr, &mut t, &Control::error("bad_message")).await?; continue };
                            let Some(na) = B64.decode(n).ok().and_then(|v| <[u8; 16]>::try_from(v).ok()) else {
                                send(&mut wr, &mut t, &Control::error("bad_message")).await?; flow = PairFlow::Idle; continue
                            };
                            let code = sas_code(&h, &na, &nb);
                            send(&mut wr, &mut t, &Control::Reveal { n: B64.encode(nb) }).await?;
                            let id = sh.id();
                            let (tx, rx) = oneshot::channel();
                            sh.pending.lock().insert(id, PendingCode { conn_id, tx: Some(tx) });
                            sh.emit(HostEvent::PairingRequest { id, code: format_code(code), name: name.clone(), platform: platform.clone() });
                            flow = PairFlow::AwaitVerdict { id, phone: None, host: None, rx: Some(rx) };
                        }
                        Control::CodeOk | Control::CodeReject => {
                            let ok = matches!(msg, Control::CodeOk);
                            if let PairFlow::AwaitVerdict { phone, .. } = &mut flow { *phone = Some(ok); }
                            if let Some(done) = settle(sh, &mut flow, &remote, &name, &platform) { send(&mut wr, &mut t, &done).await?; }
                        }
                        Control::Stats { rtt_p50_ms, rtt_p95_ms, sensor_hz, send_hz, battery_pct } => {
                            if let Some(i) = slot_idx {
                                let mut st = sh.state.lock();
                                if let Some(s) = st.slots[i].as_mut()
                                    && let Some(pi) = s.paths.iter().position(|p| p.conn_id == conn_id) {
                                    // Round trip is per link; the rest describes the phone.
                                    s.paths[pi].rtt_p50_ms = rtt_p50_ms; s.paths[pi].rtt_p95_ms = rtt_p95_ms;
                                    s.phone = PhoneStats { rtt_p50_ms, rtt_p95_ms, sensor_hz, send_hz, battery_pct };
                                }
                            }
                        }
                        _ => send(&mut wr, &mut t, &Control::error("bad_message")).await?,
                    }
                }
                v = recv_verdict(rxref) => {
                    if let PairFlow::AwaitVerdict { host, .. } = &mut flow { *host = Some(v); }
                    if let Some(done) = settle(sh, &mut flow, &remote, &name, &platform) { send(&mut wr, &mut t, &done).await?; }
                }
                Some(m) = ctl_rx.recv() => {
                    let bye = matches!(m, Control::Bye { .. });
                    send(&mut wr, &mut t, &m).await?;
                    if bye { return Ok(()); }
                }
                _ = tokio::time::sleep_until(unpaired_deadline), if slot_idx.is_none() => {
                    send(&mut wr, &mut t, &Control::Bye { reason: Some("pairing timeout".into()) }).await?;
                    return Ok(());
                }
                _ = stop.changed() => return Ok(()),
            }
        }
    }.await;

    if let PairFlow::AwaitVerdict { id, .. } = flow {
        sh.pending.lock().remove(&id);
        sh.emit(HostEvent::PairingResolved { id, ok: false });
    }
    sh.pending.lock().retain(|_, p| p.conn_id != conn_id);
    if let Some(i) = slot_idx {
        let why = match &result { Ok(()) => "closed".to_string(), Err(e) => e.to_string() };
        end_path(sh, i, conn_id, &why);
    }
    result
}

fn finish_pairing(sh: &Shared, key: &[u8; 32], name: &str, platform: &str) {
    { let mut s = sh.store.lock(); s.add_paired(key, name, platform); let _ = s.save(); }
    sh.emit(HostEvent::Paired { name: name.into() });
}

/// Resolve numeric comparison once both verdicts are known (or either is a rejection).
fn settle(sh: &Shared, flow: &mut PairFlow, key: &[u8; 32], name: &str, platform: &str) -> Option<Control> {
    let PairFlow::AwaitVerdict { id, phone, host, .. } = flow else { return None };
    let id = *id;
    let out = match (*phone, *host) {
        (Some(false), _) | (_, Some(false)) => {
            sh.pairing.lock().record_reject(Instant::now());
            Control::PairFailed { reason: "rejected".into() }
        }
        (Some(true), Some(true)) => { finish_pairing(sh, key, name, platform); Control::Paired }
        _ => return None,
    };
    sh.pending.lock().remove(&id);
    sh.emit(HostEvent::PairingResolved { id, ok: matches!(out, Control::Paired) });
    *flow = PairFlow::Idle;
    Some(out)
}

fn open_session(sh: &Shared, key: &[u8; 32], name: &str, platform: &str, profile: Option<String>, resume: Option<u8>,
                path: Path) -> Option<(usize, Control, Option<String>)> {
    let mut st = sh.state.lock();
    let idx = st.slots.iter().position(|s| s.as_ref().is_some_and(|s| &s.key == key))
        .or_else(|| resume.map(|r| r as usize).filter(|&r| r < st.slots.len() && st.slots[r].is_none()))
        .or_else(|| st.slots.iter().position(Option::is_none))?;
    let mut sid;
    loop { sid = u32::from_le_bytes(nine_proto::random_bytes::<4>()); if sid != 0 && !st.sessions.contains_key(&sid) { break; } }
    let (k_in, k_out) = (nine_proto::random_bytes::<32>(), nine_proto::random_bytes::<32>());
    let old = st.slots[idx].take();
    let (pad, pad_error) = match old {
        Some(mut o) => {
            st.sessions.remove(&o.session_id);
            // A fresh open (not a join) replaces the whole previous session, on every link it had.
            if o.connected { o.send_all(&Control::Bye { reason: Some("replaced by a newer connection".into()) }); }
            o.apply(InputState::NEUTRAL);
            match o.pad.take() { Some(p) => (Some(p), None), None => create_pad(sh, idx, name) }
        }
        None => create_pad(sh, idx, name),
    };
    st.sessions.insert(sid, idx);
    st.slots[idx] = Some(Slot {
        link: path.link,
        key: *key, name: name.into(), platform: platform.into(), profile: profile.map(|p| p.chars().take(48).collect()),
        paths: vec![path], connected: true, connected_at: Instant::now(), grace_until: None,
        session_id: sid, k_in, k_out, out_counter: Counter::default(), window: ReplayWindow::default(),
        last_input: 0, superseded: 0, first_seen: VecDeque::new(), wins: VecDeque::new(), path_events: VecDeque::new(),
        last_rx: None, last_rx_us: None, last_tx_us: None, state: InputState::NEUTRAL, flags: 0, submitted_once: false,
        neutral_resets: 0, invalid: 0, jitter: Jitter::default(), gaps: Window::new(512), host_proc: Window::new(512),
        pad, pad_error: pad_error.clone(), pad_retry_at: None, phone: PhoneStats::default(),
    });
    drop(st);
    { let mut s = sh.store.lock(); s.touch(key); let _ = s.save(); }
    sh.emit(HostEvent::SlotConnected { slot: idx as u8, name: name.into() });
    Some((idx, session_reply(sh, idx, sid, &k_in, &k_out), pad_error))
}

fn session_reply(sh: &Shared, idx: usize, sid: u32, k_in: &[u8; 32], k_out: &[u8; 32]) -> Control {
    let c = &sh.cfg;
    Control::Session {
        slot: idx as u8, session_id: sid, udp_port: sh.udp_port,
        k_in: SecretB64(B64.encode(k_in)), k_out: SecretB64(B64.encode(k_out)),
        heartbeat_ms: c.heartbeat_ms, idle_heartbeat_ms: c.idle_heartbeat_ms, timeout_ms: c.timeout_ms,
    }
}

/// Attach another authenticated link to the caller's live session (SPEC §8.5). The joining link has
/// completed its own Noise handshake; it may join only a session owned by the same static key, so
/// pairing and identity pinning apply to every link. The slot, virtual pad, held input and counters
/// are untouched: nothing is reset and nothing is released.
fn join_session(sh: &Shared, key: &[u8; 32], sid: u32, path: Path) -> Result<(usize, Control), &'static str> {
    let mut st = sh.state.lock();
    let Some(&idx) = st.sessions.get(&sid) else { return Err("no such session") };
    let Some(s) = st.slots[idx].as_mut() else { return Err("no such session") };
    if &s.key != key { return Err("session belongs to another controller"); }
    if !s.connected { return Err("session ended"); }
    if s.paths.len() >= MAX_PATHS { return Err("too many links"); }
    let label = path.link.label().to_string();
    tracing::info!(slot = idx, link = %label, peer = %path.peer, "link joined session");
    s.link = path.link;
    s.paths.push(path);
    s.note(format!("{label} up"));
    let reply = session_reply(sh, idx, sid, &s.k_in, &s.k_out);
    drop(st);
    sh.emit(HostEvent::PathUp { slot: idx as u8, link: label });
    Ok((idx, reply))
}

fn create_pad(sh: &Shared, idx: usize, name: &str) -> (Option<Box<dyn VirtualPad>>, Option<String>) {
    match sh.backend.create(idx as u8, name) {
        Ok(p) => (Some(p), None),
        Err(e) => {
            tracing::warn!(slot = idx, "virtual controller not created: {e}");
            (None, Some(e.to_string()))
        }
    }
}

/// One link's control connection is gone. While another link of the session is up, nothing else
/// changes (no neutralize, no disconnect): the input timeout still releases everything if the
/// remaining links go silent. The last link ending ends the session: neutralize now and keep the slot
/// (and its virtual pad) for the grace period.
fn end_path(sh: &Shared, idx: usize, conn_id: u64, why: &str) {
    let mut st = sh.state.lock();
    let Some(s) = st.slots[idx].as_mut() else { return };
    let Some(pi) = s.paths.iter().position(|p| p.conn_id == conn_id) else { return };
    let gone = s.paths.remove(pi);
    let label = gone.link.label().to_string();
    if !s.paths.is_empty() {
        s.wins.retain(|w| *w != conn_id);
        s.note(format!("{label} down: {why}"));
        tracing::info!(slot = idx, link = %label, remaining = s.paths.len(), "link left session: {why}");
        drop(st);
        sh.emit(HostEvent::PathDown { slot: idx as u8, link: label, reason: why.into() });
        return;
    }
    s.link = gone.link;
    s.note(format!("{label} down: {why}"));
    s.apply(InputState::NEUTRAL);
    s.connected = false;
    s.grace_until = Some(Instant::now() + Duration::from_secs(sh.cfg.grace_s as u64));
    let (sid, name) = (s.session_id, s.name.clone());
    st.sessions.remove(&sid);
    drop(st);
    sh.emit(HostEvent::SlotDisconnected { slot: idx as u8, name, grace_s: sh.cfg.grace_s });
}

// ---------------------------------------------------------------- UDP input

async fn udp_loop(sh: Arc<Shared>) {
    let mut stop = sh.shutdown.subscribe();
    let mut buf = [0u8; 2048];
    loop {
        tokio::select! {
            r = sh.udp.recv_from(&mut buf) => {
                let Ok((n, from)) = r else { continue };
                handle_datagram(&sh, &buf[..n], Reply::Udp(from), Instant::now());
            }
            _ = stop.changed() => break,
        }
    }
}

pub(crate) fn handle_datagram(sh: &Shared, d: &[u8], from: Reply, now: Instant) {
    let rx_us = mono_us();
    let mut st = sh.state.lock();
    let Ok(hdr) = Header::peek(d) else { st.malformed += 1; return };
    let Some(&idx) = st.sessions.get(&hdr.session_id) else { st.unknown_session += 1; return };
    let Some(s) = st.slots[idx].as_mut() else { return };
    // Authenticate before anything else: nothing below runs for a forged or corrupted datagram.
    let Ok((hdr, pt)) = frame::open(&s.k_in, d) else { s.invalid += 1; return };
    let pi = s.path_of(&from);
    match s.window.check(hdr.counter) {
        Seen::TooOld => return,
        Seen::Duplicate => {
            // The same datagram already arrived over another link. It proves this link is alive and
            // tells how far behind it runs; it is never processed twice.
            if let Some(p) = pi.map(|i| &mut s.paths[i]) {
                p.last_rx = Some(now); p.late += 1; p.reply = Some(from);
                if let Some((_, t)) = s.first_seen.iter().find(|(c, _)| *c == hdr.counter) {
                    p.behind.push(now.duration_since(*t).as_secs_f64() * 1000.0);
                }
            }
            return;
        }
        Seen::New => {}
    }
    if s.first_seen.len() >= 128 { s.first_seen.pop_front(); }
    s.first_seen.push_back((hdr.counter, now));
    if let Some(p) = pi.map(|i| &mut s.paths[i]) {
        p.last_rx = Some(now); p.first += 1; p.reply = Some(from.clone());
        if let Some(prev) = p.last_rx_us { p.gaps.push((rx_us - prev) as f64 / 1000.0); }
        p.last_rx_us = Some(rx_us);
        let id = p.conn_id;
        if s.wins.len() >= 64 { s.wins.pop_front(); }
        s.wins.push_back(id);
    }
    match hdr.ptype {
        PacketType::Input => {
            let Ok(f) = InputFrame::decode(&pt) else { s.invalid += 1; return };
            s.last_rx = Some(now);
            // Input frames are full state snapshots: an older one (a late copy from a slower link, or a
            // reordered datagram) must never restore an older button, trigger or stick state.
            if hdr.counter <= s.last_input { s.superseded += 1; return; }
            s.last_input = hdr.counter;
            if let Some(prev) = s.last_rx_us {
                let gap_ms = (rx_us - prev) as f64 / 1000.0;
                s.gaps.push(gap_ms);
                // Long gaps are what trip the input timeout. Logging the phone-side send gap next to the
                // arrival gap tells "the phone sent late" apart from "the network held the frames".
                if gap_ms > sh.cfg.timeout_ms as f64 && (!s.state.is_neutral() || !f.state.is_neutral()) {
                    let sent_gap_ms = s.last_tx_us.map(|p| f.t_us.saturating_sub(p) as f64 / 1000.0);
                    tracing::info!(slot = idx, arrival_gap_ms = gap_ms, sender_gap_ms = ?sent_gap_ms, held = !f.state.is_neutral(), "input gap");
                }
            }
            s.last_rx_us = Some(rx_us);
            s.last_tx_us = Some(f.t_us);
            s.jitter.update(f.t_us, rx_us);
            s.flags = f.flags;
            let target = if f.flags & FLAG_PAUSED != 0 { InputState::NEUTRAL } else { f.state };
            if s.apply(target) { s.host_proc.push((mono_us() - rx_us) as f64); }
        }
        PacketType::Ping => {
            let Ok(p) = Ping::decode(&pt) else { s.invalid += 1; return };
            s.last_rx = Some(now);
            // Answer on the link the ping came over: the phone measures each link's round trip.
            let pong = Pong { t_us: p.t_us, id: p.id, host_rx_us: rx_us, host_tx_us: mono_us() };
            let out = frame::seal(&s.k_out, Header { ptype: PacketType::Pong, session_id: s.session_id, counter: s.out_counter.bump() }, &pong.encode());
            from.send(sh, out);
        }
        PacketType::Bye => { s.apply(InputState::NEUTRAL); }
        PacketType::Pong | PacketType::Feedback => { s.invalid += 1; }
    }
}

// ---------------------------------------------------------------- timers

async fn tick_loop(sh: Arc<Shared>) {
    let mut stop = sh.shutdown.subscribe();
    let mut iv = tokio::time::interval(TICK);
    iv.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        tokio::select! { _ = iv.tick() => tick(&sh, Instant::now()), _ = stop.changed() => break }
    }
}

fn tick(sh: &Shared, now: Instant) {
    let timeout = Duration::from_millis(sh.cfg.timeout_ms as u64);
    let mut events = Vec::new();
    {
        let mut guard = sh.state.lock();
        let st = &mut *guard;
        for (i, slot) in st.slots.iter_mut().enumerate() {
            let Some(s) = slot.as_mut() else { continue };
            if s.connected && !s.state.is_neutral() && s.last_rx.is_none_or(|t| now.duration_since(t) > timeout) {
                s.apply(InputState::NEUTRAL);
                s.neutral_resets += 1;
                events.push(HostEvent::NeutralReset { slot: i as u8 });
            }
            if s.connected && s.pad.is_none() && s.pad_retry_at.is_none_or(|t| now >= t) {
                s.pad_retry_at = Some(now + Duration::from_secs(1));
                if sh.backend.status().available {
                    let (pad, err) = create_pad(sh, i, &s.name.clone());
                    if pad.is_some() {
                        tracing::info!(slot = i, "virtual controller created after the backend became available");
                        s.pad = pad; s.pad_error = None; s.submitted_once = false;
                        let cur = s.state; s.apply(cur);
                        events.push(HostEvent::Notice { level: "info".into(), message: format!("Controller ready for {}", s.name) });
                    } else { s.pad_error = err; }
                }
            }
            if let Some(fb) = s.pad.as_mut().and_then(|p| p.poll_feedback()) {
                send_feedback(sh, s, fb);
            }
            if !s.connected && s.grace_until.is_some_and(|g| now >= g) {
                *slot = None; // drops the virtual pad
                events.push(HostEvent::SlotReleased { slot: i as u8 });
            }
        }
    }
    for e in events { sh.emit(e); }
}

fn send_feedback(sh: &Shared, s: &mut Slot, fb: Feedback) {
    if !s.connected { return; }
    // One sealed datagram, sent over every link: the phone keeps the first copy and drops the rest.
    let out = frame::seal(&s.k_out, Header { ptype: PacketType::Feedback, session_id: s.session_id, counter: s.out_counter.bump() }, &fb.encode());
    for p in &s.paths { if let Some(to) = &p.reply { to.send(sh, out.clone()); } }
}

#[cfg(test)]
mod health_tests {
    use super::{slot_health, STALE_AFTER_MS};

    #[test]
    fn a_session_is_live_only_while_frames_arrive() {
        assert_eq!(slot_health(true, Some(0)), "live");
        assert_eq!(slot_health(true, Some(STALE_AFTER_MS)), "live");
        assert_eq!(slot_health(true, Some(STALE_AFTER_MS + 1)), "stale");
        assert_eq!(slot_health(true, None), "waiting");
        // Disconnected but within the reconnect grace: the slot is held, never "live".
        assert_eq!(slot_health(false, Some(10)), "holding");
        assert_eq!(slot_health(false, None), "holding");
    }
}
