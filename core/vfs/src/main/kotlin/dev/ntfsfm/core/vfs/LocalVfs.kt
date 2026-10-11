package dev.ntfsfm.core.vfs

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** A directory on the phone's own storage (needs All-files access) as a volume. */
class LocalVfs(private val root: File, override val identity: VolumeIdentity) : VirtualFileSystem {

    override val isReadOnly: Boolean get() = !root.canWrite()

    override fun listDirectory(path: String): Flow<VfsEntry> = flow {
        val dir = resolve(path)
        val children = dir.listFiles() ?: throw VfsException.NotFound(path)
        for (child in children) emit(toEntry(child, joinPath(path, child.name)))
    }.flowOn(Dispatchers.IO)

    override suspend fun stat(path: String): VfsEntry = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.exists()) throw VfsException.NotFound(path)
        toEntry(file, path)
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        val dir = resolve(path)
        if (dir.exists()) throw VfsException.AlreadyExists(path)
        if (!dir.mkdirs()) throw VfsException.Io("Could not create directory: $path")
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.exists()) throw VfsException.NotFound(path)
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!deleted) throw VfsException.Io("Could not delete: $path")
    }

    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.exists()) throw VfsException.NotFound(path)
        val target = File(file.parentFile, newName)
        if (target.exists()) throw VfsException.AlreadyExists(joinPath(parentOf(path), newName))
        if (!file.renameTo(target)) throw VfsException.Io("Could not rename: $path")
    }

    override suspend fun moveWithin(fromPath: String, toPath: String): Boolean = withContext(Dispatchers.IO) {
        val from = resolve(fromPath)
        if (!from.exists()) return@withContext false
        val to = resolve(toPath)
        if (to.exists()) return@withContext false
        to.parentFile?.mkdirs()
        from.renameTo(to)
    }

    override suspend fun setAttributes(path: String, attributes: VfsAttributes) = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.exists()) throw VfsException.NotFound(path)
        // ponytail: only read-only maps onto a POSIX filesystem; hidden/system/archive are ignored.
        file.setWritable(!attributes.readOnly)
        Unit
    }

    override suspend fun setTimestamps(path: String, timestamps: VfsTimestamps) = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.exists()) throw VfsException.NotFound(path)
        if (!file.setLastModified(timestamps.modifiedAt)) throw VfsException.Io("Could not set modified time: $path")
    }

    override suspend fun openInputStream(path: String): FileInputStream = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.exists()) throw VfsException.NotFound(path)
        FileInputStream(file)
    }

    override suspend fun openOutputStream(path: String): FileOutputStream = withContext(Dispatchers.IO) {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        FileOutputStream(file, false)
    }

    override suspend fun spaceInfo(): VolumeSpace = withContext(Dispatchers.IO) {
        VolumeSpace(root.totalSpace, root.usableSpace)
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) { root.exists() }

    /** Maps a volume path to a file, refusing anything that ".." or a symlink walks out of [root]. */
    fun resolve(path: String): File {
        val normalized = path.trim('/')
        val target = if (normalized.isEmpty()) root else File(root, normalized)
        val canonicalRoot = root.canonicalPath
        val canonicalTarget = target.canonicalPath
        if (canonicalTarget != canonicalRoot && !canonicalTarget.startsWith(canonicalRoot + File.separator)) {
            throw VfsException.Io("Path escapes volume root: $path")
        }
        return target
    }

    private fun toEntry(file: File, path: String): VfsEntry {
        val attrs = runCatching { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java) }.getOrNull()
        return VfsEntry(
            path = path,
            name = file.name,
            isDirectory = file.isDirectory,
            sizeBytes = if (file.isDirectory) 0 else file.length(),
            timestamps = VfsTimestamps(
                createdAt = attrs?.creationTime()?.toMillis(),
                modifiedAt = file.lastModified(),
                accessedAt = attrs?.lastAccessTime()?.toMillis(),
            ),
            attributes = VfsAttributes(readOnly = !file.canWrite(), hidden = file.isHidden),
        )
    }

    private fun joinPath(parent: String, name: String): String {
        val trimmed = parent.trim('/')
        return if (trimmed.isEmpty()) name else "$trimmed/$name"
    }

    private fun parentOf(path: String): String = path.trim('/').substringBeforeLast('/', "")
}
