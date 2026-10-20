//! Several links in one controller session (SPEC §8.5) against the real host code: a TCP/UDP link
//! and a stream-binding link from the same phone identity. Loopback only — this proves the session,
//! dedup and ordering rules, not radio behaviour.
use nine_host_core::backend::MockBackend;
use nine_host_core::client::{stream_link, ClientError, Connection, DatagramPath, InputSender};
use nine_host_core::{Host, HostConfig, HostEvent, Store};
use nine_proto::control::ControllerHello;
use nine_proto::frame::{InputFrame, PacketType};
use nine_proto::input::{Button, InputState};
use nine_proto::noise::KeyPair;
use nine_proto::pairing::PairUri;
use nine_proto::policy::LinkKind;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

fn cfg() -> HostConfig {
    HostConfig { name: "Multipath Host".into(), tcp_port: 0, udp_port: 0, stream_port: Some(0), advertise: false, ble: false,
        bind: "127.0.0.1".into(), grace_s: 5, ..Default::default() }
}
fn hello() -> ControllerHello { ControllerHello { proto: 1, name: "Two-link phone".into(), platform: "test".into(), app: "0".into() } }
async fn settle() { tokio::time::sleep(Duration::from_millis(80)).await; }

/// One session sender (`tx`, owns the session counter) and two links: UDP (`tx.path`) and the stream
/// binding (`b`). Like the phone, every datagram is sealed once and the same bytes go over each link.
struct Phone { tx: InputSender, b: DatagramPath, _ca: Connection, cb: Connection }

impl Phone {
    fn input(&mut self, st: InputState) -> Vec<u8> {
        let f = InputFrame { t_us: self.tx.now_us(), state: st, flags: 0 };
        self.tx.seal(PacketType::Input, &f.encode())
    }
    async fn both(&mut self, d: &[u8]) { self.tx.send_raw(d).await.unwrap(); self.b_send(d).await; }
    async fn b_send(&self, d: &[u8]) {
        let DatagramPath::Link { tx, .. } = &self.b else { unreachable!() };
        tx.send(d.to_vec()).unwrap();
    }
}

/// Pair over TCP, open on the IP link, then join the same session over the stream binding.
async fn two_links(h: &Host, id: &KeyPair) -> Phone {
    let addr: SocketAddr = format!("127.0.0.1:{}", h.tcp_port()).parse().unwrap();
    let mut ca = Connection::connect(addr, id, &hello()).await.unwrap();
    assert!(ca.host.multipath, "host must announce multipath");
    let uri = PairUri::parse(&h.open_pairing(Some("127.0.0.1".into())).uri).unwrap();
    ca.pair_qr(&uri).await.unwrap();
    let ia = ca.open(None, None).await.unwrap();
    let s = tokio::net::TcpStream::connect(("127.0.0.1", h.stream_port().unwrap())).await.unwrap();
    let (rd, wr, path) = stream_link(s);
    let mut cb = Connection::connect_over(rd, wr, id, &hello()).await.unwrap();
    let ib = cb.join(ia.session_id).await.unwrap();
    assert_eq!((ib.slot, ib.session_id, ib.k_in, ib.k_out), (ia.slot, ia.session_id, ia.k_in, ia.k_out), "join keeps slot, session and keys");
    Phone { tx: InputSender::new(addr, ia).await.unwrap(), b: path, _ca: ca, cb }
}

