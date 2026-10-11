package dev.ntfsfm.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.ntfsfm.app.R
import dev.ntfsfm.app.data.ActiveVolumesHolder
import dev.ntfsfm.core.block.BlockDeviceRemovedException
import dev.ntfsfm.core.transfer.ConflictPolicy
import dev.ntfsfm.core.transfer.ItemState
import dev.ntfsfm.core.transfer.TransferController
import dev.ntfsfm.core.transfer.TransferEngine
import dev.ntfsfm.core.transfer.TransferMode
import dev.ntfsfm.core.transfer.TransferProgress
import dev.ntfsfm.core.vfs.VirtualFileSystem
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

private const val TAG = "TransferService"

/** Runs one copy/move at a time in the foreground, so it survives the app going to the background. */
@AndroidEntryPoint
class TransferForegroundService : Service() {
    @Inject lateinit var activeVolumes: ActiveVolumesHolder
    @Inject lateinit var transferEngine: TransferEngine
    @Inject lateinit var transferState: TransferStateHolder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transferJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            transferJob?.cancel()
            return START_NOT_STICKY
        }
        if (intent != null && transferJob?.isActive != true) startTransfer(intent)
        return START_NOT_STICKY
    }

    private fun startTransfer(intent: Intent) {
        val sourceVolumeId = intent.getStringExtra(EXTRA_SOURCE_VOLUME_ID) ?: return stopSelf()
        val sourcePaths = intent.getStringArrayExtra(EXTRA_SOURCE_PATHS)?.toList() ?: return stopSelf()
        val destVolumeId = intent.getStringExtra(EXTRA_DEST_VOLUME_ID) ?: return stopSelf()
        val destPath = intent.getStringExtra(EXTRA_DEST_PATH) ?: ""
        val mode = TransferMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: TransferMode.COPY.name)
        val source = activeVolumes.get(sourceVolumeId) ?: return stopSelf()
        val dest = activeVolumes.get(destVolumeId) ?: return stopSelf()

        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(null))
        acquireWakeLock()
        val controller = TransferController()
        transferState.start(controller)
        transferJob = serviceScope.launch { runTransfer(source, sourcePaths, dest, destPath, mode, controller) }
    }

    private suspend fun runTransfer(
        source: VirtualFileSystem,
        sourcePaths: List<String>,
        dest: VirtualFileSystem,
        destPath: String,
        mode: TransferMode,
        controller: TransferController,
    ) {
        try {
            transferEngine.execute(source, sourcePaths, dest, destPath, mode, controller, ConflictPolicy.ASK) { entry ->
                transferState.askConflict(entry)
            }.flowOn(Dispatchers.IO).collect { progress ->
                val item = progress.currentItem
                if (item != null && item.state == ItemState.FAILED) Log.w(TAG, "item failed: ${item.sourcePath} -- ${item.error}")
                transferState.update(progress)
                updateNotification(progress)
            }
        } catch (e: CancellationException) {
            transferState.update(lastProgress().copy(isDone = true, wasCancelled = true))
            throw e
        } catch (e: BlockDeviceRemovedException) {
            transferState.update(lastProgress().copy(isDone = true, fatalError = "Device removed during transfer"))
        } catch (e: Exception) {
            Log.w(TAG, "transfer failed", e)
            transferState.update(lastProgress().copy(isDone = true, fatalError = e.message ?: "Transfer failed"))
        } finally {
            transferState.finish()
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun lastProgress() = transferState.progress.value ?: TransferProgress(0, 0, 0, 0, 0, null)

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ntfsfm:transfer").apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        } catch (e: SecurityException) {
            Log.w(TAG, "WAKE_LOCK unavailable; transfer may stall while the screen is off", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "File transfers", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(progress: TransferProgress?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transfer_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progress == null || progress.bytesTotal <= 0) {
            builder.setContentTitle("Preparing transfer").setProgress(0, 0, true)
        } else {
            val percent = (progress.bytesTransferred * 100 / progress.bytesTotal).toInt()
            builder.setContentTitle(progress.currentItem?.displayName ?: "Copying")
                .setContentText("$percent% - ${progress.itemsCompleted}/${progress.itemsTotal} items")
                .setProgress(100, percent, false)
        }
        return builder.build()
    }

    private fun updateNotification(progress: TransferProgress) {
        if (progress.isDone) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(progress))
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_CANCEL = "dev.ntfsfm.app.action.CANCEL_TRANSFER"
        const val EXTRA_SOURCE_VOLUME_ID = "source_volume_id"
        const val EXTRA_SOURCE_PATHS = "source_paths"
        const val EXTRA_DEST_VOLUME_ID = "dest_volume_id"
        const val EXTRA_DEST_PATH = "dest_path"
        const val EXTRA_MODE = "mode"
        private const val CHANNEL_ID = "transfers"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        fun cancelIntent(context: Context): Intent =
            Intent(context, TransferForegroundService::class.java).setAction(ACTION_CANCEL)

        fun buildIntent(
            context: Context,
            sourceVolumeId: String,
            sourcePaths: List<String>,
            destVolumeId: String,
            destPath: String,
            mode: TransferMode,
        ): Intent = Intent(context, TransferForegroundService::class.java).apply {
            putExtra(EXTRA_SOURCE_VOLUME_ID, sourceVolumeId)
            putExtra(EXTRA_SOURCE_PATHS, sourcePaths.toTypedArray())
            putExtra(EXTRA_DEST_VOLUME_ID, destVolumeId)
            putExtra(EXTRA_DEST_PATH, destPath)
            putExtra(EXTRA_MODE, mode.name)
        }
    }
}
