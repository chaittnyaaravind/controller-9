//! Tauri shell: exposes the shared host core to the companion UI. No business logic here.
use nine_host_core::backend::{Backend, MockBackend};
use nine_host_core::host::PairingInfo;
use nine_host_core::{Host, HostConfig, HostSnapshot, Store};
use std::sync::Arc;
use tauri::{Emitter, Manager, State};

struct App { host: Host, preview: bool }

fn native_backend() -> Arc<dyn Backend> {
    #[cfg(target_os = "linux")] { Arc::new(nine_pad_linux::UinputBackend::default()) }
    #[cfg(target_os = "macos")] { Arc::new(nine_pad_macos::IoHidBackend) }
    #[cfg(windows)] { nine_pad_windows::native_backend() }
}

/// Windows: one-time ViGEmBus install (official release, checksum-verified, Windows' UAC prompt).
/// The ViGEm backend re-checks the driver on every status/create, so no restart of the app is needed.
#[tauri::command]
async fn install_driver() -> Result<serde_json::Value, String> {
    #[cfg(windows)] {
        let o = tauri::async_runtime::spawn_blocking(nine_pad_windows::driver::install).await.map_err(|e| e.to_string())??;
        Ok(serde_json::json!({ "restartNeeded": o == nine_pad_windows::driver::Outcome::InstalledRestartNeeded }))
    }
    #[cfg(not(windows))] { Err("Only Windows needs a driver".into()) }
}

#[tauri::command]
fn snapshot(app: State<'_, App>) -> serde_json::Value {
    let s: HostSnapshot = app.host.snapshot();
    let mut v = serde_json::to_value(s).unwrap_or_default();
    v["preview"] = app.preview.into();
    v["version"] = env!("CARGO_PKG_VERSION").into();
    v
}
#[tauri::command]
fn open_pairing(app: State<'_, App>) -> PairingInfo { app.host.open_pairing(None) }
#[tauri::command]
fn close_pairing(app: State<'_, App>) { app.host.close_pairing() }
#[tauri::command]
fn answer(app: State<'_, App>, id: u64, allow: bool) -> bool { app.host.answer(id, allow) }
#[tauri::command]
fn unpair(app: State<'_, App>, key: String) -> bool { app.host.unpair(&key) }
#[tauri::command]
fn disconnect(app: State<'_, App>, slot: u8) { app.host.disconnect_slot(slot) }
#[tauri::command]
fn test_rumble(app: State<'_, App>, slot: u8) { app.host.test_rumble(slot) }
#[tauri::command]
fn get_config(app: State<'_, App>) -> HostConfig { app.host.config() }
#[tauri::command]
fn save_config(app: State<'_, App>, config: HostConfig) -> Result<(), String> { app.host.save_config(config) }

pub fn run() {
    tracing_subscriber::fmt().with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into())).init();
    // NINECTL_PREVIEW=1 runs with the recording mock backend; the UI labels it "Preview".
    let preview = std::env::var("NINECTL_PREVIEW").is_ok_and(|v| v == "1");
    tauri::Builder::default()
        .setup(move |app| {
            let store = match Store::default_dir().map(|d| Store::open(&d)) {
                Some(Ok(s)) => s,
                Some(Err(e)) => { tracing::error!("config unreadable, using a temporary identity: {e}"); Store::in_memory(Default::default()) }
                None => Store::in_memory(Default::default()),
            };
            let backend: Arc<dyn Backend> = if preview { Arc::new(MockBackend::default()) } else { native_backend() };
            let host = tauri::async_runtime::block_on(Host::start(store, backend))?;
            let mut ev = host.subscribe();
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                while let Ok(e) = ev.recv().await { let _ = handle.emit("host-event", e); }
            });
            app.manage(App { host, preview });
            Ok(())
        })
        .on_window_event(|w, e| {
            if let tauri::WindowEvent::Destroyed = e {
                let host = w.state::<App>().host.clone();
                tauri::async_runtime::block_on(host.shutdown());
            }
        })
        .invoke_handler(tauri::generate_handler![snapshot, open_pairing, close_pairing, answer, unpair, disconnect, test_rumble, get_config, save_config, install_driver])
        .run(tauri::generate_context!())
        .expect("failed to run Controller-9");
}
