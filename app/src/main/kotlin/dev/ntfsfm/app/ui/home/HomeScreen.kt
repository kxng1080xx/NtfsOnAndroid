package dev.ntfsfm.app.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ntfsfm.app.formatBytes
import dev.ntfsfm.app.ui.design.ContentCard
import dev.ntfsfm.app.ui.design.RoundIconButton
import dev.ntfsfm.app.ui.design.SectionHeader
import dev.ntfsfm.app.ui.design.bottomClearance
import dev.ntfsfm.app.ui.design.card
import dev.ntfsfm.app.ui.files.FileCategory
import dev.ntfsfm.app.ui.files.FileTypeBadge
import dev.ntfsfm.app.ui.files.HomeCategory
import dev.ntfsfm.app.ui.files.visualFor
import dev.ntfsfm.app.ui.permission.rememberStorageAccess
import dev.ntfsfm.app.ui.theme.LocalDarkTheme
import dev.ntfsfm.app.ui.theme.LocalToggleDarkTheme

@Composable
fun HomeScreen(
    onOpenVolume: (volumeId: String) -> Unit,
    onOpenFolder: (volumeId: String, path: String) -> Unit,
    onOpenCategory: (HomeCategory) -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val locations by viewModel.locations.collectAsStateWithLifecycle()
    val recents by viewModel.recentFolders.collectAsStateWithLifecycle()
    val storageAccess = rememberStorageAccess()

    LazyColumn(contentPadding = PaddingValues(bottom = bottomClearance())) {
        item(key = "title") { Greeting(onOpenSearch) }
        if (!storageAccess.isGranted) {
            item(key = "storage-access") { StorageAccessCard(storageAccess.request) }
        }
        item(key = "drives") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                items(locations, key = { it.volumeId }) { location ->
                    StorageHeroCard(
                        location = location,
                        enabled = location.isUsb || storageAccess.isGranted,
                        onOpen = { onOpenVolume(location.volumeId) },
                        onRequestPermission = { location.device?.let(viewModel::requestPermission) },
                        onRetry = viewModel::retry,
                        modifier = Modifier.fillParentMaxWidth(if (locations.size == 1) 1f else 0.86f),
                    )
                }
            }
        }
        item(key = "categories-header") { SectionHeader("Categories") }
        item(key = "categories") { CategoryGrid(onOpenCategory) }
        if (recents.isNotEmpty()) {
            item(key = "recents-header") { SectionHeader("Recent folders") }
            item(key = "recents") {
                ContentCard {
                    recents.forEachIndexed { index, recent ->
                        RecentRow(recent) { onOpenFolder(recent.volumeId, recent.path) }
                        if (index != recents.lastIndex) {
                            HorizontalDivider(Modifier.padding(start = 76.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Greeting(onOpenSearch: () -> Unit) {
    val dark = LocalDarkTheme.current
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 24.dp, end = 20.dp, top = 20.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Let's manage", style = MaterialTheme.typography.headlineMedium)
                Text("your files", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            RoundIconButton(Icons.Rounded.Search, "Search", onOpenSearch)
            Spacer(Modifier.width(10.dp))
            RoundIconButton(if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, if (dark) "Switch to light" else "Switch to dark", LocalToggleDarkTheme.current)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StorageAccessCard(onAllow: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .card(MaterialTheme.shapes.large, tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Files are hidden", style = MaterialTheme.typography.titleSmall)
            Text("Allow access to browse phone storage and copy to USB drives.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onAllow) { Text("Allow") }
    }
}

/** A bold gradient card per drive, like a bank card: name, usage, and a ring. */
@Composable
private fun StorageHeroCard(
    location: StorageLocationUi,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = if (location.isUsb) listOf(Color(0xFF0FA3A3), Color(0xFF2F7CF6)) else listOf(Color(0xFF3B5BFD), Color(0xFF7B61FF))
    val shape = MaterialTheme.shapes.extraLarge
    Column(
        modifier
            .height(184.dp)
            .clip(shape)
            .background(Brush.linearGradient(colors))
            .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), center = Offset(80f, 0f), radius = 520f))
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.05f))), shape)
            .clickable(enabled = enabled && location.isOpenable, onClick = onOpen)
            .padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(if (location.isUsb) Icons.Rounded.Usb else Icons.Rounded.PhoneAndroid, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(location.displayName, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        when {
            location.needsPermission -> HeroMessage("Allow access to open this drive", "Allow", onRequestPermission)
            location.unsupportedReason != null -> HeroMessage(location.unsupportedReason, "Retry", onRetry)
            !enabled -> HeroMessage("Allow file access to open phone storage", null, {})
            location.totalBytes > 0 -> Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(formatBytes(location.usedBytes), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Text("used of ${formatBytes(location.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(Color.White)
                        Text("Used", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                        Spacer(Modifier.width(12.dp))
                        LegendDot(Color.White.copy(alpha = 0.35f))
                        Text("Free ${formatBytes(location.totalBytes - location.usedBytes)}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                    }
                }
                Box(contentAlignment = Alignment.Center) {
                    UsageRing(location.usedFraction)
                    Text("${(location.usedFraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun HeroMessage(text: String, action: String?, onAction: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (action != null) {
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F2A60)),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        ) { Text(action) }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun UsageRing(fraction: Float) {
    val animated by animateFloatAsState(fraction, label = "usage")
    Canvas(Modifier.size(76.dp)) {
        val stroke = 8.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(Color.White, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun CategoryGrid(onOpenCategory: (HomeCategory) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HomeCategory.entries.chunked(4).forEach { row ->
            Row {
                row.forEach { category -> CategoryTile(category, Modifier.weight(1f)) { onOpenCategory(category) } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Icon in a soft rounded square with the label underneath, as in the reference designs. */
@Composable
private fun CategoryTile(category: HomeCategory, modifier: Modifier, onClick: () -> Unit) {
    val visual = visualFor(category)
    Column(
        modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).card(MaterialTheme.shapes.large, elevation = 1.3f), contentAlignment = Alignment.Center) {
            Icon(visual.icon, null, tint = visual.tint, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(category.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecentRow(recent: RecentFolderUi, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeBadge(visualFor(FileCategory.FOLDER))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(recent.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(recent.volumeName, recent.path.substringBeforeLast('/', "")).filter { it.isNotEmpty() }.joinToString(" › "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}
