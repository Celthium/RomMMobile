package com.rommmobile.app.core.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** Extra safe margin (0-16 dp) for rounded corners / TV overscan, chosen in settings. */
val LocalScreenMargin = staticCompositionLocalOf { 0.dp }

object RommTheme {
    val colors: RommColors
        @Composable @ReadOnlyComposable get() = LocalRommColors.current
    val layout: LayoutClass
        @Composable @ReadOnlyComposable get() = LocalLayoutClass.current
}

@Composable
fun RommTheme(
    themeMode: ThemeMode,
    screenMarginDp: Int = 0,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkRommColors else LightRommColors
    val layout = rememberLayoutClass()
    // The user "screen margin" is no longer applied: padding the whole app also pushed the side
    // panel off the edge, and the edge-to-edge layout already keeps clear of system bars and
    // cutouts. Only a TV keeps its automatic overscan inset.
    val margin: Dp = if (layout.isTv) layout.overscanInset else 0.dp
    // The app draws its own theme, not the system's: with the phone in light mode and the app in
    // dark, the status bar kept dark icons on a near-black page and the clock vanished.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val backdrop = remember { mutableStateOf<BackdropRequest?>(null) }
    CompositionLocalProvider(
        LocalRommColors provides colors,
        LocalLayoutClass provides layout,
        LocalScreenMargin provides margin,
        LocalBackdrop provides backdrop,
    ) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = rommTypography(layout),
            shapes = RommShapes,
        ) {
            // Material pads every Button and IconButton out to a 48 dp touch target while drawing
            // the control smaller inside it. The focus ring is drawn on the node, so it followed
            // that invisible box and came out a different shape from the button. This app's
            // controls are already large enough to hit, so the padding goes.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            // Without a Surface at the root, LocalContentColor stays at its Compose default
            // (black) and every Text that does not name a colour is drawn black on the dark
            // background. This one wrapper is what makes body text readable in both themes.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colors.background,
                contentColor = colors.onBackground,
            ) {
                Box(Modifier.fillMaxSize()) {
                    BackdropHost()
                    content()
                }
            }
            }
        }
    }
}

/**
 * RomM v2 type scale (10.5 / 11.5 / 13 / 14.5 / 17 / 22 / 32 / 38 px, weights 400-800). The web
 * stack starts with Segoe UI and falls back to Roboto, which is what Android has: system font,
 * nothing bundled. Titles are heavier and tighter than in v1, which is most of the new look.
 */
private fun rommTypography(layout: LayoutClass): Typography {
    val titleSize = if (layout.isShort) 21.sp else 23.sp
    val f = FontFamily.Default
    return Typography(
        displayMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.5).sp),
        headlineMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.ExtraBold, fontSize = if (layout.isShort) 24.sp else 30.sp, lineHeight = if (layout.isShort) 27.sp else 33.sp, letterSpacing = (-0.4).sp),
        headlineSmall = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
        titleLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = titleSize, lineHeight = (titleSize.value + 5).sp, letterSpacing = (-0.1).sp),
        titleMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        titleSmall = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
        bodyLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 14.5.sp, lineHeight = 21.sp),
        bodyMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, lineHeight = 15.sp),
        labelLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
        labelMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, lineHeight = 15.sp),
        labelSmall = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 13.sp, letterSpacing = 0.3.sp),
    )
}

/** v2 radii: xs 4, chip 6, art 8, lg 10, card 14, xl 20, pill. */
val RommShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Cover art on the detail page and collection mosaics (--r-radius-lg). */
val ArtShapeLarge = RoundedCornerShape(10.dp)

val PillShape = RoundedCornerShape(999.dp)
