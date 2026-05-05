package com.quickshare.tv.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.quickshare.tv.R
import com.quickshare.tv.ui.theme.Spacing
import com.quickshare.tv.ui.theme.glyphCentered

/**
 * Home — entry screen with two destinations (Receive / Send) and a peripheral
 * Settings affordance.
 *
 * ```
 *   ┌────────────────────────────────────────┐
 *   │                                  [⚙]   │  Settings — top-right
 *   │                                        │
 *   │             [Quick Share]              │  Brand icon (primary tint)
 *   │              Quick Share               │  Wordmark (primary tint, display style)
 *   │                                        │
 *   │            [⬇   Receive ]              │  Filled primary button
 *   │            [⬆   Send    ]              │  Filled primary button
 *   │                                        │
 *   │   📺  Make sure both devices are on...   │  Wi-Fi hint (low-emphasis)
 *   └────────────────────────────────────────┘
 * ```
 *
 * **Responsive sizing** — every layout dimension is derived from the actual
 * screen constraints reported by [BoxWithConstraints] and clamped against a
 * sensible TV envelope (so the layout doesn't pancake on tiny configs or
 * sprawl absurdly on huge ones). Typography steps up one M3 role on very
 * wide screens. **Nothing here is a hardcoded component size.**
 *
 * **Color roles** (M3 Expressive):
 * - Background          → `colorScheme.background`
 * - Brand icon tint     → `colorScheme.primary` (brand accent)
 * - Wordmark            → `colorScheme.onBackground` (max-contrast text)
 * - Receive / Send      → `primary` filled / `onPrimary` content
 * - Settings idle       → `surfaceVariant` / `onSurfaceVariant`
 * - Settings focused    → `primary` / `onPrimary` (auto-transition via Surface)
 *
 * **Focus**: initial focus lands on Receive so D-pad center fires immediately.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val receiveFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { receiveFocus.requestFocus() } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        // Screen-level constraints in dp. Every dimension below is a fraction
        // of these so the layout breathes proportionally on different TVs
        // (720p / 1080p / 4K with custom density / etc.) without branching.
        val w: Dp = maxWidth
        val h: Dp = maxHeight

        // ── Responsive layout sizes ─────────────────────────────────────
        // Pattern: `(fraction of screen) coerced into [min, max]`.
        // The clamp envelope is the design intent — percentages tuned
        // against the ~960 × 540 dp baseline most TVs report.
        val screenPaddingH  = Spacing.xl
        val screenPaddingV  = Spacing.lg

        val heroIcon        = (h * 0.18f ).coerceIn(72.dp, 160.dp)
        val heroToTitleGap  = (h * 0.04f ).coerceIn(Spacing.md,  Spacing.xl)
        val heroToActionsGap= (h * 0.09f ).coerceIn(Spacing.lg,  Spacing.xxl)

        val actionWidth     = (w * 0.24f ).coerceIn(180.dp, 300.dp)
        val buttonGap       = (h * 0.025f).coerceIn(Spacing.sm,  Spacing.lg)

        val settingsSize    = (minOf(w, h) * 0.08f).coerceIn(40.dp, 72.dp)
        val settingsIcon    = settingsSize * SettingsIconRatio

        val hintIcon        = (h * 0.035f).coerceIn(16.dp,  24.dp)
        val hintGap         = (h * 0.020f).coerceIn(Spacing.sm,  Spacing.md)

        // ── Responsive typography — step up one M3 role on very wide configs.
        // Avoids defining custom sp values; everything stays inside the M3
        // type ramp.
        val titleStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.displayMedium
            else typography.displaySmall
        val labelStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.titleLarge
            else typography.titleMedium
        val hintStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.bodyLarge
            else typography.bodyMedium

        // ── Typography-derived button geometry ──────────────────────────
        // Sizing the action buttons from screen height (the previous
        // approach) leaves the row visually off-center because TV M3
        // distributes excess height equally above and below an
        // intrinsically smaller content row, while font cap-vs-descent
        // bias makes the visible glyph sit a hair below the geometric
        // center. Driving everything from the label's own typography
        // collapses that gap: button height = max(icon, lineHeight) +
        // 2 × verticalPadding, with no slack to redistribute.
        val density = LocalDensity.current
        val labelFontSizeDp   = with(density) { labelStyle.fontSize.toDp() }
        val labelLineHeightDp = with(density) { labelStyle.lineHeight.toDp() }
        val buttonIcon        = labelFontSizeDp   * ButtonIconToFontRatio
        val iconLabelGap      = labelFontSizeDp   * IconLabelGapToFontRatio
        val buttonContentPadding = PaddingValues(
            horizontal = labelLineHeightDp * ButtonHorizontalPadToLineHeightRatio,
            vertical   = labelLineHeightDp * ButtonVerticalPadToLineHeightRatio,
        )

        // ── Centered hero stack ─────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = screenPaddingH, vertical = screenPaddingV),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_quick_share),
                contentDescription = null,
                modifier = Modifier.size(heroIcon),
                colorFilter = ColorFilter.tint(scheme.primary),
            )

            Spacer(Modifier.height(heroToTitleGap))

            Text(
                text = stringResource(R.string.app_name),
                style = titleStyle,
                color = scheme.primary,
            )

            Spacer(Modifier.height(heroToActionsGap))

            // Both buttons share the same width for visual balance.
            Column(
                modifier = Modifier.width(actionWidth),
                verticalArrangement = Arrangement.spacedBy(buttonGap),
            ) {
                ActionButton(
                    iconRes = R.drawable.ic_receive,
                    label = stringResource(R.string.action_receive),
                    labelStyle = labelStyle,
                    iconSize = buttonIcon,
                    iconLabelGap = iconLabelGap,
                    contentPadding = buttonContentPadding,
                    onClick = onReceive,
                    modifier = Modifier.focusRequester(receiveFocus),
                )
                ActionButton(
                    iconRes = R.drawable.ic_send,
                    label = stringResource(R.string.action_send),
                    labelStyle = labelStyle,
                    iconSize = buttonIcon,
                    iconLabelGap = iconLabelGap,
                    contentPadding = buttonContentPadding,
                    onClick = onSend,
                )
            }
        }

        // ── Settings affordance (top-right corner overlay) ──────────────
        SettingsButton(
            onClick = onSettings,
            size = settingsSize,
            iconSize = settingsIcon,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = screenPaddingV, end = screenPaddingH),
        )

        // ── Wi-Fi hint footer (low-emphasis, anchored to bottom) ────────
        // Drawn as a sibling overlay so it never displaces the centered
        // hero stack — both layouts share the same Box but live on
        // independent alignment lanes.
        WifiHint(
            iconSize = hintIcon,
            spacing = hintGap,
            style = hintStyle,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = screenPaddingV, start = screenPaddingH, end = screenPaddingH),
        )
    }
}

/**
 * Primary action with a "dim-when-unfocused" identity that mirrors
 * [SettingsButton]: the container reads as a quiet `surfaceVariant` at
 * rest and lights up to `primary` only when focused. Combined with the
 * pulsing focus glow, this gives the home screen a single unambiguous
 * "what's selected" affordance instead of two competing primary blobs.
 *
 * Behavior contract:
 * - **No scale animation.** TV M3's default focused-scale is disabled
 *   here (locked to 1.0) — the color swap + glow halo carry all the
 *   focus signal.
 * - **Glow only when focused**, and the focused glow *pulses* between
 *   [GlowMinElevation] and [GlowMaxElevation] on a [GlowPulsePeriodMs]
 *   breath cycle (see [rememberPulsingFocusGlow]).
 *
 * Sizing contract: the button has **no height constraint** — it hugs
 * the content row (max of `iconSize` and the label's intrinsic height)
 * plus the symmetric vertical padding inside [contentPadding]. The
 * caller is expected to derive [contentPadding] from the same
 * typography role as `labelStyle` so top/bottom whitespace stays
 * geometrically equal regardless of which type ramp is in play.
 *
 * Layout: icon + label live in a [Row] arranged with
 * [Arrangement.spacedBy] anchored to [Alignment.CenterHorizontally] and
 * [Alignment.CenterVertically], so the pair is centered as a single unit
 * along both axes inside the button — regardless of label length.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionButton(
    iconRes: Int,
    label: String,
    labelStyle: TextStyle,
    iconSize: Dp,
    iconLabelGap: Dp,
    contentPadding: PaddingValues,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val focusedGlow = rememberPulsingFocusGlow(color = scheme.primary)

    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        colors = ButtonDefaults.colors(
            containerColor = scheme.surfaceVariant,
            contentColor = scheme.onSurfaceVariant,
            focusedContainerColor = scheme.primary,
            focusedContentColor = scheme.onPrimary,
        ),
        // Disable TV M3's default focused/pressed scale — the color swap
        // and pulsing glow carry the focus signal on their own.
        scale = ButtonDefaults.scale(
            scale = NoScale,
            focusedScale = NoScale,
            pressedScale = NoScale,
            disabledScale = NoScale,
            focusedDisabledScale = NoScale,
        ),
        // Glow exists *only* on focus — resting and pressed both fall back
        // to Glow.None.
        glow = ButtonDefaults.glow(
            glow = Glow.None,
            focusedGlow = focusedGlow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                space = iconLabelGap,
                alignment = Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
            Text(text = label, style = labelStyle.glyphCentered())
        }
    }
}

/**
 * Builds a [Glow] whose elevation breathes between [GlowMinElevation]
 * and [GlowMaxElevation] on a [GlowPulsePeriodMs] cycle. The returned
 * Glow is intended for the `focusedGlow` slot — TV M3 only renders a
 * `focusedGlow` while the surface actually holds focus, so the
 * animation is visually inert (and visually free) on unfocused
 * affordances.
 *
 * A subtle, easing-driven pulse rather than a hard on/off blink: the
 * `FastOutSlowInEasing` curve gives it a "breathing" feel that draws
 * the eye to the focused element without becoming nagging.
 */
