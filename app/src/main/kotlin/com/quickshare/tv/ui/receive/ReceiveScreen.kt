package com.quickshare.tv.ui.receive

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.quickshare.tv.R
import com.quickshare.tv.domain.model.DeviceKind
import com.quickshare.tv.ui.iconRes
import com.quickshare.tv.ui.components.CircularProgressArc
import com.quickshare.tv.ui.components.PlayRawSoundEffect
import com.quickshare.tv.ui.components.ResultLottie
import com.quickshare.tv.ui.theme.Spacing
import com.quickshare.tv.ui.theme.glyphCentered
import kotlinx.coroutines.delay

/**
 * Receive - phase-driven companion to SendScreen.
 *
 * The page keeps the same design language as Home / Send / Settings:
 * responsive safe areas, a primary-coloured destination title, a single
 * grouped card, no scale animation, and focused controls that swap to primary
 * with a calm static glow.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ReceiveScreen(
    onExit: () -> Unit,
) {
    val viewModel: ReceiveViewModel = viewModel()
    val ui by viewModel.ui.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        viewModel.startReceiving()
        onDispose { viewModel.stopReceiving() }
    }

    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        val w: Dp = maxWidth
        val h: Dp = maxHeight

        val screenPaddingH = Spacing.xl
        val screenPaddingV = Spacing.lg
        val contentMaxWidth = (w * 0.62f).coerceIn(420.dp, 760.dp)
        val titleToBodyGap = (h * 0.06f).coerceIn(Spacing.lg, Spacing.xxl)

        val titleStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.displayMedium else typography.displaySmall
        val bodyStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.titleMedium else typography.titleSmall
        val descStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.bodyMedium else typography.bodySmall
        val controlLabelStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.titleMedium else typography.titleSmall

        val density = LocalDensity.current
        val descFontSizeDp = with(density) { descStyle.fontSize.toDp() }
        val controlLineHeightDp = with(density) { controlLabelStyle.lineHeight.toDp() }
        val buttonContentPadding = PaddingValues(
            horizontal = controlLineHeightDp * ButtonHorizontalPadToLineHeightRatio,
            vertical = controlLineHeightDp * ButtonVerticalPadToLineHeightRatio,
        )
        val pinPillContentPadding = PaddingValues(
            horizontal = descFontSizeDp * PinPillHorizontalPadToFontRatio,
            vertical = descFontSizeDp * PinPillVerticalPadToFontRatio,
        )

        val cardHorizontalPadding = Spacing.xl
        val cardVerticalPadding = Spacing.xl
        val sectionGap = Spacing.lg
        val tightGap = Spacing.sm
        val artworkSize = (minOf(w, h) * 0.22f).coerceIn(128.dp, 190.dp)
        val resultIconSize = (minOf(w, h) * 0.13f).coerceIn(80.dp, 130.dp)
        val phase = derivePhase(ui)

        LaunchedEffect(phase) {
            if (phase == ReceivePhase.SUCCESS || phase == ReceivePhase.FAILED) {
                delay(ResultAutoExitTimeoutMs)
                onExit()
            }
        }

        val cancellable = phase == ReceivePhase.TRANSFERRING
        var backArmed by remember { mutableStateOf(false) }
        LaunchedEffect(cancellable, backArmed) {
            if (!cancellable) {
                backArmed = false
                return@LaunchedEffect
            }
            if (backArmed) {
                delay(BackHintTimeoutMs)
                backArmed = false
            }
        }
        BackHandler(enabled = cancellable) {
            if (backArmed) {
                viewModel.stopReceiving()
                onExit()
            } else {
                backArmed = true
                Toast.makeText(
                    context,
                    context.getString(R.string.back_again_to_cancel),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = screenPaddingH, vertical = screenPaddingV),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.action_receive),
                style = titleStyle,
                color = scheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(titleToBodyGap))

            ReceiveCard(
                modifier = Modifier.widthIn(max = contentMaxWidth).fillMaxWidth(),
                horizontalPadding = cardHorizontalPadding,
                verticalPadding = cardVerticalPadding,
            ) {
                when (phase) {
                    ReceivePhase.LISTENING -> ListeningContent(
                        bodyStyle = bodyStyle,
                        descStyle = descStyle,
                        deviceName = deviceName,
                        deviceNameStyle = controlLabelStyle,
                        deviceNamePadding = buttonContentPadding,
                        artworkSize = artworkSize,
                        sectionGap = sectionGap,
                        tightGap = tightGap,
                    )
                    ReceivePhase.CONNECTING -> ConnectingContent(
                        ui = ui,
                        bodyStyle = bodyStyle,
                        descStyle = descStyle,
                        artworkSize = artworkSize,
                        sectionGap = sectionGap,
                        tightGap = tightGap,
                        pinPillContentPadding = pinPillContentPadding,
                    )
                    ReceivePhase.PROMPT -> PromptContent(
                        ui = ui,
                        bodyStyle = bodyStyle,
                        descStyle = descStyle,
                        buttonLabelStyle = controlLabelStyle,
                        buttonContentPadding = buttonContentPadding,
                        pinPillContentPadding = pinPillContentPadding,
                        sectionGap = sectionGap,
                        tightGap = tightGap,
                        onAccept = { viewModel.decide(true) },
                        onReject = { viewModel.decide(false) },
                    )
                    ReceivePhase.TRANSFERRING -> TransferringContent(
                        ui = ui,
                        titleStyle = bodyStyle,
                        descStyle = descStyle,
                        pillLabelStyle = controlLabelStyle,
                        pillContentPadding = buttonContentPadding,
                        progressSize = artworkSize,
                        sectionGap = sectionGap,
                    )
                    ReceivePhase.SUCCESS -> ResultContent(
                        success = true,
                        message = stringResource(R.string.receive_complete_message),
                        bodyStyle = bodyStyle,
                        artworkSize = resultIconSize,
                        sectionGap = sectionGap,
                    )
                    ReceivePhase.FAILED -> ResultContent(
                        success = false,
                        message = stringResource(ui.errorMessageRes ?: R.string.receive_failed_message),
                        bodyStyle = bodyStyle,
                        artworkSize = resultIconSize,
                        sectionGap = sectionGap,
                    )
                }
            }
        }
    }
}


@Composable
private fun ReceiveCard(
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
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun ColumnScope.ListeningContent(
    bodyStyle: TextStyle,
    descStyle: TextStyle,
    deviceName: String,
    deviceNameStyle: TextStyle,
    deviceNamePadding: PaddingValues,
    artworkSize: Dp,
    sectionGap: Dp,
    tightGap: Dp,
) {
    val scheme = MaterialTheme.colorScheme
    deviceName.takeIf { it.isNotBlank() }?.let { name ->
        DeviceNamePill(
            deviceName = name,
            labelStyle = deviceNameStyle,
            contentPadding = deviceNamePadding,
        )
        Spacer(Modifier.height(sectionGap))
    }
    ReceiveRadarArtwork(size = artworkSize)
    Spacer(Modifier.height(sectionGap))
    Text(
        text = stringResource(R.string.receive_status_listening),
        style = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        color = scheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(tightGap))
    Text(
        text = stringResource(R.string.receive_status_listening_hint),
        style = descStyle,
        color = scheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun DeviceNamePill(
    deviceName: String,
    labelStyle: TextStyle,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    val nameStyle = labelStyle.copy(
        fontSize = labelStyle.fontSize * DeviceNameToButtonFontRatio,
        lineHeight = labelStyle.lineHeight * DeviceNameToButtonFontRatio,
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(scheme.surfaceVariant)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_tv),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(nameStyle.fontSize.value.dp * DeviceNamePillIconToFontRatio),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = deviceName,
                style = nameStyle.glyphCentered(),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** PIN pill shown during the CONNECTING phase while waiting for UKEY2 to complete. */
