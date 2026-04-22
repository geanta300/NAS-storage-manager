package com.example.storagenas.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.ColumnInfo
import com.example.storagenas.data.local.entity.UploadTaskEntity
import com.example.storagenas.domain.model.UploadStatus
import kotlinx.coroutines.flow.Flow

data class QueueCountsTuple(
    @ColumnInfo(name = "total_count")
    val totalCount: Int,
    @ColumnInfo(name = "active_count")
    val activeCount: Int,
    @ColumnInfo(name = "completed_count")
    val completedCount: Int,
    @ColumnInfo(name = "failed_count")
    val failedCount: Int,
)

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

    @Query(
        """
        UPDATE upload_task
        SET status = 'CANCELLED',
            progress = 0,
            error_message = :errorMessage,
            upload_finished_at = :finishedAt,
            updated_at = :finishedAt
        WHERE status IN ('PENDING', 'QUEUED', 'UPLOADING')
        """,
    )
    suspend fun cancelAllActive(
        errorMessage: String,
        finishedAt: Long = System.currentTimeMillis(),
    ): Int

    @Query(
        """
        SELECT
            COUNT(*) AS total_count,
            SUM(CASE WHEN status IN ('PENDING', 'QUEUED', 'UPLOADING') THEN 1 ELSE 0 END) AS active_count,
            SUM(CASE WHEN status IN ('SUCCESS', 'CANCELLED', 'SKIPPED') THEN 1 ELSE 0 END) AS completed_count,
            SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count
        FROM upload_task
        """,
    )
    suspend fun getQueueCounts(): QueueCountsTuple

    @Query("DELETE FROM upload_task WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM upload_task")
    suspend fun clearAll()
}
