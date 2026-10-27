import NineAppCore
import SwiftUI

/// "Why won't it connect?": checks each paired computer and names the cause with its fix (ConnectionDoctor).
struct DoctorView: View {
    @Environment(AppModel.self) private var model
    @State private var round = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Micro("Why won’t it connect?", color: Nine.accent)
                if model.hosts.isEmpty {
                    Text("No computer is paired yet. Pair one first.").foregroundStyle(Nine.muted)
                    NavigationLink("Add a computer") { AddHostView() }.buttonStyle(SecondaryButton())
                } else {
                    ForEach(model.hosts) { h in HostCheck(host: h, round: round) }
                    HStack(spacing: 10) {
                        Button("Check again") { round += 1 }.buttonStyle(SecondaryButton())
                        NavigationLink("Enter the address by hand") { AddHostView() }.buttonStyle(SecondaryButton())
                    }
                    Text("The check only talks to your own computer on your own network.").font(.footnote).foregroundStyle(Nine.dim)
                }
            }.padding(20)
        }
        .background(Nine.bg).navigationTitle("Connection check")
    }
}

private struct HostCheck: View {
    @Environment(AppModel.self) private var model
    let host: PairedHost
    let round: Int
    @State private var finding: ConnectionDoctor.Finding?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Micro(host.name)
            if let f = finding {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Circle().fill(f.cause == .reachable ? Nine.accent : Nine.warn).frame(width: 8, height: 8)
                    Text(f.title).font(.headline).foregroundStyle(Nine.text)
                }
                Text(f.detail).font(.subheadline).foregroundStyle(Nine.muted)
                ForEach(Array(f.fixes.enumerated()), id: \.offset) { i, fix in
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        Text(String(format: "%02d", i + 1)).font(.caption.monospaced()).foregroundStyle(Nine.accent)
                        Text(fix).font(.subheadline).foregroundStyle(Nine.text)
                    }
                }
                if f.cause == .reachable { Button("Connect") { model.connection.connect(host) }.buttonStyle(SecondaryButton()) }
            } else {
                Text("Checking \(host.addr.isEmpty ? "Bluetooth" : host.addr)…").font(.subheadline).foregroundStyle(Nine.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading).card()
        .task(id: round) { finding = nil; finding = ConnectionDoctor.diagnose(await ConnectionDoctor.check(host: host)) }
    }
}
