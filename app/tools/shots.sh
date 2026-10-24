#!/usr/bin/env bash
# Runs inside the CI emulator: installs the APK, walks the screens, saves screenshots and the app's log.
set -x
OUT=shots; mkdir -p $OUT
P=app.reviewnotes.timing/.MainActivity
adb install -r timing-app.apk
adb shell settings put global window_animation_scale 1
adb logcat -c
shot() { adb exec-out screencap -p > $OUT/$1.png; }
adb shell am start -n $P --ei tab 0
sleep 45; shot 1-home
adb shell input swipe 540 1700 540 700 400; sleep 3; shot 2-home-scrolled
adb shell am start -n $P --ei tab 1; sleep 25; shot 3-scan
adb shell input swipe 540 1700 540 700 400; sleep 3; shot 4-scan-scrolled
adb shell am start -n $P --ei tab 2; sleep 8; shot 5-watch
adb shell am start -n $P --es open sh600519; sleep 25; shot 6-detail
adb shell input swipe 540 1700 540 700 400; sleep 3; shot 7-detail-scrolled
adb shell am start -n $P --ez close true --ei tab 0; sleep 3
adb logcat -d -v time -s TimingNet:* Timing:* AndroidRuntime:E > $OUT/logcat.txt
adb logcat -d -v time > $OUT/logcat-all.txt
ls -la $OUT
