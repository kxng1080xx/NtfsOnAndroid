package dev.ntfsfm.core.block.partition

import dev.ntfsfm.core.block.BlockDevice

/** Identifies a volume from its boot sector, independent of what the partition table claims. */
object BootSectorFileSystemDetector {
    suspend fun detect(device: BlockDevice, volumeStartSector: Long): FileSystemType {
        val sector = ByteArray(device.sectorSize)
        device.readSectors(volumeStartSector, 1, sector)
        return detect(sector)
    }

    fun detect(sector: ByteArray): FileSystemType {
        if (sector.u16le(510) != 0xAA55) return FileSystemType.UNKNOWN
        return when {
            sector.asciiString(3, 8) == "NTFS" -> FileSystemType.NTFS
            sector.asciiString(3, 8) == "EXFAT" -> FileSystemType.EXFAT
            sector.asciiString(82, 8) == "FAT32" -> FileSystemType.FAT32
            sector.asciiString(54, 8).let { it.startsWith("FAT16") || it.startsWith("FAT12") } -> FileSystemType.FAT16
            else -> FileSystemType.UNKNOWN
        }
    }
}
