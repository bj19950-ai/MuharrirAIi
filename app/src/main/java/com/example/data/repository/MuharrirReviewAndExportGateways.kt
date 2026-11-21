package com.example.data.repository

import android.graphics.Bitmap
import com.example.data.local.MuharrirDatabase
import com.example.data.local.OcrSegmentsJsonCodec
import com.example.data.local.ReviewItemEntity
import com.example.data.pdf.BitmapSafety
import com.example.data.pdf.PdfPageEngine
import com.example.data.storage.PrivateDocumentStorage
import com.example.domain.model.ComparisonCategory
import com.example.domain.model.DocumentStatus
import com.example.domain.model.OcrElementLevel
import com.example.domain.model.OcrSegment
import com.example.domain.model.PageStatus
import com.example.export.domain.OcrLine
import com.example.export.model.DocumentPageModel
import com.example.export.model.ExportDocumentModel
import com.example.export.model.WordExportDataGateway
import com.example.export.model.WordExportMode
import com.example.export.model.toDomainExportMode
import com.example.review.model.BoundingBox
import com.example.review.model.DocumentReviewGateway
import com.example.review.model.DocumentReviewInfo
import com.example.review.model.PageBitmapSource
import com.example.review.model.ReviewItemModel
import com.example.review.model.ReviewQueueGateway
import com.example.review.model.ReviewStatus
import com.example.review.model.ReviewStatusCounts
import com.example.review.model.SmartReviewPreferencesStore
import com.example.review.model.toDiscrepancyCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal fun ReviewItemEntity.toReviewItemModel(orderOverride: Int? = null): ReviewItemModel {
    val safeCategory = ComparisonCategory.fromPersisted(category).toDiscrepancyCategory()
    val safeStatus = runCatching { ReviewStatus.valueOf(reviewStatus) }
        .getOrDefault(ReviewStatus.PENDING)
    val hasValidBox = x2 > x1 && y2 > y1
    return ReviewItemModel(
        id = id,
        documentId = documentId,
        pageNumber = pageNumber,
        category = safeCategory,
        ocrText = ocrText,
        suggestedText = suggestedText,
        ocrConfidence = ocrConfidence,
        geminiConfidence = geminiConfidence,
        evidence = evidence.takeIf { it.isNotBlank() },
        bbox = if (hasValidBox) {
            BoundingBox(
                left = x1.toFloat(),
                top = y1.toFloat(),
                right = x2.toFloat(),
                bottom = y2.toFloat(),
            )
        } else {
            null
        },
        reviewStatus = safeStatus,
        userText = userText,
        resolvedAt = resolvedAt,
        isStale = safeStatus == ReviewStatus.STALE,
        staleNoteAr = staleNoteAr,
        orderIndex = orderOverride ?: elementIndex,
    )
}

