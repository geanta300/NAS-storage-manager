package com.example.storagenas.ui.screens.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.storagenas.domain.model.UploadTask
import com.example.storagenas.domain.model.UploadStatus
import com.example.storagenas.domain.repository.UploadRepository
import com.example.storagenas.workers.UploadWorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class QueueStatusFilter {
    ALL,
    SUCCESS,
    FAILED,
    ACTIVE,
    OTHER,
}

data class QueueUiState(
    val totalTaskCount: Int = 0,
    val activeCount: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val completedPercent: Int = 0,
    val failedTasks: List<UploadTask> = emptyList(),
    val displayedFailedCount: Int = 0,
    val totalFailedCount: Int = 0,
    val hasMoreFailedToLoad: Boolean = false,
    val statusFilter: QueueStatusFilter = QueueStatusFilter.ALL,
    val searchQuery: String = "",
    val message: String? = null,
)

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val uploadRepository: UploadRepository,
    private val uploadWorkScheduler: UploadWorkScheduler,
) : ViewModel() {
    companion object {
        private const val PAGE_SIZE: Int = 50
    }

    private val _uiState = MutableStateFlow(QueueUiState())
    val uiState: StateFlow<QueueUiState> = _uiState.asStateFlow()
    private var latestTasks: List<UploadTask> = emptyList()
    private var loadedFailedLimit: Int = PAGE_SIZE

    init {
        viewModelScope.launch {
            uploadRepository.observeTasks().collect { tasks ->
                latestTasks = tasks
                recomputeUiState(tasks)
            }
        }
    }

    fun onSearchQueryChanged(value: String) {
        loadedFailedLimit = PAGE_SIZE
        _uiState.update { it.copy(searchQuery = value) }
        recomputeUiState(latestTasks)
    }

    fun onStatusFilterChanged(filter: QueueStatusFilter) {
        loadedFailedLimit = PAGE_SIZE
        _uiState.update { it.copy(statusFilter = filter) }
        recomputeUiState(latestTasks)
    }

    fun clearFilters() {
        loadedFailedLimit = PAGE_SIZE
        _uiState.update {
            it.copy(
                searchQuery = "",
                statusFilter = QueueStatusFilter.ALL,
            )
        }
        recomputeUiState(latestTasks)
    }

    fun triggerQueuedUploads() {
        val queuedIds = latestTasks
            .filter { it.status == UploadStatus.QUEUED }
            .map { it.id }

        if (queuedIds.isEmpty()) {
            _uiState.update { it.copy(message = "No queued uploads to start") }
            return
        }

        uploadWorkScheduler.enqueueUploadTasks(queuedIds)
        _uiState.update { it.copy(message = "Started ${queuedIds.size} queued upload(s)") }
    }

    fun stopAllActiveUploads() {
        val activeCount = latestTasks.count { task ->
            task.status == UploadStatus.PENDING || task.status == UploadStatus.QUEUED || task.status == UploadStatus.UPLOADING
        }
        if (activeCount == 0) {
            _uiState.update { it.copy(message = "No active uploads to stop") }
            return
        }

        viewModelScope.launch {
            uploadWorkScheduler.cancelAllUploads()
            val cancelled = uploadRepository.cancelAllActiveTasks(errorMessage = "Cancelled by user")
            _uiState.update { it.copy(message = "Stopped $cancelled active upload(s)") }
        }
    }

    fun retryFailedUploads() {
        val failedTasks = latestTasks.filter { it.status == UploadStatus.FAILED }
        if (failedTasks.isEmpty()) {
            _uiState.update { it.copy(message = "No failed uploads to retry") }
            return
        }

        viewModelScope.launch {
            failedTasks.forEach { task ->
                uploadRepository.updateTaskState(
                    id = task.id,
                    status = UploadStatus.QUEUED,
                    progress = 0,
                    errorMessage = null,
                    clearTiming = true,
                )
            }
            uploadWorkScheduler.enqueueUploadTasks(failedTasks.map { it.id })
            _uiState.update { it.copy(message = "Retrying ${failedTasks.size} failed upload(s)") }
        }
    }

    fun clearQueueList() {
        viewModelScope.launch {
            uploadRepository.clearAllTasks()
            _uiState.update { it.copy(message = "Queue list cleared") }
        }
    }

    fun loadMoreTasks() {
        val state = _uiState.value
        if (state.statusFilter != QueueStatusFilter.FAILED) return
        if (state.totalFailedCount <= loadedFailedLimit) return
        loadedFailedLimit = (loadedFailedLimit + PAGE_SIZE).coerceAtMost(state.totalFailedCount)
        recomputeUiState(latestTasks)
    }

    private fun recomputeUiState(tasks: List<UploadTask>) {
        val state = _uiState.value
        val query = state.searchQuery.trim().lowercase()
        val queueTasks = tasks.filterNot { it.status == UploadStatus.SKIPPED }

        val activeCount = queueTasks.count {
            it.status == UploadStatus.PENDING || it.status == UploadStatus.QUEUED || it.status == UploadStatus.UPLOADING
        }
        val successCount = queueTasks.count { it.status == UploadStatus.SUCCESS }
        val cancelledCount = queueTasks.count { it.status == UploadStatus.CANCELLED }
        val failedCount = queueTasks.count { it.status == UploadStatus.FAILED }
        val completedCount = successCount + cancelledCount + failedCount
        val completedPercent =
            if (queueTasks.isEmpty()) {
                0
            } else {
                ((completedCount * 100) / queueTasks.size).coerceIn(0, 100)
            }

        val failedTasks = queueTasks
            .asSequence()
            .filter { it.status == UploadStatus.FAILED }
            .filter { task ->
                if (query.isBlank()) {
                    true
                } else {
                    task.displayName.lowercase().contains(query) || task.destinationPath.lowercase().contains(query)
                }
            }
            .sortedByDescending { it.updatedAt }
            .toList()

        val visibleFailedTasks = failedTasks.take(loadedFailedLimit)

        _uiState.update {
            it.copy(
                totalTaskCount = queueTasks.size,
                activeCount = activeCount,
                completedCount = completedCount,
                failedCount = failedCount,
                completedPercent = completedPercent,
                failedTasks = visibleFailedTasks,
                displayedFailedCount = visibleFailedTasks.size,
                totalFailedCount = failedTasks.size,
                hasMoreFailedToLoad = visibleFailedTasks.size < failedTasks.size,
            )
        }
    }
}
