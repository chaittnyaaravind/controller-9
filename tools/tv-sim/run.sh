#!/bin/zsh
# Android phone → Google TV over the emulator's virtual Bluetooth (netsim). Needs AVDs c9-test (phone)
# and c9-tv (system-images;android-36;google-tv;arm64-v8a), the release APK on the phone and a debug
# APK on the TV (for the c9_bond hook). See tests/results/2026-10-26-google-tv-emulator/README.md.
set -eu
PH=${PH:-emulator-5554}; TV=${TV:-emulator-5556}
adb(){ /opt/homebrew/share/android-commandlinetools/platform-tools/adb "$@"; }
pos(){ python3 -c "import sys,re;x=sys.stdin.read();m=re.search(sys.argv[1]+r'[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]',x);print('' if not m else '%d %d'%((int(m[1])+int(m[3]))//2,(int(m[2])+int(m[4]))//2))" "$1"; }
adb -s $TV shell am force-stop dev.ninecontroller.android
adb -s $TV shell "am start -n dev.ninecontroller.android/.MainActivity --es c9_bond 'Xbox Wireless Controller'" >/dev/null
tvok=0; phn=0; phok=0
for i in $(seq 1 60); do
  if [ $tvok = 0 ]; then adb -s $TV shell uiautomator dump /sdcard/t.xml >/dev/null 2>&1; x=$(adb -s $TV shell cat /sdcard/t.xml)
    if echo "$x" | grep -q "pairing request"; then b=$(echo "$x" | pos 'text="Pair"'); adb -s $TV shell input tap ${=b}; tvok=1; fi; fi
  if [ $phok = 0 ]; then adb -s $PH shell uiautomator dump /sdcard/p.xml >/dev/null 2>&1; x=$(adb -s $PH shell cat /sdcard/p.xml)
    b=$(echo "$x" | pos 'text="Pair"'); if [ -n "$b" ]; then adb -s $PH shell input tap ${=b}; phok=1
    elif [ $phn = 0 ]; then adb -s $PH shell cmd statusbar expand-notifications; adb -s $PH shell uiautomator dump /sdcard/p.xml >/dev/null 2>&1
      b=$(adb -s $PH shell cat /sdcard/p.xml | pos 'text="Pair &amp; connect"'); [ -n "$b" ] && { adb -s $PH shell input tap ${=b}; phn=1; }; fi; fi
  [ $tvok = 1 ] && [ $phok = 1 ] && break; sleep 0.5
done
sleep 10
adb -s $TV shell dumpsys input | grep -A3 "Xbox Wireless Controller" | head -4
