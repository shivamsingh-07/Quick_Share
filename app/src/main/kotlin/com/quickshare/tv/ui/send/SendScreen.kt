package com.quickshare.tv.ui.send

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
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

// =====================================================================
//  SendScreen
//
//  Phase-driven flow that mirrors the settings page design language:
//  page title above a single grouped card, `surface` background with
//  `borderVariant` outline, typography-driven control geometry, no
//  scale animation, focus signalled by colour swap + glow on the
//  primary CTA, static glow on chrome.
//
//  ┌──────────────── Phase derivation ─────────────────────────────┐
//  │                                                                │
//  │   uris empty                            → PICK_FILES           │
//  │   uris set, mode=PICK_DEVICE, no peer   → PICK_DEVICE          │
//  │     (also renders the file list ABOVE   →   the device list)   │
//  │   uris set, mode=QR, no peer            → AWAITING_SCAN        │
//  │   peer connected / progress             → TRANSFERRING         │
//  │   ui.done                               → SUCCESS              │
//  │   ui.errorMessageRes != null            → FAILED               │
//  │                                                                │
//  └────────────────────────────────────────────────────────────────┘
//
//  PICK_DEVICE is the new default once URIs arrive. The picker shows
//  a "Nearby Devices" card and a centered QR fallback below an OR divider.
//  Device rows are mDNS-resolved peers, each rendered with a phone/TV
//  icon based on the receiver's advertised device type. The user can
//  D-pad to a row + select to start the transfer, or tap "Use QR" to
//  fall back to the legacy QR pipeline.
//
//  The screen drives the VM via `LaunchedEffect(uris)`: when URIs
//  arrive it kicks off the send pipeline; when URIs clear it resets
//  the VM so a stale `done` / `error` from a prior session can't
//  leak into the next one.
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SendScreen(
    uris: List<Uri>,
    onPickFiles: () -> Unit,
    onExit: () -> Unit,
) {
    val viewModel: SendViewModel = viewModel()
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose { viewModel.stopSending() }
    }

    // Drive the underlying send pipeline from the URI prop. Re-keyed
    // on `uris` so picking a fresh batch restarts cleanly; clearing
    // the picker resets the VM (see `reset()` doc).
    LaunchedEffect(uris) {
        if (uris.isNotEmpty()) viewModel.startWith(context, uris)
        else viewModel.reset()
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

        // ── Responsive layout sizes (mirrors SettingsScreen) ────────────
        val screenPaddingH  = Spacing.xl
        val screenPaddingV  = Spacing.lg
        val contentMaxWidth = (w * 0.62f ).coerceIn(420.dp, 760.dp)
        val titleToBodyGap  = (h * 0.06f ).coerceIn(Spacing.lg,  Spacing.xxl)

        // Hero artwork sizes are split per phase. The QR needs the
        // bigger envelope so each module is ≥ ~6 px of clean anti-
        // aliased circle on typical TV densities (~1.5×) — crisp from
        // a 10-foot couch, no PNG bicubic scale-down step to introduce
        // blur. The progress arc is mid-sized: it has a percentage
        // glyph centred inside it that needs to read at 10 ft. The
        // result icon (check / cross in a circle) carries no inner
        // text and is the smallest of the three — same visual weight
        // as a Material status badge.
        val qrArtworkSize       = (minOf(w, h) * 0.32f).coerceIn(180.dp, 280.dp)
        val progressArtworkSize = (minOf(w, h) * 0.20f).coerceIn(120.dp, 180.dp)
        val resultIconSize      = (minOf(w, h) * 0.13f).coerceIn(80.dp, 130.dp)

        // ── Responsive typography (mirrors SettingsScreen) ──────────────
        val titleStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.displayMedium
            else typography.displaySmall
        val bodyStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.titleMedium
            else typography.titleSmall
        val descStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.bodyMedium
            else typography.bodySmall
        val controlLabelStyle: TextStyle =
            if (w > LargeScreenWidthThreshold) typography.titleMedium
            else typography.titleSmall

        // ── Typography-driven control geometry (matches HomeScreen). ────
        val density = LocalDensity.current
        val controlFontSizeDp   = with(density) { controlLabelStyle.fontSize.toDp() }
        val descFontSizeDp      = with(density) { descStyle.fontSize.toDp() }
        val controlLineHeightDp = with(density) { controlLabelStyle.lineHeight.toDp() }
        val buttonIcon          = controlFontSizeDp   * ButtonIconToFontRatio
        val iconLabelGap        = controlFontSizeDp   * IconLabelGapToFontRatio
        val buttonContentPadding = PaddingValues(
            horizontal = controlLineHeightDp * ButtonHorizontalPadToLineHeightRatio,
            vertical   = controlLineHeightDp * ButtonVerticalPadToLineHeightRatio,
        )
        val pinPillContentPadding = PaddingValues(
            horizontal = descFontSizeDp * PinPillHorizontalPadToFontRatio,
            vertical = descFontSizeDp * PinPillVerticalPadToFontRatio,
        )

        // Card geometry (matches SettingsScreen ratios).
        val cardHorizontalPadding = Spacing.xl
        val cardVerticalPadding   = Spacing.xl
        val sectionGap            = Spacing.lg
        val tightGap              = Spacing.sm

        // The file-list card is a passive reference panel — it gets a
        // tighter vertical envelope than the active status card so the
        // page reads as "small reference, big focus", and so a single
        // picked file doesn't burn ~80 dp of vertical padding the
        // bottom card then has to compete with.
        val fileCardVerticalPadding   = Spacing.sm
        val betweenCardsGap           = Spacing.md
        val deviceCardHorizontalPadding = Spacing.lg
        val deviceCardVerticalPadding   = Spacing.md
        val qrChipContentPadding = PaddingValues(
            horizontal = Spacing.md,
            vertical = Spacing.sm,
        )

        val phase = derivePhase(uris, ui)
        LaunchedEffect(phase) {
            if (phase == SendPhase.SUCCESS || phase == SendPhase.FAILED) {
                delay(ResultAutoExitTimeoutMs)
                onExit()
            }
        }

        // Phase-aware Back behaviour. PICK_FILES / SUCCESS / FAILED
        // delegate to the parent `BackHandler` in `QuickShareNav`
        // (single press → home). PICK_DEVICE / AWAITING_SCAN /
        // TRANSFERRING are "in-flight" phases where an accidental Back
        // would lose the session — we intercept the first press to
        // prime a 3-second confirmation window (signalled with the
        // same Android `Toast` style used elsewhere in the app, e.g.
        // the home-screen "Enable Bluetooth" prompt), and only the
        // second press within that window actually cancels.
        val cancellable = phase == SendPhase.PICK_DEVICE ||
            phase == SendPhase.AWAITING_SCAN ||
            phase == SendPhase.TRANSFERRING
        var backArmed by remember { mutableStateOf(false) }
        // Arm-state auto-disarms after `BackHintTimeoutMs`. Re-keying
        // on `backArmed` so a second press that *does* land within the
        // window cancels the pending disarm scheduling.
        LaunchedEffect(backArmed) {
            if (backArmed) {
                delay(BackHintTimeoutMs)
                backArmed = false
            }
        }
        BackHandler(enabled = cancellable) {
            if (backArmed) {
                viewModel.stopSending()
                onExit()
            } else {
                backArmed = true
                // System `Toast` instead of an inline pill: the toast
                // floats above whatever's on screen (transferring
                // progress arc, QR card, picker list) without
                // competing with the centred content for layout
                // space, and matches the home-screen Bluetooth-toast
                // pattern so the user reads "press again to confirm"
                // as one consistent system-wide affordance.
                Toast.makeText(
                    context,
                    context.getString(R.string.back_again_to_cancel),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        // File metadata is resolved off-thread via ContentResolver and
        // re-resolved whenever the URI list changes. The fallback list
        // (built synchronously from URI tail segments) is used while
        // the I/O query is in flight so the file card never blinks.
        val fileInfos = rememberPickedFileInfos(uris)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = screenPaddingH, vertical = screenPaddingV),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Constant page title — every phase reads as the same
            // destination ("Send"); only the in-card content changes.
            // Keeping the title fixed avoids the layout reshuffle a
            // phase-specific title would cause when phases swap.
            Text(
                text = stringResource(R.string.action_send),
                style = titleStyle,
                color = scheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(titleToBodyGap))

            // File-list card — present whenever the user has picked
            // anything, regardless of phase. Acts as the "what we're
            // sending" reference panel above the live status panel.
            if (fileInfos.isNotEmpty()) {
                SectionTitle(
                    text = stringResource(R.string.send_files_card_title),
                    style = bodyStyle,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxWidth(),
                )
                Spacer(Modifier.height(betweenCardsGap))
                FileListCard(
                    files = fileInfos,
                    nameStyle = descStyle,
                    sizeStyle = descStyle,
                    nameColor = scheme.onSurface,
                    sizeColor = scheme.onSurfaceVariant,
                    rowVerticalPadding = Spacing.sm,
                    rowSideGap = Spacing.md,
                    horizontalPadding = cardHorizontalPadding,
                    verticalPadding = fileCardVerticalPadding,
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxWidth(),
                )
                Spacer(Modifier.height(betweenCardsGap))
            }

            // PICK_DEVICE renders the picker as a two-element block
            // (header row + device list card), NOT inside the standard
            // SendCard. Every other phase shares the standard card
            // chrome so the page reads as two stacked surfaces of
            // equal weight.
            if (phase == SendPhase.PICK_DEVICE) {
                DevicePickerSection(
                    devices = ui.devices,
                    bluetooth = ui.bluetooth,
                    onSelectDevice = viewModel::selectDevice,
                    onUseQr = viewModel::switchToQrMode,
                    headerStyle = bodyStyle,
                    bodyStyle = bodyStyle,
                    descStyle = descStyle,
                    rowLabelStyle = bodyStyle,
                    chipLabelStyle = descStyle,
                    iconLabelGap = iconLabelGap,
                    rowIconSize = DeviceCircleSize * DeviceCircleIconRatio,
                    promptIconSize = buttonIcon * 1.7f,
                    horizontalPadding = deviceCardHorizontalPadding,
                    verticalPadding = deviceCardVerticalPadding,
                    rowVerticalPadding = Spacing.sm,
                    headerToCardGap = betweenCardsGap,
                    chipContentPadding = qrChipContentPadding,
                    chipIconSize = buttonIcon * 0.9f,
                    modifier = Modifier
                        .widthIn(max = contentMaxWidth)
                        .fillMaxWidth(),
                )
            } else {
                SendCard(
                    modifier = Modifier.widthIn(max = contentMaxWidth).fillMaxWidth(),
                    horizontalPadding = cardHorizontalPadding,
                    verticalPadding = cardVerticalPadding,
                ) {
                    when (phase) {
                        SendPhase.PICK_FILES -> PickFilesContent(
                            buttonLabelStyle = controlLabelStyle,
                            buttonIconSize = buttonIcon,
                            iconLabelGap = iconLabelGap,
                            buttonContentPadding = buttonContentPadding,
                            onPickFiles = onPickFiles,
                        )

                        SendPhase.AWAITING_SCAN -> QrContent(
                            pngBytes = ui.qrPngBytes,
                            bodyStyle = bodyStyle,
                            artworkSize = qrArtworkSize,
                            sectionGap = sectionGap,
                        )

                        SendPhase.TRANSFERRING -> TransferringContent(
                            ui = ui,
                            bodyStyle = bodyStyle,
                            descStyle = descStyle,
                            artworkSize = progressArtworkSize,
                            sectionGap = sectionGap,
                            tightGap = tightGap,
                            devicePillContentPadding = buttonContentPadding,
                            pinPillContentPadding = pinPillContentPadding,
                        )

                        SendPhase.SUCCESS -> ResultContent(
                            success = true,
                            message = stringResource(R.string.send_complete_message),
                            bodyStyle = bodyStyle,
                            artworkSize = resultIconSize,
                            sectionGap = sectionGap,
                        )

                        SendPhase.FAILED -> ResultContent(
                            success = false,
                            message = stringResource(ui.errorMessageRes ?: R.string.send_failed_message),
                            bodyStyle = bodyStyle,
                            artworkSize = resultIconSize,
                            sectionGap = sectionGap,
                        )

                        // Unreachable — handled in the outer `if`.
                        SendPhase.PICK_DEVICE -> Unit
                    }
                }
            }
        }

        // Back-press confirmation is now surfaced as an Android
        // `Toast` from the `BackHandler` above — no inline UI lives
        // here, which keeps the picker / QR / progress card centred
        // and free of bottom-anchored chrome.
    }
}


