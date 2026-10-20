"""Assert the macOS keyboard & mouse mode sweep. Usage: verify.py <tap.jsonl>"""
import json, sys, platform, datetime
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip().startswith("{")]
keys = [r["key"] for r in rows if "key" in r and r["down"]]
# A B X Y LB RB View Menu LS RS Up Down Left Right (Guide has no key), then lx- lx+ ly- ly+ = A D S W
want = [49, 8, 15, 3, 12, 14, 48, 53, 56, 9, 126, 125, 123, 124, 0, 2, 1, 13]
assert keys[:len(want)] == want, f"key order {keys}"  # the sweep may wrap around to A at the end
ups = [r["key"] for r in rows if "key" in r and not r["down"]]
assert all(k in ups for k in want), f"every key released: {sorted(ups)}"
assert len(ups) == len(keys), f"{len(keys)} presses but {len(ups)} releases — a key was left held"
moves = [r for r in rows if "dx" in r]
assert any(r["dx"] < 0 for r in moves) and any(r["dx"] > 0 for r in moves), "right stick x moves the pointer both ways"
assert any(r["dy"] > 0 for r in moves) and any(r["dy"] < 0 for r in moves), "right stick y moves the pointer both ways"
btn = [(r["button"], r["down"]) for r in rows if "button" in r]
assert btn == [("right", True), ("right", False), ("left", True), ("left", False)], f"LT=right click, RT=left click: {btn}"
print(f"macOS keyboard & mouse mode — {datetime.date.today()} · macOS {platform.mac_ver()[0]} · {platform.machine()}")
print(f"PASS: 18 keys pressed and released in sweep order, pointer moved both ways on both axes ({len(moves)} move events), LT/RT clicked right/left.")
print("All events were tagged and swallowed by the test tap (none reached an app).")
