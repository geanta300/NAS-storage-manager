package com.example.storagenas.ui.screens.upload

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

data class UploadUiState(
    val destinationPath: String = "/",
    val message: String? = null,
    val recentTasks: List<UploadTask> = emptyList(),
    val isQueueing: Boolean = false,
)

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nasConfigRepository: NasConfigRepository,
    private val uploadRepository: UploadRepository,
    private val uploadWorkScheduler: UploadWorkScheduler,
    private val appLogRepository: AppLogRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UploadUiState())
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            nasConfigRepository.observeConfig().collect { config ->
                val destination = config?.defaultRemotePath ?: "/"
                _uiState.update { it.copy(destinationPath = destination) }
            }
        }

        viewModelScope.launch {
            uploadRepository.observeTasks().collect { tasks ->
                _uiState.update { it.copy(recentTasks = tasks.take(10)) }
            }
        }
    }

    fun onDestinationPathChanged(value: String) {
        _uiState.update { it.copy(destinationPath = value.ifBlank { "/" }) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun queueUris(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isQueueing = true, message = null) }

            val destination = _uiState.value.destinationPath.ifBlank { "/" }
            val tasks = uris.map { uri ->
                val metadata = resolveMetadata(uri)
                UploadTask(
                    localUri = uri.toString(),
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
            persistReadPermissionsIfPossible(uris)

            val message = "Queued ${ids.size} file(s) for upload"
            appLogRepository.addLog(AppLog(type = LogType.UPLOAD, message = message))
            _uiState.update { it.copy(isQueueing = false, message = message) }
        }
    }

    private data class FileMetadata(
        val displayName: String,
        val size: Long?,
        val mimeType: String?,
    )

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

        val fallbackName = "file_${System.currentTimeMillis()}${mimeType?.toSimpleExtension().orEmpty()}"

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
