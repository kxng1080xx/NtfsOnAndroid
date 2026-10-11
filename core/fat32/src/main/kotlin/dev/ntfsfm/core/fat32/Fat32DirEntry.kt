package dev.ntfsfm.core.fat32

import dev.ntfsfm.core.block.partition.u16le
import dev.ntfsfm.core.block.partition.u32le
import dev.ntfsfm.core.block.partition.u8
import dev.ntfsfm.core.vfs.VfsAttributes
import dev.ntfsfm.core.vfs.VfsTimestamps
import java.util.Calendar
import java.util.Locale

const val ATTR_READ_ONLY = 0x01
const val ATTR_HIDDEN = 0x02
const val ATTR_SYSTEM = 0x04
const val ATTR_VOLUME_ID = 0x08
const val ATTR_DIRECTORY = 0x10
const val ATTR_ARCHIVE = 0x20
const val ATTR_LONG_NAME = 0x0F

internal const val ENTRY_SIZE = 32
private const val DELETED_MARKER = 0xE5
private const val END_MARKER = 0x00
private const val LAST_LFN_FLAG = 0x40
private const val LFN_CHARS = 13

/** One file or directory as stored in its parent: the 8.3 entry plus any LFN entries before it. */
data class Fat32DirEntry(
    val longName: String?,
    val shortName: String,
    val attributes: Int,
    val firstCluster: Long,
    val fileSize: Long,
    val createdAt: Long?,
    val modifiedAt: Long,
    /** Byte offsets in the directory of every slot this entry uses; the 8.3 entry is last. */
    val entryByteOffsets: List<Int>,
) {
    val displayName: String get() = longName ?: shortName
    val isDirectory: Boolean get() = attributes and ATTR_DIRECTORY != 0

    fun toVfsAttributes() = VfsAttributes(
        readOnly = attributes and ATTR_READ_ONLY != 0,
        hidden = attributes and ATTR_HIDDEN != 0,
        system = attributes and ATTR_SYSTEM != 0,
        archive = attributes and ATTR_ARCHIVE != 0,
    )

    fun toVfsTimestamps() = VfsTimestamps(createdAt = createdAt, modifiedAt = modifiedAt, accessedAt = null)
}

object Fat32DirEntryCodec {
    fun parseAll(directoryBytes: ByteArray): List<Fat32DirEntry> {
        val entries = mutableListOf<Fat32DirEntry>()
        val pendingLfnParts = ArrayDeque<String>()
        var offset = 0
        while (offset + ENTRY_SIZE <= directoryBytes.size) {
            val first = directoryBytes.u8(offset)
            if (first == END_MARKER) break
            val attr = directoryBytes.u8(offset + 11)
            when {
                first == DELETED_MARKER -> pendingLfnParts.clear()
                attr == ATTR_LONG_NAME -> pendingLfnParts.addFirst(readLfnChars(directoryBytes, offset)) // stored last part first
                attr and ATTR_VOLUME_ID != 0 -> pendingLfnParts.clear()
                else -> {
                    val lfnCount = pendingLfnParts.size
                    val longName = pendingLfnParts.joinToString("").ifBlank { null }
                    pendingLfnParts.clear()
                    val entryStart = maxOf(offset - lfnCount * ENTRY_SIZE, 0)
                    entries += Fat32DirEntry(
                        longName = longName,
                        shortName = decodeShortName(directoryBytes, offset),
                        attributes = attr,
                        firstCluster = (directoryBytes.u16le(offset + 20).toLong() shl 16) or directoryBytes.u16le(offset + 26).toLong(),
                        fileSize = directoryBytes.u32le(offset + 28),
                        createdAt = decodeFatDateTime(directoryBytes.u16le(offset + 16), directoryBytes.u16le(offset + 14)),
                        modifiedAt = decodeFatDateTime(directoryBytes.u16le(offset + 24), directoryBytes.u16le(offset + 22)) ?: 0L,
                        entryByteOffsets = (entryStart..offset step ENTRY_SIZE).toList(),
                    )
                }
            }
            offset += ENTRY_SIZE
        }
        return entries
    }

