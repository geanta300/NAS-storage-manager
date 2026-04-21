package com.example.storagenas.di

import com.example.storagenas.network.reachability.NasReachabilityChecker
import com.example.storagenas.network.reachability.SocketNasReachabilityChecker
import com.example.storagenas.network.sftp.SftpClient
import com.example.storagenas.network.sftp.SshjSftpClient
import com.example.storagenas.network.zerotier.EmbeddedZeroTierIntegrationManager
import com.example.storagenas.network.zerotier.ZeroTierIntegrationManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {
    @Binds
    @Singleton
    abstract fun bindSftpClient(impl: SshjSftpClient): SftpClient

    @Binds
    @Singleton
    abstract fun bindNasReachabilityChecker(impl: SocketNasReachabilityChecker): NasReachabilityChecker

    @Binds
    @Singleton
    abstract fun bindZeroTierIntegrationManager(impl: EmbeddedZeroTierIntegrationManager): ZeroTierIntegrationManager
}
