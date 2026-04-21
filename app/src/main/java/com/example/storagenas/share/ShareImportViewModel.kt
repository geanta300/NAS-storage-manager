package com.example.storagenas.share

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.model.LogType
import com.example.storagenas.domain.model.UploadStatus
import com.example.storagenas.domain.model.UploadTask
import com.example.storagenas.domain.repository.AppLogRepository
import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.domain.repository.UploadRepository
import com.example.storagenas.workers.UploadWorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class ShareItemUi(
    val uri: Uri,
    val displayName: String,
)

data class ShareImportUiState(
    val destinationPath: String = "/",
    val items: List<ShareItemUi> = emptyList(),
    val isBusy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ShareImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nasConfigRepository: NasConfigRepository,
    private val uploadRepository: UploadRepository,
    private val uploadWorkScheduler: UploadWorkScheduler,
    private val appLogRepository: AppLogRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShareImportUiState())
    val uiState: StateFlow<ShareImportUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val defaultPath = nasConfigRepository.getConfig()?.defaultRemotePath ?: "/"
            _uiState.update { it.copy(destinationPath = defaultPath) }
        }

        viewModelScope.launch {
            ShareIntentStore.sharedUris.collect { uris ->
                _uiState.update {
                    it.copy(
                        items = uris.map { uri -> ShareItemUi(uri = uri, displayName = resolveDisplayName(uri)) },
                    )
                }
            }
        }
    }

    fun onDestinationPathChanged(value: String) {
        _uiState.update { it.copy(destinationPath = value.ifBlank { "/" }) }
    }

    fun queueSharedItems(onComplete: () -> Unit) {
        val items = _uiState.value.items
        if (items.isEmpty()) {
            _uiState.update { it.copy(message = "No shared items found") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null) }

            val destination = _uiState.value.destinationPath.ifBlank { "/" }
            val tasks = items.map { item ->
                val metadata = resolveMetadata(item.uri)
                UploadTask(
                    localUri = item.uri.toString(),
                    displayName = metadata.displayName,
                    mimeType = metadata.mimeType,
                    size = metadata.size,
                    destinationPath = destination,
                    status = UploadStatus.QUEUED,
                    progress = 0,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            }

            val ids = uploadRepository.addTasks(tasks)
            uploadWorkScheduler.enqueueUploadTasks(ids)
            persistReadPermissionsIfPossible(items.map { it.uri })

            val message = "Queued ${ids.size} shared file(s)"
            appLogRepository.addLog(AppLog(type = LogType.UPLOAD, message = message))

            ShareIntentStore.clear()
            _uiState.update { it.copy(isBusy = false, message = message) }
            onComplete()
        }
    }

    fun cancelShareImport(onComplete: () -> Unit) {
        ShareIntentStore.clear()
        onComplete()
    }

    private data class FileMetadata(
        val displayName: String,
        val size: Long?,
        val mimeType: String?,
    )

    private fun resolveDisplayName(uri: Uri): String = resolveMetadata(uri).displayName

    private fun resolveMetadata(uri: Uri): FileMetadata {
        val mimeType = context.contentResolver.getType(uri)
        var displayName: String? = null
        var size: Long? = null

        val cursor: Cursor? = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)

                if (nameIndex >= 0) displayName = it.getString(nameIndex)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }

        val fallbackName = "shared_${System.currentTimeMillis()}${mimeType?.toSimpleExtension().orEmpty()}"

        return FileMetadata(
            displayName = displayName ?: fallbackName,
            size = size,
            mimeType = mimeType,
        )
    }

    private fun String.toSimpleExtension(): String =
        when (lowercase(Locale.getDefault())) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "application/pdf" -> ".pdf"
            else -> ""
        }

    private fun persistReadPermissionsIfPossible(uris: List<Uri>) {
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }
}
