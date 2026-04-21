package com.example.storagenas.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.storagenas.domain.model.UploadStatus

@Entity(
    tableName = "upload_task",
    indices = [Index(value = ["status"]), Index(value = ["created_at"])],
)
data class UploadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "local_uri")
    val localUri: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,
    val size: Long? = null,
    @ColumnInfo(name = "destination_path")
    val destinationPath: String,
    val status: UploadStatus = UploadStatus.PENDING,
    val progress: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "upload_started_at")
    val uploadStartedAt: Long? = null,
    @ColumnInfo(name = "upload_finished_at")
    val uploadFinishedAt: Long? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
)
