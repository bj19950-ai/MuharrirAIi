package com.example.review.ui

import android.graphics.Bitmap
import com.example.review.model.DiscrepancyCategory
import com.example.review.model.ReviewItemModel
import com.example.review.model.ReviewStatus
import com.example.review.model.ReviewStatusCounts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

enum class ReviewFilter(val labelAr: String) {
    ALL("الكل"),
    DISCREPANCY_ONLY("اختلاف فقط"),
    UNRESOLVED_ONLY("غير محسوم فقط"),
    PENDING_ONLY("غير مراجع فقط"),
}

enum class ReopenReason {
    STALE_REANALYSIS,
    MANUAL_REOPEN,
}

data class ReopenConfirmationRequest(
    val itemId: Long,
    val reason: ReopenReason,
)

data class ReviewUiState(
    val documentId: String = "",
    val documentTitle: String = "",
    val reviewApprovedAt: Long? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val allItems: List<ReviewItemModel> = emptyList(),
    val filteredItems: List<ReviewItemModel> = emptyList(),
    val currentItem: ReviewItemModel? = null,
    val currentIndex: Int = 0, // 0-based inside filteredItems
    val selectedFilter: ReviewFilter = ReviewFilter.ALL,
    val includeAgreementInAll: Boolean = false,
    val stats: ReviewStatusCounts = ReviewStatusCounts(),
    val pageBitmap: Bitmap? = null,
    val isBitmapLoading: Boolean = false,
    val bitmapLoadError: Boolean = false,
    val enlargedCropBitmap: Bitmap? = null,
    val editedText: String = "",
    val isManualEditEnabled: Boolean = false,
    val savedConfirmationVisible: Boolean = false,
    val confirmationMessage: String? = null,
    val errorMessage: String? = null,
    val pageJumpInput: String = "",
    val pageJumpError: String? = null,
    val reopenDialog: ReopenConfirmationRequest? = null,
    val showPartialApprovalDialog: Boolean = false,
    val approvalSucceeded: Boolean = false,
) {
    val totalFilteredCount: Int get() = filteredItems.size
    val displayPosition: Int get() = if (filteredItems.isEmpty() || currentItem == null) 0 else currentIndex + 1
    val canGoPrevious: Boolean get() = currentIndex > 0
    val canGoNext: Boolean get() = currentIndex < filteredItems.lastIndex
}

/**
 * Preserves the user's current position per document across back navigation and re-entry.
 */
object ReviewPositionStore {
    private val lastItemIdByDocument = ConcurrentHashMap<String, Long>()

    fun getLastItemId(documentId: String): Long? = lastItemIdByDocument[documentId]

    fun setLastItemId(documentId: String, itemId: Long) {
        lastItemIdByDocument[documentId] = itemId
    }
}

object ReviewArabicFormatter {
    private val arabicLocale: Locale = Locale.forLanguageTag("ar")

    fun categoryLabel(category: DiscrepancyCategory): String = when (category) {
        DiscrepancyCategory.A -> "متطابق"
        DiscrepancyCategory.B -> "اختلاف"
        DiscrepancyCategory.C -> "قراءة غير مؤكدة"
        DiscrepancyCategory.D -> "غير محسوم (الصورة غير واضحة)"
        DiscrepancyCategory.E -> "غموض بنيوي"
    }

    fun statusLabel(status: ReviewStatus, isStale: Boolean): String {
        if (isStale || status == ReviewStatus.STALE) {
            return "يتطلب إعادة مراجعة"
        }
        return when (status) {
            ReviewStatus.PENDING -> "قيد الانتظار"
            ReviewStatus.APPROVED_GEMINI -> "تم قبول اقتراح Gemini"
            ReviewStatus.MANUALLY_EDITED -> "تم التعديل يدويًا"
            ReviewStatus.UNRESOLVED -> "غير محسوم"
            ReviewStatus.STALE -> "يتطلب إعادة مراجعة"
        }
    }

    fun formatConfidence(confidence: Float?): String {
        if (confidence == null) return "غير متوفرة"
        val percentage = if (confidence <= 1f) {
            (confidence * 100f).roundToInt()
        } else {
            confidence.roundToInt()
        }.coerceIn(0, 100)
        return "$percentage%"
    }

    fun formatResolvedTimestamp(timestampMillis: Long): String {
        val formatter = SimpleDateFormat("HH:mm - yyyy/MM/dd", arabicLocale)
        return "حُسم في ${formatter.format(Date(timestampMillis))}"
    }

    fun initialApprovedText(item: ReviewItemModel): String = when (item.reviewStatus) {
        ReviewStatus.APPROVED_GEMINI -> item.suggestedText.orEmpty()
        ReviewStatus.MANUALLY_EDITED -> item.userText.orEmpty()
        else -> item.suggestedText ?: item.ocrText
    }
}
