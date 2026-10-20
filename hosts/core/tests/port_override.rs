//! Temporary ports (the usual ones were taken) must never reach the saved settings — a pairing or a
//! Settings save during such a run once wrote port 0 to config.json, so every later launch got random ports.
use nine_host_core::backend::MockBackend;
use nine_host_core::store::PortOverride;
use nine_host_core::{Host, Store};
use std::sync::Arc;

#[tokio::test]
async fn temporary_ports_are_used_but_never_saved() {
    let dir = std::env::temp_dir().join(format!("c9-port-override-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let mut store = Store::open(&dir).unwrap();
    store.config.advertise = false;
    store.config.ble = false;
    store.config.bind = "127.0.0.1".into();
    let saved = (store.config.tcp_port, store.config.udp_port, store.config.stream_port);
    store.port_override = Some(PortOverride { tcp: 0, udp: 0, stream: saved.2.map(|_| 0) });

    let h = Host::start(store, Arc::new(MockBackend::default())).await.unwrap();
    assert_ne!(h.tcp_port(), saved.0, "the override (a free port) is what gets bound");
    assert_eq!(h.config().tcp_port, saved.0, "settings shown to the user keep the real ports");
    h.save_config(h.config()).unwrap(); // the same write a pairing or the Settings page does

    let reopened = Store::open(&dir).unwrap();
    assert_eq!((reopened.config.tcp_port, reopened.config.udp_port, reopened.config.stream_port), saved);
    assert!(reopened.port_override.is_none());
    h.shutdown().await;
    let _ = std::fs::remove_dir_all(&dir);
}
