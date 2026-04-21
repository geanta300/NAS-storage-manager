package com.example.storagenas.domain.usecase

import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.LogType
import com.example.storagenas.domain.model.SyncJob
import com.example.storagenas.domain.model.SyncMode
import com.example.storagenas.domain.model.SyncStatus
import com.example.storagenas.domain.model.UploadStatus
import com.example.storagenas.domain.model.UploadTask
import com.example.storagenas.domain.model.UploadedFileRecord
import com.example.storagenas.domain.model.NasConfig
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.domain.repository.SyncRepository
import com.example.storagenas.domain.repository.UploadRepository
import com.example.storagenas.domain.repository.UploadedFileRecordRepository
import com.example.storagenas.sync.GalleryScanner
import com.example.storagenas.network.common.NetworkResult
import com.example.storagenas.network.sftp.SftpClient
import com.example.storagenas.workers.UploadWorkScheduler
import javax.inject.Inject

data class ScheduleGallerySyncRequest(
    val mode: SyncMode,
    val selectedAlbumIds: Set<String>,
    val destinationRoot: String,
    val skipDuplicates: Boolean,
    val preserveAlbumStructure: Boolean,
)

data class ScheduleGallerySyncResult(
    val syncJobId: Long,
    val scannedCount: Int,
    val queuedCount: Int,
    val skippedCount: Int,
    val summary: String,
)

