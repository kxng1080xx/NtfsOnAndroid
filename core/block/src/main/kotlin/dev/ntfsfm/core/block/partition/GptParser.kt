package dev.ntfsfm.core.block.partition

import dev.ntfsfm.core.block.BlockDevice
import java.util.Locale

object GptParser {
    private const val HEADER_LBA = 1L
    private const val SIGNATURE = "EFI PART"
    private const val UNUSED_GUID = "00000000-0000-0000-0000-000000000000"

    /** Returns null when there is no GPT header at LBA 1. */
    suspend fun parse(device: BlockDevice): PartitionTable? {
        val header = ByteArray(device.sectorSize)
        device.readSectors(HEADER_LBA, 1, header)
        if (header.asciiString(0, 8) != SIGNATURE) return null

        val entryArrayLba = header.u64le(72)
        val entryCount = header.u32le(80).toInt()
        val entrySize = header.u32le(84).toInt()
        if (entryCount <= 0 || entrySize <= 0) return PartitionTable(PartitionTableType.GPT, emptyList())

        val entriesPerSector = device.sectorSize / entrySize
        val sectorsNeeded = (entryCount + entriesPerSector - 1) / entriesPerSector
        val entryTable = ByteArray(device.sectorSize * sectorsNeeded)
        device.readSectors(entryArrayLba, sectorsNeeded, entryTable)

        val partitions = mutableListOf<Partition>()
        for (i in 0 until entryCount) {
            val offset = i * entrySize
            if (offset + entrySize > entryTable.size) break
            val typeGuid = readGuid(entryTable, offset)
            if (typeGuid == UNUSED_GUID) continue
            val startLba = entryTable.u64le(offset + 32)
            val endLba = entryTable.u64le(offset + 40)
            val name = entryTable.utf16leString(offset + 56, 72)
            val fs = BootSectorFileSystemDetector.detect(device, startLba)
            partitions += Partition(
                index = i,
                startSector = startLba,
                sectorCount = endLba - startLba + 1,
                typeGuidOrByte = typeGuid,
                fileSystem = fs,
                label = name.ifBlank { null },
                role = PartitionRoleClassifier.fromGptGuid(typeGuid),
            )
        }
        return PartitionTable(PartitionTableType.GPT, partitions)
    }

    /** Mixed-endian GUID layout: first three groups little-endian, last two as raw bytes. */
    private fun readGuid(bytes: ByteArray, offset: Int): String {
        val d1 = bytes.u32le(offset)
        val d2 = bytes.u16le(offset + 4)
        val d3 = bytes.u16le(offset + 6)
        val d4 = (offset + 8 until offset + 10).joinToString("") { "%02x".format(bytes.u8(it)) }
        val d5 = (offset + 10 until offset + 16).joinToString("") { "%02x".format(bytes.u8(it)) }
        return String.format(Locale.ROOT, "%08x-%04x-%04x-%s-%s", d1, d2, d3, d4, d5)
    }
}
