//! Pairing edge cases over real loopback sockets with real Noise (MockBackend, no OS controller).
use base64::Engine as _;
use nine_host_core::backend::MockBackend;
use nine_host_core::client::Connection;
use nine_host_core::{Host, HostConfig, HostEvent, Store};
use nine_proto::control::{Control, ControllerHello};
use nine_proto::noise::KeyPair;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;

async fn host() -> (Host, SocketAddr) {
    let cfg = HostConfig { name: "Test Host".into(), tcp_port: 0, udp_port: 0, advertise: false, bind: "127.0.0.1".into(), stream_port: None, ble: false, ..Default::default() };
    let h = Host::start(Store::in_memory(cfg), Arc::new(MockBackend::default())).await.unwrap();
    let addr: SocketAddr = format!("127.0.0.1:{}", h.tcp_port()).parse().unwrap();
    (h, addr)
}

fn hello() -> ControllerHello { ControllerHello { proto: 1, name: "Phone".into(), platform: "test".into(), app: "0".into() } }

/// Start a code-pairing round up to the point where the host shows a code and waits for the user.
async fn request_code(c: &mut Connection) {
    c.send(&Control::PairCode).await.unwrap();
    assert!(matches!(c.recv().await.unwrap(), Control::Commit { .. }));
    c.send(&Control::Nonce { n: B64.encode([7u8; 16]) }).await.unwrap();
    assert!(matches!(c.recv().await.unwrap(), Control::Reveal { .. }));
}

#[tokio::test]
async fn a_second_code_request_retires_the_unanswered_prompt() {
    let (h, addr) = host().await;
    h.open_pairing(Some("127.0.0.1".into()));
    let mut ev = h.subscribe();
    let mut c = Connection::connect(addr, &KeyPair::generate(), &hello()).await.unwrap();

    request_code(&mut c).await;
    let first = loop { if let HostEvent::PairingRequest { id, .. } = ev.recv().await.unwrap() { break id; } };

    // The phone asks again without the first prompt being answered.
    request_code(&mut c).await;
    let mut resolved_first = false;
    let second = loop {
        match tokio::time::timeout(Duration::from_secs(2), ev.recv()).await.expect("no event").unwrap() {
            HostEvent::PairingResolved { id, ok } if id == first => { assert!(!ok); resolved_first = true; }
            HostEvent::PairingRequest { id, .. } => break id,
            _ => {}
        }
    };
    assert!(resolved_first, "the stale prompt must be closed before a new one is shown");
    assert_ne!(first, second);
    // Answering the stale prompt does nothing; the live one still works.
    assert!(!h.answer(first, true), "stale prompt still pending");
    assert!(h.answer(second, false));
}
