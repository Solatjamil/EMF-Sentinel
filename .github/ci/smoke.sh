#!/usr/bin/env bash
# TEMPORARY (removed with the workflow). Runs on the runner while the emulator is up
# (reactivecircus/android-emulator-runner).
#
# Smoke test of two builds that were assembled with Google's DEMO ad ids (production ad units must
# never be requested from CI):
#   release = the R8-minified release build  (what ships to Play)
#   debug   = the same code without R8       (tells R8 effects from everything else)
#   baseline = the app BEFORE this change (commit 72eed5c), plain debug build: the reference for anything
#              odd seen on screen (is it new, or was it already there?)
# For each: install, launch, watch the process, drive the UI to the Insights and Health tabs where
# the native ad lives, walk the other screens, and record logcat / crash buffer / exit reasons /
# window facts for digest.py to turn into annotations.
set -u
PKG=com.goshbuzz.emfsentinel
BASE="${LOGS:-/tmp/ci-logs}/smoke"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$BASE"

adb wait-for-device
for i in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break
  sleep 2
done

# A loaded emulator on a shared runner sometimes ANRs the launcher; its dialog would cover the app and is not
# the app's business. (Crashes and ANRs are still detected from the crash buffer, pids and exit info.)
adb shell settings put global hide_error_dialogs 1 2>/dev/null

unlock_screen() {
  adb shell svc power stayon true 2>/dev/null
  adb shell input keyevent KEYCODE_WAKEUP 2>/dev/null
  adb shell locksettings set-disabled true 2>/dev/null
  adb shell wm dismiss-keyguard 2>/dev/null
  sleep 1
  adb shell input keyevent 82 2>/dev/null
  sleep 1
}

keyguard_state() {
  adb shell dumpsys window policy | tr -d '\r' | grep -iE 'showing=|isKeyguardShowing|mKeyguardShowing' | head -3 | tr '\n' ' '
}

run_case() {  # <label> <apk>
  local label="$1" apk="$2" OUT="$BASE/$1"
  mkdir -p "$OUT"
  echo "######## case $label: $apk"
  if [ ! -f "$apk" ]; then echo "missing $apk" | tee "$OUT/env.txt"; return; fi

  adb uninstall "$PKG" >/dev/null 2>&1 || true
  unlock_screen
  {
    echo "label=$label"
    echo "android=$(adb shell getprop ro.build.version.release | tr -d '\r')"
    echo "api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
    echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
    echo "size=$(adb shell wm size | tr -d '\r' | tail -1)"
    echo "density=$(adb shell wm density | tr -d '\r' | tail -1)"
    echo "apk_bytes=$(stat -c %s "$apk")"
    echo "keyguard_before_launch: $(keyguard_state)"
  } | tee "$OUT/env.txt"

  # -g grants every runtime permission up front so no permission dialog gets in the way.
  adb install -r -g "$apk" 2>&1 | tee "$OUT/install.txt"
  adb logcat -b all -c
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>&1 | grep -E "Events injected|No activities|rror" | tee "$OUT/launch.txt"

  elapsed=0
  for step in 3 5 10 15; do
    sleep "$step"
    elapsed=$((elapsed + step))
    echo "pid_at_${elapsed}s=$(adb shell pidof "$PKG" | tr -d '\r')" | tee -a "$OUT/env.txt"
  done
  echo "keyguard_after_launch: $(keyguard_state)" | tee -a "$OUT/env.txt"
  echo "top_activity: $(adb shell dumpsys activity activities | tr -d '\r' | grep -E 'topResumedActivity|mResumedActivity' | head -2 | tr '\n' ' ')" | tee -a "$OUT/env.txt"

  python3 "$here/smoke_ui.py" "$OUT" || true

  echo "pid_at_end=$(adb shell pidof "$PKG" | tr -d '\r')" | tee -a "$OUT/env.txt"
  adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1
  adb logcat -b crash -d -v threadtime > "$OUT/crash.txt" 2>&1
  adb logcat -b events -d -v threadtime 2>&1 | grep -E "goshbuzz" > "$OUT/events.txt"
  adb shell dumpsys activity exit-info "$PKG" > "$OUT/exit_info.txt" 2>&1
  adb shell dumpsys window > "$OUT/dumpsys_window.txt" 2>&1
  adb shell dumpsys window windows > "$OUT/dumpsys_windows.txt" 2>&1
  adb shell dumpsys activity top > "$OUT/dumpsys_activity_top.txt" 2>&1
}

run_case release app/build/outputs/apk/release/app-release.apk
run_case debug   app/build/outputs/apk/debug/app-debug.apk
run_case baseline baseline/app/build/outputs/apk/debug/app-debug.apk
echo "smoke.sh finished"
