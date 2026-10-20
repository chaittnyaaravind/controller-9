//! End-to-end over real loopback sockets with real Noise + AEAD. The virtual-pad backend is the
//! MockBackend (records submitted states) — these tests prove protocol/session behaviour, not OS
//! controller injection.
use nine_host_core::backend::MockBackend;
use nine_host_core::client::{ClientError, Connection, InputSender};
use nine_host_core::{Host, HostConfig, HostEvent, Store};
use nine_proto::control::ControllerHello;
use nine_proto::input::{Button, InputState};
use nine_proto::noise::KeyPair;
use nine_proto::pairing::{format_code, PairUri};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

fn cfg() -> HostConfig {
    HostConfig { name: "Test Host".into(), tcp_port: 0, udp_port: 0, advertise: false, bind: "127.0.0.1".into(), grace_s: 2, stream_port: Some(0), ble: false, ..Default::default() }
}

async fn host() -> (Host, MockBackend, SocketAddr) {
    let mock = MockBackend::default();
    let h = Host::start(Store::in_memory(cfg()), Arc::new(mock.clone())).await.unwrap();
    let addr: SocketAddr = format!("127.0.0.1:{}", h.tcp_port()).parse().unwrap();
    (h, mock, addr)
}

fn hello(name: &str) -> ControllerHello {
    ControllerHello { proto: 1, name: name.into(), platform: "test".into(), app: "0".into() }
}

/// Auto-approve numeric-comparison requests on the host, reporting the code it displayed.
fn auto_approve(h: &Host) -> tokio::sync::mpsc::UnboundedReceiver<String> {
    let mut ev = h.subscribe();
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    let h = h.clone();
    tokio::spawn(async move {
        while let Ok(e) = ev.recv().await {
            if let HostEvent::PairingRequest { id, code, .. } = e { let _ = tx.send(code); h.answer(id, true); }
        }
    });
    rx
}

async fn paired_controller(h: &Host, addr: SocketAddr, name: &str) -> (KeyPair, Connection) {
    let id = KeyPair::generate();
    let info = h.open_pairing(Some("127.0.0.1".into()));
    let uri = PairUri::parse(&info.uri).unwrap();
    let mut c = Connection::connect(addr, &id, &hello(name)).await.unwrap();
    c.pair_qr(&uri).await.unwrap();
    (id, c)
}

async fn settle() { tokio::time::sleep(Duration::from_millis(60)).await; }

#[tokio::test]
async fn code_pairing_then_session_input_and_rtt() {
    let (h, mock, addr) = host().await;
    let mut codes = auto_approve(&h);
    let id = KeyPair::generate();
    let mut c = Connection::connect(addr, &id, &hello("Phone A")).await.unwrap();
    assert_eq!(c.host.name, "Test Host");
    assert_eq!(c.host_static, h.public_key());
    // unpaired controllers may not open a session
    assert!(matches!(c.open(None, None).await, Err(ClientError::Refused(r)) if r == "not_paired"));
    // pairing window closed -> refused
    assert!(matches!(c.pair_code(|_| true).await, Err(ClientError::Refused(r)) if r == "pairing_closed"));
    h.open_pairing(None);
    let (tx, rx) = std::sync::mpsc::channel();
    let code = c.pair_code(move |code| { tx.send(code).unwrap(); true }).await.unwrap();
    assert_eq!(rx.recv().unwrap(), code);
    assert_eq!(codes.recv().await.unwrap(), format_code(code), "phone and host show the same code");

    let info = c.open(None, Some("Racing")).await.unwrap();
    assert_eq!(info.slot, 0);
    assert!(info.backend_warning.is_none());
    let mut tx = InputSender::new(addr, info).await.unwrap();
    let pressed = InputState { buttons: Button::A.mask(), lx: -32767, rt: 65535, ..Default::default() };
    tx.send_input(pressed, 0).await.unwrap();
    for _ in 0..20 { tx.ping().await.unwrap(); tx.pump(Duration::from_millis(5)).await; }
    settle().await;
    assert!(mock.log.lock().contains(&(0, pressed)));
    assert!(tx.rtt_ms.len() >= 15, "pongs received: {}", tx.rtt_ms.len());
    let snap = h.snapshot();
    assert_eq!(snap.slots[0].profile.as_deref(), Some("Racing"));
    assert_eq!(snap.paired.len(), 1);
}

#[tokio::test]
async fn qr_pairing_rejects_wrong_host_and_reused_token() {
    let (h, _, addr) = host().await;
    let info = h.open_pairing(Some("127.0.0.1".into()));
    let mut uri = PairUri::parse(&info.uri).unwrap();
    let real = uri.clone();
    uri.host_static = [7u8; 32];
    let mut c = Connection::connect(addr, &KeyPair::generate(), &hello("X")).await.unwrap();
    assert!(matches!(c.pair_qr(&uri).await, Err(ClientError::WrongHost)));
    c.pair_qr(&real).await.unwrap();
    let mut c2 = Connection::connect(addr, &KeyPair::generate(), &hello("Y")).await.unwrap();
    assert!(matches!(c2.pair_qr(&real).await, Err(ClientError::Refused(r)) if r == "bad_proof"), "one-time token reused");
}

