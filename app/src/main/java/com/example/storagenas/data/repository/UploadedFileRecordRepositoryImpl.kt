package com.example.storagenas.data.repository

import com.example.storagenas.data.local.dao.UploadedFileRecordDao
import com.example.storagenas.domain.model.UploadedFileRecord
import com.example.storagenas.domain.repository.UploadedFileRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class UploadedFileRecordRepositoryImpl @Inject constructor(
    private val dao: UploadedFileRecordDao,
) : UploadedFileRecordRepository {
    override fun observeRecords(): Flow<List<UploadedFileRecord>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun addRecord(record: UploadedFileRecord): Long = dao.insert(record.toEntity())

    override suspend fun addRecords(records: List<UploadedFileRecord>): List<Long> =
        dao.insertAll(records.map { it.toEntity() })

    override suspend fun existsByFingerprint(
        displayName: String,
        size: Long,
        modified: Long,
    ): Boolean = dao.existsByFingerprint(displayName = displayName, size = size, modified = modified)

    override suspend fun findByFingerprint(
        displayName: String,
        size: Long,
        modified: Long,
    ): UploadedFileRecord? =
        dao.findByFingerprint(
            displayName = displayName,
            size = size,
            modified = modified,
        )?.toDomain()

    override suspend fun clearAll() {
        dao.clearAll()
    }
}
