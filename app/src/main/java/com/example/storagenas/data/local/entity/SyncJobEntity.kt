package com.example.storagenas.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.storagenas.domain.model.SyncMode
import com.example.storagenas.domain.model.SyncStatus

@Entity(
    tableName = "sync_job",
    indices = [Index(value = ["status"]), Index(value = ["created_at"])],
)
data class SyncJobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val mode: SyncMode,
    @ColumnInfo(name = "album_ids")
    val albumIds: String? = null,
    @ColumnInfo(name = "destination_root")
    val destinationRoot: String,
    val status: SyncStatus = SyncStatus.PENDING,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
    @ColumnInfo(name = "summary")
    val summary: String? = null,
)
