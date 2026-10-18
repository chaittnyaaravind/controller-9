import Foundation
import NineKit
import Security

/// The controller's long-lived X25519 identity, stored in the Keychain (this device only, never synced/backed up).
public enum IdentityStore {
    static let service = "dev.ninecontroller.identity"

    public static func keyPair() -> KeyPair {
        if let s = read(), let kp = try? KeyPair(secret: s) { return kp }
        let kp = KeyPair.generate()
        write(kp.secretBytes)
        return kp
    }

    public static func reset() {
        SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service] as CFDictionary)
    }

    static func read() -> [UInt8]? {
        var out: CFTypeRef?
        let q: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne]
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data, d.count == 32 else { return nil }
        return Array(d)
    }

    static func write(_ secret: [UInt8]) {
        reset()
        let q: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecValueData: Data(secret),
                                  kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, kSecAttrSynchronizable: false]
        SecItemAdd(q as CFDictionary, nil)
    }
}

public struct PairedHost: Codable, Equatable, Identifiable, Sendable {
    public var id: String, name: String, pub: String, addr: String, port: UInt16, pairedAt: String, lastUsed: String?
    public init(id: String, name: String, pub: String, addr: String, port: UInt16, pairedAt: String, lastUsed: String? = nil) {
        self.id = id; self.name = name; self.pub = pub; self.addr = addr; self.port = port; self.pairedAt = pairedAt; self.lastUsed = lastUsed
    }
}

public struct AppSettings: Codable, Equatable, Sendable {
    public var activeProfile = "racing"
    public var lastHost: String?
    public var hapticsEnabled = true
    public var hapticStrength = 0.7
    public var sensorHz = 100
    public var calibrationDeg = 0.0
    public var showStats = true
    public init() {}
}

func now() -> String { ISO8601DateFormatter().string(from: Date()) }

/// JSON files in Application Support (excluded from iCloud backup).
public final class FileStore: @unchecked Sendable {
    public let dir: URL
    public init(dir: URL? = nil) {
        let base = dir ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("9-controller")
        try? FileManager.default.createDirectory(at: base.appendingPathComponent("profiles"), withIntermediateDirectories: true)
        var u = base
        var rv = URLResourceValues(); rv.isExcludedFromBackup = true
        try? u.setResourceValues(rv)
        self.dir = base
    }
    func read<T: Decodable>(_ name: String, as: T.Type) -> T? {
        (try? Data(contentsOf: dir.appendingPathComponent(name))).flatMap { try? JSONDecoder().decode(T.self, from: $0) }
    }
    func write<T: Encodable>(_ name: String, _ v: T) {
        let e = JSONEncoder(); e.outputFormatting = [.prettyPrinted, .sortedKeys]
        try? e.encode(v).write(to: dir.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    public var hosts: [PairedHost] { read("hosts.json", as: [PairedHost].self) ?? [] }
    public func saveHosts(_ h: [PairedHost]) { write("hosts.json", h) }
    public func upsertHost(_ h: PairedHost) { saveHosts(hosts.filter { $0.id != h.id } + [h]) }
    public func removeHost(_ id: String) { saveHosts(hosts.filter { $0.id != id }) }

    public var settings: AppSettings { read("settings.json", as: AppSettings.self) ?? AppSettings() }
    public func saveSettings(_ s: AppSettings) { write("settings.json", s) }

    // Profiles
    var profileDir: URL { dir.appendingPathComponent("profiles") }
    public func profiles() -> [Profile] {
        ((try? FileManager.default.contentsOfDirectory(at: profileDir, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "json" }
            .compactMap { try? Profile.parse(Data(contentsOf: $0)) }
            .sorted { $0.name.lowercased() < $1.name.lowercased() }
    }
    public func save(_ p: Profile) throws {
        try p.validate()
        try p.jsonData().write(to: profileDir.appendingPathComponent("\(p.id).json"), options: .atomic)
    }
    public func deleteProfile(_ id: String) { try? FileManager.default.removeItem(at: profileDir.appendingPathComponent("\(id).json")) }
    public func installPresets(from urls: [URL]) {
        for u in urls { if let d = try? Data(contentsOf: u), let p = try? Profile.parse(d) { try? save(p) } }
    }
    public func uniqueId(_ base: String) -> String {
        let clean = String(base.lowercased().map { ($0.isASCII && ($0.isLetter || $0.isNumber)) ? $0 : "-" }.prefix(56))
        let ids = Set(profiles().map(\.id))
        var id = clean.isEmpty ? "profile" : clean, n = 2
        while ids.contains(id) { id = "\(clean)-\(n)"; n += 1 }
        return id
    }
    public func importProfile(_ data: Data) throws -> Profile {
        var p = try Profile.parse(data)
        if profiles().contains(where: { $0.id == p.id }) { p.id = uniqueId(p.id); p.name = String("\(p.name) (imported)".prefix(48)) }
        try save(p)
        return p
    }
    public func duplicate(_ p: Profile) throws -> Profile {
        var q = p; q.id = uniqueId(p.id + "-copy"); q.name = String("\(p.name) copy".prefix(48))
        try save(q); return q
    }
}
