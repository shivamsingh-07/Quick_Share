package com.quickshare.tv.ui.components

import android.media.MediaPlayer
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays a short raw-resource sound once whenever [trigger] changes to a
 * non-null value. The player is released on completion or if the composable
 * leaves before playback finishes.
 */
@Composable
fun PlayRawSoundEffect(
    @RawRes soundRes: Int,
    trigger: Any?,
) {
    val context = LocalContext.current
    DisposableEffect(soundRes, trigger) {
        if (trigger == null) {
            return@DisposableEffect onDispose { }
        }

        val player = MediaPlayer.create(context, soundRes)
        if (player == null) {
            return@DisposableEffect onDispose { }
        }

        val released = AtomicBoolean(false)
        fun releaseOnce() {
            if (released.compareAndSet(false, true)) {
                player.release()
            }
        }

        player.setOnCompletionListener { releaseOnce() }
        runCatching { player.start() }
            .onFailure { releaseOnce() }

        onDispose { releaseOnce() }
    }
}
