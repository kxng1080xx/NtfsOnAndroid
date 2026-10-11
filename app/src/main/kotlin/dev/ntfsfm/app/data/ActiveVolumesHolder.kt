package dev.ntfsfm.app.data

import dev.ntfsfm.core.vfs.VirtualFileSystem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every mounted volume by id, so screens and the transfer service can find one from a nav argument. */
@Singleton
class ActiveVolumesHolder @Inject constructor() {
    private val _volumes = MutableStateFlow<Map<String, VirtualFileSystem>>(emptyMap())
    val volumes: StateFlow<Map<String, VirtualFileSystem>> = _volumes.asStateFlow()

    fun put(vfs: VirtualFileSystem) {
        _volumes.value = _volumes.value + (vfs.identity.volumeId to vfs)
    }

    fun remove(volumeId: String) {
        _volumes.value = _volumes.value - volumeId
    }

    /** Drops USB volumes whose drive was unplugged; leaves internal storage alone. */
    fun removeUsbVolumesNotIn(liveVolumeIds: Set<String>) {
        _volumes.value = _volumes.value.filterKeys { !it.startsWith(USB_VOLUME_ID_PREFIX) || it in liveVolumeIds }
    }

    fun get(volumeId: String): VirtualFileSystem? = _volumes.value[volumeId]

    fun all(): Map<String, VirtualFileSystem> = _volumes.value

    companion object {
        const val USB_VOLUME_ID_PREFIX = "usb-"
    }
}
