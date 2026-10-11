package dev.ntfsfm.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.ntfsfm.app.data.AppSettings
import dev.ntfsfm.app.data.ThemeMode
import dev.ntfsfm.app.ui.design.ContentCard
import dev.ntfsfm.app.ui.design.SettingsRow
import dev.ntfsfm.app.ui.design.PageTitle
import dev.ntfsfm.app.ui.design.SectionHeader
import dev.ntfsfm.app.ui.design.bottomClearance
import dev.ntfsfm.app.ui.design.card
import dev.ntfsfm.app.ui.permission.rememberStorageAccess
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val appSettings: AppSettings) : ViewModel() {
    val settings = appSettings.settings
    fun setThemeMode(mode: ThemeMode) = appSettings.setThemeMode(mode)
    fun setShowHiddenFiles(show: Boolean) = appSettings.setShowHiddenFiles(show)
    fun setGridViewByDefault(grid: Boolean) = appSettings.setGridViewByDefault(grid)
}

private const val SOURCE_URL = "https://github.com/kxng1080xx/NtfsOnAndroid"

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val storageAccess = rememberStorageAccess()
    val context = LocalContext.current
    val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }

    LazyColumn(contentPadding = PaddingValues(bottom = bottomClearance())) {
        item { PageTitle("Settings", "Make it yours") }

        item { SectionHeader("Appearance") }
        item {
            ContentCard {
                SettingsRow(Icons.Rounded.Palette, "Theme", subtitle = "Light, dark, or follow your phone")
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).card(CircleShape, elevation = 1.2f).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ThemeSegment(Icons.Rounded.BrightnessAuto, "System", settings.themeMode == ThemeMode.SYSTEM, Modifier.weight(1f)) { viewModel.setThemeMode(ThemeMode.SYSTEM) }
                    ThemeSegment(Icons.Rounded.LightMode, "Light", settings.themeMode == ThemeMode.LIGHT, Modifier.weight(1f)) { viewModel.setThemeMode(ThemeMode.LIGHT) }
                    ThemeSegment(Icons.Rounded.DarkMode, "Dark", settings.themeMode == ThemeMode.DARK, Modifier.weight(1f)) { viewModel.setThemeMode(ThemeMode.DARK) }
                }
            }
        }

        item { SectionHeader("Browsing") }
        item {
            ContentCard {
                SettingsRow(
                    Icons.Rounded.Visibility,
                    "Show hidden files",
                    subtitle = "Files marked hidden, and names starting with a dot",
                    iconTint = Color(0xFF7B61FF),
                    onClick = { viewModel.setShowHiddenFiles(!settings.showHiddenFiles) },
                ) { Switch(settings.showHiddenFiles, onCheckedChange = viewModel::setShowHiddenFiles) }
                SettingsRow(
                    Icons.Rounded.GridView,
                    "Grid view by default",
                    subtitle = "Open folders as tiles instead of a list",
                    iconTint = Color(0xFF0FA3A3),
                    onClick = { viewModel.setGridViewByDefault(!settings.gridViewByDefault) },
                ) { Switch(settings.gridViewByDefault, onCheckedChange = viewModel::setGridViewByDefault) }
            }
        }

        item { SectionHeader("Storage") }
        item {
            ContentCard {
                SettingsRow(
                    if (storageAccess.isGranted) Icons.Rounded.FolderOpen else Icons.Rounded.Storage,
                    "All files access",
                    subtitle = if (storageAccess.isGranted) "Allowed: phone storage is visible" else "Needed to browse phone storage",
                    iconTint = if (storageAccess.isGranted) Color(0xFF22C55E) else MaterialTheme.colorScheme.tertiary,
                    onClick = storageAccess.request,
                ) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.outline) }
            }
        }

        item { SectionHeader("About") }
        item {
            ContentCard {
                SettingsRow(Icons.Rounded.Info, "NTFS File Manager", subtitle = "Version $version · NTFS by ntfs-3g 2026.9.28")
                SettingsRow(
                    Icons.Rounded.Code,
                    "Source code",
                    subtitle = "github.com/kxng1080xx/NtfsOnAndroid",
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) },
                ) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.outline) }
            }
        }
    }
}


@Composable
private fun ThemeSegment(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(icon, null, Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

