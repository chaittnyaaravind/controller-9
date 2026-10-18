use nine_host_core::backend::{Backend, BackendError, BackendKind, BackendStatus, VirtualPad};
use nine_proto::frame::Feedback;
use nine_proto::input::{Button, InputState};
use std::collections::HashMap;
use std::ffi::CString;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

// --- kernel ABI (include/uapi/linux/input.h, input-event-codes.h, uinput.h) -----------------
const EV_SYN: u16 = 0x00;
const EV_KEY: u16 = 0x01;
const EV_ABS: u16 = 0x03;
const EV_FF: u16 = 0x15;
const EV_UINPUT: u16 = 0x0101;
const SYN_REPORT: u16 = 0;
const UI_FF_UPLOAD: u16 = 1;
const UI_FF_ERASE: u16 = 2;
const FF_RUMBLE: u16 = 0x50;
const BUS_USB: u16 = 0x03;
const BUS_VIRTUAL: u16 = 0x06;

const BTN_SOUTH: u16 = 0x130;
const BTN_EAST: u16 = 0x131;
const BTN_X: u16 = 0x133; // xpad reports the Xbox X button as BTN_X (== BTN_NORTH)
const BTN_Y: u16 = 0x134; // and Y as BTN_Y (== BTN_WEST); SDL's mappings expect this
const BTN_TL: u16 = 0x136;
const BTN_TR: u16 = 0x137;
const BTN_SELECT: u16 = 0x13a;
const BTN_START: u16 = 0x13b;
const BTN_MODE: u16 = 0x13c;
const BTN_THUMBL: u16 = 0x13d;
const BTN_THUMBR: u16 = 0x13e;
const ABS_X: u16 = 0x00;
const ABS_Y: u16 = 0x01;
const ABS_Z: u16 = 0x02;
const ABS_RX: u16 = 0x03;
const ABS_RY: u16 = 0x04;
const ABS_RZ: u16 = 0x05;
const ABS_HAT0X: u16 = 0x10;
const ABS_HAT0Y: u16 = 0x11;
const TRIGGER_MAX: i32 = 1023;

const fn ioc(dir: u64, nr: u64, size: usize) -> u64 { (dir << 30) | ((size as u64) << 16) | ((b'U' as u64) << 8) | nr }
const IOC_W: u64 = 1;
const IOC_RW: u64 = 3;

#[repr(C)]
struct InputId { bustype: u16, vendor: u16, product: u16, version: u16 }
#[repr(C)]
struct UinputSetup { id: InputId, name: [u8; 80], ff_effects_max: u32 }
#[repr(C)]
struct AbsInfo { value: i32, minimum: i32, maximum: i32, fuzz: i32, flat: i32, resolution: i32 }
#[repr(C)]
struct UinputAbsSetup { code: u16, absinfo: AbsInfo }
#[repr(C)]
#[derive(Clone, Copy)]
struct FfEffect {
    kind: u16, id: i16, direction: u16, trigger_button: u16, trigger_interval: u16, replay_length: u16, replay_delay: u16,
    #[cfg(target_pointer_width = "64")] u: [u64; 4],
    #[cfg(target_pointer_width = "32")] u: [u32; 7],
}
#[repr(C)]
struct UinputFfUpload { request_id: u32, retval: i32, effect: FfEffect, old: FfEffect }
#[repr(C)]
struct UinputFfErase { request_id: u32, retval: i32, effect_id: u32 }
#[repr(C)]
struct InputEvent { time: libc::timeval, kind: u16, code: u16, value: i32 }

#[cfg(target_pointer_width = "64")]
const _: () = { assert!(size_of::<FfEffect>() == 48); assert!(size_of::<InputEvent>() == 24); };
const _: () = { assert!(size_of::<UinputSetup>() == 92); assert!(size_of::<UinputAbsSetup>() == 28); };

const UI_DEV_CREATE: u64 = ioc(0, 1, 0);
const UI_DEV_DESTROY: u64 = ioc(0, 2, 0);
const UI_DEV_SETUP: u64 = ioc(IOC_W, 3, size_of::<UinputSetup>());
const UI_ABS_SETUP: u64 = ioc(IOC_W, 4, size_of::<UinputAbsSetup>());
const UI_SET_EVBIT: u64 = ioc(IOC_W, 100, 4);
const UI_SET_KEYBIT: u64 = ioc(IOC_W, 101, 4);
const UI_SET_ABSBIT: u64 = ioc(IOC_W, 103, 4);
const UI_SET_FFBIT: u64 = ioc(IOC_W, 107, 4);
const UI_BEGIN_FF_UPLOAD: u64 = ioc(IOC_RW, 200, size_of::<UinputFfUpload>());
const UI_END_FF_UPLOAD: u64 = ioc(IOC_W, 201, size_of::<UinputFfUpload>());
const UI_BEGIN_FF_ERASE: u64 = ioc(IOC_RW, 202, size_of::<UinputFfErase>());
const UI_END_FF_ERASE: u64 = ioc(IOC_W, 203, size_of::<UinputFfErase>());

