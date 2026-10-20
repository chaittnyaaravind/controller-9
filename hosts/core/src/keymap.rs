//! Keyboard & mouse mode: the controller as the keys and mouse a PC game already understands.
//! Used where no virtual gamepad can be created (macOS without Apple's virtual-HID entitlement).
//! OS-independent: emits logical keys; each OS backend maps them to its key codes.
use nine_proto::input::{Button, InputState};
use std::collections::BTreeSet;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Key { W, A, S, D, Up, Down, Left, Right, Space, C, R, F, Q, E, V, Tab, Escape, Shift }

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum Out { Key(Key), MouseLeft, MouseRight }

/// The default layout — the common PC action-game bindings.
pub const BUTTONS: [(Button, Out); 14] = [
    (Button::A, Out::Key(Key::Space)), (Button::B, Out::Key(Key::C)), (Button::X, Out::Key(Key::R)),
    (Button::Y, Out::Key(Key::F)), (Button::Lb, Out::Key(Key::Q)), (Button::Rb, Out::Key(Key::E)),
    (Button::View, Out::Key(Key::Tab)), (Button::Menu, Out::Key(Key::Escape)), (Button::Ls, Out::Key(Key::Shift)),
    (Button::Rs, Out::Key(Key::V)), (Button::Up, Out::Key(Key::Up)), (Button::Down, Out::Key(Key::Down)),
    (Button::Left, Out::Key(Key::Left)), (Button::Right, Out::Key(Key::Right)),
];

/// Stick press and release points (fractions of full deflection) — the gap stops chatter.
const PRESS: f32 = 0.5;
const RELEASE: f32 = 0.35;
/// Trigger press and release (fractions of full pull) for the mouse buttons.
const T_PRESS: f32 = 0.5;
const T_RELEASE: f32 = 0.3;

/// Right-stick mouse: dead zone, then a squared curve up to this speed (pixels per second).
pub const MOUSE_MAX_PX_S: f32 = 1600.0;
const MOUSE_DEAD: f32 = 0.12;

#[derive(Default)]
pub struct Mapper { held: BTreeSet<Out> }

impl Mapper {
    /// Press/release changes for a new state, in a stable order (releases first).
    pub fn update(&mut self, s: &InputState) -> Vec<(Out, bool)> {
        let mut want = BTreeSet::new();
        for (b, o) in BUTTONS { if s.buttons & b.mask() != 0 { want.insert(o); } }
        let axis = |v: i16| v as f32 / 32767.0;
        let (x, y) = (axis(s.lx), axis(s.ly));
        let held = &self.held;
        let mut stick = |o: Out, v: f32| { let t = if held.contains(&o) { RELEASE } else { PRESS }; if v > t { want.insert(o); } };
        stick(Out::Key(Key::W), y); stick(Out::Key(Key::S), -y); stick(Out::Key(Key::D), x); stick(Out::Key(Key::A), -x);
        let trig = |o: Out, v: u16| { let t = if held.contains(&o) { T_RELEASE } else { T_PRESS }; v as f32 / 65535.0 > t };
        if trig(Out::MouseLeft, s.rt) { want.insert(Out::MouseLeft); }
        if trig(Out::MouseRight, s.lt) { want.insert(Out::MouseRight); }
        let mut changes: Vec<(Out, bool)> = self.held.difference(&want).map(|o| (*o, false)).collect();
        changes.extend(want.difference(&self.held).map(|o| (*o, true)));
        self.held = want;
        changes
    }

    /// Releases everything held — on disconnect, timeout or shutdown nothing stays pressed.
    pub fn release_all(&mut self) -> Vec<(Out, bool)> {
        std::mem::take(&mut self.held).into_iter().map(|o| (o, false)).collect()
    }

    pub fn holding(&self, o: Out) -> bool { self.held.contains(&o) }
}

/// Mouse velocity in pixels per second from the right stick (screen y grows downward).
pub fn mouse_velocity(s: &InputState) -> (f32, f32) {
    let (x, y) = (s.rx as f32 / 32767.0, s.ry as f32 / 32767.0);
    let m = (x * x + y * y).sqrt();
    if m <= MOUSE_DEAD { return (0.0, 0.0); }
    let k = ((m - MOUSE_DEAD) / (1.0 - MOUSE_DEAD)).min(1.0);
    let speed = k * k * MOUSE_MAX_PX_S / m;
    (x * speed, -y * speed)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn st(f: impl FnOnce(&mut InputState)) -> InputState { let mut s = InputState::NEUTRAL; f(&mut s); s }

    #[test]
    fn every_button_has_its_own_output() {
        let outs: BTreeSet<Out> = BUTTONS.iter().map(|(_, o)| *o).collect();
        assert_eq!(outs.len(), BUTTONS.len());
        let mut m = Mapper::default();
        for (b, o) in BUTTONS {
            assert_eq!(m.update(&st(|s| s.buttons = b.mask())).iter().filter(|c| c.1).map(|c| c.0).collect::<Vec<_>>(), vec![o]);
            assert_eq!(m.update(&InputState::NEUTRAL), vec![(o, false)]);
        }
    }

    #[test]
    fn left_stick_is_wasd_with_hysteresis() {
        let mut m = Mapper::default();
        assert!(m.update(&st(|s| s.ly = 13_000)).is_empty(), "0.40 does not press");
        assert_eq!(m.update(&st(|s| s.ly = 20_000)), vec![(Out::Key(Key::W), true)]);
        assert!(m.update(&st(|s| s.ly = 13_000)).is_empty(), "0.40 keeps it held");
        assert_eq!(m.update(&st(|s| s.ly = 10_000)), vec![(Out::Key(Key::W), false)]);
        let c = m.update(&st(|s| { s.lx = -32767; s.ly = -32767; }));
        assert!(c.contains(&(Out::Key(Key::A), true)) && c.contains(&(Out::Key(Key::S), true)));
    }

    #[test]
    fn triggers_are_mouse_buttons_and_release_all_clears() {
        let mut m = Mapper::default();
        let c = m.update(&st(|s| { s.rt = 65535; s.lt = 65535; s.buttons = Button::A.mask(); }));
        assert_eq!(c.len(), 3);
        let mut r = m.release_all(); r.sort();
        assert_eq!(r, vec![(Out::Key(Key::Space), false), (Out::MouseLeft, false), (Out::MouseRight, false)]);
        assert!(m.release_all().is_empty());
    }

    #[test]
    fn mouse_has_a_dead_zone_and_tops_out() {
        assert_eq!(mouse_velocity(&st(|s| s.rx = 3000)), (0.0, 0.0));
        let (vx, vy) = mouse_velocity(&st(|s| s.rx = 32767));
        assert!((vx - MOUSE_MAX_PX_S).abs() < 1.0 && vy == 0.0);
        let (_, vy) = mouse_velocity(&st(|s| s.ry = 32767));
        assert!(vy < -1500.0, "stick up moves the pointer up");
        let (half, _) = mouse_velocity(&st(|s| s.rx = 16384));
        assert!(half > 0.0 && half < MOUSE_MAX_PX_S / 3.0, "squared curve: {half}");
    }
}
