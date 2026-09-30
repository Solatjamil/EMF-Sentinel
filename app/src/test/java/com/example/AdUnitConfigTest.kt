package com.example

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards an AdMob policy: development builds must only ever carry Google's DEMO ad units — clicking
 * your own live ads can get an AdMob account suspended — while a release build must carry well-formed
 * production unit IDs (see app/build.gradle.kts).
 *
 * Plain JVM test: it only reads the generated BuildConfig of the variant under test. AGP 9 creates
 * unit-test tasks for the debug build type only, so in practice this runs as `testDebugUnitTest`;
 * the qa/release IDs were checked against their generated BuildConfig classes in CI
 * (docs/IMPLEMENTATION_NOTES.md, verification log). The logic is variant-agnostic and starts covering
 * qa/release automatically if `android.onlyEnableUnitTestForTheTestedBuildType=false` is ever set.
 */
class AdUnitConfigTest {

    private val demoPublisherPrefix = "ca-app-pub-3940256099942544/"
    private val unitIdFormat = Regex("ca-app-pub-\\d{16}/\\d{10}")

    @Test
    fun bannerAndNativeUnitIdsMatchTheBuildType() {
        val bannerUnit = BuildConfig.ADMOB_BANNER_UNIT_ID
        val nativeUnit = BuildConfig.ADMOB_NATIVE_UNIT_ID

        assertTrue("Banner unit id is malformed: $bannerUnit", unitIdFormat.matches(bannerUnit))
        assertTrue("Native unit id is malformed: $nativeUnit", unitIdFormat.matches(nativeUnit))

        if (BuildConfig.BUILD_TYPE != "release") {
            assertTrue(
                "Non-release builds must use Google's demo banner unit, but got $bannerUnit",
                bannerUnit.startsWith(demoPublisherPrefix)
            )
            assertTrue(
                "Non-release builds must use Google's demo native unit, but got $nativeUnit",
                nativeUnit.startsWith(demoPublisherPrefix)
            )
        }
    }
}
