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
    },
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
    },
}

fn native_backend() -> Arc<dyn Backend> {
    #[cfg(target_os = "linux")] { Arc::new(nine_pad_linux::UinputBackend::default()) }
    #[cfg(target_os = "macos")] { Arc::new(nine_pad_macos::IoHidBackend) }
    #[cfg(windows)] { Arc::new(nine_pad_windows::VhfBackend::default()) }
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
        Cmd::Status => { println!("{}", serde_json::to_string_pretty(&native_backend().status())?); Ok(()) }
        Cmd::Host { mock, ephemeral, bind, tcp_port, udp_port, pair, auto_approve, no_advertise, no_ble, stream_port } =>
            run_host(mock, ephemeral, bind, tcp_port, udp_port, pair, auto_approve, no_advertise, no_ble, stream_port).await,
        Cmd::Sim { host, uri, code, rate, duration, loss, name, json, link, stream_port } =>
            run_sim(host, uri, code, rate, duration, loss, name, json, &link, stream_port).await,
    }
}

#[allow(clippy::too_many_arguments)]
async fn run_host(mock: bool, ephemeral: bool, bind: Option<String>, tcp: Option<u16>, udp: Option<u16>, pair: bool, auto: bool, no_adv: bool,
                  no_ble: bool, stream_port: Option<u16>) -> Result<()> {
    let mut store = if ephemeral { Store::in_memory(Default::default()) } else {
        Store::open(&Store::default_dir().context("no config directory")?)?
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
                 link: &str, stream_port: u16) -> Result<()> {
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
    while start.elapsed().as_secs_f64() < duration {
        let t = start.elapsed().as_secs_f64();
        let state = InputState {
            buttons: if (t as u64).is_multiple_of(2) { Button::A.mask() } else { 0 },
            lx: axis_from_unit((t * std::f64::consts::PI).sin()),
            rt: (40000.0 + 20000.0 * (t * 2.0).sin()) as u16,
            ..Default::default()
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
