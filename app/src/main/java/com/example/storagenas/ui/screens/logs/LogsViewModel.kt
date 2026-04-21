package com.example.storagenas.ui.screens.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.storagenas.domain.model.AppLog
import com.example.storagenas.domain.repository.AppLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogsUiState(
    val logs: List<AppLog> = emptyList(),
    val routeDiagnostics: List<AppLog> = emptyList(),
    val nonDiagnosticLogs: List<AppLog> = emptyList(),
)

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val appLogRepository: AppLogRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appLogRepository.observeLogs().collect { logs ->
                val diagnostics = logs.filter { it.message.contains("ROUTE_DIAG") }
                val nonDiagnostics = logs.filterNot { it.message.contains("ROUTE_DIAG") }
                _uiState.update {
                    it.copy(
                        logs = logs,
                        routeDiagnostics = diagnostics,
                        nonDiagnosticLogs = nonDiagnostics,
                    )
                }
            }
        }
    }

    fun clearLogHistory() {
        viewModelScope.launch {
            appLogRepository.clearLogs()
        }
    }
}
