import Foundation
import Network

/// "Why won't it connect?" — same causes and wording as Android's net/ConnectionDoctor.kt (replica/fixes.md, F2).
/// Gathers what the phone can see (Wi-Fi address, VPN) and tries the computer's addresses once, then names the
/// likely cause in plain words with its fix. Local network only.
public enum ConnectionDoctor {
    public enum Probe: Int, Sendable { case reachable, refused, timeout, noRoute }
    public struct Net: Equatable, Sendable { public let addr: String; public let prefix: Int
        public init(_ addr: String, _ prefix: Int) { self.addr = addr; self.prefix = prefix } }
    public struct Facts: Sendable {
        public var hostName: String, hostAddr: String, hostPort: UInt16, onWifi: Bool, vpn: Bool, phone: [Net], probe: Probe
        public init(hostName: String, hostAddr: String, hostPort: UInt16, onWifi: Bool, vpn: Bool, phone: [Net], probe: Probe) {
            self.hostName = hostName; self.hostAddr = hostAddr; self.hostPort = hostPort; self.onWifi = onWifi; self.vpn = vpn; self.phone = phone; self.probe = probe
        }
    }
    public enum Cause: Sendable { case reachable, noNetwork, vpn, notRunning, otherNetwork, blocked, noAddress }
    public struct Finding: Sendable { public let cause: Cause; public let title: String; public let detail: String; public let fixes: [String] }

    public static func diagnose(_ f: Facts) -> Finding {
        let name = f.hostName
        if f.hostAddr.isEmpty { return Finding(cause: .noAddress, title: "\(name) was paired over Bluetooth only", detail: "There is no network address to try.",
                                               fixes: ["Pair again by QR code while both are on the same Wi-Fi."]) }
        if f.probe == .reachable { return Finding(cause: .reachable, title: "\(name) answers", detail: "The network is fine: this iPhone reached \(f.hostAddr):\(f.hostPort).",
                                                  fixes: ["Tap Connect again. If it still fails, restart Controller-9 on the computer."]) }
        if f.probe == .refused { return Finding(cause: .notRunning, title: "Controller-9 isn't running on \(name)",
                                                detail: "The computer answered, but nothing is listening for controllers on port \(f.hostPort).",
                                                fixes: ["Open Controller-9 on \(name) and leave it running.", "If it is open, quit and reopen it."]) }
        if f.vpn { return Finding(cause: .vpn, title: "A VPN is on", detail: "A VPN can hide the computers on your own network from this iPhone.",
                                  fixes: ["Turn the VPN off while you play, or allow local network access in the VPN app."]) }
        let same = f.phone.contains { sameNet($0, f.hostAddr) }
        if f.phone.isEmpty || (!f.onWifi && !same) {
            return Finding(cause: .noNetwork, title: "This iPhone isn't on the computer's Wi-Fi", detail: "\(name) is at \(f.hostAddr), and this iPhone has no Wi-Fi connection to that network.",
                           fixes: ["Join the same Wi-Fi as \(name).", "No Wi-Fi? Turn on Personal Hotspot and join it from the computer."])
        }
        // No answer and different address ranges: likely, not certain (routed networks; firewalls drop instead of refusing).
        if !same {
            return Finding(cause: .otherNetwork, title: "Phone and computer may be on different networks",
                           detail: "No answer from \(f.hostAddr), and this iPhone is on \(f.phone.map { "\($0.addr)/\($0.prefix)" }.joined(separator: ", ")). Guest Wi-Fi, separate 5 GHz and 2.4 GHz networks, or a mesh extender can cause this.",
                           fixes: ["Check Controller-9 is open on \(name).", "Join the same Wi-Fi network on both.", "If \(name) moved networks, pair again from the computer's QR code."])
        }
        return Finding(cause: .blocked, title: "\(name) doesn't answer",
                       detail: "Both are on the same network, but nothing came back from \(f.hostAddr). Usually the computer's firewall, or a Wi-Fi that keeps devices apart (guest, office, school and hotel networks often do).",
                       fixes: ["Check Controller-9 is open on \(name) (a firewall can make a closed app look like silence).",
                               "Windows: when it asks, allow Controller-9 on Private networks, and set this Wi-Fi to Private.",
                               "Mac: System Settings → Network → Firewall → allow Controller-9.",
                               "On a network that keeps devices apart, use Personal Hotspot instead."])
    }

