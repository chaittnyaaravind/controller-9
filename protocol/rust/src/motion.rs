//! Motion steering pipeline shared by the Android and iOS clients (reference implementation).
//!
//! Input: gravity in *screen* coordinates (x right, y up, z out of the screen), any unit.
//! Output: normalized steering in [-1, 1], positive = clockwise rotation = steer right.
use crate::profile::Motion;

/// Wheel angle in degrees, 0 when the screen is upright, positive when rotated clockwise.
/// `None` when the phone is too close to flat for a reliable angle (hold the last value).
pub fn wheel_angle_deg(gx: f64, gy: f64, gz: f64) -> Option<f64> {
    let g = (gx * gx + gy * gy + gz * gz).sqrt();
    let gxy = gx.hypot(gy);
    if g < 1e-6 || gxy < 0.35 * g { return None; }
    Some(gx.atan2(-gy).to_degrees())
}

pub fn wrap_deg(d: f64) -> f64 {
    let mut d = (d + 180.0) % 360.0;
    if d < 0.0 { d += 360.0; }
    d - 180.0
}

/// One Euro filter (Casiez, Roussel, Vogel — CHI 2012).
#[derive(Clone, Debug)]
pub struct OneEuro { min_cutoff: f64, beta: f64, d_cutoff: f64, prev: Option<(f64, f64, f64)> }

impl OneEuro {
    pub fn new(min_cutoff: f64, beta: f64) -> Self { Self { min_cutoff, beta, d_cutoff: 1.0, prev: None } }
    fn alpha(cutoff: f64, dt: f64) -> f64 {
        let tau = 1.0 / (2.0 * std::f64::consts::PI * cutoff);
        1.0 / (1.0 + tau / dt)
    }
    pub fn reset(&mut self) { self.prev = None; }
    /// `t` in seconds, monotonic.
    pub fn filter(&mut self, x: f64, t: f64) -> f64 {
        let Some((px, pdx, pt)) = self.prev else { self.prev = Some((x, 0.0, t)); return x };
        let dt = if t > pt { t - pt } else { 1.0 / 120.0 };
        let dx = (x - px) / dt;
        let edx = pdx + Self::alpha(self.d_cutoff, dt) * (dx - pdx);
        let cutoff = self.min_cutoff + self.beta * edx.abs();
        let y = px + Self::alpha(cutoff, dt) * (x - px);
        self.prev = Some((y, edx, t));
        y
    }
}

/// Lock, sensitivity, invert, dead zone and response curve applied to an angle from center.
pub fn shape(delta_deg: f64, m: &Motion) -> f64 {
    let mut x = delta_deg / m.lock_deg * m.sensitivity;
    if m.invert { x = -x; }
    let x = x.clamp(-1.0, 1.0);
    let a = x.abs();
    if a <= m.deadzone { return 0.0; }
    let a = ((a - m.deadzone) / (1.0 - m.deadzone)).powf(m.curve);
    a.copysign(x)
}

#[derive(Clone, Debug)]
pub struct Steering { pub motion: Motion, center_deg: f64, filter: OneEuro, last_delta: f64 }

impl Steering {
    pub fn new(motion: Motion) -> Self {
        let filter = OneEuro::new(motion.min_cutoff, motion.beta);
        Self { motion, center_deg: 0.0, filter, last_delta: 0.0 }
    }
    pub fn set_center(&mut self, deg: f64) { self.center_deg = deg; self.filter.reset(); self.last_delta = 0.0; }
    pub fn center(&self) -> f64 { self.center_deg }
    /// Returns normalized steering in [-1, 1].
    pub fn update(&mut self, gx: f64, gy: f64, gz: f64, t: f64) -> f64 {
        let delta = match wheel_angle_deg(gx, gy, gz) {
            Some(a) => wrap_deg(a - self.center_deg),
            None => self.last_delta,
        };
        self.last_delta = delta;
        let f = self.filter.filter(delta, t);
        shape(f, &self.motion)
    }
}

