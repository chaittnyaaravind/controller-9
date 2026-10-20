// `controller9 host` — headless host.  `controller9 sim` — simulated controller for tests and benchmarks.
use anyhow::{bail, Context, Result};
use clap::{Parser, Subcommand};
use nine_host_core::backend::{Backend, MockBackend};
use nine_host_core::client::{Connection, InputSender};
use nine_host_core::{Host, HostEvent, Store};
use nine_proto::control::ControllerHello;
use nine_proto::input::{axis_from_unit, Button, InputState};
use nine_proto::noise::KeyPair;
use nine_proto::pairing::{format_code, PairUri};
use std::io::Write;
use std::net::{SocketAddr, ToSocketAddrs};
use std::sync::Arc;
use std::time::{Duration, Instant};

#[derive(Parser)]
#[command(name = "controller9", version, about = "Controller-9 host and test tools (legacy name: ninectl)")]
struct Cli { #[command(subcommand)] cmd: Cmd }

#[derive(Subcommand)]
enum Cmd {
    /// Run the host without a window.
    Host {
        /// Use the recording mock backend (no OS-visible controller).
        #[arg(long)] mock: bool,
        /// Keep identity and pairings in memory only.
        #[arg(long)] ephemeral: bool,
        /// Use this directory for identity/pairings instead of the per-user config dir (test rigs).
        #[arg(long)] config_dir: Option<std::path::PathBuf>,
        #[arg(long)] bind: Option<String>,
        #[arg(long)] tcp_port: Option<u16>,
        #[arg(long)] udp_port: Option<u16>,
        /// Open pairing at start and print the QR code.
        #[arg(long)] pair: bool,
        /// Approve numeric-comparison requests without asking (test rigs only).
        #[arg(long)] auto_approve: bool,
        #[arg(long)] no_advertise: bool,
        /// Do not scan for phones offering Bluetooth LE.
        #[arg(long)] no_ble: bool,
        /// Stream-binding TCP port (SPEC §8.2); 0 disables it.
        #[arg(long)] stream_port: Option<u16>,
        /// Re-open the pairing window whenever it closes (unattended test rigs only).
        #[arg(long)] keep_pairing_open: bool,
        /// Print a JSON line of per-slot diagnostics every N seconds (0 = off).
        #[arg(long, default_value_t = 0)] stats_every: u64,
        /// Test only: every N seconds send each connected phone a heavy-motor, then a light-motor rumble.
        #[arg(long, default_value_t = 0)] rumble_test_every: u64,
        /// Test only: print a JSON "input:" line whenever a slot's received state changes (sampled every 4 ms).
        #[arg(long)] trace_input: bool,
    },
    /// Windows: install the ViGEmBus driver games need (official release, checksum-verified, UAC prompt).
    InstallDriver,
    /// Print the virtual-controller backend status for this OS.
    Status,
    /// Simulated controller: pair, open a session, stream input, report RTT.
    Sim {
        /// host:port of the control channel.
        #[arg(long)] host: Option<String>,
        /// Pair using a ninectl://pair URI (from the host's QR code).
        #[arg(long)] uri: Option<String>,
        /// Pair by numeric comparison (you confirm the code).
        #[arg(long)] code: bool,
        #[arg(long, default_value_t = 120.0)] rate: f64,
        #[arg(long, default_value_t = 10.0)] duration: f64,
        /// Simulated send-side loss 0..1.
        #[arg(long, default_value_t = 0.0)] loss: f64,
        #[arg(long, default_value = "Controller-9 sim")] name: String,
        #[arg(long)] json: bool,
        /// Link to use: "ip" (TCP control + UDP input) or "stream" (single TCP stream, SPEC §8.2).
        #[arg(long, default_value = "ip")] link: String,
        /// Stream-binding port on the host (with --link stream).
        #[arg(long, default_value_t = nine_proto::link::STREAM_TCP_PORT)] stream_port: u16,
        /// Input to send: "wave" (A toggles, left stick X sine, right trigger wave) or "sweep"
        /// (every button, stick direction and trigger in turn, SWEEP_STEP_S each — for mapping checks).
        #[arg(long, default_value = "wave")] pattern: String,
    },
}

const SWEEP_STEP_S: f64 = 0.4;

/// The "sweep" pattern: one control at a time, so a tester (evtest, SDL, a browser) can confirm each mapping.
/// Each step is held for the first 75 % of SWEEP_STEP_S, then everything is neutral, so every press has an edge.
fn sweep_state(t: f64) -> (InputState, String) {
    const AXES: [&str; 10] = ["lx-", "lx+", "ly-", "ly+", "rx-", "rx+", "ry-", "ry+", "lt", "rt"];
    let n = Button::ALL.len() + AXES.len();
    let step = (t / SWEEP_STEP_S) as usize % n;
    let label = if step < Button::ALL.len() { Button::ALL[step].name().to_string() } else { AXES[step - Button::ALL.len()].to_string() };
    if (t / SWEEP_STEP_S).fract() >= 0.75 { return (InputState::NEUTRAL, label); }
    let mut s = InputState::default();
    match label.as_str() {
        "lx-" => s.lx = -32767, "lx+" => s.lx = 32767, "ly-" => s.ly = -32767, "ly+" => s.ly = 32767,
        "rx-" => s.rx = -32767, "rx+" => s.rx = 32767, "ry-" => s.ry = -32767, "ry+" => s.ry = 32767,
        "lt" => s.lt = 65535, "rt" => s.rt = 65535,
        _ => s.buttons = Button::ALL[step].mask(),
    }
    (s, label)
}

fn install_driver() -> Result<()> {
    #[cfg(windows)] {
        let o = nine_pad_windows::driver::install().map_err(|e| anyhow::anyhow!(e))?;
        println!("{}", serde_json::json!({ "installed": true, "restart_needed": o == nine_pad_windows::driver::Outcome::InstalledRestartNeeded, "status": native_backend().status() }));
        Ok(())
    }
    #[cfg(not(windows))] { anyhow::bail!("install-driver is for Windows; Linux needs no driver, macOS has none to install") }
}

