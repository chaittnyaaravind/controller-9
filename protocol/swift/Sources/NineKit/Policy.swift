import Foundation

/// Link-selection policy — mirrors protocol/rust/src/policy.rs; pinned by vectors/policy.json.
public enum LinkKind: String, Codable, CaseIterable, Sendable {
    case usb, ethernet, lan, hotspot, wifiDirect = "wifi_direct", stream, ble
    public var base: Double {
        switch self { case .usb: return 100; case .ethernet: return 95; case .lan, .hotspot, .wifiDirect: return 85; case .stream: return 60; case .ble: return 40 }
    }
    public var label: String {
        switch self { case .usb: return "USB network"; case .ethernet: return "Ethernet"; case .lan: return "Wi-Fi / LAN"; case .hotspot: return "Hotspot"
        case .wifiDirect: return "Wi-Fi Direct"; case .stream: return "TCP-only (stream)"; case .ble: return "Bluetooth LE" }
    }
    public var isIP: Bool { self != .ble && self != .stream }
}

public struct LinkStats: Codable, Equatable, Sendable {
    public var kind: LinkKind = .lan, available = false
    public var rttP95Ms: Double?
    public var lossPct = 0.0, disconnectsPerMin = 0.0
    public var lastFailureAgeS: Double?
    public var attempts = 0, successes = 0
    public init(kind: LinkKind, available: Bool, rttP95Ms: Double? = nil) { self.kind = kind; self.available = available; self.rttP95Ms = rttP95Ms }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        kind = try c.decodeIfPresent(LinkKind.self, forKey: .kind) ?? .lan
        available = try c.decodeIfPresent(Bool.self, forKey: .available) ?? false
        rttP95Ms = try c.decodeIfPresent(Double.self, forKey: .rttP95Ms)
        lossPct = try c.decodeIfPresent(Double.self, forKey: .lossPct) ?? 0
        disconnectsPerMin = try c.decodeIfPresent(Double.self, forKey: .disconnectsPerMin) ?? 0
        lastFailureAgeS = try c.decodeIfPresent(Double.self, forKey: .lastFailureAgeS)
        attempts = try c.decodeIfPresent(Int.self, forKey: .attempts) ?? 0
        successes = try c.decodeIfPresent(Int.self, forKey: .successes) ?? 0
    }
}

public struct PolicyState: Codable, Equatable, Sendable {
    public var current: LinkKind?
    public var currentHealthy = false
    public var sinceSwitchS = 0.0, betterForS = 0.0
    public var overrideKind: LinkKind?
    public init(current: LinkKind? = nil, currentHealthy: Bool = false, sinceSwitchS: Double = 0, betterForS: Double = 0, overrideKind: LinkKind? = nil) {
        self.current = current; self.currentHealthy = currentHealthy; self.sinceSwitchS = sinceSwitchS; self.betterForS = betterForS; self.overrideKind = overrideKind
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        current = try c.decodeIfPresent(LinkKind.self, forKey: .current)
        currentHealthy = try c.decodeIfPresent(Bool.self, forKey: .currentHealthy) ?? false
        sinceSwitchS = try c.decodeIfPresent(Double.self, forKey: .sinceSwitchS) ?? 0
        betterForS = try c.decodeIfPresent(Double.self, forKey: .betterForS) ?? 0
        overrideKind = try c.decodeIfPresent(LinkKind.self, forKey: .overrideKind)
    }
}

public enum Decision: Equatable, Sendable { case stay, switchTo(LinkKind), failover([LinkKind]), unavailable }

public enum LinkPolicy {
    public static let switchMargin = 25.0, betterForS = 3.0, minDwellS = 10.0, recentFailureS = 10.0

    public static func score(_ s: LinkStats) -> Double? {
        guard s.available else { return nil }
        var v = s.kind.base
        if let r = s.rttP95Ms { v -= max(r - 20, 0) }
        v -= s.lossPct * 4
        v -= s.disconnectsPerMin * 15
        if let a = s.lastFailureAgeS, a < recentFailureS { v -= 50 }
        if s.attempts >= 3 && Double(s.successes) < 0.5 * Double(s.attempts) { v -= 30 }
        return v
    }

    public static func rank(_ c: [LinkStats]) -> [LinkKind] {
        c.compactMap { s in score(s).map { ($0, LinkKind.allCases.firstIndex(of: s.kind)!, s.kind) } }
            .sorted { $0.0 != $1.0 ? $0.0 > $1.0 : $0.1 < $1.1 }.map(\.2)
    }

    public static func decide(_ c: [LinkStats], _ st: PolicyState) -> Decision {
        func available(_ k: LinkKind) -> Bool { c.contains { $0.kind == k && $0.available } }
        if let o = st.overrideKind, available(o) {
            if st.current == o && st.currentHealthy { return .stay }
            return st.currentHealthy && st.current != nil ? .switchTo(o) : .failover([o])
        }
        var order = rank(c)
        if let cur = st.current, st.currentHealthy {
            guard let best = order.first else { return .stay }
            if best == cur { return .stay }
            guard let sb = c.first(where: { $0.kind == best }).flatMap(score), let sc = c.first(where: { $0.kind == cur }).flatMap(score) else { return .stay }
            return sb - sc >= switchMargin && st.betterForS >= betterForS && st.sinceSwitchS >= minDwellS ? .switchTo(best) : .stay
        }
        if let cur = st.current, let i = order.firstIndex(of: cur) { order.append(order.remove(at: i)) }
        return order.isEmpty ? .unavailable : .failover(order)
    }
}
