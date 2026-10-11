package dev.ntfsfm.core.fat32

import dev.ntfsfm.core.block.BlockDevice
import dev.ntfsfm.core.block.BlockDeviceException
import dev.ntfsfm.core.vfs.NameValidationResult
import dev.ntfsfm.core.vfs.NtfsNameValidator
import dev.ntfsfm.core.vfs.VfsAttributes
import dev.ntfsfm.core.vfs.VfsEntry
import dev.ntfsfm.core.vfs.VfsException
import dev.ntfsfm.core.vfs.VfsTimestamps
import dev.ntfsfm.core.vfs.VirtualFileSystem
import dev.ntfsfm.core.vfs.VolumeIdentity
import dev.ntfsfm.core.vfs.VolumeSpace
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A FAT32 volume, implemented in Kotlin on top of [Fat32Volume]. Names match case-insensitively, like Windows. */
class Fat32Vfs(
    private val volume: Fat32Volume,
    override val identity: VolumeIdentity,
    override val isReadOnly: Boolean,
) : VirtualFileSystem {

    // Directory edits are read-modify-write of the whole directory; two at once would lose one.
    // ponytail: one lock per volume, not per directory.
    private val directoryLock = Mutex()

    override fun listDirectory(path: String): Flow<VfsEntry> = flow {
        val cluster = clusterForDirectory(path)
        for (entry in listEntries(cluster)) {
            if (entry.shortName == "." || entry.shortName == "..") continue
            emit(entry.toVfsEntry(joinPath(path, entry.displayName)))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun stat(path: String): VfsEntry = withContext(Dispatchers.IO) {
        if (isRoot(path)) rootEntry() else resolveEntry(path).second.toVfsEntry(path)
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        requireWritable()
        val (parentCluster, name) = splitParentCluster(path)
        if (listEntries(parentCluster).any { it.displayName.equals(name, ignoreCase = true) }) {
            throw VfsException.AlreadyExists(path)
        }
        val newCluster = volume.allocateChain(1).first()
        // ".." of a directory directly under the root points at cluster 0, per the spec.
        val dotDotTarget = if (parentCluster == volume.rootCluster) 0L else parentCluster
        val dirContent = ByteArray(volume.bytesPerCluster)
        Fat32DirEntryCodec.encode(null, ".", ATTR_DIRECTORY, newCluster, 0, now()).single().copyInto(dirContent, 0)
        Fat32DirEntryCodec.encode(null, "..", ATTR_DIRECTORY, dotDotTarget, 0, now()).single().copyInto(dirContent, ENTRY_SIZE)
        volume.writeCluster(newCluster, dirContent)
        insertEntry(parentCluster, name, ATTR_DIRECTORY, newCluster, 0)
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        requireWritable()
        deleteRecursive(path)
    }

    private suspend fun deleteRecursive(path: String) {
        val (parentCluster, entry) = resolveEntry(path)
        if (entry.isDirectory) {
            for (child in listEntries(entry.firstCluster)) {
                if (child.shortName == "." || child.shortName == "..") continue
                deleteRecursive(joinPath(path, child.displayName))
            }
        }
        if (entry.firstCluster >= 2) volume.freeChain(entry.firstCluster)
        removeEntrySlots(parentCluster, entry.entryByteOffsets)
    }

    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        requireWritable()
        val validation = NtfsNameValidator.validate(newName)
        if (validation is NameValidationResult.Invalid) throw VfsException.NameNotSupported(newName, validation.reason)
        val (parentCluster, entry) = resolveEntry(path)
        if (listEntries(parentCluster).any { it != entry && it.displayName.equals(newName, ignoreCase = true) }) {
            throw VfsException.AlreadyExists(joinPath(parentOf(path), newName))
        }
        removeEntrySlots(parentCluster, entry.entryByteOffsets)
        insertEntry(parentCluster, newName, entry.attributes, entry.firstCluster, entry.fileSize, entry.modifiedAt)
    }

    override suspend fun setAttributes(path: String, attributes: VfsAttributes) = withContext(Dispatchers.IO) {
        requireWritable()
        val (parentCluster, entry) = resolveEntry(path)
        var attr = entry.attributes and ATTR_DIRECTORY
        if (attributes.readOnly) attr = attr or ATTR_READ_ONLY
        if (attributes.hidden) attr = attr or ATTR_HIDDEN
        if (attributes.system) attr = attr or ATTR_SYSTEM
        if (attributes.archive) attr = attr or ATTR_ARCHIVE
        patchShortEntry(parentCluster, entry) { it[11] = attr.toByte() }
    }

    override suspend fun setTimestamps(path: String, timestamps: VfsTimestamps) = withContext(Dispatchers.IO) {
        requireWritable()
        val (parentCluster, entry) = resolveEntry(path)
        patchShortEntry(parentCluster, entry) { slot ->
            timestamps.createdAt?.let { Fat32DirEntryCodec.encodeFatDateTime(it) }?.let { (date, time) ->
                slot.writeU16le(14, time)
                slot.writeU16le(16, date)
            }
            timestamps.accessedAt?.let { slot.writeU16le(18, Fat32DirEntryCodec.encodeFatDateTime(it).first) }
            val (date, time) = Fat32DirEntryCodec.encodeFatDateTime(timestamps.modifiedAt)
            slot.writeU16le(22, time)
            slot.writeU16le(24, date)
        }
    }

    override suspend fun openInputStream(path: String): InputStream = withContext(Dispatchers.IO) {
        val entry = resolveEntry(path).second
        if (entry.isDirectory) throw VfsException.Io("Cannot open a directory as a stream: $path")
        Fat32ClusterInputStream(volume, entry.firstCluster, entry.fileSize)
    }

    /** Truncate = free the old chain and drop the old entry; the new entry appears on close. */
    override suspend fun openOutputStream(path: String): OutputStream = withContext(Dispatchers.IO) {
        requireWritable()
        val (parentCluster, name) = splitParentCluster(path)
        val existing = listEntries(parentCluster).firstOrNull { it.displayName.equals(name, ignoreCase = true) }
        if (existing != null) {
            if (existing.isDirectory) throw VfsException.Io("Cannot write to a directory: $path")
            if (existing.firstCluster >= 2) volume.freeChain(existing.firstCluster)
            removeEntrySlots(parentCluster, existing.entryByteOffsets)
        }
        Fat32ClusterOutputStream(volume) { firstCluster, size ->
            insertEntry(parentCluster, name, ATTR_ARCHIVE, firstCluster, size)
        }
    }

    override suspend fun spaceInfo(): VolumeSpace = withContext(Dispatchers.IO) {
        VolumeSpace(totalBytes = volume.totalBytes, freeBytes = volume.freeSpaceBytes())
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            volume.readFatEntry(volume.rootCluster)
            true
        } catch (e: BlockDeviceException) {
            false
        } catch (e: VfsException) {
            false
        }
    }

    private fun requireWritable() {
        if (isReadOnly) throw VfsException.ReadOnly(identity.volumeId)
    }

    private suspend fun clusterForDirectory(path: String): Long {
        if (isRoot(path)) return volume.rootCluster
        val entry = resolveEntry(path).second
        if (!entry.isDirectory) throw VfsException.Io("Not a directory: $path")
        return entry.firstCluster
    }

    private suspend fun listEntries(startCluster: Long): List<Fat32DirEntry> =
        Fat32DirEntryCodec.parseAll(readDirectoryBytes(startCluster).first)

    /** The whole directory as one array, plus the clusters it came from. */
    private suspend fun readDirectoryBytes(startCluster: Long): Pair<ByteArray, List<Long>> {
        val clusters = volume.clusterChain(startCluster)
        val bytes = ByteArray(clusters.size * volume.bytesPerCluster)
        clusters.forEachIndexed { i, cluster -> volume.readCluster(cluster).copyInto(bytes, i * volume.bytesPerCluster) }
        return bytes to clusters
    }

    private suspend fun writeDirectoryBytes(clusters: List<Long>, bytes: ByteArray) {
        clusters.forEachIndexed { i, cluster ->
            volume.writeCluster(cluster, bytes.copyOfRange(i * volume.bytesPerCluster, (i + 1) * volume.bytesPerCluster))
        }
    }

    /** (cluster of the containing directory, the entry) for a non-root path. */
    private suspend fun resolveEntry(path: String): Pair<Long, Fat32DirEntry> {
        val components = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (components.isEmpty()) throw VfsException.Io("Cannot resolve the volume root as an entry")
        var currentCluster = volume.rootCluster
        var parentOfMatch = currentCluster
        var match: Fat32DirEntry? = null
        components.forEachIndexed { index, name ->
            val found = listEntries(currentCluster).firstOrNull { it.displayName.equals(name, ignoreCase = true) }
                ?: throw VfsException.NotFound(path)
            parentOfMatch = currentCluster
            match = found
            if (index != components.lastIndex) {
                if (!found.isDirectory) throw VfsException.NotFound(path)
                currentCluster = found.firstCluster
            }
        }
        return parentOfMatch to match!!
    }

    /** (cluster of the parent directory, the last path component). */
    private suspend fun splitParentCluster(path: String): Pair<Long, String> {
        val trimmed = path.trim('/')
        val name = trimmed.substringAfterLast('/')
        val parentPath = trimmed.substringBeforeLast('/', "")
        if (parentPath.isEmpty()) return volume.rootCluster to name
        val entry = resolveEntry(parentPath).second
        if (!entry.isDirectory) throw VfsException.NotFound(parentPath)
        return entry.firstCluster to name
    }

    private suspend fun insertEntry(parentCluster: Long, name: String, attributes: Int, firstCluster: Long, size: Long, modifiedAt: Long = now()) =
        directoryLock.withLock {
            val (bytes, clusters) = readDirectoryBytes(parentCluster)
            val existingShortNames = Fat32DirEntryCodec.parseAll(bytes).map { it.shortName.uppercase(Locale.ROOT) }.toSet()
            val shortName = if (Fat32ShortName.fitsExactly(name)) name else Fat32ShortName.generate(name, existingShortNames)
            val longName = if (shortName == name) null else name
            val encoded = Fat32DirEntryCodec.encode(longName, shortName, attributes, firstCluster, size, modifiedAt)
            val (newBytes, newClusters, offset) = allocateSlots(bytes, clusters, encoded.size)
            encoded.forEachIndexed { i, entryBytes -> entryBytes.copyInto(newBytes, offset + i * ENTRY_SIZE) }
            writeDirectoryBytes(newClusters, newBytes)
        }

    private suspend fun removeEntrySlots(parentCluster: Long, byteOffsets: List<Int>) = directoryLock.withLock {
        val (bytes, clusters) = readDirectoryBytes(parentCluster)
        for (offset in byteOffsets) Fat32DirEntryCodec.deletedMarkerEntry().copyInto(bytes, offset)
        writeDirectoryBytes(clusters, bytes)
    }

    private suspend fun patchShortEntry(parentCluster: Long, entry: Fat32DirEntry, mutate: (ByteArray) -> Unit) =
        directoryLock.withLock {
            val (bytes, clusters) = readDirectoryBytes(parentCluster)
            val offset = entry.entryByteOffsets.last()
            val slot = bytes.copyOfRange(offset, offset + ENTRY_SIZE)
            mutate(slot)
            slot.copyInto(bytes, offset)
            writeDirectoryBytes(clusters, bytes)
        }

    /**
     * Finds [slotsNeeded] consecutive free slots (deleted or past the end marker), growing the
     * directory a zeroed cluster at a time when there is no such run. Returns (bytes, clusters, offset).
     */
    private suspend fun allocateSlots(bytes: ByteArray, clusters: List<Long>, slotsNeeded: Int): Triple<ByteArray, List<Long>, Int> {
        var runStart = -1
        var runLength = 0
        var offset = 0
        while (offset + ENTRY_SIZE <= bytes.size) {
            val first = bytes[offset].toInt() and 0xFF
            if (first == 0x00 || first == 0xE5) {
                if (runStart == -1) runStart = offset
                if (++runLength >= slotsNeeded) return Triple(bytes, clusters, runStart)
            } else {
                runStart = -1
                runLength = 0
            }
            offset += ENTRY_SIZE
        }
        // The trailing free run (if any) continues into the new clusters.
        val slotsPerCluster = volume.bytesPerCluster / ENTRY_SIZE
        val clustersNeeded = (slotsNeeded - runLength + slotsPerCluster - 1) / slotsPerCluster
        val newClusters = volume.extendChain(clusters.last(), clustersNeeded)
        val grownBytes = bytes + ByteArray(clustersNeeded * volume.bytesPerCluster)
        return Triple(grownBytes, clusters + newClusters, if (runStart == -1) bytes.size else runStart)
    }

    private fun isRoot(path: String) = path.trim('/').isEmpty()

    private fun rootEntry() = VfsEntry(
        path = "",
        name = identity.displayName,
        isDirectory = true,
        sizeBytes = 0,
        timestamps = VfsTimestamps(null, 0, null),
        attributes = VfsAttributes(),
    )

    private fun joinPath(parent: String, name: String): String {
        val trimmed = parent.trim('/')
        return if (trimmed.isEmpty()) name else "$trimmed/$name"
    }

    private fun parentOf(path: String) = path.trim('/').substringBeforeLast('/', "")

    private fun Fat32DirEntry.toVfsEntry(path: String) =
        VfsEntry(path, displayName, isDirectory, fileSize, toVfsTimestamps(), toVfsAttributes())

    private fun now() = System.currentTimeMillis()

    companion object {
        suspend fun mount(
            device: BlockDevice,
            volumeStartSector: Long,
            volumeId: String,
            displayName: String,
            readOnly: Boolean = false,
        ): Fat32Vfs {
            val boot = Fat32BootSector.read(device, volumeStartSector)
            return Fat32Vfs(Fat32Volume(device, volumeStartSector, boot), VolumeIdentity(volumeId, displayName), readOnly)
        }
    }
}
