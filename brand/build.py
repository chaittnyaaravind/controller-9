#!/usr/bin/env python3
"""Controller-9 identity: one geometry source for every logo asset.

    python3 brand/build.py          # rewrite brand/svg, Android drawables and desktop UI copies

The mark is a squared stick gate (the "C", open to the right) holding a squared "9" whose bowl
is the thumbstick cap (accent dot). One squared wave leaves the gate opening — the link. The
wordmark is drawn from the same strokes (no font file), so it renders identically everywhere.
Raster icons (Tauri .png/.icns/.ico) are produced by brand/raster.sh from these SVGs.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEXT, ACCENT, INK, GRAPHITE, PAPER, ACCENT_ON_PAPER = "#EEF0F1", "#C6F432", "#11140A", "#111315", "#F4F5F2", "#4E6B00"

# ---------------------------------------------------------------- geometry (48 x 48 grid)
STROKE = 4.6


def gate():
    r, l, t, rt, b = 10.5, 4.5, 6, 37, 42
    return f"M{rt} {t} H{l + r} A{r} {r} 0 0 0 {l} {t + r} V{b - r} A{r} {r} 0 0 0 {l + r} {b} H{rt}"


def nine():
    x0, x1, y0, yb, y1, r = 15.5, 27.5, 13.5, 23.5, 34.5, 4.5
    return (f"M{x1} {yb} H{x0 + r} A{r} {r} 0 0 1 {x0} {yb - r} V{y0 + r} A{r} {r} 0 0 1 {x0 + r} {y0} H{x1 - r} "
            f"A{r} {r} 0 0 1 {x1} {y0 + r} V{y1 - r} A{r} {r} 0 0 1 {x1 - r} {y1} H{x0 + 1}")


WAVE = "M40.5 15 A4.5 4.5 0 0 1 45 19.5 V28.5 A4.5 4.5 0 0 1 40.5 33"
WAVE_STROKE = 2.8
# Stick cap: 3.2 square, radius 0.6, centred in the bowl (21.5, 18.5).
DOT = "M20.5 16.9 H22.5 A0.6 0.6 0 0 1 23.1 17.5 V19.5 A0.6 0.6 0 0 1 22.5 20.1 H20.5 A0.6 0.6 0 0 1 19.9 19.5 V17.5 A0.6 0.6 0 0 1 20.5 16.9 Z"

# Wordmark glyphs: cap height 20, stroke 3.2, advance in units. Squared corners, radius 6.
K = 6
GLYPHS = {
    "C": (f"M15 0 H{K} A{K} {K} 0 0 0 0 {K} V{20 - K} A{K} {K} 0 0 0 {K} 20 H15", 15),
    "O": (f"M{K} 0 H{16 - K} A{K} {K} 0 0 1 16 {K} V{20 - K} A{K} {K} 0 0 1 {16 - K} 20 H{K} A{K} {K} 0 0 1 0 {20 - K} V{K} A{K} {K} 0 0 1 {K} 0 Z", 16),
    "N": ("M0 20 V0 L14 20 V0", 14),
    "T": ("M0 0 H14 M7 0 V20", 14),
    "R": ("M0 20 V0 H9 A4.5 4.5 0 0 1 13.5 4.5 V6.5 A4.5 4.5 0 0 1 9 11 H0 M8.5 11 L14 20", 14),
    "L": ("M0 0 V20 H12.5", 12.5),
    "E": ("M13 0 H0 V20 H13 M0 10 H11", 13),
    "-": ("M0 11 H7", 7),
    "9": ("M14 11 H4.5 A4.5 4.5 0 0 1 0 6.5 V4.5 A4.5 4.5 0 0 1 4.5 0 H9.5 A4.5 4.5 0 0 1 14 4.5 V15.5 A4.5 4.5 0 0 1 9.5 20 H1", 14),
}
WORD, TRACK, WSTROKE = "CONTROLLER-9", 4.4, 3.2


def word_layout():
    x, out = 0.0, []
    for ch in WORD:
        d, w = GLYPHS[ch]
        out.append((x, d))
        x += w + TRACK
    return out, x - TRACK


# ---------------------------------------------------------------- SVG
def svg_mark(fg, acc, wave=True, dot=True):
    parts = [f'<path d="{gate()}"/>', f'<path d="{nine()}"/>']
    if wave: parts.append(f'<path d="{WAVE}" stroke="{acc}" stroke-width="{WAVE_STROKE}"/>')
    g = f'<g fill="none" stroke="{fg}" stroke-width="{STROKE}" stroke-linejoin="miter">{"".join(parts)}</g>'
    return g + (f'<path d="{DOT}" fill="{acc}"/>' if dot else "")


def svg_word(fg):
    glyphs, _ = word_layout()
    body = "".join(f'<path transform="translate({x:g} 0)" d="{d}"/>' for x, d in glyphs)
    return f'<g fill="none" stroke="{fg}" stroke-width="{WSTROKE}" stroke-linejoin="miter" stroke-miterlimit="1.6">{body}</g>'


def doc(view, body, title, w=None, h=None):
    size = f' width="{w}" height="{h}"' if w else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}"{size} role="img" aria-label="{title}">'
            f"<title>{title}</title>{body}</svg>\n")


def lockup(fg, acc):
    # Mark 48 units; wordmark cap height = 0.44 x mark, vertically centred, 10-unit gap.
    _, ww = word_layout()
    s = 48 * 0.44 / 20
    tx, ty = 48 + 10, (48 - 20 * s) / 2
    total = tx + ww * s + 2
    body = svg_mark(fg, acc) + f'<g transform="translate({tx:g} {ty:.3f}) scale({s:.4f})">{svg_word(fg)}</g>'
    return doc(f"0 0 {total:.2f} 48", body, "Controller-9")


def app_icon():
    # Graphite tile, mark inset to the 66% safe zone used by adaptive icons and macOS.
    body = (f'<rect width="1024" height="1024" rx="228" fill="{GRAPHITE}"/>'
            f'<rect x="1.5" y="1.5" width="1021" height="1021" rx="226.5" fill="none" stroke="{TEXT}" stroke-opacity=".08" stroke-width="3"/>'
            f'<g transform="translate(184 184) scale(13.6667)">{svg_mark(TEXT, ACCENT)}</g>')
    return doc("0 0 1024 1024", body, "Controller-9")


# ---------------------------------------------------------------- Android VectorDrawable
def vd_path(d, stroke=None, width=None, fill=None, miter=None):
    a = [f'android:pathData="{d}"']
    if stroke: a += [f'android:strokeColor="{stroke}"', f'android:strokeWidth="{width}"', 'android:strokeLineCap="butt"', 'android:strokeLineJoin="miter"']
    if miter: a.append(f'android:strokeMiterLimit="{miter}"')
    a.append(f'android:fillColor="{fill or "#00000000"}"')
    return "<path " + " ".join(a) + " />"


def vd(w, h, vw, vh, body):
    return ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by brand/build.py — edit there, not here. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{w}dp" android:height="{h}dp" '
            f'android:viewportWidth="{vw}" android:viewportHeight="{vh}">\n    ' + "\n    ".join(body) + "\n</vector>\n")


def vd_mark(fg, acc, wave=True, dot=True):
    out = [vd_path(gate(), fg, STROKE), vd_path(nine(), fg, STROKE)]
    if wave: out.append(vd_path(WAVE, acc, WAVE_STROKE))
    if dot: out.append(vd_path(DOT, fill=acc))
    return out


def vd_word(fg):
    glyphs, ww = word_layout()
    body = [f'<group android:translateX="{x + 2:g}" android:translateY="2">{vd_path(d, fg, WSTROKE, miter=1.6)}</group>' for x, d in glyphs]
    return body, ww + 4


def main():
    out = ROOT / "brand" / "svg"
    out.mkdir(parents=True, exist_ok=True)
    _, ww = word_layout()
    files = {
        "controller9-mark.svg": doc("0 0 48 48", svg_mark(TEXT, ACCENT), "Controller-9"),
        "controller9-mark-on-light.svg": doc("0 0 48 48", svg_mark(GRAPHITE, ACCENT_ON_PAPER), "Controller-9"),
        "controller9-mark-mono-light.svg": doc("0 0 48 48", svg_mark("#FFFFFF", "#FFFFFF"), "Controller-9"),
        "controller9-mark-mono-dark.svg": doc("0 0 48 48", svg_mark("#000000", "#000000"), "Controller-9"),
        "controller9-mark-small.svg": doc("0 0 48 48", svg_mark(TEXT, ACCENT, wave=False), "Controller-9"),
        "controller9-wordmark.svg": lockup(TEXT, ACCENT),
        "controller9-wordmark-on-light.svg": lockup(GRAPHITE, ACCENT_ON_PAPER),
        "controller9-wordmark-mono-light.svg": lockup("#FFFFFF", "#FFFFFF"),
        "controller9-wordmark-mono-dark.svg": lockup("#000000", "#000000"),
        "controller9-logotype.svg": doc(f"-2 -2 {ww + 4:g} 24", svg_word(TEXT), "Controller-9"),
        "controller9-app-icon.svg": app_icon(),
    }
    for name, text in files.items():
        (out / name).write_text(text)

    # Desktop companion: served next to index.html.
    ui = ROOT / "hosts" / "app" / "ui" / "brand"
    ui.mkdir(parents=True, exist_ok=True)
    for name in ("controller9-mark.svg", "controller9-wordmark.svg", "controller9-mark-small.svg"):
        (ui / name).write_text(files[name])

    # Android: tinted at runtime where needed (Compose colorFilter); launcher uses the 108dp grid.
    res = ROOT / "apps" / "android" / "app" / "src" / "main" / "res" / "drawable"
    (res / "c9_mark.xml").write_text(vd(48, 48, 48, 48, vd_mark(TEXT, ACCENT)))
    (res / "c9_mark_small.xml").write_text(vd(24, 24, 48, 48, vd_mark(TEXT, ACCENT, wave=False)))
    body, vw = vd_word(TEXT)
    (res / "c9_logotype.xml").write_text(vd(round(vw / 24 * 20), 20, vw, 24, body))
    s, off = 60 / 48, 24  # 60dp mark centred in the 108dp adaptive canvas (66dp safe zone)
    fg = [f'<group android:scaleX="{s}" android:scaleY="{s}" android:translateX="{off}" android:translateY="{off}">'] + vd_mark(TEXT, ACCENT) + ["</group>"]
    (res / "ic_launcher_foreground.xml").write_text(vd(108, 108, 108, 108, fg))
    mono = [f'<group android:scaleX="{s}" android:scaleY="{s}" android:translateX="{off}" android:translateY="{off}">'] + vd_mark("#FFFFFF", "#FFFFFF") + ["</group>"]
    (res / "ic_launcher_monochrome.xml").write_text(vd(108, 108, 108, 108, mono))
    print(f"wrote {len(files)} SVGs, desktop copies and Android drawables")


if __name__ == "__main__":
    main()
