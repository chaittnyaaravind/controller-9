# Controller profile format — schema 1

A profile is one JSON document describing a touch layout plus motion, stick and haptic settings.
Profiles live on the phone; the host only sees the active profile's name (`open.profile`).
Formal schema: `schema/profile.v1.schema.json`. Presets: `profiles/*.json` (bundled by both apps).

## Coordinates

The controller surface is the full screen in the profile's `orientation`. `x, y` is the control's
top-left corner and `w, h` its size, all as fractions of the surface width/height (0..1).
A control must fit: `x + w <= 1`, `y + h <= 1`, `w, h >= 0.03`.

## Control types and binds

| `type` | `bind` | Behaviour |
|---|---|---|
| `button` | `a b x y lb rb view menu ls rs up down left right guide` | press = bit set; `toggle: true` latches |
| `stick` | `left` / `right` | relative-origin virtual stick, radial dead zone `sticks.deadzone` |
| `dpad` | `dpad` | 8-way; diagonal = two bits |
| `trigger` | `lt` / `rt` | vertical analog slider 0..65535 |
| `pedal` | `lt` / `rt` | full-height analog pedal; pressure = touch depth from the top of the pedal |
| `steering` | `motion` | visual wheel indicator; also taps to recenter |

Optional per control: `label` (≤12 chars), `opacity` 0.1–1, `haptic` (default true).

## Motion steering

Gravity in screen coordinates → wheel angle (0 upright, + clockwise) → minus calibrated center →
One Euro filter (`minCutoff` Hz, `beta`) → `÷ lockDeg × sensitivity` → invert → clamp ±1 →
dead zone (rescaled) → `|x|^curve` → axis `lx` or `rx`. Reference: `protocol/rust/src/motion.rs`;
cross-language vectors: `vectors/motion.json`.

## Versioning and migration

`schema` is required. Readers accept every older schema by migrating it forward, and reject newer
ones with a clear "update the app" message — they never silently drop fields. Schema 0 was the
pre-release draft (`version: 0`, `layout[].kind/action/rect`, `steer.range` lock-to-lock); its
migration is defined in `profile.rs::migrate_v0` and pinned by `vectors/profiles.json`.

Import/export is the plain JSON file (`.controller9.json`). Imports are validated before they are saved.
