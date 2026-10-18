# Name migration: 9-controller → Controller-9 (2026-10-18)

**Controller-9** is the user-facing product name everywhere. Compatibility-sensitive identifiers
were reviewed one by one; nothing has been publicly distributed yet, so every retained identifier
can still be changed before the first public release at no migration cost — but changing them later
would break upgrades, pairings or interoperability.

Checkpoint before the rename: tag `checkpoint/pre-controller-9-rename` (commit `f03bfdc`).

## Changed (public branding)
| Where | Before | After |
|---|---|---|
| Android launcher label, in-app titles, messages | 9-controller | Controller-9 |
| Android Gradle root project / APK file names | 9-controller / `app-*.apk` | Controller-9 / `Controller-9-<version>-<type>.apk` |
| iOS display name, privacy strings, in-app text | 9-controller | Controller-9 |
| iOS Xcode project / target / scheme | NineController | Controller9 (bundle id unchanged) |
| Desktop app product name, window title, installer product name, copyright | 9-controller | Controller-9 |
| Desktop UI, setup and help text | 9-controller | Controller-9 |
| Windows INF provider, device and service **descriptions** | 9-controller … | Controller-9 … |
| Linux uinput device names | `9-controller (Xbox 360 compatible) #n` | `Controller-9 (Xbox 360 compatible) #n` |
| macOS IOHID product/manufacturer strings | 9-controller | Controller-9 |
| CLI | `ninectl` | `controller9` (and `ninectl`, same binary) |
| Profile export file suffix | `.9ctl.json` | `.controller9.json` (import accepts any JSON) |
| Host default name fallback | 9-controller host | Controller-9 host |
| Docs, specs, crate descriptions, profile schema `$id` | 9-controller | Controller-9 |

## Data-location migration (automatic, non-destructive)
| Platform | Old location | New location | Behaviour |
|---|---|---|---|
| Desktop hosts | `ProjectDirs("dev","9-controller","9-controller")` config dir | `ProjectDirs("dev","Controller-9","Controller-9")` | on first run, files copied (permissions kept); old dir left untouched (`hosts/core/src/store.rs::migrate_dir`, unit-tested) |
| iOS | `Application Support/9-controller` | `Application Support/Controller-9` | moved once inside the app container |
| Android | app-private `files/` | unchanged | — |

## Intentionally retained (compatibility)
| Identifier | Value | Why retained |
|---|---|---|
| Noise prologue | `9ctl/1` | part of the handshake hash; changing it is a protocol break (all vectors, all clients) |
| Datagram magic | `"9C"` | wire format |
| Pairing labels | `9ctl-qr`, `9ctl-commit`, `9ctl-sas` | hashed into proofs/codes |
| DNS-SD service type | `_ninectl._tcp` | discovery between old and new builds; also in iOS `NSBonjourServices` |
| Pairing URI scheme | `ninectl://pair` | printed QR codes and parsers |
| Android `applicationId` / Kotlin packages | `dev.ninecontroller.android` | changing the id creates a separate app; decide before first Play upload |
| iOS bundle id | `dev.ninecontroller.ios` | App Store record identity; decide before first TestFlight upload |
| Desktop bundle identifier | `dev.ninecontroller.host` | macOS app identity, Windows install key |
| Windows hardware id / service / interface | `Root\NineVhf`, `NineVhf`, `{6E1E3B2A-9C7F-4D0B-9A51-3C2F8E9D4A01}` | driver upgrades and the host↔driver contract |
| Keychain service / Keystore alias | `dev.ninecontroller.identity`, `ninectl-identity-wrap` | existing identities would be lost |
| Rust crate names | `nine-proto`, `nine-host-core`, `nine-pad-*`, `ninectl` package | internal; renaming churns every import with no user benefit |
| Repository directory | `~/Projects/9-controller` | local path; renaming is the owner's choice and breaks nothing in the code |

## Final search (run at the end of the rename)
`git grep -n "9-controller"` returns only: this file, the migration code (legacy path constants in
`hosts/core/src/store.rs` and `apps/ios/Core/.../Stores.swift`), repository-path references
(`~/Projects/9-controller`), and historical text in `docs/FEASIBILITY.md` / git history.
