# Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Phone doesn't list the computer | router blocks multicast (client isolation), or host discovery off | scan the QR code or enter the address from the desktop app |
| "Can't reach the computer" | firewall | allow TCP 47901 + UDP 47900 (Windows Defender prompt, macOS firewall, `ufw`) |
| Pairing "closed" | pairing window not open | desktop app → *Pair a phone* (2 min window) |
| "rate limited" | 5 attempts/min or 3 rejected codes | wait 60 s |
| QR "expired or used" | tokens are one-time, 5 min | press *New code* |
| "identity key changed" | host reinstalled / different computer at that address | forget the computer on the phone and pair again — only if you trust it |
| Connected, but games see nothing | backend Blocked (macOS) / driver missing (Windows) / uinput permission (Linux) | see the banner's fix text and `docs/platforms/*` |
| Windows game ignores pad | XInput-only title | enable Steam Input for the game |
| Stutter | Wi-Fi congestion | check Lost/Jitter in Diagnostics; use 5 GHz; move closer |
| Steering off-centre | not calibrated | *Center* on the controller screen, hold still 1.5 s |
| Swift tests: "plugin for module 'TestingMacros' not found" | Command Line Tools-only toolchain | use `tools/swift-test.sh <package>` |
