package com.example.storagenas.network.reachability

import com.example.storagenas.domain.model.NasConfig
import com.example.storagenas.domain.model.ZeroTierConnectionMode
import com.example.storagenas.network.common.NetworkResult
import com.example.storagenas.network.model.NasConnectionState
import com.example.storagenas.network.sftp.SftpClient
import com.example.storagenas.network.zerotier.ZeroTierIntegrationManager
import javax.inject.Inject

class ConnectionStatusEvaluator @Inject constructor(
    private val zeroTierIntegrationManager: ZeroTierIntegrationManager,
    private val sftpClient: SftpClient,
) {
    suspend fun evaluate(config: NasConfig): NasConnectionState {
        val zeroTierStatus = if (config.zeroTierConnectionMode == ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST) {
            zeroTierIntegrationManager.getStatus()
        } else {
            zeroTierIntegrationManager.ensureConnected()
        }

        if (
            config.zeroTierConnectionMode != ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST &&
            !zeroTierStatus.interfaceActive
        ) {
            return NasConnectionState.ZeroTierDisconnected
        }

        return when (val result = sftpClient.testConnection(config)) {
            is NetworkResult.Success -> NasConnectionState.ZeroTierConnectedNasReachable
            is NetworkResult.Error ->
                if (result.code == NetworkResult.ErrorCode.CONNECTION ||
                    result.code == NetworkResult.ErrorCode.TIMEOUT
                ) {
                    NasConnectionState.ZeroTierConnectedNasUnreachable
                } else {
                    NasConnectionState.Error(result.message)
                }
        }
    }
}
