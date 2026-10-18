# Controller-9 — guide for coding agents

- Read `docs/PLAN.md`, `docs/REQUIREMENTS.md` and `protocol/SPEC.md` before changing behaviour.
- The protocol is normative: change `SPEC.md` + `protocol/rust` first, regenerate vectors
  (`cargo run -p nine-proto --bin gen-vectors`), then update Kotlin and Swift until their vector
  tests pass. Never edit vectors by hand.
- Run `tests/run_all.sh` before committing. Small, coherent commits.
- Never claim a platform works without running it; update `docs/SUPPORT_MATRIX.md` with evidence.
- Never log or print keys; use the redacting types. No telemetry, no cloud, no new permissions
  without documenting them in `docs/platforms/*`.
- `tools/netprobe` is the unauthenticated v0 probe — test-only, never production.
- Toolchain paths on the dev Mac: Rust `/opt/homebrew/opt/rustup/bin`, JDK `/opt/homebrew/opt/openjdk@21`,
  Android SDK `/opt/homebrew/share/android-commandlinetools`. Swift tests: `tools/swift-test.sh <pkg>`.
