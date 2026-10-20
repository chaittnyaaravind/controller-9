//! Relative steering — the reference implementation for every phone client (docs/STEERING.md).
//! Kotlin (`protocol/kotlin/.../Steering.kt`) and Swift (`protocol/swift/.../Steering.swift`) must
//! reproduce `protocol/vectors/steering.json`, generated from this file.
//!
//! Frames. A rotation-vector sensor gives the phone orientation as a unit quaternion q mapping body
//! coordinates to world coordinates (v_world = q·v_body·q*). Body axes: x right of the screen in its
//! natural orientation, y up the screen, z out of the screen. The display rotation is never used.
//!
//! Neutral = the player's own grip, a mean quaternion q0. Relative rotation r = q0*·q (in the neutral
//! body frame: if the phone turned by r in its own frame, q = q0·r). Steering angle = twist of r about
//! the steering axis a: θ = 2·atan2(r_v·a, r_w), r taken with r_w ≥ 0. Positive = turning right.
//! Default axis (0, 0, −1): a clockwise wheel turn, as the player sees it, about the screen normal.
//! A full calibration measures a from the held turns: a = normalize(ρR − ρL) (rotation vectors).
use crate::motion::{wrap_deg, OneEuro};
use crate::profile::Motion;
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Vec3 { pub x: f64, pub y: f64, pub z: f64 }

// Named methods (not operator traits) keep this reference line-by-line comparable with its mirrors.
#[allow(clippy::should_implement_trait)]
impl Vec3 {
    pub const SCREEN_CLOCKWISE: Vec3 = Vec3 { x: 0.0, y: 0.0, z: -1.0 };
    pub const fn new(x: f64, y: f64, z: f64) -> Self { Vec3 { x, y, z } }
    pub fn dot(self, o: Vec3) -> f64 { self.x * o.x + self.y * o.y + self.z * o.z }
    pub fn cross(self, o: Vec3) -> Vec3 { Vec3::new(self.y * o.z - self.z * o.y, self.z * o.x - self.x * o.z, self.x * o.y - self.y * o.x) }
    pub fn add(self, o: Vec3) -> Vec3 { Vec3::new(self.x + o.x, self.y + o.y, self.z + o.z) }
    pub fn sub(self, o: Vec3) -> Vec3 { Vec3::new(self.x - o.x, self.y - o.y, self.z - o.z) }
    pub fn scale(self, k: f64) -> Vec3 { Vec3::new(self.x * k, self.y * k, self.z * k) }
    pub fn neg(self) -> Vec3 { self.scale(-1.0) }
    pub fn norm(self) -> f64 { self.dot(self).sqrt() }
    pub fn normalized(self) -> Option<Vec3> { let n = self.norm(); (n >= 1e-9 && n.is_finite()).then(|| self.scale(1.0 / n)) }
    pub fn angle_deg(self, o: Vec3) -> f64 { (self.dot(o) / (self.norm() * o.norm())).clamp(-1.0, 1.0).acos().to_degrees() }
    pub fn to_vec(self) -> Vec<f64> { vec![self.x, self.y, self.z] }
    pub fn from_slice(v: &[f64]) -> Option<Vec3> { (v.len() == 3 && v.iter().all(|x| x.is_finite())).then(|| Vec3::new(v[0], v[1], v[2])) }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Quat { pub w: f64, pub x: f64, pub y: f64, pub z: f64 }

#[allow(clippy::should_implement_trait)]
impl Quat {
    pub const IDENTITY: Quat = Quat { w: 1.0, x: 0.0, y: 0.0, z: 0.0 };
    pub const fn new(w: f64, x: f64, y: f64, z: f64) -> Self { Quat { w, x, y, z } }
    /// Hamilton product: `a.mul(b)` applies b first, then a.
    pub fn mul(self, o: Quat) -> Quat {
        Quat::new(
            self.w * o.w - self.x * o.x - self.y * o.y - self.z * o.z,
            self.w * o.x + self.x * o.w + self.y * o.z - self.z * o.y,
            self.w * o.y - self.x * o.z + self.y * o.w + self.z * o.x,
            self.w * o.z + self.x * o.y - self.y * o.x + self.z * o.w,
        )
    }
    pub fn conj(self) -> Quat { Quat::new(self.w, -self.x, -self.y, -self.z) }
    pub fn dot(self, o: Quat) -> f64 { self.w * o.w + self.x * o.x + self.y * o.y + self.z * o.z }
    pub fn vec(self) -> Vec3 { Vec3::new(self.x, self.y, self.z) }
    pub fn normalized(self) -> Option<Quat> {
        let n = self.dot(self).sqrt();
        (n >= 1e-9 && n.is_finite()).then(|| Quat::new(self.w / n, self.x / n, self.y / n, self.z / n))
    }
    /// Same rotation with w ≥ 0.
    pub fn canonical(self) -> Quat { if self.w < 0.0 { Quat::new(-self.w, -self.x, -self.y, -self.z) } else { self } }
    /// Rotation angle 0..180°.
    pub fn angle_deg(self) -> f64 { (2.0 * self.vec().norm().atan2(self.w.abs())).to_degrees() }
    /// Axis·angle (radians), angle in 0..π.
    pub fn rotation_vector(self) -> Vec3 {
        let c = self.canonical();
        let s = c.vec().norm();
        if s < 1e-12 { return Vec3::new(0.0, 0.0, 0.0); }
        c.vec().scale(2.0 * s.atan2(c.w) / s)
    }
    pub fn rotate(self, v: Vec3) -> Vec3 { self.mul(Quat::new(0.0, v.x, v.y, v.z)).mul(self.conj()).vec() }
    pub fn axis_angle(axis: Vec3, deg: f64) -> Quat {
        let Some(a) = axis.normalized() else { return Quat::IDENTITY };
        let h = deg.to_radians() / 2.0;
        Quat::new(h.cos(), a.x * h.sin(), a.y * h.sin(), a.z * h.sin())
    }
    /// Android rotation-vector values x, y, z[, w].
    pub fn from_rotation_vector(v: &[f64]) -> Option<Quat> {
        if v.len() < 3 { return None; }
        let w = if v.len() >= 4 { v[3] } else { (1.0 - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]).max(0.0).sqrt() };
        Quat::new(w, v[0], v[1], v[2]).normalized()
    }
    pub fn to_vec(self) -> Vec<f64> { vec![self.w, self.x, self.y, self.z] }
    pub fn from_slice(v: &[f64]) -> Option<Quat> {
        if v.len() != 4 || !v.iter().all(|x| x.is_finite()) { return None; }
        Quat::new(v[0], v[1], v[2], v[3]).normalized()
    }
}

