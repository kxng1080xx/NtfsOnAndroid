package dev.ntfsfm.core.transport.scsi

/** CDB builders for the SCSI Block Commands subset a USB stick actually needs. Multi-byte fields are big-endian. */
object ScsiCommands {
    fun testUnitReady(): ByteArray = ByteArray(6)

    fun requestSense(allocationLength: Int = 18): ByteArray = byteArrayOf(0x03, 0, 0, 0, allocationLength.toByte(), 0)

    fun inquiry(allocationLength: Int = 36): ByteArray = byteArrayOf(0x12, 0, 0, 0, allocationLength.toByte(), 0)

    fun readCapacity10(): ByteArray = ByteArray(10).also { it[0] = 0x25 }

    /** SERVICE ACTION IN(16) / READ CAPACITY(16), for drives past 2 TiB. */
    fun readCapacity16(allocationLength: Int = 32): ByteArray = ByteArray(16).also {
        it[0] = 0x9E.toByte()
        it[1] = 0x10
        writeBe32(it, 10, allocationLength.toLong())
    }

    fun read10(startLba: Long, blockCount: Int): ByteArray = cdb10(0x28, startLba, blockCount)

    fun write10(startLba: Long, blockCount: Int): ByteArray = cdb10(0x2A, startLba, blockCount)

    fun read16(startLba: Long, blockCount: Int): ByteArray = cdb16(0x88, startLba, blockCount)

    fun write16(startLba: Long, blockCount: Int): ByteArray = cdb16(0x8A, startLba, blockCount)

    private fun cdb10(opcode: Int, startLba: Long, blockCount: Int) = ByteArray(10).also {
        it[0] = opcode.toByte()
        writeBe32(it, 2, startLba)
        it[7] = (blockCount shr 8).toByte()
        it[8] = blockCount.toByte()
    }

    private fun cdb16(opcode: Int, startLba: Long, blockCount: Int) = ByteArray(16).also {
        it[0] = opcode.toByte()
        writeBe64(it, 2, startLba)
        writeBe32(it, 10, blockCount.toLong())
    }

    private fun writeBe32(buf: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) buf[offset + i] = (value shr (24 - i * 8)).toByte()
    }

    private fun writeBe64(buf: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) buf[offset + i] = (value shr (56 - i * 8)).toByte()
    }
}

data class ReadCapacityResult(val lastLba: Long, val blockSize: Int) {
    /** READ CAPACITY(10) answers 0xFFFFFFFF when the drive is too big for it; ask again with (16). */
    val isMaxSentinel: Boolean get() = lastLba == 0xFFFFFFFFL

    companion object {
        fun parse10(response: ByteArray) = ReadCapacityResult(readBe(response, 0, 4), readBe(response, 4, 4).toInt())

        fun parse16(response: ByteArray) = ReadCapacityResult(readBe(response, 0, 8), readBe(response, 8, 4).toInt())

        private fun readBe(buf: ByteArray, offset: Int, length: Int): Long {
            var v = 0L
            for (i in 0 until length) v = (v shl 8) or (buf[offset + i].toLong() and 0xFF)
            return v
        }
    }
}

/** Fixed-format sense data (response code 0x70/0x71). */
data class SenseData(val senseKey: Int, val additionalSenseCode: Int, val additionalSenseCodeQualifier: Int) {
    val isUnitAttention: Boolean get() = senseKey == 0x06

    companion object {
        fun parse(response: ByteArray): SenseData? {
            if (response.size < 14) return null
            return SenseData(
                senseKey = response[2].toInt() and 0x0F,
                additionalSenseCode = response[12].toInt() and 0xFF,
                additionalSenseCodeQualifier = response[13].toInt() and 0xFF,
            )
        }
    }
}
