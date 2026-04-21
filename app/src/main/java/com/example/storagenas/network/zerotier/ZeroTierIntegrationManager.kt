package com.example.storagenas.network.zerotier

interface ZeroTierIntegrationManager {
    suspend fun getStatus(): ZeroTierStatus
    suspend fun ensureConnected(): ZeroTierStatus

    /**
     * Rehydrates the embedded ZeroTier runtime (node/event handler) from persisted storage without
     * forcing join/connect attempts. This keeps status/notification reporting coherent after
     * process recreation while preserving manual "Connect to ZeroTier" as the explicit join flow.
     */
    suspend fun ensureRuntimeReadyForMonitoring(): ZeroTierStatus

    /**
     * Marks that a data-plane session (e.g. SSH/SFTP over ZeroTierSocket) is active.
     * While sessions are active, reconnect/join attempts should be deferred.
     */
    fun onDataSessionOpened()

    /**
     * Marks that a previously opened data-plane session has ended.
     */
    fun onDataSessionClosed()

    /**
     * Number of currently active data-plane sessions.
     */
    fun activeDataSessionCount(): Int

    /**
     * True when recent ZeroTier events indicate the data plane is unstable and socket opens
     * should be deferred (or routed through safe LAN fallback when available).
     */
    fun shouldAvoidZeroTierDataPlane(): Boolean
}