fn native_backend() -> Arc<dyn Backend> {
    #[cfg(target_os = "linux")] { Arc::new(nine_pad_linux::UinputBackend::default()) }
    #[cfg(target_os = "macos")] { nine_pad_macos::native_backend() }
    #[cfg(windows)] { nine_pad_windows::native_backend() }
    #[cfg(not(any(target_os = "linux", target_os = "macos", windows)))] { Arc::new(nine_host_core::backend::BlockedBackend { status: nine_host_core::BackendStatus {
        name: "none".into(), kind: nine_host_core::BackendKind::Blocked, available: false, max_pads: 0, detail: "unsupported OS".into(), remedy: None } }) }
}

fn print_qr(uri: &str) {
    if let Ok(c) = qrcode::QrCode::new(uri.as_bytes()) {
        println!("{}", c.render::<qrcode::render::unicode::Dense1x2>().quiet_zone(true).build());
    }
    println!("{uri}");
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt().with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into())).init();
    match Cli::parse().cmd {
        Cmd::InstallDriver => install_driver(),
        Cmd::Status => { println!("{}", serde_json::to_string_pretty(&native_backend().status())?); Ok(()) }
        Cmd::Host { mock, ephemeral, config_dir, bind, tcp_port, udp_port, pair, auto_approve, no_advertise, no_ble, stream_port, stats_every, rumble_test_every, keep_pairing_open, trace_input } =>
            run_host(mock, ephemeral, config_dir, bind, tcp_port, udp_port, pair, auto_approve, no_advertise, no_ble, stream_port, stats_every, rumble_test_every, keep_pairing_open, trace_input).await,
        Cmd::Sim { host, uri, code, rate, duration, loss, name, json, link, stream_port, pattern } =>
            run_sim(host, uri, code, rate, duration, loss, name, json, &link, stream_port, &pattern).await,
    }
}

