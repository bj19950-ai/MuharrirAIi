package com.example.data.comparison

import com.example.data.local.GeminiAnalysisEntity
import com.example.data.local.MuharrirDatabase
import com.example.data.local.OcrResultEntity
import com.example.data.local.ReviewItemEntity
import com.example.data.local.toDomain
import com.example.domain.model.BoundingBox
import com.example.domain.model.ComparisonCategory
import com.example.domain.model.GeminiPageAnalysisRecord
import com.example.domain.model.GeminiSuggestionItem
import com.example.domain.model.OcrElementLevel
import com.example.domain.model.OcrResult
import com.example.domain.model.OcrSegment
import com.example.domain.model.ReviewStatus
import com.example.export.domain.OcrTextCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Compares per-page OCR output (`ocr_results`) against Gemini's structured analysis
 * (`gemini_page_analyses`), classifies every item, ranks by priority & reading order,
 * and persists results into Room (`review_items`).
 *
 * Handles incremental re-analysis safely:
 * - Deletes existing PENDING items for the page before inserting recomputed ones.
 * - Preserves already-reviewed/resolved items (`review_status != PENDING`).
 * - Marks preserved resolved items as `STALE` with an Arabic note if the underlying
 *   OCR or Gemini text changed (or if the item disappeared from the new analysis).
 */
