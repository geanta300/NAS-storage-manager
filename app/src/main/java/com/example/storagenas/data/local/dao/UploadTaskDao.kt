package com.example.storagenas.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.storagenas.data.local.entity.UploadTaskEntity
import com.example.storagenas.domain.model.UploadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadTaskDao {
    @Query("SELECT * FROM upload_task ORDER BY created_at DESC")
    fun observeAll(): Flow<List<UploadTaskEntity>>

    @Query("SELECT * FROM upload_task WHERE status = :status ORDER BY created_at DESC")
    fun observeByStatus(status: UploadStatus): Flow<List<UploadTaskEntity>>

    @Query("SELECT * FROM upload_task WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): UploadTaskEntity?

    @Query("SELECT * FROM upload_task WHERE status = :status ORDER BY created_at DESC")
    suspend fun getAllByStatus(status: UploadStatus): List<UploadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: UploadTaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<UploadTaskEntity>): List<Long>

    @Update
    suspend fun update(task: UploadTaskEntity)

    @Query(
        """
        UPDATE upload_task
        SET status = :status,
            progress = :progress,
            error_message = :errorMessage,
            upload_started_at = CASE
                WHEN :clearTiming THEN NULL
                WHEN :uploadStartedAt IS NULL THEN upload_started_at
                ELSE :uploadStartedAt
            END,
            upload_finished_at = CASE
                WHEN :clearTiming THEN NULL
                WHEN :uploadFinishedAt IS NULL THEN upload_finished_at
                ELSE :uploadFinishedAt
            END,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateState(
        id: Long,
        status: UploadStatus,
        progress: Int,
        errorMessage: String?,
        uploadStartedAt: Long? = null,
        uploadFinishedAt: Long? = null,
        clearTiming: Boolean = false,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM upload_task WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM upload_task")
    suspend fun clearAll()
}
