package dev.ntfsfm.core.block.partition

import java.util.Locale

enum class FileSystemType { NTFS, EXFAT, FAT32, FAT16, UNKNOWN }

enum class PartitionTableType { MBR, GPT, NONE }

/** What a partition is for; only [DATA] partitions are shown to the user. */
enum class PartitionRole { DATA, EFI_SYSTEM, RESERVED, RECOVERY }

data class Partition(
    val index: Int,
    val startSector: Long,
    val sectorCount: Long,
    /** GPT type GUID, or the MBR type byte as "0x07". */
    val typeGuidOrByte: String,
    val fileSystem: FileSystemType,
    val label: String? = null,
    val role: PartitionRole = PartitionRole.DATA,
) {
    val isUserData: Boolean get() = role == PartitionRole.DATA

    val isMountableFileSystem: Boolean
        get() = fileSystem == FileSystemType.NTFS || fileSystem == FileSystemType.FAT32 || fileSystem == FileSystemType.EXFAT
}

data class PartitionTable(val type: PartitionTableType, val partitions: List<Partition>) {
    /** Data partitions, largest first: the first one is what a user means by "the drive". */
    val userDataPartitions: List<Partition>
        get() = partitions.filter { it.isUserData }.sortedByDescending { it.sectorCount }
}

object PartitionRoleClassifier {
    private const val GUID_EFI_SYSTEM = "c12a7328-f81f-11d2-ba4b-00a0c93ec93b"
    private const val GUID_MICROSOFT_RESERVED = "e3c9e316-0b5c-4db8-817d-f92df00215ae"
    private const val GUID_WINDOWS_RECOVERY = "de94bba4-06d1-4d40-a16a-bfd50179d6ac"
    private const val GUID_LINUX_SWAP = "0657fd6d-a4ab-43c4-84e5-0933c84b4f4f"

    fun fromGptGuid(typeGuid: String): PartitionRole = when (typeGuid.lowercase(Locale.ROOT)) {
        GUID_EFI_SYSTEM -> PartitionRole.EFI_SYSTEM
        GUID_MICROSOFT_RESERVED, GUID_LINUX_SWAP -> PartitionRole.RESERVED
        GUID_WINDOWS_RECOVERY -> PartitionRole.RECOVERY
        else -> PartitionRole.DATA
    }

    fun fromMbrTypeByte(typeByte: Int): PartitionRole = when (typeByte) {
        0x12, 0x27 -> PartitionRole.RECOVERY // Compaq/OEM diagnostics, Windows RE
        0x82 -> PartitionRole.RESERVED // Linux swap
        0xEF -> PartitionRole.EFI_SYSTEM
        else -> PartitionRole.DATA
    }
}
