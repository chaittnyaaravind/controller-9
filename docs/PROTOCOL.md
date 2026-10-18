# Protocol overview

Normative spec: [`protocol/SPEC.md`](../protocol/SPEC.md) (v1). Profiles: [`protocol/PROFILE.md`](../protocol/PROFILE.md).
Byte-exact vectors: `protocol/vectors/` — `noise_xx`, `frames` (every packet type + 8 malformed
cases + freshness), `pairing` (incl. multi-address URI), `motion`, `profiles`, `link` (stream records,
BLE segments), `policy`. Rust generates them; Kotlin and Swift test suites reproduce every byte.

| Concern | Definition |
|---|---|
| Endianness | datagram fields little-endian; control/stream lengths big-endian u16 |
| Real-time frame | header `"9C" ver type sessionId:u32 counter:u64` (AAD) + AEAD(t_us:u64, buttons:u32, lx ly rx ry:i16, lt rt:u16, flags:u16) — 58 bytes on the wire |
| Controller/slot identity | `sessionId` is random per session and maps to exactly one slot on the host; it is authenticated (AAD) and keyed — a peer cannot claim another slot. The slot number itself is not repeated in each frame (it would add bytes without adding security) |
| Sequence | `counter` u64, starts at 1, strictly increasing per direction and session; never wraps in practice (2^64 frames); a session must end before `u64::MAX` |
| Time | `t_us` monotonic sender microseconds (not wall clock) |
| Bounds | axes −32767…32767 (−32768 rejected), triggers 0…65535, unknown button/flag bits rejected |
| State model | every input frame carries the full state, so any single frame recovers it; stale frames are discarded, never applied |
| Fast vs reliable | input/ping/pong/feedback on INPUT; pairing/session/rumble/stats/bye on CONTROL |
| Transport independence | SPEC §8: IP, stream records, BLE GATT segmentation — the same logical channels everywhere |
| Compatibility | `proto` negotiated in the handshake; additive JSON fields are ignored by older peers (`links`, `addrs`, `stats`); anything else needs a new `proto` |
