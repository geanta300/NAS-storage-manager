package com.example.storagenas.network.zerotier

import android.content.Context
import android.util.Log
import com.example.storagenas.domain.repository.SettingsRepository
import com.zerotier.sockets.ZeroTierEventListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class EmbeddedZeroTierIntegrationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ZeroTierIntegrationManager {
    @Volatile
    private var zeroTierNode: Any? = null
    @Volatile
    private var zeroTierEventHandler: ZeroTierEventListener? = null
    @Volatile
    private var eventHandlerInstallSkippedForNode: Boolean = false
    @Volatile
    private var lastConnectError: String? = null
    @Volatile
    private var lastConnectAttemptAtMs: Long = 0L
    @Volatile
    private var accessDenied: Boolean = false
    @Volatile
    private var lastKnownTransportReady: Boolean = false
    @Volatile
    private var lastKnownRawInterfaceActive: Boolean = false
    @Volatile
    private var lastKnownNodeId: String? = null
    @Volatile
    private var lastKnownAssignedIpv4: String? = null
    @Volatile
    private var lastKnownNetworkStatus: String? = null
    @Volatile
    private var lastJoinNetworkIdHex: String? = null
    @Volatile
    private var lastJoinAttemptAtMs: Long = 0L
    @Volatile
    private var lastDataSessionOpenedAtMs: Long = 0L
    @Volatile
    private var lastDataSessionClosedAtMs: Long = 0L

    private val nativeAccessMutex = Mutex()
    private val activeDataSessions = AtomicInteger(0)

    private val eventLock = Any()
    private val eventHistory = ArrayDeque<ZeroTierEventRecord>()

    override suspend fun getStatus(): ZeroTierStatus = buildStatus(tryAutoConnect = false)

    override suspend fun ensureConnected(): ZeroTierStatus = buildStatus(tryAutoConnect = true)

    override suspend fun ensureRuntimeReadyForMonitoring(): ZeroTierStatus =
        buildStatus(
            tryAutoConnect = false,
            ensureRuntimeForMonitoring = true,
        )

    override fun onDataSessionOpened() {
        lastDataSessionOpenedAtMs = System.currentTimeMillis()
        val active = activeDataSessions.incrementAndGet()
        Log.i(TAG, "Data session opened (active=$active)")
    }

    override fun onDataSessionClosed() {
        while (true) {
            val current = activeDataSessions.get()
            if (current <= 0) {
                if (current < 0) activeDataSessions.set(0)
                Log.w(TAG, "Data session close requested while active count is $current")
                return
            }
            if (activeDataSessions.compareAndSet(current, current - 1)) {
                lastDataSessionClosedAtMs = System.currentTimeMillis()
                Log.i(TAG, "Data session closed (active=${current - 1})")
                return
            }
        }
    }

    override fun activeDataSessionCount(): Int = activeDataSessions.get()

    override fun shouldAvoidZeroTierDataPlane(): Boolean {
        val now = System.currentTimeMillis()
        return synchronized(eventLock) {
            eventHistory.any { record ->
                (now - record.atMs) <= DATA_PLANE_QUARANTINE_MS &&
                    record.codeName in DISRUPTIVE_EVENT_CODES
            }
        }
    }

    private suspend fun buildStatus(
        tryAutoConnect: Boolean,
        ensureRuntimeForMonitoring: Boolean = false,
    ): ZeroTierStatus =
        withContext(Dispatchers.IO) {
            nativeAccessMutex.withLock {
                val settings = settingsRepository.observeSettings().first()
                if (settings.zeroTierNetworkId.isBlank()) {
                    Log.w(TAG, "ZeroTier status check: network ID missing")
                    return@withLock ZeroTierStatus(
                        configured = false,
                        embeddedClientAvailable = false,
                        transportReady = false,
                        interfaceActive = false,
                        message = "ZeroTier network ID missing. Configure it in Settings.",
                    )
                }
                if (!isValidNetworkId(settings.zeroTierNetworkId)) {
                    Log.w(TAG, "ZeroTier status check: invalid network ID format")
                    return@withLock ZeroTierStatus(
                        configured = false,
                        embeddedClientAvailable = isEmbeddedLibraryPresent(),
                        transportReady = false,
                        interfaceActive = false,
                        message = "ZeroTier network ID must be a 16-hex value.",
                    )
                }

                val embeddedClientAvailable = isEmbeddedLibraryPresent()
                val activeSessions = activeDataSessionCount()

                val runtimeRehydrateError =
                    if (ensureRuntimeForMonitoring && embeddedClientAvailable && activeSessions == 0) {
                        ensureRuntimeNodeStartedForMonitoring()
                    } else {
                        null
                    }

                if (activeSessions > 0) {
                    val rawInterfaceActive = isZeroTierInterfaceActive()
                    val connected = lastKnownTransportReady || rawInterfaceActive || activeSessions > 0
                    val recentEvents = recentEventsSummary()
                    val info = if (tryAutoConnect) {
                        "Embedded ZeroTier reconnect/status probing deferred while $activeSessions data session(s) are active."
                    } else {
                        null
                    }
                    Log.i(
                        TAG,
                        "Status: configured=true sdkAvailable=$embeddedClientAvailable deferred=true " +
                            "transportReady=$lastKnownTransportReady rawInterfaceActive=$rawInterfaceActive effectiveConnected=$connected " +
                            "activeDataSessions=$activeSessions " +
                            "nodeId=${lastKnownNodeId ?: "n/a"} assignedIpv4=${lastKnownAssignedIpv4 ?: "n/a"} " +
                            "networkStatus=${lastKnownNetworkStatus ?: "n/a"} events=$recentEvents",
                    )
                    return@withLock ZeroTierStatus(
                        configured = true,
                        embeddedClientAvailable = embeddedClientAvailable,
                        transportReady = lastKnownTransportReady,
                        interfaceActive = connected,
                        nodeId = lastKnownNodeId,
                        assignedIpv4 = lastKnownAssignedIpv4,
                        networkStatus = lastKnownNetworkStatus,
                        recentEvents = recentEvents,
                        message = info,
                    )
                }

                val nowMs = System.currentTimeMillis()
                val recentlyClosedDataSession =
                    lastDataSessionClosedAtMs > 0L &&
                        (nowMs - lastDataSessionClosedAtMs) <= DATA_SESSION_CONTROL_PLANE_GRACE_MS
                if (recentlyClosedDataSession) {
                    val rawInterfaceActive = isZeroTierInterfaceActive()
                    val connected = lastKnownTransportReady || rawInterfaceActive
                    val recentEvents = recentEventsSummary(nowMs)
                    val message =
                        "Embedded ZeroTier control-plane checks deferred for " +
                            "${DATA_SESSION_CONTROL_PLANE_GRACE_MS}ms after data-session close."
                    Log.i(
                        TAG,
                        "Status deferred after data-session close: transportReady=$lastKnownTransportReady " +
                            "rawInterfaceActive=$rawInterfaceActive effectiveConnected=$connected " +
                            "activeDataSessions=$activeSessions events=$recentEvents",
                    )
                    return@withLock ZeroTierStatus(
                        configured = true,
                        embeddedClientAvailable = embeddedClientAvailable,
                        transportReady = lastKnownTransportReady,
                        interfaceActive = connected,
                        nodeId = lastKnownNodeId,
                        assignedIpv4 = lastKnownAssignedIpv4,
                        networkStatus = lastKnownNetworkStatus,
                        recentEvents = recentEvents,
                        message = if (tryAutoConnect) message else null,
                    )
                }

                val transportReadyBeforeConnect = isEmbeddedTransportReady(settings.zeroTierNetworkId)
                val interfaceActiveBeforeConnect = isZeroTierInterfaceActive()
                val shouldAttemptConnect = tryAutoConnect &&
                    embeddedClientAvailable &&
                    !transportReadyBeforeConnect &&
                    !interfaceActiveBeforeConnect &&
                    !accessDenied &&
                    activeSessions == 0 &&
                    shouldRunConnectAttempt()

                if (shouldAttemptConnect) {
                    lastConnectAttemptAtMs = System.currentTimeMillis()
                    lastConnectError = connectEmbeddedNode(settings.zeroTierNetworkId)
                }

                val transportReady = isEmbeddedTransportReady(settings.zeroTierNetworkId)
                val rawInterfaceActive = isZeroTierInterfaceActive()
                val connected = transportReady || rawInterfaceActive
                val interfaceNames = zeroTierInterfaceNames()
                val nodeId = currentNodeIdHex()
                val assignedIpv4 = currentAssignedIpv4(settings.zeroTierNetworkId)
                val networkStatus = currentNetworkStatus(settings.zeroTierNetworkId)
                val recentEvents = recentEventsSummary()

                lastKnownTransportReady = transportReady
                lastKnownRawInterfaceActive = rawInterfaceActive
                lastKnownNodeId = nodeId
                lastKnownAssignedIpv4 = assignedIpv4
                lastKnownNetworkStatus = networkStatus

                val info = when {
                    connected -> null
                    runtimeRehydrateError != null -> {
                        "Embedded ZeroTier runtime restore failed: $runtimeRehydrateError"
                    }
                    accessDenied -> "ZeroTier access denied. Authorize this node in ZeroTier Central."
                    tryAutoConnect && !embeddedClientAvailable -> {
                        "Embedded ZeroTier SDK is missing from this build. Add libzt .aar in Gradle."
                    }
                    !embeddedClientAvailable -> "Embedded ZeroTier SDK not available in this build."
                    activeSessions > 0 && tryAutoConnect -> {
                        "Embedded ZeroTier reconnect deferred while $activeSessions data session(s) are active."
                    }
                    lastConnectError != null -> {
                        "Embedded ZeroTier disconnected. Last connect error: $lastConnectError"
                    }
                    !shouldAttemptConnect && tryAutoConnect && embeddedClientAvailable -> {
                        "Embedded ZeroTier reconnect is throttled. Retrying shortly."
                    }
                    else -> "Embedded ZeroTier disconnected."
                }

                Log.i(
                    TAG,
                    "Status: configured=true sdkAvailable=$embeddedClientAvailable " +
                        "transportReady=$transportReady rawInterfaceActive=$rawInterfaceActive effectiveConnected=$connected " +
                        "activeDataSessions=$activeSessions " +
                        "nodeId=${nodeId ?: "n/a"} assignedIpv4=${assignedIpv4 ?: "n/a"} networkStatus=$networkStatus " +
                        "events=$recentEvents interfaces=$interfaceNames lastConnectError=${lastConnectError ?: "none"}",
                )

                ZeroTierStatus(
                    configured = true,
                    embeddedClientAvailable = embeddedClientAvailable,
                    transportReady = transportReady,
                    interfaceActive = connected,
                    nodeId = nodeId,
                    assignedIpv4 = assignedIpv4,
                    networkStatus = networkStatus,
                    recentEvents = recentEvents,
                    message = info,
                )
            }
        }

    private fun ensureRuntimeNodeStartedForMonitoring(): String? =
        runCatching {
            val existing = zeroTierNode
            if (existing != null) {
                installEventHandler(existing)
                return@runCatching null
            }

            val node = createAndStartNode()
            zeroTierNode = node
            Log.i(TAG, "Embedded ZeroTier runtime rehydrated for monitoring (no forced join)")
            null
        }.getOrElse { error ->
            Log.w(TAG, "Failed to rehydrate Embedded ZeroTier runtime for monitoring", error)
            error.message ?: error::class.java.simpleName
        }

    private fun connectEmbeddedNode(networkIdHex: String): String? =
        runCatching {
            val nowMs = System.currentTimeMillis()
            val parsedNetworkId = parseNetworkId(networkIdHex)
            val createdNow = zeroTierNode == null
            val node = zeroTierNode ?: createAndStartNode().also { zeroTierNode = it }

            val nodeOnline = waitUntilTrue(timeoutMs = NODE_ONLINE_WAIT_MS) {
                // Safety: only proceed to join after explicit NODE_ONLINE signal.
                // Calling join too early after node start has caused native crashes on some libzt builds.
                hasRecentEventCode("NODE_ONLINE") || invokeBoolean(node, "isOnline")
            }
            if (!nodeOnline) {
                Log.w(
                    TAG,
                    "Skipping join for $networkIdHex because NODE_ONLINE was not observed yet (createdNow=$createdNow)",
                )
                return@runCatching "ZeroTier node is still starting. Tap Connect to ZeroTier again in a few seconds."
            }

            val alreadyReady = invokeBoolean(node, "isNetworkTransportReady", Long::class.javaPrimitiveType!!, parsedNetworkId)
            if (alreadyReady) {
                Log.i(TAG, "Embedded ZeroTier already connected: networkId=$networkIdHex")
                lastJoinNetworkIdHex = networkIdHex
                lastJoinAttemptAtMs = nowMs
                null
            } else {
                val recentlyAttemptedSameJoin =
                    lastJoinNetworkIdHex.equals(networkIdHex, ignoreCase = true) &&
                        (nowMs - lastJoinAttemptAtMs) < JOIN_RETRY_THROTTLE_MS

                if (recentlyAttemptedSameJoin) {
                    val elapsed = nowMs - lastJoinAttemptAtMs
                    Log.i(
                        TAG,
                        "Skipping join for $networkIdHex (attempted ${elapsed}ms ago). Waiting for transport readiness.",
                    )
                    return@runCatching "Join recently attempted; waiting for ZeroTier transport readiness."
                }

                Log.i(TAG, "Attempting embedded ZeroTier connect: networkId=$networkIdHex")
                lastJoinNetworkIdHex = networkIdHex
                lastJoinAttemptAtMs = nowMs
                val joinResult = invokeInt(node, "join", Long::class.javaPrimitiveType!!, parsedNetworkId)
                if (joinResult < 0) {
                    throw IllegalStateException("Join failed for $networkIdHex (code=$joinResult)")
                }

                val ready = waitUntilTrue(timeoutMs = NETWORK_READY_TIMEOUT_MS) {
                    invokeBoolean(node, "isNetworkTransportReady", Long::class.javaPrimitiveType!!, parsedNetworkId)
                }
                if (!ready) {
                    val online = invokeBoolean(node, "isOnline")
                    throw IllegalStateException(
                        "Network transport not ready within ${NETWORK_READY_TIMEOUT_MS}ms for $networkIdHex " +
                            "(online=$online joinCode=$joinResult)",
                    )
                }
                Log.i(TAG, "Embedded ZeroTier connect successful for networkId=$networkIdHex")
                null
            }
        }.getOrElse { error ->
            Log.e(TAG, "Embedded ZeroTier connect failed", error)
            error.message ?: error::class.java.simpleName
        }

    private fun createAndStartNode(): Any {
        val nodeClass = Class.forName(ZEROTIER_NODE_CLASS)
        val node = nodeClass.getDeclaredConstructor().newInstance()
        eventHandlerInstallSkippedForNode = false
        invokeVoid(node, "initFromStorage", String::class.java, context.filesDir.absolutePath)
        installEventHandler(node)
        invokeVoid(node, "start")
        return node
    }

    private fun installEventHandler(node: Any) {
        if (eventHandlerInstallSkippedForNode) {
            return
        }
        runCatching {
            val callback = zeroTierEventHandler ?: ZeroTierEventListener { id, eventCode ->
                runCatching {
                    onZeroTierEvent(id, eventCode)
                }.onFailure { error ->
                    Log.e(TAG, "Embedded ZeroTier event callback failed", error)
                }
            }.also { zeroTierEventHandler = it }
            val result = node.javaClass
                .getMethod("initSetEventHandler", ZeroTierEventListener::class.java)
                .invoke(node, callback) as? Number
            val code = result?.toInt() ?: Int.MIN_VALUE
            if (code < 0) {
                if (code == -2) {
                    // Some libzt builds report -2 if handler is already set internally.
                    // Treat this as non-fatal and avoid noisy repeated logs.
                    eventHandlerInstallSkippedForNode = true
                    Log.i(TAG, "Embedded ZeroTier event handler already active/unavailable (code=-2); skipping re-install")
                    return@runCatching
                }
                throw IllegalStateException("initSetEventHandler failed with code=$code")
            }
            zeroTierEventHandler = callback
            Log.i(TAG, "Embedded ZeroTier event handler installed")
        }.onFailure { error ->
            Log.w(TAG, "Unable to install Embedded ZeroTier event handler", error)
        }
    }

    private fun onZeroTierEvent(id: Long, code: Int) {
        val name = eventCodeName(code)
        synchronized(eventLock) {
            eventHistory.addLast(
                ZeroTierEventRecord(
                    atMs = System.currentTimeMillis(),
                    id = id,
                    code = code,
                    codeName = name,
                ),
            )
            while (eventHistory.size > MAX_EVENT_HISTORY) {
                eventHistory.removeFirst()
            }
        }
        when (code) {
            213 -> accessDenied = false // NETWORK_OK clears the flag
            214 -> {
                accessDenied = true
                lastConnectError = "ZeroTier access denied. Authorize this node in ZeroTier Central."
            }
            242 -> lastConnectError = "ZeroTier peer unreachable."
            202, 203, 218, 221, 231 -> {
                // Network/node went down; permit a fresh join attempt after throttle interval.
                lastJoinNetworkIdHex = null
                lastJoinAttemptAtMs = 0L
            }
        }
        Log.i(TAG, "Event: id=$id code=$code name=$name")
    }

    private fun recentEventsSummary(nowMs: Long = System.currentTimeMillis()): String {
        val cutoff = nowMs - EVENT_WINDOW_MS
        val events = synchronized(eventLock) {
            eventHistory
                .filter { it.atMs >= cutoff }
                .takeLast(MAX_EVENT_SUMMARY_ITEMS)
        }
        if (events.isEmpty()) return "none"

        return events.joinToString(",") { event ->
            "${event.codeName}@${formatEventTime(event.atMs)}"
        }
    }

    private fun hasRecentEventCode(
        codeName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val cutoff = nowMs - EVENT_WINDOW_MS
        return synchronized(eventLock) {
            eventHistory.any { it.atMs >= cutoff && it.codeName == codeName }
        }
    }

    private fun formatEventTime(timestampMs: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))

    private fun eventCodeName(code: Int): String =
        when (code) {
            200 -> "NODE_UP"
            201 -> "NODE_ONLINE"
            202 -> "NODE_OFFLINE"
            203 -> "NODE_DOWN"
            204 -> "NODE_FATAL_ERROR"
            210 -> "NETWORK_NOT_FOUND"
            211 -> "NETWORK_CLIENT_TOO_OLD"
            212 -> "NETWORK_REQ_CONFIG"
            213 -> "NETWORK_OK"
            214 -> "NETWORK_ACCESS_DENIED"
            215 -> "NETWORK_READY_IP4"
            216 -> "NETWORK_READY_IP6"
            218 -> "NETWORK_DOWN"
            219 -> "NETWORK_UPDATE"
            220 -> "STACK_UP"
            221 -> "STACK_DOWN"
            230 -> "NETIF_UP"
            231 -> "NETIF_DOWN"
            233 -> "NETIF_LINK_UP"
            234 -> "NETIF_LINK_DOWN"
            240 -> "PEER_DIRECT"
            241 -> "PEER_RELAY"
            242 -> "PEER_UNREACHABLE"
            243 -> "PEER_PATH_DISCOVERED"
            244 -> "PEER_PATH_DEAD"
            250 -> "ROUTE_ADDED"
            251 -> "ROUTE_REMOVED"
            260 -> "ADDR_ADDED_IP4"
            261 -> "ADDR_REMOVED_IP4"
            262 -> "ADDR_ADDED_IP6"
            263 -> "ADDR_REMOVED_IP6"
            else -> "EVENT_$code"
        }

    private fun currentNodeIdHex(): String? {
        val node = zeroTierNode ?: return null
        return runCatching {
            val value = node.javaClass.getMethod("getId").invoke(node) as? Number ?: return null
            value.toLong().toString(16).padStart(10, '0')
        }.getOrNull()
    }

    private fun currentAssignedIpv4(networkIdHex: String): String? {
        val node = zeroTierNode ?: return null
        val networkId = runCatching { parseNetworkId(networkIdHex) }.getOrNull() ?: return null
        return runCatching {
            val result = node.javaClass
                .getMethod("getIPv4Address", Long::class.javaPrimitiveType!!)
                .invoke(node, networkId)
            (result as? InetAddress)?.hostAddress
        }.getOrNull()
    }

    private fun currentNetworkStatus(networkIdHex: String): String {
        val networkId = runCatching { parseNetworkId(networkIdHex) }.getOrNull() ?: return "n/a"
        return runCatching {
            val nativeClass = Class.forName(ZEROTIER_NATIVE_CLASS)
            val statusMethod = nativeClass.getMethod("zts_net_get_status", Long::class.javaPrimitiveType!!)
            val code = (statusMethod.invoke(null, networkId) as? Number)?.toInt() ?: Int.MIN_VALUE
            "code=$code"
        }.getOrDefault("n/a")
    }

    private fun isEmbeddedLibraryPresent(): Boolean =
        runCatching {
            Class.forName(ZEROTIER_NODE_CLASS)
            true
        }.getOrDefault(false)

    private fun parseNetworkId(value: String): Long =
        java.lang.Long.parseUnsignedLong(value.removePrefix("0x"), 16)

    private fun isValidNetworkId(value: String): Boolean =
        value.removePrefix("0x").matches(Regex("^[0-9a-fA-F]{16}$"))

    private fun waitUntilTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_DELAY_MS)
        }
        return false
    }

    private fun invokeBoolean(target: Any, method: String): Boolean =
        runCatching {
            target.javaClass.getMethod(method).invoke(target) as? Boolean ?: false
        }.getOrDefault(false)

    private fun invokeBoolean(target: Any, method: String, argType: Class<*>, argValue: Any): Boolean =
        runCatching {
            target.javaClass.getMethod(method, argType).invoke(target, argValue) as? Boolean ?: false
        }.getOrDefault(false)

    private fun invokeVoid(target: Any, method: String) {
        target.javaClass.getMethod(method).invoke(target)
    }

    private fun invokeVoid(target: Any, method: String, argType: Class<*>, argValue: Any) {
        target.javaClass.getMethod(method, argType).invoke(target, argValue)
    }

    private fun invokeInt(target: Any, method: String, argType: Class<*>, argValue: Any): Int =
        runCatching {
            (target.javaClass.getMethod(method, argType).invoke(target, argValue) as? Number)?.toInt() ?: Int.MIN_VALUE
        }.getOrDefault(Int.MIN_VALUE)

    private fun isZeroTierInterfaceActive(): Boolean =
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .any { networkInterface ->
                    networkInterface.isUp &&
                        !networkInterface.isLoopback &&
                        networkInterface.name.contains("zt", ignoreCase = true)
                }
        }.getOrDefault(false)

    private fun zeroTierInterfaceNames(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.name.contains("zt", ignoreCase = true) }
                .map { "${it.name}(up=${it.isUp})" }
        }.getOrDefault(emptyList())

    private fun isEmbeddedTransportReady(networkIdHex: String): Boolean =
        runCatching {
            val node = zeroTierNode ?: return false
            val parsedNetworkId = parseNetworkId(networkIdHex)
            invokeBoolean(node, "isNetworkTransportReady", Long::class.javaPrimitiveType!!, parsedNetworkId)
        }.getOrDefault(false)

    private fun shouldRunConnectAttempt(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - lastConnectAttemptAtMs >= RECONNECT_THROTTLE_MS

    private data class ZeroTierEventRecord(
        val atMs: Long,
        val id: Long,
        val code: Int,
        val codeName: String,
    )

    private companion object {
        const val TAG = "EmbeddedZeroTier"
        const val ZEROTIER_NODE_CLASS = "com.zerotier.sockets.ZeroTierNode"
        const val ZEROTIER_NATIVE_CLASS = "com.zerotier.sockets.ZeroTierNative"
        const val NETWORK_READY_TIMEOUT_MS = 12_000L
        const val NODE_ONLINE_WAIT_MS = 3_000L
        const val POLL_DELAY_MS = 150L
        const val RECONNECT_THROTTLE_MS = 10_000L
        const val JOIN_RETRY_THROTTLE_MS = 20_000L
        const val DATA_SESSION_CONTROL_PLANE_GRACE_MS = 20_000L
        const val EVENT_WINDOW_MS = 180_000L
        const val DATA_PLANE_QUARANTINE_MS = 90_000L
        const val MAX_EVENT_HISTORY = 64
        const val MAX_EVENT_SUMMARY_ITEMS = 8
        val DISRUPTIVE_EVENT_CODES = setOf(
            "NODE_OFFLINE",
            "NETWORK_UPDATE",
            "NETWORK_DOWN",
            "NETIF_DOWN",
            "PEER_UNREACHABLE",
            "PEER_PATH_DEAD",
            "STACK_DOWN",
        )
    }
}
