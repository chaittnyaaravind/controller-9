// swift-tools-version:6.0
// NineKit — 9-controller protocol v1 for Apple platforms (iOS app; testable on macOS with `swift test`).
import PackageDescription

let package = Package(
    name: "NineKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "NineKit", targets: ["NineKit"])],
    targets: [
        .target(name: "NineKit"),
        .testTarget(name: "NineKitTests", dependencies: ["NineKit"]),
    ],
    swiftLanguageModes: [.v5]
)
