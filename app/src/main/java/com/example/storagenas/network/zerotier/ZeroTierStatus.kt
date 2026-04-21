package com.example.storagenas.network.zerotier

data class ZeroTierStatus(
    val configured: Boolean,
    val embeddedClientAvailable: Boolean,
    val transportReady: Boolean,
    val interfaceActive: Boolean,
    val nodeId: String? = null,
    val assignedIpv4: String? = null,
    val networkStatus: String? = null,
    val recentEvents: String? = null,
    val message: String? = null,
)
