# Acceptance status (2026-10-18)

| Item | Status | Evidence |
|---|---|---|
| Rename to Controller-9 without breaking IDs/protocol | **done** | `docs/NAME_MIGRATION.md`; all suites green after rename; vectors unchanged |
| Protocol byte-compatible Rust/Kotlin/Swift incl. links + policy | **done (automated)** | 7 vector files reproduced by all three; live IP + stream interop Rust↔Kotlin, Rust↔Swift |
| Failover, authentication, sequence protection, safe neutral | **done in integration tests on loopback / simulated BLE** | `hosts/core/tests/{end_to_end,links}.rs`; real-device T6 **UNVERIFIED** |
| ≥1 local transport works without Internet | **UNVERIFIED on devices** (works on loopback; no Internet is used by design) | T1 |
| Verified alternative with Wi-Fi radio off | **partial** — Android→macOS over real BLE with Wi-Fi off: input, RTT, disconnect neutral and reconnect measured at the host core ([results](../tests/results/2026-10-18-android-realme-bluetooth.md)). Mac games still can't see it (entitlement). USB, iOS, Windows, Linux **UNVERIFIED** | T4, T5 |
| Android signed APK/AAB, install-tested | **not done** — unsigned builds only; no device | A1 |
| iOS archive / TestFlight | **blocked** — full Xcode + Apple team | — |
| macOS host in games | **blocked** — Apple entitlement | A7 |
| Windows driver built, signed, OS-visible | **not done** — needs Windows 11 + WDK; signing decision | A5 |
| Linux uinput OS-visible | **not done** — needs a Linux machine | A6 |
| Game compatibility entries | **none claimed** | T10 |
| Download page links only real artifacts | **done** — manifest empty, page shows status only | `site/download/` |
| CI release workflows | **written, never run** | `.github/workflows/` |

Broad public launch criteria (master prompt §J): **not met**. Blockers are hardware access,
platform signing/entitlements, and the device test campaign — not missing code.
