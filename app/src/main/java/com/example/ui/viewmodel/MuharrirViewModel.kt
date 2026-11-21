package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.gemini.GeminiKeyTestOutcome
import com.example.data.pdf.PdfInspectionResult
import com.example.data.pdf.PdfInspector
import com.example.data.repository.ImportOutcome
import com.example.data.repository.MuharrirRepository
import com.example.data.storage.PersistableUriPermissionManager
import com.example.domain.model.ApiKeyMetadata
import com.example.domain.model.BackendConnectionState
import com.example.domain.model.DocumentRecord
import com.example.domain.model.DocumentStatus
import com.example.domain.model.ExportMode
import com.example.domain.model.GeminiKeyRecord
import com.example.domain.model.GeminiUsageStats
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrEngineType
import com.example.domain.model.PageFailureReasons
import com.example.domain.model.PageRecord
import com.example.domain.model.PageStatus
import com.example.domain.model.PipelineStageInfo
import com.example.domain.model.ProcessingPreferences
import com.example.domain.ocr.OcrAvailabilityResult
import com.example.review.model.SmartReviewPreferencesStore
import com.example.service.ProcessingForegroundService
import com.example.service.ProcessingStateBridge
import com.example.ui.theme.AppThemePalette
import com.example.ui.theme.AppearanceMode
import com.example.ui.theme.AppearancePreferences
import com.example.ui.theme.AppearancePreferencesStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DashboardFilter(val arabicLabel: String) {
    ALL("الكل"),
    NEW("جديد"),
    PROCESSING("قيد المعالجة"),
    NEEDS_REVIEW("يحتاج إلى مراجعة"),
    COMPLETED("مكتمل"),
    STOPPED_OR_FAILED("متوقف / فشل"),
}

data class NewDocumentUiState(
    val isInspecting: Boolean = false,
    val isImporting: Boolean = false,
    val importProgressPercent: Int = 0,
    val importPhaseLabelAr: String? = null,
    val replaceDocumentId: String? = null,
    val replaceDocumentName: String? = null,
    val selectedUriString: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long? = null,
    val pageCount: Int? = null,
    val previewBitmap: Bitmap? = null,
    val currentPreviewPage: Int = 0,
    val isLoadingPreviewPage: Boolean = false,
    val previewNoteAr: String? = null,
    val validationErrorAr: String? = null,
    val startPageInput: String = "",
    val endPageInput: String = "",
    val pageRangeErrorAr: String? = null,
) {
    val hasValidSelection: Boolean
        get() = selectedUriString != null && fileName != null && validationErrorAr == null

    val parsedStartPage: Int?
        get() = startPageInput.trim().toIntOrNull()

    val parsedEndPage: Int?
        get() = endPageInput.trim().toIntOrNull()

    val isPageRangeValid: Boolean
        get() {
            val total = pageCount ?: return true
            if (total <= 0) return true
            val start = parsedStartPage ?: return false
            val end = parsedEndPage ?: return false
            return pageRangeErrorAr == null && start >= 1 && end <= total && start <= end
        }
}

data class DashboardUiState(
    val documents: List<DocumentRecord> = emptyList(),
    val filteredDocuments: List<DocumentRecord> = emptyList(),
    val selectedFilter: DashboardFilter = DashboardFilter.ALL,
    val totalCount: Int = 0,
    val processingCount: Int = 0,
    val awaitingReviewCount: Int = 0,
    val completedCount: Int = 0,
    val failedOrStoppedCount: Int = 0,
)

data class OcrExecutionUiState(
    val isRunning: Boolean = false,
    val activeDocumentId: String? = null,
    val currentPageNumber: Int = 0,
    val completedPages: Int = 0,
    val failedPages: Int = 0,
    val totalPages: Int = 0,
    val activeEngine: OcrEngineType = OcrEngineType.TESSERACT,
    val availability: OcrAvailabilityResult? = null,
)

data class GeminiExecutionUiState(
    val isRunning: Boolean = false,
    val activeDocumentId: String? = null,
    val currentPageNumber: Int = 0,
    val analyzedPages: Int = 0,
    val failedPages: Int = 0,
    val totalTargetPages: Int = 0,
)

