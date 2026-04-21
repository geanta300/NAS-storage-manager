package com.example.storagenas.network.zerotier

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.storagenas.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZeroTierKeepAliveCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun syncWithSettings(trigger: String) {
        val enabled = settingsRepository.observeSettings().first().keepZeroTierAliveInBackground
        applyPreference(enabled = enabled, trigger = trigger)
    }

    fun applyPreference(enabled: Boolean, trigger: String) {
        if (enabled) {
            if (!hasNotificationPermission()) {
                Log.w(
                    TAG,
                    "Not starting ZeroTier keep-alive from $trigger because notification permission is missing",
                )
                return
            }
            if (ZeroTierKeepAliveService.isRunning()) {
                Log.i(TAG, "ZeroTier keep-alive already running; skip restart (trigger=$trigger)")
                return
            }
            Log.i(TAG, "Starting ZeroTier keep-alive service from $trigger")
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    ZeroTierKeepAliveService.createStartIntent(context),
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed starting ZeroTier keep-alive service", error)
            }
        } else {
            if (!ZeroTierKeepAliveService.isRunning()) {
                Log.i(TAG, "ZeroTier keep-alive already stopped; skip stop request (trigger=$trigger)")
                return
            }
            Log.i(TAG, "Stopping ZeroTier keep-alive service from $trigger")
            runCatching {
                context.startService(ZeroTierKeepAliveService.createStopIntent(context))
            }.onFailure { error ->
                Log.w(TAG, "Failed stopping ZeroTier keep-alive service", error)
            }
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "ZeroTierKeepAlive"
    }
}
