package dev.ntfsfm.core.ntfs

/** One directory entry as libntfs-3g reports it. Built from C (ntfs_bridge.c), so field order matters. */
data class NativeDirEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val readOnly: Boolean,
    val hidden: Boolean,
    val system: Boolean,
    val archive: Boolean,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long,
    val accessedAtMillis: Long,
)

/**
 * JNI surface of libntfsbridge. Handles are native pointers; 0 means failure.
 * Not thread-safe: libntfs-3g has no locking, so callers serialize per volume.
 */
object NtfsNative {
    init {
        System.loadLibrary("ntfsbridge")
    }

    external fun mount(device: NativeBlockDeviceBridge, readOnly: Boolean): Long
    external fun unmount(volumeHandle: Long)
    external fun isVolumeDirty(volumeHandle: Long): Boolean
    external fun isVolumeReadOnly(volumeHandle: Long): Boolean

    external fun listDirectory(volumeHandle: Long, path: String): Array<NativeDirEntry?>
    external fun stat(volumeHandle: Long, path: String): NativeDirEntry?

    external fun openFile(volumeHandle: Long, path: String, forWrite: Boolean): Long
    external fun closeFile(fileHandle: Long)
    external fun readFile(fileHandle: Long, buffer: ByteArray, offset: Int, length: Int, filePosition: Long): Int
    external fun writeFile(fileHandle: Long, buffer: ByteArray, offset: Int, length: Int, filePosition: Long): Int
    external fun truncateFile(fileHandle: Long, sizeBytes: Long): Boolean

    external fun createFile(volumeHandle: Long, path: String): Boolean
    external fun createDirectory(volumeHandle: Long, path: String): Boolean
    external fun delete(volumeHandle: Long, path: String): Boolean
    external fun rename(volumeHandle: Long, oldPath: String, newPath: String): Boolean
    external fun setAttributes(volumeHandle: Long, path: String, readOnly: Boolean, hidden: Boolean, system: Boolean, archive: Boolean): Boolean
    external fun setTimestamps(volumeHandle: Long, path: String, createdAtMillis: Long, modifiedAtMillis: Long): Boolean

    external fun freeSpaceBytes(volumeHandle: Long): Long
    external fun totalSpaceBytes(volumeHandle: Long): Long
}
