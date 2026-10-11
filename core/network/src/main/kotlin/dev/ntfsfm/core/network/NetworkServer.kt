package dev.ntfsfm.core.network

import dev.ntfsfm.core.vfs.VirtualFileSystem
import dev.ntfsfm.core.vfs.VolumeIdentity

enum class NetworkProtocol(val label: String, val defaultPort: Int) {
    SMB("SMB", 445),
    FTP("FTP", 21),
    FTPS("FTPS", 21),
}

/** A saved network location. Blank [username] means guest/anonymous. */
data class NetworkServer(
    val id: String,
    val protocol: NetworkProtocol,
    val name: String,
    val host: String,
    val port: Int = 0,
    /** SMB share name; unused for FTP. */
    val share: String = "",
    /** Folder to open on the server; for FTP, relative to the login directory. */
    val rootPath: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    val effectivePort: Int get() = if (port > 0) port else protocol.defaultPort
    val volumeId: String get() = "net-$id"

    val summary: String
        get() = buildString {
            append(protocol.label).append(" · ")
            if (username.isNotBlank()) append(username).append('@')
            append(host)
            if (port > 0 && port != protocol.defaultPort) append(':').append(port)
            if (protocol == NetworkProtocol.SMB && share.isNotBlank()) append('/').append(share)
        }
}

/** Opens the right volume type for [server]; connection happens on first use. */
fun NetworkServer.openVfs(): VirtualFileSystem {
    val identity = VolumeIdentity(volumeId, name)
    return when (protocol) {
        NetworkProtocol.SMB -> SmbVfs(this, identity)
        NetworkProtocol.FTP, NetworkProtocol.FTPS -> FtpVfs(this, identity)
    }
}
