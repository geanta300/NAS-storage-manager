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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                    IconButton(onClick = viewModel::stopAllActiveUploads) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop all uploads")
                    }
                    IconButton(onClick = viewModel::retryFailedUploads) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry Failed")
                    }
                    if (uiState.totalTaskCount > 0) {
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
            onLoadMore = viewModel::loadMoreTasks,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
fun QueueScreenContent(
    uiState: QueueUiState,
    onSearchQueryChanged: (String) -> Unit,
    onStatusFilterChanged: (QueueStatusFilter) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val shouldLoadMore by remember(uiState.hasMoreFailedToLoad, uiState.statusFilter, listState) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val hasUserScrolled =
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
            uiState.statusFilter == QueueStatusFilter.FAILED &&
                uiState.hasMoreFailedToLoad &&
                total > 0 &&
                hasUserScrolled &&
                lastVisible >= total - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    if (uiState.totalTaskCount == 0) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No transfers in queue", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
        ) {
            item {
                SectionHeader(title = "Upload Request")
            }

            item {
                QueueFilters(
                    searchQuery = uiState.searchQuery,
                    statusFilter = uiState.statusFilter,
                    onSearchQueryChanged = onSearchQueryChanged,
                    onStatusFilterChanged = onStatusFilterChanged,
                )
            }

            item {
                QueueRequestSummaryCard(uiState = uiState)
            }

            if (uiState.statusFilter == QueueStatusFilter.FAILED) {
                if (uiState.failedTasks.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No failed files match current search",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(uiState.failedTasks, key = { it.id }) { task ->
                        UploadTaskRow(task = task)
                    }
                }

                item {
                    Text(
                        text = "Showing ${uiState.displayedFailedCount} of ${uiState.totalFailedCount} failed files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                if (uiState.hasMoreFailedToLoad) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            } else {
                item {
                    Text(
                        text = "Showing request summary only. Use FAILED filter to inspect specific failed files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRequestSummaryCard(uiState: QueueUiState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Folder upload request",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            LinearProgressIndicator(
                progress = { (uiState.completedPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "${uiState.completedCount}/${uiState.totalTaskCount} processed • ${uiState.activeCount} active • ${uiState.failedCount} failed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            label = { Text("Search (applies to FAILED list)") },
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

private fun filterLabel(filter: QueueStatusFilter): String =
    when (filter) {
        QueueStatusFilter.ALL -> "All"
        QueueStatusFilter.SUCCESS -> "Success"
        QueueStatusFilter.FAILED -> "Failed Files"
        QueueStatusFilter.ACTIVE -> "Active"
        QueueStatusFilter.OTHER -> "Other"
    }
