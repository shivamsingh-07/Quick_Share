package com.quickshare.tv.ui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.quickshare.tv.R
import com.quickshare.tv.domain.model.DiscoveredDevice
import com.quickshare.tv.system.BluetoothMonitor
import com.quickshare.tv.ui.iconRes
import com.quickshare.tv.ui.theme.Spacing
import com.quickshare.tv.ui.theme.glyphCentered

/**
 * Picker block:
 *   1) Header label.
 *   2) Device list / scanning card.
 *   3) "OR" separator.
 *   4) Centered QR button — rendered in primary colours when Bluetooth is
 *      unavailable so it reads as the primary CTA without relying on
 *      programmatic TV focus.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DevicePickerSection(
    devices: List<DiscoveredDevice>,
    bluetooth: BluetoothMonitor.State,
    onSelectDevice: (String) -> Unit,
    onUseQr: () -> Unit,
    headerStyle: TextStyle,
    bodyStyle: TextStyle,
    descStyle: TextStyle,
    rowLabelStyle: TextStyle,
    chipLabelStyle: TextStyle,
    iconLabelGap: Dp,
    rowIconSize: Dp,
    promptIconSize: Dp,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    rowVerticalPadding: Dp,
    headerToCardGap: Dp,
    chipContentPadding: PaddingValues,
    chipIconSize: Dp,
    modifier: Modifier = Modifier,
) {
    // When Bluetooth is unavailable the QR chip is the only viable action —
    // surface it as the primary CTA regardless of TV focus state, since
    // programmatic requestFocus() does not reliably render a focus highlight
    // before the user presses a D-pad key.
    val btReady = bluetooth == BluetoothMonitor.State.READY

    Column(modifier = modifier) {
        SectionTitle(
            text = stringResource(R.string.send_picker_section_label),
            style = headerStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(headerToCardGap))

        DeviceListCard(
            devices = devices,
            bluetooth = bluetooth,
            onSelectDevice = onSelectDevice,
            rowLabelStyle = rowLabelStyle,
            bodyStyle = bodyStyle,
            descStyle = descStyle,
            rowIconSize = rowIconSize,
            promptIconSize = promptIconSize,
            horizontalPadding = horizontalPadding,
            verticalPadding = verticalPadding,
            rowVerticalPadding = rowVerticalPadding,
        )
        Spacer(Modifier.height(Spacing.md))
        OrSeparator(style = descStyle)
        Spacer(Modifier.height(Spacing.md))

        UseQrChip(
            onClick = onUseQr,
            labelStyle = chipLabelStyle,
            iconSize = chipIconSize,
            iconLabelGap = iconLabelGap,
            contentPadding = chipContentPadding,
            prominent = !btReady,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun OrSeparator(
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SendFileRowDivider(color = scheme.borderVariant, modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.send_picker_or),
            style = style.copy(fontWeight = FontWeight.SemiBold).glyphCentered(),
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        SendFileRowDivider(color = scheme.borderVariant, modifier = Modifier.weight(1f))
    }
}

/**
 * Device list panel — two empty-state branches:
 *  - Bluetooth `READY` + no devices → scanning state.
 *  - Bluetooth not ready            → Bluetooth-off prompt.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DeviceListCard(
    devices: List<DiscoveredDevice>,
    bluetooth: BluetoothMonitor.State,
    onSelectDevice: (String) -> Unit,
    rowLabelStyle: TextStyle,
    bodyStyle: TextStyle,
    descStyle: TextStyle,
    rowIconSize: Dp,
    promptIconSize: Dp,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    rowVerticalPadding: Dp,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(CardCornerRadius)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface)
            .border(width = 1.dp, color = scheme.borderVariant, shape = shape)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        if (devices.isEmpty()) {
            if (bluetooth == BluetoothMonitor.State.READY) {
                ScanningEmptyState(rowLabelStyle = rowLabelStyle)
            } else {
                BluetoothPromptContent(
                    iconRes = R.drawable.ic_bluetooth_off,
                    title = stringResource(R.string.send_picker_bt_off_title),
                    body = stringResource(R.string.send_picker_bt_off_body),
                    bodyStyle = bodyStyle,
                    descStyle = descStyle,
                    iconSize = promptIconSize,
                    rowVerticalPadding = rowVerticalPadding,
                )
            }
        } else {
            val firstFocus = remember { FocusRequester() }
            val firstDeviceId = devices.firstOrNull()?.id
            LaunchedEffect(firstDeviceId, bluetooth) {
                if (firstDeviceId != null && bluetooth == BluetoothMonitor.State.READY) {
                    runCatching { firstFocus.requestFocus() }
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    space = Spacing.md,
                    alignment = Alignment.Start,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                devices.forEachIndexed { index, device ->
                    DeviceTile(
                        device = device,
                        onClick = { onSelectDevice(device.id) },
                        labelStyle = rowLabelStyle,
                        iconSize = rowIconSize,
                        modifier = if (index == 0) {
                            Modifier.focusRequester(firstFocus)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanningEmptyState(rowLabelStyle: TextStyle) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = stringResource(R.string.send_picker_scanning),
        style = rowLabelStyle,
        color = scheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BluetoothPromptContent(
    iconRes: Int,
    title: String,
    body: String,
    bodyStyle: TextStyle,
    descStyle: TextStyle,
    iconSize: Dp,
    rowVerticalPadding: Dp,
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = rowVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
        Text(
            text = title,
            style = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = body,
            style = descStyle,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Circular picker tile — the circle itself is the TV-focusable selector
 * (focus colour swap, soft glow on focus). The device name sits below.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DeviceTile(
    device: DiscoveredDevice,
    onClick: () -> Unit,
    labelStyle: TextStyle,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val focusedGlow = Glow(elevationColor = scheme.primary, elevation = DeviceRowGlowElevation)
    val iconRes = device.kind.iconRes()

    Column(
        modifier = modifier.width(DeviceTileWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = scheme.surfaceVariant,
                contentColor = scheme.onSurfaceVariant,
                focusedContainerColor = scheme.primary,
                focusedContentColor = scheme.onPrimary,
            ),
            scale = ClickableSurfaceDefaults.scale(
                scale = NoScale,
                focusedScale = NoScale,
                pressedScale = NoScale,
            ),
            glow = ClickableSurfaceDefaults.glow(
                glow = Glow.None,
                focusedGlow = focusedGlow,
            ),
            modifier = Modifier.size(DeviceCircleSize),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = device.displayName,
            style = labelStyle.glyphCentered(),
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * QR affordance chip.
 *
 * [prominent] — when true (Bluetooth unavailable) the chip is rendered with
 * primary colours in its resting state so it reads as the primary CTA even
 * before the user presses a D-pad key.  The focused/pressed states stay
 * correct for normal D-pad interaction when Bluetooth is ready.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UseQrChip(
    onClick: () -> Unit,
    labelStyle: TextStyle,
    iconSize: Dp,
    iconLabelGap: Dp,
    contentPadding: PaddingValues,
    prominent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val focusedGlow = Glow(elevationColor = scheme.primary, elevation = DeviceRowGlowElevation)
    val restContainer = if (prominent) scheme.primary        else scheme.surfaceVariant
    val restContent   = if (prominent) scheme.onPrimary     else scheme.onSurfaceVariant
    val restGlow      = if (prominent) focusedGlow          else Glow.None
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = contentPadding,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(percent = 50)),
        colors = ButtonDefaults.colors(
            containerColor = restContainer,
            contentColor = restContent,
            focusedContainerColor = scheme.primary,
            focusedContentColor = scheme.onPrimary,
            pressedContainerColor = scheme.primary,
            pressedContentColor = scheme.onPrimary,
        ),
        scale = ButtonDefaults.scale(
            scale = NoScale,
            focusedScale = NoScale,
            pressedScale = NoScale,
            disabledScale = NoScale,
            focusedDisabledScale = NoScale,
        ),
        glow = ButtonDefaults.glow(
            glow = restGlow,
            focusedGlow = focusedGlow,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(iconLabelGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_qr_code),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
            Text(
                text = stringResource(R.string.send_picker_use_qr),
                style = labelStyle.glyphCentered(),
            )
        }
    }
}

// Device tile design tokens ──────────────────────────────────────────
private val DeviceRowGlowElevation = 4.dp
private val DeviceTileWidth = 104.dp
private val DeviceCircleSize = 72.dp
private const val NoScale = 1f
private val CardCornerRadius = Spacing.md

@Composable
private fun SectionTitle(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(text = text, style = style, color = color, modifier = modifier)
}
