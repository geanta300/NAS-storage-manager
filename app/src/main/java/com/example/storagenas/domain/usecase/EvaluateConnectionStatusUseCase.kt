package com.example.storagenas.domain.usecase

import com.example.storagenas.domain.repository.NasConfigRepository
import com.example.storagenas.network.model.NasConnectionState
import com.example.storagenas.network.reachability.ConnectionStatusEvaluator
import javax.inject.Inject

class EvaluateConnectionStatusUseCase @Inject constructor(
    private val nasConfigRepository: NasConfigRepository,
    private val connectionStatusEvaluator: ConnectionStatusEvaluator,
) {
    suspend operator fun invoke(): NasConnectionState {
        val config = nasConfigRepository.getConfig()
            ?: return NasConnectionState.Error(
                message = "NAS configuration is missing. Please configure NAS settings first.",
            )

        return connectionStatusEvaluator.evaluate(config)
    }
}
