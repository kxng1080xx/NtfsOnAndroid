package dev.ntfsfm.core.network

import dev.ntfsfm.core.vfs.VfsAttributes
import dev.ntfsfm.core.vfs.VfsEntry
import dev.ntfsfm.core.vfs.VfsException
import dev.ntfsfm.core.vfs.VfsTimestamps
import dev.ntfsfm.core.vfs.VirtualFileSystem
import dev.ntfsfm.core.vfs.VolumeIdentity
import dev.ntfsfm.core.vfs.VolumeSpace
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient

/**
 * An FTP or explicit-TLS FTPS server as a volume. FTP runs one command at a time per
 * connection, so metadata goes through one shared, locked client and every file stream
 * gets its own connection (copying FTP to the same FTP server must not deadlock).
 */
class FtpVfs(
    private val server: NetworkServer,
    override val identity: VolumeIdentity,
) : VirtualFileSystem {
    override val isReadOnly: Boolean = false

    private var control: FTPClient? = null
    private val lock = Any()

    private fun connect(): FTPClient {
        val client = if (server.protocol == NetworkProtocol.FTPS) FTPSClient(false) else FTPClient()
        client.controlEncoding = "UTF-8"
        client.connectTimeout = 20_000
        client.setDefaultTimeout(30_000)
        try {
            client.connect(server.host, server.effectivePort)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) throw IOException("Server refused the connection: ${client.replyString.trim()}")
            val user = server.username.ifBlank { "anonymous" }
            val pass = if (server.username.isBlank()) "guest@" else server.password
            if (!client.login(user, pass)) throw VfsException.Io("Login failed: ${client.replyString.trim()}")
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            client.setFileType(FTP.BINARY_FILE_TYPE)
            client.enterLocalPassiveMode()
            client.soTimeout = 60_000
            client.setControlKeepAliveTimeout(Duration.ofSeconds(30))
            return client
        } catch (e: VfsException) {
            runCatching { client.disconnect() }
            throw e
        } catch (e: IOException) {
            runCatching { client.disconnect() }
            throw VfsException.VolumeUnavailable(identity.volumeId, e)
        }
    }

    /** Runs [block] on the shared control connection, reconnecting once if it went stale. */
    private fun <T> withControl(block: (FTPClient) -> T): T = synchronized(lock) {
        val client = control?.takeIf { it.isConnected && runCatching { it.sendNoOp() }.getOrDefault(false) } ?: connect().also { control = it }
        try {
            block(client)
        } catch (e: IOException) {
            runCatching { client.disconnect() }
            control = null
            throw VfsException.Io(e.message ?: "FTP error", e)
        }
    }

    /** Server path: the configured root plus the volume-relative path. */
    private fun remote(path: String): String {
        val root = server.rootPath.trim('/')
        val rel = path.trim('/')
        return listOf(root, rel).filter { it.isNotEmpty() }.joinToString("/", prefix = if (server.rootPath.startsWith("/")) "/" else "")
            .ifEmpty { "." }
    }

    override fun listDirectory(path: String): Flow<VfsEntry> = flow {
        val files = withControl { c ->
            val listed = if (c.hasFeature("MLST")) c.mlistDir(remote(path)) else c.listFiles(remote(path))
            if (listed == null) throw VfsException.NotFound(path)
            listed.toList()
        }
        for (f in files) {
            if (f == null || f.name == "." || f.name == "..") continue
            emit(f.toEntry(joinPath(path, f.name.substringAfterLast('/'))))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun stat(path: String): VfsEntry = withContext(Dispatchers.IO) {
        if (path.trim('/').isEmpty()) {
            return@withContext VfsEntry("", identity.displayName, true, 0, VfsTimestamps(null, 0, null), VfsAttributes())
        }
        val name = path.trim('/').substringAfterLast('/')
        val parent = path.trim('/').substringBeforeLast('/', "")
        val file = withControl { c ->
            if (c.hasFeature("MLST")) {
                c.mlistFile(remote(path))
            } else {
                (c.listFiles(remote(parent)) ?: emptyArray()).firstOrNull { it?.name == name }
            }
        } ?: throw VfsException.NotFound(path)
        file.toEntry(path)
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        withControl { c -> if (!c.makeDirectory(remote(path))) throw VfsException.Io("Could not create folder: ${c.replyString.trim()}") }
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        val entry = stat(path)
        if (entry.isDirectory) {
            val children = withControl { c -> (c.listFiles(remote(path)) ?: emptyArray()).toList() }
            for (child in children) {
                if (child == null || child.name == "." || child.name == "..") continue
                delete(joinPath(path, child.name))
            }
            withControl { c -> if (!c.removeDirectory(remote(path))) throw VfsException.Io("Could not delete: ${c.replyString.trim()}") }
        } else {
            withControl { c -> if (!c.deleteFile(remote(path))) throw VfsException.Io("Could not delete: ${c.replyString.trim()}") }
        }
    }

    override suspend fun rename(path: String, newName: String) {
        val parent = path.trim('/').substringBeforeLast('/', "")
        if (!moveWithin(path, joinPath(parent, newName))) throw VfsException.Io("Could not rename: $path")
    }

    override suspend fun moveWithin(fromPath: String, toPath: String): Boolean = withContext(Dispatchers.IO) {
        withControl { c -> c.rename(remote(fromPath), remote(toPath)) }
    }

    // FTP has no file attributes.
    override suspend fun setAttributes(path: String, attributes: VfsAttributes) = Unit

    override suspend fun setTimestamps(path: String, timestamps: VfsTimestamps) = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(timestamps.modifiedAt))
        withControl { c -> if (c.hasFeature("MFMT")) c.setModificationTime(remote(path), stamp) }
        Unit
    }

    override suspend fun openInputStream(path: String): InputStream = withContext(Dispatchers.IO) {
        val client = connect()
        val stream = client.retrieveFileStream(remote(path))
        if (stream == null) {
            val reply = client.replyString.trim()
            runCatching { client.disconnect() }
            throw if (client.replyCode == 550) VfsException.NotFound(path) else VfsException.Io("Could not read: $reply")
        }
        object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                    client.completePendingCommand()
                } finally {
                    runCatching { client.logout() }
                    runCatching { client.disconnect() }
                }
            }
        }
    }

    override suspend fun openOutputStream(path: String): OutputStream = withContext(Dispatchers.IO) {
        val client = connect()
        val stream = client.storeFileStream(remote(path))
        if (stream == null) {
            val reply = client.replyString.trim()
            runCatching { client.disconnect() }
            throw VfsException.Io("Could not write: $reply")
        }
        object : FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                try {
                    super.close()
                    // The upload only counts once the server confirms it.
                    if (!client.completePendingCommand()) throw IOException("Upload failed: ${client.replyString.trim()}")
                } finally {
                    runCatching { client.logout() }
                    runCatching { client.disconnect() }
                }
            }
        }
    }

    // FTP can't report free space.
    override suspend fun spaceInfo(): VolumeSpace = VolumeSpace(0, 0)

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) { runCatching { withControl { true } }.getOrDefault(false) }

    fun close() = synchronized(lock) {
        runCatching { control?.logout() }
        runCatching { control?.disconnect() }
        control = null
    }

    private fun FTPFile.toEntry(path: String) = VfsEntry(
        path = path,
        name = name.substringAfterLast('/'),
        isDirectory = isDirectory,
        sizeBytes = if (isDirectory) 0 else size.coerceAtLeast(0),
        timestamps = VfsTimestamps(null, timestamp?.timeInMillis ?: 0, null),
        attributes = VfsAttributes(hidden = name.startsWith(".")),
    )
}
