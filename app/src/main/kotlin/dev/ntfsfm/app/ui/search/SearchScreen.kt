package dev.ntfsfm.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ntfsfm.app.files.openInOtherApp
import dev.ntfsfm.app.ui.design.EmptyState
import dev.ntfsfm.app.ui.design.PillChip
import dev.ntfsfm.app.ui.design.RoundIconButton
import dev.ntfsfm.app.ui.design.bottomClearance
import dev.ntfsfm.app.ui.design.card
import dev.ntfsfm.app.ui.files.FileListRow
import dev.ntfsfm.app.ui.files.HomeCategory
import dev.ntfsfm.app.ui.files.entrySubtitle

/**
 * Search across every connected drive. [initialCategory] comes from a home-screen tile;
 * [onBack] is null when this is the Search tab itself.
 */
@Composable
fun SearchScreen(
    initialCategory: HomeCategory?,
    onBack: (() -> Unit)?,
    onOpenResult: (volumeId: String, folderPath: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    LaunchedEffect(initialCategory) {
        if (initialCategory != null) viewModel.setCategory(initialCategory) else focus.requestFocus()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(10.dp))
            }
            Row(
                Modifier.weight(1f).card(CircleShape, elevation = 1.5f).padding(start = 18.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (state.query.isEmpty()) {
                        Text(
                            state.category?.let { "Search ${it.label.lowercase()}" } ?: "Search all storage",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Rounded.Close, "Clear search") }
                }
            }
        }
        LazyRow(
            Modifier.padding(vertical = 12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { PillChip("All types", selected = state.category == null, onClick = { viewModel.setCategory(null) }) }
            items(HomeCategory.entries) { category ->
                PillChip(category.label, selected = state.category == category, onClick = { viewModel.setCategory(category) })
            }
        }
        if (state.isSearching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 28.dp).height(3.dp), trackColor = Color.Transparent)
        }
        when {
            !state.hasSearched -> EmptyState(Icons.Rounded.Search, "Find anything", "Results appear as they're found, across every connected drive.")
            state.results.isEmpty() && !state.isSearching -> EmptyState(
                Icons.Rounded.SearchOff,
                "No matches",
                if (state.query.isNotBlank()) "Nothing matched \"${state.query}\"." else "Nothing of this type was found on your storage.",
            )
            else -> LazyColumn(
                Modifier.padding(horizontal = 12.dp).card(MaterialTheme.shapes.extraLarge),
                contentPadding = PaddingValues(top = 6.dp, bottom = bottomClearance()),
            ) {
                item {
                    Text(
                        "${state.results.size} result${if (state.results.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
                items(state.results, key = { "${it.volumeId}:${it.entry.path}" }) { result ->
                    val entry = result.entry
                    FileListRow(
                        entry = entry,
                        selected = false,
                        supportingText = listOf(result.volumeName, entry.path.substringBeforeLast('/', ""), entrySubtitle(entry))
                            .filter { it.isNotEmpty() }
                            .joinToString(" · "),
                        onClick = {
                            // Files open in another app; if none can, show the file in its folder instead.
                            if (entry.isDirectory) {
                                onOpenResult(result.volumeId, entry.path)
                            } else if (!openInOtherApp(context, result.volumeId, entry.path, entry.name)) {
                                onOpenResult(result.volumeId, entry.path.substringBeforeLast('/', ""))
                            }
                        },
                        onLongClick = {},
                    )
                }
            }
        }
    }
}
