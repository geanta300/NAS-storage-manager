package com.example.storagenas.ui.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.storagenas.domain.model.SyncJob
import com.example.storagenas.domain.model.SyncMode
import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.domain.repository.SyncRepository
import com.example.storagenas.domain.usecase.ScheduleGallerySyncRequest
import com.example.storagenas.domain.usecase.ScheduleGallerySyncUseCase
import com.example.storagenas.sync.GalleryScanner
import com.example.storagenas.sync.model.GalleryAlbum
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncUiState(
    val mode: SyncMode = SyncMode.SELECTED_ALBUMS,
    val albums: List<GalleryAlbum> = emptyList(),
    val selectedAlbumIds: Set<String> = emptySet(),
    val destinationRoot: String = "/",
    val skipDuplicates: Boolean = true,
    val preserveAlbumStructure: Boolean = true,
    val isLoadingAlbums: Boolean = false,
    val isRunningSync: Boolean = false,
    val latestSummary: String? = null,
    val syncHistory: List<SyncJob> = emptyList(),
)

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val galleryScanner: GalleryScanner,
    private val scheduleGallerySyncUseCase: ScheduleGallerySyncUseCase,
    private val nasConfigRepository: NasConfigRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            nasConfigRepository.observeConfig().collect { config ->
                val destination = config?.defaultRemotePath ?: "/"
                _uiState.update { it.copy(destinationRoot = destination) }
            }
        }

        refreshAlbums()

        viewModelScope.launch {
            syncRepository.observeJobs().collect { jobs ->
                _uiState.update { it.copy(syncHistory = jobs.take(10)) }
            }
        }
    }

    fun onModeChanged(mode: SyncMode) {
        _uiState.update { current ->
            current.copy(
                mode = mode,
                selectedAlbumIds = if (mode == SyncMode.SELECTED_ALBUMS) current.selectedAlbumIds else emptySet(),
            )
        }
    }

    fun onDestinationRootChanged(value: String) {
        _uiState.update { it.copy(destinationRoot = value.ifBlank { "/" }) }
    }

    fun onSkipDuplicatesChanged(value: Boolean) {
        _uiState.update { it.copy(skipDuplicates = value) }
    }

    fun onPreserveAlbumStructureChanged(value: Boolean) {
        _uiState.update { it.copy(preserveAlbumStructure = value) }
    }

    fun toggleAlbumSelection(albumId: String) {
        _uiState.update { current ->
            val next = current.selectedAlbumIds.toMutableSet()
            if (!next.add(albumId)) next.remove(albumId)
            current.copy(selectedAlbumIds = next)
        }
    }

    fun selectAllAlbums() {
        _uiState.update { current ->
            current.copy(selectedAlbumIds = current.albums.map { it.id }.toSet())
        }
    }

    fun clearAlbumSelection() {
        _uiState.update { it.copy(selectedAlbumIds = emptySet()) }
    }

    fun refreshAlbums() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAlbums = true) }
            val albums = runCatching { galleryScanner.getAlbums() }.getOrElse { emptyList() }
            _uiState.update {
                it.copy(
                    albums = albums,
                    isLoadingAlbums = false,
                )
            }
        }
    }

    fun startSync() {
        val current = _uiState.value
        if (current.mode == SyncMode.SELECTED_ALBUMS && current.selectedAlbumIds.isEmpty()) {
            _uiState.update { it.copy(latestSummary = "Select at least one album") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isRunningSync = true, latestSummary = null) }

            val result = scheduleGallerySyncUseCase(
                ScheduleGallerySyncRequest(
                    mode = current.mode,
                    selectedAlbumIds = current.selectedAlbumIds,
                    destinationRoot = current.destinationRoot,
                    skipDuplicates = current.skipDuplicates,
                    preserveAlbumStructure = current.preserveAlbumStructure,
                ),
            )

            _uiState.update {
                it.copy(
                    isRunningSync = false,
                    latestSummary = result.getOrNull()?.summary
                        ?: result.exceptionOrNull()?.message
                        ?: "Sync failed",
                )
            }
        }
    }
}
