# ADR 0005 — Android module structure and dependency versions

**Status:** accepted (2026-10-17)

**Decision.** `:app` (Compose UI, data, net, input packages following NowInAndroid layering inside
one module) plus `:protocol` — the shared pure-Kotlin module that lives in `protocol/kotlin` so its
tests run on the JVM against the shared vectors and a live Rust host.

**Versions.** The installed android skill pins Kotlin 1.9 and a 2024 Compose BOM; they are stale.
We pin current stable releases verified against Google Maven / Maven Central on 2026-10-17:
AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, Compose BOM 2026.09.00, compileSdk 37, targetSdk 36,
minSdk 26. Crypto uses Bouncy Castle 1.86 (MIT) lightweight APIs for identical behaviour on all API
levels. `targetSdk` stays 36 until API 37 behaviour changes are tested on a device.
