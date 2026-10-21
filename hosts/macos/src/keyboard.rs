//! Keyboard & mouse mode for macOS: the phone drives the keys and pointer through Quartz events
//! (`CGEventPost`). Needs only the user's Accessibility approval — no Apple entitlement — so it is
//! the macOS path until the virtual-HID entitlement exists. Layout: `nine_host_core::keymap`.
use nine_host_core::backend::{Backend, BackendError, BackendKind, BackendStatus, VirtualPad};
use nine_host_core::keymap::{mouse_velocity, Key, Mapper, Out};
use nine_proto::input::InputState;
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

#[repr(C)]
#[derive(Clone, Copy, Debug)]
struct CGPoint { x: f64, y: f64 }

type Ev = *mut c_void;

#[link(name = "CoreGraphics", kind = "framework")]
unsafe extern "C" {
    fn CGEventSourceCreate(state: i32) -> *mut c_void;
    fn CGEventCreate(src: *mut c_void) -> Ev;
    fn CGEventCreateKeyboardEvent(src: *mut c_void, key: u16, down: bool) -> Ev;
    fn CGEventCreateMouseEvent(src: *mut c_void, ty: u32, pos: CGPoint, button: u32) -> Ev;
    fn CGEventGetLocation(ev: Ev) -> CGPoint;
    fn CGEventSetIntegerValueField(ev: Ev, field: u32, v: i64);
    fn CGEventSetFlags(ev: Ev, flags: u64);
    fn CGEventPost(tap: u32, ev: Ev);
    fn CGPreflightPostEventAccess() -> bool;
    fn CGRequestPostEventAccess() -> bool;
}
#[link(name = "CoreFoundation", kind = "framework")]
unsafe extern "C" { fn CFRelease(p: *const c_void); }
#[link(name = "ApplicationServices", kind = "framework")]
unsafe extern "C" { fn AXIsProcessTrusted() -> bool; }

/// Only the "post events" grant lets CGEventPost through. Measured on macOS 27.0.1 (2026-10-20):
/// with Accessibility (AX) trusted but post-events off, every tagged event posted at the HID, session
/// and annotated-session taps was dropped. AX trust alone is NOT enough, so it is only logged.
fn can_post() -> bool { unsafe { CGPreflightPostEventAccess() } }

/// Diagnostic: posts one tagged F18 (does nothing in apps) through the same path as real input,
/// so a test tap can prove whether macOS delivers this process's events.
pub fn post_self_test() {
    let p = Poster::new();
    // F18 at the HID level, F19 at the session level, F20 at the annotated-session level.
    for (key, tap) in [(79u16, 0u32), (80, 1), (90, 2)] {
        for down in [true, false] {
            let e = unsafe { CGEventCreateKeyboardEvent(p.src, key, down) };
            if e.is_null() { continue; }
            unsafe { CGEventSetIntegerValueField(e, F_USER_DATA, MARK); CGEventPost(tap, e); CFRelease(e); }
        }
    }
    tracing::info!(can_post = can_post(), "keyboard mode self-test: posted tagged F18 (HID), F19 (session), F20 (annotated)");
}

/// Logged once at startup so a permission problem is visible in the app log, never guessed.
pub fn log_permissions() {
    let (post, ax) = unsafe { (CGPreflightPostEventAccess(), AXIsProcessTrusted()) };
    tracing::info!(post_events = post, accessibility = ax, "macOS input permissions");
}

const HID_TAP: u32 = 0; // kCGHIDEventTap
const HID_STATE: i32 = 1; // kCGEventSourceStateHIDSystemState
const F_DELTA_X: u32 = 4; const F_DELTA_Y: u32 = 5; const F_USER_DATA: u32 = 42;
const SHIFT_FLAG: u64 = 0x0002_0000;
/// Tag on every event we post ("C9") — lets tests (and anyone debugging) tell them apart.
pub const MARK: i64 = 0x4339;

/// macOS ANSI virtual key codes (HIToolbox Events.h).
fn code(k: Key) -> u16 {
    match k {
        Key::A => 0, Key::S => 1, Key::D => 2, Key::F => 3, Key::C => 8, Key::V => 9, Key::Q => 12, Key::W => 13,
        Key::E => 14, Key::R => 15, Key::Tab => 48, Key::Space => 49, Key::Escape => 53, Key::Shift => 56,
        Key::Left => 123, Key::Right => 124, Key::Down => 125, Key::Up => 126,
    }
}

/// Owns a CGEventSource; posts tagged events. All calls are on Quartz's thread-safe C API.
struct Poster { src: *mut c_void }
// SAFETY: CGEventSource/CGEventPost are documented as usable from any thread; the source is only
// released in Drop, after the movement thread has been joined.
unsafe impl Send for Poster {}
unsafe impl Sync for Poster {}

