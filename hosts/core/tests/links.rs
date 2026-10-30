//! Transport bindings end-to-end against the real host code. The BLE case uses in-memory
//! characteristics (SIMULATED radio) to prove segmentation, reassembly and session logic at
//! realistic MTUs; it is not evidence that a phone and host talk over Bluetooth.
use nine_host_core::backend::MockBackend;
use nine_host_core::client::{stream_link, Connection, DatagramPath, InputSender};
use nine_host_core::link::GattPipes;
use nine_host_core::{Host, HostConfig, Store};
use nine_proto::control::ControllerHello;
use nine_proto::input::{Button, InputState};
use nine_proto::link::{Reassembler, Segmenter};
use nine_proto::noise::KeyPair;
use nine_proto::pairing::PairUri;
use nine_proto::policy::LinkKind;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;

fn cfg() -> HostConfig {
    HostConfig { name: "Link Host".into(), tcp_port: 0, udp_port: 0, stream_port: Some(0), advertise: false, ble: false,
        bind: "127.0.0.1".into(), grace_s: 5, ..Default::default() }
}
async fn host() -> (Host, MockBackend) {
    let mock = MockBackend::default();
    (Host::start(Store::in_memory(cfg()), Arc::new(mock.clone())).await.unwrap(), mock)
}
fn hello() -> ControllerHello { ControllerHello { proto: 1, name: "Link phone".into(), platform: "test".into(), app: "0".into() } }
async fn settle() { tokio::time::sleep(Duration::from_millis(80)).await; }
fn qr(h: &Host) -> PairUri { PairUri::parse(&h.open_pairing(Some("127.0.0.1".into())).uri).unwrap() }

#[tokio::test]
async fn stream_binding_over_tcp() {
    let (h, mock) = host().await;
    let s = tokio::net::TcpStream::connect(("127.0.0.1", h.stream_port().unwrap())).await.unwrap();
    let (rd, wr, path) = stream_link(s);
    let mut c = Connection::connect_over(rd, wr, &KeyPair::generate(), &hello()).await.unwrap();
    assert!(c.host.links.contains(&"stream".to_string()));
    c.pair_qr(&qr(&h)).await.unwrap();
    let info = c.open(None, None).await.unwrap();
    let mut tx = InputSender::over(path, info);
    let st = InputState { buttons: Button::A.mask(), rt: 50000, ..Default::default() };
    tx.send_input(st, 0).await.unwrap();
    for _ in 0..10 { tx.ping().await.unwrap(); tx.pump(Duration::from_millis(5)).await; }
    settle().await;
    assert_eq!(h.snapshot().slots[0].stale, 0, "coalescing must never reorder datagrams");
    assert!(mock.log.lock().contains(&(0, st)));
    assert!(tx.rtt_ms.len() >= 8, "pongs over the stream: {}", tx.rtt_ms.len());
    assert_eq!(h.snapshot().slots[0].link, LinkKind::Stream);
}

#[tokio::test]
async fn stream_binding_rejects_unknown_record_kind() {
    let (h, _) = host().await;
    let mut s = tokio::net::TcpStream::connect(("127.0.0.1", h.stream_port().unwrap())).await.unwrap();
    s.write_all(&[0x07, 0, 1, 0xff]).await.unwrap();
    let mut b = [0u8; 16];
    let n = tokio::time::timeout(Duration::from_secs(2), s.read(&mut b)).await.unwrap().unwrap_or(0);
    assert_eq!(n, 0, "host must close the link");
}

