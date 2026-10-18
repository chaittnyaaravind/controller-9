//! Writes `protocol/vectors/*.json`. Kotlin and Swift test suites must reproduce these exactly.
//! Run: cargo run -p nine-proto --bin gen-vectors
use nine_proto::frame::{self, Feedback, Header, InputFrame, PacketType, Ping, Pong};
use nine_proto::input::InputState;
use nine_proto::motion::{shape, Steering};
use nine_proto::noise::{Handshake, KeyPair, Role};
use nine_proto::pairing;
use nine_proto::profile::{Motion, Profile, StickAxis};
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
    ];
    json!(cases.into_iter().map(|(c, st)| json!({
        "candidates": c, "state": st,
        "scores": c.iter().map(score).collect::<Vec<_>>(),
        "rank": rank(&c), "decision": decide(&c, &st)
    })).collect::<Vec<_>>())
}

fn main() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../vectors");
    std::fs::create_dir_all(&dir).unwrap();
    for (name, v) in [("noise_xx", noise()), ("frames", frames()), ("pairing", pairing_v()), ("motion", motion_v()), ("profiles", profiles_v()), ("link", link_v()), ("policy", policy_v())] {
        let p = dir.join(format!("{name}.json"));
        std::fs::write(&p, serde_json::to_string_pretty(&v).unwrap() + "\n").unwrap();
        println!("wrote {}", p.display());
    }
}