@Composable
private fun SectionTitle(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier,
    )
}

// =====================================================================
//  Card primitive (mirrors SettingsCard)
// =====================================================================

@Composable
private fun SendCard(
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

// =====================================================================
//  Phase content
// =====================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ColumnScope.PickFilesContent(
    buttonLabelStyle: TextStyle,
    buttonIconSize: Dp,
    iconLabelGap: Dp,
    buttonContentPadding: PaddingValues,
    onPickFiles: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    PrimaryActionButton(
        text = stringResource(R.string.send_action_select_files),
        iconRes = R.drawable.ic_send,
        labelStyle = buttonLabelStyle,
        iconSize = buttonIconSize,
        iconLabelGap = iconLabelGap,
        contentPadding = buttonContentPadding,
        onClick = onPickFiles,
        modifier = Modifier.focusRequester(focus),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ColumnScope.QrContent(
    pngBytes: ByteArray?,
    bodyStyle: TextStyle,
    artworkSize: Dp,
    sectionGap: Dp,
) {
    val scheme = MaterialTheme.colorScheme

    // No focusable affordance lives in this card — Cancel is gone in
    // favour of the double-Back gesture, and the QR is purely visual.
    // The page's only focus-eligible target is now the file-list card
    // (which has no focusable descendants either), so the framework
    // happily leaves focus stowed off-screen until the user presses
    // Back, which is exactly the desired calm-state behaviour.

    QrArtwork(pngBytes = pngBytes, size = artworkSize)
    Spacer(Modifier.height(sectionGap))
    Text(
        text = stringResource(R.string.send_qr_hint),
        style = bodyStyle.copy(
            fontSize = bodyStyle.fontSize * QrHintFontScale,
            lineHeight = bodyStyle.lineHeight * QrHintFontScale,
        ),
        color = scheme.onSurface,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ColumnScope.TransferringContent(
    ui: SendViewModel.Ui,
    bodyStyle: TextStyle,
    descStyle: TextStyle,
    artworkSize: Dp,
    sectionGap: Dp,
    tightGap: Dp,
    devicePillContentPadding: PaddingValues,
    pinPillContentPadding: PaddingValues,
) {
    val scheme = MaterialTheme.colorScheme

    // QR-initiated transfers intentionally skip both the peer name and
    // the PIN row:
    //   - The user already authenticated by scanning our QR.
    //   - We sign the UKEY2 auth string with the QR EC key, so the
    //     receiver phone auto-accepts without showing its confirm
    //     dialog — there's no PIN comparison for the user to do.
    //   - The QR path also accepts anonymous (no-visible-name) peers
    //     (see `pickQrPeer` in `SendRepository.kt`), so trying to
    //     render `peerName` would frequently show a fallback like
    //     "Phone" that adds noise without information.
    val isQrInitiated = ui.mode == SendViewModel.Mode.QR
    if (!isQrInitiated) {
        ui.peerName
            ?.takeIf { it.isNotBlank() }
            ?.let { peerName ->
                PeerDevicePill(
                    kind = ui.peerKind,
                    deviceName = peerName,
                    labelStyle = bodyStyle,
                    contentPadding = devicePillContentPadding,
                )
                Spacer(Modifier.height(sectionGap))
            }
        ui.pin
            ?.takeIf { it.isNotBlank() }
            ?.let { pin ->
                SendPinPill(
                    pin = pin,
                    labelStyle = descStyle,
                    contentPadding = pinPillContentPadding,
                )
                Spacer(Modifier.height(sectionGap))
            }
    }

    // Sum byte counters across all in-flight payloads. With the
    // current FrameIo + SenderSession contract a `Progress` event is
    // emitted per chunk per file, so the running totals here always
    // reflect "how much of the entire batch is on the wire" — not
    // just the current file — which matches the single-arc UI.
    val (sentSum, totalSum) = ui.progress.values.fold(0L to 0L) { acc, (s, t) ->
        (acc.first + s) to (acc.second + t)
    }
    val rawProgress = if (totalSum > 0L) sentSum.toFloat() / totalSum.toFloat() else 0f
    val progress by animateFloatAsState(
        targetValue = rawProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250),
        label = "send-progress",
    )
    val percent = (progress * 100f).toInt().coerceIn(0, 100)

    Box(
        modifier = Modifier.size(artworkSize),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressArc(
            progress = progress,
            modifier = Modifier.fillMaxSize(),
            color = scheme.primary,
            trackColor = scheme.surfaceVariant,
            strokeWidth = CircularProgressStroke,
        )
        Text(
            text = "$percent%",
            style = bodyStyle.copy(fontWeight = FontWeight.SemiBold).glyphCentered(),
            color = scheme.onSurface,
        )
    }
    Spacer(Modifier.height(sectionGap))
    // Live status line — driven by the VM reducer:
    //   Connecting → "Connecting..."
    //   Handshaked → "Verifying connection..."
    //   Awaiting   → "Waiting for confirmation..."
    //   Progress   → "Sending..."  (overwrites the stale Awaiting copy)
    //   Done       → "Done"
    Text(
        text = stringResource(ui.statusRes),
        style = descStyle,
        color = scheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SendPinPill(
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
            text = stringResource(R.string.send_pin_label, pin),
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
private fun PeerDevicePill(
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
        fontWeight = FontWeight.SemiBold,
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

    // No "Done" button anymore — the user dismisses the result the
    // same way every other phase exits: a single Back press, handled
    // by the parent `BackHandler` in `QuickShareNav` because Result
    // phases are NOT in the `cancellable` set above.

    PlayRawSoundEffect(
        soundRes = R.raw.success,
        trigger = if (success) Unit else null,
    )
    ResultLottie(
        success = success,
        size = artworkSize,
    )
    Spacer(Modifier.height(sectionGap))
    Text(
        text = message,
        style = bodyStyle,
        color = scheme.onSurface,
        textAlign = TextAlign.Center,
    )
}


// =====================================================================
//  Buttons
// =====================================================================

/**
 * Primary CTA — shares the home-screen action button identity:
 * `surfaceVariant`/`primary` colour swap on focus, no scale, static focused
 * blur. Used here only for the "Select Files" entry-point CTA.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PrimaryActionButton(
    text: String,
    iconRes: Int,
    labelStyle: TextStyle,
    iconSize: Dp,
    iconLabelGap: Dp,
    contentPadding: PaddingValues,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val focusedGlow = Glow(elevationColor = scheme.primary, elevation = StaticFocusedGlowElevation)
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = contentPadding,
        colors = ButtonDefaults.colors(
            containerColor = scheme.surfaceVariant,
            contentColor = scheme.onSurfaceVariant,
            focusedContainerColor = scheme.primary,
            focusedContentColor = scheme.onPrimary,
        ),
        scale = ButtonDefaults.scale(
            scale = NoScale,
            focusedScale = NoScale,
            pressedScale = NoScale,
            disabledScale = NoScale,
            focusedDisabledScale = NoScale,
        ),
        glow = ButtonDefaults.glow(
            glow = Glow.None,
            focusedGlow = focusedGlow,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(iconLabelGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
            Text(text = text, style = labelStyle.glyphCentered())
        }
    }
}

// `SecondaryActionButton` (Cancel / Done) was deleted in the
// double-Back-to-cancel refactor. The whole page now has a single
// CTA (`PrimaryActionButton` for "Select Files"); every other phase
// dismisses through the parent `BackHandler`.

// =====================================================================
//  Design tokens (mirrored from Home / Settings)
// =====================================================================

private const val NoScale = 1f
private val LargeScreenWidthThreshold = 1200.dp

private val CardCornerRadius = Spacing.md

/**
 * Time window for the double-Back-to-cancel gesture. After the first
 * Back press during PICK_DEVICE / AWAITING_SCAN / TRANSFERRING we
 * fire the "Press Back again to cancel" Toast and arm the cancel; if
 * the user does NOT press Back a second time within this window we
 * silently disarm. Tuned to be long enough that a deliberate "no,
 * never mind" pause feels safe, short enough that the user can't
 * accidentally cancel a transfer minutes later by hitting Back once
 * absent-mindedly.
 */
private const val BackHintTimeoutMs = 3_000L
private const val ResultAutoExitTimeoutMs = 5_000L

private val StaticFocusedGlowElevation = 4.dp
private val CircularProgressStroke = 8.dp

// Button geometry ratios (mirrored from Home/SettingsScreen).
private const val ButtonIconToFontRatio                = 1.4f
private const val IconLabelGapToFontRatio              = 0.6f
private const val ButtonVerticalPadToLineHeightRatio   = 0.55f
private const val ButtonHorizontalPadToLineHeightRatio = 1.0f
private const val PinPillVerticalPadToFontRatio        = 0.45f
private const val PinPillHorizontalPadToFontRatio      = 1.0f
private const val PinPillFontScale                     = 0.88f
private const val QrHintFontScale                      = 0.82f
private const val DeviceNameToButtonFontRatio          = 0.85f
private const val DeviceNamePillIconToFontRatio        = 1.3f

// Device circle dimensions used to compute `rowIconSize` passed to DevicePickerSection.
private val DeviceCircleSize = 72.dp
private const val DeviceCircleIconRatio = 0.56f
