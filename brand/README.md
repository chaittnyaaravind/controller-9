# Controller-9 brand

Original mark — no third-party logos or trade dress. Generated from one geometry source:

    python3 brand/build.py     # SVGs in brand/svg, Android drawables, desktop UI copies
    brand/raster.sh            # desktop app icons (png, icns, ico) — needs Chrome, sips, iconutil

## Construction (48 × 48 grid)

- **Gate / C** — rounded-square stick gate, open to the right, stroke 4.6, radius 10.5, butt ends.
- **9 / stick cap** — squared 9 inside the gate, same stroke; its bowl holds a 3.2-unit lime square: the thumbstick cap.
- **Link** — one squared wave (stroke 2.8) leaving the gate opening, concentric with the gate corners.
- **Logotype** — "CONTROLLER-9" built from the same squared strokes (cap height 20, stroke 3.2, corner radius 6, miter joins limited to 1.6). Lockup: cap height = 0.44 × mark height, gap = 10/48 of the mark.

## Files (`brand/svg`)

| File | Use |
|---|---|
| `controller9-wordmark.svg` | full lockup on dark — app header, desktop sidebar, onboarding |
| `controller9-wordmark-on-light.svg` | full lockup on light backgrounds |
| `controller9-wordmark-mono-light.svg` / `-mono-dark.svg` | single-colour lockups (white / black) |
| `controller9-mark.svg` | compact icon on dark |
| `controller9-mark-small.svg` | ≤ 32 px: drops the wave |
| `controller9-mark-on-light.svg`, `-mono-light.svg`, `-mono-dark.svg` | compact icon variants |
| `controller9-logotype.svg` | logotype alone |
| `controller9-app-icon.svg` | graphite tile master for app icons |

Android: `res/drawable/c9_mark.xml`, `c9_mark_small.xml`, `c9_logotype.xml`, adaptive launcher
`ic_launcher_foreground.xml` + themed `ic_launcher_monochrome.xml`. Desktop: `hosts/app/ui/brand/`,
`hosts/app/src-tauri/icons/`.

## Rules

Keep clear space of ¼ mark height around the mark. Lime only for the stick cap and wave; never
recolour the strokes lime. Below 32 px use the small mark. Don't outline, rotate, add effects, or
set the logotype in a font.
