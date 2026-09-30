#!/usr/bin/env bash
# TEMPORARY (removed with the workflow). Runs on the runner while the emulator is up
# (reactivecircus/android-emulator-runner).
#
# Smoke test of the R8-minified RELEASE build that was assembled with Google's DEMO ad ids
# (production ad units must never be requested from CI). It launches the app, checks that it
# survives, drives it to the Insights and Health tabs where the native ad lives, and records
# logcat + window-inset facts for digest.py to turn into annotations.
set -u
PKG=com.goshbuzz.emfsentinel
APK=app/build/outputs/apk/release/app-release.apk
OUT="${LOGS:-/tmp/ci-logs}/smoke"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"

adb wait-for-device
{
  echo "android=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "size=$(adb shell wm size | tr -d '\r' | tail -1)"
  echo "density=$(adb shell wm density | tr -d '\r' | tail -1)"
  echo "apk_bytes=$(stat -c %s "$APK")"
} | tee "$OUT/env.txt"

# -g grants every runtime permission up front so no permission dialog gets in the way.
adb install -r -g "$APK" 2>&1 | tee "$OUT/install.txt"
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>&1 | tee "$OUT/launch.txt"
sleep 30
echo "pid_after_30s=$(adb shell pidof "$PKG" | tr -d '\r')" | tee -a "$OUT/env.txt"

python3 "$here/smoke_ui.py" "$OUT" || true

echo "pid_at_end=$(adb shell pidof "$PKG" | tr -d '\r')" | tee -a "$OUT/env.txt"
adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1
adb shell dumpsys window > "$OUT/dumpsys_window.txt" 2>&1
adb shell dumpsys window windows > "$OUT/dumpsys_windows.txt" 2>&1
adb shell dumpsys activity top > "$OUT/dumpsys_activity_top.txt" 2>&1
echo "smoke.sh finished"
