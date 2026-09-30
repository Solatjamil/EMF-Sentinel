#!/usr/bin/env bash
# TEMPORARY (removed with the workflow): Play receives an app bundle. Does it build, and does it
# embed the R8 mapping file (so Play can deobfuscate crash reports now that R8 is on)?
gradle --no-daemon :app:bundleRelease > "$LOGS/bundle_build.log" 2>&1
rc=$?
tail -5 "$LOGS/bundle_build.log"
AAB=$(ls app/build/outputs/bundle/release/*.aab 2>/dev/null | head -1)
echo "aab: ${AAB:-NOT FOUND} ($(stat -c %s "$AAB" 2>/dev/null || echo 0) bytes)"
[ -n "$AAB" ] && unzip -l "$AAB" | grep -iE "BUNDLE-METADATA|proguard|mapping|base/dex|base/manifest" | head -20
exit $rc
