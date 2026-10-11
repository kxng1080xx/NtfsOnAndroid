package dev.ntfsfm.core.transfer

import kotlinx.coroutines.flow.MutableStateFlow

/** Pause/resume for one running transfer; cancel by cancelling the collecting coroutine. */
class TransferController {
    val isPaused = MutableStateFlow(false)

    fun pause() {
        isPaused.value = true
    }

    fun resume() {
        isPaused.value = false
    }
}
