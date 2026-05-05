package com.quickshare.tv.ui.theme

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle

/**
 * Strips Compose's default font padding so glyphs sit on a clean
 * optical baseline — used on every screen to align icons with adjacent
 * text without manual offset tweaks.
 */
fun TextStyle.glyphCentered(): TextStyle = copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)
