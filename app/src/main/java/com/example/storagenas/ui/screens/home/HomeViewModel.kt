package com.example.storagenas.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.LogType
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.network.common.NetworkResult
import com.example.storagenas.network.sftp.SftpClient
import com.example.storagenas.network.zerotier.ZeroTierIntegrationManager
import com.example.storagenas.network.zerotier.ZeroTierStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isChecking: Boolean = false,
    val zeroTierStatusText: String = "Not checked yet",
    val nasStatusText: String = "Not checked yet",
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val zeroTierIntegrationManager: ZeroTierIntegrationManager,
    private val nasConfigRepository: NasConfigRepository,
    private val sftpClient: SftpClient,
    private val appLogRepository: AppLogRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastReportedStatusDigest: String = ""
    private var lastNasStatusText: String = "Not checked yet"
    private var lastNasStatusCheckAtMs: Long = 0L
    private var lastAutoConnectAttemptAtMs: Long = 0L

    init {
        refreshConnectionStatus()
        startStatusHeartbeat()
    }

    fun refreshConnectionStatus() {
        refreshConnectionStatusInternal(isManual = true)
    }

    private fun refreshConnectionStatusInternal(isManual: Boolean) {
        if (refreshJob?.isActive == true) {
            return
        }
        refreshJob = viewModelScope.launch {
            if (isManual) {
                _uiState.update { it.copy(isChecking = true) }
            }

            runCatching {
                val zeroTierStatus = fetchZeroTierStatus(isManual = isManual)
                val zeroTierText = zeroTierStatus.toDisplayText()
                val nasText = resolveNasStatusText(
                    zeroTierStatus = zeroTierStatus,
                    isManual = isManual,
                )

                _uiState.update {
                    it.copy(
                        isChecking = false,
                        zeroTierStatusText = zeroTierText,
                        nasStatusText = nasText,
                    )
                }

                val digest = "zerotier=$zeroTierText | nas=$nasText"
                if (isManual || digest != lastReportedStatusDigest) {
                    lastReportedStatusDigest = digest
                    appLogRepository.addLog(
                        AppLog(
                            type = LogType.CONNECTIVITY,
                            message = "Connection status: $digest",
                        ),
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        zeroTierStatusText = "Error: ${error.message ?: "Unexpected failure"}",
                        nasStatusText = "Unknown",
                    )
                }
            }.also {
                refreshJob = null
            }
        }
    }

    private fun startStatusHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(STATUS_HEARTBEAT_INTERVAL_MS)
                refreshConnectionStatusInternal(isManual = false)
            }
        }
    }

    override fun onCleared() {
        heartbeatJob?.cancel()
        super.onCleared()
    }

    private suspend fun resolveNasStatusText(
        zeroTierStatus: ZeroTierStatus,
        isManual: Boolean,
    ): String {
        val now = System.currentTimeMillis()
        if (!zeroTierStatus.interfaceActive) {
            val unknown = "Unknown (ZeroTier disconnected)"
            lastNasStatusText = unknown
            lastNasStatusCheckAtMs = now
            return unknown
        }

        val shouldProbeNas =
            isManual ||
                now - lastNasStatusCheckAtMs >= NAS_STATUS_HEARTBEAT_INTERVAL_MS ||
                lastNasStatusText == "Not checked yet"

        if (!shouldProbeNas) return lastNasStatusText

        val config = nasConfigRepository.getConfig()
        if (config == null) {
            val status = "Not configured"
            lastNasStatusText = status
            lastNasStatusCheckAtMs = now
            return status
        }

        val status = when (val result = sftpClient.testConnection(config)) {
            is NetworkResult.Success -> "Reachable"
            is NetworkResult.Error -> when (result.code) {
                NetworkResult.ErrorCode.CONNECTION,
                NetworkResult.ErrorCode.TIMEOUT,
                -> "Unreachable"
                else -> "Error: ${result.message}"
            }
        }
        lastNasStatusText = status
        lastNasStatusCheckAtMs = now
        return status
    }

    private fun ZeroTierStatus.toDisplayText(): String = when {
        !configured -> message ?: "ZeroTier not configured"
        interfaceActive -> "ZeroTier active (${assignedIpv4 ?: "ip pending"})"
        else -> message ?: "ZeroTier disconnected"
    }

    private suspend fun fetchZeroTierStatus(isManual: Boolean): ZeroTierStatus {
        val now = System.currentTimeMillis()
        val shouldAttemptAutoConnect =
            isManual ||
                (now - lastAutoConnectAttemptAtMs) >= ZERO_TIER_AUTO_CONNECT_INTERVAL_MS

        return if (shouldAttemptAutoConnect) {
            lastAutoConnectAttemptAtMs = now
            zeroTierIntegrationManager.ensureConnected()
        } else {
            zeroTierIntegrationManager.getStatus()
        }
    }

    private companion object {
        const val STATUS_HEARTBEAT_INTERVAL_MS = 8_000L
        const val NAS_STATUS_HEARTBEAT_INTERVAL_MS = 25_000L
        const val ZERO_TIER_AUTO_CONNECT_INTERVAL_MS = 30_000L
    }
}
