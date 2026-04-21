package com.example.storagenas.domain.model

data class SyncJob(
    val id: Long = 0,
    val mode: SyncMode,
    val albumIds: String? = null,
    val destinationRoot: String,
    val status: SyncStatus = SyncStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val summary: String? = null,
)
