package dev.ntfsfm.core.block

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A disk image file as a [BlockDevice]: used by tests and for mounting .img files. */
class FileBackedBlockDevice private constructor(
    private val file: RandomAccessFile,
    override val sectorSize: Int,
    override val sectorCount: Long,
    private val readOnly: Boolean,
) : BlockDevice {

    // RandomAccessFile has one file pointer; seek+read must not interleave.
    private val lock = Mutex()

    override suspend fun readSectors(startSector: Long, sectorCount: Int, into: ByteArray, offset: Int) {
        require(offset + sectorCount.toLong() * sectorSize <= into.size) { "destination buffer too small" }
        withContext(Dispatchers.IO) {
            lock.withLock {
                file.seek(startSector * sectorSize)
                file.readFully(into, offset, sectorCount * sectorSize)
            }
        }
    }

    override suspend fun writeSectors(startSector: Long, sectorCount: Int, from: ByteArray, offset: Int) {
        if (readOnly) throw BlockDeviceException("Device opened read-only")
        require(startSector + sectorCount <= this.sectorCount) { "write past end of device" }
        withContext(Dispatchers.IO) {
            lock.withLock {
                file.seek(startSector * sectorSize)
                file.write(from, offset, sectorCount * sectorSize)
            }
        }
    }

    override suspend fun flush() {
        if (readOnly) return
        withContext(Dispatchers.IO) { lock.withLock { file.fd.sync() } }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { lock.withLock { file.close() } }
    }

    companion object {
        const val DEFAULT_SECTOR_SIZE = 512

        suspend fun open(path: Path, sectorSize: Int = DEFAULT_SECTOR_SIZE, readOnly: Boolean = false): FileBackedBlockDevice =
            withContext(Dispatchers.IO) {
                val length = Files.size(path)
                require(length % sectorSize == 0L) { "file size $length is not a multiple of sector size $sectorSize" }
                val raf = RandomAccessFile(path.toFile(), if (readOnly) "r" else "rw")
                FileBackedBlockDevice(raf, sectorSize, length / sectorSize, readOnly)
            }

        suspend fun createBlank(path: Path, sectorCount: Long, sectorSize: Int = DEFAULT_SECTOR_SIZE): FileBackedBlockDevice =
            withContext(Dispatchers.IO) {
                val raf = RandomAccessFile(path.toFile(), "rw")
                raf.setLength(sectorCount * sectorSize)
                FileBackedBlockDevice(raf, sectorSize, sectorCount, readOnly = false)
            }
    }
}
