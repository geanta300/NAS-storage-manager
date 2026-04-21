package com.example.storagenas.data.repository

import com.example.storagenas.data.local.dao.SyncJobDao
import com.example.storagenas.domain.model.SyncJob
import com.example.storagenas.domain.model.SyncStatus
import com.example.storagenas.domain.repository.SyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SyncRepositoryImpl @Inject constructor(
    private val dao: SyncJobDao,
) : SyncRepository {
    override fun observeJobs(): Flow<List<SyncJob>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeJobsByStatus(status: SyncStatus): Flow<List<SyncJob>> =
        dao.observeByStatus(status).map { list -> list.map { it.toDomain() } }

    override suspend fun getJobById(id: Long): SyncJob? = dao.getById(id)?.toDomain()

    override suspend fun addJob(job: SyncJob): Long = dao.insert(job.toEntity())

    override suspend fun updateJob(job: SyncJob) {
        dao.update(job.toEntity())
    }

    override suspend fun updateJobState(
        id: Long,
        status: SyncStatus,
        completedAt: Long?,
        summary: String?,
    ) {
        dao.updateState(
            id = id,
            status = status,
            completedAt = completedAt,
            summary = summary,
        )
    }

    override suspend fun deleteJob(id: Long) {
        dao.deleteById(id)
    }
}
