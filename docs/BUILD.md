# Building 9-controller

All commands from the repo root unless noted. Versions are pinned in `Cargo.toml`,
`apps/android/gradle/libs.versions.toml`, and the Swift package manifests.

## Toolchain (dev Mac, verified 2026-10-17)
Rust 1.99.1 via rustup (`/opt/homebrew/opt/rustup/bin`), targets `x86_64-unknown-linux-gnu`,
`aarch64-unknown-linux-gnu`, `x86_64-pc-windows-msvc` for type checks · OpenJDK 21 · Android SDK
(platform 36 + 37.0, build-tools 37.0.0) · Gradle 9.8.1 (wrapper) · Swift 6.4 (Command Line Tools)
· XcodeGen 2.46.0.

## Everything testable here
```sh
tests/run_all.sh
```

## Desktop host
```sh
cargo build --release -p ninectl                 # headless host + simulator
cargo build --release -p ninecontroller-app      # desktop companion (dev binary)
cargo install tauri-cli --version "^2" && (cd hosts/app/src-tauri && cargo tauri build)   # installers
```
- **macOS**: produces `.app`/`.dmg`. Signing/notarization: set `APPLE_SIGNING_IDENTITY`,
  `APPLE_ID`, `APPLE_PASSWORD`, `APPLE_TEAM_ID` (see Tauri docs). The virtual-HID entitlement is
  commented out in `hosts/app/macos/entitlements.plist` until Apple grants it.
- **Windows**: needs MSVC build tools + WebView2 (built into Windows 11). Produces NSIS installer;
  put the signed driver package in `driver/` next to the app (see `docs/platforms/windows.md`).
- **Linux**: `sudo apt install libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev
  build-essential`; produces `.deb` (installs the udev rule) and AppImage.

## Windows driver (on Windows)
See `docs/platforms/windows.md`.

## Android
```sh
cd apps/android
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                    # unsigned unless keystore.properties exists
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Release signing: create `apps/android/keystore.properties` (never commit) with `storeFile`,
`storePassword`, `keyAlias`, `keyPassword`.

## iOS
Requires **full Xcode** (not installed on the dev Mac yet) and an Apple developer team.
```sh
cd apps/ios && xcodegen generate && open NineController.xcodeproj
# set DEVELOPMENT_TEAM in project.yml (or Signing & Capabilities), select a device, Run.
xcodebuild -project NineController.xcodeproj -scheme NineController -destination 'generic/platform=iOS' -configuration Release archive -archivePath build/Nine.xcarchive
```
Logic packages test without Xcode: `tools/swift-test.sh protocol/swift`, `tools/swift-test.sh apps/ios/Core`.
