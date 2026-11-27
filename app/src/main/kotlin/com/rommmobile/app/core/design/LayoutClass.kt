package com.rommmobile.app.core.design

import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.layout.layout
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class WidthClass { COMPACT, MEDIUM, EXPANDED }
enum class HeightClass { SHORT, TALL }
enum class ScreenShape { PORTRAIT, SQUARE, LANDSCAPE }

/** Where the primary navigation lives, and how wide it is. */
enum class NavMode(val railWidth: Dp, val showLabels: Boolean) {
    BOTTOM_BAR(0.dp, true),
    RAIL_COMPACT(56.dp, false),
    RAIL(80.dp, true),
    RAIL_TV(96.dp, true),
}

/**
 * Two-axis size class. Width alone is useless on handhelds: a Retroid Pocket Classic is
 * 472x411 dp (compact width, ridiculous height) and an RG Cube is a 360 dp square.
 */
@Immutable
data class LayoutClass(
    val widthDp: Int,
    val heightDp: Int,
    val isTv: Boolean,
    val fontScale: Float,
) {
    val widthClass: WidthClass = when {
        widthDp < 600 -> WidthClass.COMPACT
        widthDp < 840 -> WidthClass.MEDIUM
        else -> WidthClass.EXPANDED
    }
    val heightClass: HeightClass = if (heightDp < 480) HeightClass.SHORT else HeightClass.TALL
    val shape: ScreenShape = run {
        val ratio = widthDp.toFloat() / heightDp.coerceAtLeast(1)
        when {
            ratio < 0.9f -> ScreenShape.PORTRAIT
            ratio <= 1.25f -> ScreenShape.SQUARE
            else -> ScreenShape.LANDSCAPE
        }
    }
    val isShort: Boolean get() = heightClass == HeightClass.SHORT
    val isCompact: Boolean get() = widthClass == WidthClass.COMPACT

    // Square screens (a dual-screen handheld's 1240x1080 bottom panel, an RG Cube) get the bar at
    // the bottom like a phone: a rail there eats a fifth of an already narrow width. Only wide
    // landscape screens keep the rail on the D-pad side.
    // The floating bottom pill everywhere, as in the v2 web app on phones and on the owner's
    // dual-screen handheld (both its 16:9 top and square bottom panels). Only TVs keep a rail:
    // a remote has no thumb near the bottom of the screen.
    // Rail on the left for wide landscape screens (a 16:9 handheld: the D-pad thumb is there),
    // the floating bottom pill everywhere else (phones, the square bottom panel of a dual-screen
    // handheld).
    val navMode: NavMode = when {
        isTv -> NavMode.RAIL_TV
        shape == ScreenShape.LANDSCAPE && heightClass == HeightClass.SHORT -> NavMode.RAIL_COMPACT
        shape == ScreenShape.LANDSCAPE -> NavMode.RAIL
        else -> NavMode.BOTTOM_BAR
    }
    val bottomBarHeight: Dp = if (isShort) 62.dp else 72.dp
    val topBarHeight: Dp = when {
        isTv -> 48.dp
        isShort -> 36.dp
        else -> 56.dp
    }
    val padding: Dp = if (isShort) 8.dp else 12.dp
    /**
     * The one horizontal page margin: grids, titles, the back arrow's glyph, search field, hint
     * bar and the game page all start this far from the edge (or from the side panel).
     */
    val margin: Dp = if (isShort) 20.dp else 24.dp
    /** v2 gallery gap is 12 px; a short handheld screen gets a little less. */
    val gap: Dp = if (isShort) 8.dp else 12.dp

    /** Minimum cell for cover grids; the AlphabetRail width is subtracted by the caller. */
    val coverMinCell: Dp = run {
        val base = when {
            isTv || widthDp >= 840 -> 148
            widthDp >= 600 -> 128
            widthDp >= 400 -> 116
            else -> 104
        }
        (if (isShort) base - 8 else base).dp
    }
    val platformMinCell: Dp = coverMinCell + 16.dp
    val twoPane: Boolean = widthClass == WidthClass.EXPANDED && shape == ScreenShape.LANDSCAPE
    /** Letter strip width, plus the grid gap kept free on its right and above it. */
    val alphabetRailWidth: Dp = if (widthDp >= 600) 38.dp else 34.dp
    /** Focus is shown by colour, not size (owner's call); a TV, read from the sofa, still grows. */
    val focusScale: Float = if (isTv) 1.10f else 1f
    val overscanInset: Dp = if (isTv) (minOf(widthDp, heightDp) * 0.05f).dp else 0.dp
    val miniBarHeight: Dp = 44.dp
    val listRowHeight: Dp = 56.dp
    val maxColumns: Int = 8
}

/**
 * Height of the floating navigation drawn OVER the bottom of the content (phones, square
 * screens). Scrollable lists add it to their bottom padding so the last row can be scrolled clear
 * of the bar, while everything above still passes behind its gradient. Zero with a side rail.
 */
val LocalBottomOverlay = staticCompositionLocalOf { 0.dp }

/** Bottom padding a scrolling list needs under the floating bar, pad hints included. */
val bottomOverlayPadding: Dp
    @androidx.compose.runtime.Composable @androidx.compose.runtime.ReadOnlyComposable
    // The pad hint bar now floats over the bottom of every list, so the last row always needs
    // room for it, plus the floating nav bar when there is one.
    get() = LocalBottomOverlay.current + 52.dp

/** Height of the system navigation bar the current screen draws under (see [ExtendUnderNavBar]). */
val LocalNavBarInset = staticCompositionLocalOf { 0.dp }

/**
 * Lets a scrolling screen run under the system navigation bar (gesture handle or 3 buttons),
 * like the web app's content under the browser chrome: the screen is measured that much taller
 * and the inset is published, so grids pad their last row and the floating bars sit above it.
 */
@Composable
fun ExtendUnderNavBar(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    // With the keyboard up the navigation bar is under it: nothing to extend into.
    val imeUp = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density) > 0
    val insetPx = if (imeUp) 0 else androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(density)
    val inset = with(density) { insetPx.toDp() }
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.layout { m, c ->
            val h = if (c.hasBoundedHeight) c.maxHeight + insetPx else c.maxHeight
            val p = m.measure(c.copy(minHeight = h, maxHeight = h))
            layout(c.maxWidth, c.maxHeight) { p.place(0, 0) }
        },
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalNavBarInset provides inset,
            LocalBottomOverlay provides inset,
        ) { content() }
    }
}

val LocalLayoutClass = staticCompositionLocalOf { LayoutClass(393, 873, isTv = false, fontScale = 1f) }

@Composable
fun rememberLayoutClass(): LayoutClass {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val container = LocalWindowInfo.current.containerSize
    val isTv = remember(context) { context.isTelevision() }
    val widthDp = if (container.width > 0) (container.width / density.density).roundToInt() else configuration.screenWidthDp
    val heightDp = if (container.height > 0) (container.height / density.density).roundToInt() else configuration.screenHeightDp
    return remember(widthDp, heightDp, isTv, configuration.fontScale) {
        LayoutClass(widthDp = widthDp, heightDp = heightDp, isTv = isTv, fontScale = configuration.fontScale)
    }
}

fun Context.isTelevision(): Boolean {
    val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
