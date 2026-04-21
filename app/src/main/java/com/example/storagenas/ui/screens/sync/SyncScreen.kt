package com.example.storagenas.ui.screens.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.storagenas.domain.model.SyncMode
import com.example.storagenas.ui.components.PremiumCard
import com.example.storagenas.ui.components.SectionHeader
import com.example.storagenas.ui.components.SettingItemRow

@Composable
fun SyncScreen(
    viewModel: SyncViewModel = hiltViewModel(),
    onNavigateToNasBrowser: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val mediaPermissionGranted = hasMediaAccessPermission(context)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasMediaAccessPermission(context)) {
            viewModel.refreshAlbums()
        }
    }

    LaunchedEffect(uiState.latestSummary) {
        uiState.latestSummary?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.startSync() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Start Sync") },
                text = { Text("Start Sync", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        SyncScreenContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            onModeChanged = viewModel::onModeChanged,
            onSkipDuplicatesChanged = viewModel::onSkipDuplicatesChanged,
            onPreserveAlbumStructureChanged = viewModel::onPreserveAlbumStructureChanged,
            onToggleAlbum = viewModel::toggleAlbumSelection,
            onSelectAllAlbums = viewModel::selectAllAlbums,
            onClearAlbumSelection = viewModel::clearAlbumSelection,
            onRefreshAlbums = viewModel::refreshAlbums,
            onBrowseDestination = onNavigateToNasBrowser,
            hasMediaPermission = mediaPermissionGranted,
            onRequestMediaPermission = {
                permissionLauncher.launch(requiredMediaPermissions())
            },
        )
    }
}

@Composable
fun SyncScreenContent(
    uiState: SyncUiState,
    onModeChanged: (SyncMode) -> Unit,
    onSkipDuplicatesChanged: (Boolean) -> Unit,
    onPreserveAlbumStructureChanged: (Boolean) -> Unit,
    onToggleAlbum: (String) -> Unit,
    onSelectAllAlbums: () -> Unit,
    onClearAlbumSelection: () -> Unit,
    onRefreshAlbums: () -> Unit,
    onBrowseDestination: () -> Unit,
    hasMediaPermission: Boolean,
    onRequestMediaPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 88.dp) // Space for FAB
    ) {
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Backup & Sync",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            SectionHeader(title = "Sync Mode")
            PremiumCard {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SyncMode.values()) { mode ->
                        FilterChip(
                            selected = uiState.mode == mode,
                            onClick = { onModeChanged(mode) },
                            label = { Text(mode.name.replace("_", " ")) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }

        if (uiState.mode == SyncMode.SELECTED_ALBUMS) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Device Albums",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onRefreshAlbums) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                if (hasMediaPermission && uiState.albums.isNotEmpty()) {
                    val allAlbumsSelected = uiState.selectedAlbumIds.size == uiState.albums.size
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (allAlbumsSelected) {
                                    onClearAlbumSelection()
                                } else {
                                    onSelectAllAlbums()
                                }
                            },
                        ) {
                            Text(if (allAlbumsSelected) "Clear All" else "Select All")
                        }
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("${uiState.selectedAlbumIds.size} selected") },
                            leadingIcon = {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (!hasMediaPermission) {
                    PremiumCard {
                        Text(
                            text = "Allow media access to load phone albums.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(onClick = onRequestMediaPermission) {
                            Text("Grant Media Permission")
                        }
                    }
                }
            }

            if (hasMediaPermission) {
                if (uiState.isLoadingAlbums) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (!uiState.isLoadingAlbums && uiState.albums.isEmpty()) {
                    item {
                        PremiumCard {
                            Text("No albums found on this device yet.")
                        }
                    }
                }
                items(uiState.albums) { album ->
                    PremiumCard(
                        modifier = Modifier.clickable { onToggleAlbum(album.id) }.padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${album.name} (${album.itemCount})",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = uiState.selectedAlbumIds.contains(album.id),
                                onCheckedChange = { onToggleAlbum(album.id) }
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(title = "Destination", modifier = Modifier.padding(top = 16.dp))
            PremiumCard {
                Text(uiState.destinationRoot, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onBrowseDestination, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pick Folder")
                }
            }
        }

        item {
            SectionHeader(title = "Options", modifier = Modifier.padding(top = 16.dp))
            PremiumCard {
                SettingItemRow(
                    title = "Skip Duplicates",
                    checked = uiState.skipDuplicates,
                    onCheckedChange = onSkipDuplicatesChanged
                )
                SettingItemRow(
                    title = "Preserve Album Structure",
                    checked = uiState.preserveAlbumStructure,
                    onCheckedChange = onPreserveAlbumStructureChanged
                )
            }
        }
    }
}

private fun requiredMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun hasMediaAccessPermission(context: android.content.Context): Boolean =
    requiredMediaPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
