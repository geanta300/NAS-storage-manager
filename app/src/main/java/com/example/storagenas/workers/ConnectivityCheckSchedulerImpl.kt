package com.example.storagenas.workers

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ConnectivityCheckSchedulerImpl @Inject constructor(
    private val workManager: WorkManager,
) : ConnectivityCheckScheduler {
    override fun ensureScheduled() {
        val request =
            PeriodicWorkRequestBuilder<ConnectivityCheckWorker>(
                ConnectivityCheckWorkConstants.REPEAT_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

        workManager.enqueueUniquePeriodicWork(
            ConnectivityCheckWorkConstants.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
