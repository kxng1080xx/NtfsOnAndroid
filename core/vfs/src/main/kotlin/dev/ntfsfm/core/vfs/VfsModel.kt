package dev.ntfsfm.core.vfs

/** Stable id (survives replug for the same volume) plus what to call it in the UI. */
data class VolumeIdentity(val volumeId: String, val displayName: String)

data class VolumeSpace(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = totalBytes - freeBytes
}

data class VfsTimestamps(val createdAt: Long?, val modifiedAt: Long, val accessedAt: Long?)

data class VfsAttributes(
    val readOnly: Boolean = false,
    val hidden: Boolean = false,
    val system: Boolean = false,
    val archive: Boolean = false,
)

data class VfsEntry(
    /** Volume-relative path, no leading slash; "" is the root. */
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val timestamps: VfsTimestamps,
    val attributes: VfsAttributes,
    val mimeType: String? = null,
)

sealed class VfsException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotFound(path: String) : VfsException("Not found: $path")
    class AlreadyExists(path: String) : VfsException("Already exists: $path")
    class ReadOnly(path: String) : VfsException("Read-only: $path")
    class VolumeUnavailable(volumeId: String) : VfsException("Volume unavailable (disconnected): $volumeId")
    class NameNotSupported(name: String, reason: String) : VfsException("'$name' is not a valid name here: $reason")
    class Io(message: String, cause: Throwable? = null) : VfsException(message, cause)
}
