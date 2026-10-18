// swift-tools-version:6.0
// Platform-neutral iPhone app logic (stores, input engine, connection manager). Builds and tests on
// macOS with `swift test`; the iOS UI target in ../App links it.
import PackageDescription

let package = Package(
    name: "NineAppCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "NineAppCore", targets: ["NineAppCore"])],
    dependencies: [.package(path: "../../../protocol/swift")],
    targets: [
        .target(name: "NineAppCore", dependencies: [.product(name: "NineKit", package: "swift")]),
        .testTarget(name: "NineAppCoreTests", dependencies: ["NineAppCore"]),
    ],
    swiftLanguageModes: [.v5]
)
