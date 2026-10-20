# App Store / TestFlight kit — Controller-9 for iPhone

Status: everything below is ready; **blocked only on the Apple Developer Program ($99/year, owner enrolls).**

## Listing
| Field | Value |
|---|---|
| Name | Controller-9 |
| Subtitle (30) | Your phone is the controller |
| Category | Utilities (secondary: Games is not allowed for a controller utility without gameplay) |
| Age rating | 4+ (no objectionable content) |
| Price | Free |
| Privacy policy URL | https://controller-9.vercel.app/privacy/ |
| Support / marketing URL | https://controller-9.vercel.app/ |
| App Privacy ("nutrition label") | **Data Not Collected** |
| Export compliance | Uses standard encryption (Noise / ChaCha20-Poly1305) only to secure its own connection → exempt; `ITSAppUsesNonExemptEncryption = NO` is already in the build |
| Bundle ID | dev.ninecontroller.ios |
| Screenshots | build/appstore/screens (6.9″: 1320×2868 portrait, 2868×1320 landscape) — retake the controller shot while paired so it shows "P1 · connected" |

**Promotional text (170):** Play PC games with the phone in your hand. Real controller layouts, tilt steering and rumble over your own Wi-Fi or Bluetooth — no account, no cloud.

**Description:**
Controller-9 turns your iPhone into a game controller for your computer.

• Five familiar layouts — Xbox, DualSense, DualShock 4, Switch Pro and 8BitDo Pro 2 styles — plus racing, arcade and your own.
• Tilt to steer: straight ahead is however you hold the phone. Recenter any time.
• Accurate touch: every control sends only itself; the D-pad favours straight directions.
• Rumble from the game, felt on your phone.
• Wi-Fi and Bluetooth together, so a dropped link doesn't drop your game.
• Private by design: pair once with a code, end-to-end encrypted, local network only. No account, no analytics.

Requires the free Controller-9 companion on the computer you play on (Mac, Windows or Linux): controller-9.vercel.app.

**Keywords (100):** controller,gamepad,game controller,joystick,remote,racing,steering,pc gaming,steam,phone controller

**Review notes:** The app needs the free desktop companion to connect. Reviewers can install it from controller-9.vercel.app/#download, choose "Pair a phone" and scan the code. The app makes no network requests outside the local network.

## Steps after enrollment (≈ 1 hour of work, then Apple's review)
1. **[owner]** Enroll at developer.apple.com/programs (identity check, usually 1–2 days). The same Apple ID already signed in to Xcode becomes the paid team.
2. Create the app record in App Store Connect (bundle ID above).
3. Archive + upload from this Mac: `xcodebuild archive` → `xcodebuild -exportArchive` (method app-store-connect, `-allowProvisioningUpdates`) — uses the Xcode account, no extra keys.
4. **TestFlight:** internal testers immediately; a **public TestFlight link** after Beta App Review (typically ≤ 1 day). Anyone installs from the TestFlight app — no sideloading.
5. **App Store:** submit the same build with the listing above; review typically 1–3 days.
6. The website switches the iPhone card from "Sideload" to the TestFlight / App Store button.
