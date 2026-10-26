# Design system — "Lit Hardware" (Android) / "graphite precision" (desktop)

Android (`ui/theme/Theme.kt`, `ui/Common.kt`) moved to **Lit Hardware** on 2026-10-25: the phone is a
graphite instrument lit by one colour that belongs to the game being played. The desktop companion
(`hosts/app/ui/styles.css`) still uses graphite precision below. Components use tokens, never raw values.

## Lit Hardware (Android)

- **Game accents** (`Accent`): Volt `#C6F432` (default), Magenta `#FF2E88`, Ember `#FF6A1A`, Cyan
  `#22D3EE`, Violet `#A78BFA`, Gold `#FFC53D`, each with an ink colour for text on it. A game
  (`data/GameStore`) owns one; `LocalAccent` carries it to every screen. One accent per screen.
- **Scene**: bg `#0A0B0D` pulled 4 % toward the accent; surfaces `#121418 / #191C21 / #23272E`;
  two accent pools (11 %) under the grips on the controller, one top-right on Home; film grain 4 %.
- **Controls** (`ui/pad/PadVisuals.kt`): each sits in a well (shadow tinted with the accent),
  convex top-lit cap, 1 px rim lit from above; pressed = accent cap + glow + 5 % travel. Sticks:
  gate ring with 8 index marks, concave knob, deflection trail. Unavailable controls stay dashed.
- **Type**: Geist (variable, 400/510/600) for everything, Geist Mono for numbers; bundled in
  `res/font` (SIL OFL, `apps/android/licenses/Geist-OFL.txt`). Hero 40/41 sp −1.4.
- **Radius**: 10 (small), 20 (cards), pill. Effects limited to wells/glow, scene pools, grain.
- **Controller Lock**: screen pinning (`ControllerLock`), hold LOCKED to unlock; edge swipes are
  excluded from the back gesture on the controller.

The tables below describe the desktop companion.

## Identity

Logo: `brand/` (see `brand/README.md`). A squared stick gate forms the **C**; inside it a squared
**9** whose bowl is the thumbstick cap (the lime dot); one squared wave leaves the gate opening —
the link. The logotype is drawn from the same strokes (no font file), so it renders identically on
Android (vector drawables), the desktop app (SVG) and the app icons (rasterized by `brand/raster.sh`).

## Colour

| Token | Value | Role |
|---|---|---|
| bg | `#0B0C0D` | page |
| s1 / s2 / s3 | `#111315` / `#171A1C` / `#1E2225` | surfaces, controls, pressed/disabled |
| text | `#EEF0F1` | primary text |
| muted | text @ 66 % | secondary text (7.9 : 1 on bg) |
| dim | text @ 46 % | tertiary text, captions (4.7 : 1 on bg — AA) |
| faint | text @ 22 % | decoration only (unavailable-control outlines), never text |
| line / line2 | text @ 8 % / 14 % | hairlines |
| accent | `#C6F432` lime | live, active, primary action, pressed control — nothing else |
| accentInk | `#11140A` | text on accent |
| warn / bad | `#F5B14C` / `#F2675A` | attention / failure |

Rules: secondary text is the text colour at lower alpha (never a separate grey); borders are 1 px
hairlines; no gradients, glows or drop shadows; accent appears in at most a few places per screen.

## Type

System sans (Roboto / SF Pro) for UI, system monospace for numbers and IDs.

| Role | Size / line | Weight | Use |
|---|---|---|---|
| display | 30 / 33 sp, −0.6 sp | 500 | screen titles |
| title | 19 / 24 sp | 500 | card and section titles |
| body | 15 / 22 sp | 400 | rows, buttons |
| small | 13 / 18 sp | 400 | details, footers |
| label | 11 / 14 sp, +0.7 sp, UPPERCASE | 500 | micro-labels, eyebrows, pills |
| metric | 22 / 26 sp mono | 400 | latency, rates |

## Shape, space, size

Radius family: 8 (chips, inputs, inspector), 14 (sections, cards), pill (buttons, status).
Spacing on a 4/8 scale: gutter 20, gap 12, section 28. Minimum touch target 48 dp (buttons,
rows, chips, controller targets via `PadGeometry.hit`).

## Components

`Page` (back, eyebrow, title, scrolling column capped at 720 dp), `Section` (micro-label + one
surface with hairline-divided rows — preferred over stacks of cards), `ListRow`, `PrimaryButton`
(lime pill), `SecondaryButton` (hairline pill), `Choice` (single-choice chip), `Pill` (status
capsule with tone dot), `Metric`, `BrandMark`, `Wordmark`, `PadDiagram`.

## Status language

| Tone | Dot | Meaning |
|---|---|---|
| OK | lime | link healthy **and** replies arriving (Home: last host reply < 1 s Wi-Fi / < 2 s BLE) |
| WARN | amber | connecting, reconnecting, stale replies, or the computer has no virtual controller |
| BAD | red | failed / offline |
| IDLE | dim | nothing in progress |

A spinner is shown only in Connecting / Reconnecting states (and never with reduced motion).
"Connected" is never shown from process state alone. The desktop app labels each player
*Live* (frames < 1 s old), *Stale* (no input > 1 s), *Holding slot* (disconnected, inside the
reconnect grace) or *Waiting for input* (`host::slot_health`).

## Motion

Hovers/presses 120 ms (`--ease-hover`); panel entry 400 ms (`--ease-out`), only when a player
joins; no idle animation. Controller presses are instant state changes (no animation). Android
reads "Remove animations" (`LocalReducedMotion`) and drops the spinner; the desktop app honours
`prefers-reduced-motion`.

## Accessibility

Contrast as above; every controller control has a content description ("Cross, sends Face
button — bottom") and a pressed/released state; previews describe the layout including which
controls are unavailable; status changes on Home are a polite live region; dialogs and rows are
48 dp; the desktop app is keyboard-navigable with visible focus rings.
