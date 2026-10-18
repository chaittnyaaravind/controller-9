import Foundation
import Testing
@testable import NineKit

private let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../..").standardized
private func load(_ name: String) throws -> [String: Any] {
    try JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("vectors/\(name).json"))) as! [String: Any]
}
private func hx(_ d: [String: Any], _ k: String) -> [UInt8] { [UInt8](hex: d[k] as! String)! }

@Suite struct VectorTests {
    @Test func noiseXXByteExact() throws {
        let v = try load("noise_xx")
        let i = Handshake(initiator: true, s: try KeyPair(secret: hx(v, "init_static"))).withFixedEphemeral(hx(v, "init_ephemeral"))
        let r = Handshake(initiator: false, s: try KeyPair(secret: hx(v, "resp_static"))).withFixedEphemeral(hx(v, "resp_ephemeral"))
        let msgs = v["messages"] as! [[String: Any]]
        let m1 = try i.writeMessage(hx(msgs[0], "payload")); #expect(m1 == hx(msgs[0], "ciphertext"))
        #expect(try r.readMessage(m1) == hx(msgs[0], "payload"))
        let m2 = try r.writeMessage(hx(msgs[1], "payload")); #expect(m2 == hx(msgs[1], "ciphertext"))
        #expect(try i.readMessage(m2) == hx(msgs[1], "payload"))
        let m3 = try i.writeMessage(hx(msgs[2], "payload")); #expect(m3 == hx(msgs[2], "ciphertext"))
        #expect(try r.readMessage(m3) == hx(msgs[2], "payload"))
        let ti = try i.intoTransport(), tr = try r.intoTransport()
        #expect(ti.handshakeHash == hx(v, "handshake_hash"))
        #expect(ti.remoteStatic == hx(v, "resp_static_pub"))
        for t in v["transport"] as! [[String: Any]] {
            let (s, rcv) = (t["from"] as! String) == "initiator" ? (ti, tr) : (tr, ti)
            let ct = try s.encrypt(hx(t, "payload"))
            #expect(ct == hx(t, "ciphertext"))
            #expect(try rcv.decrypt(ct) == hx(t, "payload"))
        }
    }

