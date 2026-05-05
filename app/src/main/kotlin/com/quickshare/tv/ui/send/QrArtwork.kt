package com.quickshare.tv.ui.send

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.quickshare.tv.ui.theme.Spacing

/**
 * Responsive frame for the pre-rendered 1024px QR PNG. The repository
 * generates the QR with qrcode-kotlin's logo-clearing path, and Compose
 * only scales the finished image to the TV layout size.
 */
@Composable
internal fun QrArtwork(pngBytes: ByteArray?, size: Dp) {
    val shape = RoundedCornerShape(QrCornerRadius)

    val image = remember(pngBytes) {
        pngBytes?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .background(Color.White, shape)
            .clip(shape)
            .padding(QrFramePadding),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(QrImageCornerShape),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

// QR-specific design tokens ──────────────────────────────────────────
private val QrCornerRadius   = Spacing.md
private val QrFramePadding   = Spacing.sm
private val QrImageCornerShape = RoundedCornerShape(Spacing.xs)
