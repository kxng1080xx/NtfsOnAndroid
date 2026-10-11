package dev.ntfsfm.app.ui.home

import android.hardware.usb.UsbDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.ntfsfm.app.data.ActiveVolumesHolder
import dev.ntfsfm.app.data.UsbVolumeState
import dev.ntfsfm.app.data.VolumeRepository
import javax.inject.Inject
import dev.ntfsfm.core.data.dao.RecentLocationDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One storage card on the home screen. USB cards that can't be opened carry why. */
data class StorageLocationUi(
    val volumeId: String,
    val displayName: String,
    val isUsb: Boolean,
    val usedBytes: Long,
    val totalBytes: Long,
    val needsPermission: Boolean = false,
    val unsupportedReason: String? = null,
    val device: UsbDevice? = null,
) {
    val usedFraction: Float get() = if (totalBytes <= 0) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val isOpenable: Boolean get() = !needsPermission && unsupportedReason == null
}

/** A recently opened folder on a drive that is connected right now. */
data class RecentFolderUi(val volumeId: String, val path: String, val name: String, val volumeName: String)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val volumeRepository: VolumeRepository,
    private val activeVolumes: ActiveVolumesHolder,
    recentLocations: RecentLocationDao,
) : ViewModel() {
    val recentFolders: StateFlow<List<RecentFolderUi>> =
        combine(recentLocations.observeRecent(12), activeVolumes.volumes) { recents, volumes ->
            recents.mapNotNull { r ->
                val vfs = volumes[r.volumeId] ?: return@mapNotNull null
                RecentFolderUi(r.volumeId, r.path, r.displayName, vfs.identity.displayName)
            }.take(6)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val internalLocation = MutableStateFlow<StorageLocationUi?>(null)
    private val usbLocations = MutableStateFlow<List<StorageLocationUi>>(emptyList())
    private val _locations = MutableStateFlow<List<StorageLocationUi>>(emptyList())
    val locations: StateFlow<List<StorageLocationUi>> = _locations.asStateFlow()

    init {
        viewModelScope.launch {
            val internal = volumeRepository.internalStorageVolume()
            activeVolumes.put(internal)
            val space = runCatching { internal.spaceInfo() }.getOrNull()
            internalLocation.value = StorageLocationUi(
                volumeId = internal.identity.volumeId,
                displayName = internal.identity.displayName,
                isUsb = false,
                usedBytes = space?.usedBytes ?: 0,
                totalBytes = space?.totalBytes ?: 0,
            )
            publish()
        }
        viewModelScope.launch {
            volumeRepository.usbVolumeStates().collect { states ->
                val rebuilt = states.mapIndexed { index, state -> state.toUi(index) }
                val ready = states.filterIsInstance<UsbVolumeState.Ready>()
                ready.forEach { activeVolumes.put(it.vfs) }
                activeVolumes.removeUsbVolumesNotIn(ready.map { it.volumeId }.toSet())
                usbLocations.value = rebuilt
                publish()
            }
        }
    }

    fun requestPermission(device: UsbDevice) {
        viewModelScope.launch { volumeRepository.requestPermission(device) }
    }

    fun retry() = volumeRepository.refresh()

    private fun publish() {
        _locations.value = listOfNotNull(internalLocation.value) + usbLocations.value
    }

    private suspend fun UsbVolumeState.toUi(index: Int): StorageLocationUi {
        val driveName = device.productName?.takeIf { it.isNotBlank() } ?: "USB drive"
        return when (this) {
            is UsbVolumeState.Ready -> {
                val space = runCatching { vfs.spaceInfo() }.getOrNull()
                StorageLocationUi(volumeId, displayName, true, space?.usedBytes ?: 0, space?.totalBytes ?: 0, device = device)
            }
            is UsbVolumeState.NeedsPermission -> StorageLocationUi("usb-pending-$index", driveName, true, 0, 0, needsPermission = true, device = device)
            is UsbVolumeState.Unsupported -> StorageLocationUi("usb-unsupported-$index", driveName, true, 0, 0, unsupportedReason = reason, device = device)
            is UsbVolumeState.Failed -> StorageLocationUi("usb-failed-$index", driveName, true, 0, 0, unsupportedReason = reason, device = device)
        }
    }
}
