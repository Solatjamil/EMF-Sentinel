package com.example.ads

import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.Amber500
import com.example.ui.theme.Amber600
import com.example.ui.theme.Emerald500
import com.example.ui.theme.LightBorder
import com.example.ui.theme.LightSurface
import com.example.ui.theme.LightTextPrimary
import com.example.ui.theme.LightTextSecondary
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.SpaceBlack
import com.example.ui.theme.Zinc800
import com.example.ui.theme.Zinc900
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import java.util.Locale

private const val TAG = "NativeAdCard"

/**
 * AdMob **Native (advanced)** ad, rendered as a card that matches the app's look — it sits
 * ALONGSIDE the anchored adaptive banner, it does not replace it.
 *
 * Implementation follows Google's "Display a native ad" guide
 * (https://developers.google.com/admob/android/native/advanced) for the Google Mobile Ads SDK
 * (Legacy) and the AdMob native-advanced policy (https://support.google.com/admob/answer/6329638):
 *
 *  - The creative is drawn by a real [NativeAdView]; EVERY asset view lives inside it, and each one
 *    is registered with the SDK (headline, body, call to action, icon, advertiser, star rating and
 *    the [MediaView]) BEFORE `setNativeAd()` is called, so the SDK records clicks/impressions and
 *    places the AdChoices overlay itself.
 *  - A clearly visible "Ad" attribution badge is always shown (policy: required, >= 15px).
 *  - Room is left in the top-right corner for the AdChoices overlay (never camouflaged).
 *  - The MediaView is never smaller than 120dp, is never stretched or cropped, and video starts muted.
 *  - No custom click handlers anywhere on or over the ad, and no clickable background
 *    ("no clickable white space"). Nothing is overlaid on the ad.
 *  - Text is never truncated below the policy minimums (headline 25, body 90, CTA 15 characters).
 *  - Requests nothing while UMP consent denies ads ([AdConsentManager.canRequestAds] gate).
 *  - One ad per composition; it is destroyed when the card leaves the screen, when consent is
 *    withdrawn, and if it arrives after the card is already gone. Load failures are logged only —
 *    no retry loop, no crash — and while there is no ad the card emits NOTHING (zero height, no
 *    phantom spacing), so the screen simply looks as it did without ads.
 *
 * Place it inside scrollable, read-only content, away from navigation and other touch controls.
 * In debug/qa builds [adUnitId] is Google's demo native unit, in release it is the production unit
 * (see app/build.gradle.kts). A brand-new production unit can take up to ~1 hour to start serving.
 */
@Composable
fun NativeAdCard(
    adUnitId: String,
    canRequestAds: Boolean,
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    val nativeAd = rememberNativeAd(adUnitId = adUnitId, canRequestAds = canRequestAds).value
        ?: return // nothing loaded (yet): emit no layout at all
    val palette = if (isDarkMode) NativeAdPalette.Dark else NativeAdPalette.Light

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context -> createNativeAdView(context) },
        update = { adView -> bindNativeAd(adView, nativeAd, palette) }
    )
}

/**
 * Loads exactly one native ad while ads are allowed and exposes it as Compose state.
 * Mirrors the lifecycle in Google's Compose sample: the ad is destroyed on dispose, and an ad
 * that finishes loading after disposal is destroyed immediately instead of leaking.
 */
@Composable
private fun rememberNativeAd(adUnitId: String, canRequestAds: Boolean): State<NativeAd?> {
    val context = LocalContext.current
    val adState = remember { mutableStateOf<NativeAd?>(null) }

    DisposableEffect(adUnitId, canRequestAds) {
        // Consent gate: no AdLoader (therefore no ad request) while ads are not allowed.
        if (!canRequestAds || !AdConsentManager.canRequestAds) {
            return@DisposableEffect onDispose { }
        }

        var disposed = false
        val adLoader = AdLoader.Builder(context, adUnitId)
            .forNativeAd { loadedAd ->
                if (disposed) {
                    loadedAd.destroy() // arrived after the card left the screen
                } else {
                    adState.value?.destroy()
                    adState.value = loadedAd
                }
            }
            .withAdListener(object : AdListener() {
                override fun onAdLoaded() {
                    Log.d(TAG, "Native ad loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // No crash, no retry loop, nothing shown: the card stays absent until the
                    // screen is opened again.
                    Log.w(
                        TAG,
                        "Native ad failed: code=${error.code} domain=${error.domain} msg=${error.message}"
                    )
                }
            })
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    // Landscape media matches the fixed 16:9 MediaView slot below (no letterboxing).
                    .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE)
                    // The card reserves space in the top-right corner for this overlay.
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
                    .build()
            )
            .build()
        adLoader.loadAd(AdRequest.Builder().build())

        onDispose {
            disposed = true
            adState.value?.destroy()
            adState.value = null
        }
    }
    return adState
}

