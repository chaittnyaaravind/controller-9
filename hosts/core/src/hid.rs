//! HID gamepad report descriptor + report packing shared by the macOS (IOHIDUserDevice) and
//! Windows (VHF driver) backends. The Windows driver embeds the same bytes in C; a test keeps
//! `hosts/windows/driver/NineVhf/descriptor.h` identical to this constant.
use nine_proto::input::{Button, InputState};

#[rustfmt::skip]
pub const GAMEPAD_DESCRIPTOR: [u8; 91] = [
    0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,                         // Generic Desktop / Game Pad / Application
    0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, // Buttons 1..16
    0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
    0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, // Hat switch 0..7, null state
    0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
    0x65, 0x00, 0x45, 0x00, 0x75, 0x04, 0x95, 0x01, 0x81, 0x03, // 4-bit pad, units reset
    0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,             // X Y (left stick) Z Rz (right stick)
    0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x04, 0x81, 0x02,
    0x09, 0x33, 0x09, 0x34, 0x15, 0x00, 0x27, 0xFF, 0xFF, 0x00, 0x00, // Rx Ry = LT RT 0..65535
    0x75, 0x10, 0x95, 0x02, 0x81, 0x02,
    0xC0,
];

pub const REPORT_LEN: usize = 15;

/// HID button order (1-based usage = index + 1).
const ORDER: [Button; 11] = [Button::A, Button::B, Button::X, Button::Y, Button::Lb, Button::Rb,
    Button::View, Button::Menu, Button::Ls, Button::Rs, Button::Guide];

pub fn hat(s: &InputState) -> u8 {
    let (u, d, l, r) = (s.pressed(Button::Up), s.pressed(Button::Down), s.pressed(Button::Left), s.pressed(Button::Right));
    match (u && !d, d && !u, l && !r, r && !l) {
        (true, _, false, false) => 0, (true, _, _, true) => 1, (_, _, false, true) if !u && !d => 2,
        (_, true, _, true) => 3, (_, true, false, false) => 4, (_, true, true, _) => 5,
        (false, false, true, _) => 6, (true, _, true, _) => 7, _ => 8,
    }
}

/// HID Y axes grow downward; protocol Y grows upward.
pub fn report(s: &InputState) -> [u8; REPORT_LEN] {
    let mut b = [0u8; REPORT_LEN];
    let mut buttons: u16 = 0;
    for (i, btn) in ORDER.iter().enumerate() { if s.pressed(*btn) { buttons |= 1 << i; } }
    b[0..2].copy_from_slice(&buttons.to_le_bytes());
    b[2] = hat(s);
    let neg = |v: i16| v.saturating_neg().max(-32767);
    for (i, v) in [s.lx, neg(s.ly), s.rx, neg(s.ry)].iter().enumerate() { b[3 + 2 * i..5 + 2 * i].copy_from_slice(&v.to_le_bytes()); }
    b[11..13].copy_from_slice(&s.lt.to_le_bytes());
    b[13..15].copy_from_slice(&s.rt.to_le_bytes());
    b
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hat_directions() {
        let st = |bs: &[Button]| InputState { buttons: bs.iter().fold(0, |a, b| a | b.mask()), ..Default::default() };
        assert_eq!(hat(&st(&[])), 8);
        assert_eq!(hat(&st(&[Button::Up])), 0);
        assert_eq!(hat(&st(&[Button::Up, Button::Right])), 1);
        assert_eq!(hat(&st(&[Button::Right])), 2);
        assert_eq!(hat(&st(&[Button::Down, Button::Right])), 3);
        assert_eq!(hat(&st(&[Button::Down])), 4);
        assert_eq!(hat(&st(&[Button::Down, Button::Left])), 5);
        assert_eq!(hat(&st(&[Button::Left])), 6);
        assert_eq!(hat(&st(&[Button::Up, Button::Left])), 7);
        assert_eq!(hat(&st(&[Button::Up, Button::Down])), 8, "opposites cancel");
    }

    #[test]
    fn report_layout() {
        let s = InputState { buttons: Button::A.mask() | Button::Guide.mask(), lx: -32767, ly: 32767, rx: 1, ry: -1, lt: 0x1234, rt: 65535 };
        let r = report(&s);
        assert_eq!(u16::from_le_bytes([r[0], r[1]]), 1 | 1 << 10);
        assert_eq!(r[2], 8);
        assert_eq!(i16::from_le_bytes([r[3], r[4]]), -32767);
        assert_eq!(i16::from_le_bytes([r[5], r[6]]), -32767, "up -> HID negative");
        assert_eq!(i16::from_le_bytes([r[9], r[10]]), 1);
        assert_eq!(u16::from_le_bytes([r[11], r[12]]), 0x1234);
        assert_eq!(u16::from_le_bytes([r[13], r[14]]), 65535);
    }

    /// Bits declared by the descriptor must equal the report size.
    #[test]
    fn descriptor_size_matches_report() {
        let d = &GAMEPAD_DESCRIPTOR;
        let (mut i, mut size, mut count, mut bits) = (0, 0u32, 0u32, 0u32);
        while i < d.len() {
            let prefix = d[i];
            let len = match prefix & 3 { 3 => 4, n => n as usize };
            let val = d[i + 1..i + 1 + len].iter().rev().fold(0u32, |a, b| a << 8 | *b as u32);
            match prefix & 0xFC { 0x74 => size = val, 0x94 => count = val, 0x80 => bits += size * count, _ => {} }
            i += 1 + len;
        }
        assert_eq!(bits, (REPORT_LEN * 8) as u32);
    }
}
