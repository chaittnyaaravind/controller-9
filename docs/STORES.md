# Free stores — listing kit and status

Every store needs an account in the owner's name (ID checks, terms). The agent prepares everything
and does the uploads once the account exists; the owner only signs up. Researched 2026-10-19.

Kit: `tools/stores/build_kit.sh` → `build/stores/` (icon 512, itch cover 630×500, Android
screenshots 1080×2400, the exact tested files from the website + SHA256SUMS).

| Store | Platforms | Cost | Status | Owner step |
|---|---|---|---|---|
| itch.io | Android, Windows, Mac, Linux | $0 | **Ready** — `tools/stores/itch-push.sh user/controller-9` | Sign up at itch.io, create project "Controller-9" (kind: Tool, Downloadable), run `~/tools-dl/butler/butler login` once |
| Samsung Galaxy Store | Android | $0 | Ready (APK, icon, screens, copy below) | Samsung account → seller.samsungapps.com. May ask for commercial-seller status even for free apps (unconfirmed) |
| Uptodown | Android | $0 | Ready | Developer account at uptodown.com |
| Huawei AppGallery | Android | not stated | Ready | ID + bank card verification (1–3 days) |
| Microsoft Store | Windows | $0 (individual) | **Hold** | Games on Windows can't see the controller yet (no signed driver); the Store would list an app that doesn't do its job. Revisit with the driver |
| winget | Windows | $0 | **Hold** | Same reason as Microsoft Store |
| F-Droid / IzzyOnDroid / Flathub | Android / Linux | $0 | Blocked | Need the source public under an open-source licence (owner decision) |
| Homebrew Cask | Mac | needs $99/yr notarization | Blocked | — |
| Amazon Appstore | — | — | Closed on Android since 2025-08-20 | — |
| Google Play | Android | $25 | Not free | — |

## Listing copy (all stores)

**Name:** Controller-9
**Short (80):** Your phone is the game controller. Local, private, no account.
**Category:** Tools / Utilities (itch.io: Tool)
**Price:** Free
**Website:** https://controller-9.vercel.app · **Privacy:** https://controller-9.vercel.app/privacy/
**Content rating:** Everyone / 3+ (no user content, no data collected)
**Support contact:** _pending — owner to choose (do not use the personal email)_

**Description:**

Controller-9 turns your phone into a game controller for your computer.

- Five familiar layouts — Xbox, DualSense, DualShock 4, Switch Pro and 8BitDo Pro 2 styles.
- Tilt to steer, measured from however you hold the phone. Recenter any time.
- Accurate touch: each control sends only itself; the D-pad favours straight directions.
- Rumble from the game, felt on the phone.
- Wi-Fi and Bluetooth together, so a dropped link doesn't drop your game.
- Private: pair once with a code, end-to-end encrypted, local network only. No account, no analytics.

Works with games today on **Linux** (games see a standard Xbox 360-style controller).
The Windows and Mac companions pair and show live diagnostics; games there can't see the
controller yet. Install the free companion from controller-9.vercel.app.

**itch.io extra:** files are labelled per platform; SHA-256 for each is on the website.

## Rules
- Upload only the files in `build/stores/files` (the tested website builds). Never rebuild for a store.
- Keep the Linux/Windows/Mac limitation in every listing until a platform's game test passes.
