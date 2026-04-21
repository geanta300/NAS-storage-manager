package com.example.storagenas.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.storagenas.data.local.entity.UploadedFileRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadedFileRecordDao {
    @Query("SELECT * FROM uploaded_file_record ORDER BY uploaded_at DESC")
    fun observeAll(): Flow<List<UploadedFileRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: UploadedFileRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<UploadedFileRecordEntity>): List<Long>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM uploaded_file_record
            WHERE display_name = :displayName
              AND local_size = :size
              AND local_modified = :modified
        )
        """,
    )
    suspend fun existsByFingerprint(
        displayName: String,
        size: Long,
        modified: Long,
    ): Boolean

    @Query(
        """
        SELECT * FROM uploaded_file_record
        WHERE display_name = :displayName
          AND local_size = :size
          AND local_modified = :modified
        ORDER BY uploaded_at DESC
        LIMIT 1
        """,
    )
    suspend fun findByFingerprint(
        displayName: String,
        size: Long,
        modified: Long,
    ): UploadedFileRecordEntity?

    @Query("DELETE FROM uploaded_file_record")
    suspend fun clearAll()
}
