package com.example.storagenas.data.repository

import com.example.storagenas.data.local.entity.AppLogEntity
import com.example.storagenas.data.local.entity.NasConfigEntity
import com.example.storagenas.data.local.entity.SyncJobEntity
import com.example.storagenas.data.local.entity.UploadTaskEntity
import com.example.storagenas.data.local.entity.UploadedFileRecordEntity
import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.NasConfig
import com.example.storagenas.domain.model.SyncJob
import com.example.storagenas.domain.model.UploadTask
import com.example.storagenas.domain.model.UploadedFileRecord
import com.example.storagenas.domain.model.ZeroTierConnectionMode

internal fun NasConfigEntity.toDomain(
    useTcpFallbackOnLan: Boolean,
    zeroTierConnectionMode: ZeroTierConnectionMode,
): NasConfig =
    NasConfig(
        host = host,
        port = port,
        username = username,
        password = password,
        authType = authType,
        defaultRemotePath = defaultRemotePath,
        lastTestStatus = lastTestStatus,
        updatedAt = updatedAt,
        useTcpFallbackOnLan = useTcpFallbackOnLan,
        zeroTierConnectionMode = zeroTierConnectionMode,
    )

internal fun NasConfig.toEntity(): NasConfigEntity =
    NasConfigEntity(
        id = 1,
        host = host,
        port = port,
        username = username,
        password = password,
        authType = authType,
        defaultRemotePath = defaultRemotePath,
        lastTestStatus = lastTestStatus,
        updatedAt = updatedAt,
    )

internal fun UploadTaskEntity.toDomain(): UploadTask =
    UploadTask(
        id = id,
        localUri = localUri,
        displayName = displayName,
        mimeType = mimeType,
        size = size,
        destinationPath = destinationPath,
        status = status,
        progress = progress,
        createdAt = createdAt,
        updatedAt = updatedAt,
        uploadStartedAt = uploadStartedAt,
        uploadFinishedAt = uploadFinishedAt,
        errorMessage = errorMessage,
    )

internal fun UploadTask.toEntity(): UploadTaskEntity =
    UploadTaskEntity(
        id = id,
        localUri = localUri,
        displayName = displayName,
        mimeType = mimeType,
        size = size,
        destinationPath = destinationPath,
        status = status,
        progress = progress,
        createdAt = createdAt,
        updatedAt = updatedAt,
        uploadStartedAt = uploadStartedAt,
        uploadFinishedAt = uploadFinishedAt,
        errorMessage = errorMessage,
    )

internal fun SyncJobEntity.toDomain(): SyncJob =
    SyncJob(
        id = id,
        mode = mode,
        albumIds = albumIds,
        destinationRoot = destinationRoot,
        status = status,
        createdAt = createdAt,
        completedAt = completedAt,
        summary = summary,
    )

internal fun SyncJob.toEntity(): SyncJobEntity =
    SyncJobEntity(
        id = id,
        mode = mode,
        albumIds = albumIds,
        destinationRoot = destinationRoot,
        status = status,
        createdAt = createdAt,
        completedAt = completedAt,
        summary = summary,
    )

internal fun UploadedFileRecordEntity.toDomain(): UploadedFileRecord =
    UploadedFileRecord(
        id = id,
        localUri = localUri,
        displayName = displayName,
        localModified = localModified,
        localSize = localSize,
        remotePath = remotePath,
        uploadedAt = uploadedAt,
        checksumOptional = checksumOptional,
    )

internal fun UploadedFileRecord.toEntity(): UploadedFileRecordEntity =
    UploadedFileRecordEntity(
        id = id,
        localUri = localUri,
        displayName = displayName,
        localModified = localModified,
        localSize = localSize,
        remotePath = remotePath,
        uploadedAt = uploadedAt,
        checksumOptional = checksumOptional,
    )

internal fun AppLogEntity.toDomain(): AppLog =
    AppLog(
        id = id,
        type = type,
        message = message,
        createdAt = createdAt,
    )

internal fun AppLog.toEntity(): AppLogEntity =
    AppLogEntity(
        id = id,
        type = type,
        message = message,
        createdAt = createdAt,
    )