    @Test func framesByteExactAndRejections() throws {
        let v = try load("frames")
        let key = hx(v, "key")
        for c in v["valid"] as! [[String: Any]] {
            let h = Header(type: PacketType(rawValue: UInt8(c["type"] as! Int))!, sessionId: UInt32(c["session_id"] as! Int), counter: UInt64(c["counter"] as! Int))
            #expect(Datagram.seal(key: key, header: h, payload: hx(c, "payload")) == hx(c, "datagram"), "\(c["name"]!)")
            let (h2, pt) = try Datagram.open(key: key, hx(c, "datagram"))
            #expect(h2 == h)
            if h.type == .input {
                let f = c["fields"] as! [String: Any]
                let fr = try InputFrame.decode(pt)
                #expect(fr.state == InputState(buttons: UInt32(f["buttons"] as! Int), lx: Int16(f["lx"] as! Int), ly: Int16(f["ly"] as! Int),
                                               rx: Int16(f["rx"] as! Int), ry: Int16(f["ry"] as! Int), lt: UInt16(f["lt"] as! Int), rt: UInt16(f["rt"] as! Int)))
                #expect(fr.encode() == hx(c, "payload"))
            }
            if h.type == .pong { #expect(try Pong.decode(pt) == Pong(tUs: 99, id: 7, hostRxUs: 1000, hostTxUs: 1010)) }
        }
        let map: [String: FrameError] = ["too_short": .tooShort, "auth": .auth, "bad_magic": .badMagic, "bad_version": .badVersion,
                                         "unknown_type": .unknownType, "out_of_range": .outOfRange, "bad_length": .badLength]
        for c in v["invalid"] as! [[String: Any]] {
            #expect(throws: map[c["error"] as! String]!) { _ = try InputFrame.decode(try Datagram.open(key: key, hx(c, "datagram")).1) }
        }
        let fr = v["freshness"] as! [String: Any]
        var f = Freshness()
        let got = (fr["counters"] as! [Int]).map { f.accept(UInt64($0)) }
        #expect(got == fr["accepted"] as! [Bool])
        #expect(f.lost == UInt64(fr["lost"] as! Int) && f.stale == UInt64(fr["stale"] as! Int))
    }

    @Test func pairing() throws {
        let v = try load("pairing")
        let h = hx(v, "handshake_hash"), na = hx(v, "na"), nb = hx(v, "nb")
        #expect(Pairing.commitment(h: h, nb: nb) == hx(v, "commitment"))
        #expect(Pairing.sasCode(h: h, na: na, nb: nb) == UInt32(v["sas"] as! Int))
        #expect(Pairing.formatCode(Pairing.sasCode(h: h, na: na, nb: nb)) == v["sas_display"] as! String)
        #expect(Pairing.qrProof(token: hx(v, "token"), h: h) == hx(v, "qr_proof"))
        let u = try #require(PairURI(parsing: v["uri"] as! String))
        #expect(u.hostStatic == hx(v, "uri_host_static") && u.name == "Studio PC")
        #expect(u.uri == v["uri"] as! String, "URI encoding identical to Rust")
        #expect(Pairing.hostId(u.hostStatic) == v["host_id_of_uri_key"] as! String)
        #expect(PairURI(parsing: (v["uri"] as! String).replacingOccurrences(of: "v=1", with: "v=2")) == nil)
    }

    @Test func motion() throws {
        let v = try load("motion")
        let p = v["params"] as! [String: Any]
        var m = MotionSettings()
        m.enabled = true; m.lockDeg = p["lockDeg"] as! Double; m.deadzone = p["deadzone"] as! Double; m.sensitivity = p["sensitivity"] as! Double
        m.curve = p["curve"] as! Double; m.minCutoff = p["minCutoff"] as! Double; m.beta = p["beta"] as! Double
        for c in v["shape"] as! [[String: Any]] {
            #expect(abs(MotionMath.shape(c["delta_deg"] as! Double, m) - (c["out"] as! Double)) < 1e-9)
        }
        var s = Steering(m)
        s.setCenter(v["center_deg"] as! Double)
        for (i, c) in (v["sequence"] as! [[String: Any]]).enumerated() {
            let g = c["g"] as! [Double]
            let got = s.update(g[0], g[1], g[2], t: c["t"] as! Double)
            #expect(abs(got - (c["out"] as! Double)) < 1e-9, "sample \(i)")
        }
    }

    @Test func profiles() throws {
        let v = try load("profiles")
        for c in v["migration"] as! [[String: Any]] {
            let got = try Profile.parse((c["input"] as! String).data(using: .utf8)!)
            let want = try JSONDecoder().decode(Profile.self, from: JSONSerialization.data(withJSONObject: c["expected"]!))
            #expect(got == want)
        }
        for c in v["invalid"] as! [[String: Any]] {
            #expect(throws: ProfileError.self, "\(c["why"]!)") { _ = try Profile.parse((c["json"] as! String).data(using: .utf8)!) }
        }
        let presets = try FileManager.default.contentsOfDirectory(at: root.appendingPathComponent("profiles"), includingPropertiesForKeys: nil).filter { $0.pathExtension == "json" }
        #expect(presets.count >= 3)
        for f in presets {
            let p = try Profile.parse(Data(contentsOf: f))
            #expect(try Profile.parse(p.jsonData()) == p, "\(f.lastPathComponent)")
        }
    }

    @Test func controlJson() throws {
        let s = try Control.parse(Array(#"{"t":"session","slot":1,"sessionId":4000000000,"udpPort":47900,"kIn":"a","kOut":"b","heartbeatMs":50,"idleHeartbeatMs":200,"timeoutMs":250,"extra":1}"#.utf8))
        #expect(s == .session(slot: 1, sessionId: 4_000_000_000, udpPort: 47900, kIn: "a", kOut: "b", heartbeatMs: 50, idleHeartbeatMs: 200, timeoutMs: 250))
        #expect(!s.description.contains("kIn") && !"\(s)".contains("\"a\""))
        #expect(try Control.parse(Control.open(resumeSlot: 2, profile: nil).json()) == .open(resumeSlot: 2, profile: nil))
        #expect(throws: (any Error).self) { _ = try Control.parse(Array(#"{"t":"launch"}"#.utf8)) }
    }
}

/// Live Swift client ↔ Rust host. Skipped unless NINECTL_URI is set (see protocol/kotlin LiveInteropTest).
@Test func liveInteropWithRustHost() async throws {
    guard let text = ProcessInfo.processInfo.environment["NINECTL_URI"], let uri = PairURI(parsing: text) else { return }
    let conn = try await ControlConnection.connect(host: uri.addr, port: uri.port, identity: .generate(), hello: ControllerHello(name: "Swift interop", platform: "ios", app: "test"))
    try await conn.pairQr(uri)
    let info = try await conn.open(resumeSlot: nil, profile: "Interop")
    let ch = InputChannel(host: uri.addr, info: info)
    for i in 0..<200 {
        ch.sendInput(InputState(buttons: i % 20 < 10 ? NineButton.a.mask : 0, lx: axis(fromUnit: sin(Double(i) / 10))), flags: 0)
        if i % 10 == 0 { ch.ping() }
        try await Task.sleep(nanoseconds: 4_000_000)
    }
    try await Task.sleep(nanoseconds: 200_000_000)
    print("Swift->Rust interop: sent \(ch.sent) frames, RTT p50=\(ch.rttMs.percentile(50) ?? -1) ms p95=\(ch.rttMs.percentile(95) ?? -1) ms n=\(ch.rttMs.count)")
    #expect(ch.rttMs.count >= 15)
    ch.sendInput(.neutral, flags: 0); ch.close(); conn.close()
}
