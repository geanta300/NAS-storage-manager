package com.example.storagenas.network.model

data class RemoteEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long? = null,
    val modifiedAt: Long? = null,
)
