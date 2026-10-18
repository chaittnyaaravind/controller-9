import Foundation
import NineKit
import Testing
@testable import NineAppCore

final class Box: @unchecked Sendable { var v: [(InputState, UInt16)] = [] }

@Test func engineMergesAndOnlyEmitsChanges() {
    let box = Box()
    let e = ControllerEngine { s, f in box.v.append((s, f)) }
    e.setButtons("a", NineButton.a.mask); e.setButtons("dpad", NineButton.up.mask)
    #expect(e.state.buttons == NineButton.a.mask | NineButton.up.mask)
    e.setTrigger("gas", "rt", 1); e.setTrigger("brake", "lt", 0.5)
    #expect(e.state.rt == 65535 && e.state.lt == 32768)
    var m = MotionSettings(); m.enabled = true; m.axis = "rx"
    e.configure(m); e.setMotion(-0.5)
    #expect(e.state.rx == -16384 && e.flags == flagMotion)
    e.releaseAll()
    #expect(e.state == .neutral)
    let n = box.v.count
    e.releaseAll()
    #expect(box.v.count == n)
}

@Test func fileStoreProfilesImportAndHosts() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("ninecore-\(UUID().uuidString)")
    let s = FileStore(dir: dir)
    let presets = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../../../protocol/profiles").standardized
    s.installPresets(from: try FileManager.default.contentsOfDirectory(at: presets, includingPropertiesForKeys: nil))
    #expect(s.profiles().count >= 3)
    let racing = try #require(s.profiles().first { $0.id == "racing" })
    let imported = try s.importProfile(racing.jsonData())
    #expect(imported.id != "racing" && imported.name.hasSuffix("(imported)"))
    s.upsertHost(PairedHost(id: "h1", name: "PC", pub: "x", addr: "10.0.0.2", port: 47901, pairedAt: "2026-10-17T12:00:00Z"))
    #expect(s.hosts.count == 1)
    s.removeHost("h1")
    #expect(s.hosts.isEmpty)
    #expect(throws: ProfileError.self) { _ = try s.importProfile(Data(#"{"schema":9}"#.utf8)) }
}

@Test func linkClassificationAndLegacyHostsFile() throws {
    #expect(LocalLinks.classify(pathKinds: ["wifi"], remote: "192.168.1.20") == .lan)
    #expect(LocalLinks.classify(pathKinds: ["wifi"], remote: "172.20.10.2") == .hotspot)
    #expect(LocalLinks.classify(pathKinds: ["wiredEthernet"], remote: "10.0.0.2") == .ethernet)
    #expect(LocalLinks.classify(pathKinds: ["other"], remote: "192.168.2.2") == .usb)
    // hosts.json written before transport support (no addrs/links) still loads
    let legacy = #"[{"id":"h1","name":"PC","pub":"x","addr":"10.0.0.2","port":47901,"pairedAt":"2026-10-17T12:00:00Z"}]"#
    let hs = try JSONDecoder().decode([PairedHost].self, from: Data(legacy.utf8))
    #expect(hs[0].allAddrs == ["10.0.0.2"] && hs[0].offers("ble"))
    var h = hs[0]; h.addrs = ["172.20.10.3", "10.0.0.2"]; h.links = ["ip", "stream"]
    #expect(h.allAddrs == ["10.0.0.2", "172.20.10.3"])
    #expect(h.offers("stream") && !h.offers("ble"))
}