fn ioctl_val(fd: i32, req: u64, v: u64) -> io::Result<()> {
    // SAFETY: integer-argument uinput ioctls on a valid fd.
    if unsafe { libc::ioctl(fd, req as _, v as libc::c_ulong) } < 0 { Err(io::Error::last_os_error()) } else { Ok(()) }
}
fn ioctl_ptr<T>(fd: i32, req: u64, p: *mut T) -> io::Result<()> {
    // SAFETY: `p` points to a live, correctly sized #[repr(C)] struct matching `req`.
    if unsafe { libc::ioctl(fd, req as _, p) } < 0 { Err(io::Error::last_os_error()) } else { Ok(()) }
}

/// Device identity presented to the OS.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Identity {
    /// USB 045e:028e, the identity of the wired Xbox 360 pad, so SDL, Steam and Proton apply
    /// their standard Xbox mapping. This is a compatibility identity, not an access-control bypass.
    XboxCompatible,
    /// Virtual-bus device with no vendor id; maximal honesty, weaker game compatibility.
    Generic,
}

pub struct UinputBackend { pub identity: Identity, pub path: String }

impl Default for UinputBackend {
    fn default() -> Self { UinputBackend { identity: Identity::XboxCompatible, path: "/dev/uinput".into() } }
}

impl UinputBackend {
    fn open(&self) -> io::Result<OwnedFd> {
        let p = CString::new(self.path.clone()).unwrap();
        // SAFETY: valid NUL-terminated path; the returned fd is owned below.
        let fd = unsafe { libc::open(p.as_ptr(), libc::O_RDWR | libc::O_NONBLOCK | libc::O_CLOEXEC) };
        if fd < 0 { return Err(io::Error::last_os_error()); }
        // SAFETY: fd was just returned by open() and is not owned elsewhere.
        Ok(unsafe { OwnedFd::from_raw_fd(fd) })
    }
}

impl Backend for UinputBackend {
    fn status(&self) -> BackendStatus {
        let base = |available, kind, detail: String, remedy: Option<&str>| BackendStatus {
            name: "linux-uinput".into(), kind, available, max_pads: 4, detail, remedy: remedy.map(String::from) };
        match self.open() {
            Ok(_) => base(true, BackendKind::Native, format!("{} ready", self.path), None),
            Err(e) if e.kind() == io::ErrorKind::NotFound => base(false, BackendKind::Blocked, "uinput kernel module not loaded".into(),
                Some("sudo modprobe uinput && echo uinput | sudo tee /etc/modules-load.d/uinput.conf")),
            Err(e) if e.kind() == io::ErrorKind::PermissionDenied => base(false, BackendKind::Blocked, format!("no permission to open {}", self.path),
                Some("Install packaging/60-9controller-uinput.rules into /etc/udev/rules.d/, then: sudo udevadm control --reload && sudo udevadm trigger; log out and back in")),
            Err(e) => base(false, BackendKind::Blocked, format!("{}: {e}", self.path), None),
        }
    }

    fn create(&self, slot: u8, _name: &str) -> Result<Box<dyn VirtualPad>, BackendError> {
        let fd = self.open().map_err(|e| BackendError::Unavailable(format!("{}: {e}", self.path)))?;
        let raw = fd.as_raw_fd();
        let io_err = |e: io::Error| BackendError::Io(e.to_string());
        for ev in [EV_KEY, EV_ABS, EV_FF] { ioctl_val(raw, UI_SET_EVBIT, ev as u64).map_err(io_err)?; }
        for k in KEYS { ioctl_val(raw, UI_SET_KEYBIT, k as u64).map_err(io_err)?; }
        ioctl_val(raw, UI_SET_FFBIT, FF_RUMBLE as u64).map_err(io_err)?;
        let axes: [(u16, i32, i32, i32, i32); 8] = [
            (ABS_X, -32768, 32767, 16, 128), (ABS_Y, -32768, 32767, 16, 128),
            (ABS_RX, -32768, 32767, 16, 128), (ABS_RY, -32768, 32767, 16, 128),
            (ABS_Z, 0, TRIGGER_MAX, 0, 0), (ABS_RZ, 0, TRIGGER_MAX, 0, 0),
            (ABS_HAT0X, -1, 1, 0, 0), (ABS_HAT0Y, -1, 1, 0, 0),
        ];
        for (code, minimum, maximum, fuzz, flat) in axes {
            ioctl_val(raw, UI_SET_ABSBIT, code as u64).map_err(io_err)?;
            let mut a = UinputAbsSetup { code, absinfo: AbsInfo { value: 0, minimum, maximum, fuzz, flat, resolution: 0 } };
            ioctl_ptr(raw, UI_ABS_SETUP, &mut a).map_err(io_err)?;
        }
        let (bustype, vendor, product, version, label) = match self.identity {
            Identity::XboxCompatible => (BUS_USB, 0x045e, 0x028e, 0x0110, "9-controller (Xbox 360 compatible)"),
            Identity::Generic => (BUS_VIRTUAL, 0, 0, 1, "9-controller virtual gamepad"),
        };
        let mut name = [0u8; 80];
        let full = format!("{label} #{}", slot + 1);
        name[..full.len().min(79)].copy_from_slice(&full.as_bytes()[..full.len().min(79)]);
        let mut setup = UinputSetup { id: InputId { bustype, vendor, product, version }, name, ff_effects_max: 16 };
        ioctl_ptr(raw, UI_DEV_SETUP, &mut setup).map_err(io_err)?;
        ioctl_val(raw, UI_DEV_CREATE, 0).map_err(io_err)?;
        Ok(Box::new(UinputPad { fd, last: None, effects: HashMap::new() }))
    }
}