class MuharrirReviewQueueGateway(
    private val database: MuharrirDatabase,
) : ReviewQueueGateway {

    private val reviewItemDao = database.reviewItemDao()
    private val pageDao = database.pageDao()
    private val documentDao = database.documentDao()

    override fun getReviewQueue(documentId: String): Flow<List<ReviewItemModel>> {
        return reviewItemDao.observeReviewItemsForDocument(documentId).map { entities ->
            entities.map { it.toReviewItemModel() }
        }
    }

    override fun countByStatus(documentId: String): Flow<ReviewStatusCounts> {
        return reviewItemDao.observeStatusCounts(documentId, includeAgreements = false)
            .map { tuples ->
                val counts = tuples.mapNotNull { tuple ->
                    runCatching { ReviewStatus.valueOf(tuple.reviewStatus) }.getOrNull()
                        ?.let { it to tuple.itemCount }
                }.toMap()

                val approved = counts[ReviewStatus.APPROVED_GEMINI] ?: 0
                val edited = counts[ReviewStatus.MANUALLY_EDITED] ?: 0
                val unresolved = counts[ReviewStatus.UNRESOLVED] ?: 0
                val ignored = counts[ReviewStatus.IGNORED] ?: 0
                val pending = counts[ReviewStatus.PENDING] ?: 0
                val stale = counts[ReviewStatus.STALE] ?: 0
                val remaining = pending + stale
                val total = approved + edited + unresolved + ignored + remaining

                ReviewStatusCounts(
                    total = total,
                    approvedGemini = approved,
                    manuallyEdited = edited,
                    unresolved = unresolved,
                    ignored = ignored,
                    remaining = remaining,
                )
            }
    }

    override suspend fun getNextPending(documentId: String, afterItemId: Long): ReviewItemModel? =
        withContext(Dispatchers.IO) {
            val ordered = reviewItemDao.getReviewQueue(
                documentId = documentId,
                orderByPriority = true,
                includeAgreements = false,
            )
            if (ordered.isEmpty()) return@withContext null

            val afterIndex = ordered.indexOfFirst { it.id == afterItemId }
            val candidatesAfter = if (afterIndex >= 0) {
                ordered.drop(afterIndex + 1)
            } else {
                ordered
            }
            val nextAfter = candidatesAfter.firstOrNull {
                it.reviewStatus == ReviewStatus.PENDING.name ||
                    it.reviewStatus == ReviewStatus.STALE.name
            }
            val fallbackAny = nextAfter ?: ordered.firstOrNull {
                it.id != afterItemId &&
                    (it.reviewStatus == ReviewStatus.PENDING.name ||
                        it.reviewStatus == ReviewStatus.STALE.name)
            }
            fallbackAny?.toReviewItemModel()
        }

    override suspend fun saveDecision(
        itemId: Long,
        newStatus: ReviewStatus,
        userText: String?,
        resolvedAt: Long,
    ): Unit = withContext(Dispatchers.IO) {
        val existing = reviewItemDao.getItemById(itemId)
            ?: throw IllegalStateException("Review item $itemId not found")
        val updatedRows = reviewItemDao.saveReviewDecision(
            itemId = itemId,
            reviewStatus = newStatus.name,
            userText = userText,
            resolvedAt = resolvedAt,
        )
        if (updatedRows <= 0) {
            throw IllegalStateException("Failed to persist review decision for item $itemId")
        }

        val pageItems = reviewItemDao.getItemsForPage(existing.pageId)
            .filter { ComparisonCategory.fromPersisted(it.category) != ComparisonCategory.AGREEMENT }
        val pageHasRemaining = pageItems.any {
            it.reviewStatus == ReviewStatus.PENDING.name || it.reviewStatus == ReviewStatus.STALE.name
        }
        pageDao.updatePageStatus(
            pageId = existing.pageId,
            pageStatus = if (pageHasRemaining) PageStatus.NEEDS_REVIEW.name else PageStatus.REVIEWED.name,
        )

        val doc = documentDao.getDocumentById(existing.documentId)
        if (doc != null && doc.reviewApprovedAt == null &&
            doc.processingStatus != DocumentStatus.COMPLETED.name
        ) {
            documentDao.updateProcessingProgress(
                documentId = doc.id,
                processingStatus = DocumentStatus.NEEDS_REVIEW.name,
                processingProgressPercent = doc.processingProgressPercent,
                lastModifiedMillis = System.currentTimeMillis(),
                errorMessageAr = doc.errorMessageAr,
            )
        }
    }

    override suspend fun reopenItemAsPending(itemId: Long): Unit = withContext(Dispatchers.IO) {
        val existing = reviewItemDao.getItemById(itemId)
            ?: throw IllegalStateException("Review item $itemId not found")
        val updatedRows = reviewItemDao.reopenReviewItemAsPending(itemId)
        if (updatedRows <= 0) {
            throw IllegalStateException("Failed to reopen review item $itemId")
        }

        pageDao.updatePageStatus(existing.pageId, PageStatus.NEEDS_REVIEW.name)
        val doc = documentDao.getDocumentById(existing.documentId)
        if (doc != null && doc.reviewApprovedAt == null) {
            documentDao.updateProcessingProgress(
                documentId = doc.id,
                processingStatus = DocumentStatus.NEEDS_REVIEW.name,
                processingProgressPercent = doc.processingProgressPercent,
                lastModifiedMillis = System.currentTimeMillis(),
                errorMessageAr = doc.errorMessageAr,
            )
        }
    }

    override suspend fun approveAllPendingGemini(
        documentId: String,
        itemIds: List<Long>,
        resolvedAt: Long,
    ): Unit = withContext(Dispatchers.IO) {
        if (itemIds.isEmpty()) return@withContext

        itemIds.chunked(500).forEach { chunk ->
            reviewItemDao.bulkApproveGeminiByIds(
                itemIds = chunk,
                resolvedAt = resolvedAt,
            )
        }

        val itemsByPage = reviewItemDao.getReviewItemsInReadingOrder(documentId)
            .filter { ComparisonCategory.fromPersisted(it.category) != ComparisonCategory.AGREEMENT }
            .groupBy { it.pageId }

        itemsByPage.forEach { (pageId, pageItems) ->
            val pageHasRemaining = pageItems.any {
                it.reviewStatus == ReviewStatus.PENDING.name || it.reviewStatus == ReviewStatus.STALE.name
            }
            pageDao.updatePageStatus(
                pageId = pageId,
                pageStatus = if (pageHasRemaining) PageStatus.NEEDS_REVIEW.name else PageStatus.REVIEWED.name,
            )
        }

        val doc = documentDao.getDocumentById(documentId)
        if (doc != null && doc.reviewApprovedAt == null &&
            doc.processingStatus != DocumentStatus.COMPLETED.name
        ) {
            documentDao.updateProcessingProgress(
                documentId = doc.id,
                processingStatus = DocumentStatus.NEEDS_REVIEW.name,
                processingProgressPercent = doc.processingProgressPercent,
                lastModifiedMillis = System.currentTimeMillis(),
                errorMessageAr = doc.errorMessageAr,
            )
        }
    }
}

