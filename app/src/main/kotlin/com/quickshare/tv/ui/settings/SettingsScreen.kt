package com.quickshare.tv.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.quickshare.tv.R
import com.quickshare.tv.ui.theme.Spacing
import com.quickshare.tv.ui.theme.glyphCentered

/**
 * Settings — page title above a single grouped card. Each row inside the
 * card is a horizontal "label + description on the left, control on the
 * right" stripe, separated by hairline dividers. Three rows: Theme
 * (segmented buttons), Auto-accept (switch), Device name (action button
 * deep-linking to system settings).
 *
 * Inherits the home-screen design language verbatim:
 *  - Responsive sizing via [BoxWithConstraints] with clamp envelopes.
 *  - Form-control geometry derived from typography (button height = line
 *    height + symmetric padding, etc.).
 *  - Secondary controls stay on `surfaceVariant`; focus is shown with
 *    a flat border instead of swapping the whole surface fill.
 *  - **No scale animation** anywhere; focus stays calm and spatially stable.
 *  - Glyph-centered text inside fixed-height controls (segmented
 *    buttons) so cap/descent bias doesn't pull the label off-center.
 *
 * Card depth: rows live on `scheme.surface` (one tonal step above
 * `scheme.background`), with `scheme.borderVariant` for the outer
 * border *and* the inter-row dividers. The interactive controls inside
 * sit on `scheme.surfaceVariant` so they remain visible against the
 * card.
 *
 * Persistence: every control writes through [SettingsViewModel] on
 * change. The device-name affordance opens the system settings rather
 * than editing in-app.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val viewModel: SettingsViewModel = viewModel()
    val ui by viewModel.ui.collectAsState()

    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        val w: Dp = maxWidth
        val h: Dp = maxHeight

        // ── Responsive layout sizes ─────────────────────────────────────
        // Same pattern as HomeScreen: `(fraction of screen) coerced into
        // [min, max]`. The card layout puts label+description on the left
        // and control on the right, so we give it a wider envelope than
        // the previous vertical-stack design.
        val screenPaddingH  = Spacing.xl
        val screenPaddingV  = Spacing.lg
        val contentMaxWidth = (w * 0.70f ).coerceIn(520.dp, 880.dp)
        val titleToBodyGap  = (h * 0.06f ).coerceIn(Spacing.lg,  Spacing.xxl)

        // ── Responsive typography (M3 role step-up on very wide TVs). ──
        val titleStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.displayMedium
            else typography.displaySmall
        val rowLabelStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.titleLarge
            else typography.titleMedium
        val controlLabelStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.titleMedium
            else typography.titleSmall
        val descriptionStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.bodyMedium
            else typography.bodySmall

        // ── Typography → control geometry (mirrors HomeScreen pattern) ─
        // Driving control sizes from line-height keeps the visible
        // content geometrically centered without slack to redistribute.
        val density = LocalDensity.current
        val controlFontSizeDp = with(density) { controlLabelStyle.fontSize.toDp() }
        val controlLineHeight = with(density) { controlLabelStyle.lineHeight.toDp() }
        val segmentedPadding = PaddingValues(
            horizontal = controlLineHeight * SegmentedHorizontalPadToLineHeightRatio,
            vertical   = controlLineHeight * SegmentedVerticalPadToLineHeightRatio,
        )
        // Action-button geometry mirrors the home-screen action buttons. The
        // device-name edit button reuses the theme segmented-button padding so
        // both controls have the same height on this page.
        val actionButtonIcon = controlFontSizeDp * ButtonIconToFontRatio
        // Explicit Dp pill radius (rather than percent-based) so the
        // segmented-button glow renders as a true rounded outline on
        // *both* halves. TV M3's glow renderer derives its drop-shadow
        // outline from the surface shape; with `RoundedCornerShape(percent = 50)`
        // the right segment's `.copy(topStart = 0, bottomStart = 0)`
        // landed on a non-rounded outline path and the halo dropped to
        // a square.
        val pillRadius = controlLineHeight * 1.5f
        val controlShape = RoundedCornerShape(pillRadius)

        // ── Card geometry (also typography-driven) ─────────────────────
        val cardHorizontalPadding = Spacing.xl
        val cardVerticalPadding   = Spacing.sm
        val rowVerticalPadding    = Spacing.lg
        val rowSideGap            = Spacing.lg
        val labelToDescGap        = Spacing.xs

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = screenPaddingH, vertical = screenPaddingV),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Title ───────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.settings_title),
                style = titleStyle,
                color = scheme.primary,
            )
            Spacer(Modifier.height(titleToBodyGap))

            // ── Single grouped card (constrained max width on wide TVs) ─
            SettingsCard(
                modifier = Modifier.widthIn(max = contentMaxWidth).fillMaxWidth(),
                horizontalPadding = cardHorizontalPadding,
                verticalPadding = cardVerticalPadding,
            ) {
                ThemeRow(
                    isDark = ui.useDarkTheme,
                    onSelect = viewModel::setUseDarkTheme,
                    rowLabelStyle = rowLabelStyle,
                    descriptionStyle = descriptionStyle,
                    optionLabelStyle = controlLabelStyle,
                    rowVerticalPadding = rowVerticalPadding,
                    labelToDescGap = labelToDescGap,
                    rowSideGap = rowSideGap,
                    contentPadding = segmentedPadding,
                    shape = controlShape,
                )
                SettingsDivider()
                AutoAcceptRow(
                    enabled = ui.autoAccept,
                    onToggle = viewModel::setAutoAccept,
                    rowLabelStyle = rowLabelStyle,
                    descriptionStyle = descriptionStyle,
                    rowVerticalPadding = rowVerticalPadding,
                    labelToDescGap = labelToDescGap,
                    rowSideGap = rowSideGap,
                )
                SettingsDivider()
                DeviceNameRow(
                    persistedValue = ui.deviceName,
                    rowLabelStyle = rowLabelStyle,
                    descriptionStyle = descriptionStyle,
                    rowVerticalPadding = rowVerticalPadding,
                    labelToDescGap = labelToDescGap,
                    rowSideGap = rowSideGap,
                    buttonLabelStyle = controlLabelStyle,
                    buttonIconSize = actionButtonIcon,
                    buttonContentPadding = segmentedPadding,
                )
            }
        }
    }
}

// =====================================================================
//  Card + row primitives
// =====================================================================

/**
 * Single grouped container that hosts every setting row. One step of
 * tonal depth above the page background (`scheme.surface`) plus a
 * hairline `borderVariant` border for definition — on dark builds the
 * surface tone is close to the background and the border is what
 * actually outlines the card.
 */
