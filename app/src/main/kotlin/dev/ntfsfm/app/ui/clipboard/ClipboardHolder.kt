package dev.ntfsfm.app.ui.clipboard

import dev.ntfsfm.core.transfer.ClipboardEntry
import dev.ntfsfm.core.transfer.ClipboardMode
import dev.ntfsfm.core.transfer.ClipboardState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Copy/cut selection, kept across screens so you can paste into another volume. */
@Singleton
class ClipboardHolder @Inject constructor() {
    private val _state = MutableStateFlow(ClipboardState.Empty)
    val state: StateFlow<ClipboardState> = _state.asStateFlow()

    fun set(items: List<ClipboardEntry>, mode: ClipboardMode) {
        _state.value = ClipboardState(items, mode)
    }

    fun clear() {
        _state.value = ClipboardState.Empty
    }
}
