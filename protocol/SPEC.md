# Controller-9 wire protocol — version 1

Normative. Implementations: `protocol/rust` (reference), `protocol/kotlin`, `protocol/swift`.
Byte-exact behaviour is pinned by `protocol/vectors/*.json`; every implementation's test suite
must reproduce them. The v0 probe in `tools/netprobe` is unauthenticated, test-only, and is **not**
part of this protocol.

## 1. Roles and ports

| Role | Who |
|---|---|
| Controller (initiator) | phone app (Android, iOS) or `ninectl sim` |
| Host (responder) | desktop host (macOS, Windows, Linux) |

| Channel | Transport | Default port | Purpose |
|---|---|---|---|
| Control | TCP, `TCP_NODELAY` | 47901 | handshake, pairing, session setup, rumble, bye |
| Input | UDP | 47900 | input frames, ping/pong, feedback |
| Discovery | DNS-SD `_ninectl._tcp.local.` | — | host advertisement |

Discovery TXT keys: `v=1`, `id=<host id>`, `name=<display name>`, `os=macos|windows|linux`,
`slots=<free slots>`, `udp=<input port>`. Manual `host:port` entry and QR are always available.

Host id = lowercase hex of the first 8 bytes of SHA-256(host static public key).

## 2. Control-channel framing

Every control message is `len:u16 big-endian || bytes[len]`, `1 <= len <= 65535`.
A host closes the connection on `len == 0`, on a message larger than 4096 bytes before the
handshake completes, or on any decrypt failure.

## 3. Handshake — `Noise_XX_25519_ChaChaPoly_SHA256`

Exactly the Noise Protocol Framework revision 34 XX pattern:

```
  -> e
  <- e, ee, s, es
  -> s, se
```

* Prologue: ASCII `9ctl/1`.
* Static keys: one long-lived X25519 key per host and per controller install.
* Payloads (UTF-8 JSON, inside the Noise messages):
  * msg1: `{"proto":[1]}` (plaintext, hashed)
  * msg2: `{"proto":1,"name":"Studio PC","id":"<host id>","os":"macos"}`
  * msg3: `{"proto":1,"name":"Pixel 9","platform":"android","app":"1.0.0"}`
* After msg3 both sides `Split()`: `c1` encrypts controller→host, `c2` host→controller. Every
  subsequent control message is a Noise transport message (AEAD, empty AD, implicit nonce).
* `h` = final handshake hash (32 bytes); it binds pairing proofs and the SAS to this session.

If the host does not support any listed `proto` version, it completes the handshake and then sends
`{"t":"error","code":"unsupported_version"}` and closes.

## 4. Control messages (encrypted JSON, field `t`)

Controller → host

| `t` | Fields | Meaning |
|---|---|---|
| `open` | `resumeSlot?: 0..3`, `profile?: string` | request a session |
| `pair_qr` | `proof: b64` | QR pairing proof, see §5.1 |
| `pair_code` | — | start numeric-comparison pairing, see §5.2 |
| `nonce` | `n: b64(16)` | pairing nonce Na |
| `code_ok` / `code_reject` | — | user verdict on the phone |
| `stats` | `rttP50Ms?`, `rttP95Ms?`, `sensorHz?`, `sendHz?`, `batteryPct?` | controller diagnostics, ~every 2 s; display only |
| `bye` | — | clean disconnect |

Host → controller

| `t` | Fields |
|---|---|
| `session` | `slot`, `sessionId:u32`, `udpPort`, `kIn:b64(32)`, `kOut:b64(32)`, `heartbeatMs`, `idleHeartbeatMs`, `timeoutMs` |
| `pair_required` | `modes: ["qr","code"]` |
| `commit` | `c: b64(32)` |
| `reveal` | `n: b64(16)` |
| `paired` | — |
| `pair_failed` | `reason` |
| `rumble` | `low:0..65535`, `high:0..65535`, `ms:0..5000` |
| `error` | `code`, `message?` |
| `bye` | `reason?` |

Error codes: `unsupported_version`, `slots_full`, `not_paired`, `pairing_closed`, `rate_limited`,
`bad_proof`, `bad_message`, `backend_unavailable`.

## 5. Pairing

A controller is *paired* when the host has stored its static public key and the controller has
stored the host's. Unpaired controllers get `pair_required` in reply to `open`.

### 5.1 QR

The host shows a QR code containing

```
ninectl://pair?v=1&a=<ip>&p=<tcp port>&k=<b64url host static pub>&t=<b64url 16-byte token>&n=<name>
```

The token is random, one-time, and expires after 5 minutes. After the handshake the controller
**aborts unless the host static key equals `k`**, then sends `pair_qr` with

```
proof = HMAC-SHA256(key = token, msg = "9ctl-qr" || h)
```

The host compares in constant time against every live token, consumes it, stores the controller,
and replies `paired`.

### 5.2 Numeric comparison ("code pairing")