@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(CardCornerRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(scheme.surface)
            .border(width = 1.dp, color = scheme.borderVariant, shape = shape)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        content = content,
    )
}

/**
 * One "label + description on the left, control on the right" stripe.
 * The text column takes all remaining width via `weight(1f)` so the
 * control gets exactly its intrinsic size and long descriptions wrap
 * cleanly without squeezing the right-side affordance.
 *
 * Vertical alignment is `CenterVertically` so the control sits centered
 * against tall (multi-line) descriptions.
 */
@Composable
private fun SettingRow(
    label: String,
    description: String,
    rowLabelStyle: TextStyle,
    descriptionStyle: TextStyle,
    rowVerticalPadding: Dp,
    labelToDescGap: Dp,
    rowSideGap: Dp,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = rowLabelStyle,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(labelToDescGap))
            Text(
                text = description,
                style = descriptionStyle,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(rowSideGap))
        control()
    }
}

/** Hairline divider between rows inside the card. */
@Composable
private fun SettingsDivider() {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(scheme.borderVariant),
    )
}

// =====================================================================
//  Rows
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ThemeRow(
    isDark: Boolean,
    onSelect: (Boolean) -> Unit,
    rowLabelStyle: TextStyle,
    descriptionStyle: TextStyle,
    optionLabelStyle: TextStyle,
    rowVerticalPadding: Dp,
    labelToDescGap: Dp,
    rowSideGap: Dp,
    contentPadding: PaddingValues,
    shape: RoundedCornerShape,
) {
    SettingRow(
        label = stringResource(R.string.settings_theme_label),
        description = stringResource(R.string.settings_theme_desc),
        rowLabelStyle = rowLabelStyle,
        descriptionStyle = descriptionStyle,
        rowVerticalPadding = rowVerticalPadding,
        labelToDescGap = labelToDescGap,
        rowSideGap = rowSideGap,
    ) {
        Row(
            modifier = Modifier.wrapContentWidth(),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // True "segmented" look: outer corners of the group are
            // rounded, inner edges are flat. Each half is a fully
            // selectable surface so the group reads as two adjacent
            // toggles rather than two independent chips.
            val flatCorner = CornerSize(0.dp)
            SegmentedOption(
                selected = !isDark,
                label = stringResource(R.string.settings_theme_light),
                onClick = { onSelect(false) },
                labelStyle = optionLabelStyle,
                contentPadding = contentPadding,
                shape = shape.copy(topEnd = flatCorner, bottomEnd = flatCorner),
            )
            SegmentedOption(
                selected = isDark,
                label = stringResource(R.string.settings_theme_dark),
                onClick = { onSelect(true) },
                labelStyle = optionLabelStyle,
                contentPadding = contentPadding,
                shape = shape.copy(topStart = flatCorner, bottomStart = flatCorner),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AutoAcceptRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    rowLabelStyle: TextStyle,
    descriptionStyle: TextStyle,
    rowVerticalPadding: Dp,
    labelToDescGap: Dp,
    rowSideGap: Dp,
) {
    val scheme = MaterialTheme.colorScheme

    // The Switch isn't a TV M3 Surface so it can't take a Border config
    // directly. We draw a primary focus border on a wrapping Box sized
    // exactly to the Switch (no outer padding) so the border hugs the
    // pill outline with no visible gap. With `borderWidth = 0.dp` when
    // unfocused, `Modifier.border` draws nothing and the wrapper is
    // visually inert — no reserved space, no faux-border at rest.
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) FocusBorderWidth else 0.dp,
        label = "switch-focus-border",
    )
    val pillShape = RoundedCornerShape(percent = 50)

    SettingRow(
        label = stringResource(R.string.settings_auto_accept),
        description = stringResource(R.string.settings_auto_accept_detail),
        rowLabelStyle = rowLabelStyle,
        descriptionStyle = descriptionStyle,
        rowVerticalPadding = rowVerticalPadding,
        labelToDescGap = labelToDescGap,
        rowSideGap = rowSideGap,
    ) {
        Box(
            modifier = Modifier.border(borderWidth, scheme.primary, pillShape),
        ) {
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                interactionSource = interactionSource,
            )
        }
    }
}

