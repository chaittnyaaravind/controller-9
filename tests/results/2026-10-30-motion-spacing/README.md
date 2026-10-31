# Motion and spacing pass (2026-10-30)

These changes keep the black-glass design. Rules are in `docs/DESIGN_SYSTEM.md` → Motion.

| Change | Where |
|---|---|
| **Press:** every clickable dips to 97 % (100 ms down, 180 ms back), replacing the Material ripple. Buttons, chips and game cards put the click handler before their fill, so the whole shape dips | Android `ui/theme/Motion.kt` (`PressIndication`, `LocalIndication` in `NineTheme`), `ui/Common.kt`, `ui/home/HomeScreen.kt`; iOS `Nine.press` (same curve) |
| **Screens (Android):** the new screen fades in and rises over 320 ms with a strong ease-out, and the old one fades out over 160 ms. Going back, "Remove animations" and the controller screen only fade. Before, every screen used the library's default 700 ms fade | `MainActivity.kt`, `Motion.kt` |
| **Status dots** cross-fade between states over 180 ms | `Common.kt` |
| **Games rail** runs to the screen edges, and its first card starts on the page margin | `HomeScreen.kt` |
| The last group of links has a heading ("More"), like every other group | `HomeScreen.kt` |
| **Status dots** sit on the first line of a title that wraps, not halfway down it | `HomeScreen.kt` |
| **Button rows wrap** to a new line instead of cutting a label off (Home, Connection check, Calibrate, Layout detail, Profiles). Profiles' Material buttons replaced by the app's pills | several |

## Evidence (realme 12, release build)

| Check | File |
|---|---|
| Rail reaches the screen edge | `home-rail-to-edge.png` |
| "More" heading, dots on the first title line | `home-more-label-dots.png` |
| Connection check: full "Enter the address by hand" label. An intermediate build cut it to "Enter the address by"; this was caught on the phone and fixed | `doctor-buttons-wrap.png` |
| "Edit game" at rest vs held: the whole pill dips (≈5 px each side) | `press-idle.png`, `press-down.png` |
| `tests/run_all.sh` | all PASS |
| iPhone app (simulator SDK) | BUILD SUCCEEDED — not run on a device |

Not measured: the screen transitions frame by frame (they were only seen working while navigating).
Not changed: dialog buttons (Material `TextButton`) still use the ripple.
