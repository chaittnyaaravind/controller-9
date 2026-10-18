# ADR 0003 — Noise XX + per-session AEAD datagrams

**Status:** accepted (2026-10-17)

**Decision.** Control channel: `Noise_XX_25519_ChaChaPoly_SHA256` (Noise rev. 34) over TCP.
Input channel: ChaCha20-Poly1305 datagrams with explicit 64-bit counters as nonces, under two
random 32-byte keys the host issues per session over the encrypted control channel.

**Why not invent anything.** Noise is a published, analysed framework. Our implementation is
checked against the independent `snow` crate in both roles; the Kotlin and Swift ports reproduce
the Rust output byte-for-byte (`protocol/vectors/noise_xx.json`) and interoperate live.

**Nonce safety.** Each UDP key is used by exactly one sender with a strictly increasing counter that
starts at 1, and keys are never reused across sessions. Receivers accept only counters above the
highest accepted, so replays and stale frames are rejected after authentication.
