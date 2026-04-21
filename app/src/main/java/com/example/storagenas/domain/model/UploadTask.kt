package com.example.storagenas.domain.model

data class UploadTask(
    val id: Long = 0,
    val localUri: String,
    val displayName: String,
    val mimeType: String? = null,
    val size: Long? = null,
    val destinationPath: String,
    val status: UploadStatus = UploadStatus.PENDING,
    val progress: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val uploadStartedAt: Long? = null,
    val uploadFinishedAt: Long? = null,
    val errorMessage: String? = null,
)