#[allow(clippy::too_many_arguments)]
async fn run_host(mock: bool, ephemeral: bool, config_dir: Option<std::path::PathBuf>, bind: Option<String>, tcp: Option<u16>, udp: Option<u16>, pair: bool, auto: bool, no_adv: bool,
                  no_ble: bool, stream_port: Option<u16>, stats_every: u64, rumble_test_every: u64, keep_pairing_open: bool, trace_input: bool) -> Result<()> {
    let mut store = if ephemeral { Store::in_memory(Default::default()) } else {
        Store::open(&match config_dir { Some(d) => d, None => Store::default_dir().context("no config directory")? })?
    };
    if let Some(b) = bind { store.config.bind = b; }
    if let Some(p) = tcp { store.config.tcp_port = p; }
    if let Some(p) = udp { store.config.udp_port = p; }
    if no_adv { store.config.advertise = false; }
    if no_ble { store.config.ble = false; }
    if let Some(p) = stream_port { store.config.stream_port = (p != 0).then_some(p); }
    let backend: Arc<dyn Backend> = if mock { Arc::new(MockBackend::default()) } else { native_backend() };
    let st = backend.status();
    println!("backend: {} [{:?}] {}", st.name, st.kind, st.detail);
    if let Some(r) = &st.remedy { println!("  fix: {r}"); }
    let host = Host::start(store, backend).await?;
    println!("listening tcp/{} udp/{} stream/{}", host.tcp_port(), host.udp_port(),
        host.stream_port().map(|p| p.to_string()).unwrap_or_else(|| "off".into()));
    for a in nine_host_core::netif::local_ipv4() { println!("  reachable on {} ({}, {})", a.ip, a.label, a.iface); }
    if pair { print_qr(&host.open_pairing(None).uri); }
    if keep_pairing_open {
        let h = host.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(5)).await;
                if !h.snapshot().pairing_open { println!("pairing re-opened: {}", h.open_pairing(None).uri); }
            }
        });
    }
    if rumble_test_every > 0 {
        let h = host.clone();
        tokio::spawn(async move {
            let mut heavy = true;
            loop {
                tokio::time::sleep(Duration::from_secs(rumble_test_every)).await;
                let (low, high) = if heavy { (52000, 0) } else { (0, 52000) };
                for s in h.snapshot().slots.iter().filter(|s| s.connected) {
                    h.rumble(s.slot, low, high, 400);
                    println!("rumble: {}", serde_json::json!({ "t": nine_host_core::time::rfc3339_now(), "slot": s.slot, "low": low, "high": high, "ms": 400 }));
                }
                heavy = !heavy;
            }
        });
    }
    if trace_input {
        let h = host.clone();
        tokio::spawn(async move {
            let mut last: std::collections::HashMap<u8, String> = std::collections::HashMap::new();
            loop {
                tokio::time::sleep(Duration::from_millis(4)).await;
                for s in h.snapshot().slots.iter().filter(|s| s.connected) {
                    let now = serde_json::to_string(&s.state).unwrap_or_default();
                    if last.get(&s.slot) != Some(&now) {
                        let ms = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
                        println!("input: {}", serde_json::json!({ "t": nine_host_core::time::rfc3339_now(), "ms": ms, "slot": s.slot, "state": s.state }));
                        last.insert(s.slot, now);
                    }
                }
            }
        });
    }
    if stats_every > 0 {
        let h = host.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(stats_every)).await;
                let snap = h.snapshot();
                println!("ble: {}", serde_json::to_string(&snap.ble).unwrap_or_default());
                for s in &snap.slots {
                    let paths: Vec<serde_json::Value> = s.paths.iter().map(|p| serde_json::json!({
                        "link": p.link_label, "health": p.health, "silent_ms": p.silent_ms, "first": p.first, "late": p.late_copies,
                        "first_share": (p.first_share * 100.0).round() / 100.0, "behind_p50_ms": p.behind_p50_ms, "behind_p95_ms": p.behind_p95_ms,
                        "gap_p95_ms": p.gap_p95_ms, "rtt_p50_ms": p.rtt_p50_ms, "rtt_p95_ms": p.rtt_p95_ms, "preferred": p.preferred })).collect();
                    println!("stats: {}", serde_json::json!({
                        "t": nine_host_core::time::rfc3339_now(), "slot": s.slot, "name": s.name, "link": s.link_label, "connected": s.connected, "silent_ms": s.silent_ms,
                        "frames": s.frames, "rate_hz": s.rate_hz, "gap_p50_ms": s.interarrival_p50_ms, "gap_p95_ms": s.interarrival_p95_ms,
                        "jitter_ms": s.jitter_ms, "lost": s.lost, "stale": s.stale, "invalid": s.invalid, "neutral_resets": s.neutral_resets, "health": s.health, "device_id": &s.device_id,
                        "phone_rtt_p50_ms": s.phone.rtt_p50_ms, "phone_rtt_p95_ms": s.phone.rtt_p95_ms, "phone_sensor_hz": s.phone.sensor_hz,
                        "phone_send_hz": s.phone.send_hz, "battery": s.phone.battery_pct, "host_proc_p95_us": s.host_processing_p95_us,
                        "motion": s.motion, "state": s.state,
                        "redundant": s.redundant, "duplicates": s.duplicates, "superseded": s.superseded,
                        "paths": paths, "path_events": s.path_events }));
                }
            }
        });
    }
    let mut ev = host.subscribe();
    let h2 = host.clone();
    tokio::spawn(async move {
        while let Ok(e) = ev.recv().await {
            match &e {
                HostEvent::PairingRequest { id, code, name, .. } => {
                    println!("\nPairing request from \"{name}\" — code {code}");
                    let allow = auto || {
                        print!("Does the phone show the same code? [y/N] ");
                        let _ = std::io::stdout().flush();
                        let mut s = String::new();
                        tokio::task::spawn_blocking(move || { let _ = std::io::stdin().read_line(&mut s); s }).await.map(|s| s.trim().eq_ignore_ascii_case("y")).unwrap_or(false)
                    };
                    h2.answer(*id, allow);
                }
                other => println!("event: {}", serde_json::to_string(other).unwrap_or_default()),
            }
        }
    });
    tokio::signal::ctrl_c().await?;
    println!("shutting down: neutralizing controllers");
    host.shutdown().await;
    Ok(())
}

