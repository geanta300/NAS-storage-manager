package com.example.storagenas.workers

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkerModule {
    @Binds
    @Singleton
    abstract fun bindUploadWorkScheduler(impl: UploadWorkSchedulerImpl): UploadWorkScheduler

    @Binds
    @Singleton
    abstract fun bindConnectivityCheckScheduler(impl: ConnectivityCheckSchedulerImpl): ConnectivityCheckScheduler
}