    /// True when `host` (dotted IPv4) is inside `net`.
    public static func sameNet(_ net: Net, _ host: String) -> Bool {
        guard let a = ipv4(net.addr), let b = ipv4(host) else { return false }
        let p = min(max(net.prefix, 0), 32)
        let mask: UInt32 = p == 0 ? 0 : ~UInt32(0) << UInt32(32 - p)
        return a & mask == b & mask
    }

    static func ipv4(_ s: String) -> UInt32? {
        let parts = s.split(separator: ".").map { UInt32($0) }
        guard parts.count == 4, parts.allSatisfy({ $0 != nil && $0! <= 255 }) else { return nil }
        return parts.reduce(0) { ($0 << 8) | $1! }
    }

    /// Gathers the facts on this iPhone and tries each of the host's addresses (best result wins).
    public static func check(host: PairedHost, timeout: TimeInterval = 1.5) async -> Facts {
        let (wifi, nets) = localNets()
        var best: Probe = .noRoute; var shown = host.addr
        for a in host.allAddrs {
            let r = await probe(a, host.port, timeout)
            if r.rawValue < best.rawValue { best = r; shown = a }
        }
        return Facts(hostName: host.name, hostAddr: shown, hostPort: host.port, onWifi: wifi, vpn: vpnOn(), phone: nets, probe: best)
    }

    /// IPv4 addresses on Wi-Fi (en0) and the hotspot/USB bridges, with their prefix lengths.
    static func localNets() -> (Bool, [Net]) {
        var out: [Net] = []; var wifi = false
        var ifa: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifa) == 0, let first = ifa else { return (false, []) }
        defer { freeifaddrs(ifa) }
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let i = p.pointee
            guard let sa = i.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET), let nm = i.ifa_netmask else { continue }
            let name = String(cString: i.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }
            let addr = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            let mask = nm.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN)); var a = in_addr(s_addr: addr)
            inet_ntop(AF_INET, &a, &buf, socklen_t(INET_ADDRSTRLEN))
            if name == "en0" { wifi = true }
            out.append(Net(String(cString: buf), UInt32(bigEndian: mask).nonzeroBitCount))
        }
        return (wifi, out)
    }

    /// A VPN shows up as a scoped proxy entry for a tunnel interface.
    static func vpnOn() -> Bool {
        guard let s = CFNetworkCopySystemProxySettings()?.takeRetainedValue() as? [String: Any],
              let scoped = s["__SCOPED__"] as? [String: Any] else { return false }
        return scoped.keys.contains { $0.hasPrefix("utun") || $0.hasPrefix("ipsec") || $0.hasPrefix("ppp") || $0.hasPrefix("tap") || $0.hasPrefix("tun") }
    }

    static func probe(_ addr: String, _ port: UInt16, _ timeout: TimeInterval) async -> Probe {
        guard let p = NWEndpoint.Port(rawValue: port) else { return .noRoute }
        let c = NWConnection(host: NWEndpoint.Host(addr), port: p, using: .tcp)
        return await withCheckedContinuation { k in
            let once = Once(k) { c.cancel() }
            c.stateUpdateHandler = { st in
                switch st {
                case .ready: once.resume(.reachable)
                case .waiting(let e), .failed(let e): once.resume(classify(e))
                default: break
                }
            }
            c.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { once.resume(.timeout) }
        }
    }

    static func classify(_ e: NWError) -> Probe {
        if case let .posix(code) = e {
            switch code { case .ECONNREFUSED, .ECONNRESET: return .refused; case .ETIMEDOUT: return .timeout; default: return .noRoute }
        }
        return .noRoute
    }

    private final class Once: @unchecked Sendable {
        private var k: CheckedContinuation<Probe, Never>?; private let lock = NSLock(); private let done: () -> Void
        init(_ k: CheckedContinuation<Probe, Never>, done: @escaping () -> Void) { self.k = k; self.done = done }
        func resume(_ p: Probe) {
            lock.lock(); let c = k; k = nil; lock.unlock()
            guard let c else { return }
            c.resume(returning: p); done()
        }
    }
}
