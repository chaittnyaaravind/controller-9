# ADR 0007 — Windows: own KMDF Virtual HID Framework driver

**Status:** accepted (2026-10-17); **needs a Windows build/run to confirm**

**Options considered.**
| Option | Verdict |
|---|---|
| ViGEmBus | Archived Nov 2023; unmaintained kernel driver — rejected |
| `InputInjector.InjectGamepadInput` | Restricted capability (`inputInjectionBrokered`), packaged-app only, XInput visibility undocumented — rejected |
| libvirtualhid (LizardByte) | Maintained, but the Windows driver is source-available (LB-SAL 1.0) with per-machine licensing — documented alternative |
| **Own VHF driver** | Microsoft-documented framework; full control; needs signing |

**Decision.** `hosts/windows/driver/NineVhf` — a KMDF driver (VHF supports kernel-mode HID source
drivers only, per Microsoft's VHF documentation) that creates up to four HID gamepads. The host
passes 15-byte HID reports through IOCTLs; closing the handle deletes the owner's pads (no stuck
input after a crash).

**Consequences.** Visible to DirectInput, Raw Input, Windows.Gaming.Input, SDL and Steam Input.
Not an XInput device: XInput-only games need Steam Input. Distribution requires Microsoft
attestation signing (EV certificate + Partner Center); development uses test-signing mode.
