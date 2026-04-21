package com.example.storagenas.domain.usecase

import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.network.common.NetworkResult
import com.example.storagenas.network.reachability.NasReachabilityChecker
import javax.inject.Inject

class TestNasReachabilityUseCase @Inject constructor(
    private val nasConfigRepository: NasConfigRepository,
    private val nasReachabilityChecker: NasReachabilityChecker,
) {
    suspend operator fun invoke(): NetworkResult<Boolean> {
        val config = nasConfigRepository.getConfig()
            ?: return NetworkResult.Error(
                code = NetworkResult.ErrorCode.CONFIG_MISSING,
                message = "NAS configuration is missing. Please configure NAS settings first.",
            )

        return nasReachabilityChecker.isReachable(config)
    }
}
