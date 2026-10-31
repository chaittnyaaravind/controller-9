# Custom layouts drawn like controller layouts (2026-10-30)

Before: a layout made with *Customise positions and sizes* was drawn by a separate flat renderer — grey
discs with text, a D-pad of four squares without arrows, no glass — and the in-game bar (one chip wider,
with EDIT) ran over the inner corners of LT and RT.

| Change | Where |
|---|---|
| Custom-layout controls are drawn as the same black-glass parts as the controller layouts (`PadLook.element`): a control customised from a layout keeps that layout's part and glyph (house, menu, view, Cross…), D-pad chevrons, stick wells, analog trigger fill; a round cap stretched long becomes a capsule; pedals and steering keep their own drawing. Opacity is respected | Android `pad/PadLook.kt`, `ControllerScreen.kt`; iOS `NineAppCore/PadLook.swift`, `ControllerView.swift` |
| The in-game bar is limited to the clear gap between the controls along the top edge (`PadLook.topGap`), on every layout; a tight bar shortens the layout name / status, never a button | same |
| Android: *Edit this layout* moved from a bar chip into the layout menu (the bar is one chip shorter) | `ControllerScreen.kt` |

## Evidence

| Check | Result |
|---|---|
| Android `PadLookTest` (every part and glyph of all 7 layouts survives customising, stretched caps, generic parts, bar gap, pedals/steering) | 5/5 |
| iOS `PadLookTests` (same cases) | pass; NineAppCore 42/42 |
| `tests/run_all.sh` | all PASS |
| iPhone app (simulator SDK) | BUILD SUCCEEDED — not run on a device |
| Android emulator (c9-test, 2400×1080), release build, "Xbox-style · custom" | `custom-idle.png` (glass parts, bar clear of LT/RT), `custom-pressed-a.png` (A lit), `layout-menu-edit.png` |

Not checked: the realme 12 (not connected), the iPhone (unavailable).
