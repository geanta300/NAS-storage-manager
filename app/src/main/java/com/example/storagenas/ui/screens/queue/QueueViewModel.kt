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

data class QueueTaskGroup(
    val key: String,
    val title: String,
    val folderPath: String,
    val tasks: List<UploadTask>,
    val successCount: Int,
    val failedCount: Int,
)

data class QueueUiState(
    val tasks: List<UploadTask> = emptyList(),
    val filteredTasks: List<UploadTask> = emptyList(),
    val groups: List<QueueTaskGroup> = emptyList(),
    val expandedGroupKeys: Set<String> = emptySet(),
    val statusFilter: QueueStatusFilter = QueueStatusFilter.ALL,
    val searchQuery: String = "",
    val message: String? = null,
)

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val uploadRepository: UploadRepository,
    private val uploadWorkScheduler: UploadWorkScheduler,
) : ViewModel() {
    private val _uiState = MutableStateFlow(QueueUiState())
    val uiState: StateFlow<QueueUiState> = _uiState.asStateFlow()
    private var latestTasks: List<UploadTask> = emptyList()

    init {
        viewModelScope.launch {
            uploadRepository.observeTasks().collect { tasks ->
                latestTasks = tasks
                recomputeUiState(tasks)
            }
        }
    }

    fun onSearchQueryChanged(value: String) {
        _uiState.update { it.copy(searchQuery = value) }
        recomputeUiState(latestTasks)
    }

    fun onStatusFilterChanged(filter: QueueStatusFilter) {
        _uiState.update { it.copy(statusFilter = filter) }
        recomputeUiState(latestTasks)
    }

    fun clearFilters() {
        _uiState.update {
            it.copy(
                searchQuery = "",
                statusFilter = QueueStatusFilter.ALL,
            )
        }
        recomputeUiState(latestTasks)
    }

    fun toggleGroupExpanded(key: String) {
        _uiState.update { state ->
            val next = state.expandedGroupKeys.toMutableSet()
            if (!next.add(key)) {
                next.remove(key)
            }
            state.copy(expandedGroupKeys = next)
        }
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

    private fun recomputeUiState(tasks: List<UploadTask>) {
        val state = _uiState.value
        val query = state.searchQuery.trim().lowercase()
        val queueTasks = tasks.filterNot { it.status == UploadStatus.SKIPPED }

        val filtered = queueTasks
            .filter { task -> matchesStatusFilter(task, state.statusFilter) }
            .filter { task ->
                if (query.isBlank()) {
                    true
                } else {
                    task.displayName.lowercase().contains(query) || task.destinationPath.lowercase().contains(query)
                }
            }
            .sortedByDescending { it.updatedAt }

        val grouped = filtered
            .groupBy { parentFolderPath(it.destinationPath) }
            .map { (folderPath, folderTasks) ->
                QueueTaskGroup(
                    key = folderPath,
                    title = folderTitle(folderPath),
                    folderPath = folderPath,
                    tasks = folderTasks.sortedByDescending { it.updatedAt },
                    successCount = folderTasks.count { it.status == UploadStatus.SUCCESS },
                    failedCount = folderTasks.count { it.status == UploadStatus.FAILED },
                )
            }
            .sortedByDescending { group -> group.tasks.maxOfOrNull { it.updatedAt } ?: Long.MIN_VALUE }

        val validGroupKeys = grouped.map { it.key }.toSet()
        val expanded = if (state.expandedGroupKeys.isEmpty()) {
            grouped.take(2).map { it.key }.toSet()
        } else {
            state.expandedGroupKeys.intersect(validGroupKeys)
        }

        _uiState.update {
            it.copy(
                tasks = queueTasks,
                filteredTasks = filtered,
                groups = grouped,
                expandedGroupKeys = expanded,
            )
        }
    }

    private fun parentFolderPath(destinationPath: String): String {
        val normalized = destinationPath.trim().ifBlank { "/" }
        val parent = normalized.substringBeforeLast('/', missingDelimiterValue = "/")
        return parent.ifBlank { "/" }
    }

    private fun folderTitle(folderPath: String): String {
        if (folderPath == "/") return "Root"
        return folderPath.substringAfterLast('/').ifBlank { folderPath }
    }

    private fun matchesStatusFilter(task: UploadTask, filter: QueueStatusFilter): Boolean =
        when (filter) {
            QueueStatusFilter.ALL -> true
            QueueStatusFilter.SUCCESS -> task.status == UploadStatus.SUCCESS
            QueueStatusFilter.FAILED -> task.status == UploadStatus.FAILED
            QueueStatusFilter.ACTIVE -> {
                task.status == UploadStatus.PENDING ||
                    task.status == UploadStatus.QUEUED ||
                    task.status == UploadStatus.UPLOADING
            }
            QueueStatusFilter.OTHER -> {
                task.status == UploadStatus.CANCELLED
            }
        }
}
