import Foundation

/// Profile schema 1 — mirrors protocol/rust/src/profile.rs and protocol/schema/profile.v1.schema.json.
public enum ControlKind: String, Codable, CaseIterable, Sendable { case button, stick, dpad, trigger, pedal, steering }
public enum Orientation: String, Codable, Sendable { case landscape, portrait }

public struct ControlDef: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var kind: ControlKind
    public var bind: String
    public var x: Double, y: Double, w: Double, h: Double
    public var label: String?
    public var opacity: Double
    public var toggle: Bool
    public var haptic: Bool
    enum CodingKeys: String, CodingKey { case id, kind = "type", bind, x, y, w, h, label, opacity, toggle, haptic }
    public init(id: String, kind: ControlKind, bind: String, x: Double, y: Double, w: Double, h: Double, label: String? = nil, opacity: Double = 1, toggle: Bool = false, haptic: Bool = true) {
        self.id = id; self.kind = kind; self.bind = bind; self.x = x; self.y = y; self.w = w; self.h = h; self.label = label; self.opacity = opacity; self.toggle = toggle; self.haptic = haptic
    }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id); kind = try c.decode(ControlKind.self, forKey: .kind); bind = try c.decode(String.self, forKey: .bind)
        x = try c.decode(Double.self, forKey: .x); y = try c.decode(Double.self, forKey: .y); w = try c.decode(Double.self, forKey: .w); h = try c.decode(Double.self, forKey: .h)
        label = try c.decodeIfPresent(String.self, forKey: .label)
        opacity = try c.decodeIfPresent(Double.self, forKey: .opacity) ?? 1
        toggle = try c.decodeIfPresent(Bool.self, forKey: .toggle) ?? false
        haptic = try c.decodeIfPresent(Bool.self, forKey: .haptic) ?? true
    }
}

public struct MotionSettings: Codable, Equatable, Sendable {
    public var enabled = false, axis = "lx", lockDeg = 45.0, deadzone = 0.02, sensitivity = 1.0, curve = 1.0, invert = false, minCutoff = 1.5, beta = 0.03
    public init() {}
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self); let z = MotionSettings()
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? z.enabled
        axis = try c.decodeIfPresent(String.self, forKey: .axis) ?? z.axis
        lockDeg = try c.decodeIfPresent(Double.self, forKey: .lockDeg) ?? z.lockDeg
        deadzone = try c.decodeIfPresent(Double.self, forKey: .deadzone) ?? z.deadzone
        sensitivity = try c.decodeIfPresent(Double.self, forKey: .sensitivity) ?? z.sensitivity
        curve = try c.decodeIfPresent(Double.self, forKey: .curve) ?? z.curve
        invert = try c.decodeIfPresent(Bool.self, forKey: .invert) ?? z.invert
        minCutoff = try c.decodeIfPresent(Double.self, forKey: .minCutoff) ?? z.minCutoff
        beta = try c.decodeIfPresent(Double.self, forKey: .beta) ?? z.beta
    }
}
public struct StickSettings: Codable, Equatable, Sendable {
    public var deadzone = 0.08
    public init() {}
    public init(from d: Decoder) throws { deadzone = try d.container(keyedBy: CodingKeys.self).decodeIfPresent(Double.self, forKey: .deadzone) ?? 0.08 }
}
public struct HapticSettings: Codable, Equatable, Sendable {
    public var enabled = true, strength = 0.7
    public init() {}
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true; strength = try c.decodeIfPresent(Double.self, forKey: .strength) ?? 0.7
    }
}

public enum ProfileError: Error, Equatable, LocalizedError {
    case invalid(String), tooNew(Int)
    public var errorDescription: String? {
        switch self { case let .invalid(m): return m; case let .tooNew(n): return "This profile uses schema \(n); update the app to open it" }
    }
}

public struct Profile: Codable, Equatable, Identifiable, Sendable {
    public var schema = 1
    public var id: String
    public var name: String
    public var orientation: Orientation = .landscape
    public var controls: [ControlDef]
    public var motion = MotionSettings()
    public var sticks = StickSettings()
    public var haptics = HapticSettings()

