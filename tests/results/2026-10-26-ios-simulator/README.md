# iPhone app 0.2.2 in the iOS Simulator, against a real host (2026-10-26)

iPhone 18 Pro simulator, Debug build signed ad hoc (`CODE_SIGN_IDENTITY=-`), host `controller9 host --mock
--trace-input` on the same Mac. Paired with the debug-only `-pair-uri <ninectl://pair?...>` launch argument
(the Simulator has no camera for the QR code), then reconnected on its own after a relaunch (Wi-Fi / LAN,
round trip 0.4–0.7 ms).

`ControllerAccuracyUITests` pressed every control of the Xbox-style layout through the real touch pipeline;
`tools/ios-ci/check-trace.py` matched each step to the host's received state: **20/20 PASS**
(`accuracy.txt`). Screenshots: Xbox-style, GameCube-style and arcade stick layouts, connected.

Bug found and fixed: with no Keychain access (an unsigned Simulator build) every
`IdentityStore.keyPair()` call made a new identity, so the session after pairing used a different key, the
host answered "not paired" and the app deleted the pairing. The identity is now kept for the process.
Also fixed: the connected status pill ran into the L/R triggers on wide screens (now "P1 · Wi-Fi / LAN";
VoiceOver still reads the computer's name), and square arcade triggers were lettered larger than the
buttons beside them.

Not covered by the Simulator: motion steering and gyro, haptics, Bluetooth LE, a real device's Keychain.
