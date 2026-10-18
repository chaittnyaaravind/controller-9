# iOS

## Info.plist privacy keys
| Key | Purpose |
|---|---|
| `NSCameraUsageDescription` | scan the pairing QR code |
| `NSLocalNetworkUsageDescription` + `NSBonjourServices = [_ninectl._tcp]` | find and connect to hosts on the LAN |
| `NSBluetoothAlwaysUsageDescription` | Bluetooth LE fallback link (iPhone is a GATT peripheral; works while the app is in the foreground) |

Core Motion device motion does not require a permission prompt. No tracking, no analytics.

## Status
NineKit and NineAppCore build and pass tests on macOS. The SwiftUI/UIKit target parses but has
**not been type-checked or built**: full Xcode is not installed on the dev Mac (Command Line Tools
only). To build: install Xcode 26+, set the team in `apps/ios/project.yml`, `xcodegen generate`,
run on an iPhone. A signed IPA additionally needs an Apple Developer Program membership.
