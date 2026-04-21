package com.example.storagenas.domain.model

/**
 * Controls preferred connection route ordering for SFTP sessions.
 */
enum class ZeroTierConnectionMode {
    /** Prefer regular OS sockets first (fast path when external ZeroTier app already routes traffic). */
    SYSTEM_ROUTE_FIRST,

    /** Prefer embedded libzt first, then fallback to regular OS sockets. */
    AUTO_FALLBACK,

    /** Use embedded libzt sockets only. */
    EMBEDDED_ONLY,
}
