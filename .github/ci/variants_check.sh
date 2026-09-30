#!/usr/bin/env bash
# TEMPORARY (removed with the workflow): which variants / unit-test tasks exist, and which ad ids
# does the generated BuildConfig of each variant carry?
set -u
echo "=== unit-test and BuildConfig tasks Gradle knows about"
gradle --no-daemon -q :app:tasks --all 2>/dev/null | grep -iE "^test[A-Za-z]*UnitTest|^generate[A-Za-z]*BuildConfig" | sort -u
echo
for v in Debug Qa Release; do
  echo "=== generate${v}BuildConfig"
  gradle --no-daemon -q ":app:generate${v}BuildConfig" 2>&1 | tail -3
done
echo
for f in app/build/generated/source/buildConfig/*/com/example/BuildConfig.java; do
  echo "--- $f"
  grep -E "BUILD_TYPE|VERSION_(CODE|NAME)|ADMOB" "$f"
done
echo
echo "=== AdUnitConfigTest on the release variant"
gradle --no-daemon :app:testReleaseUnitTest --tests com.example.AdUnitConfigTest > "$LOGS/release_unit.log" 2>&1
rc=$?
tail -12 "$LOGS/release_unit.log"
exit $rc