Only while the host is in pairing mode (opened by the user, 120 s). Commitment ordering follows
Bluetooth LE Secure Connections numeric comparison so that a man-in-the-middle cannot grind codes:

1. controller → `pair_code`
2. host picks `Nb` (16 random bytes) → `commit` `c = SHA-256("9ctl-commit" || h || Nb)`
3. controller picks `Na` (16 random bytes) → `nonce` `Na`
4. host → `reveal` `Nb`; controller verifies `c` and aborts on mismatch
5. both display `code = (u32be(SHA-256("9ctl-sas" || h || Na || Nb)[0..4]) mod 1000000)` as 6
   digits `123 456`
6. the user confirms on **both** devices; the controller sends `code_ok`, the host user clicks
   *Allow*. Only when both happen does the host store the key and send `paired`. Either side may
   reject; the host then sends `pair_failed`.

A static PIN typed on the phone is deliberately not offered: without a PAKE an active attacker
impersonating the host learns enough to brute-force it offline.

### 5.3 Limits

* Max 5 pairing attempts per 60 s per host; 3 rejected codes lock pairing for 60 s.
* Max 8 concurrent control connections; max 20 handshakes per 10 s per source address.
* Unpaired connections are closed after 30 s without completing pairing.

## 6. Input channel (UDP)

### 6.1 Datagram

```
offset size
0      2    magic "9C"
2      1    version = 1
3      1    type
4      4    sessionId   u32 LE
8      8    counter     u64 LE   (strictly increasing per direction, starts at 1)
16     n    ciphertext  ChaCha20-Poly1305(key, nonce, plaintext, aad = bytes[0..16])
16+n   16   tag
```

`nonce = 00 00 00 00 || counter (u64 LE)` (12 bytes). Controller→host uses `kIn`, host→controller
uses `kOut`. Keys are 32 random bytes generated by the host per session and delivered in
`session`; they never leave the encrypted control channel and are never logged.

### 6.2 Types and plaintext payloads (all little-endian)

| type | name | payload |
|---|---|---|
| 1 | input | `t_us:u64, buttons:u32, lx:i16, ly:i16, rx:i16, ry:i16, lt:u16, rt:u16, flags:u16` (26 bytes) |
| 2 | ping | `t_us:u64, id:u32` (12) |
| 3 | pong | `t_us:u64, id:u32, host_rx_us:u64, host_tx_us:u64` (28) |
| 4 | feedback | `low:u16, high:u16, ms:u16` (6) |
| 5 | bye | empty |

* Axes: `-32767..=32767`; `-32768` is invalid. Triggers `0..=65535`. Y axes are positive *up*.
* `t_us`: sender monotonic clock in microseconds (not wall time).
* `flags`: bit0 motion steering active, bit1 calibrating, bit2 app paused (host neutralizes).
  Other bits must be 0.

### 6.3 Buttons (bit index)

| bit | button | bit | button |
|---|---|---|---|
| 0 | A (south) | 8 | left stick click |
| 1 | B (east) | 9 | right stick click |
| 2 | X (west) | 10 | D-pad up |
| 3 | Y (north) | 11 | D-pad down |
| 4 | LB | 12 | D-pad left |
| 5 | RB | 13 | D-pad right |
| 6 | View / Back | 14 | Guide (host may ignore) |
| 7 | Menu / Start | 15–31 | reserved, must be 0 |

### 6.4 Receiver rules

A host drops a datagram silently (and counts it) unless all hold, in this order:
1. length ≥ 32, magic and version match, type known;
2. `sessionId` maps to a live session;
3. AEAD decryption succeeds with that session's key;
4. `counter` > highest accepted counter for the session (**stale/duplicate/replay rejection**:
   input frames carry full state, so an older frame is never applied);
5. payload length exact; field bounds per §6.2/§6.3.

Counter gaps are counted as loss. RFC 3550 jitter is computed from `t_us` and host receive time.
The reply address is the source of the latest authenticated datagram (roaming allowed).

### 6.5 Timers

| Timer | Default | Rule |
|---|---|---|
| active heartbeat | 50 ms | controller sends input at least this often while any control is non-neutral |
| idle heartbeat | 200 ms | while fully neutral |
| input timeout | 250 ms | host sets the slot to neutral (all buttons up, axes 0) when no valid input arrives |
| reconnect grace | 30 s | slot and virtual device are kept for a reconnecting controller (same static key) |
| ping | 1 s | controller pings; host answers with pong |

On `bye`, TCP close, or session end the host neutralizes immediately. Controllers send input
on change (up to the sampling rate) plus heartbeats.

## 7. Slots

A host offers up to 4 slots (backend permitting). Slot = player index = one virtual gamepad.
`open.resumeSlot` is honoured if that slot is free or held in grace for the same controller key.

## 8. Versioning

`proto` integers are negotiated in the handshake payloads. Datagram `version` must equal the
negotiated value. Additive control-message fields must be ignored by older receivers; anything
else requires a new `proto` number.
