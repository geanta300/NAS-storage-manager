package com.example.storagenas.network.reachability

import java.net.InetSocketAddress
import java.net.Socket

internal data class TcpReachabilityProbeResult(
    val isReachable: Boolean,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val cause: Throwable? = null,
)

internal fun probeTcpReachability(
    host: String,
    port: Int,
    timeoutMs: Int,
): TcpReachabilityProbeResult =
    runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
        }
    }.fold(
        onSuccess = {
            TcpReachabilityProbeResult(isReachable = true)
        },
        onFailure = { error ->
            TcpReachabilityProbeResult(
                isReachable = false,
                errorClass = error::class.java.simpleName,
                errorMessage = error.message,
                cause = error,
            )
        },
    )
