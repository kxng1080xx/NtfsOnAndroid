package dev.ntfsfm.core.transport.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val ACTION_USB_PERMISSION = "dev.ntfsfm.core.transport.USB_PERMISSION"

/** Finds attached USB mass-storage devices, gets permission for them and opens a transport. */
class UsbMassStorageManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun massStorageDevices(): List<UsbDevice> = usbManager.deviceList.values.filter { it.massStorageInterface() != null }

    /** The current list, then a fresh one on every attach/detach. */
    fun deviceListUpdates(): Flow<List<UsbDevice>> = callbackFlow {
        trySend(massStorageDevices())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                trySend(massStorageDevices())
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** Shows the system permission dialog; true if the user allowed access. */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    result.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Mutable: UsbManager fills in the grant extras. Explicit package keeps that safe.
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        usbManager.requestPermission(device, pendingIntent)
        return try {
            result.await()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    /** Claims the mass-storage interface. Null if the device has none, or it is busy or gone. */
    fun openTransport(device: UsbDevice): BulkOnlyTransport? {
        val iface = device.massStorageInterface() ?: return null
        val connection = usbManager.openDevice(device) ?: return null
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            return null
        }
        val (bulkIn, bulkOut) = iface.bulkEndpoints() ?: run {
            connection.releaseInterface(iface)
            connection.close()
            return null
        }
        return BulkOnlyTransport(connection, iface, bulkIn, bulkOut)
    }
}

private fun UsbDevice.massStorageInterface(): UsbInterface? =
    (0 until interfaceCount).map { getInterface(it) }.firstOrNull {
        it.interfaceClass == BulkOnlyTransport.MASS_STORAGE_CLASS &&
            it.interfaceSubclass == BulkOnlyTransport.SCSI_SUBCLASS &&
            it.interfaceProtocol == BulkOnlyTransport.BULK_ONLY_PROTOCOL
    }

private fun UsbInterface.bulkEndpoints(): Pair<UsbEndpoint, UsbEndpoint>? {
    var bulkIn: UsbEndpoint? = null
    var bulkOut: UsbEndpoint? = null
    for (i in 0 until endpointCount) {
        val endpoint = getEndpoint(i)
        if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
        if (endpoint.direction == UsbConstants.USB_DIR_IN) bulkIn = endpoint else bulkOut = endpoint
    }
    return if (bulkIn != null && bulkOut != null) bulkIn to bulkOut else null
}
