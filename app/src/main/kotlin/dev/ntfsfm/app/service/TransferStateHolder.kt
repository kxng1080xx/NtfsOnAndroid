package dev.ntfsfm.app.service

import dev.ntfsfm.core.transfer.ConflictAnswer
import dev.ntfsfm.core.transfer.TransferController
import dev.ntfsfm.core.transfer.TransferProgress
import dev.ntfsfm.core.vfs.VfsEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A conflict the service is waiting on the user to answer. */
data class PendingConflict(val entry: VfsEntry, val response: CompletableDeferred<ConflictAnswer>)

/** Shared between the transfer service and the UI: progress out, pause/resume and conflict answers in. */
@Singleton
class TransferStateHolder @Inject constructor() {
    private var controller: TransferController? = null

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    private val _pendingConflict = MutableStateFlow<PendingConflict?>(null)
    val pendingConflict: StateFlow<PendingConflict?> = _pendingConflict.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    fun start(controller: TransferController) {
        this.controller = controller
        _isPaused.value = false
        _progress.value = null
    }

    fun update(progress: TransferProgress) {
        _progress.value = progress
    }

    fun finish() {
        controller = null
        _isPaused.value = false
    }

    fun dismiss() {
        _progress.value = null
    }

    fun pause() {
        controller?.pause()
        _isPaused.value = true
    }

    fun resume() {
        controller?.resume()
        _isPaused.value = false
    }

    /** Suspends the transfer until the UI calls [resolveConflict]. */
    suspend fun askConflict(entry: VfsEntry): ConflictAnswer {
        val deferred = CompletableDeferred<ConflictAnswer>()
        _pendingConflict.value = PendingConflict(entry, deferred)
        try {
            return deferred.await()
        } finally {
            _pendingConflict.value = null
        }
    }

    fun resolveConflict(answer: ConflictAnswer) {
        _pendingConflict.value?.response?.complete(answer)
    }
}
