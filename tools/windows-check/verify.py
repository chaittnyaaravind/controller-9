"""Assert the Windows game-visible run and say exactly which path was proven.

- Xbox 360 path (normal Windows 10/11): XInput sees every button in order and every axis both ways,
  the rumble a game sets reaches the phone, and SDL sees the same pad.
- DualShock 4 fallback (no Microsoft XUSB driver, e.g. Windows Server CI runners): XInput sees
  nothing, so SDL must see a PS4 controller with every button and axis. Printed as FALLBACK.
Usage: verify.py <evidence dir>"""
import json, sys
d = sys.argv[1]

def rows(name):
    try:
        return [json.loads(l) for l in open(f"{d}/{name}", encoding="utf-8-sig") if l.strip().startswith("{")]
    except FileNotFoundError:
        return []

x, sdl = rows("xinput.jsonl"), rows("sdl.jsonl")
sim = rows("sim.json")[-1]
lines = []

head = next((r for r in sdl if "controller" in r), None)
assert head, f"SDL saw no controller: {sdl[:5]}"
sdl_downs = [r["button"] for r in sdl if r.get("down") == 1]
sdl_want = ["a", "b", "x", "y", "leftshoulder", "rightshoulder", "back", "start", "leftstick", "rightstick", "dpup", "dpdown", "dpleft", "dpright"]
assert sdl_downs[:len(sdl_want)] == sdl_want, f"SDL button order {sdl_downs}"
sdl_axes = {r["axis"] for r in sdl if "axis" in r}
for a in ["leftx", "lefty", "rightx", "righty", "lefttrigger", "righttrigger"]:
    assert a in sdl_axes, (a, sorted(sdl_axes))
lines.append(f"SDL {head['sdl']}: '{head['controller']}', rumble={head['rumble']}, buttons in order, guide={'guide' in sdl_downs}, all axes")

if any("connected" in r for r in x):
    downs = [r["button"] for r in x if r.get("down") == 1]
    want = ["a", "b", "x", "y", "lb", "rb", "back", "start", "ls", "rs", "up", "down", "left", "right", "guide"]
    assert downs == want, f"XInput button order {downs}"
    axes = {(r["axis"], r["v"] > 0) for r in x if "axis" in r}
    for a in ["lx", "ly", "rx", "ry"]:
        assert (a, True) in axes and (a, False) in axes, (a, sorted(axes))
    assert ("lt", True) in axes and ("rt", True) in axes, sorted(axes)
    assert any(r.get("rumble") == "on" for r in x), "checker never set rumble"
    assert sim["feedback_events"] >= 1, sim
    lines.append(f"XInput (Xbox 360 path): every button in order, all axes both ways, rumble reached the phone ({sim['feedback_events']} events)")
else:
    assert "ps4" in head["controller"].lower() or "dualshock" in head["controller"].lower(), head
    lines.append("FALLBACK: XInput saw no pad (no Microsoft XUSB driver on this OS); the DualShock 4 fallback was proven through SDL. XInput path NOT proven here.")

for l in lines: print(l)
open(f"{d}/result.txt", "a").write("\n".join(lines) + "\n")