fn sim_identity() -> KeyPair {
    let path = Store::default_dir().map(|d| d.join("sim_key"));
    if let Some(Ok(b)) = path.as_ref().map(std::fs::read) && let Ok(a) = <[u8; 32]>::try_from(b) { return KeyPair::from_secret(a); }
    let kp = KeyPair::generate();
    if let Some(p) = path { let _ = std::fs::create_dir_all(p.parent().unwrap()); let _ = std::fs::write(p, kp.secret_bytes()); }
    kp
}

#[allow(clippy::too_many_arguments)]
async fn run_sim(host: Option<String>, uri: Option<String>, code: bool, rate: f64, duration: f64, loss: f64, name: String, json: bool,
                 link: &str, stream_port: u16, pattern: &str) -> Result<()> {
    if !matches!(pattern, "wave" | "sweep") { bail!("unknown --pattern {pattern} (use wave or sweep)"); }
    let uri = uri.map(|u| PairUri::parse(&u).context("invalid ninectl:// pairing URI")).transpose()?;
    let target = match (&host, &uri) {
        (Some(h), _) => h.clone(),
        (None, Some(u)) => format!("{}:{}", u.addr, u.port),
        _ => bail!("--host or --uri required"),
    };
    let addr: SocketAddr = target.to_socket_addrs()?.next().context("cannot resolve host")?;
    let id = sim_identity();
    let hello = ControllerHello { proto: 1, name, platform: "sim".into(), app: env!("CARGO_PKG_VERSION").into() };
    let t_connect = Instant::now();
    let (mut c, stream_path) = match link {
        "stream" => {
            let s = tokio::net::TcpStream::connect((addr.ip(), stream_port)).await.context("stream binding port unreachable")?;
            s.set_nodelay(true)?;
            let (rd, wr, path) = nine_host_core::client::stream_link(s);
            (Connection::connect_over(rd, wr, &id, &hello).await?, Some(path))
        }
        "ip" => (Connection::connect(addr, &id, &hello).await?, None),
        other => bail!("unknown --link {other} (use ip or stream)"),
    };
    eprintln!("connected to \"{}\" ({})", c.host.name, c.host.id);
    if let Some(u) = &uri { c.pair_qr(u).await?; eprintln!("paired by QR"); }
    if code {
        let n = c.pair_code(|n| { eprintln!("code on this controller: {} — confirm on the host", format_code(n)); true }).await?;
        eprintln!("paired by code {}", format_code(n));
    }
    let info = c.open(None, Some("sim")).await?;
    let setup_ms = t_connect.elapsed().as_secs_f64() * 1000.0;
    if let Some(w) = &info.backend_warning { eprintln!("warning: host has no virtual controller: {w}"); }
    eprintln!("session open: slot {} (handshake+session {setup_ms:.1} ms)", info.slot + 1);
    let mut tx = match stream_path { Some(p) => InputSender::over(p, info), None => InputSender::new(addr, info).await? };
    let period = Duration::from_secs_f64(1.0 / rate);
    let start = Instant::now();
    let mut next = tokio::time::Instant::now();
    let (mut frames, mut dropped) = (0u64, 0u64);
    let mut last_ping = Instant::now() - Duration::from_secs(1);
    let mut rng = 0x9E37_79B9_7F4A_7C15u64;
    let mut last_label = String::new();
    while start.elapsed().as_secs_f64() < duration {
        let t = start.elapsed().as_secs_f64();
        let state = if pattern == "sweep" {
            let (s, label) = sweep_state(t);
            if label != last_label { eprintln!("sweep {t:6.2}s {label}"); last_label = label; }
            s
        } else {
            InputState {
                buttons: if (t as u64).is_multiple_of(2) { Button::A.mask() } else { 0 },
                lx: axis_from_unit((t * std::f64::consts::PI).sin()),
                rt: (40000.0 + 20000.0 * (t * 2.0).sin()) as u16,
                ..Default::default()
            }
        };
        rng ^= rng << 13; rng ^= rng >> 7; rng ^= rng << 17;
        if (rng % 10_000) as f64 / 10_000.0 < loss { dropped += 1; tx.seal(nine_proto::frame::PacketType::Input, &[]); } else { tx.send_input(state, 0).await?; }
        frames += 1;
        if last_ping.elapsed() >= Duration::from_millis(100) { tx.ping().await?; last_ping = Instant::now(); }
        next += period;
        tx.pump(next.saturating_duration_since(tokio::time::Instant::now())).await;
    }
    tx.send_input(InputState::NEUTRAL, 0).await?;
    tx.pump(Duration::from_millis(100)).await;
    let r = &tx.rtt_ms;
    let elapsed = start.elapsed().as_secs_f64();
    let summary = serde_json::json!({
        "frames": frames, "seconds": elapsed, "rate_hz": frames as f64 / elapsed, "simulated_drops": dropped,
        "rtt_samples": r.len(), "rtt_p50_ms": r.percentile(50.0), "rtt_p95_ms": r.percentile(95.0), "rtt_p99_ms": r.percentile(99.0),
        "rtt_max_ms": r.percentile(100.0), "setup_ms": setup_ms, "feedback_events": tx.feedback.len(),
        "link": link, "note": "RTT is protocol ping/pong round trip, not end-to-end game input latency",
    });
    if json { println!("{summary}"); } else { println!("{}", serde_json::to_string_pretty(&summary)?); }
    c.send(&nine_proto::control::Control::Bye { reason: None }).await.ok();
    Ok(())
}
