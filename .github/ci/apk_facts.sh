#!/usr/bin/env bash
# TEMPORARY (removed with the workflow): facts about the R8 release APK that matter for Play / AdMob.
APK=app/build/outputs/apk/release/app-release.apk
AAPT2=$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
echo "aapt2: $AAPT2"
echo "--- badging (package, SDK levels, permissions, launcher)"
"$AAPT2" dump badging "$APK" | grep -E "^(package|minSdkVersion|sdkVersion|targetSdkVersion|uses-permission|application-label:|launchable-activity)" | head -30
echo
echo "--- AdMob application id in the packaged manifest"
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" | grep -B2 -A3 "APPLICATION_ID" | head -14
echo
echo "--- activities declared in the packaged manifest"
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" | grep -E "E: activity" -A4 | grep -E "E: activity|android:name" | head -24
