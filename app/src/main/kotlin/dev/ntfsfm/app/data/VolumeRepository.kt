package dev.ntfsfm.app.data

import android.hardware.usb.UsbDevice
import android.os.Environment
import android.util.Log
import dev.ntfsfm.core.block.BlockDevice
import dev.ntfsfm.core.block.OffsetBlockDevice
import dev.ntfsfm.core.block.partition.FileSystemType
import dev.ntfsfm.core.block.partition.Partition
import dev.ntfsfm.core.block.partition.PartitionTableReader
import dev.ntfsfm.core.fat32.Fat32Vfs
import dev.ntfsfm.core.ntfs.NtfsVfs
import dev.ntfsfm.core.transport.usb.BulkOnlyTransport
import dev.ntfsfm.core.transport.usb.UsbBlockDevice
import dev.ntfsfm.core.transport.usb.UsbMassStorageManager
import dev.ntfsfm.core.vfs.LocalVfs
import dev.ntfsfm.core.vfs.VirtualFileSystem
import dev.ntfsfm.core.vfs.VolumeIdentity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "VolumeRepository"

/** What the home screen shows for one USB volume (or a whole drive, when it can't be opened). */
sealed interface UsbVolumeState {
    val device: UsbDevice

    data class Ready(override val device: UsbDevice, val vfs: VirtualFileSystem, val volumeId: String, val displayName: String) : UsbVolumeState
    data class NeedsPermission(override val device: UsbDevice) : UsbVolumeState
    data class Unsupported(override val device: UsbDevice, val reason: String) : UsbVolumeState
    data class Failed(override val device: UsbDevice, val reason: String) : UsbVolumeState
}

/**
 * Turns attached USB drives into mounted volumes. A drive is opened once and kept open (a
 * "session") until it is unplugged, so a refresh doesn't remount it.
 */
@Singleton
class VolumeRepository @Inject constructor(
    private val usbManager: UsbMassStorageManager,
) {
    private class DeviceSession(val transport: BulkOnlyTransport, val blockDevice: BlockDevice, val volumes: List<UsbVolumeState>)

    // Keyed by UsbDevice.deviceName (the /dev/bus/usb path), unique while plugged in.
    private val sessions = mutableMapOf<String, DeviceSession>()
    private val sessionLock = Mutex()
    private val refreshTrigger = MutableStateFlow(0)

    fun internalStorageVolume(): VirtualFileSystem =
        LocalVfs(Environment.getExternalStorageDirectory(), VolumeIdentity("internal-storage", "Internal storage"))

    fun usbVolumeStates(): Flow<List<UsbVolumeState>> =
        combine(usbManager.deviceListUpdates(), refreshTrigger) { devices, _ -> devices }
            .map { devices ->
                closeSessionsForDetachedDevices(devices)
                devices.flatMap { resolveDevice(it) }
            }
            .flowOn(Dispatchers.IO)

    suspend fun requestPermission(device: UsbDevice): Boolean {
        val granted = usbManager.requestPermission(device)
        refreshTrigger.value++
        return granted
    }

    fun refresh() {
        refreshTrigger.value++
    }

    private suspend fun closeSessionsForDetachedDevices(attached: List<UsbDevice>) = sessionLock.withLock {
        val attachedNames = attached.map { it.deviceName }.toSet()
        for (name in sessions.keys.filterNot { it in attachedNames }) {
            val session = sessions.remove(name) ?: continue
            // The device is already gone; closing just releases our handles.
            runCatching { session.blockDevice.close() }
            runCatching { session.transport.close() }
        }
    }

    private suspend fun resolveDevice(device: UsbDevice): List<UsbVolumeState> {
        if (!usbManager.hasPermission(device)) return listOf(UsbVolumeState.NeedsPermission(device))
        return sessionLock.withLock {
            sessions[device.deviceName]?.let { return@withLock it.volumes }
            val transport = usbManager.openTransport(device)
                ?: return@withLock listOf(UsbVolumeState.Failed(device, "Could not claim the USB interface"))
            try {
                val blockDevice = UsbBlockDevice.open(transport)
                val table = PartitionTableReader.read(blockDevice)
                val result = if (table.partitions.isEmpty()) {
                    listOf(UsbVolumeState.Unsupported(device, "This drive has no readable partition table"))
                } else {
                    val dataPartitions = table.userDataPartitions.filter { it.isMountableFileSystem }
                    if (dataPartitions.isEmpty()) {
                        listOf(UsbVolumeState.Unsupported(device, unsupportedReasonFor(table.partitions)))
                    } else {
                        dataPartitions.map { mountPartition(device, blockDevice, it, driveHasSeveralVolumes = dataPartitions.size > 1) }
                    }
                }
                sessions[device.deviceName] = DeviceSession(transport, blockDevice, result)
                result
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read ${device.deviceName}", e)
                runCatching { transport.close() }
                listOf(UsbVolumeState.Failed(device, e.message ?: "Could not read this drive"))
            }
        }
    }

    private suspend fun mountPartition(device: UsbDevice, blockDevice: BlockDevice, partition: Partition, driveHasSeveralVolumes: Boolean): UsbVolumeState {
        val volumeId = ActiveVolumesHolder.USB_VOLUME_ID_PREFIX + device.deviceId + "-" + partition.index
        val displayName = displayNameFor(device, partition, driveHasSeveralVolumes)
        return try {
            val vfs = when (partition.fileSystem) {
                FileSystemType.FAT32 -> Fat32Vfs.mount(blockDevice, partition.startSector, volumeId, displayName)
                FileSystemType.NTFS -> NtfsVfs.mount(OffsetBlockDevice(blockDevice, partition.startSector, partition.sectorCount), volumeId, displayName)
                FileSystemType.EXFAT -> return UsbVolumeState.Unsupported(device, "$displayName is exFAT, which isn't supported yet")
                else -> return UsbVolumeState.Unsupported(device, "$displayName has an unrecognized filesystem")
            }
            UsbVolumeState.Ready(device, vfs, volumeId, displayName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mount partition ${partition.index} on ${device.deviceName}", e)
            UsbVolumeState.Failed(device, "Couldn't open $displayName: ${e.message ?: "unknown error"}")
        }
    }

    private fun displayNameFor(device: UsbDevice, partition: Partition, driveHasSeveralVolumes: Boolean): String {
        val driveName = device.productName?.takeIf { it.isNotBlank() } ?: "USB drive"
        partition.label?.takeIf { it.isNotBlank() }?.let { return it }
        return if (driveHasSeveralVolumes) "$driveName (partition ${partition.index + 1})" else driveName
    }

    private fun unsupportedReasonFor(partitions: List<Partition>): String = when {
        partitions.any { it.fileSystem == FileSystemType.EXFAT } -> "This drive is exFAT, which isn't supported yet"
        partitions.none { it.isUserData } -> "This drive only has system partitions, no user data"
        else -> "No NTFS or FAT32 partition found on this drive"
    }
}
