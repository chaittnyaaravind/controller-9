# Release checklist, licences and rollback

## Before any release
- [ ] `tests/run_all.sh` green; Stage 4 acceptance rows for the shipped platforms recorded in `tests/results/`.
- [ ] Project licence chosen by the owner (Cargo metadata currently says Apache-2.0 as a placeholder — **confirm**).
- [ ] pid.codes product id allocated; replace placeholder `0x9C01` in `hosts/macos/src/iohid.rs` and `NineVhf.c`.
- [ ] Android: release keystore (outside the repo), `assembleRelease`, `apksigner verify`, R8 mapping archived.
- [ ] iOS: team + bundle id, archive, export compliance (`ITSAppUsesNonExemptEncryption=false` assumes
      only standard encryption — confirm with counsel before App Store submission).
- [ ] macOS: Developer ID signing, notarization; virtual-HID entitlement only if granted.
- [ ] Windows: driver attestation-signed via Partner Center; installer signed (EV/OV cert).
- [ ] Linux: `.deb` installs udev rule; AppImage documented.
- [ ] Third-party notices bundled (below).

## Third-party licences (checked 2026-10-17)
- Rust: 493 crates — permissive (MIT/Apache-2.0/BSD/Zlib/Unicode/Unlicense) except **MPL-2.0**:
  `cssparser`, `cssparser-macros`, `dtoa-short`, `selectors` (via Tauri) and `option-ext` (via
  `directories`). MPL-2.0 is file-level copyleft: ship unmodified, include the licence and a
  source pointer. `r-efi` is MIT/Apache/LGPL — used under MIT.
- Android: AndroidX/Compose/CameraX/kotlinx (Apache-2.0), ZXing core (Apache-2.0), Bouncy Castle
  (MIT-style Bouncy Castle licence).
- iOS: Apple frameworks only.
- Not used: ViGEmBus (archived), libvirtualhid (LB-SAL 1.0 — would need per-machine licences).

Generate the Rust notice file: `cargo install cargo-about && cargo about generate about.hbs > THIRD_PARTY.html`.

## Rollback
- Apps and hosts are versioned independently but speak `proto: 1`; rolling back any one component
  within protocol 1 is safe.
- Hosts keep identity and pairings in the per-user config dir; reinstalling an older host keeps them.
- Windows driver: `nine-devnode remove Root\NineVhf` → `pnputil /delete-driver NineVhf.inf /uninstall`
  → install the previous package. The host's file-handle cleanup guarantees no stuck input.
- Linux: remove the udev rule to revoke non-root uinput access.
- A protocol bump (proto 2) must keep accepting proto 1 for at least one release.
