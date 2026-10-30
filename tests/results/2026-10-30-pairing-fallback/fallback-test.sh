#!/bin/bash
# Drives the Android pairing fallback: pair by a silent address, expect Bluetooth fallback, confirm after 5 s.
S=$(dirname "$0"); export PATH=/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH
dump() { adb exec-out sh -c 'uiautomator dump /sdcard/u.xml >/dev/null; cat /sdcard/u.xml' </dev/null; }
for i in $(seq 8); do adb shell input swipe 540 2100 540 500 200 </dev/null; done
B=$(dump | grep -oE 'class="android.widget.EditText"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tail -4)
set -- $B; [ -z "$4" ] && { echo no field; exit 1; }
adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) </dev/null; adb shell input keycombination 113 29 </dev/null; adb shell input keyevent 67 </dev/null  # select all, delete
adb shell input text 10.251.54.244 </dev/null; adb shell input keyevent 4 </dev/null; sleep 0.8
for i in 1 2 3; do adb shell input swipe 540 2100 540 900 200 </dev/null; done
bash $S/tapt.sh "Pair with code" </dev/null
for i in $(seq 100); do X=$(dump); echo "$X" | grep -q "Connecting to computer over Bluetooth" && { echo "$X" | grep -oE 'text="(No answer|Connecting)[^"]*"'; adb exec-out screencap -p > $S/fallback-trying2.png </dev/null; break; }; done
for i in $(seq 100); do dump | grep -q "show this code" && { echo code shown; break; }; done
sleep 5; bash $S/tapt.sh "Codes match" </dev/null
for i in $(seq 30); do [ $(sed 's/\x1b\[[0-9;]*m//g' $S/host3.log | grep -c '"type":"paired"') -ge 2 ] && { echo paired again; break; }; sleep 1; done
