"""Summarise tools/direct-bt-check evidence: hidutil lines, gc-probe JSON lines, phone log."""
import json, sys

hid_txt, gc_path, phone_path = sys.argv[1:4]
hid = open(hid_txt).read().strip()
events = []
for line in open(gc_path):
    try: events.append(json.loads(line))
    except ValueError: pass
phone = open(phone_path).read()

connected = [e["connected"] for e in events if "connected" in e]
pressed = sorted({e["button"] for e in events if e.get("pressed")})
axes = sorted({f'{e["axis"]} {"+" if e["value"] > 0 else "-"}' for e in events if "axis" in e})
steps = [l.split("step ", 1)[1].strip() for l in phone.splitlines() if "step " in l]

g3 = bool(hid)
g4 = any(c.get("profile") == "extendedGamepad" for c in connected) and len(pressed) >= 10
print("Direct Bluetooth controller — macOS check")
print(f"G1 phone advertised HID service: {'PASS' if 'advertising as HID' in phone else 'FAIL'}")
print(f"G2 computer bonded and subscribed: {'PASS' if 'subscribed to input' in phone else 'FAIL'}")
print(f"G3 macOS HID device 045E:0B13: {'PASS' if g3 else 'FAIL'}")
for l in hid.splitlines(): print("   ", " ".join(l.split()))
print(f"G4 GameController.framework extended gamepad + inputs: {'PASS' if g4 else 'FAIL'}")
for c in connected: print("    controller:", json.dumps(c))
print(f"    phone sent {len(steps)} sweep steps")
print(f"    buttons seen pressed ({len(pressed)}): {', '.join(pressed) or 'none'}")
print(f"    axes seen at ±0.9 ({len(axes)}): {', '.join(axes) or 'none'}")
sys.exit(0 if g3 and g4 else 1)
