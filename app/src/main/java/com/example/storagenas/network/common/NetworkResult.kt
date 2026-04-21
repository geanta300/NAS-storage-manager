package com.example.storagenas.network.common

sealed interface NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>
    data class Error(
        val code: ErrorCode,
        val message: String,
        val cause: Throwable? = null,
    ) : NetworkResult<Nothing>

    enum class ErrorCode {
        CONFIG_MISSING,
        VALIDATION,
        CONNECTION,
        AUTH,
        TIMEOUT,
        IO,
        NOT_FOUND,
        UNKNOWN,
    }
}

inline fun <T> NetworkResult<T>.onSuccess(block: (T) -> Unit): NetworkResult<T> {
    if (this is NetworkResult.Success) block(data)
    return this
}

inline fun <T> NetworkResult<T>.onError(block: (NetworkResult.Error) -> Unit): NetworkResult<T> {
    if (this is NetworkResult.Error) block(this)
    return this
}
