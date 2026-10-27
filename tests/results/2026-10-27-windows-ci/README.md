# Windows (cloud) — controller visible to games, 2026-10-27

GitHub Windows Server 2025 runner (`windows-probe.yml`, run 40598261890) with the Windows build from
`installers.yml` run 40595522066 (commit 4f9e615), ViGEmBus installed by the app's own `install-driver`,
Microsoft's Xbox 360 driver added (absent on Server). A simulated phone swept every control:

- **XInput** (what most games use): every button in order, all axes both ways, rumble back to the phone.
- **SDL 2.30**: "Xbox 360 Controller", buttons in order, Guide, all axes, rumble supported.

Two earlier failures today were test-harness timing on the shared runner, not the app: a 320 ms stall
skipped LB's press (the sweep now pauses through stalls), and SDL attached after the first press (it now
gets 3 s). A desktop Windows 10/11 PC and Bluetooth pairing with the phone remain untested (no hardware).
