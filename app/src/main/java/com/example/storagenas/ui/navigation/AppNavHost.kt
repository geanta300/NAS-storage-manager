package com.example.storagenas.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.storagenas.ui.screens.browser.NasBrowserScreen
import com.example.storagenas.ui.screens.home.HomeScreen
import com.example.storagenas.ui.screens.logs.LogsScreen
import com.example.storagenas.ui.screens.queue.QueueScreen
import com.example.storagenas.ui.screens.share.ShareImportScreen
import com.example.storagenas.ui.screens.settings.SettingsScreen
import com.example.storagenas.ui.screens.sync.SyncScreen
import com.example.storagenas.ui.screens.upload.UploadScreen
import com.example.storagenas.ui.theme.StorageNasTheme

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String = AppDestination.Home.route,
    modifier: Modifier = Modifier,
) {
    val navItems = remember {
        // Defensive: if any null slips in through platform interop/stale state, skip it.
        AppDestination.bottomNavItems.filterNotNull()
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route ?: AppDestination.Home.route

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.height(64.dp),
            ) {
                navItems.forEach { destination ->
                    val destinationRoute = destination.route
                    NavigationBarItem(
                        selected = currentDestination
                            ?.hierarchy
                            ?.any { it.route == destinationRoute } == true,
                        onClick = {
                            if (currentRoute != destinationRoute) {
                                navController.navigateSingleTopTo(destinationRoute)
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.title) },
                        label = { Text(destination.title) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(AppDestination.Home.route) {
                HomeScreen(
                    onNavigateToUpload = {
                        navController.navigateSingleTopTo(AppDestination.Upload.route)
                    },
                    onNavigateToSync = {
                        navController.navigateSingleTopTo(AppDestination.Sync.route)
                    },
                    onNavigateToNasBrowser = {
                        navController.navigateSingleTopTo(AppDestination.NasBrowser.route)
                    },
                    onNavigateToSettings = {
                        navController.navigateSingleTopTo(AppDestination.Settings.route)
                    },
                )
            }
            composable(AppDestination.Upload.route) {
                UploadScreen(
                    onNavigateToNasBrowser = {
                        navController.navigateSingleTopTo(AppDestination.NasBrowser.route)
                    },
                )
            }
            composable(AppDestination.NasBrowser.route) { NasBrowserScreen() }
            composable(AppDestination.Sync.route) {
                SyncScreen(
                    onNavigateToNasBrowser = {
                        navController.navigateSingleTopTo(AppDestination.NasBrowser.route)
                    },
                )
            }
            composable(AppDestination.Queue.route) { QueueScreen() }
            composable(AppDestination.Settings.route) {
                SettingsScreen(
                    onNavigateToNasBrowser = {
                        navController.navigateSingleTopTo(AppDestination.NasBrowser.route)
                    },
                    onNavigateToLogs = {
                        navController.navigateSingleTopTo(AppDestination.Logs.route)
                    },
                )
            }
            composable(AppDestination.Logs.route) { LogsScreen() }
            composable(AppDestination.ShareImport.route) {
                ShareImportScreen(
                    onFinished = {
                        navController.navigateSingleTopTo(AppDestination.Queue.route)
                    },
                )
            }
        }
    }
}

private fun NavHostController.navigateSingleTopTo(route: String) {
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
    }
}

@Composable
@Preview(showBackground = true)
fun AppNavHostPreview() {
    StorageNasTheme {
        AppNavHost(navController = rememberNavController())
    }
}
