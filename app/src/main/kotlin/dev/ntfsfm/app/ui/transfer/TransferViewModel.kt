package dev.ntfsfm.app.ui.transfer

import android.content.Context
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
    val pendingConflict: StateFlow<PendingConflict?> = transferState.pendingConflict

    fun pause() = transferState.pause()
    fun resume() = transferState.resume()
    fun dismiss() = transferState.dismiss()
    fun cancel() {
        appContext.startService(TransferForegroundService.cancelIntent(appContext))
    }

    fun resolveConflict(answer: ConflictAnswer) = transferState.resolveConflict(answer)
}
