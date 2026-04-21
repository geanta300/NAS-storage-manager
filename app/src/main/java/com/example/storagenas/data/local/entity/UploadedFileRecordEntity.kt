package com.example.storagenas.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "uploaded_file_record",
    indices = [
        Index(value = ["local_uri"]),
        Index(value = ["remote_path"]),
        Index(value = ["local_size", "local_modified", "display_name"]),
    ],
)
data class UploadedFileRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "local_uri")
    val localUri: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "local_modified")
    val localModified: Long,
    @ColumnInfo(name = "local_size")
    val localSize: Long,
    @ColumnInfo(name = "remote_path")
    val remotePath: String,
    @ColumnInfo(name = "uploaded_at")
    val uploadedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "checksum_optional")
    val checksumOptional: String? = null,
)
