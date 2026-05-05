package com.quickshare.tv.ui.receive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.quickshare.tv.R
import com.quickshare.tv.domain.model.FileMeta
import com.quickshare.tv.ui.humanReadableFileSize
import com.quickshare.tv.ui.components.PlayRawSoundEffect
import com.quickshare.tv.ui.theme.QuickShareColors
import com.quickshare.tv.ui.theme.Spacing
import com.quickshare.tv.ui.theme.glyphCentered

/**
 * Prompt phase content: shows a summary of the incoming transfer (sender
 * name, file count, total size), an optional PIN pill for visual
 * verification, a scrollable file list, and Accept / Reject buttons.
 *
 * The Accept button is auto-focused so the first D-pad press on a TV
 * lands on the safe positive action. A sound effect fires once on entry
 * to alert the user that a sharing request arrived.
 */
@Composable
internal fun ColumnScope.PromptContent(
    ui: ReceiveViewModel.Ui,
    bodyStyle: TextStyle,
    descStyle: TextStyle,
    buttonLabelStyle: TextStyle,
    buttonContentPadding: PaddingValues,
    pinPillContentPadding: PaddingValues,
    sectionGap: Dp,
    tightGap: Dp,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val acceptFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { acceptFocus.requestFocus() } }
    PlayRawSoundEffect(soundRes = R.raw.confirmation, trigger = Unit)

    val deviceLabel = ui.peerName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.receive_prompt_unknown_device)
    val totalSize = ui.pendingFiles.sumOf { it.size.coerceAtLeast(0L) }
    Text(
        text = stringResource(
            R.string.receive_prompt_summary,
            deviceLabel,
            ui.pendingFiles.size,
            shareTypeLabel(ui.pendingFiles),
            humanReadableFileSize(totalSize),
        ),
        style = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        color = scheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    ui.pin?.takeIf { it.isNotBlank() }?.let { pin ->
        Spacer(Modifier.height(sectionGap))
        PromptPinPill(
            pin = pin,
            labelStyle = descStyle,
            contentPadding = pinPillContentPadding,
        )
    }
    if (ui.pendingFiles.isNotEmpty()) {
        Spacer(Modifier.height(sectionGap))
        Text(
            text = stringResource(R.string.receive_files_card_title),
            style = descStyle,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(tightGap))
        IncomingFileList(
            files = ui.pendingFiles,
            nameStyle = descStyle,
            rowVerticalPadding = Spacing.sm,
        )
    }
    Spacer(Modifier.height(sectionGap))
    Row(verticalAlignment = Alignment.CenterVertically) {
        ReceiveActionButton(
            text = stringResource(R.string.receive_action_reject),
            labelStyle = buttonLabelStyle,
            contentPadding = buttonContentPadding,
            onClick = onReject,
            destructive = true,
        )
        Spacer(Modifier.width(Spacing.md))
        ReceiveActionButton(
            text = stringResource(R.string.receive_action_accept),
            labelStyle = buttonLabelStyle,
            contentPadding = buttonContentPadding,
            onClick = onAccept,
            modifier = Modifier.focusRequester(acceptFocus),
        )
    }
}

/** PIN pill shown inside the prompt for the user to compare with the sender's display. */
@Composable
private fun PromptPinPill(
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
private fun IncomingFileList(
    files: List<FileMeta>,
    nameStyle: TextStyle,
    rowVerticalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val visible = files.take(MaxVisibleFileRows)
    val overflow = (files.size - visible.size).coerceAtLeast(0)
    val shape = RoundedCornerShape(InnerCardCornerRadius)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceVariant)
            .border(width = 1.dp, color = scheme.borderVariant, shape = shape)
            .padding(horizontal = Spacing.md, vertical = rowVerticalPadding),
    ) {
        visible.forEachIndexed { index, file ->
            PromptFileRow(
                file = file,
                nameStyle = nameStyle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = rowVerticalPadding),
            )
            if (index < visible.lastIndex || overflow > 0) PromptFileRowDivider(color = scheme.borderVariant)
        }
        if (overflow > 0) {
            Text(
                text = stringResource(R.string.receive_files_more, overflow),
                style = nameStyle.copy(
                    fontSize = nameStyle.fontSize * MoreFilesTextScale,
                    lineHeight = nameStyle.lineHeight * MoreFilesTextScale,
                ),
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = rowVerticalPadding),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PromptFileRow(file: FileMeta, nameStyle: TextStyle, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = file.name,
            style = nameStyle,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = humanReadableFileSize(file.size),
            style = nameStyle,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun PromptFileRowDivider(color: Color) {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
internal fun shareTypeLabel(files: List<FileMeta>): String {
    if (files.isEmpty()) return stringResource(R.string.receive_share_type_file)
    val majorTypes = files.mapNotNull { file ->
        file.mimeType
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() && it != "*" }
    }.toSet()
    return when {
        majorTypes.size == 1 -> when (majorTypes.first()) {
            "image" -> stringResource(R.string.receive_share_type_image)
            "video" -> stringResource(R.string.receive_share_type_video)
            "audio" -> stringResource(R.string.receive_share_type_audio)
            "text" -> stringResource(R.string.receive_share_type_text_file)
            else -> stringResource(R.string.receive_share_type_file)
        }
        else -> stringResource(R.string.receive_share_type_file)
    }
}

/**
 * Accept / Reject buttons used inside the prompt. Shares the TV-focus
 * colour-swap + glow vocabulary with the home / send screen buttons.
 * Pass `destructive = true` for the Reject button to flip the focused
 * container to a red danger colour.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ReceiveActionButton(
    text: String,
    labelStyle: TextStyle,
    contentPadding: PaddingValues,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val focusColor = if (destructive) QuickShareColors.R700 else scheme.primary
    val focusContent = if (destructive) QuickShareColors.R50 else scheme.onPrimary
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = contentPadding,
        colors = ButtonDefaults.colors(
            containerColor = scheme.surfaceVariant,
            contentColor = scheme.onSurfaceVariant,
            focusedContainerColor = focusColor,
            focusedContentColor = focusContent,
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
            focusedGlow = Glow(elevationColor = focusColor, elevation = StaticFocusedGlowElevation),
        ),
    ) {
        Text(text = text, style = labelStyle.glyphCentered())
    }
}

// Prompt-specific constants ──────────────────────────────────────────
private const val NoScale = 1f
private const val MaxVisibleFileRows = 4
private const val MoreFilesTextScale = 0.85f
private const val PinPillFontScale = 0.88f
private val InnerCardCornerRadius = 12.dp
private val StaticFocusedGlowElevation = 4.dp
