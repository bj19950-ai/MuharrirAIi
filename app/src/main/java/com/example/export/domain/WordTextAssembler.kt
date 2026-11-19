package com.example.export.domain

import com.example.export.model.DocumentPageModel
import com.example.export.model.ExportDocumentModel
import com.example.export.model.WordDocumentPlan
import com.example.export.model.WordExportMode
import com.example.export.model.WordPageSection
import com.example.export.model.WordTextSegment
import com.example.review.model.DiscrepancyCategory
import com.example.review.model.ReviewItemModel
import com.example.review.model.ReviewStatus

/**
 * Pure Kotlin assembler that orders pages by pageNumber and items by reading order,
 * selecting userText for approved/manually-edited items, ocrText when no review was needed,
 * and flagging unresolved/pending/stale items for highlighting in Word.
 */
object WordTextAssembler {

    fun buildPlan(
        document: ExportDocumentModel,
        pages: List<DocumentPageModel>,
        reviewItems: List<ReviewItemModel>,
        mode: WordExportMode,
        highlightUnresolved: Boolean = true,
    ): WordDocumentPlan {
        val sortedPages = pages.sortedBy { it.pageNumber }
        val itemsByPage = reviewItems
            .groupBy { it.pageNumber }
            .mapValues { (_, items) ->
                items.sortedWith(
                    compareBy<ReviewItemModel>(
                        { it.orderIndex },
                        { it.bbox?.top ?: Float.MAX_VALUE },
                        { -(it.bbox?.right ?: 0f) },
                        { it.id },
                    ),
                )
            }

        val sections = sortedPages.map { page ->
            val pageItems = itemsByPage[page.pageNumber].orEmpty()
            val cleanedPageOcr = OcrTextCleaner.cleanPageText(page.ocrText, page.pageNumber)
            val paragraphs: List<List<WordTextSegment>>
            val segments: List<WordTextSegment>

            when {
                pageItems.isEmpty() -> {
                    val rawSegments = buildSegmentsFromRawPageOcr(cleanedPageOcr)
                    segments = rawSegments
                    paragraphs = rawSegments.map { listOf(it) }
                }

                shouldSpliceIntoFullPageOcr(cleanedPageOcr, pageItems) -> {
                    paragraphs = spliceItemsIntoFullPageOcr(cleanedPageOcr, pageItems, highlightUnresolved)
                    segments = paragraphs.flatten()
                }

                else -> {
                    val resolved = pageItems.mapNotNull { item -> resolveItemSegment(item, highlightUnresolved) }
                    segments = resolved
                    paragraphs = resolved.map { listOf(it) }
                }
            }

            val rawNonWhitespaceCount = page.ocrText.count { !it.isWhitespace() }
            val cleanedNonWhitespaceCount = cleanedPageOcr.count { !it.isWhitespace() }
            val textLossWarning = if (
                rawNonWhitespaceCount > 0 &&
                cleanedNonWhitespaceCount < (rawNonWhitespaceCount * TEXT_LOSS_WARNING_RATIO)
            ) {
                TEXT_LOSS_WARNING_MESSAGE
            } else {
                null
            }

            WordPageSection(
                pageNumber = page.pageNumber,
                segments = segments,
                paragraphs = paragraphs,
                textLossWarning = textLossWarning,
            )
        }

        return WordDocumentPlan(
            documentTitle = document.title.ifBlank { "مستند مراجع" },
            mode = mode,
            pages = sections,
        )
    }

    private fun shouldSpliceIntoFullPageOcr(
        cleanedOcrText: String,
        pageItems: List<ReviewItemModel>,
    ): Boolean {
        val trimmedOcr = cleanedOcrText.trim()
        if (trimmedOcr.isEmpty()) return false
        val hasDirectMatch = pageItems.any { item ->
            candidateNeedles(item).any { needle -> trimmedOcr.contains(needle) }
        }
        return hasDirectMatch || trimmedOcr.length >= MIN_FULL_PAGE_OCR_LENGTH
    }

