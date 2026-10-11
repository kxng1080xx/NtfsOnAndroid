package dev.ntfsfm.app.ui.nav

import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.ntfsfm.app.ui.browse.BrowseScreen
import dev.ntfsfm.app.ui.files.HomeCategory
import dev.ntfsfm.app.ui.home.HomeScreen
import dev.ntfsfm.app.ui.search.SearchScreen
import dev.ntfsfm.app.ui.transfer.ConflictDialog
import dev.ntfsfm.app.ui.transfer.TransferPanel
import dev.ntfsfm.app.ui.transfer.TransferViewModel

// Paths can hold anything ('/', '?', '%', spaces), so nav arguments carry them as URL-safe Base64.
private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

fun encodeArg(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), BASE64_FLAGS)

fun decodeArg(encoded: String): String =
    if (encoded.isEmpty()) "" else String(Base64.decode(encoded, BASE64_FLAGS), Charsets.UTF_8)

fun browseRoute(volumeId: String, path: String): String =
    if (path.isEmpty()) "browse/${encodeArg(volumeId)}" else "browse/${encodeArg(volumeId)}?path=${encodeArg(path)}"

@Composable
fun NtfsFileManagerNavHost(transferViewModel: TransferViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val progress by transferViewModel.progress.collectAsStateWithLifecycle()
    val isPaused by transferViewModel.isPaused.collectAsStateWithLifecycle()
    val conflict by transferViewModel.pendingConflict.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        NavHost(navController, startDestination = "home", modifier = Modifier.weight(1f)) {
            composable("home") {
                HomeScreen(
                    onOpenVolume = { navController.navigate(browseRoute(it, "")) },
                    onOpenCategory = { navController.navigate("category/${it.name}") },
                    onOpenSearch = { navController.navigate("search") },
                )
            }
            composable("search") {
                SearchScreen(
                    category = null,
                    onBack = navController::popBackStack,
                    onOpenResult = { volumeId, folder -> navController.navigate(browseRoute(volumeId, folder)) },
                )
            }
            composable("category/{category}", listOf(navArgument("category") { type = NavType.StringType })) { entry ->
                val category = HomeCategory.valueOf(checkNotNull(entry.arguments?.getString("category")))
                SearchScreen(
                    category = category,
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
            ) { entry ->
                val volumeId = decodeArg(checkNotNull(entry.arguments?.getString("volumeId")))
                BrowseScreen(
                    onBack = navController::popBackStack,
                    onOpenPath = { path -> navController.navigate(browseRoute(volumeId, path)) },
                )
            }
        }
        progress?.let {
            TransferPanel(
                progress = it,
                isPaused = isPaused,
                onPause = transferViewModel::pause,
                onResume = transferViewModel::resume,
                onCancel = transferViewModel::cancel,
                onDismiss = transferViewModel::dismiss,
            )
        }
    }
    conflict?.let { ConflictDialog(it.entry, onAnswer = transferViewModel::resolveConflict) }
}
