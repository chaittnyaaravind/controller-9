# Feature / platform support (as of 2026-10-17 — evidence level shown)

| Feature | Android | iOS | macOS host | Windows host | Linux host |
|---|---|---|---|---|---|
| Builds | ✅ debug APK | ⛔ needs Xcode | ✅ CLI + app | ⏳ Rust type-checks; driver unbuilt | ⏳ type-checks |
| Runs | ⏳ no device yet | ⛔ | ✅ host (CLI) | ⏳ | ⏳ |
| Pairing QR / code | ✅ protocol proven (JVM) | ✅ protocol proven (Swift) | ✅ | ✅ shared core | ✅ shared core |
| OS-visible controller | n/a | n/a | ⛔ Apple entitlement | ⏳ VHF driver (HID, not XInput) | ⏳ uinput |
| Rumble to phone | ✅ client | ✅ client (Core Haptics) | ⛔ | ✗ not yet | ⏳ FF_RUMBLE |
| Four players | ✅ | ✅ | ✅ core | ✅ core | ✅ core |
| Motion steering | ✅ | ✅ | — | — | — |

✅ implemented and verified to the stated level · ⏳ implemented, awaiting hardware/OS test ·
⛔ blocked externally · ✗ not implemented. No row claims game compatibility yet.
