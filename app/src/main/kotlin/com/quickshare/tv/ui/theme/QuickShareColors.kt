package com.quickshare.tv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Raw color palettes — the only file in the codebase allowed to introduce
 * hex literals. Theme role mapping (background, surface, primary, error,
 * success, ...) lives in [Theme.kt] and references these constants by name.
 *
 * Naming follows the standard tonal-ramp convention: lower numbers are
 * lighter, higher numbers are darker. Each ramp is 11 steps (50–950) so
 * roles map cleanly to M3 Expressive's tonal targets (T5, T10, T20, T30,
 * T40, T50, T60, T80, T90, T95, T98).
 *
 * Sub-palettes:
 *  - **C** — Brand slate (cool gray). Used for every neutral / brand role.
 *  - **R** — Semantic red. Powers M3's `error` family (`error`, `onError`,
 *    `errorContainer`, `onErrorContainer`).
 *  - **G** — Semantic green. Powers a custom `success` family exposed via
 *    [QuickShareExtraColors] (M3 has no native `success` role).
 */
object QuickShareColors {

    // ---- Brand slate -------------------------------------------------------
    val C50 = Color(0xFFF9FAFB)
    val C100 = Color(0xFFF3F4F6)
    val C200 = Color(0xFFE5E7EB)
    val C300 = Color(0xFFD1D5DC)
    val C400 = Color(0xFF99A1AF)
    val C500 = Color(0xFF6A7282)
    val C600 = Color(0xFF4A5565)
    val C700 = Color(0xFF364153)
    val C800 = Color(0xFF1E2939)
    val C900 = Color(0xFF101828)
    val C950 = Color(0xFF030712)

    // ---- Semantic red (error / destructive) -------------------------------
    val R50 = Color(0xFFFEF2F2)
    val R100 = Color(0xFFFEE2E2)
    val R200 = Color(0xFFFECACA)
    val R300 = Color(0xFFFCA5A5)
    val R400 = Color(0xFFF87171)
    val R500 = Color(0xFFEF4444)
    val R600 = Color(0xFFDC2626)
    val R700 = Color(0xFFB91C1C)
    val R800 = Color(0xFF991B1B)
    val R900 = Color(0xFF7F1D1D)
    val R950 = Color(0xFF450A0A)

    // ---- Semantic green (success / confirmation) --------------------------
    val G50 = Color(0xFFF0FDF4)
    val G100 = Color(0xFFDCFCE7)
    val G200 = Color(0xFFBBF7D0)
    val G300 = Color(0xFF86EFAC)
    val G400 = Color(0xFF4ADE80)
    val G500 = Color(0xFF22C55E)
    val G600 = Color(0xFF16A34A)
    val G700 = Color(0xFF15803D)
    val G800 = Color(0xFF166534)
    val G900 = Color(0xFF14532D)
    val G950 = Color(0xFF052E16)
}
