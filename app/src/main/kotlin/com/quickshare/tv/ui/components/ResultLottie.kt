package com.quickshare.tv.ui.components

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.quickshare.tv.R

/**
 * One-shot animated terminal-state badge. Kept shared so Send and Receive
 * present success/failure with the same timing and visual weight.
 */
@Composable
fun ResultLottie(
    success: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val rawRes = if (success) R.raw.lottie_success else R.raw.lottie_failure
    LottieRawAnimation(rawRes = rawRes, modifier = modifier, size = size)
}

@Composable
private fun LottieRawAnimation(
    @RawRes rawRes: Int,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(rawRes))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = 1,
        restartOnPlay = true,
    )
    LottieAnimation(
        composition = composition,
        progress = { progress },
        modifier = modifier.then(Modifier.size(size)),
    )
}
