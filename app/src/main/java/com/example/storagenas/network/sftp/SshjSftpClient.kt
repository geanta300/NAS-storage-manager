package com.example.storagenas.network.sftp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.AuthType
import com.example.storagenas.domain.model.LogType
import com.example.storagenas.domain.model.NasConfig
import com.example.storagenas.domain.model.ZeroTierConnectionMode
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.network.common.NetworkResult
import com.example.storagenas.network.model.RemoteEntry
import com.example.storagenas.network.reachability.probeTcpReachability
import com.example.storagenas.network.zerotier.ZeroTierIntegrationManager
import com.zerotier.sockets.ZeroTierSocket
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.net.SocketFactory

class SshjSftpClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val zeroTierIntegrationManager: ZeroTierIntegrationManager,
    private val appLogRepository: AppLogRepository,
) : SftpClient {
    private val sessionMutex = Mutex()
    private var cachedSession: ConnectedSession? = null

    // Both fields accessed only under sessionMutex — @Volatile not needed.
    private var lastTransportAttemptSummary: String = "none"
    private var lastSessionCreationFailure: String? = null
    private var zeroTierDataPlaneQuarantineUntilMs: Long = 0L
    private var zeroTierDataPlaneQuarantineReason: String? = null
    private var cachedDirectTcpProbe: CachedDirectTcpProbe? = null

    override suspend fun connect(config: NasConfig): NetworkResult<Unit> =
        execute(config) { /* connected if here */ }

    override suspend fun testConnection(config: NasConfig): NetworkResult<Unit> =
        execute(config) { sftp ->
            sftp.ls(".")
        }

    override suspend fun listFolders(
        config: NasConfig,
        remotePath: String,
    ): NetworkResult<List<RemoteEntry>> =
        execute(config) { sftp ->
            sftp.ls(remotePath)
                .filter { info -> info.name != "." && info.name != ".." }
                .map { info ->
                    val attrs = info.attributes
                    RemoteEntry(
                        path = joinPath(remotePath, info.name),
                        name = info.name,
                        isDirectory = info.isDirectory,
                        size = attrs?.size,
                        modifiedAt = attrs?.mtime?.takeIf { it > 0L }?.times(1_000L),
                    )
                }
        }

    override suspend fun createDirectory(
        config: NasConfig,
        remotePath: String,
    ): NetworkResult<Unit> =
        execute(config) { sftp ->
            sftp.mkdirs(remotePath)
        }

    override suspend fun uploadFile(
        config: NasConfig,
        localFilePath: String,
        remotePath: String,
    ): NetworkResult<Unit> =
        execute(config) { sftp ->
            ensureParentDirectoryExists(sftp, remotePath)
            sftp.put(localFilePath, remotePath)
        }

    override suspend fun downloadFile(
        config: NasConfig,
        remotePath: String,
        localFilePath: String,
    ): NetworkResult<Unit> =
        execute(config) { sftp ->
            sftp.get(remotePath, localFilePath)
        }

    override suspend fun moveFile(
        config: NasConfig,
        sourceRemotePath: String,
        destinationRemotePath: String,
    ): NetworkResult<Unit> =
        execute(config) { sftp ->
            ensureParentDirectoryExists(sftp, destinationRemotePath)
            sftp.rename(sourceRemotePath, destinationRemotePath)
        }

    override suspend fun deleteFile(
        config: NasConfig,
        remotePath: String,
    ): NetworkResult<Unit> =
        execute(config) { sftp ->
            sftp.rm(remotePath)
        }

    private suspend fun <T> execute(
        config: NasConfig,
        block: (SFTPClient) -> T,
    ): NetworkResult<T> =
        withContext(Dispatchers.IO) {
            validateConfig(config)?.let { return@withContext it }

            var lastError: NetworkResult.Error? = null
            repeat(MAX_OPERATION_ATTEMPTS) { attemptIndex ->
                val attemptNumber = attemptIndex + 1
                val attemptContext = buildAttemptContext(config, attemptNumber)
                logDiagnostic(
                    attemptContext,
                    stage = "ATTEMPT_START",
                    details = "route=$lastTransportAttemptSummary",
                )

                // Back-off before retrying to give ZeroTier time to re-route.
                if (attemptIndex > 0) {
                    delay(computeRetryDelayMs(attemptNumber, lastError))
                }

                val result = sessionMutex.withLock {
                    // Keep first-hop connect fast: when SYSTEM_ROUTE_FIRST is selected, avoid
                    // expensive runtime rehydration before we even try the system socket path.
                    // Rehydration is only needed when embedded route is primary.
                    val zeroTierStatus =
                        when (config.zeroTierConnectionMode) {
                            ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST -> null
                            else -> zeroTierIntegrationManager.ensureRuntimeReadyForMonitoring()
                        }
                    logDiagnostic(
                        attemptContext,
                        stage = "ZEROTIER_STATUS",
                        details = "mode=${config.zeroTierConnectionMode.name} " +
                            "configured=${zeroTierStatus?.configured ?: "skipped"} " +
                            "sdkAvailable=${zeroTierStatus?.embeddedClientAvailable ?: "skipped"} " +
                            "transportReady=${zeroTierStatus?.transportReady ?: "skipped"} " +
                            "interfaceActive=${zeroTierStatus?.interfaceActive ?: "skipped"} " +
                            "activeDataSessions=${zeroTierIntegrationManager.activeDataSessionCount()} " +
                            "nodeId=${sanitize(zeroTierStatus?.nodeId)} " +
                            "assignedIpv4=${sanitize(zeroTierStatus?.assignedIpv4)} " +
                            "networkStatus=${sanitize(zeroTierStatus?.networkStatus)} " +
                            "recentEvents=${sanitize(zeroTierStatus?.recentEvents)} " +
                            "message=${sanitize(zeroTierStatus?.message)}",
                    )

                    val fallbackPolicy =
                        if (config.zeroTierConnectionMode == ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST) {
                            TcpFallbackPolicy(
                                allowTcpFallback = false,
                                forceTcpFallback = false,
                                avoidZeroTierDataPlane = false,
                                reason = "systemRouteFirstNoProbe",
                                hostLikelyOnCurrentLan = false,
                                directTcpProbeAttempted = false,
                                directTcpProbeSucceeded = null,
                                directTcpProbeErrorClass = null,
                                directTcpProbeErrorMessage = null,
                            )
                        } else {
                            resolveTcpFallbackPolicy(
                                config = config,
                                attemptContext = attemptContext,
                                zeroTierStatus =
                                    zeroTierStatus
                                        ?: return@withLock NetworkResult.Error(
                                            code = NetworkResult.ErrorCode.CONNECTION,
                                            message = "ZeroTier status unavailable.",
                                        ),
                            )
                        }
                    logDiagnostic(
                        attemptContext,
                        stage = "FALLBACK_POLICY",
                        details =
                            "allow=${fallbackPolicy.allowTcpFallback} " +
                                "force=${fallbackPolicy.forceTcpFallback} " +
                                "hostLikelyOnCurrentLan=${fallbackPolicy.hostLikelyOnCurrentLan} " +
                                "probeAttempted=${fallbackPolicy.directTcpProbeAttempted} " +
                                "probeSucceeded=${fallbackPolicy.directTcpProbeSucceeded?.toString() ?: "n/a"} " +
                                "probeErrorClass=${sanitize(fallbackPolicy.directTcpProbeErrorClass)} " +
                                "reason=${sanitize(fallbackPolicy.reason)}",
                    )
                    if (fallbackPolicy.forceTcpFallback) {
                        logDiagnostic(
                            attemptContext,
                            stage = "FORCED_TCP_FALLBACK",
                            details =
                                "reason=${sanitize(fallbackPolicy.reason)} " +
                                    "probeAttempted=${fallbackPolicy.directTcpProbeAttempted} " +
                                    "probeSucceeded=${fallbackPolicy.directTcpProbeSucceeded?.toString() ?: "n/a"} " +
                                    "probeErrorClass=${sanitize(fallbackPolicy.directTcpProbeErrorClass)}",
                            type = LogType.WARNING,
                        )
                    }

                    if (
                        config.zeroTierConnectionMode == ZeroTierConnectionMode.EMBEDDED_ONLY &&
                        zeroTierStatus?.interfaceActive != true
                    ) {
                        logDiagnostic(
                            attemptContext,
                            stage = "BLOCKED",
                            details = "reason=ZEROTIER_DISCONNECTED mode=EMBEDDED_ONLY",
                            type = LogType.WARNING,
                        )
                        return@withLock NetworkResult.Error(
                            code = NetworkResult.ErrorCode.CONNECTION,
                            message = zeroTierStatus?.message ?: "ZeroTier disconnected.",
                        )
                    }

                    if (config.authType == AuthType.SSH_KEY) {
                        return@withLock NetworkResult.Error(
                            code = NetworkResult.ErrorCode.VALIDATION,
                            message = "SSH key auth is not implemented yet in v1.",
                        )
                    }

                    val session = getOrCreateSessionLocked(
                        config = config,
                        attemptContext = attemptContext,
                        fallbackPolicy = fallbackPolicy,
                    )
                        ?: run {
                            if (zeroTierStatus?.transportReady == true) {
                                logDiagnostic(
                                    attemptContext,
                                    stage = "CONTROL_PLANE_READY_PEER_ROUTE_UNREACHABLE",
                                    details = "route=$lastTransportAttemptSummary reason=${sanitize(lastSessionCreationFailure ?: "unknown")}",
                                    type = LogType.WARNING,
                                )
                            }
                            logDiagnostic(
                                attemptContext,
                                stage = "SESSION_OPEN_FAILED",
                                details = sanitize(lastSessionCreationFailure ?: "no-diagnostics"),
                                type =
                                    if (attemptContext.attemptNumber < MAX_OPERATION_ATTEMPTS) {
                                        LogType.WARNING
                                    } else {
                                        LogType.ERROR
                                    },
                            )
                            return@withLock NetworkResult.Error(
                                code = NetworkResult.ErrorCode.CONNECTION,
                                message = "Failed to open SFTP session. Please verify NAS host, port, credentials, and ZeroTier status.",
                            )
                        }

                    try {
                        val value = block(session.sftp)
                        NetworkResult.Success(value)
                    } catch (t: Throwable) {
                        val mapped = mapError(config, t)
                        val shouldResetSession = shouldResetSessionAfterOperationError(mapped.code)
                        logDiagnostic(
                            attemptContext,
                            stage = "OPERATION_FAILED",
                            details = "errorClass=${t::class.java.simpleName} mapped=${mapped.code} " +
                                "route=$lastTransportAttemptSummary resetSession=$shouldResetSession " +
                                "msg=${sanitize(mapped.message)}",
                            type = LogType.ERROR,
                        )
                        // Keep healthy sessions alive for expected per-file failures (e.g. thumbnail
                        // sidecar not found). Closing the transport on every NOT_FOUND causes heavy
                        // reconnect churn, slower browsing, and extra pressure on unstable routes.
                        if (shouldResetSession) {
                            closeSessionLocked(session)
                        }
                        mapped
                    }
                }

                when (result) {
                    is NetworkResult.Success -> {
                        logDiagnostic(
                            attemptContext,
                            stage = "ATTEMPT_SUCCESS",
                            details = "route=$lastTransportAttemptSummary",
                        )
                        return@withContext result
                    }

                    is NetworkResult.Error -> {
                        lastError = result
                        val shouldRetry = attemptNumber < MAX_OPERATION_ATTEMPTS &&
                            result.code in RETRYABLE_ERROR_CODES
                        if (shouldRetry) {
                            logDiagnostic(
                                attemptContext,
                                stage = "RETRYING",
                                details = "code=${result.code} route=$lastTransportAttemptSummary",
                                type = LogType.WARNING,
                            )
                        } else {
                            logDiagnostic(
                                attemptContext,
                                stage = "ATTEMPT_FAILED_FINAL",
                                details = "code=${result.code} route=$lastTransportAttemptSummary msg=${sanitize(result.message)}",
                                type = LogType.ERROR,
                            )
                            return@withContext result
                        }
                    }
                }
            }

            lastError ?: NetworkResult.Error(
                code = NetworkResult.ErrorCode.UNKNOWN,
                message = "SFTP operation failed unexpectedly.",
            )
        }

    private fun validateConfig(config: NasConfig): NetworkResult.Error? {
        if (config.host.isBlank()) {
            return NetworkResult.Error(
                code = NetworkResult.ErrorCode.VALIDATION,
                message = "NAS host must not be blank.",
            )
        }
        if (config.port !in 1..65535) {
            return NetworkResult.Error(
                code = NetworkResult.ErrorCode.VALIDATION,
                message = "NAS port is invalid.",
            )
        }
        if (config.username.isBlank()) {
            return NetworkResult.Error(
                code = NetworkResult.ErrorCode.VALIDATION,
                message = "NAS username must not be blank.",
            )
        }
        return null
    }

    private fun mapError(config: NasConfig, t: Throwable): NetworkResult.Error {
        Log.w(
            TAG,
            "Mapping SFTP throwable host=${config.host} port=${config.port} " +
                "type=${t::class.java.name} msg=${t.message ?: "<no-message>"}",
        )

        return when (t) {
            is UserAuthException ->
                NetworkResult.Error(
                    code = NetworkResult.ErrorCode.AUTH,
                    message = t.message ?: "SFTP authentication failed.",
                    cause = t,
                )

            is SocketTimeoutException ->
                NetworkResult.Error(
                    code = NetworkResult.ErrorCode.TIMEOUT,
                    message = t.message ?: "SFTP connection timed out.",
                    cause = t,
                )

            is UnknownHostException ->
                NetworkResult.Error(
                    code = NetworkResult.ErrorCode.NOT_FOUND,
                    message = t.message ?: "NAS host not found.",
                    cause = t,
                )

            is ConnectException,
            is SocketException,
            is TransportException,
                ->
                NetworkResult.Error(
                    code = NetworkResult.ErrorCode.CONNECTION,
                    message = t.message ?: "Failed to connect to NAS via SFTP.",
                    cause = t,
                )

            is IOException ->
                if ((t.message ?: "").contains("remote host (-1)", ignoreCase = true)) {
                    // ZeroTier SDK returns -1 when the peer route is temporarily unavailable
                    // (e.g., during a NODE_OFFLINE → PEER_DIRECT transition). The retry loop
                    // will back off and reattempt after ZeroTier re-establishes the path.
                    NetworkResult.Error(
                        code = NetworkResult.ErrorCode.CONNECTION,
                        message = "ZeroTier route temporarily unavailable — retrying. " +
                            "If this persists, check the ZeroTier status in Settings.",
                        cause = t,
                    )
                } else if (
                    (t.message ?: "").contains("No such file", ignoreCase = true) ||
                    (t.message ?: "").contains("not found", ignoreCase = true)
                ) {
                    // Missing files (especially NAS-generated thumbnail sidecar paths)
                    // should fail fast and not enter retry loops.
                    NetworkResult.Error(
                        code = NetworkResult.ErrorCode.NOT_FOUND,
                        message = t.message ?: "Remote file not found.",
                        cause = t,
                    )
                } else {
                    NetworkResult.Error(
                        code = NetworkResult.ErrorCode.IO,
                        message = t.message ?: "I/O error during SFTP operation.",
                        cause = t,
                    )
                }

            else ->
                NetworkResult.Error(
                    code = NetworkResult.ErrorCode.UNKNOWN,
                    message = t.message ?: "Unknown SFTP error.",
                    cause = t,
                )
        }
    }

    private fun shouldResetSessionAfterOperationError(code: NetworkResult.ErrorCode): Boolean =
        when (code) {
            NetworkResult.ErrorCode.NOT_FOUND,
            NetworkResult.ErrorCode.VALIDATION,
            NetworkResult.ErrorCode.CONNECTION,
            NetworkResult.ErrorCode.TIMEOUT,
            NetworkResult.ErrorCode.IO,
                -> false

            else -> true
        }

    private fun joinPath(root: String, child: String): String {
        val cleanedRoot = if (root.endsWith('/')) root.dropLast(1) else root
        return if (cleanedRoot.isBlank()) "/$child" else "$cleanedRoot/$child"
    }

    private fun ensureParentDirectoryExists(sftp: SFTPClient, remotePath: String) {
        val parent = parentDirectoryOf(remotePath) ?: return
        if (parent == "/" || parent == ".") return

        runCatching { sftp.mkdirs(parent) }
            .onFailure { error ->
                Log.w(TAG, "Failed creating remote parent directory '$parent' for '$remotePath'", error)
            }
    }

    private fun parentDirectoryOf(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        val lastSlash = trimmed.lastIndexOf('/')
        return when {
            lastSlash < 0 -> "."
            lastSlash == 0 -> "/"
            else -> trimmed.substring(0, lastSlash)
        }
    }

    private suspend fun logDiagnostic(
        context: AttemptContext,
        stage: String,
        details: String,
        type: LogType = LogType.CONNECTIVITY,
    ) {
        val message = buildString {
            append("ROUTE_DIAG ")
            append("ATTEMPT_ID=").append(context.attemptId).append(' ')
            append("STAGE=").append(stage).append(' ')
            append("HOST=").append(context.host).append(' ')
            append("PORT=").append(context.port).append(' ')
            append("HOST_TYPE=").append(context.hostType.name).append(' ')
            append("NETWORK_TYPE=").append(context.networkType.name).append(' ')
            append("ELAPSED_MS=").append(System.currentTimeMillis() - context.startedAtMs).append(' ')
            append(details)
        }
        runCatching {
            appLogRepository.addLog(AppLog(type = type, message = message))
        }
        Log.i(TAG, message)
    }

    private fun sanitize(value: String?): String = value?.replace('\n', ' ') ?: "n/a"

    private fun buildAttemptContext(config: NasConfig, attemptNumber: Int): AttemptContext =
        AttemptContext(
            attemptId = "${UUID.randomUUID().toString().take(8)}-$attemptNumber",
            attemptNumber = attemptNumber,
            host = config.host.trim(),
            port = config.port,
            hostType = classifyHostType(config.host),
            networkType = currentNetworkType(),
            startedAtMs = System.currentTimeMillis(),
        )

    private fun currentNetworkType(): NetworkTypeLabel {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkTypeLabel.UNKNOWN
        val active = manager.activeNetwork ?: return NetworkTypeLabel.UNKNOWN
        val capabilities = manager.getNetworkCapabilities(active) ?: return NetworkTypeLabel.UNKNOWN
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTypeLabel.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTypeLabel.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTypeLabel.ETHERNET
            else -> NetworkTypeLabel.OTHER
        }
    }

    private fun createAndroidSafeSshConfig(): DefaultConfig =
        DefaultConfig().apply {
            keyExchangeFactories = keyExchangeFactories.filterNot { factory ->
                factory.name.contains("curve25519", ignoreCase = true) ||
                    factory.name.contains("x25519", ignoreCase = true)
            }
        }

    private fun configureSshjSecurityProviderForAndroid() {
        runCatching {
            SecurityUtils.setRegisterBouncyCastle(false)
            SecurityUtils.setSecurityProvider(null)
            Log.i(TAG, "SSHJ security provider set to platform default (provider=${SecurityUtils.getSecurityProvider()})")
        }.onFailure { error ->
            Log.w(TAG, "Failed to override SSHJ security provider. Continuing with library defaults.", error)
        }
    }

    private fun resolveTcpFallbackPolicy(
        config: NasConfig,
        attemptContext: AttemptContext,
        zeroTierStatus: com.example.storagenas.network.zerotier.ZeroTierStatus,
    ): TcpFallbackPolicy {
        val hostLikelyOnCurrentLan = isHostLikelyOnCurrentLan(config.host)
        val isLanEligible =
            attemptContext.hostType == HostTypeLabel.PRIVATE && attemptContext.networkType == NetworkTypeLabel.WIFI
        val userAllowsFallback = config.useTcpFallbackOnLan && isLanEligible
        val quarantineActive = isZeroTierDataPlaneQuarantineActive()
        val recentEvents = zeroTierStatus.recentEvents.orEmpty()
        val hasRecentDisruptiveEvent = DISRUPTIVE_ZEROTIER_EVENTS.any { token ->
            recentEvents.contains(token, ignoreCase = true)
        }
        val avoidZeroTierDataPlane = zeroTierIntegrationManager.shouldAvoidZeroTierDataPlane()
        val autoSafetyFallback =
            isLanEligible && (quarantineActive || hasRecentDisruptiveEvent || avoidZeroTierDataPlane)

        val hasRecentZtTimeout =
            lastSessionCreationFailure?.contains("Timeout expired", ignoreCase = true) == true &&
                lastTransportAttemptSummary.contains("ROUTE=ZEROTIER", ignoreCase = true)
        val hasRecentZtRouteUnavailable =
            lastSessionCreationFailure?.contains("remote host (-1)", ignoreCase = true) == true &&
                lastTransportAttemptSummary.contains("ROUTE=ZEROTIER", ignoreCase = true)

        val shouldProbeDirectTcp =
            isLanEligible &&
                !hostLikelyOnCurrentLan &&
                (userAllowsFallback || autoSafetyFallback)

        val directTcpProbeResult =
            if (shouldProbeDirectTcp) {
                getCachedOrRunDirectTcpProbe(
                    host = config.host,
                    port = config.port,
                )
            } else {
                null
            }

        val decision = decideTcpFallback(
            TcpFallbackDecisionInput(
                isLanEligible = isLanEligible,
                hostLikelyOnCurrentLan = hostLikelyOnCurrentLan,
                directTcpProbeSucceeded = directTcpProbeResult?.isReachable == true,
                userAllowsFallback = userAllowsFallback,
                quarantineActive = quarantineActive,
                hasRecentDisruptiveEvent = hasRecentDisruptiveEvent,
                hasRecentZeroTierTimeout = hasRecentZtTimeout,
                hasRecentZeroTierRouteUnavailable = hasRecentZtRouteUnavailable,
                avoidZeroTierDataPlane = avoidZeroTierDataPlane,
            ),
        )

        val reason = when {
            decision.reason == "localQuarantine" ->
                "localQuarantine=${sanitize(zeroTierDataPlaneQuarantineReason)}"
            decision.reason == "recentDisruptiveEvents" ->
                "recentDisruptiveEvents=${sanitize(recentEvents)}"
            decision.reason == "directPathUnverified" ->
                "directPathUnverified probeErrorClass=${sanitize(directTcpProbeResult?.errorClass)} " +
                    "probeError=${sanitize(directTcpProbeResult?.errorMessage)}"
            else -> decision.reason
        }

        return TcpFallbackPolicy(
            allowTcpFallback = decision.allowTcpFallback,
            forceTcpFallback = decision.forceTcpFallback,
            avoidZeroTierDataPlane = avoidZeroTierDataPlane,
            reason = reason,
            hostLikelyOnCurrentLan = hostLikelyOnCurrentLan,
            directTcpProbeAttempted = shouldProbeDirectTcp,
            directTcpProbeSucceeded = directTcpProbeResult?.isReachable,
            directTcpProbeErrorClass = directTcpProbeResult?.errorClass,
            directTcpProbeErrorMessage = directTcpProbeResult?.errorMessage,
        )
    }

    private fun isZeroTierDataPlaneQuarantineActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (zeroTierDataPlaneQuarantineUntilMs <= nowMs) {
            zeroTierDataPlaneQuarantineUntilMs = 0L
            zeroTierDataPlaneQuarantineReason = null
            return false
        }
        return true
    }

    private fun shouldQuarantineZeroTierDataPlaneFailure(
        trace: ConnectionRouteTrace,
        error: Throwable,
    ): Boolean {
        if (!trace.zeroTierAttempted) return false
        val combined = buildString {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 5) {
                if (depth > 0) append(" | ")
                append(current::class.java.simpleName)
                append(':')
                append(current.message ?: "n/a")
                current = current.cause
                depth++
            }
        }
        return combined.contains("remote host (-1)", ignoreCase = true) ||
            combined.contains("Timeout expired", ignoreCase = true)
    }

    private fun activateZeroTierDataPlaneQuarantine(reason: String) {
        val until = System.currentTimeMillis() + ZEROTIER_DATA_PLANE_QUARANTINE_MS
        zeroTierDataPlaneQuarantineUntilMs = until
        zeroTierDataPlaneQuarantineReason = reason
        Log.w(
            TAG,
            "ZeroTier data-plane quarantine active for ${ZEROTIER_DATA_PLANE_QUARANTINE_MS}ms " +
                "until=$until reason=$reason",
        )
    }

    private fun getCachedOrRunDirectTcpProbe(
        host: String,
        port: Int,
    ): com.example.storagenas.network.reachability.TcpReachabilityProbeResult {
        val normalizedHost = host.trim()
        val nowMs = System.currentTimeMillis()
        val cached = cachedDirectTcpProbe
            ?.takeIf {
                it.host.equals(normalizedHost, ignoreCase = true) &&
                    it.port == port &&
                    (nowMs - it.probedAtMs) <= DIRECT_TCP_PROBE_CACHE_MS
            }
        if (cached != null) {
            return com.example.storagenas.network.reachability.TcpReachabilityProbeResult(
                isReachable = cached.isReachable,
                errorClass = cached.errorClass,
                errorMessage = cached.errorMessage,
                cause = null,
            )
        }

        val fresh = probeTcpReachability(
            host = normalizedHost,
            port = port,
            timeoutMs = DIRECT_TCP_PROBE_TIMEOUT_MS,
        )
        cachedDirectTcpProbe =
            CachedDirectTcpProbe(
                host = normalizedHost,
                port = port,
                isReachable = fresh.isReachable,
                errorClass = fresh.errorClass,
                errorMessage = fresh.errorMessage,
                probedAtMs = nowMs,
            )
        return fresh
    }

    private fun isRetryableSessionOpenFailure(error: Throwable): Boolean {
        if (error is SocketTimeoutException ||
            error is ConnectException ||
            error is SocketException ||
            error is TransportException
        ) {
            return true
        }
        if (error is IOException) {
            val message = error.message.orEmpty()
            return message.contains("remote host (-1)", ignoreCase = true) ||
                message.contains("Timeout expired", ignoreCase = true)
        }
        return false
    }

    private fun computeRetryDelayMs(
        attemptNumber: Int,
        lastError: NetworkResult.Error?,
    ): Long {
        val isTransientRouteUnavailable =
            lastError?.code == NetworkResult.ErrorCode.CONNECTION &&
                (lastError.message?.contains(
                    "ZeroTier route temporarily unavailable",
                    ignoreCase = true,
                ) == true)

        return if (isTransientRouteUnavailable) {
            TRANSIENT_ROUTE_RETRY_DELAY_MS
        } else {
            RETRY_DELAY_BASE_MS * attemptNumber
        }
    }

    private fun isHostLikelyOnCurrentLan(host: String): Boolean {
        val hostOctets = parseIpv4Literal(host.trim()) ?: return false
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            return false
        }
        val linkProperties = manager.getLinkProperties(active) ?: return false
        return linkProperties.linkAddresses.any { linkAddress ->
            val localRaw = linkAddress.address?.address ?: return@any false
            if (localRaw.size != 4) return@any false
            val prefix = linkAddress.prefixLength.coerceIn(0, 32)
            isSameSubnet(hostOctets, localRaw, prefix)
        }
    }

    private fun parseIpv4Literal(value: String): IntArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (index in parts.indices) {
            val parsed = parts[index].toIntOrNull() ?: return null
            if (parsed !in 0..255) return null
            octets[index] = parsed
        }
        return octets
    }

    private fun isSameSubnet(hostOctets: IntArray, localRaw: ByteArray, prefixLength: Int): Boolean {
        val hostInt =
            (hostOctets[0] shl 24) or
                (hostOctets[1] shl 16) or
                (hostOctets[2] shl 8) or
                hostOctets[3]
        val localInt =
            ((localRaw[0].toInt() and 0xFF) shl 24) or
                ((localRaw[1].toInt() and 0xFF) shl 16) or
                ((localRaw[2].toInt() and 0xFF) shl 8) or
                (localRaw[3].toInt() and 0xFF)
        val mask = when (prefixLength) {
            0 -> 0
            32 -> -1
            else -> -1 shl (32 - prefixLength)
        }
        return (hostInt and mask) == (localInt and mask)
    }

    private fun getOrCreateSessionLocked(
        config: NasConfig,
        attemptContext: AttemptContext,
        fallbackPolicy: TcpFallbackPolicy,
    ): ConnectedSession? {
        val key = SessionKey.fromConfig(config)
        val existing = cachedSession
        if (existing != null && existing.key == key && existing.isUsable()) {
            Log.d(TAG, "Reusing existing SFTP session for ${config.host}:${config.port}")
            return existing
        }

        clearCachedSessionLocked()
        val created = createSession(config, key, attemptContext, fallbackPolicy) ?: return null
        cachedSession = created
        return created
    }

    private fun createSession(
        config: NasConfig,
        key: SessionKey,
        attemptContext: AttemptContext,
        fallbackPolicy: TcpFallbackPolicy,
    ): ConnectedSession? {
        configureSshjSecurityProviderForAndroid()
        val routeOrder = decideSocketRouteOrder(
            SocketRouteOrderInput(
                connectionMode = config.zeroTierConnectionMode,
                forceTcpFallback = fallbackPolicy.forceTcpFallback,
            ),
        )

        for ((routeIndex, preferredRoute) in routeOrder.withIndex()) {
            val ssh = SSHClient(createAndroidSafeSshConfig())
            var zeroTierDataSessionOpened = false
            val trace = ConnectionRouteTrace(
                attemptId = attemptContext.attemptId,
                hostType = attemptContext.hostType,
                networkType = attemptContext.networkType,
            )

            try {
                ssh.addHostKeyVerifier(PromiscuousVerifier())
                val connectTimeoutMs = connectTimeoutMsForRoute(
                    preferredRoute = preferredRoute,
                    routeIndex = routeIndex,
                    routeOrderSize = routeOrder.size,
                    connectionMode = config.zeroTierConnectionMode,
                )
                ssh.connectTimeout = connectTimeoutMs
                ssh.timeout = SOCKET_TIMEOUT_MS

                when (preferredRoute) {
                    PreferredSocketRoute.SYSTEM_SOCKET -> {
                        trace.markRoute("SYSTEM_SOCKET")
                        ssh.socketFactory = SocketFactory.getDefault()
                    }

                    PreferredSocketRoute.EMBEDDED_ZEROTIER -> {
                        ssh.socketFactory = ZeroTierSocketFactory(
                            trace = trace,
                            allowTcpFallback = fallbackPolicy.allowTcpFallback,
                            forceTcpFallback = fallbackPolicy.forceTcpFallback,
                            defaultConnectTimeoutMs = connectTimeoutMs,
                        )
                    }
                }

                val connectStartedAtMs = System.currentTimeMillis()
                ssh.connect(config.host, config.port)
                val connectElapsedMs = System.currentTimeMillis() - connectStartedAtMs

                val usesZeroTier = trace.route == "ZEROTIER"
                if (usesZeroTier) {
                    zeroTierIntegrationManager.onDataSessionOpened()
                    zeroTierDataSessionOpened = true
                    if (isZeroTierDataPlaneQuarantineActive()) {
                        zeroTierDataPlaneQuarantineUntilMs = 0L
                        zeroTierDataPlaneQuarantineReason = null
                        Log.i(TAG, "Cleared ZeroTier data-plane quarantine after successful ZeroTier session open")
                    }
                }

                val route = when (trace.route) {
                    "ZEROTIER" -> "ROUTE=ZEROTIER"
                    "TCP_FALLBACK" -> "ROUTE=TCP_FALLBACK"
                    "TCP_FALLBACK_FORCED" -> "ROUTE=TCP_FALLBACK_FORCED"
                    "SYSTEM_SOCKET" -> "ROUTE=SYSTEM_SOCKET"
                    else -> "ROUTE=UNKNOWN"
                }
                val localIp = trace.localIp
                lastTransportAttemptSummary =
                    "$route zeroTierSocketAttempted=${trace.zeroTierAttempted} " +
                        "tcpFallbackAttempted=${trace.tcpFallbackAttempted} localIp=$localIp " +
                        "routeCandidate=${preferredRoute.name} connectMs=$connectElapsedMs"
                lastSessionCreationFailure = null

                when (config.authType) {
                    AuthType.PASSWORD -> ssh.authPassword(config.username, config.password)
                    AuthType.SSH_KEY -> {
                        if (zeroTierDataSessionOpened) {
                            runCatching { zeroTierIntegrationManager.onDataSessionClosed() }
                            zeroTierDataSessionOpened = false
                        }
                        runCatching { ssh.disconnect() }
                        runCatching { ssh.close() }
                        return null
                    }
                }

                val sftp = ssh.newSFTPClient()
                return ConnectedSession(key = key, ssh = ssh, sftp = sftp, usesZeroTier = usesZeroTier)
            } catch (t: Throwable) {
                val route = when (trace.route) {
                    "ZEROTIER" -> "ROUTE=ZEROTIER_FAILED"
                    "TCP_FALLBACK" -> "ROUTE=TCP_FALLBACK_FAILED"
                    "TCP_FALLBACK_FORCED" -> "ROUTE=TCP_FALLBACK_FORCED_FAILED"
                    "SYSTEM_SOCKET" -> "ROUTE=SYSTEM_SOCKET_FAILED"
                    else -> "ROUTE=CONNECT_FAILED"
                }
                val localIp = trace.localIp
                lastTransportAttemptSummary =
                    "$route zeroTierSocketAttempted=${trace.zeroTierAttempted} " +
                        "tcpFallbackAttempted=${trace.tcpFallbackAttempted} localIp=$localIp " +
                        "routeCandidate=${preferredRoute.name}"
                lastSessionCreationFailure =
                    "errorClass=${t::class.java.simpleName} route=$lastTransportAttemptSummary " +
                        "message=${sanitize(t.message)}"

                if (shouldQuarantineZeroTierDataPlaneFailure(trace, t)) {
                    activateZeroTierDataPlaneQuarantine(
                        reason = "${t::class.java.simpleName}:${sanitize(t.message)}",
                    )
                }

                val canTryNextRoute =
                    routeIndex < routeOrder.lastIndex &&
                        isRetryableSessionOpenFailure(t)
                val message =
                    "Failed opening SFTP session host=${config.host} port=${config.port} " +
                        "attemptId=${attemptContext.attemptId} routeCandidate=${preferredRoute.name} " +
                        "$lastSessionCreationFailure"
                if (canTryNextRoute) {
                    Log.w(TAG, "$message (trying next route)", t)
                } else {
                    Log.e(TAG, message, t)
                }

                if (zeroTierDataSessionOpened) {
                    runCatching { zeroTierIntegrationManager.onDataSessionClosed() }
                    zeroTierDataSessionOpened = false
                }
                runCatching { ssh.disconnect() }
                runCatching { ssh.close() }

                if (!canTryNextRoute) {
                    return null
                }
            }
        }

        return null
    }

    private fun clearCachedSessionLocked() {
        val existing = cachedSession ?: return
        closeSessionLocked(existing)
    }

    private fun closeSessionLocked(session: ConnectedSession) {
        runCatching { session.sftp.close() }
        if (session.usesZeroTier) {
            // Avoid aggressive disconnect() on ZeroTier sockets. Some libzt builds can crash when
            // reader threads are torn down mid-flight during route churn or app lifecycle changes.
            runCatching { session.ssh.close() }
            runCatching { zeroTierIntegrationManager.onDataSessionClosed() }
        } else {
            runCatching { session.ssh.disconnect() }
            runCatching { session.ssh.close() }
        }
        if (cachedSession === session) {
            cachedSession = null
        }
    }

    private fun connectTimeoutMsForRoute(
        preferredRoute: PreferredSocketRoute,
        routeIndex: Int,
        routeOrderSize: Int,
        connectionMode: ZeroTierConnectionMode,
    ): Int {
        val hasFallbackRoute = routeOrderSize > 1
        return when {
            preferredRoute == PreferredSocketRoute.SYSTEM_SOCKET &&
                routeIndex == 0 &&
                connectionMode == ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST -> SYSTEM_ROUTE_FAST_FAIL_TIMEOUT_MS

            preferredRoute == PreferredSocketRoute.EMBEDDED_ZEROTIER &&
                routeIndex > 0 &&
                connectionMode == ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST -> EMBEDDED_FALLBACK_TIMEOUT_MS

            else -> CONNECT_TIMEOUT_MS
        }
    }

    private data class SessionKey(
        val host: String,
        val port: Int,
        val username: String,
        val authType: AuthType,
        val password: String,
    ) {
        companion object {
            fun fromConfig(config: NasConfig): SessionKey =
                SessionKey(
                    host = config.host.trim(),
                    port = config.port,
                    username = config.username.trim(),
                    authType = config.authType,
                    password = config.password,
                )
        }
    }

    private data class ConnectedSession(
        val key: SessionKey,
        val ssh: SSHClient,
        val sftp: SFTPClient,
        val usesZeroTier: Boolean,
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        /**
         * A session is only reused if:
         * 1. The underlying SSH transport still reports connected + authenticated.
         * 2. The session is younger than SESSION_MAX_AGE_MS (ZeroTier re-routes can silently
         *    drop the TCP connection without SSHClient detecting it immediately).
         */
        fun isUsable(): Boolean =
            runCatching { ssh.isConnected && ssh.isAuthenticated }.getOrDefault(false) &&
                (
                    usesZeroTier ||
                        (System.currentTimeMillis() - createdAtMs < SESSION_MAX_AGE_MS)
                )
    }

    private data class AttemptContext(
        val attemptId: String,
        val attemptNumber: Int,
        val host: String,
        val port: Int,
        val hostType: HostTypeLabel,
        val networkType: NetworkTypeLabel,
        val startedAtMs: Long,
    )

    private data class CachedDirectTcpProbe(
        val host: String,
        val port: Int,
        val isReachable: Boolean,
        val errorClass: String?,
        val errorMessage: String?,
        val probedAtMs: Long,
    )

    private data class TcpFallbackPolicy(
        val allowTcpFallback: Boolean,
        val forceTcpFallback: Boolean,
        val avoidZeroTierDataPlane: Boolean,
        val reason: String?,
        val hostLikelyOnCurrentLan: Boolean,
        val directTcpProbeAttempted: Boolean,
        val directTcpProbeSucceeded: Boolean?,
        val directTcpProbeErrorClass: String?,
        val directTcpProbeErrorMessage: String?,
    )

    private companion object {
        const val TAG = "SshjSftpClient"
        const val CONNECT_TIMEOUT_MS: Int = 10_000
        const val SYSTEM_ROUTE_FAST_FAIL_TIMEOUT_MS: Int = 1_800
        const val EMBEDDED_FALLBACK_TIMEOUT_MS: Int = 2_500
        const val SOCKET_TIMEOUT_MS: Int = 15_000
        const val MAX_OPERATION_ATTEMPTS: Int = 2
        /** Base back-off before a retry attempt. Attempt n waits n × BASE ms. */
        const val RETRY_DELAY_BASE_MS: Long = 500L
        /** Sessions older than this are closed and re-opened to survive ZeroTier re-routes. */
        const val SESSION_MAX_AGE_MS: Long = 300_000L
        const val ZEROTIER_DATA_PLANE_QUARANTINE_MS: Long = 120_000L
        const val DIRECT_TCP_PROBE_TIMEOUT_MS: Int = 1_500
        const val DIRECT_TCP_PROBE_CACHE_MS: Long = 30_000L
        const val TRANSIENT_ROUTE_RETRY_DELAY_MS: Long = 200L
        val RETRYABLE_ERROR_CODES = setOf(
            NetworkResult.ErrorCode.CONNECTION,
            NetworkResult.ErrorCode.TIMEOUT,
            NetworkResult.ErrorCode.IO,
        )
        val DISRUPTIVE_ZEROTIER_EVENTS = setOf(
            "NODE_OFFLINE",
            "NETWORK_UPDATE",
            "NETWORK_DOWN",
            "PEER_UNREACHABLE",
            "PEER_PATH_DEAD",
            "NETIF_DOWN",
            "STACK_DOWN",
        )
    }
}

