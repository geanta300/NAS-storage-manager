package com.example.storagenas.domain.usecase

import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.AuthType
import com.example.storagenas.domain.model.NasConfig
import com.example.storagenas.domain.model.UploadStatus
import com.example.storagenas.domain.model.UploadTask
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.domain.repository.QueueProgressSnapshot
import com.example.storagenas.domain.repository.UploadRepository
import com.example.storagenas.network.common.NetworkResult
import com.example.storagenas.network.model.NasConnectionState
import com.example.storagenas.network.model.RemoteEntry
import com.example.storagenas.network.reachability.ConnectionStatusEvaluator
import com.example.storagenas.network.sftp.SftpClient
import com.example.storagenas.network.zerotier.ZeroTierIntegrationManager
import com.example.storagenas.network.zerotier.ZeroTierStatus
import com.example.storagenas.workers.UploadWorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeQueuedUploadsIfReachableUseCaseTest {
    @Test
    fun `pre-evaluated reachable state skips re-evaluation`() = runBlocking {
        val env = TestEnvironment()
        val useCase = env.createUseCase()

        val resumed = useCase(
            trigger = "home_refresh",
            preEvaluatedState = NasConnectionState.ZeroTierConnectedNasReachable,
        )

        assertEquals(2, resumed)
        assertEquals(0, env.sftpTestConnectionCalls)
        assertEquals(listOf(1L, 2L), env.enqueuedTaskIds)
    }

    @Test
    fun `without pre-evaluated state performs one reachability evaluation`() = runBlocking {
        val env = TestEnvironment()
        val useCase = env.createUseCase()

        val resumed = useCase(trigger = "worker")

        assertEquals(2, resumed)
        assertEquals(1, env.sftpTestConnectionCalls)
        assertEquals(listOf(1L, 2L), env.enqueuedTaskIds)
    }
}

private class TestEnvironment {
    private val config = NasConfig(
        host = "192.168.1.10",
        port = 22,
        username = "test-user",
        password = "test-password",
        authType = AuthType.PASSWORD,
    )

    private val queuedTasks = listOf(
        UploadTask(id = 1L, localUri = "content://one", displayName = "one.jpg", destinationPath = "/one.jpg", status = UploadStatus.QUEUED),
        UploadTask(id = 2L, localUri = "content://two", displayName = "two.jpg", destinationPath = "/two.jpg", status = UploadStatus.QUEUED),
    )

    var sftpTestConnectionCalls: Int = 0
        private set
    var enqueuedTaskIds: List<Long> = emptyList()
        private set

    private val uploadRepository = object : UploadRepository {
        override fun observeTasks(): Flow<List<UploadTask>> = flowOf(queuedTasks)
        override fun observeTasksByStatus(status: UploadStatus): Flow<List<UploadTask>> = flowOf(queuedTasks.filter { it.status == status })
        override suspend fun getTasksByStatus(status: UploadStatus): List<UploadTask> = queuedTasks.filter { it.status == status }
        override suspend fun getTaskById(id: Long): UploadTask? = queuedTasks.firstOrNull { it.id == id }
        override suspend fun addTask(task: UploadTask): Long = task.id
        override suspend fun addTasks(tasks: List<UploadTask>): List<Long> = tasks.map { it.id }
        override suspend fun updateTask(task: UploadTask) = Unit
        override suspend fun updateTaskState(
            id: Long,
            status: UploadStatus,
            progress: Int,
            errorMessage: String?,
            uploadStartedAt: Long?,
            uploadFinishedAt: Long?,
            clearTiming: Boolean,
        ) = Unit
        override suspend fun cancelAllActiveTasks(errorMessage: String): Int = 0
        override suspend fun getQueueProgressSnapshot(): QueueProgressSnapshot =
            QueueProgressSnapshot(
                totalCount = queuedTasks.size,
                activeCount = queuedTasks.size,
                completedCount = 0,
                failedCount = 0,
                completedPercent = 0,
            )
        override suspend fun deleteTask(id: Long) = Unit
        override suspend fun clearAllTasks() = Unit
    }

