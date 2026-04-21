package com.example.storagenas.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.storagenas.domain.model.AuthType

@Entity(tableName = "nas_config")
data class NasConfigEntity(
    @PrimaryKey
    val id: Int = 1,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String,
    @ColumnInfo(name = "auth_type")
    val authType: AuthType = AuthType.PASSWORD,
    @ColumnInfo(name = "default_remote_path")
    val defaultRemotePath: String = "/",
    @ColumnInfo(name = "last_test_status")
    val lastTestStatus: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