#[tokio::test]
async fn second_link_joins_the_same_player_and_copies_are_deduplicated() {
    let mock = MockBackend::default();
    let h = Host::start(Store::in_memory(cfg()), Arc::new(mock.clone())).await.unwrap();
    let mut ev = h.subscribe();
    let mut p = two_links(&h, &KeyPair::generate()).await;
    let held = InputState { buttons: Button::A.mask(), rt: 65535, ..Default::default() };
    for _ in 0..20 {
        let d = p.input(held);
        p.both(&d).await;
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    settle().await;
    let snap = h.snapshot();
    assert_eq!(snap.slots.len(), 1, "one player, not two");
    let s = &snap.slots[0];
    assert_eq!(s.paths.len(), 2);
    assert!(s.paths.iter().any(|p| p.link == LinkKind::Stream) && s.paths.iter().any(|p| p.link != LinkKind::Stream));
    assert_eq!(s.frames, 20, "each datagram processed once");
    assert_eq!(s.duplicates, 20, "the other copy of each was dropped");
    assert_eq!(s.paths.iter().map(|p| p.first + p.late_copies).sum::<u64>(), 40);
    assert!(s.redundant);
    assert_eq!(s.state.buttons, Button::A.mask());
    assert_eq!(mock.log.lock().iter().filter(|(_, st)| *st == held).count(), 1, "the held state is submitted once");
    let (mut connected, mut path_up) = (0, 0);
    while let Ok(e) = ev.try_recv() {
        match e { HostEvent::SlotConnected { .. } => connected += 1, HostEvent::PathUp { .. } => path_up += 1, _ => {} }
    }
    assert_eq!((connected, path_up), (1, 1));
}

#[tokio::test]
async fn a_late_copy_never_restores_an_older_press() {
    let mock = MockBackend::default();
    let h = Host::start(Store::in_memory(cfg()), Arc::new(mock.clone())).await.unwrap();
    let mut p = two_links(&h, &KeyPair::generate()).await;
    let press = InputState { buttons: Button::B.mask(), lt: 65535, ..Default::default() };
    // Fast link delivers the press then the release; the slow link's copy of the press arrives last.
    let (d_press, d_release) = (p.input(press), p.input(InputState::NEUTRAL));
    p.tx.send_raw(&d_press).await.unwrap();
    p.tx.send_raw(&d_release).await.unwrap();
    settle().await;
    p.b_send(&d_press).await;
    settle().await;
    assert_eq!(h.snapshot().slots[0].state.buttons, 0, "a duplicate of an old press is dropped");
    // Reordered across links: the release arrives first (stream link), its older press later (UDP).
    let (d_press2, d_release2) = (p.input(press), p.input(InputState::NEUTRAL));
    p.b_send(&d_release2).await;
    settle().await;
    p.tx.send_raw(&d_press2).await.unwrap();
    settle().await;
    let s = &h.snapshot().slots[0];
    assert_eq!((s.state.buttons, s.state.lt), (0, 0), "the release stays released");
    assert_eq!(s.superseded, 1, "the older press was authenticated but never applied");
    assert_eq!(*mock.log.lock().last().map(|(_, st)| st).unwrap(), InputState::NEUTRAL);
}

#[tokio::test]
async fn losing_one_link_keeps_the_player_live_and_input_held() {
    let mock = MockBackend::default();
    let h = Host::start(Store::in_memory(cfg()), Arc::new(mock.clone())).await.unwrap();
    let mut ev = h.subscribe();
    let mut p = two_links(&h, &KeyPair::generate()).await;
    let held = InputState { rt: 40000, lx: -12000, ..Default::default() };
    let d = p.input(held);
    p.both(&d).await;
    settle().await;
    // Kill the stream link while input is held; keep sending over UDP only.
    let Phone { mut tx, b, _ca, cb } = p;
    drop(b); drop(cb);
    for _ in 0..30 {
        let f = InputFrame { t_us: tx.now_us(), state: held, flags: 0 };
        let d = tx.seal(PacketType::Input, &f.encode());
        tx.send_raw(&d).await.unwrap();
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    let s = &h.snapshot().slots[0];
    assert!(s.connected);
    assert_eq!(s.paths.len(), 1, "only the dead link was removed");
    assert_eq!(s.health, "live");
    assert_eq!(s.neutral_resets, 0, "no release while a link still delivers");
    assert_eq!((s.state.rt, s.state.lx), (40000, -12000));
    assert!(s.path_events.iter().any(|e| e.contains("down")), "{:?}", s.path_events);
    let (mut down, mut disconnected) = (0, 0);
    while let Ok(e) = ev.try_recv() {
        match e { HostEvent::PathDown { .. } => down += 1, HostEvent::SlotDisconnected { .. } => disconnected += 1, _ => {} }
    }
    assert_eq!((down, disconnected), (1, 0), "a link going down is not a disconnect");
    // Every link silent: the normal input timeout still releases everything.
    tokio::time::sleep(Duration::from_millis(400)).await;
    let s = &h.snapshot().slots[0];
    assert_eq!(s.state.rt, 0);
    assert_eq!(s.neutral_resets, 1);
}

#[tokio::test]
async fn pings_are_answered_on_the_link_they_came_over() {
    let h = Host::start(Store::in_memory(cfg()), Arc::new(MockBackend::default())).await.unwrap();
    let mut p = two_links(&h, &KeyPair::generate()).await;
    let mut on_b = 0;
    for _ in 0..10 {
        p.tx.ping_over(&p.b).await.unwrap();
        on_b += p.tx.pump_from(&mut p.b, Duration::from_millis(30)).await;
    }
    assert!(on_b >= 9, "pongs came back over the stream link: {on_b}");
    assert!(p.tx.rtt_ms.len() >= 9);
    let rtt = h.snapshot().slots[0].paths.iter().find(|x| x.link == LinkKind::Stream).map(|x| x.first).unwrap_or(0);
    assert!(rtt >= 9, "the stream link delivered the pings first: {rtt}");
}

#[tokio::test]
async fn join_is_refused_for_another_identity_or_an_unpaired_phone() {
    let h = Host::start(Store::in_memory(cfg()), Arc::new(MockBackend::default())).await.unwrap();
    let p = two_links(&h, &KeyPair::generate()).await;
    let sid = p.tx.info.session_id;
    let addr: SocketAddr = format!("127.0.0.1:{}", h.tcp_port()).parse().unwrap();
    // A different, paired phone cannot attach to someone else's session.
    let mut other = Connection::connect(addr, &KeyPair::generate(), &hello()).await.unwrap();
    let uri = PairUri::parse(&h.open_pairing(Some("127.0.0.1".into())).uri).unwrap();
    other.pair_qr(&uri).await.unwrap();
    assert!(matches!(other.join(sid).await, Err(ClientError::Refused(c)) if c == "join_refused"));
    // An unpaired phone is refused before any join is considered.
    let mut stranger = Connection::connect(addr, &KeyPair::generate(), &hello()).await.unwrap();
    assert!(matches!(stranger.join(sid).await, Err(ClientError::Refused(c)) if c == "not_paired"));
    let snap = h.snapshot();
    assert_eq!(snap.slots.len(), 1);
    assert_eq!(snap.slots[0].paths.len(), 2);
}