    public init(id: String, name: String, controls: [ControlDef]) { self.id = id; self.name = name; self.controls = controls }
    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        schema = try c.decode(Int.self, forKey: .schema); id = try c.decode(String.self, forKey: .id); name = try c.decode(String.self, forKey: .name)
        orientation = try c.decodeIfPresent(Orientation.self, forKey: .orientation) ?? .landscape
        controls = try c.decode([ControlDef].self, forKey: .controls)
        motion = try c.decodeIfPresent(MotionSettings.self, forKey: .motion) ?? MotionSettings()
        sticks = try c.decodeIfPresent(StickSettings.self, forKey: .sticks) ?? StickSettings()
        haptics = try c.decodeIfPresent(HapticSettings.self, forKey: .haptics) ?? HapticSettings()
    }

    public func validate() throws {
        func bad(_ m: String) -> ProfileError { .invalid(m) }
        func range(_ n: String, _ v: Double, _ lo: Double, _ hi: Double) throws { if !v.isFinite || v < lo || v > hi { throw bad("\(n) must be in \(lo)...\(hi)") } }
        guard schema == 1 else { throw bad("schema must be 1") }
        guard !id.isEmpty, id.count <= 64, id.allSatisfy({ ("a"..."z").contains($0) || ("0"..."9").contains($0) || $0 == "-" || $0 == "_" }) else { throw bad("id must be 1-64 chars of a-z 0-9 - _") }
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty, name.count <= 48 else { throw bad("name must be 1-48 characters") }
        guard controls.count <= 48 else { throw bad("at most 48 controls") }
        var seen = Set<String>()
        for c in controls {
            guard !c.id.isEmpty, c.id.count <= 32, seen.insert(c.id).inserted else { throw bad("control id '\(c.id)' empty, too long or duplicated") }
            try range("\(c.id).x", c.x, 0, 1); try range("\(c.id).y", c.y, 0, 1); try range("\(c.id).w", c.w, 0.03, 1); try range("\(c.id).h", c.h, 0.03, 1)
            if c.x + c.w > 1 + 1e-6 || c.y + c.h > 1 + 1e-6 { throw bad("\(c.id) extends past the screen edge") }
            try range("\(c.id).opacity", c.opacity, 0.1, 1)
            if (c.label?.count ?? 0) > 12 { throw bad("\(c.id) label longer than 12") }
            let ok: Bool
            switch c.kind {
            case .button: ok = NineButton(id: c.bind) != nil
            case .stick: ok = c.bind == "left" || c.bind == "right"
            case .dpad: ok = c.bind == "dpad"
            case .trigger, .pedal: ok = c.bind == "lt" || c.bind == "rt"
            case .steering: ok = c.bind == "motion"
            }
            if !ok { throw bad("\(c.id) has invalid bind '\(c.bind)' for its type") }
        }
        guard motion.axis == "lx" || motion.axis == "rx" else { throw bad("motion.axis must be lx or rx") }
        try range("motion.lockDeg", motion.lockDeg, 10, 180); try range("motion.deadzone", motion.deadzone, 0, 0.5)
        try range("motion.sensitivity", motion.sensitivity, 0.25, 4); try range("motion.curve", motion.curve, 0.5, 3)
        try range("motion.minCutoff", motion.minCutoff, 0.05, 20); try range("motion.beta", motion.beta, 0, 1)
        try range("sticks.deadzone", sticks.deadzone, 0, 0.5); try range("haptics.strength", haptics.strength, 0, 1)
    }

    /// Parse any supported schema, migrate to v1, validate.
    public static func parse(_ data: Data) throws -> Profile {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw ProfileError.invalid("invalid JSON") }
        let schema = (obj["schema"] as? Int) ?? (obj["version"] as? Int) ?? 0
        let migrated: [String: Any]
        switch schema {
        case 0: migrated = try migrateV0(obj)
        case 1: migrated = obj
        default: throw ProfileError.tooNew(schema)
        }
        let p: Profile
        do { p = try JSONDecoder().decode(Profile.self, from: JSONSerialization.data(withJSONObject: migrated)) }
        catch { throw ProfileError.invalid("invalid profile: \(error)") }
        try p.validate()
        return p
    }

    public func jsonData() -> Data {
        let e = JSONEncoder(); e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try! e.encode(self)
    }

    static func migrateV0(_ v: [String: Any]) throws -> [String: Any] {
        let name = v["name"] as? String ?? "Imported"
        var id = String(name.lowercased().map { ($0.isASCII && ($0.isLetter || $0.isNumber)) ? $0 : "-" }.prefix(64))
        if id.isEmpty { id = "imported" }
        var controls: [[String: Any]] = []
        for (i, el) in ((v["layout"] as? [[String: Any]]) ?? []).enumerated() {
            let kind: String
            switch el["kind"] as? String ?? "" {
            case "pad": kind = "dpad"; case "slider": kind = "trigger"
            case let k where ["button", "stick", "pedal"].contains(k): kind = k
            case let k: throw ProfileError.invalid("v0 control kind '\(k)' cannot be migrated")
            }
            guard let r = el["rect"] as? [Double], r.count == 4 else { throw ProfileError.invalid("v0 control \(i) has no rect") }
            controls.append(["id": "c\(i)", "type": kind, "bind": el["action"] as? String ?? (kind == "dpad" ? "dpad" : ""), "x": r[0], "y": r[1], "w": r[2], "h": r[3]])
        }
        let steer = v["steer"] as? [String: Any]
        return ["schema": 1, "id": id, "name": name, "orientation": "landscape", "controls": controls,
                "motion": ["enabled": steer?["enabled"] as? Bool ?? false, "lockDeg": (steer?["range"] as? Double ?? 90) / 2,
                           "deadzone": steer?["deadzone"] as? Double ?? 0.02]]
    }
}
