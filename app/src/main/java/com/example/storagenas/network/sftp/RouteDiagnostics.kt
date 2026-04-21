package com.example.storagenas.network.sftp

import com.example.storagenas.domain.model.ZeroTierConnectionMode

internal enum class HostTypeLabel {
    PRIVATE,
    PUBLIC_OR_DOMAIN,
}

internal enum class NetworkTypeLabel {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
    UNKNOWN,
}

internal fun classifyHostType(host: String): HostTypeLabel {
    val normalized = host.trim().lowercase()
    if (normalized == "localhost") return HostTypeLabel.PRIVATE

    val octets = parseIpv4Literal(normalized) ?: return HostTypeLabel.PUBLIC_OR_DOMAIN
    return if (isPrivateIpv4(octets)) HostTypeLabel.PRIVATE else HostTypeLabel.PUBLIC_OR_DOMAIN
}

internal fun shouldFlagExpectedUnreachablePrivateIpOnCellular(
    hostType: HostTypeLabel,
    networkType: NetworkTypeLabel,
): Boolean = hostType == HostTypeLabel.PRIVATE && networkType == NetworkTypeLabel.CELLULAR

internal data class TcpFallbackDecisionInput(
    val isLanEligible: Boolean,
    val hostLikelyOnCurrentLan: Boolean,
    val directTcpProbeSucceeded: Boolean,
    val userAllowsFallback: Boolean,
    val quarantineActive: Boolean,
    val hasRecentDisruptiveEvent: Boolean,
    val hasRecentZeroTierTimeout: Boolean,
    val hasRecentZeroTierRouteUnavailable: Boolean,
    val avoidZeroTierDataPlane: Boolean,
)

internal data class TcpFallbackDecision(
    val allowTcpFallback: Boolean,
    val forceTcpFallback: Boolean,
    val reason: String?,
)

internal enum class PreferredSocketRoute {
    SYSTEM_SOCKET,
    EMBEDDED_ZEROTIER,
}

internal data class SocketRouteOrderInput(
    val connectionMode: ZeroTierConnectionMode,
    val forceTcpFallback: Boolean,
)

internal fun decideSocketRouteOrder(input: SocketRouteOrderInput): List<PreferredSocketRoute> =
    when (input.connectionMode) {
        ZeroTierConnectionMode.EMBEDDED_ONLY -> listOf(PreferredSocketRoute.EMBEDDED_ZEROTIER)
        ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST ->
            listOf(PreferredSocketRoute.SYSTEM_SOCKET, PreferredSocketRoute.EMBEDDED_ZEROTIER)

        ZeroTierConnectionMode.AUTO_FALLBACK -> {
            if (input.forceTcpFallback) {
                listOf(PreferredSocketRoute.SYSTEM_SOCKET, PreferredSocketRoute.EMBEDDED_ZEROTIER)
            } else {
                listOf(PreferredSocketRoute.EMBEDDED_ZEROTIER, PreferredSocketRoute.SYSTEM_SOCKET)
            }
        }
    }

internal fun decideTcpFallback(input: TcpFallbackDecisionInput): TcpFallbackDecision {
    if (!input.isLanEligible) {
        return TcpFallbackDecision(
            allowTcpFallback = false,
            forceTcpFallback = false,
            reason = "lanIneligible",
        )
    }

    val hasSafetySignal =
        input.quarantineActive ||
            input.hasRecentDisruptiveEvent ||
            input.hasRecentZeroTierTimeout ||
            input.hasRecentZeroTierRouteUnavailable ||
            input.avoidZeroTierDataPlane
    val fallbackRequested = input.userAllowsFallback || hasSafetySignal
    val directPathVerified = input.hostLikelyOnCurrentLan || input.directTcpProbeSucceeded

    if (!fallbackRequested) {
        return TcpFallbackDecision(
            allowTcpFallback = false,
            forceTcpFallback = false,
            reason = "fallbackNotRequested",
        )
    }

    if (!directPathVerified) {
        return TcpFallbackDecision(
            allowTcpFallback = false,
            forceTcpFallback = false,
            reason = "directPathUnverified",
        )
    }

    val reason = when {
        hasSafetySignal && input.quarantineActive -> "localQuarantine"
        hasSafetySignal && input.avoidZeroTierDataPlane -> "zerotierDataPlaneQuarantine"
        hasSafetySignal && input.hasRecentDisruptiveEvent -> "recentDisruptiveEvents"
        hasSafetySignal && input.hasRecentZeroTierTimeout -> "recentZeroTierHandshakeTimeout"
        hasSafetySignal && input.hasRecentZeroTierRouteUnavailable -> "recentZeroTierRouteUnavailable"
        input.userAllowsFallback -> "userEnabled"
        else -> "autoSafetyFallback"
    }

    return TcpFallbackDecision(
        allowTcpFallback = true,
        forceTcpFallback = hasSafetySignal,
        reason = reason,
    )
}

private fun parseIpv4Literal(host: String): IntArray? {
    val parts = host.split(".")
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for (index in parts.indices) {
        val value = parts[index].toIntOrNull() ?: return null
        if (value !in 0..255) return null
        octets[index] = value
    }
    return octets
}

/**
 * Determines if an IPv4 address belongs to a private network as defined by:
 * - RFC 1918 (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
 * - RFC 6598 (100.64.0.0/10 Carrier-Grade NAT, often used by overlay network SDKs like ZeroTier/Tailscale)
 * - RFC 3927 (169.254.0.0/16 link-local)
 * - RFC 1122 (127.0.0.0/8 loopback)
 *
 * In the context of SshjSftpClient, this function is used to gate the `useTcpFallbackOnLan` feature.
 * When the ZeroTier connection route fails, direct TCP fallback is only permitted if the host is a private
 * address (and the network type is Wi-Fi) to prevent accidentally transmitting credentials in plaintext
 * over the public internet. ZeroTier managed overlay IPs are typically allocated from these private ranges.
 */
private fun isPrivateIpv4(octets: IntArray): Boolean {
    val first = octets[0]
    val second = octets[1]
    return when {
        first == 10 -> true
        first == 127 -> true
        first == 192 && second == 168 -> true
        first == 172 && second in 16..31 -> true
        first == 169 && second == 254 -> true
        first == 100 && second in 64..127 -> true
        else -> false
    }
}
