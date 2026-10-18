# Releases

## Policy
An artifact is published (listed in `site/download/releases.json`, which drives the download page)
only when it is: built on a native CI runner from a tagged commit · signed with the platform's
production identity · install-tested on the stated OS · versioned · checksummed (SHA-256) ·
covered by release notes, prerequisites, uninstall/rollback steps and known issues.
Unsigned or untested files are development builds and are never linked. Today the manifest is
**empty**: no artifact meets the bar yet.

## Artifact matrix
| Platform | Artifact | Built by | Signing | Status |
|---|---|---|---|---|
| Android | APK (direct install) + AAB (Play) | `release.yml` → android | release keystore from CI secrets (`ANDROID_KEYSTORE_B64`, passwords); never in Git | unsigned release APK builds locally (2.6 MB); not install-tested |
| iOS | Xcode archive → TestFlight / App Store | `release.yml` → ios (enable with `IOS_RELEASE=true`) | Apple team, certificates and provisioning in CI secrets | needs full Xcode + Apple Developer Program; no unsigned IPA distribution |
| macOS (arm64, x86_64) | .app / .dmg | `release.yml` → desktop | Developer ID + notarization (`APPLE_*` secrets) | builds (dev) locally; not signed. Host works for connection/diagnostics; no game controller without Apple entitlement |
| Windows x64 | NSIS installer + driver package | `release.yml` → desktop, windows-driver | installer: OV/EV code signing; driver: Microsoft attestation signing (Partner Center, EV cert) | driver unbuilt; never ship test-signed drivers |
| Linux x86_64 | .deb (+ udev rule) / AppImage | `release.yml` → desktop | optional GPG | not built natively yet |

`tools/release/checksums.sh <dir>` writes `SHA256SUMS`. Workflows exist in `.github/workflows/` but
have never run (no remote repository configured); first runs must be reviewed.

## Release checklist
- [ ] `tests/run_all.sh` green on the tag; `ci.yml` green on macOS, Linux, Windows runners.
- [ ] Acceptance rows for every advertised platform/link pass on real hardware (`docs/ACCEPTANCE_STATUS.md`).
- [ ] Owner decisions recorded: licence (Apache-2.0 placeholder), Android `applicationId` / iOS bundle id (docs/NAME_MIGRATION.md), pid.codes product id (placeholder 0x9C01).
- [ ] Signed artifacts verified (`apksigner verify`, `codesign --verify --deep` + `spctl`, `signtool verify /pv`).
- [ ] Third-party notices bundled (see licence notes below); privacy text: local network only, no account, no analytics.
- [ ] `releases.json` updated with url, sha256, `signed: true`, `installTested: true` per artifact.

## Third-party licences (checked 2026-10-18)
Rust: permissive except MPL-2.0 (`cssparser`, `cssparser-macros`, `dtoa-short`, `selectors` via Tauri;
`option-ext` via `directories`) — ship unmodified with notice; `btleplug` MIT/Apache/BSD-3;
`r-efi` used under MIT. Android: AndroidX/Compose/CameraX/kotlinx/ZXing Apache-2.0, Bouncy Castle
(MIT-style). iOS: Apple frameworks only. Not used: ViGEmBus (archived), libvirtualhid (LB-SAL).

## Rollback / uninstall
Protocol 1 is shared by every component, so any single component can be rolled back.
Windows: `nine-devnode remove Root\NineVhf` → `pnputil /delete-driver NineVhf.inf /uninstall`.
Linux: remove `/etc/udev/rules.d/60-9controller-uinput.rules` (or the .deb). Host data lives in the
per-user config dir (`Controller-9`); deleting it forgets every paired phone.
