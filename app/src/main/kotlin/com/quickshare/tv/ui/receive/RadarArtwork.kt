package com.quickshare.tv.ui.receive

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.quickshare.tv.R

/**
 * Animated radar-style artwork shown on the LISTENING and CONNECTING phases.
 *
 * Three concentric ripple rings expand outward from a centred Quick Share
 * icon, staggered by [RippleRingCount] / [RippleCyclePeriodMs] so a new
 * ring starts every [RippleGapBetweenRingsMs] ms. Each ring fades from
 * [ReceiveRippleMaxAlpha] to transparent as it expands. The icon itself
 * breathes at the same period as the rings, scaling between [IconScaleMin]
 * and [IconScaleMax].
 */
@Composable
internal fun ReceiveRadarArtwork(size: Dp) {
    val scheme = MaterialTheme.colorScheme
    val rippleColor = scheme.primary.copy(alpha = ReceiveRippleColorAlpha)
    val transition = rememberInfiniteTransition(label = "receive-radar-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = RippleCyclePeriodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "receive-radar-fraction",
    )
    val iconSize = size * ReceiveIconSizeRatio
    val iconPulse = (pulse * RippleRingCount) % 1f
    val iconScale = if (iconPulse < 0.5f) {
        IconScaleMax - (IconScaleMax - IconScaleMin) * (iconPulse / 0.5f)
    } else {
        IconScaleMin + (IconScaleMax - IconScaleMin) * ((iconPulse - 0.5f) / 0.5f)
    }

    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxRadius = this.size.minDimension * 0.46f
            val iconRadius = this.size.minDimension * ReceiveIconSizeRatio / 2f
            val activeFraction = RippleExpandDurationMs.toFloat() / RippleCyclePeriodMs.toFloat()
            repeat(RippleRingCount) { index ->
                val phase = (pulse - index.toFloat() / RippleRingCount.toFloat() + 1f) % 1f
                if (phase <= activeFraction) {
                    val progress = phase / activeFraction
                    val radius = iconRadius + (maxRadius - iconRadius) * progress
                    drawCircle(
                        color = rippleColor.copy(alpha = (1f - progress) * ReceiveRippleMaxAlpha),
                        radius = radius,
                        center = center,
                        style = Stroke(width = this.size.minDimension * 0.018f, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_quick_share),
            contentDescription = null,
            tint = rippleColor,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
        )
    }
}

// Radar animation constants ──────────────────────────────────────────
private const val ReceiveIconSizeRatio = 0.34f
private const val RippleExpandDurationMs = 5_000
private const val RippleGapBetweenRingsMs = 2_000
private const val RippleRingCount = 3
private const val RippleCyclePeriodMs = RippleExpandDurationMs + RippleGapBetweenRingsMs
private const val ReceiveRippleColorAlpha = 0.78f
private const val ReceiveRippleMaxAlpha = 0.34f
private const val IconScaleMin = 0.92f
private const val IconScaleMax = 1.08f
