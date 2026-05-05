package com.quickshare.tv.ui.send

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.quickshare.tv.R
import com.quickshare.tv.ui.humanReadableFileSize
import com.quickshare.tv.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CardCornerRadius = Spacing.md

/**
 * A single picked file's display metadata. `size == UnknownFileSize`
 * means the resolver couldn't determine the byte count (typical of
 * remote / cloud-backed `content://` URIs that don't expose
 * `OpenableColumns.SIZE`); the row will then render an em-dash in
 * place of the human-readable size.
 */
internal data class PickedFileInfo(val name: String, val size: Long)

internal const val UnknownFileSize = -1L

/**
 * Cap on inline file rows. Picked at 4 because:
 *  - 4 rows + header + divider stay under ~140 dp on typical TV
 *    densities, leaving the status card comfortably above the fold.
 *  - Sender flows are usually small batches (1–3 files); the cap
 *    only kicks in for the long-tail bulk-share case.
 */
private const val MaxVisibleFileRows = 4

/**
 * Card listing every URI the user picked: file name on the left,
 * human-readable size on the right, divider between rows.
 */
@Composable
internal fun FileListCard(
    files: List<PickedFileInfo>,
    nameStyle: TextStyle,
    sizeStyle: TextStyle,
    nameColor: Color,
    sizeColor: Color,
    rowVerticalPadding: Dp,
    rowSideGap: Dp,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(CardCornerRadius)

    val visible = files.take(MaxVisibleFileRows)
    val overflow = (files.size - visible.size).coerceAtLeast(0)

    Column(
        modifier = modifier
            .clip(shape)
            .background(scheme.surface)
            .border(width = 1.dp, color = scheme.borderVariant, shape = shape)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        visible.forEachIndexed { index, info ->
            FileRow(
                info = info,
                nameStyle = nameStyle,
                sizeStyle = sizeStyle,
                nameColor = nameColor,
                sizeColor = sizeColor,
                sideGap = rowSideGap,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = rowVerticalPadding),
            )
            if (index < visible.lastIndex || overflow > 0) {
                SendFileRowDivider(color = scheme.borderVariant)
            }
        }
        if (overflow > 0) {
            Text(
                text = stringResource(R.string.send_files_more, overflow),
                style = sizeStyle,
                color = sizeColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = rowVerticalPadding),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FileRow(
    info: PickedFileInfo,
    nameStyle: TextStyle,
    sizeStyle: TextStyle,
    nameColor: Color,
    sizeColor: Color,
    sideGap: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = info.name,
            style = nameStyle,
            color = nameColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
        Spacer(Modifier.size(sideGap))
        Text(
            text = if (info.size == UnknownFileSize) {
                stringResource(R.string.send_file_size_unknown)
            } else {
                humanReadableFileSize(info.size)
            },
            style = sizeStyle,
            color = sizeColor,
            maxLines = 1,
        )
    }
}

/** Thin horizontal rule used between file rows and in the OR separator. */
@Composable
internal fun SendFileRowDivider(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/**
 * Resolve display name + size for every URI the user picked. The
 * I/O lookup runs on [Dispatchers.IO]; until it completes we render
 * a synchronous fallback derived from the URI's tail segment so the
 * file card never blinks on first appearance.
 */
@Composable
internal fun rememberPickedFileInfos(uris: List<Uri>): List<PickedFileInfo> {
    val context = LocalContext.current
    var infos by remember(uris) {
        mutableStateOf(uris.map { it.toFallbackInfo() })
    }
    LaunchedEffect(uris) {
        if (uris.isEmpty()) {
            infos = emptyList()
            return@LaunchedEffect
        }
        infos = withContext(Dispatchers.IO) {
            uris.map { resolveFileInfo(context, it) }
        }
    }
    return infos
}

internal fun Uri.toFallbackInfo(): PickedFileInfo {
    val tail = lastPathSegment?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: "File"
    return PickedFileInfo(name = tail, size = UnknownFileSize)
}

internal fun resolveFileInfo(context: Context, uri: Uri): PickedFileInfo {
    val projection = arrayOf(
        OpenableColumns.DISPLAY_NAME,
        OpenableColumns.SIZE,
    )
    val resolver = context.contentResolver
    return runCatching {
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                cursor.getString(nameIdx)
            } else {
                null
            }
            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                cursor.getLong(sizeIdx)
            } else {
                UnknownFileSize
            }
            PickedFileInfo(
                name = name?.takeIf { it.isNotBlank() }
                    ?: uri.toFallbackInfo().name,
                size = size,
            )
        }
    }.getOrNull() ?: uri.toFallbackInfo()
}