// ───────────────────────────── View construction / binding ─────────────────────────────

/** Colours for the card; ARGB ints because the ad surface is built from classic Views. */
private class NativeAdPalette(
    val cardBackground: Int,
    val cardBorder: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val onAccent: Int,
    val stars: Int
) {
    companion object {
        val Dark = NativeAdPalette(
            cardBackground = Zinc900.copy(alpha = 0.5f).toArgb(), // same as the neighbouring cards
            cardBorder = Zinc800.toArgb(),
            textPrimary = Slate200.toArgb(),
            textSecondary = Slate400.toArgb(),
            accent = Emerald500.toArgb(),
            onAccent = SpaceBlack.toArgb(),
            stars = Amber500.toArgb()
        )
        val Light = NativeAdPalette(
            cardBackground = LightSurface.toArgb(),
            cardBorder = LightBorder.toArgb(),
            textPrimary = LightTextPrimary.toArgb(),
            textSecondary = LightTextSecondary.toArgb(),
            accent = Color(0xFF047857).toArgb(), // emerald-700: white text keeps >= 4.5:1 contrast
            onAccent = Color.White.toArgb(),
            stars = Amber600.toArgb()
        )
    }
}

/** The views of one ad card, stored in [NativeAdView.getTag] so `update` can re-bind them. */
private class NativeAdViews(
    val mediaFrame: View,
    val advertiser: TextView,
    val icon: ImageView,
    val headline: TextView,
    val stars: TextView,
    val body: TextView,
    val callToAction: TextView
) {
    /** The ad currently populated into these views; lets a theme-only update skip re-registering. */
    var boundAd: NativeAd? = null
}

/** FrameLayout that keeps a fixed aspect ratio (and a minimum height) for the MediaView. */
private class AspectRatioFrameLayout(
    context: Context,
    private val aspectRatio: Float,
    private val minHeightPx: Int
) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = maxOf((width / aspectRatio).toInt(), minHeightPx)
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
    }
}

