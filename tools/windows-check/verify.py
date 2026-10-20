"""Assert the Windows game-visible run: XInput saw every button in order, every axis both ways,
and the rumble a game set on the pad reached the simulated phone. Usage: verify.py <evidence dir>"""
import json, sys
d = sys.argv[1]
rows = [json.loads(l) for l in open(f"{d}/xinput.jsonl", encoding="utf-8-sig") if l.strip().startswith("{")]
sim = [json.loads(l) for l in open(f"{d}/sim.json", encoding="utf-8-sig") if l.strip().startswith("{")][-1]
assert any("connected" in r for r in rows), rows[:5]
downs = [r["button"] for r in rows if r.get("down") == 1]
want = ["a", "b", "x", "y", "lb", "rb", "back", "start", "ls", "rs", "up", "down", "left", "right", "guide"]
assert downs == want, downs
axes = {(r["axis"], r["v"] > 0) for r in rows if "axis" in r}
for a in ["lx", "ly", "rx", "ry"]:
    assert (a, True) in axes and (a, False) in axes, (a, sorted(axes))
assert ("lt", True) in axes and ("rt", True) in axes, sorted(axes)
assert any(r.get("rumble") == "on" for r in rows), "checker never set rumble"
assert sim["feedback_events"] >= 1, sim
sdl = [json.loads(l) for l in open(f"{d}/sdl.jsonl", encoding="utf-8-sig") if l.strip().startswith("{")]
head = next((r for r in sdl if "controller" in r), None)
assert head, sdl[:5]
sdl_downs = [r["button"] for r in sdl if r.get("down") == 1]
sdl_want = ["a", "b", "x", "y", "leftshoulder", "rightshoulder", "back", "start", "leftstick", "rightstick", "dpup", "dpdown", "dpleft", "dpright"]
assert sdl_downs[:len(sdl_want)] == sdl_want, sdl_downs
sdl_axes = {r["axis"] for r in sdl if "axis" in r}
for a in ["leftx", "lefty", "rightx", "righty", "lefttrigger", "righttrigger"]:
    assert a in sdl_axes, (a, sorted(sdl_axes))
sdl_msg = f"SDL {head['sdl']}: '{head['controller']}', rumble={head['rumble']}, buttons in order, guide={'guide' in sdl_downs}, all axes"
print(sdl_msg)
open(f"{d}/result.txt", "a").write(sdl_msg + "\n")
msg = f"XInput: every button in order, all axes both ways, rumble reached the phone ({sim['feedback_events']} events)"
print(msg)
open(f"{d}/result.txt", "a").write(msg + "\n")