    /** LFN entries (if [longName] is set) followed by the 8.3 entry, in on-disk order. */
    fun encode(longName: String?, shortName: String, attributes: Int, firstCluster: Long, fileSize: Long, modifiedAt: Long): List<ByteArray> {
        val shortNameBytes = encodeShortName(shortName)
        val result = mutableListOf<ByteArray>()
        if (longName != null) result += encodeLfnEntries(longName, shortNameChecksum(shortNameBytes))
        val shortEntry = ByteArray(ENTRY_SIZE)
        shortNameBytes.copyInto(shortEntry)
        shortEntry[11] = attributes.toByte()
        val (date, time) = encodeFatDateTime(modifiedAt)
        shortEntry.writeU16le(14, time) // created
        shortEntry.writeU16le(16, date)
        shortEntry.writeU16le(18, date) // accessed
        shortEntry.writeU16le(20, (firstCluster shr 16).toInt())
        shortEntry.writeU16le(22, time) // modified
        shortEntry.writeU16le(24, date)
        shortEntry.writeU16le(26, (firstCluster and 0xFFFF).toInt())
        shortEntry.writeU32le(28, fileSize)
        result += shortEntry
        return result
    }

    fun deletedMarkerEntry(): ByteArray = ByteArray(ENTRY_SIZE).also { it[0] = DELETED_MARKER.toByte() }

    private val lfnCharOffsets = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)

    private fun readLfnChars(buf: ByteArray, offset: Int): String {
        val chars = StringBuilder()
        for (charOffset in lfnCharOffsets) {
            val code = buf.u16le(offset + charOffset)
            if (code == 0) break
            if (code != 0xFFFF) chars.append(code.toChar())
        }
        return chars.toString()
    }

    private fun encodeLfnEntries(name: String, checksum: Int): List<ByteArray> {
        val chunks = name.chunked(LFN_CHARS)
        return chunks.mapIndexed { index, chunk ->
            val entry = ByteArray(ENTRY_SIZE)
            val order = index + 1
            entry[0] = (if (index == chunks.lastIndex) order or LAST_LFN_FLAG else order).toByte()
            entry[11] = ATTR_LONG_NAME.toByte()
            entry[13] = checksum.toByte()
            // Name, then one 0x0000 terminator if there is room, then 0xFFFF padding.
            lfnCharOffsets.forEachIndexed { i, charOffset ->
                val code = when {
                    i < chunk.length -> chunk[i].code
                    i == chunk.length -> 0
                    else -> 0xFFFF
                }
                entry.writeU16le(charOffset, code)
            }
            entry
        }.asReversed() // highest order first on disk
    }

    private fun decodeShortName(buf: ByteArray, offset: Int): String {
        val name = String(buf, offset, 8, Charsets.US_ASCII).trimEnd(' ')
        val ext = String(buf, offset + 8, 3, Charsets.US_ASCII).trimEnd(' ')
        return if (ext.isEmpty()) name else "$name.$ext"
    }

    private fun encodeShortName(shortName: String): ByteArray {
        val result = ByteArray(11) { ' '.code.toByte() }
        if (shortName == "." || shortName == "..") {
            shortName.toByteArray(Charsets.US_ASCII).copyInto(result)
            return result
        }
        val base = if ('.' in shortName) shortName.substringBeforeLast('.') else shortName
        val ext = if ('.' in shortName) shortName.substringAfterLast('.') else ""
        base.take(8).uppercase(Locale.ROOT).toByteArray(Charsets.US_ASCII).copyInto(result, 0)
        ext.take(3).uppercase(Locale.ROOT).toByteArray(Charsets.US_ASCII).copyInto(result, 8)
        return result
    }

    private fun shortNameChecksum(nameBytes: ByteArray): Int {
        var sum = 0
        for (b in nameBytes) sum = (((sum and 1) shl 7) + (sum ushr 1) + (b.toInt() and 0xFF)) and 0xFF
        return sum
    }

    /** FAT stores local time; date 0 means "not set". */
    private fun decodeFatDateTime(date: Int, time: Int): Long? {
        if (date == 0) return null
        val month = (date shr 5) and 0x0F
        val day = date and 0x1F
        if (month !in 1..12 || day !in 1..31) return null
        return Calendar.getInstance().apply {
            clear()
            set((date shr 9) + 1980, month - 1, day, (time shr 11) and 0x1F, (time shr 5) and 0x3F, (time and 0x1F) * 2)
        }.timeInMillis
    }

    /** (date, time) in FAT's packed local-time format. */
    internal fun encodeFatDateTime(epochMillis: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        val date = ((cal.get(Calendar.YEAR) - 1980) shl 9) or ((cal.get(Calendar.MONTH) + 1) shl 5) or cal.get(Calendar.DAY_OF_MONTH)
        val time = (cal.get(Calendar.HOUR_OF_DAY) shl 11) or (cal.get(Calendar.MINUTE) shl 5) or (cal.get(Calendar.SECOND) / 2)
        return date to time
    }
}
