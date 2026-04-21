package com.example.storagenas.network.reachability

import com.example.storagenas.domain.model.NasConfig
import com.example.storagenas.network.common.NetworkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

class SocketNasReachabilityChecker @Inject constructor() : NasReachabilityChecker {
    override suspend fun isReachable(config: NasConfig): NetworkResult<Boolean> =
        withContext(Dispatchers.IO) {
            if (config.host.isBlank()) {
                return@withContext NetworkResult.Error(
                    code = NetworkResult.ErrorCode.VALIDATION,
                    message = "NAS host is empty.",
                )
            }

            if (config.port !in 1..65535) {
                return@withContext NetworkResult.Error(
                    code = NetworkResult.ErrorCode.VALIDATION,
                    message = "NAS port is invalid.",
                )
            }

            val probe = probeTcpReachability(
                host = config.host,
                port = config.port,
                timeoutMs = CONNECT_TIMEOUT_MS,
            )
            if (probe.isReachable) {
                return@withContext NetworkResult.Success(true)
            }

            val cause = probe.cause
            when (cause) {
                is SocketTimeoutException ->
                    NetworkResult.Error(
                        code = NetworkResult.ErrorCode.TIMEOUT,
                        message = cause.message ?: "Reachability timed out.",
                        cause = cause,
                    )

                is UnknownHostException ->
                    NetworkResult.Error(
                        code = NetworkResult.ErrorCode.NOT_FOUND,
                        message = cause.message ?: "NAS host not found.",
                        cause = cause,
                    )

                else ->
                    NetworkResult.Error(
                        code = NetworkResult.ErrorCode.CONNECTION,
                        message = probe.errorMessage ?: "NAS is unreachable.",
                        cause = cause,
                    )
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS: Int = 4_000
    }
}