/// Phone side of a simulated GATT link: returns the host pipes plus the phone's control stream and
/// datagram path.
fn simulated_gatt(mtu: usize) -> (GattPipes, Box<dyn tokio::io::AsyncRead + Unpin + Send>, Box<dyn tokio::io::AsyncWrite + Unpin + Send>, DatagramPath) {
    let (cu_tx, cu_rx) = mpsc::channel::<Vec<u8>>(256);
    let (iu_tx, iu_rx) = mpsc::channel::<Vec<u8>>(256);
    let (cd_tx, mut cd_rx) = mpsc::channel::<Vec<u8>>(256);
    let (id_tx, mut id_rx) = mpsc::channel::<Vec<u8>>(256);
    // phone control: framed duplex <-> segmented notifications / writes
    let (mut phone_ctl_in_w, phone_ctl_in_r) = tokio::io::duplex(1 << 16);
    let (phone_ctl_out_w, mut phone_ctl_out_r) = tokio::io::duplex(1 << 16);
    tokio::spawn(async move {
        let mut seg = Segmenter::default();
        loop {
            let mut l = [0u8; 2];
            if phone_ctl_out_r.read_exact(&mut l).await.is_err() { return; }
            let mut b = vec![0u8; u16::from_be_bytes(l) as usize];
            if phone_ctl_out_r.read_exact(&mut b).await.is_err() { return; }
            for v in seg.segment(&b, mtu) { assert!(v.len() <= mtu - 3); if cu_tx.send(v).await.is_err() { return; } }
        }
    });
    tokio::spawn(async move {
        let mut r = Reassembler::new(65535);
        while let Some(v) = cd_rx.recv().await {
            assert!(v.len() <= 20, "host writes must fit the default ATT MTU");
            if let Some(m) = r.push(&v) { let _ = phone_ctl_in_w.write_all(&nine_proto::control::frame(&m)).await; }
        }
    });
    let (up_tx, mut up_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let (down_tx, down_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    tokio::spawn(async move {
        let mut seg = Segmenter::default();
        while let Some(d) = up_rx.recv().await { for v in seg.segment(&d, mtu) { let _ = iu_tx.send(v).await; } }
    });
    tokio::spawn(async move {
        let mut r = Reassembler::new(256);
        while let Some(v) = id_rx.recv().await { if let Some(m) = r.push(&v) { let _ = down_tx.send(m); } }
    });
    let pipes = GattPipes { control_up: cu_rx, input_up: iu_rx, control_down: cd_tx, input_down: id_tx, write_mtu: 23 };
    (pipes, Box::new(phone_ctl_in_r), Box::new(phone_ctl_out_w), DatagramPath::Link { tx: up_tx, rx: down_rx })
}

#[tokio::test]
async fn ble_gatt_binding_simulated_mtu_23_and_185() {
    for mtu in [23usize, 185] {
        let (h, mock) = host().await;
        let (pipes, rd, wr, path) = simulated_gatt(mtu);
        let h2 = h.clone();
        tokio::spawn(async move { let _ = h2.serve_gatt(pipes, "sim-ble".into()).await; });
        let mut c = Connection::connect_over(rd, wr, &KeyPair::generate(), &hello()).await.unwrap();
        c.pair_qr(&qr(&h)).await.unwrap();
        let info = c.open(None, Some("BLE")).await.unwrap();
        let mut tx = InputSender::over(path, info);
        let st = InputState { buttons: Button::B.mask(), lx: -20000, ..Default::default() };
        tx.send_input(st, 0).await.unwrap();
        for _ in 0..10 { tx.ping().await.unwrap(); tx.pump(Duration::from_millis(5)).await; }
        settle().await;
        assert!(mock.log.lock().contains(&(0, st)), "mtu {mtu}");
        assert!(tx.rtt_ms.len() >= 8, "mtu {mtu}: pongs {}", tx.rtt_ms.len());
        let snap = h.snapshot();
        assert_eq!(snap.slots[0].link, LinkKind::Ble);
    }
}

/// Pairing by code over Bluetooth waits for a person to compare codes; the link must survive the quiet
/// (seen on the realme 12 5G: the host closed it 2 s after showing the code, so Bluetooth pairing failed).
#[tokio::test]
async fn ble_link_survives_quiet_before_the_session_opens() {
    let (h, mock) = host().await;
    let (pipes, rd, wr, path) = simulated_gatt(185);
    let h2 = h.clone();
    tokio::spawn(async move { let _ = h2.serve_gatt(pipes, "sim-ble".into()).await; });
    let mut c = Connection::connect_over(rd, wr, &KeyPair::generate(), &hello()).await.unwrap();
    tokio::time::sleep(Duration::from_millis(2500)).await; // a person reading the code
    c.pair_qr(&qr(&h)).await.unwrap();
    let info = c.open(None, Some("BLE")).await.unwrap();
    let mut tx = InputSender::over(path, info);
    let st = InputState { buttons: Button::A.mask(), ..Default::default() };
    tx.send_input(st, 0).await.unwrap();
    settle().await;
    assert!(mock.log.lock().contains(&(0, st)));
}

#[tokio::test]
async fn failover_ip_to_stream_neutralizes_keeps_slot_and_rejects_old_session() {
    let (h, mock) = host().await;
    let id = KeyPair::generate();
    let addr: SocketAddr = format!("127.0.0.1:{}", h.tcp_port()).parse().unwrap();
    let mut c = Connection::connect(addr, &id, &hello()).await.unwrap();
    c.pair_qr(&qr(&h)).await.unwrap();
    let info = c.open(None, None).await.unwrap();
    let slot = info.slot;
    let mut ip = InputSender::new(addr, info).await.unwrap();
    let throttle = InputState { rt: 65535, buttons: Button::A.mask(), ..Default::default() };
    ip.send_input(throttle, 0).await.unwrap();
    settle().await;
    assert_eq!(mock.log.lock().last().unwrap().1, throttle);

    // Wi-Fi drops mid-input: the control channel dies.
    drop(c);
    settle().await;
    assert_eq!(mock.log.lock().last().unwrap().1, InputState::NEUTRAL, "throttle released when the link died");

    // Fail over to the stream binding with the same identity.
    let t0 = std::time::Instant::now();
    let s = tokio::net::TcpStream::connect(("127.0.0.1", h.stream_port().unwrap())).await.unwrap();
    let (rd, wr, path) = stream_link(s);
    let mut c2 = Connection::connect_over(rd, wr, &id, &hello()).await.unwrap();
    let info2 = c2.open(Some(slot), None).await.unwrap();
    let failover_ms = t0.elapsed().as_secs_f64() * 1000.0;
    assert_eq!(info2.slot, slot, "same player slot after failover");
    assert!(failover_ms < 3000.0);

    // Stale frames from the dead IP session must not apply.
    ip.send_input(throttle, 0).await.unwrap();
    settle().await;
    assert_eq!(mock.log.lock().last().unwrap().1, InputState::NEUTRAL);
    assert!(h.snapshot().unknown_session_datagrams >= 1);

    // Fresh state on the new link applies.
    let mut st = InputSender::over(path, info2);
    let fresh = InputState { lt: 30000, ..Default::default() };
    st.send_input(fresh, 0).await.unwrap();
    settle().await;
    assert_eq!(mock.log.lock().last().unwrap().1, fresh);
    assert_eq!(h.snapshot().slots[0].link, LinkKind::Stream);
}

#[tokio::test]
async fn takeover_while_old_link_alive_neutralizes_before_new_state() {
    let (h, mock) = host().await;
    let id = KeyPair::generate();
    let addr: SocketAddr = format!("127.0.0.1:{}", h.tcp_port()).parse().unwrap();
    let mut c = Connection::connect(addr, &id, &hello()).await.unwrap();
    c.pair_qr(&qr(&h)).await.unwrap();
    let info = c.open(None, None).await.unwrap();
    let mut ip = InputSender::new(addr, info).await.unwrap();
    let held = InputState { buttons: Button::Rb.mask(), ..Default::default() };
    ip.send_input(held, 0).await.unwrap();
    settle().await;
    let s = tokio::net::TcpStream::connect(("127.0.0.1", h.stream_port().unwrap())).await.unwrap();
    let (rd, wr, path) = stream_link(s);
    let mut c2 = Connection::connect_over(rd, wr, &id, &hello()).await.unwrap();
    let info2 = c2.open(Some(0), None).await.unwrap();
    assert!(matches!(c.recv().await.unwrap(), nine_proto::control::Control::Bye { .. }), "old link told it was replaced");
    let mut st = InputSender::over(path, info2);
    st.send_input(InputState { buttons: Button::X.mask(), ..Default::default() }, 0).await.unwrap();
    settle().await;
    let log = mock.log.lock();
    let i_held = log.iter().rposition(|(_, s)| *s == held).unwrap();
    let i_new = log.iter().position(|(_, s)| s.buttons == Button::X.mask()).unwrap();
    assert!(log[i_held..i_new].iter().any(|(_, s)| *s == InputState::NEUTRAL), "neutral between old and new link state");
}
