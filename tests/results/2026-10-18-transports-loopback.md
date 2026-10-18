# 2026-10-18 — transport bindings on loopback (protocol + crypto overhead only)

**Not Wi-Fi, not Bluetooth radio, not USB, not game-input latency.** Same Mac (macOS 27.0.1, Apple
Silicon, 127.0.0.1), host backend = MockBackend.

| Client | Link | Frames | Pings (n) | RTT p50 | RTT p95 |
|---|---|---|---|---|---|
| Rust `controller9 sim` | IP (TCP+UDP) | 600 @ 117.6 Hz | 48 | 0.174 ms | 0.207 ms |
| Rust `controller9 sim` | stream (TCP 47902) | 360 @ 116.0 Hz | 29 | 0.186 ms | 0.244 ms |
| Kotlin (JVM) | IP | 200 | 20 | 0.239 ms | 0.469 ms |
| Kotlin (JVM) | stream | 100 | 10 | 0.277 ms | 0.544 ms |
| Swift (Network.framework) | IP | 200 | 20 | 0.308 ms | 0.417 ms |
| Swift (Network.framework) | stream | 100 | 10 | 0.259 ms | 0.410 ms |

Integration tests (hosts/core/tests/links.rs): IP→stream failover while the throttle was held —
neutral applied when the control link died, same slot after failover (< 3 s asserted), stale frames
from the dead session rejected; takeover with the old link still alive — neutral recorded between
the old and new state. Simulated BLE GATT at ATT MTU 23 and 185: pairing, session, input and pings
all completed through segmentation/reassembly.
