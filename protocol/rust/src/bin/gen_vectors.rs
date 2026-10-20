//! Writes `protocol/vectors/*.json`. Kotlin and Swift test suites must reproduce these exactly.
//! Run: cargo run -p nine-proto --bin gen-vectors
use nine_proto::frame::{self, Feedback, Header, InputFrame, PacketType, Ping, Pong};
use nine_proto::input::InputState;
use nine_proto::motion::{shape, Steering};
use nine_proto::noise::{Handshake, KeyPair, Role};
use nine_proto::pairing;
use nine_proto::profile::{Motion, Profile, StickAxis};
use nine_proto::steering::{self as st, CalibrationV2, HeldPose, Pose, Quat, RangeResult, RelativeSteering, Vec3};
use serde_json::{json, Value};

fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02x}")).collect() }
fn seq<const N: usize>(start: u8) -> [u8; N] { std::array::from_fn(|i| start.wrapping_add(i as u8)) }

fn noise() -> Value {
    let (si, ei, sr, er) = (seq::<32>(0x01), seq::<32>(0x21), seq::<32>(0x41), seq::<32>(0x61));
    let mut i = Handshake::new(Role::Initiator, KeyPair::from_secret(si)).with_fixed_ephemeral(ei);
    let mut r = Handshake::new(Role::Responder, KeyPair::from_secret(sr)).with_fixed_ephemeral(er);
    let p1 = br#"{"proto":[1]}"#.to_vec();
    let p2 = br#"{"proto":1,"name":"Vector Host","id":"0000000000000000","os":"linux"}"#.to_vec();
    let p3 = br#"{"proto":1,"name":"Vector Phone","platform":"android","app":"1.0.0"}"#.to_vec();
    let m1 = i.write_message(&p1).unwrap();
    r.read_message(&m1).unwrap();
    let m2 = r.write_message(&p2).unwrap();
    i.read_message(&m2).unwrap();
    let m3 = i.write_message(&p3).unwrap();
    r.read_message(&m3).unwrap();
    let (mut ti, mut tr) = (i.into_transport().unwrap(), r.into_transport().unwrap());
    let t1 = ti.encrypt(br#"{"t":"open"}"#).unwrap();
    let t2 = tr.encrypt(br#"{"t":"pair_required","modes":["qr","code"]}"#).unwrap();
    let t3 = ti.encrypt(br#"{"t":"pair_code"}"#).unwrap();
    json!({
        "protocol": "Noise_XX_25519_ChaChaPoly_SHA256",
        "prologue": hex(b"9ctl/1"),
        "init_static": hex(&si), "init_ephemeral": hex(&ei),
        "resp_static": hex(&sr), "resp_ephemeral": hex(&er),
        "init_static_pub": hex(&KeyPair::from_secret(si).public),
        "resp_static_pub": hex(&KeyPair::from_secret(sr).public),
        "messages": [
            {"payload": hex(&p1), "ciphertext": hex(&m1)},
            {"payload": hex(&p2), "ciphertext": hex(&m2)},
            {"payload": hex(&p3), "ciphertext": hex(&m3)}
        ],
        "handshake_hash": hex(&ti.handshake_hash),
        "transport": [
            {"from": "initiator", "payload": hex(br#"{"t":"open"}"#), "ciphertext": hex(&t1)},
            {"from": "responder", "payload": hex(br#"{"t":"pair_required","modes":["qr","code"]}"#), "ciphertext": hex(&t2)},
            {"from": "initiator", "payload": hex(br#"{"t":"pair_code"}"#), "ciphertext": hex(&t3)}
        ]
    })
}

fn frames() -> Value {
    let key = seq::<32>(0x80);
    let input = InputFrame { t_us: 1_234_567_890, state: InputState { buttons: 0b0010_0000_0000_0101, lx: -32767, ly: 12000, rx: 32767, ry: -1, lt: 0, rt: 65535 }, flags: 1 };
    let cases = vec![
        ("input", PacketType::Input, 3, input.encode().to_vec(), json!({"t_us": 1_234_567_890u64, "buttons": 0b0010_0000_0000_0101, "lx": -32767, "ly": 12000, "rx": 32767, "ry": -1, "lt": 0, "rt": 65535, "flags": 1})),
        ("ping", PacketType::Ping, 4, Ping { t_us: 99, id: 7 }.encode().to_vec(), json!({"t_us": 99, "id": 7})),
        ("pong", PacketType::Pong, 5, Pong { t_us: 99, id: 7, host_rx_us: 1000, host_tx_us: 1010 }.encode().to_vec(), json!({"t_us": 99, "id": 7, "host_rx_us": 1000, "host_tx_us": 1010})),
        ("feedback", PacketType::Feedback, 6, Feedback { low: 1000, high: 65535, ms: 120 }.encode().to_vec(), json!({"low": 1000, "high": 65535, "ms": 120})),
        ("bye", PacketType::Bye, 7, vec![], json!({})),
    ];
    let valid: Vec<Value> = cases.into_iter().map(|(name, ptype, counter, payload, fields)| {
        let d = frame::seal(&key, Header { ptype, session_id: 0x0A0B0C0D, counter }, &payload);
        json!({"name": name, "type": ptype as u8, "session_id": 0x0A0B0C0Du32, "counter": counter, "payload": hex(&payload), "fields": fields, "datagram": hex(&d)})
    }).collect();
    let good = frame::seal(&key, Header { ptype: PacketType::Input, session_id: 1, counter: 1 }, &input.encode());
    let mut flipped = good.clone(); flipped[20] ^= 0x40;
    let mut bad_magic = good.clone(); bad_magic[0] = b'X';
    let mut bad_ver = good.clone(); bad_ver[2] = 2;
    let mut bad_type = good.clone(); bad_type[3] = 0x10;
    let mut bad_ctr = good.clone(); bad_ctr[8] ^= 1; // header is AAD
    let mut oob = input; oob.state.lx = i16::MIN;
    let oob_d = frame::seal(&key, Header { ptype: PacketType::Input, session_id: 1, counter: 2 }, &oob.encode());
    let short_payload = frame::seal(&key, Header { ptype: PacketType::Input, session_id: 1, counter: 3 }, &[0u8; 25]);
    let invalid = json!([
        {"name": "too_short", "datagram": hex(&good[..31]), "error": "too_short"},
        {"name": "ciphertext_bit_flip", "datagram": hex(&flipped), "error": "auth"},
        {"name": "bad_magic", "datagram": hex(&bad_magic), "error": "bad_magic"},
        {"name": "bad_version", "datagram": hex(&bad_ver), "error": "bad_version"},
        {"name": "unknown_type", "datagram": hex(&bad_type), "error": "unknown_type"},
        {"name": "header_tamper", "datagram": hex(&bad_ctr), "error": "auth"},
        {"name": "axis_out_of_range", "datagram": hex(&oob_d), "error": "out_of_range"},
        {"name": "payload_length", "datagram": hex(&short_payload), "error": "bad_length"}
    ]);
    json!({"key": hex(&key), "valid": valid, "invalid": invalid,
           "freshness": {"counters": [1, 2, 2, 5, 4, 6], "accepted": [true, true, false, true, false, true], "lost": 2, "stale": 2}})
}

fn pairing_v() -> Value {
    let h = seq::<32>(0xA0);
    let (na, nb, token) = (seq::<16>(0x10), seq::<16>(0x30), seq::<16>(0x50));
    let code = pairing::sas_code(&h, &na, &nb);
    let uri = pairing::PairUri { addr: "192.168.1.20".into(), port: 47901, host_static: seq::<32>(0xC0), token, name: "Studio PC".into(), extra: vec![] };
    let multi = pairing::PairUri { extra: vec!["172.20.10.2".into(), "192.168.42.10".into()], ..uri.clone() };
    json!({
        "handshake_hash": hex(&h), "na": hex(&na), "nb": hex(&nb), "token": hex(&token),
        "commitment": hex(&pairing::commitment(&h, &nb)),
        "sas": code, "sas_display": pairing::format_code(code),
        "qr_proof": hex(&pairing::qr_proof(&token, &h)),
        "uri": uri.to_uri(), "uri_host_static": hex(&uri.host_static),
        "host_id_of_uri_key": nine_proto::host_id(&uri.host_static),
        "uri_multi": multi.to_uri(), "uri_multi_extra": multi.extra
    })
}

fn motion_v() -> Value {
    let m = Motion { enabled: true, axis: StickAxis::Lx, lock_deg: 40.0, deadzone: 0.05, sensitivity: 1.2, curve: 1.5, invert: false, min_cutoff: 1.5, beta: 0.03 };
    let shape_cases: Vec<Value> = [-90.0, -40.0, -20.0, -1.0, 0.0, 1.5, 10.0, 25.0, 33.3, 60.0]
        .iter().map(|d| json!({"delta_deg": d, "out": shape(*d, &m)})).collect();
    let mut s = Steering::new(m);
    s.set_center(-3.0);
    let mut samples = Vec::new();
    for i in 0..60 {
        let t = i as f64 / 120.0;
        let ang: f64 = -3.0 + 35.0 * (t * 4.0).sin() + if i % 7 == 0 { 1.5 } else { 0.0 };
        let (gx, gy, gz) = if i == 30 { (0.3, -0.2, -9.7) } else { (9.81 * ang.to_radians().sin(), -9.81 * ang.to_radians().cos(), 0.4) };
        let out = s.update(gx, gy, gz, t);
        samples.push(json!({"t": t, "g": [gx, gy, gz], "out": out}));
    }
    json!({"params": {"lockDeg": 40.0, "deadzone": 0.05, "sensitivity": 1.2, "curve": 1.5, "invert": false, "minCutoff": 1.5, "beta": 0.03},
           "center_deg": -3.0, "shape": shape_cases, "sequence": samples, "tolerance": 1e-9})
}

fn profiles_v() -> Value {
    let v0 = r#"{"version":0,"name":"Old Kart","layout":[{"kind":"pad","rect":[0.05,0.5,0.25,0.4]},{"kind":"button","action":"a","rect":[0.8,0.6,0.12,0.2]},{"kind":"slider","action":"rt","rect":[0.9,0.1,0.08,0.4]}],"steer":{"enabled":true,"range":120}}"#;
    let migrated = Profile::from_json(v0).unwrap();
    json!({
        "migration": [{"input": v0, "expected": serde_json::to_value(&migrated).unwrap()}],
        "invalid": [
            {"why": "control past right edge", "json": r#"{"schema":1,"id":"x","name":"X","orientation":"landscape","controls":[{"id":"a","type":"button","bind":"a","x":0.99,"y":0,"w":0.1,"h":0.1}]}"#},
            {"why": "bad bind", "json": r#"{"schema":1,"id":"x","name":"X","orientation":"landscape","controls":[{"id":"a","type":"stick","bind":"a","x":0,"y":0,"w":0.1,"h":0.1}]}"#},
            {"why": "duplicate id", "json": r#"{"schema":1,"id":"x","name":"X","orientation":"landscape","controls":[{"id":"a","type":"button","bind":"a","x":0,"y":0,"w":0.1,"h":0.1},{"id":"a","type":"button","bind":"b","x":0.5,"y":0,"w":0.1,"h":0.1}]}"#},
            {"why": "newer schema", "json": r#"{"schema":2,"id":"x","name":"X","orientation":"landscape","controls":[]}"#},
            {"why": "lock too small", "json": r#"{"schema":1,"id":"x","name":"X","orientation":"landscape","controls":[],"motion":{"lockDeg":2}}"#}
        ]
    })
}

fn link_v() -> Value {
    use nine_proto::link::{Record, Segmenter};
    let control: Vec<u8> = (0..70u8).collect();
    let dgram: Vec<u8> = (100..158u8).collect();
    let recs = [Record::Control(control.clone()), Record::Datagram(dgram.clone())];
    let stream: Vec<u8> = recs.iter().flat_map(Record::encode).collect();
    let mut seg = Segmenter::default();
    let s23 = seg.segment(&control, 23);
    let s62 = seg.segment(&dgram, 62);
    json!({
        "control": hex(&control), "datagram": hex(&dgram), "stream": hex(&stream),
        "segments_mtu23": s23.iter().map(|v| hex(v)).collect::<Vec<_>>(),
        "segments_mtu62_after": s62.iter().map(|v| hex(v)).collect::<Vec<_>>(),
        "uuids": {"service": nine_proto::link::BLE_SERVICE, "control_up": nine_proto::link::BLE_CONTROL_UP, "control_down": nine_proto::link::BLE_CONTROL_DOWN,
                  "input_up": nine_proto::link::BLE_INPUT_UP, "input_down": nine_proto::link::BLE_INPUT_DOWN}
    })
}

fn policy_v() -> Value {
    use nine_proto::policy::{decide, rank, score, LinkKind as K, LinkStats, PolicyState};
    let l = |kind, rtt: f64| LinkStats { kind, available: true, rtt_p95_ms: Some(rtt), ..Default::default() };
    let mut lossy = l(K::Lan, 60.0); lossy.loss_pct = 6.0; lossy.disconnects_per_min = 1.0;
    let mut flaky = l(K::Hotspot, 15.0); flaky.attempts = 4; flaky.successes = 1; flaky.last_failure_age_s = Some(3.0);
    let cases: Vec<(Vec<LinkStats>, PolicyState)> = vec![
        (vec![l(K::Ble, 30.0), l(K::Lan, 8.0), l(K::Usb, 2.0)], PolicyState::default()),
        (vec![lossy.clone(), l(K::Ble, 25.0)], PolicyState { current: Some(K::Lan), current_healthy: true, since_switch_s: 60.0, better_for_s: 5.0, override_kind: None }),
        (vec![l(K::Lan, 10.0), l(K::Usb, 2.0)], PolicyState { current: Some(K::Lan), current_healthy: true, since_switch_s: 60.0, better_for_s: 5.0, override_kind: None }),
        (vec![l(K::Ble, 30.0), l(K::Usb, 2.0)], PolicyState { current: Some(K::Ble), current_healthy: true, since_switch_s: 5.0, better_for_s: 5.0, override_kind: None }),
        (vec![l(K::Ble, 30.0), l(K::Usb, 2.0)], PolicyState { current: Some(K::Ble), current_healthy: true, since_switch_s: 12.0, better_for_s: 5.0, override_kind: None }),
        (vec![l(K::Lan, 5.0), l(K::Ble, 30.0), flaky.clone()], PolicyState { current: Some(K::Lan), current_healthy: false, ..Default::default() }),
        (vec![LinkStats { kind: K::Lan, available: false, ..Default::default() }], PolicyState { current: Some(K::Lan), current_healthy: false, ..Default::default() }),
        (vec![l(K::Lan, 5.0), l(K::Ble, 30.0)], PolicyState { current: Some(K::Lan), current_healthy: true, override_kind: Some(K::Ble), ..Default::default() }),
        (vec![l(K::Lan, 5.0), l(K::Ble, 30.0)], PolicyState { current: None, current_healthy: false, override_kind: Some(K::Usb), ..Default::default() }),
        // observed on a real phone after a Wi-Fi outage: both failed; unmeasured stream must not win
        ({ let mut lan = l(K::Lan, 60.0); lan.last_failure_age_s = Some(2.0); vec![lan, LinkStats { kind: K::Stream, available: true, last_failure_age_s: Some(2.0), ..Default::default() }] },
            PolicyState { current: Some(K::Lan), current_healthy: false, ..Default::default() }),
        // observed on a real phone: measured noisy Wi-Fi vs never-measured TCP-only → stay
        (vec![l(K::Lan, 110.0), LinkStats { kind: K::Stream, available: true, ..Default::default() }],
            PolicyState { current: Some(K::Lan), current_healthy: true, since_switch_s: 60.0, better_for_s: 60.0, override_kind: None }),
    ];
    json!(cases.into_iter().map(|(c, st)| json!({
        "candidates": c, "state": st,
        "scores": c.iter().map(score).collect::<Vec<_>>(),
        "rank": rank(&c), "decision": decide(&c, &st)
    })).collect::<Vec<_>>())
}

/// Relative steering (docs/STEERING.md). Every input is written out explicitly (quaternions,
/// gravity vectors, calibrations as JSON), so mirrors need only the steering code under test.
fn steering_v() -> Value {
    // Deterministic pseudo-random wobble (no rand dependency): 32-bit LCG.
    let mut seed: u32 = 0x9c9c_0001;
    let mut rnd = move || { seed = seed.wrapping_mul(1_664_525).wrapping_add(1_013_904_223); seed as f64 / u32::MAX as f64 };
    let (x, y, z) = (Vec3::new(1.0, 0.0, 0.0), Vec3::new(0.0, 1.0, 0.0), Vec3::new(0.0, 0.0, 1.0));
    let grip = Quat::axis_angle(z, 20.0).mul(Quat::axis_angle(x, 55.0)).mul(Quat::axis_angle(y, 90.0)).mul(Quat::axis_angle(z, 4.0));
    let wheel = Vec3::SCREEN_CLOCKWISE;
    let tipped = Quat::axis_angle(x, 25.0).rotate(wheel).normalized().unwrap();
    let turned = |n: Quat, deg: f64, a: Vec3| n.mul(Quat::axis_angle(a, deg));
    let held = |q: Quat, spread: f64| HeldPose { pose: Pose::Orientation(q), spread_deg: spread, samples: 50 };
    let q = |q: Quat| json!(q.to_vec());
    let v = |v: Vec3| json!(v.to_vec());

    let twist: Vec<Value> = [(grip, 0.0, wheel), (grip, 17.5, wheel), (grip, -33.0, wheel), (grip, 170.0, wheel), (grip, 15.0, tipped), (Quat::IDENTITY, 42.0, x)]
        .iter().map(|(n, d, a)| { let r = st::relative(*n, turned(*n, *d, *a)); json!({"r": q(r), "axis": v(wheel), "out_deg": st::twist_deg(r, wheel)}) }).collect();
    let down = Vec3::new(0.0, 0.0, -1.0);
    let q0 = Quat::axis_angle(x, 30.0).mul(Quat::axis_angle(z, 90.0));
    let gravity: Vec<Value> = [(q0, 22.5), (q0, -22.5), (q0, 90.0), (Quat::IDENTITY, 30.0)]
        .iter().map(|(n, d)| { let g0 = n.conj().rotate(down); let g = turned(*n, *d, wheel).conj().rotate(down);
            json!({"g0": v(g0), "g": v(g), "axis": v(wheel), "out_deg": st::gravity_twist_deg(g0, g, wheel)}) }).collect();

    let neutral = CalibrationV2::neutral_only(&held(grip, 0.3), 1_760_000_000_000, "vectors");
    let mut ranges = Vec::new();
    for (l, r, ls, rs) in [(turned(grip, -25.0, wheel), turned(grip, 40.0, wheel), 0.4, 0.5), (turned(grip, -30.0, tipped), turned(grip, 30.0, tipped), 0.4, 0.4),
                           (turned(grip, -5.0, wheel), turned(grip, 30.0, wheel), 0.4, 0.4), (turned(grip, -30.0, wheel), grip.mul(Quat::axis_angle(x, 30.0)), 0.4, 0.4),
                           (turned(grip, -30.0, wheel), turned(grip, 30.0, wheel), 6.0, 0.4)] {
        let out = match st::range(&neutral, &held(l, ls), &held(r, rs)) {
            RangeResult::Ok { cal, axis_to_screen_normal_deg } => json!({"ok": true, "axis": cal.axis, "leftDeg": cal.left_deg, "rightDeg": cal.right_deg, "axisToScreenNormalDeg": axis_to_screen_normal_deg}),
            RangeResult::Retry(_) => json!({"ok": false}),
        };
        ranges.push(json!({"left": {"q": q(l), "spread": ls}, "right": {"q": q(r), "spread": rs}, "expect": out}));
    }

    let mut samples: Vec<Pose> = (0..80).map(|_| Pose::Orientation(grip.mul(Quat::axis_angle(Vec3::new(rnd() - 0.5, rnd() - 0.5, rnd() - 0.5), rnd() * 0.5))))
        .collect();
    samples.push(Pose::Orientation(turned(grip, 25.0, wheel))); // one bump the estimator must reject
    let cap = st::capture(&samples, 20).unwrap();
    let Pose::Orientation(cq) = cap.pose else { unreachable!() };
    let capture = json!({"samples": samples.iter().map(|p| match p { Pose::Orientation(x) => q(*x), _ => unreachable!() }).collect::<Vec<_>>(),
        "minSamples": 20, "expect": {"q": q(cq), "spread": cap.spread_deg, "samples": cap.samples}});

    // A drive: calibrated unequal range, real filter and dead zone, a recenter mid-way (blend), and a
    // turn past ±180° (unwrap). Each sample may carry a recenter calibration applied before it.
    let RangeResult::Ok { cal: full, .. } = st::range(&neutral, &held(turned(grip, -28.0, wheel), 0.4), &held(turned(grip, 36.0, wheel), 0.4)) else { unreachable!() };
    let m = Motion { enabled: true, lock_deg: 45.0, deadzone: 0.03, sensitivity: 1.1, curve: 1.2, min_cutoff: 1.5, beta: 0.03, ..Motion::default() };
    let mut s = RelativeSteering::new(Some(full.clone()), m);
    let mut seq = Vec::new();
    let mut new_neutral = grip;
    for i in 0..240 {
        let t = i as f64 / 200.0;
        let mut recenter = Value::Null;
        if i == 120 {
            new_neutral = turned(grip, 12.0, wheel);
            let (c, _) = st::recenter(s.calibration(), &held(new_neutral, 0.3), 1_760_000_000_500, "vectors");
            s.recalibrate(Some(c.clone()), t);
            recenter = serde_json::to_value(&c).unwrap();
        }
        let deg = if i < 200 { 22.0 * (t * 3.0).sin() + (rnd() - 0.5) * 0.6 } else { 150.0 + (i - 200) as f64 * 1.5 };
        let pose = turned(new_neutral, deg, wheel).mul(Quat::axis_angle(x, (rnd() - 0.5) * 2.0)); // plus a little tilt
        let out = s.update(Pose::Orientation(pose), t);
        seq.push(json!({"t": t, "q": q(pose), "recenter": recenter, "out": out, "angle": s.angle_deg}));
    }
    let good = serde_json::to_string(&full).unwrap();
    let decode = vec![
        json!({"json": good, "valid": true}),
        json!({"json": good.replace("\"v\":2", "\"v\":1"), "valid": false}),
        json!({"json": r#"{"v":2,"kind":"orientation","q":[0,0,0,0],"noiseDeg":0.1,"measuredAt":1}"#, "valid": false}),
        json!({"json": r#"{"v":2,"kind":"orientation","q":[1,0,0,0],"leftDeg":3,"rightDeg":30,"noiseDeg":0.1,"measuredAt":1}"#, "valid": false}),
        json!({"json": r#"{"v":2,"kind":"orientation","q":[1,0,0,0],"leftDeg":30,"noiseDeg":0.1,"measuredAt":1}"#, "valid": false}),
        json!({"json": r#"{"v":2,"kind":"gravity","g":[0,-1,0],"noiseDeg":0.2,"measuredAt":1}"#, "valid": true}),
        json!({"json": "not json", "valid": false}),
    ];
    json!({
        "tolerance": 1e-6,
        "twist": twist, "gravity": gravity, "neutral": neutral, "range": ranges, "capture": capture,
        "drive": {"motion": {"lockDeg": m.lock_deg, "deadzone": m.deadzone, "sensitivity": m.sensitivity, "curve": m.curve, "invert": m.invert, "minCutoff": m.min_cutoff, "beta": m.beta},
                  "calibration": full, "samples": seq},
        "decode": decode,
    })
}

fn main() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../vectors");
    std::fs::create_dir_all(&dir).unwrap();
    for (name, v) in [("noise_xx", noise()), ("frames", frames()), ("pairing", pairing_v()), ("motion", motion_v()), ("profiles", profiles_v()), ("link", link_v()), ("policy", policy_v()), ("steering", steering_v())] {
        let p = dir.join(format!("{name}.json"));
        std::fs::write(&p, serde_json::to_string_pretty(&v).unwrap() + "\n").unwrap();
        println!("wrote {}", p.display());
    }
}
