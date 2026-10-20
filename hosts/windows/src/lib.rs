//! Windows virtual gamepads through the NineVhf KMDF driver (`driver/NineVhf`), which creates
//! HID gamepads with Microsoft's Virtual HID Framework. Visible to DirectInput, Raw Input,
//! Windows.Gaming.Input (RawGameController), SDL and Steam Input. XInput-only games need Steam
//! Input (or another XInput layer) — a generic HID gamepad is not an XInput device.

/// IOCTL codes — must match `driver/NineVhf/public.h`.
pub mod abi {
    const fn ctl(func: u32, access: u32) -> u32 { (0x22 << 16) | (access << 14) | (func << 2) }
    pub const IOCTL_CREATE: u32 = ctl(0x901, 2);
    pub const IOCTL_SUBMIT: u32 = ctl(0x902, 2);
    pub const IOCTL_DESTROY: u32 = ctl(0x903, 2);
    pub const IOCTL_VERSION: u32 = ctl(0x904, 1);
    pub const INTERFACE_VERSION: u32 = 1;
    /// {6E1E3B2A-9C7F-4D0B-9A51-3C2F8E9D4A01}
    pub const INTERFACE_GUID: u128 = 0x6e1e3b2a_9c7f_4d0b_9a51_3c2f8e9d4a01;
}

#[cfg(windows)]
mod vhf;
#[cfg(windows)]
pub use vhf::VhfBackend;
#[cfg(windows)]
mod vigem;
#[cfg(windows)]
pub use vigem::VigemBackend;

/// The backend the Windows hosts use: ViGEmBus (a production-signed, freely installable driver that
/// creates Xbox 360 controllers every XInput game sees) when present, else the NineVhf driver when
/// installed, else ViGEm's "blocked" status, which tells the user how to install ViGEmBus.
#[cfg(windows)]
pub fn native_backend() -> std::sync::Arc<dyn nine_host_core::backend::Backend> {
    use nine_host_core::backend::Backend;
    let vigem = VigemBackend;
    if vigem.status().available { return std::sync::Arc::new(vigem); }
    let vhf = VhfBackend::default();
    if vhf.status().available { return std::sync::Arc::new(vhf); }
    std::sync::Arc::new(vigem)
}

/// XInput gamepad fields from the shared input model. Portable so it is tested on every OS.
pub mod xinput {
    use nine_proto::input::{Button, InputState};

    pub const DPAD_UP: u16 = 0x0001; pub const DPAD_DOWN: u16 = 0x0002;
    pub const DPAD_LEFT: u16 = 0x0004; pub const DPAD_RIGHT: u16 = 0x0008;
    pub const START: u16 = 0x0010; pub const BACK: u16 = 0x0020;
    pub const LTHUMB: u16 = 0x0040; pub const RTHUMB: u16 = 0x0080;
    pub const LB: u16 = 0x0100; pub const RB: u16 = 0x0200; pub const GUIDE: u16 = 0x0400;
    pub const A: u16 = 0x1000; pub const B: u16 = 0x2000; pub const X: u16 = 0x4000; pub const Y: u16 = 0x8000;

    #[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
    pub struct Pad { pub buttons: u16, pub lt: u8, pub rt: u8, pub lx: i16, pub ly: i16, pub rx: i16, pub ry: i16 }

    const MAP: [(Button, u16); 15] = [
        (Button::A, A), (Button::B, B), (Button::X, X), (Button::Y, Y), (Button::Lb, LB), (Button::Rb, RB),
        (Button::View, BACK), (Button::Menu, START), (Button::Ls, LTHUMB), (Button::Rs, RTHUMB),
        (Button::Up, DPAD_UP), (Button::Down, DPAD_DOWN), (Button::Left, DPAD_LEFT), (Button::Right, DPAD_RIGHT),
        (Button::Guide, GUIDE),
    ];

