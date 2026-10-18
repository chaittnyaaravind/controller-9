# Security model

## Assets
- Controller and host **static X25519 keys** (identity; pairing depends on them).
- **Session keys** (`kIn`, `kOut`) and Noise transport keys.
- The ability to **inject input** into the host computer (equivalent to a plugged-in gamepad).

## Adversary
Anyone on the same LAN: can sniff, inject, replay, delay and drop packets, impersonate hosts during
discovery, and connect to the host's ports. Out of scope: a compromised phone or host OS, physical
access to an unlocked device, and malicious games.

## Controls (where implemented)
| Threat | Control | Code | Test |
|---|---|---|---|
| Eavesdropping | Noise XX + ChaCha20-Poly1305 on every byte after msg1 | `protocol/*/noise*`, `frame*` | vectors + `snow` interop |
| Tampering (header or body) | AEAD; header is AAD | `frame.rs::open` | every-bit-flip test |
| Replay / stale frames | strictly increasing counters, checked after auth | `Freshness` | e2e `replay_stale_tamper…` |
| Rogue host (MITM) | QR pins host key; code mode uses nonce commitment + SAS | `pairing.rs`, clients | `qr_pairing_rejects_wrong_host…` |
| Host key changes after pairing | clients pin `pub` and refuse with an explicit message | Android/iOS `ConnectionManager` | needs device test |
| Rogue controller | must pair: QR token (one-time, 5 min) or host-side Allow | `hosts/core/src/pairing.rs` | `host_rejection_fails_pairing…` |
| Brute force / DoS | 5 attempts/min, 3 rejects → 60 s lock, ≤8 connections, ≤20 handshakes/10 s/IP, 5 s handshake timeout, 30 s unpaired timeout, 4096-byte pre-handshake frame cap | `host.rs`, `pairing.rs` | unit tests |
| Malformed datagrams | length/magic/version/type checks before any crypto; bounds after | `Header::peek`, `InputFrame::decode` | vectors `invalid[]` |
| Stuck input | 250 ms timeout → neutral; neutral on disconnect, pause, bye, shutdown; Windows driver deletes a crashed host's pads | `tick`, `end_session`, `NineEvtFileCleanup` | e2e `silence_neutralizes…` |
| Secret leakage | `SecretB64` / redacted `Debug`, `toString`, `description`; keys never logged | control types | redaction tests |
| Key storage | host: per-user config dir, files 0600, dir 0700; Android: AES-GCM wrapped by a non-exportable Keystore key, excluded from backup; iOS: Keychain `ThisDeviceOnly`, not synchronizable | stores | Rust store test; device tests pending |

## Residual risks (documented, not hidden)
- **Windows driver ACL** lets any interactive-user process create pads (`IU` read/write in the INF),
  as any USB gamepad would. Tighten to a service SID if a broker service is added.
- **Host key file on Windows** relies on the user-profile ACL (no DPAPI wrapping yet).
- **Unauthenticated discovery** (DNS-SD TXT) can be spoofed; it is only a hint — the QR key pin or
  the numeric comparison decides trust.
- The **phone's TCP control channel has no keepalive** beyond UDP ping health checks; a silently
  dead TCP connection is detected via missing pongs within 1 s.
- **Anti-cheat**: some games refuse virtual devices. We do not attempt to evade that.

## Reporting
Security issues: open a private advisory on the project repository (to be created) rather than a
public issue. No telemetry is collected, so we cannot see attacks — reports matter.
