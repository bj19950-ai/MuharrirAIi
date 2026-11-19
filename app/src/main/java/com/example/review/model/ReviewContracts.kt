package com.example.review.model

import android.graphics.Bitmap
import com.example.domain.model.ComparisonCategory
import kotlinx.coroutines.flow.Flow

typealias ReviewStatus = com.example.domain.model.ReviewStatus
typealias BoundingBox = com.example.domain.model.BoundingBox

enum class DiscrepancyCategory {
    A, // AGREEMENT -> متطابق
    B, // DISCREPANCY -> اختلاف
    C, // UNCERTAIN -> قراءة غير مؤكدة
    D, // UNRESOLVED_IMAGE -> غير محسوم (الصورة غير واضحة)
    E, // STRUCTURAL_AMBIGUITY -> غموض بنيوي
}

fun ComparisonCategory.toDiscrepancyCategory(): DiscrepancyCategory = when (this) {
    ComparisonCategory.AGREEMENT -> DiscrepancyCategory.A
    ComparisonCategory.DISAGREEMENT -> DiscrepancyCategory.B
    ComparisonCategory.UNCERTAIN_RECOGNITION -> DiscrepancyCategory.C
    ComparisonCategory.UNRESOLVED -> DiscrepancyCategory.D
    ComparisonCategory.STRUCTURAL_AMBIGUITY -> DiscrepancyCategory.E
}

data class ReviewItemModel(
    val id: Long,
    val documentId: String,
    val pageNumber: Int,
    val category: DiscrepancyCategory,
    val ocrText: String,
    val suggestedText: String?,
    val ocrConfidence: Float?,
    val geminiConfidence: Float?,
    val evidence: String?,
    val bbox: BoundingBox?,
    val reviewStatus: ReviewStatus,
    val userText: String?,
    val resolvedAt: Long?,
    val isStale: Boolean = reviewStatus == ReviewStatus.STALE,
    val staleNoteAr: String? = null,
    val orderIndex: Int = 0,
)

data class ReviewStatusCounts(
    val total: Int = 0,
    val approvedGemini: Int = 0,
    val manuallyEdited: Int = 0,
    val unresolved: Int = 0,
    val ignored: Int = 0,
    val remaining: Int = 0,
)

data class DocumentReviewInfo(
    val documentId: String,
    val title: String,
    val reviewApprovedAt: Long?,
)

/**
 * Adapter interface over Module 05 ReviewItemDao.
 */
interface ReviewQueueGateway {
    fun getReviewQueue(documentId: String): Flow<List<ReviewItemModel>>
    fun countByStatus(documentId: String): Flow<ReviewStatusCounts>
    suspend fun getNextPending(documentId: String, afterItemId: Long): ReviewItemModel?
    suspend fun saveDecision(
        itemId: Long,
        newStatus: ReviewStatus,
        userText: String?,
        resolvedAt: Long,
    )
    suspend fun reopenItemAsPending(itemId: Long)
}

/**
 * Adapter interface over Module 01 DocumentDao.
 */
interface DocumentReviewGateway {
    fun observeDocument(documentId: String): Flow<DocumentReviewInfo?>
    suspend fun setReviewApprovedAt(documentId: String, approvedAtMillis: Long)
}

/**
 * Adapter interface over Module 02/03 page bitmap storage (same bitmap scaling used by OCR).
 */
interface PageBitmapSource {
    suspend fun loadPageBitmap(documentId: String, pageNumber: Int): Bitmap?
}
