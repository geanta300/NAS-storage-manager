package com.example.storagenas.settings.model

import com.example.storagenas.domain.model.ZeroTierConnectionMode

data class UserSettings(
    val defaultRemotePath: String = "/",
    val skipDuplicates: Boolean = true,
    val preserveAlbumStructure: Boolean = true,
    val thumbnailCacheEnabled: Boolean = true,
    val networkPolicy: NetworkPolicy = NetworkPolicy.WIFI_ONLY,
    val uploadOnlyWhenNasReachable: Boolean = true,
    val autoRetryFailedUploads: Boolean = true,
    val zeroTierGuidanceEnabled: Boolean = true,
    val zeroTierNetworkId: String = "",
    val zeroTierApiToken: String = "",
    val zeroTierConnectionMode: ZeroTierConnectionMode = ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST,
    val keepZeroTierAliveInBackground: Boolean = false,
    /** Fall back to plain TCP when ZeroTier fails and the host is a private IP on Wi-Fi. */
    val useTcpFallbackOnLan: Boolean = false,
)
