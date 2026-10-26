#!/usr/bin/env python3
"""Render the controller-style layouts as SVG for the website, from the same geometry as
apps/android/.../pad/PadPresets.kt and apps/ios/Core/.../PadPresets.swift (200 x 100 canvas).

    python3 site/tools/pads.py      # writes site/assets/pad-<id>.svg
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "public" / "assets"
TEXT, S1, S2, LINE, LINE2, ACC, INK = "#EEF0F1", "#111315", "#171A1C", "rgba(238,240,241,.10)", "rgba(238,240,241,.24)", "#C6F432", "#11140A"
MUTED = "rgba(238,240,241,.62)"

def shoulders(lt, rt, lb, rb):
    return [("trigger_l", "trigger", lt, 30, 9, 44, 14, True), ("trigger_r", "trigger", rt, 170, 9, 44, 14, True),
            ("bumper_l", "bumper", lb, 30, 24.5, 44, 12, True), ("bumper_r", "bumper", rb, 170, 24.5, 44, 12, True)]

def face(cx, cy, t, r, b, l):
    o, d = 13.5, 14
    return [("face_n", "face", t, cx, cy - o, d, d, True), ("face_e", "face", r, cx + o, cy, d, d, True),
            ("face_s", "face", b, cx, cy + o, d, d, True), ("face_w", "face", l, cx - o, cy, d, d, True)]

def asym(l3, r3):
    return [("stick_l", "stick", "", 36, 52, 34, 34, True), ("dpad", "dpad", "", 70, 80, 30, 30, True),
            ("stick_r", "stick", "", 130, 80, 30, 30, True), ("stick_l_press", "press", l3, 14, 84, 12, 12, True),
            ("stick_r_press", "press", r3, 186, 84, 12, 12, True)]

def sym(l3, r3):
    return [("dpad", "dpad", "", 36, 52, 32, 32, True), ("stick_l", "stick", "", 72, 80, 30, 30, True),
            ("stick_r", "stick", "", 128, 80, 30, 30, True), ("stick_l_press", "press", l3, 46, 88, 12, 12, True),
            ("stick_r_press", "press", r3, 154, 88, 12, 12, True)]

SYM = {"Triangle": "tri", "Circle": "cir", "Cross": "x", "Square": "sq"}
_st = lambda r: f'stroke="{MUTED}" stroke-width="{r * .2:.2f}" fill="none" stroke-linecap="round" stroke-linejoin="round"'
def _menu(cx, cy, r): s = r * .42; return f'<path d="M{cx - s:.2f} {cy - s * .6:.2f}H{cx + s:.2f}M{cx - s:.2f} {cy:.2f}H{cx + s:.2f}M{cx - s:.2f} {cy + s * .6:.2f}H{cx + s:.2f}" {_st(r)}/>'
def _view(cx, cy, r): s = r * .3; return f'<rect x="{cx - s * 1.2:.2f}" y="{cy - s * 1.2:.2f}" width="{s * 1.6:.2f}" height="{s * 1.6:.2f}" rx=".4" {_st(r)}/><rect x="{cx - s * .4:.2f}" y="{cy - s * .4:.2f}" width="{s * 1.6:.2f}" height="{s * 1.6:.2f}" rx=".4" {_st(r)}/>'
def _home(cx, cy, r): return f'<circle cx="{cx}" cy="{cy}" r="{r * .34:.2f}" {_st(r)}/>'
def _bar(cx, cy, r): return f'<path d="M{cx:.2f} {cy - r * .4:.2f}V{cy + r * .4:.2f}" {_st(r)}/>'
CENTER_GLYPH = {"menu": _menu, "options": _menu, "start": _menu, "view": _view, "create": _bar, "share": _bar, "select": _bar,
                "guide": _home, "home": _home, "ps": _home}
PADS = {
    "xbox": ("Xbox-style", shoulders("LT", "RT", "LB", "RB") + face(164, 52, "Y", "B", "A", "X") + asym("LS", "RS") + [
        ("view", "center", "", 86, 50, 11, 11, True), ("guide", "center", "", 100, 38, 14, 14, True),
        ("menu", "center", "", 114, 50, 11, 11, True), ("share", "center", "", 100, 58, 10, 7, False)]),
    "dualsense": ("DualSense-style", shoulders("L2", "R2", "L1", "R1") + face(164, 52, "Triangle", "Circle", "Cross", "Square") + sym("L3", "R3") + [
        ("touchpad", "touchpad", "", 100, 38, 52, 24, False), ("create", "center", "", 64, 32, 8, 13, True),
        ("options", "center", "", 136, 32, 8, 13, True), ("ps", "center", "", 100, 60, 11, 11, True), ("mute", "center", "", 100, 72, 10, 5, False)]),
    "dualshock4": ("DualShock 4-style", shoulders("L2", "R2", "L1", "R1") + face(164, 52, "Triangle", "Circle", "Cross", "Square") + sym("L3", "R3") + [
        ("touchpad", "touchpad", "", 100, 36, 46, 22, False), ("share", "center", "", 70, 30, 8, 12, True),
        ("options", "center", "", 130, 30, 8, 12, True), ("ps", "center", "", 100, 58, 11, 11, True)]),
    "switchpro": ("Switch Pro-style", shoulders("ZL", "ZR", "L", "R") + face(164, 52, "X", "A", "B", "Y") + asym("LS", "RS") + [
        ("minus", "center", "−", 80, 36, 10, 10, True), ("plus", "center", "+", 120, 36, 10, 10, True),
        ("capture", "center", "", 88, 50, 9, 9.5, False), ("home", "center", "", 112, 50, 11, 11, True)]),
    "8bitdo-pro2": ("8BitDo Pro 2-style", shoulders("L2", "R2", "L", "R") + face(164, 52, "X", "A", "B", "Y") + sym("L3", "R3") + [
        ("select", "center", "", 84, 44, 14, 7, True), ("start", "center", "", 116, 44, 14, 7, True),
        ("star", "center", "", 100, 34, 9, 9, False), ("home", "center", "", 100, 58, 11, 11, True),
        ("paddle_l", "paddle", "P1", 22, 92, 20, 10, False), ("paddle_r", "paddle", "P2", 178, 92, 20, 10, False)]),
    "gamecube": ("GameCube-style", [("trigger_l", "trigger", "L", 30, 9, 44, 14, True), ("trigger_r", "trigger", "R", 170, 9, 44, 14, True),
        ("bumper_l", "bumper", "L click", 30, 24.5, 34, 11, True), ("bumper_r", "bumper", "Z", 170, 24.5, 34, 11, True),
        ("face_s", "face", "A", 160, 58, 22, 22, True), ("face_w", "face", "B", 141, 62, 12, 12, True),
        ("face_e", "face", "X", 180, 50, 13, 13, True), ("face_n", "face", "Y", 157, 38, 13, 13, True),
        ("stick_l", "stick", "", 36, 52, 34, 34, True), ("dpad", "dpad", "", 70, 82, 26, 26, True), ("stick_r", "stick", "", 130, 82, 26, 26, True),
        ("stick_l_press", "press", "LS", 14, 84, 12, 12, True), ("stick_r_press", "press", "RS", 186, 84, 12, 12, True),
        ("start", "center", "", 100, 50, 11, 11, True), ("home", "center", "", 100, 66, 10, 10, True), ("select", "center", "", 100, 34, 9, 9, True)]),
    "arcade": ("Arcade stick", [("dpad", "dpad", "", 46, 58, 54, 54, True),
        ("face_w", "face", "X", 110, 46, 17, 17, True), ("face_n", "face", "Y", 131, 41, 17, 17, True),
        ("bumper_r", "face", "RB", 152, 41, 17, 17, True), ("bumper_l", "face", "LB", 173, 46, 17, 17, True),
        ("face_s", "face", "A", 112, 68, 17, 17, True), ("face_e", "face", "B", 133, 63, 17, 17, True),
        ("trigger_r", "face", "RT", 154, 63, 17, 17, True), ("trigger_l", "face", "LT", 175, 68, 17, 17, True),
        ("view", "center", "", 84, 20, 11, 11, True), ("guide", "center", "", 100, 20, 12, 12, True), ("menu", "center", "", 116, 20, 11, 11, True),
        ("stick_l", "stick", "", 88, 86, 20, 20, True), ("stick_r", "stick", "", 186, 88, 20, 20, True),
        ("stick_l_press", "press", "LS", 62, 92, 10, 10, True), ("stick_r_press", "press", "RS", 160, 92, 10, 10, True)]),
}

def glyph(label, cx, cy, r):
    k = SYM.get(label)
    st = f'stroke="{MUTED}" stroke-width="{r * .16:.2f}" fill="none" stroke-linecap="round" stroke-linejoin="round"'
    s = r * .42
    if k == "x": return f'<path d="M{cx - s:.2f} {cy - s:.2f}L{cx + s:.2f} {cy + s:.2f}M{cx + s:.2f} {cy - s:.2f}L{cx - s:.2f} {cy + s:.2f}" {st}/>'
    if k == "cir": return f'<circle cx="{cx}" cy="{cy}" r="{s:.2f}" {st}/>'
    if k == "sq": return f'<rect x="{cx - s:.2f}" y="{cy - s:.2f}" width="{2 * s:.2f}" height="{2 * s:.2f}" rx=".4" {st}/>'
    if k == "tri": return f'<path d="M{cx} {cy - s:.2f}L{cx + s * 1.05:.2f} {cy + s * .8:.2f}H{cx - s * 1.05:.2f}Z" {st}/>'
    if not label: return ""
    fs = r * (1.0 if len(label) == 1 else .62)
    return f'<text x="{cx}" y="{cy + fs * .36:.2f}" font-size="{fs:.2f}" text-anchor="middle" fill="{MUTED}" font-family="Geist, system-ui" font-weight="600">{label}</text>'

def element(e):
    id_, part, label, cx, cy, w, h, live = e
    x, y = cx - w / 2, cy - h / 2
    op = "" if live else ' opacity=".42"'
    a = f'data-el="{id_}" class="el el-{part}"{op}'
    if part in ("face", "press"):
        r = w / 2
        return f'<g {a}><circle class="cap" cx="{cx}" cy="{cy}" r="{r - .4}" fill="{S2}" stroke="{LINE2}" stroke-width=".8"/>{glyph(label, cx, cy, r)}</g>'
    if part == "stick":
        r = w / 2
        return (f'<g {a}><circle cx="{cx}" cy="{cy}" r="{r - .4}" fill="{S1}" stroke="{LINE2}" stroke-width=".8"/>'
                f'<circle cx="{cx}" cy="{cy}" r="{r * .7:.2f}" fill="none" stroke="{LINE}" stroke-width=".6"/>'
                f'<circle class="cap knob" cx="{cx}" cy="{cy}" r="{r * .47:.2f}" fill="{S2}" stroke="{LINE2}" stroke-width=".8"/></g>')
    if part == "dpad":
        s = min(w, h); arm = s / 3; c = (cx - arm / 2, cy - arm / 2)
        pieces = [("up", 0, -1), ("down", 0, 1), ("left", -1, 0), ("right", 1, 0)]
        body = f'<rect x="{c[0]:.2f}" y="{c[1]:.2f}" width="{arm:.2f}" height="{arm:.2f}" fill="{S2}"/>'
        body += "".join(f'<rect class="cap arm-{n}" x="{c[0] + dx * arm:.2f}" y="{c[1] + dy * arm:.2f}" width="{arm:.2f}" height="{arm:.2f}" rx="{arm * .22:.2f}" fill="{S2}" stroke="{LINE2}" stroke-width=".8"/>' for n, dx, dy in pieces)
        return f'<g {a}>{body}</g>'
    if part == "touchpad":
        return f'<g {a}><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="3" fill="{S1}" stroke="{LINE2}" stroke-width=".7" stroke-dasharray="2 2"/></g>'
    if part == "paddle":
        return f'<g {a}><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{h * .45}" fill="none" stroke="{LINE2}" stroke-width=".7" stroke-dasharray="1.6 1.6"/>{glyph(label, cx, cy, h * .55)}</g>'
    if part == "center" and id_ in CENTER_GLYPH:
        r = min(w, h) / 2
        return f'<g {a}><rect class="cap" x="{x}" y="{y}" width="{w}" height="{h}" rx="{r if w == h else 2}" fill="{S2}" stroke="{LINE2}" stroke-width=".8"/>{CENTER_GLYPH[id_](cx, cy, r)}</g>'
    rx = h * .45 if part in ("bumper", "trigger") else (min(w, h) / 2 if w == h else 2)
    fill = f'<rect class="cap" x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{S2}" stroke="{LINE2}" stroke-width=".8"/>'
    lab = f'<text x="{cx}" y="{cy + h * .17:.2f}" font-size="{h * .46:.2f}" text-anchor="middle" fill="{MUTED}" font-family="Geist, system-ui" font-weight="600">{label}</text>' if label and part in ("bumper", "trigger", "center") else ""
    return f'<g {a}>{fill}{lab}</g>'

def svg(name, els):
    body = "".join(element(e) for e in els)
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100" role="img" aria-label="{name} layout"><title>{name} layout</title>{body}</svg>\n'

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for k, (name, els) in PADS.items():
        (OUT / f"pad-{k}.svg").write_text(svg(name, els))
    print("wrote", ", ".join(f"pad-{k}.svg" for k in PADS))