// ---------------------------------------------------------------------------
// ZeroTierSocketFactory — creates ZeroTierSocketAdapter instances.
// localHost / localPort parameters are intentionally ignored because libzt
// manages socket binding internally.
// ---------------------------------------------------------------------------

private class ZeroTierSocketFactory(
    private val trace: ConnectionRouteTrace,
    private val allowTcpFallback: Boolean,
    private val forceTcpFallback: Boolean,
    private val defaultConnectTimeoutMs: Int,
) : SocketFactory() {
    override fun createSocket(): Socket =
        ZeroTierSocketAdapter(
            trace = trace,
            allowTcpFallback = allowTcpFallback,
            forceTcpFallback = forceTcpFallback,
            defaultConnectTimeoutMs = defaultConnectTimeoutMs,
        )

    override fun createSocket(host: String, port: Int): Socket =
        ZeroTierSocketAdapter(
            trace = trace,
            allowTcpFallback = allowTcpFallback,
            forceTcpFallback = forceTcpFallback,
            defaultConnectTimeoutMs = defaultConnectTimeoutMs,
        ).apply {
            connect(InetSocketAddress(host, port), defaultConnectTimeoutMs)
        }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        // localHost/localPort are ignored — libzt controls binding internally.
        Log.d(TAG_FACTORY, "createSocket: localHost/localPort ignored for ZeroTier socket")
        return createSocket(host, port)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        ZeroTierSocketAdapter(
            trace = trace,
            allowTcpFallback = allowTcpFallback,
            forceTcpFallback = forceTcpFallback,
            defaultConnectTimeoutMs = defaultConnectTimeoutMs,
        ).apply {
            connect(InetSocketAddress(host, port), defaultConnectTimeoutMs)
        }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        // localAddress/localPort are ignored — libzt controls binding internally.
        Log.d(TAG_FACTORY, "createSocket: localAddress/localPort ignored for ZeroTier socket")
        return createSocket(address, port)
    }

    private companion object {
        const val TAG_FACTORY = "ZeroTierSocketFactory"
    }
}

