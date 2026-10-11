package dev.ntfsfm.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ntfsfm.core.network.FtpVfs
import dev.ntfsfm.core.network.NetworkProtocol
import dev.ntfsfm.core.network.NetworkServer
import dev.ntfsfm.core.network.SmbVfs
import dev.ntfsfm.core.network.openVfs
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved SMB/FTP servers and which are connected. Connected servers are mounted in
 * [ActiveVolumesHolder] like any drive. Passwords are encrypted with an Android Keystore key.
 */
@Singleton
class NetworkRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val activeVolumes: ActiveVolumesHolder,
) {
    private val prefs = context.getSharedPreferences("network_servers", Context.MODE_PRIVATE)

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<NetworkServer>> = _servers.asStateFlow()

    private val _connected = MutableStateFlow<Set<String>>(emptySet())
    val connected: StateFlow<Set<String>> = _connected.asStateFlow()

    fun save(server: NetworkServer): NetworkServer {
        val saved = if (server.id.isBlank()) server.copy(id = UUID.randomUUID().toString()) else server
        _servers.update { list -> list.filterNot { it.id == saved.id } + saved }
        persist()
        // Settings changed: the old connection is stale.
        if (saved.id in _connected.value) disconnect(saved.id)
        return saved
    }

    fun delete(id: String) {
        disconnect(id)
        _servers.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    /** Connects and mounts; throws with a readable message if the server can't be reached. */
    suspend fun connect(server: NetworkServer) = withContext(Dispatchers.IO) {
        val vfs = server.openVfs()
        try {
            vfs.listDirectory("").collect { } // proves login, share and path all work
        } catch (e: Exception) {
            close(vfs)
            throw e
        }
        activeVolumes.put(vfs)
        _connected.update { it + server.id }
    }

    fun disconnect(id: String) {
        val server = _servers.value.firstOrNull { it.id == id } ?: return
        activeVolumes.get(server.volumeId)?.let(::close)
        activeVolumes.remove(server.volumeId)
        _connected.update { it - id }
    }

    private fun close(vfs: Any) {
        when (vfs) {
            is SmbVfs -> vfs.close()
            is FtpVfs -> vfs.close()
        }
    }

    private fun persist() {
        val array = JSONArray()
        _servers.value.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("protocol", s.protocol.name)
                    .put("name", s.name)
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("share", s.share)
                    .put("rootPath", s.rootPath)
                    .put("username", s.username)
                    .put("password", if (s.password.isEmpty()) "" else encrypt(s.password))
                    .put("domain", s.domain),
            )
        }
        prefs.edit { putString(KEY_SERVERS, array.toString()) }
    }

    private fun load(): List<NetworkServer> {
        val json = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                NetworkServer(
                    id = o.getString("id"),
                    protocol = NetworkProtocol.valueOf(o.getString("protocol")),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.optInt("port"),
                    share = o.optString("share"),
                    rootPath = o.optString("rootPath"),
                    username = o.optString("username"),
                    // A password we can't decrypt (key wiped) just has to be typed again.
                    password = o.optString("password").takeIf { it.isNotEmpty() }?.let { runCatching { decrypt(it) }.getOrDefault("") } ?: "",
                    domain = o.optString("domain"),
                )
            }.getOrNull()
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    /** AES-GCM; stored as base64(iv || ciphertext). */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_LENGTH))
        }
        return String(cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_SERVERS = "servers"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ntfsfm.network"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
