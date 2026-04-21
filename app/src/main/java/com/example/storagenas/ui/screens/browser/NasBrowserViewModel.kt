package com.example.storagenas.ui.screens.browser

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.LogType
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.domain.repository.SettingsRepository
import com.example.storagenas.network.common.NetworkResult
import com.example.storagenas.network.model.RemoteEntry
import com.example.storagenas.network.sftp.SftpClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class NasBrowserUiState(
    val currentPath: String = ".",
    val selectedPath: String? = null,
    val folders: List<RemoteEntry> = emptyList(),
    val selectedEntryPaths: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val newFolderName: String = "",
    val isLoading: Boolean = false,
    val isBulkActionRunning: Boolean = false,
    val message: String? = null,
    val thumbnailLocalPaths: Map<String, String> = emptyMap(),
    val thumbnailCacheHitPaths: Set<String> = emptySet(),
    val loadingThumbnails: Set<String> = emptySet(),
    val previewImageLocalPath: String? = null,
    val previewImageRemotePath: String? = null,
    val previewImageName: String? = null,
    val isPreviewLoading: Boolean = false,
    val isDeletingPreviewImage: Boolean = false,
    val hasMoreEntries: Boolean = false,
    val totalEntriesInFolder: Int = 0,
    val thumbnailCacheEnabled: Boolean = true,
)

