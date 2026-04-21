package com.example.storagenas.settings.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.storagenas.domain.model.ZeroTierConnectionMode
import com.example.storagenas.settings.model.NetworkPolicy
import com.example.storagenas.settings.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class UserPreferencesDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<UserSettings> = dataStore.data.map { preferences ->
        UserSettings(
            defaultRemotePath = preferences[Keys.DEFAULT_REMOTE_PATH] ?: "/",
            skipDuplicates = preferences[Keys.SKIP_DUPLICATES] ?: true,
            preserveAlbumStructure = preferences[Keys.PRESERVE_ALBUM_STRUCTURE] ?: true,
            thumbnailCacheEnabled = true,
            networkPolicy =
                preferences[Keys.NETWORK_POLICY]
                    ?.let { value -> runCatching { NetworkPolicy.valueOf(value) }.getOrDefault(NetworkPolicy.WIFI_ONLY) }
                    ?: NetworkPolicy.WIFI_ONLY,
            uploadOnlyWhenNasReachable = preferences[Keys.UPLOAD_ONLY_WHEN_NAS_REACHABLE] ?: true,
            autoRetryFailedUploads = preferences[Keys.AUTO_RETRY_FAILED_UPLOADS] ?: true,
            zeroTierGuidanceEnabled = preferences[Keys.ZEROTIER_GUIDANCE_ENABLED] ?: true,
            zeroTierNetworkId = preferences[Keys.ZEROTIER_NETWORK_ID] ?: "",
            zeroTierApiToken = preferences[Keys.ZEROTIER_API_TOKEN] ?: "",
            zeroTierConnectionMode =
                preferences[Keys.ZEROTIER_CONNECTION_MODE]
                    ?.let { value ->
                        runCatching { ZeroTierConnectionMode.valueOf(value) }
                            .getOrDefault(ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST)
                    }
                    ?: ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST,
            keepZeroTierAliveInBackground = preferences[Keys.KEEP_ZEROTIER_ALIVE_BACKGROUND] ?: false,
            useTcpFallbackOnLan = preferences[Keys.USE_TCP_FALLBACK_ON_LAN] ?: false,
        )
    }

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        dataStore.edit { preferences ->
            val current = UserSettings(
                defaultRemotePath = preferences[Keys.DEFAULT_REMOTE_PATH] ?: "/",
                skipDuplicates = preferences[Keys.SKIP_DUPLICATES] ?: true,
                preserveAlbumStructure = preferences[Keys.PRESERVE_ALBUM_STRUCTURE] ?: true,
                thumbnailCacheEnabled = true,
                networkPolicy =
                    preferences[Keys.NETWORK_POLICY]
                        ?.let { value -> runCatching { NetworkPolicy.valueOf(value) }.getOrDefault(NetworkPolicy.WIFI_ONLY) }
                        ?: NetworkPolicy.WIFI_ONLY,
                uploadOnlyWhenNasReachable = preferences[Keys.UPLOAD_ONLY_WHEN_NAS_REACHABLE] ?: true,
                autoRetryFailedUploads = preferences[Keys.AUTO_RETRY_FAILED_UPLOADS] ?: true,
                zeroTierGuidanceEnabled = preferences[Keys.ZEROTIER_GUIDANCE_ENABLED] ?: true,
                zeroTierNetworkId = preferences[Keys.ZEROTIER_NETWORK_ID] ?: "",
                zeroTierApiToken = preferences[Keys.ZEROTIER_API_TOKEN] ?: "",
                zeroTierConnectionMode =
                    preferences[Keys.ZEROTIER_CONNECTION_MODE]
                        ?.let { value ->
                            runCatching { ZeroTierConnectionMode.valueOf(value) }
                                .getOrDefault(ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST)
                        }
                        ?: ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST,
                keepZeroTierAliveInBackground = preferences[Keys.KEEP_ZEROTIER_ALIVE_BACKGROUND] ?: false,
                useTcpFallbackOnLan = preferences[Keys.USE_TCP_FALLBACK_ON_LAN] ?: false,
            )

            val updated = transform(current)
            preferences[Keys.DEFAULT_REMOTE_PATH] = updated.defaultRemotePath
            preferences[Keys.SKIP_DUPLICATES] = updated.skipDuplicates
            preferences[Keys.PRESERVE_ALBUM_STRUCTURE] = updated.preserveAlbumStructure
            preferences[Keys.THUMBNAIL_CACHE_ENABLED] = true
            preferences[Keys.NETWORK_POLICY] = updated.networkPolicy.name
            preferences[Keys.UPLOAD_ONLY_WHEN_NAS_REACHABLE] = updated.uploadOnlyWhenNasReachable
            preferences[Keys.AUTO_RETRY_FAILED_UPLOADS] = updated.autoRetryFailedUploads
            preferences[Keys.ZEROTIER_GUIDANCE_ENABLED] = updated.zeroTierGuidanceEnabled
            preferences[Keys.ZEROTIER_NETWORK_ID] = updated.zeroTierNetworkId
            preferences[Keys.ZEROTIER_API_TOKEN] = updated.zeroTierApiToken
            preferences[Keys.ZEROTIER_CONNECTION_MODE] = updated.zeroTierConnectionMode.name
            preferences[Keys.KEEP_ZEROTIER_ALIVE_BACKGROUND] = updated.keepZeroTierAliveInBackground
            preferences[Keys.USE_TCP_FALLBACK_ON_LAN] = updated.useTcpFallbackOnLan
        }
    }

    private object Keys {
        val DEFAULT_REMOTE_PATH = stringPreferencesKey("default_remote_path")
        val SKIP_DUPLICATES = booleanPreferencesKey("skip_duplicates")
        val PRESERVE_ALBUM_STRUCTURE = booleanPreferencesKey("preserve_album_structure")
        val THUMBNAIL_CACHE_ENABLED = booleanPreferencesKey("thumbnail_cache_enabled")
        val NETWORK_POLICY = stringPreferencesKey("network_policy")
        val UPLOAD_ONLY_WHEN_NAS_REACHABLE = booleanPreferencesKey("upload_only_when_nas_reachable")
        val AUTO_RETRY_FAILED_UPLOADS = booleanPreferencesKey("auto_retry_failed_uploads")
        val ZEROTIER_GUIDANCE_ENABLED = booleanPreferencesKey("zerotier_guidance_enabled")
        val ZEROTIER_NETWORK_ID = stringPreferencesKey("zerotier_network_id")
        val ZEROTIER_API_TOKEN = stringPreferencesKey("zerotier_api_token")
        val ZEROTIER_CONNECTION_MODE = stringPreferencesKey("zerotier_connection_mode")
        val KEEP_ZEROTIER_ALIVE_BACKGROUND = booleanPreferencesKey("keep_zerotier_alive_background")
        val USE_TCP_FALLBACK_ON_LAN = booleanPreferencesKey("use_tcp_fallback_on_lan")
    }
}
