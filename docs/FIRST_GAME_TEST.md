# First real game test

Goal: the first evidence that a game sees Controller-9 as a controller. Everything verified so far
stops at the host core (the Mac backend is blocked by the missing entitlement). Linux is the
fastest path (no driver signing); Windows needs test-signing or a signed driver.

## Option A — Linux (recommended first): about 30 minutes

Any x86_64 or aarch64 Linux with a desktop (Ubuntu 24.04+ / Fedora 40+), on the same network as
the phone (or with Bluetooth for the BLE path; BLE needs `libdbus-1-dev` at build time).

```sh
# 1. Toolchain + source
curl https://sh.rustup.rs -sSf | sh            # if Rust is missing
sudo apt install build-essential pkg-config libdbus-1-dev evtest   # Fedora: dnf install dbus-devel evtest
git clone <repo> && cd 9-controller

# 2. uinput access (docs/platforms/linux.md)
sudo modprobe uinput
sudo cp hosts/linux/packaging/60-9controller-uinput.rules /etc/udev/rules.d/
sudo udevadm control --reload && sudo udevadm trigger   # then log out and in
sudo ufw allow 47900/udp; sudo ufw allow 47901/tcp; sudo ufw allow 47902/tcp

# 3. Host
cargo run --release -p ninectl -- status        # expect: linux-uinput, available
cargo run --release -p ninectl -- host --pair --stats-every 1
```

Pair the phone (Home → Pair a computer → scan the QR or Pair with code), open the controller.

### Pass criteria (record each as PASS/FAIL with evidence)

| Check | Tool | Expected |
|---|---|---|
| Device exists | `evtest` | "Controller-9 (Xbox 360 compatible) #1" listed |
| Bottom face button (A / Cross / Nintendo B) | `evtest` | `BTN_SOUTH` (0x130) 1 → 0 |
| Right face button | `evtest` | `BTN_EAST` (0x131) |
| Left / top face button | `evtest` | `BTN_X` (0x133) / `BTN_Y` (0x134) — xpad convention |
| Bumpers, View/Create/−, Menu/Options/+, Guide/PS/Home, stick presses | `evtest` | `BTN_TL`/`BTN_TR`, `BTN_SELECT`, `BTN_START`, `BTN_MODE`, `BTN_THUMBL`/`BTN_THUMBR` |
| Sticks | `evtest` | `ABS_X/Y`, `ABS_RX/RY` −32768…32767 (Y down positive) |
| Triggers / Racing pedals | `evtest` | `ABS_Z` (left), `ABS_RZ` (right) 0…max; 50 % pedal ≈ half |
| D-pad | `evtest` | `ABS_HAT0X/HAT0Y` −1/0/1 |
| Safe release | `evtest` + turn phone Wi-Fi/Bluetooth off while holding | every button back to 0, axes centred within ~0.3 s |
| SDL sees a gamepad | `testcontroller` (SDL 2/3 test app) or Steam → Settings → Controller | recognised, buttons in the right places |
| A real game | any SDL/Steam title (e.g. SuperTuxKart for Racing, a platformer for a preset) | game responds to the phone; note title and version |
| Rumble (optional) | a game with force feedback | the phone vibrates |

## Option B — Windows 11 (test-signed driver)

Needs Visual Studio 2022 + WDK and **test signing**, which requires Secure Boot off — only on a
machine where that is acceptable (not a daily-use or anti-cheat gaming PC). Follow
`docs/platforms/windows.md` (build `NineVhf`, `bcdedit /set testsigning on`, install, `nine-devnode`).
Check with `joy.cpl`, SDL `testcontroller` and a Steam Input title. Note: generic HID, **not XInput**;
XInput-only games need Steam Input. Release builds need Microsoft attestation signing (EV certificate).

## Option C — macOS

Blocked until Apple grants `com.apple.developer.hid.virtual.device`. Request text:
`docs/platforms/macos-entitlement-request.md`. Then acceptance A7 in `docs/TEST_PLAN.md`.

## Record

Write results to `tests/results/<date>-<os>-first-game.md` in the PASS/FAIL/BLOCKED format used by
the other result files, and update `docs/SUPPORT_MATRIX.md` only with what was run.
