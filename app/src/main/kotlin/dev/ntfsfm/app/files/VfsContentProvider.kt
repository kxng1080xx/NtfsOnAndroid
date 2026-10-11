package dev.ntfsfm.app.files

import android.content.ActivityNotFoundException
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.ntfsfm.app.data.ActiveVolumesHolder
import dev.ntfsfm.app.ui.nav.decodeArg
import dev.ntfsfm.app.ui.nav.encodeArg
import dev.ntfsfm.core.vfs.RandomAccessReader
import java.io.FileNotFoundException
import java.util.Locale
import kotlinx.coroutines.runBlocking

/**
 * Hands files on any mounted volume (USB, network, phone) to other apps without copying:
 * reads go through a proxy file descriptor straight to the volume, so a video player can
 * seek in a large file on a USB stick. Read-only.
 *
 * URI: content://dev.ntfsfm.app.files/<base64 volumeId>/<base64 path>/<display name>
 */
class VfsContentProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderEntryPoint {
        fun activeVolumes(): ActiveVolumesHolder
    }

    // Proxy-descriptor callbacks run on this thread; reads block it, not the binder threads.
    private val callbackThread by lazy { HandlerThread("vfs-provider").apply { start() } }

    private fun activeVolumes(): ActiveVolumesHolder =
        EntryPointAccessors.fromApplication(context!!.applicationContext, ProviderEntryPoint::class.java).activeVolumes()

    override fun onCreate(): Boolean = true

    private fun parse(uri: Uri): Pair<String, String> {
        val segments = uri.pathSegments
        if (segments.size < 2) throw FileNotFoundException("Bad uri: $uri")
        return decodeArg(segments[0]) to decodeArg(segments[1])
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Files are opened read-only")
        val (volumeId, path) = parse(uri)
        val vfs = activeVolumes().get(volumeId) ?: throw FileNotFoundException("Drive not connected")
        val reader = try {
            runBlocking { vfs.openRandomAccess(path) }
        } catch (e: Exception) {
            throw FileNotFoundException(e.message ?: "Can't open $path")
        }
        val storage = context!!.getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, ReaderCallback(reader), Handler(callbackThread.looper))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val (volumeId, path) = parse(uri)
        val entry = activeVolumes().get(volumeId)?.let { vfs -> runCatching { runBlocking { vfs.stat(path) } }.getOrNull() }
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> uri.lastPathSegment
                        OpenableColumns.SIZE -> entry?.sizeBytes
                        else -> null
                    }
                },
            )
        }
    }

    override fun getType(uri: Uri): String = mimeTypeOf(uri.lastPathSegment.orEmpty())

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private class ReaderCallback(private val reader: RandomAccessReader) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = reader.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            var total = 0
            while (total < size) {
                val n = reader.read(offset + total, data, total, size - total)
                if (n <= 0) break
                total += n
            }
            return total
        }

        override fun onRelease() = reader.close()
    }

    companion object {
        const val AUTHORITY = "dev.ntfsfm.app.files"

        fun uriFor(volumeId: String, path: String, name: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(encodeArg(volumeId))
            .appendPath(encodeArg(path))
            .appendPath(name)
            .build()

        fun mimeTypeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
    }
}

/** Opens [name] at [path] on [volumeId] in another app. Returns false if no app can handle it. */
fun openInOtherApp(context: Context, volumeId: String, path: String, name: String, chooser: Boolean = false): Boolean {
    val uri = VfsContentProvider.uriFor(volumeId, path, name)
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, VfsContentProvider.mimeTypeOf(name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val intent = if (chooser) Intent.createChooser(view, "Open with") else view
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