/**
 * Device-name row. Quick Share doesn't own the device name; it mirrors
 * what the Android TV system reports. The current value is surfaced in
 * the description (so users can verify what other devices see) and the
 * right-side button deep-links into the system "Device info" screen
 * where the name actually lives (Settings → Device Preferences → About
 * → Device name on stock TV builds).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DeviceNameRow(
    persistedValue: String,
    rowLabelStyle: TextStyle,
    descriptionStyle: TextStyle,
    rowVerticalPadding: Dp,
    labelToDescGap: Dp,
    rowSideGap: Dp,
    buttonLabelStyle: TextStyle,
    buttonIconSize: Dp,
    buttonContentPadding: PaddingValues,
) {
    SettingRow(
        label = stringResource(R.string.device_name_label),
        description = stringResource(R.string.settings_device_name_helper),
        rowLabelStyle = rowLabelStyle,
        descriptionStyle = descriptionStyle,
        rowVerticalPadding = rowVerticalPadding,
        labelToDescGap = labelToDescGap,
        rowSideGap = rowSideGap,
    ) {
        OpenSystemSettingsButton(
            deviceName = persistedValue,
            labelStyle = buttonLabelStyle,
            iconSize = buttonIconSize,
            contentPadding = buttonContentPadding,
        )
    }
}

/**
 * Compact secondary action that fires an intent into the system settings.
 * Styled like the theme selector: stable muted fill, no scale, and focus
 * indicated by a border instead of a full background swap.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OpenSystemSettingsButton(
    deviceName: String,
    labelStyle: TextStyle,
    iconSize: Dp,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val accessibilityLabel = stringResource(R.string.settings_edit_device_name, deviceName)
    val shape = RoundedCornerShape(percent = 50)

    Surface(
        selected = false,
        onClick = { openDeviceInfoSettings(context) },
        modifier = modifier.semantics { contentDescription = accessibilityLabel },
        shape = SelectableSurfaceDefaults.shape(shape = shape),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = scheme.surfaceVariant,
            contentColor = scheme.onSurfaceVariant,
            focusedContainerColor = scheme.surfaceVariant,
            focusedContentColor = scheme.onSurfaceVariant,
            pressedContainerColor = scheme.surfaceVariant,
            pressedContentColor = scheme.onSurfaceVariant,
            selectedContainerColor = scheme.surfaceVariant,
            selectedContentColor = scheme.onSurfaceVariant,
            focusedSelectedContainerColor = scheme.surfaceVariant,
            focusedSelectedContentColor = scheme.onSurfaceVariant,
            pressedSelectedContainerColor = scheme.surfaceVariant,
            pressedSelectedContentColor = scheme.onSurfaceVariant,
        ),
        scale = SelectableSurfaceDefaults.scale(
            scale = NoScale,
            focusedScale = NoScale,
            pressedScale = NoScale,
            selectedScale = NoScale,
            disabledScale = NoScale,
            focusedSelectedScale = NoScale,
            focusedDisabledScale = NoScale,
            pressedSelectedScale = NoScale,
            selectedDisabledScale = NoScale,
            focusedSelectedDisabledScale = NoScale,
        ),
        border = SelectableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(FocusBorderWidth, scheme.primary),
                shape = shape,
            ),
            focusedSelectedBorder = Border(
                border = BorderStroke(FocusBorderWidth, scheme.primary),
                shape = shape,
            ),
        ),
    ) {
        Box(
            modifier = Modifier.padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = deviceName,
                    style = labelStyle.glyphCentered(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

/**
 * Try to open the system "Device info" screen (where Android TV exposes
 * the device-name editor), falling back to the top-level system settings
 * if the OEM build doesn't advertise the more specific intent. We swallow
 * [ActivityNotFoundException] silently because there's nothing useful to
 * surface to the user on a TV with no Settings activity at all.
 */
