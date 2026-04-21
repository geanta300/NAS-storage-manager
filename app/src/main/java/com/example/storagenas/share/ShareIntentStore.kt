package com.example.storagenas.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ShareIntentStore {
    private val _sharedUris = MutableStateFlow<List<Uri>>(emptyList())
    val sharedUris: StateFlow<List<Uri>> = _sharedUris.asStateFlow()

    fun ingest(intent: Intent?) {
        if (intent == null) return

        val uris = when (intent.action) {
            Intent.ACTION_SEND -> {
                listOfNotNull(
                    IntentCompat.getParcelableExtra(
                        intent,
                        Intent.EXTRA_STREAM,
                        Uri::class.java,
                    ),
                )
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java,
                ).orEmpty()
            }

            else -> emptyList()
        }

        if (uris.isNotEmpty()) {
            _sharedUris.value = uris
        }
    }

    fun clear() {
        _sharedUris.value = emptyList()
    }
}
