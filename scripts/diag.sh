#!/bin/bash
set -x
mkdir -p diag
adb install -r -g app.apk
adb shell pm grant gh.roadguard.app android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant gh.roadguard.app android.permission.POST_NOTIFICATIONS || true
adb emu geo fix -0.17 5.70
adb logcat -c
adb shell monkey -p gh.roadguard.app -c android.intent.category.LAUNCHER 1
sleep 30
SZ=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SZ%x*}; H=${SZ#*x}; echo "$W x $H" > diag/size.txt
adb exec-out screencap -p > diag/1_launch.png
adb shell uiautomator dump /sdcard/ui1.xml >/dev/null; adb pull /sdcard/ui1.xml diag/ui1.xml
# tap GET STARTED (about 81% down the screen)
adb shell input tap $((W/2)) $((H*81/100)); sleep 5
adb exec-out screencap -p > diag/2_after_get_started.png
# tap Start journey area (centre, ~ lower middle) - just take screenshot and try a few taps
adb shell input tap $((W/2)) $((H*60/100)); sleep 8
adb exec-out screencap -p > diag/3_after_tap.png
adb shell dumpsys activity top | head -60 > diag/activity.txt
adb logcat -d > diag/logcat_full.txt
grep -iE "Capacitor|chromium|Console|AndroidRuntime|FATAL|ANR|roadguard|WebView" diag/logcat_full.txt | tail -800 > diag/logcat_app.txt
adb shell dumpsys window | grep -iE "mCurrentFocus|mFocusedApp" > diag/focus.txt
true
