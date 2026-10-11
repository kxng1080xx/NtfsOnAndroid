package dev.ntfsfm.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.ntfsfm.app.data.ActiveVolumesHolder
import dev.ntfsfm.app.ui.files.HomeCategory
import dev.ntfsfm.core.vfs.FileSearch
import dev.ntfsfm.core.vfs.VfsEntry
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchResult(val entry: VfsEntry, val volumeId: String, val volumeName: String)

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val category: HomeCategory? = null,
)

/** Searches every mounted volume at once; results stream in as they're found. */
@HiltViewModel
class SearchViewModel @Inject constructor(private val activeVolumes: ActiveVolumesHolder) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()
    private var searchJob: Job? = null

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        if (query.isBlank() && _uiState.value.category == null) {
            searchJob?.cancel()
            _uiState.update { it.copy(results = emptyList(), isSearching = false, hasSearched = false) }
            return
        }
        runSearch(query, _uiState.value.category)
    }

    /** null = all types; keeps the typed query. */
    fun setCategory(category: HomeCategory?) {
        _uiState.update { it.copy(category = category) }
        val query = _uiState.value.query
        if (category == null && query.isBlank()) {
            searchJob?.cancel()
            _uiState.update { it.copy(results = emptyList(), isSearching = false, hasSearched = false) }
        } else {
            runSearch(query, category)
        }
    }

    fun clear() {
        searchJob?.cancel()
        _uiState.value = SearchUiState()
    }

    private fun runSearch(query: String, category: HomeCategory?) {
        searchJob?.cancel()
        _uiState.update { it.copy(results = emptyList(), isSearching = true, hasSearched = true) }
        searchJob = viewModelScope.launch {
            for ((volumeId, vfs) in activeVolumes.all()) {
                val volumeName = vfs.identity.displayName
                // With a category the query narrows it by name; otherwise the name alone decides.
                val predicate: ((VfsEntry) -> Boolean)? = category?.let { cat ->
                    { entry ->
                        !entry.isDirectory && cat.matches(entry.name, entry.path) &&
                            (query.isBlank() || entry.name.contains(query.trim(), ignoreCase = true))
                    }
                }
                FileSearch(vfs).search(query, predicate = predicate, maxDepth = 12).collect { entry ->
                    _uiState.update { it.copy(results = it.results + SearchResult(entry, volumeId, volumeName)) }
                }
            }
            _uiState.update { it.copy(isSearching = false) }
        }
    }
}
