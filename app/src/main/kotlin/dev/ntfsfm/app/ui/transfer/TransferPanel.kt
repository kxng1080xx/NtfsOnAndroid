package dev.ntfsfm.app.ui.transfer

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ntfsfm.app.formatBytes
import dev.ntfsfm.app.formatDuration
import dev.ntfsfm.app.formatSpeed
import dev.ntfsfm.app.ui.design.card
import dev.ntfsfm.core.transfer.TransferProgress

private fun TransferProgress.fraction(): Float = if (bytesTotal > 0) (bytesTransferred.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f

private fun TransferProgress.timeLeft(paused: Boolean): String =
    if (paused || throughputBytesPerSecond <= 0) "--" else formatDuration((bytesTotal - bytesTransferred).coerceAtLeast(0) / throughputBytesPerSecond)

/** The copy card docked at the bottom of every screen while a copy runs, and until dismissed. */
@Composable
fun TransferPanel(
    progress: TransferProgress,
    isPaused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val animated by animateFloatAsState(progress.fraction(), label = "transfer")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .card(MaterialTheme.shapes.extraLarge, elevation = 3f)
            .animateContentSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (progress.isDone) {
            DoneContent(progress, onDismiss)
            return@Column
        }
        val preparing = progress.itemsTotal == 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(Icons.Rounded.SyncAlt, if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        preparing -> "Preparing copy"
                        isPaused -> "Paused"
                        else -> "Copying"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    progress.currentItem?.displayName ?: "Working out what to copy…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onHide) { Icon(Icons.Rounded.KeyboardArrowDown, "Hide") }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (preparing) {
                LinearProgressIndicator(Modifier.weight(1f).height(10.dp).clip(CircleShape), strokeCap = StrokeCap.Round)
            } else {
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.weight(1f).height(10.dp).clip(CircleShape),
                    color = if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("${(progress.fraction() * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("${progress.filesCompleted} / ${progress.filesTotal}", "files")
            Stat(if (isPaused) "Paused" else formatSpeed(progress.throughputBytesPerSecond), "speed")
            Stat(progress.timeLeft(isPaused), "left")
        }
        Text(
            "${formatBytes(progress.bytesTransferred)} of ${formatBytes(progress.bytesTotal)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = if (isPaused) onResume else onPause, enabled = !preparing) {
                Icon(if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (isPaused) "Resume" else "Pause")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** What the panel shrinks to when hidden: tap to bring it back. */
@Composable
fun TransferMiniPill(progress: TransferProgress, isPaused: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .card(CircleShape, elevation = 3f)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress.fraction() },
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            if (isPaused) Icon(Icons.Rounded.Pause, null, Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "${(progress.fraction() * 100).toInt()}% · " +
                if (isPaused) "Paused" else "${progress.filesCompleted}/${progress.filesTotal} · ${formatSpeed(progress.throughputBytesPerSecond)}",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun DoneContent(p: TransferProgress, onDismiss: () -> Unit) {
    val files = "${p.filesCompleted} file${if (p.filesCompleted == 1) "" else "s"}"
    val outcome = when {
        p.wasCancelled -> Outcome(Icons.Rounded.Close, MaterialTheme.colorScheme.outline, "Copy cancelled", "$files finished before it stopped")
        p.fatalError != null -> Outcome(Icons.Rounded.ErrorOutline, MaterialTheme.colorScheme.error, "Copy failed", p.fatalError!!)
        p.itemsFailed > 0 -> Outcome(Icons.Rounded.ErrorOutline, MaterialTheme.colorScheme.tertiary, "Copied with problems", "${p.itemsFailed} of ${p.itemsTotal} items couldn't be copied")
        else -> Outcome(Icons.Rounded.CheckCircle, Color(0xFF22C55E), "Copied $files", formatBytes(p.bytesTransferred))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusIcon(outcome.icon, outcome.color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(outcome.title, style = MaterialTheme.typography.titleSmall)
            Text(outcome.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss") }
    }
}

private class Outcome(val icon: ImageVector, val color: Color, val title: String, val subtitle: String)

@Composable
private fun StatusIcon(icon: ImageVector, color: Color) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color)
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
