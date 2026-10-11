package dev.ntfsfm.core.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import dev.ntfsfm.core.vfs.VfsAttributes
import dev.ntfsfm.core.vfs.VfsEntry
import dev.ntfsfm.core.vfs.VfsException
import dev.ntfsfm.core.vfs.VfsTimestamps
import dev.ntfsfm.core.vfs.VirtualFileSystem
import dev.ntfsfm.core.vfs.VolumeIdentity
import dev.ntfsfm.core.vfs.VolumeSpace
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** A Windows/Samba share (SMB2/3) as a volume. Paths are relative to the share root. */
class SmbVfs(
    private val server: NetworkServer,
    override val identity: VolumeIdentity,
) : VirtualFileSystem {
    override val isReadOnly: Boolean = false

    private val client = SMBClient(SmbConfig.builder().withTimeout(30, TimeUnit.SECONDS).withSoTimeout(60, TimeUnit.SECONDS).build())
    private var connection: Connection? = null
    private var share: DiskShare? = null

    /** The connected share, reconnecting if the link dropped (Wi-Fi blip, server restart). */
    @Synchronized
    private fun share(): DiskShare {
        share?.takeIf { it.isConnected }?.let { return it }
        runCatching { connection?.close() }
        try {
            val conn = client.connect(server.host, server.port.takeIf { it > 0 } ?: 445)
            val auth = if (server.username.isBlank()) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(server.username, server.password.toCharArray(), server.domain.ifBlank { null })
            }
            val session = conn.authenticate(auth)
            val disk = session.connectShare(server.share) as? DiskShare
                ?: throw VfsException.Io("'${server.share}' is not a file share")
            connection = conn
            share = disk
            return disk
        } catch (e: VfsException) {
            throw e
        } catch (e: Exception) {
            throw VfsException.VolumeUnavailable(identity.volumeId, e)
        }
    }

    private fun smbPath(path: String) = path.trim('/').replace('/', '\\')

    private fun <T> smb(path: String, block: (DiskShare) -> T): T = try {
        block(share())
    } catch (e: SMBApiException) {
        throw when (e.status) {
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NO_SUCH_FILE -> VfsException.NotFound(path)
            NtStatus.STATUS_OBJECT_NAME_COLLISION -> VfsException.AlreadyExists(path)
            NtStatus.STATUS_ACCESS_DENIED -> VfsException.Io("Access denied: $path", e)
            else -> VfsException.Io("${e.status}: $path", e)
        }
    }

    override fun listDirectory(path: String): Flow<VfsEntry> = flow {
        val entries = smb(path) { it.list(smbPath(path)) }
        for (info in entries) {
            if (info.fileName == "." || info.fileName == "..") continue
            emit(
                VfsEntry(
                    path = joinPath(path, info.fileName),
                    name = info.fileName,
                    isDirectory = info.fileAttributes and DIRECTORY != 0L,
                    sizeBytes = info.endOfFile,
                    timestamps = VfsTimestamps(info.creationTime.toEpochMillis(), info.lastWriteTime.toEpochMillis(), info.lastAccessTime.toEpochMillis()),
                    attributes = attributesOf(info.fileAttributes),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun stat(path: String): VfsEntry = withContext(Dispatchers.IO) {
        if (path.trim('/').isEmpty()) {
            return@withContext VfsEntry("", identity.displayName, true, 0, VfsTimestamps(null, 0, null), VfsAttributes())
        }
        val info = smb(path) { it.getFileInformation(smbPath(path)) }
        val basic = info.basicInformation
        VfsEntry(
            path = path,
            name = path.trim('/').substringAfterLast('/'),
            isDirectory = info.standardInformation.isDirectory,
            sizeBytes = info.standardInformation.endOfFile,
            timestamps = VfsTimestamps(basic.creationTime.toEpochMillis(), basic.lastWriteTime.toEpochMillis(), basic.lastAccessTime.toEpochMillis()),
            attributes = attributesOf(basic.fileAttributes),
        )
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        smb(path) { it.mkdir(smbPath(path)) }
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        smb(path) { share ->
            if (share.folderExists(smbPath(path))) share.rmdir(smbPath(path), true) else share.rm(smbPath(path))
        }
    }

    override suspend fun rename(path: String, newName: String) {
        val parent = path.trim('/').substringBeforeLast('/', "")
        if (!moveWithin(path, joinPath(parent, newName))) throw VfsException.Io("Could not rename: $path")
    }

    override suspend fun moveWithin(fromPath: String, toPath: String): Boolean = withContext(Dispatchers.IO) {
        smb(fromPath) { share ->
            share.open(
                smbPath(fromPath),
                EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            ).use { it.rename(smbPath(toPath), false) }
        }
        true
    }

    override suspend fun setAttributes(path: String, attributes: VfsAttributes) = withContext(Dispatchers.IO) {
        val current = smb(path) { it.getFileInformation(smbPath(path)) }.basicInformation.fileAttributes
        var attr = current and (DIRECTORY or 0x80L).inv() // keep the directory bit, drop NORMAL
        attr = attr and (FileAttributes.FILE_ATTRIBUTE_READONLY.value or FileAttributes.FILE_ATTRIBUTE_HIDDEN.value or
            FileAttributes.FILE_ATTRIBUTE_SYSTEM.value or FileAttributes.FILE_ATTRIBUTE_ARCHIVE.value).inv()
        if (attributes.readOnly) attr = attr or FileAttributes.FILE_ATTRIBUTE_READONLY.value
        if (attributes.hidden) attr = attr or FileAttributes.FILE_ATTRIBUTE_HIDDEN.value
        if (attributes.system) attr = attr or FileAttributes.FILE_ATTRIBUTE_SYSTEM.value
        if (attributes.archive) attr = attr or FileAttributes.FILE_ATTRIBUTE_ARCHIVE.value
        if (attr == 0L) attr = 0x80L // FILE_ATTRIBUTE_NORMAL: 0 would mean "don't change"
        val keep = FileBasicInformation.DONT_SET
        smb(path) { it.setFileInformation(smbPath(path), FileBasicInformation(keep, keep, keep, keep, attr)) }
    }

    override suspend fun setTimestamps(path: String, timestamps: VfsTimestamps) = withContext(Dispatchers.IO) {
        val keep = FileBasicInformation.DONT_SET
        val info = FileBasicInformation(
            timestamps.createdAt?.let(FileTime::ofEpochMillis) ?: keep,
            timestamps.accessedAt?.let(FileTime::ofEpochMillis) ?: keep,
            FileTime.ofEpochMillis(timestamps.modifiedAt),
            keep,
            0,
        )
        smb(path) { it.setFileInformation(smbPath(path), info) }
    }

    override suspend fun openInputStream(path: String): InputStream = withContext(Dispatchers.IO) {
        val file = smb(path) {
            it.openFile(smbPath(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        }
        object : FilterInputStream(file.inputStream) {
            override fun close() {
                try { super.close() } finally { file.close() }
            }
        }
    }

    override suspend fun openOutputStream(path: String): OutputStream = withContext(Dispatchers.IO) {
        val file = smb(path) {
            it.openFile(smbPath(path), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
        }
        object : FilterOutputStream(file.outputStream) {
            // FilterOutputStream writes byte by byte by default; pass whole buffers through.
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                try { super.close() } finally { file.close() }
            }
        }
    }

    override suspend fun spaceInfo(): VolumeSpace = withContext(Dispatchers.IO) {
        val info = smb("") { it.shareInformation }
        VolumeSpace(totalBytes = info.totalSpace, freeBytes = info.freeSpace)
    }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) { runCatching { share() }.isSuccess }

    fun close() {
        runCatching { connection?.close() }
        runCatching { client.close() }
    }

    private fun attributesOf(attr: Long) = VfsAttributes(
        readOnly = attr and FileAttributes.FILE_ATTRIBUTE_READONLY.value != 0L,
        hidden = attr and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L,
        system = attr and FileAttributes.FILE_ATTRIBUTE_SYSTEM.value != 0L,
        archive = attr and FileAttributes.FILE_ATTRIBUTE_ARCHIVE.value != 0L,
    )

    private companion object {
        val DIRECTORY = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value
    }
}

internal fun joinPath(parent: String, name: String): String {
    val trimmed = parent.trim('/')
    return if (trimmed.isEmpty()) name else "$trimmed/$name"
}
