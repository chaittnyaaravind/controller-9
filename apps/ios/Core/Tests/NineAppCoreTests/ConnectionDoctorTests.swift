import Foundation
import Network
import Testing
@testable import NineAppCore

private typealias D = ConnectionDoctor
private let home = [D.Net("192.168.1.23", 24)]
private func facts(_ p: D.Probe, phone: [D.Net] = home, wifi: Bool = true, vpn: Bool = false, addr: String = "192.168.1.20") -> D.Facts {
    D.Facts(hostName: "Desk PC", hostAddr: addr, hostPort: 47901, onWifi: wifi, vpn: vpn, phone: phone, probe: p)
}

@Test func doctorNamesEachCause() {
    #expect(D.diagnose(facts(.reachable)).cause == .reachable)
    #expect(D.diagnose(facts(.refused)).cause == .notRunning)
    #expect(D.diagnose(facts(.refused, vpn: true)).cause == .notRunning)
    #expect(D.diagnose(facts(.timeout, vpn: true)).cause == .vpn)
    #expect(D.diagnose(facts(.noRoute, phone: [], wifi: false)).cause == .noNetwork)
    #expect(D.diagnose(facts(.timeout, addr: "10.0.0.5")).cause == .otherNetwork)
    #expect(D.diagnose(facts(.timeout, phone: [D.Net("10.250.244.195", 16)], addr: "10.250.187.182")).cause == .blocked)
    #expect(D.diagnose(facts(.noRoute, addr: "")).cause == .noAddress)
    #expect(D.diagnose(facts(.denied)).cause == .localNetworkDenied)
    #expect(D.diagnose(facts(.timeout, vpn: true, addr: "10.0.0.5")).cause == .otherNetwork) // network checks come before VPN
    for p in [D.Probe.reachable, .refused, .timeout, .noRoute] { #expect(!D.diagnose(facts(p)).fixes.isEmpty) }
}

@Test func doctorSubnetMaths() {
    #expect(D.sameNet(D.Net("192.168.1.23", 24), "192.168.1.200"))
    #expect(!D.sameNet(D.Net("192.168.1.23", 24), "192.168.2.1"))
    #expect(D.sameNet(D.Net("10.250.244.195", 16), "10.250.187.182"))
    #expect(!D.sameNet(D.Net("10.250.244.195", 24), "10.250.187.182"))
    #expect(!D.sameNet(D.Net("192.168.1.23", 24), "not-an-ip"))
}

/// The real probe on this machine: a listening port answers, a closed one refuses.
@Test func doctorProbeTellsListeningFromClosed() async throws {
    let fd = socket(AF_INET, SOCK_STREAM, 0); #expect(fd >= 0)
    var addr = sockaddr_in(); addr.sin_family = sa_family_t(AF_INET); addr.sin_addr.s_addr = inet_addr("127.0.0.1"); addr.sin_port = 0
    var len = socklen_t(MemoryLayout<sockaddr_in>.size)
    _ = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, len) } }
    listen(fd, 1)
    _ = withUnsafeMutablePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) } }
    let port = UInt16(bigEndian: addr.sin_port)
    #expect(await D.probe("127.0.0.1", port, 2) == .reachable)
    close(fd)
    #expect(await D.probe("127.0.0.1", port, 2) == .refused)
}