class ScheduleGallerySyncUseCase @Inject constructor(
    private val galleryScanner: GalleryScanner,
    private val uploadedFileRecordRepository: UploadedFileRecordRepository,
    private val uploadRepository: UploadRepository,
    private val nasConfigRepository: NasConfigRepository,
    private val sftpClient: SftpClient,
    private val uploadWorkScheduler: UploadWorkScheduler,
    private val syncRepository: SyncRepository,
    private val appLogRepository: AppLogRepository,
) {
    suspend operator fun invoke(request: ScheduleGallerySyncRequest): Result<ScheduleGallerySyncResult> {
        val jobId = syncRepository.addJob(
            SyncJob(
                mode = request.mode,
                albumIds = request.selectedAlbumIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                destinationRoot = request.destinationRoot,
                status = SyncStatus.RUNNING,
            ),
        )

        return runCatching {
            var cachedNasConfig: NasConfig? = null
            var configFetchAttempted = false
            val remoteDirectoryCache = mutableMapOf<String, NetworkResult<List<com.example.storagenas.network.model.RemoteEntry>>>()

            val mediaItems = when (request.mode) {
                SyncMode.FULL_GALLERY -> galleryScanner.getAllMedia()
                SyncMode.SELECTED_ALBUMS -> galleryScanner.getMediaForAlbumIds(request.selectedAlbumIds)
                SyncMode.MANUAL_SELECTION -> emptyList()
            }

            val uploadTasks = mutableListOf<UploadTask>()
            val newRecords = mutableListOf<UploadedFileRecord>()
            var skipped = 0
            var missingOnNasRequeued = 0

            for (item in mediaItems) {
                val existingRecord = if (request.skipDuplicates) {
                    uploadedFileRecordRepository.findByFingerprint(
                        displayName = item.displayName,
                        size = item.size,
                        modified = item.modifiedAt,
                    )
                } else {
                    null
                }

                val isDuplicate =
                    if (existingRecord != null) {
                        if (!configFetchAttempted) {
                            cachedNasConfig = nasConfigRepository.getConfig()
                            configFetchAttempted = true
                        }

                        if (cachedNasConfig == null) {
                            true
                        } else {
                        when (
                            val remoteCheck = isRemoteFileStillPresent(
                                config = cachedNasConfig,
                                remotePath = existingRecord.remotePath,
                                directoryCache = remoteDirectoryCache,
                            )
                        ) {
                            is NetworkResult.Success -> true
                            is NetworkResult.Error -> {
                                if (remoteCheck.code == NetworkResult.ErrorCode.NOT_FOUND) {
                                    missingOnNasRequeued++
                                    appLogRepository.addLog(
                                        AppLog(
                                            type = LogType.WARNING,
                                            message =
                                                "Re-queueing '${item.displayName}' because remote file is missing at '${existingRecord.remotePath}'",
                                        ),
                                    )
                                    false
                                } else {
                                    appLogRepository.addLog(
                                        AppLog(
                                            type = LogType.WARNING,
                                            message =
                                                "Could not verify remote duplicate '${item.displayName}' at '${existingRecord.remotePath}' (${remoteCheck.message}); keeping duplicate-skip behavior",
                                        ),
                                    )
                                    true
                                }
                            }
                        }
                        }
                    } else {
                        false
                    }

                if (isDuplicate) {
                    skipped++
                    continue
                }

                val destination = if (request.preserveAlbumStructure) {
                    joinRemotePath(request.destinationRoot, sanitizePathSegment(item.albumName))
                } else {
                    request.destinationRoot
                }

                uploadTasks += UploadTask(
                    localUri = item.uri.toString(),
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    size = item.size,
                    destinationPath = destination,
                    status = UploadStatus.QUEUED,
                    progress = 0,
                )

                newRecords += UploadedFileRecord(
                    localUri = item.uri.toString(),
                    displayName = item.displayName,
                    localModified = item.modifiedAt,
                    localSize = item.size,
                    remotePath = joinRemotePath(destination, item.displayName),
                    checksumOptional = "QUEUED_FROM_SYNC",
                )
            }

            val ids = if (uploadTasks.isNotEmpty()) uploadRepository.addTasks(uploadTasks) else emptyList()
            if (ids.isNotEmpty()) {
                uploadWorkScheduler.enqueueUploadTasks(ids)
            }
            if (newRecords.isNotEmpty()) {
                uploadedFileRecordRepository.addRecords(newRecords)
            }

            val summary =
                "Scanned ${mediaItems.size}, queued ${ids.size}, skipped $skipped" +
                    if (missingOnNasRequeued > 0) ", missing on NAS re-queued $missingOnNasRequeued" else ""

            syncRepository.updateJobState(
                id = jobId,
                status = SyncStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
                summary = summary,
            )
            appLogRepository.addLog(AppLog(type = LogType.SYNC, message = summary))

            ScheduleGallerySyncResult(
                syncJobId = jobId,
                scannedCount = mediaItems.size,
                queuedCount = ids.size,
                skippedCount = skipped,
                summary = summary,
            )
        }.onFailure { error ->
            syncRepository.updateJobState(
                id = jobId,
                status = SyncStatus.FAILED,
                completedAt = System.currentTimeMillis(),
                summary = error.message ?: "Sync failed",
            )
            appLogRepository.addLog(
                AppLog(
                    type = LogType.ERROR,
                    message = "Sync job $jobId failed: ${error.message ?: "Unknown error"}",
                ),
            )
        }
    }

    private fun joinRemotePath(base: String, child: String): String {
        val normalized = if (base.endsWith('/')) base.dropLast(1) else base
        return if (normalized.isBlank() || normalized == "/") "/$child" else "$normalized/$child"
    }

    private fun sanitizePathSegment(value: String): String =
        value.replace("/", "_").replace("\\", "_").trim().ifBlank { "Album" }

    private suspend fun isRemoteFileStillPresent(
        config: NasConfig?,
        remotePath: String,
        directoryCache: MutableMap<String, NetworkResult<List<com.example.storagenas.network.model.RemoteEntry>>>,
    ): NetworkResult<Unit> {
        val resolvedConfig = config
            ?: return NetworkResult.Error(
                code = NetworkResult.ErrorCode.CONFIG_MISSING,
                message = "NAS configuration missing",
            )
        val parent = remotePath.substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "/" }
        val fileName = remotePath.substringAfterLast('/', missingDelimiterValue = remotePath)
        val listing = directoryCache[parent] ?: sftpClient.listFolders(
            config = resolvedConfig,
            remotePath = parent,
        ).also { directoryCache[parent] = it }

        return when (listing) {
            is NetworkResult.Success -> {
                if (listing.data.any { !it.isDirectory && it.name == fileName }) {
                    NetworkResult.Success(Unit)
                } else {
                    NetworkResult.Error(
                        code = NetworkResult.ErrorCode.NOT_FOUND,
                        message = "Remote file not found",
                    )
                }
            }

            is NetworkResult.Error -> listing
        }
    }
}