    /// Both models use Y-positive-up and the same i16 stick range; triggers narrow 16 → 8 bits.
    pub fn pad(s: &InputState) -> Pad {
        let buttons = MAP.iter().filter(|(b, _)| s.buttons & b.mask() != 0).fold(0, |acc, (_, x)| acc | x);
        Pad { buttons, lt: (s.lt >> 8) as u8, rt: (s.rt >> 8) as u8, lx: s.lx, ly: s.ly, rx: s.rx, ry: s.ry }
    }

    /// XInput motors are 0–255 and hold until changed; the phone's rumble is 0–65535 with a duration,
    /// so a running motor is sent with the longest duration and re-sent on every change (as on Linux).
    pub fn feedback(large: u8, small: u8) -> nine_proto::frame::Feedback {
        let on = large != 0 || small != 0;
        nine_proto::frame::Feedback { low: large as u16 * 257, high: small as u16 * 257, ms: if on { 5000 } else { 0 } }
    }
}

#[cfg(test)]
mod tests {
    use super::xinput;
    use nine_proto::input::{Button, InputState};

    #[test]
    fn every_button_maps_to_its_own_xinput_bit() {
        let mut seen = 0u16;
        for b in Button::ALL {
            let p = xinput::pad(&InputState { buttons: b.mask(), ..InputState::NEUTRAL });
            assert_eq!(p.buttons.count_ones(), 1, "{b:?}");
            assert_eq!(seen & p.buttons, 0, "{b:?} reuses a bit");
            seen |= p.buttons;
        }
        assert_eq!(xinput::pad(&InputState { buttons: Button::Menu.mask(), ..InputState::NEUTRAL }).buttons, xinput::START);
        assert_eq!(xinput::pad(&InputState { buttons: Button::View.mask(), ..InputState::NEUTRAL }).buttons, xinput::BACK);
    }

    #[test]
    fn axes_and_triggers_keep_their_range_and_direction() {
        let p = xinput::pad(&InputState { buttons: 0, lx: -32767, ly: 32767, rx: 123, ry: -5, lt: 65535, rt: 255 });
        assert_eq!((p.lx, p.ly, p.rx, p.ry), (-32767, 32767, 123, -5));
        assert_eq!((p.lt, p.rt), (255, 0));
        assert_eq!(xinput::pad(&InputState::NEUTRAL), xinput::Pad::default());
    }

    #[test]
    fn rumble_scales_and_stops() {
        let f = xinput::feedback(255, 128);
        assert_eq!((f.low, f.high, f.ms), (65535, 128 * 257, 5000));
        assert_eq!(xinput::feedback(0, 0).ms, 0);
    }

    #[test]
    fn ioctl_codes_match_ctl_code_macro() {
        // CTL_CODE(FILE_DEVICE_UNKNOWN, 0x901, METHOD_BUFFERED, FILE_WRITE_ACCESS)
        assert_eq!(super::abi::IOCTL_CREATE, 0x0022_A404);
        assert_eq!(super::abi::IOCTL_VERSION, 0x0022_6410);
    }

    #[test]
    fn driver_descriptor_matches_host_descriptor() {
        let c = include_str!("../driver/NineVhf/descriptor.h");
        let body = c.split('{').nth(1).unwrap().split("};").next().unwrap();
        let bytes: Vec<u8> = body.lines()
            .map(|l| l.split("/*").next().unwrap())
            .flat_map(|l| l.split(','))
            .filter_map(|t| t.trim().strip_prefix("0x").map(|h| u8::from_str_radix(h, 16).unwrap()))
            .collect();
        assert_eq!(bytes, nine_host_core::hid::GAMEPAD_DESCRIPTOR.to_vec());
    }

    #[test]
    fn guid_matches_public_h() {
        let h = include_str!("../driver/NineVhf/public.h");
        assert!(h.contains("0x6e1e3b2a, 0x9c7f, 0x4d0b, 0x9a, 0x51, 0x3c, 0x2f, 0x8e, 0x9d, 0x4a, 0x01"));
        assert_eq!(super::abi::INTERFACE_GUID, 0x6e1e3b2a_9c7f_4d0b_9a51_3c2f8e9d4a01);
    }
}
