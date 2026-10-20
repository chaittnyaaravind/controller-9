"""Wait for the Controller-9 uinput pad, record its events, optionally play FF rumble on it."""
import evdev, json, sys, time, select
deadline = time.time() + float(sys.argv[1]); rumble = len(sys.argv) > 2 and sys.argv[2] == "rumble"
dev = None
while dev is None and time.time() < deadline:
    for p in evdev.list_devices():
        d = evdev.InputDevice(p)
        if d.name.startswith("Controller-9"): dev = d; break
    else: time.sleep(0.05)
if dev is None: print(json.dumps({"found": False})); sys.exit(1)
caps = dev.capabilities(verbose=True)
info = {"found": True, "path": dev.path, "name": dev.name, "vendor": hex(dev.info.vendor), "product": hex(dev.info.product),
        "bustype": dev.info.bustype, "keys": [k[0] if isinstance(k[0], str) else k[0][0] for k in caps.get(("EV_KEY", 1), [])],
        "abs": {(a[0][0] if isinstance(a[0], tuple) else a[0]): {"min": a[1].min, "max": a[1].max} for a in caps.get(("EV_ABS", 3), [])},
        "ff": [f[0] if isinstance(f[0], str) else f[0][0] for f in caps.get(("EV_FF", 21), [])]}
print(json.dumps({"device": info}), flush=True)
if rumble:
    for strong, weak in ((0xFFFF, 0), (0, 0xFFFF)):
        eff = evdev.ff.Effect(evdev.ecodes.FF_RUMBLE, -1, 0, evdev.ff.Trigger(0, 0), evdev.ff.Replay(300, 0),
                              evdev.ff.EffectType(ff_rumble_effect=evdev.ff.Rumble(strong_magnitude=strong, weak_magnitude=weak)))
        eid = dev.upload_effect(eff); dev.write(evdev.ecodes.EV_FF, eid, 1)
        print(json.dumps({"rumble": {"strong": strong, "weak": weak, "id": eid, "t": time.time()}}), flush=True)
        time.sleep(0.6); dev.erase_effect(eid)
names = {}
while time.time() < deadline:
    r, _, _ = select.select([dev.fd], [], [], 0.2)
    if not r: continue
    try: evs = list(dev.read())
    except OSError: print(json.dumps({"gone": time.time()})); break
    for e in evs:
        if e.type in (evdev.ecodes.EV_KEY, evdev.ecodes.EV_ABS):
            n = evdev.ecodes.BTN.get(e.code) if e.type == evdev.ecodes.EV_KEY else evdev.ecodes.ABS.get(e.code)
            n = n[0] if isinstance(n, list) else n
            print(json.dumps({"t": e.timestamp(), "ev": n, "v": e.value}), flush=True)
