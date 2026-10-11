package dev.ntfsfm.core.block.partition

import dev.ntfsfm.core.block.BlockDevice

object MbrParser {
    private const val PARTITION_TABLE_OFFSET = 446
    private const val ENTRY_SIZE = 16
    private const val ENTRY_COUNT = 4
    private const val GPT_PROTECTIVE_TYPE = 0xEE

    /** Returns null when sector 0 is not an MBR (no signature, a GPT protective MBR, or a bare volume). */
    suspend fun parse(device: BlockDevice): PartitionTable? {
        val sector = ByteArray(device.sectorSize)
        device.readSectors(0, 1, sector)
        if (sector.u16le(510) != 0xAA55) return null
        // A volume boot sector also ends in 0xAA55; its boot code would parse as garbage entries.
        if (BootSectorFileSystemDetector.detect(sector) != FileSystemType.UNKNOWN) return null

        val partitions = mutableListOf<Partition>()
        for (i in 0 until ENTRY_COUNT) {
            val entryOffset = PARTITION_TABLE_OFFSET + i * ENTRY_SIZE
            val typeByte = sector.u8(entryOffset + 4)
            if (typeByte == 0) continue
            if (typeByte == GPT_PROTECTIVE_TYPE) return null
            val startSector = sector.u32le(entryOffset + 8)
            val sectorCount = sector.u32le(entryOffset + 12)
            if (sectorCount == 0L) continue
            val fs = BootSectorFileSystemDetector.detect(device, startSector)
            partitions += Partition(
                index = i,
                startSector = startSector,
                sectorCount = sectorCount,
                typeGuidOrByte = "0x%02X".format(typeByte),
                fileSystem = fs,
                role = PartitionRoleClassifier.fromMbrTypeByte(typeByte),
            )
        }
        return PartitionTable(PartitionTableType.MBR, partitions)
    }
}
