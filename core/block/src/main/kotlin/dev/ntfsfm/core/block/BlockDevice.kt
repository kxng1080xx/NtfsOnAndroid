package dev.ntfsfm.core.block

/**
 * A fixed-size array of sectors. Everything above this (partition tables, FAT32, NTFS)
 * addresses storage only through it, so USB sticks and disk images are interchangeable.
 */
interface BlockDevice {
    val sectorSize: Int
    val sectorCount: Long
    val sizeBytes: Long get() = sectorCount * sectorSize

    /** Reads [sectorCount] sectors starting at [startSector] into [into] at [offset]. */
    suspend fun readSectors(startSector: Long, sectorCount: Int, into: ByteArray, offset: Int = 0)

    suspend fun writeSectors(startSector: Long, sectorCount: Int, from: ByteArray, offset: Int = 0)

    suspend fun flush()

    suspend fun close()
}

open class BlockDeviceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The device went away mid-operation (cable pulled, permission revoked). */
class BlockDeviceRemovedException(message: String = "The device was disconnected") : BlockDeviceException(message)
