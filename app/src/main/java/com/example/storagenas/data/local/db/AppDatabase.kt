package com.example.storagenas.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.storagenas.data.local.dao.AppLogDao
import com.example.storagenas.data.local.dao.NasConfigDao
import com.example.storagenas.data.local.dao.SyncJobDao
import com.example.storagenas.data.local.dao.UploadTaskDao
import com.example.storagenas.data.local.dao.UploadedFileRecordDao
import com.example.storagenas.data.local.entity.AppLogEntity
import com.example.storagenas.data.local.entity.NasConfigEntity
import com.example.storagenas.data.local.entity.SyncJobEntity
import com.example.storagenas.data.local.entity.UploadTaskEntity
import com.example.storagenas.data.local.entity.UploadedFileRecordEntity

@Database(
    entities = [
        NasConfigEntity::class,
        UploadTaskEntity::class,
        SyncJobEntity::class,
        UploadedFileRecordEntity::class,
        AppLogEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nasConfigDao(): NasConfigDao
    abstract fun uploadTaskDao(): UploadTaskDao
    abstract fun syncJobDao(): SyncJobDao
    abstract fun uploadedFileRecordDao(): UploadedFileRecordDao
    abstract fun appLogDao(): AppLogDao

    companion object {
        const val DATABASE_NAME: String = "storage_nas.db"
    }
}
