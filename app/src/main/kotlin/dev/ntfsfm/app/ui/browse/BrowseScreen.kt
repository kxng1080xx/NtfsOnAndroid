package dev.ntfsfm.app.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.UsbOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ntfsfm.app.ui.files.FileGridTile
import dev.ntfsfm.app.ui.files.FileListRow
import dev.ntfsfm.core.transfer.ClipboardMode
import dev.ntfsfm.core.transfer.ClipboardState
import dev.ntfsfm.core.vfs.VfsEntry

private data class Breadcrumb(val name: String, val path: String)

private sealed interface NameDialogRequest {
    data object NewFolder : NameDialogRequest
    data class Rename(val entry: VfsEntry) : NameDialogRequest
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onBack: () -> Unit,
    onOpenPath: (path: String) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val transfer by viewModel.transferProgress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var nameDialog by remember { mutableStateOf<NameDialogRequest?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Something was pasted (possibly into this folder): show it.
    LaunchedEffect(transfer?.isDone) { if (transfer?.isDone == true) viewModel.load() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    BackHandler(enabled = state.isEditMode) { viewModel.exitEditMode() }

    val entries = state.sortedEntries
    val onEntryClick: (VfsEntry) -> Unit = { entry ->
        when {
            state.isEditMode -> viewModel.toggleSelection(entry.path)
            entry.isDirectory -> onOpenPath(entry.path)
            else -> viewModel.toggleSelection(entry.path) // ponytail: no file opening yet; tapping selects
        }
    }

    Scaffold(
        topBar = {
            if (state.isEditMode) {
                TopAppBar(
                    title = { Text("${state.selection.size} selected") },
                    navigationIcon = { IconButton(onClick = viewModel::exitEditMode) { Icon(Icons.Rounded.Close, "Close selection") } },
                    actions = { IconButton(onClick = viewModel::selectAll) { Icon(Icons.Rounded.SelectAll, "Select all") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                )
            } else {
                BrowseTopBar(state, onBack, viewModel, onNewFolder = { nameDialog = NameDialogRequest.NewFolder })
            }
        },
        bottomBar = {
            when {
                state.isEditMode -> SelectionBar(
                    count = state.selection.size,
                    onCopy = viewModel::copySelectionToClipboard,
                    onCut = viewModel::cutSelectionToClipboard,
                    onRename = { entries.firstOrNull { it.path in state.selection }?.let { nameDialog = NameDialogRequest.Rename(it) } },
                    onDelete = { confirmDelete = true },
                )
                !clipboard.isEmpty -> ClipboardBar(clipboard, onPaste = viewModel::pasteHere, onCancel = viewModel::cancelClipboard)
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !state.isEditMode && clipboard.isEmpty && !state.volumeUnavailable,
                enter = slideInVertically { it * 2 },
                exit = slideOutVertically { it * 2 },
            ) {
                ExtendedFloatingActionButton(
                    onClick = { nameDialog = NameDialogRequest.NewFolder },
                    icon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                    text = { Text("New folder") },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            BreadcrumbBar(state.volumeDisplayName, state.path, onOpenPath)
            if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            when {
                state.volumeUnavailable -> EmptyState(Icons.Rounded.UsbOff, "This drive isn't connected", "Reconnect it to keep browsing here.")
                state.error != null && entries.isEmpty() -> EmptyState(Icons.Rounded.WarningAmber, "Couldn't load this folder", state.error!!)
                entries.isEmpty() && !state.isLoading -> EmptyState(Icons.Rounded.FolderOpen, "Nothing here yet", "Use New folder, or paste files into this folder.")
                state.viewMode == ViewMode.GRID -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp),
                ) {
                    items(entries, key = { it.path }) { entry ->
                        FileGridTile(entry, entry.path in state.selection, { onEntryClick(entry) }, { viewModel.toggleSelection(entry.path) })
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(entries, key = { it.path }) { entry ->
                        FileListRow(entry, entry.path in state.selection, { onEntryClick(entry) }, { viewModel.toggleSelection(entry.path) })
                    }
                }
            }
        }
    }

    nameDialog?.let { request ->
        NameDialog(
            title = if (request is NameDialogRequest.Rename) "Rename" else "New folder",
            initial = (request as? NameDialogRequest.Rename)?.entry?.name ?: "",
            onDismiss = { nameDialog = null },
            onConfirm = { name ->
                when (request) {
                    NameDialogRequest.NewFolder -> viewModel.createFolder(name)
                    is NameDialogRequest.Rename -> viewModel.rename(request.entry.path, name)
                }
                nameDialog = null
            },
        )
    }
    if (confirmDelete) {
        val count = state.selection.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text("Delete $count item${if (count == 1) "" else "s"}?") },
            text = { Text("They're removed from the drive for good. There's no recycle bin on USB drives.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteSelected() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseTopBar(state: BrowseUiState, onBack: () -> Unit, viewModel: BrowseViewModel, onNewFolder: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(
                state.path.substringAfterLast('/').ifEmpty { state.volumeDisplayName },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        actions = {
            if (state.viewMode == ViewMode.LIST) {
                IconButton(onClick = { viewModel.setViewMode(ViewMode.GRID) }) { Icon(Icons.Rounded.GridView, "Grid view") }
            } else {
                IconButton(onClick = { viewModel.setViewMode(ViewMode.LIST) }) { Icon(Icons.AutoMirrored.Rounded.ViewList, "List view") }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "More options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("New folder") },
                        leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                        onClick = { menuOpen = false; onNewFolder() },
                    )
                    DropdownMenuItem(
                        text = { Text("Select all") },
                        leadingIcon = { Icon(Icons.Rounded.SelectAll, null) },
                        onClick = { menuOpen = false; viewModel.enterEditMode(); viewModel.selectAll() },
                    )
                    HorizontalDivider()
                    Text("Sort by", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    SortField.entries.forEach { field ->
                        val selected = state.sortField == field
                        DropdownMenuItem(
                            text = {
                                val label = field.name.lowercase().replaceFirstChar { it.uppercase() }
                                Text(if (selected) "$label ${if (state.sortAscending) "↑" else "↓"}" else label)
                            },
                            leadingIcon = { if (selected) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                            onClick = { viewModel.setSort(field) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Show hidden files") },
                        leadingIcon = { Checkbox(checked = state.showHidden, onCheckedChange = null) },
                        onClick = { viewModel.setShowHidden(!state.showHidden) },
                    )
                }
            }
        },
    )
}

@Composable
private fun BreadcrumbBar(volumeName: String, path: String, onOpenPath: (String) -> Unit) {
    val crumbs = buildList {
        add(Breadcrumb(volumeName, ""))
        var acc = ""
        path.split('/').filter { it.isNotEmpty() }.forEach { part ->
            acc = if (acc.isEmpty()) part else "$acc/$part"
            add(Breadcrumb(part, acc))
        }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs, key = { _, crumb -> "crumb-${crumb.path}" }) { index, crumb ->
            val isLast = index == crumbs.lastIndex
            AssistChip(
                onClick = { if (!isLast) onOpenPath(crumb.path) },
                label = { Text(crumb.name, maxLines = 1) },
                colors = if (isLast) {
                    AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    AssistChipDefaults.assistChipColors()
                },
                border = null,
            )
            if (!isLast) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onCopy: () -> Unit, onCut: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    BottomAppBar(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        BarAction(Icons.Rounded.ContentCopy, "Copy", onCopy, Modifier.weight(1f))
        BarAction(Icons.Rounded.ContentCut, "Cut", onCut, Modifier.weight(1f))
        BarAction(Icons.Rounded.DriveFileRenameOutline, "Rename", onRename, Modifier.weight(1f), enabled = count == 1)
        BarAction(Icons.Rounded.Delete, "Delete", onDelete, Modifier.weight(1f), enabled = count > 0)
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ClipboardBar(clipboard: ClipboardState, onPaste: () -> Unit, onCancel: () -> Unit) {
    val count = clipboard.items.size
    val isMove = clipboard.mode == ClipboardMode.CUT
    BottomAppBar(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text("$count item${if (count == 1) "" else "s"} ready", style = MaterialTheme.typography.titleSmall)
            Text(
                clipboard.items.joinToString { it.displayName },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
        ExtendedFloatingActionButton(
            onClick = onPaste,
            icon = { Icon(Icons.Rounded.ContentPaste, null) },
            text = { Text(if (isMove) "Move here" else "Copy here") },
            elevation = androidx.compose.material3.FloatingActionButtonDefaults.bottomAppBarFabElevation(),
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(if (initial.isEmpty()) "Folder name" else "Name") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
