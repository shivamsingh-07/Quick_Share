package com.quickshare.tv.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import com.quickshare.tv.R
import java.io.ByteArrayOutputStream
import qrcode.QRCode
import qrcode.color.Colors
import qrcode.raw.ErrorCorrectionLevel

/**
 * Renders the send-flow QR as a large PNG asset that Compose can scale down.
 *
 * qrcode-kotlin clears modules under [withLogo], but `fitIntoArea()` may leave
 * extra pixels on the right/bottom when the module count does not divide the
 * target size evenly. We render the exact module-sized QR first, then center it
 * on a 1024px white canvas.
 */
object QrPngRenderer {
    fun render(context: Context, url: String): ByteArray {
        val logoBytes = renderAppIconLogoPng(context)
        val qrCode = QRCode.ofCircles()
            .withBackgroundColor(Colors.WHITE)
            .withColor(Colors.BLACK)
            .withLogo(
                logo = logoBytes,
                width = LogoSizePx,
                height = LogoSizePx,
            )
            .withErrorCorrectionLevel(ErrorCorrectionLevel.HIGH)
            .build(url)
            .fitIntoArea(CanvasSizePx, CanvasSizePx)

        val qrContentSizePx = qrCode.rawData.size * qrCode.squareSize
        return centerPngOnWhiteCanvas(
            pngBytes = qrCode
                .resize(qrContentSizePx)
                .renderToBytes(),
            canvasSizePx = CanvasSizePx,
        )
    }

    private fun renderAppIconLogoPng(context: Context): ByteArray {
        val foreground = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)) {
            "Quick Share app icon foreground drawable missing"
        }.mutate()
        val bitmap = Bitmap.createBitmap(LogoSizePx, LogoSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AppIconBlue
            style = Paint.Style.FILL
        }
        val center = LogoSizePx / 2f
        val logoPaddingPx = LogoSizePx * LogoOuterPaddingRatio
        canvas.drawCircle(center, center, center - logoPaddingPx, paint)

        // Adaptive-icon foregrounds intentionally live inside a safe area.
        // Expand its bounds so the glyph reads correctly at QR-logo size.
        val foregroundInsetPx = -(LogoSizePx * LogoForegroundOverscaleRatio).toInt()
        foreground.setBounds(
            foregroundInsetPx,
            foregroundInsetPx,
            LogoSizePx - foregroundInsetPx,
            LogoSizePx - foregroundInsetPx,
        )
        foreground.draw(canvas)

        return bitmap.toPngBytes()
    }

    private fun centerPngOnWhiteCanvas(pngBytes: ByteArray, canvasSizePx: Int): ByteArray {
        val qrBitmap = requireNotNull(BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)) {
            "Generated QR PNG could not be decoded"
        }
        val output = Bitmap.createBitmap(canvasSizePx, canvasSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Colors.WHITE)
        val left = (canvasSizePx - qrBitmap.width) / 2f
        val top = (canvasSizePx - qrBitmap.height) / 2f
        canvas.drawBitmap(qrBitmap, left, top, null)
        qrBitmap.recycle()

        return output.toPngBytes()
    }

    private fun Bitmap.toPngBytes(): ByteArray =
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.PNG, PngQualityLossless, out)
            recycle()
            out.toByteArray()
        }

    private const val CanvasSizePx = 1024
    private const val LogoSizePx = 150
    private const val PngQualityLossless = 100
    private const val AppIconBlue = 0xFF226BFD.toInt()
    private const val LogoOuterPaddingRatio = 0.08f
    private const val LogoForegroundOverscaleRatio = 0.16f
}
