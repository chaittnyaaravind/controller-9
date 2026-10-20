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

## 8. Transport bindings

The protocol has two **logical channels**, independent of the physical link:

* **CONTROL** — reliable, ordered frames (the Noise handshake messages, then Noise transport
  messages). Each frame is 1..65535 bytes.
* **INPUT** — unreliable, unordered datagrams exactly as in §6.1 (same keys, counters and
  freshness rules). Receivers never assume ordering or delivery.

A binding maps both channels onto one physical link. Security is end-to-end Noise + AEAD on every
binding; link-level pairing (Bluetooth bonding, Wi-Fi passwords) is never relied upon.

### 8.1 IP binding (Wi-Fi/LAN, hotspot, Wi-Fi Direct group, USB tethering, Ethernet)
CONTROL = TCP (§2 framing, port 47901). INPUT = UDP (port 47900). Works on any IP interface the
OS exposes, without Internet access. The pairing URI may list extra addresses in `x`
(comma-separated, percent-encoded); clients try `a` and every `x` address in parallel and keep the
first that completes the handshake.

### 8.2 Stream binding (any reliable byte stream: TCP-only networks, Bluetooth RFCOMM, USB accessory)
One byte stream carries records `kind:u8 | len:u16 BE | bytes[len]`, `1 <= len <= 65535`:

| kind | payload |
|---|---|
| 0x01 | one CONTROL frame (without the §2 length prefix) |
| 0x02 | one INPUT datagram (§6.1), either direction |

Unknown kinds close the link. A sender must not queue more than one pending input record: when the
stream is congested it replaces the queued input datagram with the newest one (latest-state
semantics), so delay never accumulates. Coalescing may drop superseded input datagrams but must never reorder
datagrams: all types share one counter, and a reordered frame would be rejected as stale. TCP stream
binding: port 47902.

### 8.3 BLE GATT binding
Roles: **phone = GATT server (peripheral)**, **host = GATT client (central)**. This is the only
role assignment available on every target (iOS cannot be a host-side central for arbitrary phones
in the background, and Windows/Linux/macOS hosts all support the central role).

| UUID | Name | Properties | Direction |
|---|---|---|---|
| `c9c90001-1c3a-4e9d-8f2b-6c7a0b5d4e10` | service | — | — |
| `c9c90002-1c3a-4e9d-8f2b-6c7a0b5d4e10` | control_up | notify | phone → host |
| `c9c90003-1c3a-4e9d-8f2b-6c7a0b5d4e10` | control_down | write (with response) | host → phone |
| `c9c90004-1c3a-4e9d-8f2b-6c7a0b5d4e10` | input_up | notify | phone → host |
| `c9c90005-1c3a-4e9d-8f2b-6c7a0b5d4e10` | input_down | write without response | host → phone |

Advertising: the service UUID, plus a 4-byte `hint` = the first 4 bytes of the target host id (all
zero = "any host in pairing mode"), carried as **service data** in the scan response (Android: a
128-bit UUID and its service data do not fit one 31-byte legacy advertisement) **or** as the local
name `C9-<hint as 8 lowercase hex digits>` (iOS peripherals cannot advertise service data). Hosts
accept either and connect only to matching hints.

**Segmentation.** Each characteristic value is `hdr:u8 | chunk`, with `hdr` bit 7 = FIRST, bit 6 =
LAST, bits 0–5 = segment counter (mod 64, continuous per characteristic). A message is one CONTROL
frame (control_*) or one INPUT datagram (input_*). The receiver discards a partial message on a
counter gap or a FIRST without a preceding LAST. Chunk size = negotiated ATT MTU − 3 − 1.
A 58-byte input datagram fits one notification when the ATT MTU ≥ 62; hosts request MTU 185.

The phone starts the Noise handshake (msg1 on control_up) after the host subscribes to both
notify characteristics. BLE is a fallback: input is coalesced to the latest state per connection
event; achieved rates are measured per pair (TEST_PLAN), never assumed.

### 8.4 Link selection and failover
In single-link mode clients use one link at a time per host (redundant mode: §8.5). On failover
the client opens a new session on the next link (new handshake, new session keys, `resumeSlot`);
the host's slot takeover rule (§7) neutralizes the slot before the new session's first frame, and
the old session's datagrams are rejected because its session id is gone. Policy:
`docs/CONNECTION_MANAGER.md`; reference implementation `protocol/rust/src/policy.rs`.

`HostHello` may carry `"links": ["ip","stream","ble"]` and `"addrs": ["192.168.1.20", …]` (the host's
local IPv4 addresses) — additive; older peers ignore them.

### 8.5 Several links in one session (redundant mode)
A host that sends `"multipath": true` in its hello accepts `open` with `join: <sessionId>` on a second
link. The joining link runs its own complete Noise XX handshake; the host accepts the join only if that
handshake's controller static key owns the live session `sessionId` (pairing and pinning therefore
apply to every link), and answers with the **same** `session` message (slot, session id, keys). The
slot, virtual pad, held input and counters are untouched. A session may have up to 4 links.

* The controller seals each datagram once, with one counter for the whole session, and may send the
  same bytes over every link. Pings are per link (each link's pong comes back over that link).
* Receivers replace rule 4 of §6.4 with a 64-counter replay window: each counter is processed at most
  once (later copies are *duplicates*, dropped and counted per link), a counter 64 or more below the
  newest is refused, and an **input** frame is applied only if its counter is greater than the last
  applied input frame's, so a late copy can never restore an older state. For a single link this is
  equivalent to the old rule for input; out-of-order pings are now answered instead of dropped.
* A link ending removes only that link. The session ends (neutralize, reconnect grace) when its last
  link ends. The 250 ms input timeout applies to the session: input from any link keeps it fresh.
* `bye` (control or datagram) ends the whole session. To retire one link, close it.
* Host feedback (`feedback` datagrams) is sealed once and sent over every link; `rumble` control
  messages go over one link.
* Each link queues independently and never waits for another. A new input frame may replace queued
  input frames that carried only analog changes; frames with a button, pedal (zero ↔ non-zero) or flag
  change keep their place, and order is never changed.

## 9. Versioning

`proto` integers are negotiated in the handshake payloads. Datagram `version` must equal the
negotiated value. Additive control-message fields must be ignored by older receivers; anything
else requires a new `proto` number.
