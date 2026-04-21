package com.example.storagenas.domain.model

enum class AuthType {
    PASSWORD,
    SSH_KEY,
}

enum class UploadStatus {
    PENDING,
    QUEUED,
    UPLOADING,
    SUCCESS,
    FAILED,
    CANCELLED,
    SKIPPED,
}

enum class SyncMode {
    FULL_GALLERY,
    SELECTED_ALBUMS,
    MANUAL_SELECTION,
}

enum class SyncStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class LogType {
    INFO,
    WARNING,
    ERROR,
    CONNECTIVITY,
    UPLOAD,
    SYNC,
}