private fun createNativeAdView(context: Context): NativeAdView {
    val density = context.resources.displayMetrics.density
    fun px(dp: Int): Int = (dp * density + 0.5f).toInt()
    fun label(sizeSp: Float, bold: Boolean): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    val match = ViewGroup.LayoutParams.MATCH_PARENT

    // "Ad" attribution badge — rendered by the app, required by AdMob policy (min 15px).
    val badge = label(10f, bold = true).apply {
        text = "Ad"
        gravity = Gravity.CENTER
        minWidth = px(24)
        minHeight = px(18)
        setPadding(px(6), px(1), px(6), px(1))
        setTextColor(SpaceBlack.toArgb())
        background = GradientDrawable().apply {
            setCornerRadius(px(4).toFloat())
            setColor(Amber500.toArgb())
        }
    }
    val advertiser = label(12f, bold = false).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val headerRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(badge, LinearLayout.LayoutParams(wrap, wrap))
        addView(advertiser, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = px(8) })
    }

    val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, px(10).toFloat())
            }
        }
    }
    // Headline: two lines, well above the policy minimum of 25 un-truncated characters.
    val headline = label(16f, bold = true).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    val stars = label(12f, bold = true)
    val titleColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(headline, LinearLayout.LayoutParams(match, wrap))
        addView(stars, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = px(2) })
    }
    val identityRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon, LinearLayout.LayoutParams(px(44), px(44)).apply { marginEnd = px(12) })
        addView(titleColumn, LinearLayout.LayoutParams(0, wrap, 1f))
    }

    // MediaView: >= 120dp (video ads will not serve below that), 16:9, never stretched or cropped.
    val mediaAsset = MediaView(context).apply {
        layoutParams = FrameLayout.LayoutParams(match, match)
        setImageScaleType(ImageView.ScaleType.FIT_CENTER)
    }
    val mediaFrame = AspectRatioFrameLayout(context, aspectRatio = 16f / 9f, minHeightPx = px(120))
    mediaFrame.addView(mediaAsset)

    // Body: three lines (policy minimum is 90 un-truncated characters).
    val body = label(13f, bold = false).apply {
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
    }
    // Call to action. A plain TextView styled as a button: NO click listener of our own —
    // the SDK makes every registered asset clickable and handles the click.
    val callToAction = label(14f, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = px(44)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(px(16), px(10), px(16), px(10))
    }

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // Leave room for the AdChoices overlay the SDK draws in the (absolute) top-right corner.
        addView(headerRow, LinearLayout.LayoutParams(match, wrap).apply { rightMargin = px(28) })
        addView(identityRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(10) })
        addView(mediaFrame, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(12) })
        addView(body, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(10) })
        addView(callToAction, LinearLayout.LayoutParams(match, wrap).apply { topMargin = px(12) })
    }

    val adView = NativeAdView(context)
    adView.layoutParams = ViewGroup.LayoutParams(match, wrap)
    adView.setPadding(px(14), px(12), px(14), px(14))
    adView.addView(content, FrameLayout.LayoutParams(match, wrap))

    // Register every asset view with the SDK (before setNativeAd): clicks, impressions and the
    // AdChoices overlay are then handled by Google's code, not ours.
    adView.headlineView = headline
    adView.bodyView = body
    adView.callToActionView = callToAction
    adView.iconView = icon
    adView.advertiserView = advertiser
    adView.starRatingView = stars
    adView.mediaView = mediaAsset

    adView.tag = NativeAdViews(
        mediaFrame = mediaFrame,
        advertiser = advertiser,
        icon = icon,
        headline = headline,
        stars = stars,
        body = body,
        callToAction = callToAction
    )
    return adView
}

private fun bindNativeAd(adView: NativeAdView, nativeAd: NativeAd, palette: NativeAdPalette) {
    val views = adView.tag as? NativeAdViews ?: return
    val density = adView.resources.displayMetrics.density
    fun px(dp: Int): Int = (dp * density + 0.5f).toInt()

    // Theme colours: applied on every update (cheap, and follows the in-app dark/light toggle).
    // Card chrome is a bordered surface so the ad is always clearly distinguishable from content.
    adView.background = GradientDrawable().apply {
        setCornerRadius(px(24).toFloat())
        setColor(palette.cardBackground)
        setStroke(px(1), palette.cardBorder)
    }
    views.headline.setTextColor(palette.textPrimary)
    views.advertiser.setTextColor(palette.textSecondary)
    views.body.setTextColor(palette.textSecondary)
    views.stars.setTextColor(palette.stars)
    views.callToAction.setTextColor(palette.onAccent)
    views.callToAction.background = GradientDrawable().apply {
        setCornerRadius(px(12).toFloat())
        setColor(palette.accent)
    }

    // Assets + SDK registration: only when this is a different ad than the one already bound
    // (a theme toggle must not call setNativeAd() again for the same ad).
    if (views.boundAd === nativeAd) return

    views.headline.text = nativeAd.headline.orEmpty()
    views.advertiser.showText(nativeAd.advertiser)
    views.body.showText(nativeAd.body)
    views.callToAction.showText(nativeAd.callToAction)

    // Icon is "required if provided".
    val iconDrawable = nativeAd.icon?.drawable
    if (iconDrawable != null) {
        views.icon.setImageDrawable(iconDrawable)
        views.icon.visibility = View.VISIBLE
    } else {
        views.icon.visibility = View.GONE
    }

    val rating = nativeAd.starRating
    if (rating != null && rating > 0.0) {
        views.stars.text = String.format(Locale.getDefault(), "★ %.1f", rating)
        views.stars.visibility = View.VISIBLE
    } else {
        views.stars.visibility = View.GONE
    }

    views.mediaFrame.visibility = if (nativeAd.mediaContent != null) View.VISIBLE else View.GONE

    // Last step, after every asset view is populated and registered.
    adView.setNativeAd(nativeAd)
    views.boundAd = nativeAd
}

private fun TextView.showText(value: String?) {
    if (value.isNullOrBlank()) {
        visibility = View.GONE
    } else {
        text = value
        visibility = View.VISIBLE
    }
}
