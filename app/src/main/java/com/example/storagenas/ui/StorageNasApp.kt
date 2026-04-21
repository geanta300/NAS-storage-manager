package com.example.storagenas.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.storagenas.ui.navigation.AppDestination
import com.example.storagenas.ui.navigation.AppNavHost
import com.example.storagenas.ui.theme.StorageNasTheme

@Composable
fun StorageNasApp(
    startDestination: String = AppDestination.Home.route,
) {
    StorageNasTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val navController = rememberNavController()
            AppNavHost(
                navController = navController,
                startDestination = startDestination,
            )
        }
    }
}
