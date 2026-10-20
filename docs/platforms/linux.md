# Linux host

```sh
sudo modprobe uinput
echo uinput | sudo tee /etc/modules-load.d/9controller.conf
sudo cp hosts/linux/packaging/60-9controller-uinput.rules /etc/udev/rules.d/
sudo udevadm control --reload && sudo udevadm trigger      # then log out and in
cargo run --release -p ninectl -- status                   # expect: linux-uinput, available
cargo run --release -p ninectl -- host --pair
```
Verify with `evtest` (device "Controller-9 (Xbox 360 compatible) #1") and SDL `testcontroller`.
Firewall: allow TCP 47901 and UDP 47900 on the LAN (e.g. `sudo ufw allow 47900/udp`,
`sudo ufw allow 47901/tcp`). The `.deb` from `cargo tauri build` installs the udev rule and
module-load file.

Rumble: games' FF_RUMBLE effects are forwarded to the phone.

Status: **runs on Linux** (Ubuntu 24.04 aarch64): the virtual pad is created, SDL recognises it as
an Xbox 360 controller, every control and rumble verified with a simulated controller
([results](../../tests/results/2026-10-19-linux-uinput-vm.md)). Not yet: a phone over radio, a real game, x86_64.

## Permission: desktop vs headless
The udev rule uses `uaccess`: the user **logged in at the desktop** gets access, nobody else. Over SSH,
or on a headless box, `controller9 status` correctly says "no permission". There, use a group instead
(it gives every member of the group the power to create input devices):

```sh
sudo groupadd -f uinput && sudo usermod -aG uinput "$USER"
echo 'KERNEL=="uinput", SUBSYSTEM=="misc", GROUP="uinput", MODE="0660", OPTIONS+="static_node=uinput"' \
  | sudo tee /etc/udev/rules.d/61-9controller-uinput-group.rules
sudo udevadm control --reload && sudo udevadm trigger      # then log in again
```

## Verify without a phone
Checks the whole host path (pairing, encryption, uinput, SDL) with the simulated controller:

```sh
sudo apt install evtest python3-evdev libsdl2-dev        # Fedora: python3-evdev SDL2-devel
gcc -O2 -o /tmp/sdlcheck tools/linux-check/sdlcheck.c $(pkg-config --cflags --libs sdl2)
B=target/release/controller9
$B host --ephemeral --pair --auto-approve --no-advertise --no-ble &      # throwaway identity, test only
python3 tools/linux-check/evcheck.py 16 > ev.jsonl &                    # evdev: device + every event
/tmp/sdlcheck 16 > sdl.jsonl &                                          # SDL: name, mapping, events
$B sim --host 127.0.0.1:47901 --code --pattern sweep --duration 10.2
```
Expect in `sdl.jsonl`: `"controller":"X360 Controller"`, then a, b, x, y, leftshoulder, rightshoulder,
back, start, leftstick, rightstick, dpup, dpdown, dpleft, dpright, guide, then leftx, lefty, rightx,
righty, lefttrigger, righttrigger. `python3 tools/linux-check/evcheck.py 6 rumble` plays two FF_RUMBLE
effects like a game; the simulator then reports `feedback_events` > 0.
A Linux VM on a Mac is enough (QEMU + an Ubuntu arm64 cloud image; `uinput` is built into its kernel).
