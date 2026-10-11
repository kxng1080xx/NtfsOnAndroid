package dev.ntfsfm.core.transfer

enum class TransferMode { COPY, MOVE }

enum class ConflictPolicy { ASK, SKIP_ALL, OVERWRITE_ALL, KEEP_BOTH_ALL }

enum class ConflictDecision { SKIP, OVERWRITE, KEEP_BOTH }

data class ConflictAnswer(val decision: ConflictDecision, val applyToAll: Boolean = false)

fun ConflictDecision.toPolicy(): ConflictPolicy = when (this) {
    ConflictDecision.SKIP -> ConflictPolicy.SKIP_ALL
    ConflictDecision.OVERWRITE -> ConflictPolicy.OVERWRITE_ALL
    ConflictDecision.KEEP_BOTH -> ConflictPolicy.KEEP_BOTH_ALL
}

enum class ItemState { PENDING, IN_PROGRESS, DONE, SKIPPED, FAILED, CANCELLED }

data class TransferItemProgress(
    val sourcePath: String,
    val displayName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val state: ItemState,
    val error: String? = null,
)

data class TransferProgress(
    val itemsCompleted: Int,
    val itemsTotal: Int,
    val bytesTransferred: Long,
    val bytesTotal: Long,
    val throughputBytesPerSecond: Long,
    val currentItem: TransferItemProgress?,
    val isDone: Boolean = false,
    val wasCancelled: Boolean = false,
    val fatalError: String? = null,
    val itemsFailed: Int = 0,
)

class TransferCancelledException : Exception("Transfer was cancelled")
