package com.example.storagenas.data.repository

import com.example.storagenas.data.local.dao.UploadTaskDao
import com.example.storagenas.domain.model.UploadStatus
import com.example.storagenas.domain.model.UploadTask
import com.example.storagenas.domain.repository.UploadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class UploadRepositoryImpl @Inject constructor(
    private val dao: UploadTaskDao,
) : UploadRepository {
    override fun observeTasks(): Flow<List<UploadTask>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeTasksByStatus(status: UploadStatus): Flow<List<UploadTask>> =
        dao.observeByStatus(status).map { list -> list.map { it.toDomain() } }

    override suspend fun getTaskById(id: Long): UploadTask? = dao.getById(id)?.toDomain()

    override suspend fun getTasksByStatus(status: UploadStatus): List<UploadTask> =
        dao.getAllByStatus(status).map { it.toDomain() }

    override suspend fun addTask(task: UploadTask): Long = dao.insert(task.toEntity())

    override suspend fun addTasks(tasks: List<UploadTask>): List<Long> =
        dao.insertAll(tasks.map { it.toEntity() })

    override suspend fun updateTask(task: UploadTask) {
        dao.update(task.toEntity())
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
        dao.updateState(
            id = id,
            status = status,
            progress = progress,
            errorMessage = errorMessage,
            uploadStartedAt = uploadStartedAt,
            uploadFinishedAt = uploadFinishedAt,
            clearTiming = clearTiming,
        )
    }

    override suspend fun deleteTask(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun clearAllTasks() {
        dao.clearAll()
    }
}