class OcrGeminiComparisonEngine(
    private val database: MuharrirDatabase,
) {
    private val ocrResultDao = database.ocrResultDao()
    private val geminiAnalysisDao = database.geminiAnalysisDao()
    private val reviewItemDao = database.reviewItemDao()

    suspend fun compareAndPersistForPage(
        documentId: String,
        pageId: String,
        autoAcceptHighConfidence: Boolean = false,
    ): ComparisonPageSummary = withContext(Dispatchers.IO) {
        val ocrEntity: OcrResultEntity? = ocrResultDao.getOcrResultForPage(pageId)
        val geminiEntity: GeminiAnalysisEntity? = geminiAnalysisDao.getAnalysisForPage(pageId)

        if (ocrEntity == null || geminiEntity == null) {
            return@withContext ComparisonPageSummary(
                pageId = pageId,
                totalComputed = 0,
                insertedPending = 0,
                preservedResolved = 0,
                markedStale = 0,
            )
        }

        val ocrResult = ocrEntity.toDomain()
        val geminiRecord = geminiEntity.toDomain()
        val nowMillis = System.currentTimeMillis()

        val recomputed = buildRecomputedEntities(
            documentId = documentId,
            ocrResult = ocrResult,
            geminiRecord = geminiRecord,
            nowMillis = nowMillis,
            autoAcceptHighConfidence = autoAcceptHighConfidence,
        )

        val existingItems = reviewItemDao.getItemsForPage(pageId)
        val resolvedExisting = existingItems.filter { it.reviewStatus != ReviewStatus.PENDING.name }

        val consumedRecomputedIndices = mutableSetOf<Int>()
        val updatedResolved = mutableListOf<ReviewItemEntity>()
        var staleCount = 0

        for (resolved in resolvedExisting) {
            val matchIndex = findMatchingRecomputedIndex(
                resolved = resolved,
                candidates = recomputed,
                alreadyConsumed = consumedRecomputedIndices,
            )
            if (matchIndex != null) {
                consumedRecomputedIndices.add(matchIndex)
                val matched = recomputed[matchIndex]
                val ocrChanged = resolved.ocrText.trim() != matched.ocrText.trim()
                val suggestedChanged = resolved.suggestedText?.trim() != matched.suggestedText?.trim()
                if (ocrChanged || suggestedChanged) {
                    staleCount++
                    updatedResolved.add(
                        resolved.copy(
                            ocrResultId = matched.ocrResultId,
                            geminiAnalysisId = matched.geminiAnalysisId,
                            x1 = matched.x1,
                            y1 = matched.y1,
                            x2 = matched.x2,
                            y2 = matched.y2,
                            ocrText = matched.ocrText,
                            suggestedText = matched.suggestedText,
                            ocrConfidence = matched.ocrConfidence,
                            geminiConfidence = matched.geminiConfidence,
                            category = matched.category,
                            priority = matched.priority,
                            evidence = matched.evidence,
                            reasonType = matched.reasonType,
                            blockIndex = matched.blockIndex,
                            lineIndex = matched.lineIndex,
                            elementIndex = matched.elementIndex,
                            reviewStatus = ReviewStatus.STALE.name,
                            staleNoteAr = buildTextChangedStaleNote(resolved, matched),
                        ),
                    )
                } else {
                    // Underlying text did not change; keep existing review decision & status intact,
                    // just refresh FK pointers since REPLACE upsert on parent resets FK via ON DELETE SET NULL.
                    updatedResolved.add(
                        resolved.copy(
                            ocrResultId = matched.ocrResultId,
                            geminiAnalysisId = matched.geminiAnalysisId,
                        ),
                    )
                }
            } else {
                // Resolved item no longer appears after re-analysis -> mark STALE if not already STALE
                if (resolved.reviewStatus != ReviewStatus.STALE.name) {
                    staleCount++
                    updatedResolved.add(
                        resolved.copy(
                            ocrResultId = ocrResult.pageId,
                            geminiAnalysisId = geminiRecord.pageId,
                            reviewStatus = ReviewStatus.STALE.name,
                            staleNoteAr = "لم يعد هذا العنصر موجوداً في مخرجات التحليل الجديدة بعد إعادة المعالجة؛ يُرجى مراجعة الصفحة.",
                        ),
                    )
                } else {
                    updatedResolved.add(
                        resolved.copy(
                            ocrResultId = ocrResult.pageId,
                            geminiAnalysisId = geminiRecord.pageId,
                        ),
                    )
                }
            }
        }

        val toInsert = recomputed.filterIndexed { index, _ -> index !in consumedRecomputedIndices }

        reviewItemDao.replacePageComparisonItems(
            pageId = pageId,
            updatedResolvedItems = updatedResolved,
            newPendingItems = toInsert,
        )

        ComparisonPageSummary(
            pageId = pageId,
            totalComputed = recomputed.size,
            insertedPending = toInsert.size,
            preservedResolved = updatedResolved.size,
            markedStale = staleCount,
        )
    }

    internal fun buildRecomputedEntities(
        documentId: String,
        ocrResult: OcrResult,
        geminiRecord: GeminiPageAnalysisRecord,
        nowMillis: Long,
        autoAcceptHighConfidence: Boolean = false,
    ): List<ReviewItemEntity> {
        if (geminiRecord.items.isEmpty()) return emptyList()

        val drafted = geminiRecord.items.mapIndexedNotNull { rawIdx, item ->
            val ocrIsNoise = OcrTextCleaner.isHeaderOrFooterNoise(item.ocr_text, geminiRecord.pageNumber)
            val suggestedIsBlankOrNoise = item.suggested_text.isBlank() ||
                OcrTextCleaner.isHeaderOrFooterNoise(item.suggested_text, geminiRecord.pageNumber)
            if (ocrIsNoise && suggestedIsBlankOrNoise) {
                return@mapIndexedNotNull null
            }

            val x1 = item.bbox.getOrElse(0) { 0f }.roundToInt()
            val y1 = item.bbox.getOrElse(1) { 0f }.roundToInt()
            val x2 = item.bbox.getOrElse(2) { 0f }.roundToInt()
            val y2 = item.bbox.getOrElse(3) { 0f }.roundToInt()

            val match = resolveOcrSegmentMatch(item, ocrResult)
            val effectiveOcrConfidence = match.resolvedConfidence ?: ocrResult.confidence

            val classification = ComparisonClassifier.classify(
                ComparisonClassificationInput(
                    ocrText = item.ocr_text,
                    suggestedText = item.suggested_text,
                    ocrConfidence = effectiveOcrConfidence,
                    geminiConfidence = item.confidence,
                    evidence = item.evidence,
                    reasonType = item.reason_type,
                    requiresReview = item.requires_review,
                ),
            )

            DraftReviewItem(
                rawIndex = rawIdx,
                blockIndex = match.blockIndex,
                lineIndex = match.lineIndex,
                segmentElementOrder = match.elementIndex,
                x1 = min(x1, x2),
                y1 = min(y1, y2),
                x2 = max(x1, x2),
                y2 = max(y1, y2),
                ocrConfidence = effectiveOcrConfidence,
                geminiConfidence = item.confidence,
                classification = classification,
                evidence = item.evidence,
                reasonType = item.reason_type,
            )
        }

        // Sort into natural reading order on the page so monotonic `elementIndex`
        // reflects top-to-bottom, right-to-left Arabic reading order within blocks/lines.
        val readingOrdered = drafted.sortedWith(
            compareBy<DraftReviewItem>(
                { it.blockIndex },
                { it.lineIndex },
                { it.segmentElementOrder },
                { it.y1 },
                { -it.x2 },
                { it.rawIndex },
            ),
        )

        return readingOrdered.mapIndexed { readingPos, draft ->
            val suggested = draft.classification.persistedSuggestedText
            val isClearCorrection = draft.classification.category == ComparisonCategory.DISAGREEMENT &&
                !suggested.isNullOrBlank() &&
                suggested.trim() != draft.classification.persistedOcrText.trim()
            val shouldAutoAccept = autoAcceptHighConfidence &&
                isClearCorrection &&
                draft.geminiConfidence != null &&
                draft.geminiConfidence > HIGH_CONFIDENCE_AUTO_ACCEPT_THRESHOLD

            ReviewItemEntity(
                id = 0L,
                documentId = documentId,
                pageId = geminiRecord.pageId,
                ocrResultId = ocrResult.pageId,
                geminiAnalysisId = geminiRecord.pageId,
                pageNumber = geminiRecord.pageNumber,
                x1 = draft.x1,
                y1 = draft.y1,
                x2 = draft.x2,
                y2 = draft.y2,
                ocrText = draft.classification.persistedOcrText,
                suggestedText = suggested,
                ocrConfidence = draft.ocrConfidence,
                geminiConfidence = draft.geminiConfidence,
                category = draft.classification.category.name,
                priority = draft.classification.priority,
                reviewStatus = if (shouldAutoAccept) ReviewStatus.APPROVED_GEMINI.name else ReviewStatus.PENDING.name,
                userText = if (shouldAutoAccept) suggested else null,
                evidence = draft.evidence,
                reasonType = draft.reasonType,
                createdAt = nowMillis,
                resolvedAt = if (shouldAutoAccept) nowMillis else null,
                blockIndex = draft.blockIndex,
                lineIndex = draft.lineIndex,
                elementIndex = readingPos,
                staleNoteAr = null,
            )
        }
    }

    private fun resolveOcrSegmentMatch(
        item: GeminiSuggestionItem,
        ocrResult: OcrResult,
    ): OcrSegmentMatch {
        val segments = ocrResult.segments
        if (segments.isEmpty()) {
            val approxLine = item.bbox.getOrElse(1) { 0f }.roundToInt()
            val approxRtlOrder = -item.bbox.getOrElse(2) { 0f }.roundToInt()
            return OcrSegmentMatch(
                resolvedConfidence = ocrResult.confidence,
                blockIndex = 0,
                lineIndex = approxLine,
                elementIndex = approxRtlOrder,
            )
        }

        val itemBox = BoundingBox(
            left = item.bbox.getOrElse(0) { 0f },
            top = item.bbox.getOrElse(1) { 0f },
            right = item.bbox.getOrElse(2) { 0f },
            bottom = item.bbox.getOrElse(3) { 0f },
        )

        val cleanItemOcr = item.ocr_text.trim()

        val elementCandidates = segments.filter { it.level == OcrElementLevel.ELEMENT }
        val pool = elementCandidates.ifEmpty { segments }

        var bestSeg: OcrSegment? = null
        var bestScore = -1f
        val overlappingElements = mutableListOf<OcrSegment>()

        for (seg in pool) {
            val segBox = seg.boundingBox
            val iou = if (segBox != null) computeIoU(itemBox, segBox) else 0f
            val overlapRatio = if (segBox != null) computeOverlapOverCandidate(itemBox, segBox) else 0f
            if (overlapRatio >= 0.40f && seg.level == OcrElementLevel.ELEMENT) {
                overlappingElements.add(seg)
            }
            val textBonus = when {
                seg.text.trim() == cleanItemOcr && cleanItemOcr.isNotEmpty() -> 0.50f
                cleanItemOcr.isNotEmpty() && seg.text.contains(cleanItemOcr) -> 0.20f
                else -> 0f
            }
            val score = max(iou, overlapRatio * 0.8f) + textBonus
            if (score > bestScore && (iou > 0.05f || overlapRatio > 0.20f || textBonus > 0f)) {
                bestScore = score
                bestSeg = seg
            }
        }

        if (bestSeg == null) {
            bestSeg = segments.firstOrNull {
                cleanItemOcr.isNotEmpty() && it.text.trim() == cleanItemOcr
            }
        }

        val matchedConfidence: Float? = when {
            overlappingElements.size > 1 -> {
                val confs = overlappingElements.mapNotNull { it.confidence }
                confs.minOrNull() ?: bestSeg?.confidence ?: ocrResult.confidence
            }
            bestSeg?.confidence != null -> bestSeg.confidence
            else -> ocrResult.confidence
        }

        return if (bestSeg != null) {
            OcrSegmentMatch(
                resolvedConfidence = matchedConfidence,
                blockIndex = bestSeg.blockIndex,
                lineIndex = bestSeg.lineIndex ?: 0,
                elementIndex = bestSeg.elementIndex ?: 0,
            )
        } else {
            val approxLine = itemBox.top.roundToInt()
            val approxRtlOrder = -itemBox.right.roundToInt()
            OcrSegmentMatch(
                resolvedConfidence = ocrResult.confidence,
                blockIndex = Int.MAX_VALUE / 2,
                lineIndex = approxLine,
                elementIndex = approxRtlOrder,
            )
        }
    }

    private fun findMatchingRecomputedIndex(
        resolved: ReviewItemEntity,
        candidates: List<ReviewItemEntity>,
        alreadyConsumed: Set<Int>,
    ): Int? {
        val resolvedBox = BoundingBox(
            left = resolved.x1.toFloat(),
            top = resolved.y1.toFloat(),
            right = resolved.x2.toFloat(),
            bottom = resolved.y2.toFloat(),
        )

        var bestIdx: Int? = null
        var bestScore = 0f

        for ((idx, cand) in candidates.withIndex()) {
            if (idx in alreadyConsumed) continue
            val candBox = BoundingBox(
                left = cand.x1.toFloat(),
                top = cand.y1.toFloat(),
                right = cand.x2.toFloat(),
                bottom = cand.y2.toFloat(),
            )
            val iou = computeIoU(resolvedBox, candBox)
            val sameOcrText = resolved.ocrText.trim() == cand.ocrText.trim()
            val sameElementOrder = resolved.elementIndex == cand.elementIndex

            val score = when {
                iou >= 0.30f && sameOcrText -> 2.0f + iou
                iou >= 0.30f -> 1.0f + iou
                sameOcrText && sameElementOrder -> 0.9f
                sameElementOrder -> 0.5f
                sameOcrText -> 0.4f
                else -> 0f
            }
            if (score > bestScore) {
                bestScore = score
                bestIdx = idx
            }
        }
        return bestIdx
    }

    private fun buildTextChangedStaleNote(
        previous: ReviewItemEntity,
        updated: ReviewItemEntity,
    ): String {
        val prevSuggested = previous.suggestedText ?: "غير محسوم"
        val nextSuggested = updated.suggestedText ?: "غير محسوم"
        return "تغيّر النص الأساسي بعد إعادة المعالجة (OCR: «${previous.ocrText}» ← «${updated.ocrText}»، الاقتراح: «$prevSuggested» ← «$nextSuggested»). يُرجى إعادة المراجعة."
    }

    private fun computeIoU(a: BoundingBox, b: BoundingBox): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f

        val areaA = ((a.right - a.left).coerceAtLeast(0f)) * ((a.bottom - a.top).coerceAtLeast(0f))
        val areaB = ((b.right - b.left).coerceAtLeast(0f)) * ((b.bottom - b.top).coerceAtLeast(0f))
        val union = areaA + areaB - interArea
        return if (union > 0f) interArea / union else 0f
    }

    private fun computeOverlapOverCandidate(container: BoundingBox, candidate: BoundingBox): Float {
        val interLeft = max(container.left, candidate.left)
        val interTop = max(container.top, candidate.top)
        val interRight = min(container.right, candidate.right)
        val interBottom = min(container.bottom, candidate.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        if (interArea <= 0f) return 0f

        val candidateArea = ((candidate.right - candidate.left).coerceAtLeast(0f)) *
            ((candidate.bottom - candidate.top).coerceAtLeast(0f))
        return if (candidateArea > 0f) interArea / candidateArea else 0f
    }

    private data class OcrSegmentMatch(
        val resolvedConfidence: Float?,
        val blockIndex: Int,
        val lineIndex: Int,
        val elementIndex: Int,
    )

    private data class DraftReviewItem(
        val rawIndex: Int,
        val blockIndex: Int,
        val lineIndex: Int,
        val segmentElementOrder: Int,
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val ocrConfidence: Float?,
        val geminiConfidence: Float?,
        val classification: ComparisonClassificationResult,
        val evidence: String,
        val reasonType: String,
    )

    companion object {
        const val HIGH_CONFIDENCE_AUTO_ACCEPT_THRESHOLD = 0.90f
    }
}

data class ComparisonPageSummary(
    val pageId: String,
    val totalComputed: Int,
    val insertedPending: Int,
    val preservedResolved: Int,
    val markedStale: Int,
)
