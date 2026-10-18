# Project map

```
protocol/            SPEC.md, PROFILE.md, schema/, profiles/ (presets), vectors/ (generated)
  rust/              nine-proto: reference implementation + gen-vectors
  kotlin/            :protocol Gradle module (Android + JVM tests)
  swift/             NineKit Swift package (iOS; tests on macOS)
hosts/
  core/              nine-host-core: Host runtime, pairing, store, discovery, HID report, client
  linux/             nine-pad-linux: uinput backend, udev packaging
  macos/             nine-pad-macos: IOHIDUserDevice backend; poc/ probe
  windows/           nine-pad-windows: VHF IOCTL client, nine-devnode; driver/NineVhf (KMDF C)
  cli/               ninectl: headless host + simulated controller
  app/               Tauri desktop companion: src-tauri/ (Rust), ui/ (HTML/CSS/JS), installer hooks
apps/
  android/           Gradle root; app/ (Compose UI, data, net, input)
  ios/               project.yml (XcodeGen), App/ (SwiftUI/UIKit), Core/ (NineAppCore package)
tests/               run_all.sh, results/
tools/               netprobe (v0 test probe), icons/, swift-test.sh
docs/                PLAN, REQUIREMENTS, SUPPORT_MATRIX, SECURITY, TEST_PLAN, BUILD, RELEASE,
                     TROUBLESHOOTING, FEASIBILITY, decisions/ (ADRs), platforms/
```