/// Collects angles while the user holds the phone at its neutral position.
#[derive(Default, Clone, Debug)]
pub struct Calibrator { sin: f64, cos: f64, samples: Vec<f64> }

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Calibration { pub center_deg: f64, pub spread_deg: f64, pub samples: usize }

impl Calibrator {
    pub fn add(&mut self, gx: f64, gy: f64, gz: f64) {
        if let Some(a) = wheel_angle_deg(gx, gy, gz) {
            self.sin += a.to_radians().sin();
            self.cos += a.to_radians().cos();
            self.samples.push(a);
        }
    }
    /// Circular mean and RMS spread. A spread above ~1.5 degrees means the phone was not held still.
    pub fn finish(&self) -> Option<Calibration> {
        if self.samples.len() < 10 { return None; }
        let c = self.sin.atan2(self.cos).to_degrees();
        let var = self.samples.iter().map(|a| wrap_deg(a - c).powi(2)).sum::<f64>() / self.samples.len() as f64;
        Some(Calibration { center_deg: c, spread_deg: var.sqrt(), samples: self.samples.len() })
    }
}

/// Radial stick dead zone with rescale so output still reaches the edge.
pub fn radial_deadzone(x: f64, y: f64, dz: f64) -> (f64, f64) {
    let m = x.hypot(y);
    if m <= dz || m == 0.0 { return (0.0, 0.0); }
    let s = ((m - dz) / (1.0 - dz)).min(1.0) / m;
    ((x * s).clamp(-1.0, 1.0), (y * s).clamp(-1.0, 1.0))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn m() -> Motion { Motion::default() }

    #[test]
    fn angle_convention() {
        assert_eq!(wheel_angle_deg(0.0, -9.8, 0.0), Some(0.0));
        let a = wheel_angle_deg(9.8 * 30f64.to_radians().sin(), -9.8 * 30f64.to_radians().cos(), 0.0).unwrap();
        assert!((a - 30.0).abs() < 1e-9, "clockwise 30 deg -> +30, got {a}");
        assert!(wheel_angle_deg(0.5, -0.5, -9.7).is_none(), "flat phone has no wheel angle");
    }

    #[test]
    fn shape_lock_deadzone_curve_invert() {
        let mut p = m();
        p.lock_deg = 45.0; p.deadzone = 0.1; p.curve = 2.0; p.sensitivity = 1.0;
        assert_eq!(shape(2.0, &p), 0.0);
        assert_eq!(shape(90.0, &p), 1.0);
        assert_eq!(shape(-90.0, &p), -1.0);
        let half = shape(22.5, &p); // x = .5 -> ((.5-.1)/.9)^2
        assert!((half - (0.4f64 / 0.9).powi(2)).abs() < 1e-12);
        p.invert = true;
        assert!(shape(22.5, &p) < 0.0);
    }

    #[test]
    fn steering_tracks_and_recenters() {
        let mut s = Steering::new(m());
        s.set_center(10.0);
        let r = 10f64.to_radians();
        let mut out = 0.0;
        for i in 0..240 { out = s.update(r.sin(), -r.cos(), 0.0, i as f64 / 120.0); }
        assert!(out.abs() < 1e-9, "holding at the calibrated center gives 0, got {out}");
    }

    #[test]
    fn calibrator() {
        let mut c = Calibrator::default();
        for i in 0..50 {
            let a = (5.0 + if i % 2 == 0 { 0.2 } else { -0.2 }) as f64;
            c.add(a.to_radians().sin(), -a.to_radians().cos(), 0.0);
        }
        let cal = c.finish().unwrap();
        assert!((cal.center_deg - 5.0).abs() < 1e-6 && cal.spread_deg < 0.3);
        assert!(Calibrator::default().finish().is_none());
    }

    #[test]
    fn deadzone() {
        assert_eq!(radial_deadzone(0.05, 0.0, 0.1), (0.0, 0.0));
        let (x, _) = radial_deadzone(1.0, 0.0, 0.1);
        assert!((x - 1.0).abs() < 1e-12);
    }
}
