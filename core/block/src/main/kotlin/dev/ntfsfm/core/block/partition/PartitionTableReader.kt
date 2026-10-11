package dev.ntfsfm.core.block.partition

import dev.ntfsfm.core.block.BlockDevice

object PartitionTableReader {
    /** GPT, else MBR, else the whole device as one volume ("superfloppy"), else an empty table. */
    suspend fun read(device: BlockDevice): PartitionTable {
        GptParser.parse(device)?.let { gpt ->
            if (gpt.partitions.isNotEmpty() || isProtectiveMbrPresent(device)) return gpt
        }
        MbrParser.parse(device)?.let { return it }

        val fs = BootSectorFileSystemDetector.detect(device, 0)
        return if (fs != FileSystemType.UNKNOWN) {
            PartitionTable(PartitionTableType.NONE, listOf(Partition(0, 0, device.sectorCount, "-", fs)))
        } else {
            PartitionTable(PartitionTableType.NONE, emptyList())
        }
    }

    private suspend fun isProtectiveMbrPresent(device: BlockDevice): Boolean {
        val sector = ByteArray(device.sectorSize)
        device.readSectors(0, 1, sector)
        return sector.u16le(510) == 0xAA55 && sector.u8(450) == 0xEE
    }
}
