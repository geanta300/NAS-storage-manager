package com.example.storagenas.sync.model

import android.net.Uri

data class GalleryMediaItem(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val modifiedAt: Long,
    val mimeType: String?,
    val albumId: String,
    val albumName: String,
)
