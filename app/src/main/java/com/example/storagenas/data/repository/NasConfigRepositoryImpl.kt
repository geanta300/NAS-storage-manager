package com.example.storagenas.data.repository

import com.example.storagenas.data.local.dao.NasConfigDao
import com.example.storagenas.domain.model.NasConfig
import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.settings.local.UserPreferencesDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class NasConfigRepositoryImpl @Inject constructor(
    private val dao: NasConfigDao,
    private val userPreferencesDataStore: UserPreferencesDataSource,
) : NasConfigRepository {
    override fun observeConfig(): Flow<NasConfig?> = combine(
        dao.observeConfig(),
        userPreferencesDataStore.settings,
    ) { entity, settings ->
        entity?.toDomain(
            useTcpFallbackOnLan = settings.useTcpFallbackOnLan,
            zeroTierConnectionMode = settings.zeroTierConnectionMode,
        )
    }

    override suspend fun getConfig(): NasConfig? {
        val entity = dao.getConfig() ?: return null
        val settings = userPreferencesDataStore.settings.first()
        return entity.toDomain(
            useTcpFallbackOnLan = settings.useTcpFallbackOnLan,
            zeroTierConnectionMode = settings.zeroTierConnectionMode,
        )
    }

    override suspend fun saveConfig(config: NasConfig) {
        dao.upsert(config.toEntity())
    }

    override suspend fun clearConfig() {
        dao.clear()
    }
}
