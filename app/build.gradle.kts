plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.goshbuzz.emfsentinel"
    minSdk = 24
    targetSdk = 36
    // Play Console already holds release "3.3 (6)", so the next upload needs a higher
    // versionCode (Play rejects duplicates/lower codes). Keep both in step when releasing.
    versionCode = 7
    versionName = "3.4"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      // R8 shrinking is ON for release. Besides the size/obfuscation benefits Play recommends,
      // it is what removes the UNUSED androidx.activity EdgeToEdgeApi23..35 shims (and other
      // unreferenced library code) that still call the Android 15-deprecated
      // Window.setStatusBarColor / setNavigationBarColor / SHORT_EDGES APIs. Play Console's
      // "deprecated edge-to-edge APIs" scanner flags those bytes even though this app never
      // calls them. See docs/IMPLEMENTATION_NOTES.md §F and app/proguard-rules.pro.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")

      // PRODUCTION IDs — overridable via gradle.properties / -P flags or env at build time.
      // Defaults transcribed from the owner's brief; CONFIRM against the AdMob console
      // (Apps → app-ads.txt / All apps  and  Ad units) before publishing.
      val prodAppId = (project.findProperty("ADMOB_APP_ID") as? String)
        ?: "ca-app-pub-4067724379997931~6208950543"
      val prodBannerUnit = (project.findProperty("ADMOB_BANNER_UNIT_ID") as? String)
        ?: "ca-app-pub-4067724379997931/4082502150"
      // Native advanced unit created for this app. A brand-new AdMob ad unit can take up to
      // ~1 hour before it starts serving (requests return "no fill" until then) - that is
      // expected, not a bug. Never click live ads on your own device; use debug/qa builds.
      val prodNativeUnit = (project.findProperty("ADMOB_NATIVE_UNIT_ID") as? String)
        ?: "ca-app-pub-4067724379997931/9411043977"
      buildConfigField("String", "ADMOB_APP_ID", "\"$prodAppId\"")
      buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$prodBannerUnit\"")
      buildConfigField("String", "ADMOB_NATIVE_UNIT_ID", "\"$prodNativeUnit\"")
      manifestPlaceholders["ADMOB_APPLICATION_ID"] = prodAppId
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
      isCrunchPngs = false // skip AAPT2 PNG crunch on dev builds (faster, far less native memory)
      // Google TEST inventory only — never serve live ads in development builds.
      buildConfigField("String", "ADMOB_APP_ID", "\"ca-app-pub-3940256099942544~3347511713\"")
      buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"ca-app-pub-3940256099942544/9214589741\"")
      // Google demo "Native" unit (https://developers.google.com/admob/android/test-ads).
      buildConfigField("String", "ADMOB_NATIVE_UNIT_ID", "\"ca-app-pub-3940256099942544/2247696110\"")
      manifestPlaceholders["ADMOB_APPLICATION_ID"] = "ca-app-pub-3940256099942544~3347511713"
    }
    create("qa") {
      initWith(getByName("debug"))
      // QA: your REAL AdMob App ID (so your own Privacy & messaging / UMP configuration
      // is exercised) combined with Google's TEST banner + native units (no live impressions).
      buildConfigField("String", "ADMOB_APP_ID", "\"ca-app-pub-4067724379997931~6208950543\"")
      buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"ca-app-pub-3940256099942544/9214589741\"")
      buildConfigField("String", "ADMOB_NATIVE_UNIT_ID", "\"ca-app-pub-3940256099942544/2247696110\"")
      manifestPlaceholders["ADMOB_APPLICATION_ID"] = "ca-app-pub-4067724379997931~6208950543"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// No .env / secrets: this app needs NO API keys of any kind.

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  // implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.coil.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.play.services.location)
  implementation(libs.play.services.ads)
  implementation(libs.ump.user.messaging.platform)
  // Forces androidx.fragment above the 1.1.0 that play-services-basement and camera-view pull
  // in transitively (Play Console "outdated SDK" warning). See gradle/libs.versions.toml.
  implementation(libs.androidx.fragment)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
