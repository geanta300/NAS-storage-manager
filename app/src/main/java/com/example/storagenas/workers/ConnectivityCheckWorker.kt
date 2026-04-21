package com.example.storagenas.workers

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.LogType
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.domain.usecase.ResumeQueuedUploadsIfReachableUseCase

class ConnectivityCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ConnectivityWorkerEntryPoint {
        fun resumeQueuedUploadsIfReachableUseCase(): ResumeQueuedUploadsIfReachableUseCase
        fun appLogRepository(): AppLogRepository
    }

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            ConnectivityWorkerEntryPoint::class.java,
        )
        val resumeUseCase = entryPoint.resumeQueuedUploadsIfReachableUseCase()
        val appLogRepository = entryPoint.appLogRepository()

        return runCatching {
            val resumed = resumeUseCase(trigger = "periodic_connectivity_worker")
            if (resumed > 0) {
                appLogRepository.addLog(
                    AppLog(
                        type = LogType.CONNECTIVITY,
                        message = "Periodic connectivity check resumed $resumed queued upload(s)",
                    ),
                )
            }
            Result.success()
        }.getOrElse { error ->
            appLogRepository.addLog(
                AppLog(
                    type = LogType.ERROR,
                    message = "Periodic connectivity check failed: ${error.message ?: "Unknown error"}",
                ),
            )
            Result.retry()
        }
    }
}
