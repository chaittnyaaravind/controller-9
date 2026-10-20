# Linux uinput host — first run on real Linux (2026-10-19)

**What this is:** the real `controller9` host (Linux uinput backend, built natively in the VM from the
working tree) driven by the simulated controller (`controller9 sim`) over the real protocol (Noise XX
pairing by code, encrypted UDP input, TCP control), observed with evdev and SDL2.

**What this is not:** no phone, no radio and no game. Host and simulator ran on the same VM (loopback),
so RTT figures say nothing about Wi-Fi. SDL is the layer most Linux games and Steam read controllers
through, but no actual game was run (the VM has no GPU/desktop).

Environment: Ubuntu 24.04 LTS arm64 cloud image (kernel 6.8.0-147-generic, `uinput` built in) under
QEMU / Apple Hypervisor on an Apple M5 Mac. Rust 1.99.1. SDL 2.30.0 (Ubuntu package). python3-evdev 1.7.
Checkers: `tools/linux-check/` (evdev recorder + SDL GameController reporter).

## Results

| # | Check (docs/FIRST_GAME_TEST.md) | Result | Evidence |
|---|---|---|---|
| 1 | Native Linux build | **PASS** | `cargo build --release -p ninectl` (with BLE, libdbus) — first native link; before, only `cargo check` cross-target. |
| 2 | Backend status | **PASS, with caveat** | Over SSH: `blocked — no permission to open /dev/uinput`, correct remedy shown. The udev rule uses `uaccess`, which grants only the user logged in at the seat (desktop session); SSH has none. For this headless run the ACL was granted by hand (`setfacl -m u:c9:rw /dev/uinput`), the same ACL `uaccess` sets at a desktop login. Then: `linux-uinput, native, available, "/dev/uinput ready"`. |
| 3 | Device exists | **PASS** | evdev: `Controller-9 (Xbox 360 compatible) #1`, USB 045e:028e, keys BTN_A/B/NORTH/WEST/TL/TR/SELECT/START/MODE/THUMBL/THUMBR, ABS_X/Y/RX/RY −32768…32767, ABS_Z/RZ 0…1023, HAT0X/Y ±1, FF_RUMBLE. |
| 4 | SDL sees a gamepad | **PASS** | `SDL_IsGameController` true: name "X360 Controller", type Xbox 360, GUID `0300e1b55e0400008e02000010010000`, SDL's built-in mapping (`a:b0,b:b1,…,lefttrigger:a2,righttrigger:a5`), `HasRumble` true. |
| 5 | Every control maps correctly | **PASS** | `sim --pattern sweep`: all 15 buttons arrived at SDL as a, b, x, y, leftshoulder, rightshoulder, back, start, leftstick, rightstick, dpup, dpdown, dpleft, dpright, guide — in the sent order, each with press and release. Axes: leftx −/+, lefty, rightx −/+, righty, lefttrigger, righttrigger at full scale. Protocol Y up (`ly−` = down) arrived as SDL `lefty +32767` (down): correct. |
| 6 | Rumble from the game side | **PASS** | Two FF_RUMBLE effects (strong, then weak, 300 ms) uploaded and played on the virtual pad through evdev, as a game does → the controller received 3 feedback packets (`feedback_events: 3`). A phone vibrating is not covered (no phone). |
| 7 | Safe release: controller killed | **PASS** | `kill -9` of the simulator with the stick deflected: ABS_X and ABS_RZ back to 0 after 3.6 ms (the host saw the socket close). |
| 8 | Safe release: silent link loss | **PASS** | iptables DROP on the input and control ports while A was held: last input at +19 ms, then BTN_A 0 and all axes 0 at +273 ms, i.e. 253 ms after the last input (the 250 ms input timeout in SPEC). Control resumed by itself when the drop was removed. |
| 9 | Pad removed at session end | **PASS** | SDL `CONTROLLERDEVICEREMOVED` after the simulator's `bye`. |
| 10 | Send rate | info | Simulator 119 frames/s over 10.3 s; ping RTT p50 0.17 ms, p95 0.24 ms (loopback, not radio). |

## Follow-ups

* The `uaccess` rule is right for a logged-in desktop session. Headless or SSH-only hosts need the
  group rule in `docs/platforms/linux.md`.
* Still to do on Linux: a phone over real Wi-Fi/BLE, an actual game (e.g. SuperTuxKart), Steam Input, x86_64.

Reproduce: `docs/platforms/linux.md` → "Verify without a phone".
