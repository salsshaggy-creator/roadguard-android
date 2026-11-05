#!/bin/bash
set -x
mkdir -p diag
A="timeout 25 adb"
adb wait-for-device; sleep 45
until [ "$($A shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 3; done
$A install -r -g app.apk
$A shell pm grant gh.roadguard.app android.permission.ACCESS_FINE_LOCATION
$A emu geo fix -0.17 5.70
$A logcat -c
(timeout 200 adb logcat -v time > diag/logcat_full.txt) &
$A shell am start -n gh.roadguard.app/.MainActivity
sleep 35
SZ=$($A shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SZ%x*}; H=${SZ#*x}; echo "$W x $H" > diag/size.txt
$A exec-out screencap -p > diag/1_launch.png
$A shell input tap $((W/2)) $((H*81/100)); sleep 6
$A exec-out screencap -p > diag/2_after_get_started.png
$A shell input tap $((W/2)) $((H*50/100)); sleep 6
$A exec-out screencap -p > diag/3_after_tap.png
$A shell dumpsys window | grep -iE "mCurrentFocus|mFocusedApp" > diag/focus.txt
$A shell pidof gh.roadguard.app > diag/pid.txt
sleep 3; pkill -f "adb logcat" || true
grep -iE "Capacitor|chromium|Console|AndroidRuntime|FATAL|ANR|roadguard|WebView" diag/logcat_full.txt | tail -1500 > diag/logcat_app.txt
true
