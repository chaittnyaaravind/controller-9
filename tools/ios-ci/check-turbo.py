#!/usr/bin/env python3
"""Oracle for TurboUITests: count separate A presses the host received while the test held A for 1.5 s.

    tools/ios-ci/check-turbo.py <xcodebuild-test.log> <host.log>
"""
import json, re, sys
steps = dict((m[1], float(m[2])) for m in re.finditer(r"STEP (\S+) ([0-9.]+)", open(sys.argv[1]).read()))
t0, t1 = steps["turbo-hold"], steps["done"]
states = []
for line in open(sys.argv[2], errors="replace"):
    i = line.find("input: {")
    if i >= 0:
        d = json.loads(line[i + 7:])
        if t0 <= d["ms"] / 1000 < t1: states.append(d["state"]["buttons"] & 1)
presses = sum(1 for a, b in zip([0] + states, states) if a == 0 and b == 1)
ok = presses >= 12 and (not states or states[-1] == 0)
print(f"{'PASS' if ok else 'FAIL'}: {presses} separate A presses during a 1.5 s hold (turbo ~12/s), ends released: {not states or states[-1] == 0}")
sys.exit(0 if ok else 1)
