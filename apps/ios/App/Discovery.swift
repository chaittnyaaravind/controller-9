import Network
import Observation

struct FoundHost: Identifiable, Hashable {
    let id: String, name: String, endpoint: NWEndpoint, os: String?
}

/// Bonjour browsing for `_ninectl._tcp` (requires NSLocalNetworkUsageDescription + NSBonjourServices).
@MainActor @Observable
final class Discovery {
    private(set) var hosts: [FoundHost] = []
    private(set) var error: String?
    private var browser: NWBrowser?

    func start() {
        guard browser == nil else { return }
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_ninectl._tcp", domain: nil), using: .tcp)
        b.stateUpdateHandler = { [weak self] s in
            Task { @MainActor in
                if case let .failed(e) = s { self?.error = "Discovery unavailable: \(e.localizedDescription)" }
                if case let .waiting(e) = s { self?.error = "Allow Local Network access in Settings to find computers (\(e.localizedDescription))." }
            }
        }
        b.browseResultsChangedHandler = { [weak self] results, _ in
            let found: [FoundHost] = results.compactMap { r in
                guard case let .service(name, _, _, _) = r.endpoint else { return nil }
                var txt: [String: String] = [:]
                if case let .bonjour(record) = r.metadata { txt = record.dictionary }
                if let v = txt["v"], v != "1" { return nil }
                return FoundHost(id: txt["id"] ?? name, name: txt["name"] ?? name, endpoint: r.endpoint, os: txt["os"])
            }
            Task { @MainActor in self?.hosts = found.sorted { $0.name < $1.name }; self?.error = nil }
        }
        b.start(queue: .main)
        browser = b
    }

    func stop() { browser?.cancel(); browser = nil }
}
