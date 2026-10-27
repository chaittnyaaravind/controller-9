import CoreImage.CIFilterBuiltins
import NineAppCore
import SwiftUI

/// Share a game's setup phone-to-phone as a QR code (GameShare, the same format as Android).
struct ShareGameView: View {
    @Environment(AppModel.self) private var model
    let game: Game

    var body: some View {
        let code = GameShare.encode(GameShare.Shared(name: game.name, accent: game.accent.rawValue, layout: game.layout,
                                                    modes: PadPresets.from(selection: game.layout).map { ButtonModes.encode(model.settings.modes(for: $0)) } ?? ""))
        VStack(spacing: 18) {
            Micro("Share \(game.name)", color: Nine.accent)
            if let img = Self.qr(code) {
                Image(uiImage: img).interpolation(.none).resizable().scaledToFit()
                    .padding(14).background(.white, in: RoundedRectangle(cornerRadius: 16))
                    .frame(maxWidth: 280).accessibilityLabel("QR code for \(game.name)")
            }
            Text("On the other phone: Home → Scan a game. It gets this game's name, colour, layout and turbo buttons. Works between iPhone and Android.")
                .font(.subheadline).foregroundStyle(Nine.muted).multilineTextAlignment(.center)
        }
        .padding(24).frame(maxWidth: .infinity, maxHeight: .infinity).background(Nine.bg)
    }

    static func qr(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator(); f.message = Data(text.utf8); f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// Scan a friend's game QR code and add the game.
struct ScanGameView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var error: String?

    var body: some View {
        VStack(spacing: 14) {
            Micro("Scan a game", color: Nine.accent)
            QRScanner { text in
                if let s = GameShare.decode(text) { model.play(model.importGame(s)); dismiss() }
                else { error = "That isn’t a Controller-9 game code." }
            }
            .aspectRatio(1, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 14))
            if let error { Text(error).foregroundStyle(Nine.warn) }
            Text("Point the camera at the code on the other phone (Share in its game menu).").font(.footnote).foregroundStyle(Nine.dim)
        }
        .padding(20).background(Nine.bg)
    }
}

extension AppModel {
    /// Adds a shared game (a new id, never overwriting one of yours) with its turbo/toggle buttons.
    /// A custom layout that isn't on this phone falls back to the Xbox-style layout.
    func importGame(_ s: GameShare.Shared) -> Game {
        let slug = s.name.lowercased().map { $0.isLetter || $0.isNumber ? $0 : "-" }.reduce(into: "") { $0.append($1) }
        let base = slug.isEmpty ? "game" : slug
        let ids = Set(games.map(\.id))
        let id = (1...).lazy.map { n in n == 1 ? base : "\(base)-\(n)" }.first { !ids.contains($0) }!
        let pad = PadPresets.from(selection: s.layout)
        let layout = pad != nil || profiles.contains(where: { $0.id == s.layout }) ? s.layout : PadPresets.selection(PadPresets.xbox)
        if let pad, !s.modes.isEmpty {
            for (el, m) in ButtonModes.decode(s.modes) where pad.elements.contains(where: { $0.id == el }) { settings.setMode(m, for: el, in: pad) }
        }
        let g = Game(id: id, name: s.name, accent: Accent(rawValue: s.accent) ?? .platinum, layout: layout)
        games.append(g)
        return g
    }
}
