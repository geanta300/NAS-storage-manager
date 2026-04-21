package com.example.storagenas.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.WorkManager
import com.example.storagenas.data.local.dao.AppLogDao
import com.example.storagenas.data.local.dao.NasConfigDao
import com.example.storagenas.data.local.dao.SyncJobDao
import com.example.storagenas.data.local.dao.UploadTaskDao
import com.example.storagenas.data.local.dao.UploadedFileRecordDao
import com.example.storagenas.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    fun provideNasConfigDao(database: AppDatabase): NasConfigDao = database.nasConfigDao()

    @Provides
    fun provideUploadTaskDao(database: AppDatabase): UploadTaskDao = database.uploadTaskDao()

    @Provides
    fun provideSyncJobDao(database: AppDatabase): SyncJobDao = database.syncJobDao()

    @Provides
    fun provideUploadedFileRecordDao(database: AppDatabase): UploadedFileRecordDao =
        database.uploadedFileRecordDao()

    @Provides
    fun provideAppLogDao(database: AppDatabase): AppLogDao = database.appLogDao()

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(DATASTORE_NAME) },
        )

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    private const val DATASTORE_NAME: String = "user_settings.preferences_pb"

    private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE upload_task ADD COLUMN upload_started_at INTEGER")
            db.execSQL("ALTER TABLE upload_task ADD COLUMN upload_finished_at INTEGER")
        }
    }
}
