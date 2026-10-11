package dev.ntfsfm.core.vfs

import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.runBlocking

/**
 * Positional reads, for consumers that seek: a video player reading through a proxy
 * file descriptor jumps around the file. Blocking on purpose; callers are binder threads.
 */
interface RandomAccessReader : Closeable {
    val size: Long

    /** Reads up to [length] bytes at [position]; returns bytes read, 0 at end of file. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * Fallback for volumes that only have sequential streams: sequential reads reuse the
 * open stream, a backwards seek reopens it.
 * ponytail: backward seeks cost a reopen + skip; fine for playback, slow for random scrubbing.
 */
class StreamRandomAccessReader(
    private val vfs: VirtualFileSystem,
    private val path: String,
    override val size: Long,
) : RandomAccessReader {
    private var stream: InputStream? = null
    private var streamPosition = 0L

    @Synchronized
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return 0
        var s = stream
        if (s == null || position < streamPosition) {
            s?.close()
            s = runBlocking { vfs.openInputStream(path) }
            stream = s
            streamPosition = 0
        }
        while (streamPosition < position) {
            val skipped = s.skip(position - streamPosition)
            if (skipped <= 0) {
                if (s.read() < 0) return 0
                streamPosition++
            } else {
                streamPosition += skipped
            }
        }
        val n = s.read(buffer, offset, length)
        if (n <= 0) return 0
        streamPosition += n
        return n
    }

    @Synchronized
    override fun close() {
        stream?.close()
        stream = null
    }
}
