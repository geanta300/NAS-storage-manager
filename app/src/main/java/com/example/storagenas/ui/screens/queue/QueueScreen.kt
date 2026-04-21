package com.example.storagenas.ui.screens.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.storagenas.domain.model.UploadStatus
import com.example.storagenas.ui.screens.upload.UploadTaskRow
import com.example.storagenas.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    viewModel: QueueViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearQueueConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    if (showClearQueueConfirm) {
        AlertDialog(
            onDismissRequest = { showClearQueueConfirm = false },
            title = { Text("Delete queue list?") },
            text = { Text("This will remove all items from the queue list.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearQueueConfirm = false
                        viewModel.clearQueueList()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearQueueConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Transfer Queue", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = viewModel::retryFailedUploads) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry Failed")
                    }
                    if (uiState.tasks.isNotEmpty()) {
                        IconButton(onClick = { showClearQueueConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete queue list")
                        }
                    }
                    if (uiState.statusFilter != QueueStatusFilter.ALL || uiState.searchQuery.isNotBlank()) {
                        TextButton(onClick = viewModel::clearFilters) {
                            Text("Clear")
                        }
                    }
                }
            )
        }
    ) { padding ->
        QueueScreenContent(
            uiState = uiState,
            onSearchQueryChanged = viewModel::onSearchQueryChanged,
            onStatusFilterChanged = viewModel::onStatusFilterChanged,
            onToggleGroupExpanded = viewModel::toggleGroupExpanded,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
fun QueueScreenContent(
    uiState: QueueUiState,
    onSearchQueryChanged: (String) -> Unit,
    onStatusFilterChanged: (QueueStatusFilter) -> Unit,
    onToggleGroupExpanded: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (uiState.tasks.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No transfers in queue", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (uiState.groups.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QueueFilters(
                searchQuery = uiState.searchQuery,
                statusFilter = uiState.statusFilter,
                onSearchQueryChanged = onSearchQueryChanged,
                onStatusFilterChanged = onStatusFilterChanged,
            )
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No queue items match these filters", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
        ) {
            item {
                SectionHeader(title = "Queue by Folder")
            }

            item {
                QueueFilters(
                    searchQuery = uiState.searchQuery,
                    statusFilter = uiState.statusFilter,
                    onSearchQueryChanged = onSearchQueryChanged,
                    onStatusFilterChanged = onStatusFilterChanged,
                )
            }

            items(uiState.groups, key = { it.key }) { group ->
                val expanded = group.key in uiState.expandedGroupKeys
                QueueFolderGroupCard(
                    group = group,
                    expanded = expanded,
                    onToggleExpanded = { onToggleGroupExpanded(group.key) },
                )
            }
        }
    }
}

@Composable
private fun QueueFilters(
    searchQuery: String,
    statusFilter: QueueStatusFilter,
    onSearchQueryChanged: (String) -> Unit,
    onStatusFilterChanged: (QueueStatusFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            label = { Text("Search file or folder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(QueueStatusFilter.values()) { filter ->
                FilterChip(
                    selected = statusFilter == filter,
                    onClick = { onStatusFilterChanged(filter) },
                    label = { Text(filterLabel(filter)) },
                )
            }
        }
    }
}

@Composable
private fun QueueFolderGroupCard(
    group: QueueTaskGroup,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpanded() },
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = group.folderPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${group.tasks.size} items • ${group.successCount} success • ${group.failedCount} failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }

            if (expanded) {
                group.tasks.forEach { task ->
                    UploadTaskRow(task = task)
                }
            }
        }
    }
}

private fun filterLabel(filter: QueueStatusFilter): String =
    when (filter) {
        QueueStatusFilter.ALL -> "All"
        QueueStatusFilter.SUCCESS -> UploadStatus.SUCCESS.name
        QueueStatusFilter.FAILED -> UploadStatus.FAILED.name
        QueueStatusFilter.ACTIVE -> "Active"
        QueueStatusFilter.OTHER -> "Other"
    }
