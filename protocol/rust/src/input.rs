//! Common input model shared by every client and host.

pub const AXIS_MAX: i16 = 32767;
pub const TRIGGER_MAX: u16 = 65535;

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Button {
    A = 0, B = 1, X = 2, Y = 3, Lb = 4, Rb = 5, View = 6, Menu = 7,
    Ls = 8, Rs = 9, Up = 10, Down = 11, Left = 12, Right = 13, Guide = 14,
}

impl Button {
    pub const ALL: [Button; 15] = [
        Button::A, Button::B, Button::X, Button::Y, Button::Lb, Button::Rb, Button::View,
        Button::Menu, Button::Ls, Button::Rs, Button::Up, Button::Down, Button::Left,
        Button::Right, Button::Guide,
    ];
    pub const fn mask(self) -> u32 { 1 << self as u8 }
    pub fn from_name(s: &str) -> Option<Button> {
        Some(match s {
            "a" => Button::A, "b" => Button::B, "x" => Button::X, "y" => Button::Y,
            "lb" => Button::Lb, "rb" => Button::Rb, "view" => Button::View, "menu" => Button::Menu,
            "ls" => Button::Ls, "rs" => Button::Rs, "up" => Button::Up, "down" => Button::Down,
            "left" => Button::Left, "right" => Button::Right, "guide" => Button::Guide,
            _ => return None,
        })
    }
    pub fn name(self) -> &'static str {
        ["a", "b", "x", "y", "lb", "rb", "view", "menu", "ls", "rs", "up", "down", "left", "right", "guide"][self as usize]
    }
}

pub const VALID_BUTTONS: u32 = 0x7FFF;
pub const VALID_FLAGS: u16 = 0x7;
pub const FLAG_MOTION: u16 = 1;
pub const FLAG_CALIBRATING: u16 = 2;
pub const FLAG_PAUSED: u16 = 4;

/// Complete controller state. Y axes positive up; axes -32767..=32767; triggers 0..=65535.
#[derive(Copy, Clone, Debug, Default, PartialEq, Eq)]
pub struct InputState {
    pub buttons: u32,
    pub lx: i16, pub ly: i16, pub rx: i16, pub ry: i16,
    pub lt: u16, pub rt: u16,
}

impl InputState {
    pub const NEUTRAL: InputState = InputState { buttons: 0, lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0 };
    pub fn is_neutral(&self) -> bool { *self == Self::NEUTRAL }
    pub fn pressed(&self, b: Button) -> bool { self.buttons & b.mask() != 0 }
    /// Buttons that went down / up between `prev` and `self`.
    pub fn transitions(&self, prev: &InputState) -> (u32, u32) {
        (self.buttons & !prev.buttons, prev.buttons & !self.buttons)
    }
}

/// Map a normalized value in [-1, 1] to an axis value.
pub fn axis_from_unit(v: f64) -> i16 {
    (v.clamp(-1.0, 1.0) * AXIS_MAX as f64).round() as i16
}
/// Map a normalized value in [0, 1] to a trigger value.
pub fn trigger_from_unit(v: f64) -> u16 {
    (v.clamp(0.0, 1.0) * TRIGGER_MAX as f64).round() as u16
}
