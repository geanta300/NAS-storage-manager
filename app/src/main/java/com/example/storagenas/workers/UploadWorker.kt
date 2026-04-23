package com.example.storagenas.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.LogType
import com.example.storagenas.domain.model.UploadStatus
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.domain.repository.QueueProgressSnapshot
import com.example.storagenas.domain.repository.UploadRepository
import com.example.storagenas.network.common.NetworkResult
import com.example.storagenas.network.sftp.SftpClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.CancellationException

class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    companion object {
        private const val NOTIFICATION_CHANNEL_ID: String = "upload_worker_channel"
        private const val NOTIFICATION_CHANNEL_NAME: String = "Upload transfers"
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UploadWorkerEntryPoint {
        fun uploadRepository(): UploadRepository
        fun nasConfigRepository(): NasConfigRepository
        fun sftpClient(): SftpClient
        fun appLogRepository(): AppLogRepository
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(UploadWorkConstants.KEY_UPLOAD_TASK_ID, -1L)
        if (taskId <= 0L) return Result.failure()

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            UploadWorkerEntryPoint::class.java,
        )
        val uploadRepository = entryPoint.uploadRepository()
        val nasConfigRepository = entryPoint.nasConfigRepository()
        val sftpClient = entryPoint.sftpClient()
        val appLogRepository = entryPoint.appLogRepository()

