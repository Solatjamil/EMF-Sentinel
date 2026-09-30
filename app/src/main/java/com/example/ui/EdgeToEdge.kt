package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.ui.theme.SpaceBlack

/*
 * Edge-to-edge helpers.
 *
 * WHY THIS EXISTS (Play Console: "Your app uses deprecated APIs or parameters for edge-to-edge"):
 *
 * androidx.activity's enableEdgeToEdge() contains per-API "shims" (EdgeToEdgeApi23 … Api35) that
 * call Window.setStatusBarColor(), Window.setNavigationBarColor() and write
 * LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES. Android 15 deprecated all three, and Play's static
 * scanner flags those references even though the app never calls them itself — and upgrading
 * androidx.activity does not remove them. (WindowCompat.enableEdgeToEdge() from androidx.core is
 * NOT a way out: it calls the very same deprecated setters.)
 *
 * So the window is configured here using only calls that are NOT deprecated:
 *   - WindowCompat.setDecorFitsSystemWindows(false)   -> draw behind the system bars
 *   - Window.setNavigationBarContrastEnforced(false)  -> no forced scrim behind 3-button nav (API 29+)
 *   - LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS            -> draw into the display cutout (API 30+)
 * while the TRANSPARENT status/navigation bar colours come from the theme
 * (res/values/themes.xml), which needs no code call at all. On Android 15+ the system draws
 * transparent bars by itself. On API 28-29 the window keeps the platform's default cutout mode
 * (identical in portrait; SHORT_EDGES is one of the deprecated constants we avoid).
 *
 * Release builds also enable R8 so the now-unreferenced androidx.activity shims are stripped
 * from the shipped DEX (see docs/IMPLEMENTATION_NOTES.md §F).
 */

/**
 * Makes this activity's window edge-to-edge without touching any API that Android 15 deprecated.
 *
 * Call it BEFORE `super.onCreate()` so the very first frame is already laid out edge-to-edge.
 * Content then has to apply window insets itself (Compose: `Scaffold`, `windowInsetsPadding`,
 * `statusBarsPadding`, `navigationBarsPadding`, …).
 */
fun Activity.enableEdgeToEdgeCompat() {
    val window = window
    WindowCompat.setDecorFitsSystemWindows(window, false)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Fully transparent 3-button navigation bar instead of the system's translucent scrim;
        // the app paints its own opaque background behind it (see EMFSentinelApp's bottom bar).
        window.isNavigationBarContrastEnforced = false
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val attributes = window.attributes
        if (attributes.layoutInDisplayCutoutMode !=
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        ) {
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            window.attributes = attributes
        }
    }
}

/**
 * Keeps the status-bar and navigation-bar ICONS legible against the app's own background.
 *
 * The app has an in-app dark/light toggle that is independent of the system night mode, so the
 * default "follow the system theme" behaviour of `enableEdgeToEdge()` would leave dark icons on
 * a dark screen (or light icons on a light one). Dark icons are requested for the light theme.
 * (Below API 26 the system cannot tint the navigation-bar icons; see [navigationBarBackdropColor].)
 */
@Composable
fun SystemBarIconsEffect(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }
}

/**
 * The status bars + navigation bars + display cutout (notch / punch-hole) as one inset.
 *
 * Interactive UI must never be drawn under any of these. The display cutout matters in
 * landscape, where the notch sits beside the content rather than inside the status bar.
 */
@Composable
fun systemBarsAndCutoutInsets(): WindowInsets {
    val bars = WindowInsets.systemBars
    val cutout = WindowInsets.displayCutout
    return remember(bars, cutout) { bars.union(cutout) }
}

/**
 * Insets for a TOP bar: the status bar / cutout at the top plus the horizontal insets (a landscape
 * notch or a side-mounted navigation bar), but not the bottom inset.
 *
 * Built from single [WindowInsetsSides] values on purpose: `WindowInsetsSides.plus` only became
 * public API in Compose foundation 1.10, while this project resolves foundation 1.7 via its BOM.
 */
@Composable
fun topBarWindowInsets(): WindowInsets {
    val all = systemBarsAndCutoutInsets()
    return remember(all) {
        all.only(WindowInsetsSides.Top).union(all.only(WindowInsetsSides.Horizontal))
    }
}

/**
 * Colour to paint behind the (transparent) navigation bar.
 *
 * The app draws its own background under the bar. Before API 26 the platform cannot render dark
 * navigation-bar icons, so on the LIGHT theme a dark strip is used there to keep the white
 * buttons visible; everywhere else the normal theme background is used.
 */
fun navigationBarBackdropColor(darkTheme: Boolean, themeBackground: Color): Color =
    if (!darkTheme && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) SpaceBlack else themeBackground

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
