package com.example.storagenas.domain.usecase

import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.LogType
import com.example.storagenas.domain.model.UploadStatus
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.domain.repository.UploadRepository
import com.example.storagenas.network.model.NasConnectionState
import com.example.storagenas.workers.UploadWorkScheduler
import javax.inject.Inject

class ResumeQueuedUploadsIfReachableUseCase @Inject constructor(
    private val evaluateConnectionStatusUseCase: EvaluateConnectionStatusUseCase,
    private val uploadRepository: UploadRepository,
    private val uploadWorkScheduler: UploadWorkScheduler,
    private val appLogRepository: AppLogRepository,
) {
    suspend operator fun invoke(
        trigger: String,
        preEvaluatedState: NasConnectionState? = null,
    ): Int {
        val state = preEvaluatedState ?: evaluateConnectionStatusUseCase()
        if (state != NasConnectionState.ZeroTierConnectedNasReachable) return 0

        val queued = uploadRepository.getTasksByStatus(UploadStatus.QUEUED)
        if (queued.isEmpty()) return 0

        val ids = queued.map { it.id }
        uploadWorkScheduler.enqueueUploadTasks(ids)

        appLogRepository.addLog(
            AppLog(
                type = LogType.UPLOAD,
                message = "Auto-resumed ${ids.size} queued upload(s) from $trigger",
            ),
        )

        return ids.size
    }
}
