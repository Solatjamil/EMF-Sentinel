package com.example.ads

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

/**
 * Anchored adaptive AdMob banner — SDK-rendered ads ONLY.
 *
 * Guarantees:
 *  - Measures the REAL available container width (never a hardcoded 360dp/320x50).
 *  - Uses AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize for the slot.
 *  - A fixed-height slot is reserved as soon as the width is known, so content and
 *    navigation controls never jump when the ad arrives, fails, or is removed.
 *  - Loads exactly one ad per slot; no timers competing with AdMob's own refresh
 *    (configure automatic refresh in the AdMob console for this ad unit).
 *  - Requests nothing while UMP consent (canRequestAds) is false.
 *  - The AdView is destroyed on disposal; late callbacks cannot leak the Activity.
 *  - Load failure never crashes and never blocks content (slot stays an empty surface).
 *  - The creative is rendered 100% by Google — nothing is overlaid on top of it.
 */
@Composable
fun AnchoredAdaptiveBanner(
    adUnitId: String,
    canRequestAds: Boolean,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") isDarkMode: Boolean = true
) {
    val context = LocalContext.current

    // 1) Measure the actual available width after layout (px → dp, no guessing).
    var slotSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { slotSize = it }
    ) {
        if (slotSize.width <= 0) return@Box // never request at zero width

        val widthDp = (slotSize.width / context.resources.displayMetrics.density).toInt()
        if (widthDp <= 0) return@Box

        // 2) Anchored adaptive size for the measured width. Height is stable for a
        //    given width, so reserving it immediately prevents layout jumps.
        val adSize = remember(widthDp) {
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
        }
        val slotHeightDp = remember(adSize) { adSize.height.coerceAtLeast(1) }

        Box(modifier = Modifier.fillMaxWidth().height(slotHeightDp.dp)) {
            // 3) Consent gate: no AdView (therefore no ad request) while ads are not allowed.
            if (canRequestAds && AdConsentManager.canRequestAds) {
                var adView by remember { mutableStateOf<AdView?>(null) }

                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(slotHeightDp.dp),
                    factory = { ctx ->
                        AdView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setAdSize(adSize)
                            setAdUnitId(adUnitId)
                            adListener = object : AdListener() {
                                override fun onAdLoaded() {
                                    Log.d("AnchoredAdaptiveBanner", "Banner loaded")
                                }

                                override fun onAdFailedToLoad(error: LoadAdError) {
                                    // No crash, no retry loop, no user-facing error —
                                    // the reserved slot simply stays empty until the
                                    // SDK's own refresh / a future screen creates a slot.
                                    Log.w(
                                        "AnchoredAdaptiveBanner",
                                        "Banner failed: code=${error.code} domain=${error.domain} msg=${error.message}"
                                    )
                                }
                            }
                            adView = this
                            loadAd(AdRequest.Builder().build())
                        }
                    },
                    update = { view ->
                        // Width changed (rotation / resize): adopt the new adaptive size
                        // and load one replacement ad for the new slot.
                        if (view.adSize != adSize) {
                            view.setAdSize(adSize)
                            view.loadAd(AdRequest.Builder().build())
                        }
                    }
                )

                DisposableEffect(adView) {
                    onDispose {
                        // If consent was withdrawn or the screen is gone: remove and destroy.
                        (adView?.parent as? ViewGroup)?.removeView(adView)
                        adView?.destroy()
                        adView = null
                    }
                }
            }
        }
    }
}
