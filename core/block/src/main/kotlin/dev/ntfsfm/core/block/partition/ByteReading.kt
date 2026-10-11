package dev.ntfsfm.core.block.partition

// Little-endian field readers for on-disk structures (boot sectors, MBR, GPT, FAT).

fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

fun ByteArray.u16le(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

fun ByteArray.u32le(offset: Int): Long =
    (u8(offset).toLong()) or (u8(offset + 1).toLong() shl 8) or
        (u8(offset + 2).toLong() shl 16) or (u8(offset + 3).toLong() shl 24)

fun ByteArray.u64le(offset: Int): Long {
    var result = 0L
    for (i in 7 downTo 0) result = (result shl 8) or u8(offset + i).toLong()
    return result
}

fun ByteArray.asciiString(offset: Int, length: Int): String =
    String(this, offset, length, Charsets.US_ASCII).trimEnd('\u0000', ' ')

fun ByteArray.utf16leString(offset: Int, length: Int): String =
    String(this, offset, length, Charsets.UTF_16LE).trimEnd('\u0000')
