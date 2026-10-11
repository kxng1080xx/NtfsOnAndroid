package dev.ntfsfm.app.ui.transfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ntfsfm.app.formatBytes
import dev.ntfsfm.core.transfer.TransferProgress

/** Docked at the bottom of every screen while a copy runs, and until it's dismissed. */
@Composable
fun TransferPanel(
    progress: TransferProgress,
    isPaused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val fraction = if (progress.bytesTotal > 0) progress.bytesTransferred.toFloat() / progress.bytesTotal else 0f
    val animatedFraction by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "transfer")
    val outcome = outcomeOf(progress)

    Surface(
        Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(outcome.color.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(outcome.icon, null, tint = outcome.color)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(outcome.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        outcome.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress.isDone) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss") }
                } else {
                    IconButton(onClick = if (isPaused) onResume else onPause) {
                        Icon(if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (isPaused) "Resume" else "Pause")
                    }
                    IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel") }
                }
            }
            if (!progress.isDone) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { animatedFraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = outcome.color,
                    strokeCap = StrokeCap.Round,
                )
                AnimatedVisibility(expanded) {
                    Column(Modifier.padding(top = 10.dp)) {
                        Detail("Items", "${progress.itemsCompleted} of ${progress.itemsTotal}")
                        Detail("Data", "${formatBytes(progress.bytesTransferred)} of ${formatBytes(progress.bytesTotal)}")
                        Detail("Speed", "${formatBytes(progress.throughputBytesPerSecond)}/s")
                        Detail("Time left", timeLeft(progress))
                    }
                }
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.End)) {
                    Text(if (expanded) "Show less" else "Show more")
                }
            }
        }
    }
}

private class Outcome(val title: String, val subtitle: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val color: Color)

@Composable
private fun outcomeOf(p: TransferProgress): Outcome {
    val items = "${p.itemsCompleted} item${if (p.itemsCompleted == 1) "" else "s"}"
    val ok = Color(0xFF22C55E)
    return when {
        !p.isDone && p.bytesTotal <= 0 && p.itemsTotal == 0 -> Outcome("Preparing copy", "Working out what to copy…", Icons.Rounded.SyncAlt, MaterialTheme.colorScheme.primary)
        !p.isDone -> Outcome(
            p.currentItem?.displayName?.let { "Copying $it" } ?: "Copying",
            "${(p.bytesTransferred * 100 / p.bytesTotal.coerceAtLeast(1))}% · ${p.itemsCompleted} of ${p.itemsTotal} items",
            Icons.Rounded.SyncAlt,
            MaterialTheme.colorScheme.primary,
        )
        p.wasCancelled -> Outcome("Copy cancelled", "$items finished before it stopped", Icons.Rounded.Close, MaterialTheme.colorScheme.outline)
        p.fatalError != null -> Outcome("Copy failed", p.fatalError!!, Icons.Rounded.ErrorOutline, MaterialTheme.colorScheme.error)
        p.itemsFailed > 0 -> Outcome(
            "Copied with problems",
            "${p.itemsFailed} of ${p.itemsTotal} items couldn't be copied",
            Icons.Rounded.ErrorOutline,
            MaterialTheme.colorScheme.tertiary,
        )
        else -> Outcome("Copied $items", formatBytes(p.bytesTransferred), Icons.Rounded.CheckCircle, ok)
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun timeLeft(p: TransferProgress): String {
    if (p.throughputBytesPerSecond <= 0) return "--"
    val seconds = (p.bytesTotal - p.bytesTransferred).coerceAtLeast(0) / p.throughputBytesPerSecond
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