/// One motion sample: a full orientation, or gravity only (fallback sensors; points down, body frame).
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Pose { Orientation(Quat), Gravity(Vec3) }

/// Twist of relative rotation `r` about unit `axis`, degrees in (−180, 180].
pub fn twist_deg(r: Quat, axis: Vec3) -> f64 {
    let c = r.canonical();
    wrap_deg((2.0 * c.vec().dot(axis).atan2(c.w)).to_degrees())
}

pub fn relative(neutral: Quat, q: Quat) -> Quat { neutral.conj().mul(q) }

/// Gravity-only steering angle about `axis`; None when gravity is (nearly) along the axis.
pub fn gravity_twist_deg(g0: Vec3, g: Vec3, axis: Vec3) -> Option<f64> {
    let p0 = g0.sub(axis.scale(g0.dot(axis)));
    let p = g.sub(axis.scale(g.dot(axis)));
    if p0.norm() < 0.25 * g0.norm() || p.norm() < 0.25 * g.norm() { return None; }
    Some(-(axis.dot(p0.cross(p)).atan2(p0.dot(p))).to_degrees())
}

/// Sign-aligned mean of quaternions.
pub fn mean(qs: &[Quat]) -> Option<Quat> {
    let r = *qs.first()?;
    let mut m = Quat::new(0.0, 0.0, 0.0, 0.0);
    for q in qs {
        let s = if q.dot(r) < 0.0 { -1.0 } else { 1.0 };
        m = Quat::new(m.w + s * q.w, m.x + s * q.x, m.y + s * q.y, m.z + s * q.z);
    }
    m.normalized()
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct HeldPose { pub pose: Pose, pub spread_deg: f64, pub samples: usize }

pub const NEUTRAL_MAX_SPREAD: f64 = 1.5;
pub const EXTREME_MAX_SPREAD: f64 = 3.0;
pub const MIN_RANGE_DEG: f64 = 10.0;
pub const MAX_AXIS_DISAGREEMENT_DEG: f64 = 40.0;
/// Recenter blend time, seconds.
pub const BLEND_S: f64 = 0.25;

fn rms(d: &[f64]) -> f64 { (d.iter().map(|x| x * x).sum::<f64>() / d.len() as f64).sqrt() }

/// Robust estimate of a held pose: mean, drop samples further than max(3σ, 1°), mean again.
pub fn capture(samples: &[Pose], min_samples: usize) -> Option<HeldPose> {
    let qs: Vec<Quat> = samples.iter().filter_map(|p| match p { Pose::Orientation(q) => Some(*q), _ => None }).collect();
    let gs: Vec<Vec3> = samples.iter().filter_map(|p| match p { Pose::Gravity(g) => Some(*g), _ => None }).collect();
    if qs.len() >= min_samples {
        let dev = |m: Quat, q: &Quat| m.conj().mul(*q).angle_deg();
        let m = mean(&qs)?;
        let lim = (3.0 * rms(&qs.iter().map(|q| dev(m, q)).collect::<Vec<_>>())).max(1.0);
        let kept: Vec<Quat> = qs.iter().copied().filter(|q| dev(m, q) <= lim).collect();
        let m = mean(&kept)?;
        let spread = rms(&kept.iter().map(|q| dev(m, q)).collect::<Vec<_>>());
        return Some(HeldPose { pose: Pose::Orientation(m), spread_deg: spread, samples: kept.len() });
    }
    if gs.len() >= min_samples {
        let mean_g = |l: &[Vec3]| l.iter().fold(Vec3::new(0.0, 0.0, 0.0), |a, b| a.add(b.normalized().unwrap_or(*b))).normalized();
        let m = mean_g(&gs)?;
        let lim = (3.0 * rms(&gs.iter().map(|g| m.angle_deg(*g)).collect::<Vec<_>>())).max(1.0);
        let kept: Vec<Vec3> = gs.iter().copied().filter(|g| m.angle_deg(*g) <= lim).collect();
        let m = mean_g(&kept)?;
        let spread = rms(&kept.iter().map(|g| m.angle_deg(*g)).collect::<Vec<_>>());
        return Some(HeldPose { pose: Pose::Gravity(m), spread_deg: spread, samples: kept.len() });
    }
    None
}

/// Steering calibration, schema 2 (field names shared with Kotlin/Swift storage).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CalibrationV2 {
    #[serde(default = "v2")] pub v: u32,
    pub kind: String,
    #[serde(default, skip_serializing_if = "Option::is_none")] pub q: Option<Vec<f64>>,
    #[serde(default, skip_serializing_if = "Option::is_none")] pub g: Option<Vec<f64>>,
    #[serde(default = "default_axis")] pub axis: Vec<f64>,
    #[serde(default)] pub axis_measured: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")] pub left_deg: Option<f64>,
    #[serde(default, skip_serializing_if = "Option::is_none")] pub right_deg: Option<f64>,
    pub noise_deg: f64,
    pub measured_at: i64,
    #[serde(default)] pub source: String,
}
fn v2() -> u32 { 2 }
fn default_axis() -> Vec<f64> { Vec3::SCREEN_CLOCKWISE.to_vec() }

