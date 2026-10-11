package dev.ntfsfm.app.ui.browse

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ntfsfm.app.data.ActiveVolumesHolder
import dev.ntfsfm.app.data.AppSettings
import dev.ntfsfm.core.data.dao.RecentLocationDao
import dev.ntfsfm.core.data.entity.RecentLocationEntity
import dev.ntfsfm.app.service.TransferForegroundService
import dev.ntfsfm.app.service.TransferStateHolder
import dev.ntfsfm.app.ui.clipboard.ClipboardHolder
import dev.ntfsfm.app.ui.files.categoryOf
import dev.ntfsfm.app.ui.nav.decodeArg
import dev.ntfsfm.core.transfer.ClipboardEntry
import dev.ntfsfm.core.transfer.ClipboardMode
import dev.ntfsfm.core.transfer.ClipboardState
import dev.ntfsfm.core.transfer.TransferMode
import dev.ntfsfm.core.transfer.TransferProgress
import dev.ntfsfm.core.vfs.NameValidationResult
import dev.ntfsfm.core.vfs.NtfsNameValidator
import dev.ntfsfm.core.vfs.VfsEntry
import dev.ntfsfm.core.vfs.VfsException
import dev.ntfsfm.core.vfs.VirtualFileSystem
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortField { NAME, SIZE, DATE, TYPE }

enum class ViewMode { LIST, GRID }

