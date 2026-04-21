package com.example.storagenas.domain.model

data class AppLog(
    val id: Long = 0,
    val type: LogType = LogType.INFO,
    val message: String,
    val createdAt: Long = System.currentTimeMillis(),
)
