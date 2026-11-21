package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.data.comparison.ComparisonPageSummary
import com.example.data.comparison.OcrGeminiComparisonEngine
import com.example.data.gemini.EncryptedGeminiApiKeyVault
import com.example.data.gemini.GeminiAnalysisCoordinator
import com.example.data.gemini.GeminiApiKeyVault
import com.example.data.gemini.GeminiErrorClassifier
import com.example.data.gemini.GeminiErrorKind
import com.example.data.gemini.GeminiKeyTestOutcome
import com.example.data.gemini.GeminiPageAnalysisOutcome
import com.example.data.gemini.GoogleAiGeminiClient
import com.example.data.local.ApiKeySlotEntity
import com.example.data.local.DocumentEntity
import com.example.data.local.MuharrirDatabase
import com.example.data.local.OcrResultEntity
import com.example.data.local.PageEntity
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.data.ocr.OcrEngineRegistry
import com.example.data.ocr.OcrImagePreprocessor
import com.example.data.ocr.PageInspectionCacheStore
import com.example.data.ocr.TesseractDataInstaller
import com.example.data.ocr.TrainedDataInstallResult
import com.example.data.pdf.AndroidPdfRendererEngine
import com.example.data.pdf.PdfInspector
import com.example.data.pdf.PdfPageEngine
import com.example.data.pdf.PrivatePdfIndexResult
import com.example.data.remote.BackendConnectionManager
import com.example.data.storage.PrivateDocumentStorage
import com.example.data.storage.StreamCopyResult
import com.example.domain.model.ApiKeyMetadata
import com.example.domain.model.ApiKeyStatus
import com.example.domain.model.BackendConnectionState
import com.example.domain.model.DocumentRecord
import com.example.domain.model.DocumentStatus
import com.example.domain.model.ExportMode
import com.example.domain.model.GeminiKeyRecord
import com.example.domain.model.GeminiUsageStats
import com.example.domain.model.ImportStatus
import com.example.domain.model.OcrEngineType
import com.example.domain.model.OcrPageStatus
import com.example.domain.model.PageFailureReasons
import com.example.domain.model.PageRecord
import com.example.domain.model.PageStatus
import com.example.domain.model.PipelineStageInfo
import com.example.domain.model.ProcessingPreferences
import com.example.domain.model.ReviewItem
import com.example.domain.model.ReviewStatus
import com.example.domain.model.StageModuleAvailability
import com.example.domain.ocr.OcrAvailabilityResult
import com.example.domain.usecase.DetectSuspiciousPagesUseCase
import com.example.review.model.SmartReviewPreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface ImportOutcome {
    data class Success(val document: DocumentRecord) : ImportOutcome
    data class Failure(
        val reasonAr: String,
        val documentId: String?,
        val wasInterrupted: Boolean = false,
    ) : ImportOutcome
}

data class ImportProgressUpdate(
    val documentId: String,
    val phaseLabelAr: String,
    val progressPercent: Int,
)

data class OcrExecutionProgress(
    val documentId: String,
    val currentPageNumber: Int,
    val completedPages: Int,
    val failedPages: Int,
    val totalPages: Int,
    val engineType: OcrEngineType,
)

sealed interface OcrRunOutcome {
    data class Completed(
        val processedNow: Int,
        val totalDonePages: Int,
        val totalFailedPages: Int,
        val totalPages: Int,
    ) : OcrRunOutcome

    data class Failure(
        val reasonAr: String,
    ) : OcrRunOutcome
}

sealed interface GeminiRunOutcome {
    data class Completed(
        val analyzedNow: Int,
        val totalAnalyzedPages: Int,
        val failedPages: Int,
        val totalEligiblePages: Int,
        val lastMessageAr: String,
    ) : GeminiRunOutcome

    data class Failure(val reasonAr: String) : GeminiRunOutcome
}

data class GeminiExecutionProgress(
    val documentId: String,
    val currentPageNumber: Int,
    val analyzedPages: Int,
    val failedPages: Int,
    val totalTargetPages: Int,
)

/**
 * Persistent repository backed by Room + app-private storage + EncryptedSharedPreferences for Gemini keys.
 * Survives app restarts and device reboots while sending Gemini API calls directly from the device.
 */