impl CalibrationV2 {
    pub const VERSION: u32 = 2;
    pub fn neutral(&self) -> Option<Pose> {
        match self.kind.as_str() {
            "orientation" => Quat::from_slice(self.q.as_deref()?).map(Pose::Orientation),
            "gravity" => Vec3::from_slice(self.g.as_deref()?)?.normalized().map(Pose::Gravity),
            _ => None,
        }
    }
    pub fn axis_vec(&self) -> Option<Vec3> { Vec3::from_slice(&self.axis)?.normalized() }
    pub fn has_range(&self) -> bool { self.left_deg.is_some() && self.right_deg.is_some() }
    pub fn suggested_deadzone(&self) -> Option<f64> { Some((3.0 * self.noise_deg / self.left_deg?.min(self.right_deg?)).clamp(0.0, 0.1)) }
    /// None for another schema version or any out-of-bounds field: callers treat it as "not calibrated".
    pub fn validated(self) -> Option<Self> {
        if self.v != Self::VERSION || self.neutral().is_none() || self.axis_vec().is_none() || !self.noise_deg.is_finite() || self.noise_deg < 0.0 { return None; }
        if self.left_deg.is_some() != self.right_deg.is_some() { return None; }
        for r in [self.left_deg, self.right_deg].into_iter().flatten() {
            if !r.is_finite() || !(MIN_RANGE_DEG..=180.0).contains(&r) { return None; }
        }
        Some(self)
    }
    pub fn decode(s: &str) -> Option<Self> { serde_json::from_str::<Self>(s).ok()?.validated() }
    pub fn neutral_only(h: &HeldPose, at: i64, source: &str) -> Self {
        let (kind, q, g) = match h.pose {
            Pose::Orientation(q) => ("orientation", Some(q.to_vec()), None),
            Pose::Gravity(g) => ("gravity", None, Some(g.to_vec())),
        };
        CalibrationV2 { v: 2, kind: kind.into(), q, g, axis: default_axis(), axis_measured: false, left_deg: None, right_deg: None,
            noise_deg: h.spread_deg, measured_at: at, source: source.into() }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum RangeResult { Ok { cal: CalibrationV2, axis_to_screen_normal_deg: f64 }, Retry(&'static str) }

/// Measure the steering axis and both ranges from the held full-left and full-right poses.
pub fn range(neutral: &CalibrationV2, left: &HeldPose, right: &HeldPose) -> RangeResult {
    if left.spread_deg > EXTREME_MAX_SPREAD || right.spread_deg > EXTREME_MAX_SPREAD { return RangeResult::Retry("moved while holding a full turn"); }
    match (neutral.neutral(), left.pose, right.pose) {
        (Some(Pose::Orientation(n)), Pose::Orientation(l), Pose::Orientation(r)) => {
            let rl = relative(n, l).rotation_vector();
            let rr = relative(n, r).rotation_vector();
            if rl.norm().to_degrees() < MIN_RANGE_DEG || rr.norm().to_degrees() < MIN_RANGE_DEG { return RangeResult::Retry("turn too small"); }
            if rr.angle_deg(rl.neg()) > MAX_AXIS_DISAGREEMENT_DEG { return RangeResult::Retry("turns about different axes"); }
            let Some(axis) = rr.sub(rl).normalized() else { return RangeResult::Retry("no axis") };
            let right_deg = twist_deg(relative(n, r), axis);
            let left_deg = -twist_deg(relative(n, l), axis);
            if right_deg < MIN_RANGE_DEG || left_deg < MIN_RANGE_DEG { return RangeResult::Retry("turn too small"); }
            let mut cal = neutral.clone();
            cal.axis = axis.to_vec(); cal.axis_measured = true; cal.left_deg = Some(left_deg); cal.right_deg = Some(right_deg);
            RangeResult::Ok { cal, axis_to_screen_normal_deg: axis.angle_deg(Vec3::SCREEN_CLOCKWISE) }
        }
        (Some(Pose::Gravity(n)), Pose::Gravity(l), Pose::Gravity(r)) => {
            let a = Vec3::SCREEN_CLOCKWISE;
            let (Some(rd), Some(ld)) = (gravity_twist_deg(n, r, a), gravity_twist_deg(n, l, a).map(|x| -x)) else { return RangeResult::Retry("turn invisible to gravity") };
            if rd < MIN_RANGE_DEG || ld < MIN_RANGE_DEG { return RangeResult::Retry("turn too small"); }
            let mut cal = neutral.clone();
            cal.left_deg = Some(ld); cal.right_deg = Some(rd);
            RangeResult::Ok { cal, axis_to_screen_normal_deg: 0.0 }
        }
        _ => RangeResult::Retry("sensor kind changed"),
    }
}

/// New neutral keeping the measured axis and ranges; flags a grip change over 30° for re-measuring.
pub fn recenter(old: Option<&CalibrationV2>, h: &HeldPose, at: i64, source: &str) -> (CalibrationV2, bool) {
    let fresh = CalibrationV2::neutral_only(h, at, source);
    let Some(old) = old.filter(|o| o.kind == fresh.kind) else { return (fresh, old.is_some_and(CalibrationV2::has_range)) };
    let moved = match (old.neutral(), h.pose) {
        (Some(Pose::Orientation(a)), Pose::Orientation(b)) => a.conj().mul(b).angle_deg(),
        (Some(Pose::Gravity(a)), Pose::Gravity(b)) => a.angle_deg(b),
        _ => 0.0,
    };
    let mut c = old.clone();
    c.q = fresh.q; c.g = fresh.g; c.noise_deg = h.spread_deg; c.measured_at = at; c.source = source.into();
    (c, old.has_range() && moved > 30.0)
}

/// Poses → steering in [−1, 1] relative to a calibration. Angle → per-side range → sensitivity →
/// invert → clamp → dead zone → curve; One Euro filter on the angle; recenter blends over BLEND_S.
#[derive(Clone, Debug)]
pub struct RelativeSteering {
    cal: Option<CalibrationV2>,
    motion: Motion,
    filter: OneEuro,
    unwrapped: Option<f64>,
    last_out: f64,
    blend_from: f64,
    blend_start: Option<f64>,
    pub angle_deg: Option<f64>,
}

impl RelativeSteering {
    pub fn new(cal: Option<CalibrationV2>, motion: Motion) -> Self {
        RelativeSteering { filter: OneEuro::new(motion.min_cutoff, motion.beta), cal, motion, unwrapped: None, last_out: 0.0, blend_from: 0.0, blend_start: None, angle_deg: None }
    }
    pub fn calibration(&self) -> Option<&CalibrationV2> { self.cal.as_ref() }

    pub fn measure(&self, p: Pose) -> Option<f64> {
        let c = self.cal.as_ref()?;
        let axis = c.axis_vec()?;
        match (c.neutral()?, p) {
            (Pose::Orientation(n), Pose::Orientation(q)) => Some(twist_deg(relative(n, q), axis)),
            (Pose::Gravity(n), Pose::Gravity(g)) => gravity_twist_deg(n, g, axis),
            _ => None,
        }
    }

    pub fn normalize(&self, deg: f64) -> f64 {
        let m = &self.motion;
        let side = if deg >= 0.0 { self.cal.as_ref().and_then(|c| c.right_deg) } else { self.cal.as_ref().and_then(|c| c.left_deg) };
        let mut x = deg / side.unwrap_or(m.lock_deg) * m.sensitivity;
        if m.invert { x = -x; }
        let x = x.clamp(-1.0, 1.0);
        let a = x.abs();
        if a <= m.deadzone { return 0.0; }
        ((a - m.deadzone) / (1.0 - m.deadzone)).powf(m.curve).copysign(x)
    }

    pub fn update(&mut self, p: Pose, t: f64) -> f64 {
        let target = match self.measure(p) {
            None => { self.unwrapped = None; self.filter.reset(); self.angle_deg = None; 0.0 }
            Some(raw) => {
                // Unwrap across ±180° so a turn past the end of the range stays saturated instead of flipping.
                let u = match self.unwrapped { Some(prev) => { let u = prev + wrap_deg(raw - prev); if u.abs() > 270.0 { raw } else { u } } None => raw };
                self.unwrapped = Some(u);
                let f = self.filter.filter(u, t);
                self.angle_deg = Some(f);
                self.normalize(f)
            }
        };
        let out = match self.blend_start {
            None => target,
            Some(t0) => {
                let k = ((t - t0) / BLEND_S).clamp(0.0, 1.0);
                if k >= 1.0 { self.blend_start = None; }
                target + (self.blend_from - target) * (1.0 - k)
            }
        };
        self.last_out = out.clamp(-1.0, 1.0);
        self.last_out
    }

    /// Switch to a new calibration (recenter) at time `t` without a step in the output.
    pub fn recalibrate(&mut self, cal: Option<CalibrationV2>, t: f64) {
        self.blend_from = self.last_out; self.blend_start = Some(t);
        self.cal = cal; self.unwrapped = None; self.filter.reset();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    pub(crate) fn grip() -> Quat {
        let (x, y, z) = (Vec3::new(1.0, 0.0, 0.0), Vec3::new(0.0, 1.0, 0.0), Vec3::new(0.0, 0.0, 1.0));
        Quat::axis_angle(z, 20.0).mul(Quat::axis_angle(x, 55.0)).mul(Quat::axis_angle(y, 90.0)).mul(Quat::axis_angle(z, 4.0))
    }
    fn turned(n: Quat, deg: f64) -> Quat { n.mul(Quat::axis_angle(Vec3::SCREEN_CLOCKWISE, deg)) }
    fn held(q: Quat) -> HeldPose { HeldPose { pose: Pose::Orientation(q), spread_deg: 0.3, samples: 50 } }
    fn exact() -> Motion { Motion { enabled: true, lock_deg: 45.0, deadzone: 0.0, min_cutoff: 1e9, beta: 0.0, ..Motion::default() } }

    #[test]
    fn tilted_neutral_is_zero_and_turns_are_signed() {
        let mut s = RelativeSteering::new(Some(CalibrationV2::neutral_only(&held(grip()), 0, "t")), exact());
        assert!(s.update(Pose::Orientation(grip()), 0.0).abs() < 1e-9);
        assert!((s.update(Pose::Orientation(turned(grip(), 20.0)), 0.01) - 20.0 / 45.0).abs() < 1e-6);
        assert!((s.update(Pose::Orientation(turned(grip(), -20.0)), 0.02) + 20.0 / 45.0).abs() < 1e-6);
    }

    #[test]
    fn unequal_ranges_and_measured_axis() {
        let RangeResult::Ok { cal, .. } = range(&CalibrationV2::neutral_only(&held(grip()), 0, "t"), &held(turned(grip(), -25.0)), &held(turned(grip(), 40.0))) else { panic!("range refused") };
        assert!((cal.left_deg.unwrap() - 25.0).abs() < 1e-6 && (cal.right_deg.unwrap() - 40.0).abs() < 1e-6);
        let mut s = RelativeSteering::new(Some(cal), exact());
        assert!((s.update(Pose::Orientation(turned(grip(), 20.0)), 0.0) - 0.5).abs() < 1e-6);
        assert!((s.update(Pose::Orientation(turned(grip(), -12.5)), 0.01) + 0.5).abs() < 1e-6);
    }

    #[test]
    fn recenter_blends_without_a_step_and_wrapping_never_flips() {
        let mut s = RelativeSteering::new(Some(CalibrationV2::neutral_only(&held(grip()), 0, "t")), exact());
        let ng = turned(grip(), 18.0);
        let mut t = 0.0;
        for _ in 0..5 { s.update(Pose::Orientation(ng), t); t += 0.005; }
        let (c, flag) = recenter(s.calibration(), &held(ng), 1, "t");
        assert!(!flag);
        s.recalibrate(Some(c), t);
        let (mut prev, mut max_step) = (0.4, 0.0f64);
        for _ in 0..80 { let v = s.update(Pose::Orientation(ng), t); t += 0.005; max_step = max_step.max((v - prev).abs()); prev = v; }
        assert!(prev.abs() < 1e-9 && max_step < 0.03, "end {prev} step {max_step}");
        let mut s = RelativeSteering::new(Some(CalibrationV2::neutral_only(&held(grip()), 0, "t")), Motion { lock_deg: 90.0, ..exact() });
        let mut prev = 0.0;
        for d in (0..=200).step_by(2) { let v = s.update(Pose::Orientation(turned(grip(), d as f64)), d as f64 * 0.001); assert!(v >= prev - 1e-9, "flip at {d}"); prev = v; }
    }

    #[test]
    fn invalid_calibration_is_refused() {
        let good = serde_json::to_string(&CalibrationV2::neutral_only(&held(grip()), 0, "t")).unwrap();
        assert!(good.contains("\"v\":2") && CalibrationV2::decode(&good).is_some());
        assert!(CalibrationV2::decode(&good.replace("\"v\":2", "\"v\":1")).is_none());
        assert!(CalibrationV2::decode(r#"{"v":2,"kind":"orientation","q":[0,0,0,0],"noiseDeg":0.1,"measuredAt":1}"#).is_none());
        assert!(CalibrationV2::decode(r#"{"v":2,"kind":"orientation","q":[1,0,0,0],"leftDeg":3,"rightDeg":30,"noiseDeg":0.1,"measuredAt":1}"#).is_none());
        assert_eq!(RelativeSteering::new(None, exact()).update(Pose::Orientation(grip()), 0.0), 0.0);
    }
}
