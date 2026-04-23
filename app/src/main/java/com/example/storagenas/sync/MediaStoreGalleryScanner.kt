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

private const val BUCKET_ID_COLUMN = "bucket_id"
private const val BUCKET_DISPLAY_NAME_COLUMN = "bucket_display_name"

class MediaStoreGalleryScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) : GalleryScanner {

    override suspend fun getAlbums(): List<GalleryAlbum> =
        withContext(Dispatchers.IO) {
            val counters = linkedMapOf<String, Pair<String, Int>>()
            queryAlbumBuckets(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, counters)
            queryAlbumBuckets(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, counters)

            counters.map { (id, pair) ->
                GalleryAlbum(
                    id = id,
                    name = pair.first,
                    itemCount = pair.second,
                )
            }.sortedBy { it.name.lowercase() }
        }

    override suspend fun getAllMedia(): List<GalleryMediaItem> =
        buildList {
            addAll(queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection = null, args = null))
            addAll(queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, selection = null, args = null))
        }.sortedByDescending { it.modifiedAt }

    override suspend fun getMediaForAlbumIds(albumIds: Set<String>): List<GalleryMediaItem> {
        if (albumIds.isEmpty()) return emptyList()
        val placeholders = albumIds.joinToString(separator = ",") { "?" }
        val selection = "$BUCKET_ID_COLUMN IN ($placeholders)"
        val args = albumIds.toTypedArray()
        return buildList {
            addAll(queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection, args))
            addAll(queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, selection, args))
        }.sortedByDescending { it.modifiedAt }
    }

    private fun queryAlbumBuckets(
        uri: android.net.Uri,
        counters: LinkedHashMap<String, Pair<String, Int>>,
    ) {
        val resolver = context.contentResolver
        val projection = arrayOf(BUCKET_ID_COLUMN, BUCKET_DISPLAY_NAME_COLUMN)

        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(BUCKET_ID_COLUMN)
            val nameIndex = cursor.getColumnIndexOrThrow(BUCKET_DISPLAY_NAME_COLUMN)

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
    }

    private suspend fun queryMedia(
        uri: android.net.Uri,
        selection: String?,
        args: Array<String>?,
    ): List<GalleryMediaItem> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
                BUCKET_ID_COLUMN,
                BUCKET_DISPLAY_NAME_COLUMN,
            )

            val items = mutableListOf<GalleryMediaItem>()
            resolver.query(
                uri,
                projection,
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val bucketIdIndex = cursor.getColumnIndexOrThrow(BUCKET_ID_COLUMN)
                val bucketNameIndex = cursor.getColumnIndexOrThrow(BUCKET_DISPLAY_NAME_COLUMN)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val itemUri = ContentUris.withAppendedId(uri, id)
                    val name = cursor.getString(nameIndex) ?: "media_$id"
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
