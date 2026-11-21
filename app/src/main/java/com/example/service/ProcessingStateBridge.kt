package com.example.service

import android.content.Context
import com.example.data.repository.MuharrirRepository
import com.example.domain.model.OcrEngineType
import com.example.domain.ocr.OcrAvailabilityResult
import com.example.ui.viewmodel.GeminiExecutionUiState
import com.example.ui.viewmodel.OcrExecutionUiState
import com.example.ui.viewmodel.SuspiciousFixUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ActiveBackgroundTask {
    OCR,
    GEMINI_ANALYSIS,
    SUSPICIOUS_FIX,
}

/**
 * Process-local state bridge allowing [ProcessingForegroundService] to run OCR/Gemini loops
 * independently of UI lifecycle while keeping [com.example.ui.viewmodel.MuharrirViewModel]
 * synchronized via reactive [StateFlow]s.
 */
object ProcessingStateBridge {

    private val _ocrExecutionState = MutableStateFlow(OcrExecutionUiState())
    val ocrExecutionState: StateFlow<OcrExecutionUiState> = _ocrExecutionState.asStateFlow()

    private val _geminiExecutionState = MutableStateFlow(GeminiExecutionUiState())
    val geminiExecutionState: StateFlow<GeminiExecutionUiState> = _geminiExecutionState.asStateFlow()

    private val _suspiciousFixState = MutableStateFlow(SuspiciousFixUiState())
    val suspiciousFixState: StateFlow<SuspiciousFixUiState> = _suspiciousFixState.asStateFlow()

    private val _workspaceNoticeAr = MutableStateFlow<String?>(null)
    val workspaceNoticeAr: StateFlow<String?> = _workspaceNoticeAr.asStateFlow()

    private val _suspiciousRefreshTrigger = MutableStateFlow(0)
    val suspiciousRefreshTrigger: StateFlow<Int> = _suspiciousRefreshTrigger.asStateFlow()

    private val _activeTask = MutableStateFlow<ActiveBackgroundTask?>(null)
    val activeTask: StateFlow<ActiveBackgroundTask?> = _activeTask.asStateFlow()

    @Volatile
    private var repositoryProvider: ((Context) -> MuharrirRepository)? = null

    fun setRepositoryProvider(provider: ((Context) -> MuharrirRepository)?) {
        repositoryProvider = provider
    }

    fun resolveRepository(context: Context): MuharrirRepository {
        val appContext = context.applicationContext
        return repositoryProvider?.invoke(appContext) ?: MuharrirRepository.createDefault(appContext)
    }

    fun updateOcrAvailability(engineType: OcrEngineType, availability: OcrAvailabilityResult) {
        _ocrExecutionState.update {
            it.copy(
                activeEngine = engineType,
                availability = availability,
            )
        }
    }

    fun updateOcrState(transform: (OcrExecutionUiState) -> OcrExecutionUiState) {
        _ocrExecutionState.update(transform)
    }

    fun updateGeminiState(transform: (GeminiExecutionUiState) -> GeminiExecutionUiState) {
        _geminiExecutionState.update(transform)
    }

    fun updateSuspiciousFixState(transform: (SuspiciousFixUiState) -> SuspiciousFixUiState) {
        _suspiciousFixState.update(transform)
    }

    fun setWorkspaceNotice(messageAr: String?) {
        _workspaceNoticeAr.value = messageAr
    }

    fun incrementSuspiciousRefreshTrigger() {
        _suspiciousRefreshTrigger.update { it + 1 }
    }

    fun setActiveTask(task: ActiveBackgroundTask?) {
        _activeTask.value = task
    }

    fun markAllStopped() {
        _activeTask.value = null
        _ocrExecutionState.update { it.copy(isRunning = false) }
        _geminiExecutionState.update { it.copy(isRunning = false) }
        _suspiciousFixState.update { it.copy(isRunning = false) }
    }

    fun resetForTests() {
        repositoryProvider = null
        _activeTask.value = null
        _ocrExecutionState.value = OcrExecutionUiState()
        _geminiExecutionState.value = GeminiExecutionUiState()
        _suspiciousFixState.value = SuspiciousFixUiState()
        _workspaceNoticeAr.value = null
        _suspiciousRefreshTrigger.value = 0
    }
}
