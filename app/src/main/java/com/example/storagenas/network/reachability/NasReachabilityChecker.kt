package com.example.storagenas.network.reachability

import com.example.storagenas.domain.model.NasConfig
import com.example.storagenas.network.common.NetworkResult

interface NasReachabilityChecker {
    suspend fun isReachable(config: NasConfig): NetworkResult<Boolean>
}