class MuharrirDocumentReviewGateway(
    private val database: MuharrirDatabase,
) : DocumentReviewGateway {

    private val documentDao = database.documentDao()

    override fun observeDocument(documentId: String): Flow<DocumentReviewInfo?> {
        return documentDao.observeDocumentById(documentId).map { entity ->
            entity?.let {
                DocumentReviewInfo(
                    documentId = it.id,
                    title = it.originalFileName,
                    reviewApprovedAt = it.reviewApprovedAt,
                )
            }
        }
    }

    override suspend fun setReviewApprovedAt(documentId: String, approvedAtMillis: Long) =
        withContext(Dispatchers.IO) {
            documentDao.getDocumentById(documentId)
                ?: throw IllegalStateException("Document $documentId not found")
            documentDao.updateReviewApprovedAt(
                documentId = documentId,
                approvedAtMillis = approvedAtMillis,
                processingStatus = DocumentStatus.REVIEW_COMPLETED.name,
                lastModifiedMillis = approvedAtMillis,
            )
        }
}

class MuharrirPageBitmapSource(
    private val database: MuharrirDatabase,
    private val storage: PrivateDocumentStorage,
    private val pageEngine: PdfPageEngine,
) : PageBitmapSource {

    override suspend fun loadPageBitmap(documentId: String, pageNumber: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val doc = database.documentDao().getDocumentById(documentId) ?: return@withContext null
            val privateFile = storage.resolvePrivatePdfIfExists(doc.internalStoragePath)
                ?: return@withContext null

            val pages = database.pageDao().getPagesForDocument(documentId)
            val pageEntity = pages.firstOrNull { it.originalPageNumber == pageNumber }
            val ocrResult = pageEntity?.let { database.ocrResultDao().getOcrResultForPage(it.pageId) }
            val targetWidthPx = ocrResult?.imageWidthPx?.takeIf { it > 0 } ?: 1600

            pageEngine.renderFromFile(
                pdfFile = privateFile,
                pageIndex = (pageNumber - 1).coerceAtLeast(0),
                maxWidthPx = targetWidthPx,
            )?.let { rendered ->
                BitmapSafety.downsampleToMaxDimension(
                    bitmap = rendered,
                    maxDimensionPx = BitmapSafety.MAX_RENDER_DIMENSION_PX,
                    recycleOriginal = true,
                )
            }
        }
}

