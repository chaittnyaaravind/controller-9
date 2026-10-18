//! Controller profile format v1 (see `protocol/PROFILE.md`, `protocol/schema/profile.v1.schema.json`).
use crate::input::Button;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

pub const SCHEMA: u32 = 1;
pub const MAX_CONTROLS: usize = 48;

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Orientation { Landscape, Portrait }

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ControlKind { Button, Stick, Dpad, Trigger, Pedal, Steering }

fn one() -> f64 { 1.0 }
fn yes() -> bool { true }

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub struct ControlDef {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: ControlKind,
    pub bind: String,
    pub x: f64, pub y: f64, pub w: f64, pub h: f64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    #[serde(default = "one")]
    pub opacity: f64,
    #[serde(default)]
    pub toggle: bool,
    #[serde(default = "yes")]
    pub haptic: bool,
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Motion {
    pub enabled: bool,
    /// "lx" or "rx"
    #[serde(with = "axis_name")]
    pub axis: StickAxis,
    /// Degrees from center for full deflection (steering lock).
    pub lock_deg: f64,
    /// Normalized dead zone 0..0.5.
    pub deadzone: f64,
    pub sensitivity: f64,
    /// Response-curve exponent: 1 linear, >1 finer near center.
    pub curve: f64,
    pub invert: bool,
    /// One Euro filter parameters.
    pub min_cutoff: f64,
    pub beta: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StickAxis { Lx, Rx }

mod axis_name {
    use super::StickAxis;
    use serde::{Deserialize, Deserializer, Serializer};
    pub fn serialize<S: Serializer>(a: &StickAxis, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(match a { StickAxis::Lx => "lx", StickAxis::Rx => "rx" })
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<StickAxis, D::Error> {
        match String::deserialize(d)?.as_str() {
            "lx" => Ok(StickAxis::Lx),
            "rx" => Ok(StickAxis::Rx),
            o => Err(serde::de::Error::custom(format!("unknown axis {o}"))),
        }
    }
}

impl Default for Motion {
    fn default() -> Self {
        Motion { enabled: false, axis: StickAxis::Lx, lock_deg: 45.0, deadzone: 0.02, sensitivity: 1.0, curve: 1.0, invert: false, min_cutoff: 1.5, beta: 0.03 }
    }
}

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Sticks { pub deadzone: f64 }
impl Default for Sticks { fn default() -> Self { Sticks { deadzone: 0.08 } } }

#[derive(Serialize, Deserialize, Debug, Clone, Copy, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Haptics { pub enabled: bool, pub strength: f64 }
impl Default for Haptics { fn default() -> Self { Haptics { enabled: true, strength: 0.7 } } }

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Profile {
    pub schema: u32,
    pub id: String,
    pub name: String,
    pub orientation: Orientation,
    pub controls: Vec<ControlDef>,
    #[serde(default)]
    pub motion: Motion,
    #[serde(default)]
    pub sticks: Sticks,
    #[serde(default)]
    pub haptics: Haptics,
}

#[derive(Debug, thiserror::Error, PartialEq)]
pub enum ProfileError {
    #[error("invalid JSON: {0}")] Json(String),
    #[error("profile schema {0} is newer than this app supports")] TooNew(u64),
    #[error("{0}")] Invalid(String),
}

fn invalid<T>(m: impl Into<String>) -> Result<T, ProfileError> { Err(ProfileError::Invalid(m.into())) }

fn in_range(name: &str, v: f64, lo: f64, hi: f64) -> Result<(), ProfileError> {
    if v.is_finite() && v >= lo && v <= hi { Ok(()) } else { invalid(format!("{name} must be in {lo}..={hi}")) }
}

impl Profile {
    pub fn validate(&self) -> Result<(), ProfileError> {
        if self.schema != SCHEMA { return invalid("schema must be 1"); }
        let id_ok = !self.id.is_empty() && self.id.len() <= 64
            && self.id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'_');
        if !id_ok { return invalid("id must be 1-64 chars of a-z 0-9 - _"); }
        if self.name.trim().is_empty() || self.name.chars().count() > 48 { return invalid("name must be 1-48 characters"); }
        if self.controls.len() > MAX_CONTROLS { return invalid(format!("at most {MAX_CONTROLS} controls")); }
        let mut seen = std::collections::HashSet::new();
        for c in &self.controls {
            if c.id.is_empty() || c.id.len() > 32 || !seen.insert(c.id.as_str()) { return invalid(format!("control id '{}' empty, too long or duplicated", c.id)); }
            for (n, v) in [("x", c.x), ("y", c.y)] { in_range(&format!("{}.{n}", c.id), v, 0.0, 1.0)?; }
            for (n, v) in [("w", c.w), ("h", c.h)] { in_range(&format!("{}.{n}", c.id), v, 0.03, 1.0)?; }
            if c.x + c.w > 1.0 + 1e-6 || c.y + c.h > 1.0 + 1e-6 { return invalid(format!("{} extends past the screen edge", c.id)); }
            in_range(&format!("{}.opacity", c.id), c.opacity, 0.1, 1.0)?;
            if c.label.as_ref().is_some_and(|l| l.chars().count() > 12) { return invalid(format!("{} label longer than 12", c.id)); }
            let bind_ok = match c.kind {
                ControlKind::Button => Button::from_name(&c.bind).is_some(),
                ControlKind::Stick => c.bind == "left" || c.bind == "right",
                ControlKind::Dpad => c.bind == "dpad",
                ControlKind::Trigger | ControlKind::Pedal => c.bind == "lt" || c.bind == "rt",
                ControlKind::Steering => c.bind == "motion",
            };
            if !bind_ok { return invalid(format!("{} has invalid bind '{}' for its type", c.id, c.bind)); }
        }
        let m = &self.motion;
        in_range("motion.lockDeg", m.lock_deg, 10.0, 180.0)?;
        in_range("motion.deadzone", m.deadzone, 0.0, 0.5)?;
        in_range("motion.sensitivity", m.sensitivity, 0.25, 4.0)?;
        in_range("motion.curve", m.curve, 0.5, 3.0)?;
        in_range("motion.minCutoff", m.min_cutoff, 0.05, 20.0)?;
        in_range("motion.beta", m.beta, 0.0, 1.0)?;
        in_range("sticks.deadzone", self.sticks.deadzone, 0.0, 0.5)?;
        in_range("haptics.strength", self.haptics.strength, 0.0, 1.0)?;
        Ok(())
    }

    /// Parse any supported schema version, migrate to v1, and validate.
    pub fn from_json(s: &str) -> Result<Profile, ProfileError> {
        let v: Value = serde_json::from_str(s).map_err(|e| ProfileError::Json(e.to_string()))?;
        let schema = v.get("schema").or_else(|| v.get("version")).and_then(Value::as_u64).unwrap_or(0);
        let v = match schema {
            0 => migrate_v0(&v)?,
            1 => v,
            n => return Err(ProfileError::TooNew(n)),
        };
        let p: Profile = serde_json::from_value(v).map_err(|e| ProfileError::Json(e.to_string()))?;
        p.validate()?;
        Ok(p)
    }

    pub fn to_json_pretty(&self) -> String { serde_json::to_string_pretty(self).expect("profile serializes") }
}

/// v0 = the pre-release draft format: `{"version":0,"name",layout:[{kind,action,rect:[x,y,w,h]}],steer:{enabled,range,deadzone}}`
/// where `steer.range` was lock-to-lock degrees and `kind` used "pad" for D-pad and "slider" for triggers.
pub fn migrate_v0(v: &Value) -> Result<Value, ProfileError> {
    let name = v.get("name").and_then(Value::as_str).unwrap_or("Imported").to_string();
    let id: String = name.to_lowercase().chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '-' }).take(64).collect();
    let mut controls = Vec::new();
    for (i, c) in v.get("layout").and_then(Value::as_array).into_iter().flatten().enumerate() {
        let kind = match c.get("kind").and_then(Value::as_str).unwrap_or("") {
            "pad" => "dpad", "slider" => "trigger", k @ ("button" | "stick" | "pedal") => k,
            other => return invalid(format!("v0 control kind '{other}' cannot be migrated")),
        };
        let r = c.get("rect").and_then(Value::as_array).filter(|r| r.len() == 4)
            .ok_or_else(|| ProfileError::Invalid(format!("v0 control {i} has no rect")))?;
        let f = |j: usize| r[j].as_f64().unwrap_or(0.0);
        let bind = c.get("action").and_then(Value::as_str).unwrap_or(if kind == "dpad" { "dpad" } else { "" });
        controls.push(json!({"id": format!("c{i}"), "type": kind, "bind": bind, "x": f(0), "y": f(1), "w": f(2), "h": f(3)}));
    }
    let steer = v.get("steer");
    let range = steer.and_then(|s| s.get("range")).and_then(Value::as_f64).unwrap_or(90.0);
    Ok(json!({
        "schema": 1, "id": if id.is_empty() { "imported".into() } else { id }, "name": name, "orientation": "landscape",
        "controls": controls,
        "motion": {
            "enabled": steer.and_then(|s| s.get("enabled")).and_then(Value::as_bool).unwrap_or(false),
            "lockDeg": range / 2.0,
            "deadzone": steer.and_then(|s| s.get("deadzone")).and_then(Value::as_f64).unwrap_or(0.02),
        }
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    const PRESETS: [&str; 3] = [
        include_str!("../../profiles/racing.json"),
        include_str!("../../profiles/classic.json"),
        include_str!("../../profiles/arcade.json"),
    ];

    #[test]
    fn presets_validate_and_roundtrip() {
        for s in PRESETS {
            let p = Profile::from_json(s).unwrap_or_else(|e| panic!("{e}"));
            assert_eq!(Profile::from_json(&p.to_json_pretty()).unwrap(), p);
        }
    }

    #[test]
    fn rejects_bad_profiles() {
        let good: Value = serde_json::from_str(PRESETS[0]).unwrap();
        let cases: [(&str, Value); 6] = [
            ("/controls/0/x", json!(0.99)),
            ("/controls/0/bind", json!("launch")),
            ("/motion/lockDeg", json!(1)),
            ("/id", json!("Has Spaces")),
            ("/controls/1/id", good["controls"][0]["id"].clone()),
            ("/motion/curve", json!(f64::MAX)),
        ];
        for (ptr, val) in cases {
            let mut v = good.clone();
            *v.pointer_mut(ptr).unwrap() = val;
            assert!(Profile::from_json(&v.to_string()).is_err(), "{ptr} accepted");
        }
        assert_eq!(Profile::from_json(r#"{"schema":9}"#), Err(ProfileError::TooNew(9)));
    }

    #[test]
    fn migrates_v0() {
        let v0 = r#"{"version":0,"name":"Old Kart","layout":[{"kind":"pad","rect":[0.05,0.5,0.25,0.4]},{"kind":"button","action":"a","rect":[0.8,0.6,0.12,0.2]},{"kind":"slider","action":"rt","rect":[0.9,0.1,0.08,0.4]}],"steer":{"enabled":true,"range":120}}"#;
        let p = Profile::from_json(v0).unwrap();
        assert_eq!(p.id, "old-kart");
        assert_eq!(p.controls.len(), 3);
        assert_eq!(p.controls[0].kind, ControlKind::Dpad);
        assert!(p.motion.enabled && p.motion.lock_deg == 60.0);
    }
}
