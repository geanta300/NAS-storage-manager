package com.example.storagenas

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.storagenas.network.zerotier.ZeroTierKeepAliveCoordinator
import com.example.storagenas.share.ShareIntentStore
import com.example.storagenas.ui.StorageNasApp
import com.example.storagenas.ui.navigation.AppDestination
import com.example.storagenas.workers.ConnectivityCheckScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var connectivityCheckScheduler: ConnectivityCheckScheduler
    @Inject
    lateinit var zeroTierKeepAliveCoordinator: ZeroTierKeepAliveCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.TRANSPARENT
        val isNightMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isNightMode

        ShareIntentStore.ingest(intent)
        connectivityCheckScheduler.ensureScheduled()
        lifecycleScope.launch {
            zeroTierKeepAliveCoordinator.syncWithSettings(trigger = "main_activity_on_create")
        }

        setContent {
            StorageNasApp(
                startDestination = if (intent.isShareIntent()) {
                    AppDestination.ShareImport.route
                } else {
                    AppDestination.Home.route
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            zeroTierKeepAliveCoordinator.syncWithSettings(trigger = "main_activity_on_start")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ShareIntentStore.ingest(intent)
    }

    private fun Intent?.isShareIntent(): Boolean {
        val action = this?.action
        return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
    }
}
