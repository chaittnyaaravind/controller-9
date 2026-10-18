#!/usr/bin/env python3
"""Phase 0 network probe for Controller-9 (protocol v0, UNAUTHENTICATED - test only).

Proves that input frames carry observable sequence numbers and timestamps, and
that the receiver can measure rate, loss, stale/duplicate discards, jitter, RTT
and stale-input timeout (release to neutral).

  python3 netprobe.py recv --port 47900 --duration 12
  python3 netprobe.py send --host 127.0.0.1 --port 47900 --rate 120 --loss 0.05 --duration 10

v0 frame, little-endian, 40 bytes:
  magic "N9" | ver u8 | type u8 (1 input, 2 ping, 3 pong) | session u32 | seq u32 |
  t_us u64 (sender monotonic) | buttons u32 | lx ly rx ry lt rt steer i16 | flags u16
"""
import argparse
import math
import random
import select
import socket
import statistics
import struct
import time

FMT = struct.Struct("<2sBBIIQI7hH")
MAGIC, VERSION = b"N9", 0
T_INPUT, T_PING, T_PONG = 1, 2, 3
NEUTRAL_TIMEOUT_S = 0.25


def now_us() -> int:
    return time.monotonic_ns() // 1000


def pct(values, p):
    if not values:
        return float("nan")
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


def recv(args):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", args.port))
    sock.settimeout(0.05)
    session = None
    first_seq = last_seq = None
    received = stale = invalid = 0
    jitter = 0.0
    prev_transit = None
    gaps = []
    last_rx = None
    held = False
    neutral_resets = 0
    end = time.monotonic() + args.duration
    print(f"recv: listening on udp/{args.port}")
    while time.monotonic() < end:
        try:
            data, addr = sock.recvfrom(256)
        except socket.timeout:
            data = None
        t_rx = now_us()
        if data is None:
            if held and last_rx and (t_rx - last_rx) / 1e6 > NEUTRAL_TIMEOUT_S:
                held = False
                neutral_resets += 1
                print(f"recv: no input for {NEUTRAL_TIMEOUT_S*1000:.0f} ms -> buttons released, axes neutral")
            continue
        if len(data) != FMT.size:
            invalid += 1
            continue
        magic, ver, typ, sess, seq, t_us, buttons, *axes, flags = FMT.unpack(data)
        if magic != MAGIC or ver != VERSION:
            invalid += 1
            continue
        if typ == T_PING:
            sock.sendto(FMT.pack(MAGIC, VERSION, T_PONG, sess, seq, t_us, 0, *([0] * 7), 0), addr)
            continue
        if typ != T_INPUT:
            invalid += 1
            continue
        if session is None:
            session = sess
        if sess != session or axes[4] < 0 or axes[5] < 0:  # triggers are 0..32767
            invalid += 1
            continue
        if last_seq is not None and seq <= last_seq:
            stale += 1  # late or duplicate: never apply out-of-order state
            continue
        if first_seq is None:
            first_seq = seq
        last_seq = seq
        received += 1
        if last_rx is not None:
            gaps.append((t_rx - last_rx) / 1000)
        last_rx = t_rx
        held = buttons != 0 or any(axes)
        # RFC 3550 interarrival jitter; the sender/receiver clock offset cancels out.
        transit = t_rx - t_us
        if prev_transit is not None:
            jitter += (abs(transit - prev_transit) - jitter) / 16
        prev_transit = transit
    expected = (last_seq - first_seq + 1) if first_seq is not None else 0
    lost = expected - received
    print("recv summary:")
    print(f"  frames applied {received}, expected {expected}, lost {lost} ({100*lost/max(expected,1):.1f}%), "
          f"stale/dup discarded {stale}, invalid {invalid}")
    print(f"  inter-arrival ms p50 {pct(gaps,50):.2f}  p95 {pct(gaps,95):.2f}  p99 {pct(gaps,99):.2f}")
    print(f"  RFC3550 jitter {jitter/1000:.3f} ms   neutral resets {neutral_resets}")


def send(args):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setblocking(False)
    session = random.getrandbits(32)
    period = 1 / args.rate
    seq = 0
    sent = dropped = 0
    rtts = []
    pending = {}
    start = time.monotonic()
    next_t = start
    next_ping = start
    while (t := time.monotonic()) - start < args.duration:
        # Fake sensor: smooth steering sweep, button A pressed every other second.
        steer = int(32767 * math.sin(2 * math.pi * 0.5 * (t - start)))
        buttons = 1 if int(t - start) % 2 == 0 else 0
        seq += 1
        frame = FMT.pack(MAGIC, VERSION, T_INPUT, session, seq, now_us(), buttons,
                         steer, 0, 0, 0, 0, 20000, steer, 0)
        if random.random() < args.loss:
            dropped += 1  # simulated network loss
        else:
            sock.sendto(frame, (args.host, args.port))
            sent += 1
        if t >= next_ping:
            pseq = seq
            pending[pseq] = now_us()
            sock.sendto(FMT.pack(MAGIC, VERSION, T_PING, session, pseq, pending[pseq], 0, *([0] * 7), 0),
                        (args.host, args.port))
            next_ping += 0.1
        next_t += period
        # Wait on the socket (not sleep) so pongs are timestamped on arrival, not at the next frame tick.
        while (remaining := next_t - time.monotonic()) > 0:
            if not select.select([sock], [], [], remaining)[0]:
                break
            try:
                _, _, typ, _, pseq, t_us, *_ = FMT.unpack(sock.recv(256))
            except (BlockingIOError, struct.error):
                continue
            if typ == T_PONG and pending.pop(pseq, None) is not None:
                rtts.append((now_us() - t_us) / 1000)
    elapsed = time.monotonic() - start
    print("send summary:")
    print(f"  session {session:08x}  frames {seq} in {elapsed:.2f}s ({seq/elapsed:.1f} Hz), "
          f"sent {sent}, simulated drops {dropped}")
    print(f"  RTT ms n={len(rtts)}  p50 {pct(rtts,50):.3f}  p95 {pct(rtts,95):.3f}  "
          f"p99 {pct(rtts,99):.3f}  max {max(rtts, default=float('nan')):.3f}"
          + (f"  mean {statistics.mean(rtts):.3f}" if rtts else ""))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("recv")
    r.add_argument("--port", type=int, default=47900)
    r.add_argument("--duration", type=float, default=12)
    s = sub.add_parser("send")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=47900)
    s.add_argument("--rate", type=float, default=120)
    s.add_argument("--loss", type=float, default=0.0)
    s.add_argument("--duration", type=float, default=10)
    a = ap.parse_args()
    recv(a) if a.cmd == "recv" else send(a)
