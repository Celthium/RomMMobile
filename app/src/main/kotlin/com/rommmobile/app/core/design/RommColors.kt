package com.rommmobile.app.core.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * RomM web **v2** tokens (`frontend/src/v2/styles/tokens.css`, RomM 5.4.1), the interface RomM
 * shows by default since 5.x. The web app layers translucent whites over a near-black page; on
 * Android the same layers are flattened to opaque colours (computed over `bg`) wherever cards
 * stack, so nothing gets darker or lighter depending on what happens to sit underneath. Hairline
 * borders stay translucent, as in the CSS.
 *
 * Field names from the v1 palette are kept (`toplayer`, `primaryLighten`...) so every screen keeps
 * compiling; the new v2 roles are added next to them.
 */
@Immutable
data class RommColors(
    val isDark: Boolean,
    /** brand-primary */
    val primary: Color,
    /** brand-primary-hover */
    val primaryLighten: Color,
    /** brand-primary-pressed */
    val primaryDarken: Color,
    val secondary: Color,
    val secondaryLighten: Color,
    val secondaryDarken: Color,
    val accent: Color,
    /** --r-color-bg: the page. */
    val background: Color,
    /** --r-color-bg-elevated: tiles and cards resting on the page. */
    val elevated: Color,
    /** --r-color-surface: inputs, pills, buttons. */
    val surface: Color,
    /** --r-color-surface-hover: the same, focused or pressed. */
    val surfaceHover: Color,
    /** --r-color-panel: dialogs, sheets, menus. */
    val toplayer: Color,
    val border: Color,
    val borderStrong: Color,
    val onBackground: Color,
    val onSurface: Color,
    /** --r-color-fg-secondary */
    val onSurfaceSecondary: Color,
    /** --r-color-fg-muted */
    val onSurfaceMuted: Color,
    /** --r-color-fg-faint */
    val onSurfaceFaint: Color,
    /** --r-color-cover-placeholder: what a cover box shows while its image loads. */
    val coverPlaceholder: Color,
    val red: Color = Color(0xFFDA3633),
    val green: Color = Color(0xFF3FB950),
    val blue: Color = Color(0xFF0070F3),
    val white: Color = Color(0xFFFEFDFE),
    val gray: Color = Color(0xFF5D5D5D),
    val black: Color = Color(0xFF000000),
    val gold: Color = Color(0xFFFFD700),
    val fav: Color = Color(0xFFFF4F6B),
    val success: Color = Color(0xFF4ADE80),
    val warning: Color = Color(0xFFFBBF24),
    val danger: Color = Color(0xFFFF5050),
    val info: Color = Color(0xFF93C5FD),
    /** Overlays drawn on top of cover art: identical in both themes, since art is not themed. */
    val overlayFg: Color = Color(0xF2FFFFFF),
    val overlayBorder: Color = Color(0x1FFFFFFF),
    val overlayBorderStrong: Color = Color(0x40FFFFFF),
    val scrimSoft: Color = Color(0x8C000000),
    val scrimStrong: Color = Color(0xC7000000),
    /** The white "emphasis" pill of the web app (Play / primary action) and its dark label. */
    val emphasis: Color = Color(0xFFFFFFFF),
    val emphasisPressed: Color = Color(0xFFE6E6E6),
    val onEmphasis: Color = Color(0xFF111117),
    /** Kept from v1 for the few places that still draw a band under text on art. */
    val translucent: Color = Color(0x8C000000),
) {
    fun toColorScheme(): ColorScheme = if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primaryDarken,
            onPrimaryContainer = secondaryLighten,
            inversePrimary = primaryLighten,
            secondary = secondary,
            onSecondary = background,
            secondaryContainer = surfaceHover,
            onSecondaryContainer = onSurface,
            tertiary = accent,
            onTertiary = background,
            tertiaryContainer = accent.copy(alpha = 0.25f),
            onTertiaryContainer = accent,
            background = background,
            onBackground = onBackground,
            surface = toplayer,
            onSurface = onSurface,
            surfaceVariant = surface,
            onSurfaceVariant = onSurfaceMuted,
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = background,
            surfaceContainerLow = elevated,
            surfaceContainer = toplayer,
            surfaceContainerHigh = toplayer,
            surfaceContainerHighest = surfaceHover,
            surfaceBright = surfaceHover,
            surfaceDim = background,
            inverseSurface = Color.White,
            inverseOnSurface = onEmphasis,
            error = danger,
            onError = Color.White,
            errorContainer = red.copy(alpha = 0.25f),
            onErrorContainer = Color(0xFFFFB4AB),
            outline = borderStrong,
            outlineVariant = border,
            scrim = black,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primary.copy(alpha = 0.14f),
            onPrimaryContainer = primary,
            inversePrimary = primaryLighten,
            secondary = secondary,
            onSecondary = Color.White,
            secondaryContainer = surfaceHover,
            onSecondaryContainer = onSurface,
            tertiary = accent,
            onTertiary = black,
            background = background,
            onBackground = onBackground,
            surface = toplayer,
            onSurface = onSurface,
            surfaceVariant = surface,
            onSurfaceVariant = onSurfaceMuted,
            surfaceTint = Color.Transparent,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = elevated,
            surfaceContainer = toplayer,
            surfaceContainerHigh = toplayer,
            surfaceContainerHighest = surfaceHover,
            error = red,
            onError = Color.White,
            outline = borderStrong,
            outlineVariant = border,
            scrim = black,
        )
    }
}

