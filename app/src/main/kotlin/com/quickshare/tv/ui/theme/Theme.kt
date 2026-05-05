package com.quickshare.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.quickshare.tv.QuickShareApp

// ============================================================================
//  Material 3 Expressive — color schemes
// ----------------------------------------------------------------------------
//  Mapping rationale
//
//  - Slate ramp ([QuickShareColors] C50–C950) ≈ M3 tonal palette
//    T98 / T95 / T90 / T80 / T60 / T50 / T40 / T30 / T20 / T10 / T5.
//    Each role below targets the M3 Expressive tone for that role
//    (e.g. light primary = T20, dark primary = T90 — Expressive pushes
//    primary one step toward the extremes vs. the calm M3 baseline).
//  - TV M3 1.0.0 has no `surfaceContainer*` tonal ladder (it ships post-1.0).
//    We approximate depth with `surface` ≠ `background` and use
//    `surfaceVariant` for a third level.
//  - TV-specific roles `border` / `borderVariant` replace M3's
//    `outline` / `outlineVariant`.
//  - Error family uses the [QuickShareColors] R-ramp (Tailwind-derived red,
//    accessible contrast tuned for M3 Expressive defaults).
//  - Success has no native M3 role; it lives in [QuickShareExtraColors]
//    below and follows the same 4-token (color / on / container / on-
//    container) shape as M3's error family for symmetry.
// ============================================================================

private val LightScheme = lightColorScheme(
    // Primary — bold dark slate (Expressive intensity, T20).
    primary               = QuickShareColors.C800,
    onPrimary             = QuickShareColors.C50,
    primaryContainer      = QuickShareColors.C200,
    onPrimaryContainer    = QuickShareColors.C900,
    inversePrimary        = QuickShareColors.C400,

    // Secondary — mid slate (T40). Container deepened to T30 (C300) so
    // unfocused buttons and "calm" panels sit visibly above the C50
    // background instead of fading into it (C200 was effectively
    // invisible on white-paper backgrounds).
    secondary             = QuickShareColors.C600,
    onSecondary           = QuickShareColors.C50,
    secondaryContainer    = QuickShareColors.C300,
    onSecondaryContainer  = QuickShareColors.C800,

    // Tertiary — middle of the ramp; with a monochromatic palette this
    // necessarily shares hue with secondary, distinguished only by tone.
    tertiary              = QuickShareColors.C500,
    onTertiary            = QuickShareColors.C50,
    tertiaryContainer     = QuickShareColors.C300,
    onTertiaryContainer   = QuickShareColors.C900,

    // Backgrounds & surfaces — three layers of depth (bg → surface → variant).
    // `surfaceVariant` is the actual fill used for "boxes" (settings
    // cards, transfer cards, picker rows) and unfocused TV buttons;
    // bumped from C200 → C300 for the same readability reason as the
    // secondary container above.
    background            = QuickShareColors.C50,
    onBackground          = QuickShareColors.C950,
    surface               = QuickShareColors.C100,
    onSurface             = QuickShareColors.C900,
    surfaceVariant        = QuickShareColors.C300,
    onSurfaceVariant      = QuickShareColors.C600,
    surfaceTint           = QuickShareColors.C800,
    inverseSurface        = QuickShareColors.C800,
    inverseOnSurface      = QuickShareColors.C100,

    // Error — Tailwind red @ M3 Expressive light targets (T40 / T98 / T90 / T10).
    error                 = QuickShareColors.R600,
    onError               = QuickShareColors.R50,
    errorContainer        = QuickShareColors.R100,
    onErrorContainer      = QuickShareColors.R900,

    // TV-specific outline equivalents. `borderVariant` is the "soft"
    // outline that sits *on top of* `surfaceVariant`, so it has to be
    // at least one tonal step darker than the fill — bumped C200 → C400
    // to stay visible now that the fill itself is C300.
    border                = QuickShareColors.C500,
    borderVariant         = QuickShareColors.C400,

    // Modal/scrim overlay (used at low alpha).
    scrim                 = QuickShareColors.C950,
)

