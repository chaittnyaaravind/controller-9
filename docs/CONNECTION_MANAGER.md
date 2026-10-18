# Connection manager

Implemented on Android (`apps/android/.../net/ConnectionManager.kt`) and iOS
(`apps/ios/Core/Sources/NineAppCore/ConnectionManager.swift`); the decision function is shared:
reference `protocol/rust/src/policy.rs`, ports `Policy.kt` / `Policy.swift`, pinned by
`protocol/vectors/policy.json` (9 scenarios; all three languages agree).

## Candidates
Each link reports `available` + a human reason when not (Bluetooth off, permission missing, no
address known, host doesn't offer it, not on that kind of network). IP links are one *attempt*: the
phone races every known host address (QR `x` list + `HostHello.addrs`) and labels the winner by the
interface it used (Android: interface name/subnet; iOS: NWPath interface type + subnet).

## Scoring (per link, from measurements)
`base` (USB 100, Ethernet 95, LAN/hotspot/Wi-Fi Direct 85, TCP-only 60, BLE 40)
− max(0, RTT p95 − 20 ms) − 4 × loss % − 15 × disconnects in the last minute
− 50 if it failed in the last 10 s − 30 if fewer than half of ≥3 recent connects succeeded.

## Decisions
* **Failover** (current link failed or none): try links in score order with the failed link **last**,
  bounded per-link timeouts (IP 1.5 s, stream 1.5 s, BLE 8 s), backoff 100 ms → 2 s between rounds.
* **Planned switch** (current healthy): only if another link out-scores it by ≥ 25 for ≥ 3 s and the
  current link has been in use ≥ 10 s (anti-flap). Make-before-break: the new session is opened
  first; the host's slot takeover neutralizes, then the old link is closed.
* **Override**: a user-chosen link is used whenever available; otherwise the automatic order applies.
* **Unavailable**: every reason is shown; the app never shows "connected" without a session.

## Safety during transitions
* The host neutralizes a slot when its control channel drops, on takeover by a newer session, on
  `bye`, on the paused flag, and after 250 ms without input (watchdog). Verified by
  `hosts/core/tests/links.rs::failover_ip_to_stream_neutralizes_keeps_slot_and_rejects_old_session`
  and `takeover_while_old_link_alive_neutralizes_before_new_state`.
* Old-link datagrams are rejected (their session id is gone); new-link state applies only from its
  first fresh frame. Clients resend current state immediately after a switch.
* Input is never sent on two links; coalescing drops superseded input frames but never reorders.
* The same controller identity holds at most one slot; a second session takes the slot over (and the
  first is told `bye: replaced…`). Two different identities can never share a slot.

## Watchdog / health
Ping every 250 ms; a link is dead after 1 s without a pong (1.5 s on BLE) or on control-channel
loss. Diagnostics expose: active link + detail, last switch and reason, per-link attempts/successes,
RTT p50/p95, reconnect time, watchdog neutralizations; the host shows per-slot link, loss, stale and
invalid counts, jitter and neutral resets.
