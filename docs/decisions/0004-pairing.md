# ADR 0004 — QR pairing and numeric comparison; no typed static PIN

**Status:** accepted (2026-10-17)

**Decision.** Two pairing modes after an XX handshake:
1. **QR** — carries the host's static key and a 128-bit one-time token; the phone aborts unless the
   handshake's host key matches, then proves token knowledge with `HMAC(token, "9ctl-qr" || h)`.
2. **Code (numeric comparison)** — Bluetooth LE Secure Connections style: the host commits to its
   nonce before seeing the phone's, so a man-in-the-middle cannot grind a matching 6-digit code.
   The user confirms the code on both devices.

**Rejected.** A 6-digit PIN typed on the phone and mixed into a MAC: an attacker impersonating the
host receives a MAC under a key it knows and can brute-force 10^6 PINs offline. A PAKE (SPAKE2/CPace)
would fix that but has no maintained implementation across Kotlin, Swift and Rust.