@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun rememberPulsingFocusGlow(color: Color): Glow {
    val transition = rememberInfiniteTransition(label = "focus-glow-pulse")
    val pulseFraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = GlowPulsePeriodMs,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "focus-glow-pulse-fraction",
    )
    val elevation: Dp = lerp(GlowMinElevation, GlowMaxElevation, pulseFraction)
    return Glow(elevationColor = color, elevation = elevation)
}

/**
 * Circular settings entry. Both the surface and the icon scale together
 * via caller-supplied sizes, so the affordance grows with the screen
 * without breaking its visual proportions.
 *
 * Shares most of the [ActionButton] focus language: no scale animation,
 * color swap to `primary` on focus, and a focused glow halo. **Differs
 * intentionally on the breath**: the glow here is *static* at
 * [SettingsFocusedGlowElevation] so this peripheral chrome doesn't
 * compete with the pulsing primary actions for attention.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsButton(
    onClick: () -> Unit,
    size: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = scheme.surfaceVariant,
            contentColor = scheme.onSurfaceVariant,
            focusedContainerColor = scheme.primary,
            focusedContentColor = scheme.onPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(
            scale = NoScale,
            focusedScale = NoScale,
            pressedScale = NoScale,
            disabledScale = NoScale,
            focusedDisabledScale = NoScale,
        ),
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow.None,
            focusedGlow = Glow(
                elevationColor = scheme.primary,
                elevation = SettingsFocusedGlowElevation,
            ),
        ),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = stringResource(R.string.action_settings),
            modifier = Modifier
                .align(Alignment.Center)
                .size(iconSize),
        )
    }
}

/**
 * Footer hint reminding the user that Quick Share requires both devices to
 * share a Wi-Fi network. Rendered with low-emphasis colors and body
 * typography — it's onboarding guidance, not a primary affordance, so it
 * shouldn't compete with the action buttons for attention.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WifiHint(
    iconSize: Dp,
    spacing: Dp,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_tv),
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
        Text(
            text = stringResource(R.string.home_wifi_hint),
            style = style,
            color = scheme.onSurfaceVariant,
        )
    }
}

// ── Pure ratios / breakpoints / focus tuning ─────────────────────────────
// These are dimensionless design ratios, one width threshold, and the
// focus-glow elevation pair — they describe *how* the design behaves, not
// the layout's component sizes. The actual responsive dp values all come
// from the computation block at the top of the screen.

/** Settings icon diameter expressed as a fraction of its circular button. */
private const val SettingsIconRatio = 0.45f

