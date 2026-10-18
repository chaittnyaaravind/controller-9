//! BLE GATT central adapter (SPEC §8.3) using btleplug: CoreBluetooth (macOS), WinRT (Windows),
//! BlueZ/D-Bus (Linux). Phones are GATT servers; each matching phone becomes one `serve_gatt` session.
//! STATUS: compiles; NOT yet run against a phone (no BLE peripheral available on the dev machine).
use crate::host::Shared;
use crate::link::{serve_gatt, GattPipes};
use btleplug::api::{Central, CentralEvent, Manager as _, Peripheral as _, ScanFilter, WriteType};
use btleplug::platform::{Adapter, Manager, Peripheral};
use futures::StreamExt;
use nine_proto::link as L;
use std::collections::HashSet;
use std::sync::Arc;
use tokio::sync::mpsc;
use uuid::Uuid;

fn u(s: &str) -> Uuid { Uuid::parse_str(s).expect("valid UUID constant") }

pub(crate) fn spawn(sh: Arc<Shared>) {
    tokio::spawn(async move {
        if let Err(e) = run(sh.clone()).await {
            let mut st = sh.ble.lock();
            st.active = false;
            st.detail = format!("Bluetooth unavailable: {e}");
            tracing::warn!("BLE central stopped: {e}");
        }
    });
}

async fn adapter() -> Result<Adapter, String> {
    let m = Manager::new().await.map_err(|e| e.to_string())?;
    m.adapters().await.map_err(|e| e.to_string())?.into_iter().next().ok_or_else(|| "no Bluetooth adapter".to_string())
}

async fn run(sh: Arc<Shared>) -> Result<(), String> {
    { let mut st = sh.ble.lock(); st.supported = true; st.detail = "starting".into(); }
    let central = adapter().await?;
    let service = u(L::BLE_SERVICE);
    let mut events = central.events().await.map_err(|e| e.to_string())?;
    central.start_scan(ScanFilter { services: vec![service] }).await.map_err(|e| e.to_string())?;
    { let mut st = sh.ble.lock(); st.active = true; st.detail = "Scanning for phones offering Bluetooth".into(); }
    let busy: Arc<parking_lot::Mutex<HashSet<String>>> = Default::default();
    while let Some(ev) = events.next().await {
        // Hint arrives as service data (Android scan response) or local name "C9-xxxxxxxx" (iOS).
        let (id, hint): (_, Vec<u8>) = match ev {
            CentralEvent::ServiceDataAdvertisement { id, service_data } => match service_data.get(&service) { Some(h) => (id, h.clone()), None => continue },
            CentralEvent::DeviceDiscovered(id) | CentralEvent::DeviceUpdated(id) => {
                let Ok(p) = central.peripheral(&id).await else { continue };
                let Some(name) = p.properties().await.ok().flatten().and_then(|pr| pr.local_name) else { continue };
                match name_hint(&name) { Some(h) => (id, h), None => continue }
            }
            _ => continue,
        };
        if !sh.ble_hint_ok(&hint) { continue; }
        let key = format!("{id:?}");
        if !busy.lock().insert(key.clone()) { continue; }
        let Ok(p) = central.peripheral(&id).await else { busy.lock().remove(&key); continue };
        let (sh2, busy2) = (sh.clone(), busy.clone());
        tokio::spawn(async move {
            let label = p.properties().await.ok().flatten().and_then(|pr| pr.local_name).unwrap_or_else(|| "phone".into());
            sh2.ble.lock().connected.push(label.clone());
            if let Err(e) = session(&sh2, &p, &label).await { tracing::info!(%label, "BLE session ended: {e}"); }
            let _ = p.disconnect().await;
            // Labels can repeat ("phone"); drop only this session's entry.
            { let mut b = sh2.ble.lock(); if let Some(i) = b.connected.iter().position(|l| *l == label) { b.connected.remove(i); } }
            busy2.lock().remove(&key);
        });
    }
    Err("Bluetooth event stream ended".into())
}

/// `C9-<8 hex digits>` → 4-byte hint.
pub(crate) fn name_hint(name: &str) -> Option<Vec<u8>> {
    let hex = name.strip_prefix("C9-")?;
    if hex.len() != 8 { return None; }
    (0..4).map(|i| u8::from_str_radix(&hex[2 * i..2 * i + 2], 16).ok()).collect()
}

async fn session(sh: &Arc<Shared>, p: &Peripheral, label: &str) -> Result<(), String> {
    p.connect().await.map_err(|e| e.to_string())?;
    p.discover_services().await.map_err(|e| e.to_string())?;
    let chars = p.characteristics();
    let find = |s: &str| chars.iter().find(|c| c.uuid == u(s)).cloned().ok_or_else(|| format!("characteristic {s} missing"));
    let (cu, cd, iu, idn) = (find(L::BLE_CONTROL_UP)?, find(L::BLE_CONTROL_DOWN)?, find(L::BLE_INPUT_UP)?, find(L::BLE_INPUT_DOWN)?);
    let mut notes = p.notifications().await.map_err(|e| e.to_string())?;
    p.subscribe(&iu).await.map_err(|e| e.to_string())?;
    p.subscribe(&cu).await.map_err(|e| e.to_string())?; // subscribing control_up last tells the phone to start the handshake

    let (cu_tx, cu_rx) = mpsc::channel(256);
    let (iu_tx, iu_rx) = mpsc::channel(64);
    let (cd_tx, mut cd_rx) = mpsc::channel::<Vec<u8>>(256);
    let (id_tx, mut id_rx) = mpsc::channel::<Vec<u8>>(64);
    let (cu_uuid, iu_uuid) = (cu.uuid, iu.uuid);
    let router = tokio::spawn(async move {
        while let Some(n) = notes.next().await {
            if n.uuid == cu_uuid { if cu_tx.send(n.value).await.is_err() { break; } }
            else if n.uuid == iu_uuid { let _ = iu_tx.try_send(n.value); } // input: drop when the host lags, never block
        }
    });
    let (pc, pi) = (p.clone(), p.clone());
    let wc = tokio::spawn(async move { while let Some(v) = cd_rx.recv().await { if pc.write(&cd, &v, WriteType::WithResponse).await.is_err() { break; } } });
    let wi = tokio::spawn(async move { while let Some(v) = id_rx.recv().await { let _ = pi.write(&idn, &v, WriteType::WithoutResponse).await; } });
    // Host→phone writes use the minimum ATT MTU (23) unless the OS reports more; long control
    // messages are segmented, so this is always correct, only slower.
    let pipes = GattPipes { control_up: cu_rx, input_up: iu_rx, control_down: cd_tx, input_down: id_tx, write_mtu: 23 };
    let r = serve_gatt(sh, pipes, format!("{label} (Bluetooth LE)")).await;
    router.abort(); wc.abort(); wi.abort();
    r.map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    #[test]
    fn name_hints() {
        assert_eq!(super::name_hint("C9-0a1b2c3d"), Some(vec![0x0a, 0x1b, 0x2c, 0x3d]));
        assert_eq!(super::name_hint("C9-00000000"), Some(vec![0; 4]));
        assert_eq!(super::name_hint("Pixel 9"), None);
        assert_eq!(super::name_hint("C9-zz"), None);
    }
}