class MuharrirWordExportDataGateway(
    private val database: MuharrirDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val smartReviewPrefs: SmartReviewPreferencesStore? = null,
) : WordExportDataGateway {

    override suspend fun getDocument(documentId: String): ExportDocumentModel? =
        withContext(ioDispatcher) {
            database.documentDao().getDocumentById(documentId)?.let {
                ExportDocumentModel(
                    id = it.id,
                    title = it.originalFileName,
                    reviewApprovedAt = it.reviewApprovedAt,
                )
            }
        }

    override suspend fun getPagesInOrder(documentId: String): List<DocumentPageModel> =
        withContext(ioDispatcher) {
            val pages = database.pageDao().getPagesForDocument(documentId)
            val ocrByPageId = database.ocrResultDao().getOcrResultsForDocument(documentId)
                .associateBy { it.pageId }
            pages.map { page ->
                val ocr = ocrByPageId[page.pageId]
                val safeStatus = runCatching { PageStatus.valueOf(page.pageStatus) }
                    .getOrDefault(PageStatus.OCR_DONE)
                val segments = ocr?.segmentsJson?.let { OcrSegmentsJsonCodec.fromJson(it) }.orEmpty()
                val ocrLines = buildOcrLinesFromSegments(
                    recognizedText = ocr?.recognizedText.orEmpty(),
                    segments = segments,
                    pageNumber = page.originalPageNumber,
                )
                DocumentPageModel(
                    id = page.pageId,
                    documentId = page.documentId,
                    pageNumber = page.originalPageNumber,
                    ocrText = ocr?.recognizedText.orEmpty(),
                    hasMissingContent = page.hasMissingContent,
                    pageStatus = safeStatus,
                    failureReason = page.failureReason,
                    ocrLines = ocrLines,
                )
            }
        }

    override suspend fun getReviewItemsInOrder(documentId: String): List<ReviewItemModel> =
        withContext(ioDispatcher) {
            database.reviewItemDao().getReviewItemsInReadingOrder(documentId)
                .mapIndexed { idx, entity ->
                    entity.toReviewItemModel(orderOverride = idx)
                }
        }

    override suspend fun shouldHighlightUnresolved(): Boolean = withContext(ioDispatcher) {
        database.preferencesDao().getPreferences()?.highlightUnresolvedInDocx ?: true
    }

    override suspend fun shouldShowExportWarnings(): Boolean {
        return smartReviewPrefs?.isShowExportWarningsEnabled() ?: true
    }

    private fun buildOcrLinesFromSegments(
        recognizedText: String,
        segments: List<OcrSegment>,
        pageNumber: Int,
    ): List<OcrLine> {
        if (recognizedText.isBlank() || segments.isEmpty()) return emptyList()
        val lineSegments = segments.filter { it.level == OcrElementLevel.LINE && it.boundingBox != null }
        val derivedLineMetrics = if (lineSegments.isNotEmpty()) {
            lineSegments.map { seg ->
                val box = seg.boundingBox!!
                OcrLine(
                    text = seg.text.trim(),
                    fontSizePx = (box.bottom - box.top).takeIf { it > 0f },
                    top = box.top.toInt(),
                    pageNumber = pageNumber,
                )
            }
        } else {
            val elementSegments = segments.filter {
                it.level == OcrElementLevel.ELEMENT && it.boundingBox != null && it.lineIndex != null
            }
            if (elementSegments.isEmpty()) {
                emptyList()
            } else {
                elementSegments
                    .groupBy { it.blockIndex to it.lineIndex!! }
                    .toSortedMap(compareBy({ it.first }, { it.second }))
                    .values
                    .mapNotNull { lineElements ->
                        val boxes = lineElements.mapNotNull { it.boundingBox }
                        val heights = boxes.map { it.bottom - it.top }.filter { it > 0f }.sorted()
                        val medianHeight = heights.getOrNull(heights.size / 2)
                        val minTop = boxes.minOfOrNull { it.top.toInt() }
                        val lineText = lineElements.joinToString(" ") { it.text.trim() }.trim()
                        if (lineText.isEmpty()) {
                            null
                        } else {
                            OcrLine(
                                text = lineText,
                                fontSizePx = medianHeight,
                                top = minTop,
                                pageNumber = pageNumber,
                            )
                        }
                    }
            }
        }

        if (derivedLineMetrics.isEmpty()) return emptyList()

        // Align metrics onto recognizedText lines only when line counts or exact text align safely
        val textLines = recognizedText.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (textLines.size == derivedLineMetrics.size) {
            return textLines.mapIndexed { idx, line ->
                derivedLineMetrics[idx].copy(text = line, pageNumber = pageNumber)
            }
        }

        val metricsByText = derivedLineMetrics.associateBy { it.text }
        return textLines.map { line ->
            val matched = metricsByText[line]
            OcrLine(
                text = line,
                fontSizePx = matched?.fontSizePx,
                top = matched?.top,
                pageNumber = pageNumber,
            )
        }
    }

    override suspend fun recordExportedDocument(
        documentId: String,
        docxFilePath: String,
        mode: WordExportMode,
        exportedAtMillis: Long,
    ) = withContext(ioDispatcher) {
        database.documentDao().updateExportedDocument(
            documentId = documentId,
            docxPath = docxFilePath,
            exportMode = mode.toDomainExportMode().name,
            exportedAtMillis = exportedAtMillis,
            processingStatus = DocumentStatus.COMPLETED.name,
            lastModifiedMillis = exportedAtMillis,
        )
    }
}
