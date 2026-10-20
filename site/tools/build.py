#!/usr/bin/env python3
"""Build site/public/index.html from site/src/index.html.

    python3 site/tools/pads.py && python3 site/tools/build.py

Fills the hero controller (inline SVG, so it can animate), the layout gallery and the download cards
from site/releases.json. Sizes and SHA-256 are computed from the files in site/public; a listed file
that is missing stops the build.
"""
import hashlib, html, json, re, sys
from pathlib import Path

SITE = Path(__file__).resolve().parent.parent
PUB = SITE / "public"
DRAFT = "--draft" in sys.argv  # local preview only: a missing file shows as "Building…"; never deploy a draft
LAYOUTS = [("xbox", "Xbox-style", "Asymmetric sticks · A B X Y"), ("dualsense", "DualSense-style", "Symmetric sticks · touchpad"),
           ("dualshock4", "DualShock 4-style", "Symmetric sticks · Share"), ("switchpro", "Switch Pro-style", "Nintendo labels, positional"),
           ("8bitdo-pro2", "8BitDo Pro 2-style", "Symmetric retro · rear paddles")]
CHIP = {"available": ("ok", "Available"), "preview": ("pre", "Preview"), "soon": ("soon", "Coming soon")}


def human(n):
    return f"{n / 1_000_000:.1f} MB" if n >= 1_000_000 else f"{n / 1000:.0f} KB"


def card(p):
    cls, label = CHIP[p["status"]]
    e = html.escape
    out = [f'<div class="card reveal" data-platform="{e(p["id"])}">',
           f'<div class="top"><div><h3>{e(p["name"])}</h3><div class="meta">{e(p["meta"])}</div></div><span class="chip {cls}">{label}</span></div>',
           f'<p>{e(p["text"])}</p>']
    if p["status"] in ("available", "preview"):
        f = PUB / p["file"]
        if not f.is_file():
            if not DRAFT: sys.exit(f"missing download for {p['id']}: {f}")
            out.append('<div class="row"><span class="btn ghost" aria-disabled="true">Building…</span></div></div>')
            return "".join(out)
        data = f.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        out.append(f'<div class="row"><a class="btn {"primary" if p["status"] == "available" else "ghost"}" href="/{e(p["file"])}" download>'
                   f'Download · {human(len(data))}</a></div>')
        out.append(f'<div class="sha">SHA-256 {sha}</div>')
    out.append("</div>")
    return "".join(out)


def main():
    rel = json.loads((SITE / "releases.json").read_text())
    pad = (PUB / "assets" / "pad-xbox.svg").read_text()
    pad = re.sub(r"<title>.*?</title>", "", pad)
    layouts = "".join(
        f'<div class="card layout reveal"><div class="art"><img src="/assets/pad-{k}.svg" alt="{n} layout" loading="lazy" width="400" height="200"></div>'
        f'<div><h3>{n}</h3><p>{t}</p></div></div>' for k, n, t in LAYOUTS)
    by = {p["id"]: p for p in rel["platforms"]}
    groups = [("Phone", ["android", "iphone"]), ("Computer", ["mac", "windows", "linux"])]
    downloads = "".join(f'<div class="group"><div class="micro">{g}</div><div class="cards" style="--n:{len(ids)}">' + "".join(card(by[i]) for i in ids) + "</div></div>"
                        for g, ids in groups)
    page = (SITE / "src" / "index.html").read_text()
    page = page.replace("{{HERO_PAD}}", pad).replace("{{LAYOUT_CARDS}}", layouts).replace("{{DOWNLOADS}}", downloads)
    assert "{{" not in page, "unfilled placeholder"
    (PUB / "index.html").write_text(page)
    print(f"wrote site/public/index.html ({len(page) // 1000} KB), {sum(p['status'] != 'soon' for p in rel['platforms'])} downloads")


if __name__ == "__main__":
    main()
