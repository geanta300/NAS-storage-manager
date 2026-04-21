package com.example.storagenas.workers

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.LogType
import com.example.storagenas.domain.model.UploadStatus
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.domain.repository.UploadRepository
import com.example.storagenas.network.common.NetworkResult
import com.example.storagenas.network.sftp.SftpClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File

class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

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

        val task = uploadRepository.getTaskById(taskId) ?: return Result.failure()
        val uploadStartedAt = System.currentTimeMillis()

        uploadRepository.updateTaskState(
            id = taskId,
            status = UploadStatus.UPLOADING,
            progress = 5,
            errorMessage = null,
            uploadStartedAt = uploadStartedAt,
        )

        val config = nasConfigRepository.getConfig()
        if (config == null) {
            return failTask(taskId, "NAS configuration missing", uploadRepository, appLogRepository)
        }

        val uri = task.localUri.toUri()
        val source = copyUriToTempFile(uri, task.displayName)
            ?: return failTask(taskId, "Cannot read source file", uploadRepository, appLogRepository)

        val remotePath = joinRemotePath(task.destinationPath, task.displayName)
        uploadRepository.updateTaskState(
            id = taskId,
            status = UploadStatus.UPLOADING,
            progress = 60,
            errorMessage = null,
        )

        val result = sftpClient.uploadFile(
            config = config,
            localFilePath = source.absolutePath,
            remotePath = remotePath,
        )

        runCatching { source.delete() }

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
                Result.success()
            }

            is NetworkResult.Error -> {
                failTask(taskId, result.message, uploadRepository, appLogRepository)
            }
        }
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

        return runCatching {
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            target
        }.getOrNull()
    }

    private fun joinRemotePath(base: String, fileName: String): String {
        val normalized = if (base.endsWith('/')) base.dropLast(1) else base
        return if (normalized.isBlank() || normalized == "/") "/$fileName" else "$normalized/$fileName"
    }
}
