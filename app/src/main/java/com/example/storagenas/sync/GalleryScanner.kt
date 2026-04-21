package com.example.storagenas.sync

import com.example.storagenas.sync.model.GalleryAlbum
import com.example.storagenas.sync.model.GalleryMediaItem

interface GalleryScanner {
    suspend fun getAlbums(): List<GalleryAlbum>
    suspend fun getAllMedia(): List<GalleryMediaItem>
    suspend fun getMediaForAlbumIds(albumIds: Set<String>): List<GalleryMediaItem>
}
