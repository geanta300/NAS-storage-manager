package com.example.storagenas.network.model

sealed interface NasConnectionState {
    data object ZeroTierDisconnected : NasConnectionState
    data object ZeroTierConnectedNasUnreachable : NasConnectionState
    data object ZeroTierConnectedNasReachable : NasConnectionState
    data class Error(val message: String) : NasConnectionState
}