    private fun spliceItemsIntoFullPageOcr(
        cleanedOcrText: String,
        pageItems: List<ReviewItemModel>,
        highlightUnresolved: Boolean,
    ): List<List<WordTextSegment>> {
        val remainingItems = pageItems.toMutableList()
        val paragraphsText = cleanedOcrText
            .split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val resultParagraphs = mutableListOf<List<WordTextSegment>>()

        for (paragraphLine in paragraphsText) {
            val lineSegments = mutableListOf<WordTextSegment>()
            var cursor = 0

            while (cursor < paragraphLine.length && remainingItems.isNotEmpty()) {
                var earliestMatchPos = Int.MAX_VALUE
                var matchedItemIndex = -1
                var matchedNeedleLength = 0

                for (idx in remainingItems.indices) {
                    for (needle in candidateNeedles(remainingItems[idx])) {
                        val pos = paragraphLine.indexOf(needle, startIndex = cursor)
                        if (pos >= 0 && pos < earliestMatchPos) {
                            earliestMatchPos = pos
                            matchedItemIndex = idx
                            matchedNeedleLength = needle.length
                        }
                    }
                }

                if (matchedItemIndex < 0) break

                val item = remainingItems.removeAt(matchedItemIndex)

                if (earliestMatchPos > cursor) {
                    val prefix = OcrTextCleaner.cleanArabicText(
                        paragraphLine.substring(cursor, earliestMatchPos),
                    )
                    val leadingSpace = if (paragraphLine[cursor].isWhitespace() && !prefix.startsWith(" ")) " " else ""
                    val trailingSpace = if (
                        earliestMatchPos > cursor &&
                        paragraphLine[earliestMatchPos - 1].isWhitespace() &&
                        prefix.isNotEmpty() &&
                        !prefix.endsWith(" ")
                    ) {
                        " "
                    } else {
                        ""
                    }
                    val formattedPrefix = if (prefix.isEmpty()) {
                        if (paragraphLine.substring(cursor, earliestMatchPos).isNotEmpty()) " " else ""
                    } else {
                        "$leadingSpace$prefix$trailingSpace"
                    }
                    if (formattedPrefix.isNotEmpty()) {
                        lineSegments.add(
                            WordTextSegment(
                                text = formattedPrefix,
                                isUnresolvedHighlight = false,
                            ),
                        )
                    }
                }

                resolveItemSegment(item, highlightUnresolved)?.let { resolved ->
                    lineSegments.add(resolved)
                }

                cursor = earliestMatchPos + matchedNeedleLength
            }

            if (cursor < paragraphLine.length) {
                val rawSuffix = paragraphLine.substring(cursor)
                val cleanedSuffix = OcrTextCleaner.cleanArabicText(rawSuffix)
                val leadingSpace = if (rawSuffix.firstOrNull()?.isWhitespace() == true && cleanedSuffix.isNotEmpty()) " " else ""
                val formattedSuffix = "$leadingSpace$cleanedSuffix"
                if (formattedSuffix.isNotEmpty()) {
                    lineSegments.add(
                        WordTextSegment(
                            text = formattedSuffix,
                            isUnresolvedHighlight = false,
                        ),
                    )
                }
            }

            if (lineSegments.isNotEmpty()) {
                resultParagraphs.add(lineSegments)
            }
        }

        // Preserve any item whose ocrText was not matched verbatim in cleaned OCR paragraphs
        remainingItems.forEach { leftover ->
            resolveItemSegment(leftover, highlightUnresolved)?.let { seg ->
                resultParagraphs.add(listOf(seg))
            }
        }

        return resultParagraphs
    }

    private fun candidateNeedles(item: ReviewItemModel): List<String> {
        val rawNeedle = item.ocrText.trim()
        val singleLineNeedle = rawNeedle.replace(WHITESPACE_REGEX, " ").trim()
        val cleanedNeedle = OcrTextCleaner.cleanArabicText(singleLineNeedle)
        return buildList {
            if (rawNeedle.isNotEmpty()) add(rawNeedle)
            if (singleLineNeedle.isNotEmpty() && singleLineNeedle !in this) add(singleLineNeedle)
            if (cleanedNeedle.isNotEmpty() && cleanedNeedle !in this) add(cleanedNeedle)
        }
    }

    private fun buildSegmentsFromRawPageOcr(cleanedOcrText: String): List<WordTextSegment> {
        if (cleanedOcrText.isBlank()) return emptyList()
        return cleanedOcrText
            .split(Regex("\\r?\\n"))
            .map { OcrTextCleaner.cleanArabicText(it) }
            .filter { it.isNotEmpty() }
            .map { paragraph ->
                WordTextSegment(
                    text = paragraph,
                    isUnresolvedHighlight = false,
                )
            }
    }

    private fun resolveItemSegment(
        item: ReviewItemModel,
        highlightUnresolved: Boolean,
    ): WordTextSegment? {
        if (item.reviewStatus == ReviewStatus.IGNORED) {
            return null
        }

        return when {
            item.isStale ||
                item.reviewStatus == ReviewStatus.UNRESOLVED ||
                item.reviewStatus == ReviewStatus.PENDING ||
                item.reviewStatus == ReviewStatus.STALE -> {
                val rawFallback = item.userText?.takeIf { it.isNotBlank() }
                    ?: item.ocrText.ifBlank { item.suggestedText.orEmpty() }
                val unresolvedText = OcrTextCleaner.cleanArabicText(rawFallback)
                    .ifBlank { rawFallback.trim() }
                WordTextSegment(
                    text = unresolvedText,
                    isUnresolvedHighlight = highlightUnresolved,
                )
            }

            item.reviewStatus == ReviewStatus.APPROVED_GEMINI ||
                item.reviewStatus == ReviewStatus.MANUALLY_EDITED -> {
                val rawApproved = item.userText?.takeIf { it.isNotBlank() }
                    ?: item.suggestedText?.takeIf { it.isNotBlank() }
                    ?: item.ocrText
                val cleaned = OcrTextCleaner.cleanArabicText(rawApproved)
                    .ifBlank { rawApproved.trim() }
                WordTextSegment(
                    text = cleaned,
                    isUnresolvedHighlight = false,
                )
            }

            item.category == DiscrepancyCategory.A -> {
                val rawAgreement = item.userText?.takeIf { it.isNotBlank() }
                    ?: item.ocrText.ifBlank { item.suggestedText.orEmpty() }
                val finalText = OcrTextCleaner.cleanArabicText(rawAgreement)
                    .ifBlank { rawAgreement.trim() }
                WordTextSegment(
                    text = finalText,
                    isUnresolvedHighlight = false,
                )
            }

            else -> {
                val rawOther = item.userText?.takeIf { it.isNotBlank() }
                    ?: item.ocrText.ifBlank { item.suggestedText.orEmpty() }
                val fallback = OcrTextCleaner.cleanArabicText(rawOther)
                    .ifBlank { rawOther.trim() }
                WordTextSegment(
                    text = fallback,
                    isUnresolvedHighlight = false,
                )
            }
        }
    }

    private const val TEXT_LOSS_WARNING_RATIO = 0.80
    private const val MIN_FULL_PAGE_OCR_LENGTH = 20
    private val WHITESPACE_REGEX = Regex("\\s+")
    const val TEXT_LOSS_WARNING_MESSAGE =
        "تحذير: تم حذف جزء كبير من النص في هذه الصفحة أثناء التنظيف، يرجى مراجعتها"
}