data class BrowseUiState(
    val volumeId: String = "",
    val path: String = "",
    val volumeDisplayName: String = "",
    val entries: List<VfsEntry> = emptyList(),
    val isLoading: Boolean = false,
    val volumeUnavailable: Boolean = false,
    val error: String? = null,
    val selection: Set<String> = emptySet(),
    val isEditMode: Boolean = false,
    val sortField: SortField = SortField.NAME,
    val sortAscending: Boolean = true,
    val viewMode: ViewMode = ViewMode.LIST,
    val showHidden: Boolean = false,
    /** A one-off message for the snackbar, e.g. a rename that failed. */
    val message: String? = null,
) {
    /** Folders first, then by the chosen field. */
    // Lazy: computed once per state, not on every recomposition while scrolling.
    val sortedEntries: List<VfsEntry> by lazy {
        val visible = if (showHidden) entries else entries.filterNot { it.attributes.hidden }
        val byField: Comparator<VfsEntry> = when (sortField) {
            SortField.NAME -> compareBy { it.name.lowercase(Locale.ROOT) }
            SortField.SIZE -> compareBy<VfsEntry> { it.sizeBytes }.thenBy { it.name.lowercase(Locale.ROOT) }
            SortField.DATE -> compareBy<VfsEntry> { it.timestamps.modifiedAt }.thenBy { it.name.lowercase(Locale.ROOT) }
            SortField.TYPE -> compareBy<VfsEntry> { categoryOf(it.name, it.isDirectory) }
                .thenBy { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        }
        val ordered = if (sortAscending) byField else byField.reversed()
        visible.sortedWith(compareByDescending<VfsEntry> { it.isDirectory }.then(ordered))
    }
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val activeVolumes: ActiveVolumesHolder,
    private val clipboardHolder: ClipboardHolder,
    private val transferState: TransferStateHolder,
    appSettings: AppSettings,
    private val recentLocations: RecentLocationDao,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val volumeId: String = decodeArg(checkNotNull(savedStateHandle.get<String>("volumeId")))
    private val startPath: String = decodeArg(savedStateHandle.get<String>("path") ?: "")

    /** The folder on screen; changes in place as you open folders and go back. */
    private val path: String get() = _uiState.value.path

    // Folders already seen this session: going back shows them instantly, then refreshes.
    private val listingCache = mutableMapOf<String, List<VfsEntry>>()

    /** Scroll position (first item, pixel offset) per folder, so going back lands where you were. */
    val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    private var loadJob: Job? = null

    private val _uiState = MutableStateFlow(
        appSettings.settings.value.let { prefs ->
            BrowseUiState(
                volumeId = volumeId,
                path = startPath,
                showHidden = prefs.showHiddenFiles,
                viewMode = if (prefs.gridViewByDefault) ViewMode.GRID else ViewMode.LIST,
            )
        },
    )
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    val clipboard: StateFlow<ClipboardState> = clipboardHolder.state
    val transferProgress: StateFlow<TransferProgress?> = transferState.progress

    private val vfs: VirtualFileSystem? get() = activeVolumes.get(volumeId)

    init {
        _uiState.update { it.copy(volumeDisplayName = vfs?.identity?.displayName ?: "") }
        load()
    }

    /** Opens [folder] in place (no new screen). */
    fun openFolder(folder: String) {
        if (folder == path) return
        _uiState.update {
            it.copy(path = folder, entries = listingCache[folder].orEmpty(), selection = emptySet(), isEditMode = false, error = null)
        }
        load()
    }

    /** Goes to the parent folder; false at the drive's root, where Back should leave the screen. */
    fun goUp(): Boolean {
        if (path.isEmpty()) return false
        openFolder(path.substringBeforeLast('/', ""))
        return true
    }

    fun load() {
        val fs = vfs ?: run {
            _uiState.update { it.copy(volumeUnavailable = true, isLoading = false) }
            return
        }
        val folder = path
        loadJob?.cancel() // a slow listing of the folder we just left must not land here
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, volumeUnavailable = false) }
            try {
                val entries = mutableListOf<VfsEntry>()
                // Show entries as they arrive; a big folder on a slow stick takes a while.
                fs.listDirectory(folder).collect { entry ->
                    entries += entry
                    if (entries.size % 200 == 0) _uiState.update { it.copy(entries = entries.toList()) }
                }
                listingCache[folder] = entries
                _uiState.update { it.copy(entries = entries.toList(), isLoading = false) }
                rememberVisit()
            } catch (e: VfsException.VolumeUnavailable) {
                _uiState.update { it.copy(volumeUnavailable = true, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Couldn't load this folder", isLoading = false) }
            }
        }
    }

    /** Same field again flips the direction. */
    fun setSort(field: SortField) = _uiState.update {
        if (it.sortField == field) it.copy(sortAscending = !it.sortAscending) else it.copy(sortField = field, sortAscending = true)
    }

    fun setViewMode(mode: ViewMode) = _uiState.update { it.copy(viewMode = mode) }

    fun setShowHidden(show: Boolean) = _uiState.update { it.copy(showHidden = show) }

    fun toggleSelection(entryPath: String) = _uiState.update {
        val selection = if (entryPath in it.selection) it.selection - entryPath else it.selection + entryPath
        it.copy(selection = selection, isEditMode = selection.isNotEmpty())
    }

    fun enterEditMode(initialSelection: String? = null) = _uiState.update {
        it.copy(isEditMode = true, selection = setOfNotNull(initialSelection))
    }

    fun exitEditMode() = _uiState.update { it.copy(isEditMode = false, selection = emptySet()) }

    fun selectAll() = _uiState.update { state -> state.copy(selection = state.sortedEntries.map { it.path }.toSet()) }

    fun copySelectionToClipboard() = putSelectionOnClipboard(ClipboardMode.COPY)

    fun cutSelectionToClipboard() = putSelectionOnClipboard(ClipboardMode.CUT)

    private fun putSelectionOnClipboard(mode: ClipboardMode) {
        val state = _uiState.value
        val items = state.entries.filter { it.path in state.selection }
            .map { ClipboardEntry(volumeId, it.path, it.name, it.isDirectory) }
        if (items.isEmpty()) return
        clipboardHolder.set(items, mode)
        exitEditMode()
    }

    fun pasteHere() {
        val clip = clipboardHolder.state.value
        if (clip.isEmpty) return
        val mode = if (clip.mode == ClipboardMode.CUT) TransferMode.MOVE else TransferMode.COPY
        val intent = TransferForegroundService.buildIntent(
            appContext, clip.items.first().volumeId, clip.items.map { it.path }, volumeId, path, mode,
        )
        appContext.startForegroundService(intent)
        clipboardHolder.clear()
    }

    fun cancelClipboard() = clipboardHolder.clear()

    fun createFolder(name: String) {
        val fs = vfs ?: return
        if (!checkName(name)) return
        viewModelScope.launch {
            runCatching { fs.createDirectory(if (path.isEmpty()) name else "$path/$name") }
                .onFailure { showMessage("Couldn't create the folder: ${it.message}") }
            load()
        }
    }

    fun deleteSelected() {
        val fs = vfs ?: return
        val toDelete = _uiState.value.selection
        viewModelScope.launch {
            val failed = toDelete.count { runCatching { fs.delete(it) }.isFailure }
            if (failed > 0) showMessage("Couldn't delete $failed item" + if (failed == 1) "" else "s")
            exitEditMode()
            load()
        }
    }

    fun rename(entryPath: String, newName: String) {
        val fs = vfs ?: return
        if (!checkName(newName)) return
        viewModelScope.launch {
            runCatching { fs.rename(entryPath, newName) }.onFailure { showMessage("Couldn't rename: ${it.message}") }
            exitEditMode()
            load()
        }
    }

    /** Puts this folder at the top of the home screen's recent list (one row per folder). */
    private suspend fun rememberVisit() = runCatching {
        val name = path.substringAfterLast('/').ifEmpty { _uiState.value.volumeDisplayName }
        recentLocations.deleteLocation(volumeId, path)
        recentLocations.upsert(RecentLocationEntity(volumeId = volumeId, path = path, displayName = name, lastAccessedAt = System.currentTimeMillis()))
        recentLocations.trimTo(30)
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    private fun checkName(name: String): Boolean {
        val result = NtfsNameValidator.validate(name)
        if (result is NameValidationResult.Invalid) showMessage(result.reason)
        return result == NameValidationResult.Valid
    }

    private fun showMessage(text: String) = _uiState.update { it.copy(message = text) }
}
