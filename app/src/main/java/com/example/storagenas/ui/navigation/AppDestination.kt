package com.example.storagenas.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.vector.ImageVector

sealed class AppDestination(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    object Home : AppDestination("home", "Home", Icons.Outlined.Home)
    object Upload : AppDestination("upload", "Upload", Icons.Outlined.CloudUpload)
    object NasBrowser : AppDestination("nas_browser", "NAS Browser", Icons.Outlined.Folder)
    object Sync : AppDestination("sync", "Sync", Icons.Outlined.Sync)
    object Queue : AppDestination("queue", "Queue", Icons.AutoMirrored.Outlined.List)
    object Settings : AppDestination("settings", "Settings", Icons.Outlined.Settings)
    object Logs : AppDestination("logs", "Logs", Icons.AutoMirrored.Outlined.Article)
    object ShareImport : AppDestination("share_import", "Share Import", Icons.AutoMirrored.Outlined.Send)

    companion object {
        val bottomNavItems: List<AppDestination> = listOf(
            Home,
            NasBrowser,
            Upload,
            Sync,
            Queue,
            Settings,
        )
    }
}
