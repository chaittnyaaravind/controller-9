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
**Short (80):** Your phone pairs as an Xbox controller over Bluetooth. Nothing to install.
**Category:** Tools / Utilities (itch.io: Tool)
**Price:** Free — no ads, no subscription, no account
**Website:** https://controller-9.vercel.app · **Privacy:** https://controller-9.vercel.app/privacy/
**Content rating:** Everyone / 3+ (no user content, no data collected)
**Support contact:** _pending — owner to choose (do not use the personal email)_

**Description:** the linted store text is in `replica/launch/listing.json` (Google Play full description
for Android, App Store description for iPhone; checked with replica-launch `listing.py`: 0 errors).
Summary of what it may claim, each with evidence:

- Android pairs from the computer's Bluetooth settings as an Xbox controller, nothing installed —
  Mac game test (Asphalt Legends), Safari/Chrome gamepad, Linux SDL (BT host simulation).
- Seven layouts plus customise any button; tilt steering from your grip; gyro aim; rumble.
- Connection check names the cause; every control press tested 20/20 (iOS UI test against a host).
- iPhone needs the free companion: Windows/Linux games see an Xbox 360 controller; Mac companion is
  keyboard and mouse.

Never name another controller app in a listing, keyword or ad.

## Rules
- Upload only the files in `build/stores/files` (the tested website builds). Never rebuild for a store.
- Keep the Linux/Windows/Mac limitation in every listing until a platform's game test passes.
