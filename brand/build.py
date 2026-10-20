#!/usr/bin/env python3
"""Controller-9 identity: one geometry source for every logo asset.

    python3 brand/build.py          # rewrite brand/svg, Android drawables, desktop UI and web copies

Mark — "Pocket Pad": a phone held sideways that is already a controller. A rounded landscape body,
a D-pad on the left and one accent button on the right. Wordmark — "Controller-9" in Geist SemiBold
(SIL OFL 1.1), stored below as outlines so it renders identically with no font file; the hyphen takes
the accent. Raster icons (desktop .png/.icns/.ico, iOS app icon) come from brand/raster.sh.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEXT, ACCENT, INK, GRAPHITE, PAPER, ACCENT_ON_PAPER = "#EEF0F1", "#C6F432", "#11140A", "#111315", "#F4F5F2", "#4D6B00"

# ---------------------------------------------------------------- mark geometry (48 x 48 grid)
# Regular (>= 40 px) and small (<= 32 px: heavier strokes, larger parts) cuts of the same mark.
CUTS = {
    "regular": dict(x=4.5, y=13, w=39, h=22, r=7, stroke=3.2, dx=15, arm=4.8, bx=32.5, br=4.2),
    "small": dict(x=4, y=12, w=40, h=24, r=7.5, stroke=4.4, dx=15, arm=5.5, bx=32.5, br=5),
}


def body(c):
    x, y, w, h, r = c["x"], c["y"], c["w"], c["h"], c["r"]
    return (f"M{x + r:g} {y:g} H{x + w - r:g} A{r:g} {r:g} 0 0 1 {x + w:g} {y + r:g} V{y + h - r:g} "
            f"A{r:g} {r:g} 0 0 1 {x + w - r:g} {y + h:g} H{x + r:g} A{r:g} {r:g} 0 0 1 {x:g} {y + h - r:g} "
            f"V{y + r:g} A{r:g} {r:g} 0 0 1 {x + r:g} {y:g} Z")


def dpad(c):
    cy, a, d = 24, c["arm"], c["dx"]
    return f"M{d:g} {cy - a:g} V{cy + a:g} M{d - a:g} {cy:g} H{d + a:g}"


def button(c):
    x, r = c["bx"], c["br"]
    return f"M{x - r:g} 24 A{r:g} {r:g} 0 1 0 {x + r:g} 24 A{r:g} {r:g} 0 1 0 {x - r:g} 24 Z"


# ---------------------------------------------------------------- wordmark (Geist 600 outlines)
# Cap height 20, baseline y = 20, tracking -0.02 em. Regenerate with brand/outline_wordmark.py.
WORD_FG = "M10.47 20.45Q7.75 20.45 5.66 19.2Q3.57 17.95 2.39 15.62Q1.21 13.29 1.21 10.03Q1.21 6.82 2.36 4.47Q3.51 2.12 5.61 0.83Q7.7 -0.45 10.51 -0.45Q14.34 -0.45 16.46 1.45Q18.57 3.35 19.21 6.77L15.38 6.95Q15.01 4.98 13.8 3.86Q12.59 2.74 10.51 2.74Q8.81 2.74 7.57 3.63Q6.34 4.52 5.67 6.16Q5 7.8 5 10.03Q5 12.28 5.68 13.9Q6.36 15.53 7.6 16.39Q8.83 17.26 10.49 17.26Q12.72 17.26 13.94 16.04Q15.16 14.82 15.48 12.69L19.32 12.87Q18.93 15.22 17.8 16.91Q16.68 18.61 14.84 19.53Q13 20.45 10.47 20.45ZM28.33 20.34Q26.11 20.34 24.44 19.37Q22.77 18.4 21.85 16.63Q20.94 14.86 20.94 12.48Q20.94 10.08 21.85 8.33Q22.77 6.57 24.44 5.59Q26.11 4.62 28.33 4.62Q30.56 4.62 32.22 5.59Q33.88 6.57 34.79 8.33Q35.71 10.08 35.71 12.48Q35.71 14.86 34.79 16.63Q33.88 18.4 32.22 19.37Q30.56 20.34 28.33 20.34ZM28.33 17.42Q30.07 17.42 31.03 16.12Q31.98 14.81 31.98 12.48Q31.98 10.16 31.03 8.85Q30.07 7.54 28.33 7.54Q26.59 7.54 25.63 8.85Q24.66 10.16 24.66 12.48Q24.66 14.81 25.63 16.12Q26.59 17.42 28.33 17.42ZM38.24 20V4.96H41.5L41.64 9.18L41.2 9Q41.44 7.43 42.14 6.47Q42.83 5.51 43.86 5.06Q44.88 4.62 46.09 4.62Q47.75 4.62 48.89 5.35Q50.02 6.07 50.61 7.35Q51.2 8.64 51.2 10.33V20H47.59V11.49Q47.59 10.22 47.35 9.34Q47.1 8.47 46.53 8.01Q45.95 7.55 44.99 7.55Q43.54 7.55 42.69 8.56Q41.84 9.57 41.84 11.49V20ZM60.61 20Q58.37 20 57.31 18.96Q56.25 17.93 56.25 15.7V1.43H59.86V15.35Q59.86 16.41 60.32 16.81Q60.77 17.21 61.71 17.21H63.83V20ZM53.9 7.75V4.96H63.83V7.75ZM66.07 20V4.96H69.44L69.57 9.14L69.28 9.06Q69.62 6.9 70.55 5.93Q71.49 4.96 73.12 4.96H74.51V8.05H73.11Q71.95 8.05 71.19 8.39Q70.43 8.73 70.05 9.45Q69.67 10.16 69.67 11.34V20ZM83.54 20.34Q81.32 20.34 79.65 19.37Q77.98 18.4 77.06 16.63Q76.15 14.86 76.15 12.48Q76.15 10.08 77.06 8.33Q77.98 6.57 79.65 5.59Q81.32 4.62 83.54 4.62Q85.77 4.62 87.43 5.59Q89.09 6.57 90.01 8.33Q90.92 10.08 90.92 12.48Q90.92 14.86 90.01 16.63Q89.09 18.4 87.43 19.37Q85.77 20.34 83.54 20.34ZM83.54 17.42Q85.28 17.42 86.24 16.12Q87.19 14.81 87.19 12.48Q87.19 10.16 86.24 8.85Q85.28 7.54 83.54 7.54Q81.8 7.54 80.84 8.85Q79.88 10.16 79.88 12.48Q79.88 14.81 80.84 16.12Q81.8 17.42 83.54 17.42ZM97.05 20Q95.4 20 94.42 19.15Q93.45 18.31 93.45 16.44V0H97.05V16.07Q97.05 16.66 97.34 16.93Q97.63 17.21 98.17 17.21H99.28V20ZM104.86 20Q103.2 20 102.23 19.15Q101.25 18.31 101.25 16.44V0H104.86V16.07Q104.86 16.66 105.15 16.93Q105.44 17.21 105.98 17.21H107.08V20ZM115.54 20.34Q113.29 20.34 111.64 19.37Q109.98 18.4 109.08 16.63Q108.17 14.86 108.17 12.48Q108.17 10.1 109.08 8.34Q109.98 6.59 111.62 5.6Q113.26 4.62 115.47 4.62Q117.62 4.62 119.23 5.58Q120.84 6.54 121.72 8.34Q122.6 10.15 122.6 12.68V13.48H111.92Q112.02 15.47 112.99 16.46Q113.97 17.45 115.58 17.45Q116.79 17.45 117.59 16.92Q118.38 16.38 118.7 15.39L122.39 15.62Q121.79 17.8 119.96 19.07Q118.14 20.34 115.54 20.34ZM111.92 11.06H118.86Q118.75 9.23 117.83 8.34Q116.91 7.46 115.47 7.46Q114.02 7.46 113.08 8.39Q112.14 9.31 111.92 11.06ZM125.14 20V4.96H128.51L128.64 9.14L128.35 9.06Q128.69 6.9 129.63 5.93Q130.56 4.96 132.19 4.96H133.58V8.05H132.18Q131.02 8.05 130.26 8.39Q129.5 8.73 129.12 9.45Q128.74 10.16 128.74 11.34V20Z M153.34 20.45Q150.65 20.45 148.99 19.19Q147.32 17.93 146.66 15.71L150.25 15.48Q150.56 16.36 151.34 16.83Q152.11 17.31 153.34 17.31Q155.57 17.31 156.68 15.6Q157.8 13.9 157.81 10.02L158.37 10.21Q157.65 11.85 156.28 12.7Q154.92 13.54 153 13.54Q151.03 13.54 149.53 12.69Q148.03 11.84 147.19 10.31Q146.34 8.78 146.34 6.74Q146.34 4.52 147.26 2.91Q148.19 1.3 149.84 0.43Q151.5 -0.45 153.66 -0.45Q156.31 -0.45 158.04 0.69Q159.76 1.84 160.6 3.97Q161.44 6.11 161.44 9.14Q161.44 11.72 160.98 13.81Q160.52 15.89 159.54 17.37Q158.57 18.85 157.03 19.65Q155.5 20.45 153.34 20.45ZM153.67 10.69Q154.84 10.69 155.7 10.21Q156.56 9.74 157.05 8.86Q157.54 7.97 157.54 6.73Q157.54 5.5 157.08 4.58Q156.63 3.65 155.79 3.13Q154.94 2.61 153.76 2.61Q152.61 2.61 151.78 3.12Q150.95 3.63 150.5 4.55Q150.05 5.47 150.05 6.69Q150.05 7.92 150.5 8.81Q150.96 9.71 151.77 10.2Q152.59 10.69 153.67 10.69Z"
WORD_ACCENT = "M135.22 13.31V10.26H144.78V13.31Z"
WORD_W = 162.73


# ---------------------------------------------------------------- SVG
def svg_mark(fg, acc, cut="regular"):
    c = CUTS[cut]
    return (f'<path d="{body(c)}" fill="none" stroke="{fg}" stroke-width="{c["stroke"]}"/>'
            f'<path d="{dpad(c)}" fill="none" stroke="{fg}" stroke-width="{c["stroke"]}" stroke-linecap="round"/>'
            f'<path d="{button(c)}" fill="{acc}"/>')


def svg_word(fg, acc):
    return f'<path d="{WORD_FG}" fill="{fg}"/><path d="{WORD_ACCENT}" fill="{acc}"/>'


def doc(view, body_, title, w=None, h=None):
    size = f' width="{w}" height="{h}"' if w else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}"{size} role="img" aria-label="{title}">'
            f"<title>{title}</title>{body_}</svg>\n")


def lockup(fg, acc):
    # Mark 48 units; wordmark cap height = 0.40 x mark, optically centred; gap 8 units.
    s = 48 * 0.40 / 20
    tx, ty = 48 + 8, (48 - 20 * s) / 2
    total = tx + WORD_W * s + 1
    body_ = svg_mark(fg, acc) + f'<g transform="translate({tx:g} {ty:.3f}) scale({s:.4f})">{svg_word(fg, acc)}</g>'
    return doc(f"0 0 {total:.2f} 48", body_, "Controller-9")


def tile(size, radius, mark_scale):
    """Graphite tile with a soft top light; mark centred. radius 0 = square (iOS masks its own corners)."""
    inset = (size - 48 * mark_scale) / 2
    rr = f' rx="{radius}"' if radius else ""
    return (f'<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1A1D20"/>'
            f'<stop offset="1" stop-color="#0D0F10"/></linearGradient></defs>'
            f'<rect width="{size}" height="{size}"{rr} fill="url(#g)"/>'
            + (f'<rect x="1.5" y="1.5" width="{size - 3}" height="{size - 3}" rx="{radius - 1.5}" fill="none" stroke="{TEXT}" stroke-opacity=".08" stroke-width="3"/>' if radius else "")
            + f'<g transform="translate({inset:g} {inset:g}) scale({mark_scale:g})">{svg_mark(TEXT, ACCENT)}</g>')


# ---------------------------------------------------------------- Android VectorDrawable
def vd_path(d, stroke=None, width=None, fill=None, cap="butt"):
    a = [f'android:pathData="{d}"']
    if stroke: a += [f'android:strokeColor="{stroke}"', f'android:strokeWidth="{width}"', f'android:strokeLineCap="{cap}"', 'android:strokeLineJoin="round"']
    a.append(f'android:fillColor="{fill or "#00000000"}"')
    return "<path " + " ".join(a) + " />"


def vd(w, h, vw, vh, body_):
    return ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by brand/build.py — edit there, not here. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{w}dp" android:height="{h}dp" '
            f'android:viewportWidth="{vw}" android:viewportHeight="{vh}">\n    ' + "\n    ".join(body_) + "\n</vector>\n")


def vd_mark(fg, acc, cut="regular"):
    c = CUTS[cut]
    return [vd_path(body(c), fg, c["stroke"]), vd_path(dpad(c), fg, c["stroke"], cap="round"), vd_path(button(c), fill=acc)]


def main():
    out = ROOT / "brand" / "svg"
    out.mkdir(parents=True, exist_ok=True)
    files = {
        "controller9-mark.svg": doc("0 0 48 48", svg_mark(TEXT, ACCENT), "Controller-9"),
        "controller9-mark-on-light.svg": doc("0 0 48 48", svg_mark(GRAPHITE, ACCENT_ON_PAPER), "Controller-9"),
        "controller9-mark-mono-light.svg": doc("0 0 48 48", svg_mark("#FFFFFF", "#FFFFFF"), "Controller-9"),
        "controller9-mark-mono-dark.svg": doc("0 0 48 48", svg_mark("#000000", "#000000"), "Controller-9"),
        "controller9-mark-small.svg": doc("0 0 48 48", svg_mark(TEXT, ACCENT, "small"), "Controller-9"),
        "controller9-wordmark.svg": lockup(TEXT, ACCENT),
        "controller9-wordmark-on-light.svg": lockup(GRAPHITE, ACCENT_ON_PAPER),
        "controller9-wordmark-mono-light.svg": lockup("#FFFFFF", "#FFFFFF"),
        "controller9-wordmark-mono-dark.svg": lockup("#000000", "#000000"),
        "controller9-logotype.svg": doc(f"0 -1 {WORD_W:g} 22", svg_word(TEXT, ACCENT), "Controller-9"),
        "controller9-app-icon.svg": doc("0 0 1024 1024", tile(1024, 228, 15.5), "Controller-9"),
        "controller9-app-icon-square.svg": doc("0 0 1024 1024", tile(1024, 0, 15.5), "Controller-9"),
        "controller9-favicon.svg": doc("0 0 48 48", f'<rect width="48" height="48" rx="11" fill="{GRAPHITE}"/>'
                                      f'<g transform="translate(4 4) scale(.8333)">{svg_mark(TEXT, ACCENT, "small")}</g>', "Controller-9"),
    }
    for name, text in files.items():
        (out / name).write_text(text)

    # Desktop companion: served next to index.html.
    ui = ROOT / "hosts" / "app" / "ui" / "brand"
    ui.mkdir(parents=True, exist_ok=True)
    for name in ("controller9-mark.svg", "controller9-wordmark.svg", "controller9-mark-small.svg"):
        (ui / name).write_text(files[name])

    # Website.
    web = ROOT / "site" / "brand"
    web.mkdir(parents=True, exist_ok=True)
    for name in ("controller9-mark.svg", "controller9-wordmark.svg", "controller9-favicon.svg", "controller9-app-icon.svg"):
        (web / name).write_text(files[name])

    # Android: launcher uses the 108dp adaptive grid (66dp safe zone).
    res = ROOT / "apps" / "android" / "app" / "src" / "main" / "res" / "drawable"
    (res / "c9_mark.xml").write_text(vd(48, 48, 48, 48, vd_mark(TEXT, ACCENT)))
    (res / "c9_mark_small.xml").write_text(vd(24, 24, 48, 48, vd_mark(TEXT, ACCENT, "small")))
    word = [vd_path(WORD_FG, fill=TEXT), vd_path(WORD_ACCENT, fill=ACCENT)]
    (res / "c9_logotype.xml").write_text(vd(round(WORD_W / 22 * 20), 20, WORD_W, 22, ['<group android:translateY="1">'] + word + ["</group>"]))
    s, off = 64 / 48, 22  # 64dp mark centred in the 108dp canvas
    grp = f'<group android:scaleX="{s:g}" android:scaleY="{s:g}" android:translateX="{off}" android:translateY="{off}">'
    (res / "ic_launcher_foreground.xml").write_text(vd(108, 108, 108, 108, [grp] + vd_mark(TEXT, ACCENT) + ["</group>"]))
    (res / "ic_launcher_monochrome.xml").write_text(vd(108, 108, 108, 108, [grp] + vd_mark("#FFFFFF", "#FFFFFF") + ["</group>"]))
    print(f"wrote {len(files)} SVGs, desktop and web copies, Android drawables")


if __name__ == "__main__":
    main()
