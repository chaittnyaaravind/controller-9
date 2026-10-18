import Foundation
import Testing
@testable import NineKit

private let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../..").standardized
private func load(_ name: String) throws -> Any { try JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("vectors/\(name).json"))) }

@Suite struct LinkTests {
    @Test func recordsAndSegmentsByteExact() throws {
        let v = try load("link") as! [String: Any]
        let control = [UInt8](hex: v["control"] as! String)!, dgram = [UInt8](hex: v["datagram"] as! String)!
        #expect(Record.control(control).encode() + Record.datagram(dgram).encode() == [UInt8](hex: v["stream"] as! String)!)
        var rr = RecordReader()
        for b in [UInt8](hex: v["stream"] as! String)! { rr.push([b]) }
        #expect(try rr.next() == .control(control))
        #expect(try rr.next() == .datagram(dgram))
        var seg = Segmenter()
        let s23 = seg.segment(control, attMtu: 23), s62 = seg.segment(dgram, attMtu: 62)
        #expect(s23.map(\.hex) == v["segments_mtu23"] as! [String])
        #expect(s62.map(\.hex) == v["segments_mtu62_after"] as! [String])
        var re = Reassembler(max: 65535)
        #expect(s23.compactMap { re.push($0) } == [control])
        #expect(s62.compactMap { re.push($0) } == [dgram])
        var bad = RecordReader(); bad.push([7, 0, 1, 0])
        #expect(throws: LinkError.kind(7)) { _ = try bad.next() }
        #expect((v["uuids"] as! [String: String])["service"] == LinkConst.bleService)
        #expect(LinkConst.bleName(hint: [0x0a, 0x1b, 0x2c, 0x3d]) == "C9-0a1b2c3d")
    }

    @Test func policyMatchesRust() throws {
        let cases = try load("policy") as! [[String: Any]]
        let dec = JSONDecoder()
        for (i, c) in cases.enumerated() {
            let cands = try dec.decode([LinkStats].self, from: JSONSerialization.data(withJSONObject: c["candidates"]!))
            let st = try dec.decode(PolicyState.self, from: JSONSerialization.data(withJSONObject: c["state"]!))
            let scores = (c["scores"] as! [Any]).map { $0 as? Double }
            #expect(cands.map(LinkPolicy.score) == scores, "case \(i) scores")
            #expect(LinkPolicy.rank(cands).map(\.rawValue) == c["rank"] as! [String], "case \(i) rank")
            let d = c["decision"] as! [String: Any]
            let want: Decision
            switch d["action"] as! String {
            case "stay": want = .stay
            case "switch": want = .switchTo(LinkKind(rawValue: d["to"] as! String)!)
            case "failover": want = .failover((d["order"] as! [String]).map { LinkKind(rawValue: $0)! })
            default: want = .unavailable
            }
            #expect(LinkPolicy.decide(cands, st) == want, "case \(i) decision")
        }
    }

    @Test func multiAddressURI() throws {
        let v = try load("pairing") as! [String: Any]
        let u = try #require(PairURI(parsing: v["uri_multi"] as! String))
        #expect(u.extra == v["uri_multi_extra"] as! [String])
        #expect(u.uri == v["uri_multi"] as! String)
    }
}

/// QR tokens are single-use: live tests share one identity that pairs once (like a real phone).
actor LivePairing {
    static let shared = LivePairing()
    let id = KeyPair.generate()
    /// One shared task: actors are reentrant, so a plain flag would let a second caller pair again.
    private var pairing: Task<Void, Error>?
    func identity(for uri: PairURI) async throws -> KeyPair {
        let id = self.id
        if pairing == nil {
            pairing = Task {
                let c = try await ControlConnection.connect(host: uri.addr, port: uri.port, identity: id, hello: ControllerHello(name: "Swift interop", platform: "ios", app: "test"))
                try await c.pairQr(uri)
                c.close()
            }
        }
        try await pairing!.value
        return id
    }
}

/// Live tests against a Rust host share one identity, and one identity is one controller: a second
/// concurrent session would (correctly) take over the slot. So they run one after the other.
@Suite(.serialized) struct LiveRustHost {
    /// Live Swift client ↔ Rust host. Skipped unless NINECTL_URI is set (see protocol/kotlin LiveInteropTest).
    @Test func liveInteropWithRustHost() async throws {
        guard let text = ProcessInfo.processInfo.environment["NINECTL_URI"], let uri = PairURI(parsing: text) else { return }
        let id = try await LivePairing.shared.identity(for: uri)
        let conn = try await ControlConnection.connect(host: uri.addr, port: uri.port, identity: id, hello: ControllerHello(name: "Swift interop", platform: "ios", app: "test"))
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

    /// Live stream binding against a Rust host (NINECTL_URI + NINECTL_STREAM_PORT).
    @Test func liveStreamBindingWithRustHost() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let text = env["NINECTL_URI"], let uri = PairURI(parsing: text), let port = env["NINECTL_STREAM_PORT"].flatMap({ UInt16($0) }) else { return }
        let id = try await LivePairing.shared.identity(for: uri)
        let hello = ControllerHello(name: "Swift stream", platform: "ios", app: "test")
        let sb = try await StreamBinding.connect(host: uri.addr, port: port)
        let conn = try await ControlConnection.connect(over: sb.control, identity: id, hello: hello)
        #expect(conn.host.links.contains("stream"))
        let info = try await conn.open(resumeSlot: nil, profile: "Stream")
        let ch = InputChannel(link: sb, info: info)
        for i in 0..<100 {
            ch.sendInput(InputState(buttons: i % 20 < 10 ? NineButton.b.mask : 0), flags: 0)
            if i % 10 == 0 { ch.ping() }
            try await Task.sleep(nanoseconds: 4_000_000)
        }
        try await Task.sleep(nanoseconds: 200_000_000)
        print("Swift->Rust stream interop: sent \(ch.sent) frames, RTT p50=\(ch.rttMs.percentile(50) ?? -1) ms p95=\(ch.rttMs.percentile(95) ?? -1) ms n=\(ch.rttMs.count)")
        #expect(ch.rttMs.count >= 8)
        ch.sendInput(.neutral, flags: 0); conn.close()
    }
}
