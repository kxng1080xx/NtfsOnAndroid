package dev.ntfsfm.app.ui.files

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ntfsfm.app.formatBytes
import dev.ntfsfm.core.vfs.VfsEntry
import java.text.DateFormat
import java.util.Date

/** The coloured rounded square with the file-type glyph. */
@Composable
fun FileTypeBadge(visual: FileVisual, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(MaterialTheme.shapes.small).background(visual.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(visual.icon, contentDescription = null, tint = visual.tint, modifier = Modifier.size(size * 0.55f))
    }
}

fun entrySubtitle(entry: VfsEntry): String {
    val date = if (entry.timestamps.modifiedAt > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.timestamps.modifiedAt)) else null
    val size = if (entry.isDirectory) null else formatBytes(entry.sizeBytes)
    return listOfNotNull(size, date).joinToString("  ·  ")
}

@Composable
private fun selectionBackground(selected: Boolean): Color {
    val target = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    return animateColorAsState(target, label = "selection").value
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListRow(
    entry: VfsEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String = entrySubtitle(entry),
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(selectionBackground(selected))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FileTypeBadge(visualFor(entry.name, entry.isDirectory))
            if (selected) SelectedCheck(Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (supportingText.isNotEmpty()) {
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridTile(entry: VfsEntry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val visual = visualFor(entry.name, entry.isDirectory)
    Column(
        modifier
            .padding(4.dp)
            .clip(MaterialTheme.shapes.large)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            FileTypeBadge(visual, size = 64.dp)
            if (selected) SelectedCheck(Modifier.align(Alignment.BottomEnd))
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SelectedCheck(modifier: Modifier) {
    Icon(
        Icons.Rounded.CheckCircle,
        contentDescription = "Selected",
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(20.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
    )
}
