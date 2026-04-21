package com.example.storagenas.domain.model

data class NasConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String,
    val authType: AuthType = AuthType.PASSWORD,
    val defaultRemotePath: String = ".",
    val lastTestStatus: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Mirror of UserSettings.useTcpFallbackOnLan — injected at call-site so the SFTP client
     *  doesn't need a DataStore dependency. */
    val useTcpFallbackOnLan: Boolean = false,
    /** Mirror of UserSettings.zeroTierConnectionMode. */
    val zeroTierConnectionMode: ZeroTierConnectionMode = ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST,
)
