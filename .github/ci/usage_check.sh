#!/usr/bin/env bash
# TEMPORARY (removed with the workflow): did R8 remove the unused androidx.activity EdgeToEdgeApi*
# shims - the code Play Console's "deprecated edge-to-edge API" scanner flags?
D=app/build/outputs/mapping/release
count() { grep -c "$1" "$2" 2>/dev/null || true; }

ls -l "$D"
echo "EdgeToEdgeApi* lines in usage.txt   (= REMOVED by R8): $(count EdgeToEdgeApi "$D/usage.txt")"
echo "EdgeToEdgeApi* lines in mapping.txt (= KEPT in the APK): $(count EdgeToEdgeApi "$D/mapping.txt")"
echo
echo "--- androidx.activity.EdgeToEdge* classes REMOVED (usage.txt, class lines only)"
grep -E '^androidx\.activity\.EdgeToEdge[A-Za-z0-9_$]*$' "$D/usage.txt" 2>/dev/null | sort -u | head -30
echo "--- androidx.activity.EdgeToEdge* classes KEPT (mapping.txt)"
grep -E '^androidx\.activity\.EdgeToEdge[A-Za-z0-9_$]* -> ' "$D/mapping.txt" 2>/dev/null | head -30
echo
echo "--- enableEdgeToEdge mentions in mapping.txt: $(count enableEdgeToEdge "$D/mapping.txt")"
echo
echo "--- Room/WorkManager: is the reflectively-created WorkDatabase_Impl constructor kept? (first R8 build crashed here)"
echo "seeds.txt lines for WorkDatabase_Impl:"; grep -n "WorkDatabase_Impl" "$D/seeds.txt" 2>/dev/null | head -5
echo "mapping.txt: class + <init> entries for WorkDatabase_Impl:"
grep -n -A6 '^androidx\.work\.impl\.WorkDatabase_Impl -> ' "$D/mapping.txt" 2>/dev/null | grep -E "WorkDatabase_Impl|<init>" | head -6
echo "keep rule present in R8's final configuration:"; grep -n "RoomDatabase" "$D/configuration.txt" 2>/dev/null | head -6
echo "--- this app's helper classes kept by R8:"
grep -E '^com\.example\.(ui\.EdgeToEdgeKt|ads\.NativeAdCard)' "$D/mapping.txt" 2>/dev/null | head -10
echo
ls -l app/build/outputs/apk/release/
