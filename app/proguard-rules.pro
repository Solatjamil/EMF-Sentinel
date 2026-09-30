# R8 / ProGuard rules for the RELEASE build (isMinifyEnabled = true, isShrinkResources = true).
#
# Active rules are intentionally minimal:
#
#  * Google Mobile Ads SDK (Legacy), UMP, CameraX, AndroidX/Compose and kotlinx.coroutines all
#    ship their own consumer rules inside their AARs, so no -keep rules are needed for them.
#  * The app itself uses NO reflection, JNI, WebView JS interfaces or serialization libraries
#    (floor plans are persisted with the platform's org.json), so nothing has to be kept by name.
#
# If a future dependency logs "Missing class ..." during minifyReleaseWithR8, add a
# -dontwarn line for exactly the class it names rather than disabling R8 (see
# docs/IMPLEMENTATION_NOTES.md §F for why R8 must stay on for Play's edge-to-edge check).

# Keep file names + line numbers so Play Console / Android vitals crash traces stay readable
# once they are de-obfuscated with the mapping file that the App Bundle carries.
-keepattributes SourceFile,LineNumberTable
# ...but hide the original source file name in shipped stack traces.
-renamesourcefileattribute SourceFile
