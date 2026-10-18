# ADR 0001 — One Rust host core for macOS, Windows and Linux

**Status:** accepted (2026-10-17)

**Context.** Three desktop hosts must implement identical pairing, crypto, session, timeout,
slot and diagnostic behaviour. Divergence between hosts is a correctness and security risk.

**Decision.** A Cargo workspace with `hosts/core` (all shared behaviour) and one small backend crate
per OS (`hosts/linux`, `hosts/macos`, `hosts/windows`) implementing `nine_host_core::Backend`.
The CLI (`hosts/cli`) and desktop app (`hosts/app`) only wire a backend to the core.

**Consequences.** Shared behaviour is tested once on any OS (e2e tests over loopback with the mock
backend). OS backends are thin and testable in isolation. Cross-target `cargo check` verifies the
Linux and Windows code compiles from macOS; running it still needs those OSes.