    private val uploadScheduler = object : UploadWorkScheduler {
        override fun enqueueUploadTask(taskId: Long) {
            enqueuedTaskIds = enqueuedTaskIds + taskId
        }

        override fun enqueueUploadTasks(taskIds: List<Long>) {
            enqueuedTaskIds = taskIds
        }

        override fun cancelUploadTask(taskId: Long) = Unit

        override fun cancelAllUploads() = Unit
    }

    private val appLogRepository = object : AppLogRepository {
        private val logsState = MutableStateFlow(emptyList<AppLog>())

        override fun observeLogs(): Flow<List<AppLog>> = logsState

        override suspend fun addLog(log: AppLog): Long {
            logsState.value = logsState.value + log
            return logsState.value.size.toLong()
        }

        override suspend fun addLogs(logs: List<AppLog>) {
            logsState.value = logsState.value + logs
        }

        override suspend fun clearLogs() {
            logsState.value = emptyList()
        }
    }

    private val sftpClient = object : SftpClient {
        override suspend fun connect(config: NasConfig): NetworkResult<Unit> = NetworkResult.Success(Unit)

        override suspend fun testConnection(config: NasConfig): NetworkResult<Unit> {
            sftpTestConnectionCalls += 1
            return NetworkResult.Success(Unit)
        }

        override suspend fun listFolders(config: NasConfig, remotePath: String): NetworkResult<List<RemoteEntry>> =
            NetworkResult.Success(emptyList())

        override suspend fun createDirectory(config: NasConfig, remotePath: String): NetworkResult<Unit> =
            NetworkResult.Success(Unit)

        override suspend fun uploadFile(config: NasConfig, localFilePath: String, remotePath: String): NetworkResult<Unit> =
            NetworkResult.Success(Unit)

        override suspend fun downloadFile(config: NasConfig, remotePath: String, localFilePath: String): NetworkResult<Unit> =
            NetworkResult.Success(Unit)

        override suspend fun moveFile(
            config: NasConfig,
            sourceRemotePath: String,
            destinationRemotePath: String,
        ): NetworkResult<Unit> = NetworkResult.Success(Unit)

        override suspend fun deleteFile(config: NasConfig, remotePath: String): NetworkResult<Unit> =
            NetworkResult.Success(Unit)
    }

    private val zeroTierIntegrationManager = object : ZeroTierIntegrationManager {
        private val status = ZeroTierStatus(
            configured = true,
            embeddedClientAvailable = true,
            transportReady = true,
            interfaceActive = true,
        )

        override suspend fun getStatus(): ZeroTierStatus = status
        override suspend fun ensureConnected(): ZeroTierStatus = status
        override suspend fun ensureRuntimeReadyForMonitoring(): ZeroTierStatus = status
        override fun onDataSessionOpened() = Unit
        override fun onDataSessionClosed() = Unit
        override fun activeDataSessionCount(): Int = 0
        override fun shouldAvoidZeroTierDataPlane(): Boolean = false
    }

    private val nasConfigRepository = object : NasConfigRepository {
        override fun observeConfig(): Flow<NasConfig?> = flowOf(config)
        override suspend fun getConfig(): NasConfig = config
        override suspend fun saveConfig(config: NasConfig) = Unit
        override suspend fun clearConfig() = Unit
    }

    private val connectionStatusEvaluator = ConnectionStatusEvaluator(
        zeroTierIntegrationManager = zeroTierIntegrationManager,
        sftpClient = sftpClient,
    )

    private val evaluateConnectionStatusUseCase = EvaluateConnectionStatusUseCase(
        nasConfigRepository = nasConfigRepository,
        connectionStatusEvaluator = connectionStatusEvaluator,
    )

    fun createUseCase(): ResumeQueuedUploadsIfReachableUseCase =
        ResumeQueuedUploadsIfReachableUseCase(
            evaluateConnectionStatusUseCase = evaluateConnectionStatusUseCase,
            uploadRepository = uploadRepository,
            uploadWorkScheduler = uploadScheduler,
            appLogRepository = appLogRepository,
        )
}
