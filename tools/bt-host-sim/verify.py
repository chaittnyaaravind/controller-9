#!/usr/bin/env python3
"""Checks one bt-host-sim run. Usage: verify.py <sdl.jsonl> [<remote.jsonl>]"""
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip().startswith("{")]
head = next((r for r in rows if "controller" in r), None)
assert head, f"SDL saw no game controller: {rows[:6]}"
downs = [r["button"] for r in rows if r.get("down") == 1]
want = ["a", "b", "x", "y", "leftshoulder", "rightshoulder", "back", "start", "leftstick", "rightstick", "dpup", "dpdown", "dpleft", "dpright", "guide"]
assert downs[:len(want)] == want, f"SDL button order {downs}"
axes = {(r["axis"], r["v"] > 0) for r in rows if "axis" in r}
for a in ["leftx", "lefty", "rightx", "righty"]:
    assert (a, True) in axes and (a, False) in axes, (a, sorted(axes))
assert ("lefttrigger", True) in axes and ("righttrigger", True) in axes, sorted(axes)
print(f"SDL {head['sdl']}: '{head['controller']}' (type {head['type']}, rumble={head['rumble']}): all 15 buttons in order, all axes both ways, both triggers")
if len(sys.argv) > 2:
    rem = [json.loads(l) for l in open(sys.argv[2]) if l.strip().startswith("{")]
    seen = next(r["remote_seen"] for r in rem if "remote_seen" in r)
    devs = next(r["devices"] for r in rem if "devices" in r)
    print("remote input devices:", [d["name"] for d in devs])
    for k in ["KEY_A", "REL_X", "KEY_VOLUMEUP"]:
        assert k in seen, (k, seen)
    print("Remote keys: keyboard (KEY_A), mouse (REL_X) and media (KEY_VOLUMEUP) all became input events")
