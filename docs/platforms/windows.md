# Windows host and NineVhf driver

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
Verify: Device Manager → Human Interface Devices → "9-controller virtual gamepad bus"; then run the
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
