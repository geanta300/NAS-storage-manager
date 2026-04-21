package com.example.storagenas.ui.screens.share

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.storagenas.share.ShareImportUiState
import com.example.storagenas.share.ShareImportViewModel
import com.example.storagenas.share.ShareItemUi
import com.example.storagenas.ui.components.PremiumCard
import com.example.storagenas.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareImportScreen(
    onFinished: () -> Unit,
    viewModel: ShareImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Import Files", fontWeight = FontWeight.Bold) }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    viewModel.queueSharedItems(onComplete = onFinished)
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.DriveFileMove, contentDescription = "Enqueue") },
                text = { Text("Enqueue All", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        ShareImportScreenContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            onDestinationChanged = viewModel::onDestinationPathChanged
        )
    }
}

@Composable
fun ShareImportScreenContent(
    uiState: ShareImportUiState,
    onDestinationChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
    ) {
        item {
            SectionHeader(title = "Destination")
            PremiumCard {
                OutlinedTextField(
                    value = uiState.destinationPath,
                    onValueChange = onDestinationChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Remote Path") },
                    leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    singleLine = true
                )
            }
        }

        item {
            SectionHeader(title = "Items to Import (${uiState.items.size})", modifier = Modifier.padding(top = 16.dp))
        }

        items(uiState.items, key = { it.uri.toString() }) { item ->
            PremiumCard(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Ready",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.displayName ?: "Unknown File",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
