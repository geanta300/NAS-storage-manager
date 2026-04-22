package com.example.storagenas.workers

interface UploadWorkScheduler {
    fun enqueueUploadTask(taskId: Long)
    fun enqueueUploadTasks(taskIds: List<Long>)
    fun cancelUploadTask(taskId: Long)
    fun cancelAllUploads()
}