private val DarkScheme = darkColorScheme(
    // Primary — bright slate (Expressive intensity, T90).
    primary               = QuickShareColors.C200,
    onPrimary             = QuickShareColors.C800,
    primaryContainer      = QuickShareColors.C700,
    onPrimaryContainer    = QuickShareColors.C200,
    inversePrimary        = QuickShareColors.C600,

    secondary             = QuickShareColors.C400,
    onSecondary           = QuickShareColors.C950,
    secondaryContainer    = QuickShareColors.C700,
    onSecondaryContainer  = QuickShareColors.C200,

    tertiary              = QuickShareColors.C500,
    onTertiary            = QuickShareColors.C950,
    tertiaryContainer     = QuickShareColors.C800,
    onTertiaryContainer   = QuickShareColors.C300,

    background            = QuickShareColors.C950,
    onBackground          = QuickShareColors.C50,
    surface               = QuickShareColors.C900,
    onSurface             = QuickShareColors.C100,
    surfaceVariant        = QuickShareColors.C800,
    onSurfaceVariant      = QuickShareColors.C300,
    surfaceTint           = QuickShareColors.C200,
    inverseSurface        = QuickShareColors.C100,
    inverseOnSurface      = QuickShareColors.C800,

    // Error — M3 Expressive dark targets (T80 / T20 / T30 / T90).
    error                 = QuickShareColors.R400,
    onError               = QuickShareColors.R950,
    errorContainer        = QuickShareColors.R700,
    onErrorContainer      = QuickShareColors.R200,

    border                = QuickShareColors.C600,
    borderVariant         = QuickShareColors.C700,

    scrim                 = QuickShareColors.C950,
)

// ============================================================================
//  Extended semantic colors — success
// ----------------------------------------------------------------------------
//  M3 (and M3 Expressive) doesn't define a `success` role. We mirror the
//  4-token shape of the `error` family so call sites read symmetrically:
//
//      colorScheme.error / colorScheme.onError / .errorContainer / .onErrorContainer
//      qsExtras.success  / qsExtras.onSuccess  / .successContainer / .onSuccessContainer
// ============================================================================

@Immutable
data class QuickShareExtraColors(
    /** Primary success color — equivalent intensity to `colorScheme.error`. */
    val success: Color,
    /** Text / icon color on top of [success]-filled surfaces. */
    val onSuccess: Color,
    /** Soft tinted container for success messaging (banners, chips). */
    val successContainer: Color,
    /** Text / icon color on top of [successContainer]. */
    val onSuccessContainer: Color,
)

private val LightExtraColors = QuickShareExtraColors(
    success            = QuickShareColors.G600,
    onSuccess          = QuickShareColors.G50,
    successContainer   = QuickShareColors.G100,
    onSuccessContainer = QuickShareColors.G900,
)

private val DarkExtraColors = QuickShareExtraColors(
    success            = QuickShareColors.G400,
    onSuccess          = QuickShareColors.G950,
    successContainer   = QuickShareColors.G800,
    onSuccessContainer = QuickShareColors.G200,
)

val LocalQuickShareExtraColors = staticCompositionLocalOf<QuickShareExtraColors> {
    error("Quick Share theme extras not provided. Wrap content with QuickShareTheme().")
}

/** Read the success color family from any `@Composable`. */
val qsExtras: QuickShareExtraColors
    @Composable
    @ReadOnlyComposable
    get() = LocalQuickShareExtraColors.current

// ============================================================================
//  Theme entry points
// ============================================================================

@Composable
fun QuickShareTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val extras = if (darkTheme) DarkExtraColors else LightExtraColors
    TvMaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalQuickShareExtraColors provides extras,
            content = content,
        )
    }
}

/**
 * Application-level theme entry — subscribes to the persisted dark-theme
 * setting and applies [QuickShareTheme]. `MainActivity` calls this so the
 * settings persistence layer is wired in once and forgotten.
 */
@Composable
fun QuickShareThemeHost(content: @Composable () -> Unit) {
    val settings = QuickShareApp.instance.settingsRepository
    var dark by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { settings.useDarkThemeFlow.collect { dark = it } }
    QuickShareTheme(darkTheme = dark, content = content)
}