#[tokio::test]
async fn replay_stale_tamper_and_unknown_session_are_dropped() {
    let (h, mock, addr) = host().await;
    let (_, mut c) = paired_controller(&h, addr, "Phone").await;
    let info = c.open(None, None).await.unwrap();
    let mut tx = InputSender::new(addr, info.clone()).await.unwrap();
    let a = InputState { buttons: Button::A.mask(), ..Default::default() };
    let b = InputState { buttons: Button::B.mask(), ..Default::default() };
    let old = tx.seal(nine_proto::frame::PacketType::Input, &nine_proto::frame::InputFrame { t_us: 1, state: a, flags: 0 }.encode());
    tx.send_input(b, 0).await.unwrap(); // newer counter arrives first
    settle().await;
    tx.send_raw(&old).await.unwrap(); // late frame: must not be applied
    let replay = tx.seal(nine_proto::frame::PacketType::Input, &nine_proto::frame::InputFrame { t_us: 2, state: b, flags: 0 }.encode());
    tx.send_raw(&replay).await.unwrap();
    tx.send_raw(&replay).await.unwrap(); // exact replay
    let mut tampered = replay.clone();
    tampered[20] ^= 1;
    tx.send_raw(&tampered).await.unwrap();
    tx.send_raw(b"garbage").await.unwrap();
    let mut other = replay.clone();
    other[4..8].copy_from_slice(&(info.session_id ^ 1).to_le_bytes());
    tx.send_raw(&other).await.unwrap();
    settle().await;
    assert!(!mock.log.lock().iter().any(|(_, s)| *s == a), "stale frame applied");
    let s = h.snapshot();
    assert_eq!(s.slots[0].stale, 1, "late frame: authenticated, never applied");
    assert_eq!(s.slots[0].duplicates, 1, "exact replay: dropped as a duplicate");
    assert_eq!(s.slots[0].invalid, 1, "tampered");
    assert_eq!(s.malformed_datagrams, 1);
    assert_eq!(s.unknown_session_datagrams, 1);
}

#[tokio::test]
async fn silence_neutralizes_and_disconnect_keeps_slot_for_resume() {
    let (h, mock, addr) = host().await;
    let (id, mut c) = paired_controller(&h, addr, "Phone").await;
    let info = c.open(None, None).await.unwrap();
    let mut tx = InputSender::new(addr, info).await.unwrap();
    let held = InputState { buttons: Button::Rb.mask(), rt: 40000, ..Default::default() };
    tx.send_input(held, 0).await.unwrap();
    tokio::time::sleep(Duration::from_millis(400)).await; // > 250 ms timeout
    assert_eq!(mock.log.lock().last().unwrap().1, InputState::NEUTRAL, "stale input must be released");
    assert_eq!(h.snapshot().slots[0].neutral_resets, 1);

    tx.send_input(held, 0).await.unwrap();
    settle().await;
    drop(c); // control connection lost (e.g. Wi-Fi drop)
    settle().await;
    assert_eq!(mock.log.lock().last().unwrap().1, InputState::NEUTRAL, "disconnect must neutralize immediately");
    let s = h.snapshot();
    assert!(!s.slots[0].connected && s.slots[0].grace_s_left.is_some());
    // reconnect within grace -> same slot; old session id is dead
    let t0 = std::time::Instant::now();
    let mut c = Connection::connect(addr, &id, &hello("Phone")).await.unwrap();
    let info2 = c.open(Some(0), None).await.unwrap();
    let reconnect_ms = t0.elapsed().as_secs_f64() * 1000.0;
    assert_eq!(info2.slot, 0);
    assert!(reconnect_ms < 3000.0, "loopback reconnect {reconnect_ms:.1} ms");
    tx.send_input(held, 0).await.unwrap(); // old session
    settle().await;
    assert_eq!(h.snapshot().unknown_session_datagrams, 1);
    drop(c);
    tokio::time::sleep(Duration::from_millis(2300)).await; // grace (2 s in test config) expires
    assert!(h.snapshot().slots.is_empty(), "slot released after grace");
}

#[tokio::test]
async fn four_slots_then_full() {
    let (h, mock, addr) = host().await;
    let mut conns = Vec::new();
    for i in 0..4 {
        let (_, mut c) = paired_controller(&h, addr, &format!("P{i}")).await;
        let info = c.open(None, None).await.unwrap();
        assert_eq!(info.slot, i as u8);
        let mut tx = InputSender::new(addr, info).await.unwrap();
        tx.send_input(InputState { buttons: 1 << i, ..Default::default() }, 0).await.unwrap();
        conns.push((c, tx));
    }
    let (_, mut c5) = paired_controller(&h, addr, "P5").await;
    assert!(matches!(c5.open(None, None).await, Err(ClientError::Refused(r)) if r == "slots_full"));
    settle().await;
    let log = mock.log.lock();
    for i in 0..4u8 { assert!(log.iter().any(|(s, st)| *s == i && st.buttons == 1 << i), "slot {i} isolated"); }
}

