@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.example.storagenas.ui.screens.browser

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.storagenas.network.model.RemoteEntry
import com.example.storagenas.ui.components.PremiumCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NasBrowserScreen(
    viewModel: NasBrowserViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var moveTargetPath by remember(uiState.currentPath) { mutableStateOf(uiState.currentPath) }
    var copyTargetPath by remember(uiState.currentPath) { mutableStateOf(uiState.currentPath) }
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri: Uri? ->
        treeUri?.let { viewModel.downloadSelectedEntries(it) }
    }

    BackHandler {
        viewModel.onBackPressed()
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { snackbarHostState.showSnackbar(it) }
    }

    // Always refresh when entering the screen (ViewModel may survive on back stack).
    LaunchedEffect(Unit) {
        viewModel.refreshFolders()
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create Folder") },
            text = {
                OutlinedTextField(
                    value = uiState.newFolderName,
                    onValueChange = viewModel::onNewFolderNameChanged,
                    label = { Text("Folder Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createFolder()
                    showCreateFolderDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete photo?") },
            text = { Text("This will permanently delete the photo from your NAS.") },
            confirmButton = {
                TextButton(
                    enabled = !uiState.isDeletingPreviewImage,
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteCurrentPreviewImage()
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    enabled = !uiState.isDeletingPreviewImage,
                    onClick = { showDeleteConfirmDialog = false },
                ) { Text("Cancel") }
            },
        )
    }

    if (showMoveDialog) {
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text("Move selected items") },
            text = {
                OutlinedTextField(
                    value = moveTargetPath,
                    onValueChange = { moveTargetPath = it },
                    label = { Text("Destination folder path") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMoveDialog = false
                        viewModel.moveSelectedEntries(moveTargetPath)
                    },
                ) { Text("Move") }
            },
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showCopyDialog) {
        AlertDialog(
            onDismissRequest = { showCopyDialog = false },
            title = { Text("Copy selected items") },
            text = {
                OutlinedTextField(
                    value = copyTargetPath,
                    onValueChange = { copyTargetPath = it },
                    label = { Text("Destination folder path") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCopyDialog = false
                        viewModel.copySelectedEntries(copyTargetPath)
                    },
                ) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showCopyDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (uiState.previewImageLocalPath != null || uiState.isPreviewLoading || uiState.isDeletingPreviewImage) {
        FullscreenImagePreview(
            imagePath = uiState.previewImageLocalPath,
            imageName = uiState.previewImageName,
            isLoading = uiState.isPreviewLoading,
            isDeleting = uiState.isDeletingPreviewImage,
            onDismiss = viewModel::closeImagePreview,
            onDelete = { showDeleteConfirmDialog = true },
            onShareFailed = viewModel::postMessage,
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Browse NAS", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = viewModel::onBackPressed) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshFolders) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    if (uiState.isSelectionMode) {
                        IconButton(onClick = viewModel::selectAllVisibleEntries) {
                            Icon(Icons.Default.Check, contentDescription = "Select visible")
                        }
                        IconButton(onClick = viewModel::selectAllEntriesInFolder) {
                            Icon(Icons.Default.ExpandMore, contentDescription = "Select all in folder")
                        }
                        IconButton(onClick = viewModel::clearSelectionMode) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    } else {
                        IconButton(onClick = { showCreateFolderDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "New Folder")
                        }
                        if (uiState.selectedPath != null) {
                            IconButton(onClick = viewModel::clearSelectedPath) {
                                Icon(Icons.Default.Close, contentDescription = "Clear selection")
                            }
                        } else {
                            IconButton(onClick = viewModel::selectCurrentPath) {
                                Icon(Icons.Default.Check, contentDescription = "Select current path", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (uiState.isSelectionMode) {
                Surface(tonalElevation = 2.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { showCopyDialog = true },
                                enabled = !uiState.isBulkActionRunning,
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Copy")
                            }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { showMoveDialog = true },
                                enabled = !uiState.isBulkActionRunning,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null)
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Move")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { directoryPickerLauncher.launch(null) },
                                enabled = !uiState.isBulkActionRunning,
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Download")
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = viewModel::deleteSelectedEntries,
                                enabled = !uiState.isBulkActionRunning,
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        NasBrowserScreenContent(
            uiState = uiState,
            onEntryClick = viewModel::onEntryClicked,
            onEntryLongClick = viewModel::onEntryLongPressed,
            onEnsureThumbnail = viewModel::ensureThumbnail,
            onRefresh = viewModel::refreshFolders,
            onLoadMore = viewModel::loadMoreEntries,
            modifier = Modifier.padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NasBrowserScreenContent(
    uiState: NasBrowserUiState,
    onEntryClick: (RemoteEntry) -> Unit,
    onEntryLongClick: (RemoteEntry) -> Unit,
    onEnsureThumbnail: (RemoteEntry) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()

    LaunchedEffect(uiState.hasMoreEntries, uiState.folders.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisibleIndex ->
                if (!uiState.hasMoreEntries) return@collect
                val preloadThreshold = (uiState.folders.size - 8).coerceAtLeast(0)
                if (lastVisibleIndex >= preloadThreshold) {
                    onLoadMore()
                }
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = uiState.currentPath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (uiState.totalEntriesInFolder > 0) {
                        "Showing ${uiState.folders.size} of ${uiState.totalEntriesInFolder} entries"
                    } else {
                        "No entries"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PullToRefreshBox(
            modifier = Modifier.fillMaxSize(),
            state = pullToRefreshState,
            isRefreshing = uiState.isLoading,
            onRefresh = onRefresh,
        ) {
            if (uiState.isLoading && uiState.folders.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.folders.isEmpty()) {
                val listError = uiState.message?.takeIf { it.startsWith("Failed to list folders") }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = listError ?: "Directory is empty",
                        color = if (listError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.folders, key = { it.path }) { entry ->
                        val isSelected =
                            entry.path in uiState.selectedEntryPaths || uiState.selectedPath == entry.path
                        val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                        val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)

                        val isImage = !entry.isDirectory && isImageFile(entry.name)
                        if (isImage) {
                            LaunchedEffect(entry.path) { onEnsureThumbnail(entry) }
                        }

                        PremiumCard(
                            containerColor = containerColor,
                            borderColor = borderColor,
                            modifier = Modifier.combinedClickable(
                                onClick = { onEntryClick(entry) },
                                onLongClick = { onEntryLongClick(entry) },
                            ),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(2.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ThumbnailOrIcon(
                                    entry = entry,
                                    localPath = uiState.thumbnailLocalPaths[entry.path],
                                    isLoading = uiState.loadingThumbnails.contains(entry.path),
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    val isCacheHit = entry.path in uiState.thumbnailCacheHitPaths
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = when {
                                            entry.isDirectory -> "Folder"
                                            isImage && isCacheHit -> "Image • Cached thumbnail"
                                            isImage -> "Image • Network thumbnail"
                                            else -> "File"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.hasMoreEntries) {
                        item(key = "load_more") {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onLoadMore,
                            ) {
                                Text("Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailOrIcon(
    entry: RemoteEntry,
    localPath: String?,
    isLoading: Boolean,
) {
    if (entry.isDirectory) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(42.dp),
        )
        return
    }

    if (isImageFile(entry.name)) {
        LocalFileImage(
            filePath = localPath,
            isLoading = isLoading,
            modifier = Modifier.size(86.dp),
        )
        return
    }

    Icon(
        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(42.dp),
    )
}

@Composable
private fun LocalFileImage(
    filePath: String?,
    isLoading: Boolean,
    modifier: Modifier,
) {
    val bitmap = rememberDecodedImage(
        filePath = filePath,
        maxDimension = 256,
        allowImageDecoderFallback = false,
    )

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            isLoading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FullscreenImagePreview(
    imagePath: String?,
    imageName: String?,
    isLoading: Boolean,
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onShareFailed: (String) -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = imageName ?: "Image",
                    color = Color.White,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = rememberDecodedImage(
                    filePath = imagePath,
                    maxDimension = 2048,
                    allowImageDecoderFallback = true,
                )
                when {
                    bitmap != null -> {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    isLoading || isDeleting -> CircularProgressIndicator(color = Color.White)
                    else -> Text("Failed to load image", color = Color.White)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = imagePath != null && !isLoading && !isDeleting,
                    onClick = {
                        val path = imagePath ?: return@Button
                        val file = File(path)
                        if (!file.exists()) {
                            onShareFailed("Image file is not available for sharing.")
                            return@Button
                        }
                        runCatching {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share image"))
                        }.onFailure {
                            onShareFailed("Failed to share image.")
                        }
                    },
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Share")
                }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading && !isDeleting,
                    onClick = onDelete,
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Delete")
                }
            }
        }
    }
}

private fun isImageFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in setOf(
        "jpg",
        "jpeg",
        "png",
        "webp",
        "bmp",
        "gif",
        "heic",
        "heif",
        "tif",
        "tiff",
        "avif",
    )
}

@Composable
private fun rememberDecodedImage(
    filePath: String?,
    maxDimension: Int,
    allowImageDecoderFallback: Boolean,
): ImageBitmap? {
    val imageBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        filePath,
        maxDimension,
        allowImageDecoderFallback,
    ) {
        value = withContext(Dispatchers.IO) {
            val path = filePath?.takeIf { File(it).exists() } ?: return@withContext null
            decodeSampledBitmap(
                path = path,
                maxDimension = maxDimension,
                allowImageDecoderFallback = allowImageDecoderFallback,
            )?.asImageBitmap()
        }
    }
    return imageBitmap
}

private fun decodeSampledBitmap(
    path: String,
    maxDimension: Int,
    allowImageDecoderFallback: Boolean,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (longestEdge / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
        }
        BitmapFactory.decodeFile(path, decodeOptions)?.let { return it }
    }

    if (!allowImageDecoderFallback) {
        return null
    }

    return runCatching {
        val source = ImageDecoder.createSource(File(path))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val srcWidth = info.size.width
            val srcHeight = info.size.height
            if (srcWidth > 0 && srcHeight > 0) {
                val longest = maxOf(srcWidth, srcHeight).toFloat()
                if (longest > maxDimension) {
                    val scale = maxDimension / longest
                    val targetWidth = (srcWidth * scale).toInt().coerceAtLeast(1)
                    val targetHeight = (srcHeight * scale).toInt().coerceAtLeast(1)
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
            }
        }
    }.getOrNull()
}


