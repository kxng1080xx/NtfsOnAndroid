package dev.ntfsfm.app.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import dev.ntfsfm.app.files.openInOtherApp
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import dev.ntfsfm.app.formatBytes
import dev.ntfsfm.app.ui.files.FileCategory
import dev.ntfsfm.app.ui.files.FileTypeBadge
import dev.ntfsfm.app.ui.files.visualFor
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ntfsfm.app.ui.design.EmptyState
import dev.ntfsfm.app.ui.design.RoundIconButton
import dev.ntfsfm.app.ui.design.FloatingTopBar
import dev.ntfsfm.app.ui.design.LocalBottomOverlay
import dev.ntfsfm.app.ui.design.bottomClearance
import dev.ntfsfm.app.ui.design.card
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

private enum class BottomMode { ACTIONS, SELECTION, CLIPBOARD }

@Composable
fun BrowseScreen(
    onBack: () -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboard.collectAsStateWithLifecycle()
    val transfer by viewModel.transferProgress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openFile: (VfsEntry, Boolean) -> Unit = { entry, chooser ->
        if (!openInOtherApp(context, state.volumeId, entry.path, entry.name, chooser)) {
            scope.launch { snackbar.showSnackbar("No app on this phone can open ${entry.name}") }
        }
    }
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
    // Back closes the selection, then walks up the folder tree, then leaves the drive.
    BackHandler(enabled = state.isEditMode || state.path.isNotEmpty()) {
        if (state.isEditMode) viewModel.exitEditMode() else viewModel.goUp()
    }
    val onOpenPath: (String) -> Unit = viewModel::openFolder
    val onUp: () -> Unit = { if (!viewModel.goUp()) onBack() }

    val entries = state.sortedEntries
    val onEntryClick: (VfsEntry) -> Unit = { entry ->
        when {
            state.isEditMode -> viewModel.toggleSelection(entry.path)
            entry.isDirectory -> onOpenPath(entry.path)
            else -> openFile(entry, false)
        }
    }
    val bottomMode = when {
        state.isEditMode -> BottomMode.SELECTION
        !clipboard.isEmpty -> BottomMode.CLIPBOARD
        else -> BottomMode.ACTIONS
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.isEditMode) {
                FloatingTopBar(
                    title = "${state.selection.size} selected",
                    navigation = { RoundIconButton(Icons.Rounded.Close, "Close selection", viewModel::exitEditMode) },
                    actions = { RoundIconButton(Icons.Rounded.SelectAll, "Select all", viewModel::selectAll) },
                )
            } else {
                BrowseTopBar(onUp, viewModel, state, onNewFolder = { nameDialog = NameDialogRequest.NewFolder })
                FolderHeader(state, onOpenPath)
            }
            SortRow(state, viewModel)
            Box(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 28.dp)) {
                if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = Color.Transparent)
            }
            // Folders slide in place: deeper slides in from the right, going up from the left.
            AnimatedContent(
                targetState = state,
                contentKey = { it.path },
                transitionSpec = {
                    val deeper = targetState.path.length > initialState.path.length
                    val dir = if (deeper) 1 else -1
                    (slideInHorizontally(tween(260)) { w -> dir * w / 4 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(260)) { w -> -dir * w / 4 } + fadeOut(tween(160)))
                },
                label = "folder",
                modifier = Modifier.weight(1f),
            ) { folder ->
                FolderContent(folder, viewModel, onEntryClick, openFile, onRename = { nameDialog = NameDialogRequest.Rename(it) }, onDelete = { confirmDelete = true })
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = LocalBottomOverlay.current + 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbar)
            if (!state.volumeUnavailable) {
                AnimatedContent(
                    bottomMode,
                    transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { it / 2 }) },
                    label = "bottom-bar",
                ) { mode ->
                    when (mode) {
                        BottomMode.SELECTION -> SelectionBar(
                            count = state.selection.size,
                            onCopy = viewModel::copySelectionToClipboard,
                            onCut = viewModel::cutSelectionToClipboard,
                            onRename = { entries.firstOrNull { it.path in state.selection }?.let { nameDialog = NameDialogRequest.Rename(it) } },
                            onDelete = { confirmDelete = true },
                        )
                        BottomMode.CLIPBOARD -> ClipboardBar(clipboard, onPaste = viewModel::pasteHere, onCancel = viewModel::cancelClipboard)
                        BottomMode.ACTIONS -> PillButton(Icons.Rounded.CreateNewFolder, "New folder") { nameDialog = NameDialogRequest.NewFolder }
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
            icon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
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

@Composable
private fun FolderContent(
    folder: BrowseUiState,
    viewModel: BrowseViewModel,
    onEntryClick: (VfsEntry) -> Unit,
    openFile: (VfsEntry, Boolean) -> Unit,
    onRename: (VfsEntry) -> Unit,
    onDelete: () -> Unit,
) {
    val entries = folder.sortedEntries
    val saved = viewModel.scrollPositions[folder.path] ?: (0 to 0)
    when {
        folder.volumeUnavailable -> EmptyState(Icons.Rounded.UsbOff, "This drive isn't connected", "Reconnect it to keep browsing here.")
        folder.error != null && entries.isEmpty() -> EmptyState(Icons.Rounded.WarningAmber, "Couldn't load this folder", folder.error)
        entries.isEmpty() && !folder.isLoading -> EmptyState(Icons.Rounded.FolderOpen, "Nothing here yet", "Create a folder, or paste files into this one.")
        entries.isEmpty() -> Box(Modifier.fillMaxSize())
        folder.viewMode == ViewMode.GRID -> {
            val gridState = rememberLazyGridState(saved.first, saved.second)
            DisposableEffect(folder.path) {
                onDispose { viewModel.scrollPositions[folder.path] = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                state = gridState,
                contentPadding = PaddingValues(start = 11.dp, end = 11.dp, top = 6.dp, bottom = bottomClearance(96.dp)),
            ) {
                items(entries, key = { it.path }) { entry ->
                    FileGridTile(entry, entry.path in folder.selection, { onEntryClick(entry) }, { viewModel.toggleSelection(entry.path) })
                }
            }
        }
        else -> {
            val listState = rememberLazyListState(saved.first, saved.second)
            DisposableEffect(folder.path) {
                onDispose { viewModel.scrollPositions[folder.path] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            }
            LazyColumn(
                Modifier.padding(horizontal = 12.dp).card(MaterialTheme.shapes.extraLarge),
                state = listState,
                contentPadding = PaddingValues(top = 6.dp, bottom = bottomClearance(96.dp)),
            ) {
                items(entries, key = { it.path }) { entry ->
                    FileListRow(
                        entry,
                        entry.path in folder.selection,
                        { onEntryClick(entry) },
                        { viewModel.toggleSelection(entry.path) },
                        trailing = {
                            if (!folder.isEditMode) {
                                EntryMenu(
                                    onOpenWith = if (entry.isDirectory) null else ({ openFile(entry, true) }),
                                    onCopy = { viewModel.enterEditMode(entry.path); viewModel.copySelectionToClipboard() },
                                    onCut = { viewModel.enterEditMode(entry.path); viewModel.cutSelectionToClipboard() },
                                    onRename = { onRename(entry) },
                                    onDelete = { viewModel.enterEditMode(entry.path); onDelete() },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowseTopBar(onBack: () -> Unit, viewModel: BrowseViewModel, state: BrowseUiState, onNewFolder: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        Spacer(Modifier.weight(1f))
        Box {
            RoundIconButton(Icons.Rounded.MoreVert, "More options", { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, shape = MaterialTheme.shapes.large) {
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
                DropdownMenuItem(
                    text = { Text("Hidden files") },
                    leadingIcon = { Icon(Icons.Rounded.Visibility, null) },
                    trailingIcon = { Switch(checked = state.showHidden, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp)) },
                    onClick = { viewModel.setShowHidden(!state.showHidden) },
                )
            }
        }
    }
}

/** Big folder icon, name, item count and size, and a tappable path, as in a detail page. */
@Composable
private fun FolderHeader(state: BrowseUiState, onOpenPath: (String) -> Unit) {
    val name = state.path.substringAfterLast('/').ifEmpty { state.volumeDisplayName }
    val files = state.entries.filterNot { it.isDirectory }
    val count = state.entries.size
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FileTypeBadge(visualFor(FileCategory.FOLDER), size = 72.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "$count item${if (count == 1) "" else "s"}" + if (files.isNotEmpty()) " · ${formatBytes(files.sumOf { it.sizeBytes })}" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    BreadcrumbBar(state.volumeDisplayName, state.path, onOpenPath)
}

@Composable
private fun SortRow(state: BrowseUiState, viewModel: BrowseViewModel) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(
                Modifier.clip(CircleShape).clickable { sortMenu = true }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.sortField.label(), style = MaterialTheme.typography.labelLarge)
                Icon(
                    if (state.sortAscending) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                    "Sort direction",
                    Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }, shape = MaterialTheme.shapes.large) {
                SortField.entries.forEach { field ->
                    DropdownMenuItem(
                        text = { Text(field.label()) },
                        leadingIcon = { if (state.sortField == field) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                        onClick = { viewModel.setSort(field); sortMenu = false },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.card(CircleShape, elevation = 1.3f).padding(3.dp)) {
            ViewToggle(Icons.AutoMirrored.Rounded.ViewList, "List view", state.viewMode == ViewMode.LIST) { viewModel.setViewMode(ViewMode.LIST) }
            ViewToggle(Icons.Rounded.GridView, "Grid view", state.viewMode == ViewMode.GRID) { viewModel.setViewMode(ViewMode.GRID) }
        }
    }
}

private fun SortField.label() = name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun ViewToggle(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 40.dp, height = 32.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(18.dp), tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EntryMenu(onOpenWith: (() -> Unit)?, onCopy: () -> Unit, onCut: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "Item options", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large) {
            if (onOpenWith != null) {
                DropdownMenuItem(text = { Text("Open with…") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) }, onClick = { open = false; onOpenWith() })
            }
            DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { open = false; onCopy() })
            DropdownMenuItem(text = { Text("Move") }, leadingIcon = { Icon(Icons.Rounded.ContentCut, null) }, onClick = { open = false; onCut() })
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) }, onClick = { open = false; onRename() })
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDelete() },
            )
        }
    }
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
    if (crumbs.size < 2) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs, key = { _, crumb -> "crumb-${crumb.path}" }) { index, crumb ->
            val isLast = index == crumbs.lastIndex
            Text(
                crumb.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(CircleShape).clickable(enabled = !isLast) { onOpenPath(crumb.path) }.padding(horizontal = 6.dp, vertical = 6.dp),
            )
            if (!isLast) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(14.dp))
        }
    }
}

/** A floating solid pill button with an icon and label, e.g. "New folder". */
@Composable
private fun PillButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .shadow(10.dp, CircleShape, spotColor = MaterialTheme.colorScheme.primary)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun SelectionBar(count: Int, onCopy: () -> Unit, onCut: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .card(CircleShape, elevation = 1.8f)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BarAction(Icons.Rounded.ContentCopy, "Copy", onCopy)
        BarAction(Icons.Rounded.ContentCut, "Cut", onCut)
        BarAction(Icons.Rounded.DriveFileRenameOutline, "Rename", onRename, enabled = count == 1)
        BarAction(Icons.Rounded.Delete, "Delete", onDelete, enabled = count > 0, tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurface) {
    val color = if (enabled) tint else tint.copy(alpha = 0.35f)
    Column(
        Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ClipboardBar(clipboard: ClipboardState, onPaste: () -> Unit, onCancel: () -> Unit) {
    val count = clipboard.items.size
    val isMove = clipboard.mode == ClipboardMode.CUT
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .card(CircleShape, elevation = 1.8f)
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("$count item${if (count == 1) "" else "s"} to ${if (isMove) "move" else "copy"}", style = MaterialTheme.typography.titleSmall)
            Text(clipboard.items.joinToString { it.displayName }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
        Row(
            Modifier
                .card(CircleShape, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), outline = false)
                .clickable(onClick = onPaste)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ContentPaste, null, tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(8.dp))
            Text(if (isMove) "Move here" else "Paste here", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
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
                shape = MaterialTheme.shapes.medium,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