val DarkRommColors = RommColors(
    isDark = true,
    primary = Color(0xFF8B74E8),
    primaryLighten = Color(0xFFA18FFF),
    primaryDarken = Color(0xFF6043C8),
    secondary = Color(0xFF9E8CD6),
    secondaryLighten = Color(0xFFEBE7FA),
    secondaryDarken = Color(0xFF7A6BB4),
    accent = Color(0xFFE1A38D),
    background = Color(0xFF07070F),
    // rgba(255,255,255,.045 / .07 / .12) flattened over #07070F.
    elevated = Color(0xFF12121A),
    surface = Color(0xFF181820),
    surfaceHover = Color(0xFF25252C),
    // --r-color-panel rgba(16,12,28,.97), nudged up a step so a dialog still reads as a layer
    // above the page on an OLED handheld.
    toplayer = Color(0xFF15111F),
    border = Color(0x12FFFFFF),
    borderStrong = Color(0x26FFFFFF),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceSecondary = Color(0xD4FFFFFF),
    onSurfaceMuted = Color(0x8CFFFFFF),
    onSurfaceFaint = Color(0x73FFFFFF),
    coverPlaceholder = Color(0xFF1A1A2E),
)

val LightRommColors = RommColors(
    isDark = false,
    primary = Color(0xFF553E98),
    primaryLighten = Color(0xFF7850E6),
    primaryDarken = Color(0xFF371F69),
    secondary = Color(0xFF553E98),
    secondaryLighten = Color(0xFFEBE7FA),
    secondaryDarken = Color(0xFF3F2C78),
    accent = Color(0xFFE1A38D),
    background = Color(0xFFF5F5FA),
    elevated = Color(0xFFFFFFFF),
    // rgba(0,0,0,.035 / .10) flattened over #F5F5FA.
    surface = Color(0xFFECECF1),
    surfaceHover = Color(0xFFDDDDE1),
    toplayer = Color(0xFFFFFFFF),
    border = Color(0x1A000000),
    borderStrong = Color(0x38000000),
    onBackground = Color(0xFF111117),
    onSurface = Color(0xFF111117),
    onSurfaceSecondary = Color(0xE0111117),
    onSurfaceMuted = Color(0xBD111117),
    onSurfaceFaint = Color(0x99111117),
    coverPlaceholder = Color(0xFFE4E2EF),
)

val LocalRommColors = staticCompositionLocalOf { DarkRommColors }
