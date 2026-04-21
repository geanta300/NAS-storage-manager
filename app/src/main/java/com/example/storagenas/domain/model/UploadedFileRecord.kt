package com.example.storagenas.domain.model

data class UploadedFileRecord(
    val id: Long = 0,
    val localUri: String,
    val displayName: String,
    val localModified: Long,
    val localSize: Long,
    val remotePath: String,
    val uploadedAt: Long = System.currentTimeMillis(),
    val checksumOptional: String? = null,
)
