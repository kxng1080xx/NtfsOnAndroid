package dev.ntfsfm.app.ui.nav

import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.ntfsfm.app.ui.browse.BrowseScreen
import dev.ntfsfm.app.ui.design.LocalBottomOverlay
import dev.ntfsfm.app.ui.design.card
import dev.ntfsfm.app.ui.files.HomeCategory
import dev.ntfsfm.app.ui.home.HomeScreen
import dev.ntfsfm.app.ui.network.NetworkScreen
import dev.ntfsfm.app.ui.search.SearchScreen
import dev.ntfsfm.app.ui.settings.SettingsScreen
import dev.ntfsfm.app.ui.transfer.ConflictDialog
import dev.ntfsfm.app.ui.transfer.TransferMiniPill
import dev.ntfsfm.app.ui.transfer.TransferPanel
import dev.ntfsfm.app.ui.transfer.TransferViewModel

// Paths can hold anything ('/', '?', '%', spaces), so nav arguments carry them as URL-safe Base64.
private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

fun encodeArg(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), BASE64_FLAGS)

fun decodeArg(encoded: String): String =
    if (encoded.isEmpty()) "" else String(Base64.decode(encoded, BASE64_FLAGS), Charsets.UTF_8)

fun browseRoute(volumeId: String, path: String): String =
    if (path.isEmpty()) "browse/${encodeArg(volumeId)}" else "browse/${encodeArg(volumeId)}?path=${encodeArg(path)}"

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Rounded.Home),
    SEARCH("search", "Search", Icons.Rounded.Search),
    NETWORK("network", "Network", Icons.Rounded.Lan),
    SETTINGS("settings", "Settings", Icons.Rounded.Settings),
}

@Composable
fun NtfsFileManagerNavHost(transferViewModel: TransferViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val progress by transferViewModel.progress.collectAsStateWithLifecycle()
    val isPaused by transferViewModel.isPaused.collectAsStateWithLifecycle()
    val isHidden by transferViewModel.isHidden.collectAsStateWithLifecycle()
    val conflict by transferViewModel.pendingConflict.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()
    val currentTab = Tab.entries.firstOrNull { it.route == backStack?.destination?.route }
    val density = LocalDensity.current
    var overlayHeight by remember { mutableStateOf(0.dp) }

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBottomOverlay provides overlayHeight) {
            NavHost(navController, startDestination = Tab.HOME.route, modifier = Modifier.fillMaxSize()) {
                composable(Tab.HOME.route) {
                    HomeScreen(
                        onOpenVolume = { navController.navigate(browseRoute(it, "")) },
                        onOpenFolder = { volumeId, path -> navController.navigate(browseRoute(volumeId, path)) },
                        onOpenCategory = { navController.navigate("category/${it.name}") },
                        onOpenSearch = { navController.switchTab(Tab.SEARCH) },
                    )
                }
                composable(Tab.SEARCH.route) {
                    SearchScreen(
                        initialCategory = null,
                        onBack = null,
                        onOpenResult = { volumeId, folder -> navController.navigate(browseRoute(volumeId, folder)) },
                    )
                }
                composable(Tab.NETWORK.route) {
                    NetworkScreen(onOpenVolume = { navController.navigate(browseRoute(it, "")) })
                }
                composable(Tab.SETTINGS.route) { SettingsScreen() }
                composable("category/{category}", listOf(navArgument("category") { type = NavType.StringType })) { entry ->
                    SearchScreen(
                        initialCategory = HomeCategory.valueOf(checkNotNull(entry.arguments?.getString("category"))),
                        onBack = navController::popBackStack,
                        onOpenResult = { volumeId, folder -> navController.navigate(browseRoute(volumeId, folder)) },
                    )
                }
                composable(
                    "browse/{volumeId}?path={path}",
                    listOf(
                        navArgument("volumeId") { type = NavType.StringType },
                        navArgument("path") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    // Folders change inside the screen; only leaving the drive pops it.
                    BrowseScreen(onBack = navController::popBackStack)
                }
            }
        }

        // Everything that floats over the bottom: the copy card, then the tab bar.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .onSizeChanged { overlayHeight = with(density) { it.height.toDp() } }
                .padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            progress?.let {
                if (isHidden && !it.isDone) {
                    TransferMiniPill(it, isPaused, onClick = transferViewModel::show)
                } else {
                    TransferPanel(
                        progress = it,
                        isPaused = isPaused,
                        onPause = transferViewModel::pause,
                        onResume = transferViewModel::resume,
                        onCancel = transferViewModel::cancel,
                        onHide = transferViewModel::hide,
                        onDismiss = transferViewModel::dismiss,
                    )
                }
                if (currentTab != null) Spacer(Modifier.height(10.dp))
            }
            AnimatedVisibility(
                visible = currentTab != null,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                TabBar(currentTab) { navController.switchTab(it) }
            }
        }
    }
    conflict?.let { ConflictDialog(it.entry, onAnswer = transferViewModel::resolveConflict) }
}

private fun NavHostController.switchTab(tab: Tab) = navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Floating pill bar; the active tab grows into a filled capsule with its label. */
@Composable
private fun TabBar(current: Tab?, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.card(CircleShape, elevation = 1.8f).padding(6.dp).animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { tab ->
            val selected = tab == current
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = if (selected) 18.dp else 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(tab.icon, tab.label, tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}