@HiltViewModel
class NasBrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nasConfigRepository: NasConfigRepository,
    private val settingsRepository: SettingsRepository,
    private val appLogRepository: AppLogRepository,
    private val sftpClient: SftpClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NasBrowserUiState())
    val uiState: StateFlow<NasBrowserUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private val thumbnailJobs = mutableMapOf<String, Job>()
    private val thumbnailJobsLock = Any()
    private val thumbnailSemaphore = Semaphore(THUMBNAIL_MAX_CONCURRENT_DOWNLOADS)
    private var allEntriesInFolder: List<RemoteEntry> = emptyList()
    private var currentDisplayLimit: Int = INITIAL_VISIBLE_ENTRY_COUNT

    init {
        viewModelScope.launch {
            val config = nasConfigRepository.getConfig()
            val initialPath = config?.defaultRemotePath?.ifBlank { "." } ?: "."
            _uiState.update { it.copy(currentPath = initialPath) }
        }
        viewModelScope.launch {
            settingsRepository.observeSettings().collect { settings ->
                _uiState.update { it.copy(thumbnailCacheEnabled = settings.thumbnailCacheEnabled) }
                if (!settings.thumbnailCacheEnabled) {
                    cancelThumbnailRequests(clearCachedPaths = true)
                    clearThumbnailDiskCache()
                }
            }
        }
    }

    fun onNewFolderNameChanged(value: String) {
        _uiState.update { it.copy(newFolderName = value) }
    }

    fun onBackPressed() {
        val state = _uiState.value
        when {
            state.previewImageLocalPath != null || state.isPreviewLoading || state.isDeletingPreviewImage -> {
                closeImagePreview()
            }

            state.isSelectionMode -> {
                clearSelectionMode()
            }

            !isAtRootPath(state.currentPath) -> {
                navigateUp()
            }

            else -> {
                _uiState.update {
                    it.copy(message = "Already at top folder. Open another tab from bottom navigation.")
                }
            }
        }
    }

    fun navigateUp() {
        val current = _uiState.value.currentPath
        val normalized = current.trimEnd('/').ifEmpty { "." }
        if (normalized == "/" || normalized == ".") return

        val lastSlashIdx = normalized.lastIndexOf('/')
        val parent = when {
            lastSlashIdx < 0 -> "."
            lastSlashIdx == 0 -> "/"
            else -> normalized.substring(0, lastSlashIdx)
        }

        cancelThumbnailRequests(clearCachedPaths = true)

        _uiState.update {
            it.copy(
                currentPath = parent,
                selectedEntryPaths = emptySet(),
                isSelectionMode = false,
                thumbnailLocalPaths = emptyMap(),
                thumbnailCacheHitPaths = emptySet(),
                loadingThumbnails = emptySet(),
            ) 
        }
        refreshFolders()
    }

    fun onEntryClicked(entry: RemoteEntry) {
        if (_uiState.value.isSelectionMode) {
            toggleEntrySelection(entry.path)
            return
        }

        if (entry.isDirectory) {
            openFolder(entry.path)
            return
        }

        if (isImageEntry(entry.name)) {
            openImagePreview(entry)
            return
        }

        _uiState.update { it.copy(message = "Preview is available only for image files.") }
    }

    fun onEntryLongPressed(entry: RemoteEntry) {
        _uiState.update { state ->
            val nextSelected = state.selectedEntryPaths.toMutableSet().apply { add(entry.path) }
            state.copy(
                isSelectionMode = true,
                selectedEntryPaths = nextSelected,
                message = null,
            )
        }
    }

    fun clearSelectionMode() {
        _uiState.update {
            it.copy(
                isSelectionMode = false,
                selectedEntryPaths = emptySet(),
            )
        }
    }

    fun selectAllVisibleEntries() {
        val visiblePaths = _uiState.value.folders.map { it.path }.toSet()
        if (visiblePaths.isEmpty()) {
            _uiState.update { it.copy(message = "No entries available to select") }
            return
        }
        _uiState.update {
            it.copy(
                isSelectionMode = true,
                selectedEntryPaths = visiblePaths,
                message = "Selected ${visiblePaths.size} item(s)",
            )
        }
    }

    fun selectAllEntriesInFolder() {
        val allPaths = allEntriesInFolder.map { it.path }.toSet()
        if (allPaths.isEmpty()) {
            _uiState.update { it.copy(message = "No entries available to select") }
            return
        }
        _uiState.update {
            it.copy(
                isSelectionMode = true,
                selectedEntryPaths = allPaths,
                message = "Selected ${allPaths.size} item(s) in folder",
            )
        }
    }

    fun loadMoreEntries() {
        if (allEntriesInFolder.isEmpty()) return
        if (currentDisplayLimit >= allEntriesInFolder.size) return

        currentDisplayLimit = (currentDisplayLimit + LOAD_MORE_STEP).coerceAtMost(allEntriesInFolder.size)
        _uiState.update {
            it.copy(
                folders = allEntriesInFolder.take(currentDisplayLimit),
                hasMoreEntries = currentDisplayLimit < allEntriesInFolder.size,
                totalEntriesInFolder = allEntriesInFolder.size,
            )
        }
    }

    private fun toggleEntrySelection(path: String) {
        _uiState.update { state ->
            val next = state.selectedEntryPaths.toMutableSet()
            if (!next.add(path)) {
                next.remove(path)
            }
            state.copy(
                selectedEntryPaths = next,
                isSelectionMode = next.isNotEmpty(),
            )
        }
    }

    fun postMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun openFolder(path: String) {
        cancelThumbnailRequests(clearCachedPaths = true)
        allEntriesInFolder = emptyList()
        currentDisplayLimit = INITIAL_VISIBLE_ENTRY_COUNT
        _uiState.update {
            it.copy(
                currentPath = path,
                selectedEntryPaths = emptySet(),
                isSelectionMode = false,
                thumbnailLocalPaths = emptyMap(),
                thumbnailCacheHitPaths = emptySet(),
                loadingThumbnails = emptySet(),
            )
        }
        refreshFolders()
    }

    fun refreshFolders() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val config = nasConfigRepository.getConfig()
            if (config == null) {
                _uiState.update { it.copy(message = "Configure NAS settings first") }
                return@launch
            }

            val requestedPath = _uiState.value.currentPath.ifBlank { "." }
            _uiState.update { it.copy(isLoading = true, message = null) }
            var resolvedPath = requestedPath
            var result: NetworkResult<List<RemoteEntry>> = sftpClient.listFolders(
                config = config,
                remotePath = requestedPath,
            )

            // Some servers return an empty listing for relative root (".") depending on
            // chroot/home-folder policy. Probe absolute root once before showing empty state.
            if (result is NetworkResult.Success && requestedPath == "." && result.data.isEmpty()) {
                val rootAttempt = sftpClient.listFolders(config = config, remotePath = "/")
                if (rootAttempt is NetworkResult.Success) {
                    resolvedPath = "/"
                    result = rootAttempt
                }
            }

            // If saved path is stale or inaccessible, try NAS root once so Browse still works.
            if (result is NetworkResult.Error && requestedPath != "/") {
                appLogRepository.addLog(
                    AppLog(
                        type = LogType.WARNING,
                        message = "Browse list failed for '$requestedPath' (${result.message}). Retrying at '/'.",
                    ),
                )
                val rootAttempt = sftpClient.listFolders(config = config, remotePath = "/")
                if (rootAttempt is NetworkResult.Success) {
                    resolvedPath = "/"
                    result = rootAttempt
                }
            }

            when (result) {
                is NetworkResult.Success -> {
                    val sorted = result.data.sortedWith(
                        compareBy<RemoteEntry> { !it.isDirectory }
                            .thenByDescending { it.modifiedAt ?: Long.MIN_VALUE }
                            .thenBy { it.name.lowercase() },
                    )
                    allEntriesInFolder = sorted
                    currentDisplayLimit = INITIAL_VISIBLE_ENTRY_COUNT.coerceAtMost(sorted.size)
                    if (_uiState.value.thumbnailCacheEnabled) {
                        pruneThumbnailCacheIfNeeded()
                    }
                    _uiState.update {
                        it.copy(
                            currentPath = resolvedPath,
                            isLoading = false,
                            folders = sorted.take(currentDisplayLimit),
                            hasMoreEntries = currentDisplayLimit < sorted.size,
                            totalEntriesInFolder = sorted.size,
                            message = null,
                        )
                    }
                }

                is NetworkResult.Error -> {
                    allEntriesInFolder = emptyList()
                    currentDisplayLimit = INITIAL_VISIBLE_ENTRY_COUNT
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            folders = emptyList(),
                            hasMoreEntries = false,
                            totalEntriesInFolder = 0,
                            message = "Failed to list folders at '$requestedPath': ${result.message}",
                        )
                    }
                }
            }
        }
    }

    fun ensureThumbnail(entry: RemoteEntry) {
        if (entry.isDirectory || !isImageEntry(entry.name)) return
        val currentState = _uiState.value
        if (!currentState.thumbnailCacheEnabled) return
        if (currentState.thumbnailLocalPaths[entry.path] != null) return
        if (currentState.loadingThumbnails.contains(entry.path)) return

        val shouldStart = synchronized(thumbnailJobsLock) {
            val existing = thumbnailJobs[entry.path]
            if (existing?.isActive == true) {
                false
            } else {
                true
            }
        }
        if (!shouldStart) return

        _uiState.update { it.copy(loadingThumbnails = it.loadingThumbnails + entry.path) }

        val job = viewModelScope.launch {
            var downloadedLocalPath: String? = null
            var isCacheHit = false

            try {
                thumbnailSemaphore.withPermit {
                    val config = nasConfigRepository.getConfig() ?: return@withPermit
                    val localFile = localPreviewFile(entry.path, entry.name, entry.modifiedAt)

                    if (isLikelyDecodableImage(localFile, allowImageDecoderFallback = false)) {
                        downloadedLocalPath = localFile.absolutePath
                        isCacheHit = true
                        return@withPermit
                    }

                    val parentPath = entry.path.substringBeforeLast('/', missingDelimiterValue = "").ifBlank { "." }
                    // Prefer NAS-generated thumbnail sidecars only to keep network and disk usage low.
                    val thumbPathsToTry = listOf(
                        joinPath(parentPath, ".@__thumb/s800/${entry.name}"),
                        joinPath(parentPath, ".@__thumb/${entry.name}"),
                    )

                    var result: NetworkResult<Unit> =
                        NetworkResult.Error(NetworkResult.ErrorCode.UNKNOWN, "Thumbnails not started")
                    for (targetRemotePath in thumbPathsToTry) {
                        result = sftpClient.downloadFile(
                            config = config,
                            remotePath = targetRemotePath,
                            localFilePath = localFile.absolutePath,
                        )
                        if (result is NetworkResult.Success) {
                            if (isLikelyDecodableImage(localFile, allowImageDecoderFallback = false)) {
                                downloadedLocalPath = localFile.absolutePath
                                isCacheHit = false
                                break
                            }
                            runCatching { localFile.delete() }
                        }
                    }

                    // Fallback: if NAS sidecar thumbnails are unavailable, fetch the original image
                    // only for reasonably sized files so list thumbnails still appear for users.
                    if (
                        downloadedLocalPath == null &&
                        shouldFallbackToOriginalImageForThumbnail(entry)
                    ) {
                        result = sftpClient.downloadFile(
                            config = config,
                            remotePath = entry.path,
                            localFilePath = localFile.absolutePath,
                        )
                        if (
                            result is NetworkResult.Success &&
                            isLikelyDecodableImage(localFile, allowImageDecoderFallback = false)
                        ) {
                            downloadedLocalPath = localFile.absolutePath
                            isCacheHit = false
                        } else {
                            runCatching { localFile.delete() }
                        }
                    }
                }
            } finally {
                _uiState.update { state ->
                    val loadingSet = state.loadingThumbnails - entry.path
                    val canAttachThumbToState = state.folders.any { it.path == entry.path }
                    if (downloadedLocalPath != null && canAttachThumbToState) {
                        val cacheHitSet = if (isCacheHit) {
                            state.thumbnailCacheHitPaths + entry.path
                        } else {
                            state.thumbnailCacheHitPaths - entry.path
                        }
                        state.copy(
                            loadingThumbnails = loadingSet,
                            thumbnailLocalPaths = state.thumbnailLocalPaths + (entry.path to downloadedLocalPath!!),
                            thumbnailCacheHitPaths = cacheHitSet,
                        )
                    } else {
                        state.copy(
                            loadingThumbnails = loadingSet,
                            thumbnailCacheHitPaths = state.thumbnailCacheHitPaths - entry.path,
                        )
                    }
                }
            }
        }

        synchronized(thumbnailJobsLock) {
            thumbnailJobs[entry.path] = job
        }

        job.invokeOnCompletion {
            synchronized(thumbnailJobsLock) {
                if (thumbnailJobs[entry.path] === job) {
                    thumbnailJobs.remove(entry.path)
                }
            }
        }
    }

    fun openImagePreview(entry: RemoteEntry) {
        val cached = _uiState.value.thumbnailLocalPaths[entry.path]
        if (
            cached != null &&
            isLikelyDecodableImage(
                File(cached),
                allowImageDecoderFallback = true,
            )
        ) {
            _uiState.update {
                it.copy(
                    previewImageLocalPath = cached,
                    previewImageRemotePath = entry.path,
                    previewImageName = entry.name,
                    isPreviewLoading = false,
                    message = null,
                )
            }
            return
        }

        viewModelScope.launch {
            val config = nasConfigRepository.getConfig()
            if (config == null) {
                _uiState.update { it.copy(message = "Configure NAS settings first") }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isPreviewLoading = true,
                    isDeletingPreviewImage = false,
                    previewImageLocalPath = null,
                    previewImageRemotePath = entry.path,
                    previewImageName = entry.name,
                    message = null,
                )
            }

            val localFile = localPreviewFile(entry.path, entry.name, entry.modifiedAt)
            val result = sftpClient.downloadFile(
                config = config,
                remotePath = entry.path,
                localFilePath = localFile.absolutePath,
            )

            when (result) {
                is NetworkResult.Success -> {
                    if (isLikelyDecodableImage(localFile, allowImageDecoderFallback = true)) {
                        val cacheEnabled = _uiState.value.thumbnailCacheEnabled
                        _uiState.update {
                            it.copy(
                                isPreviewLoading = false,
                                previewImageLocalPath = localFile.absolutePath,
                                previewImageRemotePath = entry.path,
                                thumbnailLocalPaths = if (cacheEnabled) {
                                    it.thumbnailLocalPaths + (entry.path to localFile.absolutePath)
                                } else {
                                    it.thumbnailLocalPaths
                                },
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isPreviewLoading = false,
                                previewImageLocalPath = null,
                                previewImageRemotePath = null,
                                message = "Downloaded image format is not supported for preview.",
                            )
                        }
                    }
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isPreviewLoading = false,
                            previewImageLocalPath = null,
                            previewImageRemotePath = null,
                            message = "Failed to open image: ${result.message}",
                        )
                    }
                }
            }
        }
    }

    fun deleteCurrentPreviewImage() {
        val remotePath = _uiState.value.previewImageRemotePath ?: return
        viewModelScope.launch {
            val config = nasConfigRepository.getConfig()
            if (config == null) {
                _uiState.update { it.copy(message = "Configure NAS settings first") }
                return@launch
            }

            _uiState.update { it.copy(isDeletingPreviewImage = true, message = null) }
            when (val result = sftpClient.deleteFile(config = config, remotePath = remotePath)) {
                is NetworkResult.Success -> {
                    val localPath = _uiState.value.thumbnailLocalPaths[remotePath]
                    if (localPath != null) {
                        runCatching { File(localPath).delete() }
                    }
                    appLogRepository.addLog(
                        AppLog(type = LogType.INFO, message = "Deleted remote image: $remotePath"),
                    )
                    _uiState.update { state ->
                        state.copy(
                            folders = state.folders.filterNot { it.path == remotePath },
                            thumbnailLocalPaths = state.thumbnailLocalPaths - remotePath,
                            loadingThumbnails = state.loadingThumbnails - remotePath,
                            previewImageLocalPath = null,
                            previewImageRemotePath = null,
                            previewImageName = null,
                            isPreviewLoading = false,
                            isDeletingPreviewImage = false,
                            message = "Deleted image from NAS",
                        )
                    }
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isDeletingPreviewImage = false,
                            message = "Failed to delete image: ${result.message}",
                        )
                    }
                }
            }
        }
    }

    fun closeImagePreview() {
        val previewPath = _uiState.value.previewImageLocalPath
        val shouldDeletePreview = !_uiState.value.thumbnailCacheEnabled
        _uiState.update {
            it.copy(
                previewImageLocalPath = null,
                previewImageRemotePath = null,
                previewImageName = null,
                isPreviewLoading = false,
                isDeletingPreviewImage = false,
            )
        }
        if (shouldDeletePreview && previewPath != null) {
            runCatching { File(previewPath).delete() }
        }
    }

    fun createFolder() {
        val folderName = _uiState.value.newFolderName.trim()
        if (folderName.isBlank()) {
            _uiState.update { it.copy(message = "Folder name cannot be empty") }
            return
        }

        viewModelScope.launch {
            val config = nasConfigRepository.getConfig()
            if (config == null) {
                _uiState.update { it.copy(message = "Configure NAS settings first") }
                return@launch
            }

            val path = joinPath(_uiState.value.currentPath, folderName)
            when (val result = sftpClient.createDirectory(config, path)) {
                is NetworkResult.Success -> {
                    appLogRepository.addLog(AppLog(type = LogType.INFO, message = "Created remote folder: $path"))
                    _uiState.update {
                        it.copy(newFolderName = "", message = "Folder created: $path")
                    }
                    refreshFolders()
                }

                is NetworkResult.Error -> {
                    _uiState.update { it.copy(message = "Create folder failed: ${result.message}") }
                }
            }
        }
    }

    fun selectCurrentPath() {
        val path = _uiState.value.currentPath
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(defaultRemotePath = path) }

            nasConfigRepository.getConfig()?.let { current ->
                nasConfigRepository.saveConfig(
                    current.copy(
                        defaultRemotePath = path,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }

            appLogRepository.addLog(AppLog(type = LogType.INFO, message = "Selected destination: $path"))
            _uiState.update { it.copy(selectedPath = path, message = "Selected destination: $path") }
        }
    }

    fun clearSelectedPath() {
        _uiState.update { it.copy(selectedPath = null, message = null) }
    }

    fun deleteSelectedEntries() {
        val entries = selectedEntriesSnapshot()
        if (entries.isEmpty()) {
            _uiState.update { it.copy(message = "Select at least one file") }
            return
        }

        viewModelScope.launch {
            val config = nasConfigRepository.getConfig()
            if (config == null) {
                _uiState.update { it.copy(message = "Configure NAS settings first") }
                return@launch
            }

            _uiState.update { it.copy(isBulkActionRunning = true, message = null) }
            var deleted = 0
            var failed = 0
            var skippedDirectories = 0

            for (entry in entries) {
                if (entry.isDirectory) {
                    skippedDirectories++
                    continue
                }
                when (sftpClient.deleteFile(config = config, remotePath = entry.path)) {
                    is NetworkResult.Success -> deleted++
                    is NetworkResult.Error -> failed++
                }
            }

            val summary =
                "Deleted $deleted, failed $failed" +
                    if (skippedDirectories > 0) ", skipped $skippedDirectories folder(s)" else ""

            appLogRepository.addLog(AppLog(type = LogType.INFO, message = "Bulk delete: $summary"))
            _uiState.update {
                it.copy(
                    isBulkActionRunning = false,
                    isSelectionMode = false,
                    selectedEntryPaths = emptySet(),
                    message = summary,
                )
            }
            refreshFolders()
        }
    }

    fun moveSelectedEntries(destinationFolderPath: String) {
        val targetFolder = destinationFolderPath.trim().ifBlank { "/" }
        val entries = selectedEntriesSnapshot()
        if (entries.isEmpty()) {
            _uiState.update { it.copy(message = "Select at least one file or folder") }
            return
        }

        viewModelScope.launch {
            val config = nasConfigRepository.getConfig()
            if (config == null) {
                _uiState.update { it.copy(message = "Configure NAS settings first") }
                return@launch
            }

            _uiState.update { it.copy(isBulkActionRunning = true, message = null) }
            var moved = 0
            var failed = 0
            var skipped = 0

            for (entry in entries) {
                val destinationRemotePath = joinPath(targetFolder, entry.name)
                if (destinationRemotePath == entry.path) {
                    skipped++
                    continue
                }
                when (
                    sftpClient.moveFile(
                        config = config,
                        sourceRemotePath = entry.path,
                        destinationRemotePath = destinationRemotePath,
                    )
                ) {
                    is NetworkResult.Success -> moved++
                    is NetworkResult.Error -> failed++
                }
            }

            val summary = "Moved $moved, failed $failed" + if (skipped > 0) ", skipped $skipped" else ""
            appLogRepository.addLog(
                AppLog(type = LogType.INFO, message = "Bulk move to '$targetFolder': $summary"),
            )
            _uiState.update {
                it.copy(
                    isBulkActionRunning = false,
                    isSelectionMode = false,
                    selectedEntryPaths = emptySet(),
                    message = summary,
                )
            }
            refreshFolders()
        }
    }

    fun copySelectedEntries(destinationFolderPath: String) {
        val targetFolder = destinationFolderPath.trim().ifBlank { "/" }
        val entries = selectedEntriesSnapshot()
        if (entries.isEmpty()) {
            _uiState.update { it.copy(message = "Select at least one file or folder") }
            return
        }

        viewModelScope.launch {
            val config = nasConfigRepository.getConfig()
            if (config == null) {
                _uiState.update { it.copy(message = "Configure NAS settings first") }
                return@launch
            }

            _uiState.update { it.copy(isBulkActionRunning = true, message = null) }
            var copied = 0
            var failed = 0
            var skippedDirectories = 0
            var skippedSamePath = 0

            for (entry in entries) {
                if (entry.isDirectory) {
                    skippedDirectories++
                    continue
                }

                val destinationRemotePath = joinPath(targetFolder, entry.name)
                if (destinationRemotePath == entry.path) {
                    skippedSamePath++
                    continue
                }

                val tempFile = File.createTempFile("nas_copy_", "_${entry.name}", context.cacheDir)
                try {
                    val downloadResult = sftpClient.downloadFile(
                        config = config,
                        remotePath = entry.path,
                        localFilePath = tempFile.absolutePath,
                    )
                    if (downloadResult is NetworkResult.Error) {
                        failed++
                        continue
                    }

                    when (
                        sftpClient.uploadFile(
                            config = config,
                            localFilePath = tempFile.absolutePath,
                            remotePath = destinationRemotePath,
                        )
                    ) {
                        is NetworkResult.Success -> copied++
                        is NetworkResult.Error -> failed++
                    }
                } finally {
                    runCatching { tempFile.delete() }
                }
            }

            val summary = buildString {
                append("Copied $copied, failed $failed")
                if (skippedDirectories > 0) append(", skipped $skippedDirectories folder(s)")
                if (skippedSamePath > 0) append(", skipped $skippedSamePath same-path item(s)")
            }

            appLogRepository.addLog(
                AppLog(type = LogType.INFO, message = "Bulk copy to '$targetFolder': $summary"),
            )
            _uiState.update {
                it.copy(
                    isBulkActionRunning = false,
                    isSelectionMode = false,
                    selectedEntryPaths = emptySet(),
                    message = summary,
                )
            }
            refreshFolders()
        }
    }

    fun downloadSelectedEntries(targetTreeUri: Uri) {
        val entries = selectedEntriesSnapshot().filterNot { it.isDirectory }
        if (entries.isEmpty()) {
            _uiState.update { it.copy(message = "Select at least one file to download") }
            return
        }

        viewModelScope.launch {
            val config = nasConfigRepository.getConfig()
            if (config == null) {
                _uiState.update { it.copy(message = "Configure NAS settings first") }
                return@launch
            }
            val targetTree = DocumentFile.fromTreeUri(context, targetTreeUri)
            if (targetTree == null || !targetTree.isDirectory) {
                _uiState.update { it.copy(message = "Selected destination is not a valid folder") }
                return@launch
            }

            _uiState.update { it.copy(isBulkActionRunning = true, message = null) }
            var downloaded = 0
            var failed = 0

            for (entry in entries) {
                val tempFile = File.createTempFile("nas_download_", "_${entry.name}", context.cacheDir)
                try {
                    when (
                        val downloadResult = sftpClient.downloadFile(
                            config = config,
                            remotePath = entry.path,
                            localFilePath = tempFile.absolutePath,
                        )
                    ) {
                        is NetworkResult.Success -> {
                            val mime = guessMimeType(entry.name)
                            val existing = targetTree.findFile(entry.name)
                            if (existing != null) {
                                runCatching { existing.delete() }
                            }
                            val document = targetTree.createFile(mime, entry.name)
                            if (document == null) {
                                failed++
                                continue
                            }

                            val writeSucceeded = withContext(Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.openOutputStream(document.uri, "w")?.use { output ->
                                        tempFile.inputStream().use { input ->
                                            input.copyTo(output)
                                        }
                                    } ?: error("Unable to open destination file stream")
                                }.isSuccess
                            }

                            if (writeSucceeded) downloaded++ else failed++
                        }

                        is NetworkResult.Error -> {
                            failed++
                            appLogRepository.addLog(
                                AppLog(
                                    type = LogType.WARNING,
                                    message = "Download failed for '${entry.path}': ${downloadResult.message}",
                                ),
                            )
                        }
                    }
                } finally {
                    runCatching { tempFile.delete() }
                }
            }

            val summary = "Downloaded $downloaded file(s), failed $failed"
            appLogRepository.addLog(AppLog(type = LogType.INFO, message = "Bulk download: $summary"))
            _uiState.update {
                it.copy(
                    isBulkActionRunning = false,
                    message = summary,
                    isSelectionMode = false,
                    selectedEntryPaths = emptySet(),
                )
            }
        }
    }

    private fun joinPath(base: String, child: String): String {
        val normalizedBase = if (base.endsWith('/')) base.dropLast(1) else base
        return if (normalizedBase.isBlank() || normalizedBase == "/") "/$child" else "$normalizedBase/$child"
    }

    private fun isImageEntry(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_IMAGE_EXTENSIONS
    }

    private fun isLikelyDecodableImage(
        file: File,
        allowImageDecoderFallback: Boolean,
    ): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val decodableViaBitmapFactory = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        }.getOrDefault(false)
        if (decodableViaBitmapFactory) return true

        if (!allowImageDecoderFallback) {
            return false
        }

        return runCatching {
            val source = ImageDecoder.createSource(file)
            var decoded = false
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoded = true
            }
            decoded
        }.getOrDefault(false)
    }

    private fun localPreviewFile(remotePath: String, fileName: String, modifiedAt: Long? = null): File {
        val ext = fileName.substringAfterLast('.', "img").ifBlank { "img" }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val cacheKey = "$remotePath|${modifiedAt ?: 0L}"
        val digest = md.digest(cacheKey.toByteArray(Charsets.UTF_8))
        val hexString = digest.joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "preview_${hexString}.${ext}")
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun selectedEntriesSnapshot(): List<RemoteEntry> {
        val state = _uiState.value
        return allEntriesInFolder.filter { it.path in state.selectedEntryPaths }
    }

    private fun shouldFallbackToOriginalImageForThumbnail(entry: RemoteEntry): Boolean {
        val size = entry.size ?: return true
        return size in 1..THUMBNAIL_FALLBACK_MAX_BYTES
    }

    private fun isAtRootPath(path: String): Boolean {
        val normalized = path.trimEnd('/').ifBlank { "." }
        return normalized == "/" || normalized == "."
    }

    private fun pruneThumbnailCacheIfNeeded() {
        val previewFiles = context.cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("preview_")
        }?.toList().orEmpty()

        if (previewFiles.isEmpty()) return

        val sortedNewestFirst = previewFiles.sortedByDescending { it.lastModified() }
        var runningBytes = 0L
        var keptCount = 0

        for (file in sortedNewestFirst) {
            val shouldKeepByCount = keptCount < THUMBNAIL_CACHE_MAX_FILES
            val nextBytes = runningBytes + file.length()
            val shouldKeepBySize = nextBytes <= THUMBNAIL_CACHE_MAX_BYTES
            if (shouldKeepByCount && shouldKeepBySize) {
                runningBytes = nextBytes
                keptCount++
            } else {
                runCatching { file.delete() }
            }
        }
    }

    private fun cancelThumbnailRequests(clearCachedPaths: Boolean) {
        val jobsToCancel = synchronized(thumbnailJobsLock) {
            val running = thumbnailJobs.values.toList()
            thumbnailJobs.clear()
            running
        }
        jobsToCancel.forEach { it.cancel() }

        _uiState.update {
            it.copy(
                loadingThumbnails = emptySet(),
                thumbnailLocalPaths = if (clearCachedPaths) emptyMap() else it.thumbnailLocalPaths,
                thumbnailCacheHitPaths = if (clearCachedPaths) emptySet() else it.thumbnailCacheHitPaths,
            )
        }
    }

    private fun clearThumbnailDiskCache() {
        val previewFiles = context.cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("preview_")
        } ?: return
        previewFiles.forEach { file -> runCatching { file.delete() } }
    }

    companion object {
        private const val THUMBNAIL_MAX_CONCURRENT_DOWNLOADS = 1
        private const val THUMBNAIL_CACHE_MAX_FILES = 3_000
        private const val THUMBNAIL_CACHE_MAX_BYTES = 150L * 1024L * 1024L
        private const val THUMBNAIL_FALLBACK_MAX_BYTES = 24L * 1024L * 1024L
        private const val INITIAL_VISIBLE_ENTRY_COUNT = 180
        private const val LOAD_MORE_STEP = 120
        private val SUPPORTED_IMAGE_EXTENSIONS = setOf(
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
}

