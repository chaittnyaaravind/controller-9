#!/usr/bin/env python3
"""Oracle for ControllerAccuracyUITests: match each "STEP <name> <unix-seconds>" line of the test log
against the host's `--trace-input` lines ("input: {json}") and check that each step sent its own input
and nothing else.

    tools/ios-ci/check-trace.py <xcodebuild-test.log> <host.log>
"""
import json, re, sys

BITS = ["A", "B", "X", "Y", "LB", "RB", "View", "Menu", "LS", "RS", "up", "down", "left", "right", "Guide"]
EXPECT = {b: ("btn", b) for b in ["A", "B", "X", "Y", "LB", "RB", "View", "Menu", "Guide", "LS", "RS"]}
EXPECT.update({"LT": ("axis", "lt"), "RT": ("axis", "rt"), "dpad-left-high": ("btn", "left"), "dpad-up": ("btn", "up"),
               "dpad-right": ("btn", "right"), "dpad-down": ("btn", "down"), "face-gap": ("none", None),
               "stick-left-right": ("axis", "lx"), "stick-right-right": ("axis", "rx")})

steps = [(m[1], float(m[2])) for m in re.finditer(r"STEP (\S+) ([0-9.]+)", open(sys.argv[1]).read())]
seen = {}
steps = [s for s in steps if not (s[0] in seen or seen.setdefault(s[0], 1) and False)]  # xcodebuild repeats lines
inputs = []
for line in open(sys.argv[2], errors="replace"):
    i = line.find("input: {")
    if i >= 0:
        d = json.loads(line[i + 7:]); inputs.append((d["ms"] / 1000, d["state"]))

def active(st):
    out = {BITS[i] for i in range(len(BITS)) if st["buttons"] >> i & 1}
    for k in ("lt", "rt"):
        if st[k] > 0: out.add(k)
    for k in ("lx", "ly", "rx", "ry"):
        if abs(st[k]) > 3000: out.add(k + ("+" if st[k] > 0 else "-"))
    return out

fails = 0
for (name, t0), (_, t1) in zip(steps, steps[1:]):
    if name not in EXPECT: continue
    got = set().union(*[active(s) for t, s in inputs if t0 <= t < t1]) if any(t0 <= t < t1 for t, _ in inputs) else set()
    kind, want = EXPECT[name]
    if kind == "none": ok = not got; exp = "nothing"
    elif kind == "btn": ok = got == {want}; exp = want
    else: ok = got == ({want} if want in ("lt", "rt") else {want + "+"}); exp = want if want in ("lt", "rt") else want + "+"
    fails += not ok
    print(f"{'PASS' if ok else 'FAIL'}  {name:18} expected {exp:8} got {sorted(got) or 'nothing'}")
n = sum(1 for s, _ in steps if s in EXPECT)
print(f"\n{n - fails}/{n} steps PASS")
sys.exit(1 if fails or n == 0 else 0)
