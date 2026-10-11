package dev.ntfsfm.core.ntfs

import android.util.Log
import dev.ntfsfm.core.block.BlockDevice
import dev.ntfsfm.core.vfs.RandomAccessReader
import dev.ntfsfm.core.vfs.VfsAttributes
import dev.ntfsfm.core.vfs.VfsEntry
import dev.ntfsfm.core.vfs.VfsException
import dev.ntfsfm.core.vfs.VfsTimestamps
import dev.ntfsfm.core.vfs.VirtualFileSystem
import dev.ntfsfm.core.vfs.VolumeIdentity
import dev.ntfsfm.core.vfs.VolumeSpace
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class NtfsMountException(message: String) : Exception(message)

/** An NTFS volume driven by libntfs-3g through [NtfsNative]. */
class NtfsVfs private constructor(
    private val volumeHandle: Long,
    override val identity: VolumeIdentity,
    override val isReadOnly: Boolean,
) : VirtualFileSystem {

    // libntfs-3g has no internal locking; every native call on this volume goes through here.
    // ponytail: one lock per volume, so a big copy and a directory listing take turns.
    private val lock = Any()

    internal fun <T> native(block: () -> T): T = synchronized(lock, block)

    override fun listDirectory(path: String): Flow<VfsEntry> = flow {
        val entries = native { NtfsNative.listDirectory(volumeHandle, path) }
        for (entry in entries) {
            // null: an entry whose inode couldn't be opened (corrupt record); skip it.
            if (entry == null || entry.name == "." || entry.name == "..") continue
            emit(entry.toVfsEntry(joinPath(path, entry.name)))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun stat(path: String): VfsEntry = withContext(Dispatchers.IO) {
        val entry = native { NtfsNative.stat(volumeHandle, path) } ?: throw VfsException.NotFound(path)
        entry.toVfsEntry(path)
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        requireWritable()
        if (!native { NtfsNative.createDirectory(volumeHandle, path) }) {
            throw VfsException.Io("Could not create directory: $path")
        }
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        requireWritable()
        val entry = native { NtfsNative.stat(volumeHandle, path) } ?: throw VfsException.NotFound(path)
        // ntfs_delete refuses non-empty directories; empty them first.
        if (entry.isDirectory) {
            val children = native { NtfsNative.listDirectory(volumeHandle, path) }
            for (child in children) {
                if (child == null || child.name == "." || child.name == "..") continue
                delete(joinPath(path, child.name))
            }
        }
        if (!native { NtfsNative.delete(volumeHandle, path) }) throw VfsException.Io("Could not delete: $path")
    }

    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        requireWritable()
        val parent = path.trim('/').substringBeforeLast('/', "")
        val newPath = if (parent.isEmpty()) newName else "$parent/$newName"
        if (!native { NtfsNative.rename(volumeHandle, path, newPath) }) throw VfsException.Io("Could not rename: $path")
    }

    override suspend fun moveWithin(fromPath: String, toPath: String): Boolean = withContext(Dispatchers.IO) {
        requireWritable()
        native { NtfsNative.rename(volumeHandle, fromPath, toPath) }
    }

    override suspend fun setAttributes(path: String, attributes: VfsAttributes) = withContext(Dispatchers.IO) {
        requireWritable()
        val ok = native {
            NtfsNative.setAttributes(volumeHandle, path, attributes.readOnly, attributes.hidden, attributes.system, attributes.archive)
        }
        if (!ok) throw VfsException.Io("Could not set attributes: $path")
    }

    override suspend fun setTimestamps(path: String, timestamps: VfsTimestamps) = withContext(Dispatchers.IO) {
        requireWritable()
        val created = timestamps.createdAt ?: timestamps.modifiedAt
        if (!native { NtfsNative.setTimestamps(volumeHandle, path, created, timestamps.modifiedAt) }) {
            throw VfsException.Io("Could not set timestamps: $path")
        }
    }

    override suspend fun openInputStream(path: String): InputStream = withContext(Dispatchers.IO) {
        val handle = native { NtfsNative.openFile(volumeHandle, path, false) }
        if (handle == 0L) throw VfsException.NotFound(path)
        NtfsFileInputStream(this@NtfsVfs, handle)
    }

    override suspend fun openOutputStream(path: String): OutputStream = withContext(Dispatchers.IO) {
        requireWritable()
        native {
            val existed = NtfsNative.stat(volumeHandle, path) != null
            if (!existed && !NtfsNative.createFile(volumeHandle, path)) {
                throw VfsException.Io("Could not create file: $path")
            }
            val handle = NtfsNative.openFile(volumeHandle, path, true)
            if (handle == 0L) throw VfsException.Io("Could not open for write: $path")
            if (existed && !NtfsNative.truncateFile(handle, 0)) {
                NtfsNative.closeFile(handle)
                throw VfsException.Io("Could not truncate before overwrite: $path")
            }
            NtfsFileOutputStream(this@NtfsVfs, handle)
        }
    }

    override suspend fun openRandomAccess(path: String): RandomAccessReader = withContext(Dispatchers.IO) {
        val size = stat(path).sizeBytes
        val handle = native { NtfsNative.openFile(volumeHandle, path, false) }
        if (handle == 0L) throw VfsException.NotFound(path)
        NtfsRandomAccessReader(this@NtfsVfs, handle, size)
    }

    override suspend fun spaceInfo(): VolumeSpace = withContext(Dispatchers.IO) {
        native { VolumeSpace(NtfsNative.totalSpaceBytes(volumeHandle), NtfsNative.freeSpaceBytes(volumeHandle)) }
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching { native { NtfsNative.stat(volumeHandle, "") } }.isSuccess
    }

    /** Flushes and releases the volume. The NtfsVfs is unusable afterwards. */
    suspend fun unmount() = withContext(Dispatchers.IO) { native { NtfsNative.unmount(volumeHandle) } }

    private fun requireWritable() {
        if (isReadOnly) throw VfsException.ReadOnly(identity.volumeId)
    }

    private fun joinPath(parent: String, name: String): String {
        val trimmed = parent.trim('/')
        return if (trimmed.isEmpty()) name else "$trimmed/$name"
    }

    private fun NativeDirEntry.toVfsEntry(path: String) = VfsEntry(
        path = path,
        name = name,
        isDirectory = isDirectory,
        sizeBytes = sizeBytes,
        timestamps = VfsTimestamps(createdAtMillis, modifiedAtMillis, accessedAtMillis),
        attributes = VfsAttributes(readOnly, hidden, system, archive),
    )

    companion object {
        suspend fun mount(
            device: BlockDevice,
            volumeId: String,
            displayName: String,
            requestReadOnly: Boolean = false,
        ): NtfsVfs = withContext(Dispatchers.IO) {
            val bridge = NativeBlockDeviceBridge(device)
            Log.i(
                "NtfsVfs",
                "mounting $volumeId: sectorSize=${device.sectorSize} sectorCount=${device.sectorCount} " +
                    "sizeBytes=${device.sizeBytes} requestReadOnly=$requestReadOnly",
            )
            val handle = NtfsNative.mount(bridge, requestReadOnly)
            if (handle == 0L) {
                throw NtfsMountException(
                    "This NTFS drive couldn't be opened. It may be damaged, or Windows may have left it locked -- " +
                        "on Windows, eject the drive safely, or turn off Fast startup, then try again.",
                )
            }
            val effectiveReadOnly = NtfsNative.isVolumeReadOnly(handle)
            NtfsVfs(handle, VolumeIdentity(volumeId, displayName), effectiveReadOnly)
        }
    }
}

internal class NtfsFileInputStream(private val vfs: NtfsVfs, private val handle: Long) : InputStream() {
    private var position = 0L
    private var closed = false

    override fun read(): Int {
        val buf = ByteArray(1)
        return if (read(buf, 0, 1) <= 0) -1 else buf[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        check(!closed) { "stream is closed" }
        if (len == 0) return 0
        val n = vfs.native { NtfsNative.readFile(handle, b, off, len, position) }
        if (n <= 0) return -1
        position += n
        return n
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        position += n
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        vfs.native { NtfsNative.closeFile(handle) }
    }
}

internal class NtfsFileOutputStream(private val vfs: NtfsVfs, private val handle: Long) : OutputStream() {
    private var position = 0L
    private var closed = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!closed) { "stream is closed" }
        var written = 0
        while (written < len) {
            val n = vfs.native { NtfsNative.writeFile(handle, b, off + written, len - written, position) }
            if (n <= 0) throw VfsException.Io("Native write failed at offset $position")
            position += n
            written += n
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        vfs.native { NtfsNative.closeFile(handle) }
    }
}

internal class NtfsRandomAccessReader(
    private val vfs: NtfsVfs,
    private val handle: Long,
    override val size: Long,
) : RandomAccessReader {
    private var closed = false

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "reader is closed" }
        if (position >= size || length == 0) return 0
        val n = vfs.native { NtfsNative.readFile(handle, buffer, offset, length, position) }
        return if (n < 0) throw VfsException.Io("Native read failed at offset $position") else n
    }

    override fun close() {
        if (closed) return
        closed = true
        vfs.native { NtfsNative.closeFile(handle) }
    }
}
