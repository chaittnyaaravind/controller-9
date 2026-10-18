# 9-controller — Phase 0 Feasibility

Status as of 2026-10-17 (Phase 0). **Update:** JDK 21, Android SDK (36, 37.0), Gradle, Rust and XcodeGen are now installed; see docs/BUILD.md. VHF supports kernel-mode (KMDF) source drivers only — ADR 0007 corrects the earlier UMDF2 note. "Verified" means run on this machine with the output recorded here.
Everything else is research or unverified and is labelled as such.

## Environment (verified)

| Item | State |
|---|---|
| Dev host | macOS 27.0.1 (26A434), Apple Silicon arm64 |
| Git | 2.54.0 |
| JDK | **missing** — blocks Android build |
| Android SDK / adb / Gradle | **missing** — blocks APK build, install and device tests |
| Android device | **unknown** — cannot enumerate without adb |
| Xcode | Command Line Tools only (clang, Swift 6.4); no full Xcode, so no signed app/system-extension builds |
| Windows / Linux machine or VM | **none available** (no Docker, UTM, Parallels, VirtualBox, QEMU) |
| Python 3.14, Node 26, Homebrew 7 | present |
| REA 6.5.0 | CLI verified callable (`npx -y rea-agents@6.5.0 --help`). MCP server registered in `~/.claude.json` but **not loaded in this session** (needs a Claude Code restart). No Ghidra/IDA/jadx engine installed. |
| Android skill | installed; architecture guidance adopted, but its pinned versions (Kotlin 1.9, 2024 Compose BOM) are stale — use current stable releases (ADR pending) |

## Matrix

| Platform | Best route found | Evidence | Cost / friction | Key risks | Smallest PoC |
|---|---|---|---|---|---|
| **Windows** | (1) Own UMDF2 driver on Microsoft **Virtual HID Framework** exposing an HID gamepad (Xbox One/Series HID profile is visible to XInput via XInputHID). (2) Licensed **libvirtualhid** driver (LizardByte; MSI, Azure Trusted Signing). (3) ViGEmBus — **archived Nov 2023, do not adopt**. | Research only: Microsoft provides no supported public virtual-XInput API; libvirtualhid docs report XInput enumeration on one desktop, none in headless CI; Xbox 360/XUSB emulation relies on an undocumented contract. `InputInjector.InjectGamepadInput` needs the restricted `inputInjectionBrokered` capability and its visibility to XInput games is undocumented. | Own driver: WDK, Azure Trusted Signing (paid), admin install. libvirtualhid: source-available licence (LB-SAL 1.0) + per-machine licence for driver-backed devices; AMD64 only. | Anti-cheat may block virtual HID; XInput visibility must be proven per Windows build; licensing cost/terms. | On a Windows 11 PC: create a VHF HID gamepad (test-signed, test mode) and confirm in `joy.cpl` + an XInput tester. **Not run — no Windows machine.** |
| **Linux** | **uinput** (`/dev/uinput`) for Xbox-style evdev gamepad; `uhid` for descriptor-exact devices. | Research only; standard kernel interfaces. | Needs udev rule / `input` group for non-root access; no signing. | Distro packaging; Steam Input may re-map. | On any Linux box: create uinput gamepad, verify with `evtest` and SDL `testcontroller`. **Not run — no Linux machine.** |
| **macOS** | Requires Apple-granted **`com.apple.developer.hid.virtual.device`** (CoreHID/IOHIDUserDevice). Alternatives: DriverKit HID dext (separate entitlements + system-extension approval) or licensed libvirtualhid macOS broker. | **Verified:** `hosts/macos/poc/vhid_probe.c` — unentitled `IOHIDUserDeviceCreateWithProperties` **returns NULL** on macOS 27.0.1. Research: entitlement requests reported pending for months; an Apple engineer notes GameController.framework has checks that may ignore virtual HID devices. | Paid Apple Developer account, entitlement approval (uncertain), notarization, full Xcode. | Even with the entitlement, games using GameController.framework may not see the device. **Do not promise macOS support.** | Request the entitlement; once granted, rerun the probe signed and test with SDL `testcontroller` and a GameController-based app. |
| **Android** | Kotlin/Compose app; `SensorManager` game rotation vector + gyroscope at an explicit sampling period. | None yet. | Needs JDK 17+, Android SDK, a physical device. | Sensor rate/coordinate differences by vendor; background throttling. | Sensor test screen logging rate + axes under load. **Blocked: no SDK/device.** |
| **Network** | UDP datagrams for high-frequency input; separate control channel for pairing/session. | **Verified on loopback** (`tools/netprobe/netprobe.py`): 1200 frames at 120.0 Hz; 67 simulated drops → receiver counted exactly 67 lost (5.6%); 0 invalid; RFC 3550 jitter 0.045 ms; RTT n=100 p50 0.236 ms, p95 0.327 ms, p99 0.378 ms; stale-input timeout fired once (250 ms) and reset to neutral. | — | Loopback proves instrumentation only — **no Wi-Fi or phone data yet**. v0 frames are unauthenticated (test-only). Stale/duplicate discard path not yet exercised. | Same probe with the Android app as sender over Wi-Fi. |

Note: a first probe run reported RTT ≈ 8.4 ms; that was a measurement bug (pongs only read once per 8.33 ms frame tick) and was fixed before the run above.

## Decisions needed / blockers

1. Approve installing a JDK and the Android SDK command-line tools (needed for any APK).
2. Provide a physical Android device with USB debugging (sensor + Wi-Fi proofs).
3. Provide a Windows 11 PC and/or a Linux machine (virtual-gamepad proofs). macOS cannot host either.
4. Windows backend: own VHF driver (signing cost, full control) vs. licensed libvirtualhid (faster, licence terms). Decide after the Windows PoC.

## Sources

- libvirtualhid Windows driver notes — https://docs.lizardbyte.dev/projects/libvirtualhid/latest/md_docs_2windows-driver.html
- libvirtualhid repository — https://github.com/lizardByte/libvirtualhid
- Sunshine: transition away from ViGEmBus — https://github.com/LizardByte/Sunshine/issues/3527
- InputInjector.InjectGamepadInput — https://learn.microsoft.com/en-us/uwp/api/windows.ui.input.preview.injection.inputinjector.injectgamepadinput
- Apple forums: virtual-HID entitlement path for a gamepad app — https://developer.apple.com/forums/thread/845599
- Apple forums: DriverKit virtual-device entitlement superseded by CoreHID — https://developer.apple.com/forums/thread/840311
- Apple forums: virtual controllers via Game Controller framework — https://developer.apple.com/forums/thread/812774
