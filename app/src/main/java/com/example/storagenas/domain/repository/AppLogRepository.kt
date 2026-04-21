package com.example.storagenas.domain.repository

import com.example.storagenas.domain.model.AppLog
import kotlinx.coroutines.flow.Flow

interface AppLogRepository {
    fun observeLogs(): Flow<List<AppLog>>
    suspend fun addLog(log: AppLog): Long
    suspend fun addLogs(logs: List<AppLog>)
    suspend fun clearLogs()
}
