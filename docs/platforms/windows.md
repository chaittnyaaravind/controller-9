# Windows host and NineVhf driver

## Default path: ViGEmBus (free, already signed)
The hosts use `nine_pad_windows::native_backend()`: **ViGEmBus** first, NineVhf if installed, else a
"blocked" status whose remedy tells the user to install ViGEmBus.
- ViGEmBus (Nefarius, BSD-3, production-signed by its author) is a kernel bus driver that creates
  Xbox 360 controllers. XInput, DirectInput, Windows.Gaming.Input, SDL and Steam all see them, and
  the game's rumble comes back to the phone.
- **We do not bundle it.** The user installs the official release (v1.22.0) once, from
  github.com/nefarius/ViGEmBus/releases or `winget install ViGEm.ViGEmBus`. That needs admin
  rights once; Controller-9 itself asks for no new permission.
- The project is retired by its author (Aug 2023, no new features), but the signed driver still
  installs and works on Windows 10/11. Some anti-cheat titles refuse virtual pads.
- CI proof: `.github/workflows/installers.yml` → "Game-visible test" installs ViGEmBus (pinned
  SHA-256), runs the real host + simulated phone, and asserts with `tools/windows-check/` that
  XInput sees every button and axis and that rumble reaches the phone.

## Build the driver (Windows 11, x64)
1. Install Visual Studio 2022 (Desktop C++), the matching **Windows SDK and WDK**.
2. `cd hosts\windows\driver\NineVhf` → `msbuild NineVhf.vcxproj /p:Configuration=Release /p:Platform=x64`
   (produces `NineVhf.sys`, `NineVhf.inf`, `NineVhf.cat` under `x64\Release\NineVhf\`).

## Install for development (test signing)
```bat
bcdedit /set testsigning on          & rem reboot afterwards (Secure Boot must be off for test signing)
pnputil /add-driver NineVhf.inf /install
cargo run -p nine-pad-windows --bin nine-devnode -- install Root\NineVhf NineVhf.inf
```
Verify: Device Manager → Human Interface Devices → "Controller-9 virtual gamepad bus"; then run the
host (`ninectl host`) — the backend status should read "NineVhf virtual HID driver ready".
Remove: `nine-devnode remove Root\NineVhf` and `pnputil /delete-driver NineVhf.inf /uninstall`.

## Release signing
Kernel drivers for Windows 10/11 must be signed by Microsoft: EV code-signing certificate →
Windows Hardware Dev Center (Partner Center) → **attestation signing** submission of the CAB.
The NSIS installer (`hosts/app/windows/hooks.nsh`) then installs the signed package with `pnputil`
and creates the device node with `nine-devnode.exe`.

## Compatibility
Generic HID gamepad: DirectInput, Raw Input, Windows.Gaming.Input `RawGameController`, SDL, Steam
Input. **Not XInput** — XInput-only games need Steam Input (or a similar layer). Rumble is not wired
on Windows yet (needs an output report in the descriptor). Anti-cheat may refuse virtual devices.

## Status
Driver source, INF, project, IOCTL client and devnode helper are written; the Rust parts
type-check for `x86_64-pc-windows-msvc`. **Nothing has been built or run on Windows yet.**
