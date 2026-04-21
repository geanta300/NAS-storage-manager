package com.example.storagenas.di

import com.example.storagenas.data.repository.AppLogRepositoryImpl
import com.example.storagenas.data.repository.NasConfigRepositoryImpl
import com.example.storagenas.data.repository.SettingsRepositoryImpl
import com.example.storagenas.data.repository.SyncRepositoryImpl
import com.example.storagenas.data.repository.UploadRepositoryImpl
import com.example.storagenas.data.repository.UploadedFileRecordRepositoryImpl
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.domain.repository.SettingsRepository
import com.example.storagenas.domain.repository.SyncRepository
import com.example.storagenas.domain.repository.UploadRepository
import com.example.storagenas.domain.repository.UploadedFileRecordRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindNasConfigRepository(impl: NasConfigRepositoryImpl): NasConfigRepository

    @Binds
    @Singleton
    abstract fun bindUploadRepository(impl: UploadRepositoryImpl): UploadRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository

    @Binds
    @Singleton
    abstract fun bindUploadedFileRecordRepository(
        impl: UploadedFileRecordRepositoryImpl,
    ): UploadedFileRecordRepository

    @Binds
    @Singleton
    abstract fun bindAppLogRepository(impl: AppLogRepositoryImpl): AppLogRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
