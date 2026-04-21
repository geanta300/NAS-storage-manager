package com.example.storagenas.sync

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.example.storagenas.sync.model.GalleryAlbum
import com.example.storagenas.sync.model.GalleryMediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MediaStoreGalleryScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) : GalleryScanner {

    override suspend fun getAlbums(): List<GalleryAlbum> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            )

            val counters = linkedMapOf<String, Pair<String, Int>>()

            resolver.query(
                uri,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: "Unnamed Album"
                    val existing = counters[id]
                    counters[id] = if (existing == null) {
                        name to 1
                    } else {
                        existing.first to (existing.second + 1)
                    }
                }
            }

            counters.map { (id, pair) ->
                GalleryAlbum(
                    id = id,
                    name = pair.first,
                    itemCount = pair.second,
                )
            }.sortedBy { it.name.lowercase() }
        }

    override suspend fun getAllMedia(): List<GalleryMediaItem> =
        queryMedia(selection = null, args = null)

    override suspend fun getMediaForAlbumIds(albumIds: Set<String>): List<GalleryMediaItem> {
        if (albumIds.isEmpty()) return emptyList()
        val placeholders = albumIds.joinToString(separator = ",") { "?" }
        val selection = "${MediaStore.Images.Media.BUCKET_ID} IN ($placeholders)"
        return queryMedia(selection = selection, args = albumIds.toTypedArray())
    }

    private suspend fun queryMedia(
        selection: String?,
        args: Array<String>?,
    ): List<GalleryMediaItem> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            )

            val items = mutableListOf<GalleryMediaItem>()
            resolver.query(
                uri,
                projection,
                selection,
                args,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val bucketIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val bucketNameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val itemUri = ContentUris.withAppendedId(uri, id)
                    val name = cursor.getString(nameIndex) ?: "image_$id"
                    val size = cursor.getLong(sizeIndex)
                    val modifiedSec = cursor.getLong(modifiedIndex)
                    val mime = cursor.getString(mimeIndex)
                    val albumId = cursor.getString(bucketIdIndex) ?: "unknown"
                    val albumName = cursor.getString(bucketNameIndex) ?: "Unnamed Album"

                    items += GalleryMediaItem(
                        uri = itemUri,
                        displayName = name,
                        size = size,
                        modifiedAt = modifiedSec * 1000L,
                        mimeType = mime,
                        albumId = albumId,
                        albumName = albumName,
                    )
                }
            }

            items
        }
}
