package com.example.storagenas.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.storagenas.ui.components.PremiumCard
import com.example.storagenas.ui.components.SectionHeader
import com.example.storagenas.ui.theme.StorageNasTheme

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToUpload: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToNasBrowser: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreenContent(
        uiState = uiState,
        onRefreshStatus = { viewModel.refreshConnectionStatus() },
        onNavigateToUpload = onNavigateToUpload,
        onNavigateToSync = onNavigateToSync,
        onNavigateToNasBrowser = onNavigateToNasBrowser,
        onNavigateToSettings = onNavigateToSettings,
    )
}

@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    onRefreshStatus: () -> Unit,
    onNavigateToUpload: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToNasBrowser: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                text = "Dashboard",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Monitor your node, launch uploads, and jump into key tools quickly.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }

        item {
            PremiumCard(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Connection overview",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "StorageNAS Node",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        StatusLine(label = "ZeroTier", value = uiState.zeroTierStatusText)
                        Spacer(modifier = Modifier.height(8.dp))
                        StatusLine(label = "NAS", value = uiState.nasStatusText)
                    }
                    if (uiState.isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        IconButton(onClick = onRefreshStatus) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Status",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(title = "Quick Actions")
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Upload",
                    subtitle = "Send new files",
                    icon = Icons.Default.CloudUpload,
                    onClick = onNavigateToUpload
                )
                ActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Sync",
                    subtitle = "Keep content aligned",
                    icon = Icons.Default.Sync,
                    onClick = onNavigateToSync
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Browse NAS",
                    subtitle = "Explore folders",
                    icon = Icons.Default.Folder,
                    onClick = onNavigateToNasBrowser
                )
                ActionCard(
                    modifier = Modifier.weight(1f),
                    title = "Settings",
                    subtitle = "Adjust preferences",
                    icon = Icons.Default.Settings,
                    onClick = onNavigateToSettings
                )
            }
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                fontWeight = FontWeight.Medium
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
fun ActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    PremiumCard(
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    StorageNasTheme {
        HomeScreenContent(
            uiState = HomeUiState(
                zeroTierStatusText = "ZeroTier active (ip pending)",
                nasStatusText = "Reachable",
            ),
            onRefreshStatus = {},
            onNavigateToUpload = {},
            onNavigateToSync = {},
            onNavigateToNasBrowser = {},
            onNavigateToSettings = {}
        )
    }
}