// ── Typography → action-button geometry ──────────────────────────────────
// Sizing the button in proportion to its label keeps the visible content
// geometrically centered regardless of which type ramp the label uses
// (titleMedium on standard TVs, titleLarge on very wide configs).

/** Icon diameter as a multiple of the label font size — slightly larger than the cap-height for visual parity. */
private const val ButtonIconToFontRatio = 1.4f

/** Spacing between icon and label as a multiple of the label font size. */
private const val IconLabelGapToFontRatio = 0.6f

/** Vertical button padding as a multiple of the label line height. Equal top + bottom by construction. */
private const val ButtonVerticalPadToLineHeightRatio = 0.55f

/** Horizontal button padding as a multiple of the label line height — generous enough to keep the label off the rounded corners. */
private const val ButtonHorizontalPadToLineHeightRatio = 1.0f

/**
 * Width breakpoint at which typography steps up one M3 role. ~1200 dp
 * covers very wide TV configurations (e.g. 4 K reporting > the usual ~960
 * dp baseline due to a custom density).
 */
private val LargeScreenWidthThreshold = 1200.dp

/**
 * Scale multiplier used to disable TV M3's default focus/press scaling.
 * Passed for every state slot so the surface stays geometrically static
 * — focus is conveyed by color + glow only, not by motion.
 */
private const val NoScale = 1f

// ── Focus glow pulse ─────────────────────────────────────────────────────
// The action-button focused halo breathes between two elevations on a fixed
// period. Tuned subtle: low at the floor so it doesn't vanish mid-cycle,
// low at the ceiling so it reads as an accent rather than a drop shadow.
// The FastOutSlowInEasing curve makes it feel like a breath, not a blink.

/** Lower bound of the pulsing glow elevation (kept above 0 so the halo never disappears mid-cycle). */
private val GlowMinElevation = 2.dp

/** Upper bound of the pulsing glow elevation. */
private val GlowMaxElevation = 6.dp

/** Full breath cycle (rest → expanded → rest) in milliseconds. */
private const val GlowPulsePeriodMs = 1200

/**
 * Static focused glow for the settings button — sits at the median of
 * the action buttons' pulse range so the halo intensity reads
 * consistent across all interactive elements without animating. The
 * settings affordance is peripheral chrome, so a steady glow keeps it
 * from competing with the pulsing primary actions for the eye.
 */
private val SettingsFocusedGlowElevation = 4.dp