        return try {
            doWorkSafely(
                taskId = taskId,
                uploadRepository = uploadRepository,
                nasConfigRepository = nasConfigRepository,
                sftpClient = sftpClient,
                appLogRepository = appLogRepository,
            )
        } catch (_: CancellationException) {
            val cancelledTask = uploadRepository.getTaskById(taskId)
            if (cancelledTask != null) {
                cancelTask(taskId, cancelledTask.displayName, uploadRepository, appLogRepository)
            }
            throw CancellationException()
        } catch (error: Throwable) {
            handleUnexpectedWorkerError(taskId, error, uploadRepository, appLogRepository)
        }
    }

    private suspend fun doWorkSafely(
        taskId: Long,
        uploadRepository: UploadRepository,
        nasConfigRepository: NasConfigRepository,
        sftpClient: SftpClient,
        appLogRepository: AppLogRepository,
    ): Result {

        val task = uploadRepository.getTaskById(taskId) ?: return Result.failure()
        if (isStopped) {
            return cancelTask(taskId, task.displayName, uploadRepository, appLogRepository)
        }

        safeSetForeground(
            title = "Folder upload request",
            progress = uploadRepository.getQueueProgressSnapshot().completedPercent,
            uploadRepository = uploadRepository,
            appLogRepository = appLogRepository,
        )

        val uploadStartedAt = System.currentTimeMillis()

        uploadRepository.updateTaskState(
            id = taskId,
            status = UploadStatus.UPLOADING,
            progress = 5,
            errorMessage = null,
            uploadStartedAt = uploadStartedAt,
        )
        if (isStopped) {
            return cancelTask(taskId, task.displayName, uploadRepository, appLogRepository)
        }

        val config = nasConfigRepository.getConfig()
        if (config == null) {
            return failTask(taskId, "NAS configuration missing", uploadRepository, appLogRepository)
        }

        val uri = task.localUri.toUri()
        val source = copyUriToTempFile(uri, task.displayName)
            ?: return failTask(taskId, "Cannot read source file", uploadRepository, appLogRepository)

        try {
            if (isStopped) {
                return cancelTask(taskId, task.displayName, uploadRepository, appLogRepository)
            }

            val remotePath = joinRemotePath(task.destinationPath, task.displayName)
        uploadRepository.updateTaskState(
            id = taskId,
            status = UploadStatus.UPLOADING,
            progress = 60,
            errorMessage = null,
        )
        safeSetForeground(
            title = "Folder upload request",
            progress = uploadRepository.getQueueProgressSnapshot().completedPercent,
            uploadRepository = uploadRepository,
            appLogRepository = appLogRepository,
        )

            val result = sftpClient.uploadFile(
                config = config,
                localFilePath = source.absolutePath,
                remotePath = remotePath,
            )

            if (isStopped) {
                return cancelTask(taskId, task.displayName, uploadRepository, appLogRepository)
            }

            return when (result) {
            is NetworkResult.Success -> {
                uploadRepository.updateTaskState(
                    id = taskId,
                    status = UploadStatus.SUCCESS,
                    progress = 100,
                    errorMessage = null,
                    uploadFinishedAt = System.currentTimeMillis(),
                )
                appLogRepository.addLog(
                    AppLog(
                        type = LogType.UPLOAD,
                        message = "Uploaded ${task.displayName} to $remotePath",
                    ),
                )
                val snapshot = uploadRepository.getQueueProgressSnapshot()
                safeSetForeground(
                    title = "Folder upload request",
                    progress = snapshot.completedPercent,
                    uploadRepository = uploadRepository,
                    appLogRepository = appLogRepository,
                )
                Result.success()
            }

                is NetworkResult.Error -> {
                    if (isStopped) {
                        cancelTask(taskId, task.displayName, uploadRepository, appLogRepository)
                    } else if (result.code.isRetryable() && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        retryTask(taskId, result.message, uploadRepository, appLogRepository)
                    } else {
                        failTask(taskId, result.message, uploadRepository, appLogRepository)
                    }
                }
            }
        } finally {
            runCatching { source.delete() }
        }
    }

    private suspend fun safeSetForeground(
        title: String,
        progress: Int,
        uploadRepository: UploadRepository,
        appLogRepository: AppLogRepository,
    ) {
        runCatching {
            val snapshot = uploadRepository.getQueueProgressSnapshot()
            setForeground(
                createForegroundInfo(
                    title = title,
                    progress = progress,
                    snapshot = snapshot,
                ),
            )
        }.onFailure { error ->
            appLogRepository.addLog(
                AppLog(
                    type = LogType.WARNING,
                    message = "Upload worker foreground start failed: ${error.message}",
                ),
            )
        }
    }

    private suspend fun handleUnexpectedWorkerError(
        taskId: Long,
        error: Throwable,
        uploadRepository: UploadRepository,
        appLogRepository: AppLogRepository,
    ): Result {
        val latest = uploadRepository.getTaskById(taskId)
        val shouldMarkFailed = latest?.status == UploadStatus.PENDING ||
            latest?.status == UploadStatus.QUEUED ||
            latest?.status == UploadStatus.UPLOADING

        if (shouldMarkFailed) {
            uploadRepository.updateTaskState(
                id = taskId,
                status = UploadStatus.FAILED,
                progress = 0,
                errorMessage = error.message ?: "Unexpected upload worker error",
                uploadFinishedAt = System.currentTimeMillis(),
            )
        }
        appLogRepository.addLog(
            AppLog(
                type = LogType.ERROR,
                message = "Upload worker crashed for task $taskId: ${error::class.java.simpleName}: ${error.message}",
            ),
        )
        return Result.failure()
    }

    private suspend fun cancelTask(
        taskId: Long,
        displayName: String,
        uploadRepository: UploadRepository,
        appLogRepository: AppLogRepository,
    ): Result {
        val currentTask = uploadRepository.getTaskById(taskId)
        if (currentTask?.status == UploadStatus.CANCELLED) {
            return Result.success()
        }
        uploadRepository.updateTaskState(
            id = taskId,
            status = UploadStatus.CANCELLED,
            progress = 0,
            errorMessage = "Cancelled by user",
            uploadFinishedAt = System.currentTimeMillis(),
        )
        appLogRepository.addLog(
            AppLog(
                type = LogType.WARNING,
                message = "Upload task $taskId cancelled: $displayName",
            ),
        )
        return Result.success()
    }

    private suspend fun failTask(
        taskId: Long,
        message: String,
        uploadRepository: UploadRepository,
        appLogRepository: AppLogRepository,
    ): Result {
        uploadRepository.updateTaskState(
            id = taskId,
            status = UploadStatus.FAILED,
            progress = 0,
            errorMessage = message,
            uploadFinishedAt = System.currentTimeMillis(),
        )
        appLogRepository.addLog(
            AppLog(
                type = LogType.ERROR,
                message = "Upload task $taskId failed: $message",
            ),
        )
        return Result.failure()
    }

    private fun copyUriToTempFile(uri: Uri, displayName: String): File? {
        val safeName = displayName.replace("/", "_").replace("\\", "_")
        val target = File.createTempFile("upload_", "_$safeName", applicationContext.cacheDir)

        return try {
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                target.delete()
                return null
            }
            target
        } catch (error: CancellationException) {
            target.delete()
            throw error
        } catch (_: Throwable) {
            target.delete()
            null
        }
    }

    private suspend fun retryTask(
        taskId: Long,
        message: String,
        uploadRepository: UploadRepository,
        appLogRepository: AppLogRepository,
    ): Result {
        uploadRepository.updateTaskState(
            id = taskId,
            status = UploadStatus.QUEUED,
            progress = 0,
            errorMessage = message,
            clearTiming = true,
        )
        appLogRepository.addLog(
            AppLog(
                type = LogType.WARNING,
                message = "Upload task $taskId queued for retry ${runAttemptCount + 1}/$MAX_RETRY_ATTEMPTS: $message",
            ),
        )
        return Result.retry()
    }

    private fun NetworkResult.ErrorCode?.isRetryable(): Boolean = when (this) {
        NetworkResult.ErrorCode.UNKNOWN,
        NetworkResult.ErrorCode.TIMEOUT,
        NetworkResult.ErrorCode.CONNECTION,
        NetworkResult.ErrorCode.IO -> true
        else -> false
    }

    private fun joinRemotePath(base: String, fileName: String): String {
        val normalized = if (base.endsWith('/')) base.dropLast(1) else base
        return if (normalized.isBlank() || normalized == "/") "/$fileName" else "$normalized/$fileName"
    }

    private fun createForegroundInfo(
        title: String,
        progress: Int,
        snapshot: QueueProgressSnapshot,
    ): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading to NAS")
            .setContentText("$title • ${snapshot.completedCount}/${snapshot.totalCount} processed • ${snapshot.failedCount} failed")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()

        val notificationId = 1_001
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }
}