@Composable
private fun PinPill(
    pin: String,
    labelStyle: TextStyle,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .border(1.dp, scheme.primary, shape)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.receive_pin_label, pin),
            style = labelStyle.copy(
                fontSize = labelStyle.fontSize * PinPillFontScale,
                lineHeight = labelStyle.lineHeight * PinPillFontScale,
                fontWeight = FontWeight.SemiBold,
            ).glyphCentered(),
            color = scheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ColumnScope.ConnectingContent(
    ui: ReceiveViewModel.Ui,
    bodyStyle: TextStyle,
    descStyle: TextStyle,
    artworkSize: Dp,
    sectionGap: Dp,
    tightGap: Dp,
    pinPillContentPadding: PaddingValues,
) {
    val scheme = MaterialTheme.colorScheme
    ReceiveRadarArtwork(size = artworkSize)
    Spacer(Modifier.height(sectionGap))
    PeerHeader(
        ui = ui,
        bodyStyle = bodyStyle,
        descStyle = descStyle,
        tightGap = tightGap,
        pinPillContentPadding = pinPillContentPadding,
    )
    Text(
        text = if (ui.authString != null || ui.pin != null) {
            stringResource(R.string.receive_status_handshaking)
        } else {
            stringResource(R.string.receive_status_connecting)
        },
        style = descStyle,
        color = scheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * Transferring phase: sender pill + circular progress + subtext.
 *
 * Design notes:
 *  - The PIN pill is intentionally suppressed once the user has accepted the
 *    transfer; the digits only matter for the verify-then-accept handshake
 *    and become visual noise during streaming.
 *  - Progress is rendered as a circular arc with the live percentage at its
 *    centre. A radial dial reads more naturally than a linear bar at TV
 *    distance because the percentage label sits at the optical centre of
 *    the dial, not floating off to one side.
 *  - Subtext stays generic; the circular dial already carries the active
 *    progress detail, so file-specific copy would add noise at TV distance.
 */
@Composable
private fun ColumnScope.TransferringContent(
    ui: ReceiveViewModel.Ui,
    titleStyle: TextStyle,
    descStyle: TextStyle,
    pillLabelStyle: TextStyle,
    pillContentPadding: PaddingValues,
    progressSize: Dp,
    sectionGap: Dp,
) {
    val scheme = MaterialTheme.colorScheme

    val deviceLabel = ui.peerName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.receive_prompt_unknown_device)
    SenderDevicePill(
        kind = ui.peerKind,
        deviceName = deviceLabel,
        labelStyle = pillLabelStyle,
        contentPadding = pillContentPadding,
    )
    Spacer(Modifier.height(sectionGap))

    val rawProgress = batchTransferProgress(ui)
    val progress by animateFloatAsState(
        targetValue = rawProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250),
        label = "receive-progress",
    )
    val percent = (progress * 100f).toInt().coerceIn(0, 100)

    Box(
        modifier = Modifier.size(progressSize),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressArc(
            progress = progress,
            color = scheme.primary,
            trackColor = scheme.surfaceVariant,
            strokeWidth = CircularProgressStroke,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = "$percent%",
            style = titleStyle.copy(fontWeight = FontWeight.SemiBold).glyphCentered(),
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(sectionGap))
    Text(
        text = stringResource(R.string.receive_status_receiving),
        style = descStyle,
        color = scheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun batchTransferProgress(ui: ReceiveViewModel.Ui): Float {
    val introTotal = ui.pendingFiles.sumOf { it.size.coerceAtLeast(0L) }
    if (introTotal > 0L) {
        val received = ui.pendingFiles.sumOf { file ->
            val current = ui.progressByPayload[file.payloadId]?.first
            when {
                current != null && file.size > 0L -> current.coerceIn(0L, file.size)
                current != null -> current.coerceAtLeast(0L)
                file.payloadId in ui.savedPayloadIds -> file.size.coerceAtLeast(0L)
                else -> 0L
            }
        }
        return received.toFloat() / introTotal.toFloat()
    }

    val (receivedSum, totalSum) = ui.progressByPayload.values.fold(0L to 0L) { acc, (received, total) ->
        (acc.first + received.coerceAtLeast(0L)) to (acc.second + total.coerceAtLeast(0L))
    }
    return if (totalSum > 0L) receivedSum.toFloat() / totalSum.toFloat() else 0f
}

/**
 * Compact device pill used during the Transferring phase. Renders just the
 * kind-appropriate icon plus the sender's display name — the icon already
 * conveys the device kind, so the explicit "Phone" / "Tablet" prefix would
 * be redundant noise at TV distance.
 */
@Composable
private fun SenderDevicePill(
    kind: DeviceKind,
    deviceName: String,
    labelStyle: TextStyle,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    val nameStyle = labelStyle.copy(
        fontSize = labelStyle.fontSize * DeviceNameToButtonFontRatio,
        lineHeight = labelStyle.lineHeight * DeviceNameToButtonFontRatio,
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(scheme.surfaceVariant)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(kind.iconRes()),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(nameStyle.fontSize.value.dp * DeviceNamePillIconToFontRatio),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = deviceName,
                style = nameStyle.glyphCentered(),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


@Composable
private fun ColumnScope.ResultContent(
    success: Boolean,
    message: String,
    bodyStyle: TextStyle,
    artworkSize: Dp,
    sectionGap: Dp,
) {
    val scheme = MaterialTheme.colorScheme
    PlayRawSoundEffect(
        soundRes = R.raw.success,
        trigger = if (success) Unit else null,
    )
    ResultLottie(
        success = success,
        size = artworkSize,
    )
    Spacer(Modifier.height(sectionGap))
    Text(text = message, style = bodyStyle, color = scheme.onSurface, textAlign = TextAlign.Center)
}

@Composable
private fun PeerHeader(
    ui: ReceiveViewModel.Ui,
    bodyStyle: TextStyle,
    descStyle: TextStyle,
    tightGap: Dp,
    pinPillContentPadding: PaddingValues,
) {
    ui.peerName?.takeIf { it.isNotBlank() }?.let { peerName ->
        SenderDevicePill(
            kind = ui.peerKind,
            deviceName = peerName,
            labelStyle = bodyStyle,
            contentPadding = pinPillContentPadding,
        )
        Spacer(Modifier.height(tightGap))
    }
    ui.pin?.takeIf { it.isNotBlank() }?.let { pin ->
        PinPill(
            pin = pin,
            labelStyle = descStyle,
            contentPadding = pinPillContentPadding,
        )
        Spacer(Modifier.height(tightGap))
    }
}

// =====================================================================
//  Design tokens
// =====================================================================

private const val DeviceNameToButtonFontRatio = 0.85f
private const val DeviceNamePillIconToFontRatio = 1.3f
private const val BackHintTimeoutMs = 3_000L
private const val ResultAutoExitTimeoutMs = 5_000L
private const val PinPillFontScale = 0.88f

private val LargeScreenWidthThreshold = 1200.dp
private val CardCornerRadius = 16.dp
/**
 * Stroke for the post-accept circular transfer dial. Calibrated so the
 * arc reads as a confident progress indicator at TV-distance viewing
 * (~3 m) on a 1080p display without overpowering the centred percentage
 * label.
 */
private val CircularProgressStroke = 8.dp

private const val ButtonVerticalPadToLineHeightRatio = 0.55f
private const val ButtonHorizontalPadToLineHeightRatio = 1.0f
private const val PinPillVerticalPadToFontRatio = 0.45f
private const val PinPillHorizontalPadToFontRatio = 1.0f
