package com.example.storagenas.domain.repository

import com.example.storagenas.domain.model.UploadedFileRecord
import kotlinx.coroutines.flow.Flow

interface UploadedFileRecordRepository {
    fun observeRecords(): Flow<List<UploadedFileRecord>>
    suspend fun addRecord(record: UploadedFileRecord): Long
    suspend fun addRecords(records: List<UploadedFileRecord>): List<Long>
    suspend fun existsByFingerprint(displayName: String, size: Long, modified: Long): Boolean
    suspend fun findByFingerprint(displayName: String, size: Long, modified: Long): UploadedFileRecord?
    suspend fun clearAll()
}
