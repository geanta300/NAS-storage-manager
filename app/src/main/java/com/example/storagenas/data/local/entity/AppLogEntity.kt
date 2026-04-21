package com.example.storagenas.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.storagenas.domain.model.LogType

@Entity(
    tableName = "app_log",
    indices = [Index(value = ["type"]), Index(value = ["created_at"])],
)
data class AppLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: LogType = LogType.INFO,
    val message: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)
