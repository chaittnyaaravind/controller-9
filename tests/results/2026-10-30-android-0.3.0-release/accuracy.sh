#!/bin/bash
# Android port of ControllerAccuracyUITests: press each control on the landscape (2400x1080) Xbox-style
# controller screen and print "STEP <name> <unix-seconds>" for tools/ios-ci/check-trace.py.
export PATH=/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH
step() { echo "STEP $1 $(python3 -c 'import time;print(time.time())')"; }
press() { step "$1"; adb shell input swipe $2 $3 $2 $3 250; python3 -c 'import time;time.sleep(0.9)'; }
drag() { step "$1"; adb shell input swipe $2 $3 $4 $3 700; python3 -c 'import time;time.sleep(0.9)'; }
press A 1987 708
press B 2142 562
press X 1832 562
press Y 1987 415
press LB 452 264
press RB 2056 264
press View 1094 540
press Menu 1415 540
press Guide 1254 409
press LS 270 908
press RS 2239 908
press LT 452 96
press RT 2056 96
press dpad-left-high 802 850
press dpad-up 911 754
press dpad-right 1021 864
press dpad-down 911 972
press face-gap 1987 562
drag stick-left-right 522 562 722
drag stick-right-right 1598 864 1798
step end
