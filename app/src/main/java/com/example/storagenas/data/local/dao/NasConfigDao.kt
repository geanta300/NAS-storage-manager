package com.example.storagenas.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.storagenas.data.local.entity.NasConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NasConfigDao {
    @Query("SELECT * FROM nas_config WHERE id = 1 LIMIT 1")
    fun observeConfig(): Flow<NasConfigEntity?>

    @Query("SELECT * FROM nas_config WHERE id = 1 LIMIT 1")
    suspend fun getConfig(): NasConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: NasConfigEntity)

    @Query("DELETE FROM nas_config")
    suspend fun clear()
}
