package com.example.storagenas.ui.screens.queue

import com.example.storagenas.domain.model.UploadStatus
import com.example.storagenas.domain.model.UploadTask
import com.example.storagenas.domain.repository.QueueProgressSnapshot
import com.example.storagenas.domain.repository.UploadRepository
import com.example.storagenas.workers.UploadWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `failed filter exposes first 50 failed files and loads more on demand`() = runTest {
        val tasks = (1..120).map { index ->
            UploadTask(
                id = index.toLong(),
                localUri = "content://$index",
                displayName = "file_$index.jpg",
                destinationPath = "/camera/file_$index.jpg",
                status = UploadStatus.FAILED,
                progress = 0,
                createdAt = index.toLong(),
                updatedAt = index.toLong(),
            )
        }

        val repository = FakeUploadRepository(tasks)
        val scheduler = RecordingUploadWorkScheduler()
        val viewModel = QueueViewModel(repository, scheduler)

        advanceUntilIdle()
        viewModel.onStatusFilterChanged(QueueStatusFilter.FAILED)
        advanceUntilIdle()

        assertEquals(50, viewModel.uiState.value.displayedFailedCount)
        assertEquals(120, viewModel.uiState.value.totalFailedCount)
        assertTrue(viewModel.uiState.value.hasMoreFailedToLoad)

        viewModel.loadMoreTasks()
        advanceUntilIdle()
        assertEquals(100, viewModel.uiState.value.displayedFailedCount)
        assertTrue(viewModel.uiState.value.hasMoreFailedToLoad)

        viewModel.loadMoreTasks()
        advanceUntilIdle()
        assertEquals(120, viewModel.uiState.value.displayedFailedCount)
        assertFalse(viewModel.uiState.value.hasMoreFailedToLoad)
    }

    @Test
    fun `stop all cancels active tasks and marks them cancelled`() = runTest {
        val queued = UploadTask(
            id = 1L,
            localUri = "content://queued",
            displayName = "queued.jpg",
            destinationPath = "/camera/queued.jpg",
            status = UploadStatus.QUEUED,
            updatedAt = 1L,
        )
        val uploading = UploadTask(
            id = 2L,
            localUri = "content://uploading",
            displayName = "uploading.jpg",
            destinationPath = "/camera/uploading.jpg",
            status = UploadStatus.UPLOADING,
            progress = 40,
            updatedAt = 2L,
        )
        val success = UploadTask(
            id = 3L,
            localUri = "content://done",
            displayName = "done.jpg",
            destinationPath = "/camera/done.jpg",
            status = UploadStatus.SUCCESS,
            progress = 100,
            updatedAt = 3L,
        )

        val repository = FakeUploadRepository(listOf(queued, uploading, success))
        val scheduler = RecordingUploadWorkScheduler()
        val viewModel = QueueViewModel(repository, scheduler)

        advanceUntilIdle()
        viewModel.stopAllActiveUploads()
        advanceUntilIdle()

        assertEquals(1, scheduler.cancelAllCalls)
        assertEquals(UploadStatus.CANCELLED, repository.taskById(1L)?.status)
        assertEquals(UploadStatus.CANCELLED, repository.taskById(2L)?.status)
        assertEquals(UploadStatus.SUCCESS, repository.taskById(3L)?.status)
        assertTrue(viewModel.uiState.value.message?.contains("Stopped 2 active upload") == true)
    }
}

private class FakeUploadRepository(initialTasks: List<UploadTask>) : UploadRepository {
    private val tasksState = MutableStateFlow(initialTasks)

    override fun observeTasks(): Flow<List<UploadTask>> = tasksState.asStateFlow()

    override fun observeTasksByStatus(status: UploadStatus): Flow<List<UploadTask>> =
        flowOf(tasksState.value.filter { it.status == status })

    override suspend fun getTasksByStatus(status: UploadStatus): List<UploadTask> =
        tasksState.value.filter { it.status == status }

    override suspend fun getTaskById(id: Long): UploadTask? = taskById(id)

    override suspend fun addTask(task: UploadTask): Long {
        tasksState.value = tasksState.value + task
        return task.id
    }

    override suspend fun addTasks(tasks: List<UploadTask>): List<Long> {
        tasksState.value = tasksState.value + tasks
        return tasks.map { it.id }
    }

    override suspend fun updateTask(task: UploadTask) {
        tasksState.value = tasksState.value.map { current -> if (current.id == task.id) task else current }
    }

    override suspend fun updateTaskState(
        id: Long,
        status: UploadStatus,
        progress: Int,
        errorMessage: String?,
        uploadStartedAt: Long?,
        uploadFinishedAt: Long?,
        clearTiming: Boolean,
    ) {
        tasksState.value = tasksState.value.map { task ->
            if (task.id != id) {
                task
            } else {
                task.copy(
                    status = status,
                    progress = progress,
                    errorMessage = errorMessage,
                    uploadStartedAt =
                        if (clearTiming) {
                            null
                        } else {
                            uploadStartedAt ?: task.uploadStartedAt
                        },
                    uploadFinishedAt =
                        if (clearTiming) {
                            null
                        } else {
                            uploadFinishedAt ?: task.uploadFinishedAt
                        },
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    override suspend fun deleteTask(id: Long) {
        tasksState.value = tasksState.value.filterNot { it.id == id }
    }

    override suspend fun cancelAllActiveTasks(errorMessage: String): Int {
        var cancelled = 0
        tasksState.value = tasksState.value.map { task ->
            if (
                task.status == UploadStatus.PENDING ||
                task.status == UploadStatus.QUEUED ||
                task.status == UploadStatus.UPLOADING
            ) {
                cancelled += 1
                task.copy(
                    status = UploadStatus.CANCELLED,
                    progress = 0,
                    errorMessage = errorMessage,
                )
            } else {
                task
            }
        }
        return cancelled
    }

    override suspend fun getQueueProgressSnapshot(): QueueProgressSnapshot {
        val all = tasksState.value
        val active = all.count {
            it.status == UploadStatus.PENDING ||
                it.status == UploadStatus.QUEUED ||
                it.status == UploadStatus.UPLOADING
        }
        val failed = all.count { it.status == UploadStatus.FAILED }
        val completed = all.count {
            it.status == UploadStatus.SUCCESS ||
                it.status == UploadStatus.CANCELLED ||
                it.status == UploadStatus.SKIPPED ||
                it.status == UploadStatus.FAILED
        }
        val percent = if (all.isEmpty()) 0 else ((completed * 100) / all.size).coerceIn(0, 100)
        return QueueProgressSnapshot(
            totalCount = all.size,
            activeCount = active,
            completedCount = completed,
            failedCount = failed,
            completedPercent = percent,
        )
    }

    override suspend fun clearAllTasks() {
        tasksState.value = emptyList()
    }

    fun taskById(id: Long): UploadTask? = tasksState.value.firstOrNull { it.id == id }
}

private class RecordingUploadWorkScheduler : UploadWorkScheduler {
    var cancelAllCalls: Int = 0

    override fun enqueueUploadTask(taskId: Long) = Unit

    override fun enqueueUploadTasks(taskIds: List<Long>) = Unit

    override fun cancelUploadTask(taskId: Long) = Unit

    override fun cancelAllUploads() {
        cancelAllCalls += 1
    }
}