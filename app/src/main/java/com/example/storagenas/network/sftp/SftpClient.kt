package com.example.storagenas.network.sftp

import com.example.storagenas.domain.model.NasConfig
import com.example.storagenas.network.common.NetworkResult
import com.example.storagenas.network.model.RemoteEntry

interface SftpClient {
    suspend fun connect(config: NasConfig): NetworkResult<Unit>

    suspend fun testConnection(config: NasConfig): NetworkResult<Unit>

    suspend fun listFolders(
        config: NasConfig,
        remotePath: String,
    ): NetworkResult<List<RemoteEntry>>

    suspend fun createDirectory(
        config: NasConfig,
        remotePath: String,
    ): NetworkResult<Unit>

    suspend fun uploadFile(
        config: NasConfig,
        localFilePath: String,
        remotePath: String,
    ): NetworkResult<Unit>

    suspend fun downloadFile(
        config: NasConfig,
        remotePath: String,
        localFilePath: String,
    ): NetworkResult<Unit>

    suspend fun moveFile(
        config: NasConfig,
        sourceRemotePath: String,
        destinationRemotePath: String,
    ): NetworkResult<Unit>

    suspend fun deleteFile(
        config: NasConfig,
        remotePath: String,
    ): NetworkResult<Unit>
}