data class SuspiciousFixUiState(
    val isRunning: Boolean = false,
    val activeDocumentId: String? = null,
    val currentPageNumber: Int = 0,
    val fixedPages: Int = 0,
    val failedPages: Int = 0,
    val totalSuspiciousPages: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MuharrirViewModel(
    application: Application,
    private val repository: MuharrirRepository,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        repository = MuharrirRepository.createDefault(application),
    )

    private val appearanceStore = AppearancePreferencesStore.getInstance(application)
    val appearancePreferences: StateFlow<AppearancePreferences> = appearanceStore.preferences

    private val smartReviewStore = SmartReviewPreferencesStore.getInstance(application)
    val autoAcceptHighConfidenceGemini: StateFlow<Boolean> = smartReviewStore.autoAcceptHighConfidence
    val showExportWarnings: StateFlow<Boolean> = smartReviewStore.showExportWarnings

    private val _selectedFilter = MutableStateFlow(DashboardFilter.ALL)
    private val _selectedDocumentId = MutableStateFlow<String?>(null)
    val selectedDocumentId: StateFlow<String?> = _selectedDocumentId.asStateFlow()

    private val _newDocState = MutableStateFlow(NewDocumentUiState())
    val newDocState: StateFlow<NewDocumentUiState> = _newDocState.asStateFlow()

    private val _workspacePreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val workspacePreviewBitmap: StateFlow<Bitmap?> = _workspacePreviewBitmap.asStateFlow()

    private val _workspacePreviewPage = MutableStateFlow(0)
    val workspacePreviewPage: StateFlow<Int> = _workspacePreviewPage.asStateFlow()

    private val _isLoadingWorkspacePage = MutableStateFlow(false)
    val isLoadingWorkspacePage: StateFlow<Boolean> = _isLoadingWorkspacePage.asStateFlow()

    private val _pageThumbnails = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())
    val pageThumbnails: StateFlow<Map<Int, Bitmap>> = _pageThumbnails.asStateFlow()

    val workspaceNoticeAr: StateFlow<String?> = ProcessingStateBridge.workspaceNoticeAr

    private val _settingsNoticeAr = MutableStateFlow<String?>(null)
    val settingsNoticeAr: StateFlow<String?> = _settingsNoticeAr.asStateFlow()

    val ocrExecutionState: StateFlow<OcrExecutionUiState> = ProcessingStateBridge.ocrExecutionState

    val geminiExecutionState: StateFlow<GeminiExecutionUiState> = ProcessingStateBridge.geminiExecutionState

    val suspiciousFixState: StateFlow<SuspiciousFixUiState> = ProcessingStateBridge.suspiciousFixState

    private val _reprocessingMissingPageIds = MutableStateFlow<Set<String>>(emptySet())
    val reprocessingMissingPageIds: StateFlow<Set<String>> = _reprocessingMissingPageIds.asStateFlow()

    private val _retryingPageIds = MutableStateFlow<Set<Int>>(emptySet())
    val retryingPageIds: StateFlow<Set<Int>> = _retryingPageIds.asStateFlow()

    private val suspiciousRefreshTrigger: StateFlow<Int> = ProcessingStateBridge.suspiciousRefreshTrigger

    private val _documentPageRanges = MutableStateFlow<Map<String, IntRange>>(emptyMap())
    val documentPageRanges: StateFlow<Map<String, IntRange>> = _documentPageRanges.asStateFlow()

    private val _testingKeyId = MutableStateFlow<String?>(null)
    val testingKeyId: StateFlow<String?> = _testingKeyId.asStateFlow()

    private var activeImportJob: Job? = null

    val preferences: StateFlow<ProcessingPreferences> = repository.preferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ProcessingPreferences(),
        )

    val apiKeys: StateFlow<List<ApiKeyMetadata>> = repository.apiKeys
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val geminiKeys: StateFlow<List<GeminiKeyRecord>> = repository.geminiKeys
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val geminiUsageStats: StateFlow<GeminiUsageStats> = repository.geminiUsageStats
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = GeminiUsageStats(),
        )

    val backendConnectionState: BackendConnectionState
        get() = repository.backendConnectionState

    val dashboardState: StateFlow<DashboardUiState> = combine(
        repository.documents,
        _selectedFilter,
    ) { docs, filter ->
        val filtered = when (filter) {
            DashboardFilter.ALL -> docs
            DashboardFilter.NEW -> docs.filter {
                it.status == DocumentStatus.NEW && it.importStatus == ImportStatus.READY_FOR_PROCESSING
            }
            DashboardFilter.PROCESSING -> docs.filter {
                it.importStatus == ImportStatus.IMPORTING || it.status in setOf(
                    DocumentStatus.PROCESSING,
                    DocumentStatus.EXTRACTING_TEXT,
                    DocumentStatus.ANALYZING_TEXT,
                    DocumentStatus.GENERATING_WORD,
                )
            }
            DashboardFilter.NEEDS_REVIEW -> docs.filter { it.status == DocumentStatus.NEEDS_REVIEW }
            DashboardFilter.COMPLETED -> docs.filter {
                it.status == DocumentStatus.COMPLETED || it.status == DocumentStatus.REVIEW_COMPLETED
            }
            DashboardFilter.STOPPED_OR_FAILED -> docs.filter {
                it.status == DocumentStatus.PAUSED ||
                    it.status == DocumentStatus.FAILED ||
                    it.importStatus == ImportStatus.INTERRUPTED ||
                    it.importStatus == ImportStatus.FAILED
            }
        }
        DashboardUiState(
            documents = docs,
            filteredDocuments = filtered,
            selectedFilter = filter,
            totalCount = docs.size,
            processingCount = docs.count {
                it.importStatus == ImportStatus.IMPORTING || it.status in setOf(
                    DocumentStatus.PROCESSING,
                    DocumentStatus.EXTRACTING_TEXT,
                    DocumentStatus.ANALYZING_TEXT,
                    DocumentStatus.GENERATING_WORD,
                )
            },
            awaitingReviewCount = docs.count { it.status == DocumentStatus.NEEDS_REVIEW },
            completedCount = docs.count { it.status == DocumentStatus.COMPLETED },
            failedOrStoppedCount = docs.count {
                it.status == DocumentStatus.PAUSED ||
                    it.status == DocumentStatus.FAILED ||
                    it.importStatus == ImportStatus.INTERRUPTED ||
                    it.importStatus == ImportStatus.FAILED
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    val activeDocument: StateFlow<DocumentRecord?> = combine(
        repository.documents,
        _selectedDocumentId,
    ) { docs, selectedId ->
        selectedId ?: docs.firstOrNull()?.id
    }.flatMapLatest { targetId ->
        if (targetId == null) flowOf(null) else repository.observeDocumentWithPages(targetId)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    val pagesNeedingReprocessingCount: StateFlow<Int> = activeDocument
        .flatMapLatest { doc ->
            if (doc == null) flowOf(0) else repository.observeCountPagesNeedingReprocessing(doc.id)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    val suspiciousPages: StateFlow<List<PageRecord>> = combine(
        activeDocument,
        ocrExecutionState,
        suspiciousRefreshTrigger,
    ) { doc, ocrState, _ ->
        val isOcrRunningOnDoc = doc != null && ocrState.isRunning && ocrState.activeDocumentId == doc.id
        doc to isOcrRunningOnDoc
    }.flatMapLatest { (doc, isOcrRunningOnDoc) ->
        if (doc == null || isOcrRunningOnDoc) {
            flowOf(emptyList())
        } else {
            flow {
                emit(repository.detectSuspiciousPages(doc))
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val pagesWithMissingContent: StateFlow<List<PageRecord>> = activeDocument
        .map { doc ->
            doc?.pages
                ?.filter { it.hasMissingContent }
                ?.sortedBy { it.originalPageNumber }
                .orEmpty()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val pagesWithFailedAnalysis: StateFlow<List<PageRecord>> = activeDocument
        .map { doc ->
            doc?.pages
                ?.filter {
                    it.status == PageStatus.FAILED &&
                        it.failureReason != PageFailureReasons.USER_SKIPPED
                }
                ?.sortedBy { it.originalPageNumber }
                .orEmpty()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    init {
        ProcessingStateBridge.setRepositoryProvider { repository }
        viewModelScope.launch {
            repository.prepareTesseractAssets(getApplication())
            repository.recoverInterruptedImports()
            repository.recoverInterruptedOcr()
        }
        viewModelScope.launch {
            repository.preferences.collectLatest { prefs ->
                repository.prepareTesseractAssets(getApplication())
                val availability = repository.checkOcrEngineAvailability(
                    context = getApplication(),
                    engineType = prefs.preferredOcrEngine,
                )
                ProcessingStateBridge.updateOcrAvailability(
                    engineType = prefs.preferredOcrEngine,
                    availability = availability,
                )
            }
        }
    }

    fun setDashboardFilter(filter: DashboardFilter) {
        _selectedFilter.value = filter
    }

    fun selectDocument(documentId: String) {
        if (_selectedDocumentId.value != documentId) {
            _selectedDocumentId.value = documentId
            _workspacePreviewPage.value = 0
            _workspacePreviewBitmap.value = null
            _pageThumbnails.value = emptyMap()
            ProcessingStateBridge.setWorkspaceNotice(null)
        }
    }

    fun prepareReplacementImport(document: DocumentRecord) {
        _newDocState.value = NewDocumentUiState(
            replaceDocumentId = document.id,
            replaceDocumentName = document.fileName,
        )
    }

    fun clearReplacementMode() {
        _newDocState.update { it.copy(replaceDocumentId = null, replaceDocumentName = null) }
    }

    fun getPlannedStages(document: DocumentRecord?): List<PipelineStageInfo> =
        repository.buildPlannedStages(document)

    fun onPdfSelected(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        PersistableUriPermissionManager.takePersistableReadPermission(appContext.contentResolver, uri)
        val replaceId = _newDocState.value.replaceDocumentId
        val replaceName = _newDocState.value.replaceDocumentName
        _newDocState.value = NewDocumentUiState(
            isInspecting = true,
            replaceDocumentId = replaceId,
            replaceDocumentName = replaceName,
        )
        viewModelScope.launch {
            when (val result = PdfInspector.inspectPdf(appContext, uri)) {
                is PdfInspectionResult.Valid -> {
                    val defaultEnd = (result.pageCount ?: 1).coerceAtLeast(1)
                    _newDocState.update {
                        it.copy(
                            isInspecting = false,
                            selectedUriString = result.uriString,
                            fileName = result.fileName,
                            fileSizeBytes = result.fileSizeBytes,
                            pageCount = result.pageCount,
                            previewBitmap = result.previewBitmap,
                            currentPreviewPage = 0,
                            previewNoteAr = result.previewNoteAr,
                            validationErrorAr = null,
                            startPageInput = "1",
                            endPageInput = defaultEnd.toString(),
                            pageRangeErrorAr = null,
                        )
                    }
                }
                is PdfInspectionResult.Invalid -> {
                    _newDocState.update {
                        it.copy(
                            isInspecting = false,
                            selectedUriString = null,
                            fileName = null,
                            fileSizeBytes = null,
                            pageCount = null,
                            previewBitmap = null,
                            validationErrorAr = result.reasonAr,
                            startPageInput = "",
                            endPageInput = "",
                            pageRangeErrorAr = null,
                        )
                    }
                }
            }
        }
    }

    fun updateNewDocumentPageRange(startPageText: String, endPageText: String) {
        val current = _newDocState.value
        val total = current.pageCount
        val error = validatePageRangeInputs(
            startText = startPageText,
            endText = endPageText,
            totalPages = total,
        )
        _newDocState.update {
            it.copy(
                startPageInput = startPageText,
                endPageInput = endPageText,
                pageRangeErrorAr = error,
            )
        }
    }

    private fun validatePageRangeInputs(
        startText: String,
        endText: String,
        totalPages: Int?,
    ): String? {
        if (totalPages == null || totalPages <= 0) return null
        val start = startText.trim().toIntOrNull()
            ?: return "يرجى إدخال رقم صفحة بداية صحيح."
        val end = endText.trim().toIntOrNull()
            ?: return "يرجى إدخال رقم صفحة نهاية صحيح."
        return when {
            start < 1 -> "يجب أن تكون صفحة البداية 1 أو أكبر."
            end > totalPages -> "يجب ألا تتجاوز صفحة النهاية عدد الصفحات ($totalPages)."
            start > end -> "يجب أن تكون «من صفحة» أصغر من أو تساوي «إلى صفحة»."
            else -> null
        }
    }

    fun changeNewDocPreviewPage(context: Context, delta: Int) {
        val current = _newDocState.value
        val uriString = current.selectedUriString ?: return
        val total = current.pageCount ?: return
        val nextIndex = (current.currentPreviewPage + delta).coerceIn(0, (total - 1).coerceAtLeast(0))
        if (nextIndex == current.currentPreviewPage && current.previewBitmap != null) return

        val appContext = context.applicationContext
        _newDocState.update { it.copy(isLoadingPreviewPage = true, currentPreviewPage = nextIndex) }
        viewModelScope.launch {
            val bitmap = PdfInspector.renderPage(appContext, uriString, nextIndex)
            _newDocState.update { state ->
                if (state.selectedUriString == uriString) {
                    state.copy(
                        previewBitmap = bitmap ?: state.previewBitmap,
                        isLoadingPreviewPage = false,
                    )
                } else {
                    state
                }
            }
        }
    }

    fun clearSelectedPdf() {
        val replaceId = _newDocState.value.replaceDocumentId
        val replaceName = _newDocState.value.replaceDocumentName
        _newDocState.value = NewDocumentUiState(
            replaceDocumentId = replaceId,
            replaceDocumentName = replaceName,
        )
    }

    fun cancelActiveImport() {
        activeImportJob?.cancel()
        activeImportJob = null
        _newDocState.update {
            it.copy(
                isImporting = false,
                importProgressPercent = 0,
                importPhaseLabelAr = null,
                validationErrorAr = "تم إيقاف عملية الاستيراد بناءً على طلبك.",
            )
        }
    }

    fun startNewDocumentTask(
        context: Context,
        startPage: Int? = null,
        endPage: Int? = null,
        onImported: (DocumentRecord) -> Unit,
    ) {
        val current = _newDocState.value
        val uriString = current.selectedUriString ?: return
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        if (current.validationErrorAr != null || current.pageRangeErrorAr != null || current.isImporting) return

        val appContext = context.applicationContext
        val replaceId = current.replaceDocumentId
        val requestedStart = startPage ?: current.parsedStartPage ?: 1
        val requestedEnd = endPage ?: current.parsedEndPage ?: (current.pageCount ?: 1)

        activeImportJob?.cancel()
        activeImportJob = viewModelScope.launch {
            _newDocState.update {
                it.copy(
                    isImporting = true,
                    importProgressPercent = 5,
                    importPhaseLabelAr = "بدء استيراد المستند إلى التخزين الخاص...",
                    validationErrorAr = null,
                )
            }

            val outcome = repository.importPdfFromUri(
                context = appContext,
                uri = uri,
                replaceDocumentId = replaceId,
                onProgress = { update ->
                    _newDocState.update {
                        it.copy(
                            importProgressPercent = update.progressPercent,
                            importPhaseLabelAr = update.phaseLabelAr,
                        )
                    }
                },
            )

            when (outcome) {
                is ImportOutcome.Success -> {
                    val total = (outcome.document.pageCount ?: outcome.document.pages.size).coerceAtLeast(1)
                    val validStart = requestedStart.coerceIn(1, total)
                    val validEnd = requestedEnd.coerceIn(validStart, total)
                    _documentPageRanges.update { it + (outcome.document.id to (validStart..validEnd)) }

                    _selectedDocumentId.value = outcome.document.id
                    _workspacePreviewPage.value = (validStart - 1).coerceIn(0, (total - 1).coerceAtLeast(0))
                    _workspacePreviewBitmap.value = current.previewBitmap
                    _pageThumbnails.value = emptyMap()
                    val rangeNote = if (validStart > 1 || validEnd < total) {
                        " بنطاق معالجة من صفحة $validStart إلى $validEnd."
                    } else {
                        "."
                    }
                    ProcessingStateBridge.setWorkspaceNotice(
                        if (replaceId != null) {
                            "تم استبدال ملف PDF وحفظ النسخة الجديدة وفهرسة $total صفحة$rangeNote"
                        } else {
                            "تم استيراد المستند وحفظه في التخزين الخاص للتطبيق ($total صفحة)$rangeNote"
                        },
                    )
                    _newDocState.value = NewDocumentUiState()
                    onImported(outcome.document)
                }
                is ImportOutcome.Failure -> {
                    _newDocState.update {
                        it.copy(
                            isImporting = false,
                            importProgressPercent = 0,
                            importPhaseLabelAr = null,
                            validationErrorAr = outcome.reasonAr,
                        )
                    }
                }
            }
        }
    }

    fun loadWorkspacePreviewPage(context: Context, document: DocumentRecord, pageIndex: Int) {
        val total = document.pageCount ?: 1
        val clamped = pageIndex.coerceIn(0, (total - 1).coerceAtLeast(0))
        _workspacePreviewPage.value = clamped
        _isLoadingWorkspacePage.value = true
        val appContext = context.applicationContext
        viewModelScope.launch {
            val bitmap = repository.renderDocumentPage(appContext, document, clamped)
            _workspacePreviewBitmap.value = bitmap
            _isLoadingWorkspacePage.value = false
        }
    }

    fun requestPageThumbnail(document: DocumentRecord, pageNumber: Int) {
        if (_pageThumbnails.value.containsKey(pageNumber)) return
        viewModelScope.launch {
            val thumb = repository.getOrGeneratePageThumbnail(document, pageNumber)
            if (thumb != null) {
                _pageThumbnails.update { current -> current + (pageNumber to thumb) }
            }
        }
    }

    fun removeDocument(documentId: String) {
        viewModelScope.launch {
            repository.deleteDocument(documentId)
            if (_selectedDocumentId.value == documentId) {
                _selectedDocumentId.value = null
                _workspacePreviewBitmap.value = null
                _pageThumbnails.value = emptyMap()
            }
        }
    }

    fun startOrResumeOcr(
        context: Context,
        documentId: String,
        startPage: Int? = null,
        endPage: Int? = null,
    ) {
        val storedRange = _documentPageRanges.value[documentId]
        val effectiveStart = (startPage ?: storedRange?.first ?: 1).coerceAtLeast(1)
        val effectiveEnd = (endPage ?: storedRange?.last ?: Int.MAX_VALUE).coerceAtLeast(effectiveStart)
        if (startPage != null && endPage != null) {
            _documentPageRanges.update { it + (documentId to (effectiveStart..effectiveEnd)) }
        }
        launchOcrJob(
            context = context,
            documentId = documentId,
            retryFailedOnly = false,
            singlePageId = null,
            startPage = effectiveStart,
            endPage = effectiveEnd,
            reprocessFailedAndShortOnly = false,
        )
    }

    fun retryFailedOcrPages(context: Context, documentId: String) {
        launchOcrJob(
            context = context,
            documentId = documentId,
            retryFailedOnly = true,
            singlePageId = null,
            startPage = 1,
            endPage = Int.MAX_VALUE,
            reprocessFailedAndShortOnly = false,
        )
    }

    fun reprocessFailedPages(context: Context, documentId: String) {
        launchOcrJob(
            context = context,
            documentId = documentId,
            retryFailedOnly = false,
            singlePageId = null,
            startPage = 1,
            endPage = Int.MAX_VALUE,
            reprocessFailedAndShortOnly = true,
        )
    }

    fun retrySingleOcrPage(context: Context, documentId: String, pageId: String) {
        launchOcrJob(
            context = context,
            documentId = documentId,
            retryFailedOnly = false,
            singlePageId = pageId,
            startPage = 1,
            endPage = Int.MAX_VALUE,
            reprocessFailedAndShortOnly = false,
        )
    }

    fun pauseOcr() {
        ProcessingForegroundService.cancel(getApplication())
        ProcessingStateBridge.markAllStopped()
        ProcessingStateBridge.setWorkspaceNotice(
            "تم إيقاف استخراج النص مؤقتاً. الصفحات المكتملة محفوظة ويمكنك الاستئناف لاحقاً.",
        )
    }

    private fun launchOcrJob(
        context: Context,
        documentId: String,
        retryFailedOnly: Boolean,
        singlePageId: String?,
        startPage: Int = 1,
        endPage: Int = Int.MAX_VALUE,
        reprocessFailedAndShortOnly: Boolean = false,
    ) {
        if (ocrExecutionState.value.isRunning || geminiExecutionState.value.isRunning || suspiciousFixState.value.isRunning) {
            return
        }
        val appContext = context.applicationContext
        val engineType = preferences.value.preferredOcrEngine
        ProcessingStateBridge.setRepositoryProvider { repository }
        ProcessingForegroundService.startOcr(
            context = appContext,
            documentId = documentId,
            engineType = engineType,
            retryFailedOnly = retryFailedOnly,
            singlePageId = singlePageId,
            startPage = startPage,
            endPage = endPage,
            reprocessShortOnly = reprocessFailedAndShortOnly,
        )
    }

    fun dismissWorkspaceNotice() {
        ProcessingStateBridge.setWorkspaceNotice(null)
    }

    fun setOcrEngine(engine: OcrEngineType) {
        viewModelScope.launch { repository.updateOcrEngine(engine) }
    }

    fun setExportMode(mode: ExportMode) {
        viewModelScope.launch { repository.updateExportMode(mode) }
    }

    fun setHighlightUnresolved(enabled: Boolean) {
        viewModelScope.launch { repository.updateHighlightUnresolved(enabled) }
    }

    fun setAutoSaveCheckpoints(enabled: Boolean) {
        viewModelScope.launch { repository.updateAutoSaveCheckpoints(enabled) }
    }

    fun setResumeAfterInterruption(enabled: Boolean) {
        viewModelScope.launch { repository.updateResumeAfterInterruption(enabled) }
    }

    fun setAppearanceMode(mode: AppearanceMode) {
        appearanceStore.setAppearanceMode(mode)
    }

    fun setThemePalette(palette: AppThemePalette) {
        appearanceStore.setThemePalette(palette)
    }

    fun setAutoAcceptHighConfidenceGemini(enabled: Boolean) {
        smartReviewStore.setAutoAcceptHighConfidenceEnabled(enabled)
    }

    fun setShowExportWarnings(enabled: Boolean) {
        smartReviewStore.setShowExportWarningsEnabled(enabled)
    }

    fun addGeminiApiKey(label: String, rawApiKey: String) {
        viewModelScope.launch {
            val result = repository.addGeminiKey(label, rawApiKey)
            result.fold(
                onSuccess = { added ->
                    _settingsNoticeAr.value = "تم حفظ المفتاح «${added.label}» (${added.maskedPrefix}) مشفراً عبر Android Keystore."
                },
                onFailure = { err ->
                    _settingsNoticeAr.value = err.message ?: "تعذر حفظ مفتاح Gemini API."
                },
            )
        }
    }

    fun testGeminiApiKey(keyId: String) {
        if (_testingKeyId.value != null) return
        viewModelScope.launch {
            _testingKeyId.value = keyId
            when (val outcome = repository.testGeminiKey(keyId)) {
                is GeminiKeyTestOutcome.Valid -> {
                    _settingsNoticeAr.value = outcome.messageAr
                }
                is GeminiKeyTestOutcome.Failed -> {
                    _settingsNoticeAr.value = outcome.reasonAr
                }
            }
            _testingKeyId.value = null
        }
    }

    fun toggleGeminiApiKey(keyId: String, enabled: Boolean) {
        viewModelScope.launch { repository.setGeminiKeyEnabled(keyId, enabled) }
    }

    fun removeGeminiApiKey(keyId: String) {
        viewModelScope.launch { repository.removeGeminiKey(keyId) }
    }

    fun selectGeminiModel(modelId: String) {
        viewModelScope.launch { repository.selectGeminiModel(modelId) }
    }

    fun startGeminiAnalysis(
        documentId: String,
        singlePageId: String? = null,
        startPage: Int? = null,
        endPage: Int? = null,
    ) {
        if (geminiExecutionState.value.isRunning || ocrExecutionState.value.isRunning || suspiciousFixState.value.isRunning) {
            return
        }
        val storedRange = _documentPageRanges.value[documentId]
        val effectiveStart = (startPage ?: storedRange?.first ?: 1).coerceAtLeast(1)
        val effectiveEnd = (endPage ?: storedRange?.last ?: Int.MAX_VALUE).coerceAtLeast(effectiveStart)
        if (startPage != null && endPage != null) {
            _documentPageRanges.update { it + (documentId to (effectiveStart..effectiveEnd)) }
        }
        ProcessingStateBridge.setRepositoryProvider { repository }
        ProcessingForegroundService.startGemini(
            context = getApplication(),
            documentId = documentId,
            singlePageId = singlePageId,
            startPage = effectiveStart,
            endPage = effectiveEnd,
        )
    }

    fun pauseGeminiAnalysis() {
        ProcessingForegroundService.cancel(getApplication())
        ProcessingStateBridge.markAllStopped()
        ProcessingStateBridge.setWorkspaceNotice("تم إيقاف تحليل Gemini مؤقتاً. النتائج المكتملة محفوظة محلياً.")
    }

    fun fixSuspiciousPagesWithGemini(documentId: String) {
        if (suspiciousFixState.value.isRunning || geminiExecutionState.value.isRunning || ocrExecutionState.value.isRunning) {
            return
        }
        ProcessingStateBridge.setRepositoryProvider { repository }
        ProcessingForegroundService.startSuspiciousFix(
            context = getApplication(),
            documentId = documentId,
        )
    }

    fun forceGeminiExtractionForPage(documentId: String, pageNumber: Int) {
        val pageId = activeDocument.value
            ?.takeIf { it.id == documentId }
            ?.pages
            ?.firstOrNull { it.originalPageNumber == pageNumber }
            ?.pageId
            ?: "${documentId}_page_$pageNumber"

        if (_reprocessingMissingPageIds.value.contains(pageId)) return

        viewModelScope.launch {
            _reprocessingMissingPageIds.update { it + pageId }
            try {
                val result = repository.forceGeminiExtractionForPage(documentId, pageNumber)
                result.fold(
                    onSuccess = {
                        ProcessingStateBridge.setWorkspaceNotice(
                            "تمت إعادة استخراج نص صفحة $pageNumber بالكامل عبر Gemini واعتمادها مباشرة دون إضافتها لطابور المراجعة.",
                        )
                    },
                    onFailure = { err ->
                        ProcessingStateBridge.setWorkspaceNotice(
                            err.message ?: "تعذرت إعادة استخراج صفحة $pageNumber عبر Gemini.",
                        )
                    },
                )
            } finally {
                _reprocessingMissingPageIds.update { it - pageId }
                ProcessingStateBridge.incrementSuspiciousRefreshTrigger()
            }
        }
    }

    fun skipPageAnalysis(documentId: String, pageNumber: Int) {
        viewModelScope.launch {
            val result = repository.skipPageAnalysis(documentId, pageNumber)
            result.fold(
                onSuccess = {
                    ProcessingStateBridge.setWorkspaceNotice(
                        "تم تخطي تحليل صفحة $pageNumber عبر Gemini مع الإبقاء على نص OCR وعناصر المراجعة الحالية.",
                    )
                },
                onFailure = { err ->
                    ProcessingStateBridge.setWorkspaceNotice(
                        err.message ?: "تعذر تخطي صفحة $pageNumber.",
                    )
                },
            )
        }
    }

    fun retryPageAnalysis(documentId: String, pageNumber: Int) {
        if (_retryingPageIds.value.contains(pageNumber)) return
        viewModelScope.launch {
            _retryingPageIds.update { it + pageNumber }
            try {
                val result = repository.retryPageAnalysis(documentId, pageNumber)
                result.fold(
                    onSuccess = {
                        ProcessingStateBridge.setWorkspaceNotice(
                            "تمت إعادة تحليل صفحة $pageNumber عبر Gemini بنجاح.",
                        )
                    },
                    onFailure = { err ->
                        ProcessingStateBridge.setWorkspaceNotice(
                            err.message ?: "تعذرت إعادة تحليل صفحة $pageNumber عبر Gemini.",
                        )
                    },
                )
            } finally {
                _retryingPageIds.update { it - pageNumber }
                ProcessingStateBridge.incrementSuspiciousRefreshTrigger()
            }
        }
    }

    fun retryAllFailed(documentId: String) {
        if (_retryingPageIds.value.isNotEmpty()) return
        viewModelScope.launch {
            val failedPages = repository.getPagesWithFailedAnalysis(documentId)
            if (failedPages.isEmpty()) return@launch

            var succeeded = 0
            var failed = 0
            for (page in failedPages) {
                val pageNumber = page.originalPageNumber
                _retryingPageIds.update { it + pageNumber }
                try {
                    val result = repository.retryPageAnalysis(documentId, pageNumber)
                    if (result.isSuccess) {
                        succeeded++
                    } else {
                        failed++
                    }
                } finally {
                    _retryingPageIds.update { it - pageNumber }
                }
            }
            ProcessingStateBridge.incrementSuspiciousRefreshTrigger()
            ProcessingStateBridge.setWorkspaceNotice(
                when {
                    succeeded > 0 && failed == 0 ->
                        "تمت إعادة تحليل جميع الصفحات المتعثرة ($succeeded) بنجاح."
                    succeeded > 0 && failed > 0 ->
                        "تمت إعادة تحليل $succeeded صفحة بنجاح، ولا تزال $failed صفحة متعثرة."
                    else ->
                        "تعذرت إعادة تحليل الصفحات المتعثرة ($failed صفحة)."
                },
            )
        }
    }

    fun registerApiKeySlot(label: String, keyPreviewIgnored: String) {
        addGeminiApiKey(label, keyPreviewIgnored)
    }

    fun requestKeyValidationNotice(label: String) {
        _settingsNoticeAr.value = "استخدم زر اختبار المفتاح بجانب المفتاح المحفوظ."
    }

    fun toggleApiKey(id: String) {
        viewModelScope.launch { repository.toggleApiKeyEnabled(id) }
    }

    fun removeApiKey(id: String) {
        viewModelScope.launch { repository.removeApiKeySlot(id) }
    }

    fun dismissSettingsNotice() {
        _settingsNoticeAr.value = null
    }

    val reviewQueueGateway: com.example.review.model.ReviewQueueGateway
        get() = repository.reviewQueueGateway

    val documentReviewGateway: com.example.review.model.DocumentReviewGateway
        get() = repository.documentReviewGateway

    val pageBitmapSource: com.example.review.model.PageBitmapSource
        get() = repository.pageBitmapSource

    val wordExportDataGateway: com.example.export.model.WordExportDataGateway
        get() = repository.wordExportDataGateway
}
