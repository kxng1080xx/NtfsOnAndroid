package dev.ntfsfm.core.transport.usb

import dev.ntfsfm.core.block.BlockDevice
import dev.ntfsfm.core.block.BlockDeviceException
import dev.ntfsfm.core.transport.scsi.ReadCapacityResult
import dev.ntfsfm.core.transport.scsi.ScsiCommands
import kotlinx.coroutines.delay

/** A USB stick or disk as a [BlockDevice], speaking SCSI over [BulkOnlyTransport]. */
class UsbBlockDevice private constructor(
    private val transport: BulkOnlyTransport,
    override val sectorSize: Int,
    override val sectorCount: Long,
    private val use16ByteCommands: Boolean,
) : BlockDevice {

    private val maxSectorsPerTransfer =
        (transport.maxDataTransferBytes / sectorSize).coerceAtLeast(1).coerceAtMost(MAX_BLOCKS_PER_COMMAND)

    override suspend fun readSectors(startSector: Long, sectorCount: Int, into: ByteArray, offset: Int) {
        forEachChunk(startSector, sectorCount) { chunkStart, chunkCount, bufOffset ->
            val cdb = if (use16ByteCommands) ScsiCommands.read16(chunkStart, chunkCount) else ScsiCommands.read10(chunkStart, chunkCount)
            val length = chunkCount * sectorSize
            val destOffset = offset + bufOffset
            val needsScratch = destOffset != 0 || length != into.size
            val target = if (needsScratch) ByteArray(length) else into
            transport.executeCommand(cdb, BulkOnlyTransport.Direction.IN, target, length)
            if (needsScratch) System.arraycopy(target, 0, into, destOffset, length)
        }
    }

    override suspend fun writeSectors(startSector: Long, sectorCount: Int, from: ByteArray, offset: Int) {
        forEachChunk(startSector, sectorCount) { chunkStart, chunkCount, bufOffset ->
            val cdb = if (use16ByteCommands) ScsiCommands.write16(chunkStart, chunkCount) else ScsiCommands.write10(chunkStart, chunkCount)
            val length = chunkCount * sectorSize
            val srcOffset = offset + bufOffset
            val source = if (srcOffset == 0 && length == from.size) from else from.copyOfRange(srcOffset, srcOffset + length)
            transport.executeCommand(cdb, BulkOnlyTransport.Direction.OUT, source, length)
        }
    }

    // ponytail: WRITE(10) without FUA; most sticks have no volatile cache worth a SYNCHRONIZE CACHE.
    override suspend fun flush() = Unit

    override suspend fun close() = transport.close()

    private inline fun forEachChunk(startSector: Long, sectorCount: Int, block: (start: Long, count: Int, bufOffset: Int) -> Unit) {
        var remaining = sectorCount
        var currentStart = startSector
        var bufOffset = 0
        while (remaining > 0) {
            val chunkCount = minOf(remaining, maxSectorsPerTransfer)
            block(currentStart, chunkCount, bufOffset)
            remaining -= chunkCount
            currentStart += chunkCount
            bufOffset += sectorSize * chunkCount
        }
    }

    companion object {
        private const val MAX_BLOCKS_PER_COMMAND = 0xFFFF
        private const val INQUIRY_LENGTH = 36
        private const val SENSE_LENGTH = 18
        private const val MAX_READY_RETRIES = 20
        private const val READY_RETRY_DELAY_MS = 150L

        /**
         * INQUIRY, then TEST UNIT READY until the drive spins up (REQUEST SENSE between tries
         * clears unit-attention), then READ CAPACITY.
         */
        suspend fun open(transport: BulkOnlyTransport, lun: Int = 0): UsbBlockDevice {
            // Some drives refuse anything until they have answered an INQUIRY; the answer itself is unused.
            runCatching {
                transport.executeCommand(ScsiCommands.inquiry(), BulkOnlyTransport.Direction.IN, ByteArray(INQUIRY_LENGTH), lun = lun)
            }
            var lastFailure: Throwable? = null
            repeat(MAX_READY_RETRIES) {
                try {
                    transport.executeCommand(ScsiCommands.testUnitReady(), BulkOnlyTransport.Direction.IN, lun = lun)
                    return openReady(transport, lun)
                } catch (e: Exception) {
                    lastFailure = e
                    runCatching {
                        transport.executeCommand(ScsiCommands.requestSense(), BulkOnlyTransport.Direction.IN, ByteArray(SENSE_LENGTH), lun = lun)
                    }
                    delay(READY_RETRY_DELAY_MS)
                }
            }
            throw BlockDeviceException("Drive did not become ready", lastFailure)
        }

        private suspend fun openReady(transport: BulkOnlyTransport, lun: Int): UsbBlockDevice {
            val capacityBuf = ByteArray(8)
            transport.executeCommand(ScsiCommands.readCapacity10(), BulkOnlyTransport.Direction.IN, capacityBuf, lun = lun)
            var capacity = ReadCapacityResult.parse10(capacityBuf)
            val use16 = capacity.isMaxSentinel
            if (use16) {
                val capacity16Buf = ByteArray(32)
                transport.executeCommand(ScsiCommands.readCapacity16(), BulkOnlyTransport.Direction.IN, capacity16Buf, lun = lun)
                capacity = ReadCapacityResult.parse16(capacity16Buf)
            }
            return UsbBlockDevice(transport, capacity.blockSize, capacity.lastLba + 1, use16)
        }
    }
}
