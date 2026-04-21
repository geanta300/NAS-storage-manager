package com.example.storagenas.data.repository

import com.example.storagenas.domain.repository.SettingsRepository
import com.example.storagenas.settings.local.UserPreferencesDataSource
import com.example.storagenas.settings.model.UserSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SettingsRepositoryImpl @Inject constructor(
    private val dataSource: UserPreferencesDataSource,
) : SettingsRepository {
    override fun observeSettings(): Flow<UserSettings> = dataSource.settings

    override suspend fun updateSettings(transform: (UserSettings) -> UserSettings) {
        dataSource.update(transform)
    }
}
