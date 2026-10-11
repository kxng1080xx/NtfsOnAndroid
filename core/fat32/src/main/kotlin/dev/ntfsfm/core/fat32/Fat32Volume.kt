package dev.ntfsfm.core.fat32

import dev.ntfsfm.core.block.BlockDevice
import dev.ntfsfm.core.block.partition.u32le
import dev.ntfsfm.core.vfs.VfsException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val FAT32_FREE = 0L
const val FAT32_BAD = 0x0FFFFFF7L
const val FAT32_EOC = 0x0FFFFFFFL
private const val FAT32_MASK = 0x0FFFFFFFL
private const val FAT_SCAN_CHUNK_SECTORS = 64
private const val FSINFO_LEAD_SIGNATURE = 0x41615252L
private const val FSINFO_STRUCT_SIGNATURE = 0x61417272L
private const val FSINFO_FREE_COUNT_OFFSET = 488
private const val FSINFO_UNKNOWN = 0xFFFFFFFFL

/**
 * Clusters and the FAT. Every FAT access holds [mutex], which also guards the
 * one-sector FAT cache, the next-free hint and the free-cluster count.
 * ponytail: assumes the BPB sector size equals the device sector size, true for USB sticks.
 */
class Fat32Volume(
    private val device: BlockDevice,
    private val partitionStartSector: Long,
    private val boot: Fat32BootSector,
) {
    private val mutex = Mutex()
    private val fatStartSector = partitionStartSector + boot.reservedSectorCount
    private val dataStartSector = fatStartSector + boot.numFats * boot.fatSize32
    private val totalClusters = (boot.totalSectors - (dataStartSector - partitionStartSector)) / boot.sectorsPerCluster

    private var cachedFatSector = -1L
    private var cachedFatBuf = ByteArray(0)
    private var nextFreeHint = 2L
    private var freeClusters: Long? = null
    private var fsInfoInvalidated = false

    val bytesPerSector: Int get() = boot.bytesPerSector
    val bytesPerCluster: Int get() = boot.bytesPerSector * boot.sectorsPerCluster
    val rootCluster: Long get() = boot.rootCluster
    val totalBytes: Long get() = boot.totalSectors * boot.bytesPerSector

    fun clusterToSector(cluster: Long): Long = dataStartSector + (cluster - 2) * boot.sectorsPerCluster

    suspend fun readCluster(cluster: Long): ByteArray {
        val buf = ByteArray(bytesPerCluster)
        device.readSectors(clusterToSector(cluster), boot.sectorsPerCluster, buf)
        return buf
    }

    suspend fun writeCluster(cluster: Long, data: ByteArray) {
        require(data.size == bytesPerCluster)
        device.writeSectors(clusterToSector(cluster), boot.sectorsPerCluster, data)
    }

    suspend fun readFatEntry(cluster: Long): Long = mutex.withLock { readFatEntryLocked(cluster) }

    private suspend fun fatSector(sector: Long): ByteArray {
        if (sector != cachedFatSector) {
            val buf = ByteArray(boot.bytesPerSector)
            device.readSectors(sector, 1, buf)
            cachedFatBuf = buf
            cachedFatSector = sector
        }
        return cachedFatBuf
    }

    private suspend fun readFatEntryLocked(cluster: Long): Long {
        val entryOffset = cluster * 4
        val buf = fatSector(fatStartSector + entryOffset / boot.bytesPerSector)
        return buf.u32le((entryOffset % boot.bytesPerSector).toInt()) and FAT32_MASK
    }

    /** Writes every FAT copy, keeping the reserved top 4 bits of each entry. */
    private suspend fun writeFatEntryLocked(cluster: Long, value: Long) {
        val entryOffset = cluster * 4
        val offsetInSector = (entryOffset % boot.bytesPerSector).toInt()
        for (fatIndex in 0 until boot.numFats) {
            val sector = fatStartSector + fatIndex * boot.fatSize32 + entryOffset / boot.bytesPerSector
            val buf = fatSector(sector)
            val preservedTopBits = buf.u32le(offsetInSector) and 0xF0000000L
            buf.writeU32le(offsetInSector, (value and FAT32_MASK) or preservedTopBits)
            device.writeSectors(sector, 1, buf)
        }
    }

    suspend fun clusterChain(startCluster: Long): List<Long> {
        if (startCluster < 2) return emptyList()
        val chain = mutableListOf<Long>()
        var current = startCluster
        while (current in 2 until FAT32_BAD) {
            chain += current
            current = readFatEntry(current)
            if (chain.size > totalClusters + 16) throw VfsException.Io("FAT chain did not terminate -- possible corruption")
        }
        return chain
    }

    /** Claims [count] free clusters, linked into one chain ending in EOC. */
    suspend fun allocateChain(count: Int): List<Long> = mutex.withLock { allocateLocked(count) }

    /** Allocates [additionalClusters] and links them after [lastClusterOfChain]. */
    suspend fun extendChain(lastClusterOfChain: Long, additionalClusters: Int): List<Long> {
        if (additionalClusters == 0) return emptyList()
        return mutex.withLock {
            val newClusters = allocateLocked(additionalClusters)
            writeFatEntryLocked(lastClusterOfChain, newClusters.first())
            newClusters
        }
    }

    private suspend fun allocateLocked(count: Int): List<Long> {
        if (count == 0) return emptyList()
        val lastClusterExclusive = totalClusters + 2
        val allocated = mutableListOf<Long>()
        // Scan from the hint to the end, then wrap round to the start.
        var cluster = nextFreeHint.coerceIn(2, lastClusterExclusive - 1)
        var scanned = 0L
        while (allocated.size < count && scanned < totalClusters) {
            if (readFatEntryLocked(cluster) == FAT32_FREE) allocated += cluster
            cluster = if (cluster + 1 >= lastClusterExclusive) 2 else cluster + 1
            scanned++
        }
        if (allocated.size < count) throw VfsException.Io("Not enough free space on volume")
        invalidateFsInfo()
        allocated.forEachIndexed { i, c ->
            writeFatEntryLocked(c, if (i == allocated.lastIndex) FAT32_EOC else allocated[i + 1])
        }
        nextFreeHint = cluster
        freeClusters = freeClusters?.minus(count)
        return allocated
    }

    suspend fun freeChain(startCluster: Long) = mutex.withLock {
        invalidateFsInfo()
        var current = startCluster
        var freed = 0L
        while (current in 2 until FAT32_BAD) {
            val next = readFatEntryLocked(current)
            writeFatEntryLocked(current, FAT32_FREE)
            if (current < nextFreeHint) nextFreeHint = current
            current = next
            if (++freed > totalClusters + 16) throw VfsException.Io("FAT chain did not terminate -- possible corruption")
        }
        freeClusters = freeClusters?.plus(freed)
    }

    suspend fun freeSpaceBytes(): Long = mutex.withLock {
        val free = freeClusters ?: (readFsInfoFreeClusters() ?: scanFreeClusters()).also { freeClusters = it }
        free * bytesPerCluster
    }

    /**
     * We don't keep FSInfo's free count up to date, so mark it unknown before the first change;
     * Windows then recounts instead of trusting a stale number.
     */
    private suspend fun invalidateFsInfo() {
        if (fsInfoInvalidated) return
        fsInfoInvalidated = true
        if (freeClusters == null) freeClusters = readFsInfoFreeClusters() ?: scanFreeClusters()
        val sector = fsInfoSectorOrNull() ?: return
        val buf = ByteArray(boot.bytesPerSector)
        device.readSectors(sector, 1, buf)
        if (!isFsInfo(buf)) return
        buf.writeU32le(FSINFO_FREE_COUNT_OFFSET, FSINFO_UNKNOWN)
        device.writeSectors(sector, 1, buf)
    }

    private fun fsInfoSectorOrNull(): Long? =
        if (boot.fsInfoSector in 1 until boot.reservedSectorCount) partitionStartSector + boot.fsInfoSector else null

    private fun isFsInfo(buf: ByteArray) =
        buf.u32le(0) == FSINFO_LEAD_SIGNATURE && buf.u32le(484) == FSINFO_STRUCT_SIGNATURE

    private suspend fun readFsInfoFreeClusters(): Long? {
        val sector = fsInfoSectorOrNull() ?: return null
        val buf = ByteArray(boot.bytesPerSector)
        runCatching { device.readSectors(sector, 1, buf) }.getOrElse { return null }
        if (!isFsInfo(buf)) return null
        val free = buf.u32le(FSINFO_FREE_COUNT_OFFSET)
        return if (free != FSINFO_UNKNOWN && free <= totalClusters) free else null
    }

    private suspend fun scanFreeClusters(): Long {
        val entriesPerSector = boot.bytesPerSector / 4
        val lastClusterExclusive = totalClusters + 2
        val fatSectorsNeeded = (lastClusterExclusive + entriesPerSector - 1) / entriesPerSector
        val buffer = ByteArray(FAT_SCAN_CHUNK_SECTORS * boot.bytesPerSector)
        var free = 0L
        var sectorIndex = 0L
        while (sectorIndex < fatSectorsNeeded) {
            val chunkSectors = minOf(FAT_SCAN_CHUNK_SECTORS.toLong(), fatSectorsNeeded - sectorIndex).toInt()
            device.readSectors(fatStartSector + sectorIndex, chunkSectors, buffer)
            val firstCluster = sectorIndex * entriesPerSector
            for (i in 0 until chunkSectors * entriesPerSector) {
                val cluster = firstCluster + i
                if (cluster >= 2 && cluster < lastClusterExclusive && buffer.u32le(i * 4) and FAT32_MASK == FAT32_FREE) free++
            }
            sectorIndex += chunkSectors
        }
        return free
    }
}
