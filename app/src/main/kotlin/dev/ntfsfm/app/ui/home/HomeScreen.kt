package dev.ntfsfm.app.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ntfsfm.app.formatBytes
import dev.ntfsfm.app.ui.design.SectionCard
import dev.ntfsfm.app.ui.design.SectionHeader
import dev.ntfsfm.app.ui.files.FileTypeBadge
import dev.ntfsfm.app.ui.files.HomeCategory
import dev.ntfsfm.app.ui.files.visualFor
import dev.ntfsfm.app.ui.permission.rememberStorageAccess

@Composable
fun HomeScreen(
    onOpenVolume: (volumeId: String) -> Unit,
    onOpenCategory: (HomeCategory) -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val locations by viewModel.locations.collectAsStateWithLifecycle()
    val storageAccess = rememberStorageAccess()

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") { HomeHeader(onOpenSearch) }
        if (!storageAccess.isGranted) {
            item(key = "storage-access") { StorageAccessCard(storageAccess.request) }
        }
        item(key = "storage-header") { SectionHeader("Storage") }
        items(locations, key = { it.volumeId }) { location ->
            StorageLocationCard(
                location = location,
                enabled = location.isUsb || storageAccess.isGranted,
                onOpen = { onOpenVolume(location.volumeId) },
                onRequestPermission = { location.device?.let(viewModel::requestPermission) },
                onRetry = viewModel::retry,
            )
            Spacer(Modifier.height(10.dp))
        }
        item(key = "categories-header") { SectionHeader("Categories") }
        item(key = "categories") { CategoryGrid(onOpenCategory) }
    }
}

@Composable
private fun HomeHeader(onOpenSearch: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            .background(Brush.linearGradient(listOf(scheme.primary, Color(0xFF7C3AED), scheme.secondary)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text("NTFS File Manager", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "Your phone and USB drives, all in one place",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(20.dp))
        Surface(
            onClick = onOpenSearch,
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.22f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                Text("Search files and folders", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun StorageAccessCard(onAllow: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Files are hidden", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    "Allow access so you can browse phone storage and copy to and from USB drives.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAllow) { Text("Allow") }
        }
    }
}

@Composable
private fun StorageLocationCard(
    location: StorageLocationUi,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
) {
    val accent = if (location.isUsb) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    SectionCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled && location.isOpenable, onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(MaterialTheme.shapes.medium).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (location.isUsb) Icons.Rounded.Usb else Icons.Rounded.PhoneAndroid, contentDescription = null, tint = accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(location.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    location.needsPermission -> Text(
                        "Allow access to open this drive",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    location.unsupportedReason != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(4.dp))
                        Text(location.unsupportedReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    location.totalBytes > 0 -> {
                        Spacer(Modifier.height(8.dp))
                        UsageBar(location.usedFraction, accent)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatBytes(location.usedBytes)} used of ${formatBytes(location.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            when {
                location.needsPermission -> FilledTonalButton(onClick = onRequestPermission) { Text("Allow") }
                location.unsupportedReason != null -> TextButton(onClick = onRetry) { Text("Retry") }
                enabled -> Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UsageBar(fraction: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                .height(8.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.6f)))),
        )
    }
}

@Composable
private fun CategoryGrid(onOpenCategory: (HomeCategory) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeCategory.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { category ->
                    CategoryTile(category, Modifier.weight(1f)) { onOpenCategory(category) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CategoryTile(category: HomeCategory, modifier: Modifier, onClick: () -> Unit) {
    val visual = visualFor(category)
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(visual.container)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FileTypeBadge(visual.copy(container = MaterialTheme.colorScheme.surface), size = 44.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            category.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