const KEYS: [u16; 11] = [BTN_SOUTH, BTN_EAST, BTN_X, BTN_Y, BTN_TL, BTN_TR, BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR];
const BUTTON_KEYS: [(Button, u16); 11] = [
    (Button::A, BTN_SOUTH), (Button::B, BTN_EAST), (Button::X, BTN_X), (Button::Y, BTN_Y),
    (Button::Lb, BTN_TL), (Button::Rb, BTN_TR), (Button::View, BTN_SELECT), (Button::Menu, BTN_START),
    (Button::Guide, BTN_MODE), (Button::Ls, BTN_THUMBL), (Button::Rs, BTN_THUMBR),
];

struct UinputPad { fd: OwnedFd, last: Option<InputState>, effects: HashMap<i16, (u16, u16, u16)> }

impl UinputPad {
    fn ev(kind: u16, code: u16, value: i32) -> InputEvent {
        InputEvent { time: libc::timeval { tv_sec: 0, tv_usec: 0 }, kind, code, value }
    }
}

/// Protocol state -> evdev events (only changed values, then one SYN_REPORT).
fn events(prev: Option<&InputState>, s: &InputState) -> Vec<(u16, u16, i32)> {
    let mut out = Vec::with_capacity(16);
    for (b, key) in BUTTON_KEYS {
        if prev.is_none_or(|p| p.pressed(b) != s.pressed(b)) { out.push((EV_KEY, key, s.pressed(b) as i32)); }
    }
    let hat = |neg: Button, pos: Button, st: &InputState| st.pressed(pos) as i32 - st.pressed(neg) as i32;
    let trig = |v: u16| (v as i32 * TRIGGER_MAX + 32767) / 65535;
    // evdev Y axes grow downward; the protocol's grow upward.
    let vals = |st: &InputState| [
        (ABS_X, st.lx as i32), (ABS_Y, -(st.ly as i32)), (ABS_RX, st.rx as i32), (ABS_RY, -(st.ry as i32)),
        (ABS_Z, trig(st.lt)), (ABS_RZ, trig(st.rt)),
        (ABS_HAT0X, hat(Button::Left, Button::Right, st)), (ABS_HAT0Y, hat(Button::Up, Button::Down, st)),
    ];
    let now = vals(s);
    let before = prev.map(vals);
    for (i, (code, v)) in now.iter().enumerate() {
        if before.is_none_or(|b| b[i].1 != *v) { out.push((EV_ABS, *code, *v)); }
    }
    if !out.is_empty() { out.push((EV_SYN, SYN_REPORT, 0)); }
    out
}

impl VirtualPad for UinputPad {
    fn submit(&mut self, s: &InputState) -> Result<(), BackendError> {
        let evs: Vec<InputEvent> = events(self.last.as_ref(), s).into_iter().map(|(k, c, v)| Self::ev(k, c, v)).collect();
        if evs.is_empty() { return Ok(()); }
        let bytes = evs.len() * size_of::<InputEvent>();
        // SAFETY: writing a contiguous slice of #[repr(C)] input_event structs.
        let n = unsafe { libc::write(self.fd.as_raw_fd(), evs.as_ptr().cast(), bytes) };
        if n < 0 { return Err(BackendError::Io(io::Error::last_os_error().to_string())); }
        self.last = Some(*s);
        Ok(())
    }

