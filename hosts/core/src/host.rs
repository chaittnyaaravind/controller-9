//! Host runtime: TCP control server, UDP input receiver, slots, timers, pairing flows.
use crate::backend::{Backend, BackendStatus, VirtualPad};
use crate::pairing::Pairing;
use crate::store::{HostConfig, PairedDevice, Store};
use crate::time::mono_us;
use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use nine_proto::control::{self, Control, ControllerHello, HostHello, InitHello, SecretB64};
use nine_proto::frame::{self, Counter, Feedback, Freshness, Header, InputFrame, PacketType, Ping, Pong};
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
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
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
    pub connected: bool,
    pub grace_s_left: Option<u32>,
    pub name: String,
    pub platform: String,
    pub profile: Option<String>,
    pub connected_s: u64,
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
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct HostSnapshot {
    pub name: String,
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

struct Slot {
    key: [u8; 32],
    name: String,
    platform: String,
    profile: Option<String>,
    conn_id: u64,
    connected: bool,
    connected_at: Instant,
    grace_until: Option<Instant>,
    session_id: u32,
    k_in: [u8; 32],
    k_out: [u8; 32],
    out_counter: Counter,
    fresh: Freshness,
    last_rx: Option<Instant>,
    last_rx_us: Option<u64>,
    reply: Option<SocketAddr>,
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
    ctl_tx: mpsc::UnboundedSender<Control>,
    phone: PhoneStats,
}

impl Slot {
    fn apply(&mut self, s: InputState) -> bool {
        if self.submitted_once && s == self.state { return false; }
        self.state = s;
        self.submitted_once = true;
        if let Some(p) = self.pad.as_mut() {
            if let Err(e) = p.submit(&s) { self.pad_error = Some(e.to_string()); }
        }
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

struct Shared {
    secret: [u8; 32],
    public: [u8; 32],
    host_id: String,
    cfg: HostConfig,
    tcp_port: u16,
    udp_port: u16,
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
    fn id(&self) -> u64 { self.next_id.fetch_add(1, Ordering::Relaxed) }
}

#[derive(Clone)]
pub struct Host { sh: Arc<Shared> }

#[derive(Debug, thiserror::Error)]
pub enum HostError {
    #[error("cannot bind {0}: {1}")]
    Bind(String, std::io::Error),
}

impl Host {
    pub async fn start(store: Store, backend: Arc<dyn Backend>) -> Result<Host, HostError> {
        let cfg = store.config.clone();
        let tcp = TcpListener::bind((cfg.bind.as_str(), cfg.tcp_port)).await
            .map_err(|e| HostError::Bind(format!("tcp/{}", cfg.tcp_port), e))?;
        let udp = UdpSocket::bind((cfg.bind.as_str(), cfg.udp_port)).await
            .map_err(|e| HostError::Bind(format!("udp/{}", cfg.udp_port), e))?;
        let (tcp_port, udp_port) = (tcp.local_addr().unwrap().port(), udp.local_addr().unwrap().port());
        let max = (cfg.max_slots as usize).min(nine_proto::MAX_SLOTS).min(backend.status().max_pads.max(1));
        let state = State { slots: (0..max).map(|_| None).collect(), ..Default::default() };
        let (shutdown, _) = watch::channel(false);
        let sh = Arc::new(Shared {
            secret: store.identity.secret_bytes(),
            public: store.identity.public,
            host_id: nine_proto::host_id(&store.identity.public),
            cfg: cfg.clone(), tcp_port, udp_port,
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
        tokio::spawn(accept_loop(sh.clone(), tcp));
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
        let uri = PairUri { addr, port: self.sh.tcp_port, host_static: self.sh.public, token, name: self.sh.cfg.name.clone() }.to_uri();
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
                if Some(s.key.to_vec()) == key { let _ = s.ctl_tx.send(Control::Bye { reason: Some("unpaired".into()) }); }
            }
        }
        removed
    }

    pub fn disconnect_slot(&self, slot: u8) {
        let st = self.sh.state.lock();
        if let Some(Some(s)) = st.slots.get(slot as usize) { let _ = s.ctl_tx.send(Control::Bye { reason: Some("disconnected by host".into()) }); }
    }

    /// Ask the game's controller to rumble the phone (manual test from the host UI).
    pub fn test_rumble(&self, slot: u8) {
        let st = self.sh.state.lock();
        if let Some(Some(s)) = st.slots.get(slot as usize) { let _ = s.ctl_tx.send(Control::Rumble { low: 30000, high: 30000, ms: 250 }); }
    }

    pub fn snapshot(&self) -> HostSnapshot {
        let sh = &self.sh;
        let now = Instant::now();
        let (pairing_open, pairing_seconds_left) = { let p = sh.pairing.lock(); (p.is_open(now), p.seconds_left(now)) };
        let paired = sh.store.lock().paired.clone();
        let st = sh.state.lock();
        let slots = st.slots.iter().enumerate().filter_map(|(i, s)| s.as_ref().map(|s| {
            let p50 = s.gaps.percentile(50.0);
            SlotSnapshot {
                slot: i as u8, connected: s.connected,
                grace_s_left: s.grace_until.map(|g| g.saturating_duration_since(now).as_secs() as u32),
                name: s.name.clone(), platform: s.platform.clone(), profile: s.profile.clone(),
                connected_s: now.duration_since(s.connected_at).as_secs(),
                frames: s.fresh.accepted(), rate_hz: p50.filter(|v| *v > 0.0).map(|v| 1000.0 / v),
                lost: s.fresh.lost(), stale: s.fresh.stale(), invalid: s.invalid, jitter_ms: s.jitter.ms(),
                interarrival_p50_ms: p50, interarrival_p95_ms: s.gaps.percentile(95.0),
                host_processing_p95_us: s.host_proc.percentile(95.0), neutral_resets: s.neutral_resets,
                motion: s.flags & nine_proto::input::FLAG_MOTION != 0, phone: s.phone.clone(),
                state: StateDto { buttons: s.state.buttons, lx: s.state.lx, ly: s.state.ly, rx: s.state.rx, ry: s.state.ry, lt: s.state.lt, rt: s.state.rt },
                pad_ok: s.pad.is_some() && s.pad_error.is_none(), pad_error: s.pad_error.clone(),
            }
        })).collect();
        HostSnapshot {
            name: sh.cfg.name.clone(), id: sh.host_id.clone(), os: std::env::consts::OS.into(),
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
                let _ = s.ctl_tx.send(Control::Bye { reason: Some("host shutting down".into()) });
            }
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
        let _ = self.sh.shutdown.send(true);
        self.sh.advertising.lock().take();
    }
}

// ---------------------------------------------------------------- TCP control

async fn accept_loop(sh: Arc<Shared>, listener: TcpListener) {
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
                    let r = handle_conn(&sh2, stream, peer).await;
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

async fn read_frame(rd: &mut OwnedReadHalf, max: usize) -> std::io::Result<Vec<u8>> {
    let len = rd.read_u16().await? as usize;
    if len == 0 || len > max {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "bad frame length"));
    }
    let mut b = vec![0u8; len];
    rd.read_exact(&mut b).await?;
    Ok(b)
}

async fn send(wr: &mut OwnedWriteHalf, t: &mut Transport, m: &Control) -> std::io::Result<()> {
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
    let (mut rd, mut wr) = stream.into_split();
    let mut hs = Handshake::new(Role::Responder, KeyPair::from_secret(sh.secret));
    let (mut t, hello, supported) = tokio::time::timeout(HANDSHAKE_TIMEOUT, async {
        let m1 = read_frame(&mut rd, control::MAX_PREHANDSHAKE_FRAME).await?;
        let p1 = hs.read_message(&m1).map_err(io_err)?;
        let supported = serde_json::from_slice::<InitHello>(&p1).map(|h| h.proto.contains(&1)).unwrap_or(false);
        let hh = HostHello { proto: 1, name: sh.cfg.name.clone(), id: sh.host_id.clone(), os: std::env::consts::OS.into() };
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
    let conn_id = sh.id();
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
                        Control::Open { resume_slot, profile } => {
                            if !sh.store.lock().is_paired(&remote) {
                                send(&mut wr, &mut t, &Control::PairRequired { modes: vec!["qr".into(), "code".into()] }).await?;
                                continue;
                            }
                            match open_session(sh, &remote, &name, &platform, profile, resume_slot, conn_id, &ctl_tx) {
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
                                if let Some(s) = st.slots[i].as_mut().filter(|s| s.conn_id == conn_id) {
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
    if let Some(i) = slot_idx { end_session(sh, i, conn_id); }
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

#[allow(clippy::too_many_arguments)]
fn open_session(sh: &Shared, key: &[u8; 32], name: &str, platform: &str, profile: Option<String>, resume: Option<u8>,
                conn_id: u64, ctl_tx: &mpsc::UnboundedSender<Control>) -> Option<(usize, Control, Option<String>)> {
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
            if o.conn_id != conn_id && o.connected { let _ = o.ctl_tx.send(Control::Bye { reason: Some("replaced by a newer connection".into()) }); }
            o.apply(InputState::NEUTRAL);
            match o.pad.take() { Some(p) => (Some(p), None), None => create_pad(sh, idx, name) }
        }
        None => create_pad(sh, idx, name),
    };
    st.sessions.insert(sid, idx);
    st.slots[idx] = Some(Slot {
        key: *key, name: name.into(), platform: platform.into(), profile: profile.map(|p| p.chars().take(48).collect()),
        conn_id, connected: true, connected_at: Instant::now(), grace_until: None,
        session_id: sid, k_in, k_out, out_counter: Counter::default(), fresh: Freshness::default(),
        last_rx: None, last_rx_us: None, reply: None, state: InputState::NEUTRAL, flags: 0, submitted_once: false,
        neutral_resets: 0, invalid: 0, jitter: Jitter::default(), gaps: Window::new(512), host_proc: Window::new(512),
        pad, pad_error: pad_error.clone(), ctl_tx: ctl_tx.clone(), phone: PhoneStats::default(),
    });
    drop(st);
    { let mut s = sh.store.lock(); s.touch(key); let _ = s.save(); }
    sh.emit(HostEvent::SlotConnected { slot: idx as u8, name: name.into() });
    let c = &sh.cfg;
    Some((idx, Control::Session {
        slot: idx as u8, session_id: sid, udp_port: sh.udp_port,
        k_in: SecretB64(B64.encode(k_in)), k_out: SecretB64(B64.encode(k_out)),
        heartbeat_ms: c.heartbeat_ms, idle_heartbeat_ms: c.idle_heartbeat_ms, timeout_ms: c.timeout_ms,
    }, pad_error))
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

/// Control connection gone: neutralize now, keep the slot (and its virtual pad) for the grace period.
fn end_session(sh: &Shared, idx: usize, conn_id: u64) {
    let mut st = sh.state.lock();
    let Some(s) = st.slots[idx].as_mut().filter(|s| s.conn_id == conn_id) else { return };
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
                handle_datagram(&sh, &buf[..n], from, Instant::now());
            }
            _ = stop.changed() => break,
        }
    }
}

fn handle_datagram(sh: &Shared, d: &[u8], from: SocketAddr, now: Instant) {
    let rx_us = mono_us();
    let mut st = sh.state.lock();
    let Ok(hdr) = Header::peek(d) else { st.malformed += 1; return };
    let Some(&idx) = st.sessions.get(&hdr.session_id) else { st.unknown_session += 1; return };
    let Some(s) = st.slots[idx].as_mut() else { return };
    let Ok((hdr, pt)) = frame::open(&s.k_in, d) else { s.invalid += 1; return };
    if !s.fresh.accept(hdr.counter) { return; }
    s.reply = Some(from);
    match hdr.ptype {
        PacketType::Input => {
            let Ok(f) = InputFrame::decode(&pt) else { s.invalid += 1; return };
            s.last_rx = Some(now);
            if let Some(prev) = s.last_rx_us { s.gaps.push((rx_us - prev) as f64 / 1000.0); }
            s.last_rx_us = Some(rx_us);
            s.jitter.update(f.t_us, rx_us);
            s.flags = f.flags;
            let target = if f.flags & FLAG_PAUSED != 0 { InputState::NEUTRAL } else { f.state };
            if s.apply(target) { s.host_proc.push((mono_us() - rx_us) as f64); }
        }
        PacketType::Ping => {
            let Ok(p) = Ping::decode(&pt) else { s.invalid += 1; return };
            s.last_rx = Some(now);
            let pong = Pong { t_us: p.t_us, id: p.id, host_rx_us: rx_us, host_tx_us: mono_us() };
            let out = frame::seal(&s.k_out, Header { ptype: PacketType::Pong, session_id: s.session_id, counter: s.out_counter.next() }, &pong.encode());
            let _ = sh.udp.try_send_to(&out, from);
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
    if let Some(to) = s.reply.filter(|_| s.connected) {
        let out = frame::seal(&s.k_out, Header { ptype: PacketType::Feedback, session_id: s.session_id, counter: s.out_counter.next() }, &fb.encode());
        let _ = sh.udp.try_send_to(&out, to);
    }
}
