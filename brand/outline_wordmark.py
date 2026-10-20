"""Outline 'Controller-9' in Geist 600 → SVG path data (cap height 20, baseline y=20)."""
import json, sys
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
f = instantiateVariableFont(TTFont(sys.argv[1]), {"wght": 600})
upm = f["head"].unitsPerEm; cap = f["OS/2"].sCapHeight
s = 20 / cap; track = -0.02 * upm
cmap = f.getBestCmap(); gs = f.getGlyphSet(); hmtx = f["hmtx"]
def run(text, x0):
    pen = SVGPathPen(gs, ntos=lambda v: f"{v:.2f}".rstrip("0").rstrip("."))
    x = x0
    for i, ch in enumerate(text):
        g = cmap[ord(ch)]
        gs[g].draw(TransformPen(pen, (s, 0, 0, -s, x * s, 20)))
        x += hmtx[g][0] + track
    return pen.getCommands(), x
a, x = run("Controller", 0)
b, x = run("-", x)
c, x = run("9", x)
print(json.dumps({"fg": a + " " + c, "accent": b, "width": round((x - track) * s, 2), "cap": 20}))
