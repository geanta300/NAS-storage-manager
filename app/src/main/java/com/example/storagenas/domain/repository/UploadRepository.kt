package com.example.storagenas.domain.repository

import com.example.storagenas.domain.model.UploadStatus
import com.example.storagenas.domain.model.UploadTask
import kotlinx.coroutines.flow.Flow

interface UploadRepository {
    fun observeTasks(): Flow<List<UploadTask>>
    fun observeTasksByStatus(status: UploadStatus): Flow<List<UploadTask>>
    suspend fun getTasksByStatus(status: UploadStatus): List<UploadTask>
    suspend fun getTaskById(id: Long): UploadTask?
    suspend fun addTask(task: UploadTask): Long
    suspend fun addTasks(tasks: List<UploadTask>): List<Long>
    suspend fun updateTask(task: UploadTask)
    suspend fun updateTaskState(
        id: Long,
        status: UploadStatus,
        progress: Int,
        errorMessage: String?,
        uploadStartedAt: Long? = null,
        uploadFinishedAt: Long? = null,
        clearTiming: Boolean = false,
    )

    suspend fun deleteTask(id: Long)
    suspend fun clearAllTasks()
}
