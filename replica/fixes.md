# What phone-controller users hate, and what Controller-9 does about it

Sample: **454 public reviews and comments** collected 2026-10-26 from 8 sources:
App Store (Remote Gamepad 42, VGamepad Lite 53, Mobile Joypad 1, Steam Link 9 that mention its
touch controls; Apple's public reviews feed), Google Play (DroidJoy 80, Monect PC Remote 80,
Remote Gamepad 80; the reviews the "See all reviews" panel shows), Hacker News (109 comments
about phones as controllers; Algolia API). Wireless Gamepad for PC had no reviews. GamerPad's feed
was empty. 345 rows have star ratings. Google Play links point to the app's review panel (Play
has no per-review URL); App Store links carry the review id.

Ranked with `replica-entrepreneur/reviews.py` and a controller-specific theme file
(`themes-controller.json`), 36-month window. Full report: `feedback.md` (kept local, not committed).

## 1. What they hate

| # | problem | reviews | sources | quotes |
|---|---|---|---|---|
| 1 | **Ads and paywalls on basic functions** (ads mid-game, subscriptions for clicks) | 51 ads + 56 price | 6 | "Too many ads. I start the app and then play a game, in the middle of a boss fight, an ad strikes, killing my run" ([VGamepad Lite, 1★](https://apps.apple.com/us/app/id1477007195?see-all=reviews#review-12783130177)) · "right clicking is locked behind a subscription. $12 a year to right click." ([Monect, 2★](https://play.google.com/store/apps/details?id=com.monect.portable&hl=en&showAllReviews=true)) |
| 2 | **Bugs: it just doesn't work** | 50 | 7 | "Okay, but buttons barely work. … almost every time I try to press the buttons, the wrong button is detected." ([VGamepad Lite, 2★](https://apps.apple.com/us/app/id1477007195?see-all=reviews#review-6681307812)) |
| 3 | **Lag, freezes and disconnects** | 49 | 7 | "disconnects more often than it works, and the ads literally cover the whole screen when it does work for a few seconds." ([Monect, 1★](https://play.google.com/store/apps/details?id=com.monect.portable&hl=en&showAllReviews=true)) |
| 4 | **Setup: PC server software, drivers, unclear instructions** | 42 | 7 | "They also expect you to install a server program on your PC that almost does try to install something that seems suspisious" ([DroidJoy, 1★](https://play.google.com/store/apps/details?id=com.grill.droidjoy&hl=en&showAllReviews=true)) |
| 5 | **Can't connect / server not found** | 22 | 6 | "Keeps saying server not found even though I'm using the same wifi as my PC." ([DroidJoy, 1★](https://play.google.com/store/apps/details?id=com.grill.droidjoy&hl=en&showAllReviews=true)) · "Fails to connect even when allowing comms through the firewall. No adequate support info on how to resolve." ([Remote Gamepad, 1★](https://apps.apple.com/us/app/id6448859108?see-all=reviews#review-11567889275)) |
| 6 | **Bluetooth promised but missing or broken** | 9 | 2 | "The description says it supports bluetooth, but I only see WiFi and USB in the app." ([Remote Gamepad, 3★](https://apps.apple.com/us/app/id6448859108?see-all=reviews#review-14125451842)) |

## 2. What is missing

| # | request | reviews | sources | quotes |
|---|---|---|---|---|
| 1 | **Mac and TV as the thing you play on** | 85 platform (30 TV, 10 Mac) | 7 | "Please add MacOS support" ([VGamepad Lite, 1★](https://apps.apple.com/us/app/id1477007195?see-all=reviews#review-9833267739)) · "pls add “connect to android tv” on the ios version" ([Remote Gamepad, 5★](https://apps.apple.com/us/app/id6448859108?see-all=reviews#review-11387412165)) |
| 2 | **Move and resize each button on its own** | 48 | 5 | "give option to seperate x,y,a,b button and resize,when i resize,four button resizing same time" ([DroidJoy, 2★](https://play.google.com/store/apps/details?id=com.grill.droidjoy&hl=en&showAllReviews=true)) · "being able to go smaller than 50% and position snapping/alignment, would be great." ([Remote Gamepad, 4★](https://apps.apple.com/us/app/id6448859108?see-all=reviews#review-14447916888)) |
| 3 | **Tilt that is calibrated to how you hold it** | 30 tilt, 5 calibration | 5 | "when using accelerometer there is no way to calibrate it, meaning that its resting position is always flat" ([DroidJoy, 4★](https://play.google.com/store/apps/details?id=com.grill.droidjoy&hl=en&showAllReviews=true)) · "add a angle calibration setting because if u don’t have your phone in a perfect angle u wouldn’t get the full rotations." ([Remote Gamepad, 3★](https://apps.apple.com/us/app/id6448859108?see-all=reviews#review-14210739957)) |
| 4 | **Several phones as several controllers** | 22 | 3 | "Worth the few bucks to be able to use my tablet and phone to play on PC with multiple people versus having to buy more controllers." ([DroidJoy, 5★](https://play.google.com/store/apps/details?id=com.grill.droidjoy&hl=en&showAllReviews=true)) |
| 5 | **Missing triggers / Xbox button** | 18 | 5 | "Doesn't work on Wifi; Work on BT mode, but LT and RT Button doesn't." ([Remote Gamepad, Play](https://play.google.com/store/apps/details?id=com.remotegamepad&hl=en&showAllReviews=true)) |
| 6 | **Two buttons at once** (thin: 1 review) | 1 | 1 | "There is a bug when pressing 2 buttons at the same time it wouldn’t work sometimes" ([Remote Gamepad, 3★](https://apps.apple.com/us/app/id6448859108?see-all=reviews#review-14210739957)) |
| 7 | **Vibration synced with the game** | 7 | 4 | "if you can sync vibration with game I will give you five stars" ([DroidJoy, 4★](https://play.google.com/store/apps/details?id=com.grill.droidjoy&hl=en&showAllReviews=true)) |

Thin, noted only: playing in portrait (2 reviews), using a physical clip-on gamepad through the
phone (1), Raspberry Pi as host (1).

## 3. What is unsolved

- **"I just want it to work without installing a server."** Setup and connection make up 64 of the
  complaints (rows 4 and 5 above), and almost every competitor needs a PC program. Whole groups give
  up before the first game.
- **Mac players and TV players** are told "Windows only" (85 platform reviews, 7 sources).
- **People who refuse ads or subscriptions for a basic controller** (107 reviews on ads and price).

## Where Controller-9 already stands (checked in this repo, with evidence)

| complaint | Controller-9 today | evidence |
|---|---|---|
| ads, subscriptions | free, no ads, no account, no analytics | site + `docs/platforms/*` |
| PC server / drivers | **Android: nothing to install** — pairs as an Xbox Wireless Controller from Bluetooth settings | Mac GameController 12 buttons/12 axes, Asphalt Legends; Linux SDL/evdev (BT host sim) |
| Mac | Android Bluetooth controller works on Mac; Mac app has keyboard-and-mouse mode | `tests/results/2026-10-25-direct-bluetooth-mac.txt` |
| wrong button detected | automated touch-accuracy test 20/20 on iPhone 15 and the iOS Simulator | `tests/results/2026-10-26-ios-simulator/` |
| tilt calibration | grip-relative steering, recenter, dead zone, steering lock | `docs/STEERING.md` |
| several phones | up to 4 controllers per computer | host slots |
| triggers, Xbox button, rumble | all 15 buttons, analog triggers, rumble felt on Mac | DIRECT_BLUETOOTH.md |

## 4. Fix plan (evidence × cheapness)

| # | change | size | skill | evidence |
|---|---|---|---|---|
| F1 | **Customise any controller layout**: "Customise" on Xbox/DualSense/… copies it into an editable layout (move, resize each button separately, snap). `PadPreset.toProfile()` exists but nothing calls it. | M | replica-build | missing #2: 48 reviews, 5 sources |
| F2 | **Connection doctor**: when the computer is not found, test it and name the cause in plain words — different network or Wi-Fi that isolates devices, firewall, VPN, computer app not running — with the one fix for each, plus "enter the address by hand". | M | replica-build | hate #5 + #4: 64 reviews, 7 sources |
| F3 | **Prove TV**: test the Android Bluetooth controller with an Android TV / Google TV and a smart TV; list only what passes. | S (needs a TV) | replica-test | missing #1: 30 TV reviews, 6 sources |
| F4 | **Chords**: add "A+B at once" and "stick + button" steps to the accuracy UI test (iOS) and an instrumented test (Android). | S | replica-test | missing #6 (thin) + hate #2 |
| F5 | **iPhone over Bluetooth**: run the iPhone ↔ computer-app Bluetooth link for real (code exists, never tested), so iPhone works without Wi-Fi. | M | replica-test | hate #6: 9 reviews, 2 sources |
| F6 | **Say it plainly on the site and store listing**: free, no ads, no account; nothing to install on Android; Mac supported; tested 20/20 accuracy. | S | replica-launch | hate #1 (107), unsolved |

## 5. The angle

1. **For people who gave up on phone-controller apps because the PC program would not connect,
   Controller-9 on Android pairs straight from Bluetooth settings as an Xbox controller — nothing to
   install.** Evidence: setup + can't connect, 64 reviews across 7 sources.
2. **For players tired of ads in the middle of a boss fight and subscriptions for a right click,
   Controller-9 is free with no ads and no account.** Evidence: ads + price, 107 reviews across 6 sources.
3. **For Mac and TV players told "Windows only", Controller-9 works where they play.** Evidence:
   platform requests, 85 reviews across 7 sources (TV still to prove: F3).

**Recommended: 1, with 2 as the promise underneath it.** It is the one competitors cannot copy
quickly (it needs the Bluetooth HID work), and the evidence is broad. No competitor names go in our
app name, ads or listing; reviewers' words are research, not testimonials.

Next: F1 and F2 (replica-build), then `/replica-launch` for F6. `/replica-brand` is not needed:
Controller-9 is already its own brand.
