package com.example.storagenas.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.storagenas.data.local.entity.SyncJobEntity
import com.example.storagenas.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncJobDao {
    @Query("SELECT * FROM sync_job ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SyncJobEntity>>

    @Query("SELECT * FROM sync_job WHERE status = :status ORDER BY created_at DESC")
    fun observeByStatus(status: SyncStatus): Flow<List<SyncJobEntity>>

    @Query("SELECT * FROM sync_job WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SyncJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: SyncJobEntity): Long

    @Update
    suspend fun update(job: SyncJobEntity)

    @Query(
        """
        UPDATE sync_job
        SET status = :status,
            completed_at = :completedAt,
            summary = :summary
        WHERE id = :id
        """,
    )
    suspend fun updateState(
        id: Long,
        status: SyncStatus,
        completedAt: Long? = null,
        summary: String? = null,
    )

    @Query("DELETE FROM sync_job WHERE id = :id")
    suspend fun deleteById(id: Long)
}
