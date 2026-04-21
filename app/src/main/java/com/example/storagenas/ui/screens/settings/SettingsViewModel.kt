package com.example.storagenas.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.AuthType
import com.example.storagenas.domain.model.LogType
import com.example.storagenas.domain.model.NasConfig
import com.example.storagenas.domain.model.ZeroTierConnectionMode
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.domain.repository.SettingsRepository
import com.example.storagenas.network.common.NetworkResult
import com.example.storagenas.network.sftp.SftpClient
import com.example.storagenas.network.zerotier.ZeroTierKeepAliveCoordinator
import com.example.storagenas.network.zerotier.ZeroTierIntegrationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val password: String = "",
    val defaultRemotePath: String = ".",
    val zeroTierNetworkId: String = "",
    val zeroTierApiToken: String = "",
    val zeroTierConnectionMode: ZeroTierConnectionMode = ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST,
    val keepZeroTierAliveInBackground: Boolean = false,
    val useTcpFallbackOnLan: Boolean = false,
    val thumbnailCacheEnabled: Boolean = true,
    val isBusy: Boolean = false,
    val infoMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val nasConfigRepository: NasConfigRepository,
    private val settingsRepository: SettingsRepository,
    private val appLogRepository: AppLogRepository,
    private val sftpClient: SftpClient,
    private val zeroTierIntegrationManager: ZeroTierIntegrationManager,
    private val zeroTierKeepAliveCoordinator: ZeroTierKeepAliveCoordinator,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadCurrentConfig()
        viewModelScope.launch {
            nasConfigRepository.observeConfig().collect { config ->
                config ?: return@collect
                _uiState.update {
                    it.copy(defaultRemotePath = config.defaultRemotePath.ifBlank { "." })
                }
            }
        }
    }

    fun onHostChanged(value: String) = _uiState.update { it.copy(host = value) }
    fun onPortChanged(value: String) = _uiState.update { it.copy(port = value) }
    fun onUsernameChanged(value: String) = _uiState.update { it.copy(username = value) }
    fun onPasswordChanged(value: String) = _uiState.update { it.copy(password = value) }
    fun onDefaultRemotePathChanged(value: String) = _uiState.update { it.copy(defaultRemotePath = value) }
    fun onZeroTierNetworkIdChanged(value: String) = _uiState.update { it.copy(zeroTierNetworkId = value) }
    fun onZeroTierApiTokenChanged(value: String) = _uiState.update { it.copy(zeroTierApiToken = value) }
    fun onZeroTierConnectionModeChanged(value: ZeroTierConnectionMode) =
        _uiState.update { it.copy(zeroTierConnectionMode = value) }

    fun onKeepZeroTierAliveInBackgroundChanged(value: Boolean) {
        _uiState.update { it.copy(keepZeroTierAliveInBackground = value) }
    }
    fun onUseTcpFallbackOnLanChanged(value: Boolean) = _uiState.update { it.copy(useTcpFallbackOnLan = value) }

    fun onKeepAliveNotificationPermissionDenied() {
        _uiState.update {
            it.copy(
                keepZeroTierAliveInBackground = false,
                infoMessage = "Allow Notifications to show the ongoing ZeroTier keep-alive status.",
            )
        }
    }

    fun saveConfig() {
        val config = buildNasConfigOrNull() ?: return
        val desiredKeepAlive = _uiState.value.keepZeroTierAliveInBackground

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, infoMessage = null) }
            nasConfigRepository.saveConfig(config)
            settingsRepository.updateSettings {
                it.copy(
                    defaultRemotePath = config.defaultRemotePath,
                    zeroTierNetworkId = _uiState.value.zeroTierNetworkId.trim(),
                    zeroTierApiToken = _uiState.value.zeroTierApiToken.trim(),
                    zeroTierConnectionMode = _uiState.value.zeroTierConnectionMode,
                    keepZeroTierAliveInBackground = desiredKeepAlive,
                    useTcpFallbackOnLan = _uiState.value.useTcpFallbackOnLan,
                    thumbnailCacheEnabled = true,
                )
            }
            zeroTierKeepAliveCoordinator.applyPreference(
                enabled = desiredKeepAlive,
                trigger = "settings_save",
            )
            appLogRepository.addLog(
                AppLog(type = LogType.INFO, message = "NAS settings saved for host ${config.host}:${config.port}"),
            )
            _uiState.update { it.copy(isBusy = false, infoMessage = "Settings saved") }
        }
    }

    fun connectToZeroTier() {
        val networkId = _uiState.value.zeroTierNetworkId.trim()
        if (networkId.isBlank()) {
            _uiState.update { it.copy(infoMessage = "ZeroTier network ID is required") }
            return
        }
        if (!networkId.removePrefix("0x").matches(Regex("^[0-9a-fA-F]{16}$"))) {
            _uiState.update { it.copy(infoMessage = "ZeroTier network ID must be 16 hex characters") }
            return
        }

        val apiToken = _uiState.value.zeroTierApiToken.trim()

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, infoMessage = "Connecting to ZeroTier...") }

            settingsRepository.updateSettings {
                it.copy(
                    zeroTierNetworkId = networkId,
                    zeroTierApiToken = apiToken,
                )
            }

            val status = zeroTierIntegrationManager.ensureConnected()
            if (status.interfaceActive) {
                val ip = status.assignedIpv4 ?: "IP pending"
                appLogRepository.addLog(
                    AppLog(type = LogType.CONNECTIVITY, message = "ZeroTier connect successful (ip=$ip)"),
                )
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        infoMessage = "ZeroTier connected ($ip)",
                    )
                }
            } else {
                val message = status.message ?: "ZeroTier connection failed."
                appLogRepository.addLog(
                    AppLog(type = LogType.WARNING, message = "ZeroTier connect failed: $message"),
                )
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        infoMessage = "ZeroTier connection failed: $message",
                    )
                }
            }
        }
    }

    fun connectToNas() {
        val config = buildNasConfigOrNull() ?: return

        viewModelScope.launch {
            val startedAtMs = System.currentTimeMillis()
            _uiState.update { it.copy(isBusy = true, infoMessage = "Connecting to NAS...") }
            when (val result = sftpClient.connect(config)) {
                is NetworkResult.Success -> {
                    val elapsedMs = System.currentTimeMillis() - startedAtMs
                    appLogRepository.addLog(
                        AppLog(
                            type = LogType.CONNECTIVITY,
                            message = "NAS connection successful in ${elapsedMs}ms",
                        ),
                    )
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            infoMessage = "NAS connection successful (${elapsedMs}ms)",
                        )
                    }
                }

                is NetworkResult.Error -> {
                    val elapsedMs = System.currentTimeMillis() - startedAtMs
                    appLogRepository.addLog(
                        AppLog(
                            type = LogType.ERROR,
                            message = "NAS connection failed in ${elapsedMs}ms: ${result.message}",
                        ),
                    )
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            infoMessage = "NAS connection failed (${elapsedMs}ms): ${result.message}",
                        )
                    }
                }
            }
        }
    }

    private fun loadCurrentConfig() {
        viewModelScope.launch {
            val config = nasConfigRepository.getConfig()
            val settings = settingsRepository.observeSettings().first()
            _uiState.update {
                it.copy(
                    host = config?.host ?: it.host,
                    port = config?.port?.toString() ?: it.port,
                    username = config?.username ?: it.username,
                    password = config?.password ?: it.password,
                    defaultRemotePath = config?.defaultRemotePath ?: settings.defaultRemotePath,
                    zeroTierNetworkId = settings.zeroTierNetworkId,
                    zeroTierApiToken = settings.zeroTierApiToken,
                    zeroTierConnectionMode = settings.zeroTierConnectionMode,
                    keepZeroTierAliveInBackground = settings.keepZeroTierAliveInBackground,
                    useTcpFallbackOnLan = settings.useTcpFallbackOnLan,
                    thumbnailCacheEnabled = true,
                )
            }
        }
    }

    private fun buildNasConfigOrNull(): NasConfig? {
        val state = _uiState.value
        val port = state.port.toIntOrNull()
        if (state.host.isBlank()) {
            _uiState.update { it.copy(infoMessage = "Host is required") }
            return null
        }
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(infoMessage = "Port must be between 1 and 65535") }
            return null
        }
        if (state.username.isBlank()) {
            _uiState.update { it.copy(infoMessage = "Username is required") }
            return null
        }
        val requiresEmbeddedConfig =
            state.zeroTierConnectionMode == ZeroTierConnectionMode.AUTO_FALLBACK ||
                state.zeroTierConnectionMode == ZeroTierConnectionMode.EMBEDDED_ONLY
        if (requiresEmbeddedConfig) {
            if (state.zeroTierNetworkId.isBlank()) {
                _uiState.update { it.copy(infoMessage = "ZeroTier network ID is required for embedded mode") }
                return null
            }
            if (!state.zeroTierNetworkId.removePrefix("0x").matches(Regex("^[0-9a-fA-F]{16}$"))) {
                _uiState.update { it.copy(infoMessage = "ZeroTier network ID must be 16 hex characters") }
                return null
            }
        }

        return NasConfig(
            host = state.host.trim(),
            port = port,
            username = state.username.trim(),
            password = state.password,
            authType = AuthType.PASSWORD,
            defaultRemotePath = state.defaultRemotePath.ifBlank { "." },
            useTcpFallbackOnLan = state.useTcpFallbackOnLan,
            zeroTierConnectionMode = state.zeroTierConnectionMode,
            updatedAt = System.currentTimeMillis(),
        )
    }
}
