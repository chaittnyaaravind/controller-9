# Linux host

```sh
sudo modprobe uinput
echo uinput | sudo tee /etc/modules-load.d/9controller.conf
sudo cp hosts/linux/packaging/60-9controller-uinput.rules /etc/udev/rules.d/
sudo udevadm control --reload && sudo udevadm trigger      # then log out and in
cargo run --release -p ninectl -- status                   # expect: linux-uinput, available
cargo run --release -p ninectl -- host --pair
```
Verify with `evtest` (device "9-controller (Xbox 360 compatible) #1") and SDL `testcontroller`.
Firewall: allow TCP 47901 and UDP 47900 on the LAN (e.g. `sudo ufw allow 47900/udp`,
`sudo ufw allow 47901/tcp`). The `.deb` from `cargo tauri build` installs the udev rule and
module-load file.

Rumble: games' FF_RUMBLE effects are forwarded to the phone.

Status: type-checks for x86_64 and aarch64 Linux; **not yet run on Linux.**
