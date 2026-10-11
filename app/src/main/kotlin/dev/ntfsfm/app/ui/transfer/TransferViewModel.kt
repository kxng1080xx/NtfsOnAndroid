package dev.ntfsfm.app.ui.transfer

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ntfsfm.app.service.PendingConflict
import dev.ntfsfm.app.service.TransferForegroundService
import dev.ntfsfm.app.service.TransferStateHolder
import dev.ntfsfm.core.transfer.ConflictAnswer
import dev.ntfsfm.core.transfer.TransferProgress
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val transferState: TransferStateHolder,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    val progress: StateFlow<TransferProgress?> = transferState.progress
    val isPaused: StateFlow<Boolean> = transferState.isPaused
    val isHidden: StateFlow<Boolean> = transferState.isHidden
    val pendingConflict: StateFlow<PendingConflict?> = transferState.pendingConflict

    // Via the service, so the notification's Pause/Resume button updates as well.
    fun pause() = sendToService(TransferForegroundService.ACTION_PAUSE)
    fun resume() = sendToService(TransferForegroundService.ACTION_RESUME)
    fun dismiss() = transferState.dismiss()
    fun hide() = transferState.hide()
    fun show() = transferState.show()
    fun cancel() = sendToService(TransferForegroundService.ACTION_CANCEL)

    private fun sendToService(action: String) {
        appContext.startService(Intent(appContext, TransferForegroundService::class.java).setAction(action))
    }

    fun resolveConflict(answer: ConflictAnswer) = transferState.resolveConflict(answer)
}
