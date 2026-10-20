"""How XInput (the API most Windows games use) sees the pad: slot, every button/axis change, rumble.

Usage: python xinputcheck.py <seconds> [rumble_at_seconds]
Prints one JSON object per line. Uses XInputGetStateEx (xinput1_4 ordinal 100) so Guide is reported.
At rumble_at, sets both motors for 1 s on the pad it found, then stops them (game -> phone rumble).
"""
import ctypes, json, sys, time
from ctypes import wintypes

class GAMEPAD(ctypes.Structure):
    _fields_ = [("buttons", wintypes.WORD), ("lt", ctypes.c_ubyte), ("rt", ctypes.c_ubyte),
                ("lx", ctypes.c_short), ("ly", ctypes.c_short), ("rx", ctypes.c_short), ("ry", ctypes.c_short)]
class STATE(ctypes.Structure):
    _fields_ = [("packet", wintypes.DWORD), ("pad", GAMEPAD)]
class VIBRATION(ctypes.Structure):
    _fields_ = [("left", wintypes.WORD), ("right", wintypes.WORD)]

dll = ctypes.WinDLL("xinput1_4.dll")
get_state = dll[100]  # XInputGetStateEx
get_state.argtypes = [wintypes.DWORD, ctypes.POINTER(STATE)]
set_state = dll.XInputSetState
set_state.argtypes = [wintypes.DWORD, ctypes.POINTER(VIBRATION)]

NAMES = {0x1000: "a", 0x2000: "b", 0x4000: "x", 0x8000: "y", 0x0100: "lb", 0x0200: "rb", 0x0020: "back",
         0x0010: "start", 0x0040: "ls", 0x0080: "rs", 0x0001: "up", 0x0002: "down", 0x0004: "left",
         0x0008: "right", 0x0400: "guide"}

def out(**kw):
    print(json.dumps(kw), flush=True)

secs = float(sys.argv[1]) if len(sys.argv) > 1 else 15
rumble_at = float(sys.argv[2]) if len(sys.argv) > 2 else None
t0 = time.perf_counter(); slot = None; prev = 0; rumbled = False; axes_seen = set()
while time.perf_counter() - t0 < secs:
    now = time.perf_counter() - t0
    for i in ([slot] if slot is not None else range(4)):
        s = STATE()
        if get_state(i, ctypes.byref(s)) != 0:
            if slot == i: out(t=round(now, 3), removed=i); slot = None
            continue
        if slot is None: slot = i; out(t=round(now, 3), connected=i)
        g = s.pad
        for bit, name in NAMES.items():
            if (g.buttons ^ prev) & bit: out(t=round(now, 3), button=name, down=int(bool(g.buttons & bit)))
        prev = g.buttons
        for name, v, full in (("lx", g.lx, 30000), ("ly", g.ly, 30000), ("rx", g.rx, 30000), ("ry", g.ry, 30000),
                              ("lt", g.lt, 250), ("rt", g.rt, 250)):
            key = (name, v > 0)
            if abs(v) >= full and key not in axes_seen: axes_seen.add(key); out(t=round(now, 3), axis=name, v=v)
    if slot is not None and rumble_at is not None and not rumbled and now >= rumble_at:
        set_state(slot, ctypes.byref(VIBRATION(65535, 32768))); out(t=round(now, 3), rumble="on")
        time.sleep(1); set_state(slot, ctypes.byref(VIBRATION(0, 0))); out(t=round(time.perf_counter() - t0, 3), rumble="off")
        rumbled = True
    time.sleep(0.002)