class MuharrirRepository(
    private val database: MuharrirDatabase,
    private val storage: PrivateDocumentStorage,
    private val backendManager: BackendConnectionManager = BackendConnectionManager(),
    private val pageEngine: PdfPageEngine = AndroidPdfRendererEngine,
    private val ocrEngineRegistry: OcrEngineRegistry? = null,
    private val geminiKeyVault: GeminiApiKeyVault? = null,
    private val geminiCoordinator: GeminiAnalysisCoordinator? = null,
    private val smartReviewPrefs: SmartReviewPreferencesStore? = null,
    private val pageInspectionCacheStore: PageInspectionCacheStore? = null,
) {
    private val documentDao = database.documentDao()
    private val pageDao = database.pageDao()
    private val ocrResultDao = database.ocrResultDao()
    private val geminiAnalysisDao = database.geminiAnalysisDao()
    private val reviewItemDao = database.reviewItemDao()
    private val preferencesDao = database.preferencesDao()
    private val apiKeySlotDao = database.apiKeySlotDao()
    private val comparisonEngine = OcrGeminiComparisonEngine(database)
    private val detectSuspiciousPagesUseCase = DetectSuspiciousPagesUseCase()
    private val visualEmptyMemoryCache = ConcurrentHashMap<String, Boolean>()
    private val geminiRepairedMemoryCache = ConcurrentHashMap<String, Boolean>()

    val reviewQueueGateway: com.example.review.model.ReviewQueueGateway =
        MuharrirReviewQueueGateway(database)
    val documentReviewGateway: com.example.review.model.DocumentReviewGateway =
        MuharrirDocumentReviewGateway(database)
    val pageBitmapSource: com.example.review.model.PageBitmapSource =
        MuharrirPageBitmapSource(database, storage, pageEngine)
    val wordExportDataGateway: com.example.export.model.WordExportDataGateway =
        MuharrirWordExportDataGateway(database, smartReviewPrefs = smartReviewPrefs)

    val documents: Flow<List<DocumentRecord>> =
        documentDao.observeActiveDocuments().map { entities ->
            entities.map { it.toDomain() }
        }

    val preferences: Flow<ProcessingPreferences> =
        preferencesDao.observePreferences().map { entity ->
            entity?.toDomain() ?: ProcessingPreferences()
        }

    val apiKeys: Flow<List<ApiKeyMetadata>> =
        apiKeySlotDao.observeApiKeySlots().map { list ->
            list.map { it.toDomain() }
        }

    val geminiKeys: Flow<List<GeminiKeyRecord>> =
        geminiKeyVault?.keys ?: flowOf(emptyList())

    val geminiUsageStats: Flow<GeminiUsageStats> =
        geminiKeyVault?.usageStats ?: flowOf(GeminiUsageStats())

    val backendConnectionState: BackendConnectionState
        get() = backendManager.connectionState

    fun observeDocumentWithPages(documentId: String): Flow<DocumentRecord?> {
        return combine(
            documentDao.observeDocumentById(documentId),
            pageDao.observePagesForDocument(documentId),
            ocrResultDao.observeOcrResultsForDocument(documentId),
            geminiAnalysisDao.observeAnalysesForDocument(documentId),
            reviewItemDao.observeReviewItemsForDocument(documentId),
        ) { docEntity, pageEntities, ocrEntities, geminiEntities, reviewEntities ->
            val ocrByPageId = ocrEntities.associateBy { it.pageId }
            val geminiByPageId = geminiEntities.associateBy { it.pageId }
            docEntity?.toDomain(
                pages = pageEntities.map { page ->
                    page.toDomain(
                        ocrResultEntity = ocrByPageId[page.pageId],
                        geminiAnalysisEntity = geminiByPageId[page.pageId],
                    )
                },
                reviewItems = reviewEntities.map { it.toDomain() },
            )
        }
    }

    suspend fun getDocumentById(documentId: String): DocumentRecord? = withContext(Dispatchers.IO) {
        val doc = documentDao.getDocumentById(documentId) ?: return@withContext null
        val ocrByPageId = ocrResultDao.getOcrResultsForDocument(documentId).associateBy { it.pageId }
        val geminiByPageId = geminiAnalysisDao.getAnalysesForDocument(documentId).associateBy { it.pageId }
        val pages = pageDao.getPagesForDocument(documentId).map { page ->
            page.toDomain(
                ocrResultEntity = ocrByPageId[page.pageId],
                geminiAnalysisEntity = geminiByPageId[page.pageId],
            )
        }
        val reviewItems = reviewItemDao.getReviewQueue(
            documentId = documentId,
            includeAgreements = true,
        ).map { it.toDomain() }
        doc.toDomain(pages = pages, reviewItems = reviewItems)
    }

    /**
     * Marks any left-over IMPORTING tasks from an unexpected app kill/reboot as INTERRUPTED
     * and cleans up leftover temporary `.part` files.
     */
    suspend fun recoverInterruptedImports() = withContext(Dispatchers.IO) {
        val stuckDocs = documentDao.getDocumentsByImportStatus(ImportStatus.IMPORTING.name)
        val now = System.currentTimeMillis()
        stuckDocs.forEach { entity ->
            storage.getStagingPdfFile(entity.id).delete()
            storage.getReplacementStagingFile(entity.id).delete()
            documentDao.updateImportProgress(
                documentId = entity.id,
                importStatus = ImportStatus.INTERRUPTED.name,
                processingStatus = DocumentStatus.PAUSED.name,
                indexedPagesCount = entity.indexedPagesCount,
                importProgressPercent = entity.importProgressPercent,
                lastModifiedMillis = now,
                errorMessageAr = "انقطع استيراد الملف قبل اكتمال الحفظ أو فهرسة الصفحات. يمكنك استبدال الملف أو حذفه.",
            )
        }
    }

    /**
     * Resets any pages stuck in RUNNING OCR status back to PENDING after an app kill or reboot,
     * preserving all completed DONE pages and their persisted OcrResult rows.
     */
    suspend fun recoverInterruptedOcr() = withContext(Dispatchers.IO) {
        pageDao.resetRunningOcrToPending()
    }

    suspend fun prepareTesseractAssets(context: Context): TrainedDataInstallResult =
        withContext(Dispatchers.IO) {
            TesseractDataInstaller.ensureArabicTrainedData(context)
        }

    suspend fun checkOcrEngineAvailability(
        context: Context,
        engineType: OcrEngineType,
    ): OcrAvailabilityResult = withContext(Dispatchers.IO) {
        val registry = ocrEngineRegistry ?: OcrEngineRegistry.createDefault(context)
        registry.getAdapter(engineType).checkAvailability()
    }

    suspend fun getPagesNeedingReprocessing(documentId: String): List<PageEntity> =
        withContext(Dispatchers.IO) {
            pageDao.getPagesNeedingReprocessing(documentId)
        }

    fun observeCountPagesNeedingReprocessing(documentId: String): Flow<Int> =
        pageDao.observeCountPagesNeedingReprocessing(documentId)

    suspend fun detectSuspiciousPages(document: DocumentRecord): List<PageRecord> =
        withContext(Dispatchers.IO) {
            val excludedRepairedIds = document.pages
                .filter { isPageGeminiRepaired(it.pageId) }
                .mapTo(mutableSetOf()) { it.pageId }
            detectSuspiciousPagesUseCase.detect(
                pages = document.pages,
                excludedPageIds = excludedRepairedIds,
                isVisuallyEmpty = { page -> isPageVisuallyEmpty(document, page) },
            )
        }

    /**
     * Loops ONLY through detected suspicious pages (abnormally short OCR text compared to document average),
     * sends their page images to Gemini for full text extraction (ignoring the 150-char limit),
     * overwrites the OCR text in Room, and refreshes any existing comparison items.
     */
    suspend fun fixSuspiciousPagesWithGemini(
        documentId: String,
        onProgress: suspend (GeminiExecutionProgress) -> Unit = {},
    ): GeminiRunOutcome = withContext(Dispatchers.IO) {
        val coordinator = geminiCoordinator
            ?: return@withContext GeminiRunOutcome.Failure("منسق Gemini غير متوفر في هذا التكوين.")

        val doc = getDocumentById(documentId)
            ?: return@withContext GeminiRunOutcome.Failure("المستند المطلوب غير موجود.")

        val privateFile = storage.resolvePrivatePdfIfExists(doc.internalStorageRef)
            ?: return@withContext GeminiRunOutcome.Failure("تعذر الوصول إلى ملف PDF في التخزين الخاص.")

        val suspiciousPages = detectSuspiciousPages(doc)
        if (suspiciousPages.isEmpty()) {
            return@withContext GeminiRunOutcome.Completed(
                analyzedNow = 0,
                totalAnalyzedPages = 0,
                failedPages = 0,
                totalEligiblePages = 0,
                lastMessageAr = "لا توجد صفحات ناقصة النص تحتاج إلى إصلاح عبر Gemini.",
            )
        }

        val hasEnabledKey = geminiKeyVault?.listKeys()?.any { it.isEnabled } ?: true
        if (!hasEnabledKey) {
            return@withContext GeminiRunOutcome.Failure(
                "لا يوجد مفتاح Gemini API مفعّل حالياً. أضف أو فعّل مفتاحاً من الإعدادات.",
            )
        }

        var fixedNow = 0
        var failedCount = 0
        var lastErrorMsg: String? = null

        for ((idx, page) in suspiciousPages.withIndex()) {
            currentCoroutineContext().ensureActive()
            var rawBitmap: Bitmap? = null
            try {
                rawBitmap = pageEngine.renderFromFile(
                    pdfFile = privateFile,
                    pageIndex = (page.originalPageNumber - 1).coerceAtLeast(0),
                    maxWidthPx = 1600,
                )
                if (rawBitmap == null) {
                    failedCount++
                    lastErrorMsg = "تعذر تصيير صورة الصفحة ${page.originalPageNumber} لإرسالها إلى Gemini."
                } else if (isVisuallyEmptyBitmap(rawBitmap)) {
                    cachePageVisuallyEmpty(page.pageId, true)
                } else {
                    cachePageVisuallyEmpty(page.pageId, false)
                    val extractionResult = coordinator.extractTextFallbackWithFailover(
                        pageNumber = page.originalPageNumber,
                        rawPageBitmap = rawBitmap,
                    )
                    extractionResult.fold(
                        onSuccess = { extractedText ->
                            val cleaned = extractedText.trim()
                            val now = System.currentTimeMillis()
                            val existingOcr = ocrResultDao.getOcrResultForPage(page.pageId)
                            val updatedEntity = if (existingOcr != null) {
                                existingOcr.copy(
                                    recognizedText = cleaned,
                                    recognizedAtMillis = now,
                                )
                            } else {
                                OcrResultEntity(
                                    pageId = page.pageId,
                                    documentId = documentId,
                                    pageNumber = page.originalPageNumber,
                                    engineType = (page.ocrEngineUsed ?: OcrEngineType.TESSERACT).name,
                                    recognizedText = cleaned,
                                    segmentsJson = "[]",
                                    confidence = null,
                                    imageWidthPx = rawBitmap.width,
                                    imageHeightPx = rawBitmap.height,
                                    recognizedAtMillis = now,
                                )
                            }
                            ocrResultDao.upsertOcrResult(updatedEntity)
                            pageDao.updatePageOcrState(
                                pageId = page.pageId,
                                pageStatus = if (page.geminiAnalysis != null) PageStatus.ANALYZED.name else PageStatus.OCR_DONE.name,
                                ocrStatus = OcrPageStatus.DONE.name,
                                ocrEngineUsed = page.ocrEngineUsed?.name ?: updatedEntity.engineType,
                                ocrErrorMessageAr = null,
                                ocrUpdatedAtMillis = now,
                            )
                            markPageGeminiRepaired(page.pageId, true)
                            if (geminiAnalysisDao.getAnalysisForPage(page.pageId) != null) {
                                comparisonEngine.compareAndPersistForPage(
                                    documentId = documentId,
                                    pageId = page.pageId,
                                    autoAcceptHighConfidence = smartReviewPrefs?.isAutoAcceptHighConfidenceEnabled() == true,
                                )
                            }
                            fixedNow++
                        },
                        onFailure = { throwable ->
                            if (throwable is CancellationException) throw throwable
                            failedCount++
                            lastErrorMsg = throwable.localizedMessage?.takeIf { it.isNotBlank() }
                                ?: "تعذّر استخراج نص الصفحة ${page.originalPageNumber} عبر Gemini."
                        },
                    )
                }
            } finally {
                if (rawBitmap != null && !rawBitmap.isRecycled) {
                    rawBitmap.recycle()
                }
            }

            onProgress(
                GeminiExecutionProgress(
                    documentId = documentId,
                    currentPageNumber = page.originalPageNumber,
                    analyzedPages = fixedNow,
                    failedPages = failedCount,
                    totalTargetPages = suspiciousPages.size,
                ),
            )

            val hasUsableKey = geminiKeyVault?.getNextEnabledKey() != null
            if (!hasUsableKey && idx < suspiciousPages.lastIndex && failedCount > 0) {
                lastErrorMsg = lastErrorMsg ?: "توقفت المعالجة لعدم توفر مفتاح Gemini مفعّل."
                break
            }
        }

        val summaryAr = when {
            fixedNow > 0 && failedCount == 0 ->
                "تم إصلاح $fixedNow صفحة ناقصة تلقائياً عبر Gemini وتحديث نصوصها في قاعدة البيانات."
            fixedNow > 0 && failedCount > 0 ->
                "تم إصلاح $fixedNow صفحة، وتعذّر إصلاح $failedCount صفحة: ${lastErrorMsg.orEmpty()}"
            else ->
                lastErrorMsg ?: "تعذّر إصلاح الصفحات الناقصة عبر Gemini."
        }

        return@withContext GeminiRunOutcome.Completed(
            analyzedNow = fixedNow,
            totalAnalyzedPages = fixedNow,
            failedPages = failedCount,
            totalEligiblePages = suspiciousPages.size,
            lastMessageAr = summaryAr,
        )
    }

    suspend fun getPagesWithMissingContent(documentId: String): List<PageRecord> =
        withContext(Dispatchers.IO) {
            val ocrByPageId = ocrResultDao.getOcrResultsForDocument(documentId).associateBy { it.pageId }
            val geminiByPageId = geminiAnalysisDao.getAnalysesForDocument(documentId).associateBy { it.pageId }
            pageDao.getPagesWithMissingContent(documentId).map { entity ->
                entity.toDomain(
                    ocrResultEntity = ocrByPageId[entity.pageId],
                    geminiAnalysisEntity = geminiByPageId[entity.pageId],
                )
            }
        }

    fun observePagesWithMissingContent(documentId: String): Flow<List<PageRecord>> =
        pageDao.observePagesWithMissingContent(documentId).map { entities ->
            entities.map { it.toDomain() }
        }

    /**
     * Forces full Gemini text extraction for a single page flagged as missing content,
     * bypassing the review queue completely:
     * - Overwrites recognizedText in Room
     * - Sets hasMissingContent = false and marks the page REVIEWED
     * - Deletes ALL existing ReviewItemEntity records for this page
     * - Does NOT run the comparison engine again for this page
     */
    suspend fun forceGeminiExtractionForPage(
        documentId: String,
        pageNumber: Int,
    ): Result<String> = withContext(Dispatchers.IO) {
        val coordinator = geminiCoordinator
            ?: return@withContext Result.failure(IllegalStateException("منسق Gemini غير متوفر في هذا التكوين."))

        val doc = getDocumentById(documentId)
            ?: return@withContext Result.failure(IllegalStateException("المستند المطلوب غير موجود."))

        val page = doc.pages.firstOrNull { it.originalPageNumber == pageNumber }
            ?: return@withContext Result.failure(IllegalStateException("الصفحة رقم $pageNumber غير موجودة في المستند."))

        val privateFile = storage.resolvePrivatePdfIfExists(doc.internalStorageRef)
            ?: return@withContext Result.failure(IllegalStateException("تعذر الوصول إلى ملف PDF في التخزين الخاص."))

        val hasEnabledKey = geminiKeyVault?.listKeys()?.any { it.isEnabled } ?: true
        if (!hasEnabledKey) {
            return@withContext Result.failure(
                IllegalStateException("لا يوجد مفتاح Gemini API مفعّل حالياً. أضف أو فعّل مفتاحاً من الإعدادات."),
            )
        }

        var rawBitmap: Bitmap? = null
        try {
            rawBitmap = pageEngine.renderFromFile(
                pdfFile = privateFile,
                pageIndex = (pageNumber - 1).coerceAtLeast(0),
                maxWidthPx = 1600,
            )
            if (rawBitmap == null) {
                return@withContext Result.failure(
                    IllegalStateException("تعذر تصيير صورة الصفحة $pageNumber لإرسالها إلى Gemini."),
                )
            }

            val extractionResult = coordinator.extractTextFallbackWithFailover(
                pageNumber = pageNumber,
                rawPageBitmap = rawBitmap,
            )
            val extractedText = extractionResult.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                return@withContext Result.failure(throwable)
            }.trim()

            val now = System.currentTimeMillis()
            val existingOcr = ocrResultDao.getOcrResultForPage(page.pageId)
            val updatedOcrEntity = if (existingOcr != null) {
                existingOcr.copy(
                    recognizedText = extractedText,
                    segmentsJson = "[]",
                    recognizedAtMillis = now,
                )
            } else {
                OcrResultEntity(
                    pageId = page.pageId,
                    documentId = documentId,
                    pageNumber = pageNumber,
                    engineType = (page.ocrEngineUsed ?: OcrEngineType.TESSERACT).name,
                    recognizedText = extractedText,
                    segmentsJson = "[]",
                    confidence = null,
                    imageWidthPx = rawBitmap.width,
                    imageHeightPx = rawBitmap.height,
                    recognizedAtMillis = now,
                )
            }

            ocrResultDao.upsertOcrResult(updatedOcrEntity)
            reviewItemDao.deleteAllForPage(page.pageId)
            pageDao.updatePageMissingContent(page.pageId, false)
            pageDao.updatePageOcrState(
                pageId = page.pageId,
                pageStatus = PageStatus.REVIEWED.name,
                ocrStatus = OcrPageStatus.DONE.name,
                ocrEngineUsed = page.ocrEngineUsed?.name ?: updatedOcrEntity.engineType,
                ocrErrorMessageAr = null,
                ocrUpdatedAtMillis = now,
            )
            pageDao.updatePageFailureState(page.pageId, PageStatus.REVIEWED.name, null)
            markPageGeminiRepaired(page.pageId, true)

            if (doc.status == DocumentStatus.NEEDS_REVIEW && doc.reviewApprovedAt == null) {
                val countsAfter = reviewItemDao.countByStatus(documentId, includeAgreements = false)
                val hasPending = (countsAfter[ReviewStatus.PENDING] ?: 0) > 0 ||
                    (countsAfter[ReviewStatus.STALE] ?: 0) > 0
                if (!hasPending) {
                    documentDao.updateProcessingProgress(
                        documentId = documentId,
                        processingStatus = DocumentStatus.PROCESSING.name,
                        processingProgressPercent = doc.processingProgressPercent,
                        lastModifiedMillis = now,
                        errorMessageAr = null,
                    )
                }
            }

            Result.success(extractedText)
        } finally {
            if (rawBitmap != null && !rawBitmap.isRecycled) {
                rawBitmap.recycle()
            }
        }
    }

    suspend fun reprocessFailedPages(
        context: Context,
        documentId: String,
        engineType: OcrEngineType,
        onProgress: suspend (OcrExecutionProgress) -> Unit = {},
    ): OcrRunOutcome = runDocumentOcr(
        context = context,
        documentId = documentId,
        engineType = engineType,
        reprocessFailedAndShortOnly = true,
        onProgress = onProgress,
    )

    /**
     * Executes bounded, resumable on-device OCR across document pages.
     * - Normal run/resume processes only PENDING (or reset RUNNING) pages within [startPage]..[endPage], skipping DONE pages.
     * - When [retryFailedOnly] is true, retries only FAILED pages within [startPage]..[endPage] without overwriting DONE pages.
     * - When [reprocessFailedAndShortOnly] is true, reprocesses ONLY pages that failed or returned < 150 chars.
     * - When [singlePageId] is non-null, processes only that specific page.
     */
    suspend fun runDocumentOcr(
        context: Context,
        documentId: String,
        engineType: OcrEngineType,
        retryFailedOnly: Boolean = false,
        singlePageId: String? = null,
        startPage: Int = 1,
        endPage: Int = Int.MAX_VALUE,
        reprocessFailedAndShortOnly: Boolean = false,
        onProgress: suspend (OcrExecutionProgress) -> Unit = {},
    ): OcrRunOutcome = withContext(Dispatchers.IO) {
        val doc = documentDao.getDocumentById(documentId)
            ?: return@withContext OcrRunOutcome.Failure("المستند المطلوب غير موجود في قاعدة البيانات.")

        val privateFile = storage.resolvePrivatePdfIfExists(doc.internalStoragePath)
            ?: return@withContext OcrRunOutcome.Failure("تعذر العثور على ملف PDF المحفوظ محلياً في التخزين الخاص.")

        val registry = ocrEngineRegistry ?: OcrEngineRegistry.createDefault(context)
        val adapter = registry.getAdapter(engineType)

        when (val availability = adapter.checkAvailability()) {
            is OcrAvailabilityResult.Unavailable -> {
                return@withContext OcrRunOutcome.Failure(availability.reasonAr)
            }
            is OcrAvailabilityResult.LimitedCapability,
            OcrAvailabilityResult.Ready -> Unit
        }

        pageDao.resetDocumentRunningOcrToPending(documentId)
        val allPages = pageDao.getPagesForDocument(documentId)
        if (allPages.isEmpty()) {
            return@withContext OcrRunOutcome.Failure("لا توجد صفحات مفهرسة لهذا المستند.")
        }

        val effectiveStartPage = startPage.coerceAtLeast(1)
        val effectiveEndPage = endPage.coerceAtLeast(effectiveStartPage)
        val targetPages = when {
            singlePageId != null -> allPages.filter { it.pageId == singlePageId }
            reprocessFailedAndShortOnly -> pageDao.getPagesNeedingReprocessing(documentId)
            retryFailedOnly -> allPages.filter {
                it.ocrStatus == OcrPageStatus.FAILED.name &&
                    it.originalPageNumber in effectiveStartPage..effectiveEndPage
            }
            else -> allPages.filter {
                it.originalPageNumber in effectiveStartPage..effectiveEndPage &&
                    (it.ocrStatus == OcrPageStatus.PENDING.name || it.ocrStatus == OcrPageStatus.RUNNING.name)
            }
        }

        val totalPages = allPages.size
        var doneCount = allPages.count { it.ocrStatus == OcrPageStatus.DONE.name }
        var failedCount = allPages.count { it.ocrStatus == OcrPageStatus.FAILED.name }

        if (targetPages.isEmpty()) {
            return@withContext OcrRunOutcome.Completed(
                processedNow = 0,
                totalDonePages = doneCount,
                totalFailedPages = failedCount,
                totalPages = totalPages,
            )
        }

        val batchSize = (preferencesDao.getPreferences()?.batchSizePages ?: 10).coerceAtLeast(1)
        var processedInRun = 0

        documentDao.updateProcessingProgress(
            documentId = documentId,
            processingStatus = DocumentStatus.EXTRACTING_TEXT.name,
            processingProgressPercent = (doneCount * 100) / totalPages,
            lastModifiedMillis = System.currentTimeMillis(),
            errorMessageAr = null,
        )

        try {
            for (batch in targetPages.chunked(batchSize)) {
                currentCoroutineContext().ensureActive()
                for (page in batch) {
                    currentCoroutineContext().ensureActive()

                    val wasPreviouslyDone = page.ocrStatus == OcrPageStatus.DONE.name
                    val wasPreviouslyFailed = page.ocrStatus == OcrPageStatus.FAILED.name

                    pageDao.updatePageOcrState(
                        pageId = page.pageId,
                        pageStatus = page.pageStatus,
                        ocrStatus = OcrPageStatus.RUNNING.name,
                        ocrEngineUsed = engineType.name,
                        ocrErrorMessageAr = null,
                        ocrUpdatedAtMillis = System.currentTimeMillis(),
                    )

                    var rawBitmap: Bitmap? = null
                    var prepared: OcrImagePreprocessor.PreparedOcrBitmap? = null
                    try {
                        rawBitmap = pageEngine.renderFromFile(
                            pdfFile = privateFile,
                            pageIndex = (page.originalPageNumber - 1).coerceAtLeast(0),
                            maxWidthPx = 1600,
                        )
                        if (rawBitmap == null) {
                            val errAr = "تعذر تصيير صفحة PDF رقم ${page.originalPageNumber} لمعالجتها بالـ OCR."
                            pageDao.updatePageOcrState(
                                pageId = page.pageId,
                                pageStatus = PageStatus.FAILED.name,
                                ocrStatus = OcrPageStatus.FAILED.name,
                                ocrEngineUsed = engineType.name,
                                ocrErrorMessageAr = errAr,
                                ocrUpdatedAtMillis = System.currentTimeMillis(),
                            )
                            if (wasPreviouslyDone) doneCount = (doneCount - 1).coerceAtLeast(0)
                            if (!wasPreviouslyFailed) failedCount++
                        } else {
                            prepared = OcrImagePreprocessor.prepareForOcr(rawBitmap)
                            val result = adapter.recognizePage(
                                documentId = documentId,
                                pageId = page.pageId,
                                pageNumber = page.originalPageNumber,
                                pageBitmap = prepared.bitmap,
                            )
                            result.fold(
                                onSuccess = { ocrResult ->
                                    val isBlank = isVisuallyEmptyBitmap(rawBitmap)
                                    cachePageVisuallyEmpty(page.pageId, isBlank)
                                    markPageGeminiRepaired(page.pageId, false)

                                    val trimmedOcr = ocrResult.recognizedText.trim()
                                    val needsFallback = trimmedOcr.isEmpty() ||
                                        (trimmedOcr.length < MIN_OCR_FALLBACK_CHAR_THRESHOLD && !isBlank)

                                    var usedGeminiFallback = false
                                    val finalOcrResult = if (needsFallback && geminiCoordinator != null) {
                                        val fallbackText = geminiCoordinator.extractTextFallbackWithFailover(
                                            pageNumber = page.originalPageNumber,
                                            rawPageBitmap = rawBitmap,
                                        ).getOrNull()?.trim()

                                        if (!fallbackText.isNullOrEmpty() && fallbackText.length > trimmedOcr.length) {
                                            usedGeminiFallback = true
                                            ocrResult.copy(recognizedText = fallbackText)
                                        } else {
                                            ocrResult
                                        }
                                    } else {
                                        ocrResult
                                    }

                                    if (usedGeminiFallback) {
                                        markPageGeminiRepaired(page.pageId, true)
                                    }
                                    ocrResultDao.upsertOcrResult(finalOcrResult.toEntity())
                                    pageDao.updatePageOcrState(
                                        pageId = page.pageId,
                                        pageStatus = PageStatus.OCR_DONE.name,
                                        ocrStatus = OcrPageStatus.DONE.name,
                                        ocrEngineUsed = engineType.name,
                                        ocrErrorMessageAr = null,
                                        ocrUpdatedAtMillis = System.currentTimeMillis(),
                                    )
                                    if (geminiAnalysisDao.getAnalysisForPage(page.pageId) != null) {
                                        comparisonEngine.compareAndPersistForPage(
                                            documentId = documentId,
                                            pageId = page.pageId,
                                            autoAcceptHighConfidence = smartReviewPrefs?.isAutoAcceptHighConfidenceEnabled() == true,
                                        )
                                    }
                                    if (!wasPreviouslyDone) doneCount++
                                    if (wasPreviouslyFailed) failedCount = (failedCount - 1).coerceAtLeast(0)
                                },
                                onFailure = { throwable ->
                                    if (throwable is CancellationException) throw throwable
                                    val errAr = throwable.localizedMessage?.takeIf { it.isNotBlank() }
                                        ?: "تعذر استخراج النص من الصفحة رقم ${page.originalPageNumber}."
                                    pageDao.updatePageOcrState(
                                        pageId = page.pageId,
                                        pageStatus = PageStatus.FAILED.name,
                                        ocrStatus = OcrPageStatus.FAILED.name,
                                        ocrEngineUsed = engineType.name,
                                        ocrErrorMessageAr = errAr,
                                        ocrUpdatedAtMillis = System.currentTimeMillis(),
                                    )
                                    if (wasPreviouslyDone) doneCount = (doneCount - 1).coerceAtLeast(0)
                                    if (!wasPreviouslyFailed) failedCount++
                                },
                            )
                        }
                    } finally {
                        prepared?.recycleSafely()
                        if (rawBitmap != null && !rawBitmap.isRecycled) {
                            rawBitmap.recycle()
                        }
                    }

                    processedInRun++
                    val pct = ((doneCount * 100) / totalPages).coerceIn(0, 100)
                    documentDao.updateProcessingProgress(
                        documentId = documentId,
                        processingStatus = DocumentStatus.EXTRACTING_TEXT.name,
                        processingProgressPercent = pct,
                        lastModifiedMillis = System.currentTimeMillis(),
                        errorMessageAr = null,
                    )
                    onProgress(
                        OcrExecutionProgress(
                            documentId = documentId,
                            currentPageNumber = page.originalPageNumber,
                            completedPages = doneCount,
                            failedPages = failedCount,
                            totalPages = totalPages,
                            engineType = engineType,
                        ),
                    )
                }
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                pageDao.resetDocumentRunningOcrToPending(documentId)
                val updatedPages = pageDao.getPagesForDocument(documentId)
                val finalDone = updatedPages.count { it.ocrStatus == OcrPageStatus.DONE.name }
                val pct = ((finalDone * 100) / totalPages.coerceAtLeast(1)).coerceIn(0, 100)
                documentDao.updateProcessingProgress(
                    documentId = documentId,
                    processingStatus = DocumentStatus.PAUSED.name,
                    processingProgressPercent = pct,
                    lastModifiedMillis = System.currentTimeMillis(),
                    errorMessageAr = null,
                )
            }
            throw cancel
        }

        val finalPages = pageDao.getPagesForDocument(documentId)
        val finalDone = finalPages.count { it.ocrStatus == OcrPageStatus.DONE.name }
        val finalFailed = finalPages.count { it.ocrStatus == OcrPageStatus.FAILED.name }
        val finalPct = ((finalDone * 100) / totalPages.coerceAtLeast(1)).coerceIn(0, 100)

        val finalDocStatus = when {
            finalFailed > 0 && finalDone == 0 -> DocumentStatus.FAILED
            finalFailed > 0 || finalDone < totalPages -> DocumentStatus.PAUSED
            else -> DocumentStatus.PROCESSING
        }
        val finalErr = if (finalFailed > 0) {
            "تعذر استخراج النص في $finalFailed صفحة من أصل $totalPages. يمكنك إعادة محاولة الصفحات الفاشلة فقط."
        } else {
            null
        }

        documentDao.updateProcessingProgress(
            documentId = documentId,
            processingStatus = finalDocStatus.name,
            processingProgressPercent = finalPct,
            lastModifiedMillis = System.currentTimeMillis(),
            errorMessageAr = finalErr,
        )

        return@withContext OcrRunOutcome.Completed(
            processedNow = processedInRun,
            totalDonePages = finalDone,
            totalFailedPages = finalFailed,
            totalPages = totalPages,
        )
    }

    /**
     * Imports a PDF from SAF URI into application-private storage using streaming I/O,
     * validates PDF structure, indexes all pages in batches without artificial page limits,
     * and persists everything in Room.
     *
     * When [replaceDocumentId] is non-null, validates and stages the replacement first so the
     * existing document is never lost if replacement fails.
     */
    suspend fun importPdfFromUri(
        context: Context,
        uri: Uri,
        replaceDocumentId: String? = null,
        onProgress: suspend (ImportProgressUpdate) -> Unit = {},
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val isReplacement = replaceDocumentId != null
        val existingDoc = if (replaceDocumentId != null) {
            documentDao.getDocumentById(replaceDocumentId)
        } else {
            null
        }

        val documentId = existingDoc?.id ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val meta = PdfInspector.querySourceMetadata(context, uri)

        if (!isReplacement) {
            val initialEntity = DocumentEntity(
                id = documentId,
                originalFileName = meta.fileName,
                internalStoragePath = "",
                fileSizeBytes = meta.declaredSizeBytes.coerceAtLeast(0L),
                importDateMillis = now,
                lastModifiedMillis = now,
                pageCount = null,
                indexedPagesCount = 0,
                processingStatus = DocumentStatus.PROCESSING.name,
                importStatus = ImportStatus.IMPORTING.name,
                importProgressPercent = 5,
                processingProgressPercent = 0,
                isValidationPassed = false,
                validationNote = null,
                errorMessageAr = null,
            )
            documentDao.upsertDocument(initialEntity)
        }

        onProgress(
            ImportProgressUpdate(
                documentId = documentId,
                phaseLabelAr = "جارٍ نسخ ملف PDF إلى التخزين الخاص للتطبيق...",
                progressPercent = 10,
            ),
        )

        val stagingFile = if (isReplacement) {
            storage.getReplacementStagingFile(documentId)
        } else {
            storage.getStagingPdfFile(documentId)
        }

        val copyResult = try {
            resolver.openInputStream(uri)?.use { stream ->
                storage.streamCopyToStaging(
                    inputStream = stream,
                    stagingFile = stagingFile,
                    expectedBytes = meta.declaredSizeBytes,
                    onBytesCopied = { copiedBytes ->
                        if (meta.declaredSizeBytes > 0L) {
                            val pct = (10 + ((copiedBytes * 35) / meta.declaredSizeBytes).toInt())
                                .coerceIn(10, 45)
                            onProgress(
                                ImportProgressUpdate(
                                    documentId = documentId,
                                    phaseLabelAr = "جارٍ حفظ النسخة الخاصة من الملف...",
                                    progressPercent = pct,
                                ),
                            )
                        }
                    },
                )
            } ?: StreamCopyResult.Failure("تعذر فتح ملف PDF المحدد للقراءة.")
        } catch (e: CancellationException) {
            stagingFile.delete()
            if (!isReplacement) {
                documentDao.updateImportProgress(
                    documentId = documentId,
                    importStatus = ImportStatus.INTERRUPTED.name,
                    processingStatus = DocumentStatus.PAUSED.name,
                    indexedPagesCount = 0,
                    importProgressPercent = 15,
                    lastModifiedMillis = System.currentTimeMillis(),
                    errorMessageAr = "تم إيقاف عملية استيراد الملف قبل اكتمالها.",
                )
            }
            throw e
        } catch (e: SecurityException) {
            stagingFile.delete()
            StreamCopyResult.Failure("لا توجد صلاحية لقراءة الملف المحدد: ${e.localizedMessage ?: "مرفوض"}")
        } catch (e: IOException) {
            stagingFile.delete()
            StreamCopyResult.Failure("تعذرت قراءة الملف أثناء الاستيراد: ${e.localizedMessage ?: "خطأ إدخال/إخراج"}")
        }

        val copiedFileSize: Long
        when (copyResult) {
            is StreamCopyResult.Failure -> {
                if (!isReplacement) {
                    val failImportStatus = if (copyResult.isInterrupted) ImportStatus.INTERRUPTED else ImportStatus.FAILED
                    val failProcStatus = if (copyResult.isInterrupted) DocumentStatus.PAUSED else DocumentStatus.FAILED
                    documentDao.updateImportProgress(
                        documentId = documentId,
                        importStatus = failImportStatus.name,
                        processingStatus = failProcStatus.name,
                        indexedPagesCount = 0,
                        importProgressPercent = 0,
                        lastModifiedMillis = System.currentTimeMillis(),
                        errorMessageAr = copyResult.reasonAr,
                    )
                }
                return@withContext ImportOutcome.Failure(
                    reasonAr = copyResult.reasonAr,
                    documentId = if (isReplacement) null else documentId,
                    wasInterrupted = copyResult.isInterrupted,
                )
            }
            is StreamCopyResult.Success -> {
                copiedFileSize = copyResult.bytesWritten
            }
        }

        onProgress(
            ImportProgressUpdate(
                documentId = documentId,
                phaseLabelAr = "جارٍ التحقق من سلامة الصفحات وفهرستها...",
                progressPercent = 50,
            ),
        )

        val indexedEntities = mutableListOf<PageEntity>()
        val indexResult = try {
            pageEngine.indexPages(
                pdfFile = stagingFile,
                batchSize = 25,
                onBatchIndexed = { indexedCount, totalPages, batch ->
                    val pct = (50 + ((indexedCount * 45) / totalPages.coerceAtLeast(1)))
                        .coerceIn(50, 95)
                    batch.forEach { pageMeta ->
                        indexedEntities.add(
                            PageEntity(
                                pageId = "${documentId}_page_${pageMeta.pageNumber}",
                                documentId = documentId,
                                originalPageNumber = pageMeta.pageNumber,
                                widthPoints = pageMeta.widthPoints,
                                heightPoints = pageMeta.heightPoints,
                                renderingReference = "$documentId:${pageMeta.pageNumber - 1}",
                                thumbnailCachePath = null,
                                pageStatus = PageStatus.PENDING.name,
                            ),
                        )
                    }
                    onProgress(
                        ImportProgressUpdate(
                            documentId = documentId,
                            phaseLabelAr = "فهرسة الصفحات: $indexedCount من $totalPages",
                            progressPercent = pct,
                        ),
                    )
                },
            )
        } catch (e: CancellationException) {
            stagingFile.delete()
            if (!isReplacement) {
                documentDao.updateImportProgress(
                    documentId = documentId,
                    importStatus = ImportStatus.INTERRUPTED.name,
                    processingStatus = DocumentStatus.PAUSED.name,
                    indexedPagesCount = indexedEntities.size,
                    importProgressPercent = 55,
                    lastModifiedMillis = System.currentTimeMillis(),
                    errorMessageAr = "توقفت فهرسة الصفحات قبل اكتمال الاستيراد.",
                )
            }
            throw e
        }

        when (indexResult) {
            is PrivatePdfIndexResult.Failure -> {
                stagingFile.delete()
                if (!isReplacement) {
                    documentDao.updateImportProgress(
                        documentId = documentId,
                        importStatus = ImportStatus.FAILED.name,
                        processingStatus = DocumentStatus.FAILED.name,
                        indexedPagesCount = 0,
                        importProgressPercent = 0,
                        lastModifiedMillis = System.currentTimeMillis(),
                        errorMessageAr = indexResult.reasonAr,
                    )
                }
                return@withContext ImportOutcome.Failure(
                    reasonAr = indexResult.reasonAr,
                    documentId = if (isReplacement) null else documentId,
                )
            }
            is PrivatePdfIndexResult.Success -> {
                // Promote staging file to private original.pdf atomically
                val finalPrivateFile = storage.promoteStagingToPrivatePdf(documentId, stagingFile)
                if (finalPrivateFile == null) {
                    stagingFile.delete()
                    val storageErr = "تعذر تثبيت النسخة النهائية للملف في التخزين الخاص."
                    if (!isReplacement) {
                        documentDao.updateImportProgress(
                            documentId = documentId,
                            importStatus = ImportStatus.FAILED.name,
                            processingStatus = DocumentStatus.FAILED.name,
                            indexedPagesCount = 0,
                            importProgressPercent = 0,
                            lastModifiedMillis = System.currentTimeMillis(),
                            errorMessageAr = storageErr,
                        )
                    }
                    return@withContext ImportOutcome.Failure(
                        reasonAr = storageErr,
                        documentId = if (isReplacement) null else documentId,
                    )
                }

                // Clear old thumbnails on replacement and rewrite page entities cleanly
                if (isReplacement) {
                    PdfInspector.clearMemoryCache()
                }
                val finalPages = indexedEntities.map { entity ->
                    entity.copy(renderingReference = finalPrivateFile.absolutePath)
                }
                val completedTime = System.currentTimeMillis()
                val persistedDoc = DocumentEntity(
                    id = documentId,
                    originalFileName = meta.fileName,
                    internalStoragePath = finalPrivateFile.absolutePath,
                    fileSizeBytes = copiedFileSize,
                    importDateMillis = existingDoc?.importDateMillis ?: now,
                    lastModifiedMillis = completedTime,
                    pageCount = indexResult.pageCount,
                    indexedPagesCount = finalPages.size,
                    processingStatus = DocumentStatus.NEW.name,
                    importStatus = ImportStatus.READY_FOR_PROCESSING.name,
                    importProgressPercent = 100,
                    processingProgressPercent = 0,
                    isValidationPassed = true,
                    validationNote = "محفوظ محلياً في التخزين الخاص للتطبيق ومفهرس بالكامل (${indexResult.pageCount} صفحة).",
                    errorMessageAr = null,
                )

                documentDao.upsertDocument(persistedDoc)
                pageDao.replaceDocumentPages(documentId, finalPages)

                onProgress(
                    ImportProgressUpdate(
                        documentId = documentId,
                        phaseLabelAr = "اكتمل الاستيراد والفهرسة بنجاح",
                        progressPercent = 100,
                    ),
                )

                return@withContext ImportOutcome.Success(
                    persistedDoc.toDomain(pages = finalPages.map { it.toDomain() }),
                )
            }
        }
    }

    suspend fun renderDocumentPage(
        context: Context,
        document: DocumentRecord,
        pageIndex: Int,
        maxWidthPx: Int = 1080,
    ): Bitmap? {
        val privateFile = storage.resolvePrivatePdfIfExists(document.internalStorageRef)
        val ref = privateFile?.absolutePath ?: document.originalFileRef
        return PdfInspector.renderPage(context, ref, pageIndex, maxWidthPx, pageEngine)
    }

    suspend fun getOrGeneratePageThumbnail(
        document: DocumentRecord,
        pageNumber: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val privateFile = storage.resolvePrivatePdfIfExists(document.internalStorageRef)
            ?: return@withContext null
        val thumbFile = storage.getThumbnailFile(document.id, pageNumber)
        val bitmap = PdfInspector.getOrGenerateThumbnail(
            privatePdfFile = privateFile,
            thumbnailFile = thumbFile,
            pageIndex = (pageNumber - 1).coerceAtLeast(0),
            pageEngine = pageEngine,
        )
        if (bitmap != null && thumbFile.exists()) {
            pageDao.updateThumbnailPath(
                pageId = "${document.id}_page_${pageNumber}",
                thumbnailPath = thumbFile.absolutePath,
            )
        }
        bitmap
    }

    suspend fun deleteDocument(documentId: String) = withContext(Dispatchers.IO) {
        storage.deleteDocumentFiles(documentId)
        PdfInspector.clearMemoryCache()
        documentDao.deleteDocumentById(documentId)
    }

    suspend fun updateOcrEngine(engine: OcrEngineType) = withContext(Dispatchers.IO) {
        val current = preferencesDao.getPreferences()?.toDomain() ?: ProcessingPreferences()
        preferencesDao.upsertPreferences(current.copy(preferredOcrEngine = engine).toEntity())
    }

    suspend fun updateExportMode(mode: ExportMode) = withContext(Dispatchers.IO) {
        val current = preferencesDao.getPreferences()?.toDomain() ?: ProcessingPreferences()
        preferencesDao.upsertPreferences(current.copy(preferredExportMode = mode).toEntity())
    }

    suspend fun updateHighlightUnresolved(enabled: Boolean) = withContext(Dispatchers.IO) {
        val current = preferencesDao.getPreferences()?.toDomain() ?: ProcessingPreferences()
        preferencesDao.upsertPreferences(current.copy(highlightUnresolvedInDocx = enabled).toEntity())
    }

    suspend fun updateAutoSaveCheckpoints(enabled: Boolean) = withContext(Dispatchers.IO) {
        val current = preferencesDao.getPreferences()?.toDomain() ?: ProcessingPreferences()
        preferencesDao.upsertPreferences(current.copy(autoSaveCheckpoints = enabled).toEntity())
    }

    suspend fun updateResumeAfterInterruption(enabled: Boolean) = withContext(Dispatchers.IO) {
        val current = preferencesDao.getPreferences()?.toDomain() ?: ProcessingPreferences()
        preferencesDao.upsertPreferences(current.copy(resumeAfterInterruption = enabled).toEntity())
    }

    suspend fun addGeminiKey(label: String, rawApiKey: String): Result<GeminiKeyRecord> =
        withContext(Dispatchers.IO) {
            val vault = geminiKeyVault
                ?: return@withContext Result.failure(IllegalStateException("مخزن المفاتيح المشفر غير مهيأ."))
            vault.addKey(label, rawApiKey)
        }

    suspend fun testGeminiKey(keyId: String): GeminiKeyTestOutcome = withContext(Dispatchers.IO) {
        val coordinator = geminiCoordinator
            ?: return@withContext GeminiKeyTestOutcome.Failed(null, "منسق Gemini غير مهيأ.")
        coordinator.testApiKey(keyId)
    }

    suspend fun setGeminiKeyEnabled(keyId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        geminiKeyVault?.setKeyEnabled(keyId, enabled)
    }

    suspend fun removeGeminiKey(keyId: String) = withContext(Dispatchers.IO) {
        geminiKeyVault?.removeKey(keyId)
    }

    suspend fun selectGeminiModel(modelId: String) = withContext(Dispatchers.IO) {
        geminiKeyVault?.setSelectedModelId(modelId)
    }

    /**
     * Runs per-page Gemini visual + OCR verification directly from the device against Google's API.
     * Processes OCR-completed pages (or a single specified page) one page per request with failover.
     */
    suspend fun runGeminiAnalysis(
        documentId: String,
        singlePageId: String? = null,
        startPage: Int = 1,
        endPage: Int = Int.MAX_VALUE,
        onProgress: suspend (GeminiExecutionProgress) -> Unit = {},
    ): GeminiRunOutcome = withContext(Dispatchers.IO) {
        val coordinator = geminiCoordinator
            ?: return@withContext GeminiRunOutcome.Failure("منسق Gemini غير متوفر في هذا التكوين.")

        val doc = getDocumentById(documentId)
            ?: return@withContext GeminiRunOutcome.Failure("المستند المطلوب غير موجود.")

        val privateFile = storage.resolvePrivatePdfIfExists(doc.internalStorageRef)
            ?: return@withContext GeminiRunOutcome.Failure("تعذر الوصول إلى ملف PDF في التخزين الخاص.")

        val eligiblePages = doc.pages.filter { it.ocrResult != null }
        if (eligiblePages.isEmpty()) {
            return@withContext GeminiRunOutcome.Failure("لا توجد صفحات مكتملة OCR بعد؛ شغّل استخراج النص أولاً.")
        }

        val effectiveStartPage = startPage.coerceAtLeast(1)
        val effectiveEndPage = endPage.coerceAtLeast(effectiveStartPage)
        val targetPages = if (singlePageId != null) {
            eligiblePages.filter { it.pageId == singlePageId }
        } else {
            eligiblePages.filter {
                it.geminiAnalysis == null &&
                    it.failureReason != PageFailureReasons.USER_SKIPPED &&
                    it.originalPageNumber in effectiveStartPage..effectiveEndPage
            }
        }

        val alreadyAnalyzedCount = eligiblePages.count { it.geminiAnalysis != null }
        if (targetPages.isEmpty()) {
            val isFilteredRange = singlePageId == null && (startPage > 1 || endPage < Int.MAX_VALUE)
            return@withContext GeminiRunOutcome.Completed(
                analyzedNow = 0,
                totalAnalyzedPages = alreadyAnalyzedCount,
                failedPages = 0,
                totalEligiblePages = eligiblePages.size,
                lastMessageAr = if (isFilteredRange) {
                    "لا توجد صفحات غير محللة ومكتملة OCR ضمن النطاق ($effectiveStartPage–$effectiveEndPage)."
                } else {
                    "جميع الصفحات الجاهزة ($alreadyAnalyzedCount) محللة مسبقاً عبر Gemini."
                },
            )
        }

        documentDao.updateProcessingProgress(
            documentId = documentId,
            processingStatus = DocumentStatus.ANALYZING_TEXT.name,
            processingProgressPercent = (alreadyAnalyzedCount * 100) / doc.pages.size.coerceAtLeast(1),
            lastModifiedMillis = System.currentTimeMillis(),
            errorMessageAr = null,
        )

        var analyzedNow = 0
        var failedCount = 0
        var lastErrorMsg: String? = null

        try {
            for ((idx, page) in targetPages.withIndex()) {
                currentCoroutineContext().ensureActive()
                var pageBitmap: Bitmap? = null
                try {
                    pageBitmap = pageEngine.renderFromFile(
                        pdfFile = privateFile,
                        pageIndex = (page.originalPageNumber - 1).coerceAtLeast(0),
                        maxWidthPx = 1280,
                    )
                    if (pageBitmap == null) {
                        failedCount++
                        lastErrorMsg = "تعذر تصيير صورة الصفحة ${page.originalPageNumber} لإرسالها إلى Gemini."
                        pageDao.updatePageFailureState(
                            pageId = page.pageId,
                            pageStatus = PageStatus.FAILED.name,
                            failureReason = PageFailureReasons.RENDER_ERROR,
                        )
                    } else {
                        when (val outcome = coordinator.analyzePageWithFailover(page, pageBitmap)) {
                            is GeminiPageAnalysisOutcome.Success -> {
                                geminiAnalysisDao.upsertAnalysis(outcome.record.toEntity())
                                pageDao.updatePageFailureState(
                                    pageId = page.pageId,
                                    pageStatus = PageStatus.ANALYZED.name,
                                    failureReason = null,
                                )
                                pageDao.updatePageMissingContent(
                                    pageId = page.pageId,
                                    hasMissingContent = outcome.record.isMissingLargeContent,
                                )
                                comparisonEngine.compareAndPersistForPage(
                                    documentId = documentId,
                                    pageId = page.pageId,
                                    autoAcceptHighConfidence = smartReviewPrefs?.isAutoAcceptHighConfidenceEnabled() == true,
                                )
                                analyzedNow++
                            }
                            is GeminiPageAnalysisOutcome.Failure -> {
                                failedCount++
                                lastErrorMsg = outcome.reasonAr
                                pageDao.updatePageFailureState(
                                    pageId = page.pageId,
                                    pageStatus = PageStatus.FAILED.name,
                                    failureReason = outcome.failureReason ?: PageFailureReasons.UNKNOWN_ERROR,
                                )
                            }
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (throwable: Throwable) {
                    val classified = GeminiErrorClassifier.classify(throwable)
                    val reasonCode = when (classified.kind) {
                        GeminiErrorKind.INVALID_KEY -> PageFailureReasons.INVALID_KEY
                        GeminiErrorKind.PERMISSION_DENIED -> PageFailureReasons.PERMISSION_DENIED
                        GeminiErrorKind.QUOTA_EXHAUSTED -> PageFailureReasons.QUOTA
                        GeminiErrorKind.RATE_LIMITED -> PageFailureReasons.RATE_LIMITED
                        GeminiErrorKind.SERVER_ERROR -> PageFailureReasons.SERVER_ERROR
                        GeminiErrorKind.NETWORK_ERROR -> PageFailureReasons.NETWORK_ERROR
                        GeminiErrorKind.MALFORMED_OUTPUT -> PageFailureReasons.PARSE_ERROR
                        GeminiErrorKind.UNKNOWN_FAILURE -> PageFailureReasons.UNKNOWN_ERROR
                    }
                    failedCount++
                    lastErrorMsg = classified.arabicMessage
                    pageDao.updatePageFailureState(
                        pageId = page.pageId,
                        pageStatus = PageStatus.FAILED.name,
                        failureReason = reasonCode,
                    )
                } finally {
                    if (pageBitmap != null && !pageBitmap.isRecycled) {
                        pageBitmap.recycle()
                    }
                }

                val currentAnalyzedTotal = alreadyAnalyzedCount + analyzedNow
                onProgress(
                    GeminiExecutionProgress(
                        documentId = documentId,
                        currentPageNumber = page.originalPageNumber,
                        analyzedPages = currentAnalyzedTotal,
                        failedPages = failedCount,
                        totalTargetPages = targetPages.size,
                    ),
                )
                documentDao.updateProcessingProgress(
                    documentId = documentId,
                    processingStatus = DocumentStatus.ANALYZING_TEXT.name,
                    processingProgressPercent = (currentAnalyzedTotal * 100) / doc.pages.size.coerceAtLeast(1),
                    lastModifiedMillis = System.currentTimeMillis(),
                    errorMessageAr = null,
                )
                // Stop batch early if no enabled key remains after failover/exhaustion
                val hasUsableKey = geminiKeyVault?.getNextEnabledKey() != null
                if (!hasUsableKey && idx < targetPages.lastIndex && failedCount > 0) {
                    lastErrorMsg = lastErrorMsg ?: "توقفت المعالجة لعدم توفر مفتاح Gemini مفعّل."
                    break
                }
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                documentDao.updateProcessingProgress(
                    documentId = documentId,
                    processingStatus = DocumentStatus.PAUSED.name,
                    processingProgressPercent = ((alreadyAnalyzedCount + analyzedNow) * 100) / doc.pages.size.coerceAtLeast(1),
                    lastModifiedMillis = System.currentTimeMillis(),
                    errorMessageAr = null,
                )
            }
            throw cancel
        }

        val totalAnalyzedNow = getDocumentById(documentId)?.pages?.count { it.geminiAnalysis != null }
            ?: (alreadyAnalyzedCount + analyzedNow)

        val countsAfterAnalysis = reviewItemDao.countByStatus(documentId, includeAgreements = false)
        val hasPendingReview = (countsAfterAnalysis[ReviewStatus.PENDING] ?: 0) > 0 ||
            (countsAfterAnalysis[ReviewStatus.STALE] ?: 0) > 0
        val finalStatus = when {
            failedCount > 0 && analyzedNow == 0 -> DocumentStatus.PAUSED
            hasPendingReview -> DocumentStatus.NEEDS_REVIEW
            else -> DocumentStatus.PROCESSING
        }
        documentDao.updateProcessingProgress(
            documentId = documentId,
            processingStatus = finalStatus.name,
            processingProgressPercent = (totalAnalyzedNow * 100) / doc.pages.size.coerceAtLeast(1),
            lastModifiedMillis = System.currentTimeMillis(),
            errorMessageAr = if (failedCount > 0) lastErrorMsg else null,
        )

        val summaryAr = when {
            analyzedNow > 0 && failedCount == 0 ->
                "تم تحليل $analyzedNow صفحة عبر Gemini وحفظ مخرجاتها المهيكلة ($totalAnalyzedNow / ${eligiblePages.size})."
            analyzedNow > 0 && failedCount > 0 ->
                "تم تحليل $analyzedNow صفحة، وتعذّر تحليل $failedCount صفحة: ${lastErrorMsg.orEmpty()}"
            else ->
                lastErrorMsg ?: "تعذر إتمام تحليل Gemini."
        }

        return@withContext GeminiRunOutcome.Completed(
            analyzedNow = analyzedNow,
            totalAnalyzedPages = totalAnalyzedNow,
            failedPages = failedCount,
            totalEligiblePages = eligiblePages.size,
            lastMessageAr = summaryAr,
        )
    }

    suspend fun getPagesWithFailedAnalysis(documentId: String): List<PageRecord> =
        withContext(Dispatchers.IO) {
            val ocrByPageId = ocrResultDao.getOcrResultsForDocument(documentId).associateBy { it.pageId }
            val geminiByPageId = geminiAnalysisDao.getAnalysesForDocument(documentId).associateBy { it.pageId }
            pageDao.getPagesWithFailedAnalysis(documentId).map { entity ->
                entity.toDomain(
                    ocrResultEntity = ocrByPageId[entity.pageId],
                    geminiAnalysisEntity = geminiByPageId[entity.pageId],
                )
            }
        }

    fun observePagesWithFailedAnalysis(documentId: String): Flow<List<PageRecord>> =
        pageDao.observePagesWithFailedAnalysis(documentId).map { entities ->
            entities.map { it.toDomain() }
        }

    /**
     * Marks a failed page as user-skipped so it is removed from the failed queue
     * and excluded from future batch Gemini runs, without deleting OCR text or review items.
     */
    suspend fun skipPageAnalysis(
        documentId: String,
        pageNumber: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val doc = getDocumentById(documentId)
            ?: return@withContext Result.failure(IllegalStateException("المستند المطلوب غير موجود."))
        val page = doc.pages.firstOrNull { it.originalPageNumber == pageNumber }
            ?: return@withContext Result.failure(IllegalStateException("الصفحة رقم $pageNumber غير موجودة في المستند."))

        pageDao.updatePageFailureState(
            pageId = page.pageId,
            pageStatus = PageStatus.FAILED.name,
            failureReason = PageFailureReasons.USER_SKIPPED,
        )
        Result.success(Unit)
    }

    /**
     * Retries Gemini analysis for a single failed page.
     * On success: sets page status = REVIEWED and clears failureReason.
     * On failure: keeps page status = FAILED and updates failureReason.
     */
    suspend fun retryPageAnalysis(
        documentId: String,
        pageNumber: Int,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val coordinator = geminiCoordinator
            ?: return@withContext Result.failure(IllegalStateException("منسق Gemini غير متوفر في هذا التكوين."))
        val doc = getDocumentById(documentId)
            ?: return@withContext Result.failure(IllegalStateException("المستند المطلوب غير موجود."))
        val page = doc.pages.firstOrNull { it.originalPageNumber == pageNumber }
            ?: return@withContext Result.failure(IllegalStateException("الصفحة رقم $pageNumber غير موجودة في المستند."))
        if (page.ocrResult == null) {
            return@withContext Result.failure(
                IllegalStateException("يجب إتمام استخراج النص (OCR) للصفحة $pageNumber قبل إعادة تحليل Gemini."),
            )
        }
        val privateFile = storage.resolvePrivatePdfIfExists(doc.internalStorageRef)
            ?: return@withContext Result.failure(IllegalStateException("تعذر الوصول إلى ملف PDF في التخزين الخاص."))

        var pageBitmap: Bitmap? = null
        try {
            pageBitmap = pageEngine.renderFromFile(
                pdfFile = privateFile,
                pageIndex = (pageNumber - 1).coerceAtLeast(0),
                maxWidthPx = 1280,
            )
            if (pageBitmap == null) {
                val msg = "تعذر تصيير صورة الصفحة $pageNumber لإعادة تحليلها عبر Gemini."
                pageDao.updatePageFailureState(
                    pageId = page.pageId,
                    pageStatus = PageStatus.FAILED.name,
                    failureReason = PageFailureReasons.RENDER_ERROR,
                )
                return@withContext Result.failure(IllegalStateException(msg))
            }

            when (val outcome = coordinator.analyzePageWithFailover(page, pageBitmap)) {
                is GeminiPageAnalysisOutcome.Success -> {
                    geminiAnalysisDao.upsertAnalysis(outcome.record.toEntity())
                    pageDao.updatePageMissingContent(
                        pageId = page.pageId,
                        hasMissingContent = outcome.record.isMissingLargeContent,
                    )
                    comparisonEngine.compareAndPersistForPage(
                        documentId = documentId,
                        pageId = page.pageId,
                        autoAcceptHighConfidence = smartReviewPrefs?.isAutoAcceptHighConfidenceEnabled() == true,
                    )
                    pageDao.updatePageFailureState(
                        pageId = page.pageId,
                        pageStatus = PageStatus.REVIEWED.name,
                        failureReason = null,
                    )

                    val refreshedDoc = getDocumentById(documentId)
                    val totalPagesCount = (refreshedDoc?.pages?.size ?: doc.pages.size).coerceAtLeast(1)
                    val totalAnalyzedNow = refreshedDoc?.pages?.count { it.geminiAnalysis != null } ?: 1
                    val countsAfter = reviewItemDao.countByStatus(documentId, includeAgreements = false)
                    val hasPending = (countsAfter[ReviewStatus.PENDING] ?: 0) > 0 ||
                        (countsAfter[ReviewStatus.STALE] ?: 0) > 0
                    documentDao.updateProcessingProgress(
                        documentId = documentId,
                        processingStatus = if (hasPending) DocumentStatus.NEEDS_REVIEW.name else DocumentStatus.PROCESSING.name,
                        processingProgressPercent = (totalAnalyzedNow * 100) / totalPagesCount,
                        lastModifiedMillis = System.currentTimeMillis(),
                        errorMessageAr = null,
                    )
                    Result.success(Unit)
                }
                is GeminiPageAnalysisOutcome.Failure -> {
                    pageDao.updatePageFailureState(
                        pageId = page.pageId,
                        pageStatus = PageStatus.FAILED.name,
                        failureReason = outcome.failureReason ?: PageFailureReasons.UNKNOWN_ERROR,
                    )
                    Result.failure(IllegalStateException(outcome.reasonAr))
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            val classified = GeminiErrorClassifier.classify(throwable)
            val reasonCode = when (classified.kind) {
                GeminiErrorKind.INVALID_KEY -> PageFailureReasons.INVALID_KEY
                GeminiErrorKind.PERMISSION_DENIED -> PageFailureReasons.PERMISSION_DENIED
                GeminiErrorKind.QUOTA_EXHAUSTED -> PageFailureReasons.QUOTA
                GeminiErrorKind.RATE_LIMITED -> PageFailureReasons.RATE_LIMITED
                GeminiErrorKind.SERVER_ERROR -> PageFailureReasons.SERVER_ERROR
                GeminiErrorKind.NETWORK_ERROR -> PageFailureReasons.NETWORK_ERROR
                GeminiErrorKind.MALFORMED_OUTPUT -> PageFailureReasons.PARSE_ERROR
                GeminiErrorKind.UNKNOWN_FAILURE -> PageFailureReasons.UNKNOWN_ERROR
            }
            pageDao.updatePageFailureState(
                pageId = page.pageId,
                pageStatus = PageStatus.FAILED.name,
                failureReason = reasonCode,
            )
            Result.failure(IllegalStateException(classified.arabicMessage))
        } finally {
            if (pageBitmap != null && !pageBitmap.isRecycled) {
                pageBitmap.recycle()
            }
        }
    }

    suspend fun comparePageOcrWithGemini(
        documentId: String,
        pageId: String,
    ): ComparisonPageSummary = comparisonEngine.compareAndPersistForPage(documentId, pageId)

    suspend fun getReviewQueue(
        documentId: String,
        filterStatus: ReviewStatus? = null,
        filterPage: Int? = null,
        orderByPriority: Boolean = true,
        includeAgreements: Boolean = false,
    ): List<ReviewItem> = withContext(Dispatchers.IO) {
        reviewItemDao.getReviewQueue(
            documentId = documentId,
            filterStatus = filterStatus,
            filterPage = filterPage,
            orderByPriority = orderByPriority,
            includeAgreements = includeAgreements,
        ).map { it.toDomain() }
    }

    suspend fun countReviewByStatus(
        documentId: String,
        includeAgreements: Boolean = false,
    ): Map<ReviewStatus, Int> = withContext(Dispatchers.IO) {
        reviewItemDao.countByStatus(documentId, includeAgreements)
    }

    suspend fun getNextPendingReviewItem(
        documentId: String,
        afterPriority: Int = 0,
        afterPageNumber: Int = 0,
        afterElementIndex: Int = -1,
    ): ReviewItem? = withContext(Dispatchers.IO) {
        reviewItemDao.getNextPending(
            documentId = documentId,
            afterPriority = afterPriority,
            afterPageNumber = afterPageNumber,
            afterElementIndex = afterElementIndex,
        )?.toDomain()
    }

    /**
     * Registers only metadata for a planned API key slot.
     * Kept for Room schema compatibility; Module 04 encrypted secrets live in EncryptedSharedPreferences.
     */
    suspend fun registerApiKeySlot(label: String): ApiKeyMetadata = withContext(Dispatchers.IO) {
        val cleanLabel = label.trim().ifEmpty { "مفتاح Gemini" }
        val entity = ApiKeySlotEntity(
            id = UUID.randomUUID().toString(),
            label = cleanLabel,
            isEnabled = true,
            status = ApiKeyStatus.PENDING_BACKEND.name,
            createdAtMillis = System.currentTimeMillis(),
        )
        apiKeySlotDao.upsertSlot(entity)
        entity.toDomain()
    }

    suspend fun toggleApiKeyEnabled(id: String) = withContext(Dispatchers.IO) {
        val existing = apiKeySlotDao.getSlotById(id) ?: return@withContext
        val nextEnabled = !existing.isEnabled
        apiKeySlotDao.upsertSlot(
            existing.copy(
                isEnabled = nextEnabled,
                status = if (nextEnabled) ApiKeyStatus.PENDING_BACKEND.name else ApiKeyStatus.DISABLED.name,
            ),
        )
    }

    suspend fun removeApiKeySlot(id: String) = withContext(Dispatchers.IO) {
        apiKeySlotDao.deleteSlotById(id)
    }

    fun buildPlannedStages(document: DocumentRecord?): List<PipelineStageInfo> {
        val localValidated = document != null && document.isValidationPassed
        val importedAndIndexed = document != null &&
            document.importStatus == ImportStatus.READY_FOR_PROCESSING &&
            document.indexedPagesCount > 0
        val ocrCompleted = document != null &&
            document.pages.isNotEmpty() &&
            document.pages.all { it.ocrStatus == OcrPageStatus.DONE }
        val geminiCompleted = document != null &&
            document.pages.isNotEmpty() &&
            document.pages.all { it.geminiAnalysis != null }

        return listOf(
            PipelineStageInfo(
                id = "stage_local_validation",
                titleAr = "اختيار ملف PDF والتحقق الأولي",
                subtitleAr = "فحص صيغة الملف وترويسة %PDF- وعدد الصفحات دون حد أقصى.",
                availability = StageModuleAvailability.READY_IN_MODULE_01,
                isCompletedLocally = localValidated,
            ),
            PipelineStageInfo(
                id = "stage_private_import",
                titleAr = "الاستيراد إلى التخزين الخاص وفهرسة الصفحات",
                subtitleAr = "نسخ متدفق إلى التخزين الخاص، فهرسة جميع الصفحات وأبعادها في قاعدة بيانات Room مع حفظ دائم.",
                availability = StageModuleAvailability.READY_IN_MODULE_02,
                isCompletedLocally = importedAndIndexed,
            ),
            PipelineStageInfo(
                id = "stage_ocr",
                titleAr = "استخراج النص المبدئي على الجهاز (OCR)",
                subtitleAr = "تشغيل Tesseract4Android كمحرك أساسي للعربية (ara.traineddata) أو ML Kit v2 كمحرك ثانوي للاتينية والأرقام.",
                availability = StageModuleAvailability.READY_IN_MODULE_03,
                isCompletedLocally = ocrCompleted,
            ),
            PipelineStageInfo(
                id = "stage_gemini",
                titleAr = "تحليل Gemini البصري واللغوي (على الجهاز مباشرة)",
                subtitleAr = "إرسال صورة كل صفحة مع مخرجات OCR إلى Google Gemini SDK مباشرة لاستخراج الملاحظات المهيكلة والأدلة.",
                availability = StageModuleAvailability.READY_IN_MODULE_04,
                isCompletedLocally = geminiCompleted,
            ),
            PipelineStageInfo(
                id = "stage_compare",
                titleAr = "مقارنة مخرجات OCR مع Gemini",
                subtitleAr = "دمج الاقتراحات وتصنيف التطابقات والاختلافات والأحرف العربية المتشابهة وتوليد طابور المراجعة.",
                availability = StageModuleAvailability.READY_IN_MODULE_05,
                isCompletedLocally = geminiCompleted || (document?.reviewItems?.isNotEmpty() == true),
            ),
            PipelineStageInfo(
                id = "stage_review",
                titleAr = "المراجعة البشرية التفاعلية",
                subtitleAr = "مراجعة الكلمات غير المحسومة واعتماد التصحيحات أو تعديلها يدوياً مع عرض الصورة الأصلية المكبّرة.",
                availability = StageModuleAvailability.READY_IN_MODULE_06,
                isCompletedLocally = document?.reviewApprovedAt != null,
            ),
            PipelineStageInfo(
                id = "stage_docx",
                titleAr = "إنشاء ملف Word عربي (DOCX)",
                subtitleAr = "توليد مستند DOCX داعم للاتجاه RTL مع تمييز الكلمات غير المحسومة ومشاركته عبر النظام.",
                availability = StageModuleAvailability.READY_IN_MODULE_07,
                isCompletedLocally = document?.exportedDocument != null,
            ),
        )
    }

    private suspend fun isPageVisuallyEmpty(
        document: DocumentRecord,
        page: PageRecord,
    ): Boolean {
        visualEmptyMemoryCache[page.pageId]?.let { return it }
        pageInspectionCacheStore?.isVisuallyEmptyCached(page.pageId)?.let { cached ->
            visualEmptyMemoryCache[page.pageId] = cached
            return cached
        }

        val privateFile = storage.resolvePrivatePdfIfExists(document.internalStorageRef)
            ?: return false

        var bitmap: Bitmap? = null
        return try {
            bitmap = pageEngine.renderFromFile(
                pdfFile = privateFile,
                pageIndex = (page.originalPageNumber - 1).coerceAtLeast(0),
                maxWidthPx = 400,
            )
            if (bitmap == null) {
                false
            } else {
                val blank = isVisuallyEmptyBitmap(bitmap)
                cachePageVisuallyEmpty(page.pageId, blank)
                blank
            }
        } catch (_: Exception) {
            false
        } finally {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun cachePageVisuallyEmpty(pageId: String, isEmpty: Boolean) {
        visualEmptyMemoryCache[pageId] = isEmpty
        pageInspectionCacheStore?.setVisuallyEmpty(pageId, isEmpty)
    }

    private fun isPageGeminiRepaired(pageId: String): Boolean {
        geminiRepairedMemoryCache[pageId]?.let { return it }
        val persisted = pageInspectionCacheStore?.isGeminiFallbackApplied(pageId) ?: false
        if (persisted) {
            geminiRepairedMemoryCache[pageId] = true
        }
        return persisted
    }

    private fun markPageGeminiRepaired(pageId: String, repaired: Boolean) {
        geminiRepairedMemoryCache[pageId] = repaired
        pageInspectionCacheStore?.setGeminiFallbackApplied(pageId, repaired)
    }

    private fun isVisuallyEmptyBitmap(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true

        val stepX = (width / 32).coerceAtLeast(1)
        val stepY = (height / 32).coerceAtLeast(1)
        var nonWhitePixels = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luminance = (r * 299 + g * 587 + b * 114) / 1000
                if (luminance < 220) {
                    nonWhitePixels++
                    if (nonWhitePixels >= VISUAL_NON_WHITE_MIN_PIXELS) {
                        return false
                    }
                }
                x += stepX
            }
            y += stepY
        }
        return true
    }

    companion object {
        private const val MIN_OCR_FALLBACK_CHAR_THRESHOLD = 150
        private const val VISUAL_NON_WHITE_MIN_PIXELS = 8

        fun createDefault(context: Context): MuharrirRepository {
            val appContext = context.applicationContext
            val keyVault = EncryptedGeminiApiKeyVault(appContext)
            val coordinator = GeminiAnalysisCoordinator(
                keyVault = keyVault,
                client = GoogleAiGeminiClient(),
            )
            return MuharrirRepository(
                database = MuharrirDatabase.getInstance(appContext),
                storage = PrivateDocumentStorage(appContext),
                backendManager = BackendConnectionManager(),
                ocrEngineRegistry = OcrEngineRegistry.createDefault(appContext),
                geminiKeyVault = keyVault,
                geminiCoordinator = coordinator,
                smartReviewPrefs = SmartReviewPreferencesStore.getInstance(appContext),
                pageInspectionCacheStore = PageInspectionCacheStore.getInstance(appContext),
            )
        }
    }
}
