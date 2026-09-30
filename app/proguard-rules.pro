# R8 / ProGuard rules for the RELEASE build (isMinifyEnabled = true, isShrinkResources = true).
#
# Google Mobile Ads SDK, UMP, CameraX, AndroidX/Compose and kotlinx.coroutines ship their own
# consumer rules inside their AARs, and the app itself uses no reflection, JNI, WebView JS
# interfaces or serialization libraries (floor plans are persisted with the platform's org.json).
# The ONE rule below is for a library that is only pulled in transitively.
#
# If a future dependency logs "Missing class ..." during minifyReleaseWithR8, add a -dontwarn line
# for exactly the class it names rather than disabling R8 (see docs/IMPLEMENTATION_NOTES.md §F for
# why R8 has to stay on for Play's edge-to-edge check).
#
# ALWAYS launch the minified RELEASE build on a device/emulator before uploading it: R8 does not
# run in debug builds, so unit tests and debug installs cannot catch a missing keep rule.

# --- Room (comes in transitively: play-services-ads -> WorkManager -> Room) -----------------------
# Room creates its generated *_Impl database classes reflectively:
#   Class.forName("..._Impl").getDeclaredConstructor().newInstance()
# The room-runtime that ends up on this classpath declares only
#   -keep class * extends androidx.room.RoomDatabase
# with no members, and under R8 full mode (the AGP default) the otherwise-unreferenced no-arg
# constructor is removed. The class NAME survives, so the failure is an InstantiationException
# rather than a ClassNotFoundException, and it happens during process start inside
# androidx.startup (WorkManagerInitializer), i.e. before any app code runs:
#   RuntimeException: Unable to get provider androidx.startup.InitializationProvider
#   Caused by: Failed to create an instance of androidx.work.impl.WorkDatabase
# (Found by the emulator smoke test of the first R8 build, on API 35 and 36.)
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# --- Crash-report readability -----------------------------------------------------------------
# Keep file names + line numbers so Play Console / Android vitals traces stay readable once they are
# de-obfuscated with the mapping file the App Bundle carries (proguard.map is embedded by AGP).
-keepattributes SourceFile,LineNumberTable
# ...but hide the original source file name in shipped stack traces.
-renamesourcefileattribute SourceFile
