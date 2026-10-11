package dev.ntfsfm.core.vfs

import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.flow.Flow

/**
 * One mounted volume (phone storage, a USB partition, a network share). Paths are
 * volume-relative with '/' separators and no leading slash; "" is the root.
 */
interface VirtualFileSystem {
    val identity: VolumeIdentity
    val isReadOnly: Boolean

    fun listDirectory(path: String): Flow<VfsEntry>

    suspend fun stat(path: String): VfsEntry

    suspend fun createDirectory(path: String)

    /** Deletes a file, or a directory with everything in it. */
    suspend fun delete(path: String)

    /** Renames in place; [newName] is a bare name, not a path. */
    suspend fun rename(path: String, newName: String)

    /** Cheap same-volume move. Returns false when the volume can't do it, so the caller copies + deletes. */
    suspend fun moveWithin(fromPath: String, toPath: String): Boolean = false

    suspend fun setAttributes(path: String, attributes: VfsAttributes)

    suspend fun setTimestamps(path: String, timestamps: VfsTimestamps)

    suspend fun openInputStream(path: String): InputStream

    /** Creates or truncates. */
    suspend fun openOutputStream(path: String): OutputStream

    /** Seekable reads; volumes with native positional I/O override this. */
    suspend fun openRandomAccess(path: String): RandomAccessReader =
        StreamRandomAccessReader(this, path, stat(path).sizeBytes)

    suspend fun spaceInfo(): VolumeSpace

    suspend fun isAvailable(): Boolean
}

sealed class NameValidationResult {
    data object Valid : NameValidationResult()
    data class Invalid(val reason: String, val suggestion: String) : NameValidationResult()
}

interface NameValidator {
    fun validate(name: String): NameValidationResult
    fun sanitize(name: String): String
}

/** Win32 naming rules, which NTFS and FAT volumes are held to so Windows can still open them. */
object NtfsNameValidator : NameValidator {
    private val illegalChars = charArrayOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
    private val reservedNames = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    private fun Char.isIllegal() = this in illegalChars || this < ' '

    override fun sanitize(name: String): String {
        val replaced = name.map { if (it.isIllegal()) '_' else it }.joinToString("").trimEnd('.', ' ')
        return replaced.ifBlank { "_" }
    }

    override fun validate(name: String): NameValidationResult {
        if (name.isBlank()) return NameValidationResult.Invalid("Name is empty", sanitize(name))
        name.firstOrNull { it.isIllegal() }?.let {
            return NameValidationResult.Invalid("'$it' is not allowed in NTFS names", sanitize(name))
        }
        if (name.trimEnd() != name || name.trimEnd('.') != name) {
            return NameValidationResult.Invalid("Names can't end with a space or period", sanitize(name))
        }
        val baseName = name.substringBefore('.').uppercase(Locale.ROOT)
        if (baseName in reservedNames) {
            return NameValidationResult.Invalid("'$baseName' is a reserved device name on NTFS", sanitize(name))
        }
        return NameValidationResult.Valid
    }
}
