package dev.ntfsfm.core.fat32

import dev.ntfsfm.core.block.BlockDevice
import dev.ntfsfm.core.block.partition.asciiString
import dev.ntfsfm.core.block.partition.u16le
import dev.ntfsfm.core.block.partition.u32le
import dev.ntfsfm.core.block.partition.u8
import dev.ntfsfm.core.vfs.VfsException

/** The BPB fields FAT32 needs. Sector numbers are relative to the volume start. */
data class Fat32BootSector(
    val bytesPerSector: Int,
    val sectorsPerCluster: Int,
    val reservedSectorCount: Int,
    val numFats: Int,
    val fatSize32: Long,
    val rootCluster: Long,
    val totalSectors: Long,
    val volumeLabel: String,
    val fsInfoSector: Int,
) {
    companion object {
        suspend fun read(device: BlockDevice, volumeStartSector: Long): Fat32BootSector {
            val sector = ByteArray(device.sectorSize)
            device.readSectors(volumeStartSector, 1, sector)
            if (sector.u16le(510) != 0xAA55 || sector.asciiString(82, 8) != "FAT32") {
                throw VfsException.Io("Not a FAT32 boot sector")
            }
            val totalSectors32 = sector.u32le(32)
            return Fat32BootSector(
                bytesPerSector = sector.u16le(11),
                sectorsPerCluster = sector.u8(13),
                reservedSectorCount = sector.u16le(14),
                numFats = sector.u8(16),
                fatSize32 = sector.u32le(36),
                rootCluster = sector.u32le(44),
                totalSectors = if (totalSectors32 != 0L) totalSectors32 else sector.u16le(19).toLong(),
                volumeLabel = sector.asciiString(71, 11),
                fsInfoSector = sector.u16le(48),
            )
        }
    }
}

internal fun ByteArray.writeU16le(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value shr 8).toByte()
}

internal fun ByteArray.writeU32le(offset: Int, value: Long) {
    for (i in 0 until 4) this[offset + i] = (value shr (i * 8)).toByte()
}
