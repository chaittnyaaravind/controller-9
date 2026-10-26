# Controller layouts

Five **interface presets** for the phone's controller screen. They change where controls sit and how
they are labelled. They do **not** emulate any vendor's hardware, and they are not a claim that a game
will treat Controller-9 as that controller. Every preset sends the same protocol-v1 gamepad input
(SPEC §6.3: 15 button bits, two sticks, two analog triggers).

Code: `apps/android/app/src/main/kotlin/dev/ninecontroller/android/pad/` — `PadModel.kt` (logical
actions, parts, glyphs), `PadPresets.kt` (the seven presets as data), `PadMapping.kt` (the single
mapping layer, remap rules, persistence, screen geometry). One renderer
(`ui/pad/PadVisuals.kt`) draws both the live touch surface and every preview. Tests:
`app/src/test/.../PadMappingTest.kt` (18 tests) and `UiLogicTest.kt`.

## Four separate things per control

| Field | Example (DualSense Cross) | Where |
|---|---|---|
| Physical label | "Cross" (drawn as ✕) | `PadElement.label`, `glyph` |
| Displayed position | bottom of the face diamond, (164, 65.5) in the 200 × 100 canvas | `PadElement.cx/cy/w/h` |
| Logical action | `SOUTH` — "Face button — bottom" | `PadMapping.actionOf()` |
| Transmitted value | button A, bit 0 | `Action.button` / `Action.wire` |

## Face buttons are positional

The bottom face button always sends bit 0 ("A"), the right one bit 1, left bit 2, top bit 3. So:

| Position | Xbox-style | DualSense / DualShock 4-style | Switch Pro / 8BitDo Pro 2-style | Sends |
|---|---|---|---|---|
| bottom | A | Cross | B | bit 0 (A) |
| right | B | Circle | A | bit 1 (B) |
| left | X | Square | Y | bit 2 (X) |
| top | Y | Triangle | X | bit 3 (Y) |

Consequence: switching layouts never changes what a thumb position does in a game. For Nintendo
labels this is the same choice Steam Input / SDL make by default for a Pro Controller. A user who
wants Nintendo *label* order (A confirms) can swap A and B in the layout detail screen.

## The seven presets

| Preset | Shape | Shoulders | Centre | Extra | Not carried by protocol v1 |
|---|---|---|---|---|---|
| Xbox-style | asymmetric: left stick high, D-pad low, right stick low | LT RT / LB RB | View → Back, Guide → Guide, Menu → Start | LS RS (stick press) | Share |
| DualSense-style | symmetric: D-pad and face high, sticks low | L2 R2 / L1 R1 | Create → Back, Options → Start, PS → Guide | L3 R3 | Touchpad (touch and click), Mute |
| DualShock 4-style | symmetric, smaller touchpad, Share beside it | L2 R2 / L1 R1 | Share → Back, Options → Start, PS → Guide | L3 R3 | Touchpad |
| Switch Pro-style | asymmetric | ZL ZR / L R | − → Back, + → Start, Home → Guide | LS RS | Capture |
| 8BitDo Pro 2-style | symmetric retro body | L2 R2 / L R | Select → Back, Start → Start, Home → Guide | L3 R3, rear paddles P1 / P2 | Star; paddles start unassigned |
| GameCube-style | asymmetric, big A with B / X / Y around it, C-stick | L R / Z (→ RB), "L click" (→ LB) | Select → Back, Start → Start, Home → Guide (extras for PC) | LS RS | — |
| Arcade stick | flat box: lever (→ D-pad) left, 8 buttons right (X Y RB LB / A B RT LT) | in the button rows | View, Guide, Menu along the top | two small sticks, LS RS | — |

Previews also draw a body silhouette per family (wide grips, long PlayStation grips, flat retro body)
so the cards are distinguishable at a glance. Previews draw no vendor logos; the Guide/PS/Home
buttons use a generic house glyph.

### Unavailable controls

Controls with no protocol input are drawn dashed and dim, are absent from the touch surface's
control list (they can never send anything), and tapping one on the live surface shows why
(for example "Touchpad: Protocol v1 has no touchpad input (neither touch nor click), so it sends
nothing."). Layout details list the same reason.

### Triggers

On presets, triggers are thumb-tapped and send full travel (65535) while held. Analog throttle
remains available in custom profiles (Racing uses depth-sensitive pedals — the input that was
verified over Wi-Fi and Bluetooth LE). The real Switch Pro ZL/ZR are digital; here they are the
analog trigger axes at full travel.

## Remapping

`PadMapping.assign()` enforces:

- Sticks, D-pad and triggers keep their kind; they are never remapped.
- Primary buttons (face, bumpers, centre, stick press) can only be **permuted**. Choosing an action
  another button already sends swaps the two, so no action is lost and none is doubled.
- Paddles may only **mirror** a button that is already on the layout, or be unassigned.
- Unknown controls, unavailable controls and non-button actions are rejected.

Persistence: one DataStore key per preset, `pad_map_<presetId>`, holding only differences from the
defaults, e.g. `face_e=south;face_s=east;paddle_r=bumper_r`. On load the text is validated; anything
unreadable, duplicated, kind-changing or unknown falls back to the preset defaults with a reason.
"Restore defaults" clears the key. The selected layout is the existing `active_profile` key
(`pad:<presetId>`, or a custom profile id).

## Safety (unchanged behaviour)

Every preset goes through the same `ControllerEngine` and touch handlers as before. A layout
change, pause, screen exit or disconnect calls `releaseAll()` first; the host still neutralizes a
slot after 250 ms without valid input. Tests: `layoutChangeOrDisconnectReleasesEverything`,
`pressAndReleaseThroughTheEngine`.

## Orientation

The live controller screen is landscape (either rotation): it is a two-thumb surface and motion
steering assumes landscape. Positions stretch with the screen width while sizes scale uniformly,
and every target is at least 48 dp (`PadGeometry.hit`, tested at 873 × 393, 640 × 360 and
1280 × 800 dp). All other screens — including the previews — work in portrait and landscape.
