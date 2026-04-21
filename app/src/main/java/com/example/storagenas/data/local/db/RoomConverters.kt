package com.example.storagenas.data.local.db

import androidx.room.TypeConverter
import com.example.storagenas.domain.model.AuthType
import com.example.storagenas.domain.model.LogType
import com.example.storagenas.domain.model.SyncMode
import com.example.storagenas.domain.model.SyncStatus
import com.example.storagenas.domain.model.UploadStatus

class RoomConverters {
    @TypeConverter
    fun fromAuthType(value: AuthType?): String? = value?.name

    @TypeConverter
    fun toAuthType(value: String?): AuthType? = value?.let(AuthType::valueOf)

    @TypeConverter
    fun fromUploadStatus(value: UploadStatus?): String? = value?.name

    @TypeConverter
    fun toUploadStatus(value: String?): UploadStatus? = value?.let(UploadStatus::valueOf)

    @TypeConverter
    fun fromSyncMode(value: SyncMode?): String? = value?.name

    @TypeConverter
    fun toSyncMode(value: String?): SyncMode? = value?.let(SyncMode::valueOf)

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus?): String? = value?.name

    @TypeConverter
    fun toSyncStatus(value: String?): SyncStatus? = value?.let(SyncStatus::valueOf)

    @TypeConverter
    fun fromLogType(value: LogType?): String? = value?.name

    @TypeConverter
    fun toLogType(value: String?): LogType? = value?.let(LogType::valueOf)
}
