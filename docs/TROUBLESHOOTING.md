# Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| "Xbox Wireless Controller" doesn't appear on the computer | the app isn't open, or Bluetooth is off on the phone | open Controller-9 (it turns the controller on and asks for Bluetooth if needed); keep the phone's own Bluetooth settings screen closed |
| The computer lists the phone twice | the phone's Bluetooth settings screen is open (it shows the phone under its own name), or an old pairing is left | close the phone's Bluetooth settings; remove older "Xbox Wireless Controller" / phone entries on the computer, then pair again |
| Connected, but games see nothing — after an app update, a phone restart or turning the phone's Bluetooth off and on | macOS keeps the link but drops the gamepad when the phone's controller restarts (`docs/DIRECT_BLUETOOTH.md`, lesson 11) | turn the **computer's** Bluetooth off and on; it's back in about 5 seconds |
| A game shows a generic joystick, not Xbox buttons | the computer paired with the phone under its old name | remove the pairing on the computer and pair again while the app is open (the phone is then named "Xbox Wireless Controller") |
| Chrome stops seeing the pad after many reconnects | Chrome keeps stale pads in its four slots | restart Chrome |
| Guide opens a Games overlay instead of reaching the game | macOS answers the Xbox button itself, as with a real pad | use Menu in games; Guide is for the system |
| No click when pressing | Vibration is off or set to 0 % | Settings → Controls & feel → Vibration |
| Steering off-centre | not calibrated | Recenter on the controller (hold still 1.5 s), or Settings → Calibrate steering |
| A tap near a button doesn't press it | the tap was about equally close to two buttons (that presses neither, on purpose) | land a little closer to the button you mean; anything within ~20 dp of one button presses it |
