package com.example.storagenas.workers

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class UploadWorkSchedulerImpl @Inject constructor(
    private val workManager: WorkManager,
) : UploadWorkScheduler {
    override fun enqueueUploadTask(taskId: Long) {
        val request = createRequest(taskId)
        workManager.enqueueUniqueWork(
            UploadWorkConstants.UNIQUE_WORK_PREFIX + taskId,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun enqueueUploadTasks(taskIds: List<Long>) {
        taskIds.forEach(::enqueueUploadTask)
    }

    private fun createRequest(taskId: Long) =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(UploadWorkConstants.KEY_UPLOAD_TASK_ID, taskId)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()
}
