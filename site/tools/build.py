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
           ("8bitdo-pro2", "8BitDo Pro 2-style", "Symmetric retro · rear paddles"),
           ("gamecube", "GameCube-style", "Big A · C-stick · Z"), ("arcade", "Arcade stick", "Lever · 8 buttons · fighting games")]
CHIP = {"available": ("ok", "Available"), "preview": ("pre", "Preview"), "soon": ("soon", "Coming soon")}


def human(n):
    return f"{n / 1_000_000:.1f} MB" if n >= 1_000_000 else f"{n / 1000:.0f} KB"


def card(p):
    cls, label = CHIP[p["status"]]
    e = html.escape
    out = [f'<div class="card reveal" data-platform="{e(p["id"])}">',
           f'<div class="top"><div><h3>{e(p["name"])}</h3><div class="meta">{e(p["meta"])}</div></div><span class="chip {cls}">{label}</span></div>',
           f'<p>{e(p["text"])}</p>']
    if p["status"] in ("available", "preview") and "files" in p:
        rows, shas = [], []
        for item in p["files"]:
            f = PUB / item["file"]
            if not f.is_file():
                if not DRAFT: sys.exit(f"missing download for {p['id']}: {f}")
                rows.append(f'<span class="btn ghost" aria-disabled="true">{e(item["label"])} · building…</span>'); continue
            data = f.read_bytes()
            rows.append(f'<a class="btn ghost" href="/{e(item["file"])}" download>{e(item["label"])} · {human(len(data))}</a>')
            shas.append(f'{e(item["label"])} {hashlib.sha256(data).hexdigest()}')
        out.append('<div class="row">' + "".join(rows) + "</div>")
        out.append("".join(f'<div class="sha">SHA-256 {x}</div>' for x in shas))
        out.append("</div>")
        return "".join(out)
    if p["status"] in ("available", "preview"):
        f = PUB / p["file"]
        if not f.is_file():
            if not DRAFT: sys.exit(f"missing download for {p['id']}: {f}")
            out.append('<div class="row"><span class="btn ghost" aria-disabled="true">Building…</span></div></div>')
            return "".join(out)
        data = f.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        guide = f'<a class="btn ghost" href="{e(p["guide"])}">Install guide</a>' if p.get("guide") else ""
        out.append(f'<div class="row"><a class="btn {"primary" if p["status"] == "available" else "ghost"}" href="/{e(p["file"])}" download>'
                   f'Download · {human(len(data))}</a>{guide}</div>')
        out.append(f'<div class="sha">SHA-256 {sha}</div>')
    out.append("</div>")
    return "".join(out)


SITE_URL = "https://controller-9.vercel.app"


def write_altstore(ip, ipa_bytes):
    """AltStore / SideStore source, generated from the IPA itself so its permissions always match
    (AltStore refuses an install whose listed permissions differ from the app's Info.plist)."""
    import io, plistlib, zipfile, datetime
    z = zipfile.ZipFile(io.BytesIO(ipa_bytes))
    info = plistlib.loads(z.read(next(n for n in z.namelist() if n.count("/") == 2 and n.endswith(".app/Info.plist"))))
    privacy = {k: v for k, v in info.items() if k.startswith("NS") and k.endswith("UsageDescription")}
    shots = sorted(x.name for x in (PUB / "assets" / "screens").glob("*.png")) if (PUB / "assets" / "screens").is_dir() else []
    src = {
        "name": "Controller-9", "subtitle": "Your phone is the controller", "identifier": "app.vercel.controller-9",
        "sourceURL": f"{SITE_URL}/altstore.json", "website": SITE_URL, "iconURL": f"{SITE_URL}/brand/apple-touch-icon.png",
        "tintColor": "#C6F432", "nsfw": False, "featuredApps": [info["CFBundleIdentifier"]],
        "apps": [{
            "name": "Controller-9", "bundleIdentifier": info["CFBundleIdentifier"], "developerName": "Controller-9",
            "subtitle": "Your phone is the controller", "category": "utilities", "tintColor": "#C6F432",
            "localizedDescription": ("Turn your iPhone into a game controller for your computer: five familiar layouts, grip-relative tilt "
                                     "steering and rumble, over your own Wi-Fi or Bluetooth. No account, no cloud. Needs the free "
                                     "Controller-9 companion on your Mac, Windows or Linux computer."),
            "iconURL": f"{SITE_URL}/brand/apple-touch-icon.png",
            "screenshots": [f"{SITE_URL}/assets/screens/{x}" for x in shots],
            "versions": [{
                "version": info["CFBundleShortVersionString"], "buildVersion": info["CFBundleVersion"],
                "date": datetime.date.today().isoformat(), "downloadURL": f"{SITE_URL}/{ip['file']}", "size": len(ipa_bytes),
                "minOSVersion": info.get("MinimumOSVersion", "17.0"),
                "localizedDescription": "First release: five controller layouts, tilt steering, rumble, Wi-Fi + Bluetooth.",
            }],
            "appPermissions": {"entitlements": [], "privacy": privacy},
        }],
        "news": [],
    }
    (PUB / "altstore.json").write_text(json.dumps(src, indent=2, ensure_ascii=False) + "\n")


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
    # iPhone install guide: published only while the iPhone build is listed (never for "soon").
    ip = by.get("iphone")
    gdir = PUB / "iphone"
    if ip and ip["status"] in ("available", "preview"):
        data = (PUB / ip["file"]).read_bytes()
        g = (SITE / "src" / "iphone.html").read_text()
        g = (g.replace("{{IPA_FILE}}", html.escape(ip["file"])).replace("{{IPA_SIZE}}", human(len(data)))
              .replace("{{IPA_SHA}}", hashlib.sha256(data).hexdigest()).replace("{{IPA_TESTED}}", html.escape(ip["tested"])).replace("{{SITE}}", SITE_URL))
        assert "{{" not in g
        gdir.mkdir(exist_ok=True); (gdir / "index.html").write_text(g)
        write_altstore(ip, data)
    elif (gdir / "index.html").exists():
        (gdir / "index.html").unlink()
    print(f"wrote site/public/index.html ({len(page) // 1000} KB), {sum(p['status'] != 'soon' for p in rel['platforms'])} downloads")


if __name__ == "__main__":
    main()
