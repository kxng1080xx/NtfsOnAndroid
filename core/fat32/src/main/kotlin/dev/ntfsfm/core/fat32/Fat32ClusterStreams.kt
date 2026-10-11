package dev.ntfsfm.core.fat32

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking

/** Sequential reads along a cluster chain. Blocking, like any InputStream; callers run it on IO. */
class Fat32ClusterInputStream(
    private val volume: Fat32Volume,
    startCluster: Long,
    private val fileSize: Long,
) : InputStream() {
    private val chain: List<Long> = if (startCluster < 2) emptyList() else runBlocking { volume.clusterChain(startCluster) }
    private var currentCluster: ByteArray? = null
    private var currentClusterIndex = -1
    private var positionInFile = 0L

    override fun read(): Int {
        val buf = ByteArray(1)
        return if (read(buf, 0, 1) <= 0) -1 else buf[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (positionInFile >= fileSize) return -1
        val toRead = minOf(len.toLong(), fileSize - positionInFile).toInt()
        if (toRead <= 0) return -1
        val clusterSize = volume.bytesPerCluster
        var written = 0
        while (written < toRead) {
            val clusterIndex = (positionInFile / clusterSize).toInt()
            if (clusterIndex >= chain.size) break // chain shorter than the size claims
            if (clusterIndex != currentClusterIndex) {
                currentCluster = runBlocking { volume.readCluster(chain[clusterIndex]) }
                currentClusterIndex = clusterIndex
            }
            val offsetInCluster = (positionInFile % clusterSize).toInt()
            val chunk = minOf(toRead - written, clusterSize - offsetInCluster)
            System.arraycopy(currentCluster!!, offsetInCluster, b, off + written, chunk)
            written += chunk
            positionInFile += chunk
        }
        return if (written == 0) -1 else written
    }

    override fun close() {
        currentCluster = null
    }
}

/**
 * Writes a new file cluster by cluster, growing the chain as it goes. The directory entry
 * only exists once [onClosed] runs with (first cluster or 0, size).
 */
class Fat32ClusterOutputStream(
    private val volume: Fat32Volume,
    private val onClosed: suspend (firstCluster: Long, size: Long) -> Unit,
) : OutputStream() {
    private val chain = mutableListOf<Long>()
    private var clusterBuffer = ByteArray(volume.bytesPerCluster)
    private var offsetInCluster = 0
    private var totalWritten = 0L
    private var closed = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!closed) { "stream is closed" }
        var remaining = len
        var srcOffset = off
        while (remaining > 0) {
            val chunk = minOf(remaining, volume.bytesPerCluster - offsetInCluster)
            System.arraycopy(b, srcOffset, clusterBuffer, offsetInCluster, chunk)
            offsetInCluster += chunk
            srcOffset += chunk
            remaining -= chunk
            totalWritten += chunk
            if (offsetInCluster == volume.bytesPerCluster) flushClusterBuffer()
        }
    }

    private fun flushClusterBuffer() {
        runBlocking {
            val cluster = ensureClusterFor(chain.size)
            volume.writeCluster(cluster, clusterBuffer)
        }
        clusterBuffer = ByteArray(volume.bytesPerCluster)
        offsetInCluster = 0
    }

    private suspend fun ensureClusterFor(chainIndex: Int): Long {
        if (chainIndex < chain.size) return chain[chainIndex]
        chain += if (chain.isEmpty()) volume.allocateChain(1) else volume.extendChain(chain.last(), 1)
        return chain[chainIndex]
    }

    override fun close() {
        if (closed) return
        closed = true
        if (offsetInCluster > 0) flushClusterBuffer()
        runBlocking { onClosed(chain.firstOrNull() ?: 0L, totalWritten) }
    }
}
