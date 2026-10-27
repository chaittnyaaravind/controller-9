# iPhone 15 (iOS 27.0.1), Controller-9 0.2.2 build 3 — real-device run (2026-10-27)

Built and signed with a free Apple ID (Xcode automatic signing; the 19 Oct profiles had expired on
26 Oct, so the 0.2.1 install had stopped launching), installed with `devicectl`, profile trusted on the
phone by the owner. Host: `controller9 host --mock --trace-input` on the MacBook Air.

| Check | Result |
|---|---|
| Install over 0.2.1, launch | runs; reconnected to the paired Mac host by itself |
| Touch accuracy over Wi-Fi (stream link) | **20/20** — every control sends only itself (`accuracy-wifi.txt`) |
| **Bluetooth LE link to the computer app** (F5) | iPhone Wi-Fi turned off by the owner: the session dropped and came back over **Bluetooth LE** by itself; touch accuracy over Bluetooth only **20/20** (`accuracy-bluetooth.txt`, `connections.txt`) |
| Rumble | host sent a heavy/light test rumble every 4 s (23 sent); the owner felt them |
| Gyro aim (turned on through Settings by a UI test) | full right-stick travel both ways on both axes by hand (−100%…+100% x and y, 5,530 samples). Free-hand turns mix axes, so the left/right/up/down *direction* is covered by unit tests, not proven by this run |
