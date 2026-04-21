package com.example.storagenas.data.repository

import com.example.storagenas.data.local.dao.AppLogDao
import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.repository.AppLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AppLogRepositoryImpl @Inject constructor(
    private val dao: AppLogDao,
) : AppLogRepository {
    override fun observeLogs(): Flow<List<AppLog>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun addLog(log: AppLog): Long = dao.insert(log.toEntity())

    override suspend fun addLogs(logs: List<AppLog>) {
        dao.insertAll(logs.map { it.toEntity() })
    }

    override suspend fun clearLogs() {
        dao.clearAll()
    }
}
