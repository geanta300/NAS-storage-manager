package com.example.storagenas.domain.repository

import com.example.storagenas.domain.model.SyncJob
import com.example.storagenas.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

interface SyncRepository {
    fun observeJobs(): Flow<List<SyncJob>>
    fun observeJobsByStatus(status: SyncStatus): Flow<List<SyncJob>>
    suspend fun getJobById(id: Long): SyncJob?
    suspend fun addJob(job: SyncJob): Long
    suspend fun updateJob(job: SyncJob)
    suspend fun updateJobState(
        id: Long,
        status: SyncStatus,
        completedAt: Long? = null,
        summary: String? = null,
    )

    suspend fun deleteJob(id: Long)
}
