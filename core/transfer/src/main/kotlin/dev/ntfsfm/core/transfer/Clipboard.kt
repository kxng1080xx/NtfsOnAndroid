package dev.ntfsfm.core.transfer

enum class ClipboardMode { COPY, CUT }

data class ClipboardEntry(val volumeId: String, val path: String, val displayName: String, val isDirectory: Boolean)

data class ClipboardState(val items: List<ClipboardEntry>, val mode: ClipboardMode) {
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        val Empty = ClipboardState(emptyList(), ClipboardMode.COPY)
    }
}
