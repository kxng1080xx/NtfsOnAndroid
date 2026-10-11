package dev.ntfsfm.core.transport.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.Build
import dev.ntfsfm.core.block.BlockDeviceRemovedException
import dev.ntfsfm.core.transport.scsi.SenseData
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * USB Mass Storage Bulk-Only Transport: each SCSI command is CBW out, optional data phase, CSW in.
 * One command at a time per device, hence the mutex.
 */
class BulkOnlyTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val timeoutMs: Int = 5000,
) {
    enum class Direction { IN, OUT }

    data class CommandResult(val dataResidue: Long)

    private enum class CswStatus { PASSED, FAILED, PHASE_ERROR }

    private data class ParsedCsw(val status: CswStatus, val dataResidue: Long)

    private val mutex = Mutex()
    private val tagCounter = AtomicInteger(1)

    /** bulkTransfer caps at 16 KiB before Android 9; UsbRequest with a direct buffer goes far past that from 9 on. */
    val maxDataTransferBytes: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 256 * 1024 else LEGACY_MAX_TRANSFER

    val maxPacketSize: Int get() = bulkIn.maxPacketSize

    private val inRequest = UsbRequest().apply { initialize(connection, bulkIn) }
    private val outRequest = UsbRequest().apply { initialize(connection, bulkOut) }
    private val transferBuffer: ByteBuffer = ByteBuffer.allocateDirect(maxDataTransferBytes)

    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { inRequest.close() }
        runCatching { outRequest.close() }
        connection.releaseInterface(usbInterface)
        connection.close()
    }

    suspend fun executeCommand(
        cdb: ByteArray,
        direction: Direction,
        data: ByteArray = ByteArray(0),
        dataLength: Int = data.size,
        lun: Int = 0,
        allowRetryAfterReset: Boolean = true,
    ): CommandResult = mutex.withLock {
        withContext(Dispatchers.IO) { runCommand(cdb, direction, data, dataLength, lun, allowRetryAfterReset) }
    }

    // Caller holds the mutex. Retries recurse here, not through executeCommand, which would self-deadlock.
    private fun runCommand(
        cdb: ByteArray,
        direction: Direction,
        data: ByteArray,
        dataLength: Int,
        lun: Int,
        allowRetryAfterReset: Boolean,
    ): CommandResult {
        val tag = tagCounter.getAndIncrement()
        val cbw = buildCbw(tag, dataLength, direction, lun, cdb)
        if (bulkTransfer(bulkOut, cbw, cbw.size) < 0) throw BlockDeviceRemovedException()

        if (dataLength > 0 && transferDataPhase(direction, data, dataLength) < 0) {
            // A stalled data phase is legal: clear the halt, the CSW still follows.
            val endpoint = if (direction == Direction.IN) bulkIn else bulkOut
            if (!recoverStall(endpoint)) throw UsbStallRecoveryFailedException()
        }

        val csw = ByteArray(CSW_LENGTH)
        if (bulkTransfer(bulkIn, csw, CSW_LENGTH) < 0) {
            if (!recoverStall(bulkIn)) throw UsbStallRecoveryFailedException()
            if (bulkTransfer(bulkIn, csw, CSW_LENGTH) < 0) {
                massStorageReset()
                if (bulkTransfer(bulkIn, csw, CSW_LENGTH) < 0) throw BlockDeviceRemovedException()
            }
        }

        val parsed = parseCsw(csw, tag)
        return when (parsed.status) {
            CswStatus.PASSED -> CommandResult(parsed.dataResidue)
            CswStatus.FAILED -> throw ScsiCommandFailedException(null)
            CswStatus.PHASE_ERROR -> {
                if (!allowRetryAfterReset) throw ScsiCommandFailedException(null)
                massStorageReset()
                runCommand(cdb, direction, data, dataLength, lun, allowRetryAfterReset = false)
            }
        }
    }

    private fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int): Int =
        connection.bulkTransfer(endpoint, buffer, length, timeoutMs)

    /** Moves the data phase in [maxDataTransferBytes] chunks. Returns bytes moved, or -1 on failure. */
    private fun transferDataPhase(direction: Direction, data: ByteArray, dataLength: Int): Int {
        val isIn = direction == Direction.IN
        val request = if (isIn) inRequest else outRequest
        var moved = 0
        while (moved < dataLength) {
            val chunk = minOf(dataLength - moved, maxDataTransferBytes)
            transferBuffer.clear()
            if (!isIn) transferBuffer.put(data, moved, chunk)
            transferBuffer.position(0)
            transferBuffer.limit(chunk)
            if (!request.queue(transferBuffer)) return -1
            if (connection.requestWait(timeoutMs.toLong()) !== request) return -1
            if (isIn) {
                transferBuffer.position(0)
                transferBuffer.limit(chunk)
                transferBuffer.get(data, moved, chunk)
            }
            moved += chunk
        }
        return moved
    }

    /** CLEAR_FEATURE(ENDPOINT_HALT). */
    private fun recoverStall(endpoint: UsbEndpoint): Boolean =
        connection.controlTransfer(0x02, 0x01, 0, endpoint.address, null, 0, timeoutMs) >= 0

    /** Bulk-Only Mass Storage Reset, then clear both halts as the spec requires. */
    private fun massStorageReset(): Boolean {
        connection.controlTransfer(0x21, 0xFF, 0, usbInterface.id, null, 0, timeoutMs)
        recoverStall(bulkIn)
        return recoverStall(bulkOut)
    }

    private fun buildCbw(tag: Int, dataLength: Int, direction: Direction, lun: Int, cdb: ByteArray): ByteArray {
        require(cdb.size <= 16) { "CDB must be at most 16 bytes" }
        val cbw = ByteArray(CBW_LENGTH)
        writeLe32(cbw, 0, CBW_SIGNATURE)
        writeLe32(cbw, 4, tag.toLong())
        writeLe32(cbw, 8, dataLength.toLong())
        cbw[12] = if (direction == Direction.IN) 0x80.toByte() else 0
        cbw[13] = (lun and 0x0F).toByte()
        cbw[14] = (cdb.size and 0x1F).toByte()
        cdb.copyInto(cbw, 15)
        return cbw
    }

    private fun parseCsw(csw: ByteArray, expectedTag: Int): ParsedCsw {
        if (readLe32(csw, 0) != CSW_SIGNATURE) throw ScsiCommandFailedException(null)
        if (readLe32(csw, 4) != expectedTag.toLong()) throw ScsiCommandFailedException(null)
        val status = when (csw[12].toInt()) {
            0 -> CswStatus.PASSED
            1 -> CswStatus.FAILED
            else -> CswStatus.PHASE_ERROR
        }
        return ParsedCsw(status, readLe32(csw, 8))
    }

    private fun writeLe32(buf: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) buf[offset + i] = (value shr (i * 8)).toByte()
    }

    private fun readLe32(buf: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (buf[offset + i].toLong() and 0xFF)
        return v
    }

    companion object {
        const val MASS_STORAGE_CLASS = 0x08
        const val SCSI_SUBCLASS = 0x06
        const val BULK_ONLY_PROTOCOL = 0x50

        private const val CBW_LENGTH = 31
        private const val CSW_LENGTH = 13
        private const val CBW_SIGNATURE = 0x43425355L // "USBC"
        private const val CSW_SIGNATURE = 0x53425355L // "USBS"
        private const val LEGACY_MAX_TRANSFER = 16 * 1024
    }
}

class ScsiCommandFailedException(val sense: SenseData?) :
    Exception("SCSI command failed" + (sense?.let { " (sense key=0x${it.senseKey.toString(16)})" } ?: ""))

class UsbStallRecoveryFailedException : Exception("USB endpoint did not recover after CLEAR_FEATURE/reset")
