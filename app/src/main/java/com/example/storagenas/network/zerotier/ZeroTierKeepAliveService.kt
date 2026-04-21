package com.example.storagenas.network.zerotier

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.storagenas.MainActivity
import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.LogType
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class ZeroTierKeepAliveService : Service() {
    @Inject
    lateinit var zeroTierIntegrationManager: ZeroTierIntegrationManager

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var appLogRepository: AppLogRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var lastStatusDigest: String = ""
    private var reconnectAttemptStreak: Int = 0

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (heartbeatJob?.isActive == true) {
            updateNotification("ZeroTier keep-alive active")
            Log.i(TAG, "Keep-alive already active; ignoring redundant start command")
            return START_STICKY
        }

        val startedForeground = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification("ZeroTier keep-alive active"))
            true
        }.getOrElse { error ->
            Log.e(TAG, "Unable to start foreground mode for ZeroTier keep-alive", error)
            serviceScope.launch {
                appLogRepository.addLog(
                    AppLog(
                        type = LogType.ERROR,
                        message = "ZeroTier keep-alive failed to enter foreground mode: ${error.message ?: error::class.java.simpleName}",
                    ),
                )
            }
            false
        }
        if (!startedForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunningFlag.set(true)
        startHeartbeatLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunningFlag.set(false)
        heartbeatJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (heartbeatJob?.isActive == true) {
            Log.i(TAG, "Keep-alive already active; skip restart request after task removal")
            return
        }
        // Some OEM ROMs aggressively stop foreground services when the task is removed.
        // If user explicitly enabled keep-alive, request service start again.
        serviceScope.launch {
            val keepAliveEnabled = runCatching {
                settingsRepository.observeSettings().first().keepZeroTierAliveInBackground
            }.getOrDefault(false)
            if (!keepAliveEnabled) return@launch

            runCatching {
                ContextCompat.startForegroundService(
                    this@ZeroTierKeepAliveService,
                    createStartIntent(this@ZeroTierKeepAliveService),
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to restart ZeroTier keep-alive after task removal", error)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = serviceScope.launch {
            appLogRepository.addLog(
                AppLog(
                    type = LogType.CONNECTIVITY,
                    message = "ZeroTier keep-alive service started",
                ),
            )

            while (isActive) {
                val settings = settingsRepository.observeSettings().first()
                if (!settings.keepZeroTierAliveInBackground) {
                    appLogRepository.addLog(
                        AppLog(
                            type = LogType.CONNECTIVITY,
                            message = "ZeroTier keep-alive service stopping (disabled in settings)",
                        ),
                    )
                    withContext(Dispatchers.Main.immediate) {
                        stopSelf()
                    }
                    break
                }

                val activeDataSessions = zeroTierIntegrationManager.activeDataSessionCount()
                // Safety: avoid control-plane reconnect/join calls from the keep-alive loop.
                // We only rehydrate runtime state so monitoring survives process recreation.
                // Explicit join/connect remains available from Settings (Connect to ZeroTier).
                val status = zeroTierIntegrationManager.ensureRuntimeReadyForMonitoring()
                val digest = buildString {
                    append("interfaceActive=").append(status.interfaceActive).append(' ')
                    append("transportReady=").append(status.transportReady).append(' ')
                    append("activeDataSessions=").append(activeDataSessions).append(' ')
                    append("nodeId=").append(status.nodeId ?: "n/a").append(' ')
                    append("assignedIpv4=").append(status.assignedIpv4 ?: "n/a").append(' ')
                    append("networkStatus=").append(status.networkStatus ?: "n/a").append(' ')
                    append("message=").append(status.message ?: "n/a")
                }

                val statusText = if (status.interfaceActive) {
                    reconnectAttemptStreak = 0
                    "ZeroTier active (${status.assignedIpv4 ?: "ip pending"})"
                } else {
                    reconnectAttemptStreak += 1
                    "ZeroTier inactive (monitoring, active sessions=$activeDataSessions)"
                }
                updateNotification(statusText)

                if (digest != lastStatusDigest) {
                    lastStatusDigest = digest
                    appLogRepository.addLog(
                        AppLog(
                            type = LogType.CONNECTIVITY,
                            message = "ZeroTier keep-alive heartbeat: $digest",
                        ),
                    )
                }
                if (!status.interfaceActive && reconnectAttemptStreak % 3 == 1) {
                    appLogRepository.addLog(
                        AppLog(
                            type = LogType.WARNING,
                            message = "ZeroTier keep-alive reconnect pending: ${status.message ?: "no-details"} (activeDataSessions=$activeDataSessions)",
                        ),
                    )
                }

                delay(
                    if (status.interfaceActive) {
                        HEARTBEAT_INTERVAL_CONNECTED_MS
                    } else {
                        val shift = (reconnectAttemptStreak - 1).coerceIn(0, 8)
                        val backoff = HEARTBEAT_INTERVAL_RECONNECT_MS * (1L shl shift)
                        backoff.coerceAtMost(MAX_HEARTBEAT_INTERVAL_RECONNECT_MS)
                    },
                )
            }
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "ZeroTier Keep-Alive",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps embedded ZeroTier active for faster reconnect."
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(contentText: String) {
        runCatching {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(contentText))
        }.onFailure { error ->
            Log.w(TAG, "Failed to update ZeroTier keep-alive notification", error)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP_SERVICE,
            createStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("StorageNAS ZeroTier")
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopIntent,
            )
            .build()
    }

    companion object {
        private const val TAG = "ZeroTierKeepAlive"
        private const val CHANNEL_ID = "zerotier_keepalive_channel"
        private const val NOTIFICATION_ID = 48012
        private const val REQUEST_OPEN_APP = 1001
        private const val REQUEST_STOP_SERVICE = 1002
        private const val HEARTBEAT_INTERVAL_CONNECTED_MS = 45_000L
        private const val HEARTBEAT_INTERVAL_RECONNECT_MS = 10_000L
        private const val MAX_HEARTBEAT_INTERVAL_RECONNECT_MS = 300_000L
        private const val ACTION_START = "com.example.storagenas.zerotier.KEEP_ALIVE_START"
        private const val ACTION_STOP = "com.example.storagenas.zerotier.KEEP_ALIVE_STOP"
        private val isRunningFlag = AtomicBoolean(false)

        fun isRunning(): Boolean = isRunningFlag.get()

        fun createIntent(context: Context): Intent =
            Intent(context, ZeroTierKeepAliveService::class.java)

        fun createStartIntent(context: Context): Intent =
            createIntent(context).setAction(ACTION_START)

        fun createStopIntent(context: Context): Intent =
            createIntent(context).setAction(ACTION_STOP)
    }
}
