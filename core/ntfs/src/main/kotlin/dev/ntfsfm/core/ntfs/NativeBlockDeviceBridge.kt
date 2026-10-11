package dev.ntfsfm.core.ntfs

import android.util.Log
import dev.ntfsfm.core.block.BlockDevice
import kotlinx.coroutines.runBlocking

private const val TAG = "NtfsBlockBridge"

/**
 * What ntfs_device_bridge.c calls into. Native code is synchronous, so each call blocks
 * on the suspending [BlockDevice]. Return values follow the C convention: bytes or -1.
 * Method names and signatures are looked up by name from C; don't rename them.
 */
class NativeBlockDeviceBridge(private val device: BlockDevice) {

    fun getSectorSize(): Int = device.sectorSize

    fun getSectorCount(): Long = device.sectorCount

    fun readSectorsBlocking(startSector: Long, sectorCount: Int, buffer: ByteArray): Int = try {
        runBlocking { device.readSectors(startSector, sectorCount, buffer) }
        device.sectorSize * sectorCount
    } catch (e: Throwable) {
        Log.w(
            TAG,
            "read failed: startSector=$startSector sectorCount=$sectorCount bufferSize=${buffer.size} " +
                "deviceSectors=${device.sectorCount} sectorSize=${device.sectorSize}",
            e,
        )
        -1
    }

    fun writeSectorsBlocking(startSector: Long, sectorCount: Int, buffer: ByteArray): Int = try {
        runBlocking { device.writeSectors(startSector, sectorCount, buffer) }
        device.sectorSize * sectorCount
    } catch (e: Throwable) {
        Log.w(
            TAG,
            "write failed: startSector=$startSector sectorCount=$sectorCount bufferSize=${buffer.size} " +
                "deviceSectors=${device.sectorCount}",
            e,
        )
        -1
    }

    fun flushBlocking(): Boolean = try {
        runBlocking { device.flush() }
        true
    } catch (e: Throwable) {
        Log.w(TAG, "flush failed", e)
        false
    }
}
