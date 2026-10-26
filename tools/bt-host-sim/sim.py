#!/usr/bin/env python3
"""Host-side simulation of the phone's Bluetooth controller (docs/DIRECT_BLUETOOTH.md).

Creates a Linux uhid device exactly as a Bluetooth LE HID host would: bus Bluetooth, the phone's
name, 045E:0B13 and its report map, from tests/vectors/hid-xbox-ble.json (the app's own bytes). Then:
  pad     play every input step; SDL (tools/linux-check/sdlcheck, running separately) reports what a
          game sees — through SDL's HIDAPI Xbox driver or evdev, the paths Steam and PC games use.
  remote  create the device with the Remote-keys report map and check that the keyboard, mouse and
          media collections become real input devices (KEY_A, REL_X, KEY_VOLUMEUP).
Needs root (/dev/uhid). Usage: sudo python3 sim.py pad|remote <vectors.json>
"""
import json, os, select, struct, sys, time

UHID_DESTROY, UHID_CREATE2, UHID_INPUT2 = 1, 11, 12
BUS_BLUETOOTH = 0x05

def create(fd, v, rd):
    name = v["name"].encode()[:127]
    ev = struct.pack("<I128s64s64sHHIIII4096s", UHID_CREATE2, name, b"c9-sim", b"a8:ef:5f:74:a8:34",
                     len(rd), BUS_BLUETOOTH, v["vendor"], v["product"], v["version"], 0, rd)
    os.write(fd, ev)

def send(fd, report_id, data):
    payload = bytes([report_id]) + data
    os.write(fd, struct.pack("<IH4096s", UHID_INPUT2, len(payload), payload))

def out(**kw):
    print(json.dumps(kw), flush=True)

def pad(fd, v):
    rid = v["input_id"]; neutral = bytes.fromhex(v["neutral"])
    time.sleep(3)  # let the kernel bind a driver and SDL open the pad
    for _ in range(2):
        for s in v["steps"]:
            out(step=s["step"], t=round(time.monotonic(), 3))
            send(fd, rid, bytes.fromhex(s["report"])); time.sleep(0.25)
            send(fd, rid, neutral); time.sleep(0.25)

def remote(fd, v):
    import evdev
    from evdev import ecodes as e
    time.sleep(2)
    devs = [evdev.InputDevice(p) for p in evdev.list_devices()]
    mine = [d for d in devs if v["name"] in d.name]
    out(devices=[{"path": d.path, "name": d.name, "caps": sorted(e.EV[k] for k in d.capabilities())} for d in mine])
    r = v["remote"]
    send(fd, r["keyboard_id"], bytes.fromhex(r["key_a"])); send(fd, r["keyboard_id"], bytes.fromhex(r["key_up"]))
    send(fd, r["mouse_id"], bytes.fromhex(r["mouse_right_40"]))
    send(fd, r["consumer_id"], bytes.fromhex(r["volume_up"])); send(fd, r["consumer_id"], bytes.fromhex(r["consumer_up"]))
    seen = set(); end = time.monotonic() + 2
    while time.monotonic() < end:
        rl, _, _ = select.select(mine, [], [], 0.2)
        for d in rl:
            for ev in d.read():
                if ev.type == e.EV_KEY and ev.value == 1: seen.add(e.KEY.get(ev.code, ev.code) if not isinstance(e.KEY.get(ev.code), list) else e.KEY[ev.code][0])
                if ev.type == e.EV_REL and ev.code == e.REL_X: seen.add("REL_X")
    out(remote_seen=sorted(map(str, seen)))

def main():
    mode, path = sys.argv[1], sys.argv[2]
    v = json.load(open(path))
    rd = bytes.fromhex(v["report_map_remote" if mode == "remote" else "report_map"])
    fd = os.open("/dev/uhid", os.O_RDWR)
    create(fd, v, rd)
    out(created=v["name"], vid=hex(v["vendor"]), pid=hex(v["product"]), bus="bluetooth", report_map_bytes=len(rd))
    try:
        pad(fd, v) if mode == "pad" else remote(fd, v)
        time.sleep(1)
    finally:
        os.write(fd, struct.pack("<I", UHID_DESTROY)); os.close(fd)

if __name__ == "__main__":
    main()