    fn poll_feedback(&mut self) -> Option<Feedback> {
        let raw = self.fd.as_raw_fd();
        let mut out = None;
        loop {
            let mut e = Self::ev(0, 0, 0);
            // SAFETY: reading one input_event into a properly sized struct.
            let n = unsafe { libc::read(raw, (&mut e as *mut InputEvent).cast(), size_of::<InputEvent>()) };
            if n != size_of::<InputEvent>() as isize { break; }
            match (e.kind, e.code) {
                (EV_UINPUT, UI_FF_UPLOAD) => {
                    // SAFETY: zeroed POD struct filled by the kernel.
                    let mut up: UinputFfUpload = unsafe { std::mem::zeroed() };
                    up.request_id = e.value as u32;
                    if ioctl_ptr(raw, UI_BEGIN_FF_UPLOAD, &mut up).is_ok() {
                        if up.effect.kind == FF_RUMBLE {
                            // ff_rumble_effect { strong_magnitude: u16, weak_magnitude: u16 } at the start of the union.
                            // SAFETY: union storage is at least 4 bytes; read the first two u16s.
                            let mags: [u16; 2] = unsafe { std::ptr::read_unaligned(up.effect.u.as_ptr().cast()) };
                            self.effects.insert(up.effect.id, (mags[0], mags[1], up.effect.replay_length));
                            up.retval = 0;
                        } else { up.retval = -libc::EINVAL; }
                        let _ = ioctl_ptr(raw, UI_END_FF_UPLOAD, &mut up);
                    }
                }
                (EV_UINPUT, UI_FF_ERASE) => {
                    let mut er = UinputFfErase { request_id: e.value as u32, retval: 0, effect_id: 0 };
                    if ioctl_ptr(raw, UI_BEGIN_FF_ERASE, &mut er).is_ok() {
                        self.effects.remove(&(er.effect_id as i16));
                        let _ = ioctl_ptr(raw, UI_END_FF_ERASE, &mut er);
                    }
                }
                (EV_FF, id) => {
                    let (strong, weak, len) = self.effects.get(&(id as i16)).copied().unwrap_or((0, 0, 0));
                    out = Some(if e.value > 0 {
                        Feedback { low: strong, high: weak, ms: if len == 0 { 5000 } else { len.min(5000) } }
                    } else { Feedback { low: 0, high: 0, ms: 0 } });
                }
                _ => {}
            }
        }
        out
    }
}

impl Drop for UinputPad {
    fn drop(&mut self) {
        let _ = self.submit(&InputState::NEUTRAL);
        let _ = ioctl_val(self.fd.as_raw_fd(), UI_DEV_DESTROY, 0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ioctl_numbers_match_kernel_headers() {
        assert_eq!(UI_DEV_CREATE, 0x5501);
        assert_eq!(UI_DEV_SETUP, 0x405c5503);
        assert_eq!(UI_ABS_SETUP, 0x401c5504);
        assert_eq!(UI_SET_EVBIT, 0x40045564);
        #[cfg(target_pointer_width = "64")]
        assert_eq!(UI_BEGIN_FF_UPLOAD, 0xc06855c8);
    }

    #[test]
    fn maps_state_to_events() {
        let s = InputState { buttons: Button::A.mask() | Button::Up.mask(), ly: 32767, rt: 65535, ..Default::default() };
        let e = events(None, &s);
        assert!(e.contains(&(EV_KEY, BTN_SOUTH, 1)));
        assert!(e.contains(&(EV_ABS, ABS_Y, -32767)), "protocol up = evdev negative");
        assert!(e.contains(&(EV_ABS, ABS_RZ, TRIGGER_MAX)));
        assert!(e.contains(&(EV_ABS, ABS_HAT0Y, -1)));
        assert_eq!(*e.last().unwrap(), (EV_SYN, SYN_REPORT, 0));
        assert!(events(Some(&s), &s).is_empty(), "no change, no events");
        let e2 = events(Some(&s), &InputState::NEUTRAL);
        assert!(e2.contains(&(EV_KEY, BTN_SOUTH, 0)) && e2.contains(&(EV_ABS, ABS_HAT0Y, 0)));
    }

    /// Real device creation. Runs only where /dev/uinput is accessible (Stage 4 on Linux).
    #[test]
    #[ignore]
    fn creates_real_device() {
        let b = UinputBackend::default();
        assert!(b.status().available, "{:?}", b.status());
        let mut pad = b.create(0, "test").unwrap();
        pad.submit(&InputState { buttons: Button::A.mask(), ..Default::default() }).unwrap();
        let found = std::fs::read_to_string("/proc/bus/input/devices").unwrap().contains("9-controller");
        assert!(found, "device not listed in /proc/bus/input/devices");
    }
}
