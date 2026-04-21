package com.example.storagenas.domain.repository

import com.example.storagenas.domain.model.NasConfig
import kotlinx.coroutines.flow.Flow

interface NasConfigRepository {
    fun observeConfig(): Flow<NasConfig?>
    suspend fun getConfig(): NasConfig?
    suspend fun saveConfig(config: NasConfig)
    suspend fun clearConfig()
}
