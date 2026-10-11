package dev.ntfsfm.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ntfsfm.app.ui.files.FileListRow
import dev.ntfsfm.app.ui.files.FileTypeBadge
import dev.ntfsfm.app.ui.files.HomeCategory
import dev.ntfsfm.app.ui.files.entrySubtitle
import dev.ntfsfm.app.ui.files.visualFor

/** Free-text search, or (with [category]) a category browse like "all images". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    category: HomeCategory?,
    onBack: () -> Unit,
    onOpenResult: (volumeId: String, folderPath: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(category) {
        if (category != null) viewModel.setCategory(category) else focus.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        placeholder = { Text(category?.let { "Search ${it.label.lowercase()}" } ?: "Search all storage") },
                        leadingIcon = {
                            if (category != null) FileTypeBadge(visualFor(category), size = 28.dp) else Icon(Icons.Rounded.Search, null)
                        },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Rounded.Close, "Clear search") }
                            }
                        },
                        singleLine = true,
                        shape = CircleShape,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp).focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.isSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                !state.hasSearched -> Centered(Icons.Rounded.Search, "Search your files", "Results appear as they are found, across every connected drive.")
                state.results.isEmpty() && !state.isSearching -> Centered(
                    Icons.Rounded.SearchOff,
                    "No matches",
                    if (state.query.isNotBlank()) "Nothing matched \"${state.query}\"." else "Nothing of this type was found on your storage.",
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.results, key = { "${it.volumeId}:${it.entry.path}" }) { result ->
                        val entry = result.entry
                        FileListRow(
                            entry = entry,
                            selected = false,
                            supportingText = listOf(result.volumeName, entry.path.substringBeforeLast('/', ""), entrySubtitle(entry))
                                .filter { it.isNotEmpty() }
                                .joinToString(" · "),
                            onClick = {
                                val folder = if (entry.isDirectory) entry.path else entry.path.substringBeforeLast('/', "")
                                onOpenResult(result.volumeId, folder)
                            },
                            onLongClick = {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
