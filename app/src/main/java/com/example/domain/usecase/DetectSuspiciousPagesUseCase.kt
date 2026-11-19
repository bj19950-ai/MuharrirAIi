package com.example.domain.usecase

import com.example.domain.model.OcrPageStatus
import com.example.domain.model.PageRecord

data class SuspiciousPagesAnalysis(
    val averageCharCount: Double,
    val thresholdCharCount: Double,
    val suspiciousPages: List<PageRecord>,
)

/**
 * Automatically detects OCR-completed pages whose extracted text length is abnormally short
 * (below 20% of the document's average page character count) while excluding completely blank /
 * visually empty pages.
 */
class DetectSuspiciousPagesUseCase(
    private val minRatioOfAverage: Double = SUSPICIOUS_LENGTH_RATIO_OF_AVERAGE,
) {

    suspend fun detect(
        pages: List<PageRecord>,
        excludedPageIds: Set<String> = emptySet(),
        isVisuallyEmpty: suspend (PageRecord) -> Boolean = { false },
    ): List<PageRecord> = analyze(
        pages = pages,
        excludedPageIds = excludedPageIds,
        isVisuallyEmpty = isVisuallyEmpty,
    ).suspiciousPages

    suspend fun analyze(
        pages: List<PageRecord>,
        excludedPageIds: Set<String> = emptySet(),
        isVisuallyEmpty: suspend (PageRecord) -> Boolean = { false },
    ): SuspiciousPagesAnalysis {
        val ocrProcessedPages = pages.filter { page ->
            page.ocrStatus == OcrPageStatus.DONE || page.ocrResult != null
        }
        if (ocrProcessedPages.size < 2) {
            return SuspiciousPagesAnalysis(
                averageCharCount = 0.0,
                thresholdCharCount = 0.0,
                suspiciousPages = emptyList(),
            )
        }

        val averageChars = ocrProcessedPages
            .map { pageCharCount(it) }
            .average()

        if (averageChars <= 0.0) {
            return SuspiciousPagesAnalysis(
                averageCharCount = 0.0,
                thresholdCharCount = 0.0,
                suspiciousPages = emptyList(),
            )
        }

        val threshold = averageChars * minRatioOfAverage
        val candidates = ocrProcessedPages.filter { page ->
            page.pageId !in excludedPageIds && pageCharCount(page) < threshold
        }

        val nonBlankSuspicious = buildList {
            for (candidate in candidates) {
                if (!isVisuallyEmpty(candidate)) {
                    add(candidate)
                }
            }
        }.sortedBy { it.originalPageNumber }

        return SuspiciousPagesAnalysis(
            averageCharCount = averageChars,
            thresholdCharCount = threshold,
            suspiciousPages = nonBlankSuspicious,
        )
    }

    companion object {
        const val SUSPICIOUS_LENGTH_RATIO_OF_AVERAGE = 0.20

        fun pageCharCount(page: PageRecord): Int =
            page.ocrResult?.recognizedText?.trim()?.length ?: 0
    }
}
