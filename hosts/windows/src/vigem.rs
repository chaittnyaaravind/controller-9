//! Xbox 360 controllers through ViGEmBus (Nefarius, production-signed, installed separately by the
//! user from its official release). XInput, DirectInput, Windows.Gaming.Input, SDL and Steam all see
//! them. Rumble from the game comes back through ViGEm's XUSB notifications. Falls back to a
//! DualShock 4 (HID; no rumble) where the Xbox 360 pad can't start.
use crate::xinput;
use nine_host_core::backend::{Backend, BackendError, BackendKind, BackendStatus, VirtualPad};
use nine_proto::frame::Feedback;
use nine_proto::input::InputState;
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use vigem_client::{Client, DS4Report, DualShock4Wired, TargetId, XButtons, XGamepad, Xbox360Wired};

pub const REMEDY: &str = "Install the free ViGEmBus driver (github.com/nefarius/ViGEmBus/releases, or `winget install ViGEm.ViGEmBus`), then restart Controller-9";

#[derive(Default, Clone, Copy)]
pub struct VigemBackend;

enum Target { X360(Xbox360Wired<Client>), Ds4(DualShock4Wired<Client>) }

struct VigemPad {
    target: Target,
    rumble: Arc<Mutex<Option<Feedback>>>,
    listener: Option<JoinHandle<()>>,
}

impl Backend for VigemBackend {
    fn status(&self) -> BackendStatus {
        let s = |available, kind, detail: String, remedy: Option<&str>| BackendStatus {
            name: "windows-vigem".into(), kind, available, max_pads: 4, detail, remedy: remedy.map(String::from) };
        match Client::connect() {
            Ok(_) => s(true, BackendKind::Native, "ViGEmBus ready · games see an Xbox 360 controller".into(), None),
            Err(e) => s(false, BackendKind::Blocked, format!("ViGEmBus driver not available ({e:?})"), Some(REMEDY)),
        }
    }

    fn create(&self, slot: u8, _name: &str) -> Result<Box<dyn VirtualPad>, BackendError> {
        let connect = || Client::connect().map_err(|e| BackendError::Unavailable(format!("ViGEmBus: {e:?}")));
        let mut x = Xbox360Wired::new(connect()?, TargetId::XBOX360_WIRED);
        let x_err = match x.plugin().and_then(|_| x.wait_ready()) {
            Ok(()) => {
                let rumble = Arc::new(Mutex::new(None));
                // Rumble is optional: a pad without it still works, so a failed request only loses feedback.
                let listener = x.request_notification().ok().map(|req| {
                    let sink = rumble.clone();
                    req.spawn_thread(move |_, n| { *sink.lock().unwrap() = Some(xinput::feedback(n.large_motor, n.small_motor)); })
                });
                return Ok(Box::new(VigemPad { target: Target::X360(x), rumble, listener }));
            }
            Err(e) => { let _ = x.unplug(); e }
        };
        // An Xbox 360 pad needs Microsoft's XUSB driver; without it (seen on Windows Server: error 483)
        // fall back to a DualShock 4, which is plain HID.
        tracing::warn!(slot, error = ?x_err, "Xbox 360 pad could not start; falling back to DualShock 4");
        let mut d = DualShock4Wired::new(connect()?, TargetId::DUALSHOCK4_WIRED);
        d.plugin().and_then(|_| d.wait_ready())
            .map_err(|e| BackendError::Io(format!("pad {slot}: Xbox 360 failed ({x_err:?}), DualShock 4 failed ({e:?})")))?;
        Ok(Box::new(VigemPad { target: Target::Ds4(d), rumble: Arc::new(Mutex::new(None)), listener: None }))
    }
}

impl VirtualPad for VigemPad {
    fn submit(&mut self, s: &InputState) -> Result<(), BackendError> {
        let r = match &mut self.target {
            Target::X360(t) => {
                let p = xinput::pad(s);
                t.update(&XGamepad { buttons: XButtons { raw: p.buttons }, left_trigger: p.lt, right_trigger: p.rt,
                    thumb_lx: p.lx, thumb_ly: p.ly, thumb_rx: p.rx, thumb_ry: p.ry })
            }
            Target::Ds4(t) => {
                let r = crate::ds4::report(s);
                t.update(&DS4Report { thumb_lx: r.lx, thumb_ly: r.ly, thumb_rx: r.rx, thumb_ry: r.ry, buttons: r.buttons,
                    special: r.special, trigger_l: r.l2, trigger_r: r.r2 })
            }
        };
        r.map_err(|e| BackendError::Io(format!("update: {e:?}")))
    }

    fn poll_feedback(&mut self) -> Option<Feedback> { self.rumble.lock().unwrap().take() }
}

impl Drop for VigemPad {
    // Unplugging aborts the pending notification request, which ends the listener thread.
    fn drop(&mut self) {
        let _ = match &mut self.target { Target::X360(t) => t.unplug(), Target::Ds4(t) => t.unplug() };
        if let Some(h) = self.listener.take() { let _ = h.join(); }
    }
}