// ---------------------------------------------------------------------------
// ZeroTierSocketAdapter — wraps ZeroTierSocket with optional TCP fallback.
// ---------------------------------------------------------------------------

private class ZeroTierSocketAdapter(
    private val trace: ConnectionRouteTrace,
    private val allowTcpFallback: Boolean,
    private val forceTcpFallback: Boolean,
    private val defaultConnectTimeoutMs: Int,
) : Socket() {
    private var delegate: Any? = null
    private var requestedSoTimeout: Int = 0
    private var requestedTcpNoDelay: Boolean = true
    private var requestedKeepAlive: Boolean = false
    private var requestedReuseAddress: Boolean = false

    override fun connect(endpoint: SocketAddress?) {
        connect(endpoint, 0)
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (delegate != null) {
            throw SocketException("Socket is already connected.")
        }
        val inet = endpoint as? InetSocketAddress
            ?: throw SocketException("Unsupported endpoint type: ${endpoint?.javaClass?.name}")
        val remoteHost = inet.address?.hostAddress ?: inet.hostString
        val effectiveTimeoutMs =
            when {
                timeout > 0 -> timeout
                defaultConnectTimeoutMs > 0 -> defaultConnectTimeoutMs
                else -> DEFAULT_ZEROTIER_CONNECT_TIMEOUT_MS
            }

        if (forceTcpFallback) {
            trace.markTcpFallbackAttempt()
            trace.markRoute("TCP_FALLBACK_FORCED")
            val tcpSocket = Socket()
            tcpSocket.soTimeout = requestedSoTimeout
            tcpSocket.tcpNoDelay = requestedTcpNoDelay
            tcpSocket.keepAlive = requestedKeepAlive
            tcpSocket.reuseAddress = requestedReuseAddress
            tcpSocket.connect(inet, effectiveTimeoutMs)
            delegate = tcpSocket
            trace.markLocalIp(tcpSocket.localAddress?.hostAddress ?: "n/a")
            Log.w(TAG, "Forced TCP fallback for $remoteHost:${inet.port} due to unstable ZeroTier routing")
            return
        }

        trace.markZeroTierAttempt()
        try {
            trace.markRoute("ZEROTIER")
            val ztSocket = createZeroTierSocketWithTimeout(remoteHost, inet.port, effectiveTimeoutMs)
            delegate = ztSocket
            trace.markLocalIp(ztSocket.localAddress?.hostAddress ?: "n/a")
            ztSocket.setSoTimeout(requestedSoTimeout)
            ztSocket.setTcpNoDelayEnabled(requestedTcpNoDelay)
            ztSocket.setKeepAliveEnabled(requestedKeepAlive)
            ztSocket.setReuseAddress(requestedReuseAddress)
        } catch (zeroTierError: Throwable) {
            Log.w(TAG, "ZeroTier-only socket failed for $remoteHost:${inet.port}", zeroTierError)
            if (allowTcpFallback) {
                Log.i(TAG, "Falling back to plain TCP for $remoteHost:${inet.port}")
                trace.markTcpFallbackAttempt()
                trace.markRoute("TCP_FALLBACK")
                val tcpSocket = Socket()
                tcpSocket.soTimeout = requestedSoTimeout
                tcpSocket.tcpNoDelay = requestedTcpNoDelay
                tcpSocket.keepAlive = requestedKeepAlive
                tcpSocket.reuseAddress = requestedReuseAddress
                tcpSocket.connect(inet, effectiveTimeoutMs)
                delegate = tcpSocket
                trace.markLocalIp(tcpSocket.localAddress?.hostAddress ?: "n/a")
            } else {
                throw zeroTierError
            }
        }
    }

    override fun getInputStream(): InputStream = when (val d = delegate) {
        is ZeroTierSocket -> d.inputStream
        is Socket -> d.getInputStream()
        else -> throw SocketException("Socket is not connected yet.")
    }

    override fun getOutputStream(): OutputStream = when (val d = delegate) {
        is ZeroTierSocket -> d.outputStream
        is Socket -> d.getOutputStream()
        else -> throw SocketException("Socket is not connected yet.")
    }

    override fun close() {
        runCatching {
            when (val d = delegate) {
                is ZeroTierSocket -> d.close()
                is Socket -> d.close()
            }
        }
        delegate = null
    }

    override fun isConnected(): Boolean = when (val d = delegate) {
        is ZeroTierSocket -> true
        is Socket -> d.isConnected
        else -> false
    }

    override fun isClosed(): Boolean = when (val d = delegate) {
        is ZeroTierSocket -> false
        is Socket -> d.isClosed
        else -> true
    }

    override fun getInetAddress(): InetAddress? = when (val d = delegate) {
        is ZeroTierSocket -> d.remoteAddress
        is Socket -> d.inetAddress
        else -> null
    }

    override fun getPort(): Int = when (val d = delegate) {
        is ZeroTierSocket -> d.remotePort
        is Socket -> d.port
        else -> 0
    }

    override fun getLocalAddress(): InetAddress? = when (val d = delegate) {
        is ZeroTierSocket -> d.localAddress
        is Socket -> d.localAddress
        else -> null
    }

    override fun getLocalPort(): Int = when (val d = delegate) {
        is ZeroTierSocket -> d.localPort
        is Socket -> d.localPort
        else -> 0
    }

    override fun setSoTimeout(timeout: Int) {
        requestedSoTimeout = timeout
        when (val d = delegate) {
            is ZeroTierSocket -> d.setSoTimeout(timeout)
            is Socket -> d.soTimeout = timeout
        }
    }

    override fun getSoTimeout(): Int = when (val d = delegate) {
        is ZeroTierSocket -> d.soTimeout
        is Socket -> d.soTimeout
        else -> requestedSoTimeout
    }

    override fun setTcpNoDelay(on: Boolean) {
        requestedTcpNoDelay = on
        when (val d = delegate) {
            is ZeroTierSocket -> d.setTcpNoDelayEnabled(on)
            is Socket -> d.tcpNoDelay = on
        }
    }

    override fun getTcpNoDelay(): Boolean = when (val d = delegate) {
        is ZeroTierSocket -> d.tcpNoDelayEnabled()
        is Socket -> d.tcpNoDelay
        else -> requestedTcpNoDelay
    }

    override fun setKeepAlive(on: Boolean) {
        requestedKeepAlive = on
        when (val d = delegate) {
            is ZeroTierSocket -> d.setKeepAliveEnabled(on)
            is Socket -> d.keepAlive = on
        }
    }

    override fun getKeepAlive(): Boolean = when (val d = delegate) {
        is ZeroTierSocket -> d.keepAlive
        is Socket -> d.keepAlive
        else -> requestedKeepAlive
    }

    override fun setReuseAddress(on: Boolean) {
        requestedReuseAddress = on
        when (val d = delegate) {
            is ZeroTierSocket -> d.setReuseAddress(on)
            is Socket -> d.reuseAddress = on
        }
    }

    override fun getReuseAddress(): Boolean = when (val d = delegate) {
        is ZeroTierSocket -> d.reuseAddress
        is Socket -> d.reuseAddress
        else -> requestedReuseAddress
    }

    override fun shutdownInput() {
        when (val d = delegate) {
            // Defensive: some libzt builds can crash when shutdownInput/shutdownOutput are invoked
            // while the internal reader is still active. Let close()/disconnect own teardown.
            is ZeroTierSocket -> Unit
            is Socket -> d.shutdownInput()
        }
    }

    override fun shutdownOutput() {
        when (val d = delegate) {
            // Defensive: avoid explicit half-close calls for ZeroTier sockets.
            is ZeroTierSocket -> Unit
            is Socket -> d.shutdownOutput()
        }
    }

    override fun isInputShutdown(): Boolean = when (val d = delegate) {
        is ZeroTierSocket -> d.inputStreamHasBeenShutdown()
        is Socket -> d.isInputShutdown
        else -> false
    }

    override fun isOutputShutdown(): Boolean = when (val d = delegate) {
        is ZeroTierSocket -> d.outputStreamHasBeenShutdown()
        is Socket -> d.isOutputShutdown
        else -> false
    }

    private fun createZeroTierSocketWithTimeout(
        host: String,
        port: Int,
        timeoutMs: Int,
    ): ZeroTierSocket {
        if (timeoutMs <= 0) return ZeroTierSocket(host, port)

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ZeroTierSocketOpen").apply { isDaemon = true }
        }

        return try {
            val future = executor.submit<ZeroTierSocket> { ZeroTierSocket(host, port) }
            future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            throw SocketTimeoutException(
                "ZeroTier socket open timed out for $host:$port after ${timeoutMs}ms",
            ).also { it.initCause(timeout) }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SocketException("Interrupted while opening ZeroTier socket for $host:$port")
                .also { it.initCause(interrupted) }
        } catch (execution: ExecutionException) {
            throw (execution.cause ?: execution)
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val TAG = "ZeroTierSocketAdapter"
        const val DEFAULT_ZEROTIER_CONNECT_TIMEOUT_MS: Int = 3_000
    }
}

// ---------------------------------------------------------------------------
// ConnectionRouteTrace — instance-scoped (not ThreadLocal) for thread-safety.
// One instance per createSession() call, passed explicitly through the chain.
// ---------------------------------------------------------------------------

private class ConnectionRouteTrace(
    val attemptId: String,
    val hostType: HostTypeLabel,
    val networkType: NetworkTypeLabel,
) {
    var zeroTierAttempted: Boolean = false
        private set
    var tcpFallbackAttempted: Boolean = false
        private set
    var route: String = "UNKNOWN"
        private set
    var localIp: String = "n/a"
        private set

    fun markZeroTierAttempt() { zeroTierAttempted = true }
    fun markTcpFallbackAttempt() { tcpFallbackAttempted = true }
    fun markRoute(routeValue: String) { route = routeValue }
    fun markLocalIp(ip: String) { localIp = ip }
}