impl Poster {
    fn new() -> Poster { Poster { src: unsafe { CGEventSourceCreate(HID_STATE) } } }
    fn location(&self) -> CGPoint {
        unsafe { let e = CGEventCreate(std::ptr::null_mut()); let p = CGEventGetLocation(e); CFRelease(e); p }
    }
    fn post(&self, e: Ev) {
        if e.is_null() { return; }
        unsafe { CGEventSetIntegerValueField(e, F_USER_DATA, MARK); CGEventPost(HID_TAP, e); CFRelease(e); }
    }
    fn key(&self, k: Key, down: bool) {
        let e = unsafe { CGEventCreateKeyboardEvent(self.src, code(k), down) };
        if k == Key::Shift && !e.is_null() { unsafe { CGEventSetFlags(e, if down { SHIFT_FLAG } else { 0 }) }; }
        self.post(e);
    }
    fn button(&self, left: bool, down: bool) {
        let ty = match (left, down) { (true, true) => 1, (true, false) => 2, (false, true) => 3, (false, false) => 4 };
        self.post(unsafe { CGEventCreateMouseEvent(self.src, ty, self.location(), if left { 0 } else { 1 }) });
    }
    /// Relative move: games read the deltas (mouse-look), desktops read the new position.
    fn moved(&self, dx: i64, dy: i64, left: bool, right: bool) {
        let p = self.location();
        let ty = if left { 6 } else if right { 7 } else { 5 };
        let e = unsafe { CGEventCreateMouseEvent(self.src, ty, CGPoint { x: p.x + dx as f64, y: p.y + dy as f64 }, if right && !left { 1 } else { 0 }) };
        if !e.is_null() { unsafe { CGEventSetIntegerValueField(e, F_DELTA_X, dx); CGEventSetIntegerValueField(e, F_DELTA_Y, dy); } }
        self.post(e);
    }
    fn apply(&self, changes: &[(Out, bool)]) {
        for &(o, down) in changes {
            match o { Out::Key(k) => self.key(k, down), Out::MouseLeft => self.button(true, down), Out::MouseRight => self.button(false, down) }
        }
    }
}

impl Drop for Poster { fn drop(&mut self) { if !self.src.is_null() { unsafe { CFRelease(self.src) } } } }

struct Shared { poster: Poster, state: Mutex<(InputState, Mapper)>, stop: AtomicBool }

pub struct KeyboardPad { sh: Arc<Shared>, mover: Option<JoinHandle<()>>, logged_first: bool }

#[derive(Default, Clone, Copy)]
pub struct KeyboardBackend;

impl KeyboardBackend {
    /// Shows macOS's own Accessibility prompt (once per app); true when already allowed.
    pub fn request_access() -> bool { unsafe { CGRequestPostEventAccess() } }
}

impl Backend for KeyboardBackend {
    fn status(&self) -> BackendStatus {
        let ok = can_post();
        BackendStatus {
            name: "macos-keyboard".into(), kind: if ok { BackendKind::Native } else { BackendKind::Blocked }, available: ok, max_pads: 1,
            detail: if ok { "Keyboard & mouse mode · stick = WASD, A = Space, right stick = mouse, RT/LT = click".into() }
                    else { "Keyboard & mouse mode needs Accessibility permission".into() },
            remedy: (!ok).then(|| "System Settings → Privacy & Security → Accessibility → turn on Controller-9".into()),
        }
    }

    fn create(&self, _slot: u8, _name: &str) -> Result<Box<dyn VirtualPad>, BackendError> {
        if !can_post() {
            return Err(BackendError::Unavailable("Accessibility permission is off for Controller-9".into()));
        }
        let sh = Arc::new(Shared { poster: Poster::new(), state: Mutex::new((InputState::NEUTRAL, Mapper::default())), stop: AtomicBool::new(false) });
        let t = sh.clone();
        // submit() only runs when input changes; a held stick must keep moving the pointer.
        let mover = std::thread::Builder::new().name("c9-mouse".into()).spawn(move || {
            let (mut last, mut acc) = (Instant::now(), (0.0f32, 0.0f32));
            while !t.stop.load(Ordering::Relaxed) {
                std::thread::sleep(Duration::from_millis(8));
                let now = Instant::now(); let dt = now.duration_since(last).as_secs_f32().min(0.05); last = now;
                let (v, l, r) = { let g = t.state.lock().unwrap(); (mouse_velocity(&g.0), g.1.holding(Out::MouseLeft), g.1.holding(Out::MouseRight)) };
                if v == (0.0, 0.0) { acc = (0.0, 0.0); continue; }
                acc.0 += v.0 * dt; acc.1 += v.1 * dt;
                let (dx, dy) = (acc.0.trunc(), acc.1.trunc());
                if dx != 0.0 || dy != 0.0 { acc.0 -= dx; acc.1 -= dy; t.poster.moved(dx as i64, dy as i64, l, r); }
            }
        }).map_err(|e| BackendError::Io(e.to_string()))?;
        tracing::info!("keyboard mode: controller ready (keys and mouse)");
        Ok(Box::new(KeyboardPad { sh, mover: Some(mover), logged_first: false }))
    }
}

impl VirtualPad for KeyboardPad {
    fn submit(&mut self, s: &InputState) -> Result<(), BackendError> {
        let changes = { let mut g = self.sh.state.lock().unwrap(); g.0 = *s; g.1.update(s) };
        if !self.logged_first && !changes.is_empty() {
            self.logged_first = true;
            tracing::info!(?changes, "keyboard mode: first input from the phone turned into key/mouse events");
        }
        self.sh.poster.apply(&changes);
        Ok(())
    }
}

impl Drop for KeyboardPad {
    // Safe release: stop the pointer, then lift every key and button still held.
    fn drop(&mut self) {
        self.sh.stop.store(true, Ordering::Relaxed);
        if let Some(h) = self.mover.take() { let _ = h.join(); }
        let changes = self.sh.state.lock().unwrap().1.release_all();
        self.sh.poster.apply(&changes);
    }
}