private fun openDeviceInfoSettings(context: Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // try next candidate
        }
    }
}

// =====================================================================
//  Custom controls
// =====================================================================

/**
 * One half of the segmented theme picker. A TV M3 selectable [Surface]
 * styled to share the home-screen design language: no scale, primary-
 * tinted when selected *or* focused, and a **static** (non-pulsing)
 * focused glow halo. The breath effect is reserved exclusively for the
 * home-screen action buttons; every other interactive surface in the
 * app uses a steady glow so peripheral chrome doesn't compete for the
 * eye with primary actions.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SegmentedOption(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    labelStyle: TextStyle,
    contentPadding: PaddingValues,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = SelectableSurfaceDefaults.shape(shape = shape),
        // Focus does NOT swap the container/content — only selection
        // does. Focus is signalled exclusively by the border below, so
        // the user can tell at a glance which segment is selected
        // (primary fill) vs. which one the cursor is on (border).
        colors = SelectableSurfaceDefaults.colors(
            containerColor                   = scheme.surfaceVariant,
            contentColor                     = scheme.onSurfaceVariant,
            focusedContainerColor            = scheme.surfaceVariant,
            focusedContentColor              = scheme.onSurfaceVariant,
            pressedContainerColor            = scheme.surfaceVariant,
            pressedContentColor              = scheme.onSurfaceVariant,
            selectedContainerColor           = scheme.primary,
            selectedContentColor             = scheme.onPrimary,
            focusedSelectedContainerColor    = scheme.primary,
            focusedSelectedContentColor      = scheme.onPrimary,
            pressedSelectedContainerColor    = scheme.primary,
            pressedSelectedContentColor      = scheme.onPrimary,
        ),
        scale = SelectableSurfaceDefaults.scale(
            scale                        = NoScale,
            focusedScale                 = NoScale,
            pressedScale                 = NoScale,
            selectedScale                = NoScale,
            disabledScale                = NoScale,
            focusedSelectedScale         = NoScale,
            focusedDisabledScale         = NoScale,
            pressedSelectedScale         = NoScale,
            selectedDisabledScale        = NoScale,
            focusedSelectedDisabledScale = NoScale,
        ),
        // Focus is signalled by a flat border (no glow halo). When the
        // segment is *also* selected its container is already `primary`,
        // so the focus border switches to `onPrimary` to stay visible
        // against that fill.
        border = SelectableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(FocusBorderWidth, scheme.primary),
                shape = shape,
            ),
            focusedSelectedBorder = Border(
                border = BorderStroke(FocusBorderWidth, scheme.onPrimary),
                shape = shape,
            ),
        ),
    ) {
        Box(
            modifier = Modifier.padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, style = labelStyle.glyphCentered())
        }
    }
}

// ── Design tokens ──────────────────────────────────────────────────────────

private const val NoScale = 1f
private val LargeScreenWidthThreshold = 1200.dp

/** Stroke width of the flat focus border used by settings controls. */
private val FocusBorderWidth = 2.dp

/**
 * Card outer radius. Larger than the segmented-button radius so the card
 * reads as the parent container rather than competing visually with the
 * pill-shaped controls inside it.
 */
private val CardCornerRadius = Spacing.md

// ── Settings-specific control geometry ──────────────────────────────────

/** Horizontal segmented-button padding as a multiple of the option line height. */
private const val SegmentedHorizontalPadToLineHeightRatio = 1.4f

/** Vertical segmented-button padding as a multiple of the option line height. */
private const val SegmentedVerticalPadToLineHeightRatio = 0.5f

// ── Action-button geometry (mirrored from HomeScreen) ───────────────────
//
// Kept locally rather than depending on HomeScreen because the two
// screens deliberately keep their own design tokens until a third caller
// justifies a shared `ui/components/` extraction (see top-of-file note).

/** Icon side as a multiple of the label cap-height. */
private const val ButtonIconToFontRatio = 1.4f