#[tokio::test]
async fn paused_flag_and_udp_bye_neutralize() {
    let (h, mock, addr) = host().await;
    let (_, mut c) = paired_controller(&h, addr, "Phone").await;
    let info = c.open(None, None).await.unwrap();
    let mut tx = InputSender::new(addr, info).await.unwrap();
    let held = InputState { buttons: Button::A.mask(), ..Default::default() };
    tx.send_input(held, 0).await.unwrap();
    tx.send_input(held, nine_proto::input::FLAG_PAUSED).await.unwrap();
    settle().await;
    assert_eq!(mock.log.lock().last().unwrap().1, InputState::NEUTRAL);
    tx.send_input(held, 0).await.unwrap();
    tx.bye().await.unwrap();
    settle().await;
    assert_eq!(mock.log.lock().last().unwrap().1, InputState::NEUTRAL);
}

#[tokio::test]
async fn host_rejection_fails_pairing_and_feedback_reaches_controller() {
    let (h, mock, addr) = host().await;
    h.open_pairing(None);
    let mut ev = h.subscribe();
    let h2 = h.clone();
    tokio::spawn(async move {
        while let Ok(e) = ev.recv().await { if let HostEvent::PairingRequest { id, .. } = e { h2.answer(id, false); } }
    });
    let mut c = Connection::connect(addr, &KeyPair::generate(), &hello("Intruder")).await.unwrap();
    assert!(matches!(c.pair_code(|_| true).await, Err(ClientError::Refused(r)) if r == "rejected"));
    assert!(h.snapshot().paired.is_empty());

    let (_, mut c) = paired_controller(&h, addr, "Phone").await;
    let info = c.open(None, None).await.unwrap();
    let mut tx = InputSender::new(addr, info).await.unwrap();
    tx.send_input(InputState::NEUTRAL, 0).await.unwrap();
    settle().await;
    mock.feedback.lock().push((0, nine_proto::frame::Feedback { low: 100, high: 200, ms: 50 }));
    tx.pump(Duration::from_millis(100)).await;
    assert_eq!(tx.feedback.len(), 1, "game rumble forwarded to the phone");
}

/// The user fixes a blocked backend (grants macOS Accessibility, installs the Windows driver) while
/// the phone stays connected: the controller must appear without reconnecting, and input must flow.
#[tokio::test]
async fn pad_is_created_once_the_backend_becomes_available() {
    use nine_host_core::backend::{Backend, BackendError, BackendKind, BackendStatus, VirtualPad};
    use std::sync::atomic::{AtomicBool, Ordering};
    struct Toggle { on: AtomicBool, mock: MockBackend }
    impl Backend for Toggle {
        fn status(&self) -> BackendStatus {
            let on = self.on.load(Ordering::SeqCst);
            BackendStatus { name: "toggle".into(), kind: BackendKind::Mock, available: on, max_pads: 4, detail: String::new(), remedy: None }
        }
        fn create(&self, slot: u8, name: &str) -> Result<Box<dyn VirtualPad>, BackendError> {
            if self.on.load(Ordering::SeqCst) { self.mock.create(slot, name) } else { Err(BackendError::Unavailable("permission off".into())) }
        }
    }
    let toggle = Arc::new(Toggle { on: AtomicBool::new(false), mock: MockBackend::default() });
    let h = Host::start(Store::in_memory(cfg()), toggle.clone()).await.unwrap();
    let addr: SocketAddr = format!("127.0.0.1:{}", h.tcp_port()).parse().unwrap();
    let (_id, mut c) = paired_controller(&h, addr, "Phone").await;
    let info = c.open(None, None).await.unwrap();
    let mut tx = InputSender::new(addr, info).await.unwrap();
    let pressed = InputState { buttons: Button::A.mask(), ..InputState::NEUTRAL };
    tx.send_input(pressed, 0).await.unwrap();
    settle().await;
    assert!(toggle.mock.log.lock().is_empty(), "no pad while the backend is blocked");

    toggle.on.store(true, Ordering::SeqCst); // the user grants the permission
    for _ in 0..30 { tx.send_input(pressed, 0).await.unwrap(); tokio::time::sleep(Duration::from_millis(50)).await; } // a phone keeps sending
    assert_eq!(toggle.mock.log.lock().last().map(|(_, s)| *s), Some(pressed), "pad created and the held button applied");
    tx.send_input(InputState::NEUTRAL, 0).await.unwrap();
    settle().await;
    assert_eq!(toggle.mock.log.lock().last().map(|(_, s)| *s), Some(InputState::NEUTRAL), "input keeps flowing");
}
