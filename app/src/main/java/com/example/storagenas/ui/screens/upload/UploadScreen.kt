package com.example.storagenas.ui.screens.upload

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.storagenas.domain.model.UploadStatus
import com.example.storagenas.domain.model.UploadTask
import com.example.storagenas.ui.components.PremiumCard
import com.example.storagenas.ui.components.SectionHeader
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UploadScreen(
    viewModel: UploadViewModel = hiltViewModel(),
    onNavigateToNasBrowser: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        viewModel.queueUris(uris)
    }

    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        viewModel.queueUris(uris)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        UploadScreenContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            onPickPhotos = { photoLauncher.launch(arrayOf("image/*", "video/*")) },
            onPickDocuments = { documentLauncher.launch(arrayOf("*/*")) },
            onBrowseDestination = onNavigateToNasBrowser
        )
    }
}

@Composable
fun UploadScreenContent(
    uiState: UploadUiState,
    onPickPhotos: () -> Unit,
    onPickDocuments: () -> Unit,
    onBrowseDestination: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Upload",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            SectionHeader(title = "Upload Destination")
            PremiumCard {
                Text(
                    text = uiState.destinationPath,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBrowseDestination,
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pick Folder")
                }
            }
        }

        item {
            SectionHeader(title = "Select Files")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                UploadCard(
                    modifier = Modifier.weight(1f),
                    title = "Media",
                    icon = Icons.Default.Image,
                    onClick = onPickPhotos,
                    isBusy = uiState.isQueueing
                )
                UploadCard(
                    modifier = Modifier.weight(1f),
                    title = "Documents",
                    icon = Icons.Default.Description,
                    onClick = onPickDocuments,
                    isBusy = uiState.isQueueing
                )
            }
        }

        if (uiState.recentTasks.isNotEmpty()) {
            item {
                SectionHeader(title = "Recent Uploads", modifier = Modifier.padding(top = 16.dp))
            }
            items(uiState.recentTasks, key = { it.id }) { task ->
                UploadTaskRow(task = task)
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
fun UploadCard(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    isBusy: Boolean
) {
    PremiumCard(
        modifier = modifier.clickable(enabled = !isBusy) { onClick() },
        containerColor = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth()
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun UploadTaskRow(task: UploadTask) {
    val icon = when (task.status) {
        UploadStatus.SUCCESS -> Icons.Outlined.CheckCircle
        UploadStatus.FAILED -> Icons.Outlined.Error
        else -> Icons.Outlined.HourglassEmpty
    }

    val color = when (task.status) {
        UploadStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
        UploadStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    PremiumCard(
        modifier = Modifier.padding(vertical = 4.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = task.status.name,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${task.status.name} • ${task.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val startedText = task.uploadStartedAt?.let(::formatShortDateTime) ?: "-"
                val finishedText = task.uploadFinishedAt?.let(::formatShortDateTime) ?: "-"
                Text(
                    text = "Started: $startedText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Finished: $finishedText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.status == UploadStatus.UPLOADING) {
                CircularProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

private val shortDateTimeFormatter = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

private fun formatShortDateTime(value: Long): String =
    synchronized(shortDateTimeFormatter) {
        shortDateTimeFormatter.format(Date(value))
    }
